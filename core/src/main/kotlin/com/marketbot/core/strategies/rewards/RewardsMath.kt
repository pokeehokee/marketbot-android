package com.marketbot.core.strategies.rewards

import com.marketbot.core.polymarket.Level
import com.marketbot.core.util.parseInstant
import com.marketbot.core.util.num
import com.marketbot.core.util.objects
import com.marketbot.core.util.str
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * Polymarket US Liquidity Incentive Program scoring, as documented at
 * docs.polymarket.us/incentives/liquidity. Port of pmfarm `rewards.py`
 * (golden-tested): each second each book side is walked from its best price
 * until cumulative size reaches targetSize; orders in that range score
 * discount^ticks x size; each side is normalised to 1.0 per second.
 *
 * These are ESTIMATES. The only paid figure is GET /v1/incentives/earnings.
 */
object RewardsMath {
    const val SECONDS_PER_DAY = 86_400.0
    private const val EPS = 1e-9

    data class SideDepth(val best: Double?, val score: Double, val depth: Double, val adjustedPrice: Double?) {
        val reachesTarget get() = adjustedPrice != null
    }

    fun walkSide(levels: List<Level>, tick: Double, target: Double, discount: Double): SideDepth {
        if (levels.isEmpty()) return SideDepth(null, 0.0, 0.0, null)
        val best = levels[0].price
        var score = 0.0; var depth = 0.0
        for (l in levels) {
            val ticks = pyRound(abs(l.price - best) / tick)
            score += discount.pow(ticks.toDouble()) * l.qty
            depth += l.qty
            if (depth + EPS >= target) return SideDepth(best, score, depth, l.price)
        }
        return SideDepth(best, score, depth, null)
    }

    fun secondsQualify(bid: SideDepth, ask: SideDepth, maxSpread: Double?): Pair<Boolean, Boolean> {
        if (maxSpread == null) return bid.reachesTarget to ask.reachesTarget
        if (!(bid.reachesTarget && ask.reachesTarget)) return false to false
        val ok = (ask.adjustedPrice!! - bid.adjustedPrice!!) <= 2 * maxSpread + EPS
        return ok to ok
    }

    fun withoutOwn(levels: List<Level>, own: Map<Double, Double>): List<Level> =
        levels.mapNotNull { l -> val q = l.qty - (own[r6(l.price)] ?: 0.0); if (q > EPS) Level(l.price, q) else null }

    fun withOrder(levels: List<Level>, price: Double, qty: Double, isBid: Boolean): List<Level> {
        val merged = sortedMapOf<Double, Double>()
        for (l in levels) merged[r6(l.price)] = (merged[r6(l.price)] ?: 0.0) + l.qty
        merged[r6(price)] = (merged[r6(price)] ?: 0.0) + qty
        val out = merged.map { (p, q) -> Level(p, q) }
        return if (isBid) out.reversed() else out
    }

    fun sideRatePerDay(pool: Double, periodSeconds: Double): Double =
        if (periodSeconds <= 0) 0.0 else pool / periodSeconds * SECONDS_PER_DAY / 2.0

    data class QuoteEstimate(val pays: Boolean, val share: Double, val usdPerDay: Double)

    fun estimateQuote(bids: List<Level>, asks: List<Level>, side: String, price: Double, qty: Double,
                      tick: Double, target: Double, discount: Double, maxSpread: Double?, sideRate: Double): QuoteEstimate {
        val isBid = side == "bid"
        val mine = withOrder(if (isBid) bids else asks, price, qty, isBid)
        val b = walkSide(if (isBid) mine else bids, tick, target, discount)
        val a = walkSide(if (isBid) asks else mine, tick, target, discount)
        val (bp, ap) = secondsQualify(b, a, maxSpread)
        val pays = if (isBid) bp else ap
        val ours = if (isBid) b else a
        if (!pays || ours.score <= 0) return QuoteEstimate(false, 0.0, 0.0)
        if (isBid && price + EPS < ours.adjustedPrice!!) return QuoteEstimate(false, 0.0, 0.0)
        if (!isBid && price - EPS > ours.adjustedPrice!!) return QuoteEstimate(false, 0.0, 0.0)
        val ticks = pyRound(abs(price - ours.best!!) / tick)
        val share = discount.pow(ticks.toDouble()) * qty / ours.score
        return QuoteEstimate(true, share, share * sideRate)
    }

