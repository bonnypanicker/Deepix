package com.devomind.gallerysearch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * All-files access ([Environment.isExternalStorageManager]) is what lets the app delete a photo it
 * doesn't own without the per-item system delete dialog — the Recycle Bin and "delete directly"
 * setting both depend on it. This is a small facade over the runtime check + the settings intents.
 *
 * On pre-R devices the legacy `WRITE_EXTERNAL_STORAGE` grant already allows direct file deletes, so
 * [hasAllFilesAccess] short-circuits to true there (the manifest requests it up to API 29).
 */
object StoragePermissions {

    fun hasAllFilesAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // maxSdk 29 WRITE_EXTERNAL_STORAGE covers direct deletes on legacy devices.
            true
        }
    }

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
