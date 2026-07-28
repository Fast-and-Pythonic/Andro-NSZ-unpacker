package com.androNSZ.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androNSZ.R

/**
 * The building blocks of a settings list: a section header, a row, and the three
 * controls a row can carry (dropdown, switch, drill-down chevron).
 *
 * The settings screen used to be a stack of identical cards, each holding a title and a
 * full-width text field. That reads as six equally important things and hides the fact
 * that most of them are one word of state. These rows put the value next to its label
 * and let section headers carry the grouping, so the screen can be scanned instead of
 * read. They are deliberately stateless — everything but a dropdown's own open/closed
 * flag is hoisted to the caller.
 */

/** Width of the dropdown pill. Fixed so the values line up down the screen. */
private val DropdownWidth = 180.dp

/** Section title above a group of rows. */
@Composable
fun SettingsSectionHeader(
   title: String,
   modifier: Modifier = Modifier
) {
   Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
   )
}

/**
 * One setting: a label, an optional explanation under it, and an optional control on
 * the right. The whole row is the hit target when [onClick] is given — a 56 dp strip is
 * far easier to hit than the control at the end of it.
 */
@Composable
fun SettingsRow(
   title: String,
   modifier: Modifier = Modifier,
   subtitle: String? = null,
   onClick: (() -> Unit)? = null,
   trailing: @Composable (() -> Unit)? = null
) {
   Row(
      modifier = modifier
         .fillMaxWidth()
         .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
         .heightIn(min = 56.dp)
         .padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically
   ) {
      Column(
         modifier = Modifier.weight(1f),
         verticalArrangement = Arrangement.spacedBy(2.dp)
      ) {
         Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
         )
         if (subtitle != null) {
            Text(
               text = subtitle,
               style = MaterialTheme.typography.bodySmall,
               color = MaterialTheme.colorScheme.onSurfaceVariant
            )
         }
      }
      if (trailing != null) {
         trailing()
      }
   }
}

/**
 * A setting whose value is one of a short list. The pill on the right shows the current
 * value and anchors the menu; the row itself opens it too.
 */
@Composable
fun <T> SettingsDropdownRow(
   title: String,
   options: List<Pair<T, String>>,
   selected: T,
   onSelect: (T) -> Unit,
   modifier: Modifier = Modifier,
   subtitle: String? = null
) {
   var expanded by remember { mutableStateOf(false) }
   val label = options.firstOrNull { it.first == selected }?.second
      ?: options.firstOrNull()?.second.orEmpty()

   SettingsRow(
      title = title,
      subtitle = subtitle,
      onClick = { expanded = true },
      modifier = modifier
   ) {
      Box {
         Row(
            modifier = Modifier
               .width(DropdownWidth)
               .height(40.dp)
               .clip(RoundedCornerShape(8.dp))
               .background(MaterialTheme.colorScheme.surfaceVariant)
               .clickable { expanded = true }
               .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
         ) {
            Text(
               text = label,
               style = MaterialTheme.typography.bodyMedium,
               color = MaterialTheme.colorScheme.onSurface,
               modifier = Modifier.weight(1f)
            )
            Icon(
               imageVector = Icons.Filled.ArrowDropDown,
               // Decorative: the row is already labelled by its title.
               contentDescription = null,
               tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
         }

         DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
         ) {
            options.forEach { (value, text) ->
               DropdownMenuItem(
                  text = { Text(text) },
                  onClick = {
                     onSelect(value)
                     expanded = false
                  }
               )
            }
         }
      }
   }
}

/** A setting that is on or off. Tapping anywhere on the row toggles it. */
@Composable
fun SettingsSwitchRow(
   title: String,
   checked: Boolean,
   onCheckedChange: (Boolean) -> Unit,
   modifier: Modifier = Modifier,
   subtitle: String? = null
) {
   SettingsRow(
      title = title,
      subtitle = subtitle,
      onClick = { onCheckedChange(!checked) },
      modifier = modifier
   ) {
      Switch(checked = checked, onCheckedChange = onCheckedChange)
   }
}

/**
 * One option of a mutually exclusive group, with its consequence spelled out underneath.
 *
 * A dropdown hides every option but the chosen one, which is exactly wrong when the
 * options need explaining — "half the cores" and "from the speed test" mean nothing until
 * you see what number each one yields. The selected row is tinted rather than only
 * marked, so the group reads at a glance.
 */
@Composable
fun SettingsRadioRow(
   title: String,
   selected: Boolean,
   onClick: () -> Unit,
   modifier: Modifier = Modifier,
   subtitle: String? = null,
   enabled: Boolean = true
) {
   Row(
      modifier = modifier
         .fillMaxWidth()
         .background(
            if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
            else Color.Transparent
         )
         .clickable(enabled = enabled, onClick = onClick)
         .alpha(if (enabled) 1f else 0.38f)
         .heightIn(min = 56.dp)
         .padding(horizontal = 16.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalAlignment = Alignment.CenterVertically
   ) {
      RadioButton(
         selected = selected,
         // The row carries the click; a second target here would only fight it.
         onClick = null,
         enabled = enabled,
         modifier = Modifier.size(20.dp)
      )
      Column(
         modifier = Modifier.weight(1f),
         verticalArrangement = Arrangement.spacedBy(1.dp)
      ) {
         Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface
         )
         if (subtitle != null) {
            Text(
               text = subtitle,
               style = MaterialTheme.typography.bodySmall,
               color = MaterialTheme.colorScheme.onSurfaceVariant
            )
         }
      }
   }
}

/** A setting that lives on its own screen. */
@Composable
fun SettingsNavRow(
   title: String,
   onClick: () -> Unit,
   modifier: Modifier = Modifier,
   subtitle: String? = null
) {
   SettingsRow(
      title = title,
      subtitle = subtitle,
      onClick = onClick,
      modifier = modifier
   ) {
      Icon(
         imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
         contentDescription = null,
         tint = MaterialTheme.colorScheme.onSurfaceVariant
      )
   }
}

/** Separator between two sections. */
@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
   HorizontalDivider(modifier = modifier.padding(vertical = 8.dp))
}

/**
 * The accent palette: a row of colors with a check mark on the active one.
 *
 * [selected] is null when the accent does not come from this palette at all — the
 * system-wallpaper mode — and then no swatch is marked.
 */
@Composable
fun AccentPresetRow(
   presets: List<Int>,
   selected: Int?,
   onSelect: (Int) -> Unit,
   modifier: Modifier = Modifier
) {
   Row(
      modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically
   ) {
      val description = stringResource(R.string.cd_accent_preset)
      presets.forEach { argb ->
         val isSelected = argb == selected
         Box(
            modifier = Modifier
               .size(32.dp)
               .clip(CircleShape)
               .background(Color(argb))
               .border(
                  width = if (isSelected) 3.dp else 1.dp,
                  color = if (isSelected) MaterialTheme.colorScheme.primary
                          else MaterialTheme.colorScheme.outline,
                  shape = CircleShape
               )
               .clickable(onClickLabel = description) { onSelect(argb) },
            contentAlignment = Alignment.Center
         ) {
            if (isSelected) {
               Icon(
                  imageVector = Icons.Filled.Check,
                  contentDescription = null,
                  // The palette spans pale blue to deep red; pick whichever of black or
                  // white the swatch itself does not drown.
                  tint = if (Color(argb).luminance() > 0.5f) Color.Black else Color.White,
                  modifier = Modifier.size(16.dp)
               )
            }
         }
      }
   }
}
