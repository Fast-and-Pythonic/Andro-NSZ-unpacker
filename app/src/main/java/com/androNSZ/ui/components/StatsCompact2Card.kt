package com.androNSZ.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.androNSZ.model.Compact2Stats

@Composable
fun StatsCompact2Card(stats: Compact2Stats, isSuccess: Boolean) {
   val contentColor = if (isSuccess) MaterialTheme.colorScheme.onPrimaryContainer
                      else MaterialTheme.colorScheme.onErrorContainer
   val successColor = Color(0xFF4CAF50)
   val failedColor  = MaterialTheme.colorScheme.error

   Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(
         containerColor = if (isSuccess) MaterialTheme.colorScheme.primaryContainer
                          else MaterialTheme.colorScheme.errorContainer
      )
   ) {
      Column(modifier = Modifier.padding(12.dp)) {
         Text(
            text = stats.headerLine,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor
         )
         Spacer(modifier = Modifier.height(8.dp))

         stats.rows.forEach { row ->
            Text(
               text = buildAnnotatedString {
                  withStyle(SpanStyle(color = contentColor)) { append("${row.label} — ") }
                  withStyle(SpanStyle(color = successColor, fontWeight = FontWeight.Bold)) { append("${row.success}") }
                  withStyle(SpanStyle(color = contentColor)) { append(" : ") }
                  withStyle(SpanStyle(color = failedColor, fontWeight = FontWeight.Bold)) { append("${row.failed}") }
                  withStyle(SpanStyle(color = contentColor, fontWeight = FontWeight.Bold)) { append(" / ${row.total}") }
               },
               style = MaterialTheme.typography.bodyMedium
            )
         }

         Spacer(modifier = Modifier.height(8.dp))
         Text(
            text = stats.footerLine,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor
         )
      }
   }
}
