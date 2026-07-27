package com.androNSZ.util

/**
 * One throughput telemetry sample, as written to `nsz_throughput.csv` by
 * [ThroughputRecorder].
 *
 * [bytes] is the **cumulative** aggregate output of the whole job, so a speed is
 * always a difference between two samples — never a per-sample value.
 *
 * [levelTag] is vestigial: it marked which calibration block a sample belonged to,
 * back when a search ran *inside* a real job. That search is gone (see the history
 * note in `model/ThreadMode.kt`) and nothing writes the field any more, but the
 * column stays so the CSV format — and anything already parsing it — does not change.
 */
data class ThroughputSample(
   val tMs: Long,
   val bytes: Long,
   val targetWorkers: Int,
   val activeWorkers: Int,
   val filesRemaining: Int,
   val levelTag: String
)

/** Speed of one calibration level: [round] measured [level] parallel unpacks. */
data class BlockStat(
   val round: Int,
   val level: Int,
   val mbps: Double
)

/**
 * What a finished calibration measured, kept so the numbers behind a verdict stay
 * visible in Settings instead of collapsing to a single thread count.
 *
 * A bare verdict cannot be sanity-checked: it looks equally plausible whether the
 * test measured the storage or (as a since-deleted synthetic test once did) the page
 * cache, and it gives the user nothing to compare against another run, another device
 * or a manual measurement. [levelMBps] is the raw per-level speed, [scores] the
 * drift-corrected per-round ratios the verdict was actually taken from — the two
 * disagree whenever flash wear skewed the raw numbers, which is exactly the case
 * worth seeing (gotchas.md G18).
 */
data class BenchSummary(
   val knee: Int,
   val levelMBps: Map<Int, Double>,
   val scores: Map<Int, Double>,
   val atMillis: Long
) {
   /**
    * Flattened for `SharedPreferences`, which has no map type. Deliberately a
    * hand-rolled format rather than JSON: it is three lines of pure code either
    * way, and this keeps the stored value greppable in a prefs dump.
    *
    * `knee;atMillis;level:mbps:score,level:mbps:score…` — a missing score is an
    * empty field. Numbers go through [toString]/[toDoubleOrNull], which are
    * locale-independent, so a device set to a comma decimal separator round-trips.
    */
   fun encode(): String {
      val levels = (levelMBps.keys + scores.keys).distinct().sorted()
      val rows = levels.joinToString(",") { level ->
         "$level:${levelMBps[level]?.toString() ?: ""}:${scores[level]?.toString() ?: ""}"
      }
      return "$knee;$atMillis;$rows"
   }

   companion object {
      /** Inverse of [encode]; null for anything that does not parse. */
      fun decode(s: String): BenchSummary? {
         val parts = s.split(';')
         if (parts.size < 3) return null
         val knee = parts[0].toIntOrNull() ?: return null
         val atMillis = parts[1].toLongOrNull() ?: return null
         val mbps = LinkedHashMap<Int, Double>()
         val scores = LinkedHashMap<Int, Double>()
         if (parts[2].isNotEmpty()) {
            for (row in parts[2].split(',')) {
               val f = row.split(':')
               if (f.size < 3) return null
               val level = f[0].toIntOrNull() ?: return null
               f[1].toDoubleOrNull()?.let { mbps[level] = it }
               f[2].toDoubleOrNull()?.let { scores[level] = it }
            }
         }
         return BenchSummary(knee, mbps, scores, atMillis)
      }
   }
}

