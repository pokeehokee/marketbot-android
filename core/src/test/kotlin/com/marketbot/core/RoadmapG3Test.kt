package com.marketbot.core

import com.marketbot.core.net.ApiJournal
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.store.EquitySnapshot
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.store.inMemoryStores
import com.marketbot.core.strategies.longshot.WeatherLongshot
import com.marketbot.core.strategies.rewards.QuoteRefresh
import com.marketbot.core.strategy.CashFlow
import com.marketbot.core.strategy.CashFlows
import com.marketbot.core.strategy.Lock
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.StrategyRegistry
import com.marketbot.core.strategy.VariantRecord
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deposits, paper variants, farm quote refresh, the API journal and the pause lock. */
class RoadmapG3Test {
    private val t0 = Instant.parse("2026-10-20T12:00:00Z")
    private fun snap(h: Long, v: Double, venue: String = "kalshi") =
        EquitySnapshot(t0.plusSeconds(h * 3600), venue, Mode.LIVE, Sourced.kalshi(v, "balance"), null, Sourced.kalshi(v, "balance+portfolio_value"))
    private fun settled(h: Long, pnl: Double, mode: Mode = Mode.LIVE, id: String = "s", contracts: Double = 5.0) =
        QuoteRow(strategyId = id, mode = mode, venue = "kalshi", ticker = "T$h$pnl", action = "sell_yes", price = 0.07, contracts = contracts,
            placedAt = t0, filledContracts = contracts, resolvedAt = t0.plusSeconds(h * 3600), result = "no", pnl = pnl, hypothetical = false)

    @Test fun `performance takes out every flow at or before each point, and nothing before the series starts`() {
        val series = listOf(0L to 400.0, 10L to 610.0, 20L to 620.0).map { (h, v) -> t0.plusSeconds(h * 3600).toEpochMilli() to v }
        val flows = listOf(CashFlow("kalshi", t0.plusSeconds(5 * 3600).toString(), 200.0), CashFlow("kalshi", t0.minusSeconds(3600).toString(), 50.0))
        assertEquals(listOf(400.0, 410.0, 420.0), CashFlows.performance(series, flows).map { it.second })
        assertEquals(series, CashFlows.performance(series, emptyList()))
    }

    @Test fun `a jump settled P&L does not explain is suggested once, and not again once recorded or dismissed`() {
        val snaps = listOf(snap(0, 400.0), snap(1, 402.0), snap(2, 602.0), snap(3, 597.5))
        val rows = listOf(settled(1, 2.0) /* between 0 and 1: explains it */, settled(3, -4.5))
        val c = CashFlows.candidates(snaps, rows, emptyList(), emptySet())
        assertEquals(1, c.size, c.toString()); assertEquals(200.0, c.single().amount, 1e-9)
        assertEquals(snap(2, 0.0).at.toString(), c.single().at)
        assertTrue(CashFlows.candidates(snaps, rows, listOf(c.single().copy(at = t0.plusSeconds(7200 + 600).toString())), emptySet()).isEmpty(), "already recorded")
        assertTrue(CashFlows.candidates(snaps, rows, emptyList(), setOf(c.single().at)).isEmpty(), "dismissed")
        // A small move under both thresholds is never a suggestion.
        assertTrue(CashFlows.candidates(listOf(snap(0, 400.0), snap(1, 404.0)), emptyList(), emptyList(), emptySet()).isEmpty())
    }

    @Test fun `cash flows round-trip through the state store, sorted`() = runBlocking {
        val st = inMemoryStores().state
        CashFlows.save(st, listOf(CashFlow("kalshi", t0.plusSeconds(60).toString(), -20.0), CashFlow("polymarket", t0.toString(), 100.0, "hand")))
        assertEquals(listOf(100.0, -20.0), CashFlows.load(st).map { it.amount })
        Unit
    }

