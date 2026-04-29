package com.androNSZ.ui.screen

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import com.androNSZ.util.toDisplayPath
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.androNSZ.R
import com.androNSZ.model.ConversionMode
import com.androNSZ.nut.KeysManager
import com.androNSZ.ui.components.CompactCenterAlignedTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSelectionScreen(
   onModeSelected: (ConversionMode) -> Unit,
   keysInstalled: Boolean,
   onInstallKeys: () -> Unit,
   onNavigateToAbout: () -> Unit,
   onNavigateToSettings: () -> Unit,
   onCheckKeys: () -> Unit,
   onChangeOutputFolder: () -> Unit,
   outputFolderUri: Uri?
) {
   val context = LocalContext.current
   var settingsMenuExpanded by remember { mutableStateOf(false) }

   Scaffold(
      topBar = {
         CompactCenterAlignedTopAppBar(
            title = { Text(stringResource(R.string.app_title)) },
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
                           onCheckKeys()
                        }
                     )
                     HorizontalDivider()
                     DropdownMenuItem(
                        // "Output folder" is intentionally NOT translated — keep as-is for all languages
                        text = {
                           Column {
                              Text("Output folder")
                              Text(
                                 text = outputFolderUri?.toDisplayPath() ?: "Downloads",
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant
                              )
                           }
                        },
                        onClick = {
                           settingsMenuExpanded = false
                           onChangeOutputFolder()
                        },
                        leadingIcon = {
                           Icon(
                              imageVector = Icons.Filled.FolderOpen,
                              contentDescription = null
                           )
                        }
                     )
                     HorizontalDivider()
                     DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_settings)) },
                        onClick = {
                           settingsMenuExpanded = false
                           onNavigateToSettings()
                        },
                        leadingIcon = {
                           Icon(
                              imageVector = Icons.Filled.Settings,
                              contentDescription = null
                           )
                        }
                     )
                     DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_about_app)) },
                        onClick = {
                           settingsMenuExpanded = false
                           onNavigateToAbout()
                        },
                        leadingIcon = {
                           Icon(
                              imageVector = Icons.Filled.Info,
                              contentDescription = null
                           )
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
      Column(
         modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
         horizontalAlignment = Alignment.CenterHorizontally,
         verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
      if (!keysInstalled) {
         Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = onInstallKeys,
            colors = CardDefaults.cardColors(
               containerColor = MaterialTheme.colorScheme.errorContainer
            )
         ) {
            Column(
               modifier = Modifier
                  .fillMaxWidth()
                  .padding(16.dp),
               horizontalAlignment = Alignment.CenterHorizontally,
               verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
               Text(
                  text = stringResource(R.string.msg_prod_keys_required),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  textAlign = TextAlign.Center,
               )
               Text(
                  text = stringResource(R.string.action_install_prod_keys),
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  textAlign = TextAlign.Center,
               )
            }
         }
      }
      
//      Spacer(modifier = Modifier.height(0.dp))
      
      Text(
         text = stringResource(R.string.msg_select_mode),
         style = MaterialTheme.typography.headlineMedium,
         color = MaterialTheme.colorScheme.onSurface,
         textAlign = TextAlign.Center
      )
      
      Spacer(modifier = Modifier.height(0.dp))
      
      ModeCard(
         title = stringResource(R.string.action_select_files),
         description = stringResource(R.string.msg_select_files_desc),
         icon = Icons.Filled.InsertDriveFile,
         enabled = keysInstalled,
         onClick = { onModeSelected(ConversionMode.SingleFiles(emptyList())) }
      )

      ModeCard(
         title = stringResource(R.string.action_select_folder),
         description = stringResource(R.string.msg_select_folder_desc),
         icon = Icons.Filled.Folder,
         enabled = keysInstalled,
         onClick = { onModeSelected(ConversionMode.FolderMode(Uri.EMPTY, null)) }
      )
      }
   }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeCard(
   title: String,
   description: String,
   icon: ImageVector,
   enabled: Boolean,
   onClick: () -> Unit
) {
   Card(
      modifier = Modifier
         .fillMaxWidth()
         .height(120.dp),
      onClick = onClick,
      enabled = enabled,
      colors = CardDefaults.cardColors(
         containerColor = MaterialTheme.colorScheme.primaryContainer
      )
   ) {
      Row(
         modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
         horizontalArrangement = Arrangement.spacedBy(16.dp),
         verticalAlignment = Alignment.CenterVertically
      ) {
         Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer
         )
         Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
         ) {
            Text(
               text = title,
               style = MaterialTheme.typography.titleLarge,
               color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
               text = description,
               style = MaterialTheme.typography.bodyMedium,
               color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
         }
      }
   }
}
