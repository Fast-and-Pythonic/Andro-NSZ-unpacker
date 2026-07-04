package com.androNSZ

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.androNSZ.model.CancelledException
import com.androNSZ.model.ConversionProgress
import com.androNSZ.model.NszConversionException
import com.androNSZ.util.ProgressThrottler
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
    external fun nativeConvertXcz(
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

    /**
     * Configure CNMT verification once before a batch/folder job. Read-only in
     * native code during conversion. [headerKey] is 32 bytes (or null);
     * [keyAreaKeys] is a flat array of 17-byte records (generation + 16-byte key)
     * from [com.androNSZ.nut.KeysParser.parseKeyAreaKeys] (or null).
     */
    @JvmStatic
    external fun nativeSetVerification(
        enabled: Boolean,
        headerKey: ByteArray?,
        keyAreaKeys: ByteArray?
    )

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
        outputBaseUri: Uri? = null,
        statusCallback: StatusCallback? = null,
    ): Flow<ConversionProgress> = callbackFlow {

        val originalFileName = queryFileName(context, inputUri)
        val outputName = originalFileName.substringBeforeLast('.') + ".nsp"

        val debugLogFile = File(context.getExternalFilesDir(null), "nsz_debug.log")
        var outputUri: Uri? = null
        var pfd: ParcelFileDescriptor? = null
        var inputPfd: ParcelFileDescriptor? = null
        var resolvedInput: ResolvedInputFile? = null

        lastDebugLogPath = debugLogFile.absolutePath
        lastVerifyError = null
        lastVerifySkipped = false

        try {
            withContext(Dispatchers.IO) { nativeSetDebugLog(debugLogFile.absolutePath) }

            // Resolve the input to a native path. Prefer reading the source
            // directly through its file descriptor (no copy); fall back to a
            // temp-file copy only when the provider returns a non-seekable fd.
            val inputPath = withContext(Dispatchers.IO) {
                if (inputUri.scheme == "file") {
                    inputUri.path!!
                } else {
                    val p = context.contentResolver.openFileDescriptor(inputUri, "r")
                    if (p != null && p.statSize >= 0L) {
                        inputPfd = p
                        // "fd:N" tells native to dup()+fdopen() the descriptor
                        // directly, instead of re-opening via /proc/self/fd which
                        // fails on FUSE-backed scoped storage.
                        "fd:${p.fd}"
                    } else {
                        p?.close()
                        val r = resolveToFilePath(context, inputUri, statusCallback)
                        resolvedInput = r
                        r.file.absolutePath
                    }
                }
            }

            val createdOutputUri = createOutputUri(context, outputBaseUri, outputName)
            outputUri = createdOutputUri

            // Recycle lint can't see it, but pfd is closed in the finally block below.
            @Suppress("Recycle")
            val openedPfd = withContext(Dispatchers.IO) {
                context.contentResolver.openFileDescriptor(createdOutputUri, "rw")
            } ?: throw NszConversionException(-1, "Cannot open output file descriptor")
            pfd = openedPfd

            val nativePath = "/proc/self/fd/${openedPfd.fd}"

            val throttler = ProgressThrottler()

            val cb = object : ProgressCallback {
                override fun onProgress(done: Long, total: Long) {
                    throttler.sample(done, total)?.let { trySend(it) }
                }
            }

            var result = withContext(Dispatchers.IO) {
                nativeConvert(inputPath, nativePath, cb, statusCallback)
            }

            // Some content providers (e.g. FUSE-backed scoped storage) hand out
            // a descriptor whose /proc/self/fd path can't be re-opened by native
            // code, surfacing as an input/parse error. Fall back to the temp-copy
            // path and retry. The fast attempt fails immediately at PFS0 parsing,
            // so the retry costs essentially nothing.
            if (result != OK && inputPfd != null &&
                (result == ERR_OPEN_INPUT || result == ERR_INVALID_PFS0 ||
                 result == ERR_INVALID_NCZ || result == ERR_IO)) {
                statusCallback?.onStatus("NSZ", "Direct read failed (code $result), copying to cache and retrying")
                withContext(Dispatchers.IO) { runCatching { inputPfd?.close() } }
                inputPfd = null
                val r = withContext(Dispatchers.IO) { resolveToFilePath(context, inputUri, statusCallback) }
                resolvedInput = r
                result = withContext(Dispatchers.IO) {
                    nativeConvert(r.file.absolutePath, nativePath, cb, statusCallback)
                }
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
                    finalizeOutputUri(context, createdOutputUri, outputBaseUri)
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
                runCatching { inputPfd?.close() }
                resolvedInput?.deleteIfTemp()
            }
        }

        awaitClose { }
    }

    fun convertXcz(
        context: Context,
        inputUri: Uri,
        headerKey: ByteArray?,
        outputBaseUri: Uri? = null,
        statusCallback: StatusCallback? = null,
    ): Flow<ConversionProgress> = callbackFlow {

        val originalFileName = queryFileName(context, inputUri)
        val outputName = originalFileName.substringBeforeLast('.') + ".xci"

        val debugLogFile = File(context.getExternalFilesDir(null), "nsz_debug.log")
        var outputUri: Uri? = null
        var pfd: ParcelFileDescriptor? = null
        var inputPfd: ParcelFileDescriptor? = null
        var resolvedInput: ResolvedInputFile? = null

        lastDebugLogPath = debugLogFile.absolutePath
        lastVerifyError = null
        lastVerifySkipped = true  // XCI verification not implemented yet

        try {
            withContext(Dispatchers.IO) { nativeSetDebugLog(debugLogFile.absolutePath) }

            // Resolve the input to a native path. Prefer reading the source
            // directly through its file descriptor (no copy); fall back to a
            // temp-file copy only when the provider returns a non-seekable fd.
            val inputPath = withContext(Dispatchers.IO) {
                if (inputUri.scheme == "file") {
                    inputUri.path!!
                } else {
                    val p = context.contentResolver.openFileDescriptor(inputUri, "r")
                    if (p != null && p.statSize >= 0L) {
                        inputPfd = p
                        "fd:${p.fd}"
                    } else {
                        p?.close()
                        val r = resolveToFilePath(context, inputUri, statusCallback)
                        resolvedInput = r
                        r.file.absolutePath
                    }
                }
            }

            val createdOutputUri = createOutputUri(context, outputBaseUri, outputName)
            outputUri = createdOutputUri

            // Recycle lint can't see it, but pfd is closed in the finally block below.
            @Suppress("Recycle")
            val openedPfd = withContext(Dispatchers.IO) {
                context.contentResolver.openFileDescriptor(createdOutputUri, "rw")
            } ?: throw NszConversionException(-1, "Cannot open output file descriptor")
            pfd = openedPfd

            val nativePath = "/proc/self/fd/${openedPfd.fd}"

            val throttler = ProgressThrottler()

            val cb = object : ProgressCallback {
                override fun onProgress(done: Long, total: Long) {
                    throttler.sample(done, total)?.let { trySend(it) }
                }
            }

            var result = withContext(Dispatchers.IO) {
                nativeConvertXcz(inputPath, nativePath, cb, statusCallback)
            }

            // FUSE fallback: a descriptor whose /proc/self/fd path can't be
            // re-opened by native code surfaces as an input/parse error. Retry
            // through a temp copy (same rationale as convert()).
            if (result != OK && inputPfd != null &&
                (result == ERR_OPEN_INPUT || result == ERR_INVALID_PFS0 ||
                 result == ERR_INVALID_NCZ || result == ERR_IO)) {
                statusCallback?.onStatus("XCZ", "Direct read failed (code $result), copying to cache and retrying")
                withContext(Dispatchers.IO) { runCatching { inputPfd?.close() } }
                inputPfd = null
                val r = withContext(Dispatchers.IO) { resolveToFilePath(context, inputUri, statusCallback) }
                resolvedInput = r
                result = withContext(Dispatchers.IO) {
                    nativeConvertXcz(r.file.absolutePath, nativePath, cb, statusCallback)
                }
            }

            when (result) {
                OK -> {
                    finalizeOutputUri(context, createdOutputUri, outputBaseUri)
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
                runCatching { inputPfd?.close() }
                resolvedInput?.deleteIfTemp()
            }
        }

        awaitClose { }
    }

    fun cancel() = nativeCancel()

    private fun createOutputUri(context: Context, outputBaseUri: Uri?, outputName: String): Uri {
        if (outputBaseUri != null) {
            if (outputBaseUri.scheme == "file") {
                val file = File(File(outputBaseUri.path!!), outputName)
                return Uri.fromFile(file)
            }
            if (outputBaseUri.scheme == "content") {
                val parentUri = if (DocumentsContract.isTreeUri(outputBaseUri)) {
                    DocumentsContract.buildDocumentUriUsingTree(
                        outputBaseUri,
                        DocumentsContract.getTreeDocumentId(outputBaseUri)
                    )
                } else {
                    outputBaseUri
                }
                return DocumentsContract.createDocument(
                    context.contentResolver,
                    parentUri,
                    "application/octet-stream",
                    outputName
                ) ?: throw NszConversionException(-1, "Cannot create output file in selected folder")
            }
        }

        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, outputName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv
        ) ?: throw NszConversionException(-1, "Cannot create output file in Downloads")
    }

    private fun finalizeOutputUri(context: Context, outputUri: Uri, outputBaseUri: Uri?) {
        if (outputBaseUri == null) {
            context.contentResolver.update(
                outputUri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null
            )
        }
    }
}
