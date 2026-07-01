package com.androNSZ.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androNSZ.R
import com.androNSZ.model.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatusLogPanel(statusLog: List<LogEntry>) {
   if (statusLog.isEmpty()) return

   val context = LocalContext.current
   val scope = rememberCoroutineScope()

   var logVisible by remember { mutableStateOf(false) }
   var wordWrap by remember { mutableStateOf(true) }

   Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
   ) {
      Row(
         modifier = Modifier
            .weight(1f)
            .clickable { logVisible = !logVisible }
            .padding(vertical = 4.dp),
         verticalAlignment = Alignment.CenterVertically
      ) {
         Icon(
            imageVector = if (logVisible) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
         )
         Spacer(Modifier.width(4.dp))
         Text(if (logVisible) stringResource(R.string.action_hide_log) else stringResource(R.string.action_show_log))
      }
      TextButton(onClick = { wordWrap = !wordWrap }) {
         Text(stringResource(if (wordWrap) R.string.action_wrap_lines_on else R.string.action_wrap_lines_off))
      }
   }

   if (logVisible) {
      val consumeAllScroll = remember {
         object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
         }
      }
      Surface(
         modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp, max = 300.dp)
            .nestedScroll(consumeAllScroll),
         color = MaterialTheme.colorScheme.surfaceVariant,
         shape = MaterialTheme.shapes.medium
      ) {
         val logScrollState = rememberScrollState()
         val hScrollState = rememberScrollState()
         LaunchedEffect(statusLog.size) {
            logScrollState.animateScrollTo(logScrollState.maxValue)
         }
         Column(
            modifier = Modifier
               .padding(10.dp)
               .let { if (!wordWrap) it.horizontalScroll(hScrollState) else it }
               .verticalScroll(logScrollState)
         ) {
            for (entry in statusLog) {
               val color = when (entry.tag) {
                  "VERIFIED" -> Color(0xFF4CAF50)
                  "ERROR" -> MaterialTheme.colorScheme.error
                  else -> MaterialTheme.colorScheme.onSurface
               }
               Text(
                  text = "[${entry.tag}] ${entry.message}",
                  style = MaterialTheme.typography.bodySmall,
                  fontFamily = FontFamily.Monospace,
                  color = color,
                  softWrap = wordWrap,
                  overflow = TextOverflow.Clip,
               )
            }
         }
      }

      // Dump the on-screen log to its own file (kept separate from the engine's
      // nsz_debug.log and the folder-mode log) so it can be pulled off the device
      // for troubleshooting without copying text by hand. Works for both modes.
      Row(
         modifier = Modifier.fillMaxWidth(),
         horizontalArrangement = Arrangement.End
      ) {
         TextButton(onClick = {
            scope.launch {
               val result = withContext(Dispatchers.IO) {
                  runCatching { writeScreenLog(context, statusLog) }
               }
               val msg = result.fold(
                  onSuccess = { context.getString(R.string.msg_screen_log_saved, it) },
                  onFailure = { context.getString(R.string.msg_screen_log_save_failed, it.message ?: "") }
               )
               Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
         }) {
            Text(stringResource(R.string.action_save_log))
         }
      }
   }
}

/**
 * Writes the current on-screen log to nsz_screen_log.txt in the app's external
 * files dir, overwriting any previous snapshot. Returns the absolute path.
 */
private fun writeScreenLog(context: Context, log: List<LogEntry>): String {
   val file = File(context.getExternalFilesDir(null), "nsz_screen_log.txt")
   val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
   file.printWriter().use { w ->
      w.println("=== AndroNSZ Screen Log ===")
      w.println("Saved: $timestamp")
      w.println()
      for (entry in log) {
         w.println("[${entry.tag}] ${entry.message}")
      }
   }
   return file.absolutePath
}
