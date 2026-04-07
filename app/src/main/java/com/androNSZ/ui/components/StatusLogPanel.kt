package com.androNSZ.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.androNSZ.model.LogEntry

@Composable
fun StatusLogPanel(statusLog: List<LogEntry>) {
   if (statusLog.isEmpty()) return

   var logVisible by remember { mutableStateOf(false) }

   CompactToggleButton(
      expanded = logVisible,
      collapsedText = "Показать лог",
      expandedText = "Скрыть лог",
      onClick = { logVisible = !logVisible }
   )

   if (logVisible) {
      Surface(
         modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp, max = 200.dp),
         color = MaterialTheme.colorScheme.surfaceVariant,
         shape = MaterialTheme.shapes.medium
      ) {
         val logScrollState = rememberScrollState()
         LaunchedEffect(statusLog.size) {
            logScrollState.animateScrollTo(logScrollState.maxValue)
         }
         Column(
            modifier = Modifier
               .padding(10.dp)
               .verticalScroll(logScrollState)
         ) {
            for (entry in statusLog) {
               val color = when (entry.tag) {
                  "VERIFIED" -> Color(0xFF4CAF50)
                  "ERROR" -> MaterialTheme.colorScheme.error
                  else -> MaterialTheme.colorScheme.onSurface
               }
               Text(
                  text = "[${entry.tag}]${entry.message}",
                  style = MaterialTheme.typography.bodySmall,
                  fontFamily = FontFamily.Monospace,
                  color = color
               )
            }
         }
      }
   }
}
