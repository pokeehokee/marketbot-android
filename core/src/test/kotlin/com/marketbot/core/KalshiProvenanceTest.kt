package com.marketbot.core

import com.marketbot.core.kalshi.KalshiBalance
import com.marketbot.core.kalshi.KalshiMarket
import com.marketbot.core.kalshi.TopOfBook
import com.marketbot.core.kalshi.makerFeeEstimate
import com.marketbot.core.kalshi.priceRanges
import com.marketbot.core.kalshi.roundToTick
import com.marketbot.core.kalshi.Round
import com.marketbot.core.kalshi.settlementPnl
import com.marketbot.core.kalshi.tickSize
import com.marketbot.core.provenance.Source
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.provenance.ValueUnavailable
import com.marketbot.core.util.parseObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every Kalshi value must come from the API field that carries it, and only
 * fall back to a calculation -- labelled as one -- when the field is absent.
 */
class KalshiProvenanceTest {
    @Test fun `cash is read from balance_dollars, and only from cents when that field is absent`() {
        val b = KalshiBalance.parse(parseObject("""{"balance":40312,"balance_dollars":"403.1250","portfolio_value":18670,"updated_ts":1}"""))
        assertEquals(403.125, b.cash.value)
        assertEquals(Source.KALSHI_API, b.cash.source)
        assertTrue(b.cash.origin.endsWith("#balance_dollars"))
        assertEquals(186.70, b.portfolioValue!!.value, 1e-9)
        assertEquals(Source.KALSHI_API, b.portfolioValue!!.source)
        // equity is a sum of two API fields: labelled as a calculation, never as an API figure
        assertEquals(589.825, b.equity!!.value, 1e-9)
        assertEquals(Source.DERIVED, b.equity!!.source)

        val old = KalshiBalance.parse(parseObject("""{"balance":40312}"""))
        assertEquals(403.12, old.cash.value, 1e-9)
        assertTrue(old.cash.origin.contains("cents"))
        assertNull(old.portfolioValue, "a missing mark is unknown, not zero")
        assertNull(old.equity)
    }

    @Test fun `a balance payload with no cash field is refused, never read as zero`() {
        assertFailsWith<ValueUnavailable> { KalshiBalance.parse(parseObject("""{"portfolio_value":100}""")) }
    }

    @Test fun `per-shard balances come from balance_breakdown`() {
        val b = KalshiBalance.parse(parseObject("""{"balance_dollars":"10.00","portfolio_value":0,
            "balance_breakdown":[{"exchange_index":0,"balance":"8.00"},{"exchange_index":2,"balance":"2.00"}]}"""))
        assertEquals(2.0, b.byExchangeIndex.getValue(2).value)
        assertEquals(Source.KALSHI_API, b.byExchangeIndex.getValue(2).source)
    }

    @Test fun `top of book on a real KXHIGHNY listing comes from the dollar fields`() {
        val m = KalshiMarket(payload("kalshi_markets_KXHIGHNY")["markets"].list()[0].o())
        val tob = m.book!!
        assertEquals(Source.KALSHI_API, tob.yesBid.source)
        assertEquals(Source.KALSHI_API, tob.yesAsk.source)
        assertTrue(tob.yesAsk.origin.endsWith("#yes_ask_dollars"))
        assertEquals(m.raw["yes_ask_dollars"].s!!.toDouble(), tob.yesAsk.value)
    }

    @Test fun `a missing YES ask is derived from the NO bid and labelled as derived`() {
        val tob = TopOfBook.of(parseObject("""{"ticker":"T","yes_bid_dollars":"0.03","no_bid_dollars":"0.95"}"""))!!
        assertEquals(0.05, tob.yesAsk.value, 1e-9)
        assertEquals(Source.DERIVED, tob.yesAsk.source)
        assertEquals(Source.KALSHI_API, tob.yesBid.source)
    }

    @Test fun `tick size is read from the live price_ranges step field`() {
        val m = KalshiMarket(payload("kalshi_markets_KXHIGHNY")["markets"].list()[0].o())
        val t = tickSize(0.05, m.raw.priceRanges())
        assertEquals(0.01, t.value)
        assertEquals(Source.KALSHI_API, t.source)
        val sub = tickSize(0.03, kotlinx.serialization.json.Json.parseToJsonElement(
            """[{"start":"0.00","end":"0.10","step":"0.001"},{"start":"0.10","end":"1.00","step":"0.01"}]""") as kotlinx.serialization.json.JsonArray)
        assertEquals(0.001, sub.value)
        val none = tickSize(0.05, null)
        assertEquals(Source.DERIVED, none.source)
    }

    @Test fun `rounding never improves the price`() {
        assertEquals(0.06, roundToTick(0.0501, 0.01, Round.UP))
        assertEquals(0.05, roundToTick(0.0599, 0.01, Round.DOWN))
        assertEquals(0.035, roundToTick(0.035, 0.001, Round.UP))
    }

    @Test fun `maker fee follows the series fee_type from the API`() {
        val series = payload("kalshi_series_KXHIGHNY")["series"].o()
        val ft = Sourced.kalshi(series["fee_type"].s!!, "GET /series/KXHIGHNY#fee_type")
        assertEquals("quadratic", ft.value)
        assertEquals(0.0, makerFeeEstimate(ft, 0.05, 5).value)
        assertTrue(makerFeeEstimate(Sourced.kalshi("quadratic_with_maker_fees", "x"), 0.05, 5).value > 0)
        assertTrue(makerFeeEstimate(null, 0.05, 5).value > 0, "unknown fee type is charged the taker rate")
    }

    @Test fun `settlement P&L is labelled as the exchange's figure`() {
        val p = settlementPnl(parseObject("""{"ticker":"T","revenue":0,"no_total_cost_dollars":"4.75","fee_cost":"0","no_count_fp":"5.00"}"""))
        assertEquals(-4.75, p.value, 1e-9)
        assertEquals(Source.KALSHI_API, p.source)
    }

    @Test fun `the real KXBTCD listing carries the shard index the orders must route to`() {
        val m = KalshiMarket(payload("kalshi_markets_KXBTCD")["markets"].list()[0].o())
        assertEquals(2, m.exchangeIndex)
        assertEquals("greater", m.strikeType)
        assertTrue(m.floorStrike!! > 1000)
    }
}
