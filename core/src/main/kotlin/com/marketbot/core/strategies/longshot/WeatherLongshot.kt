package com.marketbot.core.strategies.longshot

import com.marketbot.core.kalshi.FillState
import com.marketbot.core.kalshi.KalshiMarket
import com.marketbot.core.kalshi.makerFeeEstimate
import com.marketbot.core.kalshi.settlementPnl
import com.marketbot.core.kalshi.tickSize
import com.marketbot.core.kalshi.priceRanges
import com.marketbot.core.provenance.Source
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.store.EquitySnapshot
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.strategy.BookSummary
import com.marketbot.core.strategy.Evidence
import com.marketbot.core.strategy.EvidenceLevel
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.Param
import com.marketbot.core.strategy.PreflightResult
import com.marketbot.core.strategy.Requirement
import com.marketbot.core.strategy.RunReport
import com.marketbot.core.strategy.Stat
import com.marketbot.core.strategy.Strategy
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.strategy.TapeFillModel
import com.marketbot.core.strategy.Venue
import com.marketbot.core.strategy.bookStats
import com.marketbot.core.strategy.money
import com.marketbot.core.strategy.realisedSince
import com.marketbot.core.strategy.reconcileKalshi
import com.marketbot.core.strategy.startOfLocalDay
import com.marketbot.core.strategy.summarise
import com.marketbot.core.strategy.untilNextMinuteOfHour
import com.marketbot.core.util.num
import com.marketbot.core.util.str
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Sell cheap YES on Kalshi daily temperature markets by resting post-only
 * asks ~24 h before close, and hold every fill to settlement. The
 * favorite-longshot bias: cheap weather YES settles YES less often than its
 * price says. Port of weatherbot `longshot_runner` + `longshot_resolver`.
 */
class WeatherLongshot : Strategy {
    override val id = "kalshi_weather_longshot"
    override val name = "Weather longshot"
    override val venue = Venue.KALSHI
    override val summary = "Rests offers to sell cheap YES on Kalshi's daily high/low temperature buckets and holds them to settlement."
    override val evidence = Evidence(
        EvidenceLevel.LIVE_PROVEN,
        "Out of sample +2.64c/contract resting at the ask; live by placement +\$32.30, +\$11.68, then -\$14.72.",
        listOf(
            "Backtest (strict 24 h lead, zero lookahead): +2.64c/contract, CI [+2.06, +3.23], 4,320 markets.",
            "Realistic yield after adverse fills: about \$4/day per \$300 of collateral.",
            "The stage-1 gate FAILED on 2026-09-16: losses ran at 0.92 of implied against a 0.90 bar.",
            "Losses are big and rare: a win is 2-25c, a loss up to 98c per contract.",
            "Crossing the spread instead of resting removes about three quarters of the edge.",
        ),
        "weatherbot CLAUDE.md (main, 2026-09-17)",
    )

    override val params: List<Param> = listOf(
        Param.Int_("lot_cap", "Contracts per market", "Lot size. Smaller lots over more markets gave the fewest losing days (13 of 82 at 5, against 20 at 10).", 5, 1, 10),
        Param.Num("daily_loss_limit", "Daily loss brake", "Stop quoting for the day once realised losses reach this. Must cover one worst-case market and stay under three.", 10.0, 1.0, 100.0, "$"),
        Param.Num("collateral_fraction", "Share of equity to deploy", "Collateral locked is the most the book can lose. Measured at 0.75.", 0.75, 0.05, 1.0, decimals = 2),
        Param.Num("min_lead_hours", "Minimum hours before close", "Selling later than ~20 h costs edge: -0.48c at 20 h, -1.0c at 16 h on the same markets.", 20.0, 0.0, 40.0, "h", 1),
        Param.Num("idle_quote_hours", "Recycle unfilled quotes after", "Cancel resting quotes this old that have not filled (the fill is checked at the exchange first).", 8.0, 1.0, 48.0, "h", 1),
        Param.Num("max_mid_high", "Max price, HIGH markets", "Highest mid sold on daily-high buckets. Measured through 0.25.", 0.25, 0.03, 0.25, decimals = 2),
        Param.Num("max_mid_low", "Max price, LOW markets", "Highest mid sold on daily-low buckets. Above 0.15 the two halves of the sample disagree.", 0.15, 0.03, 0.25, decimals = 2),
        Param.Num("min_mid", "Min price", "A 1c contract needs a 99:1 win rate to break even.", 0.02, 0.01, 0.10, decimals = 2),
        Param.Num("max_spread", "Max bid-ask spread", "Wider books mean the ask is far from fair and fills are adverse.", 0.06, 0.01, 0.20, decimals = 2),
        Param.Text("excluded_ask_bands", "Skipped ask bands", "Ask ranges never quoted, as lo-hi pairs. 0.10-0.15 lost money live and in two of three periods.", "0.10-0.15"),
        Param.Text("paused_kinds", "Paused market kinds", "HIGH and/or LOW to stop quoting (resting quotes of a paused kind are withdrawn).", ""),
        Param.Int_("max_markets", "Max markets at once", "A runaway guard, not a risk control: the collateral share is the control.", 120, 1, 300),
        Param.Int_("max_contracts", "Lot ceiling when budget is large", "Upper clamp of the budget-scaled lot.", 10, 1, 10),
        Param.Num("paper_cash", "Paper account size", "Starting cash of the virtual account used in paper mode.", 400.0, 10.0, 100000.0, "$"),
        Param.Int_("run_minute", "Run at minute past each hour", "The original ran at :05.", 5, 0, 59),
    )

