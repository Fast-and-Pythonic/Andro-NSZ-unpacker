package com.androNSZ.util

import com.androNSZ.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared rules for the app's log files.
 *
 * The invariant every sink follows: **one file = exactly one run**. Starting a run
 * rotates the current file to `<name>.prev.<ext>` (dropping the older `.prev`) and
 * writes the new one from scratch, so logs can never pile up — and each file opens
 * with a banner carrying the run number, which is what makes a stale log obvious
 * instead of being mistaken for the current one.
 *
 * Sinks: `nsz_debug.log` (native engine trace), `nsz_folder_debug.log` (Kotlin
 * status lines for folder/combined runs and folder scans), `nsz_screen_log.txt`
 * (manual snapshot of the on-screen log), `nsz_throughput.csv` (aggregate
 * throughput telemetry), plus logcat, which is never rotated.
 */
object LogFiles {

   const val NATIVE_LOG = "nsz_debug.log"
   const val FOLDER_LOG = "nsz_folder_debug.log"
   const val SCREEN_LOG = "nsz_screen_log.txt"
   const val THROUGHPUT_CSV = "nsz_throughput.csv"

   /** `nsz_debug.log` -> `nsz_debug.prev.log` (extension preserved). */
   fun prevOf(file: File): File {
      val name = file.name
      val dot = name.lastIndexOf('.')
      val rotated = if (dot > 0) {
         name.substring(0, dot) + ".prev" + name.substring(dot)
      } else {
         "$name.prev"
      }
      return File(file.parentFile, rotated)
   }

   /**
    * Keeps the previous run available: drops the old `.prev` and moves the current
    * file into its place. A no-op when [file] does not exist yet.
    *
    * Best-effort by design — a log that cannot be rotated must never take a
    * conversion down with it.
    */
   fun rotate(file: File) {
      runCatching {
         if (!file.exists()) return
         val prev = prevOf(file)
         if (prev.exists()) prev.delete()
         if (!file.renameTo(prev)) {
            // Rename can fail across some FUSE-backed providers; falling back to a
            // copy keeps the guarantee that the new run starts from an empty file.
            file.copyTo(prev, overwrite = true)
            file.delete()
         }
      }
   }

   /**
    * The header every log file starts with: which run this is, when it started,
    * and a short reminder of how logging is organised here — so a log read months
    * later (or by a fresh pair of eyes) explains itself.
    *
    * [kind] is the log's own name (e.g. "Native engine log"), [mode] the job that
    * produced it (queue / folder / combined / folder-scan / screen-snapshot).
    */
   fun banner(runId: Int, kind: String, mode: String): String {
      val started = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
      val build = if (BuildConfig.DEBUG) "debug" else "release"
      return buildString {
         appendLine("=== AndroNSZ: $kind ===")
         appendLine("Run      : #$runId")
         appendLine("Started  : $started")
         appendLine("Mode     : $mode")
         appendLine("App      : ${BuildConfig.VERSION_NAME} ($build)")
         appendLine("--- how logging works here ---")
         appendLine("* One file = one run. The previous run is kept as <name>.prev.<ext>;")
         appendLine("  anything older is gone. Check the Run # before trusting a log.")
         appendLine("* The same \"Run #\" in different log files means the same run.")
         appendLine("* $NATIVE_LOG        - native engine trace, [elapsed ms] since this run started")
         appendLine("* $FOLDER_LOG - Kotlin status lines: folder/combined runs and folder scans")
         appendLine("* $SCREEN_LOG   - manual snapshot of the on-screen log (Save button)")
         appendLine("* $THROUGHPUT_CSV  - aggregate throughput samples, 10 Hz (see ThroughputRecorder)")
         appendLine("* logcat (tag AndroNSZ) gets every native line even when no file log is open.")
         appendLine()
      }
   }

   /**
    * The same [banner], turned into `#` comment lines so it can head a CSV
    * without making the file unparseable. Blank lines become a bare `#` — a
    * trailing space there would show up as data in some readers.
    */
   fun commentedBanner(runId: Int, kind: String, mode: String): String =
      banner(runId, kind, mode)
         .lineSequence()
         .joinToString("\n") { if (it.isEmpty()) "#" else "# $it" }
}
