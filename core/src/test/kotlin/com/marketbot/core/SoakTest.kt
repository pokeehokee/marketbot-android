package com.marketbot.core

import com.marketbot.core.btc.BtcIndex
import com.marketbot.core.btc.VenueQuote
import com.marketbot.core.net.KalshiSigner
import com.marketbot.core.polymarket.PmTrade
import com.marketbot.core.polymarket.PmTradeStream
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.store.inMemoryStores
import com.marketbot.core.strategies.deepitm.DeepItmRule
import com.marketbot.core.strategies.deepitm.KalshiBtcDeepItm
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.util.Diagnostics
import com.marketbot.core.util.MutableClock
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Long runs, compressed. Build 116 ran out of memory after 13 hours on a phone
 * with every unit test green, because nothing ran long enough to see growth.
 * These run a strategy for hours of simulated time and assert that what it keeps
 * between passes stops growing.
 */
class SoakTest {
    private val fake = FakeKalshi()
    @AfterTest fun close() = fake.close()

    private fun usedMb(): Long {
        repeat(3) { System.gc(); Thread.sleep(30) }
        val rt = Runtime.getRuntime(); return (rt.totalMemory() - rt.freeMemory()) / 1_048_576
    }

    private fun btc(t: String, strike: Double, yesBid: Double, noBid: Double, close: Instant) =
        """{"ticker":"$t","event_ticker":"${t.substringBeforeLast('-')}","status":"active","close_time":"$close",
           "strike_type":"greater","floor_strike":$strike,"yes_bid_dollars":"$yesBid","no_bid_dollars":"$noBid",
           "yes_bid_size_fp":"20.00","yes_ask_size_fp":"30.00","exchange_index":2,"price_ranges":[{"start":"0","end":"1","step":"0.01"}]}"""

    @Test fun `deep-ITM in paper for four simulated hours keeps its caches bounded`() = runTest(timeout = kotlin.time.Duration.parse("5m")) {
        val s = KalshiBtcDeepItm()
        val stores = inMemoryStores()
        val clock = MutableClock(Instant.parse("2026-10-27T18:20:00Z"))
        var t = 1_000.0; var px = 75_000.0
        val venues = mapOf("a" to VenueQuote { px }, "b" to VenueQuote { px + 2 }, "c" to VenueQuote { px - 2 })
        val candles = (0..16).map { i -> listOf(1_000.0 + 60 * i, 0.0, 0.0, 0.0, 75_000 * exp(0.0004 * sin(i.toDouble())), 1.0) }
        val ix = BtcIndex(venues, { candles }, nowS = { t })
        repeat(400) { i -> px = 75_000 * exp(0.00004 * sin(i / 3.0)); ix.pollOnce(); t += 1.0 }
        // A busy tape: 300 prints on every strike, all after the quotes are placed.
        fake.trades = """{"trades":[${(0 until 300).joinToString(",") { i ->
            """{"trade_id":"t$i","ticker":"X","created_time":"2026-10-27T23:59:${"%02d".format(i % 60)}Z","count_fp":"1.00","yes_price_dollars":"0.9400","taker_side":"no"}""" }}],"cursor":""}"""
        val client = fake.client(signed = false)
        val ctx = StrategyContext(s.id, Mode.PAPER, StrategyConfig(s.params), stores, clock, client, PolymarketClient(null), ix, liveArmed = false)

        var lastEvent = ""
        var halfway = 0L
        val steps = 4 * 120   // 30 s apart: four hours, crossing four hourly ladders
        for (i in 0 until steps) {
            val (event, close) = DeepItmRule.nextHourEvent(clock.now())
            if (event != lastEvent) {
                fake.markets["event:$event"] = listOf(
                    btc("$event-T74000", 74000.0, 0.94, 0.04, close), btc("$event-T73500", 73500.0, 0.95, 0.03, close),
                    btc("$event-T76000", 76000.0, 0.05, 0.93, close))
                lastEvent = event
            }
            runCatching { s.runOnce(ctx) }
            clock.advanceSeconds(30); repeat(30) { px = 75_000 * exp(0.00004 * sin((t + it) / 3.0)); ix.pollOnce(); t += 1.0 }
            if (i == steps / 2) halfway = usedMb()
        }
        val end = usedMb()
        val sizes = Diagnostics.sizes().toMap()
        assertTrue((sizes["Kalshi BTC paper tapes cached"] ?: 0) <= 3 * 300 * 2, "tapes held: ${sizes["Kalshi BTC paper tapes cached"]}")
        assertTrue((sizes["BTC index samples"] ?: 0) <= 2_000, "index samples: ${sizes["BTC index samples"]}")
        // The in-memory test ledger grows by design (Room holds it on the phone); everything else must be flat.
        assertTrue(end - halfway < 48, "heap grew ${end - halfway} MB over the second two hours ($halfway -> $end MB)")
    }

    @Test fun `a day of trade prints across a changing set of markets stays bounded`() {
        val signer = KalshiSigner("kid", golden("signatures").o()["rsa_pss"].o()["pkcs1_pem"].s!!)
        val st = PmTradeStream(signer, "ws://127.0.0.1:1/never", maxPerMarket = 500)
        // 15-s ticks for a day: the subscription drifts by a market every few minutes; most markets are never drained.
        for (tick in 0 until 5_760) {
            val window = (tick / 20 until tick / 20 + 40).map { "m$it" }.toSet()
            st.setMarkets(window)
            for (m in window) repeat(3) { st.offer(PmTrade(m, 0.5, 1.0, "ORDER_SIDE_BUY")) }
            if (tick % 2 == 0) window.take(5).forEach { st.drain(it) }
        }
        assertTrue(st.pending <= 40 * 500, "prints waiting: ${st.pending}")
        st.stop()
    }
}
