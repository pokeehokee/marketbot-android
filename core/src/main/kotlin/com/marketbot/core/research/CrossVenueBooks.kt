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
    /** Kalshi cities Polymarket US also lists temperature buckets for. */
    val KALSHI_CITIES = listOf("NYC", "MIA", "CHI", "LAX", "SFO", "PHIL")
    const val HEADER = "at,venue,city,kind,ticker,floor,cap,bid,ask,bid_qty,ask_qty,close"

    data class Row(val at: Instant, val venue: String, val city: String, val kind: String, val ticker: String,
                   val floor: Double?, val cap: Double?, val bid: Double?, val ask: Double?, val bidQty: Double?, val askQty: Double?, val close: Instant?) {
        fun csv(): String = listOf(at, venue, city, kind, ticker, floor, cap, bid, ask, bidQty, askQty, close)
            .joinToString(",") { it?.toString()?.replace(",", " ") ?: "" }
    }

    /** One pass: every open bucket closing within [horizon] on both venues. Each venue fails on its own. */
    suspend fun read(kalshi: KalshiClient, polymarket: PolymarketClient, now: Instant, horizon: Duration = Duration.ofHours(48),
                     maxPmBooks: Int = 80): List<Row> {
        val out = mutableListOf<Row>()
        for (city in KALSHI_CITIES) for ((kind, series) in WeatherSeries.BY_CITY[city].orEmpty()) {
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
