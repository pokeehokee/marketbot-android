package com.marketbot.core.strategy

import com.marketbot.core.provenance.Source
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.store.QuoteRow
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

fun money(v: Double) = (if (v < 0) "-$" else "$") + "%.2f".format(Locale.ROOT, kotlin.math.abs(v))
fun cents(v: Double) = "%+.2fc".format(Locale.ROOT, v * 100)
fun pct(v: Double) = "%.1f%%".format(Locale.ROOT, v * 100)

/** Delay until the next HH:[minute] local time. */
fun untilNextMinuteOfHour(now: Instant, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Duration {
    val z = ZonedDateTime.ofInstant(now, zone)
    var next = z.withMinute(minute).withSecond(0).withNano(0)
    if (!next.isAfter(z)) next = next.plusHours(1)
    return Duration.between(z, next)
}

/** Start of today in the device's time zone; the daily brakes reset at local midnight, like the originals. */
fun startOfLocalDay(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant =
    ZonedDateTime.ofInstant(now, zone).toLocalDate().atStartOfDay(zone).toInstant()

/** Realised P&L of resolved, non-hypothetical rows resolved since [since]. */
fun realisedSince(rows: List<QuoteRow>, since: Instant?): Double =
    rows.filter { it.resolvedAt != null && it.hypothetical == false && it.pnl != null && (since == null || !it.resolvedAt.isBefore(since)) }
        .sumOf { it.pnl!! }

/** Book summary used by every strategy's stats screen. */
data class BookSummary(
    val settled: Int, val filled: Int, val contracts: Double, val pnl: Double, val wins: Int, val losses: Int,
    val fillRate: Double?, val pnlSource: Source?,
) {
    val perContract: Double? get() = if (contracts > 0) pnl / contracts else null
    val winRate: Double? get() = if (wins + losses > 0) wins.toDouble() / (wins + losses) else null
}

fun summarise(rows: List<QuoteRow>): BookSummary {
    val settled = rows.filter { it.resolvedAt != null }
    val real = settled.filter { it.hypothetical == false }
    val withVerdict = rows.filter { it.filledContracts != null && (it.resolvedAt != null || it.cancelledAt != null) }
    val sources = real.mapNotNull { it.pnlSource }.toSet()
    return BookSummary(
        settled = settled.size,
        filled = real.size,
        contracts = real.sumOf { it.filledContracts ?: it.contracts },
        pnl = real.sumOf { it.pnl ?: 0.0 },
        wins = real.count { (it.pnl ?: 0.0) > 0 },
        losses = real.count { (it.pnl ?: 0.0) < 0 },
        fillRate = if (withVerdict.isEmpty()) null else withVerdict.count { (it.filledContracts ?: 0.0) > 0 }.toDouble() / withVerdict.size,
        pnlSource = when { sources.isEmpty() -> null; sources.size == 1 -> sources.first(); else -> Source.DERIVED },
    )
}

fun bookStats(prefix: String, s: BookSummary, live: Boolean): List<Stat> {
    val src = s.pnlSource ?: if (live) Source.KALSHI_API else Source.PAPER_MODEL
    val origin = if (live) "sum of exchange settlement P&L" else "paper simulator"
    return listOfNotNull(
        Stat("$prefix realised P&L", Sourced(money(s.pnl), src, origin)),
        Stat("$prefix filled & settled", Sourced("${s.filled} of ${s.settled}", Source.DERIVED, "count of ledger rows")),
        s.perContract?.let { Stat("$prefix per contract", Sourced(cents(it), Source.DERIVED, "realised P&L / filled contracts")) },
        s.winRate?.let { Stat("$prefix win rate", Sourced(pct(it), Source.DERIVED, "wins / (wins + losses)")) },
        s.fillRate?.let { Stat("$prefix fill rate", Sourced(pct(it), Source.DERIVED, if (live) "filled / quotes with an exchange fill verdict" else "simulated fills / closed paper quotes")) },
    )
}
