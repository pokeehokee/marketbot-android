package com.marketbot.core.strategies.bets

import com.marketbot.core.contract.Vocabulary
import com.marketbot.core.provenance.Source
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.store.StateStore
import com.marketbot.core.store.Stores
import com.marketbot.core.strategy.Evidence
import com.marketbot.core.strategy.EvidenceLevel
import com.marketbot.core.strategy.Help
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.Param
import com.marketbot.core.strategy.PreflightResult
import com.marketbot.core.strategy.Requirement
import com.marketbot.core.strategy.RunReport
import com.marketbot.core.strategy.Stat
import com.marketbot.core.strategy.Strategy
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.strategy.Venue
import com.marketbot.core.strategy.money
import com.marketbot.core.util.JSON
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import kotlin.math.floor

/**
 * One research bet, as Claude writes it into the sync repository's proposals file. It names one
 * exact Kalshi market and side; nothing else is ever traded for it.
 */
@Serializable
data class BetSpec(
    val ticker: String,
    /** "yes" or "no": the side the research says will win. */
    val side: String,
    /** Highest price to pay for that side, in dollars (0.47 = 47c). Above it the bet waits. */
    val maxPrice: Double,
    /** Dollars to put on it; contracts = floor(stake / price). */
    val stakeUsd: Double,
    /** The research's probability that [side] wins, 0-1, when it gives one. */
    val probability: Double? = null,
    /** The case for it, in a sentence or two. */
    val thesis: String = "",
    /** What would make it wrong (kept on the record; the app does not act on it). */
    val against: String = "",
    /** Where the research came from. */
    val source: String = "",
)

/** Where a bet stands. */
@Serializable
data class BetState(
    val id: String,
    val spec: BetSpec,
    val approvedAt: String,
    /** waiting (price above the limit), placed, settled, missed (closed before the price came in), invalid. */
    val status: String = "waiting",
    val detail: String = "",
    /** The market's result once settled, whether or not the bet was placed. */
    val result: String? = null,
)

/** One line of the bet record, as written to `bets/results.json`. */
@Serializable
data class BetRecord(
    val id: String,
    val ticker: String,
    val market: String,
    val side: String,
    val status: String,
    val detail: String,
    val approvedAt: String,
    val probability: Double? = null,
    val thesis: String = "",
    val against: String = "",
    val source: String = "",
    val maxPrice: Double,
    val stakeUsd: Double,
    val placedAt: String? = null,
    val pricePaid: Double? = null,
    val contracts: Double? = null,
    val fee: Double? = null,
    val result: String? = null,
    /** True when the market settled on the side the research picked. */
    val predictionCorrect: Boolean? = null,
    /** (probability - outcome)^2 for the research's probability, when it gave one. */
    val brier: Double? = null,
    val paperPnl: Double? = null,
)

/**
 * Paper-only one-off bets from research: each is a single Kalshi market and side that you approved
 * on the phone from a Claude sync proposal. It buys that side at the market's ask once the ask is
 * at or under the bet's limit (paying the taker fee, as a real market order would), holds it to
 * settlement and records whether the research was right. It never chooses a market itself.
 */
class ResearchBets(override val id: String = ID) : Strategy {
    override val name = "Research bets"
    override val venue = Venue.KALSHI
    override val summary = "Paper record of one-off bets from research: only the exact markets you approve, each graded at settlement."
    override val help = Help(
        makes = "Nothing by design. It answers one question per bet: was the research right, and what would the bet have made?",
        loses = "A paper bet loses what it would have lost. The record is the point: a run of research that is right less often than the price implied is research to stop trusting.",
        notes = listOf(
            "Bets arrive through Claude sync and wait for your approval; approving the first one turns this on in paper (screen lock).",
            "Buys the chosen side at the ask once the ask is at or under the bet's limit, with Kalshi's taker fee; until then it waits. If the market closes first, the bet is recorded as missed, with the market's result.",
            "Every bet, with its thesis, price, outcome and whether the prediction was right, goes back to bets/results.json at each sync.",
            "Paper only: nothing is sent to the exchange.",
        ),
    )
    override val evidence = Evidence(EvidenceLevel.UNPROVEN, "Each bet is its own test; the record of bets is the evidence.", emptyList(), "this app's paper record")
    override val supportedModes: Set<Mode> = setOf(Mode.OFF, Mode.PAPER)
    override val params: List<Param> = listOf(
        Param.Num("paper_cash", "Paper wallet", "Starting cash of the virtual account the bets are paid from.", 30.0, 1.0, 100000.0, "$"),
        Param.Int_("every_min", "Check every", "Minutes between checks of the bets' markets.", 10, 2, 60, "min"),
    )

    override fun nextDelay(ctx: StrategyContext): Duration = Duration.ofMinutes(ctx.config.int("every_min").toLong())
    override fun effects(config: StrategyConfig) = listOf(
        "Paper only. Trades nothing but the markets of the bets you approve.",
        "Bets are paid from a ${money(config.num("paper_cash"))} paper wallet; a bet that does not fit what is left waits.",
    )

