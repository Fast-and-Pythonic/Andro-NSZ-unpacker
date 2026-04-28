package com.androNSZ.fs

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.androNSZ.Constants
import com.androNSZ.NszConverter
import com.androNSZ.model.*
import com.androNSZ.util.ResolvedInputFile
import com.androNSZ.util.getUriSize
import com.androNSZ.util.resolveToFilePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object FolderProcessor {

    suspend fun processFolder(
        context: Context,
        structure: FolderStructure,
        headerKey: ByteArray?,
        outputBaseUri: Uri?,
        progressCallback: (FolderProgressUpdate) -> Unit,
        statusCallback: NszConverter.StatusCallback?
    ): Result<Pair<Uri, FolderConversionSummary>> = withContext(Dispatchers.IO) {
        val totalFileCount = countAllFiles(structure.allFiles)

        statusCallback?.onStatus("INFO", "Starting folder processing")
        statusCallback?.onStatus("INFO", "Total files in folder: ${structure.allFiles.size}")
        statusCallback?.onStatus("INFO", "NSZ files to convert: ${structure.nszFiles.size}")
        statusCallback?.onStatus("INFO", "Total size: %.2f MB".format(structure.totalSize / 1024.0 / 1024.0))

        var cumulativeBytesProcessed = 0L
        var lastCumulativeBytes = 0L
        var lastTimeMs = System.currentTimeMillis()
        var lastEmitTimeMs = System.currentTimeMillis()
        var lastNumericEmitTimeMs = System.currentTimeMillis()
        var lastSpeed = 0.0

        val outputFolderName = generateOutputFolderName(context, structure.rootUri, outputBaseUri)
        statusCallback?.onStatus("FOLDER", "Creating output folder: $outputFolderName")

        val outputFolderUri = if (outputBaseUri != null) {
            createOutputFolderInSaf(context, outputBaseUri, outputFolderName)
        } else {
            createOutputFolder(context, outputFolderName)
        }
        if (outputFolderUri == null) {
            statusCallback?.onStatus("ERROR", "Failed to create output folder")
            return@withContext Result.failure(Exception("Cannot create output folder"))
        }

        statusCallback?.onStatus("FOLDER", "Output folder created: $outputFolderUri")

        val emitProgress = { currentFileName: String?, currentFileDone: Long, currentFileTotal: Long, cumulativeDone: Long, processedFiles: Int ->
            val safeFileTotal = currentFileTotal.coerceAtLeast(0L)
            val safeFileDone = currentFileDone.coerceAtLeast(0L).coerceAtMost(safeFileTotal)
            val baseDone = cumulativeDone.coerceAtLeast(0L).coerceAtMost(structure.totalSize)
            val currentCumulative = (baseDone + safeFileDone).coerceAtMost(structure.totalSize)

            val now = System.currentTimeMillis()
            
            val shouldUpdateNumeric = (now - lastNumericEmitTimeMs >= Constants.PROGRESS_NUMERIC_UPDATE_INTERVAL_MS)
            
            // Update progress bar every 250ms (4 times per second)
            if (now - lastEmitTimeMs >= Constants.PROGRESS_BAR_UPDATE_INTERVAL_MS) {
                if (shouldUpdateNumeric) {
                    val elapsedSec = (now - lastTimeMs).coerceAtLeast(1L) / 1000.0
                    lastSpeed = if (elapsedSec > 0) {
                        (currentCumulative - lastCumulativeBytes).toDouble() / 1024 / 1024 / elapsedSec
                    } else {
                        0.0
                    }
                    lastCumulativeBytes = currentCumulative
                    lastTimeMs = now
                    lastNumericEmitTimeMs = now
                }
                
                lastEmitTimeMs = now

                progressCallback(
                    FolderProgressUpdate(
                        overallProgress = ConversionProgress(
                            doneBytes = currentCumulative,
                            totalBytes = structure.totalSize,
                            speedMBps = lastSpeed
                        ),
                        currentFileProgress = currentFileName?.takeIf { safeFileTotal > 0 }?.let {
                            ConversionProgress(
                                doneBytes = safeFileDone,
                                totalBytes = safeFileTotal,
                                speedMBps = 0.0
                            )
                        },
                        currentFileName = currentFileName,
                        processedFiles = processedFiles,
                        totalFiles = totalFileCount
                    )
                )
            }
        }

        val results = mutableListOf<FileConversionResult>()

        try {
            statusCallback?.onStatus("INFO", "Starting file processing...")
            val processingStartTime = System.currentTimeMillis()

            cumulativeBytesProcessed = processNodes(
                context,
                structure.allFiles,
                structure.rootUri,
                outputFolderUri,
                outputFolderName,
                headerKey,
                cumulativeBytesProcessed,
                emitProgress,
                statusCallback,
                results
            )

            val totalTimeMs = System.currentTimeMillis() - processingStartTime
            val avgSpeedMBps = if (totalTimeMs > 0) {
                (cumulativeBytesProcessed / 1024.0 / 1024.0) / (totalTimeMs / 1000.0)
            } else 0.0

            // Собираем детальную статистику по типам операций
            val nszResults = results.filter { 
                (it is FileConversionResult.Success && it.operationType == FileOperationType.NSZ_CONVERSION) ||
                (it is FileConversionResult.Failed && it.operationType == FileOperationType.NSZ_CONVERSION)
            }
            val xczResults = results.filter { 
                (it is FileConversionResult.Success && it.operationType == FileOperationType.XCZ_CONVERSION) ||
                (it is FileConversionResult.Failed && it.operationType == FileOperationType.XCZ_CONVERSION)
            }
            val copyResults = results.filter { 
                (it is FileConversionResult.Success && it.operationType == FileOperationType.FILE_COPY) ||
                (it is FileConversionResult.Failed && it.operationType == FileOperationType.FILE_COPY)
            }

            val summary = FolderConversionSummary(
                totalFiles = totalFileCount,
                nszFilesProcessed = structure.nszFiles.size,
                successCount = results.count { it is FileConversionResult.Success },
                failedCount = results.count { it is FileConversionResult.Failed },
                skippedCount = results.count { it is FileConversionResult.Skipped },
                results = results.toList(),
                totalDurationMs = totalTimeMs,
                totalBytesProcessed = cumulativeBytesProcessed,
                // Детальная статистика по типам операций
                nszSuccessCount = nszResults.count { it is FileConversionResult.Success },
                nszFailedCount = nszResults.count { it is FileConversionResult.Failed },
                xczSuccessCount = xczResults.count { it is FileConversionResult.Success },
                xczFailedCount = xczResults.count { it is FileConversionResult.Failed },
                xczFilesProcessed = structure.xczFiles.size,
                copySuccessCount = copyResults.count { it is FileConversionResult.Success },
                copyFailedCount = copyResults.count { it is FileConversionResult.Failed },
                copyFilesProcessed = copyResults.size
            )

            statusCallback?.onStatus("SUMMARY", "========================================")
            statusCallback?.onStatus("SUMMARY", "Conversion Summary")
            statusCallback?.onStatus("SUMMARY", "========================================")
            statusCallback?.onStatus("SUMMARY", "Total files: ${summary.totalFiles}")
            statusCallback?.onStatus("SUMMARY", "Successful: ${summary.successCount}")
            statusCallback?.onStatus("SUMMARY", "Failed: ${summary.failedCount}")
            statusCallback?.onStatus("SUMMARY", "")
            statusCallback?.onStatus("SUMMARY", "NSZ conversions: ${summary.nszSuccessCount}/${summary.nszFilesProcessed} (failed: ${summary.nszFailedCount})")
            statusCallback?.onStatus("SUMMARY", "XCZ conversions: ${summary.xczSuccessCount}/${summary.xczFilesProcessed} (failed: ${summary.xczFailedCount})")
            statusCallback?.onStatus("SUMMARY", "Files copied: ${summary.copySuccessCount}/${summary.copyFilesProcessed} (failed: ${summary.copyFailedCount})")
            statusCallback?.onStatus("SUMMARY", "")
            statusCallback?.onStatus("SUMMARY", "Time: ${totalTimeMs/1000}s")
            statusCallback?.onStatus("SUMMARY", "Average speed: %.2f MB/s".format(avgSpeedMBps))

            if (summary.failedCount > 0) {
                statusCallback?.onStatus("SUMMARY", "")
                statusCallback?.onStatus("SUMMARY", "Failed files:")
                results.filterIsInstance<FileConversionResult.Failed>().forEach { failed ->
                    statusCallback?.onStatus("FAILED_FILE", "  ${failed.fileName}: ${failed.errorMessage} (code: ${failed.errorCode})")
                }
            }

            statusCallback?.onStatus("COMPLETE", "Result saved to: $outputFolderUri")
            emitProgress(null, 0L, 0L, cumulativeBytesProcessed, totalFileCount)

            if (summary.successCount > 0) {
                Result.success(Pair(outputFolderUri, summary))
            } else {
                deleteFolder(context, outputFolderUri)
                Result.failure(Exception("All files failed to process. Successful: 0, Errors: ${summary.failedCount}"))
            }
        } catch (e: NszConversionException) {
            statusCallback?.onStatus("ERROR", "NSZ conversion error: ${e.message} (code: ${e.code})")
            deleteFolder(context, outputFolderUri)
            Result.failure(e)
        } catch (e: Exception) {
            statusCallback?.onStatus("ERROR", "Critical processing error: ${e.message}")
            statusCallback?.onStatus("ERROR", "Stack: ${e.stackTraceToString().take(500)}")
            deleteFolder(context, outputFolderUri)
            Result.failure(e)
        }
    }

    private fun createOutputFolder(context: Context, name: String): Uri? {
        return try {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                cv
            )
        } catch (e: Exception) {
            try {
                val downloadsDir = File(context.getExternalFilesDir(null), "Downloads")
                downloadsDir.mkdirs()
                val outputDir = File(downloadsDir, name)
                outputDir.mkdirs()
                Uri.fromFile(outputDir)
            } catch (e2: Exception) {
                null
            }
        }
    }

    private suspend fun processNodes(
        context: Context,
        nodes: List<FileNode>,
        sourceRootUri: Uri,
        destParentUri: Uri,
        destRelativePath: String?,
        headerKey: ByteArray?,
        cumulativeBytesProcessed: Long,
        progressCallback: (String?, Long, Long, Long, Int) -> Unit,
        statusCallback: NszConverter.StatusCallback?,
        results: MutableList<FileConversionResult>
    ): Long {
        var cumulative = cumulativeBytesProcessed
        var processedFiles = countProcessedFiles(results)
        var fileIndex = 0

        for (node in nodes) {
            when (node) {
                is FileNode.File -> {
                    fileIndex++
                    if (node.isNsz) {
                        val fileSize = getUriSize(context, node.uri)
                        val fileSizeMB = fileSize / 1024.0 / 1024.0
                        progressCallback(node.name, 0L, fileSize, cumulative, processedFiles)

                        try {
                            statusCallback?.onStatus("NSZ", "[$fileIndex/${nodes.size}] Starting conversion: ${node.name} (%.2f MB)".format(fileSizeMB))
                            val startTime = System.currentTimeMillis()

                            convertAndSaveNsz(
                                context,
                                node.uri,
                                destParentUri,
                                destRelativePath,
                                node.name.substringBeforeLast('.') + ".nsp",
                                headerKey,
                                { done, _ ->
                                    progressCallback(node.name, done.coerceAtMost(fileSize), fileSize, cumulative, processedFiles)
                                },
                                statusCallback
                            )

                            val elapsedMs = System.currentTimeMillis() - startTime
                            val speedMBps = if (elapsedMs > 0) fileSizeMB / (elapsedMs / 1000.0) else 0.0
                            statusCallback?.onStatus("NSZ", "[$fileIndex/${nodes.size}] Conversion completed: ${node.name} in ${elapsedMs/1000}s (%.2f MB/s)".format(speedMBps))

                            results.add(FileConversionResult.Success(
                                fileName = node.name,
                                outputName = node.name.substringBeforeLast('.') + ".nsp",
                                sizeBytes = fileSize,
                                durationMs = elapsedMs,
                                operationType = FileOperationType.NSZ_CONVERSION
                            ))

                            cumulative += fileSize
                            processedFiles++
                            progressCallback(null, 0L, 0L, cumulative, processedFiles)

                        } catch (e: NszConversionException) {
                            statusCallback?.onStatus("ERROR", "Conversion failed: ${node.name} - ${e.message} (code: ${e.code})")
                            results.add(FileConversionResult.Failed(
                                fileName = node.name,
                                errorCode = e.code,
                                errorMessage = e.message ?: "Unknown error",
                                sizeBytes = fileSize,
                                operationType = FileOperationType.NSZ_CONVERSION
                            ))
                            cumulative += fileSize
                            processedFiles++
                            progressCallback(null, 0L, 0L, cumulative, processedFiles)
                        } catch (e: Exception) {
                            statusCallback?.onStatus("ERROR", "Processing error: ${node.name} - ${e.message}")
                            results.add(FileConversionResult.Failed(
                                fileName = node.name,
                                errorCode = -999,
                                errorMessage = e.message ?: "Unknown error",
                                sizeBytes = fileSize,
                                operationType = FileOperationType.NSZ_CONVERSION
                            ))
                            cumulative += fileSize
                            processedFiles++
                            progressCallback(null, 0L, 0L, cumulative, processedFiles)
                        }
                    } else {
                        val fileSize = getUriSize(context, node.uri)
                        val fileSizeMB = fileSize / 1024.0 / 1024.0
                        progressCallback(node.name, 0L, fileSize, cumulative, processedFiles)

                        try {
                            statusCallback?.onStatus("COPY", "[$fileIndex/${nodes.size}] Copying: ${node.name} (%.2f MB)".format(fileSizeMB))
                            val startTime = System.currentTimeMillis()

                            copyFile(context, node.uri, destParentUri, destRelativePath, node.name, statusCallback)

                            val elapsedMs = System.currentTimeMillis() - startTime
                            statusCallback?.onStatus("COPY", "[$fileIndex/${nodes.size}] Copy completed: ${node.name} in ${elapsedMs}ms")

                            results.add(FileConversionResult.Success(
                                fileName = node.name,
                                outputName = node.name,
                                sizeBytes = fileSize,
                                durationMs = elapsedMs,
                                operationType = FileOperationType.FILE_COPY
                            ))
                            cumulative += fileSize
                            processedFiles++
                            progressCallback(null, 0L, 0L, cumulative, processedFiles)

                        } catch (e: Exception) {
                            statusCallback?.onStatus("ERROR", "Copy failed: ${node.name} - ${e.message}")
                            results.add(FileConversionResult.Failed(
                                fileName = node.name,
                                errorCode = -998,
                                errorMessage = e.message ?: "Copy failed",
                                sizeBytes = fileSize,
                                operationType = FileOperationType.FILE_COPY
                            ))
                            cumulative += fileSize
                            processedFiles++
                            progressCallback(null, 0L, 0L, cumulative, processedFiles)
                        }
                    }
                }
                is FileNode.Directory -> {
                    statusCallback?.onStatus("FOLDER", "Entering folder: ${node.name} (${node.children.size} items)")

                    val subFolder = createSubFolder(context, destParentUri, destRelativePath, node.name, statusCallback)
                    if (subFolder != null) {
                        cumulative = processNodes(
                            context,
                            node.children,
                            sourceRootUri,
                            subFolder.first,
                            subFolder.second,
                            headerKey,
                            cumulative,
                            progressCallback,
                            statusCallback,
                            results
                        )
                        statusCallback?.onStatus("FOLDER", "Exiting folder: ${node.name}")
                    } else {
                        statusCallback?.onStatus("ERROR", "Failed to create subfolder: ${node.name}")
                    }
                }
            }
        }
        return cumulative
    }

    private fun createSubFolder(
        context: Context,
        parentUri: Uri,
        parentRelativePath: String?,
        name: String,
        statusCallback: NszConverter.StatusCallback? = null
    ): Pair<Uri, String?>? {
        return try {
            val result = when {
                parentUri.scheme == "file" -> {
                    val parentFile = File(parentUri.path!!)
                    val subFolder = File(parentFile, name)
                    subFolder.mkdirs()
                    Pair(Uri.fromFile(subFolder), null)
                }
                DocumentsContract.isDocumentUri(context, parentUri) -> {
                    val subUri = DocumentsContract.createDocument(
                        context.contentResolver, parentUri,
                        DocumentsContract.Document.MIME_TYPE_DIR, name
                    ) ?: return null
                    Pair(subUri, null)
                }
                else -> Pair(parentUri, buildChildRelativePath(parentRelativePath, name))
            }
            statusCallback?.onStatus("FOLDER", "Subfolder created: $name")
            result
        } catch (e: Exception) {
            statusCallback?.onStatus("ERROR", "Error creating subfolder '$name': ${e.message}")
            null
        }
    }

    private suspend fun convertAndSaveNsz(
        context: Context,
        sourceUri: Uri,
        destParentUri: Uri,
        destRelativePath: String?,
        outputName: String,
        headerKey: ByteArray?,
        progressCallback: (Long, Long) -> Unit,
        statusCallback: NszConverter.StatusCallback?
    ) {
        val tempOutput = TempFileManager.createManagedTempFile(context, outputName, "nsp")
        var resolvedInput: ResolvedInputFile? = null
        statusCallback?.onStatus("NSZ", "Created temp file: ${tempOutput.absolutePath}")

        try {
            statusCallback?.onStatus("NSZ", "Resolving source file path...")
            resolvedInput = resolveToFilePath(context, sourceUri, statusCallback)
            statusCallback?.onStatus("NSZ", "Source file: ${resolvedInput.file.absolutePath}")

            statusCallback?.onStatus("NSZ", "Starting native conversion...")
            val conversionStartTime = System.currentTimeMillis()

            val result = NszConverter.nativeConvert(
                resolvedInput.file.absolutePath,
                tempOutput.absolutePath,
                object : NszConverter.ProgressCallback {
                    override fun onProgress(done: Long, total: Long) {
                        progressCallback(done, total)
                    }
                },
                statusCallback
            )

            val conversionTimeMs = System.currentTimeMillis() - conversionStartTime

            if (result != NszConverter.OK) {
                val errorMsg = NszConverter.nativeErrorString(result)
                statusCallback?.onStatus("ERROR", "Native conversion failed: $errorMsg (code: $result)")
                throw NszConversionException(result, errorMsg)
            }

            statusCallback?.onStatus("NSZ", "Native conversion completed in ${conversionTimeMs}ms")
            statusCallback?.onStatus("NSZ", "Result size: %.2f MB".format(tempOutput.length() / 1024.0 / 1024.0))

            statusCallback?.onStatus("NSZ", "Copying result to target folder...")
            copyFileToDestination(context, tempOutput, destParentUri, destRelativePath, outputName, statusCallback)
            statusCallback?.onStatus("NSZ", "Result copied: $outputName")

        } catch (e: Exception) {
            statusCallback?.onStatus("ERROR", "Error converting '$outputName': ${e.message}")
            throw e
        } finally {
            resolvedInput?.deleteIfTemp(statusCallback)
            val deleted = TempFileManager.deleteQuietly(tempOutput)
            if (deleted) {
                statusCallback?.onStatus("NSZ", "Temp file deleted")
            } else if (tempOutput.exists()) {
                statusCallback?.onStatus("ERROR", "Failed to delete temp file: ${tempOutput.absolutePath}")
            }
        }
    }

    private fun copyFile(
        context: Context,
        sourceUri: Uri,
        destParentUri: Uri,
        destRelativePath: String?,
        fileName: String,
        statusCallback: NszConverter.StatusCallback? = null
    ) {
        try {
            when {
                destParentUri.scheme == "file" -> {
                    val destFile = File(File(destParentUri.path!!), fileName)
                    statusCallback?.onStatus("COPY", "Copying file to: ${destFile.absolutePath}")

                    context.contentResolver.openInputStream(sourceUri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                DocumentsContract.isDocumentUri(context, destParentUri) -> {
                    statusCallback?.onStatus("COPY", "Creating file via SAF: $fileName")
                    val destUri = DocumentsContract.createDocument(
                        context.contentResolver, destParentUri, "application/octet-stream", fileName
                    ) ?: throw Exception("Cannot create SAF file: $fileName")

                    context.contentResolver.openInputStream(sourceUri)?.use { input ->
                        context.contentResolver.openOutputStream(destUri)?.use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                else -> {
                    statusCallback?.onStatus("COPY", "Creating file via MediaStore: $fileName")

                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                        put(MediaStore.Downloads.IS_PENDING, 1)

                        val relativePath = resolveRelativePath(context, destParentUri, destRelativePath)
                        if (relativePath != null) {
                            put(MediaStore.Downloads.RELATIVE_PATH, "Download/$relativePath")
                        }
                    }

                    val destUri = context.contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cv
                    ) ?: throw Exception("Cannot create destination file: $fileName")

                    try {
                        context.contentResolver.openInputStream(sourceUri)?.use { input ->
                            context.contentResolver.openOutputStream(destUri)?.use { output ->
                                input.copyTo(output)
                            }
                        }

                        val updateCv = ContentValues().apply {
                            put(MediaStore.Downloads.IS_PENDING, 0)
                        }
                        context.contentResolver.update(destUri, updateCv, null, null)

                    } catch (e: Exception) {
                        context.contentResolver.delete(destUri, null, null)
                        throw e
                    }
                }
            }
            statusCallback?.onStatus("COPY", "File copied successfully: $fileName")
        } catch (e: Exception) {
            statusCallback?.onStatus("ERROR", "Error copying file '$fileName': ${e.message}")
            throw e
        }
    }

    private fun copyFileToDestination(
        context: Context,
        sourceFile: File,
        destParentUri: Uri,
        destRelativePath: String?,
        fileName: String,
        statusCallback: NszConverter.StatusCallback? = null
    ) {
        try {
            when {
                destParentUri.scheme == "file" -> {
                    val destFile = File(File(destParentUri.path!!), fileName)
                    statusCallback?.onStatus("COPY", "Copying to local filesystem: ${destFile.absolutePath}")

                    sourceFile.inputStream().use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                DocumentsContract.isDocumentUri(context, destParentUri) -> {
                    statusCallback?.onStatus("COPY", "Creating file via SAF: $fileName")
                    val destUri = DocumentsContract.createDocument(
                        context.contentResolver, destParentUri, "application/octet-stream", fileName
                    ) ?: throw Exception("Cannot create SAF file: $fileName")

                    sourceFile.inputStream().use { input ->
                        context.contentResolver.openOutputStream(destUri)?.use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                else -> {
                    statusCallback?.onStatus("COPY", "Creating file via MediaStore: $fileName")

                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                        put(MediaStore.Downloads.IS_PENDING, 1)

                        val relativePath = resolveRelativePath(context, destParentUri, destRelativePath)
                        if (relativePath != null) {
                            put(MediaStore.Downloads.RELATIVE_PATH, "Download/$relativePath")
                        }
                    }

                    val destUri = context.contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cv
                    ) ?: throw Exception("Cannot create destination file: $fileName")

                    try {
                        sourceFile.inputStream().use { input ->
                            context.contentResolver.openOutputStream(destUri)?.use { output ->
                                input.copyTo(output)
                            }
                        }

                        val updateCv = ContentValues().apply {
                            put(MediaStore.Downloads.IS_PENDING, 0)
                        }
                        context.contentResolver.update(destUri, updateCv, null, null)

                    } catch (e: Exception) {
                        context.contentResolver.delete(destUri, null, null)
                        throw e
                    }
                }
            }
            statusCallback?.onStatus("COPY", "File copied to target folder: $fileName")
        } catch (e: Exception) {
            statusCallback?.onStatus("ERROR", "Error copying to destination '$fileName': ${e.message}")
            throw e
        }
    }

    private fun deleteFolder(context: Context, folderUri: Uri) {
        try {
            if (folderUri.scheme == "file") {
                File(folderUri.path!!).deleteRecursively()
            } else {
                DocumentsContract.deleteDocument(context.contentResolver, folderUri)
            }
        } catch (e: Exception) {
            // Ignore deletion errors
        }
    }

    private fun generateOutputFolderName(context: Context, rootUri: Uri, outputBaseUri: Uri? = null): String {
        val originalName = getSourceFolderName(context, rootUri)
            .takeIf { it.isNotBlank() }
            ?: "AndroNSZ"
        val baseName = "${originalName}_unpacked"

        var candidate = baseName
        var index = 2
        while (if (outputBaseUri != null) outputNameExistsInSaf(context, outputBaseUri, candidate)
               else outputNameExists(context, candidate)) {
            candidate = "${baseName}_$index"
            index++
        }

        return candidate
    }

    private fun createOutputFolderInSaf(context: Context, treeUri: Uri, name: String): Uri? {
        return try {
            val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            DocumentsContract.createDocument(
                context.contentResolver, treeDocUri, DocumentsContract.Document.MIME_TYPE_DIR, name
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun outputNameExistsInSaf(context: Context, treeUri: Uri, name: String): Boolean {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        return try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val col = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (col >= 0 && cursor.getString(col) == name) return@use true
                }
                false
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun getSourceFolderName(context: Context, rootUri: Uri): String {
        val queryUri = toDocumentUri(rootUri)

        context.contentResolver.query(
            queryUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    return cursor.getString(nameIndex)
                }
            }
        }

        return rootUri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.ifBlank { "AndroNSZ" }
            ?: "AndroNSZ"
    }

    private fun toDocumentUri(uri: Uri): Uri {
        if (uri.scheme != "content") return uri

        return if (DocumentsContract.isTreeUri(uri)) {
            DocumentsContract.buildDocumentUriUsingTree(
                uri,
                DocumentsContract.getTreeDocumentId(uri)
            )
        } else {
            uri
        }
    }

    private fun outputNameExists(context: Context, name: String): Boolean {
        val downloadsDir = File(context.getExternalFilesDir(null), "Downloads")
        return File(downloadsDir, name).exists()
    }

    private fun countProcessedFiles(results: List<FileConversionResult>): Int {
        return results.count {
            it is FileConversionResult.Success ||
                it is FileConversionResult.Failed ||
                it is FileConversionResult.Skipped
        }
    }

    private fun buildChildRelativePath(parentRelativePath: String?, name: String): String {
        return parentRelativePath
            ?.takeIf { it.isNotBlank() }
            ?.let { "$it/$name" }
            ?: name
    }

    private fun resolveRelativePath(context: Context, destParentUri: Uri, destRelativePath: String?): String? {
        return destRelativePath?.takeIf { it.isNotBlank() } ?: getRelativePathFromUri(context, destParentUri)
    }

    private fun getRelativePathFromUri(context: Context, uri: Uri): String? {
        if (uri.scheme != "content") return null

        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        cursor.getString(nameIndex)
                    } else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
