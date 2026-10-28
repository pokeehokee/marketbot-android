package com.marketbot.core.store

import com.marketbot.core.provenance.Source
import com.marketbot.core.strategy.Mode
import java.time.Instant
import kotlin.random.Random

/**
 * Random operation sequences against any [Ledger], and the invariants every
 * implementation must keep. Lives in main so the app's Room ledger is checked
 * by exactly the same generator as the in-memory reference.
 */
object LedgerChecks {
    /** Apply [n] random inserts, fills, cancels and settlements, as strategies do, and return what was done. */
    suspend fun randomHistory(l: Ledger, seed: Int, n: Int = 400, strategies: List<String> = listOf("a", "b")): List<Long> {
        val r = Random(seed)
        val ids = mutableListOf<Long>()
        val t0 = Instant.parse("2026-10-01T00:00:00Z")
        repeat(n) { step ->
            val now = t0.plusSeconds(step * 600L)
            val open = ids.mapNotNull { l.byId(it) }.filter { it.resolvedAt == null }
            when (if (open.isEmpty()) 0 else r.nextInt(5)) {
                0 -> ids += l.insert(QuoteRow(strategyId = strategies[r.nextInt(strategies.size)], mode = if (r.nextBoolean()) Mode.LIVE else Mode.PAPER,
                    venue = "kalshi", ticker = "T${r.nextInt(30)}", action = "sell_yes", price = 0.02 + r.nextInt(20) / 100.0,
                    contracts = (1 + r.nextInt(10)).toDouble(), placedAt = now, orderId = "o$step"))
                1 -> open.random(r).let { q -> l.update(q.copy(filledContracts = minOf(q.contracts, (q.filledContracts ?: 0.0) + 1 + r.nextInt(3)), fillCheckedAt = now)) }
                2 -> open.random(r).let { q -> if (q.cancelledAt == null) l.update(q.copy(cancelledAt = now, filledContracts = q.filledContracts ?: 0.0)) }
                else -> open.random(r).let { q ->
                    val filled = q.filledContracts ?: 0.0
                    val yes = r.nextInt(10) == 0
                    val pnl = (if (yes) -(1 - q.price) else q.price) * (if (filled > 0) filled else q.contracts)
                    l.update(q.copy(resolvedAt = now, result = if (yes) "yes" else "no", pnl = pnl, hypothetical = filled <= 0,
                        filledContracts = filled, pnlSource = if (filled <= 0) Source.DERIVED else if (q.mode == Mode.LIVE) Source.KALSHI_API else Source.PAPER_MODEL))
                }
            }
        }
        return ids
    }

    /** Every violated invariant, as a sentence; empty when the ledger is sound. */
    suspend fun violations(l: Ledger, ids: List<Long>, strategies: List<String> = listOf("a", "b")): List<String> {
        val out = mutableListOf<String>()
        val rows = ids.map { l.byId(it) ?: return listOf("row $it lost") }
        for (s in strategies) for (m in listOf(Mode.LIVE, Mode.PAPER)) {
            val all = l.all(s, m)
            if (all.any { it.mode != m || it.strategyId != s }) out += "all($s, $m) returned another mode or strategy"
            if (all.map { it.id }.toSet().size != all.size) out += "all($s, $m) returned a row twice"
            if (all.size != rows.count { it.strategyId == s && it.mode == m }) out += "all($s, $m) count differs from inserted"
            val open = l.open(s, m)
            if (open.any { it.resolvedAt != null }) out += "open($s, $m) returned a resolved row"
            if (open.size != all.count { it.resolvedAt == null }) out += "open($s, $m) count differs"
            val realised = com.marketbot.core.strategy.realisedSince(all, null)
            val expect = all.filter { it.resolvedAt != null && it.hypothetical == false }.sumOf { it.pnl ?: 0.0 }
            if (kotlin.math.abs(realised - expect) > 1e-9) out += "realised P&L for $s/$m includes something it must not"
            if (all.any { it.hypothetical == true && it.pnlSource == Source.KALSHI_API }) out += "a hypothetical row claims an exchange P&L"
            val summary = com.marketbot.core.strategy.summarise(all)
            if (kotlin.math.abs(summary.pnl - expect) > 1e-9) out += "summary P&L for $s/$m differs from realised"
            if (summary.filled != all.count { it.resolvedAt != null && it.hypothetical == false }) out += "summary counts a row twice or a hypothetical one"
        }
        if (rows.any { (it.filledContracts ?: 0.0) > it.contracts + 1e-9 }) out += "a row filled more than it quoted"
        return out
    }
}