    fun rules(c: StrategyConfig) = LongshotRules(
        minMid = c.num("min_mid"), maxMidHigh = c.num("max_mid_high"), maxMidLow = c.num("max_mid_low"),
        maxSpread = c.num("max_spread"), maxConcurrentMarkets = c.int("max_markets"),
        maxContractsPerMarket = c.int("max_contracts"), minContractsPerMarket = minOf(5, c.int("lot_cap")),
        lotCap = c.int("lot_cap"), collateralFraction = c.num("collateral_fraction"),
        dailyLossLimit = c.num("daily_loss_limit"), minLeadHours = c.num("min_lead_hours"),
        idleQuoteHours = c.num("idle_quote_hours"), excludedAskBands = parseBands(c.text("excluded_ask_bands")),
        pausedKinds = c.text("paused_kinds").split(',', ' ').map { it.trim().uppercase() }.filter { it == "HIGH" || it == "LOW" }.toSet(),
    )

    override fun validate(config: StrategyConfig): List<String> {
        val lot = config.int("lot_cap"); val brake = config.num("daily_loss_limit")
        val worst = lot * 0.98
        val out = mutableListOf<String>()
        if (brake < worst) out += "The daily brake (${money(brake)}) is smaller than one worst-case market at $lot contracts (${money(worst)}): one loss would run past it."
        if (brake > worst * 3) out += "The daily brake (${money(brake)}) allows more than three worst-case losses at $lot contracts (${money(worst * 3)}); it would not act as a brake."
        if (runCatching { parseBands(config.text("excluded_ask_bands")) }.isFailure) out += "Skipped ask bands must look like 0.10-0.15, 0.20-0.25."
        return out
    }

    override fun nextDelay(ctx: StrategyContext): Duration = untilNextMinuteOfHour(ctx.clock.now(), ctx.config.int("run_minute"))

    private fun key(ctx: StrategyContext, k: String) = "$id.${ctx.mode.name.lowercase()}.$k"

