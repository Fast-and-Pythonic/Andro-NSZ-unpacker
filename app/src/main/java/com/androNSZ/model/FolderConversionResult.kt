package com.androNSZ.model

import android.net.Uri

enum class FileOperationType {
    NSZ_CONVERSION,  // NSZ → NSP
    XCZ_CONVERSION,  // XCZ → XCI
    FILE_COPY        // Обычное копирование
}

sealed class FileConversionResult {
    abstract val fileName: String

    data class Success(
        override val fileName: String,
        val outputName: String,
        val sizeBytes: Long,
        // Size of the produced (uncompressed) file. For copies it equals sizeBytes.
        val unpackedSizeBytes: Long,
        val durationMs: Long,
        val operationType: FileOperationType
    ) : FileConversionResult()

    data class Failed(
        override val fileName: String,
        val errorCode: Int,
        val errorMessage: String,
        val sizeBytes: Long,
        val operationType: FileOperationType
    ) : FileConversionResult()

    data class Skipped(
        override val fileName: String,
        val reason: String
    ) : FileConversionResult()
}

data class FolderConversionSummary(
    val totalFiles: Int,
    val nszFilesProcessed: Int,
    val successCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val results: List<FileConversionResult>,
    val totalDurationMs: Long,
    val totalBytesProcessed: Long,
    // Детальная статистика по типам операций
    val nszSuccessCount: Int,
    val nszFailedCount: Int,
    val xczSuccessCount: Int,
    val xczFailedCount: Int,
    val xczFilesProcessed: Int,
    val copySuccessCount: Int,
    val copyFailedCount: Int,
    val copyFilesProcessed: Int
)

/**
 * A live per-file status update for the "files to unpack" list, keyed by source
 * [Uri]. Lets the UI mirror single-files mode: a file goes Converting when its
 * work starts and Completed/Failed when it finishes, with the final stats filled
 * in on success. Emitted only for NSZ/XCZ files (the ones shown in that list).
 */
data class FolderFileEvent(
    val sourceUri: Uri,
    val status: FileStatus,
    val durationMs: Long? = null,
    val speedMBps: Double? = null,
    val unpackedSize: Long? = null,
    // Verification outcome for the output NSP (see FileEntry.verify).
    val verify: VerifyStatus = VerifyStatus.NOT_CHECKED
)

/** A single file currently being converted in parallel, for the per-file bars. */
data class ActiveFolderFile(
    val name: String,
    val progress: ConversionProgress
)

data class FolderProgressUpdate(
    val overallProgress: ConversionProgress,
    // One entry per file currently converting in parallel (empty between files).
    val activeFiles: List<ActiveFolderFile>,
    val processedFiles: Int,
    val totalFiles: Int
)
