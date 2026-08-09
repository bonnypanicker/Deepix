package com.devomind.gallerysearch

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.devomind.gallerysearch.databinding.ActivityFirstRunBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * First-run onboarding: explains what "People" indexing does locally with explicit user action.
 * Marks a stable flag once the user has made a choice — we never re-prompt after that.
 */
class FirstRunActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFirstRunBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.BLACK
        binding = ActivityFirstRunBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        binding.progressBar.visibility = View.GONE
        binding.progressText.visibility = View.GONE
        binding.progressBar.max = FirstRunLightningBurstSize

        binding.btnFirstScan.setOnClickListener { startFirstScan() }
        binding.btnSkip.setOnClickListener {
            IndexPreferences.setFirstRunDone(this, true)
            finish()
        }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }

    private fun startFirstScan() {
        IndexPreferences.setFirstRunDone(this, true)
        binding.btnFirstScan.isEnabled = false
        binding.btnSkip.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.progressText.visibility = View.VISIBLE

        lifecycleScope.launch {
            // Full photo count via MediaStore, but only need an estimate for the UI.
            val repository = GalleryRepository(applicationContext)
            val totalPhotos = withContext(Dispatchers.IO) { repository.getImageItemsForAlbumIds(emptySet()).size }
            binding.progressBar.max = max(1, totalPhotos)
            binding.progressText.text = "Scanning…"

            // Queue the face-index worker; Android handles scheduling. We can't easily track its
            // progress without the WorkManager observer, which the Settings tab reads — so we finish
            // once the work is queued and let the user see it land in Settings.
            FaceIndexWorker.enqueue(applicationContext, replaceExisting = true)

            finish()
        }
    }

    companion object {
        private const val FirstRunLightningBurstSize = 500

        /** Cold-start entrypoint: only prompts the user once ever. */
        fun ensure(context: android.content.Context) {
            if (IndexPreferences.hasSeenFirstRun(context)) return
            val intent = android.content.Intent(context, FirstRunActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
