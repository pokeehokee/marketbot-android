package com.marketbot.core

import com.marketbot.core.polymarket.Level
import com.marketbot.core.polymarket.PmBook
import com.marketbot.core.polymarket.PmTrade
import com.marketbot.core.strategies.rewards.FarmCandidate
import com.marketbot.core.strategies.rewards.FarmRules
import com.marketbot.core.strategies.rewards.FarmScanner
import com.marketbot.core.strategies.rewards.PaperFarmBroker
import com.marketbot.core.strategies.rewards.PaperFarmState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reward-farm paper fidelity against pmfarm itself: trade prints, queue position, the top-of-book screen. */
class FarmFidelityTest {
    private fun near(a: Double, b: Double, what: String) = assertTrue(abs(a - b) < 1e-9, "$what: $a vs $b")

    @Test fun `paper queue and fills match pmfarm's PaperBroker step for step`() = runTest {
        val scenarios = golden("pm_paper_queue").list()
        var steps = 0
        for ((si, sc) in scenarios.withIndex()) {
            var ts = 0L
            val br = PaperFarmBroker(PaperFarmState(500.0, startingCash = 500.0), clockMs = { ts })
            val ids = mutableListOf<String>()
            for ((i, st0) in sc.o()["steps"].list().withIndex()) {
                val st = st0.o(); val at = "scenario $si step $i ${st["op"].s}"
                val fills = when (st["op"].s) {
                    "place" -> { ts = (st["ts"].dd * 1000).toLong()
                        ids += br.place("m1", st["side"].s!!, st["price"].dd, st["qty"].dd, st["intent"].s!!, null, st["ahead"].dd)!!; emptyList() }
                    "book" -> {
                        fun lv(k: String) = (st[k] as JsonArray).map { (it as JsonArray).let { p -> Level(p[0].jsonPrimitive.double, p[1].jsonPrimitive.double) } }
                        br.onBook(PmBook("m1", lv("bids"), lv("asks"), "MARKET_STATE_OPEN", Instant.EPOCH))
                    }
                    else -> br.onTrade(PmTrade("m1", st["price"].dd, st["qty"].dd, st["taker"].s!!))
                }
                val want = (st["fills"] as? JsonArray).orEmpty().map { it as JsonArray }
                assertEquals(want.size, fills.size, "$at fills")
                for ((f, w) in fills.zip(want)) { near(f.qty, w[1].jsonPrimitive.double, "$at qty"); assertEquals(w[2].jsonPrimitive.content, f.why, at) }
                val orders = st["orders"] as JsonObject
                assertEquals(orders.keys.map { it.toInt() }.toSet(), br.s.orders.keys.map { ids.indexOf(it) }.toSet(), "$at live orders")
                for ((k, v) in orders) {
                    val o = br.s.orders.getValue(ids[k.toInt()]); val a = v as JsonArray
                    near(o.qty, a[0].jsonPrimitive.double, "$at qty $k"); near(o.ahead, a[1].jsonPrimitive.double, "$at ahead $k")
                }
                near(br.s.cash, st["cash"].dd, "$at cash")
                near(br.s.positions["m1"]?.qty ?: 0.0, st["position"].dd, "$at position")
                steps++
            }
        }
        assertEquals(1800, steps)
    }

    @Test fun `with prints, book shrinkage that no print explains is cancels and fills nothing`() = runTest {
        val br = PaperFarmBroker(PaperFarmState(500.0, startingCash = 500.0))
        br.place("m", "bid", 0.40, 10.0, "BUY_LONG", null, 30.0)
        br.onBook(PmBook("m", listOf(Level(0.40, 100.0)), listOf(Level(0.45, 50.0)), "", Instant.EPOCH))   // 70 joined behind us
        br.onTrade(PmTrade("m", 0.45, 1.0, "ORDER_SIDE_BUY"))   // a print elsewhere: the market is fed
        val f = br.onBook(PmBook("m", listOf(Level(0.40, 70.0)), listOf(Level(0.45, 50.0)), "", Instant.EPOCH))
        assertTrue(f.isEmpty(), "30 cancelled at our level is not a fill")
        assertEquals(21.0, br.s.orders.values.single().ahead, 1e-9, "30 cancels spread pro rata: 30 x 30/100 of them were ahead")
    }

    @Test fun `trade prints parse from the SDK's message shape`() {
        val t = PmTrade.parse(com.marketbot.core.util.parseObject("""{"trade":{"marketSlug":"s","price":{"value":"0.42"},"quantity":{"value":"7"},"taker":{"side":"ORDER_SIDE_SELL"}}}"""))!!
        assertEquals(PmTrade("s", 0.42, 7.0, "ORDER_SIDE_SELL"), t)
        assertNull(PmTrade.parse(com.marketbot.core.util.parseObject("""{"heartbeat":{}}""")))
        val sub = PmTrade.subscribe("r1", setOf("b", "a")).toString()
        assertEquals("""{"subscribe":{"requestId":"r1","subscriptionType":"SUBSCRIPTION_TYPE_TRADE","marketSlugs":["a","b"]}}""", sub)
    }

    @Test fun `top-of-book screen keeps tight two-sided books in band, three legs per allowed event leg`() {
        val r = FarmRules()
        fun c(slug: String, ev: String) = FarmCandidate(slug, ev, null, emptyList())
        val cands = listOf(c("ok", "e1"), c("wide", "e1"), c("onesided", "e1"), c("closed", "e1"), c("cheap", "e2")) +
            (1..10).map { c("alt$it", "e3") }
        val bbo = mapOf("ok" to (0.40 to 0.43), "wide" to (0.30 to 0.45), "onesided" to (0.40 to null), "closed" to (0.40 to 0.42),
            "cheap" to (0.005 to 0.015)) + (1..10).associate { "alt$it" to (0.50 to 0.52) }
        val out = FarmScanner.screen(cands, bbo, setOf("closed"), r).map { it.slug }
        assertEquals(listOf("ok") + (1..r.maxPerEvent * 3).map { "alt$it" }, out)
    }
}
