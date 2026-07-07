package com.androNSZ.viewmodel

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androNSZ.R
import com.androNSZ.NszConverter
import com.androNSZ.data.SettingsRepository
import com.androNSZ.fs.CoreScheduler
import com.androNSZ.fs.FolderLogWriter
import com.androNSZ.fs.FolderScanner
import com.androNSZ.fs.FolderProcessor
import com.androNSZ.fs.TempFileManager
import com.androNSZ.util.CpuTopology
import com.androNSZ.model.*
import com.androNSZ.nut.KeysManager
import com.androNSZ.nut.KeysParser
import com.androNSZ.util.ProgressThrottler
import com.androNSZ.util.getUriSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
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
private val AUTO_CONCURRENCY: Int =
   (Runtime.getRuntime().availableProcessors() / 2 - 1).coerceIn(1, 3)

/**
 * Resolve how many files to convert in parallel for a job. Defaults to the
 * core-adaptive [AUTO_CONCURRENCY]. An experimental override (Settings) can raise
 * it up to the full core count, but only when verification is OFF — that gate keeps
 * the safe 1..3 default whenever CNMT verification runs, and scopes the "use all
 * cores" experiment to the case it was meant for. `override == 0` means auto.
 */
fun resolveConcurrency(verificationEnabled: Boolean, override: Int): Int {
   if (verificationEnabled) return AUTO_CONCURRENCY
   val maxCores = Runtime.getRuntime().availableProcessors()
   return if (override in 1..maxCores) override else AUTO_CONCURRENCY
}

class MainViewModel : ViewModel() {

   // Navigation & Mode
   private val _screenStack = mutableStateListOf<Screen>(Screen.ModeSelection)
   val currentScreen: Screen get() = _screenStack.last()

   fun navigateTo(screen: Screen) { _screenStack.add(screen) }
   fun navigateBack() { if (_screenStack.size > 1) _screenStack.removeAt(_screenStack.lastIndex) }

   var conversionMode by mutableStateOf<ConversionMode>(ConversionMode.None)

   // Batch files mode
   val fileQueue = mutableStateListOf<FileEntry>()
   var currentFileIndex by mutableIntStateOf(0)
   var batchOverallProgress by mutableStateOf<ConversionProgress?>(null)
   var batchCurrentFileName by mutableStateOf<String?>(null)
   var batchProcessedFiles by mutableIntStateOf(0)
   var batchTotalFiles by mutableIntStateOf(0)
   // Average unpack speed across all completed files, set once the batch finishes.
   var batchAverageSpeedMBps by mutableStateOf<Double?>(null)

   // Per-file progress for the files currently being converted in parallel,
   // keyed by their index in [fileQueue]. An entry exists only while a file is
   // actively converting, so the UI can draw one progress bar per active file.
   val activeFileProgress = mutableStateMapOf<Int, ConversionProgress>()

   // Folder mode
   var folderStructure by mutableStateOf<FolderStructure?>(null)
   // The NSZ/XCZ files that will be unpacked, as a queue mirroring batch mode:
   // each entry carries size + live status + final per-file stats, so the
   // "files to unpack" list can show the same details as single-files mode.
   val folderFileEntries = mutableStateListOf<FileEntry>()
   private var folderLogWriter: FolderLogWriter? = null
   var folderLogPath by mutableStateOf<String?>(null)
   var folderOverallProgress by mutableStateOf<ConversionProgress?>(null)
   // One entry per file currently converting in parallel (drives the per-file
   // bars, mirroring [activeFileProgress] in batch mode).
   var folderActiveFiles by mutableStateOf<List<ActiveFolderFile>>(emptyList())
   var folderProcessedFiles by mutableIntStateOf(0)
   var folderTotalFiles by mutableIntStateOf(0)
   // Average unpack speed across the whole run, set once the folder finishes.
   var folderAverageSpeedMBps by mutableStateOf<Double?>(null)

   // Conversion state
   var isConverting  by mutableStateOf(false)
   var progress      by mutableStateOf<ConversionProgress?>(null)

   // Elapsed-time tracking (for speed comparison)
   var elapsedMs by mutableLongStateOf(0L)
   private var timerJob: Job? = null
   private var timerStartMs = 0L