    fun costPerContract(side: String, price: Double) = if (side == "bid") price else 1.0 - price

    fun r6(x: Double) = Math.round(x * 1e6) / 1e6

    /** Python round(): half to even. */
    fun pyRound(x: Double): Long {
        val f = floor(x); val d = x - f
        return when { d > 0.5 -> f.toLong() + 1; d < 0.5 -> f.toLong(); else -> if (f.toLong() % 2 == 0L) f.toLong() else f.toLong() + 1 }
    }
}

data class Period(val programId: String, val kind: String, val pool: Double, val periodSeconds: Double, val end: Instant?,
                  val target: Double, val discount: Double, val maxSpread: Double?) {
    val sideRate get() = RewardsMath.sideRatePerDay(pool, periodSeconds)
}

data class FarmCandidate(val slug: String, val eventKey: String, val eventStart: Instant?, val periods: List<Period>) {
    val sideRate get() = periods.sumOf { it.sideRate }
}

data class QuotePlan(val slug: String, val eventKey: String, val side: String, val price: Double, val qty: Double,
                     val cost: Double, val usdPerDay: Double, val share: Double, val eventStart: Instant?)

data class FarmRules(
    val minSideRatePerDay: Double = 3.0, val maxCandidates: Int = 200, val minExpectedDaily: Double = 1.25,
    val minPayoutUsd: Double = 1.0, val rewardCalibration: Double = 1.0, val maxInstruments: Int = 25,
    val maxPerEvent: Int = 2, val maxPerFamily: Int = 3, val eventCutoffMin: Double = 45.0,
    val periodEndBufferMin: Double = 10.0, val maxBookSpread: Double = 0.08, val minPrice: Double = 0.02,
    val maxPrice: Double = 0.98, val excludeSlugPrefixes: List<String> = listOf("cpc-", "cpoc-btc", "cpoc-eth"),
    val allowShortQuotes: Boolean = false, val perInstrumentFrac: Double = 0.35, val perInstrumentMaxUsd: Double = 25.0,
    val minOrderUsd: Double = 0.50, val saturationShare: Double = 0.90, val cashReserveFrac: Double = 0.25,
    val maxInventoryFrac: Double = 0.60, val maxDrawdownFrac: Double = 0.25, val hardFloorUsd: Double = 6.0,
    val startEquity: Double = 0.0, val maxOpenOrders: Int = 50, val orderTtlMin: Double = 30.0,
    /** Hummingbot-style order refresh: keep an entry quote within this many ticks of its target (0 = exact, as pmfarm). */
    val requoteToleranceTicks: Int = 0,
    /** Replace a quote older than this many minutes even if unchanged (0 = never). */
    val maxOrderAgeMin: Double = 0.0,
    /** After a fill in a market, wait this long before a new entry quote there (0 = no wait). */
    val fillDelaySec: Int = 0,
)

/** Port of pmfarm `scanner.py`. */
object FarmScanner {
    /**
     * pmfarm's top-of-book screen: a two-sided book with a spread inside
     * [FarmRules.maxBookSpread] and one side inside the price band, at most
     * `maxPerEvent x 3` legs per event (only `maxPerEvent` can be selected, so
     * full books are not fetched for dozens of alternate lines of one game).
     */
    fun screen(cands: List<FarmCandidate>, bbo: Map<String, Pair<Double?, Double?>>, closed: Set<String>, r: FarmRules): List<FarmCandidate> {
        val perEvent = mutableMapOf<String, Int>()
        return cands.filter { c ->
            val (bid, ask) = bbo[c.slug] ?: (null to null)
            val ok = bid != null && ask != null && c.slug !in closed && c.slug in bbo &&
                ask - bid > 0 && ask - bid <= r.maxBookSpread + 1e-9 &&
                ((bid >= r.minPrice && bid <= r.maxPrice) || (ask >= r.minPrice && ask <= r.maxPrice))
            if (ok && (perEvent[c.eventKey] ?: 0) < r.maxPerEvent * 3) { perEvent.merge(c.eventKey, 1, Int::plus); true } else false
        }
    }

