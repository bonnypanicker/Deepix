package com.devomind.gallerysearch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** All entry points must run on the UI dispatcher. */
internal class MediaRefreshScheduler(
    private val scope: CoroutineScope,
    private val refresh: suspend () -> Unit
) {
    private var ready = false
    private var resumed = false
    private var pending = false
    private var job: Job? = null

    fun onReady() {
        ready = true
        schedule()
    }

    fun onResume() {
        resumed = true
        if (ready) pending = true
        schedule()
    }

    fun onPause() {
        resumed = false
    }

    fun onMediaChanged() {
        pending = true
        schedule()
    }

    private fun schedule() {
        if (!ready || !resumed || job?.isActive == true) return
        job = scope.launch {
            try {
                while (pending && resumed) {
                    // Bound refresh frequency without starving a continuously running batch.
                    delay(1500L)
                    if (!resumed) break
                    pending = false
                    refresh()
                }
            } finally {
                job = null
            }
        }
    }
}
