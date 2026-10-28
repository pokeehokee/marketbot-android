package com.marketbot.core.strategies.longshot

import com.marketbot.core.store.QuoteRow
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The weatherbot stage gates and kill criteria (its CLAUDE.md, "Staged capital
 * plan and kill criteria", 2026-09-08), computed from this app's own live book
 * instead of quoted from September.
 *
 * Gates (pass all to add capital):
 *  - fill rate >= 50% over >= 100 quotes with a fill verdict
 *  - realised / expected losses <= 0.90 over >= 30 losses
 *  - fewer than 3 brake trips in 14 days
 * Kill criteria (stop live trading):
 *  - rolling 60-day loss ratio >= 1.0 with >= 30 losses (the bias is gone)
 *  - fill rate < 40% over the last 14 days (the queue has crowded us out)
 *  - any traded series moves off fee_type quadratic (checked by the strategy)
 */
object StageGates {
    enum class Verdict { PASS, FAIL, INSUFFICIENT, KILL, CLEAR }
    data class Line(val name: String, val verdict: Verdict, val detail: String)

    /** The market's implied P(YES) when the quote was placed: its mid. */
    fun quotedMid(q: QuoteRow): Double {
        fun x(k: String) = (q.extra[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
        val b = x("yes_bid"); val a = x("yes_ask")
        return if (a != null && b != null) (a + b) / 2 else q.price
    }

    private fun hasVerdict(q: QuoteRow) = q.filledContracts != null && (q.resolvedAt != null || q.cancelledAt != null)

    data class Ratio(val losses: Int, val implied: Double) {
        val ratio: Double? get() = if (implied > 0) losses / implied else null
    }

    /** Filled-and-settled rows: YES settlements against the sum of quoted mids. */
    fun lossRatio(rows: List<QuoteRow>): Ratio {
        val settled = rows.filter { it.resolvedAt != null && it.hypothetical == false }
        return Ratio(settled.count { it.result == "yes" }, settled.sumOf { quotedMid(it) })
    }

    fun fillRate(rows: List<QuoteRow>): Pair<Int, Double?> {
        val v = rows.filter { hasVerdict(it) }
        return v.size to if (v.isEmpty()) null else v.count { (it.filledContracts ?: 0.0) > 0 }.toDouble() / v.size
    }

    fun gates(rows: List<QuoteRow>, since: Instant, brakeTrips: List<LocalDate>, now: Instant): List<Line> {
        val stage = rows.filter { !it.placedAt.isBefore(since) }
        val (n, fr) = fillRate(stage)
        val lr = lossRatio(stage)
        val recentTrips = brakeTrips.count { !it.isBefore(LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(14)) }
        fun pct(x: Double?) = x?.let { "%.0f%%".format(it * 100) } ?: "n/a"
        return listOf(
            Line("Fill rate >= 50% over 100+ quotes",
                if (n < 100) Verdict.INSUFFICIENT else if ((fr ?: 0.0) >= 0.5) Verdict.PASS else Verdict.FAIL,
                if (n == 0) "no quotes with a verdict yet" else "${pct(fr)} of $n quotes with a verdict"),
            Line("Losses <= 0.90 of implied over 30+ losses",
                if (lr.losses < 30) Verdict.INSUFFICIENT else if ((lr.ratio ?: 9.0) <= 0.90) Verdict.PASS else Verdict.FAIL,
                if (lr.implied <= 0) "no settled fills yet" else "${lr.losses} losses against %.1f implied (ratio %s)".format(lr.implied, lr.ratio?.let { "%.2f".format(it) } ?: "n/a")),
            Line("Fewer than 3 brake trips in 14 days", if (recentTrips < 3) Verdict.PASS else Verdict.FAIL, "$recentTrips trip(s)"),
        )
    }

    fun kills(rows: List<QuoteRow>, now: Instant): List<Line> {
        val r60 = rows.filter { it.resolvedAt != null && !it.resolvedAt.isBefore(now.minus(Duration.ofDays(60))) }
        val lr = lossRatio(r60)
        val r14 = rows.filter { !it.placedAt.isBefore(now.minus(Duration.ofDays(14))) }
        val (n, fr) = fillRate(r14)
        return listOf(
            Line("60-day loss ratio below 1.0",
                if (lr.losses < 30) Verdict.INSUFFICIENT else if ((lr.ratio ?: 0.0) >= 1.0) Verdict.KILL else Verdict.CLEAR,
                if (lr.implied <= 0) "no settled fills yet" else "${lr.losses} losses, ratio ${lr.ratio?.let { "%.2f".format(it) } ?: "n/a"}"),
            Line("14-day fill rate at least 40%",
                if (n < 40) Verdict.INSUFFICIENT else if ((fr ?: 1.0) < 0.40) Verdict.KILL else Verdict.CLEAR,
                if (n == 0) "no quotes yet" else "${fr?.let { "%.0f%%".format(it * 100) } ?: "n/a"} of $n"),
        )
    }

}
