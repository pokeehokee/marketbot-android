package com.marketbot.core

import com.marketbot.core.engine.Engine
import com.marketbot.core.kalshi.FillLimit
import com.marketbot.core.kalshi.FillLimitTripped
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.store.inMemoryStores
import com.marketbot.core.strategy.Mode
import com.marketbot.core.util.MutableClock
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guards that work at the exchange: the fill limit, the kill sweep and order reconciliation. */
class ExchangeSafetyTest {
    private val fake = FakeKalshi()
    @AfterTest fun close() = fake.close()
    private val now = Instant.parse("2026-10-29T12:00:00Z")

    @Test fun `every order joins its shard's fill-limit group, created once and resized when the setting changes`() = runTest {
        val stores = inMemoryStores()
        var limit = 20
        val k = fake.client(signed = true)
        k.fillLimit = FillLimit(stores.state, { limit })
        k.placeOrder("KXHIGHNY-26OCT30-B70.5", "ask", 5, 0.07, 0.01, 0)
        k.placeOrder("KXHIGHNY-26OCT30-B72.5", "ask", 5, 0.07, 0.01, 0)
        k.placeOrder("KXBTCD-26OCT2917-T74000", "bid", 5, 0.95, 0.01, 2)
        assertEquals(mapOf("group-1" to (20 to 0), "group-2" to (20 to 2)), fake.groups.toMap(), "one group per shard")
        assertEquals(listOf("\"group-1\"", "\"group-1\"", "\"group-2\""), fake.bodies.map { it["order_group_id"].toString() })
        limit = 30
        k.placeOrder("KXHIGHNY-26OCT30-B74.5", "ask", 5, 0.07, 0.01, 0)
        assertTrue(fake.groupActions.any { it.startsWith("group-1/limit") }, fake.groupActions.toString())
        limit = 0
        k.placeOrder("KXHIGHNY-26OCT30-B76.5", "ask", 5, 0.07, 0.01, 0)
        assertNull(fake.bodies.last()["order_group_id"], "0 turns the limit off")
    }

    @Test fun `a tripped group stops the app placing on that shard until it is reset, and is never routed around`() = runTest {
        val stores = inMemoryStores()
        val trips = mutableListOf<Int>()
        val k = fake.client(signed = true)
        val fl = FillLimit(stores.state, { 20 }, onTrip = { trips += it })
        k.fillLimit = fl
        k.placeOrder("KXHIGHNY-26OCT30-B70.5", "ask", 5, 0.07, 0.01, 0)
        fake.groupTripped = true
        assertFailsWith<FillLimitTripped> { k.placeOrder("KXHIGHNY-26OCT30-B72.5", "ask", 5, 0.07, 0.01, 0) }
        assertEquals(listOf(0), trips)
        val posts = fake.posts().size
        assertFailsWith<FillLimitTripped> { k.placeOrder("KXHIGHNY-26OCT30-B74.5", "ask", 5, 0.07, 0.01, 0) }
        assertEquals(posts, fake.posts().size, "refused locally: no order without the group")
        fake.groupTripped = false
        fl.reset(k, 0)
        assertTrue(fake.groupActions.any { it.startsWith("group-1/reset") })
        assertNotNull(k.placeOrder("KXHIGHNY-26OCT30-B76.5", "ask", 5, 0.07, 0.01, 0).orderId)
    }

    private fun engine(stores: com.marketbot.core.store.Stores) =
        Engine(stores, MutableClock(now), clientFactory = { _ -> fake.client(true) to PolymarketClient(null) }, fillStreamFactory = null)

