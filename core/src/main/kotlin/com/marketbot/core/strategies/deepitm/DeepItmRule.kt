package com.marketbot.core.strategies.deepitm

import com.marketbot.core.util.ET
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.ln
import kotlin.math.sqrt

/** One strike of an "above $X" ladder, YES-referenced, from the venue's own fields. */
data class LadderMarket(
    val ticker: String,
    val strike: Double,
    val close: Instant,
    val yesBid: Double?,
    /** Best NO bid = 1 - best YES ask. */
    val noBid: Double?,
    val yesBidSize: Double?,
    /** Size resting at the NO bid, i.e. at the YES ask. */
    val noBidSize: Double?,
    val exchangeIndex: Int? = null,
    val tick: Double = 0.01,
)

/** What the rule wants resting right now: buy [side] at the current best bid for it. */
data class Intent(val ticker: String, val side: String, val price: Double, val count: Int, val fair: Double, val z: Double, val queueAhead: Double)

data class DeepItmRules(
    val lot: Int = 5,
    val maxOpen: Int = 8,
    val bandLo: Double = 0.90,
    val bandHi: Double = 0.96,
    val fairMin: Double = 0.97,
    val ttcMinS: Double = 300.0,
    val ttcMaxS: Double = 3000.0,
    val skipCloseHoursEt: Set<Int> = setOf(3, 10, 21, 23),
    val dailyStop: Double = -15.0,
    val totalKill: Double = -60.0,
    /** Which qualifying bids take the free slots when more qualify than there are slots: see [SlotOrder]. */
    val slotOrder: String = SlotOrder.CHEAPEST,
)

/**
 * How [DeepItmRule.select] spends the free slots. The pool exceeds [DeepItmRules.maxOpen] in almost
 * every hour (127 of 135 in the paper record, with 20-170 candidates), so this decides which markets
 * get capital at all, exactly as the ask-band ordering does for the weather longshot.
 *
 * It is a setting rather than a fixed rule so a change can be run as a paper variant beside the
 * main book and judged on the record, instead of being argued from a handful of losses.
 */
object SlotOrder {
    /** Lowest bid first: the original runner's rule, and correct while the band's floor is right. */
    const val CHEAPEST = "cheapest"
    /** Highest bid first: the safest contracts, which also pay the least per contract. */
    const val DEAREST = "dearest"
    /** Nearest the middle of the joined band, which is where the weather longshot's edge sits. */
    const val MIDDLE = "middle"
    val ALL = listOf(CHEAPEST, DEAREST, MIDDLE)
}

/**
 * The entry rule recorded for the original `DeepItmNoOpens` runner (port of
 * weatherbot `deep_itm/strategy.py`, golden-tested): when the random-walk fair
 * for a side is >= 0.97 and that side's best bid is inside 0.90-0.96, rest a
 * bid JOINING it (never improving), at most 8 strikes, 300-3000 s before
 * close, skipping hours that close at the session opens (03/10/21/23 ET).
 * Both sides: YES below spot, NO above. Hold every fill to settlement.
 */
object DeepItmRule {
    private fun phi(x: Double) = 0.5 * (1.0 + erf(x / sqrt(2.0)))

    /** P(settle above strike) under a driftless random walk in log price, and its z. */
    fun fairYes(spot: Double, strike: Double, sigma1s: Double?, ttcS: Double): Pair<Double, Double>? {
        if (spot <= 0 || strike <= 0 || sigma1s == null || sigma1s <= 0 || ttcS <= 0) return null
        val z = ln(spot / strike) / (sigma1s * sqrt(ttcS))
        return phi(z) to z
    }

    fun closeHourEt(close: Instant) = ZonedDateTime.ofInstant(close, ET).hour

