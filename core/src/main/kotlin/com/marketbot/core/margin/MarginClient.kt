package com.marketbot.core.margin

import com.marketbot.core.net.JsonHttp
import com.marketbot.core.net.RateLimiter
import com.marketbot.core.util.num
import com.marketbot.core.util.objects
import com.marketbot.core.util.str
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Kalshi's perps (margin) exchange, **public market data only**.
 *
 * Perps are a separate product on a separate API surface under `/margin`: leveraged perpetual
 * futures, with funding instead of settlement. This client is deliberately read-only and holds no
 * signer, so it cannot place, amend or cancate anything, and cannot read a portfolio. It exists to
 * record what the perps market does, because the carry question could not be answered from the 20
 * days of history the exchange publishes (see `docs/perps-research.md`).
 *
 * Nothing here is wired into a trading path. Perps break the invariant the rest of this app rests
 * on — collateral locked is the most a position can lose — because a leveraged position can be
 * liquidated for more than it cost. Arming perps would need a different risk model, not a new
 * strategy, and the recorder is the step that comes first.
 */
class MarginClient(
    base: String = "https://api.elections.kalshi.com/trade-api/v2",
    http: JsonHttp? = null,
) {
    private val http = http ?: JsonHttp(base, limiter = RateLimiter(6.0))

    /** Every listed perps market with its top of book, reference price and risk numbers. */
    suspend fun markets(): List<PerpMarket> = http.get("/margin/markets").objects("markets").map { PerpMarket(it) }

    /** The in-progress funding period's estimate for one market: the rate so far and the premium index. */
    suspend fun fundingEstimate(ticker: String): FundingEstimate? =
        http.get("/margin/funding_rates/estimate", listOf("ticker" to ticker)).takeIf { it.isNotEmpty() }?.let { FundingEstimate(it) }

    /** Finalized funding periods, newest first: the rate charged and the mark price it was charged at. */
    suspend fun fundingHistory(ticker: String, limit: Int = 50): List<FundingPoint> =
        http.get("/margin/funding_rates/historical", listOf("ticker" to ticker, "limit" to limit.toString()))
            .objects("funding_rates").mapNotNull { FundingPoint.of(it) }

    /** Whether the perps exchange is open, so a quiet sample is not read as a quiet market. */
    suspend fun tradingOpen(): Boolean =
        http.get("/margin/exchange/status").let { it["trading_active"]?.toString() == "true" }
}

/** One perps market as the venue reports it. Fields are read, never computed. */
class PerpMarket(val raw: JsonObject) {
    val ticker: String get() = raw.str("ticker") ?: ""
    val assetClass: String? get() = raw.str("asset_class")
    val status: String? get() = raw.str("status")
    val bid: Double? get() = raw.num("bid")
    val ask: Double? get() = raw.num("ask")
    val bidSize: Double? get() = raw.num("bid_size_fp")
    val askSize: Double? get() = raw.num("ask_size_fp")
    val price: Double? get() = raw.num("price")
    /** The index the perp is tethered to; funding is what pulls the two together. */
    val referencePrice: Double? get() = (raw["reference_price"] as? JsonObject)?.num("price")
    val liquidationMark: Double? get() = (raw["liquidation_mark_price"] as? JsonObject)?.num("price")
    val openInterestNotional: Double? get() = raw.num("open_interest_notional_value_dollars")
    val volume24hNotional: Double? get() = raw.num("volume_24h_notional_value_dollars")
    val leverageEstimate: Double? get() = raw.num("leverage_estimate")
    val contractSize: Double? get() = raw.num("contract_size")
    val tickSize: Double? get() = raw.num("tick_size")

    /** A two-sided book, which a dead or halted market does not have. */
    val twoSided: Boolean get() = (bid ?: 0.0) > 0.0 && (ask ?: 0.0) > 0.0 && (ask ?: 0.0) > (bid ?: 0.0)
}

class FundingEstimate(val raw: JsonObject) {
    val ticker: String? get() = raw.str("market_ticker")
    val fundingRate: Double? get() = raw.num("funding_rate")
    val premiumIndex: Double? get() = raw.num("premium_index")
    val markPrice: Double? get() = raw.num("mark_price")
    val nextFundingTime: Instant? get() = com.marketbot.core.util.parseInstant(raw.str("next_funding_time"))
}

/** A finalized funding period: the rate charged and the mark price at that moment. */
data class FundingPoint(val ticker: String, val at: Instant, val rate: Double, val markPrice: Double?) {
    companion object {
        fun of(o: JsonObject): FundingPoint? {
            val t = o.str("market_ticker") ?: return null
            val at = com.marketbot.core.util.parseInstant(o.str("funding_time")) ?: return null
            val r = o.num("funding_rate") ?: return null
            return FundingPoint(t, at, r, o.num("mark_price"))
        }
    }
}
