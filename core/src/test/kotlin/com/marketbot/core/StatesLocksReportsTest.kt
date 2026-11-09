package com.marketbot.core

import com.marketbot.core.btc.BtcIndex
import com.marketbot.core.engine.Engine
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.store.inMemoryStores
import com.marketbot.core.strategies.longshot.WeatherLongshot
import com.marketbot.core.strategy.Calibration
import com.marketbot.core.strategy.Lock
import com.marketbot.core.strategy.LockStore
import com.marketbot.core.strategy.Markout
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.OrderDenied
import com.marketbot.core.strategy.Periods
import com.marketbot.core.strategy.Protection
import com.marketbot.core.strategy.ReasonCode
import com.marketbot.core.strategy.Reasons
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.strategy.TradingState
import com.marketbot.core.util.MutableClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trading states and reason codes, locks and protections, markouts, period reports and calibration. */
class StatesLocksReportsTest {
    private val fake = FakeKalshi()
    @AfterTest fun close() = fake.close()
    private val t0 = Instant.parse("2026-10-29T12:00:00Z")

    private fun settled(i: Int, pnl: Double, at: Instant, ticker: String = "KXHIGHNY-26OCT29-B7$i.5", result: String = if (pnl < 0) "yes" else "no") =
        QuoteRow(id = i.toLong(), strategyId = "s", mode = Mode.PAPER, venue = "kalshi", ticker = ticker, action = "sell_yes", price = 0.07, contracts = 5.0,
            placedAt = at.minusSeconds(86_400), filledContracts = 5.0, resolvedAt = at, result = result, pnl = pnl, hypothetical = false)

    @Test fun `every hold-back a pass reports has one code and one state`() {
        assertEquals(ReasonCode.BRAKE_DAILY, Reasons.ofSummary("brake")!!.code)
        assertEquals(TradingState.REDUCING, Reasons.ofSummary("daily_stop")!!.state)
        assertEquals(TradingState.HALTED, Reasons.ofSummary("kill criterion")!!.state)
        assertEquals(ReasonCode.INDEX_STALE, Reasons.ofSummary("stale_index")!!.code)
        assertNull(Reasons.ofSummary("Quoted 3 market(s), \$12.00 collateral"))
        assertEquals(ReasonCode.RATE_LIMITED, Reasons.ofError(com.marketbot.core.net.ApiException(429, "too_many", "slow down")).code)
        assertEquals(ReasonCode.EXCHANGE_ERROR, Reasons.ofError(com.marketbot.core.net.ApiException(503, null, "down")).code)
        assertEquals(ReasonCode.NETWORK, Reasons.ofError(java.io.IOException("reset")).code)
        assertEquals(TradingState.HALTED, Reasons.ofError(com.marketbot.core.kalshi.FillLimitTripped(0, t0)).state)
    }

