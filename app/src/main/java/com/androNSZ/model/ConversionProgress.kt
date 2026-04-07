package com.androNSZ.model

data class ConversionProgress(
   val doneBytes: Long,
   val totalBytes: Long,
   val speedMBps: Double,
) {
   val percent: Float get() = if (totalBytes > 0) doneBytes.toFloat() / totalBytes else 0f
}
