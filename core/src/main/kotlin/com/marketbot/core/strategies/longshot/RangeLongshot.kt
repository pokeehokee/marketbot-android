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

/** How long after close the venue is given to publish a settlement before a row is called ungraded. */
private val SETTLE_GRACE: Duration = Duration.ofMinutes(30)

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
    /** Kalshi's strike_type (between, greater, less...) where known. */
    val strikeType: String? = null,
    /** The venue's own price increment for this market, where it publishes one. */
    val tick: Double? = null,
    /** The exchange shard the venue routes this market's orders to, where it has shards. */
    val exchangeIndex: Int? = null,
)

/** Which side is cheap, and the order that trades it ([taker]: fills at once at [price] and pays the taker fee). */
data class CheapPick(val action: String, val price: Double, val cheapPrice: Double, val taker: Boolean = false)

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

    /** Collateral per contract: selling YES at p locks 1 - p; buying YES or NO at p locks p. */
    fun lock(action: String, price: Double) = if (action == "sell_yes") 1.0 - price else price

    /** Paper P&L per contract at settlement, before fees. */
    fun pnl(action: String, price: Double, result: String) = when (action) {
        "sell_yes" -> price - if (result == "yes") 1.0 else 0.0
        "buy_no" -> (if (result == "no") 1.0 else 0.0) - price
        else -> (if (result == "yes") 1.0 else 0.0) - price
    }
}

