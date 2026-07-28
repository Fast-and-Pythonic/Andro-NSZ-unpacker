package com.androNSZ.ui.screen

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androNSZ.R
import com.androNSZ.model.PickerMode
import com.androNSZ.ui.components.AppDropdownMenuItem
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.androNSZ.ui.components.simpleVerticalScrollbar
import com.androNSZ.util.AppRestart
import com.androNSZ.util.StoragePermission
import com.androNSZ.util.fmtBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pre-resolved metadata for one browser row. Built off the main thread (see the
 * produceState in [PickerContent]) so that scrolling the LazyColumn never touches
 * java.io.File: isDirectory/length are blocking stat syscalls, and reading them per
 * visible row per frame is what tanked the scroll fps. Rows just read these fields.
 */
private data class BrowserEntry(
   val absolutePath: String,
   val name: String,
   val isDirectory: Boolean,
   val icon: ImageVector,
   val subtitle: String?,  // null for directories
   val sizeBytes: Long,    // 0 for directories (recursive sizing is too costly here)
   val lastModified: Long
)

/** Criteria offered by the sort menus on both panes. */
private enum class SortKey { NAME, SIZE, DATE, TYPE }

/**
 * How usable "All files access" is right now. [NeedsRestart] is the case where the
 * permission is granted but the running process still has the old storage mount and
 * only a restart can fix it (G21); [Checking] is the brief probe before we know.
 */
private enum class StorageAccess { Checking, Denied, NeedsRestart, Ready }

/** Stat one filesystem entry into a [BrowserEntry]. Called off the main thread; the
 *  blocking stat syscalls (isDirectory/length/lastModified) must never run per row
 *  per frame. Folders skip sizing — a recursive walk per listing is too expensive. */
private fun statEntry(f: File): BrowserEntry {
   val isDir = f.isDirectory
   return BrowserEntry(
      absolutePath = f.absolutePath,
      name = f.name,
      isDirectory = isDir,
      icon = iconFor(isDir, f.name),
      subtitle = if (isDir) null else fileSubtitle(f),
      sizeBytes = if (isDir) 0L else f.length(),
      lastModified = f.lastModified()
   )
}

/**
 * Sort a mixed file/folder list. Folders always come first; the chosen [key] orders
 * items within each group. [ascending] flips only the key — never the folders-first
 * grouping. A trailing name tiebreaker keeps the order stable and gives folders a sane
 * alphabetical order under SIZE (folders have sizeBytes = 0) and TYPE (no extension).
 */
private fun sortEntries(entries: List<BrowserEntry>, key: SortKey, ascending: Boolean): List<BrowserEntry> {
   val base: Comparator<BrowserEntry> = when (key) {
      SortKey.NAME -> compareBy { it.name.lowercase() }
      SortKey.SIZE -> compareBy { it.sizeBytes }
      SortKey.DATE -> compareBy { it.lastModified }
      SortKey.TYPE -> compareBy({ it.name.substringAfterLast('.', "").lowercase() }, { it.name.lowercase() })
   }
   val directed = if (ascending) base else base.reversed()
   return entries.sortedWith(
      compareBy<BrowserEntry> { !it.isDirectory }.then(directed).thenBy { it.name.lowercase() }
   )
}

