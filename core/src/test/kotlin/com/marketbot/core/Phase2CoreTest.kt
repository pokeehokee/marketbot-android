package com.marketbot.core

import com.marketbot.core.btc.BtcIndex
import com.marketbot.core.net.NetStats
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.store.inMemoryStores
import com.marketbot.core.strategies.longshot.StageGates
import com.marketbot.core.strategies.longshot.StageGates.Verdict
import com.marketbot.core.strategies.longshot.WeatherLongshot
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.strategy.brakeZone
import com.marketbot.core.util.MutableClock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Engine-side P1 review items. */
class Phase2CoreTest {
    private val fake = FakeKalshi()
    @AfterTest fun close() { fake.close(); NetStats.resetForTest() }

    // ---------------------------------------------------------------- clock skew
    @Test fun `server Date header measures skew and corrects only beyond the header's precision`() {
        val local = 1_800_000_000_000L
        fun date(ms: Long) = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC))
        NetStats.record("a.example", 100, date(local), local - 50, local + 50)
        assertEquals(0, NetStats.correctionMs("a.example"), "within a second is noise")
        NetStats.record("b.example", 100, date(local + 30_000), local - 50, local + 50)
        val c = NetStats.correctionMs("b.example")
        assertTrue(c in 29_500..31_000, "30 s fast server: $c")
        assertEquals(c, NetStats.worstSkewMs())
        assertEquals(0, NetStats.correctionMs("never.seen"))
        assertEquals(1, NetStats.today()["b.example"]!!.requests)
    }

    @Test fun `a rejected signature names the clock when the clock is the cause`() {
        val local = 1_800_000_000_000L
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(local + 40_000), ZoneOffset.UTC))
        NetStats.record("api.example", 1, date, local, local)
        val e = com.marketbot.core.net.JsonHttp.errorOf(401, """{"code":"unauthorized"}""", okhttp3.HttpUrl.Builder().scheme("https").host("api.example").build())
        assertTrue("clock is off by 40 s" in e.message!!, e.message)
        val other = com.marketbot.core.net.JsonHttp.errorOf(401, "{}", okhttp3.HttpUrl.Builder().scheme("https").host("fine.example").build())
        assertTrue("clock" !in other.message!!)
    }

    // ---------------------------------------------------------------- subaccount, demo, transfer
    @Test fun `a subaccount is named on portfolio reads and on every order`() = runTest {
        val k = fake.client(true, subaccount = 3)
        k.balance()
        k.placeOrder("KXHIGHNY-26OCT28-B70.5", "ask", 5, 0.07, 0.01, 0)
        val bal = fake.requests.first { it.requestUrl!!.encodedPath.endsWith("/portfolio/balance") }
        assertEquals("3", bal.requestUrl!!.queryParameter("subaccount"))
        assertEquals("3", fake.bodies.single()["subaccount"].toString())
    }

    @Test fun `the primary account sends no subaccount field`() = runTest {
        val k = fake.client(true)
        k.balance(); k.placeOrder("T", "ask", 1, 0.07, 0.01, 0)
        assertNull(fake.requests.first().requestUrl!!.queryParameter("subaccount"))
        assertNull(fake.bodies.single()["subaccount"])
    }

    @Test fun `demo is recognised from the base url`() {
        assertTrue(com.marketbot.core.kalshi.KalshiClient(null, com.marketbot.core.kalshi.KALSHI_DEMO_BASE).demo)
        assertTrue(!com.marketbot.core.kalshi.KalshiClient(null).demo)
    }

    @Test fun `a shard transfer is sent in centicents between event_contract shards`() = runTest {
        val id = fake.client(true).transferBetweenShards(12.34, 0, 2)
        assertEquals("tr-1", id)
        val b = fake.bodies.single()
        assertEquals("123400", b["amount"].toString())
        assertEquals("0", b["source_exchange_shard"].toString())
        assertEquals("2", b["destination_exchange_shard"].toString())
        assertEquals("\"event_contract\"", b["source"].toString())
        assertTrue(b["client_transfer_id"] != null)
    }

    // ---------------------------------------------------------------- brake zone
    @Test fun `brake zone falls back to the phone's zone when blank or invalid`() {
        assertEquals(ZoneId.of("America/Chicago"), brakeZone("America/Chicago"))
        assertEquals(ZoneId.systemDefault(), brakeZone(""))
        assertEquals(ZoneId.systemDefault(), brakeZone("Mars/Olympus"))
    }

    // ---------------------------------------------------------------- stage gates and kills
    private val now = Instant.parse("2026-10-28T12:00:00Z")
    private fun row(i: Int, filled: Double?, result: String?, mid: Double = 0.10, daysAgo: Long = 1) = QuoteRow(
        strategyId = "kalshi_weather_longshot", mode = Mode.LIVE, venue = "kalshi", ticker = "T$i", action = "sell_yes", price = mid,
        contracts = 5.0, placedAt = now.minusSeconds(daysAgo * 86_400 + 3_600), filledContracts = filled,
        resolvedAt = if (result != null) now.minusSeconds(daysAgo * 86_400) else null,
        cancelledAt = if (result == null && filled != null) now.minusSeconds(daysAgo * 86_400) else null,
        result = result, hypothetical = if (result != null) (filled ?: 0.0) <= 0 else null,
        extra = buildJsonObject { put("yes_bid", JsonPrimitive(mid - 0.01)); put("yes_ask", JsonPrimitive(mid + 0.01)) })

    @Test fun `gates are insufficient on a thin record`() {
        val rows = (1..20).map { row(it, 5.0, "no") }
        val g = StageGates.gates(rows, now.minusSeconds(30 * 86_400), emptyList(), now)
        assertEquals(listOf(Verdict.INSUFFICIENT, Verdict.INSUFFICIENT, Verdict.PASS), g.map { it.verdict })
    }

    @Test fun `gates pass on a good record and fail on a losing one`() {
        // 400 filled at a 0.10 mid: 40 implied losses.
        val good = (1..400).map { row(it, 5.0, if (it <= 30) "yes" else "no") }
        assertTrue(StageGates.gates(good, now.minusSeconds(30 * 86_400), emptyList(), now).all { it.verdict == Verdict.PASS })
        val bad = (1..400).map { row(it, 5.0, if (it <= 38) "yes" else "no") }
        assertEquals(Verdict.FAIL, StageGates.gates(bad, now.minusSeconds(30 * 86_400), emptyList(), now)[1].verdict, "0.95 > 0.90")
        val trips = listOf(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 27))
        assertEquals(Verdict.FAIL, StageGates.gates(good, now.minusSeconds(30 * 86_400), trips, now)[2].verdict)
    }

    @Test fun `kill fires when the bias is gone and when fills dry up`() {
        val gone = (1..300).map { row(it, 5.0, if (it <= 31) "yes" else "no") }   // 31 vs 30 implied
        assertEquals(Verdict.KILL, StageGates.kills(gone, now)[0].verdict)
        val starved = (1..50).map { row(it, if (it <= 15) 5.0 else 0.0, if (it <= 15) "no" else null) }
        assertEquals(Verdict.KILL, StageGates.kills(starved, now)[1].verdict, "30% fill over 50")
        val old = (1..300).map { row(it, 5.0, if (it <= 31) "yes" else "no", daysAgo = 90) }
        assertEquals(Verdict.INSUFFICIENT, StageGates.kills(old, now)[0].verdict, "outside 60 days")
    }

    @Test fun `a firing kill criterion stops live quoting and notifies once a day`() = runTest {
        fake.markets["KXHIGHNY"] = listOf(FakeKalshi.market("KXHIGHNY-26OCT29-B70.5", 0.06, 0.07, "2026-10-30T05:00:00Z"))
        val stores = inMemoryStores()
        val clock = MutableClock(now)
        (1..300).forEach { stores.ledger.insert(row(it, 5.0, if (it <= 31) "yes" else "no")) }
        stores.state.put("kalshi_weather_longshot.live.settlements_read_at", now.toString())
        val s = WeatherLongshot()
        val notes = mutableListOf<String>()
        val ctx = StrategyContext(s.id, Mode.LIVE, StrategyConfig(s.params), stores, clock, fake.client(true, clock), PolymarketClient(null),
            BtcIndex(emptyMap(), { emptyList() }), liveArmed = true, notify = { t, _ -> notes += t })
        val r1 = s.runOnce(ctx); s.runOnce(ctx)
        assertEquals("kill criterion", r1.summary)
        assertTrue(fake.bodies.isEmpty(), "no order placed")
        assertEquals(1, notes.size)
    }

    // ---------------------------------------------------------------- offline
    @Test fun `offline, the engine waits instead of running passes, and says so once`() = kotlinx.coroutines.runBlocking {
        val stores = inMemoryStores()
        var online = false
        val engine = com.marketbot.core.engine.Engine(stores, MutableClock(now), clientFactory = { _ -> fake.client(true) to PolymarketClient(null) },
            online = { online })
        val s = engine.strategies.first { it is WeatherLongshot }
        stores.state.put("strategy.${s.id}.mode", "PAPER")
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        try {
            engine.start(scope)
            kotlinx.coroutines.delay(800)
            val ev = stores.events.recent(100, s.id)
            assertEquals(1, ev.count { it.kind == "offline" })
            assertTrue(fake.requests.none { it.requestUrl!!.encodedPath.endsWith("/markets") }, "no market reads while offline")
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel() }
    }

    @Test fun `cancelling one live order by hand keeps what had filled`() = runTest {
        val stores = inMemoryStores()
        val engine = com.marketbot.core.engine.Engine(stores, MutableClock(now), clientFactory = { _ -> fake.client(true) to PolymarketClient(null) },
            fillStreamFactory = null)
        val id = stores.ledger.insert(QuoteRow(strategyId = "kalshi_weather_longshot", mode = Mode.LIVE, venue = "kalshi", ticker = "KXHIGHNY-26OCT29-B70.5",
            action = "sell_yes", price = 0.07, contracts = 5.0, placedAt = now, orderId = "o5"))
        fake.orders["o5"] = """{"order":{"order_id":"o5","status":"resting","fill_count_fp":"3.00"}}"""
        val msg = engine.cancelOne("kalshi_weather_longshot", id)
        assertTrue("cancelled" in msg, msg)
        val q = stores.ledger.byId(id)!!
        assertEquals(3.0, q.filledContracts); assertTrue(q.cancelledAt != null)
        assertEquals("KXHIGHNY-26OCT29-B70.5", fake.deletes().single().requestUrl!!.queryParameter("market_ticker"))
        assertTrue("no longer resting" in engine.cancelOne("kalshi_weather_longshot", id))
    }
}
