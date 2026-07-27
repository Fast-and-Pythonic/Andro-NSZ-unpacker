package com.androNSZ.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androNSZ.R
import com.androNSZ.model.ThreadMode
import com.androNSZ.ui.components.CompactCenterAlignedTopAppBar
import com.androNSZ.ui.components.ExpandableDescription
import com.androNSZ.util.BenchSummary
import com.androNSZ.util.RealFileBenchmark
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * Everything about how many files unpack at once: the source of the count, the manual
 * slider, and the measurement that can replace it.
 *
 * Split off the main Settings screen because it is two audiences in one place — most
 * users want a single number and a sensible default, while anyone tuning wants the
 * per-level table and the test that produced it. Keeping both here leaves the main
 * screen short.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadSettingsScreen(
   currentThreadMode: ThreadMode,
   onThreadModeChange: (ThreadMode) -> Unit,
   currentDecompressionThreads: Int,
   maxThreads: Int,
   onDecompressionThreadsChange: (Int) -> Unit,
   calibratedThreads: Int,
   // What half the cores comes to, shown while nothing has been measured.
   halfThreads: Int,
   benchRunning: Boolean,
   benchLevel: RealFileBenchmark.LevelResult?,
   benchSummary: BenchSummary?,
   benchFailed: Boolean,
   // The test unpacks hard; running it during a conversion would measure both.
   benchEnabled: Boolean,
   onRunBenchmark: () -> Unit,
   onCancelBenchmark: () -> Unit,
   onBack: () -> Unit
) {
   BackHandler { onBack() }
   Scaffold(
      topBar = {
         CompactCenterAlignedTopAppBar(
            title = { Text(stringResource(R.string.settings_thread_screen)) },
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
               verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
               Text(
                  text = stringResource(R.string.settings_thread_mode),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
               )

               var modeExpanded by remember { mutableStateOf(false) }
               ExposedDropdownMenuBox(
                  expanded = modeExpanded,
                  onExpandedChange = { modeExpanded = !modeExpanded }
               ) {
                  OutlinedTextField(
                     value = threadModeLabel(currentThreadMode),
                     onValueChange = {},
                     readOnly = true,
                     trailingIcon = {
                        Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null)
                     },
                     modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                     colors = OutlinedTextFieldDefaults.colors()
                  )
                  ExposedDropdownMenu(
                     expanded = modeExpanded,
                     onDismissRequest = { modeExpanded = false }
                  ) {
                     ThreadMode.entries.forEach { mode ->
                        DropdownMenuItem(
                           text = { Text(threadModeLabel(mode)) },
                           onClick = {
                              onThreadModeChange(mode)
                              modeExpanded = false
                           }
                        )
                     }
                  }
               }
               ExpandableDescription(stringResource(R.string.settings_thread_mode_desc))

               when (currentThreadMode) {
                  ThreadMode.MANUAL -> {
                     Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                     ) {
                        Text(
                           text = stringResource(R.string.settings_decompression_threads),
                           style = MaterialTheme.typography.titleMedium,
                           color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                           text = if (currentDecompressionThreads <= 0) {
                              pluralStringResource(R.plurals.threads, halfThreads, halfThreads)
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
                     ExpandableDescription(
                        stringResource(R.string.settings_decompression_threads_desc)
                     )
                  }
                  ThreadMode.HALF -> {
                     Text(
                        text = pluralStringResource(R.plurals.threads_half, halfThreads, halfThreads),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                     )
                  }
                  ThreadMode.CALIBRATED -> {
                     Text(
                        text = if (calibratedThreads > 0) {
                           stringResource(R.string.settings_calibrated_value, calibratedThreads)
                        } else {
                           stringResource(R.string.settings_calibrated_none, halfThreads)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
               verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
               // Offered in every mode: measuring the device is useful even to
               // someone who then pins the number by hand.
               Text(
                  text = stringResource(R.string.settings_speed_test_real),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
               )
               ExpandableDescription(stringResource(R.string.settings_speed_test_real_desc))

               if (benchRunning) {
                  Text(
                     text = benchLevel?.let {
                        stringResource(
                           R.string.settings_speed_test_progress,
                           it.levelsDone,
                           RealFileBenchmark.levelCount(maxThreads),
                           // Through plurals, so Russian gets "2 потока" not "2 потоков".
                           pluralStringResource(R.plurals.threads, it.level, it.level),
                           "%.0f".format(it.mbps)
                        )
                     } ?: stringResource(R.string.settings_speed_test_starting),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                  LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                  OutlinedButton(onClick = onCancelBenchmark) {
                     Text(stringResource(R.string.settings_speed_test_cancel))
                  }
               } else {
                  if (benchFailed) {
                     Text(
                        text = stringResource(R.string.settings_speed_test_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                     )
                  }
                  // Nothing measured yet: say so where the button is, rather than
                  // leaving the mode list as the only hint that a test exists.
                  if (calibratedThreads <= 0) {
                     Text(
                        text = stringResource(R.string.settings_speed_test_suggest),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                     )
                  }
                  Button(onClick = onRunBenchmark, enabled = benchEnabled) {
                     Text(stringResource(R.string.settings_speed_test_run))
                  }

                  if (benchSummary != null) {
                     Spacer(modifier = Modifier.height(8.dp))
                     BenchResultTable(benchSummary)
                     ExpandableDescription(
                        stringResource(R.string.settings_speed_test_results_hint)
                     )
                  }
               }
            }
         }
      }
   }
}

/**
 * The numbers behind the verdict: one row per thread count with the raw speed and the
 * drift-corrected score. Shown because a bare count is unfalsifiable — it looks the
 * same whether the test measured the storage or something else entirely. The chosen
 * count is tinted rather than marked with a glyph, so the rows stay aligned.
 */
@Composable
private fun BenchResultTable(summary: BenchSummary) {
   Text(
      text = stringResource(
         R.string.settings_speed_test_results,
         pluralStringResource(R.plurals.threads, summary.knee, summary.knee),
         DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(summary.atMillis))
      ),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
   )
   // Highest count first: what people want to know is whether more threads still buy
   // anything, and that reads top-down.
   val levels = (summary.levelMBps.keys + summary.scores.keys).distinct().sortedDescending()
   levels.forEach { level ->
      val mbps = summary.levelMBps[level]
      val score = summary.scores[level]
      Text(
         text = if (score != null) {
            stringResource(
               R.string.settings_speed_test_result_row,
               level,
               "%.0f".format(mbps ?: 0.0),
               "%.2f".format(score)
            )
         } else {
            stringResource(
               R.string.settings_speed_test_result_row_noscore,
               level,
               "%.0f".format(mbps ?: 0.0)
            )
         },
         style = MaterialTheme.typography.bodySmall,
         color = if (level == summary.knee) {
            MaterialTheme.colorScheme.primary
         } else {
            MaterialTheme.colorScheme.onSurfaceVariant
         }
      )
   }
}
