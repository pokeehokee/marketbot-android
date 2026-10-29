package com.marketbot.core

import com.marketbot.core.btc.BtcIndex
import com.marketbot.core.contract.Vocabulary
import com.marketbot.core.net.JsonHttp
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.store.inMemoryStores
import com.marketbot.core.strategies.longshot.CheapSide
import com.marketbot.core.strategies.longshot.KalshiCommodityLongshot
import com.marketbot.core.strategies.longshot.PolymarketTemperatureLongshot
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.strategy.StrategyRegistry
import com.marketbot.core.util.MutableClock
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The two paper strategies built from the 2026-10-29 pool search. */
class SearchCandidatesTest {
    private val fake = FakeKalshi()
    private val pm = MockWebServer()
    @AfterTest fun close() { fake.close(); runCatching { pm.shutdown() } }
    private val t0 = Instant.parse("2026-10-30T19:00:00Z")

    @Test fun `the cheap side is sold at the maker price, by the search's spread rule`() {
        assertEquals(CheapSideExpect("sell_yes", 0.05), CheapSide.pick(0.04, 0.05, 0.02, 0.25)?.let { CheapSideExpect(it.action, it.price) })
        assertEquals(CheapSideExpect("buy_yes", 0.95), CheapSide.pick(0.95, 0.96, 0.02, 0.25)?.let { CheapSideExpect(it.action, it.price) }, "NO is cheap: rest a YES bid")
        assertNull(CheapSide.pick(0.01, 0.12, 0.02, 0.25), "spread 11c on a 12c ask: too wide")
        assertNull(CheapSide.pick(0.40, 0.45, 0.02, 0.25), "neither side under 25c")
        assertNull(CheapSide.pick(0.005, 0.01, 0.02, 0.25), "below the minimum price")
        assertNull(CheapSide.pick(null, 0.05, 0.02, 0.25), "no bid: no book")
        assertEquals(0.95, CheapSide.lock("sell_yes", 0.05), 1e-9); assertEquals(0.95, CheapSide.lock("buy_yes", 0.95), 1e-9)
        assertEquals(-0.95, CheapSide.pnl("sell_yes", 0.05, "yes"), 1e-9); assertEquals(0.05, CheapSide.pnl("buy_yes", 0.95, "yes"), 1e-9)
    }
    private data class CheapSideExpect(val action: String, val price: Double)

    @Test fun `temperature slugs parse to city and kind, and the monthly ones are left out`() {
        assertEquals("nyc" to "HIGH", Vocabulary.pmTemperature("tc-temp-nychigh-2026-10-29-gte69lt70f"))
        assertEquals("mdw" to "LOW", Vocabulary.pmTemperature("tc-temp-mdwlow-2026-10-30-lt50f"))
        assertNull(Vocabulary.pmTemperature("tc-temp-sjc-dec-lt40"))
    }

    @Test fun `both are paper only, with variants, and owned markets are claimed for the kill sweep`() {
        for (id in listOf(PolymarketTemperatureLongshot.ID, KalshiCommodityLongshot.ID)) {
            val s = StrategyRegistry.byId(id)!!
            assertFalse(Mode.LIVE in s.supportedModes, "$id has no live order path")
            assertEquals(2, StrategyRegistry.variantsOf(id).size)
        }
        assertTrue(KalshiCommodityLongshot().ownsMarket("kalshi", "KXGOLDD-26OCT30H1700-T4000"))
        assertFalse(KalshiCommodityLongshot().ownsMarket("kalshi", "KXHIGHNY-26OCT30-B70.5"))
        assertTrue(PolymarketTemperatureLongshot().ownsMarket("polymarket", "tc-temp-nychigh-2026-10-29-lt67f"))
    }

    private fun ctx(s: com.marketbot.core.strategy.Strategy, stores: com.marketbot.core.store.Stores, clock: MutableClock, pmc: PolymarketClient = PolymarketClient(null)) =
        StrategyContext(s.id, Mode.PAPER, StrategyConfig(s.params), stores, clock, fake.client(false), pmc,
            BtcIndex(venues = emptyMap(), candles = { emptyList() }), liveArmed = false)

