package com.marketbot.core

import com.marketbot.core.btc.BtcIndex
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.provenance.Source
import com.marketbot.core.store.inMemoryStores
import com.marketbot.core.strategies.longshot.WeatherLongshot
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.util.MutableClock
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A longshot's whole life over several live passes: quote, partial fill, idle recycle, settle, brake. */
class LifecycleTest {
    private val fake = FakeKalshi()
    @AfterTest fun close() = fake.close()

    @Test fun `quote, partial fill, recycle, settle from the payload, then the brake`() = runTest {
        val close = "2026-10-29T05:00:00Z"
        val ny = "KXHIGHNY-26OCT28-B70.5"; val dal = "KXHIGHTDAL-26OCT28-B90.5"
        fake.markets["KXHIGHNY"] = listOf(FakeKalshi.market(ny, 0.06, 0.07, close))
        fake.markets["KXHIGHTDAL"] = listOf(FakeKalshi.market(dal, 0.16, 0.18, close))
        val stores = inMemoryStores()
        val clock = MutableClock(Instant.parse("2026-10-27T20:00:00Z"))
        val s = WeatherLongshot()
        fun ctx() = StrategyContext(s.id, Mode.LIVE, StrategyConfig(s.params, mapOf("brake_time_zone" to "UTC")),
            stores, clock, fake.client(true, clock), PolymarketClient(null), BtcIndex(emptyMap(), { emptyList() }), liveArmed = true)

        // ---- pass 1: both markets quoted, post-only, expiring at the exchange
        val r1 = s.runOnce(ctx())
        val rows1 = stores.ledger.all(s.id, Mode.LIVE)
        assertEquals(setOf(ny, dal), rows1.map { it.ticker }.toSet(), "${r1.summary}; " + stores.events.recent(20, s.id).joinToString { it.message })
        assertTrue(fake.bodies.all { it["post_only"].toString() == "true" && it["expiration_time"] != null })
        val nyRow = rows1.first { it.ticker == ny }; val dalRow = rows1.first { it.ticker == dal }

        // ---- 9 h later: 2 of NY's 5 filled; both are idle, so both are recycled after the fill is read
        fake.orders[nyRow.orderId!!] = """{"order":{"order_id":"${nyRow.orderId}","status":"resting","fill_count_fp":"2.00","remaining_count_fp":"3.00"}}"""
        clock.advanceSeconds(9 * 3600)
        s.runOnce(ctx())
        val nyAfter = stores.ledger.byId(nyRow.id)!!; val dalAfter = stores.ledger.byId(dalRow.id)!!
        assertNotNull(nyAfter.cancelledAt); assertEquals(2.0, nyAfter.filledContracts)
        assertNotNull(dalAfter.cancelledAt); assertEquals(0.0, dalAfter.filledContracts)
        assertTrue(fake.deletes().map { it.requestUrl!!.queryParameter("market_ticker") }.containsAll(listOf(ny, dal)))
        val open = stores.ledger.open(s.id, Mode.LIVE).filter { it.resting }
        assertEquals(open.map { it.ticker }.toSet().size, open.size, "never two resting quotes on one market")

        // ---- after the close: NY settles YES (a loss on the 2 filled), DAL NO with nothing filled
        clock.at = Instant.parse("2026-10-29T07:00:00Z")
        fake.marketByTicker[ny] = FakeKalshi.market(ny, 0.0, 0.01, close, status = "finalized", result = "yes")
        fake.marketByTicker[dal] = FakeKalshi.market(dal, 0.0, 0.01, close, status = "finalized", result = "no")
        for ((id, _) in fake.orders.toMap()) if (id != nyRow.orderId)
            fake.orders[id] = """{"order":{"order_id":"$id","status":"canceled","fill_count_fp":"0.00"}}"""
        fake.settlements = """{"settlements":[{"ticker":"$ny","market_result":"yes","revenue":0,"no_total_cost_dollars":"1.86",
            "yes_total_cost_dollars":"0","fee_cost":"0.000000","no_count_fp":"2.00","yes_count_fp":"0.00"}],"cursor":""}"""
        fake.markets.clear()
        s.runOnce(ctx())
        val nySettled = stores.ledger.byId(nyRow.id)!!
        assertEquals(-1.86, nySettled.pnl!!, 1e-9)
        assertEquals(Source.KALSHI_API, nySettled.pnlSource)
        assertEquals(false, nySettled.hypothetical)
        val dalSettled = stores.ledger.byId(dalRow.id)!!
        assertEquals(true, dalSettled.hypothetical, "never filled: graded, but never realised")

        assertEquals("ok", s.runOnce(ctx()).summary.let { if (it == "brake") "brake too early" else "ok" }, "-1.86 alone is inside the $10 brake")

        // ---- an earlier settled loss the same day takes realised to -10.36: the brake holds
        stores.ledger.insert(com.marketbot.core.store.QuoteRow(strategyId = s.id, mode = Mode.LIVE, venue = "kalshi", ticker = "KXHIGHCHI-26OCT28-B60.5",
            action = "sell_yes", price = 0.10, contracts = 10.0, placedAt = clock.now().minusSeconds(100_000), filledContracts = 10.0,
            resolvedAt = clock.now().minusSeconds(600), result = "yes", pnl = -8.50, pnlSource = Source.KALSHI_API, hypothetical = false))
        fake.markets["KXHIGHNY"] = listOf(FakeKalshi.market("KXHIGHNY-26OCT30-B70.5", 0.06, 0.07, "2026-10-31T05:00:00Z"))
        val before = fake.bodies.size
        val r = s.runOnce(ctx())
        assertEquals("brake", r.summary)
        assertEquals(before, fake.bodies.size, "no order after the brake")
        assertEquals(listOf(java.time.LocalDate.of(2026, 10, 29)), s.brakeTrips(ctx(), Mode.LIVE))
    }
}
