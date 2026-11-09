package com.marketbot.core.strategy

import com.marketbot.core.store.QuoteRow
import java.time.Duration
import java.time.ZoneId

/**
 * The fill log: for every order, how long it took to fill and where the price went after, split by
 * how the market settled. The weather longshot's paper book showed losers filling far more often
 * than winners (72% against 42%); this is the instrument that says when, and under which conditions.
 */
object FillLog {
    data class Side(val label: String, val quotes: Int, val filled: Int, val medianMinutesToFill: Double?, val markoutAtFill: Double?, val markout60: Double?) {
        val fillRate: Double? get() = if (quotes > 0) filled.toDouble() / quotes else null
    }

    data class LeadRow(val label: String, val quotes: Int, val fillRate: Double?, val settledFilled: Int, val losses: Int, val implied: Double) {
        val ratio: Double? get() = if (implied > 0) losses / implied else null
    }

    data class Report(val won: Side, val lost: Side, val byLead: List<LeadRow>) {
        /** Fill rate when the market went against the order over when it did not: above 1 is adverse selection. */
        val penalty: Double? get() { val a = lost.fillRate; val b = won.fillRate; return if (a != null && b != null && b > 0) a / b else null }
    }

    /** Minutes from placing to the first fill seen, or null when it never filled or the moment is unknown. */
    fun minutesToFill(q: QuoteRow): Double? {
        if ((q.filledContracts ?: 0.0) <= 0) return null
        if (q.fillSource?.contains("taker") == true) return 0.0
        val seen = Markout.seenAt(q) ?: return null
        return Duration.between(q.placedAt, seen).toSeconds() / 60.0
    }

    private fun verdict(q: QuoteRow) = Outcomes.binary(q) && q.filledContracts != null && (q.resolvedAt != null || q.cancelledAt != null)

    val LEADS = listOf(0.0 to 1.0, 1.0 to 3.0, 3.0 to 8.0, 8.0 to 16.0, 16.0 to 24.0, 24.0 to 1e9)

    fun leadHours(q: QuoteRow): Double? = q.closeTime?.let { Duration.between(q.placedAt, it).toMinutes() / 60.0 }

    fun report(rows: List<QuoteRow>): Report? {
        val v = rows.filter { verdict(it) && Outcomes.lost(it) != null }
        if (v.isEmpty()) return null
        fun side(label: String, l: List<QuoteRow>): Side {
            val f = l.filter { (it.filledContracts ?: 0.0) > 0 }
            val mins = f.mapNotNull { minutesToFill(it) }.sorted()
            return Side(label, l.size, f.size, mins.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] },
                f.mapNotNull { Markout.read(it, 0) }.takeIf { it.isNotEmpty() }?.average(),
                f.mapNotNull { Markout.read(it, 60) }.takeIf { it.isNotEmpty() }?.average())
        }
        val byLead = LEADS.map { (lo, hi) ->
            val l = rows.filter { verdict(it) && (leadHours(it) ?: -1.0).let { h -> h >= lo && h < hi } }
            val settled = l.filter { (it.filledContracts ?: 0.0) > 0 && it.resolvedAt != null && it.hypothetical == false && Outcomes.lost(it) != null }
            LeadRow(if (hi > 1e8) "${lo.toInt()}h+" else "${lo.toInt()}-${hi.toInt()}h", l.size,
                if (l.isEmpty()) null else l.count { (it.filledContracts ?: 0.0) > 0 }.toDouble() / l.size,
                settled.size, settled.count { Outcomes.lost(it) == true }, settled.sumOf { Outcomes.impliedLoss(it) })
        }.filter { it.quotes > 0 }
        return Report(side("Markets that went its way", v.filter { Outcomes.lost(it) == false }),
            side("Markets that went against it", v.filter { Outcomes.lost(it) == true }), byLead)
    }
}

/**
 * Where a strategy makes and loses money: settled real fills grouped by series, city, how far
 * before close the order was placed, the weekday it settled and its price band. A pattern here is
 * a hypothesis for a paper variant, never a filter to bolt on: slice a record enough ways and
 * one slice always looks bad by chance.
 */
object Attribution {
    enum class Dim(val label: String) { SERIES("Series"), CITY("City"), LEAD("Hours before close"), WEEKDAY("Weekday"), BAND("Price") }

    data class Group(val key: String, val n: Int, val contracts: Double, val pnl: Double, val losses: Int, val implied: Double) {
        val perContract: Double? get() = if (contracts > 0) pnl / contracts else null
        val ratio: Double? get() = if (implied > 0) losses / implied else null
    }

    fun key(q: QuoteRow, d: Dim, zone: ZoneId): String = when (d) {
        Dim.SERIES -> q.ticker.substringBefore('-')
        Dim.CITY -> com.marketbot.core.strategies.longshot.WeatherSeries.SERIES_TO_CITY[q.ticker.substringBefore('-')]?.first
            ?: com.marketbot.core.contract.Vocabulary.pmTemperature(q.ticker)?.first?.uppercase() ?: q.ticker.substringBefore('-')
        Dim.LEAD -> FillLog.leadHours(q)?.let { h -> FillLog.LEADS.first { h >= it.first && h < it.second }.let { (lo, hi) -> if (hi > 1e8) "${lo.toInt()}h+" else "${lo.toInt()}-${hi.toInt()}h" } } ?: "unknown"
        Dim.WEEKDAY -> (q.closeTime ?: q.resolvedAt)?.atZone(zone)?.dayOfWeek?.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ROOT) ?: "unknown"
        Dim.BAND -> {
            val p = if (q.action == "sell_yes") q.price else 1 - q.price
            listOf(0.0, 0.02, 0.05, 0.10, 0.15, 0.25, 0.40, 0.60, 0.85, 0.95, 1.01).zipWithNext().first { p >= it.first && p < it.second }
                .let { (lo, hi) -> "%.0f-%.0fc".format(lo * 100, minOf(hi, 1.0) * 100) }
        }
    }

    /** Groups worst first (most money lost), real settled fills only. */
    fun by(rows: List<QuoteRow>, d: Dim, zone: ZoneId = ZoneId.systemDefault()): List<Group> =
        rows.filter { it.resolvedAt != null && it.hypothetical == false && it.pnl != null }
            .groupBy { key(it, d, zone) }
            .map { (k, l) ->
                val b = l.filter { Outcomes.binary(it) && Outcomes.lost(it) != null }
                Group(k, l.size, l.sumOf { it.filledContracts ?: it.contracts }, l.sumOf { it.pnl!! },
                    b.count { Outcomes.lost(it) == true }, b.sumOf { Outcomes.impliedLoss(it) })
            }.sortedBy { it.pnl }
}
