package com.marketbot.core.strategy

import com.marketbot.core.kalshi.KalshiClient
import com.marketbot.core.kalshi.settlementPnl
import com.marketbot.core.store.QuoteRow
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/**
 * Read-outs computed from the ledger (and, for reconciliation, the exchange):
 * what a strategy's own record says about whether its edge is still there.
 * Everything here works on any strategy whose rows are binary-outcome orders
 * (sell_yes, buy_yes, buy_no); the reward farm's two-sided quoting is not one.
 */
object Outcomes {
    fun binary(q: QuoteRow) = q.action == "sell_yes" || q.action == "buy_yes" || q.action == "buy_no"

    /** Whether the position lost at settlement, or null before it settled. */
    fun lost(q: QuoteRow): Boolean? = when (q.result) {
        null -> null
        else -> when (q.action) { "sell_yes" -> q.result == "yes"; "buy_yes" -> q.result == "no"; "buy_no" -> q.result == "yes"; else -> null }
    }

    /**
     * The market's own probability that the position loses, when it was placed.
     * Selling YES at p loses with probability p (the quoted mid where the row kept
     * it); buying at p loses with probability 1 - p.
     */
    fun impliedLoss(q: QuoteRow): Double = when (q.action) {
        "sell_yes" -> com.marketbot.core.strategies.longshot.StageGates.quotedMid(q)
        else -> 1.0 - q.price
    }

    /** Money locked per contract until settlement: (1 - p) to sell YES at p, p to buy at p. */
    fun lockPerContract(q: QuoteRow): Double = if (q.action == "sell_yes") 1.0 - q.price else q.price
}

/** One health line: a kill criterion or a stage gate, and its verdict on the record so far. */
@Serializable
data class Check(val name: String, val verdict: Verdict, val detail: String) {
    enum class Verdict { PASS, CLEAR, INSUFFICIENT, FAIL, KILL }
}

object KillCriteria {
    /**
     * The generic criterion for binary strategies: over the last [days], positions
     * that lost against the number the prices implied. At or above 1.0 the market's
     * prices were right (or worse) and the edge the strategy exists for is gone.
     * Needs [minLosses] real losses before it can say so, because a handful of
     * losses is noise in either direction.
     */
    fun lossRatio(rows: List<QuoteRow>, now: Instant, days: Long = 60, minLosses: Int = 30): Check {
        val settled = rows.filter { Outcomes.binary(it) && it.resolvedAt != null && it.hypothetical == false &&
            !it.resolvedAt.isBefore(now.minus(Duration.ofDays(days))) && Outcomes.lost(it) != null }
        val losses = settled.count { Outcomes.lost(it) == true }
        val implied = settled.sumOf { Outcomes.impliedLoss(it) }
        val ratio = if (implied > 0) losses / implied else null
        val v = when { losses < minLosses -> Check.Verdict.INSUFFICIENT; (ratio ?: 0.0) >= 1.0 -> Check.Verdict.KILL; else -> Check.Verdict.CLEAR }
        return Check("$days-day losses below what prices implied", v,
            if (settled.isEmpty()) "no settled fills yet" else "$losses losses against %.1f implied (ratio %s; needs $minLosses losses to judge)"
                .format(implied, ratio?.let { "%.2f".format(it) } ?: "n/a"))
    }
}

/**
 * Fill rate split by how the market settled, per price band. A resting order is
 * adversely selected when it fills more often on markets that go against it:
 * the "penalty" is fill-rate-when-lost over fill-rate-when-won. Measured live in
 * the source repo at x1.15 to x1.49 by band (2026-09-13).
 */
object AdverseSelection {
    data class Band(val lo: Double, val hi: Double, val quotes: Int, val lostQuotes: Int, val fillWhenLost: Double?, val fillWhenWon: Double?) {
        val penalty: Double? get() = if (fillWhenLost != null && fillWhenWon != null && fillWhenWon > 0) fillWhenLost / fillWhenWon else null
        val label: String get() = "%.0f-%.0fc".format(lo * 100, hi * 100)
    }

    val LONGSHOT_BANDS = listOf(0.02 to 0.05, 0.05 to 0.10, 0.10 to 0.15, 0.15 to 0.25)
    val DEEP_BANDS = listOf(0.85 to 0.94, 0.94 to 0.97, 0.97 to 1.0)

