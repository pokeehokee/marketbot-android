package com.marketbot.core.strategy

import com.marketbot.core.store.QuoteRow
import java.time.Duration
import java.time.ZoneId

/**
 * Money at risk on one city and day across every strategy. The weather longshot and the
 * Polymarket temperature longshot can both sell buckets on New York's high for the same day; one
 * hot morning then hits both books at once, and no single strategy's cap sees it.
 */
object CorrelatedExposure {
    /** Dollars of worst-case loss allowed per city and day on the live book; 0 turns the cap off. */
    const val CAP_KEY = "risk.city_day_cap"
    const val DEFAULT_CAP = 25.0

    private val PM_CITY = mapOf("nyc" to "NYC", "mdw" to "CHI", "chi" to "CHI", "mia" to "MIA", "lax" to "LAX", "sfo" to "SFO", "phl" to "PHIL")
    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    private val KALSHI_DATE = Regex("^(\\d{2})([A-Z]{3})(\\d{2})")
    private val PM_DATE = Regex("-(\\d{4}-\\d{2}-\\d{2})-")

    /** "NYC 2026-11-09" for a temperature market on either venue, else null. */
    fun key(ticker: String): String? {
        val pm = com.marketbot.core.contract.Vocabulary.pmTemperature(ticker)
        if (pm != null) {
            val day = PM_DATE.find(ticker)?.groupValues?.get(1) ?: return null
            return "${PM_CITY[pm.first] ?: pm.first.uppercase()} $day"
        }
        val series = ticker.substringBefore('-')
        val city = com.marketbot.core.strategies.longshot.WeatherSeries.SERIES_TO_CITY[series]?.first ?: return null
        val m = KALSHI_DATE.find(ticker.substringAfter('-', "")) ?: return null
        val month = MONTHS.indexOf(m.groupValues[2]) + 1
        if (month == 0) return null
        return "$city 20${m.groupValues[1]}-%02d-${m.groupValues[3]}".format(month)
    }

    /** The most a row can still lose: what is resting or held, times the collateral per contract. */
    fun worstCase(q: QuoteRow): Double {
        if (q.resolvedAt != null || !Outcomes.binary(q)) return 0.0
        val n = if (q.cancelledAt != null) (q.filledContracts ?: 0.0) else q.contracts
        return n * Outcomes.lockPerContract(q)
    }

    fun byKey(open: List<QuoteRow>): Map<String, Double> =
        open.mapNotNull { q -> key(q.ticker)?.let { it to worstCase(q) } }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
            .filterValues { it > 0 }
}

/**
 * Whether a paper variant beats its main strategy, decided only on markets both were running
 * for (rows placed after the later of the two started), and only when the difference is real:
 * a 95% interval on the per-contract difference that excludes zero, with 30+ settled each.
 */
object VariantVerdict {
    data class Verdict(val text: String, val decided: Boolean, val better: String?)

    private fun perContract(rows: List<QuoteRow>) = rows.filter { it.resolvedAt != null && it.hypothetical == false && it.pnl != null }
        .mapNotNull { q -> (q.filledContracts ?: q.contracts).takeIf { it > 0 }?.let { q.pnl!! / it } }

    fun compare(main: List<QuoteRow>, variant: List<QuoteRow>, mainName: String, variantName: String, minEach: Int = 30): Verdict {
        val start = listOfNotNull(main.minOfOrNull { it.placedAt }, variant.minOfOrNull { it.placedAt }).maxOrNull()
            ?: return Verdict("Not started: the variant has no orders yet.", false, null)
        val a = perContract(main.filter { !it.placedAt.isBefore(start) })
        val b = perContract(variant.filter { !it.placedAt.isBefore(start) })
        if (a.size < minEach || b.size < minEach)
            return Verdict("Keep running: ${a.size} and ${b.size} settled since both started; a verdict needs $minEach each.", false, null)
        fun mean(x: List<Double>) = x.average()
        fun varOf(x: List<Double>): Double { val m = mean(x); return x.sumOf { (it - m) * (it - m) } / (x.size - 1) }
        val diff = mean(b) - mean(a)
        val se = kotlin.math.sqrt(varOf(a) / a.size + varOf(b) / b.size)
        val lo = diff - 1.96 * se; val hi = diff + 1.96 * se
        val range = "${cents(diff)} per contract (95% ${cents(lo)} to ${cents(hi)}) on ${a.size} and ${b.size} settled"
        return when {
            lo > 0 -> Verdict("$variantName beats $mainName: $range.", true, variantName)
            hi < 0 -> Verdict("$mainName beats $variantName: $range.", true, mainName)
            1.96 * se < 0.01 -> Verdict("No meaningful difference: $range. Either setting will do.", true, null)
            else -> {
                val need = if (kotlin.math.abs(diff) > 1e-6) ((2.8 * 2.8) * (varOf(a) + varOf(b)) / (diff * diff)).toInt() else Int.MAX_VALUE
                Verdict("Keep running: $range" + (if (need < 100_000) "; about $need settled each would settle a difference this size." else "."), false, null)
            }
        }
    }
}

