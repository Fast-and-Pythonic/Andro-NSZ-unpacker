package com.androNSZ

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.withContext
import java.io.File

data class ConversionProgress(
   val doneBytes: Long,
   val totalBytes: Long,
   val speedMBps: Double,
) {
   val percent: Float get() = if (totalBytes > 0) doneBytes.toFloat() / totalBytes else 0f
}

class NszConversionException(val code: Int, msg: String) : Exception(msg)
class CancelledException : Exception("Cancelled")

object NszConverter {

    init {
        System.loadLibrary("AndroNSZ")
    }

    /* ── Native methods ─────────────────────────────────────────────── */

    @JvmStatic
    external fun nativeConvert(
        inputPath: String,
        outputPath: String,
        progressCallback: ProgressCallback?,
        statusCallback: StatusCallback?
    ): Int

    @JvmStatic
    external fun nativeSetDebugLog(path: String?)

    @JvmStatic
    external fun nativeCloseDebugLog()

    @JvmStatic
    external fun nativeCancel()

    @JvmStatic
    external fun nativeErrorString(errorCode: Int): String

    @JvmStatic
    external fun nativeVerifyNsp(nspPath: String, headerKey: ByteArray): String?

    /* ── Callback interfaces ────────────────────────────────────────── */

    interface ProgressCallback {
        fun onProgress(done: Long, total: Long)
    }

    interface StatusCallback {
        fun onStatus(tag: String, msg: String)
    }

    /* ── Error codes (mirror native ncz_types.h) ────────────────────── */

    const val OK = 0
    const val ERR_OPEN_INPUT = -1
    const val ERR_OPEN_OUTPUT = -2
    const val ERR_INVALID_PFS0 = -3
    const val ERR_INVALID_NCZ = -4
    const val ERR_ZSTD = -5
    const val ERR_IO = -6
    const val ERR_OOM = -7
    const val ERR_CANCELLED = -8
    const val ERR_HASH_MISMATCH = -9

    /* ── State ───────────────────────────────────────────────────── */

    var lastDebugLogPath: String? = null
        private set

    var lastVerifyError: String? = null
        private set

    var lastVerifySkipped: Boolean = false
        private set

    /* ── High-level API ─────────────────────────────────────────── */

    fun convert(
        context: Context,
        inputUri: Uri,
        headerKey: ByteArray?,
        statusCallback: StatusCallback? = null,
    ): Flow<ConversionProgress> = callbackFlow {

        val inputPath = withContext(Dispatchers.IO) {
            resolveToFilePath(context, inputUri)
        }
        val inputName  = inputPath.substringAfterLast('/')
        val outputName = inputName.substringBeforeLast('.') + ".nsp"

        val debugLogFile = File(context.getExternalFilesDir(null), "nsz_debug.log")
        lastDebugLogPath  = debugLogFile.absolutePath
        lastVerifyError   = null
        lastVerifySkipped = false
        withContext(Dispatchers.IO) { nativeSetDebugLog(debugLogFile.absolutePath) }

        /* ── Create a pending entry in public Downloads via MediaStore ── */
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, outputName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val outputUri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv
        ) ?: run {
            close(NszConversionException(-1, "Cannot create output file in Downloads"))
            awaitClose {}
            return@callbackFlow
        }

        val pfd = withContext(Dispatchers.IO) {
            context.contentResolver.openFileDescriptor(outputUri, "rw")
        } ?: run {
            context.contentResolver.delete(outputUri, null, null)
            close(NszConversionException(-1, "Cannot open output file descriptor"))
            awaitClose {}
            return@callbackFlow
        }

        val nativePath = "/proc/self/fd/${pfd.fd}"

        var lastDone   = 0L
        var lastTimeMs = System.currentTimeMillis()

        val cb = object : ProgressCallback {
            override fun onProgress(done: Long, total: Long) {
                val now        = System.currentTimeMillis()
                val elapsedSec = (now - lastTimeMs).coerceAtLeast(1L) / 1000.0
                val speed      = (done - lastDone).toDouble() / 1024 / 1024 / elapsedSec
                lastDone   = done
                lastTimeMs = now
                trySend(ConversionProgress(done, total, speed))
            }
        }

        val result = withContext(Dispatchers.IO) {
            nativeConvert(inputPath, nativePath, cb, statusCallback)
        }

        /* ── Verify NCA headers before closing the fd ── */
        if (result == 0 && headerKey != null) {
            lastVerifyError   = withContext(Dispatchers.IO) { nativeVerifyNsp(nativePath, headerKey) }
            lastVerifySkipped = false
        } else if (result == 0) {
            lastVerifyError   = null
            lastVerifySkipped = true
        }

        withContext(Dispatchers.IO) { nativeCloseDebugLog() }
        pfd.close()

        /* ── Finalise or discard the MediaStore entry ── */
        when (result) {
            0 -> {
                context.contentResolver.update(
                    outputUri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null, null
                )
            }
            ERR_CANCELLED -> {
                context.contentResolver.delete(outputUri, null, null)
                close(CancelledException())
            }
            else -> {
                context.contentResolver.delete(outputUri, null, null)
                close(NszConversionException(result, nativeErrorString(result)))
            }
        }

        close()
        awaitClose { /* nothing */ }
    }

    fun cancel() = nativeCancel()

    /* ── Internal helpers ───────────────────────────────────────── */

    private fun resolveToFilePath(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.path!!

        val tmpFile = File(context.cacheDir, queryFileName(context, uri))
        context.contentResolver.openInputStream(uri)!!.use { ins ->
            tmpFile.outputStream().use { out -> ins.copyTo(out) }
        }
        return tmpFile.absolutePath
    }

    private fun queryFileName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return "input.nsz"
    }
}