package com.marketbot.core

import com.marketbot.core.btc.BtcIndex
import com.marketbot.core.btc.VenueQuote
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.provenance.Source
import com.marketbot.core.store.inMemoryStores
import com.marketbot.core.strategies.deepitm.DeepItmRule
import com.marketbot.core.strategies.deepitm.KalshiBtcDeepItm
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.util.MutableClock
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeepItmEngineTest {
    private val fake = FakeKalshi()
    private val stores = inMemoryStores()
    private val s = KalshiBtcDeepItm()
    // 20:20Z = 16:20 ET; the 21:00Z hour closes at 17:00 ET (not a skipped open). 2400 s to close.
    private val clock = MutableClock(Instant.parse("2026-10-27T20:20:00Z"))
    private var t = 1_000.0
    private var px = 75_000.0

    @AfterTest fun close() = fake.close()

    /** An index fed by fake venues; warmed up with 15 minutes of 1 Hz samples. */
    private suspend fun warmIndex(): BtcIndex {
        val venues = mapOf("a" to VenueQuote { px }, "b" to VenueQuote { px + 2 }, "c" to VenueQuote { px - 2 })
        val candles = (0..16).map { i -> listOf(1_000.0 + 60 * i, 0.0, 0.0, 0.0, 75_000 * exp(0.0004 * sin(i.toDouble())), 1.0) }
        val ix = BtcIndex(venues, { candles }, nowS = { t })
        repeat(400) { i -> px = 75_000 * exp(0.00004 * sin(i / 3.0)); ix.pollOnce(); t += 1.0 }
        return ix
    }

    private fun ctx(mode: Mode, ix: BtcIndex, armed: Boolean = false) = StrategyContext(s.id, mode, StrategyConfig(s.params), stores, clock,
        fake.client(mode == Mode.LIVE), PolymarketClient(null), ix, liveArmed = armed)

    private fun ladder(event: String) {
        // spot ~75,000; strikes far below are deep YES, far above deep NO.
        fake.markets["event:$event"] = listOf(
            btc("$event-T74000", 74000.0, yesBid = 0.94, noBid = 0.04),
            btc("$event-T73500", 73500.0, yesBid = 0.95, noBid = 0.03),
            btc("$event-T76000", 76000.0, yesBid = 0.05, noBid = 0.93),
            btc("$event-T75000", 75000.0, yesBid = 0.48, noBid = 0.50),   // at the money: never
        )
    }

    private fun btc(t: String, strike: Double, yesBid: Double, noBid: Double) =
        """{"ticker":"$t","event_ticker":"${t.substringBeforeLast('-')}","status":"active","close_time":"2026-10-27T21:00:00Z",
           "strike_type":"greater","floor_strike":$strike,"yes_bid_dollars":"$yesBid","no_bid_dollars":"$noBid",
           "yes_bid_size_fp":"20.00","yes_ask_size_fp":"30.00","exchange_index":2,"price_ranges":[{"start":"0","end":"1","step":"0.01"}]}"""

    @Test fun `paper step bids the deep strikes on both sides, at the best bid, from public data only`() = runTest {
        val ix = warmIndex()
        val (event, _) = DeepItmRule.nextHourEvent(clock.now())
        assertEquals("KXBTCD-26OCT2717", event)
        ladder(event)
        s.runOnce(ctx(Mode.PAPER, ix))
        val rows = stores.ledger.all(s.id, Mode.PAPER)
        assertEquals(setOf("$event-T74000" to "buy_yes", "$event-T73500" to "buy_yes", "$event-T76000" to "buy_no"),
            rows.map { it.ticker to it.action }.toSet())
        assertEquals(0.94, rows.first { it.ticker.endsWith("T74000") }.price)
        assertEquals(20.0, rows.first { it.ticker.endsWith("T74000") }.queueAhead, "YES bids queue behind yes_bid_size")
        assertEquals(30.0, rows.first { it.ticker.endsWith("T76000") }.queueAhead, "NO bids queue behind the YES ask size")
        assertTrue(fake.posts().isEmpty())
    }

    @Test fun `armed live step routes every order to the crypto shard`() = runTest {
        val ix = warmIndex()
        val (event, _) = DeepItmRule.nextHourEvent(clock.now())
        ladder(event)
        s.runOnce(ctx(Mode.LIVE, ix, armed = true))
        assertEquals(3, fake.bodies.size)
        assertTrue(fake.bodies.all { it["exchange_index"].toString() == "2" })
        val no = fake.bodies.first { it["ticker"].s!!.endsWith("T76000") }
        assertEquals("\"ask\"", no["side"].toString(), "buying NO is a YES ask")
        assertEquals("\"0.0700\"", no["price"].toString(), "NO bid 0.93 = YES ask 0.07")
    }

    @Test fun `a stale index cancels every standing quote`() = runTest {
        val ix = warmIndex()
        val (event, _) = DeepItmRule.nextHourEvent(clock.now())
        ladder(event)
        s.runOnce(ctx(Mode.PAPER, ix))
        assertTrue(stores.ledger.open(s.id, Mode.PAPER).any { it.resting })
        t += 30.0                     // no polls for 30 s
        s.runOnce(ctx(Mode.PAPER, ix))
        assertTrue(stores.ledger.open(s.id, Mode.PAPER).none { it.resting })
    }

    @Test fun `paper grading uses the market result field and labels it`() = runTest {
        val ix = warmIndex()
        val (event, _) = DeepItmRule.nextHourEvent(clock.now())
        ladder(event)
        s.runOnce(ctx(Mode.PAPER, ix))
        val q = stores.ledger.all(s.id, Mode.PAPER).first { it.ticker.endsWith("T74000") }
        stores.ledger.update(q.copy(filledContracts = 5.0, fillSource = "paper: test"))
        clock.advanceSeconds(3600)
        fake.marketByTicker[q.ticker] = """{"ticker":"${q.ticker}","status":"finalized","result":"yes"}"""
        // a fresh strategy instance forces the grade timer
        val s2 = KalshiBtcDeepItm()
        s2.runOnce(StrategyContext(s2.id, Mode.PAPER, StrategyConfig(s2.params), stores, clock, fake.client(false), PolymarketClient(null), ix, false))
        val g = stores.ledger.byId(q.id)!!
        assertEquals("yes", g.result)
        assertEquals(5 * (1 - 0.94), g.pnl!!, 1e-9)
        assertEquals(Source.PAPER_MODEL, g.pnlSource)
        assertTrue(g.pnlOrigin!!.contains("GET /markets/"))
    }

    /** A live row the order endpoint called filled, in a market that has closed. */
    private suspend fun stuckLiveRow(ticker: String, orderId: String) = com.marketbot.core.store.QuoteRow(strategyId = s.id, mode = Mode.LIVE,
        venue = "kalshi", ticker = ticker, action = "buy_yes", price = 0.94, contracts = 5.0, placedAt = clock.now().minusSeconds(7200),
        closeTime = clock.now().minusSeconds(3600), orderId = orderId, filledContracts = 5.0, fillSource = "Kalshi API order#fill_count_fp",
        extra = kotlinx.serialization.json.buildJsonObject { put("exchange_index", kotlinx.serialization.json.JsonPrimitive(2)) })
        .let { it.copy(id = stores.ledger.insert(it)) }

    @Test fun `closed positions awaiting settlement do not use up max_open`() = runTest {
        val ix = warmIndex()
        val (event, _) = DeepItmRule.nextHourEvent(clock.now())
        ladder(event)
        repeat(8) { stuckLiveRow("KXBTCD-26OCT2716-T7${it}000", "old-$it") }
        s.runOnce(ctx(Mode.LIVE, ix, armed = true))
        assertEquals(3, fake.bodies.size, "eight closed, unsettled rows must not block new orders")
    }

    @Test fun `a row with no fills and no settlement at the exchange is graded never filled`() = runTest {
        val ix = warmIndex()
        val q = stuckLiveRow("KXBTCD-26OCT2716-T74000", "old-1")
        clock.advanceSeconds(3 * 3600)
        fake.marketByTicker[q.ticker] = """{"ticker":"${q.ticker}","status":"finalized","result":"yes"}"""
        s.runOnce(ctx(Mode.LIVE, ix, armed = true))
        val g = stores.ledger.byId(q.id)!!
        assertEquals(0.0, g.filledContracts)
        assertEquals(0.0, g.pnl)
        assertTrue(g.hypothetical == true)
        assertEquals(Source.KALSHI_API, g.pnlSource)
        val asked = fake.requests.first { it.requestUrl!!.encodedPath.endsWith("/portfolio/fills") }.requestUrl!!
        assertEquals("old-1", asked.queryParameter("order_id"))
        assertEquals("2", asked.queryParameter("exchange_index"))
    }

    @Test fun `a row the exchange shows filled keeps waiting for its settlement`() = runTest {
        val ix = warmIndex()
        val q = stuckLiveRow("KXBTCD-26OCT2716-T74000", "old-1")
        clock.advanceSeconds(3 * 3600)
        fake.marketByTicker[q.ticker] = """{"ticker":"${q.ticker}","status":"finalized","result":"yes"}"""
        fake.fills = """{"fills":[{"order_id":"old-1","ticker":"${q.ticker}","count_fp":"5.00"}],"cursor":""}"""
        s.runOnce(ctx(Mode.LIVE, ix, armed = true))
        assertEquals(null, stores.ledger.byId(q.id)!!.resolvedAt)
    }

    @Test fun `a settlement past the first pages is found by ticker`() = runTest {
        val ix = warmIndex()
        val q = stuckLiveRow("KXBTCD-26OCT2716-T74000", "old-1")
        clock.advanceSeconds(3 * 3600)
        fake.marketByTicker[q.ticker] = """{"ticker":"${q.ticker}","status":"finalized","result":"yes"}"""
        fake.settlements = """{"settlements":[{"ticker":"${q.ticker}","market_result":"yes","yes_count_fp":"5.00","yes_total_cost_dollars":"4.70","no_count_fp":"0","no_total_cost_dollars":"0","revenue":500,"fee_cost":"0"}],"cursor":""}"""
        s.runOnce(ctx(Mode.LIVE, ix, armed = true))
        val g = stores.ledger.byId(q.id)!!
        assertEquals(0.30, g.pnl!!, 1e-9)
        assertEquals(false, g.hypothetical)
    }
}
