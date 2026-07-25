package com.androNSZ.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androNSZ.R
import com.androNSZ.model.AccentMode
import com.androNSZ.model.StatsFormat
import com.androNSZ.model.ThemeMode
import com.androNSZ.ui.components.ColorWheelPicker
import com.androNSZ.ui.components.CompactCenterAlignedTopAppBar
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
   currentFormat: StatsFormat,
   onFormatChange: (StatsFormat) -> Unit,
   currentLanguage: String,
   onLanguageChange: (String) -> Unit,
   currentTheme: ThemeMode,
   onThemeChange: (ThemeMode) -> Unit,
   currentAccentMode: AccentMode,
   currentAccentColor: Int,
   onAccentModeChange: (AccentMode) -> Unit,
   onAccentColorChange: (Int) -> Unit,
   currentVerification: Boolean,
   onVerificationChange: (Boolean) -> Unit,
   currentDecompressionThreads: Int,
   maxThreads: Int,
   onDecompressionThreadsChange: (Int) -> Unit,
   currentShowUpdateBanner: Boolean,
   onShowUpdateBannerChange: (Boolean) -> Unit,
   onBack: () -> Unit
) {
   BackHandler { onBack() }
   Scaffold(
      topBar = {
         CompactCenterAlignedTopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = {
               IconButton(onClick = onBack) {
                  Icon(
                     imageVector = Icons.Filled.ArrowBack,
                     contentDescription = stringResource(R.string.cd_back),
                     tint = MaterialTheme.colorScheme.onPrimary,
                  )
               }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
               containerColor = MaterialTheme.colorScheme.primary,
               titleContentColor = MaterialTheme.colorScheme.onPrimary,
            )
         )
      }
   ) { padding ->
      Column(
         modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
         verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
         Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
               containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
         ) {
            Column(
               modifier = Modifier
                  .fillMaxWidth()
                  .padding(16.dp),
               verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
               Text(
                  text = stringResource(R.string.settings_stats_format),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
               )

               var expanded by remember { mutableStateOf(false) }

               ExposedDropdownMenuBox(
                  expanded = expanded,
                  onExpandedChange = { expanded = !expanded }
               ) {
                  OutlinedTextField(
                     value = when (currentFormat) {
                        StatsFormat.COMPACT -> stringResource(R.string.stats_format_compact)
                        StatsFormat.COMPACT2 -> stringResource(R.string.stats_format_compact2)
                        StatsFormat.COMPACT3 -> stringResource(R.string.stats_format_compact3)
                        StatsFormat.DETAILED -> stringResource(R.string.stats_format_detailed)
                     },
                     onValueChange = {},
                     readOnly = true,
                     trailingIcon = {
                        Icon(
                           imageVector = Icons.Filled.ArrowDropDown,
                           contentDescription = null
                        )
                     },
                     modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                     colors = OutlinedTextFieldDefaults.colors()
                  )

                  ExposedDropdownMenu(
                     expanded = expanded,
                     onDismissRequest = { expanded = false }
                  ) {
                     DropdownMenuItem(
                        text = { Text(stringResource(R.string.stats_format_compact)) },
                        onClick = {
                           onFormatChange(StatsFormat.COMPACT)
                           expanded = false
                        }
                     )
                     DropdownMenuItem(
                        text = { Text(stringResource(R.string.stats_format_compact2)) },
                        onClick = {
                           onFormatChange(StatsFormat.COMPACT2)
                           expanded = false
                        }
                     )
                     DropdownMenuItem(
                        text = { Text(stringResource(R.string.stats_format_compact3)) },
                        onClick = {
                           onFormatChange(StatsFormat.COMPACT3)
                           expanded = false
                        }
                     )
                     DropdownMenuItem(
                        text = { Text(stringResource(R.string.stats_format_detailed)) },
                        onClick = {
                           onFormatChange(StatsFormat.DETAILED)
                           expanded = false
                        }
                     )
                  }
               }
            }
         }

         Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
               containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
         ) {
            Column(
               modifier = Modifier
                  .fillMaxWidth()
                  .padding(16.dp),
               verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
               Text(
                  text = stringResource(R.string.settings_language),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
               )

               var langExpanded by remember { mutableStateOf(false) }

               val languages = listOf(
                  "system" to stringResource(R.string.language_system),
                  "en"     to stringResource(R.string.language_english),
                  "ru"     to stringResource(R.string.language_russian),
               )

               ExposedDropdownMenuBox(
                  expanded = langExpanded,
                  onExpandedChange = { langExpanded = !langExpanded }
               ) {
                  OutlinedTextField(
                     value = languages.firstOrNull { it.first == currentLanguage }?.second
                        ?: stringResource(R.string.language_system),
                     onValueChange = {},
                     readOnly = true,
                     trailingIcon = {
                        Icon(
                           imageVector = Icons.Filled.ArrowDropDown,
                           contentDescription = null
                        )
                     },
                     modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                     colors = OutlinedTextFieldDefaults.colors()
                  )

                  ExposedDropdownMenu(
                     expanded = langExpanded,
                     onDismissRequest = { langExpanded = false }
                  ) {
                     languages.forEach { (code, label) ->
                        DropdownMenuItem(
                           text = { Text(label) },
                           onClick = {
                              onLanguageChange(code)
                              langExpanded = false
                           }
                        )
                     }
                  }
               }
            }
         }

         Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
               containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
         ) {
            Column(
               modifier = Modifier
                  .fillMaxWidth()
                  .padding(16.dp),
               verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
               Text(
                  text = stringResource(R.string.settings_theme),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
               )

               var themeExpanded by remember { mutableStateOf(false) }

               val themes = listOf(
                  ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                  ThemeMode.LIGHT  to stringResource(R.string.theme_light),
                  ThemeMode.DARK   to stringResource(R.string.theme_dark),
               )

               ExposedDropdownMenuBox(
                  expanded = themeExpanded,
                  onExpandedChange = { themeExpanded = !themeExpanded }
               ) {
                  OutlinedTextField(
                     value = themes.firstOrNull { it.first == currentTheme }?.second
                        ?: stringResource(R.string.theme_system),
                     onValueChange = {},
                     readOnly = true,
                     trailingIcon = {
                        Icon(
                           imageVector = Icons.Filled.ArrowDropDown,
                           contentDescription = null
                        )
                     },
                     modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                     colors = OutlinedTextFieldDefaults.colors()
                  )

                  ExposedDropdownMenu(
                     expanded = themeExpanded,
                     onDismissRequest = { themeExpanded = false }
                  ) {
                     themes.forEach { (mode, label) ->
                        DropdownMenuItem(
                           text = { Text(label) },
                           onClick = {
                              onThemeChange(mode)
                              themeExpanded = false
                           }
                        )
                     }
                  }
               }
            }
         }

         Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
               containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
         ) {
            Column(
               modifier = Modifier
                  .fillMaxWidth()
                  .padding(16.dp),
               verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
               Text(
                  text = stringResource(R.string.settings_accent),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
               )

               var accentExpanded by remember { mutableStateOf(false) }

               val accentModes = listOf(
                  AccentMode.DEFAULT to stringResource(R.string.accent_default),
                  AccentMode.SYSTEM to stringResource(R.string.accent_system),
                  AccentMode.CUSTOM to stringResource(R.string.accent_custom),
               )

               ExposedDropdownMenuBox(
                  expanded = accentExpanded,
                  onExpandedChange = { accentExpanded = !accentExpanded }
               ) {
                  OutlinedTextField(
                     value = accentModes.firstOrNull { it.first == currentAccentMode }?.second
                        ?: stringResource(R.string.accent_default),
                     onValueChange = {},
                     readOnly = true,
                     trailingIcon = {
                        Icon(
                           imageVector = Icons.Filled.ArrowDropDown,
                           contentDescription = null
                        )
                     },
                     modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                     colors = OutlinedTextFieldDefaults.colors()
                  )

                  ExposedDropdownMenu(
                     expanded = accentExpanded,
                     onDismissRequest = { accentExpanded = false }
                  ) {
                     accentModes.forEach { (mode, label) ->
                        DropdownMenuItem(
                           text = { Text(label) },
                           onClick = {
                              onAccentModeChange(mode)
                              accentExpanded = false
                           }
                        )
                     }
                  }
               }

               // Manual color controls: a color wheel, preset swatches, a hex field.
               if (currentAccentMode == AccentMode.CUSTOM) {
                  ColorWheelPicker(
                     color = currentAccentColor,
                     onColorChange = onAccentColorChange,
                     modifier = Modifier.fillMaxWidth()
                  )

                  val presets = listOf(
                     0xFF6650A4, 0xFF1565C0, 0xFF00897B,
                     0xFF2E7D32, 0xFFEF6C00, 0xFFC62828
                  ).map { it.toInt() }

                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                     presets.forEach { argb ->
                        val selected = argb == currentAccentColor
                        Box(
                           modifier = Modifier
                              .size(36.dp)
                              .clip(CircleShape)
                              .background(Color(argb))
                              .border(
                                 width = if (selected) 3.dp else 1.dp,
                                 color = if (selected) MaterialTheme.colorScheme.onSurface
                                         else MaterialTheme.colorScheme.outline,
                                 shape = CircleShape
                              )
                              .clickable { onAccentColorChange(argb) }
                        )
                     }
                  }

                  var hexText by remember(currentAccentColor) {
                     mutableStateOf(argbToHex(currentAccentColor))
                  }
                  OutlinedTextField(
                     value = hexText,
                     onValueChange = { input ->
                        hexText = input
                        parseHexColor(input)?.let { onAccentColorChange(it) }
                     },
                     label = { Text(stringResource(R.string.accent_hex_label)) },
                     singleLine = true,
                     leadingIcon = {
                        Box(
                           modifier = Modifier
                              .size(24.dp)
                              .clip(CircleShape)
                              .background(Color(currentAccentColor))
                        )
                     },
                     modifier = Modifier.fillMaxWidth(),
                     colors = OutlinedTextFieldDefaults.colors()
                  )
               }
            }
         }

         Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
               containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
         ) {
            Column(
               modifier = Modifier
                  .fillMaxWidth()
                  .padding(16.dp),
               verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
               Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
               ) {
                  Text(
                     text = stringResource(R.string.settings_verification),
                     style = MaterialTheme.typography.titleMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.weight(1f)
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Switch(
                     checked = currentVerification,
                     onCheckedChange = onVerificationChange
                  )
               }
               Text(
                  text = stringResource(R.string.settings_verification_desc),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
               )

               // Decompression-parallelism control: how many files to unpack at
               // once. Independent of verification (CNMT verification is nearly free).
               Spacer(modifier = Modifier.height(4.dp))
               Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
               ) {
                  Text(
                     text = stringResource(R.string.settings_decompression_threads),
                     style = MaterialTheme.typography.titleMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                  Text(
                     text = if (currentDecompressionThreads <= 0) {
                        stringResource(R.string.settings_threads_auto)
                     } else {
                        currentDecompressionThreads.toString()
                     },
                     style = MaterialTheme.typography.titleMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
               }
               Slider(
                  value = currentDecompressionThreads.coerceIn(0, maxThreads).toFloat(),
                  onValueChange = { onDecompressionThreadsChange(it.roundToInt()) },
                  valueRange = 0f..maxThreads.toFloat(),
                  steps = (maxThreads - 1).coerceAtLeast(0)
               )
               Text(
                  text = stringResource(R.string.settings_decompression_threads_desc),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
               )
            }
         }

         Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
               containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
         ) {
            Column(
               modifier = Modifier
                  .fillMaxWidth()
                  .padding(16.dp),
               verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
               Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
               ) {
                  Text(
                     text = stringResource(R.string.settings_show_update_banner),
                     style = MaterialTheme.typography.titleMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.weight(1f)
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Switch(
                     checked = currentShowUpdateBanner,
                     onCheckedChange = onShowUpdateBannerChange
                  )
               }
               Text(
                  text = stringResource(R.string.settings_show_update_banner_desc),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
               )
            }
         }
      }
   }
}

/** Format the RGB part of an ARGB color as `#RRGGBB`. */
private fun argbToHex(argb: Int): String = "#%06X".format(0xFFFFFF and argb)

/** Parse `#RRGGBB` / `RRGGBB` into an opaque ARGB color, or null if invalid. */
private fun parseHexColor(input: String): Int? {
   val cleaned = input.removePrefix("#").trim()
   if (cleaned.length != 6) return null
   return try {
      0xFF000000.toInt() or cleaned.toInt(16)
   } catch (e: NumberFormatException) {
      null
   }
}
