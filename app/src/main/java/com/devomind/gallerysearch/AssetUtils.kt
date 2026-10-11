package com.devomind.gallerysearch

import android.content.Context
import android.util.Log

object AssetUtils {

    private const val Tag = "AssetUtils"
    private const val MinModelBufferBytes = 8192

    /**
     * Reads an asset file fully into a byte array.
     *
     * The buffer is sized from the stream's declared length and filled in place: growing a
     * ByteArrayOutputStream to 60MB+ and then copying it with `toByteArray()` keeps two arrays that
     * size alive at once, and a model is loaded exactly when a search starts — on a heap that already
     * holds the grid. One oversized array instead of two is the difference between a GC pause and the
     * main thread being starved for tens of seconds.
     */
    fun readAssetBytes(context: Context, assetName: String): ByteArray {
        return context.assets.open(assetName).use { input ->
            var data = ByteArray(input.available().coerceAtLeast(MinModelBufferBytes))
            var size = 0
            while (true) {
                if (size == data.size) data = data.copyOf(data.size * 2)
                val bytesRead = input.read(data, size, data.size - size)
                if (bytesRead == -1) break
                size += bytesRead
            }
            val result = if (size == data.size) data else data.copyOf(size)
            Log.d(Tag, "Loaded $assetName: ${result.size} bytes")
            result
        }
    }

    fun readAssetText(context: Context, assetName: String): String =
        context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
