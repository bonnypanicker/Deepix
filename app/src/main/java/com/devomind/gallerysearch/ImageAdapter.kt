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
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.Glide
import com.devomind.gallerysearch.databinding.ItemAlbumBinding
import com.devomind.gallerysearch.databinding.ItemCollageBinding
import com.devomind.gallerysearch.databinding.ItemEmptyBinding
import com.devomind.gallerysearch.databinding.ItemFolderBinding
import com.devomind.gallerysearch.databinding.ItemImageBinding
import com.devomind.gallerysearch.databinding.ItemPinnedAlbumChipBinding
import com.devomind.gallerysearch.databinding.ItemPinnedAlbumsHeaderBinding
import com.devomind.gallerysearch.databinding.ItemSmartAlbumOnboardingBinding
import com.devomind.gallerysearch.databinding.ItemTimelineHeaderBinding

sealed class GalleryCell {
    data class Header(val title: String, val subtitle: String) : GalleryCell()
    data class Photo(
        val item: GalleryRepository.MediaItem,
        val featured: Boolean = false,
        val searchSources: SearchSources = SearchSources(),
        val collageSpan: Int = 0,
        val collageHeightPx: Int = 0
    ) : GalleryCell()
    data class Collage(val items: List<GalleryRepository.MediaItem>) : GalleryCell()
    data class AlbumCell(val album: GalleryRepository.Album) : GalleryCell()
    data class FolderCell(val node: FolderNode) : GalleryCell()
    data class PinnedAlbumsHeader(
        val albums: List<GalleryRepository.Album>
    ) : GalleryCell()
    object SmartAlbumOnboarding : GalleryCell()
    data class Empty(val text: String) : GalleryCell()
}