    // =====================================================================
    override suspend fun runOnce(ctx: StrategyContext): RunReport {
        if (ctx.mode == Mode.OFF) return RunReport("off")
        if (ctx.mode == Mode.LIVE && !ctx.liveArmed) {
            ctx.warn("blocked", "Live mode is selected but not armed; nothing was sent to the exchange.")
            return RunReport("live not armed")
        }
        validate(ctx.config).takeIf { it.isNotEmpty() }?.let {
            ctx.error("config", "Settings need fixing before this can run: ${it.joinToString(" ")}")
            return RunReport("invalid settings")
        }
        val r = rules(ctx.config)
        val live = ctx.live
        val mode = if (live) Mode.LIVE else Mode.PAPER

        if (!live) updatePaperFills(ctx)
        val resolved = resolve(ctx, live)

        // ---- equity and budget, from the API where it exists
        val (cash, pv) = if (live) {
            val b = ctx.kalshi.balance()
            ctx.stores.equity.record(EquitySnapshot(ctx.clock.now(), "kalshi", Mode.LIVE, b.cash, b.portfolioValue, b.equity))
            ctx.stores.audit.record("kalshi.cash", b.cash)
            b.portfolioValue?.let { ctx.stores.audit.record("kalshi.portfolio_value", it) }
            b.cash to b.portfolioValue
        } else paperEquity(ctx)

        val open = ctx.stores.ledger.open(id, mode)
        val committed = committedCollateral(open)
        val budget0 = LongshotModel.collateralBudget(cash.value, committed, committed, r.collateralFraction, pv?.value)
        val budgetSrc = if (pv != null) Sourced.derived(budget0, "(cash + portfolio_value) x ${r.collateralFraction} - committed",
            "budget is a calculation over API balance fields")
        else Sourced.derived(budget0, "(cash + committed) x ${r.collateralFraction} - committed",
            if (live) "balance payload had no portfolio_value; equity reconstructed (overstates by resting collateral)" else "paper account")
        ctx.info("budget", "Cash ${money(cash.value)}, positions ${pv?.let { money(it.value) } ?: "n/a"}, committed ${money(committed)}, budget ${money(budget0)}",
            mapOf("cash" to cash, "committed" to Sourced.derived(committed, "sum((1-price) x contracts) over open quotes", "the exchange reports no per-strategy figure"),
                "budget" to budgetSrc) + (pv?.let { mapOf("positions_value" to it) } ?: emptyMap()))

        // ---- live-only guards
        if (live) {
            val last = ctx.stores.state.get(key(ctx, "settlements_read_at"))?.let { Instant.parse(it) }
            if (last == null || Duration.between(last, ctx.clock.now()) > Duration.ofMinutes(90)) {
                ctx.warn("blocked", "Settlements have not been read from Kalshi in the last 90 minutes, so the daily brake would be reading stale P&L. No new quotes this pass.")
                return RunReport("settlements stale")
            }
            val feeOk = ctx.stores.state.get(key(ctx, "fee_types_ok"))
            if (feeOk == "false") {
                ctx.error("kill", "A traded weather series no longer has fee_type=quadratic. Resting fills may now pay a maker fee, which removes the edge. Live quoting is stopped until it is re-measured.")
                return RunReport("fee type changed")
            }
            recycleIdleLive(ctx, r)
        } else recycleIdlePaper(ctx, r)

        val realisedToday = realisedSince(ctx.stores.ledger.all(id, mode), startOfLocalDay(ctx.clock.now()))
        if (realisedToday <= -r.dailyLossLimit) {
            ctx.warn("brake", "Daily loss brake: realised ${money(realisedToday)} today against a ${money(r.dailyLossLimit)} limit. No new quotes until tomorrow.",
                mapOf("realised_today" to Sourced(realisedToday, if (live) Source.KALSHI_API else Source.PAPER_MODEL, "sum of today's settlement P&L")))
            return RunReport("brake")
        }
        if (budget0 <= 0) {
            ctx.info("budget", "Collateral budget spent; nothing to quote.")
            return RunReport("budget spent; $resolved resolved")
        }

        // ---- candidates
        val markets = mutableListOf<KalshiMarket>()
        for (s in WeatherSeries.ALL) {
            runCatching { markets += ctx.kalshi.openMarkets(s) }.onFailure { ctx.warn("listing", "$s listing failed: ${it.message}") }
        }
        val openNow = ctx.stores.ledger.open(id, mode)
        val already = openNow.map { it.ticker }.toSet()
        var openMarkets = openNow.filterNot { it.cancelledAt != null && (it.filledContracts ?: 0.0) == 0.0 }.map { it.ticker }.toSet().size
        val now = ctx.clock.now()
        var shortLead = 0; var paused = 0; var deadBand = 0
        val byTicker = markets.associateBy { it.ticker }
        val accepted = mutableListOf<Candidate>()
        for (m in markets) {
            if (m.ticker.isEmpty() || m.ticker in already) continue
            val book = m.book ?: continue
            val close = m.closeTime
            if (close != null && Duration.between(now, close).seconds / 3600.0 < r.minLeadHours) { shortLead++; continue }
            if (LongshotModel.evaluate(m.ticker, book.yesBid.value, book.yesAsk.value, r = r) == null) continue
            if (WeatherSeries.kind(m.ticker) in r.pausedKinds) { paused++; continue }
            if (LongshotModel.inExcludedBand(book.yesAsk.value, r.excludedAskBands)) { deadBand++; continue }
            accepted += Candidate(m.ticker, book.yesBid.value, book.yesAsk.value)
        }
        val ordered = LongshotModel.orderCandidates(accepted)
        val lot = minOf(r.maxContractsPerMarket, r.lotCap, LongshotModel.lotForBudget(budget0, ordered.map { it.yesAsk }, r.minContractsPerMarket, r.maxContractsPerMarket))
        ctx.info("candidates", "${markets.size} open markets; ${ordered.size} accepted, lot $lot; skipped $shortLead under ${r.minLeadHours}h, $paused paused kind, $deadBand in a skipped band.")

        // ---- quote
        var budget = budget0
        var placed = 0; var locked = 0.0; var expected = 0.0
        val feeTypes = mutableMapOf<String, Sourced<String>?>()
        for (c in ordered) {
            val affordable = LongshotModel.sizeForBudget(c.yesAsk, budget, lot)
            if (affordable <= 0) continue
            val q = LongshotModel.evaluate(c.ticker, c.yesBid, c.yesAsk, openMarkets, affordable, r) ?: continue
            val m = byTicker.getValue(c.ticker)
            var orderId: String? = null; var coid: String? = null
            if (live) {
                val tick = tickSize(q.price, m.raw.priceRanges())
                val attempt = runCatching { ctx.kalshi.placeOrder(c.ticker, "ask", q.contracts, q.price, tick.value, m.exchangeIndex) }
                val res = attempt.getOrNull()
                if (res == null) { ctx.error("order", "Order failed on ${c.ticker}: ${attempt.exceptionOrNull()?.message}"); continue }
                if (res.filtered) { ctx.info("order", "${c.ticker}: post-only order would cross; not quoted."); continue }
                orderId = res.orderId; coid = res.clientOrderId
                if (orderId == null) ctx.warn("order", "${c.ticker}: no order id returned; tracking by client order id $coid")
            }
            val series = c.ticker.substringBefore('-')
            val fee = feeTypes.getOrPut(series) { runCatching { ctx.kalshi.seriesFeeType(series) }.getOrNull() }
            ctx.stores.ledger.insert(QuoteRow(
                strategyId = id, mode = mode, venue = "kalshi", ticker = c.ticker, action = "sell_yes",
                price = q.price, contracts = q.contracts.toDouble(), placedAt = now, closeTime = m.closeTime,
                orderId = orderId, clientOrderId = coid, marketKind = WeatherSeries.kind(c.ticker),
                queueAhead = m.num("yes_ask_size_fp"),
                extra = buildJsonObject {
                    put("yes_bid", c.yesBid); put("yes_ask", c.yesAsk); put("expected_edge", q.expectedEdge)
                    m.num("volume_24h_fp")?.let { put("volume_24h", it) }
                    m.num("open_interest_fp")?.let { put("open_interest", it) }
                    fee?.let { put("fee_type", it.value) }
                },
            ))
            openMarkets += 1
            budget -= LongshotModel.collateralPerContract(q.price) * q.contracts
            locked += LongshotModel.collateralPerContract(q.price) * q.contracts
            expected += q.expectedEdge * q.contracts
            placed++
            ctx.trade(if (live) "order" else "paper_quote", "${if (live) "ORDER" else "PAPER"} sell YES ${c.ticker} ${q.contracts} @ ${"%.2f".format(q.price)}  ${q.reason}",
                mapOf("yes_bid" to Sourced.kalshi(c.yesBid, "market ${c.ticker}#yes_bid_dollars"),
                    "yes_ask" to Sourced.kalshi(c.yesAsk, "market ${c.ticker}#yes_ask_dollars")))
            if (openMarkets >= r.maxConcurrentMarkets || budget <= 0) break
        }
        if (live) checkFeeTypes(ctx)
        val summary = "Quoted $placed market(s), ${money(locked)} collateral, expected ${money(expected)} if all fill; $resolved resolved."
        ctx.info("pass", summary)
        return RunReport(summary)
    }

