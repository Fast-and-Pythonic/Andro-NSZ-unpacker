package com.androNSZ.fs

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Core-aware scheduler for parallel file conversion on heterogeneous CPUs
 * (big.LITTLE). It minimizes makespan (the Q||Cmax problem): heavy files go to
 * fast cores, and a slow core is deliberately left idle rather than handed a
 * heavy file that a fast core could finish sooner. See architecture.md A13.
 *
 * The estimate is time ≈ weight / speed, with the file's (compressed) size as the
 * work proxy and the core's sysfs capacity as the speed proxy — see
 * [com.androNSZ.util.CpuTopology].
 */
object CoreScheduler {

   /** A core to schedule onto: [speed] relative (fastest = 1.0), [mask] its cluster affinity (0 = don't pin). */
   data class CoreSpec(val coreId: Int, val speed: Double, val mask: Long)

   /**
    * Assign file [indices] (weighted by [weightOf]) across [cores], returning the
    * ordered index list per core (parallel to [cores]). LPT + greedy
    * earliest-completion-time, then a makespan-minimizing local search that fixes
    * greedy's myopia (e.g. a big file dumped on an idle slow core).
    */
   fun computeAssignment(
      indices: List<Int>,
      weightOf: (Int) -> Long,
      cores: List<CoreSpec>
   ): List<MutableList<Int>> {
      val assign = List(cores.size) { mutableListOf<Int>() }
      if (cores.isEmpty() || indices.isEmpty()) return assign

      val load = DoubleArray(cores.size)  // projected finish time per core
      fun cost(idx: Int, c: Int) = weightOf(idx).toDouble() / cores[c].speed

      // LPT: place the largest jobs first, each on the core that would finish it
      // earliest given its current load.
      for (idx in indices.sortedByDescending { weightOf(it) }) {
         var best = 0
         var bestFinish = Double.MAX_VALUE
         for (c in cores.indices) {
            val finish = load[c] + cost(idx, c)
            if (finish < bestFinish) { bestFinish = finish; best = c }
         }
         assign[best].add(idx)
         load[best] += cost(idx, best)
      }

      localSearch(assign, load, cores, ::cost)
      return assign
   }

   /**
    * Repeatedly move one job off the makespan (max-load) core to another core when
    * that strictly lowers the global maximum finish time. Small N, so this bounded
    * pass converges quickly. Moves-only (no swaps) — enough for the big/little
    * cases we target.
    */
   private fun localSearch(
      assign: List<MutableList<Int>>,
      load: DoubleArray,
      cores: List<CoreSpec>,
      cost: (Int, Int) -> Double
   ) {
      repeat(200) {
         val oldMax = load.max()
         val hi = load.indices.maxByOrNull { load[it] } ?: return
         var moved = false

         outer@ for (idx in assign[hi].toList()) {
            for (c in cores.indices) {
               if (c == hi) continue
               val newHi = load[hi] - cost(idx, hi)
               val newC = load[c] + cost(idx, c)
               var g = maxOf(newHi, newC)
               for (k in cores.indices) if (k != hi && k != c) g = maxOf(g, load[k])
               if (g < oldMax - 1e-9) {
                  assign[hi].remove(idx)
                  assign[c].add(idx)
                  load[hi] = newHi
                  load[c] = newC
                  moved = true
                  break@outer
               }
            }
         }
         if (!moved) return
      }
   }

   /**
    * Compute the assignment and run it: one coroutine per used core, each
    * processing its assigned indices sequentially via [body]. A core's cluster
    * [CoreSpec.mask] (or null when 0) is passed to [body] so the conversion pins
    * itself. No work-stealing — a finished core stops (may idle), which is the
    * point: it protects makespan.
    */
   suspend fun run(
      indices: List<Int>,
      weightOf: (Int) -> Long,
      cores: List<CoreSpec>,
      body: suspend (index: Int, affinityMask: Long?) -> Unit
   ) {
      val plan = computeAssignment(indices, weightOf, cores)
      coroutineScope {
         for (c in cores.indices) {
            val jobs = plan[c]
            if (jobs.isEmpty()) continue
            val mask = cores[c].mask.takeIf { it != 0L }
            launch {
               for (idx in jobs) body(idx, mask)
            }
         }
      }
   }
}
