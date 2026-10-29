package com.marketbot.core.strategies.longshot

import com.marketbot.core.provenance.Source
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.strategy.Check
import com.marketbot.core.strategy.FunnelStep
import com.marketbot.core.strategy.KillCriteria
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.Outcomes
import com.marketbot.core.strategy.Param
import com.marketbot.core.strategy.Requirement
import com.marketbot.core.strategy.RunReport
import com.marketbot.core.strategy.Stat
import com.marketbot.core.strategy.Strategy
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.strategy.bookStats
import com.marketbot.core.strategy.money
import com.marketbot.core.strategy.realisedSince
import com.marketbot.core.strategy.startOfLocalDay
import com.marketbot.core.strategy.summarise
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One open market as a venue lists it: the YES top of book and the moment the measured rule is timed from. */
data class RangeCandidate(
    val ticker: String,
    val yesBid: Double?,
    val yesAsk: Double?,
    /** The moment the lead is measured from (Kalshi: close; Polymarket temperature: the start of the measured day). */
    val ref: Instant,
    /** When the market can be graded (close, or the settlement time). */
    val settlesAt: Instant?,
    val queueAtAsk: Double?,
    val queueAtBid: Double?,
    val kind: String?,
)

/** Which side is cheap, and the resting order that sells it. */
data class CheapPick(val action: String, val price: Double, val cheapPrice: Double)

/**
 * The trade the 2026-10-29 pool search measured ("rest on the favourite"): the cheap side of a
 * binary market, priced under [maxCheap], is sold by RESTING at the maker price. YES cheap: sell
 * YES at the ask. NO cheap: buy YES at the bid (= sell NO at 1 - bid). The spread gate is the
 * search's, so the paper book trades exactly the population that was scored.
 */
object CheapSide {
    fun spreadOk(bid: Double, ask: Double) = ask > bid && (ask - bid) <= max(0.03, 0.5 * min(ask, 1 - bid))

    fun pick(bid: Double?, ask: Double?, minCheap: Double, maxCheap: Double): CheapPick? {
        if (bid == null || ask == null || bid <= 0.0 || ask >= 1.0 || !spreadOk(bid, ask)) return null
        if (ask < maxCheap) return if (ask >= minCheap) CheapPick("sell_yes", ask, ask) else null
        val noPrice = 1 - bid
        if (noPrice < maxCheap) return if (noPrice >= minCheap) CheapPick("buy_yes", bid, noPrice) else null
        return null
    }

    /** Collateral per contract: selling YES at p locks 1 - p; buying YES at p locks p. */
    fun lock(action: String, price: Double) = if (action == "sell_yes") 1.0 - price else price

    /** Paper P&L per contract at settlement, before fees. */
    fun pnl(action: String, price: Double, result: String) = when (action) {
        "sell_yes" -> price - if (result == "yes") 1.0 else 0.0
        else -> (if (result == "yes") 1.0 else 0.0) - price
    }
}

/**
 * A paper-only longshot on a venue other than Kalshi weather: the shared engine (selection,
 * sizing, the daily brake, paper fills, grading), with the venue supplying how to list markets,
 * read a paper fill and read the result. No live order path exists: a candidate from the pool
 * search is forward-tested in paper before anything else, and LIVE is not offered.
 */
abstract class PaperRangeLongshot(final override val id: String, final override val baseId: String?) : Strategy {
    abstract val venueKey: String
    /** Where the quotes come from, for provenance tags. */
    abstract val apiSource: Source
    /** Hours of the measured rule, for the explanations. */
    abstract val measuredLead: String

    /** Open markets with their top of book. Called once a pass. */
    abstract suspend fun candidates(ctx: StrategyContext): List<RangeCandidate>
    /** Filled contracts so far for an open paper row (cumulative), or null when unreadable. */
    abstract suspend fun paperFill(ctx: StrategyContext, q: QuoteRow): Double?
    /** "yes" or "no" once the market has settled, else null. */
    abstract suspend fun result(ctx: StrategyContext, ticker: String): String?
    /** Maker fee per contract at [price] for [contracts]. */
    open suspend fun makerFee(ctx: StrategyContext, ticker: String, price: Double, contracts: Int): Sourced<Double> =
        Sourced.derived(0.0, "0", "resting orders pay no fee on this venue")

