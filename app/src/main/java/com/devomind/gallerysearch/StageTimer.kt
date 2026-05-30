package com.devomind.gallerysearch

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/** Lightweight per-stage accumulator. Logs every [logEvery] images. */
class StageTimer(private val tag: String = "IndexPerf", private val logEvery: Int = 50) {
    private val decode = AtomicLong()
    private val preprocess = AtomicLong()
    private val inference = AtomicLong()
    private val persist = AtomicLong()
    private val count = AtomicLong()

    inline fun <T> measure(bucket: Bucket, block: () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        val r = block()
        add(bucket, SystemClock.elapsedRealtimeNanos() - start)
        return r
    }

    fun add(bucket: Bucket, nanos: Long) {
        when (bucket) {
            Bucket.DECODE -> decode.addAndGet(nanos)
            Bucket.PREPROCESS -> preprocess.addAndGet(nanos)
            Bucket.INFERENCE -> inference.addAndGet(nanos)
            Bucket.PERSIST -> persist.addAndGet(nanos)
        }
    }

    fun onImagesDone(n: Int) {
        val total = count.addAndGet(n.toLong())
        if (total % logEvery < n) {
            Log.d(tag, "after=$total imgs | decode=${ms(decode)}ms preprocess=${ms(preprocess)}ms " +
                    "inference=${ms(inference)}ms persist=${ms(persist)}ms")
        }
    }

    private fun ms(a: AtomicLong) = a.get() / 1_000_000
    enum class Bucket { DECODE, PREPROCESS, INFERENCE, PERSIST }
}
