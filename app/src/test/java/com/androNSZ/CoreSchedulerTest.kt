package com.androNSZ

import com.androNSZ.fs.CoreScheduler
import com.androNSZ.fs.CoreScheduler.CoreSpec
import org.junit.Assert.*
import org.junit.Test

class CoreSchedulerTest {

   private fun makespan(
      assign: List<List<Int>>,
      cores: List<CoreSpec>,
      weights: LongArray
   ): Double = cores.indices.maxOf { c ->
      assign[c].sumOf { weights[it].toDouble() } / cores[c].speed
   }

   /** Every input index must be assigned to exactly one core. */
   private fun assertPartition(assign: List<List<Int>>, count: Int) {
      val flat = assign.flatten().sorted()
      assertEquals((0 until count).toList(), flat)
   }

   @Test
   fun heterogeneousPutsBigFilesOnFastCores() {
      // 4 cores: 2 fast (speed 2), 2 slow (speed 1). 6 files of mixed size.
      val cores = listOf(
         CoreSpec(0, 2.0, 1L), CoreSpec(1, 2.0, 2L),
         CoreSpec(2, 1.0, 4L), CoreSpec(3, 1.0, 8L)
      )
      val weights = longArrayOf(10_000, 9_000, 8_000, 7_000, 100, 50)
      val assign = CoreScheduler.computeAssignment((0 until 6).toList(), { weights[it] }, cores)

      assertPartition(assign, 6)

      // The two largest files land on the two fast cores.
      val fastCoreFiles = assign[0] + assign[1]
      assertTrue("largest file on a fast core", 0 in fastCoreFiles)
      assertTrue("second-largest file on a fast core", 1 in fastCoreFiles)

      // Makespan is far below the naive "all heavy on one/slow core" outcome; the
      // balanced assignment finishes around 8000 time units, never near 15000+.
      assertTrue("makespan too high: ${makespan(assign, cores, weights)}",
         makespan(assign, cores, weights) <= 8_500.0)
   }

   @Test
   fun homogeneousBalancesLoad() {
      val cores = (0 until 4).map { CoreSpec(it, 1.0, 1L shl it) }
      val weights = LongArray(8) { 1_000 }  // 8 equal files on 4 equal cores
      val assign = CoreScheduler.computeAssignment((0 until 8).toList(), { weights[it] }, cores)

      assertPartition(assign, 8)
      // Perfect balance: 2 files (2000 units) per core.
      assertEquals(2_000.0, makespan(assign, cores, weights), 1e-6)
   }

   @Test
   fun emptyInputsAreHandled() {
      val cores = listOf(CoreSpec(0, 1.0, 1L))
      assertTrue(CoreScheduler.computeAssignment(emptyList(), { 0L }, cores).all { it.isEmpty() })
      assertTrue(CoreScheduler.computeAssignment(listOf(0), { 1L }, emptyList()).isEmpty())
   }
}
