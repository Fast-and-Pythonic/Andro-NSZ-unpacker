package com.androNSZ

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.androNSZ.fs.TempFileManager
import com.androNSZ.model.CancelledException
import com.androNSZ.model.ConversionProgress
import com.androNSZ.model.NszConversionException
import com.androNSZ.util.ResolvedInputFile
import com.androNSZ.util.queryFileName
import com.androNSZ.util.resolveToFilePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

object NszConverter {

    init {
        System.loadLibrary("AndroNSZ")
    }

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

    interface ProgressCallback {
        fun onProgress(done: Long, total: Long)
    }

    interface StatusCallback {
        fun onStatus(tag: String, msg: String)
    }

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

    var lastDebugLogPath: String? = null
        private set

    var lastVerifyError: String? = null
        private set

    var lastVerifySkipped: Boolean = false
        private set

    fun convert(
        context: Context,
        inputUri: Uri,
        headerKey: ByteArray?,
        statusCallback: StatusCallback? = null,
    ): Flow<ConversionProgress> = callbackFlow {

        val originalFileName = queryFileName(context, inputUri)
        val resolvedInput = withContext(Dispatchers.IO) {
            resolveToFilePath(context, inputUri)
        }
        val outputName = originalFileName.substringBeforeLast('.') + ".nsp"

        val debugLogFile = File(context.getExternalFilesDir(null), "nsz_debug.log")
        var outputUri: Uri? = null
        var pfd: ParcelFileDescriptor? = null

        lastDebugLogPath = debugLogFile.absolutePath
        lastVerifyError = null
        lastVerifySkipped = false

        try {
            withContext(Dispatchers.IO) { nativeSetDebugLog(debugLogFile.absolutePath) }

            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, outputName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val createdOutputUri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv
            ) ?: throw NszConversionException(-1, "Cannot create output file in Downloads")
            outputUri = createdOutputUri

            val openedPfd = withContext(Dispatchers.IO) {
                context.contentResolver.openFileDescriptor(createdOutputUri, "rw")
            } ?: throw NszConversionException(-1, "Cannot open output file descriptor")
            pfd = openedPfd

            val nativePath = "/proc/self/fd/${openedPfd.fd}"

            var lastDone = 0L
            var lastTimeMs = System.currentTimeMillis()
            var lastEmitTimeMs = System.currentTimeMillis()
            var lastNumericEmitTimeMs = System.currentTimeMillis()
            var lastSpeed = 0.0

            val cb = object : ProgressCallback {
                override fun onProgress(done: Long, total: Long) {
                    val now = System.currentTimeMillis()
                    
                    val shouldUpdateNumeric = (now - lastNumericEmitTimeMs >= Constants.PROGRESS_NUMERIC_UPDATE_INTERVAL_MS)
                    
                    // Update progress bar every 250ms (4 times per second)
                    if (now - lastEmitTimeMs >= Constants.PROGRESS_BAR_UPDATE_INTERVAL_MS) {
                        if (shouldUpdateNumeric) {
                            val elapsedSec = (now - lastTimeMs).coerceAtLeast(1L) / 1000.0
                            lastSpeed = (done - lastDone).toDouble() / 1024 / 1024 / elapsedSec
                            lastDone = done
                            lastTimeMs = now
                            lastNumericEmitTimeMs = now
                        }
                        
                        lastEmitTimeMs = now
                        trySend(ConversionProgress(done, total, lastSpeed))
                    }
                }
            }

            val result = withContext(Dispatchers.IO) {
                nativeConvert(resolvedInput.file.absolutePath, nativePath, cb, statusCallback)
            }

            if (result == 0 && headerKey != null) {
                lastVerifyError = withContext(Dispatchers.IO) { nativeVerifyNsp(nativePath, headerKey) }
                lastVerifySkipped = false
            } else if (result == 0) {
                lastVerifyError = null
                lastVerifySkipped = true
            }

            when (result) {
                OK -> {
                    context.contentResolver.update(
                        createdOutputUri,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null,
                        null
                    )
                    close()
                }
                ERR_CANCELLED -> {
                    context.contentResolver.delete(createdOutputUri, null, null)
                    close(CancelledException())
                }
                else -> {
                    context.contentResolver.delete(createdOutputUri, null, null)
                    close(NszConversionException(result, nativeErrorString(result)))
                }
            }
        } catch (e: Exception) {
            outputUri?.let { context.contentResolver.delete(it, null, null) }
            close(e)
        } finally {
            withContext(Dispatchers.IO) {
                runCatching { nativeCloseDebugLog() }
                runCatching { pfd?.close() }
                resolvedInput.deleteIfTemp()
            }
        }

        awaitClose { }
    }

    fun cancel() = nativeCancel()
}