/** What each dollar of collateral earned per day it was locked: the fair way to compare strategies that tie money up for different lengths of time. */
object CapitalEfficiency {
    data class Line(val strategyId: String, val settled: Int, val pnl: Double, val dollarDays: Double) {
        /** Earned per dollar per day locked, in cents. */
        val centsPerDollarDay: Double? get() = if (dollarDays > 0) 100 * pnl / dollarDays else null
    }

    fun of(strategyId: String, rows: List<QuoteRow>): Line? {
        val s = rows.filter { it.resolvedAt != null && it.hypothetical == false && it.pnl != null && Outcomes.binary(it) && (it.filledContracts ?: 0.0) > 0 }
        if (s.isEmpty()) return null
        val dd = s.sumOf { q ->
            val days = maxOf(1.0 / 24, Duration.between(q.placedAt, q.resolvedAt).toMinutes() / 1440.0)
            q.filledContracts!! * Outcomes.lockPerContract(q) * days
        }
        return Line(strategyId, s.size, s.sumOf { it.pnl!! }, dd)
    }
}

/** Realised live P&L by tax year, one row per settled position, from the exchange-graded ledger. */
object TaxYear {
    const val HEADER = "settled_at,venue,market,description,action,contracts,price,realised_pnl,pnl_source,strategy"

    fun rows(all: List<QuoteRow>, year: Int, zone: ZoneId): List<QuoteRow> = all.filter {
        it.mode == Mode.LIVE && it.resolvedAt != null && it.hypothetical == false && it.pnl != null && it.resolvedAt.atZone(zone).year == year
    }.sortedBy { it.resolvedAt }

    fun csv(all: List<QuoteRow>, year: Int, zone: ZoneId): String = buildString {
        val r = rows(all, year, zone)
        append("# Realised live P&L for $year (time zone ${zone.id}). Not tax advice: confirm against each venue's own tax documents.\n")
        r.groupBy { it.venue }.forEach { (v, l) -> append("# $v: ${l.size} settled positions, total ${money(l.sumOf { it.pnl!! })}\n") }
        append(HEADER).append('\n')
        for (q in r) {
            fun f(x: Any?) = x?.toString()?.let { s -> if (s.any { it == ',' || it == '"' }) "\"" + s.replace("\"", "\"\"") + "\"" else s } ?: ""
            append(listOf(q.resolvedAt!!.atZone(zone).toLocalDate(), q.venue, q.ticker, com.marketbot.core.util.Tickers.describe(q.ticker), q.action,
                q.filledContracts ?: q.contracts, q.price, "%.2f".format(java.util.Locale.ROOT, q.pnl), q.pnlSource, q.strategyId).joinToString(",") { f(it) })
            append('\n')
        }
    }
}

/** A market the user asked to hear about when its price moves. */
@kotlinx.serialization.Serializable
data class WatchItem(val venue: String, val ticker: String, val ref: Double? = null, val moveCents: Int = 5, val label: String = "") {
    /** The alert when [mid] has moved [moveCents] or more from the reference, else null. */
    fun alert(mid: Double): String? {
        val r = ref ?: return null
        val d = (mid - r) * 100
        return if (kotlin.math.abs(d) + 1e-9 >= moveCents) "${label.ifBlank { ticker }} moved from %.0fc to %.0fc".format(r * 100, mid * 100) else null
    }

    companion object {
        const val KEY = "watchlist"
        private val ser = kotlinx.serialization.builtins.ListSerializer(serializer())
        suspend fun load(state: com.marketbot.core.store.StateStore): List<WatchItem> =
            state.get(KEY)?.let { runCatching { com.marketbot.core.util.JSON.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()
        suspend fun save(state: com.marketbot.core.store.StateStore, l: List<WatchItem>) = state.put(KEY, com.marketbot.core.util.JSON.encodeToString(ser, l))
    }
}

