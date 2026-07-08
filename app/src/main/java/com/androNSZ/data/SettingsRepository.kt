package com.androNSZ.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.androNSZ.model.AccentMode
import com.androNSZ.model.StatsFormat
import com.androNSZ.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository private constructor(private val context: Context) {

   companion object {
      // The stored context is always the application context (see getInstance),
      // so this singleton never leaks an Activity/Service context.
      @SuppressLint("StaticFieldLeak")
      @Volatile
      private var INSTANCE: SettingsRepository? = null

      fun getInstance(context: Context): SettingsRepository {
         return INSTANCE ?: synchronized(this) {
            INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
         }
      }

      private val STATS_FORMAT_KEY = stringPreferencesKey("stats_format")
      private val OUTPUT_FOLDER_KEY = stringPreferencesKey("output_folder_uri")
      private const val LANGUAGE_KEY = "language"
      private const val THEME_KEY = "theme_mode"
      private const val ACCENT_MODE_KEY = "accent_mode"
      private const val ACCENT_COLOR_KEY = "accent_color"
      private const val VERIFICATION_KEY = "verification_enabled"
      private const val DECOMPRESSION_THREADS_KEY = "decompression_threads"
      private const val SMART_DISTRIBUTION_KEY = "smart_distribution"
      // Default custom accent = the app's built-in purple (Purple40).
      const val DEFAULT_ACCENT_COLOR = 0xFF6650A4.toInt()
   }

   // Read synchronously at startup (before the first frame), so these live in
   // SharedPreferences rather than the async DataStore.
   private val langPrefs: SharedPreferences =
      context.getSharedPreferences("settings_lang", Context.MODE_PRIVATE)

   fun getLanguage(): String = langPrefs.getString(LANGUAGE_KEY, "system") ?: "system"

   fun saveLanguage(lang: String) {
      langPrefs.edit().putString(LANGUAGE_KEY, lang).apply()
   }

   fun getThemeMode(): ThemeMode {
      val stored = langPrefs.getString(THEME_KEY, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
      return try {
         ThemeMode.valueOf(stored)
      } catch (e: IllegalArgumentException) {
         ThemeMode.SYSTEM
      }
   }

   fun saveThemeMode(mode: ThemeMode) {
      langPrefs.edit().putString(THEME_KEY, mode.name).apply()
   }

   fun getAccentMode(): AccentMode {
      val stored = langPrefs.getString(ACCENT_MODE_KEY, AccentMode.DEFAULT.name) ?: AccentMode.DEFAULT.name
      return try {
         AccentMode.valueOf(stored)
      } catch (e: IllegalArgumentException) {
         AccentMode.DEFAULT
      }
   }

   fun saveAccentMode(mode: AccentMode) {
      langPrefs.edit().putString(ACCENT_MODE_KEY, mode.name).apply()
   }

   fun getAccentColor(): Int = langPrefs.getInt(ACCENT_COLOR_KEY, DEFAULT_ACCENT_COLOR)

   fun saveAccentColor(color: Int) {
      langPrefs.edit().putInt(ACCENT_COLOR_KEY, color).apply()
   }

   // Output verification defaults to ON: CNMT verification is nearly free
   // (SHA-256 overlaps decompression) and non-fatal.
   fun getVerificationEnabled(): Boolean = langPrefs.getBoolean(VERIFICATION_KEY, true)

   fun saveVerificationEnabled(enabled: Boolean) {
      langPrefs.edit().putBoolean(VERIFICATION_KEY, enabled).apply()
   }

   // Experimental override for the decompression parallelism (number of files
   // converted at once). 0 = auto (the core-adaptive 1..3 formula). Only honored
   // when verification is OFF — see MainViewModel. Read synchronously at job start.
   fun getDecompressionThreads(): Int = langPrefs.getInt(DECOMPRESSION_THREADS_KEY, 0)

   fun saveDecompressionThreads(count: Int) {
      langPrefs.edit().putInt(DECOMPRESSION_THREADS_KEY, count).apply()
   }

   // Smart load distribution: dispatch the largest files first (LPT), so a heavy
   // file never trails the batch on a slow core. Default ON — it's strictly better;
   // the toggle exists to A/B measure it (and will later also gate core affinity).
   fun getSmartDistribution(): Boolean = langPrefs.getBoolean(SMART_DISTRIBUTION_KEY, true)

   fun saveSmartDistribution(enabled: Boolean) {
      langPrefs.edit().putBoolean(SMART_DISTRIBUTION_KEY, enabled).apply()
   }

   val statsFormatFlow: Flow<StatsFormat> = context.dataStore.data
      .map { preferences ->
         val formatString = preferences[STATS_FORMAT_KEY] ?: StatsFormat.DETAILED.name
         try {
            StatsFormat.valueOf(formatString)
         } catch (e: IllegalArgumentException) {
            StatsFormat.DETAILED
         }
      }

   val outputFolderUriFlow: Flow<Uri?> = context.dataStore.data
      .map { preferences ->
         preferences[OUTPUT_FOLDER_KEY]?.let { it.toUri() }
      }

   suspend fun saveStatsFormat(format: StatsFormat) {
      context.dataStore.edit { preferences ->
         preferences[STATS_FORMAT_KEY] = format.name
      }
   }

   suspend fun saveOutputFolderUri(uri: Uri?) {
      context.dataStore.edit { preferences ->
         if (uri != null) {
            preferences[OUTPUT_FOLDER_KEY] = uri.toString()
         } else {
            preferences.remove(OUTPUT_FOLDER_KEY)
         }
      }
   }
}
