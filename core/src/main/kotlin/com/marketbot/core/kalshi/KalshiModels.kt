package com.marketbot.core.kalshi

import com.marketbot.core.provenance.Source
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.provenance.ValueUnavailable
import com.marketbot.core.util.firstNum
import com.marketbot.core.util.long
import com.marketbot.core.util.num
import com.marketbot.core.util.objects
import com.marketbot.core.util.parseInstant
import com.marketbot.core.util.str
import kotlinx.serialization.json.JsonObject
import java.time.Instant

private const val BAL = "GET /portfolio/balance"

/**
 * `/portfolio/balance`. `cash` is FREE cash (collateral for filled positions
 * already deducted, resting orders not). `portfolioValue` is the exchange's own
 * mark of open positions. Both straight from the payload; neither is ever
 * reconstructed silently.
 */
data class KalshiBalance(
    val cash: Sourced<Double>,
    val portfolioValue: Sourced<Double>?,
    val byExchangeIndex: Map<Int, Sourced<Double>>,
    val raw: JsonObject,
) {
    /** cash + portfolio_value when the exchange reports both; null means "unknown", not zero. */
    val equity: Sourced<Double>?
        get() = portfolioValue?.let {
            Sourced(cash.value + it.value, Source.DERIVED, "$BAL#balance_dollars + portfolio_value",
                "sum of two API fields; Kalshi reports no single equity field", cash.at)
        }

    companion object {
        fun parse(o: JsonObject, at: Instant = Instant.now()): KalshiBalance {
            val cash = o.num("balance_dollars")?.let { Sourced.kalshi(it, "$BAL#balance_dollars", at) }
                ?: o.num("balance")?.let { Sourced.kalshi(it / 100.0, "$BAL#balance (cents)", at) }
                ?: throw ValueUnavailable("Kalshi cash balance", "payload has neither balance_dollars nor balance")
            val pv = o.num("portfolio_value")?.let { Sourced.kalshi(it / 100.0, "$BAL#portfolio_value (cents)", at) }
            val idx = o.objects("balance_breakdown").mapNotNull { b ->
                val i = b.long("exchange_index")?.toInt() ?: return@mapNotNull null
                val v = b.num("balance") ?: return@mapNotNull null
                i to Sourced.kalshi(v, "$BAL#balance_breakdown[$i].balance", at)
            }.toMap()
            return KalshiBalance(cash, pv, idx, o)
        }
    }
}

/** Top of book for one market, YES-referenced, in dollars. */
data class TopOfBook(val yesBid: Sourced<Double>, val yesAsk: Sourced<Double>) {
    val mid: Double get() = (yesBid.value + yesAsk.value) / 2.0

    companion object {
        /**
         * `yes_bid_dollars` / `yes_ask_dollars` are the fields /markets serves.
         * Legacy integer-cents `yes_bid` / `yes_ask` are read if present. When
         * the YES ask is missing it is derived as 1 - best NO bid (same book).
         * Returns null when either side cannot be established.
         */
        fun of(m: JsonObject, at: Instant = Instant.now()): TopOfBook? {
            val t = m.str("ticker") ?: "?"
            fun side(dKey: String, cKey: String): Sourced<Double>? =
                when {
                    m.str(dKey) != null -> m.num(dKey)?.let { Sourced.kalshi(it, "market $t#$dKey", at) }
                    else -> m.num(cKey)?.let { Sourced.kalshi(it / 100.0, "market $t#$cKey (cents)", at) }
                }
            val bid = side("yes_bid_dollars", "yes_bid") ?: return null
            val ask = side("yes_ask_dollars", "yes_ask")
                ?: side("no_bid_dollars", "no_bid")?.let {
                    Sourced.derived(1.0 - it.value, "1 - no_bid", "market $t has no yes_ask field; YES ask = 1 - best NO bid", at)
                } ?: return null
            return TopOfBook(bid, ask)
        }
    }
}

