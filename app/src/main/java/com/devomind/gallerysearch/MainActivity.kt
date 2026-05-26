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
    private var allUris: List<Uri> = emptyList()
    private var searchJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.any { it.value }) {
            initializeAndIndex()
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
        binding.searchBtn.isEnabled = false

        binding.searchBtn.setOnClickListener { submitSearch() }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch()
                true
            } else {
                false
            }
        }

        requestGalleryPermission()
    }

    private fun requestGalleryPermission() {
        val permissions = requiredPermissions()
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            initializeAndIndex()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun requiredPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun initializeAndIndex() {
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
                        val uris = repo.getAllImageUris()

                        withContext(Dispatchers.Main) {
                            binding.statusText.text = "Indexing your photos..."
                        }

                        repo.buildIndex(uris) { current, total ->
                            runOnUiThread {
                                binding.statusText.text = "Indexing: $current / $total photos"
                            }
                        }
                        InitResult(image, text, repo, uris)
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
                adapter.updateList(allUris)
                binding.progressBar.visibility = View.GONE
                binding.searchBtn.isEnabled = true
                binding.statusText.text = "Ready - ${result.repository.indexedCount} photos indexed"
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
            adapter.updateList(allUris)
            binding.resultCount.text = ""
            binding.statusText.text = "Ready - ${repo.indexedCount} photos indexed"
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
                adapter.updateList(results)
                binding.resultCount.text = "Found ${results.size} results for \"$query\""
                binding.statusText.text = "Ready - ${repo.indexedCount} photos indexed"
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
        val uris: List<Uri>
    )
}
