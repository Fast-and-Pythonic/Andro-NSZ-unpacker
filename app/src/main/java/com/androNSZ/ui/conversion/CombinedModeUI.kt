package com.androNSZ.ui.conversion

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androNSZ.R
import com.androNSZ.model.CombinedItem
import com.androNSZ.model.FileEntry
import com.androNSZ.model.FileStatus
import com.androNSZ.model.PickerMode
import com.androNSZ.model.Screen
import com.androNSZ.model.collectAllFiles
import com.androNSZ.model.countAllFiles
import com.androNSZ.ui.components.CompactToggleButton
import com.androNSZ.util.fmtBytes
import com.androNSZ.viewmodel.MainViewModel

// Side inset for the non-card elements (buttons, toggles, headers). The item cards
// deliberately skip it so they run edge-to-edge across the screen.
private val SidePadding = 16.dp

/**
 * Combined mode (files + folders together). Mirrors the two other mode screens:
 * an "add" button opens the universal picker, the selection sits under a compact
 * "show selected" toggle — folders as expandable containers, standalone files as
 * rows — and a button unpacks everything straight into the output folder (see
 * [MainViewModel.startCombinedConversion]). The progress/stats block is shared
 * with folder mode via [FolderStyleProgressAndStats].
 *
 * Item cards run edge-to-edge: the outer Column pads only vertically and the
 * non-card children carry their own [SidePadding].
 */
