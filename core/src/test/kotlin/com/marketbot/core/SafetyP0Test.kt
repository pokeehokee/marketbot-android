package com.marketbot.core

import com.marketbot.core.btc.BtcIndex
import com.marketbot.core.btc.VenueQuote
import com.marketbot.core.engine.Engine
import com.marketbot.core.net.JsonHttp
import com.marketbot.core.net.PolymarketSigner
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.store.inMemoryStores
import com.marketbot.core.strategies.deepitm.DeepItmRule
import com.marketbot.core.strategies.deepitm.KalshiBtcDeepItm
import com.marketbot.core.strategies.longshot.WeatherLongshot
import com.marketbot.core.strategies.rewards.LiveFarmBroker
import com.marketbot.core.strategies.rewards.RewardFarm
import com.marketbot.core.strategy.Evidence
import com.marketbot.core.strategy.EvidenceLevel
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.PreflightResult
import com.marketbot.core.strategy.Requirement
import com.marketbot.core.strategy.RunReport
import com.marketbot.core.strategy.Stat
import com.marketbot.core.strategy.Strategy
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.strategy.Venue
import com.marketbot.core.util.MutableClock
import com.marketbot.core.util.parseObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The P0 review items, each pinned by a test. */
class SafetyP0Test {
    private val fake = FakeKalshi()
    @AfterTest fun close() = fake.close()
    private val farClose = "2026-10-29T05:00:00Z"

    private fun weatherListing() {
        fake.markets["KXHIGHNY"] = listOf(FakeKalshi.market("KXHIGHNY-26OCT28-B70.5", 0.06, 0.07, farClose),
            FakeKalshi.market("KXHIGHNY-26OCT28-B71.5", 0.05, 0.06, farClose))
    }

    // ---------------------------------------------------------------- 1. one pass at a time
    @Test fun `two passes started together never place the same order twice`() = runBlocking {
        weatherListing()
        val stores = inMemoryStores()
        val clock = MutableClock(Instant.parse("2026-10-27T20:00:00Z"))
        val engine = Engine(stores, clock, clientFactory = { _ -> fake.client(true) to PolymarketClient(null) })
        val s = engine.strategies.first { it is WeatherLongshot }
        stores.state.put("strategy.${s.id}.mode", "LIVE")
        stores.state.put("strategy.${s.id}.armed", """{"at":"x","acknowledged":[]}""")
        coroutineScope { (1..4).map { async(Dispatchers.IO) { engine.runNow(s) } }.awaitAll() }
        val tickers = fake.bodies.map { it["ticker"].s }
        assertEquals(tickers.toSet().size, tickers.size, "duplicate orders: $tickers")
        assertEquals(2, tickers.size)
    }

    // ---------------------------------------------------------------- 2/3. dead-man expiry and pause
    @Test fun `every live longshot offer carries an exchange-side expiry and cancel-on-pause`() = runTest {
        weatherListing()
        val stores = inMemoryStores()
        val clock = MutableClock(Instant.parse("2026-10-27T20:00:00Z"))
        val s = WeatherLongshot()
        val ctx = StrategyContext(s.id, Mode.LIVE, StrategyConfig(s.params), stores, clock, fake.client(true, clock), PolymarketClient(null),
            BtcIndex(emptyMap(), { emptyList() }), liveArmed = true)
        s.runOnce(ctx)
        assertTrue(fake.bodies.isNotEmpty())
        for (b in fake.bodies) {
            assertEquals("true", b["cancel_order_on_pause"].toString())
            val exp = b["expiration_time"].dd.toLong()
            assertEquals(clock.now().plusSeconds((8.5 * 3600).toLong()).epochSecond, exp, "idle recycle (8 h) + 30 min")
        }
    }