    final override val supportedModes: Set<Mode> get() = setOf(Mode.OFF, Mode.PAPER)

    open val defaultMinLeadMin = 40
    open val defaultMaxLeadMin = 90
    open val defaultEveryMin = 10

    override val params: List<Param> get() = listOf(
        Param.Int_("lot_cap", "Contracts per market", "Lot size; 5 is the weather longshot's.", 5, 1, 10),
        Param.Num("daily_loss_limit", "Daily loss brake", "Stop quoting for the day once realised paper losses reach this.", 10.0, 1.0, 100.0, "$"),
        Param.Num("collateral_fraction", "Share of equity to deploy", "Collateral locked is the most the book can lose.", 0.75, 0.05, 1.0, decimals = 2),
        Param.Int_("min_lead_min", "Quote from (minutes before)", "Earliest point of the measured window, in minutes before the reference time.", defaultMinLeadMin, 5, 1440, "min"),
        Param.Int_("max_lead_min", "Quote until (minutes before)", "Latest: markets further out than this are left for a later pass.", defaultMaxLeadMin, 10, 2880, "min"),
        Param.Num("max_cheap", "Max price of the cheap side", "The search scored the cheap side under 25c.", 0.25, 0.03, 0.40, decimals = 2),
        Param.Num("min_cheap", "Min price of the cheap side", "A 1c contract needs a 99:1 win rate to break even.", 0.02, 0.01, 0.10, decimals = 2),
        Param.Int_("max_markets", "Max markets at once", "A runaway guard, not a risk control.", 80, 1, 300),
        Param.Num("paper_cash", "Paper account size", "Starting cash of this strategy's virtual account.", 400.0, 10.0, 100000.0, "$"),
        Param.Int_("every_min", "Run every", "Minutes between passes; the window is about an hour wide.", defaultEveryMin, 2, 60, "min"),
        Param.Text("brake_time_zone", "Daily brake resets at midnight in", "IANA time zone. Blank means the phone's zone.", "", Param.TextKind.ZONE),
    ) + extraParams

    open val extraParams: List<Param> get() = emptyList()

    override fun validate(config: StrategyConfig): List<String> {
        val out = mutableListOf<String>()
        if (config.int("min_lead_min") >= config.int("max_lead_min")) out += "The window must start before it ends: \"Quote from\" has to be smaller than \"Quote until\"."
        if (config.num("min_cheap") >= config.num("max_cheap")) out += "The minimum price must be below the maximum."
        val worst = config.int("lot_cap") * (1 - config.num("min_cheap"))
        if (config.num("daily_loss_limit") < worst) out += "The daily brake (${money(config.num("daily_loss_limit"))}) is smaller than one worst-case market (${money(worst)})."
        return out
    }

    override fun effects(config: StrategyConfig): List<String> {
        val lot = config.int("lot_cap"); val worst = lot * (1 - config.num("min_cheap"))
        return listOf(
            "Paper only: nothing is sent to the exchange; fills are simulated from the public data.",
            "One market that settles against the book loses up to ${money(worst)} ($lot contracts).",
            "Offers are placed ${config.int("min_lead_min")}-${config.int("max_lead_min")} min before the reference time ($measuredLead) and withdrawn unfilled at it.",
        )
    }

    override fun nextDelay(ctx: StrategyContext): Duration = Duration.ofMinutes(ctx.config.int("every_min").toLong())

    private fun lockOf(q: QuoteRow) = CheapSide.lock(q.action, q.price)
    private fun committed(open: List<QuoteRow>) = open.sumOf { lockOf(it) * if (it.cancelledAt != null) (it.filledContracts ?: 0.0) else it.contracts }
    private fun refOf(q: QuoteRow): Instant? = (q.extra["ref"] as? JsonPrimitive)?.content?.let { runCatching { Instant.parse(it) }.getOrNull() }

