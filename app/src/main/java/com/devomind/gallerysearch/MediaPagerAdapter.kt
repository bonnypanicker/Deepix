package com.devomind.gallerysearch

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
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
        private var videoPrepared = false

        fun bind(
            item: GalleryRepository.MediaItem,
            transitionName: String?,
            isInitialSharedElement: Boolean
        ) {
            val isVideo = item.mediaType == GalleryRepository.MediaType.Video

            binding.photoView.setOnClickListener { onMediaTap() }
            binding.videoView.setOnClickListener { onMediaTap() }

            if (isVideo) {
                binding.photoView.visibility = View.GONE
                binding.videoView.visibility = View.VISIBLE
                binding.videoView.setVideoURI(item.uri)

                val controller = MediaController(binding.videoView.context)
                controller.setAnchorView(binding.videoView)
                binding.videoView.setMediaController(controller)

                binding.videoView.setOnPreparedListener { player ->
                    videoPrepared = true
                    player.isLooping = false
                    binding.videoView.start()
                }
                binding.videoView.setOnCompletionListener {
                    onVideoCompleted()
                }
                binding.videoView.setOnErrorListener { _, what, extra ->
                    android.util.Log.w("MediaPagerAdapter", "Video playback failed for ${item.uri}. what=$what extra=$extra")
                    android.widget.Toast.makeText(binding.videoView.context, "This video cannot be played.", android.widget.Toast.LENGTH_LONG).show()
                    true
                }

                if (transitionName != null) {
                    ViewCompat.setTransitionName(binding.videoView, transitionName)
                }
            } else {
                binding.videoView.visibility = View.GONE
                binding.photoView.visibility = View.VISIBLE

                if (transitionName != null) {
                    ViewCompat.setTransitionName(binding.photoView, transitionName)
                }

                val request = Glide.with(binding.photoView)
                    .load(item.uri)
                    .fitCenter()

                request.into(binding.photoView)

                if (isInitialSharedElement) {
                    val vto = binding.photoView.viewTreeObserver
                    vto.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                        override fun onPreDraw(): Boolean {
                            vto.removeOnPreDrawListener(this)
                            onInitialImageLoaded()
                            return true
                        }
                    })
                }
            }
        }

        fun startPlayback() {
            if (binding.videoView.visibility == View.VISIBLE && videoPrepared) {
                binding.videoView.start()
            }
        }

        fun pausePlayback() {
            if (binding.videoView.visibility == View.VISIBLE) {
                binding.videoView.pause()
            }
        }

        fun stopPlayback() {
            if (binding.videoView.visibility == View.VISIBLE) {
                binding.videoView.stopPlayback()
            }
        }

        fun isZoomed(): Boolean {
            return binding.photoView.visibility == View.VISIBLE && binding.photoView.scale > 1.0f
        }

        fun cleanup() {
            stopPlayback()
            videoPrepared = false
            Glide.with(binding.photoView).clear(binding.photoView)
        }
    }
}