    private fun committedCollateral(open: List<QuoteRow>) = open.sumOf {
        (1.0 - it.price) * if (it.cancelledAt != null) (it.filledContracts ?: 0.0) else it.contracts
    }

    private suspend fun paperEquity(ctx: StrategyContext): Pair<Sourced<Double>, Sourced<Double>?> {
        val start = ctx.config.num("paper_cash")
        val realised = realisedSince(ctx.stores.ledger.all(id, Mode.PAPER), null)
        val cash = Sourced(start + realised, Source.PAPER_MODEL, "paper start ${money(start)} + realised paper P&L")
        ctx.stores.equity.record(EquitySnapshot(ctx.clock.now(), "kalshi", Mode.PAPER, cash, null, null))
        return cash to null
    }

    // ---------------------------------------------------------------- paper fills
    private suspend fun updatePaperFills(ctx: StrategyContext) {
        for (q in ctx.stores.ledger.open(id, Mode.PAPER)) {
            if ((q.filledContracts ?: 0.0) >= q.contracts) continue
            if (q.cancelledAt != null && q.fillCheckedAt != null && !q.fillCheckedAt.isBefore(q.cancelledAt)) continue
            val trades = runCatching { ctx.kalshi.trades(q.ticker, q.placedAt.epochSecond) }.getOrNull() ?: continue
            val f = TapeFillModel.fill(q.placedAt, q.cancelledAt, "no", 1.0 - q.price, q.contracts, q.queueAhead ?: 0.0, trades)
            ctx.stores.ledger.update(q.copy(filledContracts = maxOf(q.filledContracts ?: 0.0, f.filled),
                fillSource = "paper: Kalshi public tape, FIFO behind ${(q.queueAhead ?: 0.0).roundToInt()} ahead", fillCheckedAt = ctx.clock.now()))
        }
    }

