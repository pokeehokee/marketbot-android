package com.marketbot.core

import com.marketbot.core.store.QuoteRow
import com.marketbot.core.strategy.Check
import com.marketbot.core.strategy.Expectation
import com.marketbot.core.strategy.Graduation
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.RecordHealth
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The record badge on every strategy card, and the gates a paper strategy passes before live money. */
class ScorecardTest {
    private val now = Instant.parse("2026-11-08T12:00:00Z")

    /** n settled sell-YES-at-p positions of 5 contracts, [lost] of them losing. */
    private fun book(n: Int, lost: Int, p: Double = 0.08, start: Instant = now.minus(Duration.ofDays(30)), mode: Mode = Mode.PAPER) = (0 until n).map { i ->
        val l = i < lost
        QuoteRow(i.toLong() + 1, "s", mode, "kalshi", "T$i", "sell_yes", p, 5.0, start.plusSeconds(i * 600L),
            filledContracts = 5.0, resolvedAt = start.plusSeconds(i * 600L + 86_400), result = if (l) "yes" else "no",
            pnl = if (l) -(1 - p) * 5 else p * 5, hypothetical = false)
    }

    @Test fun `the badge follows this app's record, not the evidence label`() {
        val exp = Expectation(0.0264, null, null, "test")
        assertNull(RecordHealth.of(Mode.PAPER, emptyList(), exp))
        assertEquals(RecordHealth.Verdict.TOO_EARLY, RecordHealth.of(Mode.PAPER, book(10, 0), exp)!!.verdict)
        // 8c premium, 4% losers: about +3.9c a contract, its interval reaching above the expected 2.64c
        assertEquals(RecordHealth.Verdict.ON_TRACK, RecordHealth.of(Mode.PAPER, book(200, 8), exp)!!.verdict)
        // 25% losers on an 8c premium: losing, whole interval below zero
        val bad = RecordHealth.of(Mode.PAPER, book(200, 50), exp)!!
        assertEquals(RecordHealth.Verdict.LOSING, bad.verdict)
        assertTrue(bad.line().startsWith("Losing in paper"), bad.line())
        // 9% losers: about -1c, interval straddling zero but below +2.64c: below its evidence
        assertEquals(RecordHealth.Verdict.BELOW, RecordHealth.of(Mode.PAPER, book(300, 27), exp)!!.verdict)
        // live rows are not judged as paper
        assertNull(RecordHealth.of(Mode.LIVE, book(200, 8), exp))
    }

    @Test fun `graduation needs four weeks, 100 fills, 10 losses, a ratio under 0_90 and a profit`() {
        val g = Graduation.gates(book(150, 10), now)
        assertTrue(Graduation.ready(g), g.toString())          // 10 lost vs 12 implied (0.83), +2.7c
        val young = Graduation.gates(book(150, 10, start = now.minus(Duration.ofDays(10))), now)
        assertEquals(Check.Verdict.INSUFFICIENT, young[0].verdict); assertFalse(Graduation.ready(young))
        val few = Graduation.gates(book(60, 2), now)
        assertEquals(listOf(Check.Verdict.PASS, Check.Verdict.INSUFFICIENT, Check.Verdict.INSUFFICIENT, Check.Verdict.INSUFFICIENT, Check.Verdict.INSUFFICIENT),
            few.map { it.verdict })
        val worse = Graduation.gates(book(150, 14), now)          // 14 vs 12 implied: ratio 1.17
        assertEquals(Check.Verdict.FAIL, worse[3].verdict); assertFalse(Graduation.ready(worse))
        assertEquals(3 to 5, Graduation.progress(worse), "14 losers on an 8c premium also lose money")
    }
}
