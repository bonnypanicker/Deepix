package com.devomind.gallerysearch

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Exported ACTION_EDIT entry point. Android's resolver displays this activity as "Edit with Pixa AI Gallery"
 * for photo and video MIME inputs; it immediately forwards the granted content Uri to the matching
 * in-app editor and returns that editor's result to the source application.
 */
class EditDispatchActivity : AppCompatActivity() {

    private var sourceUri: Uri? = null

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = sourceUri
        if (result.resultCode == RESULT_OK && uri != null) {
            val data = Intent().apply {
                this.data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_STREAM, uri)
                result.data?.extras?.let(::putExtras)
            }
            setResult(RESULT_OK, data)
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)

        // Avoid relaunching an editor if this small dispatcher is recreated while the editor is up.
        if (savedInstanceState?.getBoolean(StateEditorLaunched) == true) return

        val uri = intent.data ?: intent.getParcelableExtraCompat(Intent.EXTRA_STREAM)
        if (intent.action !in setOf(Intent.ACTION_EDIT, Intent.ACTION_VIEW) || uri == null) {
            finish()
            return
        }
        sourceUri = uri

        val mime = intent.type
            ?.takeIf { it.isNotBlank() }
            ?: contentResolver.getType(uri)
            ?: inferMimeFromUri(uri)
        val editorClass = when {
            mime?.startsWith("image/") == true -> PhotoEditorActivity::class.java
            mime?.startsWith("video/") == true -> VideoEditorActivity::class.java
            else -> null
        }
        if (editorClass == null) {
            MetroBanner.show(this, "Pixa AI Gallery can edit photos and videos")
            finish()
            return
        }

        val grantFlags = intent.flags and UriGrantFlags
        val editorIntent = Intent(this, editorClass).apply {
            putExtra(PhotoEditorActivity.ExtraUri, uri.toString())
            putExtra(PhotoEditorActivity.ExtraName, displayName(uri))
            addFlags(grantFlags)
            clipData = intent.clipData ?: android.content.ClipData.newRawUri("media", uri)
        }
        editorLauncher.launch(editorIntent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(StateEditorLaunched, true)
        sourceUri?.let { outState.putString(StateSourceUri, it.toString()) }
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        sourceUri = savedInstanceState.getString(StateSourceUri)?.let(Uri::parse)
    }

    private fun displayName(uri: Uri): String {
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        return runCatching {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "media"
    }

    private fun inferMimeFromUri(uri: Uri): String? {
        val name = uri.lastPathSegment.orEmpty().lowercase()
        return when {
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".png") -> "image/png"
            name.endsWith(".webp") -> "image/webp"
            name.endsWith(".heic") || name.endsWith(".heif") -> "image/heic"
            name.endsWith(".mp4") -> "video/mp4"
            name.endsWith(".mkv") -> "video/x-matroska"
            name.endsWith(".webm") -> "video/webm"
            name.endsWith(".3gp") -> "video/3gpp"
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, T::class.java)
        } else {
            getParcelableExtra(key)
        }

    companion object {
        private const val StateEditorLaunched = "editor_launched"
        private const val StateSourceUri = "source_uri"
        private const val UriGrantFlags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
