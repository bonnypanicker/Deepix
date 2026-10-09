package com.devomind.gallerysearch

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Exported ACTION_VIEW entry point. Android's resolver lists this as "Open with Pixa AI Gallery" for
 * photo inputs: it hands the photo to the in-app viewer, resolved through MediaStore when the Uri
 * points at a row there and shown from the granted Uri when it does not. Either way the handoff is
 * the single-item one MainActivity already uses for a photo with no on-screen list to join.
 *
 * It answers ACTION_VIEW alone, and [EditDispatchActivity] answers ACTION_EDIT alone, because the two
 * intents promise different things: viewing never writes, while the editor's Save overwrites the
 * original. Registered types are the filter's own check, so nothing here re-reads the MIME type.
 */
class OpenDispatchActivity : AppCompatActivity() {

    private var viewerLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)

        // The viewer was already started before this dispatcher was recreated — starting it again
        // would stack two copies of the same photo.
        if (savedInstanceState?.getBoolean(StateViewerLaunched) == true) {
            finish()
            return
        }

        val uri = intent.data
        if (intent.action != Intent.ACTION_VIEW || uri == null) {
            finish()
            return
        }

        lifecycleScope.launch {
            val repository = withContext(Dispatchers.IO) { GalleryRepository(applicationContext) }
            val opened = withContext(Dispatchers.IO) {
                repository.getImageItemsForUris(listOf(uri.toString())).firstOrNull()
            }
            val items = opened?.let { listOf(it) } ?: listOf(standInItem(uri))
            ViewerItemsHolder.store(items)
            // The holder only hands the list back when the marker matches a stored Uri exactly, so the
            // marker is the resolved row's own Uri — a document-style Uri for the same photo would
            // otherwise look up nothing and the viewer would finish without a page.
            val marker = items.first().uri.toString()

            val viewerIntent = Intent(this@OpenDispatchActivity, ViewerActivity::class.java).apply {
                putExtra(ViewerActivity.ExtraMarker, marker)
                putExtra(ViewerActivity.ExtraPosition, 0)
                // Album id is the MediaStore bucket id here, and the viewer only offers "Set as album
                // cover" while that extra is set. A photo MediaStore has no row for has no album to
                // set a cover for, so the action stays hidden rather than pointing at an empty bucket.
                if (opened != null) {
                    putExtra(ViewerActivity.ExtraAlbumId, opened.bucketId)
                    putExtra(ViewerActivity.ExtraAlbumName, opened.bucketName)
                }
            }
            viewerLaunched = true
            startActivity(viewerIntent)
            finish()
        }
    }

    /**
     * A photo the MediaStore index does not know — a FileProvider Uri from a file manager or another
     * gallery, handed over with a temporary read grant. Enough for the viewer, which reads a page's
     * date, dimensions and path from the Uri itself and falls back to blanks when that query finds
     * nothing. The neighbours in that folder are not listed: the grant covers this one file.
     */
    private fun standInItem(uri: Uri): GalleryRepository.MediaItem =
        GalleryRepository.MediaItem(
            uri = uri,
            bucketId = "",
            bucketName = "",
            dateMillis = 0L,
            width = 0,
            height = 0,
            mimeType = intent.type ?: contentResolver.getType(uri),
            displayName = uri.lastPathSegment?.substringAfterLast('/'),
            mediaType = GalleryRepository.MediaType.Image
        )

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(StateViewerLaunched, viewerLaunched)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val StateViewerLaunched = "viewer_launched"
    }
}
