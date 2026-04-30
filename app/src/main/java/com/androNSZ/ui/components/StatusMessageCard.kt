package com.androNSZ.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StatusMessageCard(message: String?, isSuccess: Boolean) {
   if (message == null) return

   val contentColor = if (isSuccess)
      MaterialTheme.colorScheme.onPrimaryContainer
   else
      MaterialTheme.colorScheme.onErrorContainer

   Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(
         containerColor = if (isSuccess)
            MaterialTheme.colorScheme.primaryContainer
         else
            MaterialTheme.colorScheme.errorContainer
      )
   ) {
      Column(modifier = Modifier.padding(12.dp)) {
         val lines = message.split("\n")
         val segments = mutableListOf<Any>()
         val currentBlock = StringBuilder()

         for (line in lines) {
            if (line.isNotEmpty() && line.all { it == '─' }) {
               if (currentBlock.isNotEmpty()) {
                  segments += currentBlock.toString().trimEnd('\n')
                  currentBlock.clear()
               }
               segments += Unit
            } else {
               currentBlock.appendLine(line)
            }
         }
         if (currentBlock.isNotEmpty()) {
            segments += currentBlock.toString().trimEnd('\n')
         }

         for (segment in segments) {
            if (segment is Unit) {
               HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            } else {
               Text(
                  text = segment as String,
                  style = MaterialTheme.typography.bodyMedium,
                  color = contentColor
               )
            }
         }
      }
   }
}
