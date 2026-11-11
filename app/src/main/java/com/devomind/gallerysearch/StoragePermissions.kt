package com.devomind.gallerysearch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime checks for the media-read permissions the gallery needs. All-files access
 * (MANAGE_EXTERNAL_STORAGE) was removed to satisfy Play's All Files Access policy — deletes now go
 * through MediaStore consent, and features that needed raw file access are gated behind
 * [FeatureFlags].
 *
 * Android 8-9 hold the legacy `WRITE_EXTERNAL_STORAGE` grant (the manifest requests it up to API 29),
 * which is enough for MediaStore writes of media rows the app doesn't own.
 */
object StoragePermissions {

    /** The runtime permissions that read the library on this API level. */
    fun requiredMediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun hasMediaAccess(context: Context): Boolean =
        requiredMediaPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        } || hasPartialMediaAccess(context)

    /** API 34+ "Select photos": full media access denied but a user-selected subset granted. */
    fun hasPartialMediaAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_IMAGES
            ) != PackageManager.PERMISSION_GRANTED
    }
}

