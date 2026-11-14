package com.marketbot.core

import com.marketbot.core.store.QuoteRow
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.Narration
import com.marketbot.core.strategy.Narration.Tone
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sentences on the board are written from the ledger. These freeze the two promises they
 * make: none outruns its data, and real money is never described with practice money mixed in.
 */
class NarrationTest {
    private val now: Instant = Instant.parse("2026-11-14T20:00:00Z")
    private val utc = ZoneOffset.UTC

    /** A settled deep-ITM-style bet: buy YES at [price], [contracts] of them, won unless [lost]. */
    private fun bet(lost: Boolean, at: Instant = now.minusSeconds(3600), price: Double = 0.94, contracts: Double = 5.0,
                    mode: Mode = Mode.LIVE, hypothetical: Boolean = false) = QuoteRow(
        strategyId = "s", mode = mode, venue = "kalshi", ticker = "KXBTCD-X", action = "buy_yes", price = price,
        contracts = contracts, placedAt = at.minusSeconds(600), resolvedAt = at, filledContracts = contracts,
        result = if (lost) "no" else "yes", pnl = if (lost) -price * contracts else (1 - price) * contracts, hypothetical = hypothetical)

    private fun book(wins: Int, losses: Int, at: Instant = now.minusSeconds(3600)) =
        List(wins) { bet(false, at) } + List(losses) { bet(true, at) }

    @Test fun `a verdict against the odds is muted until thirty losses, and green only after`() {
        // 150 wins, 6 losses at 0.94: the prices implied 156 x 0.06 = 9.4 losses.
        val early = Narration.odds(book(150, 6))
        assertEquals(6, early.losses)
        assertEquals(9.36, early.implied, 1e-9)
        val w = Narration.vsOddsWord(early)
        assertEquals("beating odds", w.text)
        assertEquals(Tone.NEUTRAL, w.tone, "six losses cannot earn green")
        assertTrue("Too few losses to be sure yet." in Narration.vsOddsSentence(early))

        val earned = Narration.odds(book(1000, 40))
        assertEquals(Tone.GOOD, Narration.vsOddsWord(earned).tone)
        assertFalse("Too few" in Narration.vsOddsSentence(earned))
    }

    @Test fun `nothing is judged when the prices imply almost no losses`() {
        val o = Narration.odds(book(20, 0))          // implied 1.2
        assertEquals("too early", Narration.vsOddsWord(o).text)
        assertEquals(Tone.QUIET, Narration.vsOddsWord(o).tone)
        assertEquals("no bets yet", Narration.vsOddsWord(Narration.odds(emptyList())).text)
    }

    @Test fun `losing more often than the odds is a warning before it is a verdict`() {
        val o = Narration.odds(book(30, 8))           // 8 losses against 2.28 implied
        assertEquals("behind odds", Narration.vsOddsWord(o).text)
        assertEquals(Tone.WARN, Narration.vsOddsWord(o).tone)
    }

    @Test fun `hypothetical rows are never counted`() {
        val rows = book(10, 0) + List(5) { bet(true, hypothetical = true) }
        assertEquals(0, Narration.odds(rows).losses)
    }

    @Test fun `the brake line counts in losses the person has already seen today`() {
        val dayStart = now.atZone(utc).toLocalDate().atStartOfDay(utc).toInstant()
        // One loss of 4.70 and three wins of 0.30 today: net -3.80 against a 10.00 limit.
        val rows = listOf(bet(true)) + List(3) { bet(false) }
        val b = Narration.brake("BTC ladder", rows, 10.0, dayStart)
        assertEquals(3.80, b.lostToday, 1e-9)
        assertEquals(1, b.lossesToday)
        // 6.20 of room at 4.70 a loss: the second loss reaches it.
        assertEquals("Two more losses like today's and it stops itself.", Narration.brakeLine(b))

        val close = b.copy(lostToday = 6.0)
        assertEquals("One more loss like today's would reach it.", Narration.brakeLine(close))
        assertEquals("Limit reached. No new bets until midnight.", Narration.brakeLine(b.copy(lostToday = 10.0)))
        assertEquals("No losses today. It stops itself at $10.00.", Narration.brakeLine(b.copy(lostToday = 0.0, lossesToday = 0)))
    }

