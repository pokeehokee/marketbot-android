package com.marketbot.core.research

import com.marketbot.core.margin.FundingPoint
import com.marketbot.core.margin.MarginClient
import java.time.Instant

/**
 * Research recorder for Kalshi's perps: funding, premium and top of book over time.
 *
 * Why it exists. The obvious perps strategy is funding carry — hold the side that receives funding
 * — and the exchange's own history cannot answer whether it works. Measured 2026-11-12 on what is
 * published: funding supplied 0.2% to 22% of an unhedged short's return and the price move supplied
 * the rest, the carry is 0.8-2% of the 8-hour price standard deviation, and the two halves of the
 * window disagree wildly (WTI -672%/yr then +1361%/yr). Gold and silver looked like a 180-400%/yr
 * edge purely because both fell through the one 20-day window the exchange publishes (61 funding
 * periods). A sample that short, over one regime, measures direction and calls it carry.
 *
 * So this records the inputs forward, at no risk: public data, no keys, no orders, no leverage.
 * After some weeks there is a sample spanning more than one regime, and the question can be
 * answered rather than guessed. Two row types, because they answer different halves of it:
 *
 *  - [Sample]: a point in time — book, premium, the in-progress funding estimate. Says how far the
 *    perp strays from its index, and whether that is ever larger than the spread (on 2026-11-12 it
 *    was not: BTC's premium was 0.77x the spread).
 *  - [Funding]: a finalized 8-hour period — the rate charged and the mark price at that moment.
 *    This is the series a carry backtest needs, and the one the exchange only keeps ~60 of.
 *
 * Nothing here trades. Perps are leveraged, so a position can lose more than it cost, which the
 * rest of this app's risk controls assume cannot happen.
 */
object PerpsFunding {
    const val SAMPLE_HEADER =
        "at,ticker,asset_class,status,bid,ask,bid_qty,ask_qty,price,reference_price,premium,funding_estimate," +
            "next_funding,open_interest_usd,volume_24h_usd,leverage,contract_size"
    const val FUNDING_HEADER = "funding_time,ticker,funding_rate,mark_price,recorded_at"

    data class Sample(
        val at: Instant, val ticker: String, val assetClass: String?, val status: String?,
        val bid: Double?, val ask: Double?, val bidQty: Double?, val askQty: Double?,
        val price: Double?, val referencePrice: Double?, val premium: Double?, val fundingEstimate: Double?,
        val nextFunding: Instant?, val openInterestUsd: Double?, val volume24hUsd: Double?,
        val leverage: Double?, val contractSize: Double?,
    ) {
        fun csv(): String = listOf(at, ticker, assetClass, status, bid, ask, bidQty, askQty, price, referencePrice,
            premium, fundingEstimate, nextFunding, openInterestUsd, volume24hUsd, leverage, contractSize)
            .joinToString(",") { it?.toString()?.replace(",", " ") ?: "" }
    }

    /** A finalized funding period, keyed by (ticker, funding_time) so a repeated pass cannot duplicate it. */
    data class Funding(val at: Instant, val ticker: String, val rate: Double, val markPrice: Double?, val recordedAt: Instant) {
        val key: String get() = "$ticker@$at"
        fun csv(): String = listOf(at, ticker, rate, markPrice, recordedAt).joinToString(",") { it?.toString() ?: "" }
    }

    data class Pass(val samples: List<Sample>, val funding: List<Funding>, val tradingOpen: Boolean, val listed: Int)

    /**
     * One pass. Samples the [maxMarkets] busiest two-sided markets by 24h notional volume — the only
     * ones whose premium means anything — and asks each for its funding estimate, then collects any
     * finalized funding period newer than [seen] says it already has.
     *
     * [seen] maps ticker to the newest funding time already recorded, so the history is appended to
     * rather than re-collected. Every call is wrapped: one market failing never loses the pass.
     */
    suspend fun read(
        margin: MarginClient,
        now: Instant,
        seen: Map<String, Instant> = emptyMap(),
        maxMarkets: Int = 25,
        historyLimit: Int = 20,
    ): Pass {
        val open = runCatching { margin.tradingOpen() }.getOrDefault(false)
        val all = runCatching { margin.markets() }.getOrElse { return Pass(emptyList(), emptyList(), open, 0) }
        val pick = all.filter { it.twoSided }
            .sortedByDescending { it.volume24hNotional ?: 0.0 }
            .take(maxMarkets)
        val samples = mutableListOf<Sample>()
        val funding = mutableListOf<Funding>()
        for (m in pick) {
            val est = runCatching { margin.fundingEstimate(m.ticker) }.getOrNull()
            samples += Sample(now, m.ticker, m.assetClass, m.status, m.bid, m.ask, m.bidSize, m.askSize,
                m.price, m.referencePrice, est?.premiumIndex, est?.fundingRate, est?.nextFundingTime,
                m.openInterestNotional, m.volume24hNotional, m.leverageEstimate, m.contractSize)
            val since = seen[m.ticker]
            val pts = runCatching { margin.fundingHistory(m.ticker, historyLimit) }.getOrNull().orEmpty()
            for (p in pts) if (since == null || p.at.isAfter(since)) funding += p.toRow(now)
        }
        return Pass(samples, funding.sortedBy { it.at }, open, all.size)
    }

    private fun FundingPoint.toRow(recordedAt: Instant) = Funding(at, ticker, rate, markPrice, recordedAt)

    /** The newest funding time per ticker in [rows], for the next pass's [read] call. */
    fun watermarks(rows: List<Funding>): Map<String, Instant> =
        rows.groupBy { it.ticker }.mapValues { (_, v) -> v.maxOf { it.at } }
}
