package com.androNSZ.viewmodel

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androNSZ.NszConverter
import com.androNSZ.fs.FolderLogWriter
import com.androNSZ.fs.FolderScanner
import com.androNSZ.fs.FolderProcessor
import com.androNSZ.fs.TempFileManager
import com.androNSZ.model.*
import com.androNSZ.nut.KeysManager
import com.androNSZ.nut.KeysParser
import com.androNSZ.util.getUriSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {

   // Navigation & Mode
   var currentScreen by mutableStateOf<Screen>(Screen.ModeSelection)
   var conversionMode by mutableStateOf<ConversionMode>(ConversionMode.None)

   // Single file mode (legacy)
   var selectedUri   by mutableStateOf<Uri?>(null)
   var selectedName  by mutableStateOf<String?>(null)

   // Batch files mode
   val fileQueue = mutableStateListOf<FileEntry>()
   var currentFileIndex by mutableStateOf(0)
   var batchOverallProgress by mutableStateOf<ConversionProgress?>(null)
   var batchCurrentFileName by mutableStateOf<String?>(null)
   var batchProcessedFiles by mutableStateOf(0)
   var batchTotalFiles by mutableStateOf(0)

   // Folder mode
   var folderStructure by mutableStateOf<FolderStructure?>(null)
   private var folderLogWriter: FolderLogWriter? = null
   var folderLogPath by mutableStateOf<String?>(null)
   var folderOverallProgress by mutableStateOf<ConversionProgress?>(null)
   var folderCurrentFileProgress by mutableStateOf<ConversionProgress?>(null)
   var folderCurrentFileName by mutableStateOf<String?>(null)
   var folderProcessedFiles by mutableStateOf(0)
   var folderTotalFiles by mutableStateOf(0)

   // Conversion state
   var isConverting  by mutableStateOf(false)
   var progress      by mutableStateOf<ConversionProgress?>(null)
   var statusMessage by mutableStateOf<String?>(null)
   var isSuccess     by mutableStateOf(false)
   var keysInstalled by mutableStateOf(false)
   val statusLog = mutableStateListOf<LogEntry>()

   fun checkKeys(context: android.content.Context) {
      keysInstalled = KeysManager.isInstalled(context)
   }

   fun installKeys(context: android.content.Context, uri: Uri) {
      viewModelScope.launch {
         KeysManager.installFromUri(context, uri)
         keysInstalled = KeysManager.isInstalled(context)
      }
   }

   fun pickFile(uri: Uri, displayName: String) {
      selectedUri   = uri
      selectedName  = displayName
      statusMessage = null
      isSuccess     = false
      progress      = null
      statusLog.clear()
   }

   fun startConversion(context: android.content.Context) {
      val uri = selectedUri ?: return
      isConverting  = true
      isSuccess     = false
      statusMessage = null
      progress      = ConversionProgress(0L, 0L, 0.0)
      statusLog.clear()

      val headerKey = KeysParser.parseHeaderKey(KeysManager.keysFile(context))

      val statusCb = object : NszConverter.StatusCallback {
         override fun onStatus(tag: String, msg: String) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
               statusLog.add(LogEntry(tag, msg.trim()))
            }
         }
      }

      viewModelScope.launch {
         NszConverter.convert(context, uri, headerKey, statusCb)
            .catch { e ->
               isConverting = false
               withContext(Dispatchers.IO) { TempFileManager.cleanupManagedCache(context) }
               val logPath = NszConverter.lastDebugLogPath
               val logSuffix = if (logPath != null) "\nDebug log: $logPath" else ""
               statusMessage = when (e) {
                  is CancelledException     -> "Cancelled.$logSuffix"
                  is NszConversionException -> "Error: ${e.message}$logSuffix"
                  else                      -> "Unexpected error: ${e.message}$logSuffix"
               }
            }
            .collect { p ->
               progress = p
            }
         if (isConverting) {
            isConverting = false
            withContext(Dispatchers.IO) { TempFileManager.cleanupManagedCache(context) }
            val logPath      = NszConverter.lastDebugLogPath
            val logSuffix    = if (logPath != null) "\nDebug log: $logPath" else ""
            val verifyError   = NszConverter.lastVerifyError
            val verifySkipped = NszConverter.lastVerifySkipped

            isSuccess     = true
            statusMessage = when {
               verifyError != null ->
                  "Conversion complete, but NSP verification failed:\n$verifyError$logSuffix"
                     .also { isSuccess = false }
               verifySkipped ->
                  "Done! Saved to Downloads.\n(header_key not found in prod.keys — verification skipped)$logSuffix"
               else ->
                  "Done! NSP verified. Saved to Downloads.$logSuffix"
            }
         }
      }
   }

   fun cancel() {
      NszConverter.cancel()
   }

   fun selectMode(mode: ConversionMode) {
      conversionMode = mode
   }

   fun addFilesToQueue(files: List<FileEntry>) {
      fileQueue.addAll(files)
   }

   fun removeFileFromQueue(index: Int) {
      if (index in fileQueue.indices) {
         fileQueue.removeAt(index)
      }
   }

   fun selectFolder(context: android.content.Context, uri: Uri) {
      viewModelScope.launch {
         try {
            statusMessage = "Сканирование папки..."
            statusLog.clear()

            folderLogWriter = FolderLogWriter(context)
            folderLogPath = folderLogWriter?.logFilePath

            val statusCb = object : NszConverter.StatusCallback {
               override fun onStatus(tag: String, msg: String) {
                  viewModelScope.launch(Dispatchers.Main.immediate) {
                     statusLog.add(LogEntry(tag, msg.trim()))
                  }
                  viewModelScope.launch(Dispatchers.IO) {
                     folderLogWriter?.writeLog(tag, msg.trim())
                  }
               }
            }

            val structure = FolderScanner.scanFolder(context, uri, statusCb)
            folderStructure = structure
            conversionMode = ConversionMode.FolderMode(uri, structure)
            statusMessage = null
         } catch (e: Exception) {
            statusMessage = "Ошибка сканирования: ${e.message}"
            viewModelScope.launch(Dispatchers.IO) {
               folderLogWriter?.writeLog("ERROR", "Ошибка сканирования: ${e.message}")
               folderLogWriter?.close()
            }
         }
      }
   }

   fun resetConversionState() {
      conversionMode = ConversionMode.None
      fileQueue.clear()
      folderStructure = null
      selectedUri = null
      selectedName = null
      isConverting = false
      progress = null
      batchOverallProgress = null
      batchCurrentFileName = null
      batchProcessedFiles = 0
      batchTotalFiles = 0
      folderOverallProgress = null
      folderCurrentFileProgress = null
      folderCurrentFileName = null
      folderProcessedFiles = 0
      folderTotalFiles = 0
      statusMessage = null
      isSuccess = false
      statusLog.clear()
      currentFileIndex = 0
   }

   fun startBatchConversion(context: android.content.Context) {
      if (fileQueue.isEmpty()) return

      isConverting = true
      currentFileIndex = 0
      progress = null
      batchCurrentFileName = null
      batchProcessedFiles = 0
      batchTotalFiles = fileQueue.size
      statusLog.clear()
      statusMessage = null
      isSuccess = false

      val headerKey = KeysParser.parseHeaderKey(KeysManager.keysFile(context))

      val statusCb = object : NszConverter.StatusCallback {
         override fun onStatus(tag: String, msg: String) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
               statusLog.add(LogEntry(tag, msg.trim()))
            }
         }
      }

      viewModelScope.launch {
         val fileSizes = fileQueue.map { getUriSize(context, it.uri).coerceAtLeast(0L) }
         val estimatedFileTotals = fileSizes.toMutableList()
         val totalBytes = estimatedFileTotals.sum().coerceAtLeast(0L)
         batchOverallProgress = if (fileQueue.size > 1 && totalBytes > 0L) {
            ConversionProgress(0L, totalBytes, 0.0)
         } else {
            null
         }
         var completedBytes = 0L
         var lastOverallBytes = 0L
         var lastOverallTimeMs = System.currentTimeMillis()

         for (i in fileQueue.indices) {
            currentFileIndex = i
            val file = fileQueue[i]
            val fileSize = fileSizes.getOrElse(i) { 0L }
            var currentFileTotal = estimatedFileTotals.getOrElse(i) { fileSize }
            batchCurrentFileName = file.displayName

            fileQueue[i] = file.copy(status = FileStatus.Converting)

            try {
               NszConverter.convert(context, file.uri, headerKey, statusCb)
                  .catch { e ->
                     fileQueue[i] = file.copy(status = FileStatus.Failed)
                     statusLog.add(LogEntry("ERROR", "${file.displayName}: ${e.message}"))
                  }
                  .collect { p ->
                     progress = p
                     if (batchOverallProgress != null) {
                        val reportedTotal = p.totalBytes.coerceAtLeast(0L)
                        if (reportedTotal > 0L && reportedTotal != currentFileTotal) {
                           currentFileTotal = reportedTotal
                           estimatedFileTotals[i] = reportedTotal
                        }
                        val overallTotal = estimatedFileTotals.sum().coerceAtLeast(0L)
                        val currentDone = p.doneBytes.coerceAtLeast(0L).coerceAtMost(currentFileTotal)
                        val overallDone = (completedBytes + currentDone)
                           .coerceAtMost(overallTotal)
                        val now = System.currentTimeMillis()
                        val elapsedSec = (now - lastOverallTimeMs).coerceAtLeast(1L) / 1000.0
                        val speed = if (elapsedSec > 0) {
                           (overallDone - lastOverallBytes).toDouble() / 1024 / 1024 / elapsedSec
                        } else {
                           0.0
                        }
                        lastOverallBytes = overallDone
                        lastOverallTimeMs = now
                        batchOverallProgress = ConversionProgress(
                           doneBytes = overallDone,
                           totalBytes = overallTotal,
                           speedMBps = speed
                        )
                     }
                  }

               fileQueue[i] = file.copy(status = FileStatus.Completed)

            } catch (e: Exception) {
               fileQueue[i] = file.copy(status = FileStatus.Failed)
               statusLog.add(LogEntry("ERROR", "${file.displayName}: ${e.message}"))
            } finally {
               completedBytes += currentFileTotal
               batchProcessedFiles = i + 1
               if (batchOverallProgress != null) {
                  val overallTotal = estimatedFileTotals.sum().coerceAtLeast(0L)
                  batchOverallProgress = ConversionProgress(
                     doneBytes = completedBytes.coerceAtMost(overallTotal),
                     totalBytes = overallTotal,
                     speedMBps = batchOverallProgress!!.speedMBps
                  )
               }
               batchCurrentFileName = null
               withContext(Dispatchers.IO) { TempFileManager.cleanupManagedCache(context) }
            }
         }

         isConverting = false
         val completed = fileQueue.count { it.status == FileStatus.Completed }
         statusMessage = "Обработано $completed из ${fileQueue.size} файлов"
         isSuccess = completed == fileQueue.size
      }
   }

   fun startFolderConversion(context: android.content.Context) {
      val structure = folderStructure ?: return

      isConverting = true
      statusLog.clear()
      statusMessage = null
      isSuccess = false
      progress = null
      folderOverallProgress = ConversionProgress(0L, structure.totalSize, 0.0)
      folderCurrentFileProgress = null
      folderCurrentFileName = null
      folderProcessedFiles = 0
      folderTotalFiles = countAllFiles(structure.allFiles)

      folderLogWriter = FolderLogWriter(context)
      folderLogPath = folderLogWriter?.logFilePath

      val headerKey = KeysParser.parseHeaderKey(KeysManager.keysFile(context))

      val statusCb = object : NszConverter.StatusCallback {
         override fun onStatus(tag: String, msg: String) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
               statusLog.add(LogEntry(tag, msg.trim()))
            }
            viewModelScope.launch(Dispatchers.IO) {
               folderLogWriter?.writeLog(tag, msg.trim())
            }
         }
      }

      viewModelScope.launch {
         try {
            val result = FolderProcessor.processFolder(
               context,
               structure,
               headerKey,
               { update ->
                  folderOverallProgress = update.overallProgress
                  folderCurrentFileProgress = update.currentFileProgress
                  folderCurrentFileName = update.currentFileName
                  folderProcessedFiles = update.processedFiles
                  folderTotalFiles = update.totalFiles
               },
               statusCb
            )

            isConverting = false

            withContext(Dispatchers.IO) {
               folderLogWriter?.close()
            }

            val logPathMsg = if (folderLogPath != null) "\nЛог: $folderLogPath" else ""

            result.onSuccess { (outputUri, summary) ->
               val successRate = if (summary.nszFilesProcessed > 0) {
                  (summary.successCount * 100) / summary.nszFilesProcessed
               } else 100

               isSuccess = summary.successCount > 0 && successRate >= 50

               statusMessage = buildString {
                  appendLine("Папка обработана!")
                  appendLine()
                  appendLine("Успешно: ${summary.successCount} из ${summary.nszFilesProcessed} NSZ файлов (${successRate}%)")

                  if (summary.failedCount > 0) {
                     appendLine("Ошибок: ${summary.failedCount} файл(ов)")
                     appendLine("Подробности в логе")
                  }

                  appendLine()
                  appendLine("Сохранено в Downloads")
                  append(logPathMsg)
               }
            }.onFailure { e ->
               isSuccess = false
               statusMessage = "Ошибка обработки папки: ${e.message}$logPathMsg"
            }

         } catch (e: Exception) {
            isConverting = false
            isSuccess = false

            withContext(Dispatchers.IO) {
               folderLogWriter?.writeLog("ERROR", "Критическая ошибка: ${e.message}")
               folderLogWriter?.close()
            }

            val logPathMsg = if (folderLogPath != null) "\nЛог: $folderLogPath" else ""
            statusMessage = "Ошибка: ${e.message}$logPathMsg"
         } finally {
            withContext(Dispatchers.IO) { TempFileManager.cleanupManagedCache(context) }
         }
      }
   }
}
