package com.marketbot.core.strategy

import com.marketbot.core.store.QuoteRow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * How the price moved after a fill, from the filled side's point of view: cents per
 * contract gained (positive) or lost (negative) if the position were marked at the
 * mid N minutes later. Consistently negative markouts mean fills arrive when they
 * hurt, an hour after the fill instead of a day later at settlement (poly-maker
 * tracks the same thing to widen its quotes).
 */
object Markout {
    val HORIZONS = listOf(5, 30, 60)
    const val SEEN = "fill_seen_at"
    fun key(minutes: Int) = "markout_$minutes"

    /** Cents per contract for a fill at [q]'s price when the YES mid is [yesMid]. */
    fun cents(q: QuoteRow, yesMid: Double): Double = 100 * when (q.action) {
        "sell_yes" -> q.price - yesMid
        "buy_yes" -> yesMid - q.price
        "buy_no" -> (1 - yesMid) - q.price
        else -> 0.0
    }

    fun read(q: QuoteRow, minutes: Int): Double? = (q.extra[key(minutes)] as? JsonPrimitive)?.doubleOrNull
    fun seenAt(q: QuoteRow): Instant? = (q.extra[SEEN] as? JsonPrimitive)?.content?.let { runCatching { Instant.parse(it) }.getOrNull() }

    /** The horizons now due for [q] (filled, seen, not yet recorded). */
    fun due(q: QuoteRow, now: Instant): List<Int> {
        val seen = seenAt(q) ?: return emptyList()
        return HORIZONS.filter { read(q, it) == null && q.extra[key(it)] == null && !now.isBefore(seen.plusSeconds(it * 60L)) }
    }

    fun with(extra: JsonObject, vararg kv: Pair<String, JsonPrimitive>) = JsonObject(extra + kv)

    data class Line(val minutes: Int, val n: Int, val meanCents: Double?, val shareNegative: Double?)

    fun summary(rows: List<QuoteRow>): List<Line> = HORIZONS.map { h ->
        val v = rows.mapNotNull { read(it, h) }
        Line(h, v.size, v.takeIf { it.isNotEmpty() }?.average(), v.takeIf { it.isNotEmpty() }?.let { l -> l.count { it < 0 }.toDouble() / l.size })
    }
}

/** Settled P&L per day, week (Monday start) or month, as freqtrade's /daily, /weekly and /monthly show it. */
object Periods {
    enum class Unit { DAY, WEEK, MONTH }
    data class Row(val start: LocalDate, val settled: Int, val pnl: Double, val losses: Int)

    fun table(rows: List<QuoteRow>, unit: Unit, n: Int, zone: ZoneId, now: Instant): List<Row> {
        fun startOf(d: LocalDate) = when (unit) {
            Unit.DAY -> d
            Unit.WEEK -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            Unit.MONTH -> d.withDayOfMonth(1)
        }
        val today = startOf(now.atZone(zone).toLocalDate())
        val starts = (0 until n).map { i -> when (unit) { Unit.DAY -> today.minusDays(i.toLong()); Unit.WEEK -> today.minusWeeks(i.toLong()); Unit.MONTH -> today.minusMonths(i.toLong()) } }
        val settled = rows.filter { it.resolvedAt != null && it.hypothetical == false && it.pnl != null }
            .groupBy { startOf(it.resolvedAt!!.atZone(zone).toLocalDate()) }
        return starts.map { s -> val l = settled[s].orEmpty(); Row(s, l.size, l.sumOf { it.pnl!! }, l.count { it.pnl!! < 0 }) }
    }
}

/**
 * How often markets settled YES against the probability their price gave when the
 * order was placed, by price bucket: the favourite-longshot bias drawn from the
 * app's own record. Points below the diagonal at low prices are the edge.
 */
object Calibration {
    val BUCKETS = listOf(0.0 to 0.05, 0.05 to 0.10, 0.10 to 0.15, 0.15 to 0.25, 0.25 to 0.50, 0.50 to 0.75, 0.75 to 0.85, 0.85 to 0.90, 0.90 to 0.95, 0.95 to 1.0)

    /** The weatherbot strict-sample ratio (realised / implied YES) for the longshot's bands, for comparison. */
    val RESEARCH_RATIO = mapOf(0.0 to 0.65, 0.05 to 0.76, 0.10 to 0.78, 0.15 to 0.81)

    data class Bucket(val lo: Double, val hi: Double, val n: Int, val implied: Double?, val realised: Double?) {
        val ratio: Double? get() = if (implied != null && realised != null && implied > 0) realised / implied else null
    }

    /** P(YES) implied when the order was placed: the quoted mid for an offer, the bid's own price otherwise. */
    fun impliedYes(q: QuoteRow): Double = when (q.action) {
        "sell_yes" -> com.marketbot.core.strategies.longshot.StageGates.quotedMid(q)
        "buy_yes" -> q.price
        "buy_no" -> 1 - q.price
        else -> Double.NaN
    }

