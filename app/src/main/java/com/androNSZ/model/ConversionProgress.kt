package com.androNSZ.model

/**
 * A progress snapshot. [doneBytes]/[totalBytes] are live and drive the smooth
 * progress bar; [percent] is derived from them. The `display*` fields are frozen
 * on their own, slower cadences (see Constants.PROGRESS_*_UPDATE_INTERVAL_MS) so
 * the textual readouts stay readable while the bar keeps animating. They default
 * to the live values for callers that don't throttle separately.
 */
data class ConversionProgress(
   val doneBytes: Long,
   val totalBytes: Long,
   val speedMBps: Double,
   val displayPercent: Float = if (totalBytes > 0) doneBytes.toFloat() / totalBytes else 0f,
   val displayDoneBytes: Long = doneBytes,
   val displayTotalBytes: Long = totalBytes,
) {
   val percent: Float get() = if (totalBytes > 0) doneBytes.toFloat() / totalBytes else 0f
}
