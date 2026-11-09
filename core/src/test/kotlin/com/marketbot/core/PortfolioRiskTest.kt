package com.marketbot.core

import com.marketbot.core.store.QuoteRow
import com.marketbot.core.strategy.CapitalEfficiency
import com.marketbot.core.strategy.CorrelatedExposure
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.TaxYear
import com.marketbot.core.strategy.VariantVerdict
import com.marketbot.core.strategy.WatchItem
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** City/day exposure, variant verdicts, capital efficiency, the tax-year export and the watchlist. */
class PortfolioRiskTest {
    private val t0 = Instant.parse("2026-11-09T12:00:00Z")

    @Test fun `the same city and day is one key on both venues`() {
        assertEquals("NYC 2026-11-09", CorrelatedExposure.key("KXHIGHNY-26NOV09-B70.5"))
        assertEquals("NYC 2026-11-09", CorrelatedExposure.key("KXLOWTNYC-26NOV09-T45"))
        assertEquals("NYC 2026-11-09", CorrelatedExposure.key("tc-temp-nychigh-2026-11-09-gte69lt70f"))
        assertEquals("CHI 2026-11-09", CorrelatedExposure.key("tc-temp-mdwlow-2026-11-09-lt50f"))
        assertNull(CorrelatedExposure.key("KXBTCD-26NOV0917-T90000"))
        val rows = listOf(
            row("KXHIGHNY-26NOV09-B70.5", "sell_yes", 0.08, 5.0, null),               // resting: 5 x 0.92 can be lost
            row("tc-temp-nychigh-2026-11-09-gte69lt70f", "buy_yes", 0.90, 5.0, 5.0),   // held: 5 x 0.90
            row("KXHIGHNY-26NOV09-B72.5", "sell_yes", 0.05, 5.0, 0.0, cancelled = true))  // withdrawn, nothing filled
        assertEquals(9.1, CorrelatedExposure.byKey(rows).getValue("NYC 2026-11-09"), 1e-9)
    }

    private fun row(t: String, action: String, p: Double, n: Double, filled: Double?, cancelled: Boolean = false, pnl: Double? = null,
                    placed: Instant = t0, resolved: Instant? = null, mode: Mode = Mode.LIVE, id: Long = 0) =
        QuoteRow(id = id, strategyId = "s", mode = mode, venue = if (t.startsWith("KX")) "kalshi" else "polymarket", ticker = t, action = action, price = p,
            contracts = n, placedAt = placed, filledContracts = filled, cancelledAt = if (cancelled) t0 else null, resolvedAt = resolved,
            result = if (pnl == null) null else if (pnl < 0) "yes" else "no", pnl = pnl, hypothetical = if (pnl == null) null else false)

    private fun settled(n: Int, lostEvery: Int, p: Double, start: Instant = t0) = (0 until n).map { i ->
        val lost = i % lostEvery == 0
        row("KXHIGHNY-26NOV09-B$i", "sell_yes", p, 5.0, 5.0, pnl = if (lost) -(1 - p) * 5 else p * 5, placed = start.plusSeconds(i * 60L),
            resolved = start.plusSeconds(86_400L + i * 60), mode = Mode.PAPER)
    }

    @Test fun `a variant wins only on a real difference, over the markets both ran for`() {
        val main = settled(200, 10, 0.08)            // 10% losers on 8c: -1.2c a contract
        val better = settled(200, 50, 0.08)          // 2% losers: +6.2c
        assertTrue(VariantVerdict.compare(main, better, "Main", "Variant B").let { it.decided && it.better == "Variant B" })
        assertTrue(VariantVerdict.compare(main, main, "Main", "Variant B").let { !it.decided || it.better == null })
        val young = VariantVerdict.compare(main, settled(10, 50, 0.08), "Main", "Variant B")
        assertTrue("Keep running" in young.text, young.text)
        // the main strategy's months before the variant started are not counted
        val late = settled(40, 50, 0.08, start = t0.plusSeconds(400 * 60L))
        assertTrue("Keep running" in VariantVerdict.compare(main, late, "Main", "Variant B").text)
    }

    @Test fun `capital efficiency, the tax year and watch alerts`() {
        val l = CapitalEfficiency.of("s", settled(10, 100, 0.08))!!     // 9 wins of 0.40 and one loss of 4.60; 10 x 5 x 0.92 locked for a day
        assertEquals(-1.0, l.pnl, 1e-9)
        assertEquals(-100.0 / 46, l.centsPerDollarDay!!, 1e-6)
        val live = listOf(row("KXHIGHNY-26NOV09-B1", "sell_yes", 0.08, 5.0, 5.0, pnl = 0.4, resolved = Instant.parse("2026-01-02T00:00:00Z")),
            row("KXHIGHNY-25DEC30-B1", "sell_yes", 0.08, 5.0, 5.0, pnl = -4.6, resolved = Instant.parse("2025-12-31T00:00:00Z")))
        val csv = TaxYear.csv(live, 2026, ZoneOffset.UTC)
        assertTrue("kalshi: 1 settled positions, total \$0.40" in csv, csv)
        assertEquals(1, TaxYear.rows(live, 2025, ZoneOffset.UTC).size)
        val w = WatchItem("kalshi", "T", 0.12, 5, "NYC high")
        assertNull(w.alert(0.15)); assertEquals("NYC high moved from 12c to 17c", w.alert(0.17))
    }
}
