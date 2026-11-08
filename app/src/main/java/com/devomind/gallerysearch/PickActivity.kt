package com.devomind.gallerysearch

import android.content.ClipData
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.devomind.gallerysearch.databinding.ActivityPickBinding
import com.devomind.gallerysearch.databinding.ItemSafePhotoBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The picker another app lands in when it asks for a photo and the user chooses Pixa.
 *
 * Android offers this activity for [Intent.ACTION_GET_CONTENT], [Intent.ACTION_PICK] and
 * [Intent.ACTION_OPEN_DOCUMENT], so a website's file input lists Pixa beside the system "Files"
 * picker. Choosing it opens this screen, the photos are picked here, and Done hands them back to the
 * app that asked.
 *
 * That is deliberately the opposite of the Storage Access Framework route the app used to take. A
 * DOCUMENTS_PROVIDER root makes the app a *source* in the system picker's drawer and browses the
 * library in there, so a tap on "Pixa" showed Pixa's photos inside another app's file dialog instead
 * of opening Pixa at all. Only an activity another app can start can open one.
 */
class PickActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPickBinding
    private lateinit var adapter: PickAdapter
    private lateinit var gridLayoutManager: GridLayoutManager

    private val repository by lazy { GalleryRepository(applicationContext) }

    /** False when the caller wants exactly one photo, in which case a tap is the whole answer. */
    private val allowMultiple: Boolean
        get() = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.any { it.value } || StoragePermissions.hasPartialMediaAccess(this)) {
            loadPhotos()
        } else {
            // Nothing can be picked without read access, so the caller is answered now rather than
            // left watching a screen that will never fill.
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)

        // Exported, so anything can start it: only the three actions that mean "pick a photo here"
        // have a screen behind them.
        val action = intent.action
        if (action == null || action !in PICK_ACTIONS) {
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        binding = ActivityPickBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        adapter = PickAdapter(
            onTap = { photo -> onPhotoTapped(photo) },
            onSelectionChanged = { count -> renderSelection(count) }
        )
        gridLayoutManager = GridLayoutManager(this, gridColumns())
        binding.pickGrid.layoutManager = gridLayoutManager
        binding.pickGrid.adapter = adapter
        binding.pickGrid.setHasFixedSize(true)
        // Tiles are square, so a wider window gets more of them: the new width re-lays the grid, and
        // the configuration callback cannot do it because it runs before that width is measured.
        binding.pickGrid.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val width = right - left
            if (width > 0 && width != oldRight - oldLeft) {
                gridLayoutManager.spanCount = gridColumns()
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
        applyResponsiveChrome()

        binding.pickBackBtn.setOnClickListener { finish() }
        binding.pickSelectAllBtn.setOnClickListener { adapter.selectAll() }
        binding.pickDoneBtn.setOnClickListener { returnPhotos(adapter.selectedUris()) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })

        renderSelection(0)
        requestPhotos()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyResponsiveChrome()
    }

    private fun requestPhotos() {
        if (StoragePermissions.hasMediaAccess(this)) {
            loadPhotos()
        } else {
            permissionLauncher.launch(StoragePermissions.requiredMediaPermissions())
        }
    }

    private fun loadPhotos() {
        lifecycleScope.launch {
            val photos = withContext(Dispatchers.IO) {
                repository.getImageItemsForAlbumIds(emptySet())
            }
            adapter.submit(photos)
            binding.pickEmpty.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** One tap means "this one" when the caller asked for a single photo, and a toggle otherwise. */
    private fun onPhotoTapped(photo: GalleryRepository.MediaItem) {
        if (allowMultiple) adapter.toggle(photo.uri) else returnPhotos(listOf(photo.uri))
    }

    private fun renderSelection(count: Int) {
        binding.pickTitle.text = if (count > 0) {
            getString(R.string.album_pick_count, count)
        } else {
            getString(R.string.pick_title)
        }
        binding.pickDoneBtn.isEnabled = count > 0
        binding.pickDoneBtn.alpha = if (count > 0) 1f else 0.4f
    }

    /**
     * Hands the picked photos to whoever started this activity. The first one is also the result's
     * data URI and the rest travel in its clip data, which is the shape both the single and the
     * multiple case of GET_CONTENT / OPEN_DOCUMENT are read back in.
     */
    private fun returnPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val result = Intent().apply {
            setDataAndType(uris.first(), contentResolver.getType(uris.first()) ?: "image/*")
            clipData = ClipData.newUri(contentResolver, "Pixa", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // The flag on the result is what grants the caller read access; the explicit grant covers a
        // caller that only ever looks at the data URI, and is what the platform's own pickers do.
        val caller = callingActivity?.packageName ?: callingPackage
        if (caller != null) {
            uris.forEach {
                runCatching { grantUriPermission(caller, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun gridColumns(): Int = Responsive.gridColumns(this, GRID_COLUMNS)

    private fun tileSizePx(): Int =
        Responsive.tileSizePx(Responsive.widthOf(binding.pickGrid), gridColumns())

    private fun applyResponsiveChrome() {
        Responsive.applyTitleText(binding.pickTitle, this, HERO_TITLE_SP)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.pickRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.pickTopBar.updatePadding(top = bars.top + dp(8))
            binding.pickGrid.updatePadding(bottom = bars.bottom + dp(12))
            binding.pickBottomBar.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Reuses item_safe_photo (a plain thumbnail cell with its own selection scrim and check). The
     * selection keys on the photo's URI rather than its position, so a refresh cannot move it.
     */
    private inner class PickAdapter(
        private val onTap: (GalleryRepository.MediaItem) -> Unit,
        private val onSelectionChanged: (Int) -> Unit
    ) : RecyclerView.Adapter<PickAdapter.PhotoHolder>() {

        private val photos = mutableListOf<GalleryRepository.MediaItem>()
        private val selected = LinkedHashSet<Uri>()

        fun submit(list: List<GalleryRepository.MediaItem>) {
            photos.clear()
            photos.addAll(list)
            selected.retainAll(photos.mapTo(HashSet()) { it.uri })
            notifyDataSetChanged()
            onSelectionChanged(selected.size)
        }

        fun selectedUris(): List<Uri> = selected.toList()

        fun toggle(uri: Uri) {
            if (!selected.remove(uri)) selected.add(uri)
            val position = photos.indexOfFirst { it.uri == uri }
            if (position >= 0) notifyItemChanged(position)
            onSelectionChanged(selected.size)
        }

        fun selectAll() {
            if (photos.isEmpty()) return
            photos.forEach { selected.add(it.uri) }
            notifyDataSetChanged()
            onSelectionChanged(selected.size)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoHolder =
            PhotoHolder(ItemSafePhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: PhotoHolder, position: Int) {
            val photo = photos[position]
            // The cell's XML height is the portrait tile; the column count is not, so the side is set
            // from the grid here and a sideways window gets more squares, not taller ones.
            holder.binding.thumbnail.layoutParams = holder.binding.thumbnail.layoutParams.apply {
                height = tileSizePx()
            }
            Glide.with(holder.binding.thumbnail)
                .load(photo.uri)
                .centerCrop()
                .into(holder.binding.thumbnail)
            // Only stills are listed, so the cell's video badge stays as it is declared: gone.
            val isSelected = photo.uri in selected
            holder.binding.selectionScrim.visibility = if (isSelected) View.VISIBLE else View.GONE
            holder.binding.selectionCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
            holder.binding.root.setOnClickListener { onTap(photo) }
        }

        override fun getItemCount(): Int = photos.size

        inner class PhotoHolder(val binding: ItemSafePhotoBinding) : RecyclerView.ViewHolder(binding.root)
    }

    private companion object {
        // Portrait density of this screen, and the title size its layout declares.
        const val GRID_COLUMNS = 3
        const val HERO_TITLE_SP = 26f

        /** The actions that mean "let the user pick a photo in this app". */
        val PICK_ACTIONS = setOf(
            Intent.ACTION_GET_CONTENT,
            Intent.ACTION_PICK,
            Intent.ACTION_OPEN_DOCUMENT
        )
    }
}