    private suspend fun recycleIdlePaper(ctx: StrategyContext, r: LongshotRules) {
        val cutoff = ctx.clock.now().minusSeconds((r.idleQuoteHours * 3600).toLong())
        for (q in ctx.stores.ledger.open(id, Mode.PAPER)) {
            val pausedKind = q.marketKind in r.pausedKinds
            if (q.cancelledAt != null || (!q.placedAt.isBefore(cutoff) && !pausedKind)) continue
            if ((q.filledContracts ?: 0.0) >= q.contracts) continue
            ctx.stores.ledger.update(q.copy(cancelledAt = ctx.clock.now()))
            ctx.info("recycle", "Paper quote ${q.ticker} withdrawn after ${r.idleQuoteHours}h${if (pausedKind) " (kind paused)" else ""}: ${(q.filledContracts ?: 0.0).roundToInt()} of ${q.contracts.roundToInt()} filled.")
        }
    }

    /**
     * Switching the strategy off leaves resting quotes in place: they are the
     * edge (queue position), and the next pass picks them up again. The KILL
     * SWITCH is different: it withdraws every resting live quote, fill-checked
     * at the exchange first, writing no result.
     */
    override suspend fun cancelAllResting(ctx: StrategyContext) {
        if (!ctx.kalshi.hasCredentials) return
        for (q in ctx.stores.ledger.open(id, Mode.LIVE).filter { it.resting && it.orderId != null }) {
            val fs = runCatching { ctx.kalshi.order(q.orderId!!) }.getOrNull()?.let { FillState.of(it) }
            val n = fs?.contracts ?: if (fs?.filled == true) q.contracts else 0.0
            if (fs?.filled == true && n >= q.contracts) continue
            if (runCatching { ctx.kalshi.cancelOrder(q.orderId!!, q.ticker) }.isSuccess)
                ctx.stores.ledger.update(q.copy(cancelledAt = ctx.clock.now(), filledContracts = n,
                    fillSource = fs?.let { "Kalshi API ${it.origin}" }, fillCheckedAt = ctx.clock.now()))
            else ctx.error("kill", "Could not cancel ${q.ticker} (${q.orderId}); cancel it in the Kalshi app.")
        }
        for (q in ctx.stores.ledger.open(id, Mode.PAPER).filter { it.resting }) ctx.stores.ledger.update(q.copy(cancelledAt = ctx.clock.now()))
    }