    @Test fun `an offer the exchange already expired is closed in the ledger without a cancel request`() = runTest {
        val stores = inMemoryStores()
        val clock = MutableClock(Instant.parse("2026-10-27T20:00:00Z"))
        val s = WeatherLongshot()
        val id = stores.ledger.insert(QuoteRow(strategyId = s.id, mode = Mode.LIVE, venue = "kalshi", ticker = "KXHIGHNY-26OCT28-B70.5",
            action = "sell_yes", price = 0.07, contracts = 5.0, placedAt = clock.now().minusSeconds(9 * 3600), orderId = "o1",
            closeTime = Instant.parse(farClose)))
        fake.orders["o1"] = """{"order":{"order_id":"o1","status":"canceled","fill_count_fp":"1.00"}}"""
        stores.state.put("${s.id}.live.settlements_read_at", clock.now().toString())
        val ctx = StrategyContext(s.id, Mode.LIVE, StrategyConfig(s.params), stores, clock, fake.client(true), PolymarketClient(null),
            BtcIndex(emptyMap(), { emptyList() }), liveArmed = true)
        s.runOnce(ctx)
        val q = stores.ledger.byId(id)!!
        assertNotNull(q.cancelledAt)
        assertEquals(1.0, q.filledContracts)
        assertTrue(fake.deletes().isEmpty(), "no DELETE for an order the exchange already closed")
    }

    // ---------------------------------------------------------------- deep-ITM dead-man and renewal
    private var t = 1_000.0
    private suspend fun warmIndex(): BtcIndex {
        var px = 75_000.0
        val ix = BtcIndex(mapOf("a" to VenueQuote { px }, "b" to VenueQuote { px + 2 }, "c" to VenueQuote { px - 2 }),
            { (0..16).map { i -> listOf(1_000.0 + 60 * i, 0.0, 0.0, 0.0, 75_000 * exp(0.0004 * sin(i.toDouble())), 1.0) } }, nowS = { t })
        repeat(400) { i -> px = 75_000 * exp(0.00004 * sin(i / 3.0)); ix.pollOnce(); t += 1.0 }
        return ix
    }

    @Test fun `live deep-ITM bids expire at the exchange and are renewed without losing queue`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-10-27T20:20:00Z"))
        val (event, _) = DeepItmRule.nextHourEvent(clock.now())
        fake.markets["event:$event"] = listOf("""{"ticker":"$event-T74000","event_ticker":"$event","status":"active","close_time":"2026-10-27T21:00:00Z",
            "strike_type":"greater","floor_strike":74000,"yes_bid_dollars":"0.94","no_bid_dollars":"0.04","yes_bid_size_fp":"20","yes_ask_size_fp":"30",
            "exchange_index":2,"price_ranges":[{"start":"0","end":"1","step":"0.01"}]}""")
        val ix = warmIndex()
        val stores = inMemoryStores()
        val s = KalshiBtcDeepItm()
        fun ctx() = StrategyContext(s.id, Mode.LIVE, StrategyConfig(s.params), stores, clock, fake.client(true, clock), PolymarketClient(null), ix, true)
        s.runOnce(ctx())
        val body = fake.bodies.single()
        assertEquals(clock.now().plusSeconds(120).epochSecond, body["expiration_time"].dd.toLong())
        assertEquals("true", body["cancel_order_on_pause"].toString())

