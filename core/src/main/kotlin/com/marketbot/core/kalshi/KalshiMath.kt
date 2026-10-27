package com.marketbot.core.kalshi

import com.marketbot.core.provenance.Source
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.util.num
import com.marketbot.core.util.objects
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Kalshi's trading fee: ceil(0.07 x count x P x (1-P)) cents per ORDER, i.e.
 * rounded up per order, not per contract. Returns the per-contract share in
 * dollars. Port of weatherbot `models/longshot.kalshi_fee`; the golden test
 * checks every cent price at ten order sizes against the Python.
 *
 * This is an ESTIMATE used before a trade. After a trade the exchange reports
 * the actual fee on the fill and the settlement (`fee_cost`), and that is
 * what P&L uses.
 */
fun kalshiFee(price: Double, count: Int, multiplier: Double = 1.0): Double {
    val n = max(1, count)
    val cents = max(0.0, min(100.0, price * 100.0))
    // Same operation order as the Python so the floating-point result, and so the ceil, agree exactly.
    return (ceil(0.07 * multiplier * n * cents * (100.0 - cents) / 100.0) / n) / 100.0
}

/**
 * Fee a RESTING (maker) order pays, from the series' own `fee_type`.
 * `quadratic` series charge takers only: 95 of 95 live resting fills on the
 * weather book paid `fee_cost = 0`. `quadratic_with_maker_fees` charges makers
 * too, and is the longshot kill criterion.
 */
fun makerFeeEstimate(feeType: Sourced<String>?, price: Double, count: Int, multiplier: Double = 1.0): Sourced<Double> =
    when (feeType?.value) {
        "quadratic" -> Sourced.derived(0.0, "fee_type=quadratic: makers pay nothing", "pre-trade estimate; fills report the real fee_cost", )
        null -> Sourced.derived(kalshiFee(price, count, multiplier), "ceil(0.07*C*P*(1-P))/C",
            "series fee_type unavailable; charging the taker rate to be safe")
        else -> Sourced.derived(kalshiFee(price, count, multiplier), "ceil(0.07*C*P*(1-P))/C",
            "fee_type=${feeType.value}: maker fee assumed equal to the taker formula")
    }

const val DEFAULT_TICK = 0.01

/**
 * Minimum legal price increment at [price], read from the market's
 * `price_ranges`. The live API sends `{"start","end","step"}`; older payloads
 * used `start_price_dollars` / `min_price_increment_dollars`. Both are read.
 * (The weatherbot original only knew the old keys, so on today's payloads it
 * silently fell back to 1c -- harmless on 1c-tick markets, wrong on any other.)
 */
fun tickSize(price: Double, priceRanges: JsonArray?): Sourced<Double> {
    val bands = priceRanges?.mapNotNull { it as? JsonObject } ?: emptyList()
    if (bands.isEmpty()) return Sourced.derived(DEFAULT_TICK, "default 0.01", "market has no price_ranges")
    var best: Double? = null
    for (b in bands) {
        val inc = b.num("step") ?: b.num("min_price_increment_dollars")
            ?: b.num("min_price_increment_cents")?.div(100) ?: b.num("min_price_increment")?.div(100) ?: continue
        if (inc <= 0) continue
        val lo = b.num("start") ?: b.num("start_price_dollars") ?: b.num("start_price_cents")?.div(100) ?: 0.0
        val hi = b.num("end") ?: b.num("end_price_dollars") ?: b.num("end_price_cents")?.div(100) ?: 1.0
        if (price in lo..hi) best = if (best == null) inc else min(best, inc)
    }
    return best?.let { Sourced(it, Source.KALSHI_API, "market#price_ranges.step") }
        ?: Sourced.derived(DEFAULT_TICK, "default 0.01", "no price_ranges band covers $price")
}

enum class Round { NEAREST, DOWN, UP }

/** Snap to a legal increment. DOWN for a bid, UP for an ask, so snapping never improves the price. */
fun roundToTick(price: Double, tick: Double, mode: Round = Round.NEAREST): Double {
    val steps = price / tick
    val n = when (mode) {
        Round.DOWN -> floor(steps + 1e-9)
        Round.UP -> ceil(steps - 1e-9)
        Round.NEAREST -> floor(steps + 0.5)
    }
    val d = tickDecimals(tick)
    val v = max(tick, min(1.0 - tick, n * tick))
    return "%.${d}f".format(java.util.Locale.ROOT, v).toDouble()
}

fun tickDecimals(tick: Double): Int {
    val text = "%.10f".format(java.util.Locale.ROOT, tick).trimEnd('0')
    return max(2, text.substringAfter('.', "").length)
}

/** Fixed-point decimal string the V2 order API wants ("0.0400"). */
fun formatPrice(price: Double, tick: Double): String =
    "%.${max(4, tickDecimals(tick))}f".format(java.util.Locale.ROOT, price)

fun collateralPerShortYes(price: Double) = max(0.0, 1.0 - price)

/** Every band in a market's price_ranges, for display. */
fun JsonObject.priceRanges(): JsonArray? = this["price_ranges"] as? JsonArray

@Suppress("unused")
private fun JsonObject.bands() = objects("price_ranges")
