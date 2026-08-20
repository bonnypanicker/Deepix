package com.devomind.gallerysearch

import android.app.Application
import android.content.Context
import android.os.StrictMode
import android.util.Log
import kotlin.concurrent.thread

class GallerySearchApp : Application() {
    val sharedEncoders: SharedEncoders by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SharedEncoders(applicationContext)
    }

    /** Phase 2 flat face-embedding vector index — shared by PersonMatcher + the consistency check. */
    val faceVectorIndex: FaceVectorIndex by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        FaceVectorIndex(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            // Surface main-thread disk/network and leaked closables during development only.
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build()
            )
        }
        // Warm every SharedPreferences file on a background thread while the first Activity
        // inflates. The first touch of each file costs ~40–330 ms of disk I/O, and MainActivity's
        // onCreate reads five of them on the main thread; pre-touching here moves that cost off the
        // critical path (each getSharedPreferences loads its XML on a loader thread).
        thread(isDaemon = true) {
            val app = applicationContext
            for (name in StartupSharedPreferences) {
                runCatching { app.getSharedPreferences(name, Context.MODE_PRIVATE) }
            }
        }
        // Enforce the Recycle Bin's 30-day retention off the main thread on each cold start.
        thread(isDaemon = true) { runCatching { BinManager.purgeExpired(applicationContext) } }
        // Phase 2: repair any Face↔vector-index divergence (e.g. a crash between Room commit and
        // the last vector-index flush) before PersonMatcher reads the index. Cheap at phone scale.
        thread(isDaemon = true) {
            val readyForMaintenance = runCatching {
                kotlinx.coroutines.runBlocking {
                    FaceEmbeddingModelMigration.ensureCurrent(applicationContext)
                    FaceIndexConsistency.checkAndRepair(applicationContext)
                }
            }.onFailure { Log.w("GallerySearchApp", "Face index consistency check failed", it) }.isSuccess
            if (readyForMaintenance) FaceMaintenanceWorker.schedule(applicationContext)
        }
        // Phase 3: register the nightly face maintenance (split/merge detection + vector-index
        // corruption recovery). Battery-gated, no-network; runs behind WorkManager, NOT during
        // per-photo indexing so the two don't contend.
    }

    /**
     * Must mirror the PrefName/PrefsName constants in IndexPreferences, BottomBarConfig,
     * FavoritesStore, AlbumPinStore, SmartAlbumStore, AlbumCoverStore, IndexScopeStore, SafeStore,
     * FaceEmbeddingModelMigration and FaceVectorIndexStatus. A stale entry costs only a no-op read
     * (an unknown name never creates a file), so drift here is harmless.
     */
    private val StartupSharedPreferences = listOf(
        "index_prefs",
        "bottom_bar_config",
        "gallery_favorites",
        "album_pins",
        "smart_albums",
        "album_covers",
        "index_scope",
        "photo_safe",
        "face_embedding_model",
        "face_vector_index_status"
    )
}

class SharedEncoders(private val context: android.content.Context) {
    @Volatile private var imageEncoder: ImageEncoder? = null
    @Volatile private var textEncoder: TextEncoder? = null
    @Volatile private var faceEmbedder: FaceEmbedder? = null
    @Volatile private var faceDetector: YuNetDetector? = null

    // Separate lock per encoder: constructing an ImageEncoder and a TextEncoder are independent
    // (separate OrtSessions, separate model assets) — sharing one lock would serialize them even
    // when callers deliberately try to load both concurrently (see MainActivity.ensureEncodersLoaded).
    private val imageLock = Any()
    private val textLock = Any()
    private val faceLock = Any()
    private val detectorLock = Any()

    /** Thread count chosen for ORT sessions; falls back to the global default when unset. */
    private fun optimalThreadCount(): Int {
        return IndexPreferences.getOptimalThreadCount(context).takeIf { it > 0 }
            ?: OnnxSessionOptions.DefaultThreadCount
    }

    fun getImageEncoder(threadCount: Int? = null, preloadedModelBytes: ByteArray? = null): ImageEncoder {
        return imageEncoder ?: synchronized(imageLock) {
            imageEncoder ?: ImageEncoder.create(context, threadCount ?: optimalThreadCount(), preloadedModelBytes)
                .also { imageEncoder = it }
        }
    }

    fun getTextEncoder(threadCount: Int? = null): TextEncoder {
        return textEncoder ?: synchronized(textLock) {
            textEncoder ?: TextEncoder(context, threadCount ?: optimalThreadCount()).also { textEncoder = it }
        }
    }

    /** Phase 1 sessions are created lazily and shared process-wide; spec asks 2–4 intra-op threads. */
    fun getFaceEmbedder(): FaceEmbedder {
        return faceEmbedder ?: synchronized(faceLock) {
            faceEmbedder ?: FaceEmbedder(context, threadCount = 1).also { faceEmbedder = it }
        }
    }

    /**
     * Shared YuNet session at the spec's default operating point. FaceScanWorker builds its own
     * stricter-tuned instance; everything on the Phase 1/2 path shares this one so a per-photo
     * indexing loop doesn't re-read the model asset and rebuild an OrtSession for every image.
     */
    fun getFaceDetector(): YuNetDetector {
        return faceDetector ?: synchronized(detectorLock) {
            faceDetector ?: YuNetDetector(context).also { faceDetector = it }
        }
    }

    /**
     * Close the shared [FaceEmbedder] if loaded. Freed OrtSession memory returns to the OS;
     * the next [getFaceEmbedder] call recreates the session. Called by [MemoryBudget] under
     * system memory pressure (both foreground and WorkManager contexts).
     */
    fun closeFaceEmbedder() {
        synchronized(faceLock) {
            faceEmbedder?.let {
                runCatching { it.close() }.onFailure { Log.w("SharedEncoders", "close faceEmbedder", it) }
            }
            faceEmbedder = null
        }
    }

    /** Symmetric to [closeFaceEmbedder] for the YuNet detector. */
    fun closeFaceDetector() {
        synchronized(detectorLock) {
            faceDetector?.let {
                runCatching { it.close() }.onFailure { Log.w("SharedEncoders", "close faceDetector", it) }
            }
            faceDetector = null
        }
    }
}
