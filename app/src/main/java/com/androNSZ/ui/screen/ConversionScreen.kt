package com.androNSZ.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.androNSZ.model.ConversionMode
import com.androNSZ.nut.KeysManager
import com.androNSZ.ui.conversion.FolderModeUI
import com.androNSZ.ui.conversion.LegacySingleFileUI
import com.androNSZ.ui.conversion.SingleFilesUI
import com.androNSZ.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversionScreen(
   vm: MainViewModel,
   onBackToModeSelection: () -> Unit,
   onInstallKeys: () -> Unit
) {
   val context = LocalContext.current
   var settingsMenuExpanded by remember { mutableStateOf(false) }

   Scaffold(
      topBar = {
         CenterAlignedTopAppBar(
            title = { Text("AndroNSZ") },
            navigationIcon = {
               IconButton(
                  onClick = onBackToModeSelection,
                  enabled = !vm.isConverting
               ) {
                  Icon(
                     imageVector = Icons.Filled.ArrowBack,
                     contentDescription = "Back",
                     tint = MaterialTheme.colorScheme.onPrimary,
                  )
               }
            },
            actions = {
               Box {
                  IconButton(onClick = { settingsMenuExpanded = true }) {
                     Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onPrimary,
                     )
                  }
                  DropdownMenu(
                     expanded = settingsMenuExpanded,
                     onDismissRequest = { settingsMenuExpanded = false }
                  ) {
                     DropdownMenuItem(
                        text = { Text("Изменить выбранные prod.keys") },
                        onClick = {
                           settingsMenuExpanded = false
                           onInstallKeys()
                        }
                     )
                     DropdownMenuItem(
                        text = { Text("Удалить выбранные prod.keys") },
                        onClick = {
                           settingsMenuExpanded = false
                           KeysManager.deleteKeys(context)
                           vm.checkKeys(context)
                        }
                     )
                  }
               }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
               containerColor = MaterialTheme.colorScheme.primary,
               titleContentColor = MaterialTheme.colorScheme.onPrimary,
            )
         )
      }
   ) { padding ->
      when (val mode = vm.conversionMode) {
         ConversionMode.None -> {
            LegacySingleFileUI(vm, padding, onInstallKeys)
         }
         is ConversionMode.SingleFiles -> {
            SingleFilesUI(vm, padding)
         }
         is ConversionMode.FolderMode -> {
            FolderModeUI(vm, mode, padding)
         }
      }
   }
}
