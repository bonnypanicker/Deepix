package com.devomind.gallerysearch

import android.content.Context
import java.io.File

object AssetUtils {
    fun copyAssetToCache(context: Context, assetName: String): String {
        val outFile = File(context.cacheDir, assetName)
        if (outFile.exists() && outFile.length() > 0L) return outFile.absolutePath

        context.assets.open(assetName).use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return outFile.absolutePath
    }

    fun readAssetText(context: Context, assetName: String): String =
        context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
