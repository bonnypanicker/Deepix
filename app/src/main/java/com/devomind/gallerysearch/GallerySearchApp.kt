package com.devomind.gallerysearch

import android.app.Application
import android.os.StrictMode
import kotlin.concurrent.thread

class GallerySearchApp : Application() {
    val sharedEncoders: SharedEncoders by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SharedEncoders(applicationContext)
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
        // Enforce the Recycle Bin's 30-day retention off the main thread on each cold start.
        thread(isDaemon = true) { runCatching { BinManager.purgeExpired(applicationContext) } }
    }
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
            faceEmbedder ?: FaceEmbedder(context, threadCount = 2).also { faceEmbedder = it }
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
}
