package com.androNSZ.viewmodel

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androNSZ.Constants
import com.androNSZ.R
import com.androNSZ.NszConverter
import com.androNSZ.data.SettingsRepository
import com.androNSZ.fs.FolderLogWriter
import com.androNSZ.fs.FolderScanner
import com.androNSZ.fs.FolderProcessor
import com.androNSZ.fs.TempFileManager
import com.androNSZ.model.*
import com.androNSZ.nut.KeysManager
import com.androNSZ.nut.KeysParser
import com.androNSZ.util.getUriSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * How many files to convert in parallel in batch mode.
 *
 * Each file is bound by a single-core producer (zstd + AES), so running several
 * at once fills idle cores and disk bandwidth — measured ~2x throughput on an
 * 8-core/UFS device. Scaled to the core count so low-end phones (few cores /
 * slow eMMC, where concurrent streams hurt) fall back toward sequential:
 *   8 cores -> 3,  6 -> 2,  <=4 -> 1.
 * Capped at 3 because beyond that we hit the storage write ceiling.
 */
private val BATCH_CONCURRENCY: Int =
   (Runtime.getRuntime().availableProcessors() / 2 - 1).coerceIn(1, 3)

class MainViewModel : ViewModel() {

   // Navigation & Mode
   private val _screenStack = mutableStateListOf<Screen>(Screen.ModeSelection)
   val currentScreen: Screen get() = _screenStack.last()

   fun navigateTo(screen: Screen) { _screenStack.add(screen) }
   fun navigateBack() { if (_screenStack.size > 1) _screenStack.removeLast() }

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

   // Per-file progress for the files currently being converted in parallel,
   // keyed by their index in [fileQueue]. An entry exists only while a file is
   // actively converting, so the UI can draw one progress bar per active file.
   val activeFileProgress = mutableStateMapOf<Int, ConversionProgress>()

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

   // Elapsed-time tracking (for speed comparison)
   var elapsedMs by mutableStateOf(0L)
   private var timerJob: Job? = null
   private var timerStartMs = 0L

   private fun startTimer() {
      timerJob?.cancel()
      timerStartMs = System.currentTimeMillis()
      elapsedMs = 0L
      timerJob = viewModelScope.launch {
         while (isActive) {
            elapsedMs = System.currentTimeMillis() - timerStartMs
            delay(250)
         }
      }
   }

   private fun stopTimer() {
      timerJob?.cancel()
      timerJob = null
      if (timerStartMs > 0L) {
         elapsedMs = System.currentTimeMillis() - timerStartMs
      }
   }

   var statusMessage by mutableStateOf<String?>(null)
   var compact2Stats by mutableStateOf<Compact2Stats?>(null)
   var isSuccess     by mutableStateOf(false)

   private var lastFolderSummary: FolderConversionSummary? = null
   private var lastLogPathMsg: String = ""
   var keysInstalled by mutableStateOf(false)
   val statusLog = mutableStateListOf<LogEntry>()

   // Settings
   var statsFormat by mutableStateOf(StatsFormat.DETAILED)
   var outputFolderUri by mutableStateOf<Uri?>(null)
   var appLanguage by mutableStateOf("system")

   fun checkKeys(context: android.content.Context) {
      keysInstalled = KeysManager.isInstalled(context)
   }

   fun installKeys(context: android.content.Context, uri: Uri) {
      viewModelScope.launch {
         KeysManager.installFromUri(context, uri)
         keysInstalled = KeysManager.isInstalled(context)
      }
   }

   fun loadSettings(context: android.content.Context) {
      viewModelScope.launch {
         SettingsRepository.getInstance(context).statsFormatFlow.collect {
            statsFormat = it
         }
      }
      viewModelScope.launch {
         SettingsRepository.getInstance(context).outputFolderUriFlow.collect {
            outputFolderUri = it
         }
      }
      appLanguage = SettingsRepository.getInstance(context).getLanguage()
   }

   fun saveLanguage(context: android.content.Context, lang: String) {
      SettingsRepository.getInstance(context).saveLanguage(lang)
      appLanguage = lang
   }

   fun saveOutputFolder(context: android.content.Context, uri: Uri) {
      context.contentResolver.takePersistableUriPermission(
         uri,
         android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
         android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
      )
      outputFolderUri = uri
      viewModelScope.launch {
         SettingsRepository.getInstance(context).saveOutputFolderUri(uri)
      }
   }

   fun saveStatsFormat(context: android.content.Context, format: StatsFormat) {
      statsFormat = format
      reapplyStats(context, format)
      viewModelScope.launch {
         SettingsRepository.getInstance(context).saveStatsFormat(format)
      }
   }

