package com.devomind.gallerysearch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
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
import com.devomind.gallerysearch.databinding.ActivityPersonDetailBinding
import com.devomind.gallerysearch.db.GalleryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Person detail: a proper paged timeline of the unique photos that contain a face of this Person,
 * reusing the same ImageAdapter + sticky day-header infrastructure as the main browse view.
 * User actions: rename, merge, hide, split, undo — unchanged from the Phase 4 mockup; only the
 * photo rendering was replaced (was a manual 3-column LinearLayout capped at 20 faces with a
 * fixed 140dp centerCrop that clipped aspect ratios).
 */
class PersonDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPersonDetailBinding
    private lateinit var adapter: ImageAdapter
    private lateinit var gridLayoutManager: GridLayoutManager

    private val personPhotos = ArrayList<GalleryRepository.MediaItem>()
    private var pagedDisplayedCount = 0
    private var pagedLastDay: LocalDate? = null
    private var pagingInFlight = false
    private var personId: Long = 0L

    private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
    private val dayFormatter = DateTimeFormatter.ofPattern("EEE, d", Locale.getDefault())
        .withZone(ZoneId.systemDefault())

    private val viewerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.data?.getBooleanExtra(ViewerActivity.ExtraContentChanged, false) == true) {
            loadPerson(personId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.BLACK
        binding = ActivityPersonDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        binding.backBtn.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        personId = intent.getLongExtra(ExtraPersonId, -1L)
        if (personId <= 0) {
            finish()
            return
        }

        adapter = ImageAdapter(
            onPhotoClick = { item, sharedView -> openMedia(item, sharedView) },
            onSelectionChanged = {},
            onAlbumClick = {},
            onAlbumLongClick = { _, _ -> }
        )
        adapter.gridColumnCount = IndexPreferences.getGridColumnCount(this)

        gridLayoutManager = GridLayoutManager(this, adapter.gridColumnCount)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                adapter.spanSizeAt(position, gridLayoutManager.spanCount)
        }
        binding.facesGrid.layoutManager = gridLayoutManager
        binding.facesGrid.adapter = adapter
        binding.facesGrid.setHasFixedSize(true)
        binding.facesGrid.setItemViewCacheSize(12)
        binding.facesGrid.recycledViewPool.setMaxRecycledViews(ImageAdapter.ViewTypePhoto, 24)
        binding.facesGrid.addItemDecoration(StickyHeaderDecoration(adapter))
        binding.facesGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lastVisible = gridLayoutManager.findLastVisibleItemPosition()
                val total = adapter.itemCount
                if (!pagingInFlight &&
                    pagedDisplayedCount < personPhotos.size &&
                    lastVisible >= total - PAGE_PREFETCH
                ) {
                    paginateNext()
                }
            }
        })

        loadPerson(personId)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top)
            binding.facesGrid.updatePadding(bottom = bars.bottom + 20)
            insets
        }
    }

    private fun loadPerson(pid: Long) {
        lifecycleScope.launch {
            val db = GalleryDatabase.getInstance(applicationContext)
            val person = withContext(Dispatchers.IO) { db.personDao().findById(pid) }
            if (person == null) {
                binding.personNameLabel.text = getString(R.string.people_title)
                binding.personCountLabel.text = ""
                adapter.replaceCells(
                    listOf(GalleryCell.Empty("No photos", "This person no longer exists."))
                )
                return@launch
            }
            binding.personNameLabel.text = getString(R.string.people_title)

            // Faces → unique source photos → MediaItems (date-sorted). A photo with two faces of
            // the same person collapses to a single tile here.
            val (items, faceCount) = withContext(Dispatchers.IO) {
                val repo = GalleryRepository(applicationContext)
                val faceDao = db.faceDao()
                repo.getImageItemsForUris(faceDao.distinctPhotoUrisByPerson(pid)) to
                    faceDao.countByPerson(pid)
            }
            personPhotos.clear()
            personPhotos.addAll(items)
            binding.personCountLabel.text = collectionCountText(items.size, faceCount)

            if (items.isEmpty()) {
                adapter.replaceCells(
                    listOf(GalleryCell.Empty("No photos yet", "This person has no indexed photos."))
                )
                return@launch
            }
            renderFirstPage()
        }
    }

    private fun renderFirstPage() {
        pagedDisplayedCount = 0
        pagedLastDay = null
        pagingInFlight = false
        val to = pageEnd(0)
        val (cells, lastDay) = buildTimelineCells(0, to, null)
        pagedDisplayedCount = to
        pagedLastDay = lastDay
        adapter.replaceCells(cells)
        binding.facesGrid.scrollToPosition(0)
    }

    private fun collectionCountText(photoCount: Int, faceCount: Int): String {
        val photos = if (photoCount == 1) "1 photo" else "$photoCount photos"
        val faces = if (faceCount == 1) "1 face" else "$faceCount faces"
        return "$photos · $faces"
    }

    private fun paginateNext() {
        val from = pagedDisplayedCount
        if (from >= personPhotos.size) return
        pagingInFlight = true
        val to = pageEnd(from)
        val continuing = pagedLastDay
        lifecycleScope.launch {
            val (cells, lastDay) = withContext(Dispatchers.Default) {
                buildTimelineCells(from, to, continuing)
            }
            pagedDisplayedCount = to
            pagedLastDay = lastDay
            adapter.updateCells(adapter.cells + cells)
            pagingInFlight = false
        }
    }

    /** End index (exclusive) of the page starting at [from]: ~PAGE_SIZE, extended to the day
     *  boundary so a justified/grid row isn't split mid-day. */
    private fun pageEnd(from: Int): Int {
        val size = personPhotos.size
        var end = minOf(from + PAGE_SIZE, size)
        if (end in (from + 1) until size) {
            val boundaryDay = dayKey(personPhotos[end - 1].dateMillis)
            while (end < size &&
                (end - from) < PAGE_MAX &&
                dayKey(personPhotos[end].dateMillis) == boundaryDay
            ) {
                end++
            }
        }
        return end
    }

    private fun buildTimelineCells(
        from: Int,
        to: Int,
        continuingDay: LocalDate?
    ): Pair<List<GalleryCell>, LocalDate?> {
        val cells = ArrayList<GalleryCell>()
        var lastDay: LocalDate? = continuingDay
        var currentDayItems = ArrayList<GalleryRepository.MediaItem>()
        for (i in from until to) {
            val item = personPhotos[i]
            val dayKey = dayKey(item.dateMillis)
            if (dayKey != lastDay) {
                if (currentDayItems.isNotEmpty()) {
                    currentDayItems.forEach { cells += GalleryCell.Photo(it) }
                    currentDayItems = ArrayList()
                }
                if (dayKey != null) {
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
            currentDayItems.forEach { cells += GalleryCell.Photo(it) }
        }
        return cells to lastDay
    }

    private fun openMedia(item: GalleryRepository.MediaItem, sharedView: ImageView) {
        ViewerItemsHolder.store(personPhotos)
        val position = personPhotos.indexOfFirst { it.uri == item.uri }.let { if (it < 0) 0 else it }
        val transitionName = ViewCompat.getTransitionName(sharedView) ?: ""
        val intent = Intent(this, ViewerActivity::class.java).apply {
            putExtra(ViewerActivity.ExtraMarker, item.uri.toString())
            putExtra(ViewerActivity.ExtraPosition, position)
            if (transitionName.isNotEmpty()) putExtra(ViewerActivity.ExtraTransitionName, transitionName)
        }
        if (transitionName.isNotEmpty()) {
            val options = androidx.core.app.ActivityOptionsCompat
                .makeSceneTransitionAnimation(this, sharedView, transitionName)
            viewerLauncher.launch(intent, options)
        } else {
            viewerLauncher.launch(intent)
        }
    }

    private fun showRename(person: com.devomind.gallerysearch.db.PersonEntity) {
        val input = android.widget.EditText(this).apply {
            setText(person.nameLabel ?: "")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Name")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    GalleryDatabase.getInstance(applicationContext).personDao()
                        .rename(person.personId, input.text.toString().ifBlank { null })
                    loadPerson(person.personId)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMergePicker(person: com.devomind.gallerysearch.db.PersonEntity) {
        lifecycleScope.launch {
            val db = GalleryDatabase.getInstance(applicationContext)
            val others = withContext(Dispatchers.IO) {
                db.personDao().all()
                    .filter { it.personId != person.personId && !it.isHidden }
                    .map { "${it.nameLabel ?: "#" + it.personId}" to it.personId }
            }
            if (others.isEmpty()) {
                android.widget.Toast.makeText(this@PersonDetailActivity, "No other people to merge into", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = others.map { it.first }.toTypedArray()
            android.app.AlertDialog.Builder(this@PersonDetailActivity)
                .setTitle("Merge ${person.nameLabel ?: "Person #" + person.personId} into…")
                .setSingleChoiceItems(labels, -1) { dialog, which ->
                    val (_, targetId) = others[which]
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            db.faceDao().reassignPerson(person.personId, targetId)
                            db.personDao().hide(person.personId)
                            db.personMergeLogDao().insert(
                                com.devomind.gallerysearch.db.PersonMergeLogEntity(
                                    eventKind = com.devomind.gallerysearch.db.PersonMergeLogEntity.Event.MERGE,
                                    personId = targetId,
                                    otherPersonId = person.personId,
                                    origin = com.devomind.gallerysearch.db.PersonMergeLogEntity.Origin.USER
                                )
                            )
                        }
                        dialog.dismiss()
                        finish()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showSplit(person: com.devomind.gallerysearch.db.PersonEntity) {
        lifecycleScope.launch {
            val db = GalleryDatabase.getInstance(applicationContext)
            val faces = withContext(Dispatchers.IO) { db.faceDao().findByPerson(person.personId) }
            if (faces.size < 2) {
                android.widget.Toast.makeText(this@PersonDetailActivity, "Need at least two faces to split", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            android.app.AlertDialog.Builder(this@PersonDetailActivity)
                .setTitle("Split?")
                .setMessage("Move the most divergent face to a new Person?")
                .setPositiveButton("Split") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            val centroid = faces.mapNotNull { it.embeddingJson }.firstOrNull()?.let { decodeEmbedding(it) }
                            if (centroid != null) {
                                val faceToRemove = faces.maxByOrNull { face ->
                                    face.embeddingJson?.let { j ->
                                        val e = decodeEmbedding(j)
                                        e?.let { 1f - cosine(centroid, it) } ?: 0f
                                    } ?: 0f
                                }
                                if (faceToRemove != null) {
                                    val newPerson = com.devomind.gallerysearch.db.PersonEntity(
                                        nameLabel = null,
                                        exemplarFaceId = faceToRemove.faceId
                                    )
                                    val newPersonId = db.personDao().insert(newPerson)
                                    db.faceDao().reassignFaces(listOf(faceToRemove.faceId), newPersonId)
                                    db.faceDao().setExemplar(faceToRemove.faceId, true)
                                    db.personDao().setExemplarFace(newPersonId, faceToRemove.faceId)
                                    db.personMergeLogDao().insert(
                                        com.devomind.gallerysearch.db.PersonMergeLogEntity(
                                            eventKind = com.devomind.gallerysearch.db.PersonMergeLogEntity.Event.SPLIT,
                                            personId = person.personId,
                                            otherPersonId = newPersonId,
                                            origin = com.devomind.gallerysearch.db.PersonMergeLogEntity.Origin.USER
                                        )
                                    )
                                }
                            }
                        }
                        loadPerson(person.personId)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showUndo(person: com.devomind.gallerysearch.db.PersonEntity) {
        lifecycleScope.launch {
            val db = GalleryDatabase.getInstance(applicationContext)
            val recent = withContext(Dispatchers.IO) { db.personMergeLogDao().recent(1) }
            val undo = recent.firstOrNull()
            if (undo == null || undo.eventKind != com.devomind.gallerysearch.db.PersonMergeLogEntity.Event.MERGE && undo.eventKind != com.devomind.gallerysearch.db.PersonMergeLogEntity.Event.SPLIT) {
                android.widget.Toast.makeText(this@PersonDetailActivity, "Nothing to undo", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (!ClusterMaintenance.enqueueUndo(applicationContext, undo.id)) {
                android.widget.Toast.makeText(this@PersonDetailActivity, "Can't undo that event", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(this@PersonDetailActivity, "Undo noted → recompute needed", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun safeFormat(formatter: DateTimeFormatter, millis: Long, fallback: String): String =
        runCatching { formatter.format(Instant.ofEpochMilli(millis)) }.getOrDefault(fallback)

    private fun dayKey(millis: Long): LocalDate? =
        runCatching { Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate() }
            .getOrNull()

    private fun dayHeaderTitle(millis: Long): String {
        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now(ZoneId.systemDefault())
        return when (date) {
            today -> getString(R.string.today)
            today.minusDays(1) -> getString(R.string.yesterday)
            else -> safeFormat(dayFormatter, millis, "")
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    private fun decodeEmbedding(json: String): FloatArray? = runCatching {
        val arr = org.json.JSONArray(json)
        FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
    }.getOrNull()

    companion object {
        const val ExtraPersonId = "personId"

        private const val PAGE_SIZE = 120
        private const val PAGE_MAX = 320
        private const val PAGE_PREFETCH = 12

        fun launch(context: Context, personId: Long) {
            context.startActivity(Intent(context, PersonDetailActivity::class.java).apply {
                putExtra(ExtraPersonId, personId)
            })
        }
    }
}