    override suspend fun runOnce(ctx: StrategyContext): RunReport {
        if (ctx.mode == Mode.OFF) return RunReport("off")
        validate(ctx.config).takeIf { it.isNotEmpty() }?.let {
            ctx.error("config", "Settings need fixing before this can run: ${it.joinToString(" ")}"); return RunReport("invalid settings")
        }
        val c = ctx.config
        val now = ctx.clock.now()
        // 1. paper fills for resting rows, then withdraw unfilled ones once their reference time passes
        for (q in ctx.stores.ledger.open(id, Mode.PAPER)) {
            if (q.resting && (q.filledContracts ?: 0.0) < q.contracts) {
                val f = runCatching { paperFill(ctx, q) }.getOrNull()
                if (f != null) ctx.stores.ledger.update(q.copy(filledContracts = min(q.contracts, max(q.filledContracts ?: 0.0, f)), fillCheckedAt = now,
                    fillSource = q.fillSource ?: "paper: ${venueKey} public data"))
            }
        }
        for (q in ctx.stores.ledger.open(id, Mode.PAPER)) {
            val ref = refOf(q) ?: q.closeTime
            if (q.resting && ref != null && !now.isBefore(ref) && (q.filledContracts ?: 0.0) < q.contracts) {
                ctx.stores.ledger.update(q.copy(cancelledAt = now))
            }
        }
        // 2. grade settled rows
        var resolved = 0
        for (q in ctx.stores.ledger.open(id, Mode.PAPER)) {
            if (q.closeTime != null && now.isBefore(q.closeTime)) continue
            val res = runCatching { result(ctx, q.ticker) }.getOrNull() ?: continue
            val filled = q.filledContracts ?: 0.0
            val n = if (filled > 0) filled else q.contracts
            val fee = makerFee(ctx, q.ticker, q.price, n.roundToInt())
            val pnl = (CheapSide.pnl(q.action, q.price, res) - fee.value) * n
            ctx.stores.ledger.update(q.copy(resolvedAt = now, result = res, pnl = pnl, pnlSource = Source.PAPER_MODEL,
                pnlOrigin = "paper: (payout - price - fee) x ${if (filled > 0) "simulated fills" else "full size (never filled)"}; fee: ${fee.origin}",
                hypothetical = filled <= 0))
            if (filled > 0) ctx.trade("settled", "Paper: ${q.ticker} settled ${res.uppercase()}, ${if (pnl >= 0) "made" else "lost"} ${money(kotlin.math.abs(pnl))}")
            resolved++
        }
        // 3. brake and budget
        val rows = ctx.stores.ledger.all(id, Mode.PAPER)
        val today = realisedSince(rows, startOfLocalDay(now, com.marketbot.core.strategy.brakeZone(c.text("brake_time_zone"))))
        if (today <= -c.num("daily_loss_limit")) {
            ctx.warn("brake", "Daily loss brake: realised ${money(today)} today. No new paper offers until tomorrow.")
            return RunReport("brake")
        }
        val open = ctx.stores.ledger.open(id, Mode.PAPER)
        val cash = c.num("paper_cash") + realisedSince(rows, null)
        var budget = (cash + committed(open)) * c.num("collateral_fraction") - committed(open)
        if (budget <= 0) return RunReport("budget spent; $resolved resolved")

        // 4. candidates in the measured window
        val listed = runCatching { candidates(ctx) }.getOrElse { ctx.warn("listing", "Listing failed: ${it.message}"); return RunReport("listing_failed") }
        val already = open.map { it.ticker }.toSet()
        val outWindow = mutableListOf<String>(); val quoted = mutableListOf<String>(); val noPick = mutableListOf<String>(); val gated = mutableListOf<String>()
        var openMarkets = open.filter { it.resting || (it.filledContracts ?: 0.0) > 0 }.map { it.ticker }.toSet().size
        var placed = 0; var locked = 0.0
        val lot = c.int("lot_cap")
        for (m in listed.sortedBy { it.ref }) {
            if (m.ticker in already) { quoted += m.ticker; continue }
            val leadMin = Duration.between(now, m.ref).toMinutes()
            if (leadMin < c.int("min_lead_min") || leadMin > c.int("max_lead_min")) { outWindow += "${m.ticker} (${leadMin} min)"; continue }
            val p = CheapSide.pick(m.yesBid, m.yesAsk, c.num("min_cheap"), c.num("max_cheap"))
            if (p == null) { noPick += "${m.ticker} (bid ${m.yesBid?.let { "%.2f".format(it) } ?: "-"}, ask ${m.yesAsk?.let { "%.2f".format(it) } ?: "-"})"; continue }
            val g = ctx.gate(m.ticker)
            if (g != null) { gated += "${m.ticker} (${g.text})"; continue }
            val per = CheapSide.lock(p.action, p.price)
            val n = min(lot, if (per <= 0) lot else (budget / per).toInt())
            if (n <= 0 || openMarkets >= c.int("max_markets")) continue
            ctx.stores.ledger.insert(QuoteRow(strategyId = id, mode = Mode.PAPER, venue = venueKey, ticker = m.ticker, action = p.action,
                price = p.price, contracts = n.toDouble(), placedAt = now, closeTime = m.settlesAt ?: m.ref, marketKind = m.kind,
                queueAhead = if (p.action == "sell_yes") m.queueAtAsk else m.queueAtBid,
                extra = buildJsonObject {
                    m.yesBid?.let { put("yes_bid", it) }; m.yesAsk?.let { put("yes_ask", it) }
                    put("ref", m.ref.toString()); put("cheap_price", p.cheapPrice)
                }))
            budget -= per * n; locked += per * n; placed++; openMarkets++
            ctx.trade("paper_quote", "Paper: ${if (p.action == "sell_yes") "offered to sell $n YES at ${(p.price * 100).roundToInt()}c" else "bid for $n YES at ${(p.price * 100).roundToInt()}c (selling the ${(p.cheapPrice * 100).roundToInt()}c NO)"} on ${m.ticker}",
                mapOf("yes_bid" to Sourced(m.yesBid, apiSource, "top of book ${m.ticker}"), "yes_ask" to Sourced(m.yesAsk, apiSource, "top of book ${m.ticker}")))
            if (budget <= 0) break
        }
        val summary = "Paper: offered on $placed market(s), ${money(locked)} collateral; $resolved resolved."
        var left = listed.size
        fun step(label: String, out: List<String>) = FunnelStep(label, (left - out.size).also { left = it }, out.take(30))
        return RunReport(summary, funnel = listOf(
            FunnelStep("Open markets listed", listed.size), step("Not already offered", quoted),
            step("Inside the measured window", outWindow), step("Cheap side in the band, spread passes", noPick),
            step("Not locked", gated), FunnelStep("Offered within the budget", placed)))
    }

