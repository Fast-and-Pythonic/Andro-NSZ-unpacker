package com.androNSZ.model

sealed class Screen {
   object ModeSelection : Screen()
   object Conversion : Screen()
   object About : Screen()
}
