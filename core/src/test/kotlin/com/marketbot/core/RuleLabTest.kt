package com.marketbot.core

import com.marketbot.core.btc.BtcIndex
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.store.inMemoryStores
import com.marketbot.core.strategies.longshot.RangeCandidate
import com.marketbot.core.strategies.longshot.RuleLab
import com.marketbot.core.strategy.Attribution
import com.marketbot.core.strategy.FillLog
import com.marketbot.core.strategy.Markout
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.strategy.StrategyRegistry
import com.marketbot.core.util.MutableClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rule Lab (a rule defined by settings), the fill log and the money breakdown. */
class RuleLabTest {
    private val lab = RuleLab()
    private val t0 = Instant.parse("2026-11-09T12:00:00Z")
    private fun cfg(vararg kv: Pair<String, String>) = StrategyConfig(lab.params, kv.toMap())
    private fun m(bid: Double, ask: Double, strike: String? = "between") = RangeCandidate("T", bid, ask, t0, t0, null, null, null, strike)

    @Test fun `the four combinations trade the cheap side at the right price`() {
        val yesCheap = m(0.07, 0.08)
        fun p(c: StrategyConfig, x: RangeCandidate = yesCheap) = RuleLab.pickFor(x, c)?.let { Triple(it.action, Math.round(it.price * 100), it.taker) }
        assertEquals(Triple("sell_yes", 8L, false), p(cfg()))
        assertEquals(Triple("buy_no", 93L, true), p(cfg("execution" to "taker")), "sell the cheap YES now = buy NO at 1 - bid")
        assertEquals(Triple("buy_yes", 7L, false), p(cfg("direction" to "buy_cheap")))
        assertEquals(Triple("buy_yes", 8L, true), p(cfg("direction" to "buy_cheap", "execution" to "taker")))
        val noCheap = m(0.92, 0.93)
        assertEquals(Triple("buy_yes", 92L, false), p(cfg(), noCheap), "NO cheap: rest a YES bid")
        assertEquals(Triple("buy_no", 7L, false), p(cfg("direction" to "buy_cheap"), noCheap))
        assertNull(p(cfg("cheap_side" to "yes"), noCheap), "only cheap YES allowed")
        assertNull(p(cfg("strikes" to "tails")), "a range bucket is not a tail")
        assertEquals("sell_yes", RuleLab.pickFor(m(0.07, 0.08, "greater"), cfg("strikes" to "tails"))?.action)
    }

    @Test fun `bad settings are refused, offers are withdrawn after the rest limit, and it is paper only with two variants`() {
        assertTrue(lab.validate(cfg("direction" to "sideways")).any { "Direction" in it })
        assertTrue(lab.validate(cfg("series" to " ")).any { "series" in it })
        assertTrue(lab.validate(cfg()).isEmpty(), lab.validate(cfg()).toString())
        val q = QuoteRow(strategyId = lab.id, mode = Mode.PAPER, venue = "kalshi", ticker = "T", action = "sell_yes", price = 0.08, contracts = 5.0,
            placedAt = t0, closeTime = t0.plusSeconds(12 * 3600))
        assertEquals(t0.plusSeconds(12 * 3600), lab.withdrawAt(q, cfg()))
        assertEquals(t0.plusSeconds(90 * 60), lab.withdrawAt(q, cfg("rest_max_min" to "90")))
        assertFalse(Mode.LIVE in lab.supportedModes)
        assertEquals(2, StrategyRegistry.variantsOf(RuleLab.ID).size)
        assertTrue("Sell the cheap side priced 2-25c" in lab.rule(cfg()), lab.rule(cfg()))
    }

    @Test fun `a taker rule fills at once and pays the taker fee at settlement`() = runBlocking {
        FakeKalshi().use { fake ->
            val stores = inMemoryStores(); val clock = MutableClock(t0)
            val close = t0.plusSeconds(12 * 3600).toString()
            fake.markets["KXGOLDD"] = listOf(FakeKalshi.market("KXGOLDD-26NOV09H2400-T4000", 0.07, 0.08, close))
            val c = cfg("series" to "KXGOLDD", "execution" to "taker")
            val ctx = StrategyContext(lab.id, Mode.PAPER, c, stores, clock, fake.client(false), PolymarketClient(null),
                BtcIndex(venues = emptyMap(), candles = { emptyList() }), liveArmed = false)
            lab.runOnce(ctx)
            val q = stores.ledger.open(lab.id, Mode.PAPER).single()
            assertEquals("buy_no" to 5.0, q.action to q.filledContracts)
            assertEquals(0.0, FillLog.minutesToFill(q))
        }
    }

    private fun row(i: Int, lost: Boolean, filled: Boolean, leadH: Long, waitMin: Long?, series: String = "KXHIGHNY") = QuoteRow(
        id = i.toLong(), strategyId = "s", mode = Mode.PAPER, venue = "kalshi", ticker = "$series-26NOV09-B$i", action = "sell_yes", price = 0.08, contracts = 5.0,
        placedAt = t0, closeTime = t0.plusSeconds(leadH * 3600), filledContracts = if (filled) 5.0 else 0.0,
        resolvedAt = t0.plusSeconds(leadH * 3600 + 3600), result = if (lost) "yes" else "no",
        pnl = if (!filled) 0.4 else if (lost) -4.6 else 0.4, hypothetical = !filled,
        extra = JsonObject(buildMap {
            if (waitMin != null) put(Markout.SEEN, JsonPrimitive(t0.plusSeconds(waitMin * 60).toString()))
            if (filled) put(Markout.key(60), JsonPrimitive(if (lost) -6.0 else 1.0))
        }))

    @Test fun `the fill log splits fills by outcome and lead, and the breakdown ranks where money was lost`() {
        val rows = (0 until 10).map { row(it, lost = false, filled = it < 4, leadH = 20, waitMin = if (it < 4) 120 else null) } +
            (10 until 14).map { row(it, lost = true, filled = true, leadH = 6, waitMin = 10, series = "KXHIGHCHI") }
        val r = FillLog.report(rows)!!
        assertEquals(0.4, r.won.fillRate!!, 1e-9); assertEquals(1.0, r.lost.fillRate!!, 1e-9)
        assertEquals(2.5, r.penalty!!, 1e-9)
        assertEquals(120.0, r.won.medianMinutesToFill); assertEquals(10.0, r.lost.medianMinutesToFill)
        assertEquals(-6.0, r.lost.markout60!!, 1e-9)
        assertEquals(listOf("3-8h", "16-24h"), r.byLead.map { it.label })
        val g = Attribution.by(rows, Attribution.Dim.CITY, ZoneOffset.UTC)
        assertEquals("CHI", g.first().key, "the city that lost money comes first")
        assertEquals(4, g.first().losses)
        assertEquals(setOf("Mon", "Tue"), Attribution.by(rows, Attribution.Dim.WEEKDAY, ZoneOffset.UTC).map { it.key }.toSet(), "by the day each market closed")
    }
}
