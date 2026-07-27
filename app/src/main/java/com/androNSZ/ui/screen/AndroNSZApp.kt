package com.androNSZ.ui.screen

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.androNSZ.model.FileEntry
import com.androNSZ.model.PickerMode
import com.androNSZ.model.Screen
import com.androNSZ.ui.components.UpdateDialog
import com.androNSZ.viewmodel.MainViewModel
import com.androNSZ.viewmodel.halfConcurrency
import com.androNSZ.viewmodel.resolveConcurrency

@Composable
fun AndroNSZApp(vm: MainViewModel) {
   val context = LocalContext.current

   LaunchedEffect(Unit) {
      vm.checkKeys(context)
      vm.loadSettings(context)
      vm.checkForUpdates(context, manual = false)
   }

   if (vm.showUpdateDialog) {
      UpdateDialog(
         state = vm.updateState,
         onUpdate = { vm.downloadAndInstall(context) },
         onHide = { vm.hideUpdate(context) },
         onRetry = { vm.checkForUpdates(context, manual = true) },
         onDismiss = { vm.dismissUpdateDialog() }
      )
   }
   
   val keysPicker = rememberLauncherForActivityResult(
      ActivityResultContracts.OpenDocument()
   ) { uri ->
      if (uri != null) vm.installKeys(context, uri)
   }
   
   val outputFolderPicker = rememberLauncherForActivityResult(
      ActivityResultContracts.OpenDocumentTree()
   ) { uri ->
      if (uri != null) vm.saveOutputFolder(context, uri)
   }

   // Source file for the Settings speed test's optional real-file pass. SAF rather
   // than the in-app picker (A14): it is a one-off pick from a settings screen, and
   // the engine reads content:// inputs through a descriptor anyway.
   val realBenchPicker = rememberLauncherForActivityResult(
      ActivityResultContracts.OpenDocument()
   ) { uri ->
      if (uri != null) vm.startRealFileBenchmark(context, uri)
   }
   
   val screen = vm.currentScreen
   when (screen) {
      Screen.ModeSelection -> {
         ModeSelectionScreen(
            onModeSelected = { mode ->
               vm.selectMode(mode)
               vm.navigateTo(Screen.Conversion)
            },
            keysInstalled = vm.keysInstalled,
            onInstallKeys = { keysPicker.launch(arrayOf("*/*")) },
            onNavigateToAbout = { vm.navigateTo(Screen.About) },
            onNavigateToSettings = { vm.navigateTo(Screen.Settings) },
            onCheckKeys = { vm.checkKeys(context) },
            onChangeOutputFolder = { outputFolderPicker.launch(null) },
            outputFolderUri = vm.outputFolderUri,
            updateState = vm.updateState,
            updateBannerVisible = vm.updateBannerVisible,
            onCheckForUpdates = { vm.checkForUpdates(context, manual = true) },
            onShowUpdateDialog = { vm.showUpdateDialog = true }
         )
      }
      Screen.Conversion -> {
         ConversionScreen(
            vm = vm,
            onBackToModeSelection = {
               vm.navigateBack()
               vm.resetConversionState()
            },
            onInstallKeys = { keysPicker.launch(arrayOf("*/*")) },
            onNavigateToAbout = { vm.navigateTo(Screen.About) },
            onNavigateToSettings = { vm.navigateTo(Screen.Settings) },
            onChangeOutputFolder = { outputFolderPicker.launch(null) }
         )
      }
      is Screen.FilePicker -> {
         FilePickerScreen(
            mode = screen.mode,
            onConfirm = { files ->
               when (screen.mode) {
                  PickerMode.FilesOnly ->
                     vm.addFilesToQueue(files.map { FileEntry(Uri.fromFile(it), it.name, it.length()) })
                  PickerMode.FoldersOnly ->
                     files.firstOrNull()?.let { vm.selectFolderFromFile(context, it) }
                  PickerMode.FilesAndFolders ->
                     vm.setCombinedSelection(context, files)
               }
               vm.navigateBack()
            },
            onBack = { vm.navigateBack() }
         )
      }
      Screen.About -> {
         AboutScreen(
            onBack = { vm.navigateBack() }
         )
      }
      Screen.Settings -> {
         SettingsScreen(
            currentFormat = vm.statsFormat,
            onFormatChange = { vm.saveStatsFormat(context, it) },
            currentLanguage = vm.appLanguage,
            onLanguageChange = { lang ->
               vm.saveLanguage(context, lang)
               (context as Activity).recreate()
            },
            currentTheme = vm.themeMode,
            onThemeChange = { vm.saveThemeMode(context, it) },
            currentAccentMode = vm.accentMode,
            currentAccentColor = vm.accentColorArgb,
            onAccentModeChange = { vm.saveAccentMode(context, it) },
            onAccentColorChange = { vm.saveAccentColor(context, it) },
            currentVerification = vm.verificationEnabled,
            onVerificationChange = { vm.saveVerificationEnabled(context, it) },
            currentThreadMode = vm.threadMode,
            // Queue size 1 would clamp the summary to 1; the honest answer to "how
            // many will it use" is what a job with enough files gets.
            currentThreads = resolveConcurrency(
               vm.threadMode,
               vm.decompressionThreads,
               vm.calibratedThreads,
               Runtime.getRuntime().availableProcessors(),
               Runtime.getRuntime().availableProcessors()
            ),
            onOpenThreadSettings = { vm.navigateTo(Screen.ThreadSettings) },
            currentShowUpdateBanner = vm.showUpdateBanner,
            onShowUpdateBannerChange = { vm.saveShowUpdateBanner(context, it) },
            onBack = { vm.navigateBack() }
         )
      }
      Screen.ThreadSettings -> {
         ThreadSettingsScreen(
            currentThreadMode = vm.threadMode,
            onThreadModeChange = { vm.saveThreadMode(context, it) },
            currentDecompressionThreads = vm.decompressionThreads,
            maxThreads = Runtime.getRuntime().availableProcessors(),
            onDecompressionThreadsChange = { vm.saveDecompressionThreads(context, it) },
            calibratedThreads = vm.calibratedThreads,
            halfThreads = halfConcurrency(Runtime.getRuntime().availableProcessors()),
            benchRunning = vm.benchRunning,
            benchLevel = vm.benchLevel,
            benchSummary = vm.benchSummary,
            benchFailed = vm.benchFailed,
            benchEnabled = !vm.isConverting,
            onRunBenchmark = { realBenchPicker.launch(arrayOf("*/*")) },
            onCancelBenchmark = { vm.cancelBenchmark() },
            onBack = { vm.navigateBack() }
         )
      }
   }
}