    /** Rows with a fill verdict (the fill count is known and the order is finished) and a result. */
    fun table(rows: List<QuoteRow>, bands: List<Pair<Double, Double>>): List<Band> = bands.map { (lo, hi) ->
        val r = rows.filter { Outcomes.binary(it) && it.price >= lo && it.price < hi && it.filledContracts != null &&
            (it.resolvedAt != null || it.cancelledAt != null) && Outcomes.lost(it) != null }
        val lostRows = r.filter { Outcomes.lost(it) == true }; val wonRows = r.filter { Outcomes.lost(it) == false }
        fun rate(x: List<QuoteRow>) = if (x.isEmpty()) null else x.count { (it.filledContracts ?: 0.0) > 0 }.toDouble() / x.size
        Band(lo, hi, r.size, lostRows.size, rate(lostRows), rate(wonRows))
    }
}

/** Money locked by open and held positions over time, from the ledger. */
object Collateral {
    /** Locked collateral sampled every [step] from [from] to [to]. */
    fun series(rows: List<QuoteRow>, from: Instant, to: Instant, step: Duration = Duration.ofMinutes(30)): List<Pair<Instant, Double>> {
        val binary = rows.filter { Outcomes.binary(it) && (it.resolvedAt == null || it.resolvedAt.isAfter(from)) && it.placedAt.isBefore(to) }
        val out = ArrayList<Pair<Instant, Double>>()
        var t = from
        while (!t.isAfter(to)) { out += t to lockedAt(binary, t); t = t.plus(step) }
        return out
    }

    /**
     * Locked at [t]: the whole order from placement until it is withdrawn, then only
     * what filled, until settlement.
     */
    fun lockedAt(rows: List<QuoteRow>, t: Instant): Double = rows.sumOf { q ->
        if (t.isBefore(q.placedAt) || (q.resolvedAt != null && !t.isBefore(q.resolvedAt))) 0.0
        else {
            val n = if (q.cancelledAt != null && !t.isBefore(q.cancelledAt)) (q.filledContracts ?: 0.0) else q.contracts
            n * Outcomes.lockPerContract(q)
        }
    }
}

/** Ledger P&L against the exchange's own settlement figures, per market. */
@Serializable
data class PnlReconciliation(
    val at: String, val compared: Int, val mismatches: List<Mismatch>, val notAtExchange: List<String>, val error: String? = null,
) {
    @Serializable data class Mismatch(val ticker: String, val ledger: Double, val exchange: Double)
    val clean: Boolean get() = error == null && mismatches.isEmpty() && notAtExchange.isEmpty()
    val summary: String get() = when {
        error != null -> "Could not read Kalshi's settlements: $error"
        compared == 0 && notAtExchange.isEmpty() -> "Nothing to compare yet: no live Kalshi position has settled."
        clean -> "All $compared settled market(s) match Kalshi's settlement figures to the cent."
        else -> "${mismatches.size} of $compared market(s) differ from Kalshi" +
            (if (notAtExchange.isNotEmpty()) "; ${notAtExchange.size} settled in the app but not found at Kalshi" else "") +
            ". Kalshi's figure is the true one; a market you also traded by hand differs by design."
    }
}

object PnlReconcile {
    /**
     * Sums the app's realised P&L per Kalshi market and compares it with the
     * settlement payload (revenue - cost - fee). A truncated listing is an error,
     * never a "missing" verdict.
     */
    suspend fun kalshi(client: KalshiClient, liveRows: List<QuoteRow>, now: Instant, lookbackDays: Long = 30): PnlReconciliation {
        val mine = liveRows.filter { it.venue == "kalshi" && it.resolvedAt != null && it.hypothetical == false && it.pnl != null &&
            !it.resolvedAt.isBefore(now.minus(Duration.ofDays(lookbackDays))) }
        if (mine.isEmpty()) return PnlReconciliation(now.toString(), 0, emptyList(), emptyList())
        val exchange = try { client.settlementsByTicker() } catch (e: Exception) {
            return PnlReconciliation(now.toString(), 0, emptyList(), emptyList(), e.message ?: e.javaClass.simpleName)
        }
        val byTicker = mine.groupBy { it.ticker }.mapValues { (_, r) -> r.sumOf { it.pnl!! } }
        val mismatches = mutableListOf<PnlReconciliation.Mismatch>(); val missing = mutableListOf<String>(); var compared = 0
        for ((t, ledger) in byTicker) {
            val s = exchange[t]
            if (s == null) { missing += t; continue }
            compared++
            val ex = settlementPnl(s).value
            if (kotlin.math.abs(ex - ledger) > 0.005) mismatches += PnlReconciliation.Mismatch(t, ledger, ex)
        }
        return PnlReconciliation(now.toString(), compared, mismatches, missing)
    }
}
