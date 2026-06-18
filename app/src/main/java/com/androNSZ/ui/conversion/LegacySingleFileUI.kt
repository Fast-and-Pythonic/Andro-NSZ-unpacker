package com.androNSZ.ui.conversion

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.androNSZ.R
import com.androNSZ.ui.components.ElapsedTimeRow
import com.androNSZ.util.fmtBytes
import com.androNSZ.util.resolveDisplayName
import com.androNSZ.viewmodel.MainViewModel

@Composable
fun LegacySingleFileUI(vm: MainViewModel, padding: PaddingValues, onInstallKeys: () -> Unit) {
   val context = LocalContext.current

   val filePicker = rememberLauncherForActivityResult(
      ActivityResultContracts.OpenDocument()
   ) { uri ->
      if (uri != null) {
         val name = resolveDisplayName(context, uri)
         vm.pickFile(uri, name)
      }
   }

   Column(
      modifier = Modifier
         .fillMaxSize()
         .padding(padding)
         .verticalScroll(rememberScrollState())
         .padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(20.dp),
   ) {

      /* ── prod.keys warning (only when not installed) ── */
      if (!vm.keysInstalled) {
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

         /* ── File picker card ── */
         Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = { if (!vm.isConverting) filePicker.launch(arrayOf("*/*")) },
            enabled = vm.keysInstalled && !vm.isConverting,
            colors = CardDefaults.cardColors(
               containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
         ) {
            Column(
               modifier = Modifier
                  .fillMaxWidth()
                  .padding(16.dp),
               horizontalAlignment = Alignment.CenterHorizontally,
            ) {
               Text(
                  text = if (vm.selectedName != null) stringResource(R.string.msg_selected_file) else stringResource(R.string.msg_tap_to_select),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
               )
               if (vm.selectedName != null) {
                  Spacer(Modifier.height(4.dp))
                  Text(
                     text = vm.selectedName!!,
                     style = MaterialTheme.typography.bodyLarge,
                     maxLines = 2,
                     overflow = TextOverflow.Ellipsis,
                     textAlign = TextAlign.Center,
                  )
               }
            }
         }

         /* ── Output destination note ── */
         Text(
            text = stringResource(R.string.label_output_downloads),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
         )

         /* ── Convert / Cancel button ── */
         if (vm.isConverting) {
            Button(
               onClick = { vm.cancel() },
               modifier = Modifier.fillMaxWidth(),
               colors = ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.error
               )
            ) {
               Text(stringResource(R.string.action_cancel))
            }
         } else {
            Button(
               onClick  = { vm.startConversion(context) },
               enabled  = vm.selectedUri != null,
               modifier = Modifier.fillMaxWidth(),
            ) {
               Text(stringResource(R.string.action_convert_to_nsp))
            }
         }

         /* ── Progress section ── */
         if (vm.isConverting || vm.progress != null) {
            val p = vm.progress
            if (p != null) {
               Column(
                  modifier = Modifier.fillMaxWidth(),
                  verticalArrangement = Arrangement.spacedBy(6.dp),
               ) {
                  LinearProgressIndicator(
                     progress = { p.percent },
                     modifier = Modifier.fillMaxWidth(),
                  )
                  Row(
                     modifier = Modifier.fillMaxWidth(),
                     horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                     Text(
                        text = "%.1f%%".format(p.percent * 100f),
                        style = MaterialTheme.typography.bodySmall,
                     )
                     if (p.totalBytes > 0) {
                        Text(
                           text = "${fmtBytes(p.doneBytes)} / ${fmtBytes(p.totalBytes)}",
                           style = MaterialTheme.typography.bodySmall,
                        )
                     }
                     Text(
                        text = "%.1f MB/s".format(p.speedMBps),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                     )
                  }
                  ElapsedTimeRow(vm.elapsedMs)
               }
            } else {
               LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
         }

         /* ── Status message ── */
         val msg = vm.statusMessage
         if (msg != null) {
            Card(
               modifier = Modifier.fillMaxWidth(),
               colors = CardDefaults.cardColors(
                  containerColor = if (vm.isSuccess)
                     MaterialTheme.colorScheme.primaryContainer
                  else
                     MaterialTheme.colorScheme.errorContainer
               )
            ) {
               Text(
                  text = msg,
                  modifier = Modifier.padding(12.dp),
                  style = MaterialTheme.typography.bodyMedium,
                  color = if (vm.isSuccess)
                     MaterialTheme.colorScheme.onPrimaryContainer
                  else
                     MaterialTheme.colorScheme.onErrorContainer,
               )
            }
         }

         /* ── Conversion log (collapsible) ── */
         if (vm.statusLog.isNotEmpty()) {
            var logVisible by remember { mutableStateOf(false) }
            var wordWrap by remember { mutableStateOf(true) }

            Row(
               modifier = Modifier.fillMaxWidth(),
               verticalAlignment = Alignment.CenterVertically,
            ) {
               TextButton(
                  onClick = { logVisible = !logVisible },
                  modifier = Modifier.weight(1f),
               ) {
                  Icon(
                     imageVector = if (logVisible) Icons.Filled.KeyboardArrowUp
                                   else Icons.Filled.KeyboardArrowDown,
                     contentDescription = null,
                     modifier = Modifier.size(18.dp),
                  )
                  Spacer(Modifier.width(4.dp))
                  Text(if (logVisible) stringResource(R.string.action_hide_log) else stringResource(R.string.action_show_log))
               }
               TextButton(onClick = { wordWrap = !wordWrap }) {
                  Text(stringResource(if (wordWrap) R.string.action_wrap_lines_on else R.string.action_wrap_lines_off))
               }
            }

            if (logVisible) {
               val logScrollState = rememberScrollState()
               val hScrollState = rememberScrollState()
               val consumeAllScroll = remember {
                  object : NestedScrollConnection {
                     override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
                  }
               }
               LaunchedEffect(vm.statusLog.size) {
                  logScrollState.animateScrollTo(logScrollState.maxValue)
               }
               Surface(
                  modifier = Modifier
                     .fillMaxWidth()
                     .heightIn(min = 80.dp, max = 390.dp)
                     .nestedScroll(consumeAllScroll),
                  color  = MaterialTheme.colorScheme.surfaceVariant,
                  shape  = MaterialTheme.shapes.medium,
               ) {
                  Column(
                     modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .let { if (!wordWrap) it.horizontalScroll(hScrollState) else it }
                        .verticalScroll(logScrollState),
                  ) {
                     for (entry in vm.statusLog) {
                        val color = when (entry.tag) {
                           "VERIFIED" -> Color(0xFF4CAF50)
                           "NCA_HASH" -> MaterialTheme.colorScheme.onSurfaceVariant
                           "ERROR"    -> MaterialTheme.colorScheme.error
                           else       -> MaterialTheme.colorScheme.onSurface
                        }
                        Text(
                           text       = "[${entry.tag}]${entry.message}",
                           style      = MaterialTheme.typography.bodySmall,
                           fontFamily = FontFamily.Monospace,
                           color      = color,
                           softWrap   = wordWrap,
                           overflow   = TextOverflow.Clip,
                        )
                     }
                  }
               }
            }
         }
   }
}
