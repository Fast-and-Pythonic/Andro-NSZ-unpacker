package com.androNSZ.util

import java.io.File

/**
 * CPU topology — per-core speed and big/little clusters — read once from sysfs
 * and cached. Used by [com.androNSZ.fs.CoreScheduler] to assign heavy files to
 * fast cores and to pin work to a core's cluster via thread affinity.
 *
 * Speed proxy: `/sys/devices/system/cpu/cpuN/cpu_capacity` (the kernel's 0..1024
 * capacity, directly reflecting big/little), falling back to
 * `cpufreq/cpuinfo_max_freq`, then to "all equal" (homogeneous). Every read is
 * best-effort — any failure degrades to homogeneous and never throws.
 */
object CpuTopology {

   data class Core(val id: Int, val capacity: Int)

   data class Topology(val cores: List<Core>) {
      val coreCount: Int get() = cores.size

      /** True when cores differ in capacity (a big.LITTLE layout). */
      val isHeterogeneous: Boolean get() = cores.map { it.capacity }.distinct().size > 1

      /** Relative speed of a core, normalized so the fastest core = 1.0. */
      fun speedOf(id: Int): Double {
         val cap = cores.firstOrNull { it.id == id }?.capacity ?: 1
         val max = cores.maxOfOrNull { it.capacity } ?: 1
         return if (max > 0) cap.toDouble() / max else 1.0
      }

      /**
       * Bitmask of every core in the same tier (equal capacity) as [id]. Pinning
       * to the whole cluster — not a single CPU — lets the kernel move within the
       * tier and tolerates core hotplug. 0 if [id] is unknown or out of range.
       */
      fun clusterMask(id: Int): Long {
         val cap = cores.firstOrNull { it.id == id }?.capacity ?: return 0L
         var mask = 0L
         for (c in cores) {
            if (c.capacity == cap && c.id in 0..63) mask = mask or (1L shl c.id)
         }
         return mask
      }
   }

   @Volatile private var cached: Topology? = null

   fun detect(): Topology {
      cached?.let { return it }
      return synchronized(this) { cached ?: readTopology().also { cached = it } }
   }

   private fun readTopology(): Topology {
      val n = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
      val caps = IntArray(n)

      // Prefer cpu_capacity (best signal); else max_freq; else homogeneous.
      var have = true
      for (i in 0 until n) {
         val c = readIntFile("/sys/devices/system/cpu/cpu$i/cpu_capacity")
         if (c == null) { have = false; break }
         caps[i] = c
      }
      if (!have) {
         have = true
         for (i in 0 until n) {
            val f = readIntFile("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            if (f == null) { have = false; break }
            caps[i] = f
         }
      }
      if (!have) for (i in 0 until n) caps[i] = 1024  // homogeneous fallback

      return Topology((0 until n).map { Core(it, caps[it].coerceAtLeast(1)) })
   }

   private fun readIntFile(path: String): Int? = try {
      val f = File(path)
      if (!f.canRead()) null else f.readText().trim().toIntOrNull()
   } catch (e: Exception) {
      null
   }
}