    @Test fun `every strategy has two paper-only variants with their own ids and names`() {
        val bases = StrategyRegistry.all.filter { it.baseId == null }
        assertEquals(4, bases.size)
        assertEquals(StrategyRegistry.all.size, StrategyRegistry.all.map { it.id }.toSet().size, "ids are unique")
        for (b in bases) {
            val v = StrategyRegistry.variantsOf(b.id)
            assertEquals(listOf("${b.id}@b", "${b.id}@c"), v.map { it.id })
            assertTrue(v.all { Mode.LIVE !in it.supportedModes && Mode.PAPER in it.supportedModes }, "variants never trade live")
            assertTrue(v.all { it.name.startsWith(b.name) && "variant" in it.name })
            assertTrue(v.all { it.params.map { p -> p.key } == b.params.map { p -> p.key } }, "same settings to vary")
        }
        assertTrue(Mode.LIVE in StrategyRegistry.byId(WeatherLongshot.ID)!!.supportedModes)
    }

    @Test fun `the variant comparison counts settled paper positions only`() {
        val rows = listOf(settled(1, 0.35, Mode.PAPER), settled(2, -4.65, Mode.PAPER), settled(3, 0.35, Mode.LIVE),
            settled(4, 0.35, Mode.PAPER).copy(hypothetical = true))
        val r = VariantRecord.of(rows)
        assertEquals(2, r.n); assertEquals(-4.30, r.pnl, 1e-9); assertEquals(-0.43, r.perContract!!, 1e-9)
        assertNull(VariantRecord.of(emptyList()).perContract)
    }

    @Test fun `a farm entry within the tolerance keeps its place, an exit always tracks, and old quotes are replaced`() {
        val tick = 0.01
        assertTrue(QuoteRefresh.near(0.40, 0.40, tick, entry = true, toleranceTicks = 0))
        assertFalse(QuoteRefresh.near(0.40, 0.41, tick, entry = true, toleranceTicks = 0), "0 is pmfarm: any move re-quotes")
        assertTrue(QuoteRefresh.near(0.40, 0.42, tick, entry = true, toleranceTicks = 2))
        assertFalse(QuoteRefresh.near(0.40, 0.43, tick, entry = true, toleranceTicks = 2))
        assertFalse(QuoteRefresh.near(0.40, 0.41, tick, entry = false, toleranceTicks = 5), "exits are exact")
        assertFalse(QuoteRefresh.tooOld(t0, t0.plusSeconds(3600), 0.0), "0 = never")
        assertFalse(QuoteRefresh.tooOld(t0, t0.plusSeconds(29 * 60), 30.0))
        assertTrue(QuoteRefresh.tooOld(t0, t0.plusSeconds(30 * 60), 30.0))
        assertFalse(QuoteRefresh.tooOld(null, t0, 30.0))
        assertTrue(QuoteRefresh.pausedAfterFill(t0, t0.plusSeconds(59), 60))
        assertFalse(QuoteRefresh.pausedAfterFill(t0, t0.plusSeconds(60), 60))
        assertFalse(QuoteRefresh.pausedAfterFill(t0, t0, 0))
    }

    @Test fun `the API journal records only while on, keeps a bounded ring and never keeps headers`() {
        ApiJournal.stop()
        fun e(i: Int) = ApiJournal.Entry(t0, "GET", "https://x/$i", 200, 5, null, "{\"n\":$i}", null)
        ApiJournal.record(e(0))
        ApiJournal.start(15)
        assertEquals(0, ApiJournal.size(), "starting clears and nothing was recorded while off")
        repeat(ApiJournal.MAX + 5) { ApiJournal.record(e(it)) }
        assertEquals(ApiJournal.MAX, ApiJournal.size())
        val lines = ApiJournal.export().trim().lines()
        assertEquals(ApiJournal.MAX, lines.size)
        assertTrue("https://x/5\"" in lines.first(), lines.first())
        assertTrue(lines.none { "Authorization" in it || "KALSHI-ACCESS" in it })
        ApiJournal.stop()
    }

    @Test fun `the pause lock covers every strategy and variant in both modes until it lapses`() {
        val l = Lock.pauseAll(t0, 4)
        assertTrue(StrategyRegistry.all.all { s -> Mode.entries.filter { it != Mode.OFF }.all { l.covers(s.id, it, "KXHIGHNY-26OCT30-B70.5") } })
        assertEquals(t0.plusSeconds(4 * 3600), l.untilInstant)
    }
}