   private fun reapplyStats(context: android.content.Context, format: StatsFormat) {
      val summary = lastFolderSummary ?: return
      if (format == StatsFormat.COMPACT2) {
         compact2Stats = buildCompact2Data(context, summary, lastLogPathMsg)
         statusMessage = null
      } else {
         compact2Stats = null
         statusMessage = when (format) {
            StatsFormat.COMPACT -> buildCompactStats(context, summary, lastLogPathMsg)
            StatsFormat.DETAILED -> buildDetailedStats(context, summary, lastLogPathMsg)
            else -> null
         }
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
      startTimer()

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
               val logSuffix = if (logPath != null) "\n${context.getString(R.string.format_debug_log, logPath)}" else ""
               statusMessage = when (e) {
                  is CancelledException     -> context.getString(R.string.status_cancelled) + logSuffix
                  is NszConversionException -> context.getString(R.string.error_general, e.message ?: "") + logSuffix
                  else                      -> context.getString(R.string.error_unexpected, e.message ?: "") + logSuffix
               }
            }
            .collect { p ->
               progress = p
            }
         if (isConverting) {
            isConverting = false
            withContext(Dispatchers.IO) { TempFileManager.cleanupManagedCache(context) }
            val logPath      = NszConverter.lastDebugLogPath
            val logSuffix    = if (logPath != null) "\n${context.getString(R.string.format_debug_log, logPath)}" else ""
            val verifyError   = NszConverter.lastVerifyError
            val verifySkipped = NszConverter.lastVerifySkipped

            isSuccess     = true
            statusMessage = when {
               verifyError != null ->
                  context.getString(R.string.error_conversion_verify_failed, verifyError) + logSuffix
                     .also { isSuccess = false }
               verifySkipped ->
                  context.getString(R.string.result_done_no_verify) + logSuffix
               else ->
                  context.getString(R.string.result_done_verified) + logSuffix
            }
         }
         stopTimer()
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
            statusMessage = context.getString(R.string.status_scanning_folder)
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
            statusMessage = context.getString(R.string.error_scan_failed, e.message ?: "")
            viewModelScope.launch(Dispatchers.IO) {
               folderLogWriter?.writeLog("ERROR", context.getString(R.string.error_scan_failed, e.message ?: ""))
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
      stopTimer()
      elapsedMs = 0L
      progress = null
      activeFileProgress.clear()
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
      compact2Stats = null
      isSuccess = false
      lastFolderSummary = null
      lastLogPathMsg = ""
      statusLog.clear()
      currentFileIndex = 0
   }

   fun startBatchConversion(context: android.content.Context) {
      if (fileQueue.isEmpty()) return

      isConverting = true
      currentFileIndex = 0
      progress = null
      activeFileProgress.clear()
      batchCurrentFileName = null
      batchProcessedFiles = 0
      batchTotalFiles = fileQueue.size
      statusLog.clear()
      statusMessage = null
      isSuccess = false
      startTimer()

      val headerKey = KeysParser.parseHeaderKey(KeysManager.keysFile(context))

      val statusCb = object : NszConverter.StatusCallback {
         override fun onStatus(tag: String, msg: String) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
               statusLog.add(LogEntry(tag, msg.trim()))

               // Instant file name update from C++ code (for internal NCA files)
               when (tag) {
                  "EXISTS", "FILE_START" -> {
                     val internalFileName = msg.trim()
                     if (internalFileName.isNotEmpty()) {
                        batchCurrentFileName = internalFileName
                     }
                  }
               }
            }
         }
      }