    // ---------------------------------------------------------------- live recycle
    private suspend fun recycleIdleLive(ctx: StrategyContext, r: LongshotRules) {
        val cutoff = ctx.clock.now().minusSeconds((r.idleQuoteHours * 3600).toLong())
        var freed = 0.0
        for (q in ctx.stores.ledger.open(id, Mode.LIVE)) {
            val pausedKind = q.marketKind in r.pausedKinds
            if (q.cancelledAt != null || q.orderId == null || (!q.placedAt.isBefore(cutoff) && !pausedKind)) continue
            val order = runCatching { ctx.kalshi.order(q.orderId) }.getOrElse {
                ctx.warn("recycle", "${q.ticker}: fill state unreadable, not cancelling: ${it.message}"); null
            } ?: continue
            val fs = FillState.of(order)
            if (fs.filled == null) continue
            val n = fs.contracts ?: if (fs.filled) q.contracts else 0.0
            if (fs.filled && n >= q.contracts) {
                ctx.stores.ledger.update(q.copy(filledContracts = n, fillSource = "Kalshi API ${fs.origin}", fillCheckedAt = ctx.clock.now()))
                continue
            }
            val ok = runCatching { ctx.kalshi.cancelOrder(q.orderId, q.ticker) }.onFailure {
                ctx.warn("recycle", "${q.ticker}: cancel failed, leaving it: ${it.message}")
            }.isSuccess
            if (!ok) continue
            // No result is written here. The resolver grades this row at settlement from the exchange's payload.
            ctx.stores.ledger.update(q.copy(cancelledAt = ctx.clock.now(), filledContracts = n,
                fillSource = "Kalshi API ${fs.origin}", fillCheckedAt = ctx.clock.now()))
            freed += (1.0 - q.price) * (q.contracts - n)
            ctx.info("recycle", "Cancelled idle quote ${q.ticker}: ${n.roundToInt()} of ${q.contracts.roundToInt()} filled (read from the exchange).")
        }
        if (freed > 0) ctx.info("recycle", "Recycled quotes freed ${money(freed)} of collateral.")
    }

    // ---------------------------------------------------------------- resolver
    /**
     * Grades every open row whose market has settled. A live row that filled
     * (or whose fill is unknown) is graded ONLY from its settlement payload;
     * if settlements cannot be read it waits. Nothing live is ever graded from
     * local size and price.
     */
    suspend fun resolve(ctx: StrategyContext, live: Boolean): Int {
        val mode = if (live) Mode.LIVE else Mode.PAPER
        val now = ctx.clock.now()
        val due = ctx.stores.ledger.open(id, mode).filter { it.closeTime == null || !it.closeTime.isAfter(now) }
        var payloads: Map<String, kotlinx.serialization.json.JsonObject>? = null
        if (live) {
            payloads = runCatching { ctx.kalshi.settlementsByTicker() }
                .onFailure { ctx.warn("resolve", "Settlements unavailable (${it.message}); live rows wait for the next pass.") }.getOrNull()
            if (payloads != null) ctx.stores.state.put(key(ctx, "settlements_read_at"), now.toString())
        }
        if (due.isEmpty()) return 0
        val fees = mutableMapOf<String, Sourced<String>?>()
        var closed = 0; var deferred = 0
        for (q in due) {
            val m = runCatching { ctx.kalshi.market(q.ticker) }.getOrNull() ?: continue
            val status = m.status?.lowercase() ?: continue
            val result = m.result
            if (status !in SETTLED || (result != "yes" && result != "no")) continue

            if (!live) {
                val series = q.ticker.substringBefore('-')
                val ft = fees.getOrPut(series) { runCatching { ctx.kalshi.seriesFeeType(series) }.getOrNull() }
                val filled = (q.filledContracts ?: 0.0)
                val n = if (filled > 0) filled else q.contracts
                val fee = makerFeeEstimate(ft, q.price, n.roundToInt())
                val pnl = ((q.price - if (result == "yes") 1.0 else 0.0) - fee.value) * n
                ctx.stores.ledger.update(q.copy(resolvedAt = now, result = result, pnl = pnl, pnlSource = Source.PAPER_MODEL,
                    pnlOrigin = "(price - payout - fee) x ${if (filled > 0) "simulated fills" else "full size (no simulated fill)"}; result from Kalshi market#result; fee: ${fee.origin}",
                    hypothetical = filled <= 0))
                closed++
                continue
            }

            val fs = if (q.orderId != null) runCatching { ctx.kalshi.order(q.orderId) }.getOrNull()?.let { FillState.of(it) } else null
            val filled = fs?.filled
            val payload = payloads?.get(q.ticker)
            if (filled != false && payload == null) { deferred++; continue }
            val held = payload?.let { maxOf(it.num("yes_count_fp") ?: it.num("yes_count") ?: 0.0, it.num("no_count_fp") ?: it.num("no_count") ?: 0.0) } ?: 0.0
            if (payload != null && held > 0) {
                val pnl = settlementPnl(payload)
                val yes = payload.num("yes_count_fp") ?: payload.num("yes_count") ?: 0.0
                val no = payload.num("no_count_fp") ?: payload.num("no_count") ?: 0.0
                ctx.stores.ledger.update(q.copy(resolvedAt = now, result = result, pnl = pnl.value, pnlSource = Source.KALSHI_API,
                    pnlOrigin = pnl.origin, hypothetical = false, filledContracts = held, fillSource = "Kalshi API settlement yes/no_count",
                    extra = if (minOf(yes, no) > 0) buildJsonObject { q.extra.forEach { (k, v) -> put(k, v) }; put("closed_before_settlement", minOf(yes, no)) } else q.extra))
                ctx.trade("settled", "${q.ticker} settled ${result.uppercase()}: ${money(pnl.value)} (exchange figure)", mapOf("pnl" to pnl))
            } else {
                // Never filled, per the exchange: graded as hypothetical, never summed as realised.
                val n = q.contracts
                val pnl = LongshotModel.pnlFor(q.price, n.roundToInt(), result)
                ctx.stores.ledger.update(q.copy(resolvedAt = now, result = result, pnl = pnl, pnlSource = Source.DERIVED,
                    pnlOrigin = "hypothetical: unfilled quote graded at full size", hypothetical = true,
                    filledContracts = fs?.contracts ?: 0.0, fillSource = fs?.let { "Kalshi API ${it.origin}" }))
            }
            closed++
        }
        if (deferred > 0) ctx.warn("resolve", "$deferred live row(s) deferred: settlement payload not readable yet.")
        return closed
    }

