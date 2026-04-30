package com.androNSZ.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.androNSZ.model.Compact2Stats

private val COL_WIDTH = 36.dp

@Composable
fun StatsCompact3Card(stats: Compact2Stats, isSuccess: Boolean) {
   val contentColor = if (isSuccess) MaterialTheme.colorScheme.onPrimaryContainer
                      else MaterialTheme.colorScheme.onErrorContainer
   val successColor = Color(0xFF4CAF50)
   val failedColor  = MaterialTheme.colorScheme.error

   // Измеряем ширину самого длинного лейбла, чтобы колонка была строго по содержимому
   val density     = LocalDensity.current
   val textMeasurer = rememberTextMeasurer()
   val labelStyle  = MaterialTheme.typography.bodyMedium
   val maxLabelWidthPx = stats.rows.maxOfOrNull { row ->
      textMeasurer.measure(row.label, labelStyle).size.width
   } ?: 0
   val labelColWidth = with(density) { maxLabelWidthPx.toDp() } + 12.dp
   val tableWidth   = labelColWidth + COL_WIDTH * 3

   Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(
         containerColor = if (isSuccess) MaterialTheme.colorScheme.primaryContainer
                          else MaterialTheme.colorScheme.errorContainer
      )
   ) {
      Column(
         modifier = Modifier.padding(12.dp),
         horizontalAlignment = Alignment.CenterHorizontally
      ) {
         Text(
            text = stats.headerLine,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor
         )
         Spacer(modifier = Modifier.height(10.dp))

         // Таблица: единый Column, строго tableWidth — разделители не расходятся
         Column(modifier = Modifier.width(tableWidth)) {

            // Заголовки колонок
            Row(modifier = Modifier.fillMaxWidth()) {
               Spacer(modifier = Modifier.width(labelColWidth))
               Text(
                  text = "✓",
                  modifier = Modifier.width(COL_WIDTH),
                  textAlign = TextAlign.End,
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  color = successColor
               )
               Text(
                  text = "✗",
                  modifier = Modifier.width(COL_WIDTH),
                  textAlign = TextAlign.End,
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  color = failedColor
               )
               Text(
                  text = "∑",
                  modifier = Modifier.width(COL_WIDTH),
                  textAlign = TextAlign.End,
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  color = contentColor
               )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Строки данных
            stats.rows.forEach { row ->
               Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically
               ) {
                  Text(
                     text = row.label,
                     modifier = Modifier.width(labelColWidth),
                     textAlign = TextAlign.Center,
                     style = MaterialTheme.typography.bodyMedium,
                     color = contentColor
                  )
                  Text(
                     text = "${row.success}",
                     modifier = Modifier.width(COL_WIDTH),
                     textAlign = TextAlign.End,
                     style = MaterialTheme.typography.bodyMedium,
                     fontWeight = FontWeight.Bold,
                     color = successColor
                  )
                  Text(
                     text = "${row.failed}",
                     modifier = Modifier.width(COL_WIDTH),
                     textAlign = TextAlign.End,
                     style = MaterialTheme.typography.bodyMedium,
                     fontWeight = FontWeight.Bold,
                     color = failedColor
                  )
                  Text(
                     text = "${row.total}",
                     modifier = Modifier.width(COL_WIDTH),
                     textAlign = TextAlign.End,
                     style = MaterialTheme.typography.bodyMedium,
                     fontWeight = FontWeight.Bold,
                     color = contentColor
                  )
               }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
         }

         Spacer(modifier = Modifier.height(20.dp))
         Text(
            text = stats.footerLine,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor
         )
      }
   }
}
