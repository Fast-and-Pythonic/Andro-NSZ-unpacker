package com.androNSZ.ui.theme

import androidx.compose.ui.graphics.Color

// Default custom-accent seed / brand purple (see SettingsRepository.DEFAULT_ACCENT_COLOR).
val Purple40 = Color(0xFF6650a4)

// Fixed brand accent used by AccentMode.DEFAULT (the app's out-of-box accent).
val DefaultAccent = Color(0xFFA6C8FF)

// "Done" status accent. A mid green that stays readable on the surfaceVariant
// card in both light and dark themes.
val SuccessGreen = Color(0xFF43A047)

// "Not checked" (verification didn't run) accent — amber, matching the log's WARN.
val WarningOrange = Color(0xFFFF9800)

// Destructive (delete) accent. In the dark theme the Material error color is a
// pale, washed-out red; we use this stronger red instead so the "Delete" item
// matches the saturated red of the light theme. Adjust the hex here to taste.
val DestructiveRed = Color(0xFFB34030)