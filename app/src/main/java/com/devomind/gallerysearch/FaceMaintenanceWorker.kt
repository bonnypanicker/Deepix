package com.devomind.gallerysearch

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Phase 3 nightly maintenance job. Runs once per day under battery-and-time constraints; is
 * separate from the per-photo FaceIndexWorker so a photo-burst doesn't pile onto face-correction
 * work, and so we can reschedule independently.
 *
 * Steps, in order:
 *  1. If the face vector index failed its checksum, rebuild it from Room in chunks. Stops the
 *     rest of the pass if pressure kicks in (MemoryBudget background-aware).
 *  2. Run clustering maintenance: split detection, merge detection. Writes log rows for any new
 *     suggestions it encountered (deduped by 24h window inside ClusterMaintenance).
 *  3. If the user has no faces yet (fresh install), skip work cheaply.
 *
 * Progress: the worker writes a state row to the PersonMergeLog saying "pass ran" so (Phase 4 UI)
 * can show the last successful maintenance timestamp. Plain log lines under the Tag + status flag
 * in FaceVectorIndexStatus are the primary telemetry.
 */
class FaceMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as GallerySearchApp
        val db = com.devomind.gallerysearch.db.GalleryDatabase.getInstance(applicationContext)

        // Step 0 — only run if there are faces
        if (db.faceDao().countAll() == 0) {
            Log.i(Tag, "No faces yet; skipping maintenance pass.")
            return Result.success()
        }

        // Step 1 — MemoryBudget attach + initial check.
        val memoryBudget = MemoryBudget(app).apply { attach() }
        memoryBudget.enforce(isBackground = true)

        // Step 2 — vector index health: rebuild if corrupted. Also handles a previous pass that
        // was killed mid-rebuild because status was left in REBUILDING.
        val status = FaceVectorIndexStatus.get(applicationContext)
        if (status.state == FaceVectorIndexStatus.State.CORRUPTED ||
            status.state == FaceVectorIndexStatus.State.REBUILDING ||
            status.state == FaceVectorIndexStatus.State.REBUILD_FAILED ||
            !app.faceVectorIndex.verifyChecksum()
        ) {
            Log.w(Tag, "Vector index corrupted; rebuilding from Room…")
            try {
                app.faceVectorIndex.rebuildFromRoom(db, chunkSize = 256)
            } catch (oom: OutOfMemoryError) {
                Log.w(Tag, "OOM during rebuild; aborting this cycle.", oom)
                FaceVectorIndexStatus.setFailed(applicationContext)
                return Result.retry()
            } catch (t: Throwable) {
                Log.e(Tag, "Rebuild failed", t)
                FaceVectorIndexStatus.setFailed(applicationContext)
                return Result.retry()
            }
        }

        // Step 3 — cluster maintenance: split / merge detection via ClusterMaintenance. Lint-style:
        // suggestions are written to PersonMergeLog; no auto-apply.
        val result = try {
            ClusterMaintenance.analyze(applicationContext)
        } catch (t: Throwable) {
            Log.e(Tag, "Clustering maintenance failed", t)
            return Result.retry()
        }
        Log.i(
            Tag,
            "Maintenance: splits=${result.splits.size} merges=${result.merges.size} " +
                    "(new=${result.newLogEntries}, skipped=${result.skippedExisting})"
        )
        return Result.success()
    }

    companion object {
        const val WorkName = "face_maintenance_nightly"
        private const val Tag = "FaceMaintenanceWorker"
        private const val RepeatHours = 24L
        private const val FlexHours = 4L

        /** Register the nightly periodic work. Idempotent; safe to call from app start block. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FaceMaintenanceWorker>(
                RepeatHours, TimeUnit.HOURS,
                FlexHours, TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresCharging(true)
                        .setRequiredNetworkType(androidx.work.NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WorkName,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Cancel. Only used by tests. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WorkName)
        }
    }
}
