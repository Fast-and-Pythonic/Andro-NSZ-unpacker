package com.androNSZ

import com.androNSZ.util.CpuTopology
import org.junit.Assert.*
import org.junit.Test

class CpuTopologyTest {

   /** A typical big.LITTLE layout: cpu0-3 little (256), cpu4-7 big (1024). */
   private fun bigLittle() = CpuTopology.Topology(
      (0 until 4).map { CpuTopology.Core(it, 256) } +
      (4 until 8).map { CpuTopology.Core(it, 1024) }
   )

   @Test
   fun detectsHeterogeneity() {
      assertTrue(bigLittle().isHeterogeneous)
      val uniform = CpuTopology.Topology((0 until 4).map { CpuTopology.Core(it, 1024) })
      assertFalse(uniform.isHeterogeneous)
   }

   @Test
   fun speedIsNormalizedToFastestCore() {
      val t = bigLittle()
      assertEquals(1.0, t.speedOf(4), 1e-9)          // big core = 1.0
      assertEquals(0.25, t.speedOf(0), 1e-9)         // little = 256/1024
   }

   @Test
   fun clusterMaskCoversTheWholeTier() {
      val t = bigLittle()
      // Little tier = cpu0..3 => bits 0..3 = 0b1111 = 0xF.
      assertEquals(0xFL, t.clusterMask(0))
      // Big tier = cpu4..7 => bits 4..7 = 0xF0.
      assertEquals(0xF0L, t.clusterMask(4))
   }
}