    val QUOTABLE = setOf("early", "day_of", "daily", "daily_event")
    private val EVENT_RE = Regex("^[a-z0-9]+-(.+?-\\d{4}-\\d{2}-\\d{2})")

    fun eventKey(slug: String): String =
        EVENT_RE.find(slug)?.let { "evt:" + it.groupValues[1] } ?: ("fam:" + slug.split('-').take(3).joinToString("-"))

    fun activePeriods(market: JsonObject, now: Instant, r: FarmRules): FarmCandidate? {
        val slug = market.str("marketSlug") ?: return null
        if (r.excludeSlugPrefixes.any { it.isNotEmpty() && slug.startsWith(it) }) return null
        val evStart = parseInstant(market.str("eventStartTime"))
        val periods = mutableListOf<Period>()
        for (t in market.objects("timePeriods")) {
            if (t.str("programType") != "liquidityProgram" || t.str("status") != "active") continue
            val kind = t.str("period") ?: ""
            if (kind !in QUOTABLE) continue
            val start = parseInstant(t.str("start"))
            var end = parseInstant(t.str("end"))
            val seconds: Double
            if (kind.startsWith("daily")) seconds = 86_400.0
            else {
                if (evStart == null) continue
                if (end == null) end = if (kind == "early") evStart.minus(Duration.ofHours(6)) else evStart
                if (start == null || !end!!.isAfter(start)) continue
                seconds = Duration.between(start, end).toMillis() / 1000.0
            }
            if (start != null && now.isBefore(start)) continue
            if (end != null && !now.isBefore(end.minusSeconds((r.periodEndBufferMin * 60).toLong()))) continue
            periods += Period(t.str("programId") ?: "", kind, t.num("rewardPool") ?: 0.0, seconds, end,
                t.num("targetSize") ?: 0.0, t.num("discountFactor") ?: 0.5, t.num("maxSpread"))
        }
        if (periods.isEmpty()) return null
        if (evStart != null && evStart.isAfter(now.minus(Duration.ofHours(1)))) {
            if (Duration.between(now, evStart) < Duration.ofSeconds((r.eventCutoffMin * 60).toLong())) return null
            if (!evStart.isAfter(now)) return null
        }
        return FarmCandidate(slug, eventKey(slug), evStart, periods)
    }

    private fun floorTo(x: Double, step: Double) = if (step <= 0) x else floor(x / step + 1e-9) * step

    fun planQuote(c: FarmCandidate, tick: Double, minQty: Double, closed: Boolean, active: Boolean,
                  bids: List<Level>, asks: List<Level>, r: FarmRules, budgetUsd: Double): QuotePlan? {
        if (closed || !active || bids.isEmpty() || asks.isEmpty()) return null
        val bb = bids[0].price; val ba = asks[0].price
        if (ba - bb > r.maxBookSpread + 1e-9 || ba <= bb) return null
        var best: QuotePlan? = null
        for (side in listOf("bid") + if (r.allowShortQuotes) listOf("ask") else emptyList()) {
            val price = if (side == "bid") bb else ba
            if (price < r.minPrice || price > r.maxPrice) continue
            val unit = RewardsMath.costPerContract(side, price)
            if (unit <= 0) continue
            val qMax = floorTo(budgetUsd / unit, minQty)
            val qMin = maxOf(minQty, floorTo(r.minOrderUsd / unit, minQty))
            if (qMax < qMin) continue
            val p0 = c.periods[0]
            val others = RewardsMath.walkSide(if (side == "bid") bids else asks, tick, if (p0.target == 0.0) 1.0 else p0.target, p0.discount)
            val sat = r.saturationShare
            val qSat = if (others.score > 0) floorTo(others.score * sat / (1 - sat), minQty) else qMin
            for (qty in sortedSetOf(minOf(qMax, maxOf(qMin, qSat)), qMax)) {
                var total = 0.0; var share = 0.0
                for (p in c.periods) {
                    val e = RewardsMath.estimateQuote(bids, asks, side, price, qty, tick, p.target, p.discount, p.maxSpread, p.sideRate)
                    total += e.usdPerDay; share = maxOf(share, e.share)
                }
                total *= r.rewardCalibration
                if (total <= 0) continue
                val plan = QuotePlan(c.slug, c.eventKey, side, price, qty, qty * unit, total, share, c.eventStart)
                val b = best
                if (b == null || plan.usdPerDay > b.usdPerDay * 1.05 || (plan.usdPerDay >= b.usdPerDay * 0.95 && plan.cost < b.cost)) best = plan
            }
        }
        return best
    }

