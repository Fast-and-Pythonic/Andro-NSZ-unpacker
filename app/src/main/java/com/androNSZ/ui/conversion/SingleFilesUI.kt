package com.androNSZ.ui.conversion

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.androNSZ.R
import com.androNSZ.model.FileEntry
import com.androNSZ.model.FileStatus
import com.androNSZ.ui.components.ElapsedTimeRow
import com.androNSZ.ui.components.StatusLogPanel
import com.androNSZ.ui.components.StatusMessageCard
import com.androNSZ.util.fmtBytes
import com.androNSZ.util.getUriSize
import com.androNSZ.util.resolveDisplayName
import com.androNSZ.viewmodel.MainViewModel

@Composable
fun SingleFilesUI(vm: MainViewModel, padding: PaddingValues) {
   val context = LocalContext.current

   val filePicker = rememberLauncherForActivityResult(
      ActivityResultContracts.OpenDocument()
   ) { uri ->
      if (uri != null) {
         val name = resolveDisplayName(context, uri)
         val size = getUriSize(context, uri)
         vm.addFilesToQueue(listOf(FileEntry(uri, name, size)))
      }
   }

   Column(
      modifier = Modifier
         .fillMaxSize()
         .padding(padding)
         .padding(all = 16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
   ) {
      Button(
         onClick = { filePicker.launch(arrayOf("*/*")) },
         enabled = !vm.isConverting,
         modifier = Modifier.fillMaxWidth()
      ) {
         Icon(Icons.Filled.Add, null)
         Spacer(Modifier.width(8.dp))
         Text(stringResource(R.string.action_add_files))
      }

      if (vm.fileQueue.isNotEmpty()) {
         Text(
            text = stringResource(R.string.format_files_queued, vm.fileQueue.size),
            style = MaterialTheme.typography.titleMedium,
         )

         LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
         ) {
            items(vm.fileQueue.size) { index ->
               FileQueueItem(
                  file = vm.fileQueue[index],
                  onRemove = { vm.removeFileFromQueue(index) },
                  enabled = !vm.isConverting
               )
            }
         }
      } else {
         Box(
            modifier = Modifier
               .weight(1f)
               .fillMaxWidth(),
            contentAlignment = Alignment.Center
         ) {
            Text(
               text = stringResource(R.string.msg_tap_add_files),
               style = MaterialTheme.typography.bodyLarge,
               color = MaterialTheme.colorScheme.onSurfaceVariant
            )
         }
      }

      Button(
         onClick = { vm.startBatchConversion(context) },
         enabled = vm.fileQueue.isNotEmpty() && !vm.isConverting,
         modifier = Modifier.fillMaxWidth()
      ) {
         Text(stringResource(R.string.action_convert_files, vm.fileQueue.size))
      }

      if (vm.isConverting || vm.progress != null) {
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
               val isMultiFile = vm.fileQueue.size > 1
               val overall = vm.batchOverallProgress

               Text(
                  text = if (isMultiFile)
                     stringResource(R.string.status_converting)
                  else
                     stringResource(R.string.format_file_n_of_m, vm.currentFileIndex + 1, vm.fileQueue.size),
                  style = MaterialTheme.typography.titleMedium
               )

               ElapsedTimeRow(vm.elapsedMs)
               if (isMultiFile && overall != null) {
                  LinearProgressIndicator(
                     progress = { overall.percent },
                     modifier = Modifier.fillMaxWidth()
                  )
                  Row(
                     modifier = Modifier.fillMaxWidth(),
                     horizontalArrangement = Arrangement.SpaceBetween
                  ) {
                     Text(
                        text = stringResource(R.string.format_files_processed, vm.batchProcessedFiles, vm.batchTotalFiles),
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
                  Text(
                     text = "${fmtBytes(overall.doneBytes)} / ${fmtBytes(overall.totalBytes)}",
                     style = MaterialTheme.typography.bodySmall
                  )
               }

               if (isMultiFile) {
                  // One progress bar per file currently converting in parallel.
                  val active = vm.activeFileProgress.entries.sortedBy { it.key }
                  active.forEach { (idx, p) ->
                     Spacer(Modifier.height(4.dp))
                     Text(
                        text = vm.fileQueue.getOrNull(idx)?.displayName
                           ?: stringResource(R.string.label_current_file),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                     )
                     LinearProgressIndicator(
                        progress = { p.percent },
                        modifier = Modifier.fillMaxWidth()
                     )
                     Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                     ) {
                        Text(
                           text = "%.1f%%".format(p.percent * 100f),
                           style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                           text = "%.1f MB/s".format(p.speedMBps),
                           style = MaterialTheme.typography.bodySmall
                        )
                        if (p.totalBytes > 0) {
                           Text(
                              text = "${fmtBytes(p.doneBytes)} / ${fmtBytes(p.totalBytes)}",
                              style = MaterialTheme.typography.bodySmall
                           )
                        }
                     }
                  }
               } else {
                  val p = vm.progress
                  if (p != null) {
                     LinearProgressIndicator(
                        progress = { p.percent },
                        modifier = Modifier.fillMaxWidth()
                     )
                     Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                     ) {
                        Text(
                           text = "%.1f%%".format(p.percent * 100f),
                           style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                           text = "%.1f MB/s".format(p.speedMBps),
                           style = MaterialTheme.typography.bodySmall
                        )
                        if (p.totalBytes > 0) {
                           Text(
                              text = "${fmtBytes(p.doneBytes)} / ${fmtBytes(p.totalBytes)}",
                              style = MaterialTheme.typography.bodySmall
                           )
                        }
                     }
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
fun FileQueueItem(
   file: FileEntry,
   onRemove: () -> Unit,
   enabled: Boolean
) {
   val isNsz = file.displayName.endsWith(".nsz", ignoreCase = true)
   val isXcz = file.displayName.endsWith(".xcz", ignoreCase = true)
   val isCompressed = isNsz || isXcz

   Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(
         containerColor = when (file.status) {
            FileStatus.Pending -> MaterialTheme.colorScheme.surfaceVariant
            FileStatus.Converting -> MaterialTheme.colorScheme.primaryContainer
            FileStatus.Completed -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            FileStatus.Failed -> MaterialTheme.colorScheme.errorContainer
         }
      )
   ) {
      Row(
         modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
         verticalAlignment = Alignment.Top
      ) {
         Icon(
            imageVector = if (isCompressed) Icons.Filled.Description else Icons.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier
               .padding(top = 2.dp)
               .size(20.dp),
            tint = if (isCompressed) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant
         )
         Spacer(Modifier.width(8.dp))
         Column(modifier = Modifier.weight(1f)) {
            Text(
               text = file.displayName,
               style = MaterialTheme.typography.bodyMedium,
               fontWeight = if (isCompressed) FontWeight.Bold else FontWeight.Normal
            )
            val statusText = when (file.status) {
               FileStatus.Pending -> stringResource(R.string.status_waiting)
               FileStatus.Converting -> stringResource(R.string.status_converting)
               FileStatus.Completed -> stringResource(R.string.status_done)
               FileStatus.Failed -> stringResource(R.string.status_error)
            }
            val sizeText = if (file.fileSize > 0) fmtBytes(file.fileSize) else null
            Text(
               text = if (sizeText != null) "$sizeText  |  $statusText" else statusText,
               style = MaterialTheme.typography.bodySmall,
               color = MaterialTheme.colorScheme.onSurfaceVariant
            )
         }

         if (enabled && file.status == FileStatus.Pending) {
            IconButton(onClick = onRemove) {
               Icon(Icons.Filled.Close, stringResource(R.string.action_delete))
            }
         }
      }
   }
}
