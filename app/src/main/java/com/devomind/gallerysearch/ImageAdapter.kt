package com.devomind.gallerysearch

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.DecodeFormat
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

    var cells = mutableListOf<GalleryCell>()
        private set

    private val selected = linkedSetOf<Uri>()

    init {
        setHasStableIds(true)
    }

    val selectionCount: Int
        get() = selected.size

    fun selectAll() {
        val allUris = cells.asSequence()
            .flatMap { cell ->
                when (cell) {
                    is GalleryCell.Photo -> sequenceOf(cell.item.uri)
                    is GalleryCell.Collage -> cell.items.asSequence().map { it.uri }
                    else -> emptySequence()
                }
            }
            .toSet()

        val added = allUris - selected
        selected.addAll(allUris)
        if (added.isNotEmpty()) {
            notifySelectionChanged(added, selectionModeChanged = selected.size == added.size)
            onSelectionChanged(selected.size)
        }
    }

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

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.any { it == PayloadSelection }) {
            when (val cell = cells[position]) {
                is GalleryCell.Photo -> (holder as PhotoViewHolder).bindSelection(cell, selected.isNotEmpty(), cell.item.uri in selected)
                is GalleryCell.Collage -> (holder as CollageViewHolder).bindSelection(cell, selected)
                else -> onBindViewHolder(holder, position)
            }
            return
        }
        onBindViewHolder(holder, position)
    }

    override fun getItemCount(): Int = cells.size

    fun spanSizeAt(position: Int, totalSpanCount: Int): Int {
        return when (val cell = cells.getOrNull(position)) {
            is GalleryCell.Header,
            is GalleryCell.Empty -> totalSpanCount
            is GalleryCell.Collage -> totalSpanCount
            is GalleryCell.AlbumCell -> totalSpanCount / 2
            is GalleryCell.Photo -> {
                if (cell.featured) totalSpanCount else totalSpanCount / 3
            }
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

    fun clearSelection() {
        if (selected.isEmpty()) return
        val previous = selected.toSet()
        selected.clear()
        notifySelectionChanged(previous, selectionModeChanged = true)
        onSelectionChanged(0)
    }

    fun selectedUris(): List<Uri> = selected.toList()

    private fun toggleSelection(uri: Uri) {
        val hadSelection = selected.isNotEmpty()
        if (uri in selected) {
            selected -= uri
        } else {
            animateCheckBadge(uri)
            selected += uri
        }
        notifySelectionChanged(setOf(uri), selectionModeChanged = hadSelection != selected.isNotEmpty())
        onSelectionChanged(selected.size)
    }

    private fun notifySelectionChanged(affectedUris: Set<Uri>, selectionModeChanged: Boolean) {
        if (selectionModeChanged) {
            cells.forEachIndexed { index, cell ->
                if (cell is GalleryCell.Photo || cell is GalleryCell.Collage) {
                    notifyItemChanged(index, PayloadSelection)
                }
            }
            return
        }
        affectedUris.forEach { uri ->
            cells.forEachIndexed { index, cell ->
                val containsUri = when (cell) {
                    is GalleryCell.Photo -> cell.item.uri == uri
                    is GalleryCell.Collage -> cell.items.any { it.uri == uri }
                    else -> false
                }
                if (containsUri) {
                    notifyItemChanged(index, PayloadSelection)
                }
            }
        }
    }

    private fun stableIdFor(cell: GalleryCell): Long {
        val key = when (cell) {
            is GalleryCell.Header -> "header:${cell.title}:${cell.subtitle}"
            is GalleryCell.Photo -> "photo:${cell.item.uri}"
            is GalleryCell.Collage -> "collage:${cell.items.joinToString("|") { it.uri.toString() }}"
            is GalleryCell.AlbumCell -> "album:${cell.album.id}"
            is GalleryCell.Empty -> "empty:${cell.text}"
        }
        return key.fold(1125899906842597L) { hash, char -> 31L * hash + char.code.toLong() }
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
            val gutter = (DesignTokens.GRID_GUTTER * metrics.density).toInt()
            val regularSize = ((metrics.widthPixels - gutter * 6) / 3).coerceAtLeast(96)

            when {
                cell.featured -> {
                    val spanWidth = metrics.widthPixels
                    val height = (spanWidth * 0.56f).toInt()
                    binding.thumbnail.layoutParams = binding.thumbnail.layoutParams.apply {
                        this.height = height
                    }
                    Glide.with(binding.thumbnail.context)
                        .load(cell.item.uri)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .centerCrop()
                        .override(spanWidth, height)
                        .placeholder(ColorDrawable(Color.rgb(17, 17, 17)))
                        .into(binding.thumbnail)
                }
                else -> {
                    val height = regularSize
                    binding.thumbnail.layoutParams = binding.thumbnail.layoutParams.apply {
                        this.height = height
                    }
                    Glide.with(binding.thumbnail.context)
                        .load(cell.item.uri)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .centerCrop()
                        .override(regularSize, height)
                        .placeholder(ColorDrawable(Color.rgb(17, 17, 17)))
                        .into(binding.thumbnail)
                }
            }

            bindSelection(cell, selectionMode, isSelected)
        }

        fun bindSelection(cell: GalleryCell.Photo, selectionMode: Boolean, isSelected: Boolean) {
            binding.dimScrim.visibility = if (selectionMode && !isSelected) View.VISIBLE else View.GONE
            binding.checkBadge.visibility = if (isSelected) View.VISIBLE else View.GONE
            if (isSelected) {
                binding.checkBadge.scaleX = 0f
                binding.checkBadge.scaleY = 0f
                binding.checkBadge.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
            }
            binding.videoBadge.visibility = if (cell.item.mediaType == GalleryRepository.MediaType.Video) View.VISIBLE else View.GONE
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
            val gutter = (DesignTokens.GRID_GUTTER * metrics.density).toInt()
            val regularSize = ((metrics.widthPixels - gutter * 6) / 3).coerceAtLeast(96)
            val leadSize = regularSize * 2 + gutter
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
                overrideWidth = leadSize,
                overrideHeight = leadSize,
                selectionMode = selected.isNotEmpty(),
                isSelected = cell.items[0].uri in selected,
                loadImage = true
            )
            bindTile(
                container = binding.topRightTile,
                thumbnail = binding.topRightThumbnail,
                dimScrim = binding.topRightDimScrim,
                checkBadge = binding.topRightCheckBadge,
                videoBadge = binding.topRightVideoBadge,
                item = cell.items[1],
                overrideWidth = regularSize,
                overrideHeight = regularSize,
                selectionMode = selected.isNotEmpty(),
                isSelected = cell.items[1].uri in selected,
                loadImage = true
            )
            bindTile(
                container = binding.bottomRightTile,
                thumbnail = binding.bottomRightThumbnail,
                dimScrim = binding.bottomRightDimScrim,
                checkBadge = binding.bottomRightCheckBadge,
                videoBadge = binding.bottomRightVideoBadge,
                item = cell.items[2],
                overrideWidth = regularSize,
                overrideHeight = regularSize,
                selectionMode = selected.isNotEmpty(),
                isSelected = cell.items[2].uri in selected,
                loadImage = true
            )
        }

        fun bindSelection(cell: GalleryCell.Collage, selected: Set<Uri>) {
            val metrics = binding.root.resources.displayMetrics
            val gutter = (DesignTokens.GRID_GUTTER * metrics.density).toInt()
            val regularSize = ((metrics.widthPixels - gutter * 6) / 3).coerceAtLeast(96)
            val leadSize = regularSize * 2 + gutter
            bindTile(
                container = binding.leadTile,
                thumbnail = binding.leadThumbnail,
                dimScrim = binding.leadDimScrim,
                checkBadge = binding.leadCheckBadge,
                videoBadge = binding.leadVideoBadge,
                item = cell.items[0],
                overrideWidth = leadSize,
                overrideHeight = leadSize,
                selectionMode = selected.isNotEmpty(),
                isSelected = cell.items[0].uri in selected,
                loadImage = false
            )
            bindTile(
                container = binding.topRightTile,
                thumbnail = binding.topRightThumbnail,
                dimScrim = binding.topRightDimScrim,
                checkBadge = binding.topRightCheckBadge,
                videoBadge = binding.topRightVideoBadge,
                item = cell.items[1],
                overrideWidth = regularSize,
                overrideHeight = regularSize,
                selectionMode = selected.isNotEmpty(),
                isSelected = cell.items[1].uri in selected,
                loadImage = false
            )
            bindTile(
                container = binding.bottomRightTile,
                thumbnail = binding.bottomRightThumbnail,
                dimScrim = binding.bottomRightDimScrim,
                checkBadge = binding.bottomRightCheckBadge,
                videoBadge = binding.bottomRightVideoBadge,
                item = cell.items[2],
                overrideWidth = regularSize,
                overrideHeight = regularSize,
                selectionMode = selected.isNotEmpty(),
                isSelected = cell.items[2].uri in selected,
                loadImage = false
            )
        }

        private fun bindTile(
            container: FrameLayout,
            thumbnail: ImageView,
            dimScrim: View,
            checkBadge: TextView,
            videoBadge: TextView,
            item: GalleryRepository.MediaItem,
            overrideWidth: Int,
            overrideHeight: Int,
            selectionMode: Boolean,
            isSelected: Boolean,
            loadImage: Boolean
        ) {
            dimScrim.visibility = if (selectionMode && !isSelected) View.VISIBLE else View.GONE
            checkBadge.visibility = if (isSelected) View.VISIBLE else View.GONE
            if (isSelected) {
                checkBadge.scaleX = 0f
                checkBadge.scaleY = 0f
                checkBadge.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
            }
            videoBadge.visibility =
                if (item.mediaType == GalleryRepository.MediaType.Video) View.VISIBLE else View.GONE

            if (loadImage) {
                Glide.with(thumbnail.context)
                    .load(item.uri)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .centerCrop()
                    .override(overrideWidth, overrideHeight)
                    .placeholder(ColorDrawable(Color.rgb(17, 17, 17)))
                    .into(thumbnail)
            }

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
            val metrics = binding.root.resources.displayMetrics
            val coverWidth = (metrics.widthPixels / 2).coerceAtLeast(160)
            val coverHeight = (coverWidth * 3) / 4
            binding.albumName.text = album.name
            binding.albumCount.text = if (album.count == 1) "1 item" else "${album.count} items"
            Glide.with(binding.albumCover.context)
                .load(album.coverUri)
                .format(DecodeFormat.PREFER_RGB_565)
                .centerCrop()
                .override(coverWidth, coverHeight)
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
        private const val PayloadSelection = "payload_selection"
        const val ViewTypeHeader = 1
        const val ViewTypePhoto = 2
        const val ViewTypeCollage = 3
        const val ViewTypeAlbum = 4
        const val ViewTypeEmpty = 5
    }
}
