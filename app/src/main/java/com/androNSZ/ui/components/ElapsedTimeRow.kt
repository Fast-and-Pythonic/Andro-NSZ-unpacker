package com.androNSZ.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androNSZ.R
import com.androNSZ.util.fmtDuration

/**
 * Shows the elapsed conversion time. Stays visible after the run finishes so
 * the final duration can be read off for speed comparison.
 */
@Composable
fun ElapsedTimeRow(elapsedMs: Long, modifier: Modifier = Modifier) {
   Row(
      modifier = modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Start
   ) {
      Icon(
         imageVector = Icons.Filled.Timer,
         contentDescription = null,
         modifier = Modifier.width(16.dp),
         tint = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Spacer(Modifier.width(6.dp))
      Text(
         text = stringResource(R.string.label_elapsed_time),
         style = MaterialTheme.typography.bodySmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Spacer(Modifier.width(6.dp))
      Text(
         text = fmtDuration(elapsedMs),
         style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
         fontSize = 14.sp,
         color = MaterialTheme.colorScheme.onSurface
      )
   }
}
