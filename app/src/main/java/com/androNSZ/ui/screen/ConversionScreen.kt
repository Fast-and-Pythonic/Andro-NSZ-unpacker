package com.androNSZ.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.androNSZ.R
import com.androNSZ.model.ConversionMode
import com.androNSZ.nut.KeysManager
import com.androNSZ.ui.components.CompactCenterAlignedTopAppBar
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
   // Обработка системной кнопки "назад"
   BackHandler(enabled = !vm.isConverting) {
      onBackToModeSelection()
   }

   val context = LocalContext.current
   var settingsMenuExpanded by remember { mutableStateOf(false) }

   Scaffold(
      topBar = {
         CompactCenterAlignedTopAppBar(
            title = { Text(stringResource(R.string.app_title)) },
            navigationIcon = {
               IconButton(
                  onClick = onBackToModeSelection,
                  enabled = !vm.isConverting
               ) {
                  Icon(
                     imageVector = Icons.Filled.ArrowBack,
                     contentDescription = stringResource(R.string.cd_back),
                     tint = MaterialTheme.colorScheme.onPrimary,
                  )
               }
            },
            actions = {
               Box {
                  IconButton(onClick = { settingsMenuExpanded = true }) {
                     Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.cd_settings),
                        tint = MaterialTheme.colorScheme.onPrimary,
                     )
                  }
                  DropdownMenu(
                     expanded = settingsMenuExpanded,
                     onDismissRequest = { settingsMenuExpanded = false }
                  ) {
                     DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_change_prod_keys)) },
                        onClick = {
                           settingsMenuExpanded = false
                           onInstallKeys()
                        }
                     )
                     DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_remove_prod_keys)) },
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