    override suspend fun runOnce(ctx: StrategyContext): RunReport {
        if (ctx.mode != Mode.PAPER) return RunReport("off")
        val now = ctx.clock.now()
        val bets = load(ctx.stores.state)
        if (bets.isEmpty()) return RunReport("no bets")
        val rows = ctx.stores.ledger.all(id, Mode.PAPER)
        val byBet = rows.associateBy { betIdOf(it) }
        val updated = ArrayList<BetState>()
        var placed = 0; var settled = 0
        val cash = ctx.config.num("paper_cash") + rows.sumOf { it.pnl ?: 0.0 } -
            rows.filter { it.resolvedAt == null }.sumOf { it.price * (it.filledContracts ?: 0.0) + (feeOf(it) ?: 0.0) }
        var free = cash
        for (b in bets) {
            if (b.status in setOf("settled", "missed", "invalid")) { updated += b; continue }
            val read = runCatching { ctx.kalshi.market(b.spec.ticker) }
            val m = read.getOrNull()
            if (m == null) { updated += b.copy(detail = "market unreadable: ${read.exceptionOrNull()?.message}"); continue }
            if (m.ticker.isEmpty()) { updated += b.copy(status = "invalid", detail = "Kalshi has no market ${b.spec.ticker}"); continue }
            val st = m.status?.lowercase()
            val result = m.result?.takeIf { st in Vocabulary.KALSHI_SETTLED && (it == "yes" || it == "no") }
            val row = byBet[b.id]
            if (row != null) {
                if (result != null && row.resolvedAt == null) {
                    val n = row.filledContracts ?: 0.0
                    val fee = feeOf(row) ?: 0.0
                    val pnl = (if (result == b.spec.side) n else 0.0) - row.price * n - fee
                    ctx.stores.ledger.update(row.copy(resolvedAt = now, result = result, pnl = pnl, pnlSource = Source.PAPER_MODEL,
                        pnlOrigin = "paper: payout - price x contracts - taker fee; result: GET /markets/${b.spec.ticker}#result", hypothetical = false))
                    ctx.trade("settled", "Research bet ${b.spec.ticker} settled ${result.uppercase()}: the research picked ${b.spec.side.uppercase()} " +
                        "(${if (result == b.spec.side) "right" else "wrong"}), paper ${if (pnl >= 0) "made" else "lost"} ${money(kotlin.math.abs(pnl))}")
                    updated += b.copy(status = "settled", result = result, detail = "settled ${result.uppercase()}"); settled++
                } else updated += b
                continue
            }
            if (result != null || (st != null && st !in setOf("active", "open"))) {
                updated += b.copy(status = "missed", result = result,
                    detail = "market ${st ?: "closed"} before the price reached the limit" + (result?.let { "; settled ${it.uppercase()}" } ?: ""))
                continue
            }
            val book = m.book
            val ask = if (b.spec.side == "yes") book?.yesAsk?.value else book?.yesBid?.value?.let { 1 - it }
            if (ask == null || ask <= 0.0 || ask >= 1.0) { updated += b.copy(detail = "no ask on the ${b.spec.side.uppercase()} side yet"); continue }
            if (ask > b.spec.maxPrice + 1e-9) { updated += b.copy(detail = "ask ${cents(ask)} above the ${cents(b.spec.maxPrice)} limit"); continue }
            val n = floor(minOf(b.spec.stakeUsd, free) / ask).toInt()
            if (n <= 0) { updated += b.copy(detail = "the paper wallet has ${money(free)} left; the bet waits"); continue }
            val fee = com.marketbot.core.kalshi.kalshiFee(ask, n) * n
            ctx.stores.ledger.insert(QuoteRow(strategyId = id, mode = Mode.PAPER, venue = "kalshi", ticker = b.spec.ticker, action = "buy_${b.spec.side}",
                price = ask, contracts = n.toDouble(), placedAt = now, closeTime = m.closeTime, filledContracts = n.toDouble(), fillCheckedAt = now,
                fillSource = "paper: taker at the ask, GET /markets/${b.spec.ticker}",
                extra = buildJsonObject {
                    put("bet_id", b.id); put("fee", fee); b.spec.probability?.let { put("probability", it) }
                    book?.yesBid?.value?.let { put("yes_bid", it) }; book?.yesAsk?.value?.let { put("yes_ask", it) }
                }))
            free -= ask * n + fee
            ctx.trade("paper_order", "Research bet: bought $n ${b.spec.side.uppercase()} at ${cents(ask)} on ${b.spec.ticker}",
                mapOf("ask" to Sourced.kalshi(ask, "GET /markets/${b.spec.ticker}#${if (b.spec.side == "yes") "yes_ask_dollars" else "1 - yes_bid_dollars"}")))
            updated += b.copy(status = "placed", detail = "bought $n at ${cents(ask)}"); placed++
        }
        save(ctx.stores.state, updated)
        return RunReport("${bets.size} bet(s): $placed placed, $settled settled this pass.")
    }