    private suspend fun checkFeeTypes(ctx: StrategyContext) {
        val at = ctx.stores.state.get(key(ctx, "fee_types_at"))?.let { Instant.parse(it) }
        if (at != null && Duration.between(at, ctx.clock.now()) < Duration.ofHours(6)) return
        val bad = mutableListOf<String>()
        for (s in WeatherSeries.ALL) {
            val ft = runCatching { ctx.kalshi.seriesFeeType(s) }.getOrNull() ?: return   // unreadable: try again later
            if (ft.value != "quadratic") bad += "$s=${ft.value}"
        }
        ctx.stores.state.put(key(ctx, "fee_types_at"), ctx.clock.now().toString())
        ctx.stores.state.put(key(ctx, "fee_types_ok"), bad.isEmpty().toString())
        if (bad.isNotEmpty()) {
            ctx.error("kill", "Kill criterion: series fee type changed (${bad.joinToString()}). Live quoting stops.")
            ctx.notify("Weather longshot stopped", "Kalshi changed the fee type on ${bad.size} weather series. Re-measure before re-arming.")
        }
    }

    // ---------------------------------------------------------------- UI hooks
    override suspend fun liveRequirements(ctx: StrategyContext): List<Requirement> {
        val creds = ctx.kalshi.hasCredentials
        val pre = ctx.stores.state.get("$id.preflight_ok_at")?.let { Instant.parse(it) }
        val preOk = pre != null && Duration.between(pre, ctx.clock.now()) < Duration.ofDays(7)
        val rec = if (creds) {
            val known = ctx.stores.ledger.all(id, Mode.LIVE)
            reconcileKalshi(ctx.kalshi, { WeatherSeries.kind(it) != null },
                known.mapNotNull { it.orderId }.toSet(), known.filter { it.open }.map { it.ticker }.toSet())
        } else null
        val errs = validate(ctx.config)
        return listOf(
            Requirement("creds", "Kalshi API key saved", creds, true, if (creds) "Key loaded." else "Add your key under Accounts."),
            Requirement("preflight", "Preflight passed in the last 7 days", preOk, true,
                if (preOk) "Last passed $pre." else "Run Preflight: it reads your balance and places one \$0.01-collateral test order, then cancels it."),
            Requirement("settings", "Brake and lot size agree", errs.isEmpty(), true, errs.joinToString(" ").ifEmpty { "One worst-case market fits under the brake." }),
            Requirement("reconcile", "No other bot trading these markets", rec?.clean == true, true, rec?.message ?: "Needs the API key."),
            Requirement("gate", "Accept: the stage-1 gate failed on 2026-09-16", false, false,
                "Losses ran at 0.92 of what prices implied against a 0.90 bar (37 of 40.3). The repo's own rule was not to add capital until it passes."),
            Requirement("risk", "Accept: one loss can be up to 98c per contract", false, false,
                "This wins small and often and loses big and rarely. Days with several losses happen; about 30% of days were red in the realistic replay."),
        )
    }

