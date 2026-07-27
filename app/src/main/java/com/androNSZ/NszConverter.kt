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
import com.androNSZ.model.VerifyStatus
import com.androNSZ.util.LogFiles
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

    /**
     * Opens (or force-closes, when [path] is null) the native debug log. [banner]
     * is optional header text written before the engine's own header lines — the
     * run number and logging rules, see [openJobDebugLog]. Prefer that wrapper.
     */
    @JvmStatic
    external fun nativeSetDebugLog(path: String?, banner: String?)

    @JvmStatic
    external fun nativeCloseDebugLog()

    @JvmStatic
    external fun nativeCancel()

    @JvmStatic
    external fun nativeErrorString(errorCode: Int): String

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

    /** Path of the native debug log opened by [openJobDebugLog]. */
    var lastDebugLogPath: String? = null
        private set

    /**
     * Opens the native engine's debug log for one whole job (queue, folder or
     * combined) and returns its path. [runId] and [mode] go into the header so the
     * file identifies the run it belongs to (see [LogFiles]).
     *
     * Deliberately per job, not per file: the log has a single fixed path, so a
     * per-file open truncated it for every file of a parallel batch and the first
     * file to finish closed it for all the others, leaving them with logcat only.
     * The native side reference counts (see nsz_debug.h), so an extra open is
     * harmless — but every call must still be paired with [closeJobDebugLog].
     *
     * Rotation happens here rather than natively: the engine truncates on open, so
     * the previous run has to be moved aside first.
     */
    @JvmStatic
    fun openJobDebugLog(context: Context, runId: Int, mode: String): String {
        val logFile = File(context.getExternalFilesDir(null), LogFiles.NATIVE_LOG)
        LogFiles.rotate(logFile)
        lastDebugLogPath = logFile.absolutePath
        nativeSetDebugLog(logFile.absolutePath, LogFiles.banner(runId, "Native engine log", mode))
        return logFile.absolutePath
    }

    /** Drops this job's reference to the native debug log (see [openJobDebugLog]). */
    @JvmStatic
    fun closeJobDebugLog() = nativeCloseDebugLog()

    /**
     * Derives the per-file verify verdict from the inline hashing tags the engine
     * emits during decompression (`VERIFIED` / `CORRUPTED`), forwarding every
     * status through unchanged. This replaces the old post-conversion
     * `nativeVerifyNsp` pass, which re-read the entire output file — expensive on
     * write-bound storage. Each conversion uses its own tracker, so parallel
     * files never race. Tags arrive on the native conversion thread before the
     * blocking `nativeConvert` returns; `@Volatile` guards the cross-thread read.
     */
    private class VerifyTracker(private val delegate: StatusCallback?) : StatusCallback {
        @Volatile var sawVerified = false
        @Volatile var sawCorrupted = false
        override fun onStatus(tag: String, msg: String) {
            when (tag) {
                "VERIFIED" -> sawVerified = true
                "CORRUPTED" -> sawCorrupted = true
            }
            delegate?.onStatus(tag, msg)
        }
        val verdict: VerifyStatus
            get() = when {
                sawCorrupted -> VerifyStatus.FAILED
                sawVerified -> VerifyStatus.CHECKED
                else -> VerifyStatus.NOT_CHECKED
            }
    }

    fun convert(
        context: Context,
        inputUri: Uri,
        outputBaseUri: Uri? = null,
        statusCallback: StatusCallback? = null,
        // Invoked once with the verification outcome, derived from the engine's
        // inline hashing tags (see [VerifyTracker]). Captured per-call so the
        // queue can label the card without racing under parallel conversions.
        onVerified: (VerifyStatus) -> Unit = {},
    ): Flow<ConversionProgress> = callbackFlow {

        val originalFileName = queryFileName(context, inputUri)
        val outputName = originalFileName.substringBeforeLast('.') + ".nsp"

        var outputUri: Uri? = null
        var pfd: ParcelFileDescriptor? = null
        var inputPfd: ParcelFileDescriptor? = null
        var resolvedInput: ResolvedInputFile? = null

        val tracker = VerifyTracker(statusCallback)

        // NB: the native debug log is opened once per job by the caller
        // (see [openJobDebugLog]), not here — see that function for why.
        try {
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
                nativeConvert(inputPath, nativePath, cb, tracker)
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
                    nativeConvert(r.file.absolutePath, nativePath, cb, tracker)
                }
            }

            // Verify verdict comes from the engine's inline hashing tags (no
            // separate output re-read). NOT_CHECKED when verification is off.
            if (result == 0) onVerified(tracker.verdict)

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
        outputBaseUri: Uri? = null,
        statusCallback: StatusCallback? = null,
        onVerified: (VerifyStatus) -> Unit = {},
    ): Flow<ConversionProgress> = callbackFlow {

        val originalFileName = queryFileName(context, inputUri)
        val outputName = originalFileName.substringBeforeLast('.') + ".xci"

        var outputUri: Uri? = null
        var pfd: ParcelFileDescriptor? = null
        var inputPfd: ParcelFileDescriptor? = null
        var resolvedInput: ResolvedInputFile? = null

        // XCZ hashes NCAs inline per HFS0 partition (secure partition carries the
        // META), so the verdict comes from the same VERIFIED/CORRUPTED tags.
        val tracker = VerifyTracker(statusCallback)

        // NB: the native debug log is opened once per job by the caller
        // (see [openJobDebugLog]), not here.
        try {
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
                nativeConvertXcz(inputPath, nativePath, cb, tracker)
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
                    nativeConvertXcz(r.file.absolutePath, nativePath, cb, tracker)
                }
            }

            if (result == 0) onVerified(tracker.verdict)

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
