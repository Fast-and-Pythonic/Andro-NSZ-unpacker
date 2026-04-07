package com.androNSZ

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.androNSZ.fs.TempFileManager
import com.androNSZ.ui.screen.AndroNSZApp
import com.androNSZ.ui.theme.AndroNSZTheme
import com.androNSZ.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
   private val vm: MainViewModel by lazy {
      ViewModelProvider(this)[MainViewModel::class.java]
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
