package com.androNSZ.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.androNSZ.fs.TempFileManager
import com.androNSZ.NszConverter
import java.io.File

data class ResolvedInputFile(
    val file: File,
    val isTemp: Boolean
) {
    fun deleteIfTemp(statusCallback: NszConverter.StatusCallback? = null) {
        if (!isTemp) return
        val deleted = TempFileManager.deleteQuietly(file)
        if (statusCallback != null) {
            if (deleted) {
                statusCallback.onStatus("NSZ", "Temp source file deleted")
            } else if (file.exists()) {
                statusCallback.onStatus("ERROR", "Failed to delete temp source file: ${file.absolutePath}")
            }
        }
    }
}

fun resolveToFilePath(
    context: Context,
    uri: Uri,
    statusCallback: NszConverter.StatusCallback? = null
): ResolvedInputFile {
    if (uri.scheme == "file") return ResolvedInputFile(File(uri.path!!), false)

    val fileName = queryFileName(context, uri)
    val tmpFile = TempFileManager.createManagedTempFile(context, fileName, "nsz")

    val originalSize = getUriSize(context, uri)
    statusCallback?.onStatus("NSZ", "Copying to cache: $fileName (%.2f MB)".format(originalSize / 1024.0 / 1024.0))

    context.contentResolver.openInputStream(uri)!!.use { ins ->
        tmpFile.outputStream().use { out -> ins.copyTo(out) }
    }

    val copiedSize = tmpFile.length()
    if (originalSize > 0 && copiedSize != originalSize) {
        statusCallback?.onStatus("ERROR", "Cache file size ($copiedSize) doesn't match original ($originalSize)")
        throw Exception("File size mismatch after copying to cache: expected $originalSize, got $copiedSize")
    }

    statusCallback?.onStatus("NSZ", "File successfully copied to cache: ${tmpFile.absolutePath}")
    return ResolvedInputFile(tmpFile, true)
}

fun queryFileName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) return cursor.getString(idx)
        }
    }
    return "input.nsz"
}
