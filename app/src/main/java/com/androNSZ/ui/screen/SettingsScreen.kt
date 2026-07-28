package com.androNSZ.ui.screen

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.androNSZ.BuildConfig
import com.androNSZ.R
import com.androNSZ.model.AccentMode
import com.androNSZ.model.StatsFormat
import com.androNSZ.model.ThemeMode
import com.androNSZ.model.ThreadMode
import com.androNSZ.ui.components.AccentPresetRow
import com.androNSZ.ui.components.CompactCenterAlignedTopAppBar
import com.androNSZ.ui.components.SettingsDivider
import com.androNSZ.ui.components.SettingsDropdownRow
import com.androNSZ.ui.components.SettingsNavRow
import com.androNSZ.ui.components.SettingsSectionHeader
import com.androNSZ.ui.components.SettingsSwitchRow
import com.androNSZ.ui.theme.DefaultAccent

/** Where the "GitHub" link in the footer goes. Same repository as [AboutScreen]. */
private const val REPO_URL = "https://github.com/Fast-and-Pythonic/Andro-NSZ-unpacker"

/**
 * The accent palette. The first entry is the app's own accent — picking it means
 * AccentMode.DEFAULT, picking any other means a manual color.
 */
private val AccentPresets: List<Int> = listOf(
   DefaultAccent.toArgb(),
   0xFF6650A4.toInt(), 0xFF1565C0.toInt(), 0xFF00897B.toInt(),
   0xFF2E7D32.toInt(), 0xFFEF6C00.toInt(), 0xFFC62828.toInt()
)

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
   currentThreadMode: ThreadMode,
   // What a job would actually use right now, for the summary line under the button.
   currentThreads: Int,
   onOpenThreadSettings: () -> Unit,
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
      // The 16 dp side padding belongs to the rows, not to this column: the section
      // dividers are meant to run the full width of the screen.
      Column(
         modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp)
      ) {
         SettingsSectionHeader(stringResource(R.string.settings_section_appearance))

         SettingsDropdownRow(
            title = stringResource(R.string.settings_language),
            options = listOf(
               "system" to stringResource(R.string.language_system),
               "en"     to stringResource(R.string.language_english),
               "ru"     to stringResource(R.string.language_russian),
            ),
            selected = currentLanguage,
            onSelect = onLanguageChange
         )

         SettingsDropdownRow(
            title = stringResource(R.string.settings_theme),
            options = listOf(
               ThemeMode.SYSTEM to stringResource(R.string.theme_system),
               ThemeMode.LIGHT  to stringResource(R.string.theme_light),
               ThemeMode.DARK   to stringResource(R.string.theme_dark),
            ),
            selected = currentTheme,
            onSelect = onThemeChange
         )

         SettingsDropdownRow(
            title = stringResource(R.string.settings_accent),
            subtitle = when (currentAccentMode) {
               AccentMode.DEFAULT -> stringResource(R.string.accent_default_desc)
               AccentMode.SYSTEM -> stringResource(R.string.accent_system_desc)
               AccentMode.CUSTOM -> stringResource(R.string.accent_custom_desc)
            },
            options = listOf(
               AccentMode.DEFAULT to stringResource(R.string.accent_default),
               AccentMode.SYSTEM to stringResource(R.string.accent_system),
               AccentMode.CUSTOM to stringResource(R.string.accent_custom),
            ),
            selected = currentAccentMode,
            onSelect = onAccentModeChange
         )

         // The palette stays visible in every mode: it is both the picker and the
         // answer to "which color is the app using right now". In the system mode the
         // accent comes from the wallpaper, so nothing here is marked.
         AccentPresetRow(
            presets = AccentPresets,
            selected = when (currentAccentMode) {
               AccentMode.DEFAULT -> AccentPresets.first()
               AccentMode.CUSTOM -> currentAccentColor
               AccentMode.SYSTEM -> null
            },
            onSelect = { argb ->
               if (argb == AccentPresets.first()) {
                  onAccentModeChange(AccentMode.DEFAULT)
               } else {
                  // Color first, so the theme never recomposes with the mode already
                  // switched but the old color still stored.
                  onAccentColorChange(argb)
                  onAccentModeChange(AccentMode.CUSTOM)
               }
            }
         )

         SettingsDivider()

         SettingsSectionHeader(stringResource(R.string.settings_section_unpacking))

         // Output verification is always on (MainViewModel.loadSettings pins it), so the
         // switch has nothing to offer. Kept here, and kept wired through the parameters
         // above, so bringing the choice back is one uncomment away.
         //
         // SettingsSwitchRow(
         //    title = stringResource(R.string.settings_verification),
         //    subtitle = stringResource(R.string.settings_verification_summary),
         //    checked = currentVerification,
         //    onCheckedChange = onVerificationChange
         // )

         // Everything about parallel unpacking lives on its own screen: it is one
         // setting to most users and a measurement rig to the rest, and mixing the two
         // buried the rest of this screen.
         SettingsNavRow(
            title = stringResource(R.string.settings_thread_screen),
            // Names the mode *and* what it currently yields — the mode alone does not
            // answer "so how many is that?", which is the question someone opening this
            // screen actually has.
            subtitle = stringResource(
               R.string.settings_thread_screen_summary,
               threadModeLabel(currentThreadMode),
               pluralStringResource(R.plurals.threads, currentThreads, currentThreads)
            ),
            onClick = onOpenThreadSettings
         )

         SettingsDropdownRow(
            title = stringResource(R.string.settings_stats_format),
            options = listOf(
               StatsFormat.COMPACT to stringResource(R.string.stats_format_compact),
               StatsFormat.COMPACT2 to stringResource(R.string.stats_format_compact2),
               StatsFormat.COMPACT3 to stringResource(R.string.stats_format_compact3),
               StatsFormat.DETAILED to stringResource(R.string.stats_format_detailed),
            ),
            selected = currentFormat,
            onSelect = onFormatChange
         )

         SettingsDivider()

         SettingsSectionHeader(stringResource(R.string.settings_section_updates))

         SettingsSwitchRow(
            title = stringResource(R.string.settings_show_update_banner),
            subtitle = stringResource(R.string.settings_show_update_banner_summary),
            checked = currentShowUpdateBanner,
            onCheckedChange = onShowUpdateBannerChange
         )

         SettingsFooter()
      }
   }
}

