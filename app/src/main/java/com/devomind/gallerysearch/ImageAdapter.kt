package com.devomind.gallerysearch

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.devomind.gallerysearch.databinding.ItemSortRowBinding
import com.devomind.gallerysearch.databinding.ItemTimelineHeaderBinding

/**
 * Applies the Metro selection visuals to one thumbnail: an accent frame + corner
 * tick highlight the selected item; unselected items are left untouched (no dim),
 * for a cleaner WP10 look. [animate] is true only for a genuine user toggle so the
 * tick doesn't pop every time a cell is recycled/scrolled back into view.
 */
private fun bindSelectionVisual(
    dimScrim: View,
    selectionFrame: View,
    checkBadge: View,
    isSelected: Boolean,
    animate: Boolean
) {
    dimScrim.visibility = View.GONE
    selectionFrame.visibility = if (isSelected) View.VISIBLE else View.GONE
    checkBadge.visibility = if (isSelected) View.VISIBLE else View.GONE
    checkBadge.animate().cancel()
    if (isSelected && animate) {
        checkBadge.scaleX = 0.6f
        checkBadge.scaleY = 0.6f
        checkBadge.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(120L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    } else {
        checkBadge.scaleX = 1f
        checkBadge.scaleY = 1f
    }
}

/**
 * Binds the sort affordance shared by the timeline header and the standalone sort row.
 * Must be called on every bind, including the null path: header holders are recycled across
 * every date header, so skipping the else-branch leaks a stale chip onto a later header.
 */
private fun bindSortChip(
    chip: LinearLayout,
    label: TextView,
    text: String?,
    onSortClick: (View) -> Unit
) {
    if (text == null) {
        chip.visibility = View.GONE
        chip.setOnClickListener(null)
        return
    }
    chip.visibility = View.VISIBLE
    label.text = text
    chip.contentDescription = chip.context.getString(R.string.sort_change_order) + ": " + text
    chip.setOnClickListener { onSortClick(it) }
}

sealed class GalleryCell {
    /**
     * A timeline group header. [sortLabel] is non-null on the single header that carries the sort
     * affordance (see MainActivity.withSortAffordance); every other header renders without one.
     */
    data class Header(
        val title: String,
        val subtitle: String,
        val sortLabel: String? = null
    ) : GalleryCell()

    /** Slim right-aligned sort chip, used when an order emits no date headers to hang it on. */
    data class SortRow(val label: String) : GalleryCell()

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

    /**
     * Full-span empty state: light headline, optional secondary hint, optional Fluent glyph
     * override, and an optional accent pill action (e.g. "Start indexing").
     */
    data class Empty(
        val text: String,
        val hint: String? = null,
        val iconRes: Int? = null,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null
    ) : GalleryCell()
}

class ImageAdapter(
    private val onPhotoClick: (GalleryRepository.MediaItem, android.widget.ImageView) -> Unit,
    private val onSelectionChanged: (Int) -> Unit,
    private val onAlbumClick: (GalleryRepository.Album) -> Unit,
    private val onAlbumLongClick: (GalleryRepository.Album, View) -> Unit,
    private val onFolderClick: (FolderNode) -> Unit = {},
    private val onFolderExpandClick: (FolderNode) -> Unit = {},
    private val onCreateSmartAlbum: () -> Unit = {},
    private val onDismissSmartAlbumOnboarding: () -> Unit = {},
    private val onSortClick: (View) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var cells = mutableListOf<GalleryCell>()
        private set

    var gridColumnCount: Int = DesignTokens.GRID_DEFAULT_COLUMNS
    var useCollageLayout: Boolean = false

    // Set once from the host (and on settings change) so album binds never touch SharedPreferences.
    var showAlbumFolderSize: Boolean = false

    // Sensitive-content blur (Beta): NSFW-flagged uris are blurred until tapped to reveal.
    var blurSensitive: Boolean = false
        private set
    private var nsfwUris: Set<String> = emptySet()
    private val revealedUris = mutableSetOf<String>()

    private val selected = linkedSetOf<Uri>()
    private var selectionAnchor: Uri? = null
    private var selectionGestureActive = false

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

    fun hasActiveSelectionGesture(): Boolean = selectionGestureActive && selectionAnchor != null

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
        if (selectionAnchor !in selected) {
            selectionAnchor = selected.firstOrNull()
        }
        selectionGestureActive = false
        if (added.isNotEmpty()) {
            notifySelectionChanged(added, selectionModeChanged = selected.size == added.size)
            onSelectionChanged(selected.size)
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (cells[position]) {
            is GalleryCell.Header -> ViewTypeHeader
            is GalleryCell.SortRow -> ViewTypeSortRow
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
            ViewTypeHeader -> HeaderViewHolder(ItemTimelineHeaderBinding.inflate(inflater, parent, false), onSortClick)
            ViewTypeSortRow -> SortRowViewHolder(ItemSortRowBinding.inflate(inflater, parent, false), onSortClick)
            ViewTypeCollage -> CollageViewHolder(
                ItemCollageBinding.inflate(inflater, parent, false),
                onPhotoClick,
                ::handleSelectionTap,
                ::beginSelectionGesture
            )
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
                ::handleSelectionTap,
                ::beginSelectionGesture,
                ::isBlurred,
                ::revealSensitive
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val cell = cells[position]) {
            is GalleryCell.Header -> (holder as HeaderViewHolder).bind(cell)
            is GalleryCell.SortRow -> (holder as SortRowViewHolder).bind(cell)
            is GalleryCell.Photo -> (holder as PhotoViewHolder).bind(
                cell,
                selected.isNotEmpty(),
                cell.item.uri in selected,
                gridColumnCount,
                useCollageLayout
            )
            is GalleryCell.Collage -> (holder as CollageViewHolder).bind(cell, selected)
            is GalleryCell.AlbumCell -> (holder as AlbumViewHolder).bind(cell.album, showAlbumFolderSize)
            is GalleryCell.FolderCell -> (holder as FolderViewHolder).bind(cell.node, showAlbumFolderSize)
            is GalleryCell.PinnedAlbumsHeader -> (holder as PinnedAlbumsHeaderViewHolder).bind(cell)
            is GalleryCell.SmartAlbumOnboarding -> Unit
            is GalleryCell.Empty -> (holder as EmptyViewHolder).bind(cell)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.any { it == PayloadSelection }) {
            when (val cell = cells[position]) {
                is GalleryCell.Photo -> (holder as PhotoViewHolder).bindSelection(cell, selected.isNotEmpty(), cell.item.uri in selected, animate = true)
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
            is GalleryCell.SortRow,
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
        if (selectionAnchor !in selected) selectionAnchor = selected.firstOrNull()
        if (selected.isEmpty()) selectionGestureActive = false

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
        if (selectionAnchor !in selected) selectionAnchor = selected.firstOrNull()
        if (selected.isEmpty()) selectionGestureActive = false
        cells.clear()
        cells.addAll(newCells)
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    fun clearSelection() {
        if (selected.isEmpty()) return
        val previous = selected.toSet()
        selected.clear()
        selectionAnchor = null
        selectionGestureActive = false
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
        selectionAnchor = selected.firstOrNull()
        selectionGestureActive = false
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    fun beginSelectionGesture(uri: Uri) {
        selectionGestureActive = true
        val anchor = selectionAnchor
        when {
            selected.isEmpty() || anchor == null -> {
                ensureSelected(uri)
                selectionAnchor = uri
            }
            anchor == uri -> ensureSelected(uri)
            else -> {
                val changed = selectRange(anchor, uri)
                if (!changed) ensureSelected(uri)
                selectionAnchor = uri
            }
        }
    }

    fun extendSelectionGestureTo(uri: Uri): Boolean {
        val anchor = selectionAnchor ?: return false
        if (!selectionGestureActive) return false
        return selectRange(anchor, uri)
    }

    fun endSelectionGesture() {
        selectionGestureActive = false
    }

    fun mediaUriAt(recyclerView: RecyclerView, x: Float, y: Float): Uri? {
        val child = recyclerView.findChildViewUnder(x, y) ?: return null
        val holder = recyclerView.getChildViewHolder(child)
        val cell = cells.getOrNull(holder.bindingAdapterPosition) ?: return null
        return when {
            cell is GalleryCell.Photo -> cell.item.uri
            cell is GalleryCell.Collage && holder is CollageViewHolder -> {
                val localX = x - child.left
                val localY = y - child.top
                holder.uriAt(localX, localY, cell)
            }
            else -> null
        }
    }

    private fun handleSelectionTap(uri: Uri) {
        selectionGestureActive = false
        toggleSelection(uri)
        if (uri in selected) {
            selectionAnchor = uri
        } else if (selectionAnchor == uri) {
            selectionAnchor = selected.firstOrNull()
        }
    }

    private fun ensureSelected(uri: Uri) {
        val hadSelection = selected.isNotEmpty()
        if (selected.add(uri)) {
            notifySelectionChanged(setOf(uri), selectionModeChanged = !hadSelection)
            onSelectionChanged(selected.size)
        }
    }

    private fun selectRange(anchorUri: Uri, targetUri: Uri): Boolean {
        val ordered = orderedMediaUris()
        val anchorIndex = ordered.indexOf(anchorUri)
        val targetIndex = ordered.indexOf(targetUri)
        if (anchorIndex == -1 || targetIndex == -1) return false
        val start = minOf(anchorIndex, targetIndex)
        val end = maxOf(anchorIndex, targetIndex)
        val hadSelection = selected.isNotEmpty()
        val added = linkedSetOf<Uri>()
        for (index in start..end) {
            val uri = ordered[index]
            if (selected.add(uri)) {
                added += uri
            }
        }
        if (added.isEmpty()) return false
        notifySelectionChanged(added, selectionModeChanged = !hadSelection)
        onSelectionChanged(selected.size)
        return true
    }

    private fun orderedMediaUris(): List<Uri> {
        return cells.flatMap { cell ->
            when (cell) {
                is GalleryCell.Photo -> listOf(cell.item.uri)
                is GalleryCell.Collage -> cell.items.map { it.uri }
                else -> emptyList()
            }
        }
    }

    private fun toggleSelection(uri: Uri) {
        val hadSelection = selected.isNotEmpty()
        if (uri in selected) {
            selected -= uri
        } else {
            selected += uri
        }
        if (selected.isEmpty()) {
            selectionAnchor = null
            selectionGestureActive = false
        } else if (uri in selected) {
            selectionAnchor = uri
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
            // sortLabel is deliberately excluded: a sort change should rebind the header in place
            // (areContentsTheSame sees the field) rather than look like a different item.
            is GalleryCell.Header -> "header:${cell.title}:${cell.subtitle}"
            is GalleryCell.SortRow -> "sort_row"
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

    class HeaderViewHolder(
        private val binding: ItemTimelineHeaderBinding,
        private val onSortClick: (View) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(cell: GalleryCell.Header) {
            binding.headerTitle.text = cell.title
            binding.headerSubtitle.text = cell.subtitle
            bindSortChip(binding.sortControl, binding.sortLabel, cell.sortLabel, onSortClick)
        }
    }

    class SortRowViewHolder(
        private val binding: ItemSortRowBinding,
        private val onSortClick: (View) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(cell: GalleryCell.SortRow) {
            bindSortChip(binding.sortControl, binding.sortLabel, cell.label, onSortClick)
        }
    }

    class PhotoViewHolder(
        private val binding: ItemImageBinding,
        private val onPhotoClick: (GalleryRepository.MediaItem, android.widget.ImageView) -> Unit,
        private val onSelectionTap: (Uri) -> Unit,
        private val onSelectionGestureStart: (Uri) -> Unit,
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

            bindSelection(cell, selectionMode, isSelected, animate = false)
        }

        fun bindSelection(cell: GalleryCell.Photo, selectionMode: Boolean, isSelected: Boolean, animate: Boolean) {
            val blurred = isBlurred(cell.item)
            binding.sensitiveOverlay.visibility = if (blurred) View.VISIBLE else View.GONE
            bindSelectionVisual(
                dimScrim = binding.dimScrim,
                selectionFrame = binding.selectionFrame,
                checkBadge = binding.checkBadge,
                isSelected = isSelected,
                animate = animate
            )
            // TalkBack: announce what the tile is and whether it's selected.
            val typeLabel = if (cell.item.mediaType == GalleryRepository.MediaType.Video) "Video" else "Photo"
            val dateLabel = cell.item.displayName ?: ""
            binding.root.contentDescription = if (dateLabel.isEmpty()) typeLabel else "$typeLabel, $dateLabel"
            binding.root.isSelected = isSelected
            ViewCompat.setStateDescription(
                binding.root,
                when {
                    isSelected -> "Selected"
                    selectionMode -> "Not selected"
                    else -> null
                }
            )
            bindSearchBadges(cell.searchSources)
            if (cell.item.mediaType == GalleryRepository.MediaType.Video) {
                binding.videoBadge.visibility = View.VISIBLE
                binding.videoBadge.findViewById<TextView>(R.id.durationText)?.text = formatDuration(cell.item.durationMillis)
            } else {
                binding.videoBadge.visibility = View.GONE
            }
            binding.root.setOnClickListener {
                when {
                    selectionMode -> onSelectionTap(cell.item.uri)
                    blurred -> onReveal(cell.item.uri)
                    else -> onPhotoClick(cell.item, binding.thumbnail)
                }
            }
            binding.root.setOnLongClickListener {
                onSelectionGestureStart(cell.item.uri)
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
        private val onSelectionTap: (Uri) -> Unit,
        private val onSelectionGestureStart: (Uri) -> Unit
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
                selectionFrame = binding.leadSelectionFrame,
                checkBadge = binding.leadCheckBadge,
                videoBadge = binding.leadVideoBadge,
                durationText = binding.leadDurationText,
                item = cell.items[0],
                overrideWidth = leadSize,
                overrideHeight = leadSize,
                selectionMode = selected.isNotEmpty(),
                isSelected = cell.items[0].uri in selected,
                loadImage = true,
                animate = false
            )
            bindTile(
                container = binding.topRightTile,
                thumbnail = binding.topRightThumbnail,
                dimScrim = binding.topRightDimScrim,
                selectionFrame = binding.topRightSelectionFrame,
                checkBadge = binding.topRightCheckBadge,
                videoBadge = binding.topRightVideoBadge,
                durationText = binding.topRightDurationText,
                item = cell.items[1],
                overrideWidth = regularSize,
                overrideHeight = regularSize,
                selectionMode = selected.isNotEmpty(),
                isSelected = cell.items[1].uri in selected,
                loadImage = true,
                animate = false
            )
            bindTile(
                container = binding.bottomRightTile,
                thumbnail = binding.bottomRightThumbnail,
                dimScrim = binding.bottomRightDimScrim,
                selectionFrame = binding.bottomRightSelectionFrame,
                checkBadge = binding.bottomRightCheckBadge,
                videoBadge = binding.bottomRightVideoBadge,
                durationText = binding.bottomRightDurationText,
                item = cell.items[2],
                overrideWidth = regularSize,
                overrideHeight = regularSize,
                selectionMode = selected.isNotEmpty(),
                isSelected = cell.items[2].uri in selected,
                loadImage = true,
                animate = false
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
                selectionFrame = binding.leadSelectionFrame,
                checkBadge = binding.leadCheckBadge,
                videoBadge = binding.leadVideoBadge,
                durationText = binding.leadDurationText,
                item = cell.items[0],
                overrideWidth = leadSize,
                overrideHeight = leadSize,
                selectionMode = selected.isNotEmpty(),
                isSelected = cell.items[0].uri in selected,
                loadImage = false,
                animate = true
            )
            bindTile(
                container = binding.topRightTile,
                thumbnail = binding.topRightThumbnail,
                dimScrim = binding.topRightDimScrim,
                selectionFrame = binding.topRightSelectionFrame,
                checkBadge = binding.topRightCheckBadge,
                videoBadge = binding.topRightVideoBadge,
                durationText = binding.topRightDurationText,
                item = cell.items[1],
                overrideWidth = regularSize,
                overrideHeight = regularSize,
                selectionMode = selected.isNotEmpty(),
                isSelected = cell.items[1].uri in selected,
                loadImage = false,
                animate = true
            )
            bindTile(
                container = binding.bottomRightTile,
                thumbnail = binding.bottomRightThumbnail,
                dimScrim = binding.bottomRightDimScrim,
                selectionFrame = binding.bottomRightSelectionFrame,
                checkBadge = binding.bottomRightCheckBadge,
                videoBadge = binding.bottomRightVideoBadge,
                durationText = binding.bottomRightDurationText,
                item = cell.items[2],
                overrideWidth = regularSize,
                overrideHeight = regularSize,
                selectionMode = selected.isNotEmpty(),
                isSelected = cell.items[2].uri in selected,
                loadImage = false,
                animate = true
            )
        }

        private fun bindTile(
            container: FrameLayout,
            thumbnail: ImageView,
            dimScrim: View,
            selectionFrame: View,
            checkBadge: View,
            videoBadge: LinearLayout,
            durationText: TextView,
            item: GalleryRepository.MediaItem,
            overrideWidth: Int,
            overrideHeight: Int,
            selectionMode: Boolean,
            isSelected: Boolean,
            loadImage: Boolean,
            animate: Boolean
        ) {
            ViewCompat.setTransitionName(thumbnail, "media_${item.uri}")
            bindSelectionVisual(dimScrim, selectionFrame, checkBadge, isSelected, animate)
            if (item.mediaType == GalleryRepository.MediaType.Video) {
                videoBadge.visibility = View.VISIBLE
                durationText.text = formatDuration(item.durationMillis)
            } else {
                videoBadge.visibility = View.GONE
            }

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
                if (selectionMode) onSelectionTap(item.uri) else onPhotoClick(item, thumbnail)
            }
            container.setOnLongClickListener {
                onSelectionGestureStart(item.uri)
                true
            }
        }

        fun uriAt(localX: Float, localY: Float, cell: GalleryCell.Collage): Uri? {
            return when {
                isPointInside(binding.leadTile, localX, localY) -> cell.items.getOrNull(0)?.uri
                isPointInside(binding.topRightTile, localX, localY) -> cell.items.getOrNull(1)?.uri
                isPointInside(binding.bottomRightTile, localX, localY) -> cell.items.getOrNull(2)?.uri
                else -> null
            }
        }

        private fun isPointInside(view: View, localX: Float, localY: Float): Boolean {
            return localX >= view.left && localX <= view.right && localY >= view.top && localY <= view.bottom
        }
    }

    class AlbumViewHolder(
        private val binding: ItemAlbumBinding,
        private val onAlbumClick: (GalleryRepository.Album) -> Unit,
        private val onAlbumLongClick: (GalleryRepository.Album, View) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(album: GalleryRepository.Album, showFolderSize: Boolean) {
            val metrics = binding.root.resources.displayMetrics
            val coverWidth = (metrics.widthPixels / 2).coerceAtLeast(160)
            val coverHeight = (coverWidth * 3) / 4
            binding.albumCoverContainer.layoutParams = binding.albumCoverContainer.layoutParams.apply {
                height = coverHeight
            }
            binding.albumName.text = album.name
            val countText = when {
                album.isSmart -> "CLIP search"
                album.count == 1 -> "1 item"
                else -> "${album.count} items"
            }
            val showSize = !album.isSmart && showFolderSize
            binding.albumCount.text = if (showSize && album.sizeBytes > 0L) {
                "$countText \u00b7 ${formatStorageSize(album.sizeBytes)}"
            } else {
                countText
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

        private fun formatStorageSize(bytes: Long): String {
            if (bytes <= 0L) return "0 KB"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var unit = 0
            while (value >= 1024.0 && unit < units.lastIndex) {
                value /= 1024.0
                unit++
            }
            return if (unit == 0) {
                "${bytes} B"
            } else {
                val pattern = if (value >= 10.0) "%.0f %s" else "%.1f %s"
                String.format(java.util.Locale.getDefault(), pattern, value, units[unit])
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
            binding.emptyIcon.setImageResource(cell.iconRes ?: R.drawable.ic_fluent_image_24_regular)
            binding.emptyHint.visibility = if (cell.hint != null) View.VISIBLE else View.GONE
            binding.emptyHint.text = cell.hint
            val action = cell.actionLabel
            binding.emptyAction.visibility = if (action != null) View.VISIBLE else View.GONE
            binding.emptyAction.text = action
            binding.emptyAction.setOnClickListener { cell.onAction?.invoke() }
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
        fun bind(node: FolderNode, showFolderSize: Boolean) {
            binding.folderName.text = node.name
            val media = when {
                node.imageCount > 0 && node.videoCount > 0 ->
                    "${node.imageCount} photos · ${node.videoCount} videos"
                node.imageCount == 1 -> "1 photo"
                node.imageCount > 1 -> "${node.imageCount} photos"
                node.videoCount == 1 -> "1 video"
                else -> "${node.videoCount} videos"
            }
            val folders = when (node.folderCount) {
                0 -> null
                1 -> "1 subfolder"
                else -> "${node.folderCount} subfolders"
            }
            val size = node.sizeBytes.takeIf { showFolderSize && it > 0L }?.let(::formatStorageSize)
            binding.folderCount.text = listOfNotNull(media, folders, size).joinToString(" · ")
            val indentDp = node.depth.coerceAtMost(MAX_VISIBLE_FOLDER_DEPTH) * FOLDER_INDENT_DP
            val indentPx = (indentDp * binding.root.resources.displayMetrics.density).toInt()
            binding.folderIndent.layoutParams = binding.folderIndent.layoutParams.apply {
                width = indentPx
            }

            if (node.coverUri != null) {
                binding.folderPlaceholderIcon.visibility = View.GONE
                Glide.with(binding.folderThumbnail)
                    .load(node.coverUri)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .centerCrop()
                    .placeholder(ColorDrawable(Color.rgb(17, 17, 17)))
                    .into(binding.folderThumbnail)
            } else {
                Glide.with(binding.folderThumbnail).clear(binding.folderThumbnail)
                binding.folderThumbnail.setImageDrawable(ColorDrawable(Color.rgb(17, 17, 17)))
                binding.folderPlaceholderIcon.visibility = View.VISIBLE
            }

            if (node.isLeaf) {
                binding.folderExpandIcon.visibility = View.GONE
                binding.folderExpandIcon.setOnClickListener(null)
                binding.root.setOnClickListener { onFolderClick(node) }
            } else {
                binding.folderExpandIcon.visibility = View.VISIBLE
                binding.folderExpandIcon.setImageResource(
                    if (node.expanded) R.drawable.ic_fluent_chevron_down_24_regular else R.drawable.ic_fluent_chevron_right_24_regular
                )
                binding.folderExpandIcon.contentDescription = binding.root.context.getString(
                    if (node.expanded) R.string.folder_collapse else R.string.folder_expand,
                    node.name
                )
                binding.root.setOnClickListener { onFolderClick(node) }
                binding.folderExpandIcon.setOnClickListener { onFolderExpandClick(node) }
            }
        }

        private fun formatStorageSize(bytes: Long): String {
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var unit = 0
            while (value >= 1024.0 && unit < units.lastIndex) {
                value /= 1024.0
                unit++
            }
            if (unit == 0) return "$bytes B"
            val pattern = if (value >= 10.0) "%.0f %s" else "%.1f %s"
            return String.format(java.util.Locale.getDefault(), pattern, value, units[unit])
        }
    }

    companion object {
        private const val FOLDER_INDENT_DP = 20
        private const val MAX_VISIBLE_FOLDER_DEPTH = 4
        private const val PayloadSelection = "payload_selection"
        const val ViewTypeHeader = 1
        const val ViewTypePhoto = 2
        const val ViewTypeCollage = 3
        const val ViewTypeAlbum = 4
        const val ViewTypeEmpty = 5
        const val ViewTypePinnedAlbumsHeader = 6
        const val ViewTypeFolder = 7
        const val ViewTypeSmartOnboarding = 8
        const val ViewTypeSortRow = 9

        private fun formatDuration(durationMs: Long): String {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%d:%02d", minutes, seconds)
        }
    }
}
