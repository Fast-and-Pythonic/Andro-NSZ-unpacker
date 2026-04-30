package com.androNSZ.model

data class Compact2Row(
   val label: String,
   val success: Int,
   val failed: Int,
   val total: Int
)

data class Compact2Stats(
   val headerLine: String,
   val rows: List<Compact2Row>,
   val footerLine: String
)
