package com.devomind.gallerysearch

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import com.devomind.gallerysearch.databinding.ItemSafeViewerPageBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pages of the Safe's full-screen viewer: one decrypted item per page, swiped via ViewPager2.
 * Images are decrypted lazily per page through [decryptImage] (off the main thread) and memoized in
 * [cache]; a recycled holder drops its bitmap reference — the cache owns the memory. Videos are
 * decrypted to a temp file through [decryptFile], played with ExoPlayer, and that copy is deleted
 * when the page is recycled. Tapping a page invokes [onTap] so the activity can toggle its chrome.
 */
class SafeViewerPagerAdapter(
    private val items: List<SafeManager.VaultItem>,
    private val cache: LruCache<String, Bitmap>,
    private val scope: CoroutineScope,
    private val decryptImage: (SafeManager.VaultItem) -> Bitmap?,
    private val decryptFile: (SafeManager.VaultItem) -> File?,
    private val onTap: () -> Unit
) : RecyclerView.Adapter<SafeViewerPagerAdapter.PageVH>() {

    // Every live video player, keyed by position, so the current page can be told to play and the
    // rest paused without touching holders RecyclerView never handed back.
    private val activePlayers = SparseArray<ExoPlayer>()
    private var primaryPosition = 0

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH =
        PageVH(ItemSafeViewerPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: PageVH, position: Int) {
        holder.bind(items[position], position)
    }

    override fun onViewRecycled(holder: PageVH) {
        holder.cleanup()
    }

    /** Plays the video at [position] and pauses every other one. */
    fun setPrimaryPosition(position: Int) {
        primaryPosition = position
        for (i in 0 until activePlayers.size()) {
            val player = activePlayers.valueAt(i)
            if (activePlayers.keyAt(i) == position) player.play() else player.pause()
        }
    }

    /** Releases every tracked player. Call from the host activity when the viewer closes. */
    fun releaseAll() {
        for (i in 0 until activePlayers.size()) activePlayers.valueAt(i).release()
        activePlayers.clear()
    }

    inner class PageVH(val binding: ItemSafeViewerPageBinding) : RecyclerView.ViewHolder(binding.root) {
        private var player: ExoPlayer? = null
        private var boundEntry: String? = null
        private var boundPosition = -1
        private var videoTempDir: File? = null

        fun bind(item: SafeManager.VaultItem, position: Int) {
            if (boundEntry == item.entryName) return
            cleanup()
            boundEntry = item.entryName
            boundPosition = position
            binding.pagePhoto.setOnClickListener { onTap() }
            binding.playerView.setOnClickListener { onTap() }
            if (MediaFormats.isVideoFileName(item.entryName)) bindVideo(item, position) else bindImage(item)
        }

        private fun bindImage(item: SafeManager.VaultItem) {
            binding.playerView.visibility = View.GONE
            binding.playPauseButton.visibility = View.GONE
            binding.pagePhoto.visibility = View.VISIBLE
            binding.pagePhoto.tag = item.entryName
            binding.pagePhoto.setImageDrawable(null)
            val cached = cache.get(item.entryName)
            if (cached != null) {
                binding.pageSpinner.visibility = View.GONE
                binding.pagePhoto.setImageBitmap(cached)
                return
            }
            binding.pageSpinner.visibility = View.VISIBLE
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { decryptImage(item) }
                if (bmp != null) cache.put(item.entryName, bmp)
                if (binding.pagePhoto.tag == item.entryName) {
                    binding.pageSpinner.visibility = View.GONE
                    if (bmp != null) binding.pagePhoto.setImageBitmap(bmp)
                }
            }
        }

        private fun bindVideo(item: SafeManager.VaultItem, position: Int) {
            binding.pagePhoto.visibility = View.GONE
            binding.pagePhoto.setImageDrawable(null)
            binding.playerView.visibility = View.VISIBLE
            binding.playPauseButton.visibility = View.VISIBLE
            binding.pageSpinner.visibility = View.VISIBLE
            scope.launch {
                val file = withContext(Dispatchers.IO) { decryptFile(item) }
                if (file == null) {
                    binding.pageSpinner.visibility = View.GONE
                    return@launch
                }
                // The page was recycled while decrypting — drop the plaintext copy immediately.
                if (boundEntry != item.entryName || player != null) {
                    file.parentFile?.deleteRecursively()
                    return@launch
                }
                videoTempDir = file.parentFile
                val newPlayer = ExoPlayer.Builder(binding.root.context).build()
                newPlayer.setMediaItem(Media3Item.fromUri(Uri.fromFile(file)))
                newPlayer.repeatMode = Player.REPEAT_MODE_OFF
                newPlayer.playWhenReady = position == primaryPosition
                newPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (player !== newPlayer) return
                        when (playbackState) {
                            Player.STATE_READY -> binding.pageSpinner.visibility = View.GONE
                            Player.STATE_BUFFERING -> binding.pageSpinner.visibility = View.VISIBLE
                            Player.STATE_ENDED -> binding.playPauseButton.setImageResource(
                                R.drawable.ic_fluent_replay_24_regular
                            )
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (player !== newPlayer) return
                        binding.playPauseButton.setImageResource(
                            if (isPlaying) R.drawable.ic_fluent_pause_24_regular
                            else R.drawable.ic_fluent_play_24_regular
                        )
                    }

                    override fun onRenderedFirstFrame() {
                        if (player !== newPlayer) return
                        binding.pageSpinner.visibility = View.GONE
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        if (player !== newPlayer) return
                        binding.pageSpinner.visibility = View.GONE
                    }
                })
                player = newPlayer
                activePlayers.put(position, newPlayer)
                binding.playPauseButton.setOnClickListener { togglePlayback() }
                binding.playerView.player = newPlayer
                newPlayer.prepare()
            }
        }

        private fun togglePlayback() {
            val p = player ?: return
            when {
                p.playbackState == Player.STATE_ENDED -> {
                    p.seekTo(0)
                    p.play()
                }
                p.isPlaying -> p.pause()
                else -> p.play()
            }
        }

        fun cleanup() {
            player?.release()
            if (boundPosition >= 0) activePlayers.remove(boundPosition)
            player = null
            boundEntry = null
            boundPosition = -1
            videoTempDir?.deleteRecursively()
            videoTempDir = null
            binding.playerView.player = null
            binding.playerView.visibility = View.GONE
            binding.playPauseButton.visibility = View.GONE
            binding.pageSpinner.visibility = View.GONE
            binding.pagePhoto.setImageDrawable(null)
            binding.pagePhoto.visibility = View.VISIBLE
        }
    }
}
