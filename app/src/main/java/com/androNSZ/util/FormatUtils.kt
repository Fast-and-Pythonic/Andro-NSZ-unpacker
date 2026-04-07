package com.androNSZ.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

fun resolveDisplayName(context: Context, uri: Uri): String {
   context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
         val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
         if (idx >= 0) return cursor.getString(idx)
      }
   }
   return uri.lastPathSegment ?: "unknown.nsz"
}

fun getUriSize(context: Context, uri: Uri): Long {
   context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
         val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
         if (idx >= 0 && !cursor.isNull(idx)) {
            return cursor.getLong(idx)
         }
      }
   }
   return 0L
}

fun fmtBytes(bytes: Long): String {
   return when {
      bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
      bytes >= 1_048_576L     -> "%.0f MB".format(bytes / 1_048_576.0)
      else                    -> "%.0f KB".format(bytes / 1024.0)
   }
}