    @Test fun `protections lock what their condition names, and locks expire by themselves`() = runTest {
        val rows = listOf(settled(1, -4.65, t0.minusSeconds(3600)), settled(2, 0.35, t0.minusSeconds(3000)),
            settled(3, -4.65, t0.minusSeconds(1800)), settled(4, -4.65, t0.minusSeconds(600), ticker = "KXHIGHCHI-26OCT29-B60.5"))
        val guard = Protection(Protection.Type.LOSS_GUARD, enabled = true, tradeLimit = 3, stopHours = 2)
        val l = guard.evaluate("s", Mode.PAPER, rows, t0).single()
        assertNull(l.series); assertEquals(t0.minusSeconds(600).plusSeconds(7200).toString(), l.until, "two hours from the last loss")
        assertTrue(Protection(Protection.Type.LOSS_GUARD, enabled = false).evaluate("s", Mode.PAPER, rows, t0).isEmpty(), "off by default")
        val dd = Protection(Protection.Type.MAX_DRAWDOWN, enabled = true, tradeLimit = 3, maxDrawdownUsd = 9.0).evaluate("s", Mode.PAPER, rows, t0)
        assertEquals(1, dd.size, "peak 0.35 to -13.60 is a 13.95 drawdown")
        val cool = Protection(Protection.Type.COOLDOWN, enabled = true, stopHours = 1).evaluate("s", Mode.PAPER, rows, t0)
        assertEquals(setOf("KXHIGHNY", "KXHIGHCHI"), cool.map { it.series }.toSet().let { s -> s.filterNotNull().toSet() })
        val low = Protection(Protection.Type.LOW_PROFIT, enabled = true, tradeLimit = 3, stopHours = 6).evaluate("s", Mode.PAPER, rows, t0)
        assertEquals(listOf("KXHIGHNY"), low.map { it.series }, "Chicago has one position, under the minimum")

        val store = LockStore(inMemoryStores().state)
        assertTrue(store.add(cool.first { it.series == "KXHIGHNY" }, t0))
        assertNotNull(store.denies("s", Mode.PAPER, "KXHIGHNY-26OCT30-B70.5", t0))
        assertNull(store.denies("s", Mode.PAPER, "KXHIGHTDAL-26OCT30-B90.5", t0), "another series is free")
        assertNull(store.denies("s", Mode.LIVE, "KXHIGHNY-26OCT30-B70.5", t0), "a paper lock never touches live")
        assertNull(store.denies("s", Mode.PAPER, "KXHIGHNY-26OCT30-B70.5", t0.plusSeconds(4 * 3600)), "expired")
    }

    @Test fun `a locked series is not quoted and the funnel says so`() = runTest {
        val s = WeatherLongshot()
        val stores = inMemoryStores()
        val clock = MutableClock(Instant.parse("2026-10-27T20:00:00Z"))
        fake.markets["KXHIGHNY"] = listOf(FakeKalshi.market("KXHIGHNY-26OCT28-B70.5", 0.06, 0.07, "2026-10-29T05:00:00Z"))
        fake.markets["KXHIGHTDAL"] = listOf(FakeKalshi.market("KXHIGHTDAL-26OCT28-B90.5", 0.06, 0.07, "2026-10-29T05:00:00Z"))
        val locks = LockStore(stores.state)
        locks.add(Lock("t", s.id, Mode.PAPER, "KXHIGHNY", clock.now().plusSeconds(3600).toString(), "test"), clock.now())
        val ctx = StrategyContext(s.id, Mode.PAPER, StrategyConfig(s.params), stores, clock, fake.client(false), PolymarketClient(null),
            BtcIndex(venues = emptyMap(), candles = { emptyList() }), liveArmed = false,
            gateFn = { t -> locks.denies(s.id, Mode.PAPER, t, clock.now()) })
        val f = s.runOnce(ctx).funnel
        assertEquals(listOf("KXHIGHTDAL-26OCT28-B90.5"), stores.ledger.all(s.id, Mode.PAPER).map { it.ticker })
        assertEquals(1, f.first { it.label == "Not locked" }.count)
    }

    @Test fun `with the kill switch on, no order can leave the phone whatever path tries`() = runBlocking {
        val e = Engine(inMemoryStores(), MutableClock(t0), clientFactory = { _ -> fake.client(true) to PolymarketClient(null) }, fillStreamFactory = null)
        e.setGlobalKill(true)
        val d = assertFailsWith<OrderDenied> { e.kalshiClient.placeOrder("KXHIGHNY-26OCT30-B70.5", "ask", 5, 0.07, 0.01, 0) }
        assertEquals(ReasonCode.KILL_SWITCH, d.reason.code)
        assertTrue(fake.posts().none { it.path!!.endsWith("/orders") }, "nothing sent")
        e.setGlobalKill(false)
        assertNotNull(e.kalshiClient.placeOrder("KXHIGHNY-26OCT30-B70.5", "ask", 5, 0.07, 0.01, 0).orderId)
        Unit
    }

