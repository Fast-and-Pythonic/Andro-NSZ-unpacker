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
import com.androNSZ.BuildConfig
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
import com.androNSZ.util.ApkInstaller
import com.androNSZ.util.ProgressThrottler
import com.androNSZ.util.UpdateChecker
import com.androNSZ.util.getUriSize
import java.io.File
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
 * Each file occupies two CPU threads — the producer (zstd + AES decompress) and
 * the async writer (fwrite + SHA-256) — so `cores / 2` parallel files saturate
 * every core. We deliberately keep no cores reserved for system/GUI: measurement
 * showed the extra cores help throughput more than the reservation protects UI.
 *   8 cores -> 4,  6 -> 3,  <=2 -> 1.
 */
private val AUTO_CONCURRENCY: Int =
   (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)

/**
 * Resolve how many files to convert in parallel for a job. Defaults to the
 * core-adaptive [AUTO_CONCURRENCY]. An experimental override (Settings) can raise
 * it up to the full core count (one file per core), but only when verification is
 * OFF — that gate keeps the core-adaptive default whenever CNMT verification runs.
 * `override == 0` means auto.
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

   // Combined mode (files + folders together). The editable selection is the
   // source of truth for the combined UI list; it is merged into the folder-mode
   // fields above (folderStructure, folderFileEntries, folder* progress/stats)
   // since combined runs through FolderProcessor just like folder mode.
   val combinedItems = mutableStateListOf<CombinedItem>()
   // True while folders in a just-added selection are still being scanned.
   var combinedScanning by mutableStateOf(false)

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
   var statsFormat by mutableStateOf(StatsFormat.COMPACT3)
   var outputFolderUri by mutableStateOf<Uri?>(null)
   var appLanguage by mutableStateOf("system")
   var themeMode by mutableStateOf(ThemeMode.SYSTEM)
   var accentMode by mutableStateOf(AccentMode.DEFAULT)
   var accentColorArgb by mutableIntStateOf(SettingsRepository.DEFAULT_ACCENT_COLOR)
   var verificationEnabled by mutableStateOf(true)
   // GUI: compact (single-line) file card names + extension in the stats row.
   var compactCardNames by mutableStateOf(false)
   // Experimental: 0 = auto (core-adaptive), else the number of parallel decompression
   // workers to use (honored only when verification is off). See resolveConcurrency.
   var decompressionThreads by mutableIntStateOf(0)
   // Smart core distribution (core-aware scheduler + CPU affinity, A13) — a deferred
   // experiment, disabled for release. Kept false so the conversion path always takes
   // the plain Semaphore baseline; the setting is hidden from the UI.
   var smartDistribution by mutableStateOf(false)
   // Whether the main-screen "update available" banner is shown. The update check
   // itself always runs regardless (so the overflow-menu notification stays
   // accurate) — only the banner is user-disableable.
   var showUpdateBanner by mutableStateOf(true)
   // Version the user tapped "Hide" on: its banner stays hidden (a newer version
   // un-hides it). Only affects the banner, never the menu notification.
   var hiddenBannerVersion by mutableStateOf("")

   // Update check state (see model/UpdateState.kt). The dialog is gated by
   // showUpdateDialog; the menu notification keys off updateState (always), the
   // main-screen banner off updateBannerVisible.
   var updateState by mutableStateOf<UpdateState>(UpdateState.Idle)
   var showUpdateDialog by mutableStateOf(false)
   // How long to suppress the automatic check after a successful one.
   private val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

   /** True when the banner should be visible: an update exists, banner is enabled,
    *  and the user hasn't hidden this specific version. */
   val updateBannerVisible: Boolean
      get() {
         val rel = (updateState as? UpdateState.Available)?.release ?: return false
         return showUpdateBanner && rel.versionName != hiddenBannerVersion
      }

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
      compactCardNames = SettingsRepository.getInstance(context).getCompactCardNames()
      decompressionThreads = SettingsRepository.getInstance(context).getDecompressionThreads()
      // smartDistribution stays off (disabled for release) — not loaded from prefs.

      val repo = SettingsRepository.getInstance(context)
      showUpdateBanner = repo.getShowUpdateBanner()
      hiddenBannerVersion = repo.getHiddenBannerVersion()

      // Restore a previously discovered release so both the menu notification and
      // (subject to updateBannerVisible) the banner survive restarts and the
      // once-a-day check throttle. No hide filter here — the menu must always know.
      val cached = repo.getAvailableRelease()
      if (cached != null &&
         UpdateChecker.isNewer(cached.versionName, BuildConfig.VERSION_NAME)
      ) {
         updateState = UpdateState.Available(cached)
      }
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

   fun saveCompactCardNames(context: android.content.Context, enabled: Boolean) {
      compactCardNames = enabled
      SettingsRepository.getInstance(context).saveCompactCardNames(enabled)
   }

   fun saveDecompressionThreads(context: android.content.Context, count: Int) {
      decompressionThreads = count
      SettingsRepository.getInstance(context).saveDecompressionThreads(count)
   }

   fun saveSmartDistribution(context: android.content.Context, enabled: Boolean) {
      smartDistribution = enabled
      SettingsRepository.getInstance(context).saveSmartDistribution(enabled)
   }

   fun saveShowUpdateBanner(context: android.content.Context, enabled: Boolean) {
      showUpdateBanner = enabled
      SettingsRepository.getInstance(context).saveShowUpdateBanner(enabled)
   }

   /**
    * Query GitHub Releases and compare against [BuildConfig.VERSION_NAME].
    *
    * The check always runs (an automatic one only skips within
    * [UPDATE_CHECK_INTERVAL_MS] of the last), so the menu notification stays
    * accurate. A [manual] check (menu item) reports its outcome via a dialog; an
    * automatic one stays silent on "up to date"/errors and only auto-opens the
    * dialog when the banner would be visible.
    */
   fun checkForUpdates(context: android.content.Context, manual: Boolean) {
      val repo = SettingsRepository.getInstance(context)
      if (!manual) {
         val since = System.currentTimeMillis() - repo.getLastUpdateCheckMillis()
         if (since < UPDATE_CHECK_INTERVAL_MS) return
      }
      // Don't stack checks.
      if (updateState is UpdateState.Checking || updateState is UpdateState.Downloading) return

      updateState = UpdateState.Checking
      if (manual) showUpdateDialog = true

      viewModelScope.launch {
         try {
            val release = UpdateChecker.fetchLatest()
            repo.saveLastUpdateCheckMillis(System.currentTimeMillis())
            if (UpdateChecker.isNewer(release.versionName, BuildConfig.VERSION_NAME)) {
               // Cache it so the banner/menu persist across restarts.
               repo.saveAvailableRelease(release)
               updateState = UpdateState.Available(release)
               // Auto-open the dialog only when the banner would actually show.
               if (manual || updateBannerVisible) showUpdateDialog = true
            } else {
               // Up to date — drop any stale cached release so the banner/menu clear.
               repo.saveAvailableRelease(null)
               updateState = UpdateState.UpToDate
               if (!manual) showUpdateDialog = false
            }
         } catch (e: Exception) {
            updateState = UpdateState.Failed(e.message ?: "Unknown error")
            if (!manual) showUpdateDialog = false
         }
      }
   }

   /** Download the available update's APK and launch the system installer. */
   fun downloadAndInstall(context: android.content.Context) {
      val release = (updateState as? UpdateState.Available)?.release ?: return
      updateState = UpdateState.Downloading(-1f)
      viewModelScope.launch {
         try {
            val apk = File(context.cacheDir, "AndroNSZ_${release.versionName}.apk")
            UpdateChecker.downloadApk(release.apkUrl, apk) { fraction ->
               updateState = UpdateState.Downloading(fraction)
            }
            ApkInstaller.install(context, apk)
            // Keep the dialog dismissible; the system installer takes over now.
            showUpdateDialog = false
            updateState = UpdateState.Available(release)
         } catch (e: Exception) {
            updateState = UpdateState.Failed(e.message ?: "Download failed")
         }
      }
   }

   /**
    * Hide the main-screen banner for the current version. The overflow-menu
    * notification stays (updateState remains Available); a newer version un-hides
    * the banner.
    */
   fun hideUpdate(context: android.content.Context) {
      (updateState as? UpdateState.Available)?.let {
         hiddenBannerVersion = it.release.versionName
         SettingsRepository.getInstance(context).saveHiddenBannerVersion(it.release.versionName)
      }
      showUpdateDialog = false
   }

   /** Close the update dialog; a still-available update keeps its banner. */
   fun dismissUpdateDialog() {
      showUpdateDialog = false
      if (updateState is UpdateState.UpToDate || updateState is UpdateState.Failed) {
         updateState = UpdateState.Idle
      }
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
      if (format == StatsFormat.COMPACT2 || format == StatsFormat.COMPACT3) {
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
      scanFolderInto(context, uri) { statusCb ->
         FolderScanner.scanFolder(context, uri, statusCb)
      }
   }

   /**
    * Folder-mode selection from the in-app picker, where the source is a real
    * path. Walks it with [com.androNSZ.fs.RawFolderScanner] (file:// uris), so the
    * rest of the folder pipeline is unchanged.
    */
   fun selectFolderFromFile(context: android.content.Context, folder: java.io.File) {
      scanFolderInto(context, Uri.fromFile(folder)) { statusCb ->
         com.androNSZ.fs.RawFolderScanner.scanFolder(folder, statusCb)
      }
   }

   /**
    * Shared folder-scan orchestration for both SAF ([selectFolder]) and raw-path
    * ([selectFolderFromFile]) sources: sets up the log/status plumbing, runs the
    * given [scan], and stores the resulting [FolderStructure] + mode.
    */
   private inline fun scanFolderInto(
      context: android.content.Context,
      rootUri: Uri,
      crossinline scan: suspend (NszConverter.StatusCallback) -> FolderStructure
   ) {
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

            val structure = scan(statusCb)
            folderStructure = structure
            buildFolderFileEntries(structure)
            conversionMode = ConversionMode.FolderMode(rootUri, structure)
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

   /** Rebuilds [folderFileEntries] (all Pending) from every scanned file
    *  (NSZ/XCZ that get unpacked plus everything else that gets copied). */
   private fun buildFolderFileEntries(structure: FolderStructure) {
      folderFileEntries.clear()
      folderFileEntries.addAll(
         collectAllFiles(structure.allFiles).map { node ->
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
         unpackedSize = event.unpackedSize ?: e.unpackedSize,
         verify = event.verify
      )
   }

   /**
    * Combined-mode selection from the in-app picker: appends the newly marked
    * files/folders (skipping duplicates already in the list), scanning each folder
    * once via [RawFolderScanner] off the main thread, then rebuilds the merged
    * structure. Scanning folders is why this is async — [combinedScanning] flags it.
    */
   fun setCombinedSelection(context: android.content.Context, files: List<java.io.File>) {
      viewModelScope.launch {
         combinedScanning = true
         try {
            val existing = combinedItems.mapTo(mutableSetOf()) { it.file.absolutePath }
            for (f in files) {
               if (!existing.add(f.absolutePath)) continue
               val item = withContext(Dispatchers.IO) { buildCombinedItem(f) }
               combinedItems.add(item)
            }
            rebuildCombinedStructure()
         } finally {
            combinedScanning = false
         }
      }
   }

   /** Builds one selection entry: a scanned folder, or a standalone file. */
   private suspend fun buildCombinedItem(f: java.io.File): CombinedItem {
      return if (f.isDirectory) {
         val structure = com.androNSZ.fs.RawFolderScanner.scanFolder(f)
         CombinedItem(
            file = f,
            isDirectory = true,
            structure = structure,
            sizeBytes = structure.totalSize,
            nszCount = structure.nszFiles.size,
            xczCount = structure.xczFiles.size
         )
      } else {
         val name = f.name
         val isNsz = name.endsWith(".nsz", ignoreCase = true)
         val isXcz = name.endsWith(".xcz", ignoreCase = true)
         CombinedItem(
            file = f,
            isDirectory = false,
            structure = null,
            sizeBytes = f.length(),
            nszCount = if (isNsz) 1 else 0,
            xczCount = if (isXcz) 1 else 0
         )
      }
   }

   fun removeCombinedItem(index: Int) {
      if (index in combinedItems.indices) {
         combinedItems.removeAt(index)
         rebuildCombinedStructure()
      }
   }

   /**
    * Merges [combinedItems] into a single [FolderStructure] (standalone files as
    * top-level nodes, each folder as a top-level directory carrying its scanned
    * subtree) and stores it in the folder-mode fields so the shared pipeline and
    * per-file list work unchanged. The synthetic rootUri is unused (combined
    * creates no wrapper folder).
    */
   private fun rebuildCombinedStructure() {
      if (combinedItems.isEmpty()) {
         folderStructure = null
         folderFileEntries.clear()
         return
      }
      val allFiles = mutableListOf<FileNode>()
      val nsz = mutableListOf<Uri>()
      val xcz = mutableListOf<Uri>()
      var total = 0L
      for (item in combinedItems) {
         if (item.isDirectory) {
            val s = item.structure ?: continue
            allFiles.add(FileNode.Directory(item.file.name, s.allFiles))
            nsz.addAll(s.nszFiles)
            xcz.addAll(s.xczFiles)
            total += s.totalSize
         } else {
            val name = item.file.name
            val isNsz = name.endsWith(".nsz", ignoreCase = true)
            val isXcz = name.endsWith(".xcz", ignoreCase = true)
            val uri = Uri.fromFile(item.file)
            allFiles.add(FileNode.File(uri, name, isNsz, isXcz, item.sizeBytes))
            if (isNsz) nsz.add(uri) else if (isXcz) xcz.add(uri)
            total += item.sizeBytes
         }
      }
      val structure = FolderStructure(
         rootUri = Uri.EMPTY,
         nszFiles = nsz,
         xczFiles = xcz,
         allFiles = allFiles,
         totalSize = total
      )
      folderStructure = structure
      buildFolderFileEntries(structure)
   }

   fun resetConversionState() {
      conversionMode = ConversionMode.None
      fileQueue.clear()
      combinedItems.clear()
      combinedScanning = false
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
            // Captured by convert()'s onVerified below; local to this per-file
            // coroutine, so it never races with other parallel conversions. XCZ
            // stays NOT_CHECKED (XCI verification is not implemented).
            var verifyStatus = VerifyStatus.NOT_CHECKED
            try {
               // XCZ → XCI, everything else → NSZ → NSP.
               val flow = if (file.displayName.endsWith(".xcz", ignoreCase = true)) {
                  NszConverter.convertXcz(context, file.uri, verifyKey, outputFolderUri, statusCb, mask)
               } else {
                  NszConverter.convert(context, file.uri, verifyKey, outputFolderUri, statusCb, mask, onVerified = { verifyStatus = it })
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
                  unpackedSize = unpackedBytes,
                  verify = verifyStatus
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

   fun startFolderConversion(context: android.content.Context) =
      runFolderStyleConversion(context) { structure, verifyKey, concurrency, progressCallback, statusCallback, fileEventCallback ->
         FolderProcessor.processFolder(
            context, structure, verifyKey, outputFolderUri, concurrency,
            smartDistribution, progressCallback, statusCallback, fileEventCallback
         )
      }

   /**
    * Combined mode (files + folders together). Reuses the whole folder-mode
    * pipeline (state, progress, stats, logging) and only swaps in
    * [FolderProcessor.processCombined], which plants the selection directly in the
    * output base (no wrapper folder). Operates on the merged [folderStructure]
    * built by [rebuildCombinedStructure].
    */
   fun startCombinedConversion(context: android.content.Context) =
      runFolderStyleConversion(context) { structure, verifyKey, concurrency, progressCallback, statusCallback, fileEventCallback ->
         FolderProcessor.processCombined(
            context, structure, verifyKey, outputFolderUri, concurrency,
            smartDistribution, progressCallback, statusCallback, fileEventCallback
         )
      }

   /**
    * Shared orchestration for the folder-style modes (folder and combined). Both
    * operate on [folderStructure] and drive the same folder-* progress/stats
    * fields, differing only in the [process] call. Sets up verification, logging
    * and the timer, runs [process], then maps the summary into the stats UI.
    */
   private fun runFolderStyleConversion(
      context: android.content.Context,
      process: suspend (
         structure: FolderStructure,
         verifyKey: ByteArray?,
         concurrency: Int,
         progressCallback: (FolderProgressUpdate) -> Unit,
         statusCallback: NszConverter.StatusCallback,
         fileEventCallback: (FolderFileEvent) -> Unit
      ) -> Result<Pair<Uri, FolderConversionSummary>>
   ) {
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
            val result = process(
               structure,
               verifyKey,
               concurrency,
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