      viewModelScope.launch {
         val fileSizes = fileQueue.map { getUriSize(context, it.uri).coerceAtLeast(0L) }
         val fileTotals = fileSizes.toLongArray()
         val totalBytes = fileTotals.sum().coerceAtLeast(0L)
         batchOverallProgress = if (fileQueue.size > 1 && totalBytes > 0L) {
            ConversionProgress(0L, totalBytes, 0.0)
         } else {
            null
         }

         // EXPERIMENT: process up to BATCH_CONCURRENCY files at once. The native
         // work inside convert() runs on Dispatchers.IO, so concurrent flows run
         // on separate threads; we collect on Main to keep state writes safe.
         val perFileDone = LongArray(fileQueue.size)
         val processed = AtomicInteger(0)
         val sem = Semaphore(BATCH_CONCURRENCY)
         var lastEmitMs = System.currentTimeMillis()
         var lastBytes = 0L
         var lastSpeedTimeMs = System.currentTimeMillis()

         fun emitOverall() {
            if (batchOverallProgress == null) return
            val now = System.currentTimeMillis()
            if (now - lastEmitMs < Constants.PROGRESS_BAR_UPDATE_INTERVAL_MS) return
            // fileTotals[i] starts as the compressed input size but is replaced by
            // the (larger) uncompressed total once a file's progress arrives. The
            // denominator must track the same units as perFileDone, otherwise the
            // bar fills to 100% before every file is unpacked.
            val total = fileTotals.sum().coerceAtLeast(1L)
            val done = perFileDone.sum().coerceAtMost(total)
            val dt = (now - lastSpeedTimeMs).coerceAtLeast(1L) / 1000.0
            val speed = (done - lastBytes).toDouble() / 1024 / 1024 / dt
            lastBytes = done; lastSpeedTimeMs = now; lastEmitMs = now
            batchOverallProgress = ConversionProgress(done, total, speed)
         }

         coroutineScope {
            fileQueue.indices.map { i ->
               async {
                  sem.withPermit {
                     val file = fileQueue[i]
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
                              activeFileProgress[i] = p
                              val t = p.totalBytes.coerceAtLeast(0L)
                              if (t > 0L) fileTotals[i] = t
                              perFileDone[i] = p.doneBytes.coerceAtLeast(0L).coerceAtMost(fileTotals[i])
                              emitOverall()
                           }
                        fileQueue[i] = file.copy(status = FileStatus.Completed)
                     } catch (e: Exception) {
                        fileQueue[i] = file.copy(status = FileStatus.Failed)
                        statusLog.add(LogEntry("ERROR", "${file.displayName}: ${e.message}"))
                     } finally {
                        activeFileProgress.remove(i)
                        perFileDone[i] = fileTotals[i]
                        batchProcessedFiles = processed.incrementAndGet()
                     }
                  }
               }
            }.awaitAll()
         }

         // All files are unpacked: pin the overall bar to 100% (throttling can
         // otherwise leave the last emit a hair below full).
         if (batchOverallProgress != null) {
            val finalTotal = fileTotals.sum().coerceAtLeast(1L)
            batchOverallProgress = ConversionProgress(finalTotal, finalTotal, 0.0)
         }

         batchCurrentFileName = null
         withContext(Dispatchers.IO) { TempFileManager.cleanupManagedCache(context) }

         isConverting = false
         stopTimer()
         val completed = fileQueue.count { it.status == FileStatus.Completed }
         statusMessage = context.getString(R.string.format_files_completed, completed, fileQueue.size)
         isSuccess = completed == fileQueue.size
      }
   }

   fun startFolderConversion(context: android.content.Context) {
      val structure = folderStructure ?: return

      isConverting = true
      statusLog.clear()
      statusMessage = null
      compact2Stats = null
      isSuccess = false
      progress = null
      folderOverallProgress = ConversionProgress(0L, structure.totalSize, 0.0)
      folderCurrentFileProgress = null
      folderCurrentFileName = null
      folderProcessedFiles = 0
      folderTotalFiles = countAllFiles(structure.allFiles)
      startTimer()

      folderLogWriter = FolderLogWriter(context)
      folderLogPath = folderLogWriter?.logFilePath

      val headerKey = KeysParser.parseHeaderKey(KeysManager.keysFile(context))

      val statusCb = object : NszConverter.StatusCallback {
         override fun onStatus(tag: String, msg: String) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
               statusLog.add(LogEntry(tag, msg.trim()))
               
               // Instant file name update from C++ code
               when (tag) {
                  "EXISTS", "FILE_START" -> {
                     folderCurrentFileName = msg.trim()
                  }
               }
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
               outputFolderUri,
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

            val logPathMsg = if (folderLogPath != null) "\n${context.getString(R.string.format_log_path, folderLogPath!!)}" else ""

            result.onSuccess { (outputUri, summary) ->
               val successRate = if (summary.totalFiles > 0) {
                  (summary.successCount * 100) / summary.totalFiles
               } else 100

               isSuccess = summary.successCount > 0 && successRate >= 50
               lastFolderSummary = summary
               lastLogPathMsg = logPathMsg

               statusMessage = when (statsFormat) {
                  StatsFormat.COMPACT -> buildCompactStats(context, summary, logPathMsg)
                  StatsFormat.COMPACT2, StatsFormat.COMPACT3 -> {
                     compact2Stats = buildCompact2Data(context, summary, logPathMsg)
                     null
                  }
                  StatsFormat.DETAILED -> buildDetailedStats(context, summary, logPathMsg)
               }
            }.onFailure { e ->
               isSuccess = false
               statusMessage = context.getString(R.string.error_folder_processing, e.message ?: "") + logPathMsg
            }

         } catch (e: Exception) {
            isConverting = false
            isSuccess = false

            withContext(Dispatchers.IO) {
               folderLogWriter?.writeLog("ERROR", context.getString(R.string.error_critical, e.message ?: ""))
               folderLogWriter?.close()
            }

            val logPathMsg = if (folderLogPath != null) "\n${context.getString(R.string.format_log_path, folderLogPath!!)}" else ""
            statusMessage = context.getString(R.string.error_general, e.message ?: "") + logPathMsg
         } finally {
            stopTimer()
            withContext(Dispatchers.IO) { TempFileManager.cleanupManagedCache(context) }
         }
      }
   }

   private fun buildCompact2Data(
      context: android.content.Context,
      summary: FolderConversionSummary,
      logPathMsg: String
   ): Compact2Stats {
      val rows = mutableListOf<Compact2Row>()
      rows += Compact2Row(
         label = context.getString(R.string.stats_c2_total),
         success = summary.successCount,
         failed = summary.failedCount,
         total = summary.totalFiles
      )
      if (summary.nszFilesProcessed > 0)
         rows += Compact2Row("NSZ", summary.nszSuccessCount, summary.nszFailedCount, summary.nszFilesProcessed)
      if (summary.xczFilesProcessed > 0)
         rows += Compact2Row("XCZ", summary.xczSuccessCount, summary.xczFailedCount, summary.xczFilesProcessed)
      if (summary.copyFilesProcessed > 0)
         rows += Compact2Row(
            label = context.getString(R.string.stats_c2_copied),
            success = summary.copySuccessCount,
            failed = summary.copyFailedCount,
            total = summary.copyFilesProcessed
         )
      return Compact2Stats(
         headerLine = context.getString(R.string.label_folder_processed),
         rows = rows,
         footerLine = context.getString(R.string.msg_saved_to_downloads) + logPathMsg
      )
   }

   private fun buildCompactStats(
      context: android.content.Context,
      summary: FolderConversionSummary,
      logPathMsg: String
   ): String = buildString {
      appendLine(context.getString(R.string.label_folder_processed))
      appendLine()
      appendLine(context.getString(R.string.format_files_completed, summary.successCount, summary.totalFiles))
      if (summary.failedCount > 0) {
         appendLine(context.getString(R.string.format_failed_count, summary.failedCount))
         appendLine(context.getString(R.string.msg_see_log_details))
      }
      appendLine()
      appendLine(context.getString(R.string.msg_saved_to_downloads))
      append(logPathMsg)
   }

   private fun buildDetailedStats(
      context: android.content.Context,
      summary: FolderConversionSummary,
      logPathMsg: String
   ): String = buildString {
      appendLine(context.getString(R.string.label_folder_processed))
      appendLine()
      
      // === ДЕТАЛЬНАЯ СТАТИСТИКА ===
      appendLine(context.getString(R.string.stats_title))
      appendLine("─".repeat(40))
      appendLine()
      
      // 1. Файлов всего
      appendLine(context.getString(R.string.stats_all_files))
      appendLine(context.getString(
         R.string.stats_success, 
         summary.successCount, 
         summary.totalFiles
      ))
      appendLine(context.getString(
         R.string.stats_failed,
         summary.failedCount,
         summary.totalFiles
      ))
      appendLine()
      
      // 2. NSZ файлов (если есть)
      if (summary.nszFilesProcessed > 0) {
         appendLine(context.getString(R.string.stats_nsz_conversion))
         appendLine(context.getString(
            R.string.stats_success, 
            summary.nszSuccessCount, 
            summary.nszFilesProcessed
         ))
         appendLine(context.getString(
            R.string.stats_failed,
            summary.nszFailedCount,
            summary.nszFilesProcessed
         ))
         appendLine()
      }
      
      // 3. XCZ файлов (если есть)
      if (summary.xczFilesProcessed > 0) {
         appendLine(context.getString(R.string.stats_xcz_conversion))
         appendLine(context.getString(
            R.string.stats_success, 
            summary.xczSuccessCount, 
            summary.xczFilesProcessed
         ))
         appendLine(context.getString(
            R.string.stats_failed,
            summary.xczFailedCount,
            summary.xczFilesProcessed
         ))
         appendLine()
      }
      
      // 4. Скопированных файлов (если есть)
      if (summary.copyFilesProcessed > 0) {
         appendLine(context.getString(R.string.stats_files_copied))
         appendLine(context.getString(
            R.string.stats_success, 
            summary.copySuccessCount, 
            summary.copyFilesProcessed
         ))
         appendLine(context.getString(
            R.string.stats_failed,
            summary.copyFailedCount,
            summary.copyFilesProcessed
         ))
         appendLine()
      }
      
      appendLine("─".repeat(40))
      // === КОНЕЦ ДЕТАЛЬНОЙ СТАТИСТИКИ ===

      if (summary.failedCount > 0) {
         appendLine(context.getString(R.string.format_failed_count, summary.failedCount))
         appendLine(context.getString(R.string.msg_see_log_details))
      }

      appendLine()
      appendLine(context.getString(R.string.msg_saved_to_downloads))
      append(logPathMsg)
   }
}
