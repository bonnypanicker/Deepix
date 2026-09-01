package com.devomind.gallerysearch

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.devomind.gallerysearch.databinding.ActivityPersonAlbumsBinding
import com.devomind.gallerysearch.databinding.ItemPersonAlbumBinding
import com.devomind.gallerysearch.db.GalleryDatabase
import com.devomind.gallerysearch.db.PersonEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "People" landing: album-style grid of Persons the system has indexed. Tap → Person detail.
 *
 * Rationale for building this here instead of augmenting an existing albums row: the People grid's
 * data source is the Person table (not MediaStore buckets). Mixing them re-complicates lifecycle
 * with the existing adapter logic which is heavily tuned around MediaStore.
 */
class PersonAlbumsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPersonAlbumsBinding
    private lateinit var adapter: PersonAlbumsAdapter
    private var peopleLoadGeneration = 0
    private var lastFaceCount = 0
    private var faceWorkInfos: List<WorkInfo> = emptyList()
    private var lastPersonsSeen = -1
    private val coverCache = object : LruCache<String, android.graphics.Bitmap>(CoverCacheKb) {
        override fun sizeOf(key: String, bitmap: android.graphics.Bitmap): Int =
            bitmap.allocationByteCount / 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.BLACK
        binding = ActivityPersonAlbumsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        binding.backBtn.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.emptyAction.setOnClickListener {
            FaceIndexWorker.enqueue(this, replaceExisting = false)
        }

        adapter = PersonAlbumsAdapter(
            onClick = { person -> openPerson(person) },
            // Long-press assigns identity (name and/or relationship) without leaving the grid.
            onLongClick = { person ->
                PersonIdentityEditor.show(this, person.person) { loadPeople() }
            }
        )
        binding.peopleGrid.layoutManager = GridLayoutManager(this, SPAN_COUNT)
        binding.peopleGrid.adapter = adapter
        PullToRefresh.bind(binding.peopleGrid, onRefresh = { loadPeople() })

        loadPeople()
    }

    override fun onResume() {
        super.onResume()
        loadPeople()
    }

    override fun onStart() {
        super.onStart()
        // Live face-index ticker: keeps the empty state truthful while the worker advances,
        // and pulls fresh people into the grid as new persons materialize.
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(FaceIndexWorker.WorkName)
            .observe(this) { work ->
                faceWorkInfos = work
                val info = work.firstOrNull()
                val persons = info?.progress?.getInt(FaceIndexWorker.StatsPersonsKey, lastPersonsSeen)
                    ?: lastPersonsSeen
                if (info?.state == WorkInfo.State.RUNNING && persons != lastPersonsSeen) {
                    lastPersonsSeen = persons
                    loadPeople()
                }
                updateEmptyState()
            }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top)
            binding.root.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun loadPeople() {
        val requestGeneration = ++peopleLoadGeneration
        lifecycleScope.launch {
            val db = GalleryDatabase.getInstance(applicationContext)
            val people = withContext(Dispatchers.IO) {
                db.personDao().allVisible()
                    .map { p ->
                        val faces = db.faceDao().findByPerson(p.personId)
                        val photoCount = faces.mapTo(HashSet()) { it.photoUri }.size
                        // A person matcher keeps a diverse exemplar pool for recognition, but the
                        // People cover should favour the sharpest, largest eligible source face.
                        val exemplarFace = faces
                            .asSequence()
                            .filter { it.embeddingJson != null && !it.isLowQuality }
                            .maxByOrNull { it.qualityScore }
                            ?: p.exemplarFaceId.takeIf { it > 0 }
                                ?.let { id -> faces.find { it.faceId == id } }
                            ?: faces.maxByOrNull { it.qualityScore }
                        PersonSummary(
                            person = p,
                            faceCount = faces.size,
                            photoCount = photoCount,
                            exemplarFace = exemplarFace
                        )
                    }
                    .sortedWith(
                        compareByDescending<PersonSummary> { it.photoCount }
                            .thenByDescending { it.faceCount }
                            .thenBy { it.person.personId }
                    )
            }
            // Resolve original photo dimensions for each exemplar face so the cover can be cropped
            // to the actual face (bbox is in source-image pixels).
            val exemplarUris = people.mapNotNull { it.exemplarFace?.photoUri }.toHashSet()
            val dimensions = withContext(Dispatchers.IO) { resolvePhotoDimensions(exemplarUris) }
            val faceTotal = withContext(Dispatchers.IO) { db.faceDao().countAll() }
            if (requestGeneration != peopleLoadGeneration) return@launch
            lastFaceCount = faceTotal
            adapter.photoDimensions = dimensions
            adapter.submitList(people)
            binding.emptyState.visibility = if (people.isEmpty()) View.VISIBLE else View.GONE
            binding.peopleGrid.visibility = if (people.isEmpty()) View.GONE else View.VISIBLE
            updateEmptyState()
        }
    }

    /**
     * No-people state, made explicit: indexing in flight (with live progress), faces indexed but
     * not yet grouped, or never indexed — each with its own copy, the last with a start action.
     */
    private fun updateEmptyState() {
        if (adapter.itemCount > 0) {
            binding.emptyState.visibility = View.GONE
            return
        }
        binding.emptyState.visibility = View.VISIBLE
        val info = faceWorkInfos.firstOrNull()
        val working = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
        when {
            working -> {
                val visited = info?.progress?.getInt(FaceIndexWorker.ProgressVisitedKey, -1) ?: -1
                val total = info?.progress?.getInt(FaceIndexWorker.ProgressTotalKey, -1) ?: -1
                val faces = info?.progress?.getInt(FaceIndexWorker.StatsFacesKey, 0) ?: 0
                binding.emptyTitle.text = "Finding faces…"
                binding.emptyHint.text = when {
                    visited >= 0 && total > 0 && faces > 0 ->
                        "Scanning your library — $visited / $total photos · $faces faces found so far. " +
                            "People appear here as faces are grouped."
                    visited >= 0 && total > 0 ->
                        "Scanning your library — $visited / $total photos. " +
                            "People appear here as faces are grouped."
                    else ->
                        "Scanning your library for faces. People appear here as faces are grouped."
                }
                binding.emptyAction.visibility = View.GONE
            }
            lastFaceCount > 0 -> {
                binding.emptyTitle.text = "Grouping faces…"
                binding.emptyHint.text =
                    "Faces are indexed. They're clustered into people as indexing finishes — " +
                        "check back in a moment."
                binding.emptyAction.visibility = View.GONE
            }
            else -> {
                binding.emptyTitle.setText(R.string.people_empty)
                binding.emptyHint.setText(R.string.people_empty_hint)
                binding.emptyAction.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Original photo dimensions converted into the face-detection bitmap's coordinate space —
     * delegated to the shared [FaceDetectionDims] helper (also used by the search page's
     * "Search by person" row). See there for why detection-space dims matter.
     */
    private fun resolvePhotoDimensions(uris: Set<String>): Map<String, IntArray> {
        return FaceDetectionDims.resolve(contentResolver, uris)
    }

    private fun openPerson(person: PersonSummary) {
        val intent = Intent(this, PersonDetailActivity::class.java).apply {
            putExtra(PersonDetailActivity.ExtraPersonId, person.person.personId)
        }
        person.person.exemplarFaceId.takeIf { it > 0 }?.let { intent.putExtra("extra_photo_uri", it.toString()) }
        startActivity(intent)
    }

    /** Row model. */
    data class PersonSummary(
        val person: PersonEntity,
        val faceCount: Int,
        val photoCount: Int,
        /** Either cropped face URI or null; backed by person.exemplarFaceId -> photoUri on lookup. */
        val exemplarFace: com.devomind.gallerysearch.db.FaceEntity?
    )

    private inner class PersonAlbumsAdapter(
        private val onClick: (PersonSummary) -> Unit,
        private val onLongClick: (PersonSummary) -> Unit
    ) : androidx.recyclerview.widget.ListAdapter<PersonSummary, PersonAlbumsAdapter.Holder>(
        object : androidx.recyclerview.widget.DiffUtil.ItemCallback<PersonSummary>() {
            override fun areItemsTheSame(a: PersonSummary, b: PersonSummary) = a.person.personId == b.person.personId
            override fun areContentsTheSame(a: PersonSummary, b: PersonSummary) = a == b
        }
    ) {

        /** uri string → [origW, origH]; set before submitList so covers crop to the face. */
        var photoDimensions: Map<String, IntArray> = emptyMap()

        inner class Holder(val b: ItemPersonAlbumBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root) {
            var coverJob: Job? = null
            var coverKey: String? = null
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, vt: Int): Holder =
            Holder(ItemPersonAlbumBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val label = PersonIdentity.displayName(item.person)
            if (label != null) {
                holder.b.personName.text = label
                holder.b.personName.setTextColor(
                    androidx.core.content.ContextCompat.getColor(holder.b.root.context, R.color.metroTextPrimary)
                )
                holder.b.personCount.text = photoCountText(item.photoCount)
                holder.b.personCount.visibility = View.VISIBLE
            } else {
                // No label yet — show just the count; never a "Person #id" placeholder.
                holder.b.personName.text = photoCountText(item.photoCount)
                holder.b.personName.setTextColor(
                    androidx.core.content.ContextCompat.getColor(holder.b.root.context, R.color.metroTextSecondary)
                )
                holder.b.personCount.visibility = View.GONE
            }
            loadCoverFor(holder, item)
            holder.b.root.setOnClickListener { onClick(item) }
            holder.b.root.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }

        private fun photoCountText(count: Int): String =
            if (count == 1) "1 photo" else "$count photos"

        private fun loadCoverFor(holder: Holder, item: PersonSummary) {
            val glide = Glide.with(this@PersonAlbumsActivity)
            holder.coverJob?.cancel()
            holder.coverJob = null
            glide.clear(holder.b.personCover)
            val face = item.exemplarFace
            if (face == null) {
                holder.b.personCover.setImageDrawable(null)
                return
            }
            val photoUri = Uri.parse(face.photoUri)
            val bbox = runCatching {
                val arr = org.json.JSONArray(face.bboxJson)
                floatArrayOf(
                    arr.getDouble(0).toFloat(),
                    arr.getDouble(1).toFloat(),
                    arr.getDouble(2).toFloat(),
                    arr.getDouble(3).toFloat()
                )
            }.getOrNull()
            val dims = photoDimensions[face.photoUri]
            if (bbox != null && dims != null && dims.size >= 2 && dims[0] > 0 && dims[1] > 0) {
                val coverKey = "${face.faceId}:${face.bboxJson}"
                holder.coverKey = coverKey
                coverCache.get(coverKey)?.let { cached ->
                    holder.b.personCover.setImageBitmap(cached)
                    return
                }
                holder.b.personCover.setImageDrawable(null)
                holder.coverJob = lifecycleScope.launch {
                    val decoded = withContext(Dispatchers.IO) {
                        OriginalFaceCoverDecoder.decode(
                            applicationContext,
                            photoUri,
                            bbox,
                            dims[0],
                            dims[1],
                            CoverSourceEdge,
                            rotationDegrees = face.rotationDegrees
                        )
                    }
                    if (holder.coverKey != coverKey) return@launch
                    if (decoded != null) {
                        coverCache.put(coverKey, decoded)
                        holder.b.personCover.setImageBitmap(decoded)
                    } else {
                        loadFallbackCover(holder, photoUri, bbox, dims, face.rotationDegrees)
                    }
                }
            } else {
                holder.coverKey = null
                loadFallbackCover(holder, photoUri, null, null)
            }
        }

        private fun loadFallbackCover(
            holder: Holder,
            photoUri: Uri,
            bbox: FloatArray?,
            dims: IntArray?,
            rotationDegrees: Int = 0
        ) {
            val request = Glide.with(this@PersonAlbumsActivity)
                .load(photoUri)
                .override(CoverDecodePx, CoverDecodePx)
                .format(DecodeFormat.PREFER_ARGB_8888)
            if (bbox != null && dims != null && dims.size >= 2) {
                request.transform(FaceCropTransform(bbox, dims[0], dims[1], rotationDegrees = rotationDegrees))
                    .into(holder.b.personCover)
            } else {
                request.centerCrop().into(holder.b.personCover)
            }
        }
    }

    companion object {
        private const val SPAN_COUNT = 3
        // Display-only quality; this does not change the detector or MobileFaceNet input.
        private const val CoverDecodePx = 512
        private const val CoverSourceEdge = 768
        private const val CoverCacheKb = 12 * 1024
    }
}