    override suspend fun preflight(ctx: StrategyContext): PreflightResult {
        val steps = mutableListOf<Pair<String, Boolean>>()
        try {
            val b = ctx.kalshi.balance()
            steps += "Read balance: ${money(b.cash.value)} cash (from ${b.cash.origin})" to true
            steps += "Exchange reports portfolio value" to (b.portfolioValue != null)
            val open = ctx.kalshi.openMarkets("KXHIGHNY")
            steps += "List open markets (${open.size} in KXHIGHNY)" to open.isNotEmpty()
            val fee = ctx.kalshi.seriesFeeType("KXHIGHNY")
            steps += "Series fee type is quadratic (makers pay nothing): ${fee?.value}" to (fee?.value == "quadratic")
            val target = open.filter { it.book != null }.maxByOrNull { it.closeTime ?: Instant.EPOCH }
                ?: return PreflightResult(false, steps, "No open market to test against.")
            val tick = tickSize(0.99, target.raw.priceRanges())
            val placed = ctx.kalshi.placeOrder(target.ticker, "ask", 1, 0.99, tick.value, target.exchangeIndex)
            steps += "Place 1-contract post-only test offer at 99c on ${target.ticker} (locks \$0.01)" to (placed.orderId != null)
            if (placed.orderId != null) {
                val seen = ctx.kalshi.order(placed.orderId)
                steps += "Exchange shows the order (${seen?.str("status")})" to (seen != null)
                val red = ctx.kalshi.cancelOrder(placed.orderId, target.ticker)
                steps += "Cancel it (reduced by ${red?.value})" to (red != null)
            }
        } catch (e: Exception) {
            steps += "Error: ${e.message}" to false
        }
        val ok = steps.all { it.second }
        if (ok) ctx.stores.state.put("$id.preflight_ok_at", ctx.clock.now().toString())
        ctx.info("preflight", "Preflight ${if (ok) "passed" else "FAILED"}: ${steps.joinToString("; ") { (s, k) -> (if (k) "ok " else "FAIL ") + s }}")
        return PreflightResult(ok, steps, if (ok) "All checks passed." else "Some checks failed; live trading stays blocked.")
    }

    override suspend fun stats(ctx: StrategyContext): List<Stat> {
        val out = mutableListOf<Stat>()
        for (m in listOf(Mode.LIVE, Mode.PAPER)) {
            val rows = ctx.stores.ledger.all(id, m)
            if (rows.isEmpty()) continue
            val s: BookSummary = summarise(rows)
            out += bookStats(if (m == Mode.LIVE) "Live" else "Paper", s, m == Mode.LIVE)
            val open = rows.filter { it.open }
            out += Stat("${if (m == Mode.LIVE) "Live" else "Paper"} open quotes", Sourced("${open.size}, ${money(committedCollateral(open))} collateral", Source.DERIVED, "open ledger rows"))
            val settled = rows.filter { it.resolvedAt != null && it.hypothetical == false }
            val implied = settled.sumOf { quotedMid(it) }
            val losses = settled.count { it.result == "yes" }
            if (settled.size >= 20) out += Stat("${if (m == Mode.LIVE) "Live" else "Paper"} losses vs implied",
                Sourced("$losses vs ${"%.1f".format(implied)} (${"%.2f".format(if (implied > 0) losses / implied else 0.0)})", Source.DERIVED,
                    "YES settlements / sum of quoted mids; below 1.0 means the bias is paying"))
        }
        return out
    }

    /** The mid when quoted: the market's implied P(YES) at the time, which the realised loss count is compared against. */
    private fun quotedMid(q: QuoteRow): Double {
        val b = (q.extra["yes_bid"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
        val a = (q.extra["yes_ask"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
        return if (a != null && b != null) (a + b) / 2 else q.price
    }

    companion object {
        val SETTLED = setOf("settled", "finalized", "closed", "determined")

        fun parseBands(text: String): List<Pair<Double, Double>> = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.map {
            val (a, b) = it.split('-').map { s -> s.trim().toDouble() }
            require(a < b)
            a to b
        }
    }
}
