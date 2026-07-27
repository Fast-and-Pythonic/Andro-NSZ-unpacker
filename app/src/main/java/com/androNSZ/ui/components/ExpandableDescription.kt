package com.androNSZ.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * An explanatory paragraph collapsed to a single line, with a chevron to open it.
 *
 * The Settings screen accumulated long descriptions — several of them explain *why*
 * a measurement works the way it does, which is worth having but drowns the controls
 * they belong to. Collapsed by default: a user who already knows what a setting does
 * should be able to see the settings, not the essays.
 *
 * The whole row is the hit target, not just the chevron — a one-line strip of text is
 * an awkward thing to hit, and there is nothing else it could mean.
 */
@Composable
fun ExpandableDescription(
   text: String,
   modifier: Modifier = Modifier
) {
   var expanded by remember { mutableStateOf(false) }

   Row(
      modifier = modifier
         .clickable { expanded = !expanded }
         .animateContentSize(),
      verticalAlignment = if (expanded) Alignment.Top else Alignment.CenterVertically
   ) {
      Text(
         text = text,
         style = MaterialTheme.typography.bodySmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant,
         maxLines = if (expanded) Int.MAX_VALUE else 1,
         overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
         modifier = Modifier.weight(1f)
      )
      Spacer(modifier = Modifier.width(4.dp))
      Icon(
         imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
         // Decorative: the row is already labelled by the text it belongs to.
         contentDescription = null,
         tint = MaterialTheme.colorScheme.onSurfaceVariant
      )
   }
}
