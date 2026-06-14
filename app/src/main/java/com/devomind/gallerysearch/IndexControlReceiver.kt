package com.devomind.gallerysearch

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class IndexControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ActionPause -> pauseIndexing(context)
            ActionResume -> resumeIndexing(context)
        }
    }

    private fun pauseIndexing(context: Context) {
        IndexPreferences.setIndexPaused(context, true)
        WorkManager.getInstance(context).cancelUniqueWork(IndexWorker.WorkName)
        IndexWorker.showPausedNotification(context)
    }

    private fun resumeIndexing(context: Context) {
        IndexPreferences.setIndexPaused(context, false)
        IndexWorker.cancelStatusNotification(context)

        val selectedAlbums = IndexPreferences.loadSelectedAlbums(context)
        val payload = Data.Builder()
            .putStringArray(IndexWorker.SelectedAlbumIdsKey, selectedAlbums.toTypedArray())
            .build()
        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            .setInputData(payload)
            .setBackoffCriteria(BackoffPolicy.LINEAR, DesignTokens.INDEX_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            IndexWorker.WorkName,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    companion object {
        const val ActionPause = "com.devomind.gallerysearch.action.PAUSE_INDEXING"
        const val ActionResume = "com.devomind.gallerysearch.action.RESUME_INDEXING"

        fun pendingIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, IndexControlReceiver::class.java).setAction(action)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
            return PendingIntent.getBroadcast(context, action.hashCode(), intent, flags)
        }

        private fun immutableFlag(): Int {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
        }
    }
}
