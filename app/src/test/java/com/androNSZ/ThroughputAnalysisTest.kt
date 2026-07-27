package com.androNSZ

import com.androNSZ.util.BenchSummary
import com.androNSZ.util.BlockStat
import com.androNSZ.util.ThroughputAnalysis
import com.androNSZ.util.ThroughputSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules that turn per-level timings into a verdict. Each test names the wrong
 * answer it exists to prevent — these are not arithmetic checks, they are the
 * measurement biases documented in gotchas.md G18.
 */
class ThroughputAnalysisTest {

   @Test
   fun medianIgnoresAnOutlierThatAMeanWouldFollow() {
      // A writeback stall is a deep, short dip. The mean chases it down; the median
      // does not, which is the whole reason a median is used.
      val withStall = listOf(1000.0, 1010.0, 990.0, 5.0, 1000.0)
      assertEquals(1000.0, ThroughputAnalysis.medianOf(withStall)!!, 0.001)
      assertNull(ThroughputAnalysis.medianOf(emptyList()))
   }

   @Test
   fun medianAveragesTheTwoMiddlesOnEvenCounts() {
      assertEquals(150.0, ThroughputAnalysis.medianOf(listOf(100.0, 200.0))!!, 0.001)
   }

   @Test
   fun levelScoresSurviveFlashDriftAcrossRounds() {
      // The trap this whole design exists for. 6 threads are genuinely 10 % faster
      // than 4, but the flash slows by 30 % per round, so in absolute MB/s the
      // *second* round's 6-thread run is slower than the *first* round's 4-thread
      // one. Comparing raw numbers would crown whichever level ran first.
      val blocks = listOf(
         BlockStat(round = 1, level = 6, mbps = 1100.0),
         BlockStat(round = 1, level = 4, mbps = 1000.0),
         BlockStat(round = 2, level = 6, mbps = 770.0),
         BlockStat(round = 2, level = 4, mbps = 700.0),
         BlockStat(round = 3, level = 6, mbps = 539.0),
         BlockStat(round = 3, level = 4, mbps = 490.0)
      )
      val scores = ThroughputAnalysis.levelScores(blocks)

      assertEquals(1.0, scores.getValue(6), 0.001)
      assertEquals(1000.0 / 1100.0, scores.getValue(4), 0.001)
      assertEquals(6, ThroughputAnalysis.bestLevel(scores))
   }

   @Test
   fun aLevelWinsOnConsistencyNotOnWhenItWasMeasured() {
      // Same drift, but now the two levels are equal. Neither may be declared
      // better, and the tie must resolve upward (see bestLevel's reasoning).
      val blocks = listOf(
         BlockStat(1, 6, 1000.0),
         BlockStat(1, 5, 1000.0),
         BlockStat(2, 6, 600.0),
         BlockStat(2, 5, 600.0)
      )
      val scores = ThroughputAnalysis.levelScores(blocks)

      assertEquals(1.0, scores.getValue(6), 0.001)
      assertEquals(1.0, scores.getValue(5), 0.001)
      assertEquals(6, ThroughputAnalysis.bestLevel(scores))
   }

   @Test
   fun aFullSweepBothWaysScoresEveryLevelTwice() {
      // What the real driver produces: 1..3 ascending, then 3..1 descending, with
      // the flash 20 % slower by the second pass. Each level must be scored on both
      // of its runs, and the verdict must not depend on the direction.
      val ascending = listOf(1 to 400.0, 2 to 700.0, 3 to 1000.0)
      val blocks = ascending.map { (level, mbps) -> BlockStat(1, level, mbps) } +
         ascending.reversed().map { (level, mbps) -> BlockStat(2, level, mbps * 0.8) }
      val scores = ThroughputAnalysis.levelScores(blocks)

      assertEquals(3, scores.size)
      assertEquals(1.0, scores.getValue(3), 0.001)
      assertEquals(0.4, scores.getValue(1), 0.001)
      assertEquals(3, ThroughputAnalysis.bestLevel(scores))
   }

   @Test
   fun bestLevelKeepsTheLargestCountInsideTheNoiseBand() {
      // 7 threads measure 3 % below 5 — inside the 3–9 % spread of identical runs,
      // so it is noise, not a finding, and the larger count wins.
      val scores = mapOf(7 to 0.97, 6 to 0.98, 5 to 1.0)
      assertEquals(7, ThroughputAnalysis.bestLevel(scores))

      // 10 % below is outside the band and must be rejected.
      assertEquals(6, ThroughputAnalysis.bestLevel(mapOf(7 to 0.90, 6 to 1.0, 5 to 0.99)))
   }

   @Test
   fun bestLevelOfNothingIsNothing() {
      assertNull(ThroughputAnalysis.bestLevel(emptyMap()))
      assertNull(ThroughputAnalysis.bestLevel(ThroughputAnalysis.levelScores(emptyList())))
   }

   @Test
   fun csvRoundTripsAndIgnoresCommentLines() {
      val sample = ThroughputSample(1234, 5_678_900, 4, 3, 9, "")
      val line = ThroughputAnalysis.toCsv(sample)

      assertEquals(sample, ThroughputAnalysis.parseCsv(line))
      assertNull(ThroughputAnalysis.parseCsv("# AndroNSZ: Throughput telemetry"))
      assertNull(ThroughputAnalysis.parseCsv(ThroughputAnalysis.CSV_HEADER))
      assertNull(ThroughputAnalysis.parseCsv(""))
      assertNull(ThroughputAnalysis.parseCsv("garbage,,,,,"))
   }

   @Test
   fun benchSummaryRoundTripsThroughItsStoredForm() {
      val summary = BenchSummary(
         knee = 4,
         levelMBps = mapOf(6 to 1032.5, 4 to 1202.0, 1 to 818.25),
         scores = mapOf(6 to 0.95, 4 to 1.0, 1 to 0.67),
         atMillis = 1_785_000_000_000L
      )

      assertEquals(summary, BenchSummary.decode(summary.encode()))
   }

   @Test
   fun benchSummaryKeepsLevelsThatHaveNoScore() {
      // levelScores only scores levels that shared a round with another level, so a
      // measured-but-unscored level has to survive the round trip as speed-only.
      val summary = BenchSummary(
         knee = 2,
         levelMBps = mapOf(2 to 1403.0, 1 to 878.0),
         scores = mapOf(2 to 1.0),
         atMillis = 1L
      )

      val back = BenchSummary.decode(summary.encode())
      assertEquals(summary, back)
      assertNull(back?.scores?.get(1))
      assertEquals(878.0, back?.levelMBps?.get(1))
   }

   @Test
   fun benchSummaryDecodeRejectsJunk() {
      assertNull(BenchSummary.decode(""))
      assertNull(BenchSummary.decode("4"))
      assertNull(BenchSummary.decode("notanint;1;"))
      assertNull(BenchSummary.decode("4;1;6:oops"))
   }
}