/**
 * Split-screen file picker over the real filesystem (java.io.File): the top pane
 * lists the currently marked items, the bottom pane is a directory browser rooted
 * at primary external storage. [mode] decides what can be marked — files
 * (multi-select) or a single folder. On confirm, [onConfirm] receives the marked
 * [File]s. Requires "All files access" (MANAGE_EXTERNAL_STORAGE), gated below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePickerScreen(
   mode: PickerMode,
   onConfirm: (List<File>) -> Unit,
   onBack: () -> Unit
) {
   val context = LocalContext.current
   val rootPath = remember { Environment.getExternalStorageDirectory().absolutePath }

   var granted by remember { mutableStateOf(StoragePermission.isGranted()) }
   var access by remember {
      mutableStateOf(if (granted) StorageAccess.Checking else StorageAccess.Denied)
   }
   val permLauncher = rememberLauncherForActivityResult(
      ActivityResultContracts.StartActivityForResult()
   ) {
      // The settings page returns no result code, so re-check the actual state.
      granted = StoragePermission.isGranted()
   }

   // The grant can arrive from anywhere — the settings page we opened, a page the
   // user found by hand, a vendor prompt — so re-read it on every resume instead of
   // trusting the launcher callback alone (G21).
   val lifecycleOwner = LocalLifecycleOwner.current
   DisposableEffect(lifecycleOwner) {
      val observer = LifecycleEventObserver { _, event ->
         if (event == Lifecycle.Event.ON_RESUME) granted = StoragePermission.isGranted()
      }
      lifecycleOwner.lifecycle.addObserver(observer)
      onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
   }

   // Granted isn't enough: a grant that lands while the app runs doesn't reach the
   // process's storage mount, so probe an actual listing before browsing (G21).
   LaunchedEffect(granted) {
      access = when {
         !granted -> StorageAccess.Denied
         withContext(Dispatchers.IO) { StoragePermission.canBrowseStorage() } ->
            StorageAccess.Ready
         else -> StorageAccess.NeedsRestart
      }
   }

   val title = when (mode) {
      PickerMode.FilesOnly -> stringResource(R.string.picker_title_files)
      PickerMode.FoldersOnly -> stringResource(R.string.picker_title_folder)
      PickerMode.FilesAndFolders -> stringResource(R.string.picker_title_mixed)
   }

   // TEMP tuning: per-kind row spacing (dp), adjustable live from the gear menu.
   // Bottom pane is split into files vs folders because a folder row (single line)
   // reads as looser than a file row (two lines) at the same padding. Once good
   // values are found these become constants and the menu is removed.
   var topSpacing by rememberSaveable { mutableStateOf(0) }
   var fileSpacing by rememberSaveable { mutableStateOf(1) }
   var folderSpacing by rememberSaveable { mutableStateOf(3) }
   var showSpacingSettings by rememberSaveable { mutableStateOf(false) }
   // GUI layout options: which side the top-pane ✕ buttons and the bottom-pane
   // checkboxes sit on. Defaults match the current layout (✕ right, checkbox left).
   var crossesOnLeft by rememberSaveable { mutableStateOf(false) }
   var checkboxesOnLeft by rememberSaveable { mutableStateOf(true) }
   var menuExpanded by remember { mutableStateOf(false) }
   // Gear menu has two pages: the main list and the "GUI settings" sub-list.
   var menuGuiPage by remember { mutableStateOf(false) }

   Scaffold(
      topBar = {
         com.androNSZ.ui.components.CompactCenterAlignedTopAppBar(
            title = { Text(title) },
            navigationIcon = {
               IconButton(onClick = onBack) {
                  Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
               }
            },
            actions = {
               Box {
                  IconButton(onClick = { menuExpanded = true }) {
                     Icon(Icons.Filled.Settings, stringResource(R.string.cd_picker_settings))
                  }
                  DropdownMenu(
                     expanded = menuExpanded,
                     onDismissRequest = { menuExpanded = false; menuGuiPage = false }
                  ) {
                     if (!menuGuiPage) {
                        // Main page: entry point into the GUI-settings sub-list.
                        Row(
                           modifier = Modifier
                              .clickable { menuGuiPage = true }
                              .padding(horizontal = 12.dp, vertical = 10.dp),
                           verticalAlignment = Alignment.CenterVertically
                        ) {
                           Icon(Icons.Filled.Settings, contentDescription = null)
                           Spacer(Modifier.width(8.dp))
                           Text(stringResource(R.string.picker_gui_settings))
                        }
                     } else {
                        // GUI settings sub-list. Header doubles as "back" to the main list.
                        Row(
                           modifier = Modifier
                              .clickable { menuGuiPage = false }
                              .padding(horizontal = 12.dp, vertical = 10.dp),
                           verticalAlignment = Alignment.CenterVertically
                        ) {
                           Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                           Spacer(Modifier.width(8.dp))
                           Text(
                              text = stringResource(R.string.picker_gui_settings),
                              style = MaterialTheme.typography.titleSmall
                           )
                        }
                        HorizontalDivider()

                        SideToggleRow(
                           label = stringResource(R.string.picker_crosses_side),
                           isLeft = crossesOnLeft,
                           onChange = { crossesOnLeft = it }
                        )
                        SideToggleRow(
                           label = stringResource(R.string.picker_checkboxes_side),
                           isLeft = checkboxesOnLeft,
                           onChange = { checkboxesOnLeft = it }
                        )

                        // TEMP tuning: spacing fields, hidden unless the toggle is on.
                        Row(
                           modifier = Modifier
                              .clickable { showSpacingSettings = !showSpacingSettings }
                              .padding(horizontal = 12.dp, vertical = 4.dp),
                           verticalAlignment = Alignment.CenterVertically
                        ) {
                           Text(stringResource(R.string.picker_spacing_settings))
                           Spacer(Modifier.width(12.dp).weight(1f))
                           Switch(
                              checked = showSpacingSettings,
                              onCheckedChange = { showSpacingSettings = it }
                           )
                        }
                        if (showSpacingSettings) {
                           SpacingField(
                              label = stringResource(R.string.picker_row_spacing_top),
                              value = topSpacing,
                              onValue = { topSpacing = it }
                           )
                           SpacingField(
                              label = stringResource(R.string.picker_row_spacing_files),
                              value = fileSpacing,
                              onValue = { fileSpacing = it }
                           )
                           SpacingField(
                              label = stringResource(R.string.picker_row_spacing_folders),
                              value = folderSpacing,
                              onValue = { folderSpacing = it }
                           )
                        }
                     }
                  }
               }
            }
         )
      }
   ) { padding ->
      when (access) {
         StorageAccess.Checking -> return@Scaffold
         StorageAccess.Denied -> {
            PermissionGate(
               modifier = Modifier.fillMaxSize().padding(padding),
               message = stringResource(R.string.picker_permission_rationale),
               action = stringResource(R.string.picker_permission_grant),
               onAction = { StoragePermission.launchSettings(context, permLauncher) }
            )
            return@Scaffold
         }
         StorageAccess.NeedsRestart -> {
            PermissionGate(
               modifier = Modifier.fillMaxSize().padding(padding),
               message = stringResource(R.string.picker_permission_restart_rationale),
               action = stringResource(R.string.picker_permission_restart),
               onAction = { AppRestart.restart(context) }
            )
            return@Scaffold
         }
         StorageAccess.Ready -> Unit
      }

      PickerContent(
         mode = mode,
         rootPath = rootPath,
         topSpacing = topSpacing,
         fileSpacing = fileSpacing,
         folderSpacing = folderSpacing,
         crossesOnLeft = crossesOnLeft,
         checkboxesOnLeft = checkboxesOnLeft,
         modifier = Modifier.fillMaxSize().padding(padding),
         onConfirm = onConfirm,
         onBack = onBack
      )
   }
}

@Composable
private fun PickerContent(
   mode: PickerMode,
   rootPath: String,
   topSpacing: Int,
   fileSpacing: Int,
   folderSpacing: Int,
   crossesOnLeft: Boolean,
   checkboxesOnLeft: Boolean,
   modifier: Modifier,
   onConfirm: (List<File>) -> Unit,
   onBack: () -> Unit
) {
   val allowMultiple = mode != PickerMode.FoldersOnly

   var currentPath by rememberSaveable { mutableStateOf(rootPath) }
   // Absolute paths of the marked items. Single-select modes keep at most one.
   val selected = remember { mutableStateListOf<String>() }

   // Fraction of the flexible (two-pane) height given to the top pane; the bottom
   // pane gets the rest. Adjustable by dragging the divider. Default ~0.34
   // preserves the previous 1:2 split. Persisted across rotation.
   var topFraction by rememberSaveable { mutableStateOf(0.34f) }
   // Measured pixel heights of the two panes, used to translate a drag delta into
   // a fraction change. Summed on each drag; no need to persist.
   var topPaneHeightPx by remember { mutableStateOf(0) }
   var bottomPaneHeightPx by remember { mutableStateOf(0) }

   // Independent sort settings for the two panes; persisted across rotation. Enums
   // are Serializable, so the default rememberSaveable autoSaver stores them fine.
   var topSortKey by rememberSaveable { mutableStateOf(SortKey.NAME) }
   var topAsc by rememberSaveable { mutableStateOf(true) }
   var bottomSortKey by rememberSaveable { mutableStateOf(SortKey.NAME) }
   var bottomAsc by rememberSaveable { mutableStateOf(true) }
   var topSortMenu by remember { mutableStateOf(false) }
   var bottomSortMenu by remember { mutableStateOf(false) }

   // Thin, non-interactive scroll indicators on the right edge of each pane.
   val topListState = rememberLazyListState()
   val bottomListState = rememberLazyListState()

   // Path of the item just added to the selection; consumed by the effect below to
   // scroll the top pane to wherever the active sort places it.
   var pendingScrollPath by remember { mutableStateOf<String?>(null) }

   val atRoot = currentPath == rootPath

   fun goUp() {
      if (!atRoot) File(currentPath).parentFile?.let { currentPath = it.absolutePath }
   }

   // Up-navigate first, only leaving the picker once at the root.
   BackHandler {
      if (atRoot) onBack() else goUp()
   }

   fun toggle(path: String) {
      if (selected.contains(path)) {
         selected.remove(path)
      } else {
         if (!allowMultiple) selected.clear()
         selected.add(path)
         pendingScrollPath = path
      }
   }

   // Bottom pane: list the current directory off the main thread (stat syscalls must
   // never run per visible row per frame — that tanked the scroll fps). Sorting is a
   // separate cheap step so changing the sort doesn't re-list the directory.
   val entries by produceState<List<BrowserEntry>>(initialValue = emptyList(), currentPath) {
      value = withContext(Dispatchers.IO) {
         File(currentPath).listFiles()?.map { statEntry(it) } ?: emptyList()
      }
   }
   val sortedEntries = remember(entries, bottomSortKey, bottomAsc) {
      sortEntries(entries, bottomSortKey, bottomAsc)
   }

   // Top pane: stat the marked paths off the main thread, then sort. Files are cheap
   // to stat; folders skip recursive sizing (see statEntry / sortEntries).
   val topEntries by produceState<List<BrowserEntry>>(initialValue = emptyList(), selected.toList()) {
      value = withContext(Dispatchers.IO) { selected.map { statEntry(File(it)) } }
   }
   val sortedTopEntries = remember(topEntries, topSortKey, topAsc) {
      sortEntries(topEntries, topSortKey, topAsc)
   }

   // When a new item is added, jump the top pane to it (at its sorted position) so the
   // user immediately sees the just-added entry. Cleared once the item is in view.
   LaunchedEffect(sortedTopEntries, pendingScrollPath) {
      val path = pendingScrollPath ?: return@LaunchedEffect
      val index = sortedTopEntries.indexOfFirst { it.absolutePath == path }
      if (index >= 0) {
         topListState.scrollToItem(index)
         pendingScrollPath = null
      }
   }

   Column(modifier = modifier) {
      // --- Top pane: the current selection ---
      // Single-select modes hold at most one item, so their sort control is hidden.
      Row(
         modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
         verticalAlignment = Alignment.CenterVertically
      ) {
         Text(
            text = stringResource(R.string.picker_selected_count, selected.size),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp)
         )
         if (allowMultiple) {
            Box {
               IconButton(onClick = { topSortMenu = true }) {
                  Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.cd_picker_sort))
               }
               SortMenu(
                  expanded = topSortMenu,
                  sortKey = topSortKey,
                  ascending = topAsc,
                  onDismiss = { topSortMenu = false },
                  onPick = { key -> if (key == topSortKey) topAsc = !topAsc else topSortKey = key }
               )
            }
         }
      }
      Box(
         modifier = Modifier
            .weight(topFraction)
            .fillMaxWidth()
            .onSizeChanged { topPaneHeightPx = it.height }
      ) {
         if (selected.isEmpty()) {
            Text(
               text = stringResource(R.string.picker_nothing_selected),
               style = MaterialTheme.typography.bodyMedium,
               color = MaterialTheme.colorScheme.onSurfaceVariant,
               modifier = Modifier.align(Alignment.Center).padding(16.dp)
            )
         } else {
            LazyColumn(
               state = topListState,
               modifier = Modifier.fillMaxSize().simpleVerticalScrollbar(topListState)
            ) {
               items(sortedTopEntries, key = { it.absolutePath }) { entry ->
                  SelectedRow(
                     file = File(entry.absolutePath),
                     spacing = topSpacing,
                     crossesOnLeft = crossesOnLeft,
                     onRemove = { selected.remove(entry.absolutePath) }
                  )
               }
            }
         }
      }

      PaneResizeHandle(
         onDrag = { dragAmount ->
            val total = topPaneHeightPx + bottomPaneHeightPx
            if (total > 0) {
               topFraction = (topFraction + dragAmount / total).coerceIn(0.15f, 0.85f)
            }
         }
      )

      // --- Bottom pane: the filesystem browser ---
      Row(
         modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
         verticalAlignment = Alignment.CenterVertically
      ) {
         IconButton(onClick = { goUp() }, enabled = !atRoot) {
            Icon(Icons.Filled.ArrowUpward, stringResource(R.string.picker_up))
         }
         // Long paths don't fit; make the breadcrumb scroll horizontally instead of
         // truncating, so every segment is reachable. Stays on one line (no wrap).
         Text(
            text = displayPath(currentPath, rootPath),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
               .weight(1f)
               .horizontalScroll(rememberScrollState())
         )
         Box {
            IconButton(onClick = { bottomSortMenu = true }) {
               Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.cd_picker_sort))
            }
            SortMenu(
               expanded = bottomSortMenu,
               sortKey = bottomSortKey,
               ascending = bottomAsc,
               onDismiss = { bottomSortMenu = false },
               onPick = { key -> if (key == bottomSortKey) bottomAsc = !bottomAsc else bottomSortKey = key }
            )
         }
      }

      Box(
         modifier = Modifier
            .weight(1f - topFraction)
            .fillMaxWidth()
            .onSizeChanged { bottomPaneHeightPx = it.height }
      ) {
         if (entries.isEmpty()) {
            Text(
               text = stringResource(R.string.picker_empty_folder),
               style = MaterialTheme.typography.bodyMedium,
               color = MaterialTheme.colorScheme.onSurfaceVariant,
               modifier = Modifier.align(Alignment.Center).padding(16.dp)
            )
         } else {
            LazyColumn(
               state = bottomListState,
               modifier = Modifier.fillMaxSize().simpleVerticalScrollbar(bottomListState)
            ) {
               // contentType lets the LazyColumn pool/reuse row slots by kind:
               // folder rows (single line) and file rows (two lines) differ
               // structurally, so tagging them avoids re-composing from scratch
               // when scrolling through a mixed list.
               items(
                  sortedEntries,
                  key = { it.absolutePath },
                  contentType = { it.isDirectory }
               ) { entry ->
                  BrowserRow(
                     entry = entry,
                     mode = mode,
                     spacing = if (entry.isDirectory) folderSpacing else fileSpacing,
                     checkboxesOnLeft = checkboxesOnLeft,
                     isSelected = selected.contains(entry.absolutePath),
                     onNavigate = { currentPath = entry.absolutePath },
                     onToggle = { toggle(entry.absolutePath) }
                  )
               }
            }
         }
      }

      HorizontalDivider()

      // --- Bottom bar: cancel / confirm ---
      Row(
         modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
         horizontalArrangement = Arrangement.End,
         verticalAlignment = Alignment.CenterVertically
      ) {
         TextButton(onClick = onBack) {
            Text(stringResource(R.string.action_cancel))
         }
         Spacer(Modifier.width(8.dp))
         Button(
            onClick = { onConfirm(selected.map { File(it) }) },
            enabled = selected.isNotEmpty()
         ) {
            Text(stringResource(R.string.action_done))
         }
      }
   }
}

/**
 * Dropdown of the four sort criteria. The active criterion is tinted and shows a
 * direction arrow; tapping it flips the direction, tapping another switches to it
 * (keeping the current direction). An empty leading slot on inactive rows keeps all
 * labels aligned with the active one.
 */
