package com.androNSZ.util

import android.content.Context
import android.content.Intent

/**
 * Restarts the app's own process.
 *
 * Needed after "All files access" is granted to an already-running app: the storage
 * mount mode is inherited at fork time, so only a fresh process sees the storage
 * root (G21).
 */
object AppRestart {

   /** Relaunches the app from scratch. Does not return if the restart goes through. */
   fun restart(context: Context) {
      val intent = context.packageManager
         .getLaunchIntentForPackage(context.packageName)
         ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
         ?: return
      context.startActivity(intent)
      Runtime.getRuntime().exit(0)
   }
}
