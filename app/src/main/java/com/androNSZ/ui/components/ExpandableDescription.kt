package com.androNSZ.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.androNSZ.R

/**
 * An explanatory paragraph clamped to a couple of lines, with a "More" link to open it.
 *
 * The thread screen accumulated long descriptions — several of them explain *why* a
 * measurement works the way it does, which is worth having but drowns the controls they
 * belong to. Collapsed by default: a user who already knows what a setting does should be
 * able to see the settings, not the essays.
 *
 * Two lines rather than one, because one line ends mid-thought often enough that the text
 * reads as broken rather than as folded. The link is a word, not a chevron — a chevron on
 * a strip of prose looks like a section that failed to expand.
 */
@Composable
fun ExpandableDescription(
   text: String,
   modifier: Modifier = Modifier,
   collapsedLines: Int = 2
) {
   var expanded by remember { mutableStateOf(false) }

   Column(
      modifier = modifier
         .clickable { expanded = !expanded }
         .animateContentSize()
   ) {
      Text(
         text = text,
         style = MaterialTheme.typography.bodySmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant,
         maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
         overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis
      )
      Text(
         text = stringResource(if (expanded) R.string.action_less else R.string.action_more),
         style = MaterialTheme.typography.labelMedium,
         color = MaterialTheme.colorScheme.primary,
         modifier = Modifier.padding(top = 2.dp)
      )
   }
}
