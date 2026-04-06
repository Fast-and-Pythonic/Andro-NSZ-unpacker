package com.androNSZ

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object KeysManager {
   private const val KEYS_FILE_NAME = "prod.keys"

   fun keysFile(context: Context): File =
      File(context.filesDir, KEYS_FILE_NAME)

   fun isInstalled(context: Context): Boolean {
      val f = keysFile(context)
      return f.exists() && f.length() > 0
   }

   suspend fun installFromUri(context: Context, uri: Uri) {
      withContext(Dispatchers.IO) {
         context.contentResolver.openInputStream(uri)!!.use { ins ->
            keysFile(context).outputStream().use { out -> ins.copyTo(out) }
         }
      }
   }

   fun deleteKeys(context: Context) {
      val f = keysFile(context)
      if (f.exists()) {
         f.delete()
      }
   }
}