package com.devomind.gallerysearch

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.devomind.gallerysearch.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ImageAdapter
    private lateinit var favoritesStore: FavoritesStore
    private var imageEncoder: ImageEncoder? = null
    private var textEncoder: TextEncoder? = null
    private var repository: GalleryRepository? = null
    private var albums: List<GalleryRepository.Album> = emptyList()
    private var imageItems: List<GalleryRepository.MediaItem> = emptyList()
    private var collectionItems: List<GalleryRepository.MediaItem> = emptyList()
    private var videoItems: List<GalleryRepository.MediaItem> = emptyList()
    private var selectedAlbumIds: Set<String> = emptySet()
    private var allUris: List<Uri> = emptyList()
    private var currentAlbum: GalleryRepository.Album? = null
    private var currentMode = Mode.Browse
    private var activeSection = Section.Collection
    private var searchJob: Job? = null
    private var lastProgressRefresh = -1
    private var pendingDeleteUris: List<Uri> = emptyList()
    private var pendingDeleteNeedsRetry = false
    private var isBottomPanelVisible = true
    private var topInsetPx = 0

    private val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("EEE, d", Locale.getDefault())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.any { it.value }) {
            initializeCore()
        } else {
            Toast.makeText(this, "Storage permission required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val viewerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val changed = result.data?.getBooleanExtra(ViewerActivity.ExtraContentChanged, false) == true
        if (result.resultCode == RESULT_OK && changed) {
            refreshVisibleItems()
        }
    }

    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uris = pendingDeleteUris
        val needsRetry = pendingDeleteNeedsRetry
        pendingDeleteUris = emptyList()
        pendingDeleteNeedsRetry = false

        if (result.resultCode != RESULT_OK || uris.isEmpty()) return@registerForActivityResult
        if (needsRetry) {
            deleteUris(uris, afterApproval = true)
        } else {
            onDeleteCompleted(uris.size)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureEdgeToEdge()
        favoritesStore = FavoritesStore(this)

        adapter = ImageAdapter(
            onPhotoClick = ::openMedia,
            onSelectionChanged = ::renderSelectionState,
            onAlbumClick = ::openAlbum
        )

        val layoutManager = GridLayoutManager(this, GridSpanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = adapter.spanSizeAt(position, GridSpanCount)
        }

        binding.imageGrid.layoutManager = layoutManager
        binding.imageGrid.adapter = adapter
        binding.imageGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val alpha = if (rv.computeVerticalScrollOffset() > 32) 0.2f else 0.35f
                if (adapter.selectionCount == 0) {
                    binding.menuBtn.animate().alpha(alpha).setDuration(160).start()
                }
                if (dy > 8) hideBottomPanel() else if (dy < -8) showBottomPanel()
            }
        })

        bindChrome()
        bindBackNavigation()
        requestGalleryPermission()
        observeIndexWorker()
    }

    private fun bindChrome() {
        binding.menuBtn.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
        binding.searchLaunchBtn.setOnClickListener { openSearch() }
        binding.searchDismissBtn.setOnClickListener { closeSearch(clearQuery = true) }

        binding.drawerCollection.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            switchSection(Section.Collection)
        }
        binding.drawerAlbums.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            switchSection(Section.Albums)
        }
        binding.drawerSearch.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            openSearch()
        }
        binding.drawerIndex.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            enqueueBackgroundIndexing()
        }
        binding.drawerAlbumScope.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showAlbumSelector()
        }

        binding.bottomCollections.setOnClickListener { switchSection(Section.Collection) }
        binding.bottomVideos.setOnClickListener { switchSection(Section.Videos) }
        binding.bottomAlbums.setOnClickListener { switchSection(Section.Albums) }
        binding.bottomFavorites.setOnClickListener { switchSection(Section.Favorites) }

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch()
                true
            } else {
                false
            }
        }
        binding.searchInput.doAfterTextChanged {
            if (currentMode == Mode.Search && binding.searchPanel.visibility == View.VISIBLE) {
                submitSearch()
            }
        }
        binding.searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && currentMode != Mode.Search) {
                openSearch()
            }
        }

        binding.mainSurface.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN) {
                val nearMenu = event.x < 96f * resources.displayMetrics.density &&
                    event.y < topInsetPx + (116f * resources.displayMetrics.density)
                if (adapter.selectionCount == 0) {
                    binding.menuBtn.animate().alpha(if (nearMenu) 1f else 0.2f).setDuration(120).start()
                }
            }
            false
        }
        binding.shareSelectionBtn.setOnClickListener { shareSelected() }
        binding.deleteSelectionBtn.setOnClickListener { confirmDeleteSelected() }
    }

    private fun configureEdgeToEdge() {
        configureCutoutMode()
        hideStatusBar()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topInsetPx = systemInsets.top
            binding.topOverlay.updatePadding(top = systemInsets.top + dp(8))
            binding.drawerPanel.updatePadding(top = systemInsets.top + dp(28), bottom = systemInsets.bottom + dp(24))
            binding.imageGrid.updatePadding(bottom = systemInsets.bottom + dp(96))
            binding.bottomPanel.updatePadding(bottom = systemInsets.bottom)

            val selectionParams = binding.selectionPill.layoutParams as android.widget.FrameLayout.LayoutParams
            selectionParams.bottomMargin = systemInsets.bottom + dp(84)
            binding.selectionPill.layoutParams = selectionParams
            insets
        }
    }

    private fun configureCutoutMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val params = window.attributes
        params.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.attributes = params
    }

    private fun hideStatusBar() {
        WindowCompat.getInsetsController(window, binding.root)?.apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun bindBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.drawerLayout.isDrawerOpen(GravityCompat.START) -> binding.drawerLayout.closeDrawer(GravityCompat.START)
                    adapter.selectionCount > 0 -> adapter.clearSelection()
                    currentMode == Mode.Search && !binding.searchInput.text.isNullOrBlank() -> binding.searchInput.text?.clear()
                    currentMode == Mode.Search -> closeSearch(clearQuery = false)
                    currentMode == Mode.AlbumDetail -> {
                        currentAlbum = null
                        switchSection(Section.Albums)
                    }
                    else -> finish()
                }
            }
        })
    }

    private fun requestGalleryPermission() {
        val permissions = requiredPermissions()
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            initializeCore()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun requiredPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.POST_NOTIFICATIONS
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun initializeCore() {
        lifecycleScope.launch {
            setBusy("Loading AI models...")
            try {
                val result = withContext(Dispatchers.IO) {
                    var image: ImageEncoder? = null
                    var text: TextEncoder? = null
                    try {
                        image = ImageEncoder(applicationContext)
                        text = TextEncoder(applicationContext)
                        val repo = GalleryRepository(applicationContext, image, text)
                        val selectedIds = IndexPreferences.loadSelectedAlbums(applicationContext)
                        val snapshot = loadLibrarySnapshot(repo, selectedIds)
                        InitResult(image, text, repo, snapshot)
                    } catch (error: Throwable) {
                        image?.close()
                        text?.close()
                        throw error
                    }
                }

                imageEncoder = result.imageEncoder
                textEncoder = result.textEncoder
                repository = result.repository
                applyLibrarySnapshot(result.snapshot)
                currentAlbum = null
                lastProgressRefresh = -1
                binding.progressBar.visibility = View.GONE
                binding.statusText.text = selectionSummaryText(albums, selectedAlbumIds, result.repository.indexedCount)
                renderCurrentState()
                maybeStartBackgroundIndexing()
            } catch (error: Throwable) {
                binding.progressBar.visibility = View.GONE
                showFatalError(error)
            }
        }
    }

    private suspend fun loadLibrarySnapshot(
        repo: GalleryRepository,
        requestedSelection: Set<String>
    ): LibrarySnapshot {
        val refreshedAlbums = repo.getAlbums()
        val effectiveSelection = requestedSelection.intersect(refreshedAlbums.map { it.id }.toSet())
        val refreshedImages = repo.getImageItemsForAlbumIds(effectiveSelection)
        val refreshedCollection = repo.getAllMediaItemsForAlbumIds(effectiveSelection)
        val refreshedVideos = repo.getVideoItemsForAlbumIds(effectiveSelection)
        repo.loadCachedIndexForUris(refreshedImages.map { it.uri })
        return LibrarySnapshot(
            albums = refreshedAlbums,
            imageItems = refreshedImages,
            collectionItems = refreshedCollection,
            videoItems = refreshedVideos,
            selectedAlbumIds = effectiveSelection
        )
    }

    private fun applyLibrarySnapshot(snapshot: LibrarySnapshot) {
        albums = snapshot.albums
        imageItems = snapshot.imageItems
        collectionItems = snapshot.collectionItems
        videoItems = snapshot.videoItems
        selectedAlbumIds = snapshot.selectedAlbumIds
        allUris = snapshot.imageItems.map { it.uri }
        IndexPreferences.saveSelectedAlbums(this, snapshot.selectedAlbumIds)
    }

    private val favoriteItems: List<GalleryRepository.MediaItem>
        get() {
            val favoriteKeys = favoritesStore.all()
            return collectionItems.filter { it.uri.toString() in favoriteKeys }
        }

    private val albumDetailItems: List<GalleryRepository.MediaItem>
        get() = currentAlbum?.let { album -> collectionItems.filter { it.bucketId == album.id } }.orEmpty()

    private fun currentSearchItems(): List<GalleryRepository.MediaItem> {
        return when {
            currentAlbum != null -> albumDetailItems
            activeSection == Section.Collection -> collectionItems
            activeSection == Section.Videos -> videoItems
            activeSection == Section.Favorites -> favoriteItems
            else -> collectionItems
        }
    }

    private fun switchSection(section: Section) {
        activeSection = section
        currentAlbum = null
        adapter.clearSelection()
        if (currentMode == Mode.Search) {
            updateSearchMetaText()
            submitSearch()
        } else {
            renderCurrentSection()
        }
    }

    private fun renderCurrentState() {
        when (currentMode) {
            Mode.Browse -> renderCurrentSection()
            Mode.AlbumDetail -> currentAlbum?.let(::renderAlbumDetail) ?: renderCurrentSection()
            Mode.Search -> openSearch()
        }
    }

    private fun renderCurrentSection() {
        when (activeSection) {
            Section.Collection -> renderMediaSection(title = null, items = collectionItems, emptyText = "No media yet")
            Section.Videos -> renderMediaSection(title = "videos", items = videoItems, emptyText = "No videos yet")
            Section.Albums -> renderAlbums()
            Section.Favorites -> renderMediaSection(title = "favorites", items = favoriteItems, emptyText = "No favorites yet")
        }
    }

    private fun renderMediaSection(
        title: String?,
        items: List<GalleryRepository.MediaItem>,
        emptyText: String
    ) {
        currentMode = Mode.Browse
        binding.searchPanel.visibility = View.GONE
        binding.resultCount.text = ""
        adapter.updateCells(buildTimelineCells(items, emptyText))
        updateTopBarForMode(title)
        updateDrawerState()
        updateBottomPanelState()
        showBottomPanel()
    }

    private fun renderAlbums() {
        currentMode = Mode.Browse
        binding.searchPanel.visibility = View.GONE
        binding.resultCount.text = ""
        adapter.updateCells(
            if (albums.isEmpty()) listOf(GalleryCell.Empty("No albums yet"))
            else albums.map { GalleryCell.AlbumCell(it) }
        )
        updateTopBarForMode("albums")
        updateDrawerState()
        updateBottomPanelState()
        showBottomPanel()
    }

    private fun renderAlbumDetail(album: GalleryRepository.Album) {
        currentMode = Mode.AlbumDetail
        currentAlbum = album
        binding.searchPanel.visibility = View.GONE
        val items = albumDetailItems
        binding.resultCount.text = if (items.isEmpty()) "" else if (items.size == 1) "1 item" else "${items.size} items"
        adapter.updateCells(buildTimelineCells(items, "No media in this album"))
        updateTopBarForMode(album.name.lowercase(Locale.getDefault()))
        updateDrawerState()
        updateBottomPanelState()
        showBottomPanel()
    }

    private fun openSearch() {
        currentMode = Mode.Search
        binding.searchPanel.visibility = View.VISIBLE
        binding.screenTitle.visibility = View.VISIBLE
        binding.screenTitle.text = "search"
        binding.resultCount.text = ""
        updateSearchMetaText()
        updateTopBarForMode("search")
        updateDrawerState()
        updateBottomPanelState()
        if (binding.searchInput.text.isNullOrBlank()) {
            adapter.updateCells(listOf(GalleryCell.Empty(searchPlaceholderText())))
        } else {
            submitSearch()
        }
    }

    private fun closeSearch(clearQuery: Boolean) {
        binding.searchPanel.visibility = View.GONE
        if (clearQuery) {
            binding.searchInput.text?.clear()
        }
        binding.searchInput.clearFocus()
        currentMode = if (currentAlbum != null) Mode.AlbumDetail else Mode.Browse
        renderCurrentState()
    }

    private fun updateSearchMetaText() {
        binding.searchMetaText.text = when {
            currentAlbum != null -> "Semantic + metadata search inside ${currentAlbum?.name?.lowercase(Locale.getDefault())}"
            activeSection == Section.Albums -> "Live album search in the current gallery scope"
            activeSection == Section.Videos -> "Metadata search across videos in the current gallery scope"
            activeSection == Section.Favorites -> "Semantic + metadata search across your favorites"
            else -> "Semantic + metadata search across the current gallery scope"
        }
    }

    private fun searchPlaceholderText(): String {
        return when {
            currentAlbum != null -> "Search this album"
            activeSection == Section.Albums -> "Search albums"
            activeSection == Section.Videos -> "Search videos"
            activeSection == Section.Favorites -> "Search favorites"
            else -> "Search your gallery"
        }
    }

    private fun submitSearch() {
        val query = binding.searchInput.text?.toString()?.trim().orEmpty()
        val repo = repository ?: return
        searchJob?.cancel()

        if (query.isBlank()) {
            adapter.updateCells(listOf(GalleryCell.Empty(searchPlaceholderText())))
            binding.resultCount.text = ""
            binding.statusText.text = selectionSummaryText(albums, selectedAlbumIds, repo.indexedCount)
            return
        }

        currentMode = Mode.Search
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.text = "Searching..."

        searchJob = lifecycleScope.launch {
            try {
                val cells = withContext(Dispatchers.IO) {
                    if (currentAlbum == null && activeSection == Section.Albums) {
                        buildAlbumSearchCells(query)
                    } else {
                        val baseItems = currentSearchItems()
                        val semanticResults = if (activeSection == Section.Videos) emptyList() else repo.search(query)
                        buildMediaSearchCells(query, baseItems, semanticResults)
                    }
                }
                adapter.updateCells(if (cells.isEmpty()) listOf(GalleryCell.Empty("No matching results")) else cells)
                binding.resultCount.text = when {
                    cells.isEmpty() -> "No results found"
                    cells.size == 1 -> "1 result"
                    else -> "${cells.size} results"
                }
                binding.statusText.text = selectionSummaryText(albums, selectedAlbumIds, repo.indexedCount)
            } catch (_: CancellationException) {
                // Live search cancels the previous job on each new query; treat that as expected.
            } catch (error: Throwable) {
                showFatalError(error)
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun buildTimelineCells(
        items: List<GalleryRepository.MediaItem>,
        emptyText: String
    ): List<GalleryCell> {
        if (items.isEmpty()) return listOf(GalleryCell.Empty(emptyText))
        val cells = ArrayList<GalleryCell>()
        items.groupBy { monthFormat.format(Date(it.dateMillis)) }.forEach { (month, monthItems) ->
            val first = monthItems.first()
            cells += GalleryCell.Header(month, dayFormat.format(Date(first.dateMillis)).uppercase(Locale.getDefault()))
            monthItems.groupBy { dayFormat.format(Date(it.dateMillis)) }.values.forEach { dayItems ->
                dayItems.forEachIndexed { index, item ->
                    cells += GalleryCell.Photo(item, featured = index == 0)
                }
            }
        }
        return cells
    }

    private fun buildAlbumSearchCells(query: String): List<GalleryCell> {
        val normalized = query.trim().lowercase(Locale.getDefault())
        return albums
            .filter { album ->
                album.name.lowercase(Locale.getDefault()).contains(normalized) ||
                    album.count.toString().contains(normalized)
            }
            .map { GalleryCell.AlbumCell(it) }
    }

    private fun buildMediaSearchCells(
        query: String,
        baseItems: List<GalleryRepository.MediaItem>,
        semanticResults: List<Uri>
    ): List<GalleryCell> {
        val byUri = baseItems.associateBy { it.uri }
        val ordered = LinkedHashSet<Uri>()
        semanticResults.forEach { uri ->
            val item = byUri[uri] ?: return@forEach
            if (item.mediaType == GalleryRepository.MediaType.Image) {
                ordered += uri
            }
        }

        val normalized = query.trim().lowercase(Locale.getDefault())
        baseItems.asSequence()
            .filter { item ->
                val dateText = monthFormat.format(Date(item.dateMillis)).lowercase(Locale.getDefault())
                val dayText = dayFormat.format(Date(item.dateMillis)).lowercase(Locale.getDefault())
                val typeText = if (item.mediaType == GalleryRepository.MediaType.Video) "video" else "photo"
                listOfNotNull(
                    item.displayName,
                    item.bucketName,
                    item.mimeType,
                    dateText,
                    dayText,
                    typeText
                ).any { it.lowercase(Locale.getDefault()).contains(normalized) }
            }
            .take(80)
            .forEach { ordered += it.uri }

        return ordered.mapNotNull { uri -> byUri[uri]?.let { GalleryCell.Photo(it, featured = false) } }
    }

    private fun setBusy(message: String) {
        binding.statusText.text = message
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun openAlbum(album: GalleryRepository.Album) {
        renderAlbumDetail(album)
    }

    private fun openMedia(item: GalleryRepository.MediaItem) {
        if (item.mediaType == GalleryRepository.MediaType.Video) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(item.uri, item.mimeType ?: "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Play video"))
        } else {
            viewerLauncher.launch(Intent(this, ViewerActivity::class.java).setData(item.uri))
        }
    }

    private fun renderSelectionState(count: Int) {
        binding.selectionPill.visibility = if (count > 0) View.VISIBLE else View.GONE
        if (count > 0) {
            binding.screenTitle.visibility = View.VISIBLE
            binding.screenTitle.text = "$count selected"
            binding.menuBtn.setImageResource(R.drawable.ic_fluent_back_24_regular)
            binding.menuBtn.alpha = 1f
            binding.menuBtn.setOnClickListener { adapter.clearSelection() }
            binding.searchLaunchBtn.visibility = View.GONE
        } else {
            binding.searchLaunchBtn.visibility = View.VISIBLE
            updateTopBarForMode(
                when {
                    currentMode == Mode.Search -> "search"
                    currentMode == Mode.AlbumDetail -> currentAlbum?.name?.lowercase(Locale.getDefault())
                    activeSection == Section.Collection -> null
                    activeSection == Section.Videos -> "videos"
                    activeSection == Section.Albums -> "albums"
                    activeSection == Section.Favorites -> "favorites"
                    else -> null
                }
            )
        }
    }

    private fun shareSelected() {
        val selected = adapter.selectedUris()
        if (selected.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(selected))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share items"))
    }

    private fun confirmDeleteSelected() {
        val selected = adapter.selectedUris()
        if (selected.isEmpty()) return
        val message = if (selected.size == 1) {
            "Delete the selected item from this device?"
        } else {
            "Delete ${selected.size} selected items from this device?"
        }
        AlertDialog.Builder(this)
            .setTitle("Delete items?")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ -> deleteUris(selected) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAlbumSelector() {
        if (albums.isEmpty()) {
            Toast.makeText(this, "No albums found on device.", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = albums.map { "${it.name} (${it.count})" }.toTypedArray()
        val checked = albums.map { it.id in selectedAlbumIds }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Album scope")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNeutralButton("All") { _, _ ->
                selectedAlbumIds = emptySet()
                IndexPreferences.saveSelectedAlbums(this, selectedAlbumIds)
                refreshVisibleItems()
            }
            .setPositiveButton("Apply") { _, _ ->
                selectedAlbumIds = albums.filterIndexed { index, _ -> checked[index] }.map { it.id }.toSet()
                IndexPreferences.saveSelectedAlbums(this, selectedAlbumIds)
                refreshVisibleItems()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshVisibleItems() {
        val repo = repository ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val snapshot = loadLibrarySnapshot(repo, selectedAlbumIds)
            withContext(Dispatchers.Main) {
                applyLibrarySnapshot(snapshot)
                currentAlbum = currentAlbum?.let { current -> albums.firstOrNull { it.id == current.id } }
                binding.statusText.text = selectionSummaryText(albums, selectedAlbumIds, repo.indexedCount)
                if (currentMode == Mode.Search) {
                    updateSearchMetaText()
                    submitSearch()
                } else if (currentMode == Mode.AlbumDetail && currentAlbum != null) {
                    renderAlbumDetail(currentAlbum!!)
                } else {
                    renderCurrentSection()
                }
                maybeStartBackgroundIndexing()
            }
        }
    }

    private fun deleteUris(uris: List<Uri>, afterApproval: Boolean = false) {
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && uris.size > 1 && !afterApproval) {
            Toast.makeText(this, "Bulk delete requires Android 11 or newer.", Toast.LENGTH_LONG).show()
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !afterApproval) {
                pendingDeleteUris = uris
                pendingDeleteNeedsRetry = false
                val request = MediaStore.createDeleteRequest(contentResolver, uris)
                deleteRequestLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                return
            }

            uris.forEach { contentResolver.delete(it, null, null) }
            onDeleteCompleted(uris.size)
        } catch (error: Throwable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && error is RecoverableSecurityException && !afterApproval) {
                pendingDeleteUris = uris
                pendingDeleteNeedsRetry = true
                launchDeleteConsent(error.userAction.actionIntent.intentSender)
            } else {
                Toast.makeText(this, "Delete failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchDeleteConsent(intentSender: IntentSender) {
        deleteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
    }

    private fun onDeleteCompleted(requestedCount: Int) {
        adapter.clearSelection()
        refreshVisibleItems()
        val label = if (requestedCount == 1) "Item deleted." else "$requestedCount items deleted."
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
    }

    private fun enqueueBackgroundIndexing(showToast: Boolean = true) {
        val payload = Data.Builder()
            .putStringArray(IndexWorker.SelectedAlbumIdsKey, selectedAlbumIds.toTypedArray())
            .build()

        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            .setInputData(payload)
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            IndexWorkName,
            ExistingWorkPolicy.KEEP,
            request
        )
        if (showToast) {
            Toast.makeText(this, "Indexing started.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeIndexWorker() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(IndexWorkName)
            .observe(this) { infos ->
                val work = infos.firstOrNull() ?: return@observe
                when (work.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.statusText.text = "Index job queued"
                    }
                    WorkInfo.State.RUNNING -> {
                        val current = work.progress.getInt(IndexWorker.ProgressCurrentKey, 0)
                        val total = work.progress.getInt(IndexWorker.ProgressTotalKey, 0)
                        binding.progressBar.visibility = View.VISIBLE
                        binding.statusText.text = "Indexing: $current / $total"
                        maybeRefreshLiveIndex(current)
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        binding.progressBar.visibility = View.GONE
                        refreshVisibleItems()
                        Toast.makeText(this, "Indexing complete.", Toast.LENGTH_SHORT).show()
                    }
                    WorkInfo.State.FAILED -> {
                        binding.progressBar.visibility = View.GONE
                        binding.statusText.text = "Indexing failed"
                    }
                    WorkInfo.State.CANCELLED -> {
                        binding.progressBar.visibility = View.GONE
                        binding.statusText.text = "Indexing cancelled"
                    }
                }
            }
    }

    private fun selectionSummaryText(
        albums: List<GalleryRepository.Album>,
        selectedIds: Set<String>,
        indexedCount: Int
    ): String {
        val albumText = if (selectedIds.isEmpty()) {
            "all albums"
        } else {
            val selectedCount = albums.count { it.id in selectedIds }
            "$selectedCount albums"
        }
        return "$indexedCount indexed · $albumText"
    }

    private fun maybeRefreshLiveIndex(current: Int) {
        val shouldRefresh = current > 0 && (current % 20 == 0 || current == 1) && current != lastProgressRefresh
        if (!shouldRefresh) return
        lastProgressRefresh = current

        val query = binding.searchInput.text?.toString()?.trim().orEmpty()
        val repo = repository ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            repo.loadCachedIndexForUris(allUris)
            withContext(Dispatchers.Main) {
                binding.statusText.text = "Indexing: $current · live search ready"
                if (query.isNotBlank() && currentMode == Mode.Search) {
                    submitSearch()
                }
            }
        }
    }

    private fun maybeStartBackgroundIndexing() {
        val repo = repository ?: return
        if (allUris.isEmpty()) return
        if (repo.indexedCount >= allUris.size) return
        enqueueBackgroundIndexing(showToast = false)
        binding.statusText.text =
            "Background indexing queued · ${selectionSummaryText(albums, selectedAlbumIds, repo.indexedCount)}"
    }

    private fun updateTopBarForMode(title: String?) {
        if (currentMode == Mode.AlbumDetail) {
            binding.menuBtn.setImageResource(R.drawable.ic_fluent_back_24_regular)
            binding.menuBtn.alpha = 1f
            binding.menuBtn.setOnClickListener {
                currentAlbum = null
                switchSection(Section.Albums)
            }
        } else {
            binding.menuBtn.setImageResource(R.drawable.ic_fluent_navigation_24_regular)
            binding.menuBtn.alpha = if (title == null) 0.2f else 1f
            binding.menuBtn.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
        }

        binding.screenTitle.visibility = if (title == null) View.GONE else View.VISIBLE
        binding.screenTitle.text = title.orEmpty()
        binding.searchLaunchBtn.visibility = if (adapter.selectionCount > 0) View.GONE else View.VISIBLE
    }

    private fun updateDrawerState() {
        val inactive = Color.rgb(10, 10, 10)
        val active = Color.rgb(17, 17, 17)
        binding.drawerCollection.setBackgroundColor(
            if (currentMode != Mode.Search && activeSection == Section.Collection) active else inactive
        )
        binding.drawerAlbums.setBackgroundColor(
            if ((currentMode != Mode.Search && activeSection == Section.Albums) || currentMode == Mode.AlbumDetail) active else inactive
        )
        binding.drawerSearch.setBackgroundColor(if (currentMode == Mode.Search) active else inactive)
    }

    private fun updateBottomPanelState() {
        updateBottomTab(binding.bottomCollections, activeSection == Section.Collection)
        updateBottomTab(binding.bottomVideos, activeSection == Section.Videos)
        updateBottomTab(binding.bottomAlbums, activeSection == Section.Albums)
        updateBottomTab(binding.bottomFavorites, activeSection == Section.Favorites)
    }

    private fun updateBottomTab(tab: View, active: Boolean) {
        tab.alpha = if (active) 1f else 0.58f
    }

    private fun hideBottomPanel() {
        if (!isBottomPanelVisible || adapter.selectionCount > 0) return
        isBottomPanelVisible = false
        binding.bottomPanel.animate().translationY(binding.bottomPanel.height.toFloat()).setDuration(180).start()
    }

    private fun showBottomPanel() {
        if (isBottomPanelVisible) return
        isBottomPanelVisible = true
        binding.bottomPanel.animate().translationY(0f).setDuration(180).start()
    }

    private fun showFatalError(error: Throwable) {
        AlertDialog.Builder(this)
            .setTitle("Gallery Search Error")
            .setMessage(error.stackTraceToString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onDestroy() {
        super.onDestroy()
        searchJob?.cancel()
        imageEncoder?.close()
        textEncoder?.close()
    }

    private data class InitResult(
        val imageEncoder: ImageEncoder,
        val textEncoder: TextEncoder,
        val repository: GalleryRepository,
        val snapshot: LibrarySnapshot
    )

    private data class LibrarySnapshot(
        val albums: List<GalleryRepository.Album>,
        val imageItems: List<GalleryRepository.MediaItem>,
        val collectionItems: List<GalleryRepository.MediaItem>,
        val videoItems: List<GalleryRepository.MediaItem>,
        val selectedAlbumIds: Set<String>
    )

    private enum class Mode {
        Browse,
        Search,
        AlbumDetail
    }

    private enum class Section {
        Collection,
        Videos,
        Albums,
        Favorites
    }

    companion object {
        private const val IndexWorkName = "gallery_background_index"
        private const val GridSpanCount = 6
    }
}