    /** Every settled binary market the strategy quoted (filled or not: the bias is a property of the market). */
    fun table(rows: List<QuoteRow>): List<Bucket> {
        val settled = rows.filter { Outcomes.binary(it) && (it.result == "yes" || it.result == "no") }.distinctBy { it.ticker }
        return BUCKETS.map { (lo, hi) ->
            val b = settled.filter { val p = impliedYes(it); p >= lo && (p < hi || (hi == 1.0 && p <= 1.0)) }
            Bucket(lo, hi, b.size, b.takeIf { it.isNotEmpty() }?.map(::impliedYes)?.average(),
                b.takeIf { it.isNotEmpty() }?.let { l -> l.count { it.result == "yes" }.toDouble() / l.size })
        }.filter { it.n > 0 }
    }
}

/**
 * Deposits and withdrawals, so performance is not confused with money added
 * (Ghostfolio separates the two). The exchanges' trading APIs give no transfer
 * history this app can read, so flows are entered by the user (tagged as such);
 * the app suggests them where equity jumped by more than settled P&L explains.
 */
@kotlinx.serialization.Serializable
data class CashFlow(val venue: String, val at: String, val amount: Double, val note: String = "") {
    val instant: Instant get() = Instant.parse(at)
}

object CashFlows {
    const val KEY = "engine.cash_flows"
    const val DISMISSED = "engine.cash_flow_dismissed"
    private val ser = kotlinx.serialization.builtins.ListSerializer(CashFlow.serializer())

    suspend fun load(state: com.marketbot.core.store.StateStore): List<CashFlow> =
        state.get(KEY)?.let { runCatching { com.marketbot.core.util.JSON.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()
    suspend fun save(state: com.marketbot.core.store.StateStore, l: List<CashFlow>) =
        state.put(KEY, com.marketbot.core.util.JSON.encodeToString(ser, l.sortedBy { it.at }))

    /** [series] (time ms, equity) with every flow up to each point taken out: what trading alone did. */
    fun performance(series: List<Pair<Long, Double>>, flows: List<CashFlow>): List<Pair<Long, Double>> {
        if (series.isEmpty()) return series
        val start = series.first().first
        val sorted = flows.filter { it.instant.toEpochMilli() > start }.sortedBy { it.at }
        return series.map { (t, v) -> t to v - sorted.filter { it.instant.toEpochMilli() <= t }.sumOf { it.amount } }
    }

    /**
     * Jumps in a venue's live equity between two snapshots that settled P&L in between
     * does not explain, at least [minUsd] and [minShare] of the equity before: likely a
     * deposit or withdrawal. Suggestions only; nothing is recorded until confirmed.
     */
    fun candidates(snapshots: List<com.marketbot.core.store.EquitySnapshot>, settled: List<QuoteRow>, known: List<CashFlow>, dismissed: Set<String>,
                   minUsd: Double = 5.0, minShare: Double = 0.03): List<CashFlow> {
        val out = mutableListOf<CashFlow>()
        for (venue in snapshots.map { it.venue }.distinct()) {
            val s = snapshots.filter { it.venue == venue }.sortedBy { it.at }
            for ((a, b) in s.zipWithNext()) {
                val va = a.total?.value ?: a.cash.value; val vb = b.total?.value ?: b.cash.value
                val realised = settled.filter { it.venue == venue && it.resolvedAt != null && it.hypothetical == false && it.pnl != null &&
                    it.resolvedAt.isAfter(a.at) && !it.resolvedAt.isAfter(b.at) }.sumOf { it.pnl!! }
                val residual = (vb - va) - realised
                if (kotlin.math.abs(residual) < maxOf(minUsd, minShare * kotlin.math.abs(va))) continue
                val c = CashFlow(venue, b.at.toString(), Math.round(residual * 100) / 100.0, "suggested: equity moved ${money(vb - va)}, settled P&L ${money(realised)}")
                val already = known.any { it.venue == venue && kotlin.math.abs(java.time.Duration.between(it.instant, b.at).toHours()) < 24 &&
                    kotlin.math.abs(it.amount - c.amount) <= maxOf(1.0, 0.1 * kotlin.math.abs(c.amount)) }
                if (!already && c.at !in dismissed) out += c
            }
        }
        return out
    }
}

/** One row of the paper-variant comparison: settled real (non-hypothetical) paper positions. */
data class VariantRecord(val n: Int, val pnl: Double, val perContract: Double?) {
    companion object {
        fun of(rows: List<QuoteRow>): VariantRecord {
            val r = rows.filter { it.mode == Mode.PAPER && it.resolvedAt != null && it.hypothetical == false && it.pnl != null }
            val contracts = r.sumOf { it.filledContracts ?: it.contracts }
            return VariantRecord(r.size, r.sumOf { it.pnl!! }, if (contracts > 0) r.sumOf { it.pnl!! } / contracts else null)
        }
    }
}
