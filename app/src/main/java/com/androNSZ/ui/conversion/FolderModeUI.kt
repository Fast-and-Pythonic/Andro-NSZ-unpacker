package com.androNSZ.ui.conversion

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.androNSZ.R
import com.androNSZ.model.ConversionMode
import com.androNSZ.model.FileNode
import com.androNSZ.model.countAllFiles
import com.androNSZ.ui.components.CompactToggleButton
import com.androNSZ.ui.components.StatusLogPanel
import com.androNSZ.ui.components.StatusMessageCard
import com.androNSZ.util.fmtBytes
import com.androNSZ.viewmodel.MainViewModel

@Composable
fun FolderModeUI(vm: MainViewModel, mode: ConversionMode.FolderMode, padding: PaddingValues) {
   val context = LocalContext.current

   val folderPicker = rememberLauncherForActivityResult(
      ActivityResultContracts.OpenDocumentTree()
   ) { uri ->
      if (uri != null) {
         vm.selectFolder(context, uri)
      }
   }

   Column(
      modifier = Modifier
         .fillMaxSize()
         .padding(padding)
         .verticalScroll(rememberScrollState())
         .padding(all = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
   ) {
      Button(
         onClick = { folderPicker.launch(null) },
         enabled = !vm.isConverting,
         modifier = Modifier.fillMaxWidth()
      ) {
         Icon(Icons.Filled.Folder, null)
         Spacer(Modifier.width(8.dp))
         Text(stringResource(R.string.action_select_folder))
      }

      val structure = vm.folderStructure
      if (structure != null) {
         Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
               containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
         ) {
            Column(
               modifier = Modifier.padding(16.dp),
               verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
               Text(
                  text = stringResource(R.string.label_folder_selected),
                  style = MaterialTheme.typography.titleMedium
               )
               Text(
                  text = stringResource(R.string.format_nsz_files_count, structure.nszFiles.size),
                  style = MaterialTheme.typography.bodyMedium
               )
               Text(
                  text = stringResource(R.string.format_total_files_count, countAllFiles(structure.allFiles)),
                  style = MaterialTheme.typography.bodyMedium
               )
               Text(
                  text = stringResource(R.string.format_size, fmtBytes(structure.totalSize)),
                  style = MaterialTheme.typography.bodyMedium
               )
            }
         }

         var showStructure by remember { mutableStateOf(false) }
         CompactToggleButton(
            expanded = showStructure,
            collapsedText = stringResource(R.string.action_show_structure),
            expandedText = stringResource(R.string.action_hide_structure),
            onClick = { showStructure = !showStructure }
         )

         if (showStructure) {
            Surface(
               modifier = Modifier.fillMaxWidth(),
               color = MaterialTheme.colorScheme.surfaceVariant,
               shape = MaterialTheme.shapes.medium
            ) {
               Column(modifier = Modifier.padding(12.dp)) {
                  FolderTreeView(structure.allFiles)
               }
            }
         }

         Button(
            onClick = { vm.startFolderConversion(context) },
            enabled = !vm.isConverting,
            modifier = Modifier.fillMaxWidth()
         ) {
            Text(stringResource(R.string.action_convert_folder))
         }
      }

      if (vm.isConverting || vm.folderOverallProgress != null) {
         Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
               containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
         ) {
            Column(
               modifier = Modifier.padding(16.dp),
               verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
               Text(
                  text = stringResource(R.string.label_processing_folder),
                  style = MaterialTheme.typography.titleMedium
               )

               val overall = vm.folderOverallProgress
               if (overall != null) {
                  LinearProgressIndicator(
                     progress = { overall.percent },
                     modifier = Modifier.fillMaxWidth()
                  )
                  Row(
                     modifier = Modifier.fillMaxWidth(),
                     horizontalArrangement = Arrangement.SpaceBetween
                  ) {
                     Text(
                        text = stringResource(R.string.format_files_processed, vm.folderProcessedFiles, vm.folderTotalFiles),
                        style = MaterialTheme.typography.bodySmall
                     )
                     Text(
                        text = "%.1f%%".format(overall.percent * 100f),
                        style = MaterialTheme.typography.bodySmall
                     )
                     Text(
                        text = "%.1f MB/s".format(overall.speedMBps),
                        style = MaterialTheme.typography.bodySmall
                     )
                  }
                  if (overall.totalBytes > 0) {
                     Text(
                        text = "${fmtBytes(overall.doneBytes)} / ${fmtBytes(overall.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall
                     )
                  }
               }

               val currentFileProgress = vm.folderCurrentFileProgress
               val currentFileName = vm.folderCurrentFileName
               if (currentFileName != null && currentFileProgress != null) {
                  Spacer(Modifier.size(4.dp))
                  Text(
                     text = currentFileName,
                     style = MaterialTheme.typography.bodyMedium,
                     maxLines = 1,
                     overflow = TextOverflow.Ellipsis
                  )
                  LinearProgressIndicator(
                     progress = { currentFileProgress.percent },
                     modifier = Modifier.fillMaxWidth()
                  )
                  Row(
                     modifier = Modifier.fillMaxWidth(),
                     horizontalArrangement = Arrangement.SpaceBetween
                  ) {
                     Text(
                        text = stringResource(R.string.label_current_file),
                        style = MaterialTheme.typography.bodySmall
                     )
                     Text(
                        text = "%.1f%%".format(currentFileProgress.percent * 100f),
                        style = MaterialTheme.typography.bodySmall
                     )
                  }
                  if (currentFileProgress.totalBytes > 0) {
                     Text(
                        text = "${fmtBytes(currentFileProgress.doneBytes)} / ${fmtBytes(currentFileProgress.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall
                     )
                  }
               }
            }
         }
      }

      StatusLogPanel(vm.statusLog)
      StatusMessageCard(vm.statusMessage, vm.isSuccess)
   }
}

@Composable
fun FolderTreeView(nodes: List<FileNode>, depth: Int = 0) {
   val startPadding: Dp = (depth * 16).dp
   Column {
      nodes.forEach { node ->
         when (node) {
            is FileNode.File -> {
               Row(
                  modifier = Modifier
                     .fillMaxWidth()
                     .padding(start = startPadding, top = 2.dp, bottom = 2.dp),
                  verticalAlignment = Alignment.CenterVertically
               ) {
                  Icon(
                     if (node.isNsz) Icons.Filled.Description else Icons.Filled.InsertDriveFile,
                     null,
                     modifier = Modifier.size(16.dp),
                     tint = if (node.isNsz) MaterialTheme.colorScheme.primary
                     else MaterialTheme.colorScheme.onSurfaceVariant
                  )
                  Spacer(Modifier.width(4.dp))
                  Text(
                     text = node.name,
                     style = MaterialTheme.typography.bodySmall,
                     fontWeight = if (node.isNsz) FontWeight.Bold else FontWeight.Normal
                  )
               }
            }

            is FileNode.Directory -> {
               Row(
                  modifier = Modifier
                     .fillMaxWidth()
                     .padding(start = startPadding, top = 2.dp, bottom = 2.dp),
                  verticalAlignment = Alignment.CenterVertically
               ) {
                  Icon(
                     Icons.Filled.Folder,
                     null,
                     modifier = Modifier.size(16.dp),
                     tint = MaterialTheme.colorScheme.primary
                  )
                  Spacer(Modifier.width(4.dp))
                  Text(
                     text = node.name + "/",
                     style = MaterialTheme.typography.bodySmall,
                     fontWeight = FontWeight.Bold
                  )
               }
               FolderTreeView(node.children, depth + 1)
            }
         }
      }
   }
}
