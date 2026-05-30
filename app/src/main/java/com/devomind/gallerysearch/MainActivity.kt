package com.devomind.gallerysearch

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val adapter = ImageAdapter()
    private var imageEncoder: ImageEncoder? = null
    private var textEncoder: TextEncoder? = null
    private var repository: GalleryRepository? = null
    private var albums: List<GalleryRepository.Album> = emptyList()
    private var selectedAlbumIds: Set<String> = emptySet()
    private var allUris: List<Uri> = emptyList()
    private var resultManager: SearchResultManager? = null
    private var searchJob: Job? = null
    private var lastProgressRefresh = -1

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
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.imageGrid.layoutManager = GridLayoutManager(this, 3)
        binding.imageGrid.adapter = adapter
        binding.imageGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = rv.layoutManager as GridLayoutManager
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val total = adapter.itemCount
                val manager = resultManager ?: return

                if (!manager.isLastPage && lastVisible >= total - 6) {
                    adapter.appendList(manager.nextPage())
                }
            }
        })
        binding.searchBtn.isEnabled = false
        binding.selectAlbumsBtn.isEnabled = false
        binding.startIndexBtn.isEnabled = false

        binding.searchBtn.setOnClickListener { submitSearch() }
        binding.selectAlbumsBtn.setOnClickListener { showAlbumSelector() }
        binding.startIndexBtn.setOnClickListener { enqueueBackgroundIndexing() }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch()
                true
            } else {
                false
            }
        }

        requestGalleryPermission()
        observeIndexWorker()
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
                        val uris = repo.getImageUrisForAlbumIds(effectiveSelection)
                        repo.loadCachedIndexForUris(uris)
                        InitResult(image, text, repo, uris, availableAlbums, effectiveSelection)
                    } catch (error: Throwable) {
                        image?.close()
                        text?.close()
                        throw error
                    }
                }

                imageEncoder = result.imageEncoder
                textEncoder = result.textEncoder
                repository = result.repository
                allUris = result.uris
                albums = result.albums
                selectedAlbumIds = result.selectedAlbumIds
                
                resultManager = SearchResultManager(allUris)
                adapter.updateList(resultManager!!.firstPage())
                
                binding.progressBar.visibility = View.GONE
                binding.searchBtn.isEnabled = true
                binding.selectAlbumsBtn.isEnabled = true
                binding.startIndexBtn.isEnabled = true
                binding.statusText.text = selectionSummaryText(result.albums, result.selectedAlbumIds, result.repository.indexedCount)
                binding.resultCount.text = ""
            } catch (error: Throwable) {
                binding.progressBar.visibility = View.GONE
                showFatalError(error)
            }
        }
    }

    private fun submitSearch() {
        val query = binding.searchInput.text?.toString()?.trim().orEmpty()
        val repo = repository ?: return
        searchJob?.cancel()

        if (query.isBlank()) {
            resultManager = SearchResultManager(allUris)
            adapter.updateList(resultManager!!.firstPage())
            binding.resultCount.text = ""
            binding.statusText.text = selectionSummaryText(albums, selectedAlbumIds, repo.indexedCount)
            return
        }

        searchJob = lifecycleScope.launch {
            binding.searchBtn.isEnabled = false
            binding.progressBar.visibility = View.VISIBLE
            binding.statusText.text = "Searching..."
            try {
                val results = withContext(Dispatchers.IO) {
                    repo.search(query)
                }
                
                resultManager = SearchResultManager(results)
                adapter.updateList(resultManager!!.firstPage())
                
                val count = results.size
                binding.resultCount.text = when {
                    count == 0   -> "No results found"
                    count <= SearchTuning.PageSize -> "Found $count photos"
                    else         -> "Found $count photos — scroll to see more"
                }
                
                binding.statusText.text = selectionSummaryText(albums, selectedAlbumIds, repo.indexedCount)
            } catch (error: Throwable) {
                showFatalError(error)
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.searchBtn.isEnabled = true
            }
        }
    }

    private fun setBusy(message: String) {
        binding.statusText.text = message
        binding.progressBar.visibility = View.VISIBLE
        binding.searchBtn.isEnabled = false
    }

    private fun showAlbumSelector() {
        if (albums.isEmpty()) {
            Toast.makeText(this, "No albums found on device.", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = albums.map { "${it.name} (${it.count})" }.toTypedArray()
        val checked = albums.map { it.id in selectedAlbumIds }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Select albums to index")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNeutralButton("Select all") { _, _ ->
                selectedAlbumIds = emptySet()
                IndexPreferences.saveSelectedAlbums(this, selectedAlbumIds)
                refreshVisibleUris()
            }
            .setPositiveButton("Apply") { _, _ ->
                selectedAlbumIds = albums.filterIndexed { index, _ -> checked[index] }.map { it.id }.toSet()
                IndexPreferences.saveSelectedAlbums(this, selectedAlbumIds)
                refreshVisibleUris()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshVisibleUris() {
        val repo = repository ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val uris = repo.getImageUrisForAlbumIds(selectedAlbumIds)
            repo.loadCachedIndexForUris(uris)
            withContext(Dispatchers.Main) {
                allUris = uris
                resultManager = SearchResultManager(allUris)
                adapter.updateList(resultManager!!.firstPage())
                binding.resultCount.text = ""
                binding.statusText.text = selectionSummaryText(albums, selectedAlbumIds, repo.indexedCount)
            }
        }
    }

    private fun enqueueBackgroundIndexing() {
        val payload = Data.Builder()
            .putStringArray(IndexWorker.SelectedAlbumIdsKey, selectedAlbumIds.toTypedArray())
            .build()

        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            .setInputData(payload)
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            IndexWorkName,
            ExistingWorkPolicy.REPLACE,
            request
        )
        Toast.makeText(this, "Indexing started in background.", Toast.LENGTH_SHORT).show()
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
                        binding.statusText.text = "Index job queued..."
                    }
                    WorkInfo.State.RUNNING -> {
                        val current = work.progress.getInt(IndexWorker.ProgressCurrentKey, 0)
                        val total = work.progress.getInt(IndexWorker.ProgressTotalKey, 0)
                        binding.progressBar.visibility = View.VISIBLE
                        binding.statusText.text = "Background indexing: $current / $total"
                        maybeRefreshLiveIndex(current)
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        binding.progressBar.visibility = View.GONE
                        refreshVisibleUris()
                        Toast.makeText(this, "Indexing complete.", Toast.LENGTH_SHORT).show()
                    }
                    WorkInfo.State.FAILED -> {
                        binding.progressBar.visibility = View.GONE
                        binding.statusText.text = "Background indexing failed."
                    }
                    WorkInfo.State.CANCELLED -> {
                        binding.progressBar.visibility = View.GONE
                        binding.statusText.text = "Background indexing cancelled."
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
            "All albums"
        } else {
            val selectedCount = albums.count { it.id in selectedIds }
            "$selectedCount albums"
        }
        return "Ready - $indexedCount indexed ($albumText)"
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
                binding.statusText.text = "Background indexing: $current (live updates on)"
                if (query.isNotBlank()) {
                    searchJob?.cancel()
                    searchJob = lifecycleScope.launch(Dispatchers.IO) {
                        val results = repo.search(query)
                        withContext(Dispatchers.Main) {
                            resultManager = SearchResultManager(results)
                            adapter.updateList(resultManager!!.firstPage())
                            val count = results.size
                            binding.resultCount.text = when {
                                count == 0   -> "No results found"
                                count <= SearchTuning.PageSize -> "Found $count photos"
                                else         -> "Found $count photos — scroll to see more"
                            }
                        }
                    }
                }
            }
        }
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
        val uris: List<Uri>,
        val albums: List<GalleryRepository.Album>,
        val selectedAlbumIds: Set<String>
    )

    companion object {
        private const val IndexWorkName = "gallery_background_index"
    }
}
