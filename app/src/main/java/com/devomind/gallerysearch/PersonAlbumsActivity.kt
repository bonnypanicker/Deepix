package com.devomind.gallerysearch

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.devomind.gallerysearch.databinding.ActivityPersonAlbumsBinding
import com.devomind.gallerysearch.databinding.ItemPersonAlbumBinding
import com.devomind.gallerysearch.db.GalleryDatabase
import com.devomind.gallerysearch.db.PersonEntity
import kotlinx.coroutines.Dispatchers
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

        adapter = PersonAlbumsAdapter { person -> openPerson(person) }
        binding.peopleGrid.layoutManager = GridLayoutManager(this, SPAN_COUNT)
        binding.peopleGrid.adapter = adapter

        loadPeople()
    }

    override fun onResume() {
        super.onResume()
        loadPeople()
    }

    override fun onStart() {
        super.onStart()
        /*
        super.onStart()
        // Live face-index ticker: refresh stamps when the worker advances.
        // WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(FaceIndexWorker.WorkName)
        androidx.work.WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(FaceIndexWorker.WorkName)
            .observe(this) { work ->
                val info = work.firstOrNull() ?: return@observe
                val progress = info.progress
                val visited = progress.getInt(FaceIndexWorker.ProgressVisitedKey, -1)
                val total = progress.getInt(FaceIndexWorker.ProgressTotalKey, -1)
                val faces = progress.getInt(FaceIndexWorker.StatsFacesKey, 0)
                val persons = progress.getInt(FaceIndexWorker.StatsPersonsKey, 0)
                val running = info.state == androidx.work.WorkInfo.State.RUNNING
                binding.liveStatus.apply {
                    visibility = if (running) View.VISIBLE else View.GONE
                    text = if (running && visited >= 0 && total > 0) {
                        "Indexing… $visited / $total photos · $faces faces · $persons persons"
                    } else ""
                }
                // Pull fresh contents from DB when the worker finishes a chunk so the grid reflects them.
                if (running) loadPeople()
            }
    }

        */
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
            if (requestGeneration != peopleLoadGeneration) return@launch
            adapter.photoDimensions = dimensions
            adapter.submitList(people)
            binding.emptyState.visibility = if (people.isEmpty()) View.VISIBLE else View.GONE
            binding.peopleGrid.visibility = if (people.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    /**
     * Queries MediaStore for the width/height of the given photo URIs in one pass, converted into
     * the *face-detection bitmap's* coordinate space: EXIF orientation applied (w/h swapped on
     * 90°/270°), then downsampled by the same power-of-two inSampleSize rule
     * [GalleryRepository.loadBitmapForFaceDetection] uses toward
     * [GalleryRepository.FaceDetectionMaxEdge]. Returns a map keyed by the uri string →
     * intArrayOf [width, height]. [FaceCropTransform] scales the stored face bbox (which
     * FaceAnalyzer measured in that detection space) into the Glide-decoded bitmap's space using
     * these dims — passing raw MediaStore dims here double-downscales the crop and the cover
     * shows background instead of the face.
     */
    private fun resolvePhotoDimensions(uris: Set<String>): Map<String, IntArray> {
        if (uris.isEmpty()) return emptyMap()
        val idToUri = LinkedHashMap<Long, String>()
        for (u in uris) {
            val id = runCatching {
                Uri.parse(u).lastPathSegment?.toLongOrNull()
            }.getOrNull()
            if (id != null && id > 0) idToUri[id] = u
        }
        if (idToUri.isEmpty()) return emptyMap()
        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val ids = idToUri.keys.joinToString(",")
        val out = HashMap<String, IntArray>(idToUri.size)
        val cursor = runCatching {
            contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.ORIENTATION
                ),
                "${MediaStore.Images.Media._ID} IN ($ids)",
                null,
                null
            )
        }.getOrNull() ?: return out
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val wIdx = it.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val hIdx = it.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val oIdx = it.getColumnIndex(MediaStore.Images.Media.ORIENTATION)
            while (it.moveToNext()) {
                val id = it.getLong(idIdx)
                var w = it.getInt(wIdx)
                var h = it.getInt(hIdx)
                val uriStr = idToUri[id] ?: continue
                if (w <= 0 || h <= 0) continue
                // Match the oriented detection bitmap: 90°/270° rotations swap width/height.
                val orientation = if (oIdx >= 0) it.getInt(oIdx) else 0
                if (orientation == 90 || orientation == 270) {
                    val tmp = w; w = h; h = tmp
                }
                // Mirror decodeOrientedBitmap's inSampleSize loop so dims describe the bitmap
                // YuNet actually ran on, not the full-resolution file.
                var sample = 1
                while (maxOf(w, h) / sample > GalleryRepository.FaceDetectionMaxEdge) sample *= 2
                out[uriStr] = intArrayOf(w / sample, h / sample)
            }
        }
        return out
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
        private val onClick: (PersonSummary) -> Unit
    ) : androidx.recyclerview.widget.ListAdapter<PersonSummary, PersonAlbumsAdapter.Holder>(
        object : androidx.recyclerview.widget.DiffUtil.ItemCallback<PersonSummary>() {
            override fun areItemsTheSame(a: PersonSummary, b: PersonSummary) = a.person.personId == b.person.personId
            override fun areContentsTheSame(a: PersonSummary, b: PersonSummary) = a == b
        }
    ) {

        /** uri string → [origW, origH]; set before submitList so covers crop to the face. */
        var photoDimensions: Map<String, IntArray> = emptyMap()

        inner class Holder(val b: ItemPersonAlbumBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, vt: Int): Holder =
            Holder(ItemPersonAlbumBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            holder.b.personName.text = photoCountText(item.photoCount)
            loadCoverFor(holder, item)
            holder.b.root.setOnClickListener { onClick(item) }
        }

        private fun photoCountText(count: Int): String =
            if (count == 1) "1 photo" else "$count photos"

        private fun loadCoverFor(holder: Holder, item: PersonSummary) {
            val glide = Glide.with(this@PersonAlbumsActivity)
            val face = item.exemplarFace
            if (face == null) {
                glide.clear(holder.b.personCover)
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
            val request = glide.load(photoUri)
                .override(CoverDecodePx, CoverDecodePx)
                .format(DecodeFormat.PREFER_ARGB_8888)
            if (bbox != null && dims != null && dims.size >= 2 && dims[0] > 0 && dims[1] > 0) {
                request.transform(FaceCropTransform(bbox, dims[0], dims[1]))
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
    }
}
