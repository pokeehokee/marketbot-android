package com.marketbot.core.research

import com.marketbot.core.contract.Vocabulary
import com.marketbot.core.kalshi.KalshiClient
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.strategies.longshot.WeatherSeries
import java.time.Duration
import java.time.Instant

/**
 * Research recorder: the same daily-temperature buckets on Kalshi and Polymarket US, read at
 * the same moment. The 2026-11-07 pool search could not test a cross-venue rule because history
 * has no simultaneous books; only a device that reads both, going forward, can collect them.
 * Public market data only: no keys, no orders. Rows go to the app's research file and out
 * through the Claude sync as exports/books-latest.csv.
 */
object CrossVenueBooks {
    /** Every Kalshi weather series (the six Polymarket also lists are the cross-venue pairs) and the commodity ranges: the tape recorder's books. */
    val KALSHI_SERIES: List<Pair<String, Pair<String, String>>> =
        WeatherSeries.SERIES_TO_CITY.entries.map { it.key to it.value }.sortedBy { it.first } +
            com.marketbot.core.strategies.longshot.KalshiCommodityLongshot.SERIES.map { it to (it to "RANGE") }
    const val HEADER = "at,venue,city,kind,ticker,floor,cap,bid,ask,bid_qty,ask_qty,close"
    const val TRADES_HEADER = "at,ticker,yes_price,count,taker_side,trade_id"

    data class Trade(val at: Instant, val ticker: String, val yesPrice: Double?, val count: Double?, val takerSide: String?, val id: String?) {
        fun csv(): String = listOf(at, ticker, yesPrice, count, takerSide, id).joinToString(",") { it?.toString()?.replace(",", " ") ?: "" }
    }

    /**
     * Public trades since [since] on the Kalshi markets in [books] whose cheap side is 2-25c and that close within 30 h:
     * who lifted what, and when, on the markets the longshot strategies quote. One page per market, at most [maxMarkets].
     */
    suspend fun trades(kalshi: KalshiClient, books: List<Row>, since: Instant, now: Instant, maxMarkets: Int = 60): List<Trade> {
        val pick = books.filter { r ->
            r.venue == "kalshi" && r.bid != null && r.ask != null && r.close != null && !r.close.isAfter(now.plus(Duration.ofHours(30))) &&
                (r.ask in 0.02..0.25 || (1 - r.bid) in 0.02..0.25)
        }.take(maxMarkets)
        val out = mutableListOf<Trade>()
        for (r in pick) {
            val ts = runCatching { kalshi.tradesSample(r.ticker, since.epochSecond) }.getOrNull() ?: continue
            for (t in ts) {
                val at = com.marketbot.core.util.parseInstant(t["created_time"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }) ?: continue
                fun num(k: String) = (t[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
                out += Trade(at, r.ticker, num("yes_price_dollars"), num("count_fp") ?: num("count"),
                    com.marketbot.core.contract.Vocabulary.kalshiTakerSide(t), (t["trade_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
            }
        }
        return out
    }

    data class Row(val at: Instant, val venue: String, val city: String, val kind: String, val ticker: String,
                   val floor: Double?, val cap: Double?, val bid: Double?, val ask: Double?, val bidQty: Double?, val askQty: Double?, val close: Instant?) {
        fun csv(): String = listOf(at, venue, city, kind, ticker, floor, cap, bid, ask, bidQty, askQty, close)
            .joinToString(",") { it?.toString()?.replace(",", " ") ?: "" }
    }

    /** One pass: every open bucket closing within [horizon] on both venues. Each venue fails on its own. */
    suspend fun read(kalshi: KalshiClient, polymarket: PolymarketClient, now: Instant, horizon: Duration = Duration.ofHours(48),
                     maxPmBooks: Int = 80): List<Row> {
        val out = mutableListOf<Row>()
        for ((series, ck) in KALSHI_SERIES) {
            val (city, kind) = ck
            val ms = runCatching { kalshi.openMarkets(series) }.getOrNull() ?: continue
            for (m in ms) {
                val close = m.closeTime ?: continue
                if (close.isAfter(now.plus(horizon))) continue
                val b = m.book
                out += Row(now, "kalshi", city, kind, m.ticker, m.floorStrike, m.capStrike, b?.yesBid?.value, b?.yesAsk?.value,
                    m.num("yes_bid_size_fp"), m.num("yes_ask_size_fp"), close)
            }
        }
        val metas = runCatching { polymarket.openMarkets(Vocabulary.PM_TEMP_CATEGORY) }.getOrNull().orEmpty()
            .filter { Vocabulary.pmTemperature(it.slug) != null && it.active && !it.closed }
            .filter { m -> m.endDate?.let { !it.isAfter(now.plus(horizon)) } ?: true }
            .take(maxPmBooks)
        for (m in metas) {
            val (city, kind) = Vocabulary.pmTemperature(m.slug)!!
            val b = runCatching { polymarket.book(m.slug) }.getOrNull() ?: continue
            out += Row(now, "polymarket", city, kind, m.slug, null, null, b.bestBid, b.bestAsk, b.bids.firstOrNull()?.qty, b.asks.firstOrNull()?.qty, m.endDate)
        }
        return out
    }
}
