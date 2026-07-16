package com.androNSZ.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.androNSZ.R
import com.androNSZ.model.ConversionMode
import com.androNSZ.nut.KeysManager
import com.androNSZ.util.toDisplayPath
import com.androNSZ.ui.components.AppDropdownMenuItem
import com.androNSZ.ui.components.CompactCenterAlignedTopAppBar
import com.androNSZ.ui.conversion.CombinedModeUI
import com.androNSZ.ui.conversion.FolderModeUI
import com.androNSZ.ui.conversion.SingleFilesUI
import com.androNSZ.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversionScreen(
   vm: MainViewModel,
   onBackToModeSelection: () -> Unit,
   onInstallKeys: () -> Unit,
   onNavigateToAbout: () -> Unit,
   onNavigateToSettings: () -> Unit,
   onChangeOutputFolder: () -> Unit
) {
   // Обработка системной кнопки "назад"
   BackHandler(enabled = !vm.isConverting) {
      onBackToModeSelection()
   }

   val context = LocalContext.current
   var settingsMenuExpanded by remember { mutableStateOf(false) }
   var guiSettingsDialog by remember { mutableStateOf(false) }

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
                     onDismissRequest = { settingsMenuExpanded = false },
                     shape = RoundedCornerShape(14.dp)
                  ) {
                     AppDropdownMenuItem(
                        onClick = {
                           settingsMenuExpanded = false
                           onInstallKeys()
                        },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) }
                     ) {
                        Text(stringResource(R.string.action_change_prod_keys))
                     }
                     AppDropdownMenuItem(
                        onClick = {
                           settingsMenuExpanded = false
                           KeysManager.deleteKeys(context)
                           vm.checkKeys(context)
                        },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        destructive = true
                     ) {
                        Text(stringResource(R.string.action_remove_prod_keys))
                     }
                     HorizontalDivider()
                     AppDropdownMenuItem(
                        // "Output folder" is intentionally NOT translated — keep as-is for all languages
                        onClick = {
                           settingsMenuExpanded = false
                           onChangeOutputFolder()
                        },
                        leadingIcon = {
                           Icon(imageVector = Icons.Filled.FolderOpen, contentDescription = null)
                        }
                     ) {
                        Column {
                           Text("Output folder")
                           Text(
                              text = vm.outputFolderUri?.toDisplayPath() ?: "Downloads",
                              style = MaterialTheme.typography.labelSmall,
                              color = MaterialTheme.colorScheme.onSurfaceVariant
                           )
                        }
                     }
                     HorizontalDivider()
                     AppDropdownMenuItem(
                        onClick = {
                           settingsMenuExpanded = false
                           guiSettingsDialog = true
                        },
                        leadingIcon = {
                           Icon(imageVector = Icons.Filled.Tune, contentDescription = null)
                        }
                     ) {
                        Text(stringResource(R.string.action_gui_settings))
                     }
                     AppDropdownMenuItem(
                        onClick = {
                           settingsMenuExpanded = false
                           onNavigateToSettings()
                        },
                        leadingIcon = {
                           Icon(imageVector = Icons.Filled.Settings, contentDescription = null)
                        }
                     ) {
                        Text(stringResource(R.string.action_settings))
                     }
                     AppDropdownMenuItem(
                        onClick = {
                           settingsMenuExpanded = false
                           onNavigateToAbout()
                        },
                        leadingIcon = {
                           Icon(imageVector = Icons.Filled.Info, contentDescription = null)
                        }
                     ) {
                        Text(stringResource(R.string.action_about_app))
                     }
                  }
               }

               if (guiSettingsDialog) {
                  AlertDialog(
                     onDismissRequest = { guiSettingsDialog = false },
                     title = { Text(stringResource(R.string.gui_settings_title)) },
                     text = {
                        Row(
                           modifier = Modifier.fillMaxWidth(),
                           horizontalArrangement = Arrangement.SpaceBetween,
                           verticalAlignment = Alignment.CenterVertically
                        ) {
                           Text(
                              text = stringResource(R.string.settings_compact_card_names),
                              modifier = Modifier.weight(1f).padding(end = 12.dp)
                           )
                           Switch(
                              checked = vm.compactCardNames,
                              onCheckedChange = { vm.saveCompactCardNames(context, it) }
                           )
                        }
                     },
                     confirmButton = {
                        TextButton(onClick = { guiSettingsDialog = false }) {
                           Text(stringResource(R.string.action_done))
                        }
                     }
                  )
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
         // None only occurs on the mode-selection screen, so this screen never
         // renders it; kept here just to make the `when` exhaustive.
         ConversionMode.None -> Unit
         is ConversionMode.SingleFiles -> {
            SingleFilesUI(vm, padding)
         }
         is ConversionMode.FolderMode -> {
            FolderModeUI(vm, mode, padding)
         }
         ConversionMode.Combined -> {
            CombinedModeUI(vm, padding)
         }
      }
   }
}
