package com.marketbot.core

import com.marketbot.core.btc.BtcIndex
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.provenance.Source
import com.marketbot.core.store.Stores
import com.marketbot.core.store.inMemoryStores
import com.marketbot.core.strategies.longshot.KalshiCommodityLongshot
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.util.MutableClock
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The commodity range book's LIVE order path (2026-11-14). The paper engine is covered by
 * [SearchCandidatesTest]; what is frozen here is everything that only matters when the orders
 * are real: that they are placed post-only on the market's own shard, that a withdrawal is a
 * cancel at the exchange and never a result, that P&L is the exchange's figure or nothing, and
 * that a balance the exchange will not report stops the pass instead of being guessed.
 */
class CommodityLiveTest {
    private val fake = FakeKalshi()
    @AfterTest fun close() = fake.close()

    private val t0: Instant = Instant.parse("2026-10-30T19:00:00Z")
    private val inHour: String = t0.plusSeconds(3600).toString()
    private val ticker = "KXGOLDD-26OCT30H1600-T4000"

    private fun live(s: com.marketbot.core.strategy.Strategy, stores: Stores, clock: MutableClock) =
        StrategyContext(s.id, Mode.LIVE, StrategyConfig(s.params), stores, clock, fake.client(true), PolymarketClient(null),
            BtcIndex(venues = emptyMap(), candles = { emptyList() }), liveArmed = true)

    /** One cheap-YES gold market inside the window, on the non-default shard commodities route to. */
    private fun listOneCheapMarket(exchangeIndex: Int = 2) {
        fake.markets["KXGOLDD"] = listOf(FakeKalshi.market(ticker, 0.04, 0.05, inHour, askSize = 10.0, exchangeIndex = exchangeIndex))
    }

    private fun settles(result: String) {
        fake.markets["KXGOLDD"] = emptyList()
        fake.marketByTicker[ticker] = FakeKalshi.market(ticker, 0.0, 1.0, inHour, status = "finalized", result = result)
    }

    @Test fun `a live offer is a real post-only order on the market's own shard`() = runBlocking {
        val s = KalshiCommodityLongshot(); val stores = inMemoryStores(); val clock = MutableClock(t0)
        listOneCheapMarket()
        s.runOnce(live(s, stores, clock))

        val row = stores.ledger.open(s.id, Mode.LIVE).single()
        assertEquals("sell_yes", row.action)
        assertNotNull(row.orderId, "a live row without an order id would lock budget against nothing")
        assertNotNull(row.clientOrderId, "the client order id is how a lost response is recovered")

        val body = fake.bodies.single { it["ticker"]?.toString()?.contains(ticker) == true }
        // Selling YES is the YES ASK side; the whole edge is resting there, so post_only is not an optimisation.
        assertEquals("\"ask\"", body["side"].toString())
        assertEquals("true", body["post_only"].toString())
        assertEquals("2", body["exchange_index"].toString(), "orders must route to the shard the market names")
        assertTrue(body["price"].toString().contains("0.05"), body.toString())
    }

    @Test fun `a balance the exchange will not report places nothing`() = runBlocking {
        val s = KalshiCommodityLongshot(); val stores = inMemoryStores(); val clock = MutableClock(t0)
        listOneCheapMarket()
        fake.balance = "not json at all"

        val r = s.runOnce(live(s, stores, clock))

        assertEquals("balance unreadable", r.summary)
        assertTrue(stores.ledger.open(s.id, Mode.LIVE).isEmpty())
        assertTrue(fake.posts().none { it.path!!.contains("/portfolio/events/orders") },
            "live size comes from the exchange or is not taken: never from a local fallback")
    }

    @Test fun `withdrawing an unfilled offer cancels at the exchange and writes no result of its own`() = runBlocking {
        val s = KalshiCommodityLongshot(); val stores = inMemoryStores(); val clock = MutableClock(t0)
        listOneCheapMarket()
        s.runOnce(live(s, stores, clock))
        val orderId = stores.ledger.open(s.id, Mode.LIVE).single().orderId!!

        // Close passes with the order still untouched at the exchange.
        clock.advanceSeconds(3700)
        settles("no")
        s.runOnce(live(s, stores, clock))

        assertTrue(fake.deletes().any { it.path!!.contains(orderId) }, "the order is withdrawn AT THE EXCHANGE, not just in the ledger")
        val row = stores.ledger.all(s.id, Mode.LIVE).single()
        assertNotNull(row.cancelledAt)
        assertEquals(0.0, row.pnl, "nothing filled, so nothing was made or lost")
        assertEquals(true, row.hypothetical, "an unfilled row is never summed into realised P&L")
        assertEquals(Source.DERIVED, row.pnlSource)
    }