    @Test fun `commodities offer inside the hour before close, fills from the tape, grades at settlement`() = runBlocking {
        val s = KalshiCommodityLongshot()
        val stores = inMemoryStores(); val clock = MutableClock(t0)
        val inHour = t0.plusSeconds(60 * 60).toString(); val later = t0.plusSeconds(5 * 3600).toString()
        fake.markets["KXGOLDD"] = listOf(
            FakeKalshi.market("KXGOLDD-26OCT30H1600-T4000", 0.04, 0.05, inHour, askSize = 10.0),    // YES cheap: sell YES at 5c
            FakeKalshi.market("KXGOLDD-26OCT30H1600-T3900", 0.95, 0.96, inHour),                     // NO cheap: bid 95c for YES
            FakeKalshi.market("KXGOLDD-26OCT30H2000-T4000", 0.04, 0.05, later),                      // outside the window
            FakeKalshi.market("KXGOLDD-26OCT30H1600-T4100", 0.40, 0.45, inHour))                     // no cheap side
        val r = s.runOnce(ctx(s, stores, clock))
        val rows = stores.ledger.open(s.id, Mode.PAPER).sortedBy { it.ticker }
        assertEquals(listOf("buy_yes" to 0.95, "sell_yes" to 0.05), rows.map { it.action to it.price }, r.funnel.toString())
        assertTrue(r.funnel.any { "window" in it.label && it.dropped.any { d -> "H2000" in d } })

        // A YES buyer lifts 20 at 5c: 10 were ahead of us, so our 5 fill.
        fake.trades = """{"trades":[{"trade_id":"t1","ticker":"KXGOLDD-26OCT30H1600-T4000","taker_side":"yes","yes_price_dollars":"0.0500",
            "no_price_dollars":"0.9500","count_fp":"20.00","created_time":"${t0.plusSeconds(600)}"}],"cursor":""}"""
        clock.advanceSeconds(900)
        s.runOnce(ctx(s, stores, clock))
        val sold = stores.ledger.open(s.id, Mode.PAPER).first { it.action == "sell_yes" }
        assertEquals(5.0, sold.filledContracts)

        // Close passes: both settle NO.
        clock.advanceSeconds(3600)
        fake.markets["KXGOLDD"] = emptyList()
        fake.marketByTicker["KXGOLDD-26OCT30H1600-T4000"] = FakeKalshi.market("KXGOLDD-26OCT30H1600-T4000", 0.0, 1.0, inHour, status = "finalized", result = "no")
        fake.marketByTicker["KXGOLDD-26OCT30H1600-T3900"] = FakeKalshi.market("KXGOLDD-26OCT30H1600-T3900", 0.0, 1.0, inHour, status = "finalized", result = "no")
        s.runOnce(ctx(s, stores, clock))
        val all = stores.ledger.all(s.id, Mode.PAPER).associateBy { it.action }
        assertEquals(0.25, all.getValue("sell_yes").pnl!!, 1e-9, "5 x 5c kept")
        assertEquals(false, all.getValue("sell_yes").hypothetical)
        assertEquals(true, all.getValue("buy_yes").hypothetical, "never filled: graded, never summed as realised")
        assertEquals(-4.75, all.getValue("buy_yes").pnl!!, 1e-9)
    }

    @Test fun `temperature offers an hour before the measured day, fills from book snapshots, grades from the settlement`() = runBlocking {
        val slug = "tc-temp-nychigh-2026-10-31-gte69lt70f"
        val start = t0.plusSeconds(3600)
        var offers = """[{"px":{"value":"0.0500"},"qty":"10"}]"""
        var settlement: String? = null
        pm.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.requestUrl!!.encodedPath
                return when {
                    p == "/v1/markets" -> MockResponse().setBody("""{"markets":[{"slug":"$slug","active":true,"closed":false,"gameStartTime":"$start",
                        "endDate":"${start.plusSeconds(28 * 3600)}","orderPriceMinTickSize":0.01}]}""")
                    p == "/v1/markets/$slug/book" -> MockResponse().setBody("""{"marketData":{"state":"MARKET_STATE_OPEN","bids":[{"px":{"value":"0.0400"},"qty":"30"}],"offers":$offers}}""")
                    p == "/v1/markets/$slug/settlement" -> settlement?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404).setBody("{}")
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }
        pm.start()
        val base = pm.url("").toString().trimEnd('/')
        val pmc = PolymarketClient(null, gateway = JsonHttp(base, sleep = {}), api = JsonHttp(base, sleep = {}))
        val s = PolymarketTemperatureLongshot()
        val stores = inMemoryStores(); val clock = MutableClock(t0)
        s.runOnce(ctx(s, stores, clock, pmc))
        val q = stores.ledger.open(s.id, Mode.PAPER).single()
        assertEquals("sell_yes" to 0.05, q.action to q.price); assertEquals(10.0, q.queueAhead)
        assertEquals("polymarket", q.venue)

        // The ask level shrinks from 10 to 0 and then back to 12: the 10 ahead traded, then ours.
        offers = """[{"px":{"value":"0.0600"},"qty":"5"}]"""
        clock.advanceSeconds(900); s.runOnce(ctx(s, stores, clock, pmc))
        assertTrue((stores.ledger.byId(q.id)!!.filledContracts ?: 0.0) == 0.0, "the 10 ahead of us went first")

        // The day starts: unfilled remainder withdrawn; the settlement arrives later.
        clock.advanceSeconds(3600); s.runOnce(ctx(s, stores, clock, pmc))
        assertTrue(stores.ledger.byId(q.id)!!.cancelledAt != null)
        clock.advanceSeconds(30 * 3600)
        settlement = """{"settlement":"0"}"""
        s.runOnce(ctx(s, stores, clock, pmc))
        val done = stores.ledger.byId(q.id)!!
        assertEquals("no", done.result); assertEquals(true, done.hypothetical)
    }
}
