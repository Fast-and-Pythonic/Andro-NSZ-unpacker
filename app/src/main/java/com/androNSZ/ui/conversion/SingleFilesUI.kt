package com.androNSZ.ui.conversion

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.androNSZ.R
import com.androNSZ.model.FileEntry
import com.androNSZ.model.FileStatus
import com.androNSZ.ui.components.StatusLogPanel
import com.androNSZ.ui.components.simpleVerticalScrollbar
import com.androNSZ.ui.theme.SuccessGreen
import com.androNSZ.ui.components.StatusMessageCard
import com.androNSZ.util.fmtBytes
import com.androNSZ.util.fmtDuration
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

   // Before unpacking starts the queue owns the whole screen: it fills the space
   // above the (bottom-pinned) unpack button and the page itself does not scroll.
   // Once conversion begins — and afterwards, while the progress card and log are
   // shown — the queue shrinks to a bounded box and the whole screen becomes
   // scrollable so every element stays reachable.
   val expanded = !vm.isConverting && vm.progress == null
   val scrollState = rememberScrollState()

   Column(
      modifier = Modifier
         .fillMaxSize()
         .padding(padding)
         .then(if (expanded) Modifier else Modifier.verticalScroll(scrollState))
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

         val listState = rememberLazyListState()
         // Keep list scroll self-contained inside the now-scrollable page: swallow
         // any residual scroll so a gesture over the queue doesn't bleed into the
         // outer verticalScroll. Same trick as StatusLogPanel.
         val consumeResidualScroll = remember {
            object : NestedScrollConnection {
               override fun onPostScroll(
                  consumed: Offset,
                  available: Offset,
                  source: NestedScrollSource
               ): Offset = available
            }
         }
         // Idle: fill all remaining space (weight) so the button sits at the bottom.
         // Converting/done: bounded box that shares the screen with the other cards.
         val queueSize = if (expanded) Modifier.weight(1f)
                         else Modifier.heightIn(min = 160.dp, max = 320.dp)
         LazyColumn(
            state = listState,
            modifier = queueSize
               .nestedScroll(consumeResidualScroll)
               .simpleVerticalScrollbar(listState),
            contentPadding = PaddingValues(end = 8.dp),
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
               .then(if (expanded) Modifier.weight(1f) else Modifier.height(160.dp))
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

               Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
               ) {
                  Text(
                     text = if (vm.isConverting)
                        stringResource(R.string.status_unpacking)
                     else
                        stringResource(R.string.status_unpacked),
                     style = MaterialTheme.typography.titleMedium
                  )
                  Text(
                     text = fmtDuration(vm.elapsedMs),
                     style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                     fontSize = 14.sp,
                     color = MaterialTheme.colorScheme.onSurface
                  )
               }

               // Final summary: average speed across all unpacked files.
               vm.batchAverageSpeedMBps?.let { avg ->
                  if (!vm.isConverting) {
                     Text(
                        text = stringResource(R.string.format_average_speed, avg),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                     )
                  }
               }

               if (isMultiFile && overall != null) {
                  LinearProgressIndicator(
                     progress = { overall.percent },
                     modifier = Modifier.fillMaxWidth()
                  )
                  Row(
                     modifier = Modifier.fillMaxWidth(),
                     horizontalArrangement = Arrangement.SpaceBetween
                  ) {
                     MetricText(
                        text = stringResource(R.string.format_files_processed, vm.batchProcessedFiles, vm.batchTotalFiles)
                     )
                     MetricText(text = "%.1f%%".format(overall.displayPercent * 100f))
                     MetricText(text = "%.1f MB/s".format(overall.speedMBps))
                  }
                  Text(
                     text = "${fmtBytes(overall.displayDoneBytes)} / ${fmtBytes(overall.displayTotalBytes)}",
                     style = MaterialTheme.typography.bodySmall
                  )
               }

               if (isMultiFile) {
                  // One progress bar per file currently converting in parallel.
                  val active = vm.activeFileProgress.entries.sortedBy { it.key }
                  active.forEach { (idx, p) ->
                     // Thin rule separating each file's stats from the block above.
                     // A hairline + outlineVariant blends into the surfaceVariant card,
                     // so use 1dp and a higher-contrast colour. fullBleedWidth makes it
                     // span the whole card, escaping the Column's 16dp side padding.
                     HorizontalDivider(
                        modifier = Modifier.fullBleedWidth(16.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                     )
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
                        MetricText(text = "%.1f MB/s".format(p.speedMBps))
                        MetricText(text = "%.1f%%".format(p.displayPercent * 100f))
                        if (p.totalBytes > 0) {
                           MetricText(
                              text = "${fmtBytes(p.displayDoneBytes)} / ${fmtBytes(p.displayTotalBytes)}"
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
                        MetricText(text = "%.1f MB/s".format(p.speedMBps))
                        MetricText(text = "%.1f%%".format(p.displayPercent * 100f))
                        if (p.totalBytes > 0) {
                           MetricText(
                              text = "${fmtBytes(p.displayDoneBytes)} / ${fmtBytes(p.displayTotalBytes)}"
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

/**
 * Lets a child stretch [horizontalPadding] beyond each side of its parent — used
 * to make a divider span the full card width despite the Column's side padding.
 * It measures the child wider by 2×padding and shifts it left by one padding.
 */
internal fun Modifier.fullBleedWidth(horizontalPadding: Dp): Modifier = layout { measurable, constraints ->
   val pad = horizontalPadding.roundToPx()
   val targetWidth = constraints.maxWidth + pad * 2
   // Measure the child wider than the available space (both edges)...
   val placeable = measurable.measure(
      constraints.copy(minWidth = targetWidth, maxWidth = targetWidth)
   )
   // ...but report the original width so the parent layout is undisturbed; only
   // the drawing bleeds out, shifted left by one padding so it reaches both edges.
   layout(constraints.maxWidth, placeable.height) {
      placeable.place(-pad, 0)
   }
}

/**
 * A progress metric rendered at its natural width in monospace. The parent row
 * spaces metrics with `Arrangement.SpaceBetween`, so the gaps between them are
 * equal while each value keeps its real width — nothing is squeezed into a fixed
 * slot, so no digits get clipped.
 */
@Composable
private fun MetricText(text: String) {
   Text(
      text = text,
      style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      maxLines = 1,
      softWrap = false
   )
}

/** Font size of the stats line in every queue card (both completed and pending states). */
private val QueueStatsFontSize = 12.sp

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
            // All segments are joined here with the same "  |  " separator. (Keeping
            // a separator inside a string resource won't match: Android collapses its
            // double spaces to one, making that gap visibly narrower.)
            val sep = "  |  "
            val statsColor = MaterialTheme.colorScheme.onSurfaceVariant
            if (file.status == FileStatus.Completed && file.unpackDurationMs != null) {
               // Completed | time | speed | size before → size after.
               // The "before → after" size segment is kept whole: if it doesn't
               // fit, it moves to its own line as a unit (never split mid-value)
               // and the "|" before it is dropped, so no separator dangles after
               // the speed. See QueueStatsLine.
               val head = buildAnnotatedString {
                  withStyle(SpanStyle(color = SuccessGreen)) { append(statusText) }
                  append(sep)
                  append(fmtDuration(file.unpackDurationMs))
                  append(sep)
                  append("%.1f MB/s".format(file.unpackSpeedMBps ?: 0.0))
               }
               val after = file.unpackedSize
               val sizes = if (after != null && after > 0L) {
                  if (file.fileSize > 0L) "${fmtBytes(file.fileSize)} → ${fmtBytes(after)}"
                  else fmtBytes(after)
               } else null
               QueueStatsLine(head = head, sizes = sizes, color = statsColor)
            } else {
               // Pending / converting / failed: size | status.
               Text(
                  text = buildAnnotatedString {
                     if (sizeText != null) {
                        append(sizeText)
                        append(sep)
                     }
                     append(statusText)
                  },
                  style = MaterialTheme.typography.bodyMedium,
                  fontSize = QueueStatsFontSize,
                  color = statsColor
               )
            }
         }

         if (enabled && file.status == FileStatus.Pending) {
            IconButton(onClick = onRemove) {
               Icon(Icons.Filled.Close, stringResource(R.string.action_delete))
            }
         }
      }
   }
}

/**
 * Renders a completed file's stats as "[head] | [sizes]". When the whole line fits
 * the available width it stays on one line; otherwise [sizes] (e.g. "1.2 GB → 3.4 GB")
 * moves to its own line as a single unit and the separator before it is dropped —
 * so the value never splits across lines and no "|" dangles after the speed.
 *
 * The decision is measured against a fixed one-line string, so it is stable (no
 * flicker from the separator changing what fits).
 */
@Composable
private fun QueueStatsLine(
   head: AnnotatedString,
   sizes: String?,
   color: Color,
   sep: String = "  |  "
) {
   val style = MaterialTheme.typography.bodyMedium.copy(
      fontSize = QueueStatsFontSize,
      color = color
   )
   if (sizes == null) {
      Text(text = head, style = style)
      return
   }
   val measurer = rememberTextMeasurer()
   val oneLine = buildAnnotatedString {
      append(head)
      append(sep)
      append(sizes)
   }
   BoxWithConstraints {
      val fitsOneLine = measurer.measure(
         text = oneLine,
         style = style,
         softWrap = false
      ).size.width <= this@BoxWithConstraints.constraints.maxWidth
      if (fitsOneLine) {
         Text(text = oneLine, style = style, maxLines = 1, softWrap = false)
      } else {
         Column {
            Text(text = head, style = style)
            Text(text = sizes, style = style, maxLines = 1, softWrap = false)
         }
      }
   }
}
