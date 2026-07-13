package com.devomind.gallerysearch

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.devomind.gallerysearch.databinding.ActivityIndexedFoldersBinding
import com.devomind.gallerysearch.databinding.ItemIndexFolderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fresh, self-contained picker for which folders the AI search index covers. Independent of the
 * gallery view (every photo still shows normally) and of any prior album-scope code.
 *
 * Persists to [IndexScopeStore] (empty = all folders, incl. future ones). On any change it triggers
 * [IndexController.rescan] so the index is rebuilt against the new scope — added folders get
 * indexed, unchecked folders are pruned from search results.
 */
class IndexedFoldersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIndexedFoldersBinding

    private var allFolders = true
    private val selected = linkedSetOf<String>()
    private var folders: List<GalleryRepository.Album> = emptyList()
    private var originalScope: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        binding = ActivityIndexedFoldersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        binding.backBtn.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        originalScope = IndexScopeStore.getFolderIds(this)
        allFolders = originalScope.isEmpty()
        selected.addAll(originalScope)

        binding.rowAllFolders.setOnClickListener {
            allFolders = !allFolders
            if (allFolders) {
                selected.clear()
                selected.addAll(folders.map { it.id })
            }
            renderState()
        }

        loadFolders()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.foldersRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top + dp(8))
            insets
        }
    }

    private fun loadFolders() {
        binding.loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                GalleryRepository(applicationContext).loadSnapshot(emptySet()).albums
            }
            folders = loaded
            // Default selection when nothing was scoped yet: everything checked (so turning "All
            // folders" off lets the user start unchecking).
            if (selected.isEmpty()) selected.addAll(folders.map { it.id })
            binding.loading.visibility = View.GONE
            buildRows()
            renderState()
        }
    }

    private fun buildRows() {
        binding.foldersContainer.removeAllViews()
        val inflater = layoutInflater
        for (folder in folders) {
            val row = ItemIndexFolderBinding.inflate(inflater, binding.foldersContainer, false)
            row.folderName.text = folder.name
            row.folderCount.text = if (folder.count == 1) "1 item" else "${folder.count} items"
            row.root.setOnClickListener {
                if (allFolders) return@setOnClickListener
                if (!selected.add(folder.id)) selected.remove(folder.id)
                row.folderCheck.isChecked = folder.id in selected
            }
            row.root.tag = folder.id
            binding.foldersContainer.addView(row.root)
        }
    }

    private fun renderState() {
        binding.switchAllFolders.isChecked = allFolders
        val container = binding.foldersContainer
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val id = child.tag as? String ?: continue
            val cb = child.findViewById<android.widget.CheckBox>(R.id.folderCheck)
            cb.isChecked = allFolders || id in selected
            child.isEnabled = !allFolders
            child.alpha = if (allFolders) 0.5f else 1f
        }
    }

    override fun finish() {
        persistIfChanged()
        super.finish()
    }

    private fun persistIfChanged() {
        if (folders.isEmpty()) return  // never loaded; don't clobber existing scope
        val existingIds = folders.map { it.id }.toSet()
        val newScope: Set<String> = when {
            allFolders -> emptySet()
            selected.isEmpty() -> emptySet() // unchecking everything falls back to "all"
            else -> selected.intersect(existingIds)
        }
        if (newScope != originalScope) {
            IndexScopeStore.setFolderIds(this, newScope)
            IndexController.rescan(this)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
