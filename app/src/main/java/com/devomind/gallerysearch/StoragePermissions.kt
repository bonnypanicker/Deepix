package com.devomind.gallerysearch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * All-files access ([Environment.isExternalStorageManager]) is what lets the app delete a photo it
 * doesn't own without the per-item system delete dialog — the Recycle Bin and "delete directly"
 * setting both depend on it. This is a small facade over the runtime check + the settings intents,
 * plus the media-read checks [MainActivity] and the first-run tour both need.
 *
 * Android 8-9 hold the legacy `WRITE_EXTERNAL_STORAGE` grant (the manifest requests it up to API 29),
 * which is enough for direct file deletes, so [hasAllFilesAccess] short-circuits to true there.
 * Android 10 does not: it enforces scoped storage on a targetSdk-30+ app whatever it declares, and it
 * has no All-files switch, so [allFilesAccessRequestable] is what tells the caller that sending the
 * user to Settings there can't help.
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

    fun hasAllFilesAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // Legacy storage covers direct file deletes on Android 8-9 only. On Android 10 an app
            // targeting API 29+ is in scoped storage whether or not the write permission was granted,
            // so claiming access there sends every direct path (delete, bin, vault) into a failure it
            // reports as its own.
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Whether this device has an All-files switch the user can turn on. False on Android 10, where the
     * setting does not exist: a caller that needs direct file access must either fall back to the
     * per-item system consent dialog or say the feature needs Android 11, instead of opening a
     * Settings screen with nothing in it.
     */
    fun allFilesAccessRequestable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Intent that opens the system "Allow access to manage all files" screen for this app. */
    fun manageAllFilesIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }.let { specific ->
                // Some OEM builds reject the package-scoped action; fall back to the global list.
                if (specific.resolveActivity(context.packageManager) != null) {
                    specific
                } else {
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                }
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }
}
