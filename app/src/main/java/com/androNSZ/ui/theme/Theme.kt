package com.androNSZ.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.androNSZ.model.AccentMode
import com.materialkolor.rememberDynamicColorScheme

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
      AccentMode.SYSTEM -> {
         // minSdk is 31, so Material You dynamic color is always available.
         val context = LocalContext.current
         if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
   }

   MaterialTheme(
      colorScheme = colorScheme,
      typography = Typography,
      content = content
   )
}
