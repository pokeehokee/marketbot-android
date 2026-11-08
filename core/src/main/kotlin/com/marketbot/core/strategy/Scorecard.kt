package com.marketbot.core.strategy

import com.marketbot.core.store.QuoteRow
import java.time.Duration
import java.time.Instant

/**
 * How a strategy is doing in THIS app, against what its evidence promised. The strategy cards
 * used to show only the evidence level, which is history: a method marked "Made money live"
 * can be losing in paper here, and one marked "Tested: no edge" can be ahead. This is the badge
 * that follows the app's own record.
 */
object RecordHealth {
    enum class Verdict(val label: String) {
        TOO_EARLY("Too early to judge"),
        ON_TRACK("In line with its evidence"),
        BELOW("Below its evidence"),
        LOSING("Losing"),
    }

    data class Health(val mode: Mode, val verdict: Verdict, val settled: Int, val record: RecordVsExpected?, val expected: Double?) {
        /** "Losing in paper: -1.33c/contract (95% -2.9c to +0.2c) on 333 settled; evidence +2.64c." */
        fun line(): String = buildString {
            append(verdict.label)
            if (verdict != Verdict.TOO_EARLY) append(" in ").append(mode.name.lowercase())
            val r = record
            if (r != null) append(": ").append(cents(r.perContract)).append("/contract (95% ").append(cents(r.low)).append(" to ").append(cents(r.high))
                .append(") on ").append(settled).append(" settled")
            else append(": ").append(settled).append(" settled")
            expected?.let { append("; evidence ").append(cents(it)) }
            if (verdict == Verdict.TOO_EARLY) append("; judged from $MIN_SETTLED")
        }
    }

    const val MIN_SETTLED = 30

    /**
     * LOSING: the record's whole 95% interval is below zero. BELOW: it is below what the evidence
     * expects (or, with no expectation, the record is negative). ON_TRACK otherwise. Under
     * [MIN_SETTLED] settled positions it is too early to say anything.
     */
    fun of(mode: Mode, rows: List<QuoteRow>, expectation: Expectation?): Health? {
        val real = rows.filter { it.mode == mode && it.resolvedAt != null && it.hypothetical == false && it.pnl != null }
        if (real.isEmpty()) return null
        val r = RecordVsExpected.of(real)
        val exp = expectation?.perContract
        val v = when {
            real.size < MIN_SETTLED || r == null -> Verdict.TOO_EARLY
            r.high < 0 -> Verdict.LOSING
            exp != null && r.high < exp -> Verdict.BELOW
            exp == null && r.perContract < 0 -> Verdict.BELOW
            else -> Verdict.ON_TRACK
        }
        return Health(mode, v, real.size, r, exp)
    }
}

/**
 * When a paper strategy has earned a look at live money: the rule set when the commodity and
 * Polymarket candidates went to paper (about four weeks, 100+ fills, enough losses to judge),
 * plus the realised-against-implied loss ratio the weather stage gates use.
 */
object Graduation {
    const val DAYS = 28
    const val FILLS = 100
    const val LOSSES = 10
    const val MAX_RATIO = 0.90

    fun gates(rows: List<QuoteRow>, now: Instant): List<Check> {
        val paper = rows.filter { it.mode == Mode.PAPER }
        val settled = paper.filter { it.resolvedAt != null && it.hypothetical == false && it.pnl != null }
        val binary = settled.filter { Outcomes.binary(it) && Outcomes.lost(it) != null }
        val losses = binary.count { Outcomes.lost(it) == true }
        val implied = binary.sumOf { Outcomes.impliedLoss(it) }
        val ratio = if (implied > 0) losses / implied else null
        val first = paper.minOfOrNull { it.placedAt }
        val days = first?.let { Duration.between(it, now).toDays() } ?: 0
        val rec = RecordVsExpected.of(settled)
        fun c(name: String, v: Check.Verdict, d: String) = Check(name, v, d)
        val enough = settled.size >= FILLS && losses >= LOSSES
        return listOf(
            c("$DAYS days of paper record", if (days >= DAYS) Check.Verdict.PASS else Check.Verdict.INSUFFICIENT, "$days of $DAYS days"),
            c("$FILLS+ settled fills", if (settled.size >= FILLS) Check.Verdict.PASS else Check.Verdict.INSUFFICIENT, "${settled.size} of $FILLS"),
            c("$LOSSES+ losses, enough to judge", if (losses >= LOSSES) Check.Verdict.PASS else Check.Verdict.INSUFFICIENT, "$losses of $LOSSES"),
            c("Losses at most ${"%.2f".format(MAX_RATIO)} of what prices implied",
                when { !enough -> Check.Verdict.INSUFFICIENT; (ratio ?: 9.0) <= MAX_RATIO -> Check.Verdict.PASS; else -> Check.Verdict.FAIL },
                if (implied <= 0) "no settled binary fills yet" else "$losses lost against %.1f implied (ratio %s)".format(implied, ratio?.let { "%.2f".format(it) } ?: "n/a")),
            c("Making money per contract",
                when { !enough || rec == null -> Check.Verdict.INSUFFICIENT; rec.perContract > 0 -> Check.Verdict.PASS; else -> Check.Verdict.FAIL },
                rec?.let { "${cents(it.perContract)}/contract on ${it.n} settled" } ?: "no settled fills yet"),
        )
    }

    fun ready(gates: List<Check>) = gates.isNotEmpty() && gates.all { it.verdict == Check.Verdict.PASS }

    /** "3 of 5 gates passed" style progress. */
    fun progress(gates: List<Check>) = gates.count { it.verdict == Check.Verdict.PASS } to gates.size
}
