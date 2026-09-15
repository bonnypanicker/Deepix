package com.devomind.gallerysearch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs a compression batch end-to-end in the background as a foreground service, so closing the
 * app (or the system killing it) never interrupts mid-photo and never loses an image:
 *
 *  - PREPARE encodes each selected photo through [CompressionEngine.prepare] into a journaled,
 *    verified staging file. The original is never touched in this phase.
 *  - COMMIT applies the user's decision ([CompressionBatchStore.CommitMode]) per photo through the
 *    engine's crash-safe replace/copy state machine. Items whose staging was lost (e.g. settled by
 *    recovery after a long delay) are transparently re-encoded instead of failed.
 *
 * Batch state lives in [CompressionBatchStore]; after process death WorkManager restarts this
 * worker with the same inputData and it resumes from the persisted per-item statuses. Cancelling
 * is only destructive to temp artifacts: staged files are discarded, originals stay untouched.
 *
 * Progress is shown in a system notification (determinate bar) while running, plus a dismissible
 * completion summary.
 */
class CompressionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val store = CompressionBatchStore(appContext)
    private var lastForegroundAt = 0L

    override suspend fun doWork(): Result {
        val batchId = inputData.getString(KeyBatchId) ?: return Result.success()
        val batch = store.load()
        if (batch == null || batch.id != batchId || !batch.isActive) return Result.success()

        runCatching { setForeground(foregroundInfo(batch, 0, batch.items.size)) }
            .onFailure { Log.w(Tag, "Foreground start not allowed; compression runs in background.", it) }

        return try {
            when (batch.phase) {
                // Encode pass: fill staging, then park in AWAITING_DECISION for the user's choice.
                CompressionBatchStore.Phase.PREPARING -> {
                    runPrepare(batchId)
                    store.update(batchId) {
                        if (it.phase == CompressionBatchStore.Phase.PREPARING) {
                            it.phase = CompressionBatchStore.Phase.AWAITING_DECISION
                        }
                    }
                    // The user may have left the screen long ago: the encode pass ending is the
                    // moment they must be pulled back in to choose keep vs replace.
                    store.load()
                        ?.takeIf { it.id == batchId && it.phase == CompressionBatchStore.Phase.AWAITING_DECISION }
                        ?.let { showReadyForReview(it) }
                }
                // Commit pass (freshly confirmed, or resumed after process death): finish + notify.
                CompressionBatchStore.Phase.COMMITTING -> {
                    runCommit(batchId)
                    store.update(batchId) {
                        if (it.phase == CompressionBatchStore.Phase.COMMITTING) {
                            it.phase = CompressionBatchStore.Phase.DONE
                        }
                    }
                    store.load()
                        ?.takeIf { it.id == batchId && it.phase == CompressionBatchStore.Phase.DONE }
                        ?.let { showCompletion(it) }
                }
                else -> Unit
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            onBatchCancelled(batchId)
            throw cancelled
        } catch (error: Throwable) {
            Log.w(Tag, "Compression worker failed.", error)
            withContext(NonCancellable) {
                store.update(batchId) { b ->
                    b.items.forEach { if (it.status == CompressionBatchStore.ItemStatus.PREPARING) it.status = CompressionBatchStore.ItemStatus.FAILED }
                }
            }
            Result.failure()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // PREPARE — encode every pending photo to journaled staging (originals untouched)
    // ---------------------------------------------------------------------------------------------

    private suspend fun runPrepare(batchId: String) {
        // A PREPARING status at entry means the previous run died mid-item: redo it.
        store.update(batchId) { b ->
            b.items.forEach {
                if (it.status == CompressionBatchStore.ItemStatus.PREPARING) {
                    it.status = CompressionBatchStore.ItemStatus.PENDING
                }
            }
        }
        val batch = store.load() ?: return
        val total = batch.items.size

        for ((index, item) in batch.items.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (item.status != CompressionBatchStore.ItemStatus.PENDING) continue

            val stillCurrent = store.update(batchId) { b -> b.items[index].status = CompressionBatchStore.ItemStatus.PREPARING }
            if (!stillCurrent) return
            publishProgress(batchId, index, total)

            val uri = Uri.parse(item.uri)
            val (displayName, mimeType) = resolveMeta(uri, item.displayName, item.mimeType)
            val preset = store.load()?.takeIf { it.id == batchId } ?: return
            val entry = CompressionEngine.prepare(
                applicationContext, uri, displayName,
                preset.quality, CompressionEngine.Mode.REPLACE, preset.maxDimension
            )
            val saved = store.update(batchId) { b ->
                val target = b.items[index]
                target.displayName = displayName
                target.mimeType = mimeType
                when {
                    entry == null -> {
                        target.status = CompressionBatchStore.ItemStatus.FAILED
                        target.note = "Couldn't compress this photo"
                    }
                    entry.sizeBefore > 0L && entry.sizeAfter >= entry.sizeBefore -> {
                        CompressionEngine.discard(applicationContext, entry)
                        target.status = CompressionBatchStore.ItemStatus.NO_GAIN
                        target.note = "Already optimal — skipped"
                    }
                    else -> {
                        target.status = CompressionBatchStore.ItemStatus.READY
                        target.entryId = entry.id
                        target.sizeBefore = entry.sizeBefore
                        target.sizeAfter = entry.sizeAfter
                        target.format = entry.format.name
                        target.destPath = entry.destPath
                        target.originalPath = entry.originalPath
                        target.note = ""
                    }
                }
            }
            if (!saved) return
            // The entry was journaled by prepare(); if this save is skipped the orphan is settled
            // by CompressionEngine.recover on next start — never a lost photo.
            publishProgress(batchId, index + 1, total)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // COMMIT — apply the user's decision through the engine's crash-safe state machine
    // ---------------------------------------------------------------------------------------------

    private suspend fun runCommit(batchId: String) {
        // An item left in PREPARING means a prior run died mid re-encode; it still needs committing,
        // so fold it back into the READY set rather than skipping it forever.
        store.update(batchId) { b ->
            b.items.forEach {
                if (it.status == CompressionBatchStore.ItemStatus.PREPARING) {
                    it.status = CompressionBatchStore.ItemStatus.READY
                }
            }
        }
        val batch = store.load() ?: return
        val replace = batch.commitMode == CompressionBatchStore.CommitMode.REPLACE
        val targets = batch.items.mapIndexed { index, item -> index to item }
            .filter { it.second.status == CompressionBatchStore.ItemStatus.READY }
        val total = targets.size

        for ((position, pair) in targets.withIndex()) {
            currentCoroutineContext().ensureActive()
            val (index, item) = pair
            publishProgress(batchId, position, total)

            val entry = findEntry(item.entryId)

            // A prior run may have finished this photo but crashed before recording the outcome
            // (window between the destructive step and the store write). Detect and finalize it
            // instead of re-committing: re-running commitReplace would fail because the original is
            // already gone, mis-reporting a successful replacement. Never touches a good photo.
            val alreadyDone =
                if (entry != null) CompressionEngine.completeIfFinished(applicationContext, entry, replace)
                else CompressionEngine.resultAlreadyOnDisk(applicationContext, item.destPath, item.originalPath, replace)
            if (alreadyDone) {
                if (!recordOutcome(batchId, index, replace, committed = true)) return
                publishProgress(batchId, position + 1, total)
                continue
            }

            // Resolve a committable entry: reuse staging when present, else re-encode from the
            // original (safe — encoding never modifies the original).
            val staged = entry?.takeIf { File(it.stagingPath).exists() }
            val toCommit: CompressionEngine.Entry
            if (staged != null) {
                toCommit = staged
            } else {
                if (!store.update(batchId) { b -> b.items[index].status = CompressionBatchStore.ItemStatus.PREPARING }) return
                val preset = store.load() ?: return
                val reprepared = CompressionEngine.prepare(
                    applicationContext, Uri.parse(item.uri), item.displayName.ifBlank { null },
                    preset.quality, CompressionEngine.Mode.REPLACE, preset.maxDimension
                )
                if (reprepared == null) {
                    if (!recordOutcome(batchId, index, replace, committed = false, note = "Couldn't recompress this photo")) return
                    publishProgress(batchId, position + 1, total)
                    continue
                }
                if (reprepared.sizeBefore > 0L && reprepared.sizeAfter >= reprepared.sizeBefore) {
                    CompressionEngine.discard(applicationContext, reprepared)
                    if (!recordOutcome(batchId, index, replace, committed = false, noGain = true)) return
                    publishProgress(batchId, position + 1, total)
                    continue
                }
                toCommit = reprepared
            }

            val committed = runCatching {
                if (replace) CompressionEngine.commitReplace(applicationContext, toCommit)
                else CompressionEngine.commitCopy(applicationContext, toCommit)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                Log.w(Tag, "Commit failed for ${item.uri}", error)
                false
            }
            if (!recordOutcome(
                    batchId, index, replace, committed,
                    sizeBefore = toCommit.sizeBefore,
                    sizeAfter = toCommit.sizeAfter,
                    keepEntryId = toCommit.id,
                    note = "Couldn't ${if (replace) "replace" else "save"} this photo"
                )
            ) return
            publishProgress(batchId, position + 1, total)
        }
    }

    /**
     * Persists one item's commit outcome. Returns false when the batch was replaced underneath this
     * worker (stale run), signalling the caller to stop. On failure the staged entry id is kept so a
     * retry can commit it directly; the original is never modified by this bookkeeping.
     */
    private fun recordOutcome(
        batchId: String,
        index: Int,
        replace: Boolean,
        committed: Boolean,
        sizeBefore: Long = 0L,
        sizeAfter: Long = 0L,
        keepEntryId: String = "",
        note: String = "",
        noGain: Boolean = false
    ): Boolean = store.update(batchId) { b ->
        val target = b.items[index]
        when {
            noGain -> {
                target.status = CompressionBatchStore.ItemStatus.NO_GAIN
                target.note = "Already optimal — skipped"
                target.entryId = ""
            }
            committed -> {
                target.status = CompressionBatchStore.ItemStatus.DONE
                target.entryId = ""
                if (sizeBefore > 0L) target.sizeBefore = sizeBefore
                if (sizeAfter > 0L) target.sizeAfter = sizeAfter
                target.note = ""
                if (replace) {
                    if (target.uri !in b.replacedUris) b.replacedUris.add(target.uri)
                } else {
                    if (target.uri !in b.copiedUris) b.copiedUris.add(target.uri)
                }
                val saved = target.sizeBefore - target.sizeAfter
                if (saved > 0L) b.savedBytes += saved
            }
            else -> {
                target.status = CompressionBatchStore.ItemStatus.FAILED
                target.note = note
                target.entryId = keepEntryId
            }
        }
    }

    private fun findEntry(entryId: String): CompressionEngine.Entry? {
        if (entryId.isBlank()) return null
        return CompressionEngine.loadJournal(applicationContext).firstOrNull { it.id == entryId }
    }

    private fun resolveMeta(uri: Uri, fallbackName: String, fallbackMime: String): Pair<String, String> {
        var name = fallbackName
        runCatching {
            applicationContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameCol >= 0 && !cursor.isNull(nameCol)) name = cursor.getString(nameCol)
                }
            }
        }
        val mime = applicationContext.contentResolver.getType(uri)?.takeIf { it.isNotBlank() } ?: fallbackMime
        return name to mime
    }

    // ---------------------------------------------------------------------------------------------
    // Cancellation — discard staged temp files; originals are never touched here
    // ---------------------------------------------------------------------------------------------

    private suspend fun onBatchCancelled(batchId: String) = withContext(NonCancellable) {
        val batch = store.load()
        if (batch == null || batch.id != batchId) return@withContext
        if (batch.phase == CompressionBatchStore.Phase.COMMITTING ||
            batch.phase == CompressionBatchStore.Phase.DONE
        ) {
            // Mid-commit cancellation is settled by CompressionEngine.recover on next start
            // (originals kept, verified replacements finalized) — don't discard anything here.
            return@withContext
        }
        for (item in batch.items) {
            if (item.status == CompressionBatchStore.ItemStatus.READY && item.entryId.isNotBlank()) {
                findEntry(item.entryId)?.let { runCatching { CompressionEngine.discard(applicationContext, it) } }
            }
        }
        store.update(batchId) { it.phase = CompressionBatchStore.Phase.CANCELLED }
        store.clear()
    }

    // ---------------------------------------------------------------------------------------------
    // Notification progress
    // ---------------------------------------------------------------------------------------------

    private suspend fun publishProgress(batchId: String, done: Int, total: Int) {
        val batch = store.load() ?: return
        if (batch.id != batchId) return
        setProgressAsync(
            Data.Builder()
                .putInt(ProgressCurrentKey, done)
                .putInt(ProgressTotalKey, total)
                .build()
        )
        // Same rationale as CleanupWorker: re-binding the foreground service per tick ANRs the UI.
        val now = SystemClock.elapsedRealtime()
        if (now - lastForegroundAt >= FOREGROUND_THROTTLE_MS) {
            lastForegroundAt = now
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { setForegroundAsync(foregroundInfo(batch, done, total)) }
            }
        }
    }

    private fun foregroundInfo(batch: CompressionBatchStore.Batch, done: Int, total: Int): ForegroundInfo {
        ensureChannel()
        val replacing = batch.commitMode == CompressionBatchStore.CommitMode.REPLACE
        val (title, text) = when (batch.phase) {
            CompressionBatchStore.Phase.COMMITTING ->
                (if (replacing) "Replacing originals" else "Saving compressed copies") to
                    (if (total > 0) "$done / $total" else "Preparing…")
            else ->
                "Compressing photos" to
                    (if (total > 0) "$done / $total" else "Preparing…")
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, ChannelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(total.coerceAtLeast(1), done.coerceIn(0, total.coerceAtLeast(1)), total <= 0)
            .setContentIntent(contentIntent())
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NotificationId, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NotificationId, notification)
        }
    }

    private fun showCompletion(batch: CompressionBatchStore.Batch) {
        val doneCount = batch.replacedUris.size + batch.copiedUris.size
        val noun = if (doneCount == 1) "photo" else "photos"
        val verb = if (batch.commitMode == CompressionBatchStore.CommitMode.REPLACE) "replaced" else "compressed"
        val text = buildString {
            append("$doneCount $noun $verb")
            if (batch.savedBytes > 0L) append(" · ${formatBytes(batch.savedBytes)} saved")
        }
        postSummaryNotification("Compression finished", text)
    }

    /** Posted when the encode pass finishes: the user must come back to choose keep vs replace. */
    private fun showReadyForReview(batch: CompressionBatchStore.Batch) {
        val ready = batch.items.filter { it.status == CompressionBatchStore.ItemStatus.READY }
        if (ready.isEmpty()) return
        val saved = ready.sumOf { (it.sizeBefore - it.sizeAfter).coerceAtLeast(0L) }
        val noun = if (ready.size == 1) "photo" else "photos"
        val text = buildString {
            append("${ready.size} $noun ready")
            if (saved > 0L) append(" · ${formatBytes(saved)} smaller")
        }
        postSummaryNotification("Compression ready", "$text — tap to choose keep or replace")
    }

    /** Summaries post on their own id: WorkManager cancels the foreground notification id when
     *  the worker stops, which would swallow a summary posted with the same id. */
    private fun postSummaryNotification(title: String, text: String) {
        ensureChannel()
        val notification = NotificationCompat.Builder(applicationContext, ChannelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        runCatching {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(SummaryNotificationId, notification)
        }
    }

    private fun contentIntent(): PendingIntent? = runCatching {
        // The compression screen adopts an active batch with no hand-off, so a notification tap
        // lands the user straight back on the conversion review — from anywhere.
        PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, CompressionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }.getOrNull()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(ChannelId, "Photo compression", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            mb >= 1024.0 -> String.format(java.util.Locale.getDefault(), "%.1f GB", mb / 1024.0)
            mb >= 1.0 -> String.format(java.util.Locale.getDefault(), "%.0f MB", mb)
            else -> "${bytes / 1024L} KB"
        }
    }

    companion object {
        const val PrepareWorkName = "gallery_compression_prepare"
        const val CommitWorkName = "gallery_compression_commit"
        const val KeyBatchId = "compression_batch_id"
        const val ProgressCurrentKey = "compression_progress_current"
        const val ProgressTotalKey = "compression_progress_total"
        private const val Tag = "CompressionWorker"
        private const val ChannelId = "gallery_compression_channel"
        private const val NotificationId = 1004
        private const val SummaryNotificationId = 1005
        private const val FOREGROUND_THROTTLE_MS = 500L
    }
}
