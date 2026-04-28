package com.androNSZ

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.androNSZ.data.SettingsRepository
import com.androNSZ.fs.TempFileManager
import com.androNSZ.ui.screen.AndroNSZApp
import com.androNSZ.ui.theme.AndroNSZTheme
import com.androNSZ.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
   private val vm: MainViewModel by lazy {
      ViewModelProvider(this)[MainViewModel::class.java]
   }

   override fun attachBaseContext(newBase: Context) {
      val lang = SettingsRepository.getInstance(newBase).getLanguage()
      if (lang == "system") {
         super.attachBaseContext(newBase)
      } else {
         val locale = Locale(lang)
         val config = Configuration(newBase.resources.configuration)
         config.setLocale(locale)
         super.attachBaseContext(newBase.createConfigurationContext(config))
      }
   }

   override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      lifecycleScope.launch(Dispatchers.IO) {
         TempFileManager.cleanupManagedCache(this@MainActivity)
      }
      enableEdgeToEdge()
      setContent {
         AndroNSZTheme {
            AndroNSZApp(vm)
         }
      }
   }
}
