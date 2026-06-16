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
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ImageAdapter
    private lateinit var favoritesStore: FavoritesStore
    private lateinit var albumPinStore: AlbumPinStore
    private lateinit var smartAlbumStore: SmartAlbumStore
    private var imageEncoder: ImageEncoder? = null
    private var textEncoder: TextEncoder? = null
    private var repository: GalleryRepository? = null
    private var dbRepository: DbRepository? = null
    private var albums: List<GalleryRepository.Album> = emptyList()
    private var imageItems: List<GalleryRepository.MediaItem> = emptyList()
    private var collectionItems: List<GalleryRepository.MediaItem> = emptyList()
    private var videoItems: List<GalleryRepository.MediaItem> = emptyList()
    private var selectedAlbumIds: Set<String> = emptySet()
    private var allUris: List<Uri> = emptyList()
    private var allTags: List<com.devomind.gallerysearch.db.TagEntity> = emptyList()
    private var tagUriMap: Map<Long, Set<String>> = emptyMap()
    private var currentAlbum: GalleryRepository.Album? = null
    private var currentFolder: FolderNode? = null
    private var currentSmartAlbum: SmartAlbum? = null
    private var smartAlbums: List<SmartAlbum> = emptyList()
    private var folderTreeRoots = listOf<FolderNode>()
    private var currentMode = Mode.Browse
    private var preAlbumDetailSection = Section.Collection
    private var activeSection = Section.Collection
    private var searchMode = SearchMode.Hybrid
    private var searchJob: Job? = null
    private var searchDebounceJob: Job? = null
    private var renderJob: Job? = null
    private var lastProgressRefresh = -1
    private var pendingDeleteUris: List<Uri> = emptyList()
    private var pendingDeleteNeedsRetry = false
    private var topInsetPx = 0
    
    // Infinite scroll state for search results
    // Supports lazy loading with 20 results per page, capped at 80 total
    private var fullSearchResults: List<PhotoSearchResult> = emptyList()
    private var currentDisplayedSearchResultCount = 0

    private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
    private val dayFormatter = DateTimeFormatter.ofPattern("EEE, d", Locale.getDefault())
        .withZone(ZoneId.systemDefault())

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
        albumPinStore = AlbumPinStore(this)
        smartAlbumStore = SmartAlbumStore(this)

        adapter = ImageAdapter(
            onPhotoClick = { item, view -> openMedia(item, view) },
            onSelectionChanged = ::renderSelectionState,
            onAlbumClick = ::openAlbum,
            onAlbumLongClick = ::showAlbumPinMenu,
            onFolderClick = ::openFolder,
            onFolderExpandClick = ::toggleFolderExpanded
        )
        adapter.useCollageLayout = IndexPreferences.isCollageLayout(this)
        adapter.gridColumnCount = IndexPreferences.getGridColumnCount(this)

        val initialSpanCount = if (adapter.useCollageLayout) DesignTokens.GRID_SPAN_COUNT else adapter.gridColumnCount
        val layoutManager = GridLayoutManager(this, initialSpanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = adapter.spanSizeAt(position, layoutManager.spanCount)
        }

        binding.imageGrid.layoutManager = layoutManager

        val scaleGestureListener = ThumbnailScaleGestureListener(adapter.gridColumnCount) { newColumns ->
            if (adapter.useCollageLayout) return@ThumbnailScaleGestureListener

            adapter.gridColumnCount = newColumns
            IndexPreferences.setGridColumnCount(this@MainActivity, newColumns)
            layoutManager.spanCount = newColumns
            layoutManager.spanSizeLookup.invalidateSpanIndexCache()
            adapter.notifyItemRangeChanged(0, adapter.itemCount, "grid_change")
        }
        val scaleGestureDetector = android.view.ScaleGestureDetector(this, scaleGestureListener)

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        val touchListener = android.view.View.OnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            false
        }
        binding.imageGrid.setOnTouchListener(touchListener)

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
                
                // Infinite scroll pagination for search results
                if (currentMode == Mode.Search && fullSearchResults.isNotEmpty()) {
                    val layoutManager = rv.layoutManager as GridLayoutManager
                    val lastVisible = layoutManager.findLastVisibleItemPosition()
                    val total = adapter.itemCount
                    
                    // Load more when within 6 items from the bottom
                    if (currentDisplayedSearchResultCount < fullSearchResults.size && lastVisible >= total - 6) {
                        paginateSearchResults()
                    }
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
        binding.addAlbumBtn.setOnClickListener { showCreateSmartAlbumDialog() }
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
        binding.drawerFolders.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            navigateToSection(Section.Folders)
        }
        binding.drawerIndex.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            enqueueBackgroundIndexing()
        }
        binding.drawerAlbumScope.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showAlbumSelector()
        }

        binding.drawerPinnedCollections.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            val current = IndexPreferences.isShowPinnedInCollections(this)
            IndexPreferences.setShowPinnedInCollections(this, !current)
            updateDrawerState()
            refreshVisibleItems()
        }

        binding.drawerLayoutToggle.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            val current = IndexPreferences.isCollageLayout(this)
            val newMode = !current
            IndexPreferences.setCollageLayout(this, newMode)
            adapter.useCollageLayout = newMode

            val layoutManager = binding.imageGrid.layoutManager as GridLayoutManager
            layoutManager.spanCount = if (newMode) DesignTokens.GRID_SPAN_COUNT else adapter.gridColumnCount
            layoutManager.spanSizeLookup.invalidateSpanIndexCache()

            updateDrawerState()
            refreshVisibleItems()
        }

        binding.bottomCollections.setOnClickListener { navigateToSection(Section.Collection) }
        binding.bottomAlbums.setOnClickListener { navigateToSection(Section.Albums) }
        binding.bottomFavorites.setOnClickListener { navigateToSection(Section.Favorites) }
        binding.bottomVideos.setOnClickListener { navigateToSection(Section.Videos) }
        binding.bottomFolders.setOnClickListener { navigateToSection(Section.Folders) }

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
            binding.topOverlay.updatePadding(top = systemInsets.top)
            binding.drawerPanel.updatePadding(top = systemInsets.top + dp(28), bottom = systemInsets.bottom + dp(24))

            // Wait for topOverlay to lay out to get its exact height, then apply it as top padding
            // so the content starts below the transparent header rather than under it edge-to-edge
            binding.topOverlay.post {
                val overlayHeight = binding.topOverlay.height
                binding.imageGrid.updatePadding(
                    top = overlayHeight,
                    bottom = systemInsets.bottom + dp(84)
                )
            }
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
                        navigateToSection(preAlbumDetailSection)
                    }
                    currentMode == Mode.FolderDetail -> {
                        currentFolder = null
                        navigateToSection(preAlbumDetailSection)
                    }
                    currentMode == Mode.SmartAlbumDetail -> {
                        currentSmartAlbum = null
                        navigateToSection(preAlbumDetailSection)
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
                dbRepository = DbRepository(applicationContext)
                allTags = withContext(Dispatchers.IO) { dbRepository?.getAllTags().orEmpty() }
                tagUriMap = withContext(Dispatchers.IO) {
                    allTags.associate { tag ->
                        tag.id to dbRepository?.getMediaUrisForTag(tag.id).orEmpty().toSet()
                    }
                }
                val snapshot = withContext(Dispatchers.IO) {
                    loadLibrarySnapshot(repo, selectedIds)
                }
                applyLibrarySnapshot(snapshot)
                smartAlbums = withContext(Dispatchers.IO) { smartAlbumStore.getAll() }
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
        if (IndexPreferences.isIndexPaused(applicationContext)) {
            binding.statusText.text = "Indexing paused"
            return
        }
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
        get() {
            val album = currentAlbum ?: return emptyList()
            return if (smartAlbumStore.isSmartId(album.id)) {
                val sa = smartAlbumStore.get(album.id) ?: return emptyList()
                val order = sa.memberUris.withIndex().associate { (i, u) -> u to i }
                collectionItems
                    .filter { it.uri.toString() in order.keys }
                    .sortedBy { order[it.uri.toString()] ?: Int.MAX_VALUE }
            } else {
                collectionItems.filter { it.bucketId == album.id }
            }
        }

    private fun folderDetailItems(folder: FolderNode? = currentFolder): List<GalleryRepository.MediaItem> {
        return folder?.let { flattenFolderItems(it) }.orEmpty()
    }

    private fun flattenFolderItems(node: FolderNode): List<GalleryRepository.MediaItem> {
        return node.directItems + node.children.flatMap { flattenFolderItems(it) }
    }

    private fun currentSearchPhotoItems(): List<GalleryRepository.MediaItem> {
        return when {
            currentAlbum != null -> albumDetailItems.filter { it.mediaType == GalleryRepository.MediaType.Image }
            currentFolder != null -> folderDetailItems().filter { it.mediaType == GalleryRepository.MediaType.Image }
            activeSection == Section.Collection -> imageItems
            activeSection == Section.Favorites -> favoriteItems.filter { it.mediaType == GalleryRepository.MediaType.Image }
            activeSection == Section.Albums -> imageItems
            activeSection == Section.Folders -> imageItems
            else -> emptyList()
        }
    }

    private fun switchSection(section: Section) {
        searchJob?.cancel()
        searchDebounceJob?.cancel()
        renderJob?.cancel()
        
        // Reset search pagination state
        fullSearchResults = emptyList()
        currentDisplayedSearchResultCount = 0
        
        activeSection = section
        currentAlbum = null
        currentFolder = null
        currentSmartAlbum = null
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
            Mode.FolderDetail -> currentFolder?.let(::renderFolderDetail) ?: renderCurrentSection()
            Mode.SmartAlbumDetail -> currentSmartAlbum?.let(::renderSmartAlbumDetail) ?: renderCurrentSection()
            Mode.Search -> openSearch()
        }
    }

    private fun renderCurrentSection() {
        when (activeSection) {
            Section.Collection -> renderMediaSection(title = "collections", items = collectionItems, emptyText = "No media yet")
            Section.Videos -> renderMediaSection(title = "videos", items = videoItems, emptyText = "No videos yet")
            Section.Albums -> renderAlbums()
            Section.Favorites -> renderMediaSection(title = "favorites", items = favoriteItems, emptyText = "No favorites yet")
            Section.Folders -> renderFolders()
        }
    }

    private fun renderFolders() {
        currentMode = Mode.Browse
        binding.searchPanel.visibility = View.GONE
        binding.resultCount.text = ""
        updateTopBarForMode("folders")
        updateDrawerState()
        updateBottomPanelState()
        showBottomPanel()

        val previousExpanded = collectExpandedStates(folderTreeRoots)
        val roots = buildFolderTree(collectionItems, previousExpanded)
        folderTreeRoots = roots
        val cells = flattenFolderNodes(roots)
        adapter.replaceCells(
            if (cells.isEmpty()) listOf(GalleryCell.Empty("No folders yet")) else cells
        )
        resetGridToTop()
        updateFastScrollVisibility()
    }

    private fun buildFolderTree(
        items: List<GalleryRepository.MediaItem>,
        expandedStates: Map<String, Boolean> = emptyMap()
    ): List<FolderNode> {
        data class MutableNode(
            val name: String,
            val path: String,
            val depth: Int,
            val directItems: MutableList<GalleryRepository.MediaItem> = mutableListOf(),
            val children: MutableMap<String, MutableNode> = mutableMapOf()
        )

        val roots = mutableMapOf<String, MutableNode>()
        items.forEach { item ->
            val rawPath = item.path.takeIf { it.isNotBlank() } ?: item.bucketName
            val segments = rawPath.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return@forEach

            val rootName = segments.first()
            val root = roots.getOrPut(rootName) { MutableNode(rootName, rootName, 0) }
            var current = root
            for (i in 1 until segments.size) {
                val segment = segments[i]
                val childPath = segments.take(i + 1).joinToString("/")
                current = current.children.getOrPut(segment) { MutableNode(segment, childPath, i) }
            }
            current.directItems += item
        }

        fun toFolderNode(node: MutableNode): FolderNode {
            val childNodes = node.children.values.map { toFolderNode(it) }.sortedBy { it.name }
            val count = node.directItems.size + childNodes.sumOf { it.itemCount }
            val cover = node.directItems.firstOrNull()?.uri
                ?: childNodes.firstOrNull { it.coverUri != null }?.coverUri
            return FolderNode(
                path = node.path,
                name = node.name,
                depth = node.depth,
                coverUri = cover,
                itemCount = count,
                directItems = node.directItems,
                expanded = expandedStates[node.path] ?: true,
                children = childNodes
            )
        }

        return roots.values.map { toFolderNode(it) }.sortedBy { it.name }
    }

    private fun collectExpandedStates(nodes: List<FolderNode>): Map<String, Boolean> {
        val map = mutableMapOf<String, Boolean>()
        nodes.forEach { node ->
            map[node.path] = node.expanded
            map.putAll(collectExpandedStates(node.children))
        }
        return map
    }

    private fun flattenFolderNodes(nodes: List<FolderNode>): List<GalleryCell> {
        val cells = mutableListOf<GalleryCell>()
        nodes.forEach { node ->
            cells += GalleryCell.FolderCell(node)
            if (node.expanded) {
                cells += flattenFolderNodes(node.children)
            }
        }
        return cells
    }

    private fun openFolder(node: FolderNode) {
        if (node.isLeaf && node.directItems.isEmpty()) return
        renderFolderDetail(node)
    }

    private fun toggleFolderExpanded(node: FolderNode) {
        folderTreeRoots = updateNodeExpanded(folderTreeRoots, node.path, !node.expanded)
        renderFolders()
    }

    private fun updateNodeExpanded(nodes: List<FolderNode>, path: String, expanded: Boolean): List<FolderNode> {
        return nodes.map { node ->
            if (node.path == path) {
                node.copy(expanded = expanded)
            } else {
                node.copy(children = updateNodeExpanded(node.children, path, expanded))
            }
        }
    }

    private fun renderFolderDetail(folder: FolderNode) {
        renderJob?.cancel()
        preAlbumDetailSection = activeSection
        currentMode = Mode.FolderDetail
        currentFolder = folder
        binding.searchPanel.visibility = View.GONE
        val items = folderDetailItems(folder)
        binding.resultCount.text = when (items.size) {
            0 -> ""
            1 -> "1 item"
            else -> "${items.size} items"
        }
        updateTopBarForMode(folder.name)
        updateDrawerState()
        updateBottomPanelState()
        showBottomPanel()

        val expectedPath = folder.path
        val cappedItems = items.sortedByDescending { it.dateMillis }.take(DesignTokens.DISPLAY_CAP)
        renderJob = lifecycleScope.launch {
            val cells = withContext(Dispatchers.Default) {
                buildTimelineCells(cappedItems, "No media in this folder", adapter.useCollageLayout)
            }
            if (currentMode == Mode.FolderDetail && currentFolder?.path == expectedPath) {
                adapter.replaceCells(cells)
                resetGridToTop()
                updateFastScrollVisibility()
                binding.fastScrollIndicator.syncToRecyclerView()
            }
        }
    }

    private fun renderMediaSection(
        title: String?,
        items: List<GalleryRepository.MediaItem>,
        emptyText: String
    ) {
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
                buildTimelineCells(cappedItems, emptyText, adapter.useCollageLayout)
            }
            if (currentMode == Mode.Browse && currentAlbum == null && activeSection == expectedSection) {
                albumPinStore.cleanup(albums.map { it.id }.toSet() + smartAlbums.map { it.id }.toSet())
                val pinnedIds = albumPinStore.getPinnedAlbumIds()
                val pinnedAlbums = albums.filter { it.id in pinnedIds }
                    .sortedBy { pinnedIds.indexOf(it.id) }

                val finalCells = if (expectedSection == Section.Collection &&
                    IndexPreferences.isShowPinnedInCollections(this@MainActivity) &&
                    pinnedAlbums.isNotEmpty()
                ) {
                    val withHeader = mutableListOf<GalleryCell>()
                    withHeader += GalleryCell.PinnedAlbumsHeader(pinnedAlbums)
                    withHeader += cells
                    withHeader.toList()
                } else {
                    cells
                }

                adapter.replaceCells(finalCells)
                resetGridToTop()
                updateFastScrollVisibility()
                binding.fastScrollIndicator.syncToRecyclerView()
            }
        }
    }

    private fun renderAlbums() {
        currentMode = Mode.Browse
        binding.searchPanel.visibility = View.GONE
        binding.resultCount.text = ""

        val validIds = albums.map { it.id }.toSet() + smartAlbums.map { it.id }.toSet()
        albumPinStore.cleanup(validIds)
        val pinnedIds = albumPinStore.getPinnedAlbumIds()

        val smartById = smartAlbums.associate { it.id to it.toAlbum() }
        val albumById = (albums + smartById.values).associateBy { it.id }

        val pinnedAlbums = pinnedIds.mapNotNull { albumById[it] }
        val normalAlbums = albums.filter { it.id !in pinnedIds }

        val cells = mutableListOf<GalleryCell>()
        if (pinnedAlbums.isNotEmpty()) {
            cells += GalleryCell.Header("PINNED", "")
            pinnedAlbums.forEach { cells += GalleryCell.AlbumCell(it) }
        }
        if (normalAlbums.isNotEmpty()) {
            cells += GalleryCell.Header("OTHERS", "")
            normalAlbums.forEach { cells += GalleryCell.AlbumCell(it) }
        }

        adapter.replaceCells(
            if (cells.isEmpty()) listOf(GalleryCell.Empty("No albums yet"))
            else cells
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
        preAlbumDetailSection = activeSection
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
                buildTimelineCells(cappedItems, "No media in this album", adapter.useCollageLayout)
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

        searchJob = lifecycleScope.launch {
            try {
                val filterLookup = if (parsedQuery.needsFilterLookup) {
                    withContext(Dispatchers.IO) { buildFilterLookup(parsedQuery, currentSearchPhotoItems()) }
                } else {
                    StructuredSearch.FilterLookup()
                }
                val filteredItems = parsedQuery.filterItems(currentSearchPhotoItems(), favoriteKeys, filterLookup)

                if (!isSearchSessionCurrent(query, sessionMode, sessionSection, sessionAlbumId)) return@launch

                if (filteredItems.isEmpty()) {
                    renderSearchResults(
                        results = emptyList(),
                        emptyText = "No photos match the active filters",
                        statusText = "No filter matches"
                    )
                    binding.progressBar.visibility = View.GONE
                    return@launch
                }

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

    private suspend fun buildFilterLookup(
        parsedQuery: StructuredSearch.ParsedQuery,
        items: List<GalleryRepository.MediaItem>
    ): StructuredSearch.FilterLookup {
        val db = dbRepository ?: return StructuredSearch.FilterLookup()
        val tagFilters = parsedQuery.filters.filterIsInstance<StructuredSearch.TagFilter>()
        val exifFilters = parsedQuery.filters.filter {
            it is StructuredSearch.MakeFilter || it is StructuredSearch.ModelFilter ||
                it is StructuredSearch.IsoFilter || it is StructuredSearch.FocalLengthFilter
        }

        val tagNameToUris = if (tagFilters.isNotEmpty()) {
            val allTags = db.getAllTags()
            val tagNameMap = allTags.associateBy { it.name.lowercase(java.util.Locale.getDefault()) }
            tagFilters.mapNotNull { filter ->
                val tag = tagNameMap[filter.value.lowercase(java.util.Locale.getDefault())]
                    ?: return@mapNotNull null
                val uris = db.getMediaUrisForTag(tag.id)
                tag.name.lowercase(java.util.Locale.getDefault()) to uris.toSet()
            }.toMap()
        } else {
            emptyMap()
        }

        val exifByUri = if (exifFilters.isNotEmpty()) {
            db.getExifForUris(items.map { it.uri.toString() })
        } else {
            emptyMap()
        }

        return StructuredSearch.FilterLookup(tagNameToUris, exifByUri)
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
        emptyText: String,
        useCollageLayout: Boolean
    ): List<GalleryCell> {
        if (items.isEmpty()) return listOf(GalleryCell.Empty(emptyText))
        val cells = ArrayList<GalleryCell>()
        var lastMonth: String? = null
        var lastDay: String? = null
        var currentDayItems = ArrayList<GalleryRepository.MediaItem>()
        var isFirstGroup = true

        for (item in items) {
            val month = safeFormat(monthFormatter, item.dateMillis, "Unknown date")
            val day = safeFormat(dayFormatter, item.dateMillis, "")

            if (month != lastMonth || day != lastDay) {
                if (currentDayItems.isNotEmpty()) {
                    appendDayCells(cells, currentDayItems, isFirstGroup, useCollageLayout)
                    isFirstGroup = false
                    currentDayItems = ArrayList()
                }

                if (month != lastMonth) {
                    cells += GalleryCell.Header(month, day.uppercase(Locale.getDefault()))
                    lastMonth = month
                }
                lastDay = day
            }
            currentDayItems.add(item)
        }

        if (currentDayItems.isNotEmpty()) {
            appendDayCells(cells, currentDayItems, isFirstGroup, useCollageLayout)
        }

        return cells
    }

    private fun appendDayCells(
        cells: MutableList<GalleryCell>,
        dayItems: List<GalleryRepository.MediaItem>,
        isFirstGroup: Boolean,
        useCollageLayout: Boolean
    ) {
        if (!useCollageLayout) {
            dayItems.forEach { cells += GalleryCell.Photo(it, featured = false) }
            return
        }

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
            .toList()  // Return full list without hard cap
    }

    private fun renderSearchResults(
        results: List<PhotoSearchResult>,
        emptyText: String,
        statusText: String
    ) {
        // Store full results for pagination
        fullSearchResults = results.take(DesignTokens.SEARCH_METADATA_HARD_CAP)
        currentDisplayedSearchResultCount = 0
        
        if (fullSearchResults.isEmpty()) {
            adapter.replaceCells(listOf(GalleryCell.Empty(emptyText)))
            resetGridToTop()
            binding.resultCount.text = "No results found"
            binding.statusText.text = statusText
            return
        }
        
        // Display first page (20 results)
        val pageSize = 20
        val firstPage = fullSearchResults.take(pageSize)
        currentDisplayedSearchResultCount = firstPage.size
        
        val cells = firstPage.map { result ->
            GalleryCell.Photo(
                item = result.item,
                featured = false,
                searchSources = result.sources
            )
        }
        
        adapter.replaceCells(cells)
        resetGridToTop()
        
        binding.resultCount.text = when {
            fullSearchResults.size == 1 -> "1 result"
            currentDisplayedSearchResultCount < fullSearchResults.size -> 
                "Showing ${currentDisplayedSearchResultCount} of ${fullSearchResults.size} results"
            else -> "${fullSearchResults.size} results"
        }
        binding.statusText.text = statusText
    }
    
    private fun paginateSearchResults() {
        if (currentDisplayedSearchResultCount >= fullSearchResults.size) return
        
        val pageSize = 20
        val nextBatch = fullSearchResults
            .drop(currentDisplayedSearchResultCount)
            .take(pageSize)
        
        if (nextBatch.isEmpty()) return
        
        val newCells = nextBatch.map { result ->
            GalleryCell.Photo(
                item = result.item,
                featured = false,
                searchSources = result.sources
            )
        }
        
        currentDisplayedSearchResultCount += newCells.size
        adapter.updateCells(adapter.cells + newCells)
        
        binding.resultCount.text = when {
            currentDisplayedSearchResultCount < fullSearchResults.size -> 
                "Showing ${currentDisplayedSearchResultCount} of ${fullSearchResults.size} results"
            else -> "${fullSearchResults.size} results"
        }
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

        allTags
            .sortedByDescending { tag -> items.count { it.uri.toString() in tagUriMap[tag.id].orEmpty() } }
            .take(3)
            .forEach { tag ->
                addPill(tag.name.lowercase(Locale.getDefault()), "tag=${tag.name}")
            }

        return pills.values.take(12).toList()
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
        formatter: DateTimeFormatter,
        millis: Long,
        fallback: String
    ): String {
        return runCatching { formatter.format(Instant.ofEpochMilli(millis)) }.getOrDefault(fallback)
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
        if (album.isSmart) {
            val smart = smartAlbums.find { it.id == album.id }
            if (smart != null) {
                renderSmartAlbumDetail(smart)
            }
        } else {
            renderAlbumDetail(album)
        }
    }

    private fun showAlbumPinMenu(album: GalleryRepository.Album, anchor: View) {
        if (album.isSmart) {
            showSmartAlbumMenu(album, anchor)
            return
        }
        val isPinned = albumPinStore.isPinned(album.id)
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(if (isPinned) "Unpin Album" else "Pin Album")
        popup.setOnMenuItemClickListener { _ ->
            if (isPinned) albumPinStore.unpin(album.id) else albumPinStore.pin(album.id)
            if (currentMode == Mode.Browse && currentAlbum == null) {
                renderCurrentSection()
            }
            true
        }
        popup.show()
    }

    private fun showSmartAlbumMenu(album: GalleryRepository.Album, anchor: View) {
        val smart = smartAlbums.find { it.id == album.id } ?: return
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add("Refresh")
        popup.menu.add("Rename")
        popup.menu.add("Edit Prompt")
        popup.menu.add("Delete")
        popup.menu.add("Unpin")
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Refresh" -> handleSmartAlbumRefresh(smart)
                "Rename" -> showRenameSmartAlbumDialog(smart)
                "Edit Prompt" -> showEditPromptDialog(smart)
                "Delete" -> confirmDeleteSmartAlbum(smart)
                "Unpin" -> { albumPinStore.unpin(album.id); renderCurrentSection() }
            }
            true
        }
        popup.show()
    }

    private fun handleSmartAlbumRefresh(smart: SmartAlbum) {
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.text = "Refreshing…"
        lifecycleScope.launch {
            refreshSmartAlbum(smart)
            binding.progressBar.visibility = View.GONE
            val repo = repository
            val summary = if (repo != null)
                selectionSummaryText(albums, selectedAlbumIds, repo.indexedCount) else ""
            binding.statusText.text = summary
            if (currentMode == Mode.SmartAlbumDetail && currentSmartAlbum?.id == smart.id) {
                val refreshed = smartAlbumStore.get(smart.id)
                if (refreshed != null) renderSmartAlbumDetail(refreshed)
            } else {
                renderCurrentSection()
            }
        }
    }

    private fun showRenameSmartAlbumDialog(smart: SmartAlbum) {
        val editText = EditText(this).apply {
            setText(smart.name)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#FF484848"))
            background = android.graphics.drawable.ColorDrawable(Color.parseColor("#FF1A1A1A"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val container = android.widget.FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(editText, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Album")
            .setView(container)
            .setPositiveButton("Rename") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    smartAlbumStore.upsert(smart.copy(name = newName, updatedAt = System.currentTimeMillis()))
                    smartAlbums = smartAlbumStore.getAll()
                    if (currentMode == Mode.SmartAlbumDetail && currentSmartAlbum?.id == smart.id) {
                        currentSmartAlbum = smartAlbumStore.get(smart.id)
                    }
                    renderCurrentSection()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditPromptDialog(smart: SmartAlbum) {
        val editText = EditText(this).apply {
            setText(smart.prompt)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#FF484848"))
            background = android.graphics.drawable.ColorDrawable(Color.parseColor("#FF1A1A1A"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val container = android.widget.FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(editText, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("Edit Prompt")
            .setView(container)
            .setPositiveButton("Save & Refresh") { _, _ ->
                val newPrompt = editText.text.toString().trim()
                if (newPrompt.isNotEmpty()) {
                    val updated = smart.copy(prompt = newPrompt, updatedAt = System.currentTimeMillis())
                    smartAlbumStore.upsert(updated)
                    smartAlbums = smartAlbumStore.getAll()
                    handleSmartAlbumRefresh(updated)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteSmartAlbum(smart: SmartAlbum) {
        AlertDialog.Builder(this)
            .setTitle("Delete Smart Album?")
            .setMessage("Delete \"${smart.name}\"? This won't delete any actual photos.")
            .setPositiveButton("Delete") { _, _ ->
                smartAlbumStore.delete(smart.id)
                albumPinStore.unpin(smart.id)
                smartAlbums = smartAlbumStore.getAll()
                if (currentMode == Mode.SmartAlbumDetail && currentSmartAlbum?.id == smart.id) {
                    currentSmartAlbum = null
                    navigateToSection(activeSection)
                } else {
                    renderCurrentSection()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderSmartAlbumDetail(smart: SmartAlbum) {
        renderJob?.cancel()
        preAlbumDetailSection = activeSection
        currentMode = Mode.SmartAlbumDetail
        currentSmartAlbum = smart
        binding.searchPanel.visibility = View.GONE
        updateTopBarForMode(smart.name)
        updateDrawerState()
        updateBottomPanelState()
        showBottomPanel()

        val uriOrder = smart.memberUris.withIndex().associate { (i, u) -> u to i }
        val items = collectionItems
            .filter { it.uri.toString() in uriOrder.keys }
            .sortedBy { uriOrder[it.uri.toString()] ?: Int.MAX_VALUE }

        val expectedAlbumId = smart.id
        val cappedItems = items.take(DesignTokens.DISPLAY_CAP)
        renderJob = lifecycleScope.launch {
            val cells = withContext(Dispatchers.Default) {
                buildTimelineCells(cappedItems, "No results for this prompt yet", adapter.useCollageLayout)
            }
            if (currentMode == Mode.SmartAlbumDetail && currentSmartAlbum?.id == expectedAlbumId) {
                adapter.replaceCells(cells)
                resetGridToTop()
                updateFastScrollVisibility()
                binding.fastScrollIndicator.syncToRecyclerView()
                binding.resultCount.text = when {
                    items.isEmpty() -> ""
                    items.size == 1 -> "1 result"
                    else -> "${items.size} results"
                }
                binding.progressBar.visibility = View.GONE
                binding.statusText.text = selectionSummaryText(albums, selectedAlbumIds, repository?.indexedCount ?: 0)
            }
        }
    }

    private fun showCreateSmartAlbumDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_smart_album, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.smartAlbumName)
        val promptInput = dialogView.findViewById<EditText>(R.id.smartAlbumPrompt)

        AlertDialog.Builder(this)
            .setTitle("New Smart Album")
            .setView(dialogView)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text.toString().trim()
                val prompt = promptInput.text.toString().trim()
                if (name.isEmpty() || prompt.isEmpty()) {
                    Toast.makeText(this, "Album name and prompt are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                createSmartAlbum(name, prompt)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createSmartAlbum(name: String, prompt: String) {
        val repo = repository ?: return
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.text = "Creating smart album…"
        lifecycleScope.launch {
            val resultUris = runSearchPipeline(
                query = prompt,
                mode = searchMode,
                candidateItems = currentSearchPhotoItems()
            ).take(SmartAlbumStore.MAX_SMART_MEMBERS)

            val album = SmartAlbum(
                id = SmartAlbumStore.SMART_PREFIX + java.util.UUID.randomUUID().toString(),
                name = name,
                prompt = prompt,
                searchMode = searchMode.name,
                memberUris = resultUris.map { it.toString() },
                coverUri = resultUris.firstOrNull()?.toString(),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            smartAlbumStore.upsert(album)
            smartAlbums = smartAlbumStore.getAll()
            albumPinStore.pin(album.id)
            binding.progressBar.visibility = View.GONE
            binding.statusText.text = selectionSummaryText(albums, selectedAlbumIds, repo.indexedCount)
            if (resultUris.isEmpty()) {
                Toast.makeText(this@MainActivity, "No matches yet — you can refresh later", Toast.LENGTH_LONG).show()
            }
            renderCurrentSection()
        }
    }

    private suspend fun runSearchPipeline(
        query: String,
        mode: SearchMode,
        candidateItems: List<GalleryRepository.MediaItem>
    ): List<Uri> {
        val repo = repository ?: return emptyList()
        if (candidateItems.isEmpty()) return emptyList()

        val shouldSearchAi = mode != SearchMode.MetadataOnly
        val shouldSearchMetadata = mode != SearchMode.AiOnly

        val metadataHits = if (shouldSearchMetadata) {
            withContext(Dispatchers.Default) { repo.searchMetadata(query, candidateItems) }
        } else emptyList()

        val semanticResults = if (shouldSearchAi) {
            withContext(Dispatchers.Default) { repo.search(query) }
        } else emptyList()

        val merged = withContext(Dispatchers.Default) {
            buildMergedPhotoSearchResults(candidateItems, metadataHits, semanticResults)
        }
        return merged.map { it.item.uri }
    }

    private suspend fun refreshSmartAlbum(smart: SmartAlbum): SmartAlbum? {
        val repo = repository ?: return null
        val resultUris = runSearchPipeline(
            query = smart.prompt,
            mode = try { SearchMode.valueOf(smart.searchMode) } catch (_: Exception) { SearchMode.Hybrid },
            candidateItems = currentSearchPhotoItems()
        ).take(SmartAlbumStore.MAX_SMART_MEMBERS)

        val updated = smart.copy(
            memberUris = resultUris.map { it.toString() },
            coverUri = resultUris.firstOrNull()?.toString(),
            updatedAt = System.currentTimeMillis()
        )
        smartAlbumStore.upsert(updated)
        smartAlbums = smartAlbumStore.getAll()
        return updated
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
            currentFolder != null -> folderDetailItems()
            currentMode == Mode.Search || currentMode == Mode.SmartAlbumDetail -> {
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
            Mode.FolderDetail -> currentFolder?.name
            Mode.SmartAlbumDetail -> currentSmartAlbum?.name
            Mode.Browse -> when (activeSection) {
                Section.Collection -> "collections"
                Section.Videos -> "videos"
                Section.Albums -> "albums"
                Section.Favorites -> "favorites"
                Section.Folders -> "folders"
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
            val refreshedTags = withContext(Dispatchers.IO) { dbRepository?.getAllTags().orEmpty() }
            val refreshedTagUriMap = withContext(Dispatchers.IO) {
                refreshedTags.associate { tag ->
                    tag.id to dbRepository?.getMediaUrisForTag(tag.id).orEmpty().toSet()
                }
            }
            withContext(Dispatchers.Main) {
                allTags = refreshedTags
                tagUriMap = refreshedTagUriMap
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

    @Suppress("InstanceOfCheckForException")
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
            val isRecoverable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                error is RecoverableSecurityException && !afterApproval
            if (isRecoverable) {
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
        IndexPreferences.setIndexPaused(this, false)
        IndexWorker.cancelStatusNotification(this)

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
                        if (IndexPreferences.isIndexPaused(this)) {
                            binding.statusText.text = "Indexing paused"
                            return@observe
                        }
                        refreshVisibleItems()
                        Toast.makeText(this, "Indexing complete.", Toast.LENGTH_SHORT).show()
                    }
                    WorkInfo.State.FAILED -> {
                        binding.progressBar.visibility = View.GONE
                        binding.statusText.text = "Indexing failed"
                    }
                    WorkInfo.State.CANCELLED -> {
                        binding.progressBar.visibility = View.GONE
                        binding.statusText.text = if (IndexPreferences.isIndexPaused(this)) {
                            "Indexing paused"
                        } else {
                            "Indexing cancelled"
                        }
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
        val isStep = current > 0 && (current % DesignTokens.INDEX_LIVE_REFRESH_STEP == 0 || current == 1)
        val shouldRefresh = isStep && current != lastProgressRefresh
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
        if (IndexPreferences.isIndexPaused(applicationContext)) {
            binding.statusText.text =
                "Indexing paused · ${selectionSummaryText(albums, selectedAlbumIds, repo.indexedCount)}"
            return
        }
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
                switchSection(preAlbumDetailSection)
            }
        } else if (currentMode == Mode.FolderDetail) {
            binding.menuBtn.setImageResource(R.drawable.ic_fluent_back_24_regular)
            binding.menuBtn.alpha = 1f
            binding.menuBtn.setOnClickListener {
                currentFolder = null
                switchSection(preAlbumDetailSection)
            }
        } else if (currentMode == Mode.SmartAlbumDetail) {
            binding.menuBtn.setImageResource(R.drawable.ic_fluent_back_24_regular)
            binding.menuBtn.alpha = 1f
            binding.menuBtn.setOnClickListener {
                currentSmartAlbum = null
                switchSection(preAlbumDetailSection)
            }
        } else {
            binding.menuBtn.setImageResource(R.drawable.ic_fluent_navigation_24_regular)
            binding.menuBtn.alpha = 1f
            binding.menuBtn.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
        }

        binding.screenTitle.visibility = if (title == null) View.GONE else View.VISIBLE
        binding.screenTitle.text = title.orEmpty()
        binding.searchLaunchBtn.visibility = if (adapter.selectionCount > 0) View.GONE else View.VISIBLE
        val isAlbumsSection = activeSection == Section.Albums &&
            currentMode == Mode.Browse &&
            adapter.selectionCount == 0
        binding.addAlbumBtn.visibility = if (isAlbumsSection) View.VISIBLE else View.GONE
    }

    private fun updateDrawerState() {
        val inactive = Color.rgb(10, 10, 10)
        val active = Color.rgb(17, 17, 17)
        binding.drawerCollection.setBackgroundColor(
            if (currentMode != Mode.Search && activeSection == Section.Collection) active else inactive
        )
        val albumsHighlighted = (currentMode != Mode.Search && activeSection == Section.Albums) ||
            currentMode == Mode.AlbumDetail || currentMode == Mode.SmartAlbumDetail
        binding.drawerAlbums.setBackgroundColor(if (albumsHighlighted) active else inactive)
        binding.drawerSearch.setBackgroundColor(if (currentMode == Mode.Search) active else inactive)
        binding.drawerFolders.setBackgroundColor(
            if (currentMode != Mode.Search && activeSection == Section.Folders) active else inactive
        )

        val isPinned = IndexPreferences.isShowPinnedInCollections(this)
        binding.drawerPinnedCollections.text = if (isPinned) "pinned in collections ✓" else "pinned in collections"

        val isCollage = IndexPreferences.isCollageLayout(this)
        binding.drawerLayoutToggle.text = if (isCollage) "collage view ✓" else "grid view ✓"
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
            active = (currentMode != Mode.Search && activeSection == Section.Albums) ||
                currentMode == Mode.AlbumDetail ||
                currentMode == Mode.SmartAlbumDetail
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
        updateBottomTab(
            tab = binding.bottomFolders,
            icon = binding.bottomFoldersIcon,
            active = currentMode != Mode.Search && activeSection == Section.Folders
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
        super.onDestroy()
        searchDebounceJob?.cancel()
        searchJob?.cancel()
        renderJob?.cancel()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val INDEX_WORK_NAME = "gallery_background_index"
    }

    private enum class SearchMode {
        Hybrid,
        AiOnly,
        MetadataOnly
    }
}
