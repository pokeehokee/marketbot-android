package com.marketbot.core.strategies.longshot

import com.marketbot.core.kalshi.kalshiFee
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Selector, sizing and ordering for the favorite-longshot strategy: sell cheap
 * YES on Kalshi weather buckets by RESTING an ask, hold to settlement.
 * Straight port of weatherbot `models/longshot.py` and the pure functions of
 * `scanners/longshot_runner.py`; `LongshotGoldenTest` checks each against the
 * Python on thousands of inputs. Every default is the measured value the
 * weatherbot CLAUDE.md records, and every one is a setting in the app.
 */
data class LongshotRules(
    val minMid: Double = 0.02,
    val maxMidHigh: Double = 0.25,
    val maxMidLow: Double = 0.15,
    val maxSpread: Double = 0.06,
    val maxConcurrentMarkets: Int = 120,
    val maxContractsPerMarket: Int = 10,
    val minContractsPerMarket: Int = 5,
    val lotCap: Int = 5,
    val collateralFraction: Double = 0.75,
    /** Realised P(YES) / quoted mid, measured over 4,468 markets. Reporting only: it rejects nothing. */
    val realisedRatio: Double = 0.700,
    val dailyLossLimit: Double = 10.0,
    val minLeadHours: Double = 20.0,
    val idleQuoteHours: Double = 8.0,
    val excludedAskBands: List<Pair<Double, Double>> = listOf(0.10 to 0.15),
    val pausedKinds: Set<String> = emptySet(),
) {
    fun maxMidFor(ticker: String): Double = when (WeatherSeries.kind(ticker)) {
        "HIGH" -> maxMidHigh
        "LOW" -> maxMidLow
        else -> 0.15
    }
}

data class LongshotQuote(val ticker: String, val price: Double, val contracts: Int, val expectedEdge: Double, val reason: String)

/** A market that passed the book read: ticker, YES bid, YES ask. */
data class Candidate(val ticker: String, val yesBid: Double, val yesAsk: Double)

object LongshotModel {
    /** Per-contract EV of resting a sell at [ask], net of the taker-formula fee (conservative: resting fills paid none). */
    fun expectedEdge(ask: Double, mid: Double, count: Int, ratio: Double = 0.700): Double {
        if (mid <= 0) return 0.0
        return (ask - mid * ratio) - kalshiFee(ask, count)
    }

    /** A resting sell quote, or null. Refuses whenever the measurement does not apply. Port of `evaluate()`. */
    fun evaluate(ticker: String, yesBid: Double?, yesAsk: Double?, openMarkets: Int = 0,
                 maxContracts: Int = 10, r: LongshotRules = LongshotRules()): LongshotQuote? {
        if (yesBid == null || yesAsk == null) return null
        val bid = yesBid; val ask = yesAsk
        if (!(bid in 0.0..1.0 && ask > 0.0 && ask <= 1.0) || ask < bid) return null
        val mid = (bid + ask) / 2.0
        if (!(mid >= r.minMid && mid <= r.maxMidFor(ticker))) return null
        if ((ask - bid) > r.maxSpread) return null
        if (openMarkets >= r.maxConcurrentMarkets) return null
        val contracts = max(1, maxContracts)
        val edge = expectedEdge(ask, mid, contracts, r.realisedRatio)
        if (edge <= 0) return null
        return LongshotQuote(ticker, ask, contracts, edge,
            "longshot: mid=%.3f ask=%.3f edge=%.2fc/contract".format(java.util.Locale.ROOT, mid, ask, edge * 100))
    }

    fun collateralPerContract(price: Double) = max(0.0, 1.0 - price)

    /** Largest order at [price] that fits [budget] of collateral; 0 means stop quoting. */
    fun sizeForBudget(price: Double, budget: Double, cap: Int): Int {
        val per = collateralPerContract(price)
        if (per <= 0) return cap
        return max(0, min(cap.toDouble(), pyFloorDiv(budget, per)).toInt())
    }

