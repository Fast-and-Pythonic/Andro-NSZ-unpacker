package com.androNSZ.model

sealed class FileConversionResult {
    abstract val fileName: String

    data class Success(
        override val fileName: String,
        val outputName: String,
        val sizeBytes: Long,
        val durationMs: Long
    ) : FileConversionResult()

    data class Failed(
        override val fileName: String,
        val errorCode: Int,
        val errorMessage: String,
        val sizeBytes: Long
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
    val totalBytesProcessed: Long
)

data class FolderProgressUpdate(
    val overallProgress: ConversionProgress,
    val currentFileProgress: ConversionProgress?,
    val currentFileName: String?,
    val processedFiles: Int,
    val totalFiles: Int
)