    @Test fun `yesterday's losses do not count against today's brake`() {
        val dayStart = now.atZone(utc).toLocalDate().atStartOfDay(utc).toInstant()
        val b = Narration.brake("x", listOf(bet(true, at = dayStart.minusSeconds(60))), 10.0, dayStart)
        assertEquals(0.0, b.lostToday)
        assertEquals(0, b.lossesToday)
    }

    @Test fun `the headline names real money, and says so when there is none`() {
        val b = Narration.Brake("BTC ladder", 3.85, 10.0, 1, 4.70)
        assertEquals("Up $4.30 this week with real money. One loss today, still well inside the daily limit.",
            Narration.headline(4.30, 7.50, b))
        assertEquals("Down $2.00 this week with real money. No losses today.",
            Narration.headline(-2.0, 0.0, b.copy(lostToday = 0.0, lossesToday = 0)))
        // No live book: practice is named as practice, never presented as the account's result.
        assertEquals("No real money is trading. Practice strategies are up $7.50 this week.", Narration.headline(null, 7.50, null))
    }

    @Test fun `the week line names the worst day`() {
        // 2026-11-14 is a Saturday; a loss on Thursday the 12th, wins on three other days.
        val rows = listOf(bet(true, at = Instant.parse("2026-11-12T15:00:00Z"))) +
            listOf("2026-11-09", "2026-11-10", "2026-11-14").map { bet(false, at = Instant.parse("${it}T15:00:00Z")) }
        assertEquals("Up 3 of 4 days; the worst was Thursday (-$4.70).", Narration.weekLine(rows, now, utc))
        assertEquals("Nothing settled in the last 7 days.", Narration.weekLine(emptyList(), now, utc))
    }

    @Test fun `a red day made of one loss is described as one`() {
        val rows = listOf(bet(true, at = Instant.parse("2026-11-12T15:00:00Z")), bet(false, at = Instant.parse("2026-11-12T16:00:00Z")))
        assertEquals("The one red day was a single loss: many small wins, the odd bigger loss.", Narration.redDaysLine(rows, now, utc))
        assertEquals("No losing days in two weeks.", Narration.redDaysLine(book(5, 0), now, utc))
    }

    @Test fun `the loss cost line is the ratio of an average loss to an average win`() {
        // a loss is 4.70, a win 0.30: about 16 wins
        assertEquals("A loss costs about what 16 wins make.", Narration.lossCostLine(book(10, 1)))
        assertNull(Narration.lossCostLine(book(10, 0)), "no loss yet, nothing to compare")
    }

    @Test fun `the bottom line flags a shrinking edge only on enough recent bets`() {
        // Old bets at a fat edge, recent ones at a thin one.
        val old = List(40) { bet(false, at = now.minusSeconds(20L * 86_400), price = 0.80) }
        val recent = List(12) { bet(false, at = now.minusSeconds(3600), price = 0.99) }
        val l = Narration.bottomLine(Mode.LIVE, old + recent, now)
        assertTrue(l.text.startsWith("Trading real money"), l.text)
        assertTrue("earned less lately" in l.text, l.text)
        assertEquals(Tone.WARN, l.tone)

        val few = Narration.bottomLine(Mode.LIVE, old + recent.take(3), now)
        assertFalse("earned less lately" in few.text, "three recent bets are not a trend")
    }

    @Test fun `a strategy with no settled bets says so in its own mode's words`() {
        assertEquals("Practising; no bets have finished yet.", Narration.bottomLine(Mode.PAPER, emptyList(), now).text)
        assertEquals("Off, with no record yet.", Narration.bottomLine(Mode.OFF, emptyList(), now).text)
    }
}
