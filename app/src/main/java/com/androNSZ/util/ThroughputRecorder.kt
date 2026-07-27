package com.androNSZ.util

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Records the job's **aggregate** output throughput to `nsz_throughput.csv`.
 *
 * Aggregate, not per-file: with several files unpacking at once only the total
 * matters — that is the number the storage write ceiling caps (architecture.md
 * A15), and the only one worth optimising the worker count against.
 *
 * ### Why it samples instead of logging every update
 * A single progress callback carries no usable speed (it fires every few
 * milliseconds, and one writeback stall dwarfs the interval). So the counters are
 * *pushed* here as they change and *pulled* on a fixed [SAMPLE_INTERVAL_MS] grid;
 * speeds are then differences between samples, computed by [ThroughputAnalysis]
 * over windows far longer than one sample. At 1 GB/s a sample still covers ~100 MB
 * of output, and ten minutes of recording is ~6000 rows (≈300 KB).
 *
 * ### Threading
 * The byte counter is updated from wherever progress happens to arrive — the main
 * thread in queue mode, native callback threads under `synchronized(lock)` in
 * folder mode. Rather than reach into either, the recorder owns plain atomics that
 * both sides write and the sampling coroutine reads. It therefore shares no lock
 * with the conversion pipeline and cannot stall it.
 *
 * Failing to open the file is not an error worth propagating: telemetry must never
 * take a conversion down, so [open] returns null and every caller no-ops.
 */
class ThroughputRecorder private constructor(
   private val writer: BufferedWriter,
   /** Path of the CSV, for status lines that point the user at it. */
   val path: String
) {
   
   /**
    * Called with every sample as it is taken, on the sampling coroutine. This is
    * how a live consumer reads the same stream the CSV gets, instead of
    * running a second sampler beside it.
    */
   @Volatile var onSample: ((ThroughputSample) -> Unit)? = null
   
   private val bytes = AtomicLong(0L)
   private val targetWorkers = AtomicInteger(0)
   private val activeWorkers = AtomicInteger(0)
   private val filesRemaining = AtomicInteger(0)
   // Always empty since the in-job calibration search was removed; the CSV column
   // stays for format stability. See ThroughputSample.levelTag.
   private val levelTag: String = ""
   
   private var job: Job? = null
   private var startedAtMs: Long = 0L
   
   /** Cumulative aggregate output of the job so far, in bytes. */
   fun setBytes(value: Long) { bytes.set(value.coerceAtLeast(0L)) }
   
   /** The latest value passed to [setBytes]; how a byte-sized block knows it is done. */
   fun bytesSoFar(): Long = bytes.get()
   
   /** How many workers the job is *allowed* to run right now. */
   fun setTargetWorkers(value: Int) { targetWorkers.set(value) }
   
   /** How many workers are actually converting right now. */
   fun setActiveWorkers(value: Int) { activeWorkers.set(value) }
   
   /** Files not finished yet — a level whose workers outnumber these is starved. */
   fun setFilesRemaining(value: Int) { filesRemaining.set(value) }
   
   /** Starts sampling. Safe to call once; a second call is ignored. */
   fun start(scope: CoroutineScope) {
      if (job != null) return
      startedAtMs = System.currentTimeMillis()
      job = scope.launch(Dispatchers.IO) {
         var sinceFlush = 0
         try {
            while (isActive) {
               writeSample()
               // Flushed in batches: a single row is worthless on its own anyway,
               // and the finally below flushes whatever is left.
               if (++sinceFlush >= FLUSH_EVERY) {
                  sinceFlush = 0
                  runCatching { writer.flush() }
               }
               delay(SAMPLE_INTERVAL_MS)
            }
         } finally {
            // Closing the file belongs to the sampler, not to stop(): that way
            // nothing is written after the handle is gone, and stop() never has
            // to block a completion callback waiting for this coroutine.
            writeSample()
            runCatching { writer.flush() }
            runCatching { writer.close() }
         }
      }
   }
   
   /**
    * Stops sampling; the sampler then writes one last row and closes the file.
    * Returns immediately and never throws — a logger must not be able to take a
    * conversion down with it.
    */
   fun stop() {
      job?.cancel()
      job = null
   }
   
   private fun writeSample() {
      val sample = ThroughputSample(
         tMs = System.currentTimeMillis() - startedAtMs,
         bytes = bytes.get(),
         targetWorkers = targetWorkers.get(),
         activeWorkers = activeWorkers.get(),
         filesRemaining = filesRemaining.get(),
         levelTag = levelTag
      )
      runCatching {
         writer.write(ThroughputAnalysis.toCsv(sample))
         writer.newLine()
      }
      runCatching { onSample?.invoke(sample) }
   }
   
   companion object {
      /**
       * 10 Hz. Native progress is already throttled to 50 ms upstream, so a faster
       * grid would only duplicate rows; a slower one would leave too few windows
       * per calibration block to take a median over.
       */
      const val SAMPLE_INTERVAL_MS = 100L
      
      private const val FLUSH_EVERY = 50
      
      /**
       * Opens the CSV for one job, rotating the previous run's file aside
       * (`nsz_throughput.prev.csv`) exactly like every other log sink — one file =
       * one run. The `#`-commented banner carries the same run number as the other
       * logs, which is what ties them together; the first uncommented line is the
       * column header, so the file stays machine-readable.
       *
       * Returns null if the file cannot be opened.
       */
      fun open(context: Context, runId: Int, mode: String): ThroughputRecorder? = runCatching {
         val file = File(context.getExternalFilesDir(null), LogFiles.THROUGHPUT_CSV)
         LogFiles.rotate(file)
         val writer = file.bufferedWriter()
         writer.write(LogFiles.commentedBanner(runId, "Throughput telemetry", mode))
         writer.newLine()
         writer.write(ThroughputAnalysis.CSV_HEADER)
         writer.newLine()
         ThroughputRecorder(writer, file.absolutePath)
      }.getOrNull()
   }
}
