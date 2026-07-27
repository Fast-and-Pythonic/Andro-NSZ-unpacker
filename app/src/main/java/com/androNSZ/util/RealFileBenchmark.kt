package com.androNSZ.util

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.androNSZ.NszConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Finds how many files this device rewards unpacking at once, by unpacking a real
 * NSZ/XCZ the user picks.
 *
 * ### It does exactly what a job does
 * A level of N runs **N complete conversions** in parallel, start to finish, timed
 * end to end; then the outputs are deleted and the next level begins. Nothing is cut
 * short, nothing is sampled: the number reported is total output bytes divided by
 * wall-clock time, which is the same quantity the user sees on a real batch.
 *
 * An earlier version cancelled each level partway once a gigabyte of output had
 * appeared, and derived the speed from progress counters. That machinery had two
 * defects that both biased the ranking rather than merely the scale — the clock was
 * stopped *after* cancelling and deleting the output, and only the first level ever
 * paid for a cold page cache. Running whole conversions removes the need for any of
 * it, and with it both defects.
 *
 * ### No copies of the source are needed
 * All N conversions read the *same* file into N different outputs. After the first
 * pass the reads are served from the page cache, which is what isolates the write
 * path; N distinct sources would measure reads as well and need N times the input.
 *
 * ### Why the sweep runs both ways
 * Flash slows as it is written (1013 MB/s fresh against ~716 MB/s worn — A15), so a
 * single pass measures later levels on a more tired flash and would systematically
 * favour whichever ran first. The sweep goes up 1→cores and then back down, so each
 * level sits early once and late once. G18 names reversing the order as *the* check
 * for this bias: a sound method gives the same answer both ways.
 *
 * ### Two things to know before touching this
 *  - It calls the engine directly rather than through `NszConverter.convert`, because
 *    that helper derives the output name from the input, so N parallel unpacks of one
 *    file would all target the same output. Here the outputs are plain cache paths.
 *  - [NszConverter.nativeCancel] is **global**: it stops *every* in-flight conversion
 *    (architecture.md A15). It is now used only for the user's Cancel button, and it
 *    still means this must never run while a real job converts — the caller enforces
 *    that.
 */
object RealFileBenchmark {

   /** Progress of one finished level. */
   data class LevelResult(
      val levelsDone: Int,
      val round: Int,
      val level: Int,
      val mbps: Double
   )

   /** Verdict of a complete run. */
   data class Outcome(
      val knee: Int,
      val levelMBps: Map<Int, Double>,
      val scores: Map<Int, Double>
   )

   /** Sweeps of the level range: one ascending, one descending. */
   const val ROUNDS = 2

   /**
    * Output a source of this size or larger produces per conversion, used only to
    * size the free-space check. Unpacking expands the input by roughly a third.
    */
   private const val OUTPUT_RATIO = 1.4

   /** Levels a test on this device will measure; one warm-up run precedes them. */
   fun levelCount(cores: Int): Int = ROUNDS * cores.coerceAtLeast(1)

