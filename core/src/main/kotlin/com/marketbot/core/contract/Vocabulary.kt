package com.marketbot.core.contract

import com.marketbot.core.polymarket.PM_TERMINAL
import com.marketbot.core.strategies.longshot.WeatherLongshot
import com.marketbot.core.strategies.rewards.FarmScanner
import com.marketbot.core.util.str

/**
 * Every exact word the app relies on in the venues' answers: enum values it
 * branches on, field names it reads, slug and ticker shapes it parses. The code
 * paths use these (or the constants these point at), and the daily API check
 * compares live answers and Kalshi's published spec against the same sets, so a
 * venue renaming a value shows up as a finding instead of a strategy quietly
 * finding nothing.
 *
 * "Known" means the app has been told what to do with the value, not that it
 * trades on it: a market that is `disputed` is known, and the resolver waits.
 */
object Vocabulary {
    // ------------------------------------------------------------------ Kalshi
    /** Market statuses with a defined handling: listing, waiting, or settled. */
    val KALSHI_MARKET_STATUSES = setOf("initialized", "inactive", "active", "open", "unopened", "paused",
        "closed", "determined", "disputed", "amended", "settled", "finalized")
    /** Statuses at which a result is final enough to grade (the longshot resolver's set). */
    val KALSHI_SETTLED: Set<String> get() = WeatherLongshot.SETTLED
    /** Results the binary strategies grade; "" while unresolved. Scalar markets are never traded. */
    val KALSHI_RESULTS = setOf("yes", "no", "", "scalar")
    /** strike_type the KXBTCD ladder accepts (any other drops the market). */
    const val KXBTCD_STRIKE_TYPE = "greater"
    /** The shard KXBTCD trades on: the trading-status read is per shard. */
    const val KXBTCD_SHARD = 2
    /** Order statuses FillState reads, plus the older spellings it also accepts. */
    val KALSHI_ORDER_STATUSES = setOf("resting", "canceled", "executed")
    val KALSHI_ORDER_STATUS_ALIASES = setOf("cancelled", "expired", "filled")
    /** Fee types: the second is the kill criterion (a resting fill would pay a fee). */
    val KALSHI_FEE_TYPES = setOf("quadratic", "quadratic_with_maker_fees")
    const val KALSHI_FEE_OK = "quadratic"
    val KALSHI_SIDES = setOf("yes", "no")
    val KALSHI_BOOK_SIDES = setOf("bid", "ask")

    /**
     * The outcome side a trade's taker took: `taker_outcome_side`, else `taker_book_side`
     * (bid = yes), else the legacy `taker_side`, which Kalshi deprecated (not removed
     * before 2026-05-14, so it can go at any time now).
     */
    fun kalshiTakerSide(trade: kotlinx.serialization.json.JsonObject): String? =
        trade.s("taker_outcome_side") ?: bookToOutcome(trade.s("taker_book_side")) ?: trade.s("taker_side")

    /** The outcome side a fill gave us: `outcome_side`, else `book_side`, else the legacy `side`. */
    fun kalshiFillSide(fill: kotlinx.serialization.json.JsonObject): String? =
        fill.s("outcome_side") ?: bookToOutcome(fill.s("book_side")) ?: fill.s("side")

    private fun bookToOutcome(b: String?) = when (b) { "bid" -> "yes"; "ask" -> "no"; else -> null }
    private fun kotlinx.serialization.json.JsonObject.s(k: String) = str(k)?.lowercase()?.takeIf { it.isNotEmpty() }

