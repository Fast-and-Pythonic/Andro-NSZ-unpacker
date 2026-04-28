package com.androNSZ.model

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

data class FolderProgressUpdate(
    val overallProgress: ConversionProgress,
    val currentFileProgress: ConversionProgress?,
    val currentFileName: String?,
    val processedFiles: Int,
    val totalFiles: Int
)
