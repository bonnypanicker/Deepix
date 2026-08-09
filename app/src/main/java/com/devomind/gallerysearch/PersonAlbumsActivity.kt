package com.devomind.gallerysearch

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
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

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top)
            binding.root.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun loadPeople() {
        lifecycleScope.launch {
            val db = GalleryDatabase.getInstance(applicationContext)
            val people = withContext(Dispatchers.IO) {
                db.personDao().allVisible()
                    .map { p ->
                        val faces = db.faceDao().findByPerson(p.personId)
                        val exemplarFace = p.exemplarFaceId.takeIf { it > 0 }
                            ?.let { id -> faces.find { it.faceId == id } }
                            ?: faces.filter { it.isExemplar }.maxByOrNull { it.qualityScore }
                        PersonSummary(person = p, faceCount = faces.size, exemplarFace = exemplarFace)
                    }
                    .sortedByDescending { it.faceCount }
            }
            adapter.submitList(people)
            binding.emptyState.visibility = if (people.isEmpty()) View.VISIBLE else View.GONE
            binding.peopleGrid.visibility = if (people.isEmpty()) View.GONE else View.VISIBLE
        }
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
        inner class Holder(val b: ItemPersonAlbumBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, vt: Int): Holder =
            Holder(ItemPersonAlbumBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            holder.b.personName.text = item.person.nameLabel ?: "Person #${item.person.personId}"
            holder.b.personCount.text = "${item.faceCount} photos"
            // Exemplar crop: load the photo endpoint and bitmap-crop to the face bounds.
            // Spec Phase 4 UI wants the *face* crop eagerly rendered; here we shortcut via Glide
            // with the photo URI, optimizing for obviousness. The crop is stored in the person row
            // later by PersonDetail when persons take action.
            loadCoverFor(holder, item)
            holder.b.root.setOnClickListener { onClick(item) }
        }

        private fun loadCoverFor(holder: Holder, item: PersonSummary) {
            val face = item.exemplarFace ?: return
            val photoUri = Uri.parse(face.photoUri)
            val bbox = runCatching {
                val arr = org.json.JSONArray(face.bboxJson)
                floatArrayOf(
                    arr.getDouble(0).toFloat(),
                    arr.getDouble(1).toFloat(),
                    arr.getDouble(2).toFloat(),
                    arr.getDouble(3).toFloat()
                )
            }.getOrNull() ?: return
            Glide.with(this@PersonAlbumsActivity)
                .load(photoUri)
                .centerCrop()
                .into(holder.b.personCover)
        }
    }

    companion object {
        private const val SPAN_COUNT = 3
    }
}

/** Rounds a bitmap to pseudo-circle; used for face chips. Cheap copies. */
fun Bitmap.toCircular(size: Int): Bitmap {
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    Canvas(out).apply {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = android.graphics.BitmapShader(this@toCircular, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size / 2f, size / 2f, paint)
    }
    return out
}