    /** Fields read from each Kalshi object, by the name Kalshi's OpenAPI spec gives the schema. */
    val KALSHI_FIELDS = mapOf(
        "Market" to listOf("ticker", "event_ticker", "status", "result", "close_time", "strike_type", "floor_strike",
            "yes_bid_dollars", "yes_ask_dollars", "no_bid_dollars", "yes_bid_size_fp", "yes_ask_size_fp", "price_ranges", "exchange_index"),
        "Trade" to listOf("ticker", "taker_outcome_side", "yes_price_dollars", "no_price_dollars", "count_fp", "created_time", "trade_id"),
        "Settlement" to listOf("ticker", "revenue", "fee_cost", "yes_count_fp", "no_count_fp", "yes_total_cost_dollars", "no_total_cost_dollars", "market_result"),
        "Fill" to listOf("ticker", "order_id", "outcome_side", "count_fp", "yes_price_dollars", "no_price_dollars", "created_time"),
        "Order" to listOf("order_id", "ticker", "status", "fill_count_fp", "remaining_count_fp", "initial_count_fp", "client_order_id"),
        "ExchangeStatus" to listOf("trading_active", "exchange_active", "exchange_index_statuses"),
        "OrderGroup" to listOf("id", "contracts_limit_fp", "exchange_index"),
    )
    /** Read only when the field above is absent, so their deprecation is expected and harmless. */
    val KALSHI_LEGACY_FALLBACKS = mapOf("Trade" to listOf("taker_side", "taker_book_side"), "Fill" to listOf("side", "action", "book_side"))

    /** Enums the app branches on: schema -> property -> the values it knows. */
    val KALSHI_ENUMS = mapOf(
        "Market" to mapOf("status" to KALSHI_MARKET_STATUSES, "result" to KALSHI_RESULTS,
            "strike_type" to setOf("greater", "greater_or_equal", "less", "less_or_equal", "between", "functional", "custom", "structured")),
        "Order" to mapOf("status" to KALSHI_ORDER_STATUSES),
        "Trade" to mapOf("taker_outcome_side" to KALSHI_SIDES, "taker_book_side" to KALSHI_BOOK_SIDES),
        "Fill" to mapOf("outcome_side" to KALSHI_SIDES, "book_side" to KALSHI_BOOK_SIDES),
        "Settlement" to mapOf("market_result" to setOf("yes", "no", "scalar")),
    )

    // ------------------------------------------------------------------ Polymarket US
    const val PM_BOOK_OPEN = "MARKET_STATE_OPEN"
    val PM_ORDER_TERMINAL: Set<String> get() = PM_TERMINAL
    val PM_ORDER_SIDES = setOf("ORDER_SIDE_BUY", "ORDER_SIDE_SELL")
    /** Reward period kinds the farm quotes; any other kind is skipped. */
    val PM_QUOTABLE_PERIODS: Set<String> get() = FarmScanner.QUOTABLE
    const val PM_PROGRAM_TYPE = "liquidityProgram"
    const val PM_PROGRAM_ACTIVE = "active"
    /** The daily BTC ladder's event slug, and the prefix the strategy claims markets by. */
    /** Polymarket US daily temperature buckets: tc-temp-<city><high|low>-<yyyy-mm-dd>-<bucket>, category "climate". */
    const val PM_TEMP_PREFIX = "tc-temp-"
    const val PM_TEMP_CATEGORY = "climate"
    private val PM_TEMP_DATED = Regex("^tc-temp-([a-z]+?)(high|low)-(\\d{4}-\\d{2}-\\d{2})-")
    /** (city, HIGH|LOW) for a dated temperature bucket, else null (the undated monthly ones are not traded). */
    fun pmTemperature(slug: String): Pair<String, String>? = PM_TEMP_DATED.find(slug)?.let { it.groupValues[1] to it.groupValues[2].uppercase() }
    const val PM_BTC_EVENT_PREFIX = "btc-above-day-"
    /**
     * The ladder's market slugs. Seen 2026-10-29 as `cpc-btc-above-day-<date>-<strike>`: Polymarket
     * put a short prefix in front, so a slug matches with or without one.
     */
    private val PM_BTC_MARKET = Regex("^(?:[a-z0-9]{1,8}-)?btc-above-")
    fun isPmBtcMarket(slug: String) = PM_BTC_MARKET.containsMatchIn(slug)
}