class ImageAdapter(
    private val onPhotoClick: (GalleryRepository.MediaItem, android.widget.ImageView) -> Unit,
    private val onSelectionChanged: (Int) -> Unit,
    private val onAlbumClick: (GalleryRepository.Album) -> Unit,
    private val onAlbumLongClick: (GalleryRepository.Album, View) -> Unit,
    private val onFolderClick: (FolderNode) -> Unit = {},
    private val onFolderExpandClick: (FolderNode) -> Unit = {},
    private val onCreateSmartAlbum: () -> Unit = {},
    private val onDismissSmartAlbumOnboarding: () -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var cells = mutableListOf<GalleryCell>()
        private set

    var gridColumnCount: Int = DesignTokens.GRID_DEFAULT_COLUMNS
    var useCollageLayout: Boolean = false

    // Sensitive-content blur (Beta): NSFW-flagged uris are blurred until tapped to reveal.
    var blurSensitive: Boolean = false
        private set
    private var nsfwUris: Set<String> = emptySet()
    private val revealedUris = mutableSetOf<String>()

    private val selected = linkedSetOf<Uri>()

    /** Updates the blur toggle + the set of flagged uris and refreshes affected tiles. */
    fun setSensitiveState(enabled: Boolean, flagged: Set<String>) {
        val changed = enabled != blurSensitive || flagged != nsfwUris
        blurSensitive = enabled
        nsfwUris = flagged
        if (!enabled) revealedUris.clear()
        if (changed) notifyDataSetChanged()
    }

    private fun isBlurred(item: GalleryRepository.MediaItem): Boolean {
        if (!blurSensitive) return false
        val key = item.uri.toString()
        return key in nsfwUris && key !in revealedUris
    }

    private fun revealSensitive(uri: Uri) {
        if (!revealedUris.add(uri.toString())) return
        val index = cells.indexOfFirst { it is GalleryCell.Photo && it.item.uri == uri }
        if (index >= 0) notifyItemChanged(index)
    }

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
            is GalleryCell.FolderCell -> ViewTypeFolder
            is GalleryCell.PinnedAlbumsHeader -> ViewTypePinnedAlbumsHeader
            is GalleryCell.SmartAlbumOnboarding -> ViewTypeSmartOnboarding
            is GalleryCell.Empty -> ViewTypeEmpty
        }
    }

    override fun getItemId(position: Int): Long = stableIdFor(cells[position])

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            ViewTypeHeader -> HeaderViewHolder(ItemTimelineHeaderBinding.inflate(inflater, parent, false))
            ViewTypeCollage -> CollageViewHolder(ItemCollageBinding.inflate(inflater, parent, false), onPhotoClick, ::toggleSelection)
            ViewTypeAlbum -> AlbumViewHolder(ItemAlbumBinding.inflate(inflater, parent, false), onAlbumClick, onAlbumLongClick)
            ViewTypeFolder -> FolderViewHolder(ItemFolderBinding.inflate(inflater, parent, false), onFolderClick, onFolderExpandClick)
            ViewTypePinnedAlbumsHeader -> PinnedAlbumsHeaderViewHolder(
                ItemPinnedAlbumsHeaderBinding.inflate(inflater, parent, false),
                onAlbumClick
            )
            ViewTypeEmpty -> EmptyViewHolder(ItemEmptyBinding.inflate(inflater, parent, false))
            ViewTypeSmartOnboarding -> SmartAlbumOnboardingViewHolder(
                ItemSmartAlbumOnboardingBinding.inflate(inflater, parent, false),
                onCreateSmartAlbum,
                onDismissSmartAlbumOnboarding
            )
            else -> PhotoViewHolder(
                ItemImageBinding.inflate(inflater, parent, false),
                onPhotoClick,
                ::toggleSelection,
                ::isBlurred,
                ::revealSensitive
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val cell = cells[position]) {
            is GalleryCell.Header -> (holder as HeaderViewHolder).bind(cell)
            is GalleryCell.Photo -> (holder as PhotoViewHolder).bind(
                cell,
                selected.isNotEmpty(),
                cell.item.uri in selected,
                gridColumnCount,
                useCollageLayout
            )
            is GalleryCell.Collage -> (holder as CollageViewHolder).bind(cell, selected)
            is GalleryCell.AlbumCell -> (holder as AlbumViewHolder).bind(cell.album)
            is GalleryCell.FolderCell -> (holder as FolderViewHolder).bind(cell.node)
            is GalleryCell.PinnedAlbumsHeader -> (holder as PinnedAlbumsHeaderViewHolder).bind(cell)
            is GalleryCell.SmartAlbumOnboarding -> Unit
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
            is GalleryCell.Empty,
            is GalleryCell.PinnedAlbumsHeader -> totalSpanCount
            is GalleryCell.SmartAlbumOnboarding -> totalSpanCount
            is GalleryCell.Collage -> totalSpanCount
            is GalleryCell.AlbumCell -> totalSpanCount / 2
            is GalleryCell.FolderCell -> totalSpanCount
            is GalleryCell.Photo -> {
                if (useCollageLayout) {
                    cell.collageSpan.coerceIn(1, totalSpanCount)
                } else {
                    1
                }
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

    fun replaceCells(newCells: List<GalleryCell>) {
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
        cells.clear()
        cells.addAll(newCells)
        notifyDataSetChanged()
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

    /** Public toggle so screens like Smart cleanup can make a plain tap select/deselect. */
    fun toggle(uri: Uri) = toggleSelection(uri)

    /** Replaces the current selection with [uris] that exist in the visible cells. */
    fun setSelection(uris: Collection<Uri>) {
        val present = cells.asSequence()
            .flatMap { cell ->
                when (cell) {
                    is GalleryCell.Photo -> sequenceOf(cell.item.uri)
                    is GalleryCell.Collage -> cell.items.asSequence().map { it.uri }
                    else -> emptySequence()
                }
            }
            .toSet()
        selected.clear()
        selected.addAll(uris.filter { it in present })
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    private fun toggleSelection(uri: Uri) {
        val hadSelection = selected.isNotEmpty()
        if (uri in selected) {
            selected -= uri
        } else {
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
            is GalleryCell.FolderCell -> "folder:${cell.node.path}"
            is GalleryCell.PinnedAlbumsHeader -> "pinned_albums_header"
            is GalleryCell.SmartAlbumOnboarding -> "smart_album_onboarding"
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
        private val onPhotoClick: (GalleryRepository.MediaItem, android.widget.ImageView) -> Unit,
        private val onSelectionToggle: (Uri) -> Unit,
        private val isBlurred: (GalleryRepository.MediaItem) -> Boolean = { false },
        private val onReveal: (Uri) -> Unit = {}
    ) : RecyclerView.ViewHolder(binding.root) {

        private fun loadThumbnail(
            context: android.content.Context,
            item: GalleryRepository.MediaItem,
            imageView: ImageView,
            width: Int,
            height: Int,
            blurred: Boolean
        ) {
            // Blur = decode at a tiny size and let the ImageView upscale it (a cheap, all-API blur).
            val targetW = if (blurred) BLUR_DOWNSCALE_PX else width
            val targetH = if (blurred) BLUR_DOWNSCALE_PX else height
            val request = if (item.mediaType == GalleryRepository.MediaType.Video) {
                Glide.with(context)
                    .asBitmap()
                    .load(item.uri)
                    .apply(com.bumptech.glide.request.RequestOptions().frame(1_000_000L))
                    .centerCrop()
                    .override(targetW, targetH)
                    .dontAnimate()
                    .placeholder(ColorDrawable(Color.rgb(17, 17, 17)))
            } else {
                Glide.with(context)
                    .load(item.uri)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .centerCrop()
                    .override(targetW, targetH)
                    .dontAnimate()
                    .placeholder(ColorDrawable(Color.rgb(17, 17, 17)))
            }
            request.into(imageView)
        }

        fun bind(cell: GalleryCell.Photo, selectionMode: Boolean, isSelected: Boolean, gridColumnCount: Int, useCollageLayout: Boolean) {
            val metrics = binding.root.resources.displayMetrics
            val gutter = (DesignTokens.GRID_GUTTER * metrics.density).toInt()
            val blurred = isBlurred(cell.item)

            ViewCompat.setTransitionName(binding.thumbnail, "media_${cell.item.uri}")

            if (!useCollageLayout) {
                val gridGutterSpacing = (DesignTokens.GRID_THUMBNAIL_SPACING_DP * metrics.density).toInt()
                val cellSize = ((metrics.widthPixels - gridGutterSpacing * (gridColumnCount - 1)) / gridColumnCount).coerceAtLeast(1)
                
                binding.thumbnail.layoutParams = binding.thumbnail.layoutParams.apply {
                    this.height = cellSize
                }
                loadThumbnail(binding.thumbnail.context, cell.item, binding.thumbnail, cellSize, cellSize, blurred)
            } else {
                // Justified-rows collage: height is precomputed per row so every
                // tile in a row lines up; width is filled by the grid span.
                val rowHeight = if (cell.collageHeightPx > 0) {
                    cell.collageHeightPx
                } else {
                    ((metrics.widthPixels - gutter * 6) / 3).coerceAtLeast(96)
                }
                val span = cell.collageSpan.coerceIn(1, DesignTokens.COLLAGE_SPAN_COUNT)
                val approxWidth = (metrics.widthPixels * span / DesignTokens.COLLAGE_SPAN_COUNT).coerceAtLeast(1)
                binding.thumbnail.layoutParams = binding.thumbnail.layoutParams.apply {
                    this.height = rowHeight
                }
                loadThumbnail(binding.thumbnail.context, cell.item, binding.thumbnail, approxWidth, rowHeight, blurred)
            }

            bindSelection(cell, selectionMode, isSelected)
        }

        fun bindSelection(cell: GalleryCell.Photo, selectionMode: Boolean, isSelected: Boolean) {
            val blurred = isBlurred(cell.item)
            binding.sensitiveOverlay.visibility = if (blurred) View.VISIBLE else View.GONE
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
            bindSearchBadges(cell.searchSources)
            binding.videoBadge.visibility = if (cell.item.mediaType == GalleryRepository.MediaType.Video) View.VISIBLE else View.GONE
            binding.root.setOnClickListener {
                when {
                    selectionMode -> onSelectionToggle(cell.item.uri)
                    blurred -> onReveal(cell.item.uri)
                    else -> onPhotoClick(cell.item, binding.thumbnail)
                }
            }
            binding.root.setOnLongClickListener {
                onSelectionToggle(cell.item.uri)
                true
            }
        }

        private companion object {
            const val BLUR_DOWNSCALE_PX = 16
        }

        private fun bindSearchBadges(sources: SearchSources) {
            binding.searchBadgeRow.visibility = if (sources.hasAny) View.VISIBLE else View.GONE
            binding.aiBadge.visibility = if (sources.ai) View.VISIBLE else View.GONE
            binding.metadataBadge.visibility = if (sources.metadata) View.VISIBLE else View.GONE
        }
    }

    class CollageViewHolder(
        private val binding: ItemCollageBinding,
        private val onPhotoClick: (GalleryRepository.MediaItem, android.widget.ImageView) -> Unit,
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
            ViewCompat.setTransitionName(thumbnail, "media_${item.uri}")
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
                val request = if (item.mediaType == GalleryRepository.MediaType.Video) {
                    Glide.with(thumbnail.context)
                        .asBitmap()
                        .load(item.uri)
                        .apply(com.bumptech.glide.request.RequestOptions().frame(1_000_000L))
                        .centerCrop()
                        .override(overrideWidth, overrideHeight)
                        .placeholder(ColorDrawable(Color.rgb(17, 17, 17)))
                } else {
                    Glide.with(thumbnail.context)
                        .load(item.uri)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .centerCrop()
                        .override(overrideWidth, overrideHeight)
                        .placeholder(ColorDrawable(Color.rgb(17, 17, 17)))
                }
                request.into(thumbnail)
            }

            container.setOnClickListener {
                if (selectionMode) onSelectionToggle(item.uri) else onPhotoClick(item, thumbnail)
            }
            container.setOnLongClickListener {
                onSelectionToggle(item.uri)
                true
            }
        }
    }

    class AlbumViewHolder(
        private val binding: ItemAlbumBinding,
        private val onAlbumClick: (GalleryRepository.Album) -> Unit,
        private val onAlbumLongClick: (GalleryRepository.Album, View) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(album: GalleryRepository.Album) {
            val metrics = binding.root.resources.displayMetrics
            val coverWidth = (metrics.widthPixels / 2).coerceAtLeast(160)
            val coverHeight = (coverWidth * 3) / 4
            binding.albumCoverContainer.layoutParams = binding.albumCoverContainer.layoutParams.apply {
                height = coverHeight
            }
            binding.albumName.text = album.name
            binding.albumCount.text = when {
                album.isSmart -> "CLIP search"
                album.count == 1 -> "1 item"
                else -> "${album.count} items"
            }
            binding.smartBadge.visibility = if (album.isSmart) View.VISIBLE else View.GONE
            Glide.with(binding.albumCover.context)
                .load(album.coverUri)
                .format(DecodeFormat.PREFER_RGB_565)
                .centerCrop()
                .override(coverWidth, coverHeight)
                .placeholder(ColorDrawable(Color.rgb(17, 17, 17)))
                .into(binding.albumCover)
            binding.root.setOnClickListener { onAlbumClick(album) }
            binding.root.setOnLongClickListener {
                onAlbumLongClick(album, binding.root)
                true
            }
        }
    }

    class PinnedAlbumsHeaderViewHolder(
        private val binding: ItemPinnedAlbumsHeaderBinding,
        private val onAlbumClick: (GalleryRepository.Album) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        private val chipAdapter = PinnedAlbumAdapter(onAlbumClick)

        init {
            binding.pinnedAlbumsList.layoutManager =
                LinearLayoutManager(binding.root.context, LinearLayoutManager.HORIZONTAL, false)
            binding.pinnedAlbumsList.adapter = chipAdapter
            binding.pinnedAlbumsList.setHasFixedSize(true)
        }

        fun bind(cell: GalleryCell.PinnedAlbumsHeader) {
            chipAdapter.submitList(cell.albums)
        }
    }

    class PinnedAlbumAdapter(
        private val onAlbumClick: (GalleryRepository.Album) -> Unit
    ) : ListAdapter<GalleryRepository.Album, PinnedAlbumAdapter.ChipViewHolder>(
        object : DiffUtil.ItemCallback<GalleryRepository.Album>() {
            override fun areItemsTheSame(old: GalleryRepository.Album, new: GalleryRepository.Album) = old.id == new.id
            override fun areContentsTheSame(old: GalleryRepository.Album, new: GalleryRepository.Album) = old == new
        }
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipViewHolder {
            val binding = ItemPinnedAlbumChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ChipViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
            holder.bind(getItem(position), onAlbumClick)
        }

        class ChipViewHolder(
            private val binding: ItemPinnedAlbumChipBinding
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(album: GalleryRepository.Album, onAlbumClick: (GalleryRepository.Album) -> Unit) {
                binding.chipName.text = album.name
                Glide.with(binding.chipCover.context)
                    .load(album.coverUri)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .centerCrop()
                    .placeholder(ColorDrawable(Color.rgb(17, 17, 17)))
                    .into(binding.chipCover)
                binding.root.setOnClickListener { onAlbumClick(album) }
            }
        }
    }

    class EmptyViewHolder(private val binding: ItemEmptyBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(cell: GalleryCell.Empty) {
            binding.emptyText.text = cell.text
        }
    }

    class SmartAlbumOnboardingViewHolder(
        binding: ItemSmartAlbumOnboardingBinding,
        onCreateSmartAlbum: () -> Unit,
        onDismiss: () -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.onboardingCreateBtn.setOnClickListener { onCreateSmartAlbum() }
            binding.root.setOnClickListener { onCreateSmartAlbum() }
            binding.onboardingDismissBtn.setOnClickListener { onDismiss() }
        }
    }

    class FolderViewHolder(
        private val binding: ItemFolderBinding,
        private val onFolderClick: (FolderNode) -> Unit,
        private val onFolderExpandClick: (FolderNode) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(node: FolderNode) {
            binding.folderName.text = node.name
            binding.folderCount.text = when (node.itemCount) {
                1 -> "1 item"
                else -> "${node.itemCount} items"
            }
            val indentPx = (node.depth * 24 * binding.root.resources.displayMetrics.density).toInt()
            binding.folderIndent.layoutParams = binding.folderIndent.layoutParams.apply {
                width = indentPx
            }

            if (node.coverUri != null) {
                Glide.with(binding.folderThumbnail)
                    .load(node.coverUri)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .centerCrop()
                    .placeholder(ColorDrawable(Color.rgb(17, 17, 17)))
                    .into(binding.folderThumbnail)
            } else {
                binding.folderThumbnail.setImageDrawable(ColorDrawable(Color.rgb(17, 17, 17)))
            }

            if (node.isLeaf) {
                binding.folderExpandIcon.visibility = View.GONE
                binding.root.setOnClickListener { onFolderClick(node) }
            } else {
                binding.folderExpandIcon.visibility = View.VISIBLE
                binding.folderExpandIcon.setImageResource(
                    if (node.expanded) R.drawable.ic_fluent_chevron_down_24_regular else R.drawable.ic_fluent_chevron_right_24_regular
                )
                binding.root.setOnClickListener { onFolderClick(node) }
                binding.folderExpandIcon.setOnClickListener { onFolderExpandClick(node) }
            }
        }
    }

    companion object {
        private const val PayloadSelection = "payload_selection"
        const val ViewTypeHeader = 1
        const val ViewTypePhoto = 2
        const val ViewTypeCollage = 3
        const val ViewTypeAlbum = 4
        const val ViewTypeEmpty = 5
        const val ViewTypePinnedAlbumsHeader = 6
        const val ViewTypeFolder = 7
        const val ViewTypeSmartOnboarding = 8
    }
}