@Composable
fun CombinedModeUI(vm: MainViewModel, padding: PaddingValues) {
   val context = LocalContext.current

   Column(
      modifier = Modifier
         .fillMaxSize()
         .padding(padding)
         .verticalScroll(rememberScrollState())
         .padding(vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
   ) {
      Button(
         onClick = { vm.navigateTo(Screen.FilePicker(PickerMode.FilesAndFolders)) },
         enabled = !vm.isConverting,
         modifier = Modifier.fillMaxWidth().padding(horizontal = SidePadding)
      ) {
         Icon(Icons.Filled.Checklist, null)
         Spacer(Modifier.width(8.dp))
         Text(stringResource(R.string.action_add_files_folders))
      }

      if (vm.combinedScanning) {
         Row(
            modifier = Modifier.padding(horizontal = SidePadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
         ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
               text = stringResource(R.string.status_scanning_folder),
               style = MaterialTheme.typography.bodyMedium
            )
         }
      }

      if (vm.combinedItems.isEmpty()) {
         Box(
            modifier = Modifier.fillMaxWidth().height(160.dp).padding(horizontal = SidePadding),
            contentAlignment = Alignment.Center
         ) {
            Text(
               text = stringResource(R.string.msg_tap_add_files_folders),
               style = MaterialTheme.typography.bodyLarge,
               color = MaterialTheme.colorScheme.onSurfaceVariant
            )
         }
      } else {
         // The whole list lives under one toggle so the screen stays compact; the
         // count rides in the toggle label.
         var showSelected by rememberSaveable { mutableStateOf(true) }
         val selectedTotalSize = fmtBytes(vm.combinedItems.sumOf { it.sizeBytes })
         CompactToggleButton(
            expanded = showSelected,
            collapsedText = stringResource(R.string.action_show_selected, vm.combinedItems.size, selectedTotalSize),
            expandedText = stringResource(R.string.action_hide_selected, vm.combinedItems.size, selectedTotalSize),
            onClick = { showSelected = !showSelected },
            modifier = Modifier.padding(horizontal = SidePadding)
         )

         if (showSelected) {
            // Cards are direct children of the outer Column, so they get the 12dp
            // spacing and — carrying no side padding — run edge-to-edge. A folder is
            // an expandable container; a standalone NSZ/XCZ reuses the queue card
            // (with its live status), any other standalone file falls back to a row.
            vm.combinedItems.forEachIndexed { index, item ->
               if (item.isDirectory) {
                  CombinedFolderCard(
                     vm = vm,
                     item = item,
                     enabled = !vm.isConverting,
                     onRemove = { vm.removeCombinedItem(index) }
                  )
               } else {
                  val uri = Uri.fromFile(item.file)
                  val entry = vm.folderFileEntries.firstOrNull { it.uri == uri }
                  if (entry != null) {
                     FileQueueItem(
                        file = entry,
                        onRemove = { vm.removeCombinedItem(index) },
                        enabled = !vm.isConverting,
                        compactNames = vm.compactCardNames
                     )
                  } else {
                     CombinedFileCard(
                        item = item,
                        enabled = !vm.isConverting,
                        onRemove = { vm.removeCombinedItem(index) }
                     )
                  }
               }
            }
         }
      }

      Button(
         onClick = { vm.startCombinedConversion(context) },
         enabled = vm.combinedItems.isNotEmpty() && !vm.isConverting && !vm.combinedScanning,
         modifier = Modifier.fillMaxWidth().padding(horizontal = SidePadding)
      ) {
         Text(stringResource(R.string.action_convert_combined, vm.combinedItems.size))
      }

      FolderStyleProgressAndStats(vm)

      // Extra scroll room below the log so the dynamically added/removed
      // per-file rows never butt against the bottom edge — keeps the viewport
      // from jumping while files start and finish.
      if (vm.isConverting || vm.folderOverallProgress != null) {
         Spacer(Modifier.height((LocalConfiguration.current.screenHeightDp / 2).dp))
      }
   }
}

/**
 * A ✕ button pinned to a 24dp layout slot so its 48dp IconButton touch target
 * doesn't inflate the row or strand the glyph far from the edge; the target still
 * overflows the slot for comfortable tapping.
 */
@Composable
private fun RemoveButton(onRemove: () -> Unit) {
   Box(modifier = Modifier.requiredSize(24.dp), contentAlignment = Alignment.Center) {
      IconButton(onClick = onRemove) {
         Icon(
            Icons.Filled.Close,
            stringResource(R.string.action_delete),
            modifier = Modifier.size(20.dp)
         )
      }
   }
}

/** A selected folder: a container card that expands to its file list and/or tree. */
@Composable
private fun CombinedFolderCard(
   vm: MainViewModel,
   item: CombinedItem,
   enabled: Boolean,
   onRemove: () -> Unit
) {
   var showFiles by remember { mutableStateOf(false) }
   var showStructure by remember { mutableStateOf(false) }
   val structure = item.structure
   val totalFiles = structure?.let { countAllFiles(it.allFiles) } ?: 0

   // NSZ/XCZ counts line, only for the kinds actually present.
   val nszText = if (item.nszCount > 0) stringResource(R.string.format_nsz_files_count, item.nszCount) else null
   val xczText = if (item.xczCount > 0) stringResource(R.string.format_xcz_files_count, item.xczCount) else null
   val kindParts = listOfNotNull(nszText, xczText)

   // The folder's own compressed files, each mapped to the shared per-file entry
   // (with live status) by uri — mirroring the standalone card's firstOrNull. This
   // is deliberate: when the same file is also picked standalone, several entries
   // share a uri and only the first receives status updates, so filtering the
   // shared list by uri would surface the stale duplicate. Iterating our own nodes
   // instead keeps the row count correct and lands each row on the updated entry.
   val compressedFiles = remember(structure) {
      if (structure != null) collectAllFiles(structure.allFiles) else emptyList()
   }
   val folderEntries = compressedFiles.map { node ->
      vm.folderFileEntries.firstOrNull { it.uri == node.uri }
         ?: FileEntry(node.uri, node.name, node.sizeBytes)
   }

   // Turn the folder card blue once all its NSZ/XCZ files are unpacked — matching
   // how a Completed file card is tinted (primaryContainer).
   val allDone = folderEntries.isNotEmpty() && folderEntries.all { it.status == FileStatus.Completed }

   Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(
         containerColor = if (allDone) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                          else MaterialTheme.colorScheme.surfaceVariant
      )
   ) {
      Column(
         modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
         verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
         Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
               imageVector = Icons.Filled.Folder,
               contentDescription = null,
               modifier = Modifier.size(22.dp),
               tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
               text = item.file.name + "/",
               style = MaterialTheme.typography.bodyLarge,
               fontWeight = FontWeight.Bold,
               maxLines = 1,
               overflow = TextOverflow.Ellipsis,
               modifier = Modifier.weight(1f)
            )
            if (enabled) {
               Spacer(Modifier.width(4.dp))
               RemoveButton(onRemove)
            }
         }

         Text(
            text = stringResource(R.string.format_total_files_count, totalFiles) +
               "  ·  " + fmtBytes(item.sizeBytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
         )
         if (kindParts.isNotEmpty()) {
            Text(
               text = kindParts.joinToString("  ·  "),
               style = MaterialTheme.typography.bodySmall,
               color = MaterialTheme.colorScheme.onSurfaceVariant
            )
         }

         if (folderEntries.isNotEmpty()) {
            CompactToggleButton(
               expanded = showFiles,
               collapsedText = stringResource(R.string.action_show_unpack_files),
               expandedText = stringResource(R.string.action_hide_unpack_files),
               onClick = { showFiles = !showFiles }
            )
            if (showFiles) {
               // Lift the (surfaceVariant) queue cards off the (surfaceVariant)
               // container so they read as a distinct list. The folder's contents
               // aren't individually editable, so no remove button.
               // fullBleedWidth stretches the surface 4dp past the Column's 12dp
               // side padding, so the surfaceVariant "frame" left of it is 8dp —
               // matching the surface's own 8dp inner gap to the file cards.
               Surface(
                  modifier = Modifier.fillMaxWidth().fullBleedWidth(4.dp),
                  color = MaterialTheme.colorScheme.surface,
                  shape = MaterialTheme.shapes.medium
               ) {
                  Column(
                     modifier = Modifier.padding(8.dp),
                     verticalArrangement = Arrangement.spacedBy(8.dp)
                  ) {
                     folderEntries.forEach { entry ->
                        FileQueueItem(file = entry, onRemove = {}, enabled = false, compactNames = vm.compactCardNames)
                     }
                  }
               }
            }
         }

         if (structure != null && structure.allFiles.isNotEmpty()) {
            CompactToggleButton(
               expanded = showStructure,
               collapsedText = stringResource(R.string.action_show_structure),
               expandedText = stringResource(R.string.action_hide_structure),
               onClick = { showStructure = !showStructure }
            )
            if (showStructure) {
               // Same 8dp frame/gap as the NSZ/XCZ list above: fullBleedWidth trims
               // the surfaceVariant frame to 8dp and the inner padding matches it.
               Surface(
                  modifier = Modifier.fillMaxWidth().fullBleedWidth(4.dp),
                  color = MaterialTheme.colorScheme.surface,
                  shape = MaterialTheme.shapes.medium
               ) {
                  Column(modifier = Modifier.padding(8.dp)) {
                     FolderTreeView(structure.allFiles)
                  }
               }
            }
         }
      }
   }
}

/** A standalone non-NSZ/XCZ file (it will simply be copied to the output). */
@Composable
private fun CombinedFileCard(item: CombinedItem, enabled: Boolean, onRemove: () -> Unit) {
   Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(
         containerColor = MaterialTheme.colorScheme.surfaceVariant
      )
   ) {
      Row(
         modifier = Modifier.fillMaxWidth().padding(12.dp),
         verticalAlignment = Alignment.CenterVertically
      ) {
         Column(modifier = Modifier.weight(1f)) {
            Text(
               text = item.file.name,
               style = MaterialTheme.typography.bodyMedium,
               maxLines = 1,
               overflow = TextOverflow.Ellipsis
            )
            Text(
               text = fmtBytes(item.sizeBytes),
               style = MaterialTheme.typography.bodySmall,
               color = MaterialTheme.colorScheme.onSurfaceVariant
            )
         }
         if (enabled) {
            Spacer(Modifier.width(4.dp))
            RemoveButton(onRemove)
         }
      }
   }
}
