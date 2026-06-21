package com.androNSZ.util

import com.androNSZ.Constants
import com.androNSZ.model.ConversionProgress

/**
 * Turns a raw byte-progress stream into [ConversionProgress] snapshots where the
 * progress bar follows the live byte counter while each textual readout —
 * percent, speed, size — refreshes on its own, slower cadence. This keeps the
 * numbers calm and readable without making the bar stutter.
 *
 * Each metric type has its own interval in [Constants]; tune them independently
 * to speed up or slow down a single readout.
 *
 * Not thread-safe: use one instance per progress stream.
 */
class ProgressThrottler {
   private var lastBarMs = System.currentTimeMillis()
   private var lastPercentMs = 0L
   private var lastSpeedMs = 0L
   private var lastSizeMs = 0L

   private var shownPercent = 0f
   private var shownSpeed = 0.0
   private var shownDone = 0L
   private var shownTotal = 0L

   // Baseline for averaging speed over the speed interval.
   private var speedBaseDone = 0L
   private var speedBaseMs = System.currentTimeMillis()

   /**
    * Returns a snapshot to emit, or null when the bar interval hasn't elapsed yet
    * (the caller should skip emitting). The percent/speed/size fields keep their
    * previous frozen values until their own interval elapses.
    */
   fun sample(
      done: Long,
      total: Long,
      now: Long = System.currentTimeMillis()
   ): ConversionProgress? {
      if (now - lastBarMs < Constants.PROGRESS_BAR_UPDATE_INTERVAL_MS) return null
      lastBarMs = now

      if (now - lastPercentMs >= Constants.PROGRESS_PERCENT_UPDATE_INTERVAL_MS) {
         shownPercent = if (total > 0) done.toFloat() / total else 0f
         lastPercentMs = now
      }
      if (now - lastSpeedMs >= Constants.PROGRESS_SPEED_UPDATE_INTERVAL_MS) {
         val dtSec = (now - speedBaseMs).coerceAtLeast(1L) / 1000.0
         shownSpeed = (done - speedBaseDone).toDouble() / 1024 / 1024 / dtSec
         speedBaseDone = done
         speedBaseMs = now
         lastSpeedMs = now
      }
      if (now - lastSizeMs >= Constants.PROGRESS_SIZE_UPDATE_INTERVAL_MS) {
         shownDone = done
         shownTotal = total
         lastSizeMs = now
      }

      return ConversionProgress(
         doneBytes = done,
         totalBytes = total,
         speedMBps = shownSpeed,
         displayPercent = shownPercent,
         displayDoneBytes = shownDone,
         displayTotalBytes = shownTotal
      )
   }
}
