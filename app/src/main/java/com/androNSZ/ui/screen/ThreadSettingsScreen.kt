package com.androNSZ.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androNSZ.R
import com.androNSZ.model.ThreadMode
import com.androNSZ.ui.components.CompactCenterAlignedTopAppBar
import com.androNSZ.ui.components.ExpandableDescription
import com.androNSZ.ui.components.SettingsDivider
import com.androNSZ.ui.components.SettingsRadioRow
import com.androNSZ.ui.components.SettingsSectionHeader
import com.androNSZ.util.BenchSummary
import com.androNSZ.util.RealFileBenchmark
import com.androNSZ.viewmodel.resolveConcurrency
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
 *
 * The screen opens with the answer — the number a job would use right now — because that
 * is the question its title asks. Everything below is how that number was arrived at.
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
   benchProgress: RealFileBenchmark.Progress?,
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
            title = { Text(stringResource(R.string.settings_thread_screen_title)) },
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
            .padding(top = 8.dp, bottom = 24.dp)
      ) {
         if (benchRunning) {
            BenchProgressCard(
               progress = benchProgress,
               levelCount = RealFileBenchmark.levelCount(maxThreads),
               onCancel = onCancelBenchmark
            )
         } else {
            ThreadCountHero(
               // The same function a job calls, so the headline number cannot drift from
               // the one that will actually be used.
               threads = resolveConcurrency(
                  currentThreadMode,
                  currentDecompressionThreads,
                  calibratedThreads,
                  maxThreads,
                  maxThreads
               ),
               source = when (currentThreadMode) {
                  ThreadMode.MANUAL ->
                     stringResource(R.string.settings_thread_hero_manual, maxThreads)
                  ThreadMode.HALF ->
                     stringResource(R.string.settings_thread_hero_half, maxThreads)
                  ThreadMode.CALIBRATED -> if (benchSummary != null) {
                     stringResource(
                        R.string.settings_thread_hero_calibrated,
                        DateFormat.getDateInstance(DateFormat.MEDIUM)
                           .format(Date(benchSummary.atMillis))
                     )
                  } else {
                     stringResource(R.string.settings_calibrated_none, halfThreads)
                  }
               }
            )
         }

         SettingsSectionHeader(
            title = stringResource(R.string.settings_thread_mode),
            modifier = Modifier.alpha(if (benchRunning) 0.38f else 1f)
         )

         SettingsRadioRow(
            title = stringResource(R.string.thread_mode_manual),
            subtitle = stringResource(R.string.settings_thread_mode_manual_desc),
            selected = currentThreadMode == ThreadMode.MANUAL,
            onClick = { onThreadModeChange(ThreadMode.MANUAL) },
            enabled = !benchRunning
         )
         if (currentThreadMode == ThreadMode.MANUAL && !benchRunning) {
            ThreadSlider(
               value = currentDecompressionThreads,
               maxThreads = maxThreads,
               halfThreads = halfThreads,
               onValueChange = onDecompressionThreadsChange
            )
         }

         SettingsRadioRow(
            title = stringResource(R.string.thread_mode_half),
            subtitle = stringResource(
               R.string.settings_thread_mode_half_desc,
               maxThreads,
               pluralStringResource(R.plurals.threads, halfThreads, halfThreads)
            ),
            selected = currentThreadMode == ThreadMode.HALF,
            onClick = { onThreadModeChange(ThreadMode.HALF) },
            enabled = !benchRunning
         )

         SettingsRadioRow(
            title = stringResource(R.string.thread_mode_calibrated),
            subtitle = when {
               benchRunning -> stringResource(R.string.settings_thread_mode_calibrated_running)
               calibratedThreads > 0 ->
                  stringResource(R.string.settings_calibrated_value, calibratedThreads)
               else -> stringResource(R.string.settings_calibrated_none, halfThreads)
            },
            selected = currentThreadMode == ThreadMode.CALIBRATED,
            onClick = { onThreadModeChange(ThreadMode.CALIBRATED) },
            enabled = !benchRunning
         )

         if (benchRunning) {
            Text(
               text = stringResource(R.string.settings_thread_modes_locked),
               style = MaterialTheme.typography.bodySmall,
               color = MaterialTheme.colorScheme.onSurfaceVariant,
               modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
         } else {
            ExpandableDescription(
               text = stringResource(R.string.settings_thread_mode_desc),
               modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            SettingsDivider()

            // Offered in every mode: measuring the device is useful even to someone who
            // then pins the number by hand.
            SettingsSectionHeader(stringResource(R.string.settings_speed_test_real))
            ExpandableDescription(
               text = stringResource(R.string.settings_speed_test_real_desc),
               modifier = Modifier.padding(horizontal = 16.dp)
            )

            if (benchFailed) {
               Text(
                  text = stringResource(R.string.settings_speed_test_failed),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.error,
                  modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
               )
            }
            // Nothing measured yet: say so where the button is, rather than leaving the
            // mode list as the only hint that a test exists.
            if (calibratedThreads <= 0) {
               Text(
                  text = stringResource(R.string.settings_speed_test_suggest),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
               )
            }

            Row(
               modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
               horizontalArrangement = Arrangement.spacedBy(12.dp),
               verticalAlignment = Alignment.CenterVertically
            ) {
               if (benchSummary != null) {
                  OutlinedButton(onClick = onRunBenchmark, enabled = benchEnabled) {
                     Text(stringResource(R.string.settings_speed_test_rerun))
                  }
               } else {
                  Button(onClick = onRunBenchmark, enabled = benchEnabled) {
                     Text(stringResource(R.string.settings_speed_test_run))
                  }
                  Text(
                     text = stringResource(R.string.settings_speed_test_size_hint),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
               }
            }

            if (benchSummary != null) {
               Spacer(modifier = Modifier.height(16.dp))
               BenchResultTable(benchSummary)
               ExpandableDescription(
                  text = stringResource(R.string.settings_speed_test_results_hint),
                  modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
               )
            }
         }
      }
   }
}

/** Size of the headline number. Large enough to be the first thing read on the screen. */
private val HeroNumberSize = 44.sp

/** The answer the screen exists to give: how many files will unpack at once, and why. */
@Composable
private fun ThreadCountHero(threads: Int, source: String) {
   Card(
      modifier = Modifier
         .fillMaxWidth()
         .padding(16.dp),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(
         containerColor = MaterialTheme.colorScheme.primaryContainer
      )
   ) {
      Row(
         modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
         horizontalArrangement = Arrangement.spacedBy(16.dp),
         verticalAlignment = Alignment.CenterVertically
      ) {
         Text(
            text = threads.toString(),
            style = MaterialTheme.typography.displayMedium,
            fontSize = HeroNumberSize,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
         )
         Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
         ) {
            Text(
               text = pluralStringResource(R.plurals.threads_parallel, threads),
               style = MaterialTheme.typography.titleMedium,
               color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
               text = source,
               style = MaterialTheme.typography.bodySmall,
               color = MaterialTheme.colorScheme.primary
            )
         }
      }
   }
}

