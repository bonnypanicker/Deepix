package com.devomind.gallerysearch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class IndexControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PAUSE_INDEXING -> {
                Log.d(TAG, "Indexing paused via notification")
                IndexPreferences.setIndexingPaused(context, true)
            }
            ACTION_RESUME_INDEXING -> {
                Log.d(TAG, "Indexing resumed via notification")
                IndexPreferences.setIndexingPaused(context, false)
            }
        }
    }

    companion object {
        const val ACTION_PAUSE_INDEXING = "com.devomind.gallerysearch.ACTION_PAUSE_INDEXING"
        const val ACTION_RESUME_INDEXING = "com.devomind.gallerysearch.ACTION_RESUME_INDEXING"
        private const val TAG = "IndexControlReceiver"
    }
}
