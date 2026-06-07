package com.devomind.gallerysearch

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityOptionsCompat
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
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.devomind.gallerysearch.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
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
    private var searchMode = SearchMode.Hybrid
    private var searchJob: Job? = null
    private var searchDebounceJob: Job? = null
    private var renderJob: Job? = null
    private var lastProgressRefresh = -1
    private var pendingDeleteUris: List<Uri> = emptyList()
    private var pendingDeleteNeedsRetry = false
    private var topInsetPx = 0

    private val monthFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    }
    private val dayFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("EEE, d", Locale.getDefault())
    }

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
        // #region debug-point A:activity-create
        debugLog("A", "MainActivity created", mapOf("savedState" to (savedInstanceState != null)))
        // #endregion
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureEdgeToEdge()
        favoritesStore = FavoritesStore(this)

        adapter = ImageAdapter(
            onPhotoClick = { item, view -> openMedia(item, view) },
            onSelectionChanged = ::renderSelectionState,
            onAlbumClick = ::openAlbum
        )

        val layoutManager = GridLayoutManager(this, DesignTokens.GRID_SPAN_COUNT)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = adapter.spanSizeAt(position, DesignTokens.GRID_SPAN_COUNT)
        }

        binding.imageGrid.layoutManager = layoutManager
        binding.imageGrid.adapter = adapter
        binding.imageGrid.addItemDecoration(StickyHeaderDecoration(adapter))
        binding.fastScrollIndicator.attach(binding.imageGrid, adapter)

        binding.imageGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val alpha = if (rv.computeVerticalScrollOffset() > 32) {
                    DesignTokens.SCROLLED_HEADER_ALPHA
                } else {
                    DesignTokens.HEADER_ALPHA
                }
                if (adapter.selectionCount == 0) {
                    binding.menuBtn.animate().alpha(alpha).setDuration(160).start()
                }
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
        binding.searchModeHybrid.setOnClickListener { setSearchMode(SearchMode.Hybrid) }
        binding.searchModeAi.setOnClickListener { setSearchMode(SearchMode.AiOnly) }
        binding.searchModeMetadata.setOnClickListener { setSearchMode(SearchMode.MetadataOnly) }

        binding.drawerCollection.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            navigateToSection(Section.Collection)
        }
        binding.drawerAlbums.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            navigateToSection(Section.Albums)
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

        binding.bottomCollections.setOnClickListener { navigateToSection(Section.Collection) }
        binding.bottomAlbums.setOnClickListener { navigateToSection(Section.Albums) }
        binding.bottomFavorites.setOnClickListener { navigateToSection(Section.Favorites) }
        binding.bottomVideos.setOnClickListener { navigateToSection(Section.Videos) }

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchDebounceJob?.cancel()
                submitSearch()
                true
            } else {
                false
            }
        }
        binding.searchInput.doAfterTextChanged {
            updateSearchPillState()
            if (currentMode == Mode.Search && binding.searchPanel.visibility == View.VISIBLE) {
                searchDebounceJob?.cancel()
                searchDebounceJob = lifecycleScope.launch {
                    delay(DesignTokens.SEARCH_INPUT_DEBOUNCE_MS)
                    if (currentMode == Mode.Search && binding.searchPanel.visibility == View.VISIBLE) {
                        submitSearch()
                    }
                }
            }
        }
        binding.searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && currentMode != Mode.Search) {
                openSearch()
            }
        }

        binding.mainSurface.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN) {
                val nearMenu = event.x < DesignTokens.MENU_NEAR_X_DP * resources.displayMetrics.density &&
                    event.y < topInsetPx + (DesignTokens.MENU_NEAR_Y_DP * resources.displayMetrics.density)
                if (adapter.selectionCount == 0) {
                    val alpha = if (nearMenu) 1f else DesignTokens.SCROLLED_HEADER_ALPHA
                    binding.menuBtn.animate()
                        .alpha(alpha)
                        .setDuration(DesignTokens.MENU_NEAR_FADE_DURATION_MS)
                        .start()
                }
            }
            false
        }
        binding.selectAllBtn.setOnClickListener { adapter.selectAll() }
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
            binding.imageGrid.updatePadding(bottom = systemInsets.bottom + dp(84))
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
                        navigateToSection(Section.Albums)
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
                Manifest.permission.READ_MEDIA_VIDEO
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun initializeCore() {
        // -------------------- TRACK A (UI critical path) --------------
        lifecycleScope.launch {
            setBusy("Loading gallery…")
            try {
                val selectedIds = withContext(Dispatchers.IO) {
                    IndexPreferences.loadSelectedAlbums(applicationContext)
                }
                val repo = GalleryRepository(applicationContext)
                repository = repo
                val snapshot = withContext(Dispatchers.IO) {
                    loadLibrarySnapshot(repo, selectedIds)
                }
                applyLibrarySnapshot(snapshot)
                currentAlbum = null
                lastProgressRefresh = -1
                binding.progressBar.visibility = View.GONE
                binding.statusText.text =
                    selectionSummaryText(albums, selectedAlbumIds, repo.indexedCount)
                renderCurrentState()
                primeMetadataIndexAsync()
            } catch (error: Throwable) {
                binding.progressBar.visibility = View.GONE
                showFatalError(error)
                return@launch
            }

            // -------------------- TRACK B (model warm-up) -------------
            loadEncodersInBackground()

            // -------------------- TRACK C (index after first render) ----
            binding.root.post {
                enqueueBackgroundIndexingIfPossible(showToast = false)
            }
        }
    }

    private fun loadEncodersInBackground() {
        lifecycleScope.launch {
            // #region debug-point B:encoder-load-start
            debugLog("B", "Encoder warm-up started", mapOf("mode" to currentMode.name, "section" to activeSection.name))
            // #endregion
            val sharedEncoders = (application as GallerySearchApp).sharedEncoders
            val encoders = withContext(Dispatchers.IO) {
                val imageAsync = async { runCatching { sharedEncoders.getImageEncoder() }.getOrNull() }
                val textAsync = async { runCatching { sharedEncoders.getTextEncoder() }.getOrNull() }
                imageAsync.await() to textAsync.await()
            }
            val image = encoders.first
            val text = encoders.second
            if (image == null) {
                Log.w(TAG, "Vision encoder failed to load; semantic search disabled.")
                return@launch
            }
            // #region debug-point B:encoder-load-finish
            debugLog("B", "Encoder warm-up finished", mapOf("hasImage" to true, "hasText" to (text != null)))
            // #endregion
            imageEncoder = image
            textEncoder = text
            repository?.attachEncoders(image, text)
            if (currentMode == Mode.Search && !binding.searchInput.text.isNullOrBlank()) {
                submitSearch()
            }
            withContext(Dispatchers.IO) {
                repository?.loadCachedIndexForUris(allUris)
                repository?.loadCachedMetadataIndexForUris(allUris)
            }
            maybeStartBackgroundIndexing()
        }
    }

    private fun enqueueBackgroundIndexingIfPossible(showToast: Boolean) {
        val savedSelection = IndexPreferences.loadSelectedAlbums(applicationContext)
        val payload = Data.Builder()
            .putStringArray(IndexWorker.SelectedAlbumIdsKey, savedSelection.toTypedArray())
            .build()
        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            .setInputData(payload)
            .setBackoffCriteria(BackoffPolicy.LINEAR, DesignTokens.INDEX_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            INDEX_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
        if (showToast) Toast.makeText(this, "Indexing started.", Toast.LENGTH_SHORT).show()
    }

    private suspend fun loadLibrarySnapshot(
        repo: GalleryRepository,
        requestedSelection: Set<String>
    ): LibrarySnapshot {
        val fullSnapshot = repo.loadSnapshot(emptySet())
        val refreshedAlbums = fullSnapshot.albums
        val effectiveSelection = requestedSelection.intersect(refreshedAlbums.map { it.id }.toSet())

        val imageItems = if (effectiveSelection.isEmpty()) {
            fullSnapshot.imageItems
        } else {
            fullSnapshot.imageItems.filter { it.bucketId in effectiveSelection }
        }
        val videoItems = if (effectiveSelection.isEmpty()) {
            fullSnapshot.videoItems
        } else {
            fullSnapshot.videoItems.filter { it.bucketId in effectiveSelection }
        }
        val collectionItems = (imageItems + videoItems).sortedByDescending { it.dateMillis }

        return LibrarySnapshot(
            albums = refreshedAlbums,
            imageItems = imageItems,
            collectionItems = collectionItems,
            videoItems = videoItems,
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

    private fun currentSearchPhotoItems(): List<GalleryRepository.MediaItem> {
        return when {
            currentAlbum != null -> albumDetailItems.filter { it.mediaType == GalleryRepository.MediaType.Image }
            activeSection == Section.Collection -> imageItems
            activeSection == Section.Favorites -> favoriteItems.filter { it.mediaType == GalleryRepository.MediaType.Image }
            activeSection == Section.Albums -> imageItems
            else -> emptyList()
        }
    }

    private fun switchSection(section: Section) {
        // #region debug-point C:section-switch
        debugLog(
            "C",
            "Section switch requested",
            mapOf(
                "fromSection" to activeSection.name,
                "toSection" to section.name,
                "mode" to currentMode.name,
                "albumId" to currentAlbum?.id
            )
        )
        // #endregion
        searchJob?.cancel()
        searchDebounceJob?.cancel()
        renderJob?.cancel()
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

    private fun navigateToSection(section: Section) {
        if (currentMode == Mode.Search) {
            closeSearch(clearQuery = false)
        }
        switchSection(section)
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
            Section.Collection -> renderMediaSection(title = "collections", items = collectionItems, emptyText = "No media yet")
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
        val renderStartMs = SystemClock.elapsedRealtime()
        renderJob?.cancel()
        currentMode = Mode.Browse
        binding.searchPanel.visibility = View.GONE
        binding.resultCount.text = ""
        updateTopBarForMode(title)
        updateDrawerState()
        updateBottomPanelState()
        showBottomPanel()

        val expectedSection = activeSection
        val cappedItems = items.take(DesignTokens.DISPLAY_CAP)
        renderJob = lifecycleScope.launch {
            val cells = withContext(Dispatchers.Default) {
                buildTimelineCells(cappedItems, emptyText)
            }
            if (currentMode == Mode.Browse && currentAlbum == null && activeSection == expectedSection) {
                adapter.replaceCells(cells)
                resetGridToTop()
                updateFastScrollVisibility()
                binding.fastScrollIndicator.syncToRecyclerView()
                // #region debug-point D:render-finish
                debugLog(
                    "D",
                    "Media section rendered",
                    mapOf(
                        "section" to expectedSection.name,
                        "items" to cappedItems.size,
                        "cells" to cells.size,
                        "durationMs" to (SystemClock.elapsedRealtime() - renderStartMs)
                    )
                )
                // #endregion
            }
        }
    }

    private fun renderAlbums() {
        currentMode = Mode.Browse
        binding.searchPanel.visibility = View.GONE
        binding.resultCount.text = ""
        adapter.replaceCells(
            if (albums.isEmpty()) listOf(GalleryCell.Empty("No albums yet"))
            else albums.map { GalleryCell.AlbumCell(it) }
        )
        resetGridToTop()
        updateFastScrollVisibility()
        updateTopBarForMode("albums")
        updateDrawerState()
        updateBottomPanelState()
        showBottomPanel()
    }

    private fun renderAlbumDetail(album: GalleryRepository.Album) {
        renderJob?.cancel()
        currentMode = Mode.AlbumDetail
        currentAlbum = album
        binding.searchPanel.visibility = View.GONE
        val items = albumDetailItems
        binding.resultCount.text = if (items.isEmpty()) "" else if (items.size == 1) "1 item" else "${items.size} items"
        updateTopBarForMode(album.name)
        updateDrawerState()
        updateBottomPanelState()
        showBottomPanel()

        val expectedAlbumId = album.id
        val cappedItems = items.take(DesignTokens.DISPLAY_CAP)
        renderJob = lifecycleScope.launch {
            val cells = withContext(Dispatchers.Default) {
                buildTimelineCells(cappedItems, "No media in this album")
            }
            if (currentMode == Mode.AlbumDetail && currentAlbum?.id == expectedAlbumId) {
                adapter.replaceCells(cells)
                resetGridToTop()
                updateFastScrollVisibility()
                binding.fastScrollIndicator.syncToRecyclerView()
            }
        }
    }

    private fun updateFastScrollVisibility() {
        val hasTimeline = adapter.cells.count { it is GalleryCell.Header } > 2
        binding.fastScrollIndicator.visibility = if (hasTimeline) View.VISIBLE else View.GONE
        if (hasTimeline) binding.fastScrollIndicator.syncToRecyclerView()
    }

    private fun openSearch() {
        renderJob?.cancel()
        currentMode = Mode.Search
        binding.searchPanel.visibility = View.VISIBLE
        binding.screenTitle.visibility = View.VISIBLE
        binding.screenTitle.text = "search"
        binding.resultCount.text = ""
        binding.fastScrollIndicator.visibility = View.GONE
        updateSearchMetaText()
        updateTopBarForMode("search")
        updateDrawerState()
        updateBottomPanelState()
        updateSearchPillState()
        if (binding.searchInput.text.isNullOrBlank()) {
            adapter.replaceCells(listOf(GalleryCell.Empty(searchPlaceholderText())))
            resetGridToTop()
        } else {
            submitSearch()
        }
    }

    private fun closeSearch(clearQuery: Boolean) {
        renderJob?.cancel()
        searchJob?.cancel()
        searchDebounceJob?.cancel()
        binding.searchPanel.visibility = View.GONE
        if (clearQuery) {
            binding.searchInput.text?.clear()
        }
        binding.searchInput.clearFocus()
        currentMode = if (currentAlbum != null) Mode.AlbumDetail else Mode.Browse
        renderCurrentState()
    }

    private fun updateSearchMetaText() {
        updateSearchModeUi()
        updateSearchPillState()
    }

    private fun searchPlaceholderText(): String {
        return when {
            currentAlbum != null -> "Search photos in this album"
            activeSection == Section.Favorites -> "Search favorite photos"
            else -> "Search photos"
        }
    }

    private fun submitSearch() {
        val query = binding.searchInput.text?.toString()?.trim().orEmpty()
        val repo = repository ?: return
        searchDebounceJob?.cancel()
        searchJob?.cancel()
        val parsedQuery = StructuredSearch.parse(query)
        val sessionMode = searchMode
        val sessionAlbumId = currentAlbum?.id
        val sessionSection = activeSection

        if (!parsedQuery.hasAnyCriteria) {
            adapter.replaceCells(listOf(GalleryCell.Empty(searchPlaceholderText())))
            resetGridToTop()
            binding.resultCount.text = ""
            binding.statusText.text = selectionSummaryText(albums, selectedAlbumIds, repo.indexedCount)
            return
        }

        currentMode = Mode.Search
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.text = "Searching..."
        val favoriteKeys = favoritesStore.all()
        val filteredItems = parsedQuery.filterItems(currentSearchPhotoItems(), favoriteKeys)

        if (filteredItems.isEmpty()) {
            renderSearchResults(
                results = emptyList(),
                emptyText = "No photos match the active filters",
                statusText = "No filter matches"
            )
            binding.progressBar.visibility = View.GONE
            return
        }

        searchJob = lifecycleScope.launch {
            try {
                val shouldSearchMetadata = sessionMode != SearchMode.AiOnly
                val shouldSearchAi = sessionMode != SearchMode.MetadataOnly && parsedQuery.textQuery.isNotBlank()
                val metadataHits = if (shouldSearchMetadata) {
                    withContext(Dispatchers.Default) {
                        buildMetadataHits(repo, parsedQuery, filteredItems)
                    }
                } else {
                    emptyList()
                }

                if (searchMode != SearchMode.AiOnly) {
                    val metadataResults = withContext(Dispatchers.Default) {
                        buildMergedPhotoSearchResults(
                            baseItems = filteredItems,
                            metadataHits = metadataHits,
                            semanticResults = emptyList()
                        )
                    }

                    if (!isSearchSessionCurrent(query, sessionMode, sessionSection, sessionAlbumId)) return@launch
                    renderSearchResults(
                        results = metadataResults,
                        emptyText = "No matching results",
                        statusText = when {
                            sessionMode == SearchMode.MetadataOnly -> selectionSummaryText(albums, selectedAlbumIds, repo.indexedCount)
                            parsedQuery.textQuery.isBlank() -> filterSummaryText(parsedQuery, metadataResults.size)
                            textEncoder == null -> selectionSummaryText(albums, selectedAlbumIds, repo.indexedCount)
                            else -> selectionSummaryText(albums, selectedAlbumIds, repo.indexedCount)
                        }
                    )
                }

                if (!shouldSearchAi || textEncoder == null) {
                    return@launch
                }

                val semanticResults = withContext(Dispatchers.Default) {
                    repo.search(parsedQuery.textQuery)
                }
                val finalResults = withContext(Dispatchers.Default) {
                    buildMergedPhotoSearchResults(
                        baseItems = filteredItems,
                        metadataHits = metadataHits,
                        semanticResults = semanticResults
                    )
                }

                if (!isSearchSessionCurrent(query, sessionMode, sessionSection, sessionAlbumId)) return@launch
                renderSearchResults(
                    results = finalResults,
                    emptyText = "No matching results",
                    statusText = selectionSummaryText(albums, selectedAlbumIds, repo.indexedCount)
                )
            } catch (cancelled: CancellationException) {
                Log.d(TAG, "Search job cancelled.", cancelled)
            } catch (error: Throwable) {
                showFatalError(error)
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun buildMetadataHits(
        repo: GalleryRepository,
        parsedQuery: StructuredSearch.ParsedQuery,
        items: List<GalleryRepository.MediaItem>
    ): List<MetadataSearch.Hit> {
        if (items.isEmpty()) return emptyList()
        if (parsedQuery.textQuery.isBlank()) {
            return items.sortedByDescending { it.dateMillis }
                .mapIndexed { index, item ->
                    MetadataSearch.Hit(
                        uri = item.uri.toString(),
                        score = (items.size - index).toFloat()
                    )
                }
        }
        return repo.searchMetadata(parsedQuery.textQuery, items)
    }

    private fun buildTimelineCells(
        items: List<GalleryRepository.MediaItem>,
        emptyText: String
    ): List<GalleryCell> {
        if (items.isEmpty()) return listOf(GalleryCell.Empty(emptyText))
        val cells = ArrayList<GalleryCell>()
        var isFirstGroup = true

        items.groupBy { safeFormat(monthFormat, it.dateMillis, "Unknown date") }.forEach { (month, monthItems) ->
            val first = monthItems.first()
            cells += GalleryCell.Header(
                month,
                safeFormat(dayFormat, first.dateMillis, "").uppercase(Locale.getDefault())
            )
            monthItems.groupBy { safeFormat(dayFormat, it.dateMillis, "") }.values.forEach { dayItems ->
                appendDayCells(cells, dayItems, isFirstGroup)
                if (isFirstGroup && dayItems.isNotEmpty()) isFirstGroup = false
            }
        }
        return cells
    }

    private fun appendDayCells(
        cells: MutableList<GalleryCell>,
        dayItems: List<GalleryRepository.MediaItem>,
        isFirstGroup: Boolean
    ) {
        when {
            isFirstGroup && dayItems.isNotEmpty() -> {
                cells += GalleryCell.Photo(dayItems[0], featured = true)
                dayItems.asSequence().drop(1).forEach { cells += GalleryCell.Photo(it, featured = false) }
            }
            dayItems.size >= 3 -> {
                cells += GalleryCell.Collage(dayItems.take(3))
                dayItems.asSequence().drop(3).forEach { cells += GalleryCell.Photo(it, featured = false) }
            }
            else -> dayItems.forEach { cells += GalleryCell.Photo(it, featured = false) }
        }
    }

    private fun buildMergedPhotoSearchResults(
        baseItems: List<GalleryRepository.MediaItem>,
        metadataHits: List<MetadataSearch.Hit>,
        semanticResults: List<GalleryRepository.SemanticSearchHit>
    ): List<PhotoSearchResult> {
        if (baseItems.isEmpty()) return emptyList()

        data class SearchAccumulator(
            val item: GalleryRepository.MediaItem,
            var aiScore: Float = 0f,
            var metadataScore: Float = 0f
        )

        val byUri = baseItems.associateBy { it.uri }
        val merged = LinkedHashMap<Uri, SearchAccumulator>()
        val scopedSemanticHits = semanticResults.filter { it.uri in byUri }

        metadataHits.forEachIndexed { index, hit ->
            val item = byUri[Uri.parse(hit.uri)] ?: return@forEachIndexed
            val entry = merged.getOrPut(item.uri) { SearchAccumulator(item) }
            entry.metadataScore = normalizedRank(index, metadataHits.size)
        }
        scopedSemanticHits.forEachIndexed { index, hit ->
            val item = byUri[hit.uri] ?: return@forEachIndexed
            val entry = merged.getOrPut(hit.uri) { SearchAccumulator(item) }
            entry.aiScore = normalizedRank(index, scopedSemanticHits.size)
        }

        val now = System.currentTimeMillis().coerceAtLeast(1L).toDouble()
        return merged.values.asSequence()
            .map { entry ->
                val hasAi = entry.aiScore > 0f
                val hasMetadata = entry.metadataScore > 0f
                val recencyBoost = (entry.item.dateMillis / now).coerceIn(0.0, 1.0).toFloat() * 0.05f
                val combinedScore =
                    (entry.aiScore * 1.1f) +
                        entry.metadataScore +
                        (if (hasAi && hasMetadata) 0.35f else 0f) +
                        recencyBoost
                PhotoSearchResult(
                    item = entry.item,
                    sources = SearchSources(ai = hasAi, metadata = hasMetadata),
                    score = combinedScore
                )
            }
            .sortedWith(
                compareByDescending<PhotoSearchResult> { it.score }
                    .thenByDescending { it.item.dateMillis }
            )
            .take(DesignTokens.SEARCH_METADATA_HARD_CAP)
            .toList()
    }

    private fun renderSearchResults(
        results: List<PhotoSearchResult>,
        emptyText: String,
        statusText: String
    ) {
        val cells = if (results.isEmpty()) {
            listOf(GalleryCell.Empty(emptyText))
        } else {
            results.map { result ->
                GalleryCell.Photo(
                    item = result.item,
                    featured = false,
                    searchSources = result.sources
                )
            }
        }
        adapter.replaceCells(cells)
        resetGridToTop()
        binding.resultCount.text = when {
            results.isEmpty() -> "No results found"
            results.size == 1 -> "1 result"
            else -> "${results.size} results"
        }
        binding.statusText.text = statusText
    }

    private fun isSearchSessionCurrent(
        query: String,
        mode: SearchMode,
        section: Section,
        albumId: String?
    ): Boolean {
        return currentMode == Mode.Search &&
            binding.searchPanel.visibility == View.VISIBLE &&
            binding.searchInput.text?.toString()?.trim().orEmpty() == query &&
            searchMode == mode &&
            activeSection == section &&
            currentAlbum?.id == albumId
    }

    private fun updateSearchPillState() {
        if (binding.searchPanel.visibility != View.VISIBLE) return
        val parsed = StructuredSearch.parse(binding.searchInput.text?.toString().orEmpty())
        updateSearchModeUi()
        renderActiveSearchPills(parsed)
        renderQuickSearchPills(parsed)
    }

    private fun updateSearchModeUi() {
        bindSearchModeChip(binding.searchModeHybrid, searchMode == SearchMode.Hybrid)
        bindSearchModeChip(binding.searchModeAi, searchMode == SearchMode.AiOnly)
        bindSearchModeChip(binding.searchModeMetadata, searchMode == SearchMode.MetadataOnly)
    }

    private fun bindSearchModeChip(view: TextView, selected: Boolean) {
        view.background = ContextCompat.getDrawable(
            this,
            if (selected) R.drawable.search_filter_chip_active_bg else R.drawable.search_filter_chip_bg
        )
        view.alpha = if (selected) 1f else 0.82f
    }

    private fun setSearchMode(mode: SearchMode) {
        if (searchMode == mode) return
        searchMode = mode
        updateSearchModeUi()
        if (currentMode == Mode.Search && binding.searchPanel.visibility == View.VISIBLE) {
            submitSearch()
        }
    }

    private fun renderActiveSearchPills(parsed: StructuredSearch.ParsedQuery) {
        binding.searchActivePills.removeAllViews()
        val scopePill = currentSearchScopePill()
        binding.searchActivePillsScroll.visibility =
            if (parsed.filters.isEmpty() && scopePill == null) View.GONE else View.VISIBLE
        scopePill?.let {
            binding.searchActivePills.addView(
                createSearchPillView(
                    label = it,
                    selected = true,
                    clickable = false,
                    onClick = {}
                )
            )
        }
        parsed.filters.forEach { filter ->
            binding.searchActivePills.addView(
                createSearchPillView(
                    label = "${filter.chipLabel} x",
                    selected = true,
                    onClick = { removeSearchToken(filter.rawToken) }
                )
            )
        }
    }

    private fun renderQuickSearchPills(parsed: StructuredSearch.ParsedQuery) {
        binding.searchQuickPills.removeAllViews()
        buildQuickSearchPills().forEach { pill ->
            val selected = StructuredSearch.canonicalToken(pill.token) in parsed.normalizedTokens
            binding.searchQuickPills.addView(
                createSearchPillView(
                    label = pill.label,
                    selected = selected,
                    onClick = { toggleSearchToken(pill.token) }
                )
            )
        }
    }

    private fun buildQuickSearchPills(): List<StructuredSearch.Pill> {
        val items = currentSearchPhotoItems()
        if (items.isEmpty()) return emptyList()

        val pills = LinkedHashMap<String, StructuredSearch.Pill>()

        fun addPill(label: String, token: String) {
            val canonical = StructuredSearch.canonicalToken(token)
            pills.putIfAbsent(canonical, StructuredSearch.Pill(label = label, token = token))
        }

        if (activeSection != Section.Favorites && favoriteItems.isNotEmpty()) {
            addPill("favorites", "fav=yes")
        }

        val latest = items.maxByOrNull { it.dateMillis }
        if (latest != null) {
            val date = Instant.ofEpochMilli(latest.dateMillis).atZone(ZoneId.systemDefault()).toLocalDate()
            addPill(date.year.toString(), "year=${date.year}")
            addPill("${date.month.name.lowercase(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }} ${date.year}",
                "date=${date.year}-${date.monthValue.toString().padStart(2, '0')}")
        }

        items.asSequence()
            .mapNotNull { item ->
                item.displayName.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
            }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(3)
            .forEach { entry ->
                addPill(entry.key.uppercase(Locale.ROOT), "ext=${entry.key}")
            }

        if (currentAlbum == null) {
            items.groupingBy { it.bucketName }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(3)
                .forEach { entry ->
                    addPill(entry.key.lowercase(Locale.getDefault()), formatSearchToken("album", entry.key))
                }
        }

        if (items.any { it.height > it.width }) addPill("portrait", "orientation=portrait")
        if (items.any { it.width > it.height }) addPill("landscape", "orientation=landscape")

        return pills.values.take(10).toList()
    }

    private fun currentSearchScopePill(): String? {
        return when {
            currentAlbum != null -> currentAlbum?.name
            activeSection == Section.Favorites -> "favorites"
            else -> null
        }
    }

    private fun createSearchPillView(
        label: String,
        selected: Boolean,
        clickable: Boolean = true,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (selected) R.drawable.search_filter_chip_active_bg else R.drawable.search_filter_chip_bg
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(8)
            }
            isClickable = clickable
            isFocusable = clickable
            if (clickable) {
                setOnClickListener { onClick() }
            } else {
                setOnClickListener(null)
            }
        }
    }

    private fun toggleSearchToken(token: String) {
        val parsed = StructuredSearch.parse(binding.searchInput.text?.toString().orEmpty())
        val canonical = StructuredSearch.canonicalToken(token)
        val tokens = parsed.rawTokens.toMutableList()
        val existingIndex = tokens.indexOfFirst { StructuredSearch.canonicalToken(it) == canonical }
        if (existingIndex >= 0) {
            tokens.removeAt(existingIndex)
        } else {
            tokens += token
        }
        updateSearchQueryTokens(tokens)
    }

    private fun removeSearchToken(token: String) {
        val canonical = StructuredSearch.canonicalToken(token)
        val tokens = StructuredSearch.parse(binding.searchInput.text?.toString().orEmpty())
            .rawTokens
            .filterNot { StructuredSearch.canonicalToken(it) == canonical }
        updateSearchQueryTokens(tokens)
    }

    private fun updateSearchQueryTokens(tokens: List<String>) {
        val newQuery = tokens.joinToString(" ").trim()
        if (binding.searchInput.text?.toString() == newQuery) return
        binding.searchInput.setText(newQuery)
        binding.searchInput.setSelection(newQuery.length)
    }

    private fun formatSearchToken(key: String, value: String): String {
        return if (value.any { it.isWhitespace() }) {
            "$key:\"$value\""
        } else {
            "$key=$value"
        }
    }

    private fun filterSummaryText(parsedQuery: StructuredSearch.ParsedQuery, resultCount: Int): String {
        val filterCount = parsedQuery.filters.size
        return when {
            filterCount <= 0 -> if (resultCount == 1) "1 result" else "$resultCount results"
            resultCount == 1 -> "1 photo matches $filterCount filter"
            else -> "$resultCount photos match $filterCount filters"
        }
    }

    private fun resetGridToTop() {
        binding.imageGrid.stopScroll()
        binding.imageGrid.post {
            if (!binding.imageGrid.isAttachedToWindow) return@post
            binding.imageGrid.scrollToPosition(0)
            binding.fastScrollIndicator.syncToRecyclerView()
        }
    }

    private fun safeFormat(
        formatter: ThreadLocal<SimpleDateFormat>,
        millis: Long,
        fallback: String
    ): String {
        val formatted = runCatching { formatter.get()?.format(Date(millis)) }.getOrNull()
        return if (formatted.isNullOrBlank()) fallback else formatted
    }

    private fun normalizedRank(index: Int, total: Int): Float {
        if (total <= 0) return 0f
        if (total == 1) return 1f
        return 1f - (index.toFloat() / total.toFloat())
    }

    private fun setBusy(message: String) {
        binding.statusText.text = message
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun openAlbum(album: GalleryRepository.Album) {
        renderAlbumDetail(album)
    }

    private fun openMedia(item: GalleryRepository.MediaItem, sharedView: ImageView) {
        val items = currentViewerItems()
        val position = items.indexOfFirst { it.uri == item.uri }
        if (position < 0 || items.isEmpty()) {
            ViewerItemsHolder.store(listOf(item))
            val fallbackIntent = Intent(this, ViewerActivity::class.java).apply {
                putExtra(ViewerActivity.ExtraMarker, item.uri.toString())
                putExtra(ViewerActivity.ExtraPosition, 0)
            }
            viewerLauncher.launch(fallbackIntent)
            return
        }

        ViewerItemsHolder.store(items)
        val transitionName = ViewCompat.getTransitionName(sharedView) ?: ""
        val intent = Intent(this, ViewerActivity::class.java).apply {
            putExtra(ViewerActivity.ExtraMarker, item.uri.toString())
            putExtra(ViewerActivity.ExtraPosition, position)
            putExtra(ViewerActivity.ExtraTransitionName, transitionName)
        }

        val options = ActivityOptionsCompat.makeSceneTransitionAnimation(this, sharedView, transitionName)
        viewerLauncher.launch(intent, options)
    }

    private fun currentViewerItems(): List<GalleryRepository.MediaItem> {
        return when {
            currentAlbum != null -> albumDetailItems
            currentMode == Mode.Search -> {
                adapter.cells.asSequence()
                    .flatMap { cell ->
                        when (cell) {
                            is GalleryCell.Photo -> sequenceOf(cell.item)
                            is GalleryCell.Collage -> cell.items.asSequence()
                            else -> emptySequence()
                        }
                    }
                    .toList()
            }
            activeSection == Section.Collection -> collectionItems
            activeSection == Section.Videos -> videoItems
            activeSection == Section.Favorites -> favoriteItems
            else -> collectionItems
        }
    }

    private fun renderSelectionState(count: Int) {
        binding.selectionPill.visibility = if (count > 0) View.VISIBLE else View.GONE
        binding.selectAllBtn.visibility = if (count > 0) View.VISIBLE else View.GONE

        if (count > 0) {
            binding.screenTitle.visibility = View.VISIBLE
            binding.screenTitle.text = "$count selected"
            binding.menuBtn.setImageResource(R.drawable.ic_fluent_back_24_regular)
            binding.menuBtn.alpha = 1f
            binding.menuBtn.setOnClickListener { adapter.clearSelection() }
            binding.searchLaunchBtn.visibility = View.GONE
        } else {
            binding.searchLaunchBtn.visibility = View.VISIBLE
            updateTopBarForMode(currentTopTitle())
        }
    }

    private fun currentTopTitle(): String? {
        return when (currentMode) {
            Mode.Search -> "search"
            Mode.AlbumDetail -> currentAlbum?.name
            Mode.Browse -> when (activeSection) {
                Section.Collection -> "collections"
                Section.Videos -> "videos"
                Section.Albums -> "albums"
                Section.Favorites -> "favorites"
            }
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
                val pinnedAlbum = currentAlbum
                when {
                    currentMode == Mode.Search -> {
                        updateSearchMetaText()
                        submitSearch()
                    }
                    currentMode == Mode.AlbumDetail && pinnedAlbum != null ->
                        renderAlbumDetail(pinnedAlbum)
                    else -> renderCurrentSection()
                }
                maybeStartBackgroundIndexing()
                primeMetadataIndexAsync()
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
            .setBackoffCriteria(BackoffPolicy.LINEAR, DesignTokens.INDEX_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            INDEX_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
        if (showToast) {
            Toast.makeText(this, "Indexing started.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeIndexWorker() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(INDEX_WORK_NAME)
            .observe(this) { infos ->
                val work = infos.firstOrNull() ?: return@observe
                when (work.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED -> {
                        binding.statusText.text = "Index job queued"
                    }
                    WorkInfo.State.RUNNING -> {
                        val current = work.progress.getInt(IndexWorker.ProgressCurrentKey, 0)
                        val total = work.progress.getInt(IndexWorker.ProgressTotalKey, 0)
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
        if (imageItems.isEmpty()) return
        val shouldRefresh = current > 0 && (current % DesignTokens.INDEX_LIVE_REFRESH_STEP == 0 || current == 1) && current != lastProgressRefresh
        if (!shouldRefresh) return
        lastProgressRefresh = current

        val query = binding.searchInput.text?.toString()?.trim().orEmpty()
        val repo = repository ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            repo.loadCachedIndexForUris(allUris)
            repo.loadCachedMetadataIndexForUris(allUris)
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

    private fun primeMetadataIndexAsync() {
        val repo = repository ?: return
        val visibleUris = allUris
        if (visibleUris.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            repo.loadCachedMetadataIndexForUris(visibleUris)
            if (repo.metadataIndexedCount < visibleUris.size) {
                val allImages = repo.getImageItemsForAlbumIds(emptySet())
                repo.rebuildMetadataIndex(allImages)
                repo.loadCachedMetadataIndexForUris(visibleUris)
            }
        }
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
            binding.menuBtn.alpha = 1f
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
        val albumsHighlighted = (currentMode != Mode.Search && activeSection == Section.Albums) ||
            currentMode == Mode.AlbumDetail
        binding.drawerAlbums.setBackgroundColor(if (albumsHighlighted) active else inactive)
        binding.drawerSearch.setBackgroundColor(if (currentMode == Mode.Search) active else inactive)
    }

    private fun updateBottomPanelState() {
        updateBottomTab(
            tab = binding.bottomCollections,
            icon = binding.bottomCollectionsIcon,
            active = currentMode != Mode.Search && activeSection == Section.Collection
        )
        updateBottomTab(
            tab = binding.bottomAlbums,
            icon = binding.bottomAlbumsIcon,
            active = (currentMode != Mode.Search && activeSection == Section.Albums) || currentMode == Mode.AlbumDetail
        )
        updateBottomTab(
            tab = binding.bottomFavorites,
            icon = binding.bottomFavoritesIcon,
            active = currentMode != Mode.Search && activeSection == Section.Favorites
        )
        updateBottomTab(
            tab = binding.bottomVideos,
            icon = binding.bottomVideosIcon,
            active = currentMode != Mode.Search && activeSection == Section.Videos
        )
    }

    private fun updateBottomTab(tab: View, icon: android.widget.ImageView, active: Boolean) {
        tab.alpha = if (active) 1f else 0.72f
        icon.imageTintList = ColorStateList.valueOf(
            if (active) Color.WHITE else Color.parseColor("#6F6F6F")
        )
        icon.scaleX = if (active) 1f else 0.92f
        icon.scaleY = if (active) 1f else 0.92f
    }

    private fun showBottomPanel() {
        binding.bottomPanel.translationY = 0f
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
        // #region debug-point A:activity-destroy
        debugLog("A", "MainActivity destroyed", mapOf("changingConfigurations" to isChangingConfigurations))
        // #endregion
        super.onDestroy()
        searchDebounceJob?.cancel()
        searchJob?.cancel()
        renderJob?.cancel()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val INDEX_WORK_NAME = "gallery_background_index"
    }

    // #region debug-point Z:logger
    private fun debugLog(hypothesisId: String, message: String, data: Map<String, Any?> = emptyMap()) {
        Log.d(TAG, "[DEBUG][$hypothesisId] $message ${data.entries.joinToString(prefix = "{", postfix = "}") { "${it.key}=${it.value}" }}")
    }
    // #endregion

    private enum class SearchMode {
        Hybrid,
        AiOnly,
        MetadataOnly
    }
}