/**
 * Turns the per-level timings of a calibration run into a verdict.
 *
 * Pure data in, pure data out — no Android APIs, no I/O — so every rule here is
 * covered by ordinary JUnit tests. The rules exist because the naive reading of
 * these numbers is wrong in two ways that matter:
 *
 *  1. **Flash drift.** Sustained writing exhausts the SLC cache: the *same* 4
 *     threads measured 1013 MB/s on a fresh flash and ~716 MB/s on a worn one
 *     (architecture.md A15). Measuring level 1, then 2, then 3 therefore reports a
 *     decline that has nothing to do with the thread count. The cure is on the
 *     producing side — sweep the levels once each way — and on this side:
 *     [levelScores] compares each level only against the *other levels of its own
 *     round*, then averages those ratios, so a slower late round cannot outvote an
 *     early one.
 *  2. **Run-to-run spread.** Repeats of an identical configuration disagree by
 *     3–9 %, so a difference smaller than [SIGNIFICANCE] is not a difference at all
 *     — hence [bestLevel] resolving a tie upwards rather than picking the raw
 *     fastest.
 */
object ThroughputAnalysis {

   /** Column order of `nsz_throughput.csv`; also its only non-`#` header line. */
   const val CSV_HEADER = "t_ms,bytes,target_workers,active_workers,files_remaining,level_tag"

   /**
    * How much better one level must measure before the difference is believed.
    * Repeat runs of an identical configuration on-device disagree by 3–9 %, so a
    * smaller margin than this would mostly be reading noise.
    */
   const val SIGNIFICANCE = 1.05

   /** One CSV row. [ThroughputSample.levelTag] is always empty, so nothing needs quoting. */
   fun toCsv(s: ThroughputSample): String =
      "${s.tMs},${s.bytes},${s.targetWorkers},${s.activeWorkers},${s.filesRemaining},${s.levelTag}"

   /** Parses a row written by [toCsv]; null for headers, comments and junk. */
   fun parseCsv(line: String): ThroughputSample? {
      val trimmed = line.trim()
      if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
      val f = trimmed.split(',')
      if (f.size < 6) return null
      return ThroughputSample(
         tMs = f[0].toLongOrNull() ?: return null,
         bytes = f[1].toLongOrNull() ?: return null,
         targetWorkers = f[2].toIntOrNull() ?: return null,
         activeWorkers = f[3].toIntOrNull() ?: return null,
         filesRemaining = f[4].toIntOrNull() ?: return null,
         levelTag = f[5]
      )
   }

   /** Median of [values]; null when empty. Even counts average the two middles. */
   fun medianOf(values: List<Double>): Double? {
      if (values.isEmpty()) return null
      val sorted = values.sorted()
      val mid = sorted.size / 2
      return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
   }

   /**
    * Score per thread count, drift-proof: within each round every level is
    * expressed as a fraction of that round's best level, and those fractions are
    * averaged across rounds. A score of 1.0 means "the best level of every round
    * it appeared in"; 0.8 means "consistently 20 % off the round's best".
    *
    * Comparing raw MB/s across rounds instead would simply rank the earliest
    * round's levels highest, because the flash is fastest while it is still fresh.
    */
   fun levelScores(blocks: List<BlockStat>): Map<Int, Double> {
      val ratios = LinkedHashMap<Int, MutableList<Double>>()
      for ((_, roundBlocks) in blocks.groupBy { it.round }) {
         val best = roundBlocks.maxOfOrNull { it.mbps } ?: continue
         if (best <= 0.0) continue
         for (b in roundBlocks) {
            ratios.getOrPut(b.level) { mutableListOf() }.add(b.mbps / best)
         }
      }
      return ratios.mapValues { (_, rs) -> rs.average() }
   }

   /**
    * The thread count to settle on: the **largest** level that is not significantly
    * worse than the best one measured.
    *
    * Not the smallest such level, deliberately. Run-to-run spread on device is
    * 3–9 %, wider than [SIGNIFICANCE] itself, so levels inside the band are
    * genuinely indistinguishable rather than tied — and under that uncertainty
    * the asymmetry decides: too few threads costs real wall-clock time, too many
    * costs nothing measurable. Only what measures *clearly* worse is discarded.
    */
   fun bestLevel(scores: Map<Int, Double>): Int? {
      if (scores.isEmpty()) return null
      val top = scores.values.max()
      return scores.filter { it.value * SIGNIFICANCE >= top }.keys.maxOrNull()
   }
}