   private fun startTimer() {
      timerJob?.cancel()
      timerStartMs = System.currentTimeMillis()
      elapsedMs = 0L
      timerJob = viewModelScope.launch {
         while (isActive) {
            elapsedMs = System.currentTimeMillis() - timerStartMs
            delay(250.milliseconds)
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
   var themeMode by mutableStateOf(ThemeMode.SYSTEM)
   var accentMode by mutableStateOf(AccentMode.SYSTEM)
   var accentColorArgb by mutableIntStateOf(SettingsRepository.DEFAULT_ACCENT_COLOR)
   var verificationEnabled by mutableStateOf(true)
   // Experimental: 0 = auto (core-adaptive), else the number of parallel decompression
   // workers to use (honored only when verification is off). See resolveConcurrency.
   var decompressionThreads by mutableIntStateOf(0)
   // Smart load distribution: dispatch the largest files first (LPT). See loadSettings.
   var smartDistribution by mutableStateOf(true)

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
      themeMode = SettingsRepository.getInstance(context).getThemeMode()
      accentMode = SettingsRepository.getInstance(context).getAccentMode()
      accentColorArgb = SettingsRepository.getInstance(context).getAccentColor()
      verificationEnabled = SettingsRepository.getInstance(context).getVerificationEnabled()
      decompressionThreads = SettingsRepository.getInstance(context).getDecompressionThreads()
      smartDistribution = SettingsRepository.getInstance(context).getSmartDistribution()
   }

   fun saveLanguage(context: android.content.Context, lang: String) {
      SettingsRepository.getInstance(context).saveLanguage(lang)
      appLanguage = lang
   }

   fun saveThemeMode(context: android.content.Context, mode: ThemeMode) {
      themeMode = mode
      SettingsRepository.getInstance(context).saveThemeMode(mode)
   }

   fun saveAccentMode(context: android.content.Context, mode: AccentMode) {
      accentMode = mode
      SettingsRepository.getInstance(context).saveAccentMode(mode)
   }

   fun saveVerificationEnabled(context: android.content.Context, enabled: Boolean) {
      verificationEnabled = enabled
      SettingsRepository.getInstance(context).saveVerificationEnabled(enabled)
   }

   fun saveDecompressionThreads(context: android.content.Context, count: Int) {
      decompressionThreads = count
      SettingsRepository.getInstance(context).saveDecompressionThreads(count)
   }

   fun saveSmartDistribution(context: android.content.Context, enabled: Boolean) {
      smartDistribution = enabled
      SettingsRepository.getInstance(context).saveSmartDistribution(enabled)
   }

   fun saveAccentColor(context: android.content.Context, colorArgb: Int) {
      // Picking a color implies switching to the custom accent.
      accentColorArgb = colorArgb
      accentMode = AccentMode.CUSTOM
      SettingsRepository.getInstance(context).apply {
         saveAccentColor(colorArgb)
         saveAccentMode(AccentMode.CUSTOM)
      }
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
            buildFolderFileEntries(structure)
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

   /** Rebuilds [folderFileEntries] (all Pending) from the scanned NSZ/XCZ files. */
   private fun buildFolderFileEntries(structure: FolderStructure) {
      folderFileEntries.clear()
      folderFileEntries.addAll(
         collectCompressedFiles(structure.allFiles).map { node ->
            FileEntry(node.uri, node.name, node.sizeBytes)
         }
      )
   }

   /** Applies a per-file lifecycle event from the folder processor to its entry. */
   private fun applyFolderFileEvent(event: FolderFileEvent) {
      val idx = folderFileEntries.indexOfFirst { it.uri == event.sourceUri }
      if (idx < 0) return
      val e = folderFileEntries[idx]
      folderFileEntries[idx] = e.copy(
         status = event.status,
         unpackDurationMs = event.durationMs ?: e.unpackDurationMs,
         unpackSpeedMBps = event.speedMBps ?: e.unpackSpeedMBps,
         unpackedSize = event.unpackedSize ?: e.unpackedSize
      )
   }

   fun resetConversionState() {
      conversionMode = ConversionMode.None
      fileQueue.clear()
      folderStructure = null
      folderFileEntries.clear()
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
      folderActiveFiles = emptyList()
      folderProcessedFiles = 0
      folderTotalFiles = 0
      folderAverageSpeedMBps = null
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
      batchAverageSpeedMBps = null
      statusLog.clear()
      statusMessage = null
      isSuccess = false
      startTimer()

      val headerKey = KeysParser.parseHeaderKey(KeysManager.keysFile(context))
      val keyAreaKeys = KeysParser.parseKeyAreaKeys(KeysManager.keysFile(context))
      // Configure CNMT verification once for the whole batch (read-only in native
      // code while files convert). When disabled, skip the post-conversion check too.
      NszConverter.nativeSetVerification(verificationEnabled, headerKey, keyAreaKeys)
      val verifyKey = if (verificationEnabled) headerKey else null

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

         // The native work inside convert() runs on Dispatchers.IO, so concurrent
         // flows run on separate threads; we collect on Main to keep state writes safe.
         val concurrency = resolveConcurrency(verificationEnabled, decompressionThreads)
         statusCb.onStatus(
            "INFO",
            "Parallelism: $concurrency (verification ${if (verificationEnabled) "on" else "off"})"
         )
         val perFileDone = LongArray(fileQueue.size)
         val processed = AtomicInteger(0)
         val overallThrottler = ProgressThrottler()

         fun emitOverall() {
            if (batchOverallProgress == null) return
            // fileTotals[i] starts as the compressed input size but is replaced by
            // the (larger) uncompressed total once a file's progress arrives. The
            // denominator must track the same units as perFileDone, otherwise the
            // bar fills to 100% before every file is unpacked.
            val total = fileTotals.sum().coerceAtLeast(1L)
            val done = perFileDone.sum().coerceAtMost(total)
            overallThrottler.sample(done, total)?.let { batchOverallProgress = it }
         }

         // One file's conversion, shared by the core-aware scheduler and the
         // baseline path. [mask] pins the native decompress to a CPU cluster
         // (null = no pinning).
         suspend fun convertOne(i: Int, mask: Long?) {
            val file = fileQueue[i]
            batchCurrentFileName = file.displayName
            fileQueue[i] = file.copy(
               status = FileStatus.Converting,
               unpackDurationMs = null,
               unpackSpeedMBps = null,
               unpackedSize = null
            )
            val fileStartMs = System.currentTimeMillis()
            try {
               // XCZ → XCI, everything else → NSZ → NSP.
               val flow = if (file.displayName.endsWith(".xcz", ignoreCase = true)) {
                  NszConverter.convertXcz(context, file.uri, verifyKey, outputFolderUri, statusCb, mask)
               } else {
                  NszConverter.convert(context, file.uri, verifyKey, outputFolderUri, statusCb, mask)
               }
               // NB: no .catch here — a failure must propagate to the surrounding
               // try/catch so the file stays Failed. Swallowing it with .catch lets
               // the flow complete "normally", and the code below would then
               // overwrite the status with Completed (green "Done" for a failure).
               flow
                  .collect { p ->
                     progress = p
                     activeFileProgress[i] = p
                     val t = p.totalBytes.coerceAtLeast(0L)
                     if (t > 0L) fileTotals[i] = t
                     perFileDone[i] = p.doneBytes.coerceAtLeast(0L).coerceAtMost(fileTotals[i])
                     emitOverall()
                  }
               val fileDurationMs = (System.currentTimeMillis() - fileStartMs).coerceAtLeast(1L)
               val unpackedBytes = fileTotals[i].coerceAtLeast(0L)
               val fileSpeedMBps = unpackedBytes / 1024.0 / 1024.0 / (fileDurationMs / 1000.0)
               fileQueue[i] = file.copy(
                  status = FileStatus.Completed,
                  unpackDurationMs = fileDurationMs,
                  unpackSpeedMBps = fileSpeedMBps,
                  unpackedSize = unpackedBytes
               )
            } catch (e: Exception) {
               fileQueue[i] = file.copy(status = FileStatus.Failed)
               statusLog.add(LogEntry("ERROR", "${file.displayName}: ${e.message}"))
            } finally {
               activeFileProgress.remove(i)
               perFileDone[i] = fileTotals[i]
               batchProcessedFiles = processed.incrementAndGet()
            }
         }

         // Core-aware scheduling only helps on a heterogeneous CPU with working
         // affinity; otherwise it degrades to order-only, which measured *worse*
         // than the baseline — so fall back to natural order in that case.
         val topology = CpuTopology.detect()
         val coreAware = smartDistribution && topology.isHeterogeneous &&
            NszConverter.affinitySupported()
         if (coreAware) {
            val coreSpecs = topology.cores.sortedByDescending { it.capacity }
               .take(concurrency)
               .map { CoreScheduler.CoreSpec(it.id, topology.speedOf(it.id), topology.clusterMask(it.id)) }
            statusCb.onStatus("INFO", "Load distribution: core-aware (affinity on)")
            statusCb.onStatus("INFO", "Cores: " +
               coreSpecs.joinToString { "cpu${it.coreId}×%.2f".format(it.speed) })
            coroutineScope {
               CoreScheduler.run(fileQueue.indices.toList(), { fileSizes[it] }, coreSpecs) { i, mask ->
                  convertOne(i, mask)
               }
            }
         } else {
            if (smartDistribution) {
               statusCb.onStatus("INFO", "Load distribution: baseline (no heterogeneity/affinity)")
            }
            val sem = Semaphore(concurrency)
            coroutineScope {
               fileQueue.indices.map { i ->
                  async { sem.withPermit { convertOne(i, null) } }
               }.awaitAll()
            }
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
         // Overall average speed = total unpacked bytes of completed files over the
         // whole elapsed time (includes any parallelism overlap, so it reflects the
         // real wall-clock throughput).
         val completedBytes = fileQueue.indices
            .filter { fileQueue[it].status == FileStatus.Completed }
            .sumOf { fileTotals[it].coerceAtLeast(0L) }
         val elapsedSec = elapsedMs.coerceAtLeast(1L) / 1000.0
         batchAverageSpeedMBps = if (completedBytes > 0L) {
            completedBytes / 1024.0 / 1024.0 / elapsedSec
         } else {
            null
         }
         val completed = fileQueue.count { it.status == FileStatus.Completed }
         val failed = fileQueue.count { it.status == FileStatus.Failed }
         statusMessage = buildString {
            append(context.getString(R.string.format_files_completed, completed, fileQueue.size))
            if (failed > 0) {
               append("\n")
               append(context.getString(R.string.format_failed_count, failed))
               append("\n")
               append(context.getString(R.string.msg_see_log_details))
            }
         }
         isSuccess = failed == 0 && completed == fileQueue.size
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
      folderActiveFiles = emptyList()
      folderProcessedFiles = 0
      folderTotalFiles = countAllFiles(structure.allFiles)
      folderAverageSpeedMBps = null
      // Reset the per-file list to Pending so a re-run clears previous stats.
      buildFolderFileEntries(structure)
      startTimer()

      folderLogWriter = FolderLogWriter(context)
      folderLogPath = folderLogWriter?.logFilePath

      val headerKey = KeysParser.parseHeaderKey(KeysManager.keysFile(context))
      val keyAreaKeys = KeysParser.parseKeyAreaKeys(KeysManager.keysFile(context))
      // Configure CNMT verification once for the whole run (see startBatchConversion).
      NszConverter.nativeSetVerification(verificationEnabled, headerKey, keyAreaKeys)
      val verifyKey = if (verificationEnabled) headerKey else null

      val statusCb = object : NszConverter.StatusCallback {
         override fun onStatus(tag: String, msg: String) {
            // The internal NCA file name (EXISTS/FILE_START) is no longer surfaced
            // here: with several files converting in parallel a single "current
            // file" name is ambiguous. Per-file names come from FolderProgressUpdate.
            viewModelScope.launch(Dispatchers.Main.immediate) {
               statusLog.add(LogEntry(tag, msg.trim()))
            }
            viewModelScope.launch(Dispatchers.IO) {
               folderLogWriter?.writeLog(tag, msg.trim())
            }
         }
      }

      val concurrency = resolveConcurrency(verificationEnabled, decompressionThreads)

      viewModelScope.launch {
         try {
            val result = FolderProcessor.processFolder(
               context,
               structure,
               verifyKey,
               outputFolderUri,
               concurrency,
               smartDistribution,
               { update ->
                  folderOverallProgress = update.overallProgress
                  folderActiveFiles = update.activeFiles
                  folderProcessedFiles = update.processedFiles
                  folderTotalFiles = update.totalFiles
               },
               statusCb,
               { event -> applyFolderFileEvent(event) }
            )

            isConverting = false
            folderActiveFiles = emptyList()

            withContext(Dispatchers.IO) {
               folderLogWriter?.close()
            }

            val logPathMsg = if (folderLogPath != null) "\n${context.getString(R.string.format_log_path, folderLogPath!!)}" else ""

            result.onSuccess { (_, summary) ->
               val successRate = if (summary.totalFiles > 0) {
                  (summary.successCount * 100) / summary.totalFiles
               } else 100

               isSuccess = summary.successCount > 0 && successRate >= 50
               lastFolderSummary = summary
               lastLogPathMsg = logPathMsg

               // Wall-clock average: total processed bytes over the whole run
               // (includes parallel overlap, so it reflects real throughput).
               val elapsedSec = summary.totalDurationMs.coerceAtLeast(1L) / 1000.0
               folderAverageSpeedMBps = if (summary.totalBytesProcessed > 0L) {
                  summary.totalBytesProcessed / 1024.0 / 1024.0 / elapsedSec
               } else null

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
            folderActiveFiles = emptyList()
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
