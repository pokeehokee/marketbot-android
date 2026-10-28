package com.marketbot.core

import com.marketbot.core.btc.BtcIndex
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.provenance.Source
import com.marketbot.core.store.QuoteRow
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherLongshotTest {
    private val fake = FakeKalshi()
    private val s = WeatherLongshot()
    private val stores = inMemoryStores()
    private val clock = MutableClock(Instant.parse("2026-10-27T20:00:00Z"))
    private val farClose = "2026-10-29T05:00:00Z"     // 33 h of lead

    @AfterTest fun close() = fake.close()

    private fun ctx(mode: Mode, armed: Boolean = false, signed: Boolean = mode == Mode.LIVE, cfg: Map<String, String> = emptyMap()) =
        StrategyContext(s.id, mode, StrategyConfig(s.params, cfg), stores, clock, fake.client(signed), PolymarketClient(null),
            BtcIndex(venues = emptyMap(), candles = { emptyList() }), liveArmed = armed && mode == Mode.LIVE)

    private fun listing() {
        fake.markets["KXHIGHNY"] = listOf(
            FakeKalshi.market("KXHIGHNY-26OCT28-B70.5", 0.06, 0.07, farClose),     // middle band: quoted
            FakeKalshi.market("KXHIGHNY-26OCT28-B72.5", 0.11, 0.12, farClose),     // 0.10-0.15 ask band: skipped
            FakeKalshi.market("KXHIGHNY-26OCT28-B74.5", 0.00, 0.01, farClose),     // under the 2c floor
            FakeKalshi.market("KXHIGHNY-26OCT28-B76.5", 0.03, 0.04, "2026-10-28T10:00:00Z"), // 14 h lead: too late
            FakeKalshi.market("KXHIGHNY-26OCT28-B78.5", 0.02, 0.10, farClose),     // spread 8c: too wide
        )
        fake.markets["KXLOWTNYC"] = listOf(FakeKalshi.market("KXLOWTNYC-26OCT28-B50.5", 0.17, 0.18, farClose)) // LOW above 0.15: no
        fake.markets["KXHIGHTDAL"] = listOf(FakeKalshi.market("KXHIGHTDAL-26OCT28-B90.5", 0.16, 0.18, farClose)) // HIGH ext band: yes
    }

    @Test fun `paper pass quotes exactly the markets the measured rules accept, and sends nothing`() = runTest {
        listing()
        s.runOnce(ctx(Mode.PAPER))
        val rows = stores.ledger.all(s.id, Mode.PAPER)
        assertEquals(setOf("KXHIGHNY-26OCT28-B70.5", "KXHIGHTDAL-26OCT28-B90.5"), rows.map { it.ticker }.toSet())
        assertTrue(rows.all { it.action == "sell_yes" && it.contracts == 5.0 && it.orderId == null })
        assertEquals(0.07, rows.first { it.ticker.endsWith("B70.5") }.price, "rests at the ask, never inside it")
        assertTrue(fake.posts().isEmpty(), "paper mode must never place an order")
        assertTrue(fake.requests.none { it.getHeader("KALSHI-ACCESS-KEY") != null }, "paper mode with no key reads only public data")
    }

    @Test fun `live mode that is not armed sends nothing`() = runTest {
        listing()
        fake.settlements = """{"settlements":[],"cursor":""}"""
        s.runOnce(ctx(Mode.LIVE, armed = false))
        assertTrue(fake.posts().isEmpty())
        assertTrue(stores.ledger.all(s.id).isEmpty())
    }

    @Test fun `armed live pass rests post-only asks routed to the market shard, sized from the API balance`() = runTest {
        listing()
        s.runOnce(ctx(Mode.LIVE, armed = true))
        val rows = stores.ledger.all(s.id, Mode.LIVE)
        assertEquals(2, rows.size)
        assertEquals(2, fake.bodies.size)
        for (b in fake.bodies) {
            assertEquals("\"ask\"", b["side"].toString()); assertEquals("true", b["post_only"].toString())
            assertEquals("0", b["exchange_index"].toString()); assertEquals("\"5\"", b["count"].toString())
        }
        assertTrue(rows.all { it.orderId != null && it.clientOrderId != null })
        val cash = stores.audit.latest()["kalshi.cash"]!!
        assertEquals(Source.KALSHI_API, cash.source)
        assertTrue(cash.origin.endsWith("#balance_dollars"))
    }

    @Test fun `live quoting stops when settlements cannot be read`() = runTest {
        listing()
        fake.settlements = null
        s.runOnce(ctx(Mode.LIVE, armed = true))
        assertTrue(fake.posts().isEmpty(), "a stale brake must not quote")
    }

    @Test fun `a series moving to maker fees stops live quoting`() = runTest {
        listing()
        fake.seriesFeeType = "quadratic_with_maker_fees"
        s.runOnce(ctx(Mode.LIVE, armed = true))       // detects it after the first pass
        val before = fake.posts().size
        clock.advanceSeconds(3600)
        fake.markets["KXHIGHTATL"] = listOf(FakeKalshi.market("KXHIGHTATL-26OCT28-B80.5", 0.06, 0.07, farClose))
        s.runOnce(ctx(Mode.LIVE, armed = true))
        assertEquals(before, fake.posts().size)
    }

    @Test fun `resolver grades a filled live row from the settlement payload only`() = runTest {
        val t = "KXHIGHNY-26OCT26-B70.5"
        val id = stores.ledger.insert(QuoteRow(strategyId = s.id, mode = Mode.LIVE, venue = "kalshi", ticker = t, action = "sell_yes",
            price = 0.07, contracts = 5.0, placedAt = clock.now().minusSeconds(90000), closeTime = clock.now().minusSeconds(3600), orderId = "o9"))
        fake.marketByTicker[t] = FakeKalshi.market(t, 0.0, 0.01, "2026-10-27T05:00:00Z", status = "finalized", result = "no")
        fake.orders["o9"] = """{"order":{"order_id":"o9","status":"executed","fill_count_fp":"5.00"}}"""
        fake.settlements = """{"settlements":[],"cursor":""}"""
        s.resolve(ctx(Mode.LIVE, armed = true), live = true)
        assertNull(stores.ledger.byId(id)!!.resolvedAt, "no payload yet: the row waits instead of being graded locally")

        fake.settlements = """{"settlements":[{"ticker":"$t","market_result":"no","revenue":500,"no_total_cost_dollars":"4.65","yes_total_cost_dollars":"0","fee_cost":"0.000000","no_count_fp":"5.00","yes_count_fp":"0.00"}],"cursor":""}"""
        s.resolve(ctx(Mode.LIVE, armed = true), live = true)
        val r = stores.ledger.byId(id)!!
        assertEquals(0.35, r.pnl!!, 1e-9)
        assertEquals(Source.KALSHI_API, r.pnlSource)
        assertEquals(false, r.hypothetical)
        assertTrue(r.pnlOrigin!!.contains("/portfolio/settlements"))
    }

    @Test fun `an unfilled live quote is graded hypothetical and never counts as realised`() = runTest {
        val t = "KXHIGHNY-26OCT26-B72.5"
        val id = stores.ledger.insert(QuoteRow(strategyId = s.id, mode = Mode.LIVE, venue = "kalshi", ticker = t, action = "sell_yes",
            price = 0.07, contracts = 5.0, placedAt = clock.now().minusSeconds(90000), closeTime = clock.now().minusSeconds(3600), orderId = "o8"))
        fake.marketByTicker[t] = FakeKalshi.market(t, 0.0, 0.01, "2026-10-27T05:00:00Z", status = "finalized", result = "yes")
        fake.orders["o8"] = """{"order":{"order_id":"o8","status":"canceled","fill_count_fp":"0.00"}}"""
        s.resolve(ctx(Mode.LIVE, armed = true), live = true)
        val r = stores.ledger.byId(id)!!
        assertEquals(true, r.hypothetical)
        assertEquals(Source.DERIVED, r.pnlSource)
    }

    @Test fun `the daily brake stops quoting after a bad day`() = runTest {
        listing()
        stores.ledger.insert(QuoteRow(strategyId = s.id, mode = Mode.PAPER, venue = "kalshi", ticker = "KXHIGHNY-26OCT27-B60.5",
            action = "sell_yes", price = 0.05, contracts = 11.0, placedAt = clock.now().minusSeconds(80000),
            resolvedAt = clock.now().minusSeconds(60), result = "yes", pnl = -10.45, pnlSource = Source.PAPER_MODEL, hypothetical = false))
        s.runOnce(ctx(Mode.PAPER))
        assertEquals(1, stores.ledger.all(s.id, Mode.PAPER).size, "no new paper quotes after the brake")
    }

    @Test fun `paper fills come from the public trade tape behind the queue`() = runTest {
        listing()
        s.runOnce(ctx(Mode.PAPER))
        val q = stores.ledger.all(s.id, Mode.PAPER).first { it.ticker.endsWith("B70.5") }
        assertEquals(10.0, q.queueAhead)
        val after = clock.now().plusSeconds(60).toString()
        // 12 contracts bought at 0.07 by YES takers: 10 ahead of us, then 2 of our 5
        fake.trades = """{"trades":[{"created_time":"$after","taker_side":"yes","count_fp":"12.00","yes_price_dollars":"0.0700","no_price_dollars":"0.9300"}],"cursor":""}"""
        clock.advanceSeconds(3600)
        s.runOnce(ctx(Mode.PAPER))
        val f = stores.ledger.byId(q.id)!!
        assertEquals(2.0, f.filledContracts)
        assertNotNull(f.fillSource); assertTrue(f.fillSource!!.startsWith("paper"))
    }

    @Test fun `settings that let one market outrun the brake are refused`() {
        val bad = StrategyConfig(s.params, mapOf("lot_cap" to "10", "daily_loss_limit" to "5"))
        assertTrue(s.validate(bad).isNotEmpty())
        assertTrue(s.validate(StrategyConfig(s.params)).isEmpty())
    }
}