    override suspend fun liveRequirements(ctx: StrategyContext) = listOf(
        Requirement("paper_only", "Forward-tested in paper first", false, true,
            "This strategy has no live order path: it came out of a historical search and runs in paper until its paper record shows the edge."))

    override suspend fun stats(ctx: StrategyContext): List<Stat> {
        val rows = ctx.stores.ledger.all(id, Mode.PAPER)
        if (rows.isEmpty()) return emptyList()
        val out = bookStats("Paper", summarise(rows), false).toMutableList()
        val settled = rows.filter { it.resolvedAt != null && it.hypothetical == false && Outcomes.lost(it) != null }
        if (settled.size >= 10) {
            val losses = settled.count { Outcomes.lost(it) == true }
            val implied = settled.sumOf { Outcomes.impliedLoss(it) }
            out += Stat("Paper losses vs implied", Sourced("$losses vs ${"%.1f".format(implied)} (${"%.2f".format(if (implied > 0) losses / implied else 0.0)})",
                Source.DERIVED, "losing settlements / the prices' implied loss count; the search measured well below 1.0"))
        }
        val quotes = rows.filter { it.filledContracts != null && (it.cancelledAt != null || it.resolvedAt != null) }
        if (quotes.size >= 10) out += Stat("Paper fill rate", Sourced("${"%.0f".format(100.0 * quotes.count { (it.filledContracts ?: 0.0) > 0 } / quotes.size)}% of ${quotes.size}",
            Source.DERIVED, "quotes with any simulated fill / quotes with a verdict"))
        return out
    }

    override suspend fun healthChecks(ctx: StrategyContext, mode: Mode): List<Check> =
        listOf(KillCriteria.lossRatio(ctx.stores.ledger.all(id, Mode.PAPER), ctx.clock.now()))
}