    /** Python's float `a // b` (CPython float_floor_div), which is not always floor(a / b): 1.0 // 0.1 == 9. */
    fun pyFloorDiv(a: Double, b: Double): Double {
        var mod = a.rem(b)
        var div = (a - mod) / b
        if (mod != 0.0 && ((b < 0) != (mod < 0))) { mod += b; div -= 1.0 }
        if (div == 0.0) return 0.0 * (a / b)
        var fd = floor(div)
        if (div - fd > 0.5) fd += 1.0
        return fd
    }

    /** Contracts per market that spread [budget] over the accepted pool, clamped to [lo, hi]. Port of `lot_for_budget`. */
    fun lotForBudget(budget: Double, asks: List<Double>, lo: Int = 5, hi: Int = 10): Int {
        if (asks.isEmpty() || budget <= 0) return lo
        val per = asks.sumOf { 1.0 - it } / asks.size
        if (per <= 0) return lo
        return max(lo, min(hi.toLong(), pyRound(budget / (asks.size * per))).toInt())
    }

    /** Python's round(): half to even. */
    fun pyRound(x: Double): Long {
        val f = floor(x)
        val diff = x - f
        return when {
            diff > 0.5 -> f.toLong() + 1
            diff < 0.5 -> f.toLong()
            else -> if (f.toLong() % 2 == 0L) f.toLong() else f.toLong() + 1
        }
    }

    /**
     * Spendable collateral for this book. Equity is cash + the exchange's own
     * `portfolio_value` when the payload carries it; only without it is equity
     * reconstructed as cash + live committed collateral (the documented
     * fallback, which overstates equity by resting collateral).
     */
    fun collateralBudget(cash: Double, committedLive: Double, committedBook: Double, fraction: Double = 0.75, portfolioValue: Double? = null): Double {
        val equity = if (portfolioValue != null) cash + portfolioValue else cash + committedLive
        return equity * fraction - committedBook
    }

    val BAND_PRIORITY = listOf(0.05 to 0.10, 0.15 to 1.01, 0.10 to 0.15, 0.0 to 0.05)

    /** 0 middle band, 1 extended (0.15+), 2 top (0.10-0.15), 3 bottom. */
    fun askBandRank(ask: Double): Int {
        BAND_PRIORITY.forEachIndexed { i, (lo, hi) -> if (ask >= lo && ask < hi) return i }
        return BAND_PRIORITY.size
    }

    fun evPerDollar(yesBid: Double, yesAsk: Double, ratio: Double = 0.700): Double {
        val mid = (yesBid + yesAsk) / 2.0
        return (yesAsk - mid * ratio) / max(1e-9, 1.0 - yesAsk)
    }

    /**
     * Within each kind: middle ask band, then extended, top, bottom; then EV per
     * dollar of collateral; then model rank (none on the phone, so listing
     * order); then the kinds are interleaved round-robin. Port of `order_candidates`.
     */
    fun orderCandidates(candidates: List<Candidate>, ranks: Map<String, Double> = emptyMap()): List<Candidate> {
        val byKind = linkedMapOf<String, MutableList<Candidate>>()
        for (c in candidates) byKind.getOrPut(WeatherSeries.kind(c.ticker) ?: "UNKNOWN") { mutableListOf() } += c
        val cmp = compareBy<Candidate>({ askBandRank(it.yesAsk) }, { -evPerDollar(it.yesBid, it.yesAsk) },
            { if (it.ticker in ranks) 0 else 1 }, { ranks[it.ticker] ?: 1.0 })
        val lanes = byKind.keys.sorted().map { k -> byKind.getValue(k).sortedWith(cmp) }   // sortedWith is stable
        val out = mutableListOf<Candidate>()
        for (i in 0 until (lanes.maxOfOrNull { it.size } ?: 0)) for (lane in lanes) if (i < lane.size) out += lane[i]
        return out
    }

    fun inExcludedBand(ask: Double, bands: List<Pair<Double, Double>>) = bands.any { (lo, hi) -> ask >= lo && ask < hi }

    /** Realised P/L of a short YES position, net of the formula fee. Port of the resolver's `pnl_for` (paper grading only). */
    fun pnlFor(price: Double, contracts: Int, result: String): Double {
        val gross = (price - if (result == "yes") 1.0 else 0.0) * contracts
        return gross - kalshiFee(price, contracts) * contracts
    }
}
