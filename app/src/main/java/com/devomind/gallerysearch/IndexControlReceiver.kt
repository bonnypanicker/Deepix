package com.devomind.gallerysearch

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class IndexControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ActionPause -> IndexController.pause(context)
            ActionResume -> IndexController.resume(context)
            ActionStop -> IndexController.stop(context)
        }
    }

    companion object {
        const val ActionPause = "com.devomind.gallerysearch.action.PAUSE_INDEXING"
        const val ActionResume = "com.devomind.gallerysearch.action.RESUME_INDEXING"
        const val ActionStop = "com.devomind.gallerysearch.action.STOP_INDEXING"

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