        clock.advanceSeconds(70); t += 70.0; repeat(3) { ix.pollOnce(); t += 1.0 }
        s.runOnce(ctx())
        val a = fake.amends.single()
        assertEquals("\"0.9400\"", a["price"].toString(), "price unchanged: an expiry-only amend keeps queue position")
        assertEquals("\"5.00\"", a["count"].toString())
        assertEquals(clock.now().plusSeconds(120).epochSecond, a["expiration_time"].dd.toLong())
        assertEquals(1, fake.bodies.size, "renewed, not re-placed")
    }

    // ---------------------------------------------------------------- 4/5/6/7 Polymarket
    private val pmServer = MockWebServer()
    private val pmReqs = CopyOnWriteArrayList<RecordedRequest>()
    private var pmRoutes: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

    private fun pm(): PolymarketClient {
        pmServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { pmReqs += request; return pmRoutes(request) }
        }
        runCatching { pmServer.start() }
        val base = pmServer.url("").toString().trimEnd('/')
        val secret = golden("signatures").o()["ed25519"].o()["seed_b64"].s!!
        return PolymarketClient(PolymarketSigner("kid", secret), gateway = JsonHttp(base, sleep = {}), api = JsonHttp(base, sleep = {}))
    }

    private fun ok(b: String) = MockResponse().setResponseCode(200).setBody(b)

    @Test fun `the farm's kill switch cancels only the orders this app placed`() = runTest {
        pmRoutes = { r ->
            when (r.requestUrl!!.encodedPath) {
                "/v1/orders/open" -> ok("""{"orders":[
                    {"id":"mine","marketSlug":"m","side":"ORDER_SIDE_BUY","price":{"value":"0.40"},"leavesQuantity":5,"intent":"ORDER_INTENT_BUY_LONG","state":"ORDER_STATE_NEW"},
                    {"id":"manual","marketSlug":"m","side":"ORDER_SIDE_BUY","price":{"value":"0.30"},"leavesQuantity":9,"intent":"ORDER_INTENT_BUY_LONG","state":"ORDER_STATE_NEW"}]}""")
                else -> ok("{}")
            }
        }
        val br = LiveFarmBroker(pm(), mutableSetOf("mine"))
        assertEquals(listOf("mine"), br.openOrders().map { it.id }, "the farm never sees or reprices manual orders")
        br.cancelAll()
        val cancels = pmReqs.filter { it.method == "POST" }.map { it.requestUrl!!.encodedPath }
        assertEquals(listOf("/v1/order/mine/cancel"), cancels)
        assertTrue(pmReqs.none { it.requestUrl!!.encodedPath == "/v1/orders/open/cancel" }, "never the account-wide cancel-all")
        pmServer.shutdown()
    }

    @Test fun `a lost Polymarket create is recovered when exactly one matching order appears`() = runTest {
        val creates = AtomicInteger()
        var open = """{"orders":[{"id":"new1","marketSlug":"m","side":"ORDER_SIDE_BUY","price":{"value":"0.9400"},"leavesQuantity":5,"intent":"ORDER_INTENT_BUY_LONG","state":"ORDER_STATE_NEW"}]}"""
        pmRoutes = { r ->
            when (r.requestUrl!!.encodedPath) {
                "/v1/orders" -> { creates.incrementAndGet(); MockResponse().setResponseCode(502).setBody("{}") }
                "/v1/orders/open" -> ok(open)
                else -> ok("{}")
            }
        }
        val c = pm()
        assertEquals("new1", c.placeOrderRecovering("m", "ORDER_INTENT_BUY_LONG", 0.94, 5.0))
        assertEquals(1, creates.get(), "a create is never retried")
        open = open.replace("]}", ""","""+"""{"id":"new2","marketSlug":"m","side":"ORDER_SIDE_BUY","price":{"value":"0.9400"},"leavesQuantity":5,"intent":"ORDER_INTENT_BUY_LONG","state":"ORDER_STATE_NEW"}]}""")
        assertFailsWith<Exception>("two candidates: refuse to guess") { c.placeOrderRecovering("m", "ORDER_INTENT_BUY_LONG", 0.94, 5.0) }
        assertEquals("new2", c.placeOrderRecovering("m", "ORDER_INTENT_BUY_LONG", 0.94, 5.0, known = setOf("new1")))
        pmServer.shutdown()
    }

    @Test fun `the farm keeps one live broker for the whole run`() = runTest {
        pmRoutes = { r ->
            when (r.requestUrl!!.encodedPath) {
                "/v1/account/balances" -> ok("""{"balances":[{"currency":"USD","currentBalance":10,"buyingPower":10,"openOrders":0}]}""")
                "/v1/portfolio/positions" -> ok("""{"positions":{},"eof":true}""")
                "/v1/orders/open" -> ok("""{"orders":[]}""")
                "/v1/incentives" -> ok("""{"programs":[]}""")
                else -> ok("{}")
            }
        }
        val s = RewardFarm(); val stores = inMemoryStores(); val c = pm()
        val ctx = StrategyContext(s.id, Mode.LIVE, StrategyConfig(s.params), stores, MutableClock(Instant.parse("2026-10-27T20:00:00Z")),
            fake.client(false), c, BtcIndex(emptyMap(), { emptyList() }), liveArmed = true)
        s.runOnce(ctx); s.runOnce(ctx)
        val rt = s.javaClass.getDeclaredField("rts").apply { isAccessible = true }.get(s) as Map<*, *>
        val live1 = rt[Mode.LIVE]!!.let { r -> r.javaClass.getDeclaredField("live").apply { isAccessible = true }.get(r) }
        assertNotNull(live1)
        s.runOnce(ctx)
        val live2 = rt[Mode.LIVE]!!.let { r -> r.javaClass.getDeclaredField("live").apply { isAccessible = true }.get(r) }
        assertTrue(live1 === live2)
        pmServer.shutdown()
    }

    @Test fun `earnings are summed from the reward field only, and a row without one shows nothing`() {
        val rows = listOf(parseObject("""{"date":"2026-10-01","marketSlug":"m","reward":0.26,"status":"REWARD_STATUS_PAID"}"""),
            parseObject("""{"date":"2026-10-02","marketSlug":"m","reward":"1.10","status":"REWARD_STATUS_PENDING"}"""))
        val (total, detail) = RewardFarm.earningsSummary(rows)!!
        assertEquals(1.36, total, 1e-9)
        assertTrue(detail.contains("paid") && detail.contains("pending"), detail)
        assertNull(RewardFarm.earningsSummary(rows + parseObject("""{"amount":5}""")))
    }

    // ---------------------------------------------------------------- 25. wall-clock scheduling and wake
    private class Counter(override val id: String, private val every: Duration) : Strategy {
        val passes = AtomicInteger()
        override val name = id; override val venue = Venue.KALSHI; override val summary = ""
        override val evidence = Evidence(EvidenceLevel.UNPROVEN, "", emptyList(), "")
        override val params = emptyList<com.marketbot.core.strategy.Param>()
        override fun nextDelay(ctx: StrategyContext) = every
        override suspend fun runOnce(ctx: StrategyContext): RunReport { passes.incrementAndGet(); return RunReport("ok") }
        override suspend fun liveRequirements(ctx: StrategyContext) = emptyList<Requirement>()
        override suspend fun preflight(ctx: StrategyContext) = PreflightResult(true, emptyList(), "")
        override suspend fun stats(ctx: StrategyContext) = emptyList<Stat>()
    }

    @Test fun `an hourly strategy runs when the wall clock says so, woken by a poke, and does not hold the CPU`() = runBlocking {
        val stores = inMemoryStores()
        val clock = MutableClock(Instant.parse("2026-10-27T20:00:00Z"))
        val hourly = Counter("hourly", Duration.ofHours(1))
        val engine = Engine(stores, clock, strategies = listOf(hourly), clientFactory = { _ -> fake.client(false) to PolymarketClient(null) })
        stores.state.put("strategy.hourly.mode", "PAPER")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine.start(scope)
        withTimeout(5_000) { while (hourly.passes.get() < 1) delay(20) }
        delay(100)
        assertEquals(false, engine.needsContinuousWake(), "an hourly strategy is woken by an alarm, not a wake lock")
        assertEquals(Instant.parse("2026-10-27T21:00:00Z"), engine.nextWakeAt())
        clock.advanceSeconds(3600)          // the phone slept for an hour; the alarm fires
        engine.poke()
        withTimeout(5_000) { while (hourly.passes.get() < 2) delay(20) }
        scope.cancel()
    }

    @Test fun `a fast strategy holds the CPU`() = runBlocking {
        val stores = inMemoryStores()
        val fast = Counter("fast", Duration.ofSeconds(2))
        val engine = Engine(stores, MutableClock(Instant.parse("2026-10-27T20:00:00Z")), strategies = listOf(fast),
            clientFactory = { _ -> fake.client(false) to PolymarketClient(null) })
        stores.state.put("strategy.fast.mode", "PAPER")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine.start(scope)
        withTimeout(5_000) { while (fast.passes.get() < 1) delay(20) }
        delay(100)
        assertTrue(engine.needsContinuousWake())
        assertTrue(engine.running.value)
        scope.cancel()
    }
}
