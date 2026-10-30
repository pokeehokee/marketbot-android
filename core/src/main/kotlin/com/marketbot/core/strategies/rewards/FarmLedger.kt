package com.marketbot.core.strategies.rewards

import com.marketbot.core.provenance.Source
import com.marketbot.core.store.Ledger
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.strategy.Mode
import com.marketbot.core.util.num
import com.marketbot.core.util.str
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The farm's money in the shared ledger, so the period table, the daily summary,
 * the record read-out and the protections see it like any other strategy's.
 *
 * The farm has no per-order outcome to settle, so it books one row per UTC day and
 * kind, closed (`resolvedAt` set) from the moment it is written and updated as the
 * day goes on:
 *
 * - [FILLS]: paper realised fill P&L plus maker rebates, from the paper broker.
 *   `contracts` is what filled that day, so a per-contract figure means something.
 * - [REWARD]: live rewards from Polymarket's earnings, one row per day and market,
 *   with the exchange's own `reward` figure.
 * - [REWARD_EST]: the program-formula estimate, paper and live. Always
 *   `hypothetical = true`: shown beside the record, never summed as money.
 *
 * Live fill P&L is not booked: Polymarket's positions carry no realised figure,
 * and the ledger never grades a live position locally. It stays in the venue's
 * equity curve.
 *
 * None of these rows is a binary outcome ([com.marketbot.core.strategy.Outcomes.binary]
 * is false), so markout, calibration, the loss-ratio checks and exposure leave them out:
 * those measure a YES/NO settlement against its price, which the farm does not have.
 */
object FarmLedger {
    const val FILLS = "farm_fills"
    const val REWARD = "farm_reward"
    const val REWARD_EST = "farm_reward_est"
    val ACTIONS = setOf(FILLS, REWARD, REWARD_EST)

    fun key(kind: String, day: String, slug: String? = null) = listOfNotNull(kind, day, slug).joinToString(":")

    private fun dayStart(day: String): Instant = LocalDate.parse(day).atStartOfDay(ZoneOffset.UTC).toInstant()

    /** Set (or, with [add], add to) the day's row of [kind]. Returns the row. */
    suspend fun upsert(
        ledger: Ledger, strategyId: String, mode: Mode, kind: String, day: String, at: Instant,
        pnl: Double, contracts: Double, source: Source, origin: String, add: Boolean, slug: String? = null, result: String = "booked",
    ): QuoteRow {
        val k = key(kind, day, slug)
        val old = ledger.all(strategyId, mode, dayStart(day)).firstOrNull { it.clientOrderId == k }
        val row = (old ?: QuoteRow(strategyId = strategyId, mode = mode, venue = "polymarket", ticker = slug ?: "reward farm",
            action = kind, price = 0.0, contracts = 0.0, placedAt = dayStart(day), clientOrderId = k)).let {
            it.copy(
                contracts = if (add) it.contracts + contracts else contracts,
                filledContracts = if (add) (it.filledContracts ?: 0.0) + contracts else contracts,
                pnl = if (add) (it.pnl ?: 0.0) + pnl else pnl,
                pnlSource = source, pnlOrigin = origin, result = result,
                hypothetical = kind == REWARD_EST, resolvedAt = at,
                fillSource = if (kind == FILLS) source.name else it.fillSource,
            )
        }
        if (old == null) ledger.insert(row) else ledger.update(row.copy(id = old.id))
        return row
    }

    /**
     * Paper fills: books what the broker's realised P&L plus rebates moved since the
     * last pass. [booked] is that total at the last pass, or null on the first pass
     * after an upgrade, when the history before it is left out rather than dumped
     * into today (it stays in the farm's own Results). Returns the new total to store.
     */
    suspend fun bookPaperFills(ledger: Ledger, strategyId: String, total: Double, filledQty: Double, booked: Double?, now: Instant): Double {
        if (booked == null) return total
        val delta = total - booked
        if (kotlin.math.abs(delta) < 1e-9 && filledQty <= 0) return booked
        upsert(ledger, strategyId, Mode.PAPER, FILLS, day(now), now, delta, filledQty, Source.PAPER_MODEL,
            "paper broker: realised fill P&L + maker rebates", add = true)
        return total
    }

    /**
     * Live rewards from `GET /v1/incentives/earnings`, one row per (date, marketSlug).
     * Rows without a date, a market or a numeric `reward` are skipped and counted.
     * The row's time is noon UTC of the reward's own date, so it lands in the period it was earned.
     */
    suspend fun bookEarnings(ledger: Ledger, strategyId: String, rows: List<JsonObject>): Int {
        val byKey = linkedMapOf<Pair<String, String>, Pair<Double, MutableSet<String>>>()
        var skipped = 0
        for (r in rows) {
            val day = r.str("date")?.take(10)?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
            val slug = r.str("marketSlug")
            val v = r.num("reward")
            if (day == null || slug.isNullOrBlank() || v == null) { skipped++; continue }
            val cur = byKey[day to slug]
            byKey[day to slug] = ((cur?.first ?: 0.0) + v) to (cur?.second ?: mutableSetOf()).also { it += (r.str("status") ?: "unknown") }
        }
        for ((k, v) in byKey) {
            val (day, slug) = k
            upsert(ledger, strategyId, Mode.LIVE, REWARD, day, dayStart(day).plusSeconds(12 * 3600), v.first, 0.0, Source.POLYMARKET_API,
                "GET /v1/incentives/earnings: sum of reward for $slug on $day", add = false, slug = slug,
                result = v.second.sorted().joinToString(",") { it.lowercase().removePrefix("reward_status_") })
        }
        return skipped
    }

    fun day(at: Instant): String = LocalDate.ofInstant(at, ZoneOffset.UTC).toString()
}
