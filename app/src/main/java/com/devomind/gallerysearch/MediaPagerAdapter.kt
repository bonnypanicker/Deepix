package com.devomind.gallerysearch

import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.view.ViewCompat
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.devomind.gallerysearch.databinding.ItemViewerPageBinding
import java.util.Locale

class MediaPagerAdapter(
    private val items: List<GalleryRepository.MediaItem>,
    private val initialPosition: Int,
    private val initialTransitionName: String?,
    private val onInitialImageLoaded: () -> Unit,
    private val onMediaTap: () -> Unit,
    private val onMediaLongClick: () -> Unit = {},
    private val onVideoCompleted: () -> Unit = {},
    private val onScrubbingChanged: (Boolean) -> Unit = {}
) : RecyclerView.Adapter<MediaPagerAdapter.PageViewHolder>() {

    // All live players, keyed by adapter position. Lets onDestroy release off-screen holders
    // that RecyclerView is caching but never handed back via onViewRecycled.
    private val activePlayers = SparseArray<ExoPlayer>()

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemViewerPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val item = items[position]
        val isInitial = position == initialPosition
        val transitionName = if (isInitial) initialTransitionName else "media_${item.uri}"
        holder.bind(item, position, transitionName, isInitial && initialTransitionName != null)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        holder.cleanup()
    }

    /** Releases every tracked player. Call from the host activity's onDestroy. */
    fun releaseAll() {
        for (i in 0 until activePlayers.size()) {
            activePlayers.valueAt(i).release()
        }
        activePlayers.clear()
    }

    inner class PageViewHolder(val binding: ItemViewerPageBinding) : RecyclerView.ViewHolder(binding.root) {
        var player: ExoPlayer? = null
        private var boundUri: Uri? = null
        private var boundPosition: Int = -1
        private var userScrubbing = false

        private val progressHandler = Handler(Looper.getMainLooper())
        private val progressRunnable = object : Runnable {
            override fun run() {
                val p = player ?: return
                val duration = p.duration
                if (duration > 0L && !userScrubbing) {
                    val pos = p.currentPosition.coerceIn(0L, duration)
                    binding.videoSeekBar.progress = ((pos * 1000L) / duration).toInt()
                    binding.videoElapsed.text = formatTime(pos)
                }
                progressHandler.postDelayed(this, 250L)
            }
        }

        fun bind(
            item: GalleryRepository.MediaItem,
            position: Int,
            transitionName: String?,
            isInitialSharedElement: Boolean
        ) {
            val isVideo = item.mediaType == GalleryRepository.MediaType.Video

            // Guard against RecyclerView/ViewPager2 double-binding the same video into this holder
            // during prefetch — recreating the player would orphan the previous one.
            if (isVideo && player != null && boundUri == item.uri) return

            cleanup()
            boundUri = item.uri
            boundPosition = position

            binding.photoView.setOnClickListener { onMediaTap() }
            binding.photoView.setOnLongClickListener {
                onMediaLongClick()
                true
            }
            binding.playerView.setOnClickListener { onMediaTap() }
            binding.playerView.setOnLongClickListener {
                onMediaLongClick()
                true
            }

            if (isVideo) {
                bindVideo(item, position, transitionName, isInitialSharedElement)
            } else {
                bindImage(item, transitionName, isInitialSharedElement)
            }
        }

        private fun bindImage(
            item: GalleryRepository.MediaItem,
            transitionName: String?,
            isInitialSharedElement: Boolean
        ) {
            binding.playerView.visibility = View.GONE
            binding.videoControls.visibility = View.GONE
            binding.photoView.visibility = View.VISIBLE
            binding.loadingSpinner.visibility = View.VISIBLE

            if (transitionName != null) {
                ViewCompat.setTransitionName(binding.photoView, transitionName)
            }

            Glide.with(binding.photoView)
                .load(item.uri)
                .format(DecodeFormat.PREFER_ARGB_8888)
                .error(R.drawable.ic_fluent_image_24_regular)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .override(
                    binding.photoView.resources.displayMetrics.widthPixels,
                    binding.photoView.resources.displayMetrics.heightPixels
                )
                .fitCenter()
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        binding.loadingSpinner.visibility = View.GONE
                        if (isInitialSharedElement) onInitialImageLoaded()
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        binding.loadingSpinner.visibility = View.GONE
                        if (isInitialSharedElement) onInitialImageLoaded()
                        return false
                    }
                })
                .into(binding.photoView)
        }

        private fun bindVideo(
            item: GalleryRepository.MediaItem,
            position: Int,
            transitionName: String?,
            isInitialSharedElement: Boolean
        ) {
            binding.photoView.visibility = View.GONE
            binding.playerView.visibility = View.VISIBLE
            binding.loadingSpinner.visibility = View.VISIBLE
            binding.videoControls.visibility = View.GONE
            binding.videoSeekBar.progress = 0
            binding.videoElapsed.text = "0:00"
            binding.videoTotal.text = "0:00"
            setupSeekBar()

            val newPlayer = ExoPlayer.Builder(binding.root.context).build().apply {
                setMediaItem(Media3Item.fromUri(item.uri))
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                binding.loadingSpinner.visibility = View.GONE
                                updateDurationLabel()
                            }
                            Player.STATE_ENDED -> {
                                stopProgressUpdates()
                                onVideoCompleted()
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) startProgressUpdates() else stopProgressUpdates()
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        binding.loadingSpinner.visibility = View.GONE
                        android.util.Log.w("MediaPagerAdapter", "Video playback failed for ${item.uri}", error)
                        android.widget.Toast.makeText(
                            binding.root.context,
                            "This video cannot be played.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                })
                prepare()
            }

            player = newPlayer
            activePlayers.put(position, newPlayer)
            binding.playerView.player = newPlayer

            if (transitionName != null) {
                ViewCompat.setTransitionName(binding.playerView, transitionName)
            }
            if (isInitialSharedElement) {
                binding.playerView.post { onInitialImageLoaded() }
            }
        }

        private fun setupSeekBar() {
            binding.videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val duration = player?.duration ?: 0L
                        if (duration > 0L) {
                            binding.videoElapsed.text = formatTime(progress * duration / 1000L)
                        }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    userScrubbing = true
                    onScrubbingChanged(true)
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val duration = player?.duration ?: 0L
                    if (duration > 0L) {
                        player?.seekTo((seekBar?.progress ?: 0) * duration / 1000L)
                    }
                    userScrubbing = false
                    onScrubbingChanged(false)
                }
            })
        }

        private fun updateDurationLabel() {
            val duration = player?.duration ?: 0L
            if (duration > 0L) {
                binding.videoTotal.text = formatTime(duration)
            }
        }

        private fun startProgressUpdates() {
            progressHandler.removeCallbacks(progressRunnable)
            progressHandler.post(progressRunnable)
        }

        private fun stopProgressUpdates() {
            progressHandler.removeCallbacks(progressRunnable)
        }

        /** Shows the scrubber only for video pages. No-op for images. */
        fun setScrubberVisible(visible: Boolean) {
            binding.videoControls.visibility = if (visible && player != null) View.VISIBLE else View.GONE
        }

        fun startPlayback() {
            if (binding.playerView.visibility == View.VISIBLE) {
                player?.play()
            }
        }

        fun pausePlayback() {
            if (binding.playerView.visibility == View.VISIBLE) {
                player?.pause()
            }
        }

        fun stopPlayback() {
            if (binding.playerView.visibility == View.VISIBLE) {
                player?.stop()
            }
        }

        fun isPlaying(): Boolean {
            return player?.isPlaying == true
        }

        fun isZoomed(): Boolean {
            return binding.photoView.visibility == View.VISIBLE && binding.photoView.scale > 1.0f
        }

        fun cleanup() {
            stopProgressUpdates()
            userScrubbing = false
            stopPlayback()
            player?.release()
            if (boundPosition >= 0) activePlayers.remove(boundPosition)
            player = null
            boundUri = null
            binding.playerView.player = null
            binding.videoControls.visibility = View.GONE
            val ctx = binding.photoView.context.applicationContext
            Glide.with(ctx).clear(binding.photoView)
        }

        private fun formatTime(ms: Long): String {
            if (ms <= 0L) return "0:00"
            val totalSeconds = ms / 1000L
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }
}
