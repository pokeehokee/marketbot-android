package com.marketbot.core

import com.marketbot.core.btc.BtcIndex
import com.marketbot.core.engine.Engine
import com.marketbot.core.net.JsonHttp
import com.marketbot.core.net.PolymarketSigner
import com.marketbot.core.polymarket.PmMarketMeta
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.provenance.Source
import com.marketbot.core.provenance.ValueUnavailable
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.store.inMemoryStores
import com.marketbot.core.strategies.longshot.WeatherLongshot
import com.marketbot.core.strategies.rewards.LiveFarmBroker
import com.marketbot.core.strategies.rewards.PaperFarmBroker
import com.marketbot.core.strategies.rewards.PaperFarmState
import com.marketbot.core.strategies.rewards.RewardFarm
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.util.MutableClock
import com.marketbot.core.polymarket.PmBook
import com.marketbot.core.polymarket.Level
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolymarketAndEngineTest {
    private val server = MockWebServer()
    private val reqs = CopyOnWriteArrayList<RecordedRequest>()
    var routes: Map<String, String> = emptyMap()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                reqs += request
                val p = request.requestUrl!!.encodedPath
                return routes[p]?.let { MockResponse().setResponseCode(200).setBody(it) } ?: MockResponse().setResponseCode(404).setBody("{}")
            }
        }
        server.start()
    }

    @AfterTest fun close() = server.shutdown()

    private fun pm(signed: Boolean): PolymarketClient {
        val base = server.url("").toString().trimEnd('/')
        val secret = golden("signatures").o()["ed25519"].o()["seed_b64"].s!!
        return PolymarketClient(if (signed) PolymarketSigner("kid", secret) else null,
            gateway = JsonHttp(base, sleep = {}), api = JsonHttp(base, sleep = {}))
    }

    @Test fun `fee coefficient comes from the market field, the documented constant only when absent`() {
        val m = PmMarketMeta(payload("pm_market_btc")["market"].o())
        assertEquals(0.0695, m.takerTheta.value)
        assertEquals(Source.POLYMARKET_API, m.takerTheta.source)
        assertEquals(86000.0, m.strike)
        assertEquals(Instant.parse("2026-10-28T21:00:00Z"), m.windowEnd)
        val bare = PmMarketMeta(com.marketbot.core.util.parseObject("""{"slug":"x"}"""))
        assertEquals(Source.DERIVED, bare.takerTheta.source)
    }

    @Test fun `a recorded book parses from the gateway's px-qty shape`() {
        val md = payload("pm_book_btc")["marketData"].o()
        val b = PmBook.parse("s", md, Instant.EPOCH)
        assertEquals(0.03, b.bestAsk); assertEquals(null, b.bestBid)
        assertTrue(b.open)
    }

    @Test fun `balances and positions are read from API fields and refuse to guess`() = runTest {
        routes = mapOf(
            "/v1/account/balances" to """{"balances":[{"currency":"USD","currentBalance":7.42,"buyingPower":5.0,"openOrders":1.0}]}""",
            "/v1/portfolio/positions" to """{"positions":{"m1":{"netPositionDecimal":"10","avgPx":{"value":"0.40","currency":"USD"},"cashValue":{"value":"4.50","currency":"USD"}}},"eof":true}""",
            "/v1/markets/m1/book" to """{"marketData":{"bids":[{"px":{"value":"0.42"},"qty":"5"}],"offers":[{"px":{"value":"0.45"},"qty":"5"}],"state":"MARKET_STATE_OPEN"}}""",
        )
        val c = pm(true)
        val b = c.balance()
        assertEquals(7.42, b.currentBalance.value); assertEquals(Source.POLYMARKET_API, b.currentBalance.source)
        val bal = LiveFarmBroker(c).balance(emptyMap())
        assertEquals(7.42 + 10 * 0.42, bal.equity.value, 1e-9, "longs are valued at the bid")
        assertEquals(Source.DERIVED, bal.equity.source)
        assertEquals(7.42 + 4.50, bal.exchangeEquity!!.value, 1e-9)
        assertEquals(Source.POLYMARKET_API, bal.exchangeEquity!!.source)
        assertNotNull(reqs.first { it.requestUrl!!.encodedPath == "/v1/account/balances" }.getHeader("X-PM-Signature"))
        assertTrue(reqs.filter { it.requestUrl!!.encodedPath.endsWith("/book") }.all { it.getHeader("X-PM-Signature") == null })

        routes = mapOf("/v1/account/balances" to """{"balances":[{"currency":"USD","buyingPower":5.0}]}""")
        assertFailsWith<ValueUnavailable> { c.balance() }
    }

    @Test fun `paper farm broker fills from the front of the queue and on a cross, and pays the maker rebate`() = runTest {
        val br = PaperFarmBroker(PaperFarmState(10.0, startingCash = 10.0))
        br.place("m", "bid", 0.40, 10.0, "ORDER_INTENT_BUY_LONG", null, queueAhead = 30.0)
        fun book(bidQty: Double, ask: Double = 0.45) = PmBook("m", listOf(Level(0.40, bidQty)), listOf(Level(ask, 50.0)), "", Instant.EPOCH)
        assertTrue(br.onBook(book(40.0)).isEmpty())
        // level 40 -> 15 (25 traded): all 25 were ahead of us (30 ahead)
        br.onBook(book(40.0))
        // 40 -> 25: 15 gone, 15 of the 30 ahead
        assertTrue(br.onBook(book(25.0)).isEmpty())
        // 25 -> 5: 20 gone, 15 ahead then 5 ours
        val f = br.onBook(book(5.0))
        assertEquals(5.0, f.single().qty)
        // ask comes down through our price: the rest fills
        val f2 = br.onBook(book(5.0, ask = 0.40))
        assertEquals(5.0, f2.single().qty)
        assertEquals(10.0, br.positions().getValue("m").qty)
        assertTrue(br.s.rebates > 0)
    }

    @Test fun `reward farm paper pass sends no orders and uses only public data`() = runTest {
        routes = mapOf("/v1/incentives" to """{"programs":[]}""")
        val s = RewardFarm(); val stores = inMemoryStores()
        val ctx = StrategyContext(s.id, Mode.PAPER, StrategyConfig(s.params), stores, MutableClock(Instant.parse("2026-10-27T20:00:00Z")),
            FakeKalshi().use { it.client(false) }, pm(false), BtcIndex(emptyMap(), { emptyList() }), false)
        s.runOnce(ctx)
        assertTrue(reqs.none { it.method == "POST" })
        assertTrue(reqs.none { it.getHeader("X-PM-Signature") != null })
        assertEquals(10.0, stores.equity.history("polymarket", Mode.PAPER, Instant.EPOCH).single().cash.value)
    }

    @Test fun `engine refuses to arm live until hard requirements are met and soft ones acknowledged`() = runTest {
        FakeKalshi().use { fake ->
            val stores = inMemoryStores()
            val clock = MutableClock(Instant.parse("2026-10-27T20:00:00Z"))
            val engine = Engine(stores, clock, clientFactory = { _ -> fake.client(true) to pm(false) })
            val s = engine.strategies.first { it is WeatherLongshot }
            val (ok, why) = engine.arm(s, emptySet())
            assertFalse(ok); assertTrue(why.contains("Preflight"), why)

            stores.state.put("${s.id}.preflight_ok_at", clock.now().toString())
            val (ok2, why2) = engine.arm(s, emptySet())
            assertFalse(ok2); assertTrue(why2.contains("acknowledge"), why2)

            val soft = engine.requirements(s).filter { !it.hard }.map { it.id }.toSet()
            val (ok3, _) = engine.arm(s, soft)
            assertTrue(ok3)
            assertEquals(Mode.LIVE, engine.mode(s.id))
            assertNotNull(engine.armRecord(s.id))
        }
    }

    @Test fun `kill switch cancels resting live orders at the exchange and disarms everything`() = runTest {
        FakeKalshi().use { fake ->
            val stores = inMemoryStores()
            val engine = Engine(stores, MutableClock(Instant.parse("2026-10-27T20:00:00Z")), clientFactory = { _ -> fake.client(true) to pm(false) })
            val s = engine.strategies.first { it is WeatherLongshot }
            stores.state.put("strategy.${s.id}.mode", "LIVE")
            stores.state.put("strategy.${s.id}.armed", """{"at":"x","acknowledged":[]}""")
            fake.orders["o1"] = """{"order":{"order_id":"o1","status":"resting","fill_count_fp":"0.00"}}"""
            stores.ledger.insert(QuoteRow(strategyId = s.id, mode = Mode.LIVE, venue = "kalshi", ticker = "KXHIGHNY-26OCT28-B70.5",
                action = "sell_yes", price = 0.07, contracts = 5.0, placedAt = Instant.parse("2026-10-27T19:00:00Z"), orderId = "o1"))
            engine.setGlobalKill(true)
            assertEquals(1, fake.deletes().size)
            assertEquals("KXHIGHNY-26OCT28-B70.5", fake.deletes().single().requestUrl!!.queryParameter("market_ticker"))
            assertEquals(Mode.PAPER, engine.mode(s.id))
            assertEquals(null, engine.armRecord(s.id))
            assertNotNull(stores.ledger.all(s.id, Mode.LIVE).single().cancelledAt)
            val (ok, _) = engine.arm(s, emptySet())
            assertFalse(ok, "nothing can be armed while the kill switch is on")
        }
    }
}
