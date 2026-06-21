package com.androNSZ.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.androNSZ.model.AccentMode
import com.materialkolor.rememberDynamicColorScheme

private val DarkColorScheme = darkColorScheme(
   primary = Purple80,
   secondary = PurpleGrey80,
   tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
   primary = Purple40,
   secondary = PurpleGrey40,
   tertiary = Pink40
)

@Composable
fun AndroNSZTheme(
   darkTheme: Boolean = isSystemInDarkTheme(),
   accentMode: AccentMode = AccentMode.SYSTEM,
   accentColor: Color = Purple40,
   content: @Composable () -> Unit
) {
   val colorScheme = when (accentMode) {
      // Generate a full M3 tonal palette from the picked seed: every role
      // (primary/secondary/tertiary + their containers, surfaces, etc.) is
      // derived harmoniously, not just the primary family.
      AccentMode.CUSTOM -> rememberDynamicColorScheme(
         seedColor = accentColor,
         isDark = darkTheme,
         isAmoled = false
      )
      AccentMode.SYSTEM ->
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
         } else {
            if (darkTheme) DarkColorScheme else LightColorScheme
         }
   }

   MaterialTheme(
      colorScheme = colorScheme,
      typography = Typography,
      content = content
   )
}