/**
 * The hero card while the test runs: the level being measured, how far along the run is,
 * and the way out. It replaces the headline number because during a test there is no
 * settled answer to show — the number is what the test is busy deciding.
 *
 * Everything here is live and display-only: a level is minutes of unpacking, and a card
 * that changed once per finished level looked frozen and, worse, named the level that had
 * just ended rather than the one running.
 */
@Composable
private fun BenchProgressCard(
   progress: RealFileBenchmark.Progress?,
   levelCount: Int,
   onCancel: () -> Unit
) {
   Card(
      modifier = Modifier
         .fillMaxWidth()
         .padding(16.dp),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(
         containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
      )
   ) {
      Column(
         modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
         verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
         Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
         ) {
            Text(
               text = progress?.level?.toString() ?: "—",
               style = MaterialTheme.typography.displayMedium,
               fontSize = HeroNumberSize,
               fontWeight = FontWeight.Bold,
               color = MaterialTheme.colorScheme.primary
            )
            Column(
               modifier = Modifier.weight(1f),
               verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
               Text(
                  text = progress?.let {
                     stringResource(
                        R.string.settings_speed_test_level_speed,
                        // Through plurals, so Russian gets "2 потока" not "2 потоков".
                        pluralStringResource(R.plurals.threads_parallel, it.level),
                        "%.0f".format(it.mbps)
                     )
                  } ?: stringResource(R.string.settings_speed_test_starting),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface
               )
               if (progress != null) {
                  Text(
                     text = if (progress.warmUp) {
                        stringResource(R.string.settings_speed_test_warmup)
                     } else {
                        // The run in flight, not the count already behind us.
                        stringResource(
                           R.string.settings_speed_test_run_of,
                           progress.levelsDone + 1,
                           levelCount
                        )
                     },
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
               }
            }
         }

         // The warm-up is outside the count, and a level whose total the engine does not
         // report has no fraction to show — both leave the bar indeterminate.
         val fraction = progress
            ?.takeIf { !it.warmUp && it.fraction >= 0f && levelCount > 0 }
            ?.let { (it.levelsDone + it.fraction) / levelCount }
         if (fraction != null) {
            LinearProgressIndicator(
               progress = { fraction },
               modifier = Modifier.fillMaxWidth()
            )
         } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
         }

         Text(
            text = stringResource(R.string.settings_speed_test_busy_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
         )
         OutlinedButton(onClick = onCancel) {
            Text(stringResource(R.string.settings_speed_test_cancel))
         }
      }
   }
}