@Composable
private fun SortMenu(
   expanded: Boolean,
   sortKey: SortKey,
   ascending: Boolean,
   onDismiss: () -> Unit,
   onPick: (SortKey) -> Unit
) {
   DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
      Text(
         text = stringResource(R.string.picker_sort_by),
         style = MaterialTheme.typography.titleSmall,
         modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
      )
      HorizontalDivider()
      SortKey.values().forEach { key ->
         val active = key == sortKey
         AppDropdownMenuItem(
            onClick = { onPick(key) },
            leadingIcon = {
               if (active) {
                  Icon(
                     if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                     contentDescription = null,
                     tint = MaterialTheme.colorScheme.primary
                  )
               }
            }
         ) {
            Text(
               text = when (key) {
                  SortKey.NAME -> stringResource(R.string.picker_sort_name)
                  SortKey.SIZE -> stringResource(R.string.picker_sort_size)
                  SortKey.DATE -> stringResource(R.string.picker_sort_date)
                  SortKey.TYPE -> stringResource(R.string.picker_sort_type)
               },
               color = if (active) MaterialTheme.colorScheme.primary else Color.Unspecified
            )
         }
      }
   }
}

@Composable
private fun SelectedRow(file: File, spacing: Int, crossesOnLeft: Boolean, onRemove: () -> Unit) {
   // The narrower (8dp) padding goes on whichever side the ✕ sits, so the button
   // stays close to the screen edge; the opposite side keeps the 16dp margin.
   Row(
      modifier = Modifier.fillMaxWidth().padding(
         start = if (crossesOnLeft) 8.dp else 16.dp,
         top = spacing.dp,
         end = if (crossesOnLeft) 16.dp else 8.dp,
         bottom = spacing.dp
      ),
      verticalAlignment = Alignment.CenterVertically
   ) {
      // Pin the ✕ to a 24dp layout slot (same trick as the browser checkbox) so its
      // 48dp IconButton touch target doesn't bloat the row; the target still
      // overflows the box for comfortable tapping.
      val removeButton = @Composable {
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

      if (crossesOnLeft) removeButton()
      Icon(
         imageVector = iconFor(file),
         contentDescription = null,
         modifier = Modifier.size(20.dp),
         tint = MaterialTheme.colorScheme.primary
      )
      Spacer(Modifier.width(8.dp))
      Column(modifier = Modifier.weight(1f)) {
         Text(
            text = file.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
         )
         // Folder size needs a recursive walk, so compute it off the main thread
         // and show "…" until it's ready. Files report length() instantly.
         val folderSize by produceState<Long?>(initialValue = null, file.absolutePath) {
            if (file.isDirectory) value = withContext(Dispatchers.IO) {
               file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
         }
         val sub = if (file.isDirectory) folderSize?.let { fmtBytes(it) } ?: "…"
                   else fileSubtitle(file)
         Text(
            text = sub,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
         )
      }
      if (!crossesOnLeft) removeButton()
   }
}

@Composable
private fun BrowserRow(
   entry: BrowserEntry,
   mode: PickerMode,
   spacing: Int,
   checkboxesOnLeft: Boolean,
   isSelected: Boolean,
   onNavigate: () -> Unit,
   onToggle: () -> Unit
) {
   val isDir = entry.isDirectory
   // What this row can do depends on the mode:
   //  - FilesOnly:       files are checkable, folders navigate only.
   //  - FoldersOnly:     folders are checkable AND navigable; files are inert context.
   //  - FilesAndFolders: everything is checkable; folders are also navigable.
   val checkable = when (mode) {
      PickerMode.FilesOnly -> !isDir
      PickerMode.FoldersOnly -> isDir
      PickerMode.FilesAndFolders -> true
   }
   val navigable = isDir
   val inert = !checkable && !navigable

   Row(
      modifier = Modifier
         .fillMaxWidth()
         .then(if (navigable) Modifier.clickable(onClick = onNavigate) else Modifier)
         .padding(horizontal = 16.dp, vertical = spacing.dp),
      verticalAlignment = Alignment.CenterVertically
   ) {
      // Checkbox slot. On the left it's next to the name (so it isn't stranded far
      // from a short filename) and the 24dp box is reserved even for non-checkable
      // rows to keep icons/names column-aligned. On the right it's shown only when
      // checkable (no alignment need). requiredSize pins the layout slot to 24dp
      // while the checkbox keeps its 48dp interactive touch target (it overflows
      // the box for hit-testing).
      val checkboxSlot = @Composable {
         Box(modifier = Modifier.requiredSize(24.dp), contentAlignment = Alignment.Center) {
            if (checkable) Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
         }
      }

      if (checkboxesOnLeft) {
         checkboxSlot()
         Spacer(Modifier.width(8.dp))
      }
      val tint = when {
         inert -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
         isDir -> MaterialTheme.colorScheme.primary
         else -> MaterialTheme.colorScheme.onSurfaceVariant
      }
      Icon(
         imageVector = entry.icon,
         contentDescription = null,
         modifier = Modifier.size(22.dp),
         tint = tint
      )
      Spacer(Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
         Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (inert) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
         )
         if (!isDir && entry.subtitle != null) {
            Text(
               text = entry.subtitle,
               style = MaterialTheme.typography.bodySmall,
               color = MaterialTheme.colorScheme.onSurfaceVariant
            )
         }
      }
      if (!checkboxesOnLeft && checkable) {
         Spacer(Modifier.width(8.dp))
         checkboxSlot()
      }
   }
}

/**
 * Draggable divider between the two panes. Renders as a full-width band a bit
 * taller than the divider line (for a comfortable touch target) with a centered
 * 2dp line and a short rounded "grip" pill so it reads as draggable. Vertical
 * drags are reported as raw pixel deltas via [onDrag]; the caller turns them into
 * a split-fraction change.
 */
@Composable
private fun PaneResizeHandle(onDrag: (Float) -> Unit) {
   Box(
      modifier = Modifier
         .fillMaxWidth()
         .height(16.dp)
         .pointerInput(Unit) {
            detectVerticalDragGestures { _, dragAmount -> onDrag(dragAmount) }
         },
      contentAlignment = Alignment.Center
   ) {
      HorizontalDivider(thickness = 2.dp)
      Box(
         modifier = Modifier
            .size(width = 40.dp, height = 4.dp)
            .background(
               color = MaterialTheme.colorScheme.outline,
               shape = RoundedCornerShape(2.dp)
            )
      )
   }
}

/** Full-screen stand-in for the browser while storage access isn't usable. */
@Composable
private fun PermissionGate(
   modifier: Modifier,
   message: String,
   action: String,
   onAction: () -> Unit
) {
   Column(
      modifier = modifier.padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally
   ) {
      Text(
         text = message,
         style = MaterialTheme.typography.bodyLarge,
         color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Button(onClick = onAction) {
         Text(action)
      }
   }
}

/**
 * TEMP tuning input for a row-spacing value (dp). Lives in the gear menu; will be
 * removed once the spacings are fixed as constants.
 */
@Composable
private fun SpacingField(label: String, value: Int, onValue: (Int) -> Unit) {
   OutlinedTextField(
      value = value.toString(),
      onValueChange = { txt ->
         val n = txt.filter { it.isDigit() }.toIntOrNull() ?: 0
         onValue(n.coerceIn(0, 40))
      },
      label = { Text(label) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).width(200.dp)
   )
}

/** A GUI-menu row: a label with a Left/Right side toggle button. */
@Composable
private fun SideToggleRow(label: String, isLeft: Boolean, onChange: (Boolean) -> Unit) {
   Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically
   ) {
      Text(label)
      Spacer(Modifier.width(12.dp).weight(1f))
      TextButton(onClick = { onChange(!isLeft) }) {
         Text(stringResource(if (isLeft) R.string.picker_side_left else R.string.picker_side_right))
      }
   }
}

/** Sub-line for a file row: "ext · size" (lowercase ext), or just size when none. */
private fun fileSubtitle(file: File): String {
   val ext = file.extension
   val size = fmtBytes(file.length())
   return if (ext.isNotEmpty()) "${ext.lowercase()} · $size" else size
}

private fun iconFor(isDirectory: Boolean, name: String) = when {
   isDirectory -> Icons.Filled.Folder
   name.endsWith(".nsz", ignoreCase = true) ||
      name.endsWith(".xcz", ignoreCase = true) -> Icons.Filled.Description
   else -> Icons.Filled.InsertDriveFile
}

private fun iconFor(file: File) = iconFor(file.isDirectory, file.name)

/** Path shown in the breadcrumb: "/" at the storage root, else the part below it. */
private fun displayPath(currentPath: String, rootPath: String): String {
   if (currentPath == rootPath) return "/"
   val rel = currentPath.removePrefix(rootPath)
   return rel.ifEmpty { "/" }
}
