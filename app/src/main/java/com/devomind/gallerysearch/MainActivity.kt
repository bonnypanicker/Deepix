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
import android.text.TextUtils
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.ExistingWorkPolicy
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ImageAdapter
    private lateinit var searchSectionAdapter: SearchSectionAdapter
    private lateinit var favoritesStore: FavoritesStore
    private lateinit var albumPinStore: AlbumPinStore
    private lateinit var smartAlbumStore: SmartAlbumStore
    private var imageEncoder: ImageEncoder? = null
    private var nsfwClassifier: NsfwClassifier? = null
    private var nsfwComputeJob: Job? = null
    private var textEncoder: TextEncoder? = null
    // Tracks the one-shot CLIP encoder load. The ~135 MB models are only warmed eagerly when a
    // background index pass will run; otherwise they load lazily on the first search so idle/paused
    // cold starts stay light. Completes true once encoders are attached, false if the load failed.
    private var encodersReady: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private var repository: GalleryRepository? = null
    private var dbRepository: DbRepository? = null
    private var albums: List<GalleryRepository.Album> = emptyList()
    private var imageItems: List<GalleryRepository.MediaItem> = emptyList()
    private var collectionItems: List<GalleryRepository.MediaItem> = emptyList()
    private var videoItems: List<GalleryRepository.MediaItem> = emptyList()
    private var allUris: List<Uri> = emptyList()
    /** Only the in-scope photos, i.e. what indexing actually covers. [allUris] is the whole library. */
    private var indexScopeUris: List<Uri> = emptyList()
    /**
     * In-scope photos a completed pass still didn't embed — corrupt files, unsupported codecs.
     * Retrying them on every refresh is what spun the worker; skip them until the app restarts.
     */
    private var permanentlyUnindexedUris: Set<Uri> = emptySet()
    private var allTags: List<com.devomind.gallerysearch.db.TagEntity> = emptyList()
    private var tagUriMap: Map<Long, Set<String>> = emptyMap()
    private var currentAlbum: GalleryRepository.Album? = null
    private var currentFolder: FolderNode? = null
    private var currentSmartAlbum: SmartAlbum? = null
    private var smartAlbums: List<SmartAlbum> = emptyList()
    /** Non-persisted virtual collection, rebuilt from the Room face index. */
    private var peopleCollection: GalleryRepository.Album? = null
    private var peopleCollectionRefreshGeneration = 0
    private var lastFaceIndexPeopleRefresh = -1
    /** Prevent worker callbacks from rendering or pruning pins before MediaStore is loaded. */
    private var librarySnapshotReady = false
    private var folderTreeRoots = listOf<FolderNode>()
    private var folderSort = FolderSort.Name
    private var currentMode = Mode.Browse
    private var preAlbumDetailSection = Section.Collection
    private var activeSection = Section.Collection
    private var openSafeAfterInitialRender = false
    private var searchMode = SearchMode.Hybrid
    private var searchJob: Job? = null
    private var searchDebounceJob: Job? = null
    private var renderJob: Job? = null
    private var lastProgressRefresh = -1
    private var indexRunning = false
    private var chargingPrefSnapshot = false
    private var settingsLaunchAccentKey: String? = null
    private var pendingDeleteUris: List<Uri> = emptyList()
    private var pendingDeleteNeedsRetry = false
    private var pendingAllFilesDeleteUris: List<Uri> = emptyList()
    private var topInsetPx = 0

    // Infinite scroll state for search results
    // Supports lazy loading with 20 results per page, capped at 80 total
    private var fullSearchResults: List<PhotoSearchResult> = emptyList()
    private var searchSectionResults: List<SearchSectionResult> = emptyList()
    private var selectedSearchSection: SearchSection? = null
    private var searchLandingVisible = false
    private var currentDisplayedSearchResultCount = 0
    private var searchResultsMaster: List<PhotoSearchResult> = emptyList()
    /** Search-only ordering; null means relevance (ranked by score), which browse listings have no equivalent of. */
    private var currentSearchSort: SortOption? = null
    private var showFilter = ShowFilter.All
    private var lastSearchStatusText = ""
    private var imageSearchActive = false
    private var suppressSearchInput = false
    // "Alive" search bar: the empty-state hint crossfades through what search can do
    // (AI image search, metadata search) plus live indexing progress when a pass is running.
    private var searchHintIndex = 0
    private var indexProgressCurrent = 0
    private var indexProgressTotal = 0
    private val searchHintRunnable = Runnable { cycleSearchHint() }
    private val activeFilters = LinkedHashSet<String>()

    // Incremental (paged) loading of the browse timeline grid so large libraries render fast.
    private var pagedItems: List<GalleryRepository.MediaItem> = emptyList()
    private var pagedDisplayedCount = 0
    private var pagedLastDay: String? = null
    private var pagedContext: String? = null   // null = paging inactive (e.g. albums/folders/search)
    private var pagingInFlight = false
    private var pagedPrefixCount = 0            // non-timeline cells prepended (e.g. pinned header)
    // Day headers only make sense while the list is in date order; name/size orders go flat.
    private var pagedDateOrdered = true

    // Collage thumbnail scale (1..5); adjustable by pinch gesture + Settings. Cached here so the
    // justified-rows builder doesn't hit SharedPreferences per day-row.
    private var collageScaleLevel = DesignTokens.COLLAGE_SCALE_DEFAULT

    private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
    private val dayFormatter = DateTimeFormatter.ofPattern("EEE, d", Locale.getDefault())
        .withZone(ZoneId.systemDefault())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.any { it.value } || hasPartialMediaAccess()) {
            initializeCore()
            maybeShowPartialAccessNotice()
            // The storage dialog has just been dismissed, so chaining the notification request
            // here (rather than concurrently in onCreate) makes it show on first launch.
            ensureNotificationPermission()
        } else {
            showPermissionDeniedDialog()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Optional: indexing/cleanup still run; this just enables their progress notifications. */ }

    private val viewerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        binding.imageGrid.cancelPendingInputEvents()
        val changed = result.data?.getBooleanExtra(ViewerActivity.ExtraContentChanged, false) == true
        if (changed) refreshVisibleItems()
        val similarUri = result.data?.getStringExtra(ViewerActivity.ExtraFindSimilarUri)
        if (similarUri != null) {
            val crop = result.data?.getFloatArrayExtra(ViewerActivity.ExtraFindSimilarCrop)
            searchSimilarImage(Uri.parse(similarUri), crop)
        }
    }

    private val cleanupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val changed = result.data?.getBooleanExtra(SmartCleanupActivity.ExtraContentChanged, false) == true
        if (changed) refreshVisibleItems()
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val accentChangedByResult =
            result.data?.getBooleanExtra(SettingsActivity.ExtraAccentChanged, false) == true
        val accentChangedByPreference =
            settingsLaunchAccentKey != null &&
                settingsLaunchAccentKey != IndexPreferences.getAccentColor(this)
        settingsLaunchAccentKey = null
        val accentChanged = accentChangedByResult || accentChangedByPreference
        if (accentChanged) recreate() else {
            applyBottomBarConfig()
            applyDisplaySettings()
        }
    }

    private val safeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Photos successfully moved into the Safe: remove the originals. They're already encrypted in
        // the vault, so delete permanently (not to the bin) — silently when we have direct access.
        @Suppress("DEPRECATION")
        val imported = result.data?.getParcelableArrayListExtra<Uri>(SafeActivity.ExtraImportedUris)
        if (result.resultCode == RESULT_OK && !imported.isNullOrEmpty()) {
            if (DeleteCoordinator.canDeleteDirectly(this)) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { imported.forEach { MediaFileOps.deleteFileDirect(this@MainActivity, it) } }
                    refreshVisibleItems()
                }
            } else {
                deleteUris(imported)
            }
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

    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val uris = pendingAllFilesDeleteUris
        pendingAllFilesDeleteUris = emptyList()
        if (uris.isEmpty()) return@registerForActivityResult
        if (StoragePermissions.hasAllFilesAccess(this)) {
            requestDelete(uris)
        } else {
            MetroBanner.show(this, "All-files access is required to delete items")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        val splashScreen = installSplashScreen()
        // Hold the splash until the first gallery content is laid out (keepSplash
        // flips false in dismissLoadingOverlay), with a hard timeout as a safety net.
        splashScreen.setKeepOnScreenCondition { keepSplash }
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)
        // No requestWindowFeature() here: installSplashScreen() has already added
        // window content, and the theme (windowNoTitle=true) covers the title bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.postDelayed({ keepSplash = false }, SPLASH_MAX_HOLD_MS)
        configureEdgeToEdge()

        // Process-death restore: reopen the section the user was browsing. Search/detail modes
        // restore to their parent section (their backing state is intentionally not persisted).
        if (savedInstanceState == null) {
            when (BottomBarConfig.defaultPage(this)) {
                BottomBarDestination.Collection -> activeSection = Section.Collection
                BottomBarDestination.Videos -> activeSection = Section.Videos
                BottomBarDestination.Albums -> activeSection = Section.Albums
                BottomBarDestination.Favorites -> activeSection = Section.Favorites
                BottomBarDestination.Folders -> activeSection = Section.Folders
                BottomBarDestination.Safe -> {
                    activeSection = Section.Collection
                    openSafeAfterInitialRender = true
                }
            }
        } else {
            savedInstanceState.getString(STATE_SECTION)?.let { saved ->
                activeSection = runCatching { Section.valueOf(saved) }.getOrDefault(activeSection)
            }
        }
        favoritesStore = FavoritesStore(this)
        albumPinStore = AlbumPinStore(this)
        smartAlbumStore = SmartAlbumStore(this)

        adapter = ImageAdapter(
            onPhotoClick = { item, view -> openMedia(item, view) },
            onSelectionChanged = ::renderSelectionState,
            onAlbumClick = ::openAlbum,
            onAlbumLongClick = ::showAlbumPinMenu,
            onFolderClick = ::openFolder,
            onFolderExpandClick = ::toggleFolderExpanded,
            onCreateSmartAlbum = { showCreateSmartAlbumDialog() },
            onDismissSmartAlbumOnboarding = {
                IndexPreferences.setSmartAlbumOnboardingDismissed(this)
                if (activeSection == Section.Albums && currentMode == Mode.Browse) renderAlbums()
            },
            onSortClick = { anchor -> onHeaderSortClick(anchor) }
        )
        adapter.useCollageLayout = IndexPreferences.isCollageLayout(this)
        adapter.gridColumnCount = IndexPreferences.getGridColumnCount(this)
        adapter.showAlbumFolderSize = IndexPreferences.isShowAlbumFolderSize(this)
        collageScaleLevel = IndexPreferences.getCollageScale(this)

        val initialSpanCount = if (adapter.useCollageLayout) DesignTokens.COLLAGE_SPAN_COUNT else adapter.gridColumnCount
        val layoutManager = GridLayoutManager(this, initialSpanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = adapter.spanSizeAt(position, layoutManager.spanCount)
        }

        binding.imageGrid.layoutManager = layoutManager

        val scaleGestureListener = ThumbnailScaleGestureListener { zoomIn ->
            if (adapter.useCollageLayout) adjustCollageScale(zoomIn) else adjustGridColumns(zoomIn, layoutManager)
        }
        val scaleGestureDetector = android.view.ScaleGestureDetector(this, scaleGestureListener)

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        val touchListener = View.OnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount > 1) {
                        adapter.endSelectionGesture()
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    adapter.endSelectionGesture()
                }
            }
            false
        }
        binding.imageGrid.setOnTouchListener(touchListener)

        binding.imageGrid.adapter = adapter
        binding.imageGrid.setHasFixedSize(true)
        searchSectionAdapter = SearchSectionAdapter(::openSearchSection)
        binding.searchSections.apply {
            this.layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = searchSectionAdapter
            setHasFixedSize(true)
            itemAnimator = null
        }
        binding.imageGrid.setItemViewCacheSize(12)
        // Photo/collage rows dominate fling churn; a deeper recycle pool avoids re-inflation
        // (the default pool keeps only 5 per view type).
        binding.imageGrid.recycledViewPool.setMaxRecycledViews(ImageAdapter.ViewTypePhoto, 24)
        binding.imageGrid.recycledViewPool.setMaxRecycledViews(ImageAdapter.ViewTypeCollage, 16)
        binding.imageGrid.addItemDecoration(StickyHeaderDecoration(adapter))
        binding.fastScrollIndicator.attach(binding.imageGrid, adapter)

        binding.imageGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val layoutManager = rv.layoutManager as GridLayoutManager
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val total = adapter.itemCount
                if (currentMode == Mode.Search) {
                    // Infinite scroll pagination for search results
                    if (fullSearchResults.isNotEmpty() &&
                        currentDisplayedSearchResultCount < fullSearchResults.size &&
                        lastVisible >= total - 6
                    ) {
                        paginateSearchResults()
                    }
                } else if (pagedContext != null) {
                    // Incremental loading of the browse timeline grid.
                    if (pagedDisplayedCount < pagedItems.size && lastVisible >= total - PAGE_PREFETCH_CELLS) {
                        paginateBrowse()
                    }
                }
            }
        })

        bindChrome()
        applyBottomBarConfig()
        bindBackNavigation()
        // Notification permission is requested only after the storage-permission flow resolves —
        // Android drops a second runtime-permission dialog launched while the first is still
        // pending, which previously lost the notification prompt on first launch.
        requestGalleryPermission()
        observeIndexWorker()
        observeFaceIndexWorker()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun bindChrome() {
        binding.menuBtn.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
        binding.addAlbumBtn.setOnClickListener { showCreateSmartAlbumDialog() }
        binding.searchSparkle.setOnClickListener {
            startActivity(Intent(this, IndexingActivity::class.java))
        }
        binding.searchTrailingBtn.setOnClickListener {
            if (currentMode == Mode.Search) showSortFilterSheet() else openSearch()
        }
        binding.searchClearBtn.setOnClickListener {
            if (currentMode == Mode.Search) onSearchClear()
        }

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
        binding.drawerSafe.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            openSafe()
        }
        binding.drawerPeople.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, PersonAlbumsActivity::class.java))
        }
        binding.drawerBin.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, BinActivity::class.java))
        }
        binding.drawerSmartCleanup.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            startSmartCleanup()
        }
        binding.drawerIndex.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            onIndexDrawerAction()
        }

        binding.drawerSettings.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            chargingPrefSnapshot = IndexPreferences.isChargingOnlyIndexing(this)
            settingsLaunchAccentKey = IndexPreferences.getAccentColor(this)
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        binding.bottomCollections.setOnClickListener { navigateToSection(Section.Collection) }
        binding.bottomAlbums.setOnClickListener { navigateToSection(Section.Albums) }
        binding.bottomFavorites.setOnClickListener { navigateToSection(Section.Favorites) }
        binding.bottomVideos.setOnClickListener { navigateToSection(Section.Videos) }
        binding.bottomFolders.setOnClickListener { navigateToSection(Section.Folders) }
        binding.bottomSafe.setOnClickListener { openSafe() }

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchDebounceJob?.cancel()
                submitSearch()
                true
            } else {
                false
            }
        }
        binding.searchInput.doAfterTextChanged { editable ->
            if (suppressSearchInput) return@doAfterTextChanged
            if (imageSearchActive) {
                imageSearchActive = false
                clearImageSearchThumb()
            }
            // Pause the rotating hint while typing; resume it once the field is empty again.
            if (editable.isNullOrEmpty()) startSearchHintCycle() else stopSearchHintCycle()
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

        binding.selectAllBtn.setOnClickListener { adapter.selectAll() }
        binding.shareSelectionBtn.setOnClickListener { shareSelected() }
        binding.safeSelectionBtn.setOnClickListener { moveSelectedToSafe() }
        binding.deleteSelectionBtn.setOnClickListener { confirmDeleteSelected() }
    }

    private fun configureEdgeToEdge() {
        configureCutoutMode()
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
            binding.selectionBar.updatePadding(bottom = systemInsets.bottom)
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
        val fullAccess =
            permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (fullAccess || hasPartialMediaAccess()) {
            // Partial (API 34 "Select photos") counts as granted — don't re-prompt every launch.
            initializeCore()
            maybeShowPartialAccessNotice()
            // Storage already granted (no dialog shown) — safe to prompt for notifications now.
            ensureNotificationPermission()
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

    /** API 34+ "Select photos": full media access denied but a user-selected subset granted. */
    private fun hasPartialMediaAccess(): Boolean {
        return Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_IMAGES
            ) != PackageManager.PERMISSION_GRANTED
    }

    /** One-shot banner explaining that only selected photos are visible, with a fix action. */
    private fun maybeShowPartialAccessNotice() {
        if (!hasPartialMediaAccess()) return
        MetroBanner.show(
            this,
            "Showing only the photos you selected",
            actionLabel = "Manage",
            durationMs = 8000
        ) {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    /** Denied outright: explain why the app can't run instead of dying with a toast. */
    private fun showPermissionDeniedDialog() {
        MetroDialog.confirm(
            context = this,
            title = "Photo access needed",
            message = "Pixa AI Gallery is a gallery — it can't show anything without permission to read " +
                "your photos and videos. Nothing ever leaves your phone.",
            positive = "Open settings",
            negative = "Exit",
            cancelable = false,
            onNegative = { finish() },
            onPositive = {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
                finish()
            }
        )
    }

    private fun initializeCore() {
        // -------------------- TRACK A (UI critical path) --------------
        lifecycleScope.launch {
            setBusy("Loading gallery…")
            try {
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
                    loadLibrarySnapshot(repo)
                }
                applyLibrarySnapshot(snapshot)
                peopleCollection = withContext(Dispatchers.IO) { loadPeopleCollection() }
                smartAlbums = withContext(Dispatchers.IO) { smartAlbumStore.getAll() }
                currentAlbum = null
                lastProgressRefresh = -1
                binding.progressBar.visibility = View.GONE
                binding.statusText.text =
                    indexedSummary(repo.indexedCount)
                renderCurrentState()
                if (openSafeAfterInitialRender) {
                    openSafeAfterInitialRender = false
                    binding.root.post { openSafe() }
                }
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
                maybePromptIndexingConsent()
                maybeShowGridGestureHint()
            }
        }
    }

    /**
     * One-time discoverability hint for the grid gestures (pinch to resize, long-press to select).
     * Shown once after the first successful library render, never again after dismissal/timeout.
     */
    private fun maybeShowGridGestureHint() {
        if (IndexPreferences.wasHintShown(this, IndexPreferences.HINT_PINCH_GRID)) return
        if (collectionItems.isEmpty()) return  // nothing to gesture on; keep the hint for later
        IndexPreferences.setHintShown(this, IndexPreferences.HINT_PINCH_GRID)
        IndexPreferences.setHintShown(this, IndexPreferences.HINT_LONG_PRESS_SELECT)
        MetroBanner.show(
            this,
            "Pinch to resize the grid · long-press a photo to select",
            actionLabel = "Got it",
            durationMs = 8000
        )
    }

    private fun loadEncodersInBackground() {
        // The CLIP encoders are ~135 MB and dominate cold-start memory pressure. Only warm them
        // eagerly when a background index pass is actually going to run (it needs them). When
        // indexing is paused, stopped, or complete, defer the load until the user searches — so an
        // idle/paused cold start opens fast instead of reloading the models against the first frames.
        if (shouldRunBackgroundIndexing()) {
            ensureEncodersLoaded(warmupDelayMs = ENCODER_WARMUP_DELAY_MS)
        } else {
            lifecycleScope.launch {
                maybeStartBackgroundIndexing()   // updates paused/idle status text + drawer label
                refreshSensitiveBlur()           // no-ops until the text encoder is loaded
            }
        }
    }

    /**
     * Loads the CLIP image/text encoders and the on-disk embedding caches exactly once, then wires
     * them into the repository. Safe to call from multiple entry points: returns the same in-flight
     * [CompletableDeferred] so callers can await readiness. On failure the guard is reset so the next
     * search can retry. [warmupDelayMs] lets the eager (indexing) path yield to the first frames.
     */
    private fun ensureEncodersLoaded(
        warmupDelayMs: Long = 0
    ): kotlinx.coroutines.CompletableDeferred<Boolean> {
        encodersReady?.let { return it }
        val ready = kotlinx.coroutines.CompletableDeferred<Boolean>()
        encodersReady = ready
        lifecycleScope.launch {
            if (warmupDelayMs > 0) delay(warmupDelayMs)
            val sharedEncoders = (application as GallerySearchApp).sharedEncoders
            val encoders = withContext(Dispatchers.IO) {
                // Persist the fixed ORT thread count before encoder construction so every startup
                // path uses the same session configuration without paying benchmark latency.
                ThreadBenchmark.getOrBenchmark(applicationContext)
                val imageAsync = async { runCatching { sharedEncoders.getImageEncoder() }.getOrNull() }
                val textAsync = async { runCatching { sharedEncoders.getTextEncoder() }.getOrNull() }
                imageAsync.await() to textAsync.await()
            }
            val image = encoders.first
            val text = encoders.second
            if (image == null) {
                Log.w(TAG, "Vision encoder failed to load; semantic search disabled.")
                encodersReady = null      // allow a retry on the next search
                ready.complete(false)
                // Metadata/filename search still works — say so instead of failing silently.
                MetroBanner.show(
                    this@MainActivity,
                    "AI engine couldn't load — search by name and date still works",
                    durationMs = 6000
                )
                return@launch
            }
            imageEncoder = image
            textEncoder = text
            repository?.attachEncoders(image, text)
            withContext(Dispatchers.IO) {
                repository?.loadCachedIndexForUris(allUris)
                repository?.loadCachedMetadataIndexForUris(allUris)
            }
            ready.complete(true)
            // If the user was already searching (metadata-only until now), re-run to add AI results.
            if (currentMode == Mode.Search && !binding.searchInput.text.isNullOrBlank()) {
                submitSearch()
            }
            maybeStartBackgroundIndexing()
            refreshSensitiveBlur()
        }
        return ready
    }

    private fun maybePromptIndexingConsent() {
        if (IndexPreferences.isIndexPaused(applicationContext)) {
            binding.statusText.text = "Indexing paused"
            updateIndexDrawerLabel()
            return
        }
        // User explicitly stopped indexing — don't silently restart it.
        if (IndexPreferences.isIndexStopped(applicationContext)) {
            updateIndexDrawerLabel()
            return
        }
        if (IndexPreferences.isIndexConsentGiven(applicationContext)) {
            maybeStartBackgroundIndexing()
            return
        }
        // First run: auto-start indexing once the initial UI/library load has completed.
        if (!IndexPreferences.wasIndexConsentAsked(applicationContext)) {
            enqueueBackgroundIndexing(showBanner = false)
            showIndexingStartedDialog()
        }
    }

    private fun showIndexingStartedDialog() {
        IndexPreferences.setIndexConsentAsked(applicationContext)
        val message =
            "Pixa AI Gallery is building a private search index of your photos with on-device AI, so you can " +
                "find them just by describing them — try \"beach\", \"my dog\" or \"receipts\".\n\n" +
                "This full scan runs once. After that, only newly added photos are indexed automatically.\n\n" +
                "It works entirely offline — nothing ever leaves your phone. Indexing runs in the background " +
                "and uses extra battery while it works; you can pause or stop it anytime from the side menu, " +
                "Settings, or the notification."
        MetroDialog.message(
            this,
            title = "Local AI photo search",
            message = message,
            iconRes = R.drawable.ic_deepix_ai_orb_24,
            positive = "Got it"
        )
    }

    private fun onIndexDrawerAction() {
        when {
            indexRunning -> pauseIndexing()
            IndexPreferences.isIndexPaused(this) -> resumeIndexing()
            else -> enqueueBackgroundIndexing()
        }
    }

    private fun pauseIndexing() {
        IndexPreferences.setIndexPaused(this, true)
        WorkManager.getInstance(this).cancelUniqueWork(INDEX_WORK_NAME)
        indexRunning = false
        binding.searchSparkle.setIndexing(false)
        binding.statusText.text = "Indexing paused"
        updateIndexDrawerLabel()
        MetroBanner.show(this, "Indexing paused")
    }

    private fun resumeIndexing() {
        IndexPreferences.setIndexPaused(this, false)
        IndexPreferences.setIndexStopped(this, false)
        enqueueIndexWork(ExistingWorkPolicy.KEEP)
        updateIndexDrawerLabel()
        MetroBanner.show(this, "Indexing resumed")
    }

    private fun updateIndexDrawerLabel() {
        binding.drawerIndex.text = when {
            indexRunning -> "pause indexing"
            IndexPreferences.isIndexPaused(this) -> "resume indexing"
            else -> "start indexing"
        }
    }

    private fun enqueueIndexWork(policy: ExistingWorkPolicy, initialDelaySeconds: Long = 0) {
        IndexWorker.cancelStatusNotification(this)
        val request = IndexWorker.buildWorkRequest(this, initialDelaySeconds)
        WorkManager.getInstance(this).enqueueUniqueWork(INDEX_WORK_NAME, policy, request)
    }

    /** The gallery view always shows every photo — folder selection only affects AI indexing. */
    private suspend fun loadLibrarySnapshot(repo: GalleryRepository): LibrarySnapshot {
        val fullSnapshot = repo.loadSnapshot(emptySet())
        val collectionItems = (fullSnapshot.imageItems + fullSnapshot.videoItems)
            .sortedByDescending { it.dateMillis }
        return LibrarySnapshot(
            albums = fullSnapshot.albums,
            imageItems = fullSnapshot.imageItems,
            collectionItems = collectionItems,
            videoItems = fullSnapshot.videoItems
        )
    }

    private fun applyLibrarySnapshot(snapshot: LibrarySnapshot) {
        albums = snapshot.albums
        imageItems = snapshot.imageItems
        collectionItems = snapshot.collectionItems
        videoItems = snapshot.videoItems
        allUris = snapshot.imageItems.map { it.uri }
        val scope = IndexScopeStore.getFolderIds(applicationContext)
        indexScopeUris = if (scope.isEmpty()) allUris
        else snapshot.imageItems.filter { it.bucketId in scope }.map { it.uri }
        librarySnapshotReady = true
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
            Section.Collection -> renderMediaSection(
                title = "collections",
                items = collectionItems,
                emptyCell = GalleryCell.Empty(
                    text = "No photos yet",
                    hint = "Photos and videos on this device show up here automatically."
                )
            )
            Section.Videos -> renderMediaSection(
                title = "videos",
                items = videoItems,
                emptyCell = GalleryCell.Empty(
                    text = "No videos yet",
                    hint = "Videos you record or save appear here.",
                    iconRes = R.drawable.ic_fluent_video_24_regular
                )
            )
            Section.Albums -> renderAlbums()
            Section.Favorites -> renderMediaSection(
                title = "favorites",
                items = favoriteItems,
                emptyCell = GalleryCell.Empty(
                    text = "No favorites yet",
                    hint = "Tap the heart on any photo to keep it here.",
                    iconRes = R.drawable.ic_fluent_heart_24_regular
                )
            )
            Section.Folders -> renderFolders()
        }
    }

    private fun renderFolders() {
        currentMode = Mode.Browse
        pagedContext = null  // folder tree is not a paged timeline
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
        val folderCount = roots.sumOf { 1 + it.folderCount }
        binding.resultCount.text = when {
            folderCount == 1 && collectionItems.size == 1 -> "1 folder · 1 item"
            folderCount == 1 -> "1 folder · ${collectionItems.size} items"
            collectionItems.size == 1 -> "$folderCount folders · 1 item"
            else -> "$folderCount folders · ${collectionItems.size} items"
        }
        adapter.replaceCells(
            if (cells.isEmpty()) {
                listOf(
                    GalleryCell.Empty(
                        text = "No folders yet",
                        hint = "Folders that contain photos or videos appear here.",
                        iconRes = R.drawable.ic_fluent_folder_24_regular
                    )
                )
            } else {
                cells
            }
        )
        resetGridToTop()
        updateFastScrollVisibility()
    }

    /**
     * Derive user-facing folder segments from a media item's filesystem path.
     * - Strips absolute storage prefixes (e.g. /storage/emulated/0/, /sdcard/).
     * - Drops the filename so files are grouped under their parent directory.
     * Falls back to the bucket name for items that have no usable path.
     */
    private fun folderSegmentsFor(
        path: String,
        bucketName: String,
        displayName: String?
    ): List<String> {
        var dir = path.trim().replace('\\', '/')
        if (dir.isEmpty()) return listOf(bucketName)

        if (dir.endsWith("/")) dir = dir.dropLast(1)

        // DATA contains the filename; RELATIVE_PATH does not. Compare against DISPLAY_NAME rather
        // than treating every dotted directory as a file (folders such as "com.example" are valid).
        val lastSlash = dir.lastIndexOf('/')
        if (lastSlash >= 0) {
            val last = dir.substring(lastSlash + 1)
            if (!displayName.isNullOrBlank() && last.equals(displayName, ignoreCase = true)) {
                dir = dir.substring(0, lastSlash)
            }
        }

        dir = dir
            .replace(Regex("^/storage/emulated/\\d+/"), "")
            .replace(Regex("^/storage/[^/]+/"), "")
            .removePrefix("/sdcard/")
            .trimStart('/')

        val segments = dir.split('/').filter { it.isNotBlank() }
        return segments.ifEmpty { listOf(bucketName) }
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
            val segments = folderSegmentsFor(item.path, item.bucketName, item.displayName)
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
            val childNodes = sortFolderNodes(node.children.values.map { toFolderNode(it) })
            val count = node.directItems.size + childNodes.sumOf { it.itemCount }
            val directCover = node.directItems.maxByOrNull { it.dateMillis }
            val newestChild = childNodes.maxByOrNull { it.latestDateMillis }
            val cover = directCover?.uri ?: newestChild?.coverUri
            return FolderNode(
                path = node.path,
                name = node.name,
                depth = node.depth,
                coverUri = cover,
                itemCount = count,
                imageCount = node.directItems.count { it.mediaType == GalleryRepository.MediaType.Image } +
                    childNodes.sumOf { it.imageCount },
                videoCount = node.directItems.count { it.mediaType == GalleryRepository.MediaType.Video } +
                    childNodes.sumOf { it.videoCount },
                folderCount = childNodes.size + childNodes.sumOf { it.folderCount },
                sizeBytes = node.directItems.sumOf { it.sizeBytes } + childNodes.sumOf { it.sizeBytes },
                latestDateMillis = maxOf(
                    node.directItems.maxOfOrNull { it.dateMillis } ?: 0L,
                    childNodes.maxOfOrNull { it.latestDateMillis } ?: 0L
                ),
                directItems = node.directItems,
                expanded = expandedStates[node.path] ?: (node.depth == 0),
                children = childNodes
            )
        }

        return sortFolderNodes(roots.values.map { toFolderNode(it) })
    }

    private fun sortFolderNodes(nodes: List<FolderNode>): List<FolderNode> = when (folderSort) {
        FolderSort.Name -> nodes.sortedBy { it.name.lowercase(Locale.getDefault()) }
        FolderSort.Newest -> nodes.sortedByDescending { it.latestDateMillis }
        FolderSort.MostItems -> nodes.sortedByDescending { it.itemCount }
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
        adapter.updateCells(flattenFolderNodes(folderTreeRoots))
        updateFastScrollVisibility()
    }

    private fun setAllFoldersExpanded(expanded: Boolean) {
        fun update(nodes: List<FolderNode>): List<FolderNode> = nodes.map { node ->
            node.copy(expanded = expanded, children = update(node.children))
        }
        folderTreeRoots = update(folderTreeRoots)
        adapter.updateCells(flattenFolderNodes(folderTreeRoots))
        updateFastScrollVisibility()
    }

    private fun showFolderOptions(anchor: View) {
        fun setSort(sort: FolderSort) {
            folderSort = sort
            val expanded = collectExpandedStates(folderTreeRoots)
            folderTreeRoots = buildFolderTree(collectionItems, expanded)
            adapter.updateCells(flattenFolderNodes(folderTreeRoots))
            resetGridToTop()
        }
        MetroDropdownMenu.show(
            anchor,
            listOf(
                MetroDropdownMenu.Item(
                    getString(R.string.folder_sort_name),
                    selected = folderSort == FolderSort.Name
                ) { setSort(FolderSort.Name) },
                MetroDropdownMenu.Item(
                    getString(R.string.folder_sort_newest),
                    selected = folderSort == FolderSort.Newest
                ) { setSort(FolderSort.Newest) },
                MetroDropdownMenu.Item(
                    getString(R.string.folder_sort_items),
                    selected = folderSort == FolderSort.MostItems
                ) { setSort(FolderSort.MostItems) },
                MetroDropdownMenu.Item(getString(R.string.folder_expand_all)) {
                    setAllFoldersExpanded(true)
                },
                MetroDropdownMenu.Item(getString(R.string.folder_collapse_all)) {
                    setAllFoldersExpanded(false)
                }
            )
        )
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

        renderPagedTimeline(
            items,
            GalleryCell.Empty("Nothing in this folder", iconRes = R.drawable.ic_fluent_folder_24_regular),
            "folder:${folder.path}"
        )
    }

    private fun renderMediaSection(
        title: String?,
        items: List<GalleryRepository.MediaItem>,
        emptyCell: GalleryCell.Empty
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

        // Pinned-albums strip is prepended to the Collections page (cheap; from in-memory state).
        val prefix = mutableListOf<GalleryCell>()
        if (expectedSection == Section.Collection && librarySnapshotReady) {
            val validIds = albums.map { it.id }.toSet() + smartAlbums.map { it.id }.toSet()
            albumPinStore.cleanup(validIds)
            ensureDefaultPins()
            val pinnedIds = albumPinStore.getPinnedAlbumIds()
            val smartById = smartAlbums.associate { it.id to it.toAlbum() }
            val albumById = (albums + smartById.values).associateBy { it.id }
            val pinnedAlbums = listOfNotNull(peopleCollection) +
                pinnedIds.filterNot { it == PeopleAlbumId }.mapNotNull { albumById[it] }
            if ((peopleCollection != null || IndexPreferences.isShowPinnedInCollections(this)) &&
                pinnedAlbums.isNotEmpty()
            ) {
                prefix += GalleryCell.PinnedAlbumsHeader(pinnedAlbums)
            }
        }

        renderPagedTimeline(items, emptyCell, "section:$expectedSection", prefix)
    }

    private fun renderAlbums() {
        currentMode = Mode.Browse
        pagedContext = null  // albums grid is not a paged timeline
        binding.searchPanel.visibility = View.GONE
        binding.resultCount.text = ""

        val validIds = albums.map { it.id }.toSet() + smartAlbums.map { it.id }.toSet()
        albumPinStore.cleanup(validIds)
        ensureDefaultPins()
        val pinnedIds = albumPinStore.getPinnedAlbumIds()

        val smartById = smartAlbums.associate { it.id to it.toAlbum() }
        val albumById = (albums + smartById.values).associateBy { it.id }

        val pinnedAlbums = pinnedIds.mapNotNull { albumById[it] }
        val albumDates = collectionItems.groupingBy { it.bucketId }
            .fold(0L) { latest, item -> maxOf(latest, item.dateMillis) }
        val smartDates = smartAlbums.associate { it.id to it.updatedAt }
        val currentSort = SortManager.optionFor(this, AlbumsSortScope)
        val albumComparator = when (currentSort) {
            SortOption.NameAsc -> Comparator { first, second ->
                String.CASE_INSENSITIVE_ORDER.compare(first.name, second.name)
            }
            SortOption.NameDesc -> Comparator { first, second ->
                String.CASE_INSENSITIVE_ORDER.compare(second.name, first.name)
            }
            SortOption.OldestFirst -> compareBy<GalleryRepository.Album> {
                smartDates[it.id] ?: albumDates[it.id] ?: 0L
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            else -> compareByDescending<GalleryRepository.Album> {
                smartDates[it.id] ?: albumDates[it.id] ?: 0L
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val normalAlbums = albums.filter { it.id !in pinnedIds }.sortedWith(albumComparator)

        val cells = mutableListOf<GalleryCell>()
        // Onboarding: nudge first-time users to try smart albums (until dismissed).
        if (smartAlbums.isEmpty() && !IndexPreferences.isSmartAlbumOnboardingDismissed(this)) {
            cells += GalleryCell.SmartAlbumOnboarding
        }
        if (pinnedAlbums.isNotEmpty()) {
            cells += GalleryCell.Header("PINNED", "")
            pinnedAlbums.forEach { cells += GalleryCell.AlbumCell(it) }
        }
        if (normalAlbums.isNotEmpty()) {
            cells += GalleryCell.Header("OTHERS", "", sortLabel = currentSort.label)
            normalAlbums.forEach { cells += GalleryCell.AlbumCell(it) }
        }

        adapter.replaceCells(
            if (cells.isEmpty()) {
                listOf(
                    GalleryCell.Empty(
                        text = "No albums yet",
                        hint = "Device albums show up here. You can also create a smart album " +
                            "that collects photos matching a description.",
                        iconRes = R.drawable.ic_fluent_album_24_regular,
                        actionLabel = "New smart album",
                        onAction = { showCreateSmartAlbumDialog() }
                    )
                )
            }
            else cells
        )
        resetGridToTop()
        updateFastScrollVisibility()
        updateTopBarForMode("albums")
        updateDrawerState()
        updateBottomPanelState()
        showBottomPanel()
    }

    /**
     * On first run (before the user has pinned anything), auto-pin the most
     * relevant device albums so the Albums tab isn't empty at the top.
     */
    private fun ensureDefaultPins() {
        if (albumPinStore.isInitialized()) return
        if (albums.isEmpty()) return // wait until the library has loaded
        val ranked = albums.sortedWith(
            compareByDescending<GalleryRepository.Album> { albumRelevanceScore(it.name) }
                .thenByDescending { it.count }
        )
        val defaults = ranked.take(4).map { it.id }
        if (defaults.isNotEmpty()) {
            albumPinStore.setPinnedOrder(defaults)
        } else {
            albumPinStore.markInitialized()
        }
    }

    /** Higher score => more likely to be a meaningful album worth pinning. */
    private fun albumRelevanceScore(name: String): Int {
        val n = name.lowercase(Locale.getDefault())
        return when {
            n.contains("camera") || n == "dcim" -> 100
            n.contains("screenshot") -> 80
            n.contains("download") -> 70
            n.contains("whatsapp") && n.contains("image") -> 65
            n.contains("whatsapp") -> 60
            n.contains("pictures") || n.contains("photos") -> 55
            n.contains("instagram") || n.contains("telegram") || n.contains("snapchat") -> 50
            else -> 0
        }
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

        renderPagedTimeline(
            items,
            GalleryCell.Empty("Nothing in this album", iconRes = R.drawable.ic_fluent_album_24_regular),
            "album:${album.id}"
        )
    }

    /**
     * Shows the fast-scroll bar whenever the current listing is long enough to be
     * worth scrolling. Measured from the actual scroll range after layout (not from
     * date-header count), so it appears on every image listing regardless of how the
     * photos are spread across months.
     */
    private fun updateFastScrollVisibility() {
        binding.imageGrid.removeCallbacks(fastScrollVisibilityRunnable)
        binding.imageGrid.post(fastScrollVisibilityRunnable)
    }

    private val fastScrollVisibilityRunnable = Runnable {
        val rv = binding.imageGrid
        val range = rv.computeVerticalScrollRange()
        val extent = rv.computeVerticalScrollExtent()
        val scrollable = extent > 0 && range > extent * FAST_SCROLL_MIN_RATIO
        binding.fastScrollIndicator.visibility = if (scrollable) View.VISIBLE else View.GONE
        if (scrollable) binding.fastScrollIndicator.syncToRecyclerView()
    }

    private fun openSearch() {
        renderJob?.cancel()
        // Start warming the encoders as soon as the user enters search, so the models are usually
        // ready by the time a query is submitted (they may have been deferred at startup).
        ensureEncodersLoaded()
        currentMode = Mode.Search
        pagedContext = null  // search has its own pagination
        binding.searchPanel.visibility = View.VISIBLE
        binding.screenTitle.visibility = View.GONE
        binding.searchBox.visibility = View.VISIBLE
        binding.resultCount.text = ""
        binding.imageGrid.removeCallbacks(fastScrollVisibilityRunnable)
        binding.fastScrollIndicator.visibility = View.GONE
        if (imageSearchActive) {
            stopSearchHintCycle()
            binding.searchInput.hint = "Photos similar to this image"
        } else {
            startSearchHintCycle()
        }
        updateSearchTrailingIcon()
        binding.searchInput.requestFocus()
        updateSearchMetaText()
        updateDrawerState()
        updateBottomPanelState()
        updateSearchPillState()
        if (effectiveQuery().isBlank()) {
            binding.searchResultSummary.text = ""
            adapter.replaceCells(listOf(GalleryCell.Empty(searchPlaceholderText())))
            resetGridToTop()
        } else {
            submitSearch()
        }
    }

    private fun updateSearchTrailingIcon() {
        val isSearching = currentMode == Mode.Search
        val res = if (isSearching) {
            R.drawable.ic_fluent_filter_24_regular
        } else {
            R.drawable.ic_fluent_search_24_regular
        }
        binding.searchTrailingBtn.setImageResource(res)
        binding.searchTrailingBtn.contentDescription = getString(
            if (isSearching) R.string.sort_filter else R.string.search
        )
        binding.searchClearBtn.visibility = if (isSearching) View.VISIBLE else View.GONE
    }

    private fun closeSearch(clearQuery: Boolean) {
        renderJob?.cancel()
        searchJob?.cancel()
        searchDebounceJob?.cancel()
        binding.searchPanel.visibility = View.GONE
        if (clearQuery) {
            binding.searchInput.text?.clear()
            activeFilters.clear()
        }
        imageSearchActive = false
        clearImageSearchThumb()
        binding.searchInput.clearFocus()
        currentMode = if (currentAlbum != null) Mode.AlbumDetail else Mode.Browse
        startSearchHintCycle()
        updateSearchTrailingIcon()
        renderCurrentState()
    }

    private fun updateSearchMetaText() {
        updateSearchPillState()
    }

    private fun searchPlaceholderText(): String {
        return when {
            currentAlbum != null -> "Search photos in this album"
            activeSection == Section.Favorites -> "Search favorite photos"
            else -> "Search photos"
        }
    }

    // ---------------------------------------------------------------------------------------------
    // "Alive" search bar — rotating hints
    // ---------------------------------------------------------------------------------------------

    private val orderedSearchHints = listOf(
        "Search with AI",
        "\"Beach at sunset\"",
        "\"Birthday cake\"",
        "\"Handwritten note\"",
        "\"screenshot\"",
        "\"Documents\"",
        "Search metadata",
        "Search by description"
    )

    /** The rotating hints shown while the field is empty. Indexing progress joins in only while a pass runs. */
    private fun searchHints(): List<CharSequence> {
        val hints = ArrayList<CharSequence>()
        if (indexRunning && indexProgressTotal > 0) {
            val pct = (indexProgressCurrent * 100 / indexProgressTotal).coerceIn(0, 100)
            hints += "AI Learning Photos\u2026 $pct%"
        }
        hints.addAll(orderedSearchHints)
        return hints
    }


    /** True only when the empty-state hint is actually visible and worth animating. */
    private fun canCycleSearchHint(): Boolean =
        !imageSearchActive && binding.searchInput.text.isNullOrEmpty()

    /** Starts (or restarts) the rotating hint. Cheap: one delayed Runnable on the view's own handler. */
    private fun startSearchHintCycle() {
        stopSearchHintCycle()
        if (!canCycleSearchHint()) return
        searchHintIndex = 0
        cycleSearchHint()
    }

    private fun stopSearchHintCycle() {
        binding.searchInput.removeCallbacks(searchHintRunnable)
        binding.searchInput.animate().cancel()
        binding.searchInput.alpha = 1f
    }

    private fun cycleSearchHint() {
        if (!canCycleSearchHint()) return
        val hints = searchHints()
        if (hints.isEmpty()) return
        val next = hints[searchHintIndex % hints.size]
        searchHintIndex = (searchHintIndex + 1) % hints.size
        val input = binding.searchInput
        // Crossfade the hint. The field is empty here, so fading the view only affects the hint text.
        input.animate()
            .alpha(0f)
            .setDuration(SEARCH_HINT_FADE_MS)
            .withEndAction {
                input.hint = next
                input.animate().alpha(1f).setDuration(SEARCH_HINT_FADE_MS).start()
            }
            .start()
        input.postDelayed(searchHintRunnable, SEARCH_HINT_INTERVAL_MS)
    }

    private fun submitSearch() {
        val query = effectiveQuery()
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
            binding.statusText.text = indexedSummary(repo.indexedCount)
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
                        query = query,
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

                if (!shouldSearchAi) {
                    if (!isSearchSessionCurrent(query, sessionMode, sessionSection, sessionAlbumId)) return@launch
                    renderSearchResults(
                        query = query,
                        results = withContext(Dispatchers.Default) {
                            buildMergedPhotoSearchResults(filteredItems, metadataHits, emptyList())
                        },
                        emptyText = "No matching results",
                        statusText = indexedSummary(repo.indexedCount)
                    )
                    return@launch
                }

                // Encoders may have been deferred at startup — load them on demand for AI search.
                // The CLIP pass is the slow part: show a loading screen, but when the fast metadata
                // pass already found matches, keep them visible underneath (progressive results).
                if (textEncoder == null) {
                    ensureEncodersLoaded().await()
                    if (!isSearchSessionCurrent(query, sessionMode, sessionSection, sessionAlbumId)) return@launch
                }
                val interimResults = if (metadataHits.isNotEmpty()) {
                    withContext(Dispatchers.Default) {
                        buildMergedPhotoSearchResults(filteredItems, metadataHits, emptyList())
                    }
                } else {
                    emptyList()
                }
                if (interimResults.isNotEmpty()) {
                    renderSearchResults(
                        query = query,
                        results = interimResults,
                        emptyText = "No matching results",
                        statusText = "Searching with AI…",
                        preserveSelection = true
                    )
                } else {
                    clearSearchSections()
                    adapter.replaceCells(listOf(GalleryCell.Loading(getString(R.string.search_loading))))
                    resetGridToTop()
                    binding.searchResultSummary.text = getString(R.string.search_loading)
                }

                val semanticResults = if (textEncoder == null) {
                    emptyList()
                } else {
                    withContext(Dispatchers.Default) { repo.search(parsedQuery.textQuery) }
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
                    query = query,
                    results = finalResults,
                    emptyText = "No matching results",
                    statusText = indexedSummary(repo.indexedCount),
                    preserveSelection = interimResults.isNotEmpty()
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
            val tagNameMap = allTags.associateBy { it.name.lowercase(Locale.getDefault()) }
            tagFilters.mapNotNull { filter ->
                val tag = tagNameMap[filter.value.lowercase(Locale.getDefault())]
                    ?: return@mapNotNull null
                val uris = db.getMediaUrisForTag(tag.id)
                tag.name.lowercase(Locale.getDefault()) to uris.toSet()
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

    /**
     * Builds timeline cells (day headers + per-day justified rows) for the slice [from, to).
     * [continuingDay] is the last day header emitted by the previous page, so a header isn't
     * repeated across page boundaries. Returns the cells plus the last day emitted (for the next
     * page). Pages must start on a day boundary (see [nextPageEnd]) so justified rows aren't split.
     *
     * When [dateOrdered] is false the list isn't in date order, so date headers would interleave
     * meaninglessly; the slice renders as one flat run instead.
     */
    private fun buildTimelinePage(
        items: List<GalleryRepository.MediaItem>,
        from: Int,
        to: Int,
        continuingDay: String?,
        useCollageLayout: Boolean,
        dateOrdered: Boolean
    ): Pair<List<GalleryCell>, String?> {
        val rowWidthPx = resources.displayMetrics.widthPixels
        if (!dateOrdered) {
            val cells = ArrayList<GalleryCell>(to - from)
            appendDayCells(cells, items.subList(from, to), useCollageLayout, rowWidthPx)
            return cells to null
        }
        val cells = ArrayList<GalleryCell>()
        var lastDay: String? = continuingDay
        var currentDayItems = ArrayList<GalleryRepository.MediaItem>()

        for (i in from until to) {
            val item = items[i]
            val dayKey = safeFormat(dayFormatter, item.dateMillis, "")
            if (dayKey != lastDay) {
                if (currentDayItems.isNotEmpty()) {
                    appendDayCells(cells, currentDayItems, useCollageLayout, rowWidthPx)
                    currentDayItems = ArrayList()
                }
                if (dayKey.isNotEmpty()) {
                    cells += GalleryCell.Header(
                        title = dayHeaderTitle(item.dateMillis),
                        subtitle = safeFormat(monthFormatter, item.dateMillis, "")
                    )
                    lastDay = dayKey
                }
            }
            currentDayItems.add(item)
        }

        if (currentDayItems.isNotEmpty()) {
            appendDayCells(cells, currentDayItems, useCollageLayout, rowWidthPx)
        }
        return cells to lastDay
    }

    /** End index (exclusive) of the next page within the active listing. */
    private fun nextPageEnd(from: Int): Int = pageEndWithin(pagedItems, from, pagedDateOrdered)

    /**
     * End index (exclusive) of the page starting at [from]: ~PAGE_SIZE items, extended to the day
     * boundary so justified rows aren't split mid-day. Non-date orders have no day grouping, so
     * they take the plain slice.
     */
    private fun pageEndWithin(
        items: List<GalleryRepository.MediaItem>,
        from: Int,
        dateOrdered: Boolean
    ): Int {
        val size = items.size
        var end = minOf(from + BROWSE_PAGE_SIZE, size)
        if (!dateOrdered) return end
        if (end in (from + 1) until size) {
            val boundaryDay = safeFormat(dayFormatter, items[end - 1].dateMillis, "")
            while (end < size &&
                (end - from) < BROWSE_PAGE_MAX &&
                safeFormat(dayFormatter, items[end].dateMillis, "") == boundaryDay
            ) {
                end++
            }
        }
        return end
    }

    /**
     * Hangs the sort affordance on the first header of a listing, or prepends a standalone row when
     * the order emits no headers to hang it on (name/size/modified). Applied to the first page only,
     * so later pages appended by [paginateBrowse] can't each grow their own chip.
     */
    private fun withSortAffordance(cells: List<GalleryCell>, label: String): List<GalleryCell> {
        val headerIndex = cells.indexOfFirst { it is GalleryCell.Header }
        if (headerIndex < 0) return listOf(GalleryCell.SortRow(label)) + cells
        return cells.toMutableList().also {
            it[headerIndex] = (it[headerIndex] as GalleryCell.Header).copy(sortLabel = label)
        }
    }

    /**
     * Renders a browse timeline incrementally: the first page is built and shown immediately, then
     * more pages are appended as the user scrolls (see [paginateBrowse]).
     *
     * This is the one place browse listings get ordered. [contextKey] carries the active sort so a
     * re-render under a new order can't be mistaken for the in-flight job of the previous one.
     */
    private fun renderPagedTimeline(
        items: List<GalleryRepository.MediaItem>,
        emptyCell: GalleryCell.Empty,
        scopeKey: String,
        prefixCells: List<GalleryCell> = emptyList()
    ) {
        renderJob?.cancel()
        val sortOption = SortManager.optionFor(this, scopeKey)
        val contextKey = "$scopeKey|${sortOption.key}"
        pagedItems = emptyList()
        pagedDisplayedCount = 0
        pagedLastDay = null
        pagedContext = contextKey
        pagedDateOrdered = sortOption.dateOrdered
        pagingInFlight = false
        pagedPrefixCount = prefixCells.size

        if (items.isEmpty()) {
            adapter.replaceCells(prefixCells + emptyCell)
            pagedContext = null
            resetGridToTop()
            updateFastScrollVisibility()
            binding.fastScrollIndicator.syncToRecyclerView()
            return
        }

        val useCollage = adapter.useCollageLayout
        renderJob = lifecycleScope.launch {
            val page = withContext(Dispatchers.Default) {
                val sorted = MediaSorter.sort(items, sortOption)
                val to = pageEndWithin(sorted, 0, sortOption.dateOrdered)
                val (cells, lastDay) = buildTimelinePage(sorted, 0, to, null, useCollage, sortOption.dateOrdered)
                FirstPage(sorted, cells, lastDay, to)
            }
            if (pagedContext != contextKey) return@launch
            pagedItems = page.items
            pagedDisplayedCount = page.end
            pagedLastDay = page.lastDay
            adapter.replaceCells(prefixCells + withSortAffordance(page.cells, sortOption.label))
            resetGridToTop()
            updateFastScrollVisibility()
            binding.fastScrollIndicator.syncToRecyclerView()
        }
    }

    /** Sorted items plus their first rendered page, produced together off the main thread. */
    private class FirstPage(
        val items: List<GalleryRepository.MediaItem>,
        val cells: List<GalleryCell>,
        val lastDay: String?,
        val end: Int
    )

    /** Appends the next timeline page when the user nears the bottom of the grid. */
    private fun paginateBrowse() {
        val ctx = pagedContext ?: return
        if (pagingInFlight || pagedDisplayedCount >= pagedItems.size) return
        val from = pagedDisplayedCount
        val to = nextPageEnd(from)
        if (to <= from) return
        val continuing = pagedLastDay
        val useCollage = adapter.useCollageLayout
        val items = pagedItems
        val dateOrdered = pagedDateOrdered
        pagingInFlight = true
        lifecycleScope.launch {
            val (cells, lastDay) = withContext(Dispatchers.Default) {
                buildTimelinePage(items, from, to, continuing, useCollage, dateOrdered)
            }
            if (pagedContext == ctx && pagedDisplayedCount == from) {
                pagedDisplayedCount = to
                pagedLastDay = lastDay
                adapter.updateCells(adapter.cells + cells)
                updateFastScrollVisibility()
            }
            pagingInFlight = false
        }
    }

    private fun appendDayCells(
        cells: MutableList<GalleryCell>,
        dayItems: List<GalleryRepository.MediaItem>,
        useCollageLayout: Boolean,
        rowWidthPx: Int
    ) {
        if (!useCollageLayout) {
            dayItems.forEach { cells += GalleryCell.Photo(it, featured = false) }
            return
        }
        appendJustifiedRows(cells, dayItems, rowWidthPx)
    }

    /**
     * Lays photos out as justified rows (Google-Photos / aves style): each row is
     * scaled so its photos keep their aspect ratio and together fill the full width.
     * Spans are distributed across [DesignTokens.COLLAGE_SPAN_COUNT] and every photo
     * in a row shares the same height so rows align cleanly.
     */
    private fun appendJustifiedRows(
        cells: MutableList<GalleryCell>,
        dayItems: List<GalleryRepository.MediaItem>,
        rowWidthPx: Int,
        sourcesFor: ((GalleryRepository.MediaItem) -> SearchSources)? = null
    ) {
        val spanCount = DesignTokens.COLLAGE_SPAN_COUNT
        val width = rowWidthPx.coerceAtLeast(1)
        val targetRowHeight = width / DesignTokens.collageRowsPerWidth(collageScaleLevel)
        // A row is "full" once the accumulated aspect ratios would shrink it to the
        // target height. targetAspectSum = width / targetRowHeight.
        val targetAspectSum = (width / targetRowHeight).toDouble()

        val row = ArrayList<GalleryRepository.MediaItem>()
        var aspectSum = 0.0

        fun aspectOf(item: GalleryRepository.MediaItem): Double {
            val w = item.width
            val h = item.height
            val ratio = if (w > 0 && h > 0) w.toDouble() / h.toDouble() else 1.0
            return ratio.coerceIn(
                DesignTokens.COLLAGE_MIN_ASPECT.toDouble(),
                DesignTokens.COLLAGE_MAX_ASPECT.toDouble()
            )
        }

        fun flush(stretchToFill: Boolean) {
            if (row.isEmpty()) return

            if (stretchToFill) {
                // Scale the row to a uniform height that fills the width, clamped so a single very
                // wide photo (or a barely-full last row) never collapses too short or balloons.
                val minH = targetRowHeight * DesignTokens.COLLAGE_MIN_ROW_HEIGHT_RATIO
                val maxH = targetRowHeight * DesignTokens.COLLAGE_MAX_ROW_HEIGHT_RATIO
                val rowHeight = (width / aspectSum).coerceIn(minH.toDouble(), maxH.toDouble())
                    .toInt().coerceAtLeast(1)
                val spans = IntArray(row.size)
                var assigned = 0
                for (i in row.indices) {
                    val s = Math.round((aspectOf(row[i]) / aspectSum) * spanCount).toInt().coerceAtLeast(1)
                    spans[i] = s
                    assigned += s
                }
                // Reconcile rounding so the row exactly fills the width.
                var diff = spanCount - assigned
                while (diff != 0) {
                    val idx = if (diff > 0) {
                        spans.indices.maxByOrNull { spans[it] }!!
                    } else {
                        spans.indices.filter { spans[it] > 1 }.maxByOrNull { spans[it] } ?: break
                    }
                    spans[idx] += if (diff > 0) 1 else -1
                    diff += if (diff > 0) -1 else 1
                }
                for (i in row.indices) {
                    cells += GalleryCell.Photo(
                        item = row[i],
                        searchSources = sourcesFor?.invoke(row[i]) ?: SearchSources(),
                        collageSpan = spans[i].coerceIn(1, spanCount),
                        collageHeightPx = rowHeight
                    )
                }
            } else {
                // Partial last row: keep photos at the natural target height and
                // let them sit left-aligned rather than stretching to full width.
                val rowHeight = targetRowHeight.toInt().coerceAtLeast(1)
                for (item in row) {
                    val span = Math.round(aspectOf(item) * targetRowHeight / width * spanCount)
                        .toInt().coerceIn(1, spanCount)
                    cells += GalleryCell.Photo(
                        item = item,
                        searchSources = sourcesFor?.invoke(item) ?: SearchSources(),
                        collageSpan = span,
                        collageHeightPx = rowHeight
                    )
                }
            }
            row.clear()
            aspectSum = 0.0
        }

        for (item in dayItems) {
            val ar = aspectOf(item)
            if (row.isNotEmpty() && aspectSum + ar >= targetAspectSum) {
                // The item would complete the row. Keep it here only if doing so lands the row
                // height closer to the target than leaving it out would — this avoids the greedy
                // overshoot that scales rows (and thumbnails) smaller than intended.
                val heightWith = width / (aspectSum + ar)
                val heightWithout = width / aspectSum
                val includeIsBetter =
                    Math.abs(heightWith - targetRowHeight) <= Math.abs(heightWithout - targetRowHeight)
                if (includeIsBetter) {
                    row.add(item)
                    aspectSum += ar
                    flush(stretchToFill = true)
                } else {
                    flush(stretchToFill = true)
                    row.add(item)
                    aspectSum = ar
                }
            } else {
                row.add(item)
                aspectSum += ar
            }
        }
        // Trailing row: stretch to fill when it's reasonably full (less empty space), otherwise
        // keep it left-aligned at the natural target height.
        val lastRowFilled = aspectSum >= targetAspectSum * DesignTokens.COLLAGE_LAST_ROW_FILL_THRESHOLD
        flush(stretchToFill = lastRowFilled)
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

    private suspend fun renderSearchResults(
        query: String,
        results: List<PhotoSearchResult>,
        emptyText: String,
        statusText: String,
        preserveSelection: Boolean = false
    ) {
        searchResultsMaster = results
        lastSearchStatusText = statusText
        currentDisplayedSearchResultCount = 0
        searchSectionResults = buildSearchSections(query, results)
        // Progressive renders (metadata first, AI merged in later) keep the user's tab choice
        // while it still has results; fresh searches default to the first available section.
        selectedSearchSection = if (preserveSelection) {
            searchSectionResults.firstOrNull { it.section == selectedSearchSection }?.section
                ?: searchSectionResults.firstOrNull()?.section
        } else {
            searchSectionResults.firstOrNull()?.section
        }
        searchLandingVisible = false
        searchSectionAdapter.selected = selectedSearchSection
        searchSectionAdapter.submitList(searchSectionResults)
        binding.searchSections.visibility = if (searchSectionResults.isEmpty()) View.GONE else View.VISIBLE

        if (results.isEmpty() || selectedSearchSection == null) {
            fullSearchResults = emptyList()
            adapter.replaceCells(listOf(searchEmptyCell(emptyText)))
            resetGridToTop()
            binding.searchResultSummary.text = getString(R.string.no_results)
            binding.statusText.text = statusText
            return
        }
        openSearchSection(selectedSearchSection!!)
    }

    /**
     * No-results state for search. If the index is still building, says so (partial results are
     * expected); otherwise nudges toward relaxing filters / trying different words.
     */
    private fun searchEmptyCell(emptyText: String): GalleryCell.Empty {
        val progress = IndexPreferences.getIndexProgressPercent(this)
        val stillIndexing = indexRunning || (progress in 1..99 && !IndexPreferences.isIndexStopped(this))
        val indexIdle = !indexRunning && progress < 100 &&
            (IndexPreferences.isIndexStopped(this) || IndexPreferences.isIndexPaused(this))
        return when {
            indexIdle -> GalleryCell.Empty(
                text = "AI search isn't ready",
                hint = "Indexing is ${if (progress > 0) "$progress% done and " else ""}paused, so AI " +
                    "search only sees part of your library. Resume it to search everything.",
                iconRes = R.drawable.ic_deepix_ai_orb_24,
                actionLabel = "Resume indexing",
                onAction = { resumeIndexing() }
            )
            stillIndexing -> GalleryCell.Empty(
                text = emptyText,
                hint = "Indexing is $progress% done — AI search only covers indexed photos yet. " +
                    "Try again in a bit, or use different words.",
                iconRes = R.drawable.ic_fluent_search_24_regular
            )
            activeFilters.isNotEmpty() -> GalleryCell.Empty(
                text = emptyText,
                hint = "Active filters narrow the results. Clear them or broaden the search.",
                iconRes = R.drawable.ic_fluent_filter_24_regular,
                actionLabel = "Clear filters",
                onAction = {
                    activeFilters.clear()
                    onFiltersChanged()
                }
            )
            else -> GalleryCell.Empty(
                text = emptyText,
                hint = "Try describing the scene differently — \"dog on the beach\", " +
                    "\"birthday cake\", \"receipts\".",
                iconRes = R.drawable.ic_fluent_search_24_regular
            )
        }
    }

    /**
     * Renders search results. Relevance and non-date orders keep the flat grid with infinite
     * pagination; date orders group results under month headers (the reference's philosophy).
     */
    private fun applySortAndShow() {
        fullSearchResults = sortResults(fullSearchResults, currentSearchSort)
        currentDisplayedSearchResultCount = 0

        if (currentSearchSort?.dateOrdered == true) {
            // Date-grouped: render all (capped) with day headers; pagination disabled.
            val capped = fullSearchResults.take(SEARCH_DISPLAY_CAP)
            currentDisplayedSearchResultCount = fullSearchResults.size
            adapter.replaceCells(buildSearchTimelineCells(capped))
        } else {
            val firstPage = fullSearchResults.take(SEARCH_PAGE_SIZE)
            currentDisplayedSearchResultCount = firstPage.size
            adapter.replaceCells(buildSearchPhotoCells(firstPage))
        }
        resetGridToTop()
        updateFastScrollVisibility()
        binding.fastScrollIndicator.syncToRecyclerView()
        updateSearchResultCount()
        binding.statusText.text = lastSearchStatusText
    }

    /**
     * Flat ranked results (Relevance). In collage mode they run through the justified-rows
     * builder so search matches the browse collage; in grid mode they stay 1-span photos.
     */
    private fun buildSearchPhotoCells(results: List<PhotoSearchResult>): List<GalleryCell> {
        if (!adapter.useCollageLayout) {
            return results.map {
                GalleryCell.Photo(item = it.item, featured = false, searchSources = it.sources)
            }
        }
        val cells = ArrayList<GalleryCell>(results.size)
        val sources = results.associate { it.item.uri to it.sources }
        appendJustifiedRows(cells, results.map { it.item }, resources.displayMetrics.widthPixels) {
            sources[it.uri] ?: SearchSources()
        }
        return cells
    }

    /** Groups ranked results under day headers while preserving the AI/text source badges. */
    private fun buildSearchTimelineCells(results: List<PhotoSearchResult>): List<GalleryCell> {
        val cells = ArrayList<GalleryCell>(results.size + 8)
        val rowWidthPx = resources.displayMetrics.widthPixels
        val sources = results.associate { it.item.uri to it.sources }
        var lastDay: String? = null
        var dayItems = ArrayList<GalleryRepository.MediaItem>()

        fun flushDay() {
            if (dayItems.isEmpty()) return
            if (adapter.useCollageLayout) {
                appendJustifiedRows(cells, dayItems, rowWidthPx) { sources[it.uri] ?: SearchSources() }
            } else {
                dayItems.forEach {
                    cells += GalleryCell.Photo(item = it, featured = false, searchSources = sources[it.uri] ?: SearchSources())
                }
            }
            dayItems = ArrayList()
        }

        for (result in results) {
            val dayKey = safeFormat(dayFormatter, result.item.dateMillis, "")
            if (dayKey != lastDay) {
                flushDay()
                if (dayKey.isNotEmpty()) {
                    cells += GalleryCell.Header(
                        title = dayHeaderTitle(result.item.dateMillis),
                        subtitle = safeFormat(monthFormatter, result.item.dateMillis, "")
                    )
                    lastDay = dayKey
                }
            }
            dayItems.add(result.item)
        }
        flushDay()
        return cells
    }

    /**
     * Orders search results. [option] null keeps the score ranking; otherwise the shared
     * [MediaSorter] orders the underlying items and the results follow.
     */
    private fun sortResults(results: List<PhotoSearchResult>, option: SortOption?): List<PhotoSearchResult> {
        if (option == null) return results // already ranked by score
        val byUri = results.associateBy { it.item.uri }
        return MediaSorter.sort(results.map { it.item }, option).mapNotNull { byUri[it.uri] }
    }

    private fun updateSearchResultCount() {
        val section = searchSectionResults.firstOrNull { it.section == selectedSearchSection }
        val total = section?.count ?: fullSearchResults.size
        binding.searchResultSummary.text = when {
            total == 0 -> getString(R.string.no_results)
            section != null -> "${section.section.label} · $total"
            total == 1 -> resources.getQuantityString(R.plurals.result_count, 1, 1)
            else -> getString(R.string.photos_count_summary, total)
        }
    }

    private fun onSearchClear() {
        if (effectiveQuery().isBlank() && !imageSearchActive) {
            closeSearch(clearQuery = true)
            return
        }
        activeFilters.clear()
        showFilter = ShowFilter.All
        imageSearchActive = false
        clearImageSearchThumb()
        suppressSearchInput = true
        binding.searchInput.text?.clear()
        suppressSearchInput = false
        updateSearchPillState()
        adapter.replaceCells(listOf(GalleryCell.Empty(searchPlaceholderText())))
        resetGridToTop()
        binding.searchResultSummary.text = ""
        binding.searchInput.requestFocus()
    }

    /** Metro-style bottom sheet: sort order, match engine, and a quick type filter. */
    private fun showSortFilterSheet() {
        val view = layoutInflater.inflate(R.layout.sheet_search_filter, null)
        val container = view.findViewById<LinearLayout>(R.id.sheetContainer)

        var pendingSort = currentSearchSort
        var pendingMode = searchMode
        var pendingShow = showFilter

        // We rebuild the whole option list whenever a selection changes so checkmarks update.
        var rebuild: () -> Unit = {}
        rebuild = {
            container.removeAllViews()
            addSheetHeader(container, "SORT BY")
            addSheetOption(container, "Relevance", pendingSort == null) {
                pendingSort = null
                rebuild()
            }
            SortOption.MEDIA_OPTIONS.forEach { option ->
                addSheetOption(container, option.label, pendingSort == option) {
                    pendingSort = option
                    rebuild()
                }
            }
            addSheetHeader(container, "MATCH")
            listOf(
                SearchMode.Hybrid to "Smart + text",
                SearchMode.AiOnly to "Smart only",
                SearchMode.MetadataOnly to "Text only"
            ).forEach { (mode, label) ->
                addSheetOption(container, label, pendingMode == mode) {
                    pendingMode = mode
                    rebuild()
                }
            }
            addSheetHeader(container, "SHOW")
            ShowFilter.entries.forEach { show ->
                addSheetOption(container, show.label, pendingShow == show) {
                    pendingShow = show
                    rebuild()
                }
            }
        }
        rebuild()

        val dialog = AlertDialog.Builder(this, R.style.Theme_GallerySearch_Dialog).setView(view).create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setGravity(android.view.Gravity.BOTTOM)
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        view.findViewById<TextView>(R.id.sheetCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.sheetApply).setOnClickListener {
            dialog.dismiss()
            applySheetSelections(pendingSort, pendingMode, pendingShow)
        }
        dialog.show()
    }

    private fun addSheetHeader(container: LinearLayout, title: String) {
        container.addView(TextView(this).apply {
            text = title
            textSize = 11f
            isAllCaps = true
            letterSpacing = 0.06f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.metroTextTertiary))
            setPadding(dp(20), dp(16), dp(20), dp(6))
        })
    }

    private fun addSheetOption(container: LinearLayout, label: String, selected: Boolean, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.metro_row_pressed)
            isClickable = true
            isFocusable = true
            setPadding(dp(20), dp(13), dp(20), dp(13))
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 15f
            includeFontPadding = false
            setTextColor(if (selected) DesignTokens.accent(this@MainActivity) else Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_fluent_checkmark_24_regular)
            imageTintList = ColorStateList.valueOf(DesignTokens.accent(this@MainActivity))
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            visibility = if (selected) View.VISIBLE else View.INVISIBLE
        })
        container.addView(row)
    }

    private fun applySheetSelections(sort: SortOption?, mode: SearchMode, show: ShowFilter) {
        currentSearchSort = sort
        searchMode = mode
        showFilter = show
        if (imageSearchActive) {
            // Re-sort the existing similar-image results without re-running the search.
            if (searchResultsMaster.isNotEmpty()) applySortAndShow()
            return
        }
        if (effectiveQuery().isBlank()) {
            adapter.replaceCells(listOf(GalleryCell.Empty(searchPlaceholderText())))
            resetGridToTop()
            binding.searchResultSummary.text = ""
        } else {
            submitSearch()
        }
    }

    private fun showFilterToken(): String = when (showFilter) {
        ShowFilter.All -> ""
        ShowFilter.Favorites -> "fav=yes"
        ShowFilter.Screenshots -> "is=screenshot"
    }

    private fun paginateSearchResults() {
        if (currentDisplayedSearchResultCount >= fullSearchResults.size) return

        val nextBatch = fullSearchResults
            .drop(currentDisplayedSearchResultCount)
            .take(SEARCH_PAGE_SIZE)

        if (nextBatch.isEmpty()) return

        val newCells = buildSearchPhotoCells(nextBatch)

        currentDisplayedSearchResultCount += nextBatch.size
        adapter.updateCells(adapter.cells + newCells)
        updateSearchResultCount()
    }

    /** Image-to-image search across the whole library using the CLIP image embedding. */
    private fun searchSimilarImage(uri: Uri, cropRect: FloatArray? = null) {
        val repo = repository ?: return
        val name = imageItems.firstOrNull { it.uri == uri }?.displayName ?: "image"
        val isRegion = cropRect != null && cropRect.size >= 4

        currentMode = Mode.Search
        imageSearchActive = true
        binding.searchPanel.visibility = View.VISIBLE
        suppressSearchInput = true
        binding.searchInput.setText("")
        suppressSearchInput = false
        showImageSearchThumb(uri, cropRect)
        binding.imageGrid.removeCallbacks(fastScrollVisibilityRunnable)
        binding.fastScrollIndicator.visibility = View.GONE
        binding.screenTitle.visibility = View.GONE
        binding.searchBox.visibility = View.VISIBLE
        updateSearchTrailingIcon()
        updateDrawerState()
        updateBottomPanelState()
        updateSearchPillState()
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.text = if (isRegion) "Finding photos similar to this region…" else "Finding similar photos…"
        binding.resultCount.text = ""
        // Consistent with text search: a loading screen while the embedding pass runs.
        adapter.replaceCells(listOf(GalleryCell.Loading(getString(R.string.search_loading))))
        resetGridToTop()
        binding.searchResultSummary.text = getString(R.string.search_loading)

        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            // Image-to-image needs the CLIP image encoder; load it on demand if it was deferred.
            if (imageEncoder == null) ensureEncodersLoaded().await()
            if (!imageSearchActive || currentMode != Mode.Search) return@launch
            val pool = imageItems
            val byUri = pool.associateBy { it.uri }
            val hits = withContext(Dispatchers.Default) {
                val embedding = if (isRegion) {
                    val region = android.graphics.RectF(cropRect!![0], cropRect[1], cropRect[2], cropRect[3])
                    repo.imageEmbeddingForRegion(uri, region)
                } else {
                    repo.imageEmbedding(uri)
                } ?: return@withContext null
                repo.searchByEmbedding(embedding, excludeUri = uri.toString(), floor = SIMILAR_IMAGE_FLOOR, limit = 500)
            }
            binding.progressBar.visibility = View.GONE
            if (!imageSearchActive || currentMode != Mode.Search) return@launch
            if (hits == null) {
                MetroBanner.show(this@MainActivity, "Couldn't analyze this image yet — try after indexing")
                renderSearchResults("", emptyList(), "No similar photos", "Similar photos")
                return@launch
            }
            val results = hits.mapNotNull { hit ->
                byUri[hit.uri]?.let { item ->
                    PhotoSearchResult(item, SearchSources(ai = true, metadata = false), hit.score)
                }
            }
            currentSearchSort = null
            val title = if (isRegion) "Similar to region" else "Similar to $name"
            renderSearchResults("", results, "No similar photos found", title)
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
            effectiveQuery() == query &&
            searchMode == mode &&
            activeSection == section &&
            currentAlbum?.id == albumId
    }

    /** Composes free text, selected filters, and the media filter into the search query. */
    private fun effectiveQuery(): String {
        val text = binding.searchInput.text?.toString()?.trim().orEmpty()
        return (listOf(text) + activeFilters + listOf(showFilterToken()))
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
    }

    private fun onFiltersChanged() {
        if (imageSearchActive) {
            imageSearchActive = false
            clearImageSearchThumb()
        }
        if (effectiveQuery().isBlank()) {
            clearSearchSections()
            adapter.replaceCells(listOf(GalleryCell.Empty(searchPlaceholderText())))
            resetGridToTop()
            binding.resultCount.text = ""
        } else {
            submitSearch()
        }
    }

    /** Builds the virtual People collection from persisted recognized faces. */
    private suspend fun loadPeopleCollection(): GalleryRepository.Album? {
        val faceDao = com.devomind.gallerysearch.db.GalleryDatabase
            .getInstance(applicationContext)
            .faceDao()
        val faceCount = faceDao.countAll()
        if (faceCount < MinPeopleFaces) return null
        return GalleryRepository.Album(
            id = PeopleAlbumId,
            name = getString(R.string.people_title),
            count = faceCount,
            coverUri = faceDao.bestRecognizedFacePhotoUri()?.let(Uri::parse)
        )
    }

    private fun refreshPeopleCollection() {
        val requestGeneration = ++peopleCollectionRefreshGeneration
        lifecycleScope.launch {
            val updated = withContext(Dispatchers.IO) { loadPeopleCollection() }
            if (requestGeneration != peopleCollectionRefreshGeneration || updated == peopleCollection) return@launch
            peopleCollection = updated
            if (librarySnapshotReady && activeSection == Section.Collection && currentMode == Mode.Browse) {
                binding.imageGrid.post {
                    if (!isFinishing && librarySnapshotReady &&
                        activeSection == Section.Collection && currentMode == Mode.Browse
                    ) renderCurrentSection()
                }
            }
        }
    }

    private fun updateSearchPillState() {
        if (binding.searchPanel.visibility != View.VISIBLE || effectiveQuery().isNotBlank()) return
        clearSearchSections()
    }

    private fun clearSearchSections() {
        searchSectionResults = emptyList()
        selectedSearchSection = null
        searchLandingVisible = false
        searchSectionAdapter.selected = null
        searchSectionAdapter.submitList(emptyList())
        binding.searchSections.visibility = View.GONE
    }

    private fun openSearchSection(section: SearchSection) {
        val group = searchSectionResults.firstOrNull { it.section == section } ?: return
        selectedSearchSection = section
        searchLandingVisible = false
        searchSectionAdapter.selected = section
        fullSearchResults = group.results
        applySortAndShow()
    }

    private suspend fun buildSearchSections(
        query: String,
        results: List<PhotoSearchResult>
    ): List<SearchSectionResult> {
        if (results.isEmpty()) return emptyList()
        val resultByUri = results.associateBy { it.item.uri.toString() }
        fun fromUris(section: SearchSection, count: Int, uris: Set<String>): SearchSectionResult? {
            val matches = uris.mapNotNull(resultByUri::get)
            return matches.takeIf { it.isNotEmpty() && count > 0 }?.let { SearchSectionResult(section, count, it) }
        }
        val text = StructuredSearch.parse(query).textQuery.trim().lowercase(Locale.getDefault())
        val smart = results.filter { it.sources.ai }
        val metadata = results.filter { it.sources.metadata }
        val matchedAlbums = if (text.isBlank()) emptyList() else albums.filter { it.name.lowercase(Locale.getDefault()).contains(text) }
        val albumUris = matchedAlbums.flatMapTo(LinkedHashSet()) { album ->
            currentSearchPhotoItems().asSequence().filter { it.bucketId == album.id }.map { it.uri.toString() }.toList()
        }
        val matchedTags = if (text.isBlank()) emptyList() else allTags.filter { it.name.lowercase(Locale.getDefault()).contains(text) }
        val tagUris = matchedTags.flatMapTo(LinkedHashSet()) { tag -> tagUriMap[tag.id].orEmpty() }
        val resultUris = results.map { it.item.uri.toString() }
        val db = dbRepository
        val people = db?.recognizedPeopleForPhotoUris(resultUris)
        val locations = db?.photoUrisWithLocation(resultUris).orEmpty()
        return buildList {
            smart.takeIf { it.isNotEmpty() }?.let { add(SearchSectionResult(SearchSection.Smart, it.size, it)) }
            metadata.takeIf { it.isNotEmpty() }?.let { add(SearchSectionResult(SearchSection.Metadata, it.size, it)) }
            fromUris(SearchSection.Albums, matchedAlbums.size, albumUris)?.let(::add)
            fromUris(SearchSection.Tags, matchedTags.size, tagUris)?.let(::add)
            fromUris(SearchSection.People, people?.personIds?.size ?: 0, people?.photoUris.orEmpty())?.let(::add)
            fromUris(SearchSection.Locations, locations.size, locations)?.let(::add)
        }
    }

    private fun createSearchPillView(
        label: String,
        selected: Boolean,
        onClick: () -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (selected) R.drawable.search_filter_chip_active_bg else R.drawable.search_filter_chip_bg
            )
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            isClickable = true
            isFocusable = true
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 14f
                includeFontPadding = false
                maxWidth = dp(160)
                ellipsize = TextUtils.TruncateAt.END
                setSingleLine()
                setTextColor(if (selected) Color.WHITE else ContextCompat.getColor(this@MainActivity, R.color.metroTextPrimary))
            })
            setOnClickListener { onClick() }
        }
    }

    private fun showImageSearchThumb(uri: Uri, cropRect: FloatArray? = null) {
        binding.searchImageThumb.visibility = View.VISIBLE
        binding.searchImageThumb.setOnClickListener { clearImageSearch() }
        binding.searchSparkle.visibility = View.GONE
        stopSearchHintCycle()
        binding.searchInput.hint = if (cropRect != null) "Photos similar to this region" else "Photos similar to this image"

        if (cropRect == null || cropRect.size < 4) {
            com.bumptech.glide.Glide.with(this)
                .load(uri)
                .centerCrop()
                .into(binding.searchImageThumb)
            return
        }

        // Region search: show the cropped preview so the user sees exactly what's being matched.
        val repo = repository
        val region = android.graphics.RectF(cropRect[0], cropRect[1], cropRect[2], cropRect[3])
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { repo?.regionThumbnail(uri, region) }
            if (!imageSearchActive) return@launch
            if (bitmap != null) {
                com.bumptech.glide.Glide.with(this@MainActivity).clear(binding.searchImageThumb)
                binding.searchImageThumb.scaleType = ImageView.ScaleType.CENTER_CROP
                binding.searchImageThumb.setImageBitmap(bitmap)
            } else {
                com.bumptech.glide.Glide.with(this@MainActivity).load(uri).centerCrop().into(binding.searchImageThumb)
            }
        }
    }

    private fun clearImageSearchThumb() {
        binding.searchImageThumb.visibility = View.GONE
        binding.searchImageThumb.setOnClickListener(null)
        com.bumptech.glide.Glide.with(this).clear(binding.searchImageThumb)
        binding.searchSparkle.visibility = View.VISIBLE
        startSearchHintCycle()
    }

    private fun clearImageSearch() {
        imageSearchActive = false
        clearImageSearchThumb()
        updateSearchPillState()
        if (effectiveQuery().isBlank()) {
            adapter.replaceCells(listOf(GalleryCell.Empty(searchPlaceholderText())))
            resetGridToTop()
            binding.resultCount.text = ""
        } else {
            submitSearch()
        }
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
        return if (filterCount <= 0) {
            resources.getQuantityString(R.plurals.result_count, resultCount, resultCount)
        } else {
            resources.getQuantityString(R.plurals.photos_match_filters, resultCount, resultCount, filterCount)
        }
    }

    private fun resetGridToTop() {
        // Clear GridLayoutManager's span caches so a freshly replaced cell list
        // lays out with the correct span sizes on the very first pass (otherwise
        // collage tiles briefly render with stale span widths).
        (binding.imageGrid.layoutManager as? GridLayoutManager)?.spanSizeLookup?.apply {
            isSpanIndexCacheEnabled = false
            isSpanGroupIndexCacheEnabled = false
            invalidateSpanIndexCache()
            invalidateSpanGroupIndexCache()
        }
        binding.imageGrid.stopScroll()
        binding.imageGrid.post {
            if (!binding.imageGrid.isAttachedToWindow) return@post
            binding.imageGrid.scrollToPosition(0)
            binding.fastScrollIndicator.syncToRecyclerView()
            dismissLoadingOverlay()
        }
    }

    private var loadingOverlayDismissed = false
    private var keepSplash = true

    private fun dismissLoadingOverlay() {
        keepSplash = false
        if (loadingOverlayDismissed) return
        loadingOverlayDismissed = true
        binding.loadingOverlay.animate()
            .alpha(0f)
            .setDuration(220)
            .withEndAction { binding.loadingOverlay.visibility = View.GONE }
            .start()
    }

    private fun safeFormat(
        formatter: DateTimeFormatter,
        millis: Long,
        fallback: String
    ): String {
        return runCatching { formatter.format(Instant.ofEpochMilli(millis)) }.getOrDefault(fallback)
    }

    /** Friendly day header title: "Today", "Yesterday", or the short weekday + date. */
    private fun dayHeaderTitle(millis: Long): String {
        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now(ZoneId.systemDefault())
        return when (date) {
            today -> getString(R.string.today)
            today.minusDays(1) -> getString(R.string.yesterday)
            else -> safeFormat(dayFormatter, millis, "")
        }
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
        if (album.id == PeopleAlbumId) {
            startActivity(Intent(this, PersonAlbumsActivity::class.java))
        } else if (album.isSmart) {
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
        MetroDropdownMenu.show(
            anchor,
            listOf(
                MetroDropdownMenu.Item(if (isPinned) "Unpin album" else "Pin album") {
                    if (isPinned) albumPinStore.unpin(album.id) else albumPinStore.pin(album.id)
                    if (currentMode == Mode.Browse && currentAlbum == null) {
                        renderCurrentSection()
                    }
                }
            )
        )
    }

    private fun showSmartAlbumMenu(album: GalleryRepository.Album, anchor: View) {
        val smart = smartAlbums.find { it.id == album.id } ?: return
        MetroDropdownMenu.show(
            anchor,
            listOf(
                MetroDropdownMenu.Item("Refresh") { handleSmartAlbumRefresh(smart) },
                MetroDropdownMenu.Item("Rename") { showRenameSmartAlbumDialog(smart) },
                MetroDropdownMenu.Item("Edit prompt") { showEditPromptDialog(smart) },
                MetroDropdownMenu.Item("Unpin") {
                    albumPinStore.unpin(album.id)
                    renderCurrentSection()
                },
                MetroDropdownMenu.Item("Delete", danger = true) { confirmDeleteSmartAlbum(smart) }
            )
        )
    }

    private fun handleSmartAlbumRefresh(smart: SmartAlbum) {
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.text = "Refreshing…"
        lifecycleScope.launch {
            refreshSmartAlbum(smart)
            binding.progressBar.visibility = View.GONE
            val repo = repository
            val summary = if (repo != null)
                indexedSummary(repo.indexedCount) else ""
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
        MetroDialog.input(
            this,
            title = "Rename album",
            label = "NAME",
            positive = "Rename",
            initial = smart.name,
            iconRes = R.drawable.ic_deepix_ai_orb_24
        ) { newName ->
            smartAlbumStore.upsert(smart.copy(name = newName, updatedAt = System.currentTimeMillis()))
            smartAlbums = smartAlbumStore.getAll()
            if (currentMode == Mode.SmartAlbumDetail && currentSmartAlbum?.id == smart.id) {
                currentSmartAlbum = smartAlbumStore.get(smart.id)
            }
            renderCurrentSection()
        }
    }

    private fun showEditPromptDialog(smart: SmartAlbum) {
        MetroDialog.input(
            this,
            title = "Edit prompt",
            label = "WHAT TO FIND",
            positive = "Save & refresh",
            initial = smart.prompt,
            iconRes = R.drawable.ic_deepix_ai_orb_24
        ) { newPrompt ->
            val updated = smart.copy(prompt = newPrompt, updatedAt = System.currentTimeMillis())
            smartAlbumStore.upsert(updated)
            smartAlbums = smartAlbumStore.getAll()
            handleSmartAlbumRefresh(updated)
        }
    }

    private fun confirmDeleteSmartAlbum(smart: SmartAlbum) {
        MetroDialog.confirm(
            this,
            title = "Delete smart album?",
            message = "Delete \"${smart.name}\"? This won't delete any actual photos.",
            positive = "Delete",
            danger = true
        ) {
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

        binding.resultCount.text = if (items.isEmpty()) {
            ""
        } else {
            resources.getQuantityString(R.plurals.result_count, items.size, items.size)
        }
        binding.progressBar.visibility = View.GONE
        binding.statusText.text = indexedSummary(repository?.indexedCount ?: 0)
        renderPagedTimeline(
            items,
            GalleryCell.Empty(
                text = "No matches yet",
                hint = "This smart album fills itself as matching photos are indexed. " +
                    "Refresh it from the album menu, or broaden its description.",
                iconRes = R.drawable.ic_deepix_ai_orb_24
            ),
            "smart:${smart.id}"
        )
    }

    private fun showCreateSmartAlbumDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_smart_album, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.smartAlbumName)
        val promptInput = dialogView.findViewById<EditText>(R.id.smartAlbumPrompt)
        val suggestionRow = dialogView.findViewById<LinearLayout>(R.id.smartAlbumSuggestions)
        val cancelBtn = dialogView.findViewById<TextView>(R.id.smartAlbumCancel)
        val createBtn = dialogView.findViewById<TextView>(R.id.smartAlbumCreate)

        val dialog = AlertDialog.Builder(this, R.style.Theme_GallerySearch_Dialog)
            .setView(dialogView)
            .create()

        // Quick-start prompt chips: tapping fills the prompt (and the name if empty).
        val suggestions = listOf(
            "Beach days" to "sunset at the beach",
            "Food" to "food and meals",
            "Pets" to "cats and dogs",
            "Nature" to "mountains and landscapes",
            "Cars" to "cars and vehicles",
            "Documents" to "documents and screenshots"
        )
        suggestions.forEach { (label, prompt) ->
            val chip = createSearchPillView(label = label, selected = false) {
                promptInput.setText(prompt)
                promptInput.setSelection(prompt.length)
                if (nameInput.text.isNullOrBlank()) nameInput.setText(label)
            }
            suggestionRow.addView(chip)
        }

        cancelBtn.setOnClickListener { dialog.dismiss() }
        createBtn.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val prompt = promptInput.text.toString().trim()
            if (name.isEmpty() || prompt.isEmpty()) {
                MetroBanner.show(this, "Album name and description are required")
                return@setOnClickListener
            }
            dialog.dismiss()
            createSmartAlbum(name, prompt)
        }

        dialog.show()
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
            binding.statusText.text = indexedSummary(repo.indexedCount)
            if (resultUris.isEmpty()) {
                MetroBanner.show(this@MainActivity, "No matches yet — you can refresh later")
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

        // Encoders are deferred on paused/idle cold starts (loadEncodersInBackground); load them
        // on demand here, else repo.search() silently returns nothing and smart albums come up
        // empty despite an existing index.
        if (shouldSearchAi && textEncoder == null) {
            ensureEncodersLoaded().await()
        }

        val semanticResults = if (shouldSearchAi && textEncoder != null) {
            withContext(Dispatchers.Default) { repo.search(query) }
        } else emptyList()

        val merged = withContext(Dispatchers.Default) {
            buildMergedPhotoSearchResults(candidateItems, metadataHits, semanticResults)
        }
        return merged.map { it.item.uri }
    }

    private suspend fun refreshSmartAlbum(smart: SmartAlbum): SmartAlbum? {
        repository ?: return null
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

    // ---------------------------------------------------------------------------------------------
    // Smart cleanup — opens a dedicated, interactive screen.
    // ---------------------------------------------------------------------------------------------

    private fun startSmartCleanup() {
        val repo = repository ?: run {
            MetroBanner.show(this, "Still loading — try again in a moment")
            return
        }
        val images = collectionItems.filter { it.mediaType == GalleryRepository.MediaType.Image }
        if (images.isEmpty()) {
            MetroBanner.show(this, "No photos to clean up yet")
            return
        }
        CleanupHandoff.items = images
        CleanupHandoff.indexedCount = repo.indexedCount
        cleanupLauncher.launch(Intent(this, SmartCleanupActivity::class.java))
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
        // A center-cropped grid frame cannot transition cleanly into a fit-mode video surface;
        // Android briefly stretches that frame before playback. Photos retain the shared transition.
        val transitionName = if (item.mediaType == GalleryRepository.MediaType.Image) {
            ViewCompat.getTransitionName(sharedView) ?: ""
        } else {
            null
        }
        val intent = Intent(this, ViewerActivity::class.java).apply {
            putExtra(ViewerActivity.ExtraMarker, item.uri.toString())
            putExtra(ViewerActivity.ExtraPosition, position)
            transitionName?.let { putExtra(ViewerActivity.ExtraTransitionName, it) }
            currentAlbum
                ?.takeIf { !smartAlbumStore.isSmartId(it.id) }
                ?.let { album ->
                    putExtra(ViewerActivity.ExtraAlbumId, album.id)
                    putExtra(ViewerActivity.ExtraAlbumName, album.name)
                }
        }

        if (transitionName != null) {
            val options = ActivityOptionsCompat.makeSceneTransitionAnimation(this, sharedView, transitionName)
            viewerLauncher.launch(intent, options)
        } else {
            viewerLauncher.launch(intent)
        }
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
        val selecting = count > 0
        // Metro pattern: the selection command bar takes over the bottom nav's slot.
        binding.selectionBar.visibility = if (selecting) View.VISIBLE else View.GONE
        binding.bottomPanel.visibility = if (selecting) View.GONE else View.VISIBLE

        if (selecting) {
            binding.screenTitle.visibility = View.VISIBLE
            binding.screenTitle.text = resources.getQuantityString(R.plurals.selected_count, count, count)
            binding.menuBtn.setImageResource(R.drawable.ic_fluent_back_24_regular)
            binding.menuBtn.alpha = 1f
            binding.menuBtn.setOnClickListener { adapter.clearSelection() }
            binding.searchBox.visibility = View.GONE
        } else {
            binding.searchBox.visibility = View.VISIBLE
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

    private fun openSafe() {
        safeLauncher.launch(Intent(this, SafeActivity::class.java))
    }

    private fun moveSelectedToSafe() {
        val selected = adapter.selectedUris()
        if (selected.isEmpty()) return
        // Only still images can be locked; the Safe stores encrypted image zips.
        val images = selected.filter { uri ->
            val type = contentResolver.getType(uri)
            type == null || type.startsWith("image/")
        }
        if (images.isEmpty()) {
            MetroBanner.show(this, "Only photos can be moved to the Safe")
            return
        }
        adapter.clearSelection()
        // SafeActivity encrypts them, then returns the originals to delete via our consent flow.
        safeLauncher.launch(
            Intent(this, SafeActivity::class.java)
                .putParcelableArrayListExtra(SafeActivity.ExtraImportUris, ArrayList(images))
        )
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
        requestDelete(selected)
    }

    /** Requests storage access on the first delete, then routes through the selected delete policy. */
    private fun requestDelete(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (!DeleteCoordinator.canDeleteDirectly(this)) {
            pendingAllFilesDeleteUris = uris
            runCatching { allFilesAccessLauncher.launch(StoragePermissions.manageAllFilesIntent(this)) }
                .onFailure {
                    pendingAllFilesDeleteUris = emptyList()
                    MetroBanner.show(this, "Couldn't open storage access settings")
                }
            return
        }
        // Bin deletes are undoable (banner UNDO); direct permanent deletes are not — those get
        // the one Metro confirm. The system consent dialog path confirms itself.
        if (!DeleteCoordinator.usesBin(this)) {
            val noun = if (uris.size == 1) "this photo" else "${uris.size} photos"
            MetroDialog.confirm(
                context = this,
                title = "Delete permanently?",
                message = "Recycle Bin is off, so $noun will be deleted permanently. There's no undo.",
                positive = "Delete",
                danger = true,
                iconRes = R.drawable.ic_fluent_delete_24_regular
            ) { performManagedDelete(uris) }
            return
        }
        performManagedDelete(uris)
    }

    private fun performManagedDelete(uris: List<Uri>) {
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { DeleteCoordinator.delete(this@MainActivity, uris) }
            when (outcome) {
                is DeleteCoordinator.Outcome.NeedsSystemDelete -> deleteUris(uris)
                is DeleteCoordinator.Outcome.Done -> {
                    adapter.clearSelection()
                    refreshVisibleItems()
                    val msg = buildString {
                        append(
                            resources.getQuantityString(
                                if (outcome.toBin) R.plurals.photos_moved_to_bin else R.plurals.photos_deleted,
                                outcome.succeeded,
                                outcome.succeeded
                            )
                        )
                        if (outcome.failed > 0) append(" · ${outcome.failed} failed")
                    }
                    if (outcome.toBin && outcome.binnedIds.isNotEmpty()) {
                        val undoIds = outcome.binnedIds
                        MetroBanner.show(this@MainActivity, msg, actionLabel = "Undo", durationMs = 5000) {
                            lifecycleScope.launch {
                                val restored = withContext(Dispatchers.IO) {
                                    BinManager.restoreByIds(this@MainActivity, undoIds)
                                }
                                refreshVisibleItems()
                                MetroBanner.show(
                                    this@MainActivity,
                                    if (restored > 0) "$restored restored" else "Couldn't restore"
                                )
                            }
                        }
                    } else {
                        MetroBanner.show(this@MainActivity, msg)
                    }
                }
            }
        }
    }

    private fun refreshVisibleItems(afterCompletedIndexPass: Boolean = false) {
        val repo = repository ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val snapshot = loadLibrarySnapshot(repo)
            // The worker owns its own repository instance, so this one still holds the embeddings
            // it had before the pass ran. Re-read the on-disk index or the count and search results
            // stay stuck at whatever was cached at load time.
            repo.loadCachedIndexForUris(snapshot.imageItems.map { it.uri })
            val refreshedTags = withContext(Dispatchers.IO) { dbRepository?.getAllTags().orEmpty() }
            val refreshedTagUriMap = withContext(Dispatchers.IO) {
                refreshedTags.associate { tag ->
                    tag.id to dbRepository?.getMediaUrisForTag(tag.id).orEmpty().toSet()
                }
            }
            val refreshedPeopleCollection = loadPeopleCollection()
            withContext(Dispatchers.Main) {
                allTags = refreshedTags
                tagUriMap = refreshedTagUriMap
                applyLibrarySnapshot(snapshot)
                peopleCollectionRefreshGeneration++
                peopleCollection = refreshedPeopleCollection
                // A finished pass had its chance at every in-scope photo; whatever is still missing
                // can't be encoded, so stop counting it as outstanding work.
                if (afterCompletedIndexPass) {
                    permanentlyUnindexedUris = repo.unindexedUris(indexScopeUris).toSet()
                }
                currentAlbum = currentAlbum?.let { current -> albums.firstOrNull { it.id == current.id } }
                binding.statusText.text = indexedSummary(repo.indexedCount)
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
            MetroBanner.show(this, "Bulk delete requires Android 11 or newer")
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
                MetroBanner.show(this, "Delete failed: ${error.message}")
            }
        }
    }

    private fun launchDeleteConsent(intentSender: IntentSender) {
        deleteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
    }

    private fun onDeleteCompleted(requestedCount: Int) {
        adapter.clearSelection()
        refreshVisibleItems()
        val label = if (requestedCount == 1) "1 photo deleted" else "$requestedCount photos deleted"
        MetroBanner.show(this, label)
    }

    private fun enqueueBackgroundIndexing(
        showBanner: Boolean = true,
        replace: Boolean = false,
        initialDelaySeconds: Long = 0
    ) {
        IndexPreferences.setIndexPaused(this, false)
        IndexPreferences.setIndexStopped(this, false)
        IndexPreferences.setIndexConsentGiven(this, true)
        enqueueIndexWork(
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            initialDelaySeconds
        )
        updateIndexDrawerLabel()
        if (showBanner) {
            MetroBanner.show(this, "Indexing started")
        }
    }

    private fun observeIndexWorker() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(INDEX_WORK_NAME)
            .observe(this) { infos ->
                val work = infos.firstOrNull() ?: return@observe
                indexRunning = work.state == WorkInfo.State.RUNNING || work.state == WorkInfo.State.ENQUEUED
                binding.searchSparkle.setIndexing(work.state == WorkInfo.State.RUNNING)
                updateIndexDrawerLabel()
                when (work.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED -> {
                        binding.statusText.text =
                            if (IndexPreferences.isChargingOnlyIndexing(this)) "Indexing queued · waiting to charge"
                            else "Indexing starting…"
                    }
                    WorkInfo.State.RUNNING -> {
                        val current = work.progress.getInt(IndexWorker.ProgressCurrentKey, 0)
                        val total = work.progress.getInt(IndexWorker.ProgressTotalKey, 0)
                        indexProgressCurrent = current
                        indexProgressTotal = total
                        binding.statusText.text = "Indexing: $current / $total"
                        maybeRefreshLiveIndex(current)
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        binding.progressBar.visibility = View.GONE
                        if (IndexPreferences.isIndexPaused(this)) {
                            binding.statusText.text = "Indexing paused"
                            return@observe
                        }
                        refreshVisibleItems(afterCompletedIndexPass = true)
                        refreshSensitiveBlur()
                        MetroBanner.show(this, "Indexing complete — AI search is ready")
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

    /** Keeps the virtual People collection in sync with the Room face index, without polling. */
    private fun observeFaceIndexWorker() {
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(FaceIndexWorker.WorkTag)
            .observe(this) { infos ->
                val work = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    ?: infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
                    ?: infos.firstOrNull()
                    ?: return@observe
                val indexedFaces = work.progress.getInt(FaceIndexWorker.StatsFacesKey, -1)
                if (work.state == WorkInfo.State.RUNNING &&
                    indexedFaces >= MinPeopleFaces && indexedFaces != lastFaceIndexPeopleRefresh
                ) {
                    lastFaceIndexPeopleRefresh = indexedFaces
                    refreshPeopleCollection()
                }
                if (work.state == WorkInfo.State.SUCCEEDED) {
                    refreshPeopleCollection()
                }
            }
    }

    private fun indexedSummary(indexedCount: Int): String {
        val scoped = !IndexScopeStore.isAllFolders(applicationContext)
        return if (scoped) "$indexedCount indexed · selected folders" else "$indexedCount indexed"
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

    /**
     * True when a background index pass should run now: consent given, work remaining, and neither
     * paused nor stopped. Used both to enqueue the worker and to decide whether to warm the CLIP
     * encoders eagerly at startup (only when indexing needs them).
     */
    private fun shouldRunBackgroundIndexing(): Boolean {
        val repo = repository ?: return false
        if (indexScopeUris.isEmpty()) return false
        if (!IndexPreferences.isIndexConsentGiven(applicationContext)) return false
        if (!hasUnindexedWork(repo)) return false
        if (IndexPreferences.isIndexPaused(applicationContext)) return false
        if (IndexPreferences.isIndexStopped(applicationContext)) return false
        return true
    }

    /**
     * Compares URIs rather than counts. A count check can't tell "work remaining" apart from
     * "these photos can never be encoded" (corrupt file, unsupported codec), and the latter used to
     * leave indexedCount permanently below the target — so every refresh re-enqueued the worker,
     * which finished, refreshed, and re-enqueued again.
     */
    private fun hasUnindexedWork(repo: GalleryRepository): Boolean =
        repo.unindexedUris(indexScopeUris).any { it !in permanentlyUnindexedUris }

    private fun maybeStartBackgroundIndexing() {
        val repo = repository ?: return
        if (indexScopeUris.isEmpty()) return
        if (!IndexPreferences.isIndexConsentGiven(applicationContext)) return  // wait for user approval
        if (!hasUnindexedWork(repo)) return
        if (IndexPreferences.isIndexPaused(applicationContext)) {
            binding.statusText.text =
                "Indexing paused · ${indexedSummary(repo.indexedCount)}"
            updateIndexDrawerLabel()
            return
        }
        // Respect an explicit Stop — don't silently restart while browsing.
        if (IndexPreferences.isIndexStopped(applicationContext)) {
            updateIndexDrawerLabel()
            return
        }
        // Startup already waits for the initial UI/library load before calling this, so enqueue
        // immediately instead of adding an extra fixed delay that leaves indexing "queued".
        enqueueIndexWork(ExistingWorkPolicy.KEEP)
        binding.statusText.text =
            "Background indexing starting · ${indexedSummary(repo.indexedCount)}"
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

        binding.screenTitle.visibility = if (title == null || currentMode == Mode.Search) View.GONE else View.VISIBLE
        binding.screenTitle.text = title.orEmpty()
        binding.searchBox.visibility = if (adapter.selectionCount > 0) View.GONE else View.VISIBLE
        val isAlbumsSection = activeSection == Section.Albums &&
            currentMode == Mode.Browse &&
            adapter.selectionCount == 0
        binding.addAlbumBtn.visibility = if (isAlbumsSection) View.VISIBLE else View.GONE
        val isFoldersSection = activeSection == Section.Folders &&
            currentMode == Mode.Browse &&
            adapter.selectionCount == 0
        binding.folderOptionsBtn.visibility = if (isFoldersSection) View.VISIBLE else View.GONE
        binding.folderOptionsBtn.setOnClickListener(::showFolderOptions)
    }

    private enum class FolderSort { Name, Newest, MostItems }

    /**
     * The paging context key of the current listing, which doubles as the sort-preference scope.
     * Null on screens that don't sort media (albums grid, folder tree, search).
     */
    private fun currentScopeKey(): String? = when (currentMode) {
        Mode.AlbumDetail -> currentAlbum?.let { "album:${it.id}" }
        Mode.FolderDetail -> currentFolder?.let { "folder:${it.path}" }
        Mode.SmartAlbumDetail -> currentSmartAlbum?.let { "smart:${it.id}" }
        Mode.Search -> null
        Mode.Browse -> when (activeSection) {
            Section.Collection, Section.Videos, Section.Favorites -> "section:$activeSection"
            Section.Albums, Section.Folders -> null
        }
    }

    /**
     * Header-level sort chip click. In the Albums section the chip lives on the "OTHERS" header and
     * uses album-specific options; everywhere else it delegates to the media sort menu.
     */
    private fun onHeaderSortClick(anchor: View) {
        if (currentMode == Mode.Browse && activeSection == Section.Albums) {
            showAlbumSortMenu(anchor)
        } else {
            showSortMenu(anchor)
        }
    }

    /**
     * Opens the sort dropdown for the active listing. The scope is read at click time rather than
     * captured: the adapter is built once in [onCreate], but the listing under it changes.
     */
    private fun showSortMenu(anchor: View) {
        val scopeKey = currentScopeKey() ?: return
        val current = SortManager.optionFor(this, scopeKey)
        SortMenu.show(anchor, current, SortOption.MEDIA_OPTIONS) { picked ->
            SortManager.setOption(this, scopeKey, picked)
            renderCurrentState()
        }
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
        updateIndexDrawerLabel()
    }

    /** Pinch step in grid mode: fewer columns on zoom-in (bigger), more on zoom-out (smaller). */
    private fun adjustGridColumns(zoomIn: Boolean, layoutManager: GridLayoutManager) {
        val current = adapter.gridColumnCount
        val next = (if (zoomIn) current - 1 else current + 1)
            .coerceIn(DesignTokens.GRID_MIN_COLUMNS, DesignTokens.GRID_MAX_COLUMNS)
        if (next == current) return
        adapter.gridColumnCount = next
        IndexPreferences.setGridColumnCount(this, next)
        layoutManager.spanCount = next
        layoutManager.spanSizeLookup.invalidateSpanIndexCache()
        adapter.notifyItemRangeChanged(0, adapter.itemCount, "grid_change")
    }

    /**
     * Pinch step in collage mode: a lower scale level on zoom-in (bigger thumbnails), higher on
     * zoom-out (smaller). Collage spans/heights are precomputed per cell, so this rebuilds the
     * current view's cells (in-memory, no library reload) via [rerenderForDisplayChange].
     */
    private fun adjustCollageScale(zoomIn: Boolean) {
        val current = collageScaleLevel
        val next = (if (zoomIn) current - 1 else current + 1)
            .coerceIn(DesignTokens.COLLAGE_SCALE_MIN, DesignTokens.COLLAGE_SCALE_MAX)
        if (next == current) return
        collageScaleLevel = next
        IndexPreferences.setCollageScale(this, next)
        rerenderForDisplayChange()
    }

    /**
     * Rebuilds the current view's cells from already-loaded item lists so a display change (collage
     * scale) takes effect immediately. Mirrors [refreshVisibleItems]'s dispatch without the IO reload.
     */
    private fun rerenderForDisplayChange() {
        when {
            currentMode == Mode.Search -> submitSearch()
            currentMode == Mode.AlbumDetail -> currentAlbum?.let(::renderAlbumDetail) ?: renderCurrentSection()
            currentMode == Mode.FolderDetail -> currentFolder?.let(::renderFolderDetail) ?: renderCurrentSection()
            currentMode == Mode.SmartAlbumDetail -> currentSmartAlbum?.let(::renderSmartAlbumDetail) ?: renderCurrentSection()
            else -> renderCurrentSection()
        }
    }

    private fun applyDisplaySettings() {
        adapter.useCollageLayout = IndexPreferences.isCollageLayout(this)
        adapter.gridColumnCount = IndexPreferences.getGridColumnCount(this)
        adapter.showAlbumFolderSize = IndexPreferences.isShowAlbumFolderSize(this)
        collageScaleLevel = IndexPreferences.getCollageScale(this)
        val layoutManager = binding.imageGrid.layoutManager as GridLayoutManager
        layoutManager.spanCount = if (adapter.useCollageLayout) DesignTokens.COLLAGE_SPAN_COUNT else adapter.gridColumnCount
        layoutManager.spanSizeLookup.invalidateSpanIndexCache()
        // Rebuild the current view's cells synchronously so display-only toggles (e.g. the albums
        // folder-size subtitle, which only changes on rebind) take effect immediately instead of
        // waiting on the async library reload below. Mirrors adjustCollageScale().
        rerenderForDisplayChange()
        updateDrawerState()
        // If the "only while charging" preference changed while indexing is active, re-apply it.
        if (indexRunning && IndexPreferences.isChargingOnlyIndexing(this) != chargingPrefSnapshot) {
            enqueueBackgroundIndexing(showBanner = false, replace = true)
        }
        refreshSensitiveBlur()
        refreshVisibleItems()
    }

    /**
     * Beta: classifies indexed photos as sensitive/NSFW with the on-device CLIP encoders and tells
     * the adapter which uris to blur. Runs off the main thread; reuses stored embeddings so it's
     * just dot products. Called on settings return and after the index loads.
     */
    private fun refreshSensitiveBlur() {
        // Feature temporarily disabled — never classify or blur.
        val enabled = NsfwClassifier.FEATURE_ENABLED && IndexPreferences.isBlurSensitive(this)
        if (!enabled) {
            nsfwComputeJob?.cancel()
            adapter.setSensitiveState(false, emptySet())
            return
        }
        val repo = repository ?: return
        val text = textEncoder ?: return
        val classifier = nsfwClassifier ?: NsfwClassifier(text).also { nsfwClassifier = it }
        nsfwComputeJob?.cancel()
        nsfwComputeJob = lifecycleScope.launch {
            val flagged = withContext(Dispatchers.Default) {
                val embeddings = repo.allEmbeddings()
                if (embeddings.isEmpty() || !classifier.isReady()) return@withContext emptySet<String>()
                embeddings.asSequence()
                    .filter { classifier.isSensitive(it.value) }
                    .map { it.key }
                    .toSet()
            }
            adapter.setSensitiveState(true, flagged)
        }
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
        updateBottomTab(
            tab = binding.bottomSafe,
            icon = binding.bottomSafeIcon,
            active = false
        )
    }

    private fun showAlbumSortMenu(anchor: View) {
        val current = SortManager.optionFor(this, AlbumsSortScope)
        SortMenu.show(anchor, current, SortOption.ALBUM_OPTIONS) { picked ->
            SortManager.setOption(this, AlbumsSortScope, picked)
            renderAlbums()
        }
    }

    private fun applyBottomBarConfig() {
        val tabs = mapOf(
            BottomBarDestination.Collection to binding.bottomCollections,
            BottomBarDestination.Videos to binding.bottomVideos,
            BottomBarDestination.Albums to binding.bottomAlbums,
            BottomBarDestination.Favorites to binding.bottomFavorites,
            BottomBarDestination.Folders to binding.bottomFolders,
            BottomBarDestination.Safe to binding.bottomSafe
        )
        binding.bottomPanel.removeAllViews()
        BottomBarConfig.enabledOrder(this).forEach { destination ->
            tabs.getValue(destination).let { tab ->
                tab.visibility = View.VISIBLE
                binding.bottomPanel.addView(tab)
            }
        }
        tabs.filterKeys { it !in BottomBarConfig.enabledOrder(this) }.values.forEach {
            it.visibility = View.GONE
        }
        updateBottomPanelState()
    }

    private fun updateBottomTab(tab: View, icon: ImageView, active: Boolean) {
        tab.alpha = if (active) 1f else 0.72f
        icon.imageTintList = ColorStateList.valueOf(
            if (active) DesignTokens.accent(this) else ContextCompat.getColor(this, R.color.metroTextTertiary)
        )
        icon.scaleX = if (active) 1f else 0.92f
        icon.scaleY = if (active) 1f else 0.92f
    }

    private fun showBottomPanel() {
        binding.bottomPanel.translationY = 0f
    }

    private fun showFatalError(error: Throwable) {
        dismissLoadingOverlay()
        MetroDialog.message(
            this,
            title = "Something went wrong",
            message = error.stackTraceToString(),
            positive = "Close",
            scrollableMessage = true
        )
    }

    override fun onResume() {
        super.onResume()
        startSearchHintCycle()
    }

    override fun onPause() {
        super.onPause()
        stopSearchHintCycle()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSearchHintCycle()
        searchDebounceJob?.cancel()
        searchJob?.cancel()
        renderJob?.cancel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SECTION, activeSection.name)
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val STATE_SECTION = "state_section"
        private const val AlbumsSortScope = "section:Albums"
        private const val INDEX_WORK_NAME = "gallery_background_index"
        private const val PeopleAlbumId = "virtual:people"
        private const val MinPeopleFaces = 5
        // Safety cap: never hold the launch splash longer than this even if content stalls.
        private const val SPLASH_MAX_HOLD_MS = 2500L
        private const val ENCODER_WARMUP_DELAY_MS = 1200L
        // Show the fast-scroll bar once content exceeds ~1.5 viewports.
        private const val FAST_SCROLL_MIN_RATIO = 1.5f
        private const val SEARCH_PAGE_SIZE = 30
        private const val SEARCH_DISPLAY_CAP = 1500
        private const val SIMILAR_IMAGE_FLOOR = 0.55f
        // Incremental browse-grid paging: first page renders fast, more append on scroll.
        // Sizes are in source items (not cells); rows/headers are derived per page.
        private const val BROWSE_PAGE_SIZE = 120
        private const val BROWSE_PAGE_MAX = 320
        private const val PAGE_PREFETCH_CELLS = 12
        // "Alive" search-bar hint rotation.
        private const val SEARCH_HINT_INTERVAL_MS = 4200L
        private const val SEARCH_HINT_FADE_MS = 220L
    }

    private enum class SearchMode {
        Hybrid,
        AiOnly,
        MetadataOnly
    }

    private enum class ShowFilter(val label: String) {
        All("All results"),
        Favorites("Favorites only"),
        Screenshots("Screenshots only")
    }
}
