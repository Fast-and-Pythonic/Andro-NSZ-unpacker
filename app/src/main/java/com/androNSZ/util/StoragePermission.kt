package com.androNSZ.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

/**
 * "All files access" (MANAGE_EXTERNAL_STORAGE) helpers for the in-app file picker,
 * which browses the real filesystem via java.io.File. This is a special permission
 * granted from a system settings page, not a runtime dialog.
 */
object StoragePermission {

   /** True once the user has granted "All files access" to this app. */
   fun isGranted(): Boolean = Environment.isExternalStorageManager()

   /**
    * Intent to the per-app "All files access" settings page. Pre-filled with our
    * package so the user lands directly on this app's toggle.
    */
   fun settingsIntent(context: Context): Intent =
      Intent(
         Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
         Uri.parse("package:${context.packageName}")
      )
}
