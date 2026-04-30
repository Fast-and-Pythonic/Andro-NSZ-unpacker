package com.androNSZ.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androNSZ.R
import com.androNSZ.model.StatsFormat
import com.androNSZ.ui.components.CompactCenterAlignedTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
   currentFormat: StatsFormat,
   onFormatChange: (StatsFormat) -> Unit,
   currentLanguage: String,
   onLanguageChange: (String) -> Unit,
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
      }
   }
}
