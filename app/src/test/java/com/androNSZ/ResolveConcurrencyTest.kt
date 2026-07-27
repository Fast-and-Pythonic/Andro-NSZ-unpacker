package com.androNSZ

import com.androNSZ.model.ThreadMode
import com.androNSZ.viewmodel.halfConcurrency
import com.androNSZ.viewmodel.resolveConcurrency
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers where the parallel-unpack thread count comes from in each mode, and the
 * invariants that hold in all of them: never more threads than files, never zero,
 * and always the same fallback when the chosen source has nothing to say.
 */
class ResolveConcurrencyTest {

   @Test
   fun halfRoundsDownAndNeverReachesZero() {
      assertEquals(4, halfConcurrency(8))
      assertEquals(3, halfConcurrency(7))
      assertEquals(2, halfConcurrency(4))
      assertEquals(1, halfConcurrency(2))
      assertEquals(1, halfConcurrency(1))
      assertEquals(1, halfConcurrency(0))
   }

   @Test
   fun halfModeIsTheDefaultAndIgnoresBothStoredValues() {
      assertEquals(4, resolveConcurrency(ThreadMode.HALF, 2, 3, 8, 10))
   }

   @Test
   fun manualModeUsesTheSlider() {
      assertEquals(2, resolveConcurrency(ThreadMode.MANUAL, 2, 0, 8, 10))
      assertEquals(8, resolveConcurrency(ThreadMode.MANUAL, 8, 0, 8, 10))
   }

   @Test
   fun manualModeFallsBackWhenTheSliderIsUnsetOrOutOfRange() {
      assertEquals(4, resolveConcurrency(ThreadMode.MANUAL, 0, 0, 8, 10))
      assertEquals(4, resolveConcurrency(ThreadMode.MANUAL, 99, 0, 8, 10))
      assertEquals(4, resolveConcurrency(ThreadMode.MANUAL, -1, 0, 8, 10))
   }

   @Test
   fun calibratedModeAppliesTheMeasuredValue() {
      assertEquals(3, resolveConcurrency(ThreadMode.CALIBRATED, 0, 3, 8, 10))
      // The test sweeps up to the core count, so all of them is a legal answer.
      assertEquals(8, resolveConcurrency(ThreadMode.CALIBRATED, 0, 8, 8, 10))
   }

   @Test
   fun calibratedModeFallsBackBeforeTheTestHasRun() {
      assertEquals(4, resolveConcurrency(ThreadMode.CALIBRATED, 0, 0, 8, 10))
      // A value from a device with more cores must not survive a config change.
      assertEquals(2, resolveConcurrency(ThreadMode.CALIBRATED, 0, 9, 4, 10))
   }

   @Test
   fun everyUnsetSourceLandsOnTheSameFallback() {
      // The point of making HALF the fallback: "unset" must not mean something
      // different depending on which mode the user happens to be in.
      val half = resolveConcurrency(ThreadMode.HALF, 0, 0, 8, 10)
      assertEquals(half, resolveConcurrency(ThreadMode.MANUAL, 0, 0, 8, 10))
      assertEquals(half, resolveConcurrency(ThreadMode.CALIBRATED, 0, 0, 8, 10))
   }

   @Test
   fun neverMoreThreadsThanFiles() {
      for (mode in ThreadMode.entries) {
         assertEquals("$mode with a single file", 1, resolveConcurrency(mode, 8, 8, 8, 1))
         assertEquals("$mode with two files", 2, resolveConcurrency(mode, 8, 8, 8, 2))
      }
   }

   @Test
   fun neverZeroEvenWithNonsenseInputs() {
      for (mode in ThreadMode.entries) {
         assertEquals("$mode with an empty queue", 1, resolveConcurrency(mode, 0, 0, 1, 0))
         assertEquals("$mode with zero cores", 1, resolveConcurrency(mode, 0, 0, 0, 5))
      }
   }
}
