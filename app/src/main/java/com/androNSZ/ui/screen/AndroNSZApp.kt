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
            currentDecompressionThreads = vm.decompressionThreads,
            maxThreads = Runtime.getRuntime().availableProcessors(),
            onDecompressionThreadsChange = { vm.saveDecompressionThreads(context, it) },
            currentShowUpdateBanner = vm.showUpdateBanner,
            onShowUpdateBannerChange = { vm.saveShowUpdateBanner(context, it) },
            onBack = { vm.navigateBack() }
         )
      }
   }
}

