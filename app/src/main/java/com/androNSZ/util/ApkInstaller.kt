package com.androNSZ.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

/**
 * Hands a downloaded APK to the system package installer.
 *
 * Sideloaded updates require the "install unknown apps" permission (minSdk 31, so
 * always the API 26+ per-app model). When it isn't granted yet we send the user to
 * the system settings screen to grant it, then they re-tap "Update".
 */
object ApkInstaller {

   /**
    * @return true if the install intent was launched; false if the user was sent
    *   to grant the "install unknown apps" permission first (the caller can then
    *   surface a hint to retry).
    */
   fun install(context: Context, apk: File): Boolean {
      if (!context.packageManager.canRequestPackageInstalls()) {
         val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri()
         ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
         context.startActivity(intent)
         return false
      }

      val apkUri: Uri = FileProvider.getUriForFile(
         context,
         "${context.packageName}.fileprovider",
         apk
      )
      val intent = Intent(Intent.ACTION_VIEW).apply {
         setDataAndType(apkUri, "application/vnd.android.package-archive")
         addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      context.startActivity(intent)
      return true
   }
}