/**
 * A longshot on a venue other than Kalshi weather: the shared engine (selection, sizing, the
 * daily brake, fills, grading), with the venue supplying how to list markets, read a fill and
 * read the result.
 *
 * It is PAPER-ONLY unless the venue implements the live hooks below AND the strategy sets
 * [liveCapable]. A candidate out of the pool search is forward-tested in paper first, and a
 * variant (baseId != null) is never offered live whatever its venue can do.
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

    /** Taker fee per contract, for rules that cross the spread. */
    open suspend fun takerFee(ctx: StrategyContext, ticker: String, price: Double, contracts: Int): Sourced<Double> = makerFee(ctx, ticker, price, contracts)

    /** The order this strategy places on [m], or null to pass. The pool-search trade by default. */
    open fun pick(m: RangeCandidate, c: StrategyConfig): CheapPick? = CheapSide.pick(m.yesBid, m.yesAsk, c.num("min_cheap"), c.num("max_cheap"))

    /** When an unfilled offer is withdrawn: its reference time by default. */
    open fun withdrawAt(q: QuoteRow, c: StrategyConfig): Instant? = refOf(q) ?: q.closeTime

    // ------------------------------------------------------------------ live
    /**
     * Whether this strategy may be run live. False unless a subclass both implements every hook
     * below and opts in; a variant is paper-only regardless, as everywhere else in the app.
     */
    open val liveCapable: Boolean = false

    /**
     * Rest [n] contracts of [p] on [m] at the venue. Returns (orderId, clientOrderId), or null
     * when the venue refused the order post-only (a cross), which is not an error.
     * NEVER retried: a lost response is recovered by client_order_id inside the venue client.
     */
    open suspend fun place(ctx: StrategyContext, m: RangeCandidate, p: CheapPick, n: Int): Pair<String?, String?>? =
        throw UnsupportedOperationException("$id has no live order path")

    /** Withdraw a resting live order at the venue. */
    open suspend fun cancel(ctx: StrategyContext, q: QuoteRow) { throw UnsupportedOperationException("$id has no live order path") }

    /** Contracts filled so far per the venue's own order record, or null when unreadable. */
    open suspend fun liveFill(ctx: StrategyContext, q: QuoteRow): Sourced<Double>? = null

    /** The venue's own realised figure for a settled position. Live P&L is never derived locally. */
    open suspend fun livePnl(ctx: StrategyContext, q: QuoteRow, result: String): Sourced<Double>? = null

    /** The venue's fill record for this order: zero here is the only thing that grades a row unfilled. */
    open suspend fun confirmedFills(ctx: StrategyContext, q: QuoteRow): Sourced<Double>? = null

    /** Account equity from the venue's API. Null aborts the pass: live size is never guessed. */
    open suspend fun liveEquity(ctx: StrategyContext): Sourced<Double>? = null

    final override val supportedModes: Set<Mode> get() =
        if (liveCapable && baseId == null) setOf(Mode.OFF, Mode.PAPER, Mode.LIVE) else setOf(Mode.OFF, Mode.PAPER)

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
            if (liveCapable) "In PAPER nothing is sent to the exchange and fills are simulated from the public tape; in LIVE the orders are real and rest post-only."
            else "Paper only: nothing is sent to the exchange; fills are simulated from the public data.",
            "One market that settles against the book loses up to ${money(worst)} ($lot contracts), against a ${money(config.num("daily_loss_limit"))} daily brake.",
            "Offers are placed ${config.int("min_lead_min")}-${config.int("max_lead_min")} min before the reference time ($measuredLead) and withdrawn unfilled at it.",
        )
    }

    override fun nextDelay(ctx: StrategyContext): Duration = Duration.ofMinutes(ctx.config.int("every_min").toLong())

    private fun bookMode(ctx: StrategyContext) = if (ctx.live) Mode.LIVE else Mode.PAPER
    private fun lockOf(q: QuoteRow) = CheapSide.lock(q.action, q.price)
    private fun committed(open: List<QuoteRow>) = open.sumOf { lockOf(it) * if (it.cancelledAt != null) (it.filledContracts ?: 0.0) else it.contracts }

    /**
     * Collateral behind orders that are still RESTING and unfilled. Live, this is the only part the
     * budget must subtract itself: the exchange deducts a FILLED position's collateral from cash
     * already, so counting it again would under-deploy by the size of the book. (weatherbot's
     * CLAUDE.md, 2026-09-08: "The exchange deducts collateral for FILLED positions from `balance`,
     * not for resting orders.")
     */
    private fun restingCommitted(open: List<QuoteRow>) =
        open.filter { it.resting }.sumOf { lockOf(it) * max(0.0, it.contracts - (it.filledContracts ?: 0.0)) }
    protected fun refOf(q: QuoteRow): Instant? = (q.extra["ref"] as? JsonPrimitive)?.content?.let { runCatching { Instant.parse(it) }.getOrNull() }

    override suspend fun runOnce(ctx: StrategyContext): RunReport {
        if (ctx.mode == Mode.OFF) return RunReport("off")
        if (ctx.mode == Mode.LIVE && !ctx.liveArmed) return RunReport("live not armed")
        if (ctx.mode == Mode.LIVE && !liveCapable) { ctx.error("live", "$id has no live order path."); return RunReport("no live path") }
        validate(ctx.config).takeIf { it.isNotEmpty() }?.let {
            ctx.error("config", "Settings need fixing before this can run: ${it.joinToString(" ")}"); return RunReport("invalid settings")
        }
        val c = ctx.config
        val now = ctx.clock.now()
        val mode = bookMode(ctx)
        // 1. fills for resting rows, then withdraw unfilled ones once their reference time passes
        for (q in ctx.stores.ledger.open(id, mode)) {
            if (q.resting && (q.filledContracts ?: 0.0) < q.contracts) readFill(ctx, q, now)
        }
        for (q in ctx.stores.ledger.open(id, mode)) {
            val ref = withdrawAt(q, c)
            if (!q.resting || ref == null || now.isBefore(ref) || (q.filledContracts ?: 0.0) >= q.contracts) continue
            if (!ctx.live) { ctx.stores.ledger.update(q.copy(cancelledAt = now)); continue }
            // The order is withdrawn AT THE EXCHANGE first, and its fill state is re-read after, because
            // a cancel races a fill. The row is never written off without that read, and a withdrawal
            // never writes a result: the settlement pass still grades whatever filled.
            val cancelled = runCatching { cancel(ctx, q) }
            if (cancelled.isFailure) { ctx.warn("cancel", "Could not withdraw ${q.ticker}: ${cancelled.exceptionOrNull()?.message}. Left resting."); continue }
            val after = ctx.stores.ledger.open(id, mode).firstOrNull { it.id == q.id } ?: q
            readFill(ctx, after, now)
            val latest = ctx.stores.ledger.open(id, mode).firstOrNull { it.id == q.id } ?: after
            ctx.stores.ledger.update(latest.copy(cancelledAt = now))
        }
        // 2. grade settled rows
        var resolved = 0
        for (q in ctx.stores.ledger.open(id, mode)) {
            if (q.closeTime != null && now.isBefore(q.closeTime)) continue
            val res = runCatching { result(ctx, q.ticker) }.getOrNull() ?: continue
            val filled = q.filledContracts ?: 0.0
            if (ctx.live) { if (gradeLive(ctx, q, res, filled, now)) resolved++; continue }
            val n = if (filled > 0) filled else q.contracts
            val taker = (q.extra["taker"] as? JsonPrimitive)?.content == "true"
            val fee = if (taker) takerFee(ctx, q.ticker, q.price, n.roundToInt()) else makerFee(ctx, q.ticker, q.price, n.roundToInt())
            val pnl = (CheapSide.pnl(q.action, q.price, res) - fee.value) * n
            ctx.stores.ledger.update(q.copy(resolvedAt = now, result = res, pnl = pnl, pnlSource = Source.PAPER_MODEL,
                pnlOrigin = "paper: (payout - price - fee) x ${if (filled > 0) "simulated fills" else "full size (never filled)"}; fee: ${fee.origin}",
                hypothetical = filled <= 0))
            if (filled > 0) ctx.trade("settled", "Paper: ${q.ticker} settled ${res.uppercase()}, ${if (pnl >= 0) "made" else "lost"} ${money(kotlin.math.abs(pnl))}")
            resolved++
        }
        // 3. brake and budget
        val rows = ctx.stores.ledger.all(id, mode)
        val today = realisedSince(rows, startOfLocalDay(now, com.marketbot.core.strategy.brakeZone(c.text("brake_time_zone"))))
        if (today <= -c.num("daily_loss_limit")) {
            ctx.warn("brake", "Daily loss brake: realised ${money(today)} today. No new ${if (ctx.live) "" else "paper "}offers until tomorrow.")
            return RunReport("brake")
        }
        val open = ctx.stores.ledger.open(id, mode)
        // Live size comes from the exchange's own equity and nothing else: a balance that cannot be
        // read aborts the pass rather than falling back to a local number.
        var budget: Double
        if (ctx.live) {
            val eq = runCatching { liveEquity(ctx) }.getOrNull()
            if (eq == null) { ctx.error("balance", "Could not read the account balance; nothing offered this pass."); return RunReport("balance unreadable") }
            budget = eq.value * c.num("collateral_fraction") - restingCommitted(open)
        } else {
            val cash = c.num("paper_cash") + realisedSince(rows, null)
            budget = (cash + committed(open)) * c.num("collateral_fraction") - committed(open)
        }
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
            val p = pick(m, c)
            if (p == null) { noPick += "${m.ticker} (bid ${m.yesBid?.let { "%.2f".format(it) } ?: "-"}, ask ${m.yesAsk?.let { "%.2f".format(it) } ?: "-"})"; continue }
            val g = ctx.gate(m.ticker)
            if (g != null) { gated += "${m.ticker} (${g.text})"; continue }
            val per = CheapSide.lock(p.action, p.price)
            val n = min(lot, if (per <= 0) lot else (budget / per).toInt())
            if (n <= 0 || openMarkets >= c.int("max_markets")) continue
            var orderId: String? = null; var coid: String? = null
            if (ctx.live) {
                // Placed BEFORE the row is written, and never retried: a row without an order would
                // lock budget against nothing, and a retry is how one order becomes two.
                val placedRes = runCatching { place(ctx, m, p, n) }
                placedRes.exceptionOrNull()?.let { ctx.error("order", "Order failed on ${m.ticker}: ${it.message}") }
                val pair = placedRes.getOrNull()
                if (pair == null) {
                    if (placedRes.isSuccess) ctx.info("order", "${m.ticker}: not placed (post-only would have crossed).")
                    continue
                }
                orderId = pair.first; coid = pair.second
                if (orderId == null && coid == null) { ctx.info("order", "${m.ticker}: the venue returned no order."); continue }
            }
            ctx.stores.ledger.insert(QuoteRow(strategyId = id, mode = mode, venue = venueKey, ticker = m.ticker, action = p.action,
                price = p.price, contracts = n.toDouble(), placedAt = now, closeTime = m.settlesAt ?: m.ref, marketKind = m.kind,
                orderId = orderId, clientOrderId = coid,
                queueAhead = if (p.taker) null else if (p.action == "sell_yes") m.queueAtAsk else m.queueAtBid,
                filledContracts = if (p.taker) n.toDouble() else null, fillCheckedAt = if (p.taker) now else null,
                fillSource = if (p.taker) "paper: taker, filled at the quoted price" else null,
                extra = buildJsonObject {
                    m.yesBid?.let { put("yes_bid", it) }; m.yesAsk?.let { put("yes_ask", it) }
                    put("ref", m.ref.toString()); put("cheap_price", p.cheapPrice)
                    m.tick?.let { put("tick", it) }; m.exchangeIndex?.let { put("exchange_index", it) }
                    if (p.taker) put("taker", true)
                }))
            budget -= per * n; locked += per * n; placed++; openMarkets++
            val verb = when (p.action) { "sell_yes" -> "sell $n YES"; "buy_no" -> "buy $n NO"; else -> "buy $n YES" }
            ctx.trade(if (ctx.live) "order" else "paper_quote",
                "${if (ctx.live) "" else "Paper: "}${if (p.taker) "took" else "offered to"} $verb at ${(p.price * 100).roundToInt()}c on ${m.ticker}",
                mapOf("yes_bid" to Sourced(m.yesBid, apiSource, "top of book ${m.ticker}"), "yes_ask" to Sourced(m.yesAsk, apiSource, "top of book ${m.ticker}")))
            if (budget <= 0) break
        }
        val summary = "${if (ctx.live) "Live" else "Paper"}: offered on $placed market(s), ${money(locked)} collateral; $resolved resolved."
        var left = listed.size
        fun step(label: String, out: List<String>) = FunnelStep(label, (left - out.size).also { left = it }, out.take(30))
        return RunReport(summary, funnel = listOf(
            FunnelStep("Open markets listed", listed.size), step("Not already offered", quoted),
            step("Inside the measured window", outWindow), step("Cheap side in the band, spread passes", noPick),
            step("Not locked", gated), FunnelStep("Offered within the budget", placed)))
    }

    /** Read a resting row's fill from the venue (live) or the tape model (paper) and store it. */
    private suspend fun readFill(ctx: StrategyContext, q: QuoteRow, now: Instant) {
        val read: Pair<Double, String>? =
            if (ctx.live) runCatching { liveFill(ctx, q) }.getOrNull()?.let { it.value to it.origin }
            else runCatching { paperFill(ctx, q) }.getOrNull()?.let { it to (q.fillSource ?: "paper: $venueKey public data") }
        if (read == null) return
        ctx.stores.ledger.update(q.copy(
            filledContracts = min(q.contracts, max(q.filledContracts ?: 0.0, read.first)),
            fillCheckedAt = now, fillSource = read.second))
    }

    /**
     * Grade one live row. Live P&L is the venue's own figure or nothing: a row that filled and has
     * no settlement record yet is DEFERRED, never derived from the local size and price. Only the
     * venue saying the order never traded grades it unfilled.
     */
    private suspend fun gradeLive(ctx: StrategyContext, q: QuoteRow, res: String, filled: Double, now: Instant): Boolean {
        if (filled <= 0) {
            ctx.stores.ledger.update(q.copy(resolvedAt = now, result = res, pnl = 0.0, pnlSource = Source.DERIVED,
                pnlOrigin = "never filled (per ${q.fillSource ?: "no fill read"})", hypothetical = true))
            return true
        }
        val pnl = runCatching { livePnl(ctx, q, res) }.getOrNull()
        if (pnl != null) {
            ctx.stores.ledger.update(q.copy(resolvedAt = now, result = res, pnl = pnl.value, pnlSource = pnl.source,
                pnlOrigin = pnl.origin, hypothetical = false))
            ctx.trade("settled", "${q.ticker} settled ${res.uppercase()}: ${if (pnl.value >= 0) "made" else "lost"} " +
                "${money(kotlin.math.abs(pnl.value))} (${pnl.source.label})", mapOf("pnl" to pnl))
            return true
        }
        if (q.closeTime != null && Duration.between(q.closeTime, now) < SETTLE_GRACE) return false
        val conf = runCatching { confirmedFills(ctx, q) }.getOrNull()
        if (conf != null && conf.value <= 0) {
            ctx.stores.ledger.update(q.copy(filledContracts = 0.0, fillSource = conf.origin, fillCheckedAt = now,
                resolvedAt = now, result = res, pnl = 0.0, pnlSource = conf.source,
                pnlOrigin = "never filled: no fills at ${conf.origin}, no settlement", hypothetical = true))
            ctx.warn("reconcile", "${q.ticker}: the order read said ${"%.0f".format(filled)} filled, but the exchange " +
                "has no fills and no settlement for it. Recorded as never filled.")
            return true
        }
        ctx.warn("ungraded", "${q.ticker} settled ${res.uppercase()} and filled ${"%.0f".format(filled)}, but the exchange " +
            "has no figure for it yet. Left ungraded rather than guessed.")
        return false
    }

    override suspend fun liveRequirements(ctx: StrategyContext): List<Requirement> {
        if (!liveCapable) return listOf(Requirement("paper_only", "Forward-tested in paper first", false, true,
            "This strategy has no live order path: it came out of a historical search and runs in paper until its paper record shows the edge."))
        val paper = summarise(ctx.stores.ledger.all(id, Mode.PAPER))
        val settled = ctx.stores.ledger.all(id, Mode.PAPER).filter { it.resolvedAt != null && it.hypothetical == false }
        val losses = settled.count { Outcomes.lost(it) == true }
        val pre = ctx.stores.state.get("$id.preflight_ok_at")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val preOk = pre != null && Duration.between(pre, ctx.clock.now()) < Duration.ofDays(7)
        val record = (paper.perContract ?: -1.0) > 0 && settled.size >= 30
        return listOf(
            Requirement("creds", "Venue API key saved", venueHasCredentials(ctx), true,
                if (venueHasCredentials(ctx)) "Key loaded." else "Add your key under Accounts."),
            Requirement("preflight", "Preflight passed in the last 7 days", preOk, true,
                if (preOk) "Last passed $pre." else "Run Preflight."),
            Requirement("paper", "30+ settled paper fills at a positive per-contract result", record, false,
                "Paper so far: ${settled.size} settled, $losses losing, ${paper.perContract?.let { "%+.2fc".format(it * 100) } ?: "n/a"} per contract."),
            Requirement("losses_not_fills", "Accept: the verdict needs LOSSES, not fills", false, false,
                "This trade's result is dominated by rare losses. A paper book with few losses cannot resolve it, " +
                    "which is why the evidence is the settled-history measurement and why the first live lot should be small."),
            Requirement("adverse", "Accept: paper fills never sat in the real book", false, false,
                "Paper fills are modelled from the public tape behind our queue position. A real resting order can be " +
                    "lifted by flow reacting to it; on the weather book that selection took the population's 0.73 loss ratio to 0.96 on fills."),
        )
    }

    /** Whether the venue's client holds credentials. */
    open fun venueHasCredentials(ctx: StrategyContext): Boolean = false

    override suspend fun stats(ctx: StrategyContext): List<Stat> {
        val out = mutableListOf<Stat>()
        for (m in listOf(Mode.LIVE, Mode.PAPER)) {
            val rows = ctx.stores.ledger.all(id, m)
            if (rows.isEmpty()) continue
            val label = if (m == Mode.LIVE) "Live" else "Paper"
            out += bookStats(label, summarise(rows), m == Mode.LIVE)
            val settled = rows.filter { it.resolvedAt != null && it.hypothetical == false && Outcomes.lost(it) != null }
            if (settled.size >= 10) {
                val losses = settled.count { Outcomes.lost(it) == true }
                val implied = settled.sumOf { Outcomes.impliedLoss(it) }
                out += Stat("$label losses vs implied", Sourced("$losses vs ${"%.1f".format(implied)} (${"%.2f".format(if (implied > 0) losses / implied else 0.0)})",
                    Source.DERIVED, "losing settlements / the prices' implied loss count; the search measured well below 1.0"))
            }
            val quotes = rows.filter { it.filledContracts != null && (it.cancelledAt != null || it.resolvedAt != null) }
            if (quotes.size >= 10) out += Stat("$label fill rate", Sourced("${"%.0f".format(100.0 * quotes.count { (it.filledContracts ?: 0.0) > 0 } / quotes.size)}% of ${quotes.size}",
                Source.DERIVED, if (m == Mode.LIVE) "quotes with any fill at the exchange / quotes with a verdict" else "quotes with any simulated fill / quotes with a verdict"))
        }
        return out
    }

    override suspend fun healthChecks(ctx: StrategyContext, mode: Mode): List<Check> =
        listOf(KillCriteria.lossRatio(ctx.stores.ledger.all(id, mode), ctx.clock.now()))
}
