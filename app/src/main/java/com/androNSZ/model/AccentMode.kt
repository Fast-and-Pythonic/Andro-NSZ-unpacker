package com.androNSZ.model

/**
 * Accent color source. DEFAULT uses the app's fixed brand accent (#a6c8ff);
 * SYSTEM uses the dynamic Material You palette (Android 12+, falls back to the
 * built-in scheme on older devices); CUSTOM uses a user-picked seed color.
 */
enum class AccentMode {
   DEFAULT,
   SYSTEM,
   CUSTOM
}
