package com.androNSZ.model

/**
 * Accent color source. SYSTEM uses the dynamic Material You palette (Android 12+,
 * falls back to the built-in scheme on older devices); CUSTOM uses a user-picked
 * seed color.
 */
enum class AccentMode {
   SYSTEM,
   CUSTOM
}
