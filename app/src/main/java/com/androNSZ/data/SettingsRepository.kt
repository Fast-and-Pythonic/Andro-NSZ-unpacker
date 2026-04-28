package com.androNSZ.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.androNSZ.model.StatsFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository private constructor(private val context: Context) {

   companion object {
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
   }

   private val langPrefs: SharedPreferences =
      context.getSharedPreferences("settings_lang", Context.MODE_PRIVATE)

   fun getLanguage(): String = langPrefs.getString(LANGUAGE_KEY, "system") ?: "system"

   fun saveLanguage(lang: String) {
      langPrefs.edit().putString(LANGUAGE_KEY, lang).apply()
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
         preferences[OUTPUT_FOLDER_KEY]?.let { Uri.parse(it) }
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