   /**
    * Runs the test on [sourceUri] and returns the thread count to adopt, or null if
    * nothing measurable came out of it (no space, or every conversion failed).
    *
    * [onLevel] is called after each finished level. Cancellation takes effect between
    * conversions only if the caller also calls [NszConverter.nativeCancel] — a single
    * conversion is uninterruptible from Kotlin.
    */
   suspend fun run(
      context: Context,
      sourceUri: Uri,
      cores: Int = Runtime.getRuntime().availableProcessors(),
      onLevel: ((LevelResult) -> Unit)? = null
   ): Outcome? {
      val dir = benchDir(context) ?: return null
      val maxLevel = cores.coerceAtLeast(1)

      val sourceName = withContext(Dispatchers.IO) { queryFileName(context, sourceUri) }
      val isXcz = sourceName.endsWith(".xcz", ignoreCase = true)

      // Peak usage is one level's outputs, and the widest level is the last to run —
      // finding out then would waste the whole test.
      val sourceBytes = withContext(Dispatchers.IO) { getUriSize(context, sourceUri) }
      if (sourceBytes > 0) {
         val peakNeeded = (sourceBytes * OUTPUT_RATIO * maxLevel).toLong()
         if (withContext(Dispatchers.IO) { dir.usableSpace } < peakNeeded) return null
      }

      val blocks = mutableListOf<BlockStat>()
      val rawByLevel = LinkedHashMap<Int, MutableList<Double>>()
      var levelsDone = 0

      try {
         // Discarded warm-up. Every conversion re-reads the source from the start and
         // only the first finds it cold; measuring that one would charge a single
         // level — always the same one — for reads every other level got for free.
         measureLevel(context, sourceUri, isXcz, dir, 1)

         for (round in 1..ROUNDS) {
            val levels = if (round % 2 == 1) 1..maxLevel else maxLevel downTo 1
            for (level in levels) {
               val mbps = measureLevel(context, sourceUri, isXcz, dir, level)
               levelsDone++
               // A failed conversion says nothing about the level, so it is skipped
               // rather than recorded as slow.
               if (mbps == null) continue
               blocks += BlockStat(round = round, level = level, mbps = mbps)
               rawByLevel.getOrPut(level) { mutableListOf() }.add(mbps)
               onLevel?.invoke(LevelResult(levelsDone, round, level, mbps))
            }
         }
      } finally {
         cleanup(dir)
      }

      val scores = ThroughputAnalysis.levelScores(blocks)
      val knee = ThroughputAnalysis.bestLevel(scores) ?: return null
      return Outcome(
         knee = knee,
         levelMBps = rawByLevel.mapValues { (_, v) -> ThroughputAnalysis.medianOf(v) ?: 0.0 },
         scores = scores
      )
   }

   /**
    * One level: [level] complete conversions of the source in parallel. Returns
    * aggregate MB/s over the whole run, or null if any of them failed.
    */
   private suspend fun measureLevel(
      context: Context,
      sourceUri: Uri,
      isXcz: Boolean,
      dir: File,
      level: Int
   ): Double? = coroutineScope {
      cleanup(dir)
      val outputs = (0 until level).map { i ->
         File(dir, "real_bench_$i" + if (isXcz) ".xci" else ".nsp")
      }

      val startedAtMs = System.currentTimeMillis()
      val codes = (0 until level).map { i ->
         async(Dispatchers.IO) {
            var pfd: ParcelFileDescriptor? = null
            try {
               val inputPath = if (sourceUri.scheme == "file") {
                  sourceUri.path ?: return@async -1
               } else {
                  // One descriptor per conversion: native dup()s it, and sharing a
                  // single fd across them would share its file position too.
                  val p = context.contentResolver.openFileDescriptor(sourceUri, "r")
                     ?: return@async -1
                  pfd = p
                  "fd:${p.fd}"
               }
               if (isXcz) {
                  NszConverter.nativeConvertXcz(inputPath, outputs[i].absolutePath, null, null)
               } else {
                  NszConverter.nativeConvert(inputPath, outputs[i].absolutePath, null, null)
               }
            } finally {
               runCatching { pfd?.close() }
            }
         }
      }.awaitAll()
      // Stop the clock before touching the filesystem: summing lengths and deleting
      // several GB is not unpacking, and both cost more the wider the level is.
      val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L)

      val bytes = withContext(Dispatchers.IO) { outputs.sumOf { it.length() } }
      cleanup(dir)

      if (codes.any { it != 0 } || bytes <= 0L) return@coroutineScope null
      bytes / 1024.0 / 1024.0 / (elapsedMs / 1000.0)
   }

   private fun benchDir(context: Context): File? = runCatching {
      File(context.cacheDir, "real_bench").apply { mkdirs() }
   }.getOrNull()?.takeIf { it.isDirectory }

   private fun cleanup(dir: File) {
      runCatching { dir.listFiles()?.forEach { it.delete() } }
   }

   private fun queryFileName(context: Context, uri: Uri): String {
      if (uri.scheme == "file") return uri.lastPathSegment ?: ""
      return runCatching {
         context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
         }
      }.getOrNull() ?: uri.lastPathSegment ?: ""
   }
}
