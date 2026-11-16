package com.marketbot.core

import com.marketbot.core.store.EquitySnapshot
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.provenance.Source
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.strategy.CashFlow
import java.time.Duration
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

    // ------------------------------------------------- the balance line on the notification

    private fun snap(venue: String, at: Instant, cash: Double, positions: Double? = null) =
        EquitySnapshot(at, venue, Mode.LIVE,
            Sourced(cash, Source.KALSHI_API, "balance_dollars"),
            positions?.let { Sourced(it, Source.KALSHI_API, "portfolio_value") },
            Sourced(cash + (positions ?: 0.0), Source.DERIVED, "cash + portfolio_value"))

    @Test fun `the balance is the exchange's own mark, and a day's change with it`() {
        val day = listOf(snap("kalshi", now.minusSeconds(86_400), 10.0, 28.0),
                         snap("kalshi", now.minusSeconds(60), 12.0, 27.2))
        val b = Narration.balance(day, emptyList(), now)
        assertEquals(39.2, b.equity!!, 1e-9, "cash + portfolio value, not cash alone")
        assertEquals(1.2, b.change!!, 1e-9)
        assertTrue(b.isFullWindow)
        assertEquals("$39.20 · up $1.20 in 24h", Narration.balanceLine(b, now, utc))
    }

    @Test fun `a withdrawal is not a loss`() {
        // Equity fell $200 because the operator took $200 out, while trading made $1.50.
        val rows = listOf(snap("kalshi", now.minusSeconds(86_400), 240.0),
                          snap("kalshi", now.minusSeconds(60), 41.5))
        val flow = CashFlow("kalshi", now.minusSeconds(40_000).toString(), -200.0, "withdrawal")
        val naive = Narration.balance(rows, emptyList(), now)
        assertEquals(-198.5, naive.change!!, 1e-9, "without the flow it reads as a catastrophe")
        val b = Narration.balance(rows, listOf(flow), now)
        assertEquals(1.5, b.change!!, 1e-9, "with it, performance is what trading did")
        assertEquals(-200.0, b.flowsRemoved, 1e-9)
        assertEquals("$41.50 · up $1.50 in 24h", Narration.balanceLine(b, now, utc))
    }

    @Test fun `a deposit is not a gain either`() {
        val rows = listOf(snap("kalshi", now.minusSeconds(86_400), 40.0),
                          snap("kalshi", now.minusSeconds(60), 239.0))
        val b = Narration.balance(rows, listOf(CashFlow("kalshi", now.minusSeconds(3_600).toString(), 200.0)), now)
        assertEquals(-1.0, b.change!!, 1e-9)
        assertEquals("$239.00 · down $1.00 in 24h", Narration.balanceLine(b, now, utc))
    }

    @Test fun `a span shorter than a day is reported as the span it really is`() {
        val rows = listOf(snap("kalshi", now.minusSeconds(6 * 3600), 30.0),
                          snap("kalshi", now.minusSeconds(60), 30.4))
        val b = Narration.balance(rows, emptyList(), now)
        assertFalse(b.isFullWindow, "six hours may not be called 24h")
        assertEquals("$30.40 · up $0.40 in 6h", Narration.balanceLine(b, now, utc))
    }

    @Test fun `one reading shows a balance and refuses to invent a change`() {
        val b = Narration.balance(listOf(snap("kalshi", now.minusSeconds(60), 39.4)), emptyList(), now)
        assertEquals(39.4, b.equity!!, 1e-9)
        assertNull(b.change, "a single reading cannot show a change")
        assertEquals("$39.40 · 24h change needs a day of history", Narration.balanceLine(b, now, utc))
    }

    @Test fun `no reading is not a zero balance`() {
        val b = Narration.balance(emptyList(), emptyList(), now)
        assertNull(b.equity)
        assertEquals("Balance not read yet", Narration.balanceLine(b, now, utc))
    }

    @Test fun `paper money never reaches the balance`() {
        val paper = EquitySnapshot(now.minusSeconds(60), "kalshi", Mode.PAPER,
            Sourced(400.0, Source.DERIVED, "paper wallet"), null, null)
        val b = Narration.balance(listOf(paper, snap("kalshi", now.minusSeconds(60), 39.4)), emptyList(), now)
        assertEquals(39.4, b.equity!!, 1e-9, "the $400 practice wallet must not be added in")
    }

    @Test fun `a stale reading says how old it is`() {
        val old = now.minusSeconds(3 * 3600)
        val rows = listOf(snap("kalshi", old.minusSeconds(86_400), 30.0), snap("kalshi", old, 31.0))
        val b = Narration.balance(rows, emptyList(), now)
        assertTrue(b.stale(now))
        assertTrue(Narration.balanceLine(b, now, utc).endsWith("(as of 17:00)"),
            Narration.balanceLine(b, now, utc))
        // A fresh reading carries no age.
        val fresh = Narration.balance(listOf(snap("kalshi", now.minusSeconds(86_400), 30.0),
                                             snap("kalshi", now.minusSeconds(120), 31.0)), emptyList(), now)
        assertFalse(fresh.stale(now))
        assertFalse("as of" in Narration.balanceLine(fresh, now, utc))
    }

    @Test fun `two venues are summed and each keeps its own flows`() {
        val rows = listOf(
            snap("kalshi", now.minusSeconds(86_400), 20.0), snap("kalshi", now.minusSeconds(60), 21.0),
            snap("polymarket", now.minusSeconds(86_400), 10.0), snap("polymarket", now.minusSeconds(60), 15.0))
        val b = Narration.balance(rows, listOf(CashFlow("polymarket", now.minusSeconds(3_600).toString(), 4.0)), now)
        assertEquals(36.0, b.equity!!, 1e-9)
        assertEquals(2.0, b.change!!, 1e-9, "+1 on kalshi, +5 less a $4 deposit on polymarket")
    }

    @Test fun `flat is said rather than dressed up as a move`() {
        val rows = listOf(snap("kalshi", now.minusSeconds(86_400), 39.4), snap("kalshi", now.minusSeconds(60), 39.4))
        assertEquals("$39.40 · flat over 24h", Narration.balanceLine(Narration.balance(rows, emptyList(), now), now, utc))
    }

    @Test fun `the anchor is the newest reading at or before the window opens`() {
        // Readings every 15 min for two days, rising by a cent each: the change must cover the
        // last 24h (96 quarter-hours = $0.96), not the whole 48h of history ($1.92).
        val rows = (0..192).map { snap("kalshi", now.minusSeconds(it * 900L), 30.0 + (192 - it) * 0.01) }
        val b = Narration.balance(rows, emptyList(), now)
        assertEquals(Duration.ofSeconds(96 * 900L), b.span, "96 quarter-hours is 24h")
        assertEquals(0.96, b.change!!, 1e-6, "the oldest reading is 48h old and must not be the anchor")
    }

    @Test fun `spans read the way a person says them`() {
        assertEquals("45 min", Narration.spanWords(Duration.ofMinutes(45)))
        assertEquals("6h", Narration.spanWords(Duration.ofHours(6)))
        assertEquals("3 days", Narration.spanWords(Duration.ofDays(3)))
    }
}
