package com.androNSZ.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

val compactToggleButtonPadding = PaddingValues(
   horizontal = 0.dp,
   vertical = 0.dp
)

@Composable
fun CompactToggleButton(
   expanded: Boolean,
   collapsedText: String,
   expandedText: String,
   onClick: () -> Unit,
   modifier: Modifier = Modifier
) {
   Row(
      modifier = modifier
         .fillMaxWidth()
         .clickable(onClick = onClick)
         .padding(compactToggleButtonPadding),
      verticalAlignment = Alignment.CenterVertically
   ) {
      Icon(
         imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
         contentDescription = null,
         tint = MaterialTheme.colorScheme.primary
      )
      Spacer(Modifier.width(4.dp))
      Text(if (expanded) expandedText else collapsedText)
   }
}