    @Test fun `the kill switch cancels what is resting at Kalshi in the app's markets, including orders no ledger knows`() = kotlinx.coroutines.runBlocking {
        val stores = inMemoryStores()
        stores.state.put(Engine.FILL_LIMIT_KEY, "20")
        val e = engine(stores)
        e.kalshiClient.placeOrder("KXHIGHNY-26OCT30-B70.5", "ask", 5, 0.07, 0.01, 0)   // creates the shard-0 group
        fake.restingOrders = """{"orders":[
            {"order_id":"lost-1","ticker":"KXHIGHNY-26OCT30-B70.5","status":"resting"},
            {"order_id":"mine-1","ticker":"KXHIGHTDAL-26OCT30-B90.5","status":"resting"},
            {"order_id":"hand-1","ticker":"KXFED-26DEC-T4.00","status":"resting"}],"cursor":""}"""
        stores.ledger.insert(QuoteRow(strategyId = "kalshi_weather_longshot", mode = Mode.LIVE, venue = "kalshi", ticker = "KXHIGHTDAL-26OCT30-B90.5",
            action = "sell_yes", price = 0.18, contracts = 5.0, placedAt = now, orderId = "mine-1"))
        e.setGlobalKill(true)
        val cancelled = fake.batchCancels.flatMap { b -> (b["orders"] as kotlinx.serialization.json.JsonArray).map { it.toString() } }
        assertTrue(cancelled.any { "lost-1" in it } && cancelled.any { "mine-1" in it }, cancelled.toString())
        assertTrue(cancelled.none { "hand-1" in it }, "a hand-placed order in another market is left alone")
        val r = e.lastSweep!!
        assertEquals(1, r.outside); assertEquals(listOf("Kalshi KXHIGHNY-26OCT30-B70.5"), r.unknown)
        assertTrue(fake.groupActions.any { it.startsWith("group-1/trigger") }, "Kalshi's own group is triggered too")
        e.setGlobalKill(false)
        assertTrue(fake.groupActions.any { it.startsWith("group-1/reset") }, "turning the kill switch off re-opens a group it tripped")
    }

    @Test fun `reconciliation closes ledger orders the exchange finished and reports orders it does not know`() = kotlinx.coroutines.runBlocking {
        val stores = inMemoryStores()
        val e = engine(stores)
        stores.state.put("strategy.kalshi_weather_longshot.mode", "LIVE")
        val gone = stores.ledger.insert(QuoteRow(strategyId = "kalshi_weather_longshot", mode = Mode.LIVE, venue = "kalshi", ticker = "KXHIGHNY-26OCT30-B70.5",
            action = "sell_yes", price = 0.07, contracts = 5.0, placedAt = now, orderId = "o-gone"))
        val done = stores.ledger.insert(QuoteRow(strategyId = "kalshi_weather_longshot", mode = Mode.LIVE, venue = "kalshi", ticker = "KXHIGHNY-26OCT30-B72.5",
            action = "sell_yes", price = 0.07, contracts = 5.0, placedAt = now, orderId = "o-done"))
        val still = stores.ledger.insert(QuoteRow(strategyId = "kalshi_weather_longshot", mode = Mode.LIVE, venue = "kalshi", ticker = "KXHIGHNY-26OCT30-B74.5",
            action = "sell_yes", price = 0.07, contracts = 5.0, placedAt = now, orderId = "o-still"))
        fake.orders["o-gone"] = """{"order":{"order_id":"o-gone","status":"canceled","fill_count_fp":"2.00","initial_count_fp":"5.00","remaining_count_fp":"0.00"}}"""
        fake.orders["o-done"] = """{"order":{"order_id":"o-done","status":"executed","fill_count_fp":"5.00"}}"""
        fake.restingOrders = """{"orders":[{"order_id":"o-still","ticker":"KXHIGHNY-26OCT30-B74.5","status":"resting"},
            {"order_id":"stranger","ticker":"KXHIGHCHI-26OCT30-B60.5","status":"resting"}],"cursor":""}"""
        val r = e.reconcileOrders()
        assertEquals(3, r.checked)
        val g = stores.ledger.byId(gone)!!; assertNotNull(g.cancelledAt); assertEquals(2.0, g.filledContracts)
        val d = stores.ledger.byId(done)!!; assertNull(d.cancelledAt); assertEquals(5.0, d.filledContracts)
        assertNull(stores.ledger.byId(still)!!.cancelledAt)
        assertEquals(listOf("Kalshi KXHIGHCHI-26OCT30-B60.5 (stranger)"), r.unknown)
        assertEquals(2, r.corrected.size)
    }
}
