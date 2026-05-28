package com.devomind.gallerysearch

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream

object AssetUtils {

    private const val Tag = "AssetUtils"

    /**
     * Reads an asset file fully into a byte array.
     * Uses a buffered approach to handle large files (e.g. 60MB+ ONNX models).
     */
    fun readAssetBytes(context: Context, assetName: String): ByteArray {
        return context.assets.open(assetName).use { input ->
            val buffer = ByteArrayOutputStream(input.available().coerceAtLeast(8192))
            val chunk = ByteArray(8192)
            var bytesRead: Int
            while (input.read(chunk).also { bytesRead = it } != -1) {
                buffer.write(chunk, 0, bytesRead)
            }
            buffer.toByteArray().also {
                Log.d(Tag, "Loaded $assetName: ${it.size} bytes")
            }
        }
    }

    fun readAssetText(context: Context, assetName: String): String =
        context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