    override suspend fun liveRequirements(ctx: StrategyContext) = listOf(
        Requirement("paper_only", "Paper only", false, true, "Research bets are a paper record; there is no live order path."))

    override suspend fun preflight(ctx: StrategyContext): PreflightResult {
        val bets = load(ctx.stores.state).filter { it.status in setOf("waiting", "placed") }
        val steps = bets.map { b -> val m = runCatching { ctx.kalshi.market(b.spec.ticker) }.getOrNull()
            "${b.spec.ticker}: ${m?.status ?: "unreadable"}" to (m != null && m.ticker.isNotEmpty()) }
        return PreflightResult(steps.all { it.second }, steps, if (bets.isEmpty()) "No open bets." else "Markets readable.")
    }

    override suspend fun stats(ctx: StrategyContext): List<Stat> {
        val r = report(ctx.stores, id)
        val done = r.filter { it.predictionCorrect != null }
        if (r.isEmpty()) return emptyList()
        return listOfNotNull(
            Stat("Bets", Sourced("${r.size} (${r.count { it.status == "waiting" || it.status == "placed" }} open)", Source.DERIVED, "approved research bets")),
            done.takeIf { it.isNotEmpty() }?.let { Stat("Research right", Sourced("${it.count { b -> b.predictionCorrect == true }} of ${it.size}", Source.DERIVED, "settled on the side the research picked")) },
            r.mapNotNull { it.paperPnl }.takeIf { it.isNotEmpty() }?.let { Stat("Paper P&L", Sourced(money(it.sum()), Source.PAPER_MODEL, "settled bets, after taker fees")) },
        )
    }

    companion object {
        const val ID = "research_bets"
        private const val KEY = "strategy.$ID.bets"
        private val ser = ListSerializer(BetState.serializer())
        private val TICKER = Regex("^KX[A-Z0-9]+-[A-Z0-9.\\-]+$")

        fun cents(x: Double) = "%.0fc".format(x * 100)
        private fun betIdOf(q: QuoteRow) = (q.extra["bet_id"] as? JsonPrimitive)?.content
        private fun feeOf(q: QuoteRow) = (q.extra["fee"] as? JsonPrimitive)?.content?.toDoubleOrNull()

        suspend fun load(state: StateStore): List<BetState> =
            state.get(KEY)?.let { runCatching { JSON.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()
        suspend fun save(state: StateStore, l: List<BetState>) = state.put(KEY, JSON.encodeToString(ser, l))

        /** Why the app will not take this bet, or null when it will. [wallet] is the paper wallet. */
        fun problem(b: BetSpec, wallet: Double): String? = when {
            !TICKER.matches(b.ticker) -> "\"${b.ticker}\" is not a Kalshi ticker"
            b.side !in setOf("yes", "no") -> "side must be yes or no"
            b.maxPrice !in 0.01..0.99 -> "the price limit must be between 1c and 99c"
            b.stakeUsd <= 0 || b.stakeUsd > wallet -> "the stake must be more than 0 and at most the ${money(wallet)} paper wallet"
            b.probability != null && b.probability !in 0.0..1.0 -> "the probability must be between 0 and 1"
            b.thesis.length > 1000 || b.against.length > 1000 || b.source.length > 300 -> "the notes are too long"
            else -> null
        }

        /** Add an approved bet; an id already present is left as it is. */
        suspend fun add(state: StateStore, id: String, spec: BetSpec, at: Instant) {
            val l = load(state)
            if (l.any { it.id == id }) return
            save(state, l + BetState(id, spec.copy(ticker = spec.ticker.trim().uppercase(), side = spec.side.lowercase()), at.toString()))
        }

        /** Every bet with its order and outcome, for `bets/results.json`. */
        suspend fun report(stores: Stores, strategyId: String = ID): List<BetRecord> {
            val rows = stores.ledger.all(strategyId, Mode.PAPER).associateBy { betIdOf(it) }
            return load(stores.state).map { b ->
                val q = rows[b.id]
                val result = q?.result ?: b.result
                val won = result?.let { it == b.spec.side }
                BetRecord(b.id, b.spec.ticker, com.marketbot.core.util.Tickers.describe(b.spec.ticker), b.spec.side, b.status, b.detail, b.approvedAt,
                    b.spec.probability, b.spec.thesis, b.spec.against, b.spec.source, b.spec.maxPrice, b.spec.stakeUsd,
                    q?.placedAt?.toString(), q?.price, q?.filledContracts, q?.let { feeOf(it) }, result, won,
                    if (won != null && b.spec.probability != null) (b.spec.probability - if (won) 1.0 else 0.0).let { it * it } else null, q?.pnl)
            }
        }

        fun resultsJson(records: List<BetRecord>): String =
            Json { prettyPrint = true; encodeDefaults = true }.encodeToString(ListSerializer(BetRecord.serializer()), records)
    }
}