/** A Kalshi market listing row, with the fields the strategies use. */
data class KalshiMarket(val raw: JsonObject) {
    val ticker: String get() = raw.str("ticker") ?: ""
    val eventTicker: String? get() = raw.str("event_ticker")
    val status: String? get() = raw.str("status")
    val result: String? get() = raw.str("result")?.lowercase()?.takeIf { it.isNotEmpty() }
    val closeTime: Instant? get() = parseInstant(raw.str("close_time"))
    val strikeType: String? get() = raw.str("strike_type")
    val floorStrike: Double? get() = raw.num("floor_strike")
    val capStrike: Double? get() = raw.num("cap_strike")
    val exchangeIndex: Int? get() = raw.long("exchange_index")?.toInt()
    val book: TopOfBook? get() = TopOfBook.of(raw)
    fun num(key: String): Double? = raw.num(key)
}

/** Fill state of one order, from the exchange's order record. */
data class FillState(val filled: Boolean?, val contracts: Double?, val origin: String) {
    companion object {
        private val FILL = arrayOf("fill_count_fp", "fill_count", "filled_count")
        private val REMAINING = arrayOf("remaining_count_fp", "remaining_count")
        private val INITIAL = arrayOf("initial_count_fp", "initial_count", "count")

        /**
         * Port of weatherbot `longshot_resolver.fill_state`. `filled = null`
         * means unknown, which must never be scored as a miss. `contracts =
         * null` with filled=true (bare status 'executed') means "assume the
         * whole order", not zero.
         */
        fun of(order: JsonObject): FillState {
            order.firstNum(*FILL)?.let { (k, n) -> return FillState(n > 0, n, "order#$k") }
            val rem = order.firstNum(*REMAINING)
            val init = order.firstNum(*INITIAL)
            if (rem != null && init != null) {
                val n = init.second - rem.second
                return FillState(n > 0, n, "order#${init.first} - ${rem.first}")
            }
            return when (order.str("status")?.lowercase()) {
                "executed", "filled" -> FillState(true, null, "order#status")
                "canceled", "cancelled", "expired" -> FillState(false, 0.0, "order#status")
                else -> FillState(null, null, "order#status")
            }
        }
    }
}

/**
 * Realised P&L of a settled market position, as the exchange reports it. `realized_pnl_dollars` is
 * the exchange's own locked-in figure, so it is taken as given: no fee is subtracted on top of it,
 * because `fees_paid_dollars` is reported separately and double-counting it would understate every
 * settled position. Used only when no settlement record is readable (the live set has archived it);
 * [settlementPnl] is preferred because it states the fee explicitly.
 */
fun positionPnl(p: JsonObject, from: String): Sourced<Double>? {
    val v = p.num("realized_pnl_dollars") ?: return null
    val t = p.str("ticker") ?: "?"
    return Sourced(v, Source.KALSHI_API, "GET $from[$t]#realized_pnl_dollars (fees_paid_dollars " +
        "${p.num("fees_paid_dollars")?.let { "%.4f".format(it) } ?: "absent"} reported separately, not deducted again)")
}

/**
 * Realised P&L of a settlement exactly as the exchange computes it:
 * revenue (cents) - yes/no total cost - fee_cost + 1.00 x closed pairs.
 * Port of weatherbot `_pnl_from_payload`, including the closed-pair term it
 * learned the hard way (a hand-closed position leaves both legs in the payload).
 */
fun settlementPnl(s: JsonObject): Sourced<Double> {
    fun f(k: String) = s.num(k) ?: 0.0
    val revenue = f("revenue") / 100.0
    var cost = f("yes_total_cost_dollars") + f("no_total_cost_dollars")
    var costOrigin = "yes_total_cost_dollars + no_total_cost_dollars"
    if (cost == 0.0) {
        cost = (f("yes_total_cost") + f("no_total_cost")) / 100.0
        costOrigin = "(yes_total_cost + no_total_cost) cents"
    }
    val yes = s.num("yes_count_fp").takeUnless { it == null || it == 0.0 } ?: f("yes_count")
    val no = s.num("no_count_fp").takeUnless { it == null || it == 0.0 } ?: f("no_count")
    val pairs = minOf(yes, no)
    val ticker = s.str("ticker") ?: "?"
    return Sourced(revenue - cost - f("fee_cost") + pairs, Source.KALSHI_API,
        "GET /portfolio/settlements[$ticker]: revenue - ($costOrigin) - fee_cost + closed pairs")
}