    fun candidates(markets: List<LadderMarket>, spot: Double, sigma: Double, now: Instant, r: DeepItmRules = DeepItmRules()): List<Intent> {
        val out = mutableListOf<Intent>()
        for (m in markets) {
            val ttc = (m.close.toEpochMilli() - now.toEpochMilli()) / 1000.0
            if (ttc < r.ttcMinS || ttc > r.ttcMaxS || closeHourEt(m.close) in r.skipCloseHoursEt) continue
            val (pYes, z) = fairYes(spot, m.strike, sigma, ttc) ?: continue
            for ((side, fair, bid, size, zz) in listOf(
                Quint("yes", pYes, m.yesBid, m.yesBidSize, z),
                Quint("no", 1.0 - pYes, m.noBid, m.noBidSize, -z))) {
                if (bid == null || bid <= 0.0 || bid >= 1.0) continue      // 0 / 1.00 is an empty book, not a price
                if (fair >= r.fairMin && bid >= r.bandLo - 1e-9 && bid <= r.bandHi + 1e-9)
                    out += Intent(m.ticker, side, Math.round(bid * 10000) / 10000.0, r.lot, fair, zz, size ?: 0.0)
            }
        }
        return out
    }

    /** Keep tickers already held or quoted, then fill free slots with the LOWEST favoured bids. One side per ticker. */
    fun select(cands: List<Intent>, openTickers: Set<String>, maxOpen: Int = 8,
               order: String = SlotOrder.CHEAPEST, bandLo: Double = 0.90, bandHi: Double = 0.96): List<Intent> {
        val chosen = mutableListOf<Intent>(); val seen = mutableSetOf<String>()
        for (c in cands) if (c.ticker in openTickers && seen.add(c.ticker)) chosen += c
        var slots = maxOf(0, maxOpen - openTickers.size)
        val mid = (bandLo + bandHi) / 2
        val rank: (Intent) -> Double = when (order) {
            SlotOrder.DEAREST -> { c -> -c.price }
            SlotOrder.MIDDLE -> { c -> kotlin.math.abs(c.price - mid) }
            else -> { c -> c.price }
        }
        for (c in cands.filter { it.ticker !in openTickers }.sortedWith(compareBy({ rank(it) }, { it.ticker }))) {
            if (slots <= 0) break
            if (seen.add(c.ticker)) { chosen += c; slots-- }
        }
        return chosen
    }

    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    /** ("KXBTCD-26OCT2717", close) for the hourly event closing at the next top of the hour, named in ET. */
    fun nextHourEvent(now: Instant, series: String = "KXBTCD"): Pair<String, Instant> {
        val close = now.truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS)
        val et = ZonedDateTime.ofInstant(close, ET)
        val name = "%s-%02d%s%02d%02d".format(series, et.year % 100, MONTHS[et.monthValue - 1], et.dayOfMonth, et.hour)
        return name to close
    }

    /** P&L of a held deep-ITM position at settlement; no fee on a resting fill. */
    fun pnl(side: String, price: Double, filled: Double, result: String?): Double {
        if ((result != "yes" && result != "no") || filled == 0.0) return 0.0
        return if (side == result) filled * (1.0 - price) else -filled * price
    }

    private data class Quint(val side: String, val fair: Double, val bid: Double?, val size: Double?, val z: Double)

    /**
     * The error function, to ~1e-13 absolute: Taylor series below |x| = 3,
     * Lentz continued fraction for erfc above. (Python's math.erf is the
     * reference in the golden test.)
     */
    fun erf(x: Double): Double {
        if (kotlin.math.abs(x) > 6) return if (x > 0) 1.0 else -1.0
        return erfSeries(x)
    }

    /** Taylor series for |x| < 3 (converges fast and is exact to double precision there); continued fraction beyond. */
    private fun erfSeries(x: Double): Double {
        val ax = kotlin.math.abs(x)
        if (ax < 3.0) {
            var sum = x; var term = x; var n = 0
            while (true) {
                n++
                term *= -x * x / n
                val add = term / (2 * n + 1)
                sum += add
                if (kotlin.math.abs(add) < 1e-17 * kotlin.math.abs(sum) || n > 200) break
            }
            return 2.0 / sqrt(Math.PI) * sum
        }
        // erfc via Lentz continued fraction
        val z = ax
        var f = z; var c = z; var d = 0.0
        val tiny = 1e-300
        for (i in 1..300) {
            val an = i / 2.0
            d = z + an * d; if (d == 0.0) d = tiny
            c = z + an / c; if (c == 0.0) c = tiny
            d = 1.0 / d
            val delta = c * d
            f *= delta
            if (kotlin.math.abs(delta - 1.0) < 1e-16) break
        }
        val erfc = kotlin.math.exp(-z * z) / sqrt(Math.PI) / f
        val r = 1.0 - erfc
        return if (x >= 0) r else -r
    }
}