/**
 * The manual count, tucked under the mode it belongs to and sharing its tint so it reads
 * as part of that choice rather than as a setting of its own.
 */
@Composable
private fun ThreadSlider(
   value: Int,
   maxThreads: Int,
   halfThreads: Int,
   onValueChange: (Int) -> Unit
) {
   Column(
      modifier = Modifier
         .fillMaxWidth()
         .background(MaterialTheme.colorScheme.surfaceContainerHigh)
         .padding(start = 52.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)
   ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
         Text(
            text = stringResource(R.string.settings_decompression_threads),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
         )
         Text(
            // 0 is "auto": say what auto comes to rather than showing a bare zero.
            text = if (value <= 0) {
               pluralStringResource(R.plurals.threads, halfThreads, halfThreads)
            } else {
               value.toString()
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
         )
      }
      Slider(
         value = value.coerceIn(0, maxThreads).toFloat(),
         onValueChange = { onValueChange(it.roundToInt()) },
         valueRange = 0f..maxThreads.toFloat(),
         steps = (maxThreads - 1).coerceAtLeast(0)
      )
      Row {
         Text(
            text = stringResource(R.string.settings_slider_auto),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
         )
         Text(
            text = maxThreads.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
         )
      }
      ExpandableDescription(stringResource(R.string.settings_decompression_threads_desc))
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
   Column(
      modifier = Modifier
         .fillMaxWidth()
         .padding(horizontal = 16.dp)
         .clip(RoundedCornerShape(12.dp))
         .border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
            shape = RoundedCornerShape(12.dp)
         )
   ) {
      Row(
         modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
         horizontalArrangement = Arrangement.spacedBy(8.dp),
         verticalAlignment = Alignment.CenterVertically
      ) {
         Text(
            text = stringResource(
               R.string.settings_speed_test_measured,
               pluralStringResource(R.plurals.threads, summary.knee, summary.knee)
            ),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
         )
         Text(
            text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(summary.atMillis)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
         )
      }

      Row(
         modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp)
      ) {
         listOf(
            R.string.settings_speed_test_col_threads,
            R.string.settings_speed_test_col_mbps,
            R.string.settings_speed_test_col_score
         ).forEach { column ->
            Text(
               text = stringResource(column),
               style = MaterialTheme.typography.labelSmall,
               color = MaterialTheme.colorScheme.onSurfaceVariant,
               textAlign = TextAlign.Center,
               modifier = Modifier.weight(1f)
            )
         }
      }

      // Highest count first: what people want to know is whether more threads still buy
      // anything, and that reads top-down.
      val levels = (summary.levelMBps.keys + summary.scores.keys).distinct().sortedDescending()
      levels.forEach { level ->
         val best = level == summary.knee
         val cells = listOf(
            level.toString(),
            "%.0f".format(summary.levelMBps[level] ?: 0.0),
            summary.scores[level]?.let { "%.2f".format(it) } ?: "—"
         )
         Row(
            modifier = Modifier
               .fillMaxWidth()
               .background(
                  if (best) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
               )
               .padding(horizontal = 14.dp, vertical = 5.dp)
         ) {
            cells.forEach { cell ->
               Text(
                  text = cell,
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = if (best) FontWeight.Medium else FontWeight.Normal,
                  color = if (best) {
                     MaterialTheme.colorScheme.primary
                  } else {
                     MaterialTheme.colorScheme.onSurfaceVariant
                  },
                  textAlign = TextAlign.Center,
                  modifier = Modifier.weight(1f)
               )
            }
         }
      }
      Spacer(modifier = Modifier.height(6.dp))
   }
}
