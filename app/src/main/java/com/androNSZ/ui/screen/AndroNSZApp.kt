package com.androNSZ.ui.screen

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.androNSZ.model.Screen
import com.androNSZ.viewmodel.MainViewModel

@Composable
fun AndroNSZApp(vm: MainViewModel) {
   val context = LocalContext.current
   
   LaunchedEffect(Unit) {
      vm.checkKeys(context)
      vm.loadSettings(context)
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
   
   when (vm.currentScreen) {
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
            outputFolderUri = vm.outputFolderUri
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
            onBack = { vm.navigateBack() }
         )
      }
   }
}