    @Test fun `markouts are signed for the filled side and recorded at 5, 30 and 60 minutes`() = runBlocking {
        val sell = settled(1, 0.0, t0).copy(resolvedAt = null, result = null, pnl = null, price = 0.07)
        assertEquals(2.0, Markout.cents(sell, 0.05), 1e-9, "sold at 7c, now 5c: +2c")
        assertEquals(-3.0, Markout.cents(sell.copy(action = "buy_yes", price = 0.95), 0.92), 1e-9)
        assertEquals(1.0, Markout.cents(sell.copy(action = "buy_no", price = 0.95), 0.04), 1e-9)

        val stores = inMemoryStores()
        val clock = MutableClock(t0)
        fake.marketByTicker["KXHIGHNY-26OCT30-B70.5"] = FakeKalshi.market("KXHIGHNY-26OCT30-B70.5", 0.04, 0.06, "2026-10-31T05:00:00Z")
        val e = Engine(stores, clock, clientFactory = { _ -> fake.client(false) to PolymarketClient(null) }, fillStreamFactory = null)
        val id = stores.ledger.insert(QuoteRow(strategyId = "kalshi_weather_longshot", mode = Mode.PAPER, venue = "kalshi", ticker = "KXHIGHNY-26OCT30-B70.5",
            action = "sell_yes", price = 0.07, contracts = 5.0, placedAt = t0.minusSeconds(600), filledContracts = 5.0))
        e.trackMarkoutsIfDue()
        assertNotNull(Markout.seenAt(stores.ledger.byId(id)!!))
        clock.advanceSeconds(31 * 60)
        assertTrue(e.trackMarkoutsIfDue() >= 2)
        val q = stores.ledger.byId(id)!!
        assertEquals(2.0, Markout.read(q, 5)!!, 1e-9); assertEquals(2.0, Markout.read(q, 30)!!, 1e-9); assertNull(Markout.read(q, 60))
        assertEquals(2.0, Markout.read(q, 0)!!, 1e-9, "the price at the fill itself, read when the fill was first seen")
        assertEquals(3, Markout.summary(listOf(q)).count { it.n == 1 })
        Unit
    }

    @Test fun `periods group settled P&L by day, Monday-start week and month`() {
        val now = Instant.parse("2026-10-29T12:00:00Z")   // a Thursday
        val rows = listOf(settled(1, 0.35, Instant.parse("2026-10-29T08:00:00Z")), settled(2, -4.65, Instant.parse("2026-10-27T08:00:00Z")),
            settled(3, 0.35, Instant.parse("2026-10-25T08:00:00Z")), settled(4, 1.0, Instant.parse("2026-09-30T08:00:00Z")))
        val days = Periods.table(rows, Periods.Unit.DAY, 7, ZoneOffset.UTC, now)
        assertEquals(7, days.size); assertEquals(0.35, days[0].pnl, 1e-9); assertEquals(1, days[2].losses)
        val weeks = Periods.table(rows, Periods.Unit.WEEK, 2, ZoneOffset.UTC, now)
        assertEquals(java.time.LocalDate.parse("2026-10-26"), weeks[0].start)
        assertEquals(-4.30, weeks[0].pnl, 1e-9, "Mon 26 to Thu 29"); assertEquals(0.35, weeks[1].pnl, 1e-9, "Sun 25 is the week before")
        val months = Periods.table(rows, Periods.Unit.MONTH, 2, ZoneOffset.UTC, now)
        assertEquals(3, months[0].settled); assertEquals(1.0, months[1].pnl, 1e-9)
    }

    @Test fun `calibration compares settled YES with what prices implied, one row per market`() {
        fun q(i: Int, bid: Double, ask: Double, result: String) = settled(i, 0.0, t0, result = result).copy(
            extra = buildJsonObject { put("yes_bid", bid); put("yes_ask", ask) })
        val rows = (0 until 40).map { q(it, 0.06, 0.08, if (it < 2) "yes" else "no") } + q(0, 0.06, 0.08, "yes")   // a re-quote of market 0
        val b = Calibration.table(rows).single()
        assertEquals(40, b.n, "a market counted once however often it was quoted")
        assertEquals(0.07, b.implied!!, 1e-9); assertEquals(0.05, b.realised!!, 1e-9)
        assertEquals(0.05 / 0.07, b.ratio!!, 1e-9)
    }
}
