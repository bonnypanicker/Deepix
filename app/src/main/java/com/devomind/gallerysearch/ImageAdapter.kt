package com.devomind.gallerysearch

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.devomind.gallerysearch.databinding.ItemAlbumBinding
import com.devomind.gallerysearch.databinding.ItemCollageBinding
import com.devomind.gallerysearch.databinding.ItemEmptyBinding
import com.devomind.gallerysearch.databinding.ItemImageBinding
import com.devomind.gallerysearch.databinding.ItemTimelineHeaderBinding

sealed class GalleryCell {
    data class Header(val title: String, val subtitle: String) : GalleryCell()
    data class Photo(val item: GalleryRepository.MediaItem, val featured: Boolean = false) : GalleryCell()
    data class Collage(val items: List<GalleryRepository.MediaItem>) : GalleryCell()
    data class AlbumCell(val album: GalleryRepository.Album) : GalleryCell()
    data class Empty(val text: String) : GalleryCell()
}

class ImageAdapter(
    private val onPhotoClick: (GalleryRepository.MediaItem) -> Unit,
    private val onSelectionChanged: (Int) -> Unit,
    private val onAlbumClick: (GalleryRepository.Album) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val cells = mutableListOf<GalleryCell>()
    private val selected = linkedSetOf<Uri>()

    init {
        setHasStableIds(true)
    }

    val selectionCount: Int
        get() = selected.size

    override fun getItemViewType(position: Int): Int {
        return when (cells[position]) {
            is GalleryCell.Header -> ViewTypeHeader
            is GalleryCell.Photo -> ViewTypePhoto
            is GalleryCell.Collage -> ViewTypeCollage
            is GalleryCell.AlbumCell -> ViewTypeAlbum
            is GalleryCell.Empty -> ViewTypeEmpty
        }
    }

    override fun getItemId(position: Int): Long = stableIdFor(cells[position])

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            ViewTypeHeader -> HeaderViewHolder(ItemTimelineHeaderBinding.inflate(inflater, parent, false))
            ViewTypeCollage -> CollageViewHolder(ItemCollageBinding.inflate(inflater, parent, false), onPhotoClick, ::toggleSelection)
            ViewTypeAlbum -> AlbumViewHolder(ItemAlbumBinding.inflate(inflater, parent, false), onAlbumClick)
            ViewTypeEmpty -> EmptyViewHolder(ItemEmptyBinding.inflate(inflater, parent, false))
            else -> PhotoViewHolder(ItemImageBinding.inflate(inflater, parent, false), onPhotoClick, ::toggleSelection)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val cell = cells[position]) {
            is GalleryCell.Header -> (holder as HeaderViewHolder).bind(cell)
            is GalleryCell.Photo -> (holder as PhotoViewHolder).bind(cell, selected.isNotEmpty(), cell.item.uri in selected)
            is GalleryCell.Collage -> (holder as CollageViewHolder).bind(cell, selected)
            is GalleryCell.AlbumCell -> (holder as AlbumViewHolder).bind(cell.album)
            is GalleryCell.Empty -> (holder as EmptyViewHolder).bind(cell)
        }
    }

    override fun getItemCount(): Int = cells.size

    fun spanSizeAt(position: Int, totalSpanCount: Int): Int {
        return when (val cell = cells.getOrNull(position)) {
            is GalleryCell.Header,
            is GalleryCell.Collage,
            is GalleryCell.Empty -> totalSpanCount
            is GalleryCell.AlbumCell -> totalSpanCount / 2
            is GalleryCell.Photo -> if (cell.featured) 4 else 2
            null -> 2
        }
    }

    fun updateCells(newCells: List<GalleryCell>) {
        val newUris = newCells.asSequence()
            .flatMap { cell ->
                when (cell) {
                    is GalleryCell.Photo -> sequenceOf(cell.item.uri)
                    is GalleryCell.Collage -> cell.items.asSequence().map { it.uri }
                    else -> emptySequence()
                }
            }
            .toSet()
        selected.retainAll(newUris)

        val oldCells = cells.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldCells.size
            override fun getNewListSize(): Int = newCells.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return stableIdFor(oldCells[oldItemPosition]) == stableIdFor(newCells[newItemPosition])
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldCells[oldItemPosition] == newCells[newItemPosition]
            }
        })

        cells.clear()
        cells.addAll(newCells)
        diff.dispatchUpdatesTo(this)
        onSelectionChanged(selected.size)
    }

    fun appendCells(newCells: List<GalleryCell>) {
        val start = cells.size
        cells.addAll(newCells)
        notifyItemRangeInserted(start, newCells.size)
    }

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun selectedUris(): List<Uri> = selected.toList()

    private fun toggleSelection(uri: Uri) {
        if (uri in selected) {
            selected -= uri
        } else {
            selected += uri
        }
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    private fun stableIdFor(cell: GalleryCell): Long {
        val key = when (cell) {
            is GalleryCell.Header -> "header:${cell.title}:${cell.subtitle}"
            is GalleryCell.Photo -> "photo:${cell.item.uri}"
            is GalleryCell.Collage -> "collage:${cell.items.joinToString("|") { it.uri.toString() }}"
            is GalleryCell.AlbumCell -> "album:${cell.album.id}"
            is GalleryCell.Empty -> "empty:${cell.text}"
        }
        return key.hashCode().toLong()
    }

    class HeaderViewHolder(private val binding: ItemTimelineHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(cell: GalleryCell.Header) {
            binding.headerTitle.text = cell.title
            binding.headerSubtitle.text = cell.subtitle
        }
    }

    class PhotoViewHolder(
        private val binding: ItemImageBinding,
        private val onPhotoClick: (GalleryRepository.MediaItem) -> Unit,
        private val onSelectionToggle: (Uri) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(cell: GalleryCell.Photo, selectionMode: Boolean, isSelected: Boolean) {
            val metrics = binding.root.resources.displayMetrics
            val gutter = (2f * metrics.density).toInt()
            val regularSize = ((metrics.widthPixels - gutter * 6) / 3).coerceAtLeast(96)
            val height = if (cell.featured) regularSize * 2 + gutter else regularSize
            binding.thumbnail.layoutParams = binding.thumbnail.layoutParams.apply { this.height = height }

            binding.dimScrim.visibility = if (selectionMode && !isSelected) View.VISIBLE else View.GONE
            binding.checkBadge.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.videoBadge.visibility = if (cell.item.mediaType == GalleryRepository.MediaType.Video) View.VISIBLE else View.GONE

            Glide.with(binding.thumbnail.context)
                .load(cell.item.uri)
                .centerCrop()
                .placeholder(ColorDrawable(Color.rgb(17, 17, 17)))
                .into(binding.thumbnail)

            binding.root.setOnClickListener {
                if (selectionMode) onSelectionToggle(cell.item.uri) else onPhotoClick(cell.item)
            }
            binding.root.setOnLongClickListener {
                onSelectionToggle(cell.item.uri)
                true
            }
        }
    }

    class CollageViewHolder(
        private val binding: ItemCollageBinding,
        private val onPhotoClick: (GalleryRepository.MediaItem) -> Unit,
        private val onSelectionToggle: (Uri) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(cell: GalleryCell.Collage, selected: Set<Uri>) {
            val metrics = binding.root.resources.displayMetrics
            val gutter = (2f * metrics.density).toInt()
            val regularSize = ((metrics.widthPixels - gutter * 6) / 3).coerceAtLeast(96)
            binding.collageRoot.layoutParams = binding.collageRoot.layoutParams.apply {
                height = regularSize * 2 + gutter
            }

            bindTile(
                container = binding.leadTile,
                thumbnail = binding.leadThumbnail,
                dimScrim = binding.leadDimScrim,
                checkBadge = binding.leadCheckBadge,
                videoBadge = binding.leadVideoBadge,
                item = cell.items[0],
                selectionMode = selected.isNotEmpty(),
                isSelected = cell.items[0].uri in selected
            )
            bindTile(
                container = binding.topRightTile,
                thumbnail = binding.topRightThumbnail,
                dimScrim = binding.topRightDimScrim,
                checkBadge = binding.topRightCheckBadge,
                videoBadge = binding.topRightVideoBadge,
                item = cell.items[1],
                selectionMode = selected.isNotEmpty(),
                isSelected = cell.items[1].uri in selected
            )
            bindTile(
                container = binding.bottomRightTile,
                thumbnail = binding.bottomRightThumbnail,
                dimScrim = binding.bottomRightDimScrim,
                checkBadge = binding.bottomRightCheckBadge,
                videoBadge = binding.bottomRightVideoBadge,
                item = cell.items[2],
                selectionMode = selected.isNotEmpty(),
                isSelected = cell.items[2].uri in selected
            )
        }

        private fun bindTile(
            container: FrameLayout,
            thumbnail: ImageView,
            dimScrim: View,
            checkBadge: TextView,
            videoBadge: TextView,
            item: GalleryRepository.MediaItem,
            selectionMode: Boolean,
            isSelected: Boolean
        ) {
            dimScrim.visibility = if (selectionMode && !isSelected) View.VISIBLE else View.GONE
            checkBadge.visibility = if (isSelected) View.VISIBLE else View.GONE
            videoBadge.visibility =
                if (item.mediaType == GalleryRepository.MediaType.Video) View.VISIBLE else View.GONE

            Glide.with(thumbnail.context)
                .load(item.uri)
                .centerCrop()
                .placeholder(ColorDrawable(Color.rgb(17, 17, 17)))
                .into(thumbnail)

            container.setOnClickListener {
                if (selectionMode) onSelectionToggle(item.uri) else onPhotoClick(item)
            }
            container.setOnLongClickListener {
                onSelectionToggle(item.uri)
                true
            }
        }
    }

    class AlbumViewHolder(
        private val binding: ItemAlbumBinding,
        private val onAlbumClick: (GalleryRepository.Album) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(album: GalleryRepository.Album) {
            binding.albumName.text = album.name
            binding.albumCount.text = if (album.count == 1) "1 item" else "${album.count} items"
            Glide.with(binding.albumCover.context)
                .load(album.coverUri)
                .centerCrop()
                .placeholder(ColorDrawable(Color.rgb(17, 17, 17)))
                .into(binding.albumCover)
            binding.root.setOnClickListener { onAlbumClick(album) }
        }
    }

    class EmptyViewHolder(private val binding: ItemEmptyBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(cell: GalleryCell.Empty) {
            binding.emptyText.text = cell.text
        }
    }

    companion object {
        const val ViewTypeHeader = 1
        const val ViewTypePhoto = 2
        const val ViewTypeCollage = 3
        const val ViewTypeAlbum = 4
        const val ViewTypeEmpty = 5
    }
}
