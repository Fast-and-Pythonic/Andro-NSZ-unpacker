package com.androNSZ.fs

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
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
        progressCallback: (FolderProgressUpdate) -> Unit,
        statusCallback: NszConverter.StatusCallback?
    ): Result<Pair<Uri, FolderConversionSummary>> = withContext(Dispatchers.IO) {
        val totalFileCount = countAllFiles(structure.allFiles)

        statusCallback?.onStatus("INFO", "Начало обработки папки")
        statusCallback?.onStatus("INFO", "Всего файлов в папке: ${structure.allFiles.size}")
        statusCallback?.onStatus("INFO", "NSZ файлов для конвертации: ${structure.nszFiles.size}")
        statusCallback?.onStatus("INFO", "Общий размер: %.2f MB".format(structure.totalSize / 1024.0 / 1024.0))

        var cumulativeBytesProcessed = 0L
        var lastCumulativeBytes = 0L
        var lastTimeMs = System.currentTimeMillis()

        val outputFolderName = generateOutputFolderName(context, structure.rootUri)
        statusCallback?.onStatus("FOLDER", "Создание выходной папки: $outputFolderName")

        val outputFolderUri = createOutputFolder(context, outputFolderName)
        if (outputFolderUri == null) {
            statusCallback?.onStatus("ERROR", "Не удалось создать выходную папку")
            return@withContext Result.failure(Exception("Cannot create output folder"))
        }

        statusCallback?.onStatus("FOLDER", "Выходная папка создана: $outputFolderUri")

        val emitProgress = { currentFileName: String?, currentFileDone: Long, currentFileTotal: Long, cumulativeDone: Long, processedFiles: Int ->
            val safeFileTotal = currentFileTotal.coerceAtLeast(0L)
            val safeFileDone = currentFileDone.coerceAtLeast(0L).coerceAtMost(safeFileTotal)
            val baseDone = cumulativeDone.coerceAtLeast(0L).coerceAtMost(structure.totalSize)
            val currentCumulative = (baseDone + safeFileDone).coerceAtMost(structure.totalSize)

            val now = System.currentTimeMillis()
            val elapsedSec = (now - lastTimeMs).coerceAtLeast(1L) / 1000.0
            val speed = if (elapsedSec > 0) {
                (currentCumulative - lastCumulativeBytes).toDouble() / 1024 / 1024 / elapsedSec
            } else {
                0.0
            }

            lastCumulativeBytes = currentCumulative
            lastTimeMs = now

            progressCallback(
                FolderProgressUpdate(
                    overallProgress = ConversionProgress(
                        doneBytes = currentCumulative,
                        totalBytes = structure.totalSize,
                        speedMBps = speed
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

        val results = mutableListOf<FileConversionResult>()

        try {
            statusCallback?.onStatus("INFO", "Начало обработки файлов...")
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

            val summary = FolderConversionSummary(
                totalFiles = totalFileCount,
                nszFilesProcessed = structure.nszFiles.size,
                successCount = results.count { it is FileConversionResult.Success },
                failedCount = results.count { it is FileConversionResult.Failed },
                skippedCount = results.count { it is FileConversionResult.Skipped },
                results = results.toList(),
                totalDurationMs = totalTimeMs,
                totalBytesProcessed = cumulativeBytesProcessed
            )

            statusCallback?.onStatus("SUMMARY", "========================================")
            statusCallback?.onStatus("SUMMARY", "Итоги конвертации")
            statusCallback?.onStatus("SUMMARY", "========================================")
            statusCallback?.onStatus("SUMMARY", "Всего файлов: ${summary.totalFiles}")
            statusCallback?.onStatus("SUMMARY", "NSZ файлов обработано: ${summary.nszFilesProcessed}")
            statusCallback?.onStatus("SUMMARY", "Успешно: ${summary.successCount}")
            statusCallback?.onStatus("SUMMARY", "Ошибок: ${summary.failedCount}")
            statusCallback?.onStatus("SUMMARY", "Время: ${totalTimeMs/1000}с")
            statusCallback?.onStatus("SUMMARY", "Средняя скорость: %.2f MB/s".format(avgSpeedMBps))

            if (summary.failedCount > 0) {
                statusCallback?.onStatus("SUMMARY", "")
                statusCallback?.onStatus("SUMMARY", "Файлы с ошибками:")
                results.filterIsInstance<FileConversionResult.Failed>().forEach { failed ->
                    statusCallback?.onStatus("FAILED_FILE", "  ${failed.fileName}: ${failed.errorMessage} (код: ${failed.errorCode})")
                }
            }

            statusCallback?.onStatus("COMPLETE", "Результат сохранён в: $outputFolderUri")
            emitProgress(null, 0L, 0L, cumulativeBytesProcessed, totalFileCount)

            if (summary.successCount > 0) {
                Result.success(Pair(outputFolderUri, summary))
            } else {
                deleteFolder(context, outputFolderUri)
                Result.failure(Exception("Все файлы не удалось обработать. Успешно: 0, Ошибок: ${summary.failedCount}"))
            }
        } catch (e: NszConversionException) {
            statusCallback?.onStatus("ERROR", "Ошибка конвертации NSZ: ${e.message} (код: ${e.code})")
            deleteFolder(context, outputFolderUri)
            Result.failure(e)
        } catch (e: Exception) {
            statusCallback?.onStatus("ERROR", "Критическая ошибка обработки: ${e.message}")
            statusCallback?.onStatus("ERROR", "Стек: ${e.stackTraceToString().take(500)}")
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
                            statusCallback?.onStatus("NSZ", "[$fileIndex/${nodes.size}] Начало конвертации: ${node.name} (%.2f MB)".format(fileSizeMB))
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
                            statusCallback?.onStatus("NSZ", "[$fileIndex/${nodes.size}] Конвертация завершена: ${node.name} за ${elapsedMs/1000}с (%.2f MB/s)".format(speedMBps))

                            results.add(FileConversionResult.Success(
                                fileName = node.name,
                                outputName = node.name.substringBeforeLast('.') + ".nsp",
                                sizeBytes = fileSize,
                                durationMs = elapsedMs
                            ))

                            cumulative += fileSize
                            processedFiles++
                            progressCallback(null, 0L, 0L, cumulative, processedFiles)

                        } catch (e: NszConversionException) {
                            statusCallback?.onStatus("ERROR", "Конвертация не удалась: ${node.name} - ${e.message} (код: ${e.code})")
                            results.add(FileConversionResult.Failed(
                                fileName = node.name,
                                errorCode = e.code,
                                errorMessage = e.message ?: "Unknown error",
                                sizeBytes = fileSize
                            ))
                            cumulative += fileSize
                            processedFiles++
                            progressCallback(null, 0L, 0L, cumulative, processedFiles)
                        } catch (e: Exception) {
                            statusCallback?.onStatus("ERROR", "Ошибка обработки: ${node.name} - ${e.message}")
                            results.add(FileConversionResult.Failed(
                                fileName = node.name,
                                errorCode = -999,
                                errorMessage = e.message ?: "Unknown error",
                                sizeBytes = fileSize
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
                            statusCallback?.onStatus("COPY", "[$fileIndex/${nodes.size}] Копирование: ${node.name} (%.2f MB)".format(fileSizeMB))
                            val startTime = System.currentTimeMillis()

                            copyFile(context, node.uri, destParentUri, destRelativePath, node.name, statusCallback)

                            val elapsedMs = System.currentTimeMillis() - startTime
                            statusCallback?.onStatus("COPY", "[$fileIndex/${nodes.size}] Копирование завершено: ${node.name} за ${elapsedMs}мс")

                            results.add(FileConversionResult.Success(
                                fileName = node.name,
                                outputName = node.name,
                                sizeBytes = fileSize,
                                durationMs = elapsedMs
                            ))
                            cumulative += fileSize
                            processedFiles++
                            progressCallback(null, 0L, 0L, cumulative, processedFiles)

                        } catch (e: Exception) {
                            statusCallback?.onStatus("ERROR", "Копирование не удалось: ${node.name} - ${e.message}")
                            results.add(FileConversionResult.Failed(
                                fileName = node.name,
                                errorCode = -998,
                                errorMessage = e.message ?: "Copy failed",
                                sizeBytes = fileSize
                            ))
                            cumulative += fileSize
                            processedFiles++
                            progressCallback(null, 0L, 0L, cumulative, processedFiles)
                        }
                    }
                }
                is FileNode.Directory -> {
                    statusCallback?.onStatus("FOLDER", "Вход в папку: ${node.name} (${node.children.size} элементов)")

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
                        statusCallback?.onStatus("FOLDER", "Выход из папки: ${node.name}")
                    } else {
                        statusCallback?.onStatus("ERROR", "Не удалось создать подпапку: ${node.name}")
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
            val result = if (parentUri.scheme == "file") {
                val parentFile = File(parentUri.path!!)
                val subFolder = File(parentFile, name)
                subFolder.mkdirs()
                Pair(Uri.fromFile(subFolder), null)
            } else {
                Pair(parentUri, buildChildRelativePath(parentRelativePath, name))
            }
            statusCallback?.onStatus("FOLDER", "Подпапка создана: $name")
            result
        } catch (e: Exception) {
            statusCallback?.onStatus("ERROR", "Ошибка создания подпапки '$name': ${e.message}")
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
        statusCallback?.onStatus("NSZ", "Создан временный файл: ${tempOutput.absolutePath}")

        try {
            statusCallback?.onStatus("NSZ", "Разрешение пути к исходному файлу...")
            resolvedInput = resolveToFilePath(context, sourceUri, statusCallback)
            statusCallback?.onStatus("NSZ", "Исходный файл: ${resolvedInput.file.absolutePath}")

            statusCallback?.onStatus("NSZ", "Запуск native конвертации...")
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
                statusCallback?.onStatus("ERROR", "Native конвертация завершилась с ошибкой: $errorMsg (код: $result)")
                throw NszConversionException(result, errorMsg)
            }

            statusCallback?.onStatus("NSZ", "Native конвертация завершена за ${conversionTimeMs}мс")
            statusCallback?.onStatus("NSZ", "Размер результата: %.2f MB".format(tempOutput.length() / 1024.0 / 1024.0))

            statusCallback?.onStatus("NSZ", "Копирование результата в целевую папку...")
            copyFileToDestination(context, tempOutput, destParentUri, destRelativePath, outputName, statusCallback)
            statusCallback?.onStatus("NSZ", "Результат скопирован: $outputName")

        } catch (e: Exception) {
            statusCallback?.onStatus("ERROR", "Ошибка при конвертации '$outputName': ${e.message}")
            throw e
        } finally {
            resolvedInput?.deleteIfTemp(statusCallback)
            val deleted = TempFileManager.deleteQuietly(tempOutput)
            if (deleted) {
                statusCallback?.onStatus("NSZ", "Временный файл удалён")
            } else if (tempOutput.exists()) {
                statusCallback?.onStatus("ERROR", "Не удалось удалить временный файл: ${tempOutput.absolutePath}")
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
            if (destParentUri.scheme == "file") {
                val destFile = File(File(destParentUri.path!!), fileName)
                statusCallback?.onStatus("COPY", "Копирование файла в: ${destFile.absolutePath}")

                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                statusCallback?.onStatus("COPY", "Создание файла через MediaStore: $fileName")

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
            statusCallback?.onStatus("COPY", "Файл успешно скопирован: $fileName")
        } catch (e: Exception) {
            statusCallback?.onStatus("ERROR", "Ошибка копирования файла '$fileName': ${e.message}")
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
            if (destParentUri.scheme == "file") {
                val destFile = File(File(destParentUri.path!!), fileName)
                statusCallback?.onStatus("COPY", "Копирование в локальную ФС: ${destFile.absolutePath}")

                sourceFile.inputStream().use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                statusCallback?.onStatus("COPY", "Создание файла через MediaStore: $fileName")

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
            statusCallback?.onStatus("COPY", "Файл скопирован в целевую папку: $fileName")
        } catch (e: Exception) {
            statusCallback?.onStatus("ERROR", "Ошибка копирования в назначение '$fileName': ${e.message}")
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
            // Игнорируем ошибки удаления
        }
    }

    private fun generateOutputFolderName(context: Context, rootUri: Uri): String {
        val originalName = getSourceFolderName(context, rootUri)
            .takeIf { it.isNotBlank() }
            ?: "AndroNSZ"
        val baseName = "${originalName}_unpacked"

        var candidate = baseName
        var index = 2
        while (outputNameExists(context, candidate)) {
            candidate = "${baseName}_$index"
            index++
        }

        return candidate
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