    @Test fun `a filled live row is graded from the settlement payload, not from local arithmetic`() = runBlocking {
        val s = KalshiCommodityLongshot(); val stores = inMemoryStores(); val clock = MutableClock(t0)
        listOneCheapMarket()
        s.runOnce(live(s, stores, clock))
        val orderId = stores.ledger.open(s.id, Mode.LIVE).single().orderId!!

        // The exchange says the order filled in full.
        fake.orders[orderId] = """{"order":{"order_id":"$orderId","ticker":"$ticker","status":"executed",
            "fill_count_fp":"5.00","remaining_count_fp":"0.00","initial_count_fp":"5.00"}}"""
        clock.advanceSeconds(3700)
        settles("no")
        // Local arithmetic on 5 contracts sold at 5c would book +$0.25. The exchange says $4.75.
        fake.settlements = """{"settlements":[{"ticker":"$ticker","revenue":500,"yes_total_cost_dollars":"0.2500",
            "no_total_cost_dollars":"0.0000","fee_cost":0,"yes_count_fp":"0.00","no_count_fp":"5.00"}],"cursor":""}"""

        s.runOnce(live(s, stores, clock))

        val row = stores.ledger.all(s.id, Mode.LIVE).single()
        assertEquals(5.0, row.filledContracts)
        assertEquals(4.75, row.pnl!!, 1e-9, "the exchange's own figure, never payout-minus-cost computed here")
        assertEquals(Source.KALSHI_API, row.pnlSource)
        assertEquals(false, row.hypothetical)
    }

    @Test fun `a filled row the exchange has no figure for is left ungraded rather than guessed`() = runBlocking {
        val s = KalshiCommodityLongshot(); val stores = inMemoryStores(); val clock = MutableClock(t0)
        listOneCheapMarket()
        s.runOnce(live(s, stores, clock))
        val orderId = stores.ledger.open(s.id, Mode.LIVE).single().orderId!!
        fake.orders[orderId] = """{"order":{"order_id":"$orderId","ticker":"$ticker","status":"executed",
            "fill_count_fp":"5.00","remaining_count_fp":"0.00","initial_count_fp":"5.00"}}"""

        // Settled at the exchange, but none of the three portfolio stores carries it, while the
        // fill record confirms the position is real. Well past the settlement grace period.
        clock.advanceSeconds(3600 + 3600)
        settles("no")
        fake.settlements = """{"settlements":[],"cursor":""}"""
        fake.fills = """{"fills":[{"order_id":"$orderId","ticker":"$ticker","count_fp":"5.00"}],"cursor":""}"""

        s.runOnce(live(s, stores, clock))

        val row = stores.ledger.all(s.id, Mode.LIVE).single()
        assertNull(row.resolvedAt, "a real position with no exchange figure stays open; it is never derived locally")
        assertNull(row.pnl)
        assertEquals(5.0, row.filledContracts)
    }

    @Test fun `an order the exchange has no fills for is recorded as never filled, not as a loss`() = runBlocking {
        val s = KalshiCommodityLongshot(); val stores = inMemoryStores(); val clock = MutableClock(t0)
        listOneCheapMarket()
        s.runOnce(live(s, stores, clock))
        val orderId = stores.ledger.open(s.id, Mode.LIVE).single().orderId!!
        // The order endpoint claims a fill the fill record does not have: the fill record wins.
        fake.orders[orderId] = """{"order":{"order_id":"$orderId","ticker":"$ticker","status":"executed",
            "fill_count_fp":"5.00","remaining_count_fp":"0.00","initial_count_fp":"5.00"}}"""

        clock.advanceSeconds(3600 + 3600)
        settles("yes")
        fake.settlements = """{"settlements":[],"cursor":""}"""
        fake.fills = """{"fills":[],"cursor":""}"""

        s.runOnce(live(s, stores, clock))

        val row = stores.ledger.all(s.id, Mode.LIVE).single()
        assertEquals(0.0, row.filledContracts)
        assertEquals(0.0, row.pnl)
        assertEquals(true, row.hypothetical, "the market settled against us, but we never held it")
    }

    @Test fun `live and paper books never mix`() = runBlocking {
        val s = KalshiCommodityLongshot(); val stores = inMemoryStores(); val clock = MutableClock(t0)
        listOneCheapMarket()
        s.runOnce(live(s, stores, clock))
        s.runOnce(StrategyContext(s.id, Mode.PAPER, StrategyConfig(s.params), stores, clock, fake.client(true),
            PolymarketClient(null), BtcIndex(venues = emptyMap(), candles = { emptyList() }), liveArmed = false))

        assertEquals(1, stores.ledger.all(s.id, Mode.LIVE).size)
        assertEquals(1, stores.ledger.all(s.id, Mode.PAPER).size)
        assertNotNull(stores.ledger.all(s.id, Mode.LIVE).single().orderId)
        assertNull(stores.ledger.all(s.id, Mode.PAPER).single().orderId, "a paper row never carries an exchange order")
    }

    @Test fun `a variant of the commodity book cannot be run live`() {
        val v = KalshiCommodityLongshot("${KalshiCommodityLongshot.ID}@b", KalshiCommodityLongshot.ID)
        assertFalse(Mode.LIVE in v.supportedModes)
        assertTrue(Mode.LIVE in KalshiCommodityLongshot().supportedModes)
    }
}
