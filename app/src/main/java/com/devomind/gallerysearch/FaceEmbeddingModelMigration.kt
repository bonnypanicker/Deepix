package com.devomind.gallerysearch

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.devomind.gallerysearch.db.GalleryDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps derived people data internally consistent when the face-recognition model changes. */
object FaceEmbeddingModelMigration {

    data class Result(
        val reset: Boolean,
        val oldFaces: Int = 0,
        val oldPersons: Int = 0
    )

    private const val Tag = "FaceEmbeddingMigration"
    private const val Prefs = "face_embedding_model"
    private const val KeyVersion = "version"
    private val mutex = Mutex()

    suspend fun ensureCurrent(context: Context): Result = mutex.withLock {
        val appContext = context.applicationContext
        val app = appContext as GallerySearchApp
        val database = GalleryDatabase.getInstance(appContext)
        val faceDao = database.faceDao()
        val personDao = database.personDao()
        val prefs = appContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
        val previousVersion = prefs.getString(KeyVersion, null)
        val oldFaces = faceDao.countAll()
        val staleEmbeddings = faceDao.countWithDifferentEmbeddingModel(FaceEmbedder.ModelVersion)
        val needsReset = oldFaces > 0 &&
            (previousVersion != FaceEmbedder.ModelVersion || staleEmbeddings > 0)

        if (!needsReset) {
            if (previousVersion != FaceEmbedder.ModelVersion) {
                prefs.edit().putString(KeyVersion, FaceEmbedder.ModelVersion).apply()
            }
            return Result(reset = false)
        }

        // Parse the replacement model before touching persisted data. If the asset was damaged or
        // contains an operator unsupported by this ORT build, preserve the existing index intact.
        app.sharedEncoders.getFaceEmbedder()
        val oldPersons = personDao.all().size
        app.sharedEncoders.closeFaceEmbedder()
        database.withTransaction {
            // Ordering honours faces.personId's foreign key and keeps the reset atomic.
            faceDao.deleteAll()
            personDao.deleteAll()
            database.personMergeLogDao().deleteAll()
            database.personPhotoDao().resetForEmbeddingModel()
        }
        // Persist the marker last. A kill before it is stored repeats this idempotent reset on the
        // next launch instead of comparing stale vectors with SFace embeddings.
        app.faceVectorIndex.clear()
        FaceVectorIndexStatus.setOk(appContext)
        prefs.edit().putString(KeyVersion, FaceEmbedder.ModelVersion).commit()
        FaceIndexWorker.enqueue(appContext, replaceExisting = true)
        Log.i(Tag, "Switched to ${FaceEmbedder.ModelVersion}; reset $oldFaces faces and $oldPersons persons.")
        Result(reset = true, oldFaces = oldFaces, oldPersons = oldPersons)
    }
}
