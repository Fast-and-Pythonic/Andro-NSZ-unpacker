package com.androNSZ.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

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
   // A file:// uri points straight at the file; ContentResolver.query() returns
   // nothing for it and would yield 0, zeroing the overall-progress denominator.
   if (uri.scheme == "file") return File(uri.path ?: return 0L).length()
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

/** Format a duration as M:SS, or H:MM:SS once it passes an hour. */
fun fmtDuration(ms: Long): String {
   val totalSec = (ms.coerceAtLeast(0L) + 500L) / 1000L   // round to nearest second
   val h = totalSec / 3600
   val m = (totalSec % 3600) / 60
   val s = totalSec % 60
   return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
