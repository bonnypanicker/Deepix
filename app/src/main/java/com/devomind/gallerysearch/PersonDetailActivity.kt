package com.devomind.gallerysearch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.devomind.gallerysearch.databinding.ActivityPersonDetailBinding
import com.devomind.gallerysearch.db.GalleryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Person detail: sampled faces of one Person (photo grid, paginated). User actions:
 *  - rename
 *  - merge with another Person (writes MERGE log row)
 *  - hide/archive (isHidden=true)
 *  - split a subset of faces into a new Person (writes SPLIT log row)
 *  - undo via PersonMergeLog auto-reversal (UNDO events)
 *
 * Only committed-on-disk PersonMutations apply; system suggestions in PersonMergeLog are for
 * ClusterMaintenance-generated ideas. Phase 4 UI is minimal and driven by DB state.
 */
class PersonDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPersonDetailBinding

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

        val personId = intent.getLongExtra(ExtraPersonId, -1L)
        if (personId <= 0) {
            finish()
            return
        }
        loadPerson(personId)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top)
            binding.root.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun loadPerson(personId: Long) {
        lifecycleScope.launch {
            val db = com.devomind.gallerysearch.db.GalleryDatabase.getInstance(applicationContext)
            val person = withContext(Dispatchers.IO) { db.personDao().findById(personId) }
            if (person == null) {
                binding.personNameLabel.text = "Unknown person"
                binding.personCountLabel.text = ""
                binding.facesContainer.visibility = View.GONE
                return@launch
            }
            binding.personNameLabel.text = person.nameLabel ?: "Person #${person.personId}"
            val facePhotoList = withContext(Dispatchers.IO) { db.faceDao().findByPerson(personId) }
            binding.personCountLabel.text = "${facePhotoList.size} photos"

            binding.btnRename.setOnClickListener { showRename(person) }
            binding.btnHide.setOnClickListener {
                lifecycleScope.launch {
                    db.personDao().hide(personId)
                    finish()
                }
            }
            binding.btnMerge.setOnClickListener { showMergePicker(person) }
            binding.btnSplitLearnt.setOnClickListener { showSplit(person) }
            binding.btnUndo.setOnClickListener { showUndo(person) }

            renderFacesPreview(facePhotoList)
        }
    }

    private fun renderFacesPreview(faces: List<com.devomind.gallerysearch.db.FaceEntity>) {
        // Lazy pattern: top N first; supports scrolling deeper later. Three-column grid, manually.
        binding.facesContainer.removeAllViews()
        val excerpts = faces.take(20)
        excerpts.chunked(3).forEach { rowFaces ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            rowFaces.forEach { face ->
                val card = layoutInflater.inflate(
                    R.layout.item_person_face,
                    row,
                    /* attachToRoot = */ false
                )
                val cover = card.findViewById<android.widget.ImageView>(R.id.faceCover)
                com.bumptech.glide.Glide.with(this)
                    .load(face.photoUri)
                    .centerCrop()
                    .into(cover)
                // Three equal columns per row.
                card.layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { marginEnd = 6 }
                row.addView(card)
            }
            binding.facesContainer.addView(row)
        }
        binding.facesCount.text = "${faces.size} total faces" + if (faces.size > 20) " (first 20 shown)" else ""
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
                            // 1) Move all faces from `person` to `target`.
                            db.faceDao().reassignPerson(person.personId, targetId)
                            // 2) Mark source hidden so it no longer shows in lists.
                            db.personDao().hide(person.personId)
                            // 3) Write the PersonMergeLog.
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
            // Shortcut picker for now: split off the most divergent face (highest dissimilarity to centroid)
            // Then propose the new person with the leftover faces. Full UI can come in Phase 5 polish.
            android.app.AlertDialog.Builder(this@PersonDetailActivity)
                .setTitle("Split?")
                .setMessage("Move the most divergent face to a new Person?")
                .setPositiveButton("Split") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            val db = GalleryDatabase.getInstance(applicationContext)
                            val centroid = faces.mapNotNull { it.embeddingJson }.firstOrNull()?.let { decodeEmbedding(it) }
                            if (centroid != null) {
                                val faceToRemove = faces.maxByOrNull { face ->
                                    face.embeddingJson?.let { j ->
                                        val e = decodeEmbedding(j)
                                        e?.let { 1f - cosine(centroid, it) } ?: 0f
                                    } ?: 0f
                                }
                                if (faceToRemove != null) {
                                    // Create a new Person and move the face.
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

    // Helpers
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

        fun launch(context: Context, personId: Long) {
            context.startActivity(Intent(context, PersonDetailActivity::class.java).apply {
                putExtra(ExtraPersonId, personId)
            })
        }
    }
}
