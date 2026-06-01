package com.devomind.gallerysearch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ImageAdapter
    private var imageEncoder: ImageEncoder? = null
    private var textEncoder: TextEncoder? = null
    private var repository: GalleryRepository? = null
    private var albums: List<GalleryRepository.Album> = emptyList()
    private var selectedAlbumIds: Set<String> = emptySet()
    private var allItems: List<GalleryRepository.MediaItem> = emptyList()
    private var allUris: List<Uri> = emptyList()
    private var currentAlbum: GalleryRepository.Album? = null
    private var currentMode = Mode.Collection
    private var searchJob: Job? = null
    private var lastProgressRefresh = -1

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

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ImageAdapter(
            onPhotoClick = ::openViewer,
            onSelectionChanged = ::renderSelectionState,
            onAlbumClick = ::openAlbum
        )

        val layoutManager = GridLayoutManager(this, GridSpanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return adapter.spanSizeAt(position, GridSpanCount)
            }
        }

        binding.imageGrid.layoutManager = layoutManager
        binding.imageGrid.adapter = adapter
        binding.imageGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val alpha = if (rv.computeVerticalScrollOffset() > 32) 0.2f else 0.35f
                binding.menuBtn.animate().alpha(alpha).setDuration(160).start()
            }
        })

        bindChrome()
        bindBackNavigation()
        requestGalleryPermission()
        observeIndexWorker()
    }

    private fun bindChrome() {
        binding.menuBtn.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
        binding.drawerCollection.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            currentAlbum = null
            currentMode = Mode.Collection
            renderCollection()
        }
        binding.drawerAlbums.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            currentAlbum = null
            currentMode = Mode.Albums
            renderAlbums()
        }
        binding.drawerSearch.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            currentAlbum = null
            currentMode = Mode.Search
            renderSearchShell()
        }
        binding.drawerIndex.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            enqueueBackgroundIndexing()
        }
        binding.drawerAlbumScope.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showAlbumSelector()
        }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch()
                true
            } else {
                false
            }
        }
        binding.searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) currentMode = Mode.Search
        }
        binding.mainSurface.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN) {
                val nearMenu = event.x < 96f * resources.displayMetrics.density && event.y < 116f * resources.displayMetrics.density
                binding.menuBtn.animate().alpha(if (nearMenu) 1f else 0.2f).setDuration(120).start()
            }
            false
        }
        binding.shareSelectionBtn.setOnClickListener { shareSelected() }
        binding.deleteSelectionBtn.setOnClickListener {
            Toast.makeText(this, "Delete confirmation will be wired in the media-safe deletion pass.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.drawerLayout.isDrawerOpen(GravityCompat.START) -> binding.drawerLayout.closeDrawer(GravityCompat.START)
                    adapter.selectionCount > 0 -> adapter.clearSelection()
                    currentMode == Mode.AlbumDetail -> {
                        currentAlbum = null
                        currentMode = Mode.Albums
                        renderAlbums()
                    }
                    currentMode == Mode.Search && !binding.searchInput.text.isNullOrBlank() -> {
                        binding.searchInput.text?.clear()
                        renderSearchShell()
                    }
                    currentMode == Mode.Search -> {
                        currentMode = Mode.Collection
                        renderCollection()
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
                        val availableAlbums = repo.getAlbums()
                        val selectedIds = IndexPreferences.loadSelectedAlbums(applicationContext)
                        val effectiveSelection = selectedIds.intersect(availableAlbums.map { it.id }.toSet())
                        val items = repo.getImageItemsForAlbumIds(effectiveSelection)
                        val uris = items.map { it.uri }
                        repo.loadCachedIndexForUris(uris)
                        InitResult(image, text, repo, items, availableAlbums, effectiveSelection)
                    } catch (error: Throwable) {
                        image?.close()
                        text?.close()
                        throw error
                    }
                }

                imageEncoder = result.imageEncoder
                textEncoder = result.textEncoder
                repository = result.repository
                allItems = result.items
                allUris = result.items.map { it.uri }
                albums = result.albums
                selectedAlbumIds = result.selectedAlbumIds
                currentAlbum = null
                lastProgressRefresh = -1
                binding.progressBar.visibility = View.GONE
                binding.statusText.text = selectionSummaryText(result.albums, result.selectedAlbumIds, result.repository.indexedCount)
                renderCollection()
                maybeStartBackgroundIndexing()
            } catch (error: Throwable) {
                binding.progressBar.visibility = View.GONE
                showFatalError(error)
            }
        }
    }

    private fun renderCollection() {
        currentMode = Mode.Collection
        currentAlbum = null
        binding.screenTitle.visibility = View.GONE
        binding.searchPanel.visibility = View.GONE
        binding.resultCount.text = ""
        adapter.updateCells(buildTimelineCells(allItems))
        updateTopBarForMode()
        updateDrawerState()
    }

    private fun renderAlbums() {
        currentMode = Mode.Albums
        currentAlbum = null
        binding.screenTitle.visibility = View.VISIBLE
        binding.screenTitle.text = "albums"
        binding.searchPanel.visibility = View.GONE
        binding.resultCount.text = ""
        adapter.updateCells(if (albums.isEmpty()) listOf(GalleryCell.Empty("No albums yet")) else albums.map { GalleryCell.AlbumCell(it) })
        updateTopBarForMode()
        updateDrawerState()
    }

    private fun renderAlbumDetail(album: GalleryRepository.Album) {
        currentMode = Mode.AlbumDetail
        currentAlbum = album
        binding.screenTitle.visibility = View.VISIBLE
        binding.screenTitle.text = album.name.lowercase(Locale.getDefault())
        binding.searchPanel.visibility = View.GONE
        val items = allItems.filter { it.bucketId == album.id }
        binding.resultCount.text = if (items.isEmpty()) "" else "${items.size} photos"
        adapter.updateCells(buildTimelineCells(items))
        updateTopBarForMode()
        updateDrawerState()
    }

    private fun renderSearchShell() {
        currentMode = Mode.Search
        currentAlbum = null
        binding.screenTitle.visibility = View.VISIBLE
        binding.screenTitle.text = "search"
        binding.searchPanel.visibility = View.VISIBLE
        binding.resultCount.text = ""
        if (binding.searchInput.text.isNullOrBlank()) {
            adapter.updateCells(listOf(GalleryCell.Empty("Search your photos")))
        } else {
            submitSearch()
        }
        updateTopBarForMode()
        updateDrawerState()
    }

    private fun submitSearch() {
        val query = binding.searchInput.text?.toString()?.trim().orEmpty()
        val repo = repository ?: return
        searchJob?.cancel()

        if (query.isBlank()) {
            adapter.updateCells(listOf(GalleryCell.Empty("Search your photos")))
            binding.resultCount.text = ""
            binding.statusText.text = selectionSummaryText(albums, selectedAlbumIds, repo.indexedCount)
            return
        }

        currentMode = Mode.Search
        binding.screenTitle.visibility = View.VISIBLE
        binding.screenTitle.text = "search"
        binding.searchPanel.visibility = View.VISIBLE
        updateDrawerState()

        searchJob = lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            binding.statusText.text = "Searching..."
            try {
                val results = withContext(Dispatchers.IO) {
                    repo.search(query)
                }
                val cells = buildSearchCells(query, results)
                adapter.updateCells(if (cells.isEmpty()) listOf(GalleryCell.Empty("No matching photos")) else cells)
                binding.resultCount.text = when {
                    cells.isEmpty() -> "No results found"
                    cells.size == 1 -> "1 result"
                    else -> "${cells.size} results"
                }
                binding.statusText.text = selectionSummaryText(albums, selectedAlbumIds, repo.indexedCount)
            } catch (error: Throwable) {
                showFatalError(error)
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun buildTimelineCells(items: List<GalleryRepository.MediaItem>): List<GalleryCell> {
        if (items.isEmpty()) return listOf(GalleryCell.Empty("No photos yet"))
        val cells = ArrayList<GalleryCell>()
        items.groupBy { monthFormat.format(Date(it.dateMillis)) }.forEach { (month, monthItems) ->
            val first = monthItems.first()
            cells += GalleryCell.Header(month, dayFormat.format(Date(first.dateMillis)).uppercase(Locale.getDefault()))
            monthItems.groupBy { dayFormat.format(Date(it.dateMillis)) }.values.forEach { dayItems ->
                dayItems.forEachIndexed { index, item ->
                    cells += GalleryCell.Photo(item.uri, featured = index == 0)
                }
            }
        }
        return cells
    }

    private fun setBusy(message: String) {
        binding.statusText.text = message
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun openAlbum(album: GalleryRepository.Album) {
        renderAlbumDetail(album)
    }

    private fun openViewer(uri: Uri) {
        startActivity(Intent(this, ViewerActivity::class.java).setData(uri))
    }

    private fun renderSelectionState(count: Int) {
        binding.selectionPill.visibility = if (count > 0) View.VISIBLE else View.GONE
        if (count > 0) {
            binding.screenTitle.visibility = View.VISIBLE
            binding.screenTitle.text = "$count selected"
            binding.menuBtn.setImageResource(android.R.color.transparent)
            binding.menuBtn.setImageResource(R.drawable.ic_fluent_back_24_regular)
            binding.menuBtn.alpha = 1f
            binding.menuBtn.setOnClickListener { adapter.clearSelection() }
        } else {
            binding.menuBtn.setImageResource(R.drawable.ic_fluent_navigation_24_regular)
            binding.menuBtn.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
            updateTopBarForMode()
        }
    }

    private fun shareSelected() {
        val selected = adapter.selectedUris()
        if (selected.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(selected))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share photos"))
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

    private fun refreshVisibleItems(renderAfterRefresh: Mode = currentMode) {
        val repo = repository ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val items = repo.getImageItemsForAlbumIds(selectedAlbumIds)
            repo.loadCachedIndexForUris(items.map { it.uri })
            withContext(Dispatchers.Main) {
                allItems = items
                allUris = items.map { it.uri }
                binding.statusText.text = selectionSummaryText(albums, selectedAlbumIds, repo.indexedCount)
                when (renderAfterRefresh) {
                    Mode.Collection -> renderCollection()
                    Mode.Albums -> renderAlbums()
                    Mode.Search -> renderSearchShell()
                    Mode.AlbumDetail -> {
                        val album = currentAlbum?.let { current -> albums.firstOrNull { it.id == current.id } }
                        if (album != null) renderAlbumDetail(album) else renderAlbums()
                    }
                }
                maybeStartBackgroundIndexing()
            }
        }
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
                if (query.isNotBlank() && currentMode == Mode.Search) submitSearch()
            }
        }
    }

    private fun maybeStartBackgroundIndexing() {
        val repo = repository ?: return
        if (allUris.isEmpty()) return
        if (repo.indexedCount >= allUris.size) return
        enqueueBackgroundIndexing(showToast = false)
        binding.statusText.text = "Background indexing queued · ${selectionSummaryText(albums, selectedAlbumIds, repo.indexedCount)}"
    }

    private fun buildSearchCells(query: String, semanticResults: List<Uri>): List<GalleryCell.Photo> {
        val byUri = allItems.associateBy { it.uri }
        val ordered = LinkedHashSet<Uri>()
        semanticResults.forEach { if (it in byUri) ordered += it }

        val normalized = query.trim().lowercase(Locale.getDefault())
        if (normalized.isNotBlank()) {
            allItems.asSequence()
                .filter { item ->
                    val dateText = monthFormat.format(Date(item.dateMillis)).lowercase(Locale.getDefault())
                    val dayText = dayFormat.format(Date(item.dateMillis)).lowercase(Locale.getDefault())
                    listOfNotNull(
                        item.displayName,
                        item.bucketName,
                        item.mimeType,
                        dateText,
                        dayText
                    ).any { it.lowercase(Locale.getDefault()).contains(normalized) }
                }
                .take(60)
                .forEach { ordered += it.uri }
        }

        return ordered.mapNotNull { uri -> byUri[uri]?.let { GalleryCell.Photo(it.uri, featured = false) } }
    }

    private fun updateTopBarForMode() {
        when (currentMode) {
            Mode.Collection -> {
                binding.screenTitle.visibility = View.GONE
                binding.menuBtn.setImageResource(R.drawable.ic_fluent_navigation_24_regular)
                binding.menuBtn.alpha = 0.2f
                binding.menuBtn.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
            }
            Mode.Albums -> {
                binding.screenTitle.visibility = View.VISIBLE
                binding.screenTitle.text = "albums"
                binding.menuBtn.setImageResource(R.drawable.ic_fluent_navigation_24_regular)
                binding.menuBtn.alpha = 1f
                binding.menuBtn.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
            }
            Mode.Search -> {
                binding.screenTitle.visibility = View.VISIBLE
                binding.screenTitle.text = "search"
                binding.menuBtn.setImageResource(R.drawable.ic_fluent_navigation_24_regular)
                binding.menuBtn.alpha = 1f
                binding.menuBtn.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
            }
            Mode.AlbumDetail -> {
                binding.screenTitle.visibility = View.VISIBLE
                binding.screenTitle.text = currentAlbum?.name?.lowercase(Locale.getDefault()) ?: "album"
                binding.menuBtn.setImageResource(R.drawable.ic_fluent_back_24_regular)
                binding.menuBtn.alpha = 1f
                binding.menuBtn.setOnClickListener {
                    currentAlbum = null
                    currentMode = Mode.Albums
                    renderAlbums()
                }
            }
        }
    }

    private fun updateDrawerState() {
        val inactive = Color.rgb(10, 10, 10)
        val active = Color.rgb(17, 17, 17)
        binding.drawerCollection.setBackgroundColor(if (currentMode == Mode.Collection) active else inactive)
        binding.drawerAlbums.setBackgroundColor(if (currentMode == Mode.Albums || currentMode == Mode.AlbumDetail) active else inactive)
        binding.drawerSearch.setBackgroundColor(if (currentMode == Mode.Search) active else inactive)
    }

    private fun showFatalError(error: Throwable) {
        AlertDialog.Builder(this)
            .setTitle("Gallery Search Error")
            .setMessage(error.stackTraceToString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
        val items: List<GalleryRepository.MediaItem>,
        val albums: List<GalleryRepository.Album>,
        val selectedAlbumIds: Set<String>
    )

    private enum class Mode {
        Collection,
        Albums,
        Search,
        AlbumDetail
    }

    companion object {
        private const val IndexWorkName = "gallery_background_index"
        private const val GridSpanCount = 6
    }
}
