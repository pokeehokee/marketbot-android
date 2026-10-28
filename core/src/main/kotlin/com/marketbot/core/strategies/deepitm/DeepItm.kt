package com.marketbot.core.strategies.deepitm

import com.marketbot.core.provenance.Source
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.store.QuoteRow
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
import com.marketbot.core.strategy.Venue
import com.marketbot.core.strategy.bookStats
import com.marketbot.core.strategy.money
import com.marketbot.core.strategy.realisedSince
import com.marketbot.core.strategy.startOfLocalDay
import com.marketbot.core.strategy.summarise
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant

/**
 * Deep-in-the-money maker bids on a BTC "above $X" ladder, venue-agnostic.
 * Port of weatherbot `deep_itm/runner.py`. Each pass is one 2-second step:
 *
 *   guard (kill > total kill > daily stop > stale index > warming up >
 *   exchange closed > listing failed) -> requote -> fills every 10 s ->
 *   grading every 60 s.
 *
 * Any guard but "quote" cancels every standing quote; fills are held to
 * settlement. A failed listing within 10 s of a good one HOLDS quotes rather
 * than cancelling (one 429 should not cost queue position).
 */
abstract class DeepItmStrategy(
    final override val id: String,
    final override val name: String,
    final override val venue: Venue,
    final override val summary: String,
    final override val evidence: Evidence,
    private val defaultSkipHours: String,
    private val loopSeconds: Long,
    private val defaultDeadMan: Int = 120,
) : Strategy {
    abstract fun ladderVenue(ctx: StrategyContext): LadderVenue

    override val needsBtcIndex = true

    override val params: List<Param> = listOf(
        Param.Int_("lot", "Contracts per strike", "Flat lot. Sizing as a share of cash made the paper method lose \$80 on a day the live one made \$5.", 5, 1, 50),
        Param.Int_("max_open", "Max strikes at once", "Strikes held or quoted at the same time.", 8, 1, 20),
        Param.Num("band_lo", "Lowest bid joined", "Bottom of the price band.", 0.90, 0.50, 0.99),
        Param.Num("band_hi", "Highest bid joined", "Top of the price band.", 0.96, 0.50, 0.99),
        Param.Num("fair_min", "Minimum model probability", "Random-walk P(our side wins) required.", 0.97, 0.80, 0.999, decimals = 3),
        Param.Int_("ttc_min", "Earliest, seconds before close", "Stop joining this close to settlement.", 300, 30, 3600, "s"),
        Param.Int_("ttc_max", "Latest, seconds before close", "Start joining this long before settlement.", 3000, 60, 7200, "s"),
        Param.Text("skip_hours_et", "Skip hours closing at (ET)", "Session opens, where losses ran 3-7x higher.", defaultSkipHours),
        Param.Num("daily_stop", "Daily stop", "Stop for the day at this realised loss.", 15.0, 1.0, 500.0, "$"),
        Param.Num("total_kill", "Total kill", "Stop for good (until reset) at this total realised loss.", 60.0, 5.0, 5000.0, "$"),
        Param.Int_("dead_man_s", "Order lifetime without the app", "Live bids expire at the exchange this long after the app last renewed them, so a phone that dies or sleeps cannot leave a stale bid. Renewed while the app runs.", defaultDeadMan, 30, 3000, "s"),
    )

    fun rules(c: StrategyConfig) = DeepItmRules(
        lot = c.int("lot"), maxOpen = c.int("max_open"), bandLo = c.num("band_lo"), bandHi = c.num("band_hi"),
        fairMin = c.num("fair_min"), ttcMinS = c.int("ttc_min").toDouble(), ttcMaxS = c.int("ttc_max").toDouble(),
        skipCloseHoursEt = c.text("skip_hours_et").split(',', ' ').mapNotNull { it.trim().toIntOrNull() }.toSet(),
        dailyStop = -c.num("daily_stop"), totalKill = -c.num("total_kill"),
    )

    override fun validate(config: StrategyConfig): List<String> {
        val r = rules(config); val out = mutableListOf<String>()
        if (r.bandLo >= r.bandHi) out += "The lowest bid must be below the highest bid."
        if (r.ttcMinS >= r.ttcMaxS) out += "The earliest time must be closer to the close than the latest."
        if (r.fairMin <= r.bandHi) out += "Minimum model probability must exceed the highest bid, or the rule buys with no modelled edge."
        return out
    }

    override fun nextDelay(ctx: StrategyContext): Duration = Duration.ofSeconds(loopSeconds)

    private class Runtime {
        var mode = "start"; var lastFill = 0L; var lastGrade = 0L; var lastListingOk = Long.MIN_VALUE / 2
        var lastStatusAt = Long.MIN_VALUE / 2; var exchangeOpen = false; var lastError: String? = null; var started = false
    }
    private val runtimes = mutableMapOf<Mode, Runtime>()
    private fun rt(m: Mode) = runtimes.getOrPut(m) { Runtime() }
    private fun bookMode(ctx: StrategyContext) = if (ctx.live) Mode.LIVE else Mode.PAPER
    private fun k(ctx: StrategyContext, s: String) = "$id.${bookMode(ctx).name.lowercase()}.$s"

    override suspend fun runOnce(ctx: StrategyContext): RunReport {
        if (ctx.mode == Mode.OFF) return RunReport("off")
        if (ctx.mode == Mode.LIVE && !ctx.liveArmed) return RunReport("live not armed")
        val errs = validate(ctx.config)
        if (errs.isNotEmpty()) { ctx.error("config", errs.joinToString(" ")); return RunReport("invalid settings") }
        val r = rules(ctx.config)
        val mode = bookMode(ctx)
        val rt = rt(mode)
        val venue = ladderVenue(ctx)
        val now = ctx.clock.now()
        val t = System.currentTimeMillis()
        if (!rt.started) { closeOrphans(ctx, venue); rt.started = true }

        var guard = guardMode(ctx, r, now)
        if (guard == "quote" && t - rt.lastStatusAt >= 30_000) {
            rt.exchangeOpen = runCatching { venue.tradingOpen() }.onFailure { rt.lastError = "status: ${it.message}" }.getOrDefault(false)
            rt.lastStatusAt = t
        }
        if (guard == "quote" && !rt.exchangeOpen) guard = "exchange_closed"
        var markets: List<LadderMarket> = emptyList()
        val spot = ctx.btcIndex.price(); val sigma = ctx.btcIndex.sigma()
        if (guard == "quote") {
            try {
                markets = venue.ladder(now, spot!!, sigma!!)
                rt.lastListingOk = t
            } catch (e: Exception) {
                rt.lastError = "listing: ${e.message}"
                guard = if (t - rt.lastListingOk < 10_000) "listing_retry" else "listing_failed"
            }
        }
        when (guard) {
            "listing_retry" -> {}
            "quote" -> {
                if (rt.mode != "quote") ctx.info("mode", "Mode ${rt.mode} -> quote")
                requote(ctx, venue, r, now, markets, spot!!, sigma!!)
            }
            else -> cancelAll(ctx, venue, guard, rt)
        }
        rt.mode = guard
        if (t - rt.lastFill >= 10_000) { rt.lastFill = t; updateFills(ctx, venue, now) }
        if (t - rt.lastGrade >= 60_000) { rt.lastGrade = t; grade(ctx, venue, now) }
        ctx.stores.state.put(k(ctx, "heartbeat"), now.toString())
        return RunReport(guard, buildMap {
            ctx.btcIndex.sourcedPrice()?.let { put("btc_index", it) }
            sigma?.let { put("sigma_1s", Sourced.derived(it, "max(10 s index vol, 1-min candle vol) over 15 min", "volatility is computed, no venue publishes it")) }
        })
    }

    private suspend fun guardMode(ctx: StrategyContext, r: DeepItmRules, now: Instant): String {
        if (ctx.stores.state.get(k(ctx, "killed")) != null) return "killed"
        val rows = ctx.stores.ledger.all(id, bookMode(ctx))
        val total = realisedSince(rows, null)
        if (total <= r.totalKill) {
            ctx.stores.state.put(k(ctx, "killed"), "total kill at $now: ${money(total)}")
            ctx.error("kill", "Total kill: realised ${money(total)} reached ${money(r.totalKill)}. Stopped until you reset it.")
            ctx.notify("$name stopped", "Total realised loss ${money(total)} hit the kill level.")
            return "total_kill"
        }
        if (realisedSince(rows, startOfLocalDay(now)) <= r.dailyStop) return "daily_stop"
        if (ctx.btcIndex.age() > com.marketbot.core.btc.STALE_S || ctx.btcIndex.price() == null) return "stale_index"
        if (ctx.btcIndex.sigma() == null) return "warming_up"
        return "quote"
    }

    private suspend fun cancelAll(ctx: StrategyContext, venue: LadderVenue, why: String, rt: Runtime) {
        val now = ctx.clock.now()
        for (q in ctx.stores.ledger.open(id, bookMode(ctx)).filter { it.resting }) {
            if (ctx.live) runCatching { venue.cancel(q) }.onFailure { ctx.warn("cancel", "Cancel failed on ${q.ticker}: ${it.message}") }
            finalFill(ctx, venue, q.copy(cancelledAt = now), now)
        }
        if (why != rt.mode) ctx.info("mode", "Mode ${rt.mode} -> $why")
    }

    private suspend fun requote(ctx: StrategyContext, venue: LadderVenue, r: DeepItmRules, now: Instant, markets: List<LadderMarket>, spot: Double, sigma: Double) {
        val mode = bookMode(ctx)
        val open = ctx.stores.ledger.open(id, mode)
        val held = open.filter { (it.filledContracts ?: 0.0) > 0 }.map { it.ticker }.toSet()
        val standing = open.filter { it.resting }.associateBy { it.ticker }
        val cands = DeepItmRule.candidates(markets, spot, sigma, now, r)
        val chosen = DeepItmRule.select(cands, held + standing.keys, r.maxOpen).associateBy { it.ticker }
        val deadMan = java.time.Duration.ofSeconds(ctx.config.int("dead_man_s").toLong())
        for ((ticker, q) in standing) {
            val c = chosen[ticker]
            val side = if (q.action == "buy_yes") "yes" else "no"
            if (c == null || c.side != side || kotlin.math.abs(c.price - q.price) > 1e-9) {
                if (ctx.live) runCatching { venue.cancel(q) }.onFailure { ctx.warn("cancel", "Cancel failed on $ticker: ${it.message}") }
                finalFill(ctx, venue, q.copy(cancelledAt = now), now)
            } else if (ctx.live) {
                val exp = expiresAt(q)
                if (exp != null && java.time.Duration.between(now, exp) < deadMan.dividedBy(2)) {
                    val next = minOf(q.closeTime ?: now.plus(deadMan), now.plus(deadMan))
                    if (venue.renew(q, next)) ctx.stores.ledger.update(q.copy(extra = withExpiry(q.extra, next)))
                    else if (!exp.isAfter(now.plusSeconds(5))) {
                        // Could not renew and about to lapse: close it here; the next step re-places it.
                        runCatching { venue.cancel(q) }
                        finalFill(ctx, venue, q.copy(cancelledAt = now), now)
                    }
                }
            }
        }
        val live = ctx.stores.ledger.open(id, mode).filter { it.resting }.map { it.ticker }.toSet()
        for ((ticker, c) in chosen) {
            if (ticker in live || ticker in held) continue
            val m = markets.first { it.ticker == ticker }
            var orderId: String? = null; var coid: String? = null
            val expiry = minOf(m.close, now.plus(deadMan))
            if (ctx.live) {
                val res = runCatching { venue.place(c, m, expiry) }
                val pair = res.getOrNull()
                if (pair == null) { ctx.error("order", "Order failed on $ticker: ${res.exceptionOrNull()?.message}"); continue }
                orderId = pair.first; coid = pair.second
                if (orderId == null && coid == null) { ctx.info("order", "$ticker: not placed (post-only cross or side not enabled for live)."); continue }
            }
            ctx.stores.ledger.insert(QuoteRow(strategyId = id, mode = mode, venue = venue.venueKey, ticker = ticker,
                action = "buy_${c.side}", price = c.price, contracts = c.count.toDouble(), placedAt = now, closeTime = m.close,
                orderId = orderId, clientOrderId = coid, queueAhead = c.queueAhead,
                extra = buildJsonObject {
                    put("fair", c.fair); put("z", c.z); put("spot", spot); put("sigma", sigma); put("strike", m.strike)
                    put("tick", m.tick); m.exchangeIndex?.let { put("exchange_index", it) }
                    if (ctx.live) put("expires_at", expiry.toString())
                }))
            ctx.trade(if (ctx.live) "order" else "paper_quote",
                "${if (ctx.live) "BID" else "paper bid"} $ticker ${c.side.uppercase()} ${c.count} @ ${"%.3f".format(c.price)} (fair ${"%.4f".format(c.fair)}, z ${"%.2f".format(c.z)}, queue ${c.queueAhead.toInt()})",
                mapOf("bid" to Sourced(c.price, if (venue.venueKey == "kalshi") Source.KALSHI_API else Source.POLYMARKET_API, "best ${c.side} bid on $ticker"),
                    "fair" to Sourced.derived(c.fair, "Phi(ln(spot/strike) / (sigma sqrt(ttc)))", "model probability; no venue publishes it")))
        }
    }

    private fun expiresAt(q: QuoteRow): Instant? =
        (q.extra["expires_at"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun withExpiry(extra: kotlinx.serialization.json.JsonObject, at: Instant) =
        kotlinx.serialization.json.JsonObject(extra + ("expires_at" to kotlinx.serialization.json.JsonPrimitive(at.toString())))

    private suspend fun finalFill(ctx: StrategyContext, venue: LadderVenue, q: QuoteRow, now: Instant) {
        val f = runCatching { if (ctx.live) venue.liveFill(q) else venue.paperFill(q, now, null) }
            .onFailure { rt(bookMode(ctx)).lastError = "fill ${q.ticker}: ${it.message}" }.getOrNull()
        ctx.stores.ledger.update(q.copy(filledContracts = maxOf(q.filledContracts ?: 0.0, f?.filled ?: 0.0),
            fillSource = f?.source ?: q.fillSource, fillCheckedAt = now))
    }

    private suspend fun updateFills(ctx: StrategyContext, venue: LadderVenue, now: Instant) {
        for (q in ctx.stores.ledger.open(id, bookMode(ctx)).filter { it.resting && (it.filledContracts ?: 0.0) < it.contracts })
            finalFill(ctx, venue, q, now)
    }

    private suspend fun grade(ctx: StrategyContext, venue: LadderVenue, now: Instant) {
        val due = ctx.stores.ledger.open(id, bookMode(ctx)).filter { it.closeTime != null && !it.closeTime.isAfter(now) }
        val results = mutableMapOf<String, Sourced<String>?>()
        for (q in due) {
            val res = results.getOrPut(q.ticker) { runCatching { venue.result(q.ticker) }.getOrNull() } ?: continue
            val side = if (q.action == "buy_yes") "yes" else "no"
            val filled = q.filledContracts ?: 0.0
            if (!ctx.live) {
                val pnl = DeepItmRule.pnl(side, q.price, filled, res.value)
                ctx.stores.ledger.update(q.copy(resolvedAt = now, result = res.value, pnl = pnl, pnlSource = Source.PAPER_MODEL,
                    pnlOrigin = "simulated fills x (payout - price); result: ${res.origin}", hypothetical = filled <= 0))
                if (filled > 0) ctx.trade("settled", "Paper ${q.ticker} ${side.uppercase()} settled ${res.value.uppercase()}: ${money(pnl)}")
                continue
            }
            if (filled <= 0) {
                ctx.stores.ledger.update(q.copy(resolvedAt = now, result = res.value, pnl = 0.0, pnlSource = Source.DERIVED,
                    pnlOrigin = "never filled (per ${q.fillSource})", hypothetical = true))
                continue
            }
            val pnl = runCatching { venue.livePnl(q, res.value) }.getOrNull() ?: continue   // wait for the exchange figure
            ctx.stores.ledger.update(q.copy(resolvedAt = now, result = res.value, pnl = pnl.value, pnlSource = pnl.source,
                pnlOrigin = pnl.origin, hypothetical = false))
            ctx.trade("settled", "${q.ticker} ${side.uppercase()} settled ${res.value.uppercase()}: ${money(pnl.value)} (${pnl.source.label})", mapOf("pnl" to pnl))
        }
    }

    /** Quotes left standing by a crash must not keep collecting paper fills: close them at the last heartbeat. */
    private suspend fun closeOrphans(ctx: StrategyContext, venue: LadderVenue) {
        val hb = ctx.stores.state.get(k(ctx, "heartbeat"))?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val standing = ctx.stores.ledger.open(id, bookMode(ctx)).filter { it.resting }
        for (q in standing) {
            val at = hb?.let { maxOf(it, q.placedAt) } ?: q.placedAt
            if (ctx.live) runCatching { venue.cancel(q) }
            finalFill(ctx, venue, q.copy(cancelledAt = at), ctx.clock.now())
        }
        if (standing.isNotEmpty()) ctx.warn("orphans", "Closed ${standing.size} quote(s) left standing by the last run at $hb.")
    }

    override suspend fun onStop(ctx: StrategyContext) {
        if (ctx.mode == Mode.OFF) return
        val venue = ladderVenue(ctx)
        cancelAll(ctx, venue, "stopped", rt(bookMode(ctx)))
        rt(bookMode(ctx)).started = false
    }

    suspend fun resetKill(ctx: StrategyContext) { ctx.stores.state.put(k(ctx, "killed"), null) }

    override suspend fun preflight(ctx: StrategyContext): PreflightResult {
        val steps = runCatching { ladderVenue(ctx).preflight() }.getOrElse { listOf("Error: ${it.message}" to false) }
        val idx = ctx.btcIndex.price() != null && ctx.btcIndex.sigma() != null
        val all = steps + ("BTC index and volatility are live (${ctx.btcIndex.freshVenues()} venues fresh)" to idx)
        val ok = all.all { it.second }
        if (ok) ctx.stores.state.put("$id.preflight_ok_at", ctx.clock.now().toString())
        ctx.info("preflight", "Preflight ${if (ok) "passed" else "FAILED"}: ${all.joinToString("; ") { (s, b) -> (if (b) "ok " else "FAIL ") + s }}")
        return PreflightResult(ok, all, if (ok) "All checks passed." else "Some checks failed; live trading stays blocked.")
    }

    override suspend fun liveRequirements(ctx: StrategyContext): List<Requirement> {
        val pre = ctx.stores.state.get("$id.preflight_ok_at")?.let { Instant.parse(it) }
        val preOk = pre != null && Duration.between(pre, ctx.clock.now()) < Duration.ofDays(7)
        val paper = summarise(ctx.stores.ledger.all(id, Mode.PAPER))
        val first = ctx.stores.ledger.all(id, Mode.PAPER).minOfOrNull { it.placedAt }
        val days = first?.let { Duration.between(it, ctx.clock.now()).toHours() / 24.0 } ?: 0.0
        val record = days >= 3 && (paper.winRate ?: 0.0) >= 0.94 && (paper.perContract ?: -1.0) > 0
        val creds = if (venue == Venue.KALSHI) ctx.kalshi.hasCredentials else ctx.polymarket.hasCredentials
        return listOf(
            Requirement("creds", "${venue.label} API key saved", creds, true, if (creds) "Key loaded." else "Add your key under Accounts."),
            Requirement("preflight", "Preflight passed in the last 7 days", preOk, true, if (preOk) "Last passed $pre." else "Run Preflight."),
            Requirement("paper", "3+ days of paper results near the original (about 96% hit rate, +2c/contract)", record, false,
                "Paper so far: %.1f days, %d filled, hit rate %s, %s per contract.".format(days, paper.filled,
                    paper.winRate?.let { "%.1f%%".format(it * 100) } ?: "n/a", paper.perContract?.let { "%+.2fc".format(it * 100) } ?: "n/a")),
            Requirement("battery", "Accept: the phone must stay awake and online during the window", false, false,
                "This re-quotes every few seconds near the close. If Android suspends the app, quotes can rest unattended until they fill or the market closes."),
        )
    }

    override suspend fun stats(ctx: StrategyContext): List<Stat> {
        val out = mutableListOf<Stat>()
        for (m in listOf(Mode.LIVE, Mode.PAPER)) {
            val rows = ctx.stores.ledger.all(id, m)
            if (rows.isNotEmpty()) out += bookStats(if (m == Mode.LIVE) "Live" else "Paper", summarise(rows), m == Mode.LIVE)
        }
        ctx.btcIndex.sourcedPrice()?.let { out += Stat("BTC index (proxy)", it.map { v -> "\$%,.2f".format(v) }) }
        ctx.btcIndex.sigma()?.let { out += Stat("Volatility, per sqrt(s)", Sourced.derived("%.2e".format(it), "max(index vol, candle vol)", "calculated")) }
        for (m in listOf(Mode.LIVE, Mode.PAPER)) ctx.stores.state.get("$id.${m.name.lowercase()}.killed")?.let {
            out += Stat("${m.name.lowercase()} kill", Sourced.config(it, "kill flag"))
        }
        return out
    }
}

class KalshiBtcDeepItm : DeepItmStrategy(
    id = "kalshi_btc_deep_itm",
    name = "BTC deep-ITM ladder",
    venue = Venue.KALSHI,
    summary = "Joins the best bid on near-certain strikes of Kalshi's hourly Bitcoin ladder (KXBTCD) in the last 50 minutes, holds to settlement.",
    evidence = Evidence(EvidenceLevel.REPORTED,
        "Reported +\$129 on ~1,260 real settlements, 2026-09-21 to 10-04, ~96% hit rate, ~+2c/contract.",
        listOf("The original runner's code and data stayed on the stored desktop; this is a rebuild from its recorded rules.",
            "Orders must name the Crypto & Commodities shard (exchange index 2) and that shard must hold cash.",
            "The original's first-day bug was duplicate orders from mis-routed cancels; cancels here always send the market ticker.",
            "A loss costs 90-96c per contract against a win of 4-10c."),
        "weatherbot branch ccr-f6dc9b99-xbiukn, commit f136342"),
    defaultSkipHours = "3,10,21,23",
    loopSeconds = 2,
) {
    override fun ladderVenue(ctx: StrategyContext): LadderVenue = venues.getOrPut(ctx.kalshi) { KalshiBtcLadder(ctx.kalshi) }
    private val venues = mutableMapOf<Any, LadderVenue>()
}

class PolymarketBtcDeepItm : DeepItmStrategy(
    id = "polymarket_btc_deep_itm",
    name = "BTC deep-ITM (Polymarket)",
    venue = Venue.POLYMARKET,
    summary = "The same rule on Polymarket US's daily 'BTC above \$X at 5 PM ET' ladder: joins near-certain bids in the last 50 minutes before 5 PM ET.",
    evidence = Evidence(EvidenceLevel.UNPROVEN,
        "Never measured. The rule was calibrated on Kalshi's hourly ladder.",
        listOf("Polymarket US has no hourly BTC ladder; the daily 5 PM ET one settles on the 60-s CF BRTI average.",
            "Paper fills come from book snapshots (no public tape), which over-credits fills when size ahead cancels.",
            "Live NO-side bids stay off until the short-price convention is confirmed."),
        "gateway.polymarket.us event btc-above-day-*, checked 2026-10-27"),
    defaultSkipHours = "",
    loopSeconds = 5,
    defaultDeadMan = 300,
) {
    override val params: List<Param> = super.params + Param.Bool("allow_no_live", "Allow live NO bids",
        "Polymarket's price convention for short (NO) orders is not documented; leave off until preflight confirms it.", false)

    override fun ladderVenue(ctx: StrategyContext): LadderVenue =
        (venues.getOrPut(ctx.polymarket) { PolymarketBtcLadder(ctx.polymarket) } as PolymarketBtcLadder)
            .also { it.allowNoLive = ctx.config.bool("allow_no_live") }
    private val venues = mutableMapOf<Any, LadderVenue>()
}
