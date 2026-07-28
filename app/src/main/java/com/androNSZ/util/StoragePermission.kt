package com.androNSZ.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.net.toUri

/**
 * "All files access" (MANAGE_EXTERNAL_STORAGE) helpers for the in-app file picker,
 * which browses the real filesystem via java.io.File. This is a special permission
 * granted from a system settings page, not a runtime dialog.
 *
 * See G21: a grant that lands while the app is already running reaches [isGranted]
 * but not the process's storage mount, so [canBrowseStorage] is the check that
 * decides whether browsing will actually work.
 */
object StoragePermission {

   /** True once the user has granted "All files access" to this app. */
   fun isGranted(): Boolean = Environment.isExternalStorageManager()

   /**
    * True when the permission is not only granted but usable *in this process*.
    *
    * The storage mount mode is fixed when the process is forked. Stock Android kills
    * the app when the permission flips, but some skins (HyperOS) don't, leaving a
    * granted app that still can't read the storage root until it restarts.
    *
    * Does a directory listing — call it off the main thread.
    */
   fun canBrowseStorage(): Boolean =
      runCatching { Environment.getExternalStorageDirectory().list() != null }
         .getOrDefault(false)

   /**
    * Settings pages that can grant "All files access", best first. The per-app page
    * lands directly on this app's toggle; the global list is the fallback for skins
    * where the per-app page is missing, and app details is the last resort.
    */
   fun settingsIntents(context: Context): List<Intent> = listOf(
      Intent(
         Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
         "package:${context.packageName}".toUri()
      ),
      Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
      Intent(
         Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
         "package:${context.packageName}".toUri()
      )
   )

   /**
    * Opens the first settings page from [settingsIntents] that the device resolves.
    *
    * @return true if a page was launched.
    */
   fun launchSettings(context: Context, launcher: ActivityResultLauncher<Intent>): Boolean {
      for (intent in settingsIntents(context)) {
         if (intent.resolveActivity(context.packageManager) == null) continue
         try {
            launcher.launch(intent)
            return true
         } catch (_: ActivityNotFoundException) {
            // Resolved but not launchable (disabled/guarded) — try the next page.
         }
      }
      return false
   }
}
