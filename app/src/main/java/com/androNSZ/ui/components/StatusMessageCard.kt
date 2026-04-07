package com.androNSZ.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StatusMessageCard(message: String?, isSuccess: Boolean) {
   if (message == null) return

   Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(
         containerColor = if (isSuccess)
            MaterialTheme.colorScheme.primaryContainer
         else
            MaterialTheme.colorScheme.errorContainer
      )
   ) {
      Text(
         text = message,
         modifier = Modifier.padding(12.dp),
         style = MaterialTheme.typography.bodyMedium,
         color = if (isSuccess)
            MaterialTheme.colorScheme.onPrimaryContainer
         else
            MaterialTheme.colorScheme.onErrorContainer
      )
   }
}
