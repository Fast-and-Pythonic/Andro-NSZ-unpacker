package com.androNSZ

import com.androNSZ.util.LogFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the "one file = one run, plus one previous" invariant: rotation must keep
 * exactly two generations and never throw, and the banner must carry the run number
 * that tells a stale log apart from the current one.
 */
class LogFilesTest {

   @get:Rule
   val tmp = TemporaryFolder()

   private fun logFile(name: String = LogFiles.NATIVE_LOG, body: String = "current"): File =
      File(tmp.root, name).apply { writeText(body) }

   @Test
   fun prevKeepsTheExtension() {
      assertEquals("nsz_debug.prev.log", LogFiles.prevOf(File(tmp.root, "nsz_debug.log")).name)
      assertEquals("nsz_screen_log.prev.txt", LogFiles.prevOf(File(tmp.root, "nsz_screen_log.txt")).name)
      assertEquals("noext.prev", LogFiles.prevOf(File(tmp.root, "noext")).name)
   }

   @Test
   fun rotateMovesCurrentToPrev() {
      val f = logFile(body = "run one")
      LogFiles.rotate(f)

      assertFalse("current must be gone so the new run starts empty", f.exists())
      assertEquals("run one", LogFiles.prevOf(f).readText())
   }

   @Test
   fun secondRotateDropsTheOlderGeneration() {
      val f = logFile(body = "run one")
      LogFiles.rotate(f)          // run one -> prev
      f.writeText("run two")
      LogFiles.rotate(f)          // run two -> prev, run one dropped

      assertEquals("only the immediately previous run is kept", "run two", LogFiles.prevOf(f).readText())
      // Exactly two generations at most: current (absent here) + prev.
      assertEquals(1, tmp.root.listFiles()!!.size)
   }

   @Test
   fun rotateIsANoOpWhenNothingIsThere() {
      val f = File(tmp.root, LogFiles.NATIVE_LOG)
      LogFiles.rotate(f)   // must not throw

      assertFalse(f.exists())
      assertFalse(LogFiles.prevOf(f).exists())
   }

   @Test
   fun bannerCarriesRunNumberAndTheRules() {
      val banner = LogFiles.banner(runId = 42, kind = "Native engine log", mode = "queue (4 files)")

      assertTrue(banner.contains("Run      : #42"))
      assertTrue(banner.contains("Native engine log"))
      assertTrue(banner.contains("queue (4 files)"))
      // The rules block must name every sink, so one log explains the whole scheme.
      assertTrue(banner.contains(LogFiles.NATIVE_LOG))
      assertTrue(banner.contains(LogFiles.FOLDER_LOG))
      assertTrue(banner.contains(LogFiles.SCREEN_LOG))
      assertTrue(banner.contains("logcat"))
      assertTrue(banner.contains(".prev."))
   }
}