    fun select(plans: List<QuotePlan>, r: FarmRules): List<QuotePlan> {
        val chosen = mutableListOf<QuotePlan>(); val per = mutableMapOf<String, Int>()
        for (p in plans.sortedByDescending { it.usdPerDay }) {
            if (p.usdPerDay < r.minExpectedDaily) break
            val cap = if (p.eventKey.startsWith("evt:")) r.maxPerEvent else r.maxPerFamily
            if ((per[p.eventKey] ?: 0) >= cap) continue
            chosen += p; per[p.eventKey] = (per[p.eventKey] ?: 0) + 1
            if (chosen.size >= r.maxInstruments) break
        }
        return chosen
    }
}

/** Kill switch and sizing limits. Port of pmfarm `risk.py`. */
data class FarmRiskState(val highWater: Double, val halted: Boolean = false, val reason: String = "")

object FarmRisk {
    fun floor(s: FarmRiskState?, r: FarmRules): Double {
        val hw = maxOf(s?.highWater ?: 0.0, r.startEquity)
        return maxOf(r.hardFloorUsd, hw * (1 - r.maxDrawdownFrac))
    }

    fun update(s: FarmRiskState?, equity: Double, r: FarmRules): FarmRiskState {
        var st = s ?: FarmRiskState(equity)
        st = st.copy(highWater = maxOf(st.highWater, equity, r.startEquity))
        val f = floor(st, r)
        if (!st.halted && equity < f) st = st.copy(halted = true, reason = "equity %.2f < floor %.2f".format(java.util.Locale.ROOT, equity, f))
        return st
    }

    fun entryBudget(equity: Double, freeCash: Double, r: FarmRules): Double =
        maxOf(0.0, minOf(r.perInstrumentFrac * equity, r.perInstrumentMaxUsd, freeCash - r.cashReserveFrac * equity))

    fun inventoryCost(positions: Map<String, Pair<Double, Double>>): Double =
        positions.values.sumOf { (qty, avg) -> if (qty > 0) qty * avg else -qty * (1 - avg) }
}

/** When a resting farm quote is left alone and when it is replaced (Hummingbot's refresh tolerance and maximum order age). */
object QuoteRefresh {
    /** An entry within [toleranceTicks] of its target keeps its place in the queue; an exit tracks its price to the tick. */
    fun near(resting: Double, target: Double, tick: Double, entry: Boolean, toleranceTicks: Int): Boolean =
        kotlin.math.abs(target - resting) < tick / 2 + (if (entry) toleranceTicks * tick else 0.0)

    /** Older than [maxAgeMin] minutes since first seen resting (0 = never too old). */
    fun tooOld(seenAt: java.time.Instant?, now: java.time.Instant, maxAgeMin: Double): Boolean =
        maxAgeMin > 0 && seenAt != null && java.time.Duration.between(seenAt, now).toMinutes() >= maxAgeMin

    /** Inside the pause after a fill in that market. */
    fun pausedAfterFill(lastFill: java.time.Instant?, now: java.time.Instant, delaySec: Int): Boolean =
        delaySec > 0 && lastFill != null && java.time.Duration.between(lastFill, now).seconds < delaySec
}
