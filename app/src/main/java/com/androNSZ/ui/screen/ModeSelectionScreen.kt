package com.androNSZ.ui.screen

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androNSZ.R
import com.androNSZ.model.ConversionMode
import com.androNSZ.nut.KeysManager
import com.androNSZ.ui.components.AppDropdownMenuItem
import com.androNSZ.ui.components.CompactCenterAlignedTopAppBar
import com.androNSZ.util.toDisplayPath

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
                           onCheckKeys()
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
                              text = outputFolderUri?.toDisplayPath() ?: "Downloads",
                              style = MaterialTheme.typography.labelSmall,
                              color = MaterialTheme.colorScheme.onSurfaceVariant
                           )
                        }
                     }
                     HorizontalDivider()
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
            .padding(horizontal = 16.dp, vertical = 20.dp),
         verticalArrangement = Arrangement.spacedBy(12.dp)
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

         Text(
            text = stringResource(R.string.msg_select_mode).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.outline,
            letterSpacing = 1.2.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
               .fillMaxWidth()
               .padding(top = 4.dp, bottom = 4.dp)
         )

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

         if (keysInstalled) {
            Row(
               modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(12.dp))
                  .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                  .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                  .padding(horizontal = 14.dp, vertical = 10.dp),
               verticalAlignment = Alignment.CenterVertically,
               horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
               Icon(
                  imageVector = Icons.Filled.Lock,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant
               )
               Text(
                  text = buildAnnotatedString {
                     append("prod.keys")
                     outputFolderUri?.let { append(" · ${it.toDisplayPath()}") }
                  },
                  style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                  color = MaterialTheme.colorScheme.onSurfaceVariant
               )
            }
         }
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
   // Card background tone. In the dark theme the default ElevatedCard color
   // (surfaceContainerLow) looks too dark, so we lift it a step. Adjust here:
   //   surfaceContainerLow  -> darkest  (Material default)
   //   surfaceContainer     -> a bit lighter
   //   surfaceContainerHigh -> lighter (current)
   //   surfaceContainerHighest -> lightest
   val cardContainerColor = if (isSystemInDarkTheme()) {
      MaterialTheme.colorScheme.surfaceContainer
   } else {
      MaterialTheme.colorScheme.surfaceContainerLow // keep Material default in light theme
   }

   ElevatedCard(
      modifier = Modifier.fillMaxWidth(),
      onClick = onClick,
      enabled = enabled,
      colors = CardDefaults.elevatedCardColors(
         containerColor = cardContainerColor
      ),
      elevation = CardDefaults.elevatedCardElevation(
         defaultElevation = 2.dp,
         pressedElevation = 6.dp
      )
   ) {
      Row(
         modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 20.dp),
         horizontalArrangement = Arrangement.spacedBy(18.dp),
         verticalAlignment = Alignment.CenterVertically
      ) {
         Box(
            modifier = Modifier
               .size(52.dp)
               .clip(RoundedCornerShape(12.dp))
               .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
         ) {
            Icon(
               imageVector = icon,
               contentDescription = null,
               modifier = Modifier.size(26.dp),
               tint = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                      else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
         }
         Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
               text = title,
               style = MaterialTheme.typography.titleMedium,
               color = MaterialTheme.colorScheme.onSurface
            )
            Text(
               text = description,
               style = MaterialTheme.typography.bodySmall,
               color = MaterialTheme.colorScheme.onSurfaceVariant
            )
         }
      }
   }
}
