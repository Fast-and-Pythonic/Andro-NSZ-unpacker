package com.androNSZ.ui.screen

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
   }

   val keysPicker = rememberLauncherForActivityResult(
      ActivityResultContracts.OpenDocument()
   ) { uri ->
      if (uri != null) vm.installKeys(context, uri)
   }

   when (vm.currentScreen) {
      Screen.ModeSelection -> {
         ModeSelectionScreen(
            onModeSelected = { mode ->
               vm.selectMode(mode)
               vm.currentScreen = Screen.Conversion
            },
            keysInstalled = vm.keysInstalled,
            onInstallKeys = { keysPicker.launch(arrayOf("*/*")) }
         )
      }
      Screen.Conversion -> {
         ConversionScreen(
            vm = vm,
            onBackToModeSelection = {
               vm.currentScreen = Screen.ModeSelection
               vm.resetConversionState()
            },
            onInstallKeys = { keysPicker.launch(arrayOf("*/*")) }
         )
      }
   }
}