/** App name, running version and a link to the repository, at the end of the list. */
@Composable
private fun SettingsFooter() {
   val context = LocalContext.current
   Column(
      modifier = Modifier
         .fillMaxWidth()
         .padding(horizontal = 16.dp)
         .padding(top = 12.dp, bottom = 20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(6.dp)
   ) {
      Text(
         text = stringResource(R.string.app_name),
         style = MaterialTheme.typography.titleMedium,
         fontWeight = FontWeight.Bold,
         color = MaterialTheme.colorScheme.onSurface
      )
      Text(
         text = "v${BuildConfig.VERSION_NAME}",
         style = MaterialTheme.typography.bodyMedium,
         color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      val openRepo = stringResource(R.string.cd_open_github)
      Row(
         modifier = Modifier
            .clickable(onClickLabel = openRepo) {
               context.startActivity(Intent(Intent.ACTION_VIEW, REPO_URL.toUri()))
            }
            .padding(top = 2.dp),
         horizontalArrangement = Arrangement.spacedBy(6.dp),
         verticalAlignment = Alignment.CenterVertically
      ) {
         Icon(
            imageVector = Icons.Filled.Code,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
         )
         Text(
            text = stringResource(R.string.settings_footer_github),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
         )
      }
   }
}

/** Localized label for a thread-count source, for the row that leads to its screen. */
@Composable
fun threadModeLabel(mode: ThreadMode): String = when (mode) {
   ThreadMode.MANUAL -> stringResource(R.string.thread_mode_manual)
   ThreadMode.HALF -> stringResource(R.string.thread_mode_half)
   ThreadMode.CALIBRATED -> stringResource(R.string.thread_mode_calibrated)
}
