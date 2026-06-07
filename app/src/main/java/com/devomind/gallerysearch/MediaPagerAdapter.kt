package com.devomind.gallerysearch

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.devomind.gallerysearch.databinding.ItemViewerPageBinding

class MediaPagerAdapter(
    private val items: List<GalleryRepository.MediaItem>,
    private val initialPosition: Int,
    private val initialTransitionName: String?,
    private val onInitialImageLoaded: () -> Unit,
    private val onMediaTap: () -> Unit,
    private val onVideoCompleted: () -> Unit = {}
) : RecyclerView.Adapter<MediaPagerAdapter.PageViewHolder>() {

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemViewerPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val item = items[position]
        val isInitial = position == initialPosition
        val transitionName = if (isInitial) initialTransitionName else "media_${item.uri}"
        holder.bind(item, transitionName, isInitial && initialTransitionName != null)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        holder.cleanup()
    }

    inner class PageViewHolder(val binding: ItemViewerPageBinding) : RecyclerView.ViewHolder(binding.root) {
        var player: ExoPlayer? = null

        fun bind(
            item: GalleryRepository.MediaItem,
            transitionName: String?,
            isInitialSharedElement: Boolean
        ) {
            val isVideo = item.mediaType == GalleryRepository.MediaType.Video

            binding.photoView.setOnClickListener { onMediaTap() }
            binding.playerView.setOnClickListener { onMediaTap() }

            if (isVideo) {
                binding.photoView.visibility = View.GONE
                binding.playerView.visibility = View.VISIBLE

                player = ExoPlayer.Builder(binding.root.context).build().apply {
                    setMediaItem(Media3Item.fromUri(item.uri))
                    repeatMode = Player.REPEAT_MODE_OFF
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) {
                                onVideoCompleted()
                            }
                        }
                        
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            android.util.Log.w("MediaPagerAdapter", "Video playback failed for ${item.uri}", error)
                            android.widget.Toast.makeText(binding.root.context, "This video cannot be played.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    })
                    prepare()
                }

                binding.playerView.player = player

                if (transitionName != null) {
                    ViewCompat.setTransitionName(binding.playerView, transitionName)
                }
                
                if (isInitialSharedElement) {
                    binding.playerView.post {
                        onInitialImageLoaded()
                    }
                }
            } else {
                binding.playerView.visibility = View.GONE
                binding.photoView.visibility = View.VISIBLE

                if (transitionName != null) {
                    ViewCompat.setTransitionName(binding.photoView, transitionName)
                }

                val request = Glide.with(binding.photoView)
                    .load(item.uri)
                    .format(DecodeFormat.PREFER_ARGB_8888)
                    .error(R.drawable.ic_fluent_image_24_regular)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .override(binding.photoView.resources.displayMetrics.widthPixels,
                        binding.photoView.resources.displayMetrics.heightPixels)
                    .fitCenter()

                request.into(binding.photoView)

                if (isInitialSharedElement) {
                    binding.photoView.viewTreeObserver.addOnPreDrawListener(
                        object : android.view.ViewTreeObserver.OnPreDrawListener {
                            override fun onPreDraw(): Boolean {
                                binding.photoView.viewTreeObserver.removeOnPreDrawListener(this)
                                onInitialImageLoaded()
                                return true
                            }
                        })
                }
            }
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
            stopPlayback()
            player?.release()
            player = null
            binding.playerView.player = null
            val ctx = binding.photoView.context.applicationContext
            Glide.with(ctx).clear(binding.photoView)
        }
    }
}

