package com.marketbot.core.strategies.rewards

import com.marketbot.core.polymarket.PmBook
import com.marketbot.core.polymarket.PmMarketMeta
import com.marketbot.core.polymarket.pmIntentFor
import com.marketbot.core.provenance.Source
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.provenance.ValueUnavailable
import com.marketbot.core.store.EquitySnapshot
import com.marketbot.core.strategy.variantName
import com.marketbot.core.strategy.Help
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
import com.marketbot.core.strategy.money
import com.marketbot.core.util.JSON
import com.marketbot.core.util.num
import com.marketbot.core.util.obj
import com.marketbot.core.util.str
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Polymarket US liquidity-reward farming. Port of pmfarm: rest maker-only
 * bids at the best price on markets whose reward pool is large against the
 * size already resting, collect the per-second pool, exit fills at
 * break-even or better, never sell at a loss before settlement.
 */
class RewardFarm(override val id: String = ID, override val baseId: String? = null) : Strategy {
    override val name = variantName("Liquidity-reward farming", id, baseId)
    override val venue = Venue.POLYMARKET
    override val summary = "Rests maker-only bids where Polymarket's liquidity reward pool is large against the competing size, and collects the reward."
    override val evidence = Evidence(EvidenceLevel.UNPROVEN,
        "Estimated ~\$108/day on \$10; actually paid \$0.26 on its one live day. The account went \$10 to \$7.42.",
        listOf("The estimate is the program's scoring formula and assumes a 50/50 pool split between book sides; it has never been calibrated against payouts.",
            "Payouts under \$1 per market are not paid; rewards land days later.",
            "Fills carry adverse selection on thin books; the one loss on record was a BTC range market, now excluded.",
            "Run paper, then compare 'estimated' against 'paid' (Polymarket's earnings) before trusting any figure."),
        "polymarket repo README (claude/intelligent-dirac, 2026-10-04)")

    override val help = Help(
        makes = "Polymarket US pays liquidity rewards to resting orders near the middle of the book on selected markets. This strategy keeps small bids " +
            "(and, once verified, offers) resting where the program pays, and collects the rewards.",
        loses = "Resting orders get filled, often by someone who knows more. A filled position can lose more than the rewards pay, and the reward " +
            "estimates have never matched what was actually paid: the one live day paid \$0.26 against about \$108 estimated.",
        notes = listOf(
            "Rewards arrive days later and payouts under \$1 per market are not paid.",
            "Set the calibration from 'Rewards earned' against 'estimated' before trusting any \$/day figure.",
            "It cancels only orders it placed; orders you place by hand are left alone.",
        ),
    )

    override val params: List<Param> = listOf(
        Param.Num("paper_cash", "Paper account size", "Starting cash of the virtual account.", 10.0, 1.0, 100000.0, "$"),
        Param.Int_("max_instruments", "Max markets", "Markets worked at once.", 25, 1, 60),
        Param.Num("min_expected_daily", "Min estimated \$/day per market", "Selection gate (after calibration). Payouts under \$1 are not paid.", 1.25, 0.1, 100.0, "$"),
        Param.Num("reward_calibration", "Calibration (paid / estimated)", "Multiply every estimate by this. Set it from the 'Paid vs estimated' figure once rewards arrive.", 1.0, 0.0, 2.0, decimals = 3),
        Param.Num("per_instrument_frac", "Max loss per market, share of equity", "Worst-case loss allowed in one market.", 0.35, 0.01, 1.0),
        Param.Num("per_instrument_max_usd", "Max loss per market", "Absolute cap per market.", 25.0, 0.5, 10000.0, "$"),
        Param.Num("cash_reserve_frac", "Cash reserve", "Share of equity no entry may consume.", 0.25, 0.0, 0.9),
        Param.Num("max_inventory_frac", "Stop opening above", "Stop new entries when held positions exceed this share of equity.", 0.60, 0.05, 1.0),
        Param.Num("max_drawdown_frac", "Kill at drawdown", "Halt everything below the high-water mark x (1 - this).", 0.25, 0.01, 0.9),
        Param.Num("hard_floor_usd", "Kill below equity", "Absolute halt level.", 6.0, 0.0, 100000.0, "$"),
        Param.Num("start_equity", "Protect at least", "High-water mark never starts below this (new install).", 0.0, 0.0, 100000.0, "$"),
        Param.Num("max_book_spread", "Max book spread", "Require a two-sided, anchored book.", 0.08, 0.01, 0.5),
        Param.Num("event_cutoff_min", "Pull quotes before event start", "Never quote in-game.", 45.0, 5.0, 600.0, "min"),
        Param.Text("exclude_slug_prefixes", "Excluded market families", "Fast-underlying markets get picked off.", "cpc-,cpoc-btc,cpoc-eth"),
        Param.Int_("tick_seconds", "Re-quote every", "Seconds between quoting passes.", 15, 5, 300, "s"),
        Param.Bool("short_quotes", "Quote the NO side too", "Rest asks as well as bids. Live needs Preflight to confirm Polymarket's NO-side price convention first; until then live quotes bids only, as pmfarm did.", false),
        Param.Int_("rescan_minutes", "Rescan markets every", "Full universe scan interval.", 20, 5, 240, "min"),
        Param.Int_("requote_tolerance_ticks", "Keep a quote within", "Leave a resting entry quote alone while its target is within this many ticks, keeping its place in the queue (Hummingbot's refresh tolerance). 0 re-quotes on any move, as pmfarm did. Unmeasured: try in paper.", 0, 0, 10, "ticks"),
        Param.Int_("max_order_age_min", "Replace quotes older than", "Cancel and re-place a quote after this many minutes even if unchanged. 0 = never. Unmeasured.", 0, 0, 600, "min"),
        Param.Int_("fill_delay_s", "Pause after a fill", "Seconds to wait after a fill in a market before quoting a new entry there. 0 = no pause. Unmeasured.", 0, 0, 3600, "s"),
    )

    fun rules(c: StrategyConfig) = FarmRules(
        minExpectedDaily = c.num("min_expected_daily"), rewardCalibration = c.num("reward_calibration"),
        maxInstruments = c.int("max_instruments"), perInstrumentFrac = c.num("per_instrument_frac"),
        perInstrumentMaxUsd = c.num("per_instrument_max_usd"), cashReserveFrac = c.num("cash_reserve_frac"),
        maxInventoryFrac = c.num("max_inventory_frac"), maxDrawdownFrac = c.num("max_drawdown_frac"),
        hardFloorUsd = c.num("hard_floor_usd"), startEquity = c.num("start_equity"),
        maxBookSpread = c.num("max_book_spread"), eventCutoffMin = c.num("event_cutoff_min"),
        excludeSlugPrefixes = c.text("exclude_slug_prefixes").split(',').map { it.trim() }.filter { it.isNotEmpty() },
        requoteToleranceTicks = c.int("requote_tolerance_ticks"), maxOrderAgeMin = c.int("max_order_age_min").toDouble(), fillDelaySec = c.int("fill_delay_s"),
    )

    override fun effects(config: StrategyConfig): List<String> {
        val cash = config.num("paper_cash")
        val perMarket = minOf(config.num("per_instrument_frac") * cash, config.num("per_instrument_max_usd"))
        val floor = maxOf(config.num("hard_floor_usd"), maxOf(cash, config.num("start_equity")) * (1 - config.num("max_drawdown_frac")))
        return listOf(
            "Worst loss in any one market: ${money(perMarket)} (on a ${money(cash)} paper account).",
            "Everything halts if equity falls to ${money(floor)}, until you reset it.",
            "Requotes every ${config.int("tick_seconds")} s: about ${86_400 / config.int("tick_seconds")} passes a day while it runs.",
        )
    }

    override fun nextDelay(ctx: StrategyContext): Duration = Duration.ofSeconds(ctx.config.int("tick_seconds").toLong())

    private class Rt {
        var targets: Map<String, FarmCandidate> = emptyMap(); var known = mutableMapOf<String, FarmCandidate>()
        var lastScan = Instant.EPOCH; var lastTick: Instant? = null; var paper: PaperFarmBroker? = null; var scannedOnce = false
        var lastSettleCheck = Instant.EPOCH; val metas = mutableMapOf<String, PmMarketMeta>()
        var live: LiveFarmBroker? = null
        /** When each resting order was first seen (for the maximum age); pruned to orders still resting. */
        val orderSeen = mutableMapOf<String, Instant>()
        /** The last fill seen per market (for the pause after a fill), and the position it left. */
        val lastFillAt = mutableMapOf<String, Instant>(); val lastPosQty = mutableMapOf<String, Double>()
        var funnel: List<com.marketbot.core.strategy.FunnelStep> = emptyList()
        /** Paper: public trade prints, when a Polymarket key is set (pmfarm's TradeStream). */
        var prints: com.marketbot.core.polymarket.PmTradeStream? = null
        /** Live: cancels the exchange may not have applied yet; their size still shows in the public book for up to 45 s. */
        val recentCancels = mutableMapOf<String, MutableList<RecentCancel>>()
    }
    private data class RecentCancel(val price: Double, val side: String, val qty: Double, val atMs: Long)
    private val rts = mutableMapOf<Mode, Rt>()
    init { com.marketbot.core.util.Diagnostics.track(this, "Reward farm market records") { f -> f.rts.values.sumOf { it.metas.size + it.known.size + it.recentCancels.size } } }
    private fun mode(ctx: StrategyContext) = if (ctx.live) Mode.LIVE else Mode.PAPER
    private fun k(ctx: StrategyContext, s: String) = "$id.${mode(ctx).name.lowercase()}.$s"

    private suspend fun broker(ctx: StrategyContext, rt: Rt): FarmBroker =
        if (ctx.live) rt.live ?: run {
            val ownedKey = k(ctx, "owned_orders")
            val owned = ctx.stores.state.get(ownedKey)?.split(',')?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
            LiveFarmBroker(ctx.polymarket, owned) { ids -> ctx.stores.state.put(ownedKey, ids.joinToString(",")) }.also { rt.live = it }
        }
        else rt.paper ?: PaperFarmBroker.fromJson(ctx.stores.state.get(k(ctx, "paper_state")), ctx.config.num("paper_cash")).also { rt.paper = it }

    private suspend fun savePaper(ctx: StrategyContext, rt: Rt) { rt.paper?.let { ctx.stores.state.put(k(ctx, "paper_state"), it.toJson()) } }

    private suspend fun riskState(ctx: StrategyContext): FarmRiskState? =
        ctx.stores.state.get(k(ctx, "risk"))?.let { runCatching { JSON.decodeFromString(RiskJson.serializer(), it) }.getOrNull() }
            ?.let { FarmRiskState(it.highWater, it.halted, it.reason) }

    private suspend fun saveRisk(ctx: StrategyContext, s: FarmRiskState) =
        ctx.stores.state.put(k(ctx, "risk"), JSON.encodeToString(RiskJson.serializer(), RiskJson(s.highWater, s.halted, s.reason)))

    private suspend fun meta(ctx: StrategyContext, rt: Rt, slug: String) = rt.metas.getOrPut(slug) { ctx.polymarket.market(slug) }

    override suspend fun runOnce(ctx: StrategyContext): RunReport {
        if (ctx.mode == Mode.OFF) return RunReport("off")
        if (ctx.mode == Mode.LIVE && !ctx.liveArmed) return RunReport("live not armed")
        val r = rules(ctx.config).let { base ->
            // Live NO-side quoting only once Preflight has shown the exchange prices shorts the way we send them.
            val shorts = ctx.config.bool("short_quotes") && (!ctx.live || ctx.stores.state.get("$id.no_convention") == NoConvention.YES_PRICE)
            base.copy(allowShortQuotes = shorts)
        }
        val rt = rts.getOrPut(mode(ctx)) { Rt() }
        val now = ctx.clock.now()
        if (inMaintenance(now)) { ctx.info("maintenance", "Exchange maintenance window (Thu 06:00-08:00 ET); pausing."); return RunReport("maintenance") }
        val dt = rt.lastTick?.let { Duration.between(it, now).toMillis() / 1000.0 } ?: 0.0
        rt.lastTick = now
        val br = broker(ctx, rt)

        // Paper: refresh books of everything held/quoted first, so marks are real before valuing.
        // Trade prints since the last tick go first: they say which shrinkage was trading.
        val books = mutableMapOf<String, PmBook>()
        if (!br.live) {
            val slugs = (br.positions().keys + br.openOrders().map { it.slug }).toSet()
            val subscribed = slugs + rt.targets.keys
            printStream(ctx, rt)?.setMarkets(subscribed)
            // Targets with nothing resting still get their prints drained: the broker only marks them trade-fed,
            // and an undrained market's inbox otherwise grows for as long as the farm runs.
            for (s in subscribed - slugs) rt.prints?.drain(s)?.forEach { br.onTrade(it) }
            for (s in slugs) {
                val fromPrints = rt.prints?.drain(s).orEmpty().flatMap { br.onTrade(it) }
                val b = runCatching { ctx.polymarket.book(s) }.getOrNull()
                if (b != null) books[s] = b
                for (f in fromPrints + (b?.let { br.onBook(it) } ?: emptyList())) {
                    rt.lastFillAt[f.slug] = now
                    ctx.trade("paper_fill", "Paper fill ${f.slug} ${f.side} ${"%.3f".format(f.price)} x ${"%.2f".format(f.qty)} (${f.why})")
                }
            }
        }
        settlements(ctx, rt, br, now)

        val bal = try { br.balance(books) } catch (e: ValueUnavailable) {
            ctx.error("pause", "Exchange state unavailable (${e.message}); cancelling and pausing.")
            runCatching { br.cancelAll() }; savePaper(ctx, rt); return RunReport("paused")
        }
        val positions = br.positions(); val orders = br.openOrders()
        if (br.live) for (slug in positions.keys + rt.lastPosQty.keys) {
            val q = positions[slug]?.qty ?: 0.0
            if (abs(q - (rt.lastPosQty[slug] ?: q)) > 1e-9) rt.lastFillAt[slug] = now
            if (q == 0.0) rt.lastPosQty.remove(slug) else rt.lastPosQty[slug] = q
        }
        val liveIds = orders.map { it.id }.toSet()
        rt.orderSeen.keys.retainAll(liveIds); for (id in liveIds) rt.orderSeen.putIfAbsent(id, now)
        ctx.stores.equity.record(EquitySnapshot(now, "polymarket", mode(ctx), bal.equity, null, bal.exchangeEquity))
        val risk = FarmRisk.update(riskState(ctx), bal.equity.value, r)
        saveRisk(ctx, risk)
        if (risk.halted) {
            ctx.error("halt", "KILL SWITCH: ${risk.reason}. All orders cancelled; reset it after review.", mapOf("equity" to bal.equity))
            ctx.notify("$name halted", risk.reason)
            runCatching { br.cancelAll() }; savePaper(ctx, rt)
            return RunReport("halted")
        }

        if (Duration.between(rt.lastScan, now) >= Duration.ofMinutes(ctx.config.int("rescan_minutes").toLong())) scan(ctx, rt, r, bal.equity.value, now)

        val inv = FarmRisk.inventoryCost(positions.mapValues { it.value.qty to it.value.avgPx })
        val mayOpen = inv < r.maxInventoryFrac * bal.equity.value
        var entrySlots = r.maxOpenOrders - orders.size
        val budget = FarmRisk.entryBudget(bal.equity.value, bal.freeCash.value, r)
        val bySlug = orders.groupBy { it.slug }
        val slugs = (rt.targets.keys + positions.keys + bySlug.keys).toSortedSet()
        var estRate = 0.0
        for (slug in slugs) {
            try {
                val res = workMarket(ctx, rt, br, r, slug, rt.targets[slug], positions[slug], bySlug[slug] ?: emptyList(), mayOpen, budget, now, books, entrySlots)
                estRate += res.first * r.rewardCalibration
                entrySlots = res.second
            } catch (e: Exception) {
                ctx.warn("market", "$slug skipped this tick: ${e.message}")
            }
        }
        if (dt > 0) accrue(ctx, now, estRate * dt / 86_400.0, dt)
        savePaper(ctx, rt)
        ctx.info("tick", "Working ${orders.map { it.slug }.toSet().size} market(s) with ${orders.size} order(s) and ${positions.size} position(s); equity ${money(bal.equity.value)}, rewards estimated at ${money(estRate)}/day",
            buildMap {
                put("equity", bal.equity); put("free_cash", bal.freeCash)
                bal.exchangeEquity?.let { put("exchange_equity", it) }
                put("est_rewards_per_day", Sourced.derived(estRate, "program scoring formula x calibration", "an estimate; only earnings are paid"))
            })
        return RunReport("tick", funnel = if (rt.funnel.isEmpty()) emptyList() else
            rt.funnel + com.marketbot.core.strategy.FunnelStep("Quoting now", orders.map { it.slug }.toSet().size))
    }

    private fun printStream(ctx: StrategyContext, rt: Rt): com.marketbot.core.polymarket.PmTradeStream? {
        if (rt.prints == null) ctx.polymarket.signerForStream?.let { s -> rt.prints = com.marketbot.core.polymarket.PmTradeStream(s).also { it.start() } }
        return rt.prints
    }

    private suspend fun scan(ctx: StrategyContext, rt: Rt, r: FarmRules, equity: Double, now: Instant) {
        rt.lastScan = now
        val programs = runCatching { ctx.polymarket.liquidityPrograms() }.getOrElse {
            ctx.warn("scan", "Incentive programs unavailable: ${it.message}; retrying next tick."); rt.lastScan = Instant.EPOCH; return
        }
        val all = programs.mapNotNull { FarmScanner.activePeriods(it, now, r) }.filter { it.sideRate >= r.minSideRatePerDay }
            .sortedByDescending { it.sideRate }
        // Cheap screen on bulk top of book (50 markets a request) before paying for full books, as pmfarm does.
        val bbo = runCatching { ctx.polymarket.marketsBulk(all.map { it.slug }) }.getOrElse {
            ctx.warn("scan", "Top-of-book screen unavailable: ${it.message}; retrying next tick."); rt.lastScan = Instant.EPOCH; return
        }
        bbo.forEach { (slug, v) -> rt.metas[slug] = v.first }
        val cands = FarmScanner.screen(all, bbo.mapValues { it.value.second }, bbo.filterValues { it.first.closed }.keys, r).take(r.maxCandidates)
        val budget = minOf(r.perInstrumentFrac * equity, r.perInstrumentMaxUsd)
        val plans = mutableListOf<QuotePlan>()
        for (c in cands) {
            val m = runCatching { meta(ctx, rt, c.slug) }.getOrNull() ?: continue
            val b = runCatching { ctx.polymarket.book(c.slug) }.getOrNull() ?: continue
            if (!b.open) continue
            FarmScanner.planQuote(c, m.tick, m.minQty, m.closed, m.active, b.bids, b.asks, r, budget)?.let { plans += it }
        }
        val selected = FarmScanner.select(plans, r)
        rt.known.putAll(cands.associateBy { it.slug })
        rt.targets = selected.associate { it.slug to rt.known.getValue(it.slug) }
        rt.scannedOnce = true
        rt.funnel = listOf(com.marketbot.core.strategy.FunnelStep("Markets in active reward programs", all.size),
            com.marketbot.core.strategy.FunnelStep("Pass the top-of-book screen", cands.size),
            com.marketbot.core.strategy.FunnelStep("A quote fits the rules and budget", plans.size),
            com.marketbot.core.strategy.FunnelStep("Selected to work", selected.size))
        ctx.info("scan", "${all.size} in programs, ${cands.size} passed the top-of-book screen, ${plans.size} plannable, ${selected.size} selected (est ${money(selected.sumOf { it.usdPerDay })}/day after calibration).")
    }

    /** Returns (estimated uncalibrated $/day of what rests, remaining entry slots). */
    private suspend fun workMarket(
        ctx: StrategyContext, rt: Rt, br: FarmBroker, r: FarmRules, slug: String, cand: FarmCandidate?, pos: FarmPosition?,
        own: List<FarmOrder>, mayOpen: Boolean, budget: Double, now: Instant, books: MutableMap<String, PmBook>, slots0: Int,
    ): Pair<Double, Int> {
        var slots = slots0
        if (cand == null && pos == null && own.isNotEmpty() && !rt.scannedOnce) return 0.0 to slots
        val m = meta(ctx, rt, slug)
        val book = books[slug] ?: ctx.polymarket.book(slug).also { books[slug] = it; if (!br.live) br.onBook(it) }
        val ownBid = own.filter { it.side == "bid" }.groupBy { RewardsMath.r6(it.price) }.mapValues { e -> e.value.sumOf { it.qtyLeft } }
        val ownAsk = own.filter { it.side == "ask" }.groupBy { RewardsMath.r6(it.price) }.mapValues { e -> e.value.sumOf { it.qtyLeft } }
        // A cancel can still show in the public book for a while: count its size as ours too (pmfarm, 45 s).
        val fresh = rt.recentCancels[slug].orEmpty().filter { ctx.clock.now().toEpochMilli() - it.atMs < 45_000 }
        if (fresh.isEmpty()) rt.recentCancels.remove(slug) else rt.recentCancels[slug] = fresh.toMutableList()
        val ownBidAll = ownBid.toMutableMap().also { m -> fresh.filter { it.side == "bid" }.forEach { c -> m.merge(RewardsMath.r6(c.price), c.qty, Double::plus) } }
        val ownAskAll = ownAsk.toMutableMap().also { m -> fresh.filter { it.side == "ask" }.forEach { c -> m.merge(RewardsMath.r6(c.price), c.qty, Double::plus) } }
        val bids = if (br.live) RewardsMath.withoutOwn(book.bids, ownBidAll) else book.bids
        val asks = if (br.live) RewardsMath.withoutOwn(book.asks, ownAskAll) else book.asks
        val posQty = pos?.qty ?: 0.0

        data class Desired(val side: String, val price: Double, val qty: Double, val intent: String, val kind: String)
        val desired = mutableListOf<Desired>()
        val start = listOfNotNull(cand?.eventStart, m.gameStart).minOrNull()
        val pastCutoff = start != null && !(start.isBefore(now.minus(Duration.ofHours(12))) || (start.isBefore(now.minus(Duration.ofHours(1))) && slug in rt.known)) &&
            Duration.between(now, start) < Duration.ofSeconds((r.eventCutoffMin * 60).toLong())
        val cutoff = pastCutoff || !book.open
        if (!cutoff) {
            val posCost = pos?.let { abs(it.qty) * if (it.qty > 0) it.avgPx else 1 - it.avgPx } ?: 0.0
            val quotable = cand?.periods?.any { it.end == null || now.isBefore(it.end.minusSeconds((r.periodEndBufferMin * 60).toLong())) } == true
            val pausedAfterFill = QuoteRefresh.pausedAfterFill(rt.lastFillAt[slug], now, r.fillDelaySec)
            if (cand != null && mayOpen && quotable && posCost < budget && !pausedAfterFill) {
                val plan = FarmScanner.planQuote(cand, m.tick, m.minQty, m.closed, m.active, bids, asks, r, budget - posCost)
                if (plan != null && plan.usdPerDay >= maxOf(r.minPayoutUsd, r.minExpectedDaily * 0.5))
                    desired += Desired(plan.side, plan.price, plan.qty, pmIntentFor(plan.side, posQty, false), "entry")
            }
            if (posQty > 0 && asks.isNotEmpty() && bids.isNotEmpty()) {
                val px = maxOf(asks[0].price, roundPx(pos!!.avgPx, m.tick, true), RewardsMath.r6(bids[0].price + m.tick))
                desired += Desired("ask", px, posQty, pmIntentFor("ask", posQty, true), "exit")
            } else if (posQty < 0 && bids.isNotEmpty() && asks.isNotEmpty()) {
                val px = minOf(bids[0].price, roundPx(pos!!.avgPx, m.tick, false), RewardsMath.r6(asks[0].price - m.tick))
                desired += Desired("bid", px, -posQty, pmIntentFor("bid", posQty, true), "exit")
            }
        }
        val bySide = mutableMapOf<String, Desired>()
        for (d in desired) if (d.side !in bySide || d.kind == "exit") bySide[d.side] = d
        val want = bySide.values.toMutableList()

        val keep = mutableListOf<Desired>(); val resting = mutableListOf<Triple<String, Double, Double>>()
        val stuck = mutableSetOf<String>()
        for (o in own) {
            val tooOld = QuoteRefresh.tooOld(rt.orderSeen[o.id], now, r.maxOrderAgeMin)
            val match = if (tooOld) null else want.firstOrNull { d -> d.side == o.side && d.intent == o.intent &&
                QuoteRefresh.near(o.price, d.price, m.tick, d.kind == "entry", r.requoteToleranceTicks) &&
                abs(d.qty - o.qtyLeft) <= maxOf(m.minQty, 0.2 * d.qty) }
            if (match != null && match !in keep) { keep += match; resting += Triple(o.side, o.price, o.qtyLeft) }
            else if (!br.cancel(slug, o.id)) stuck += o.side   // still live: it shows in open orders next tick, so not a "recent cancel"
            else {
                if (br.live) rt.recentCancels.getOrPut(slug) { mutableListOf() } += RecentCancel(o.price, o.side, o.qtyLeft, ctx.clock.now().toEpochMilli())
                if ("BUY_" in o.intent) slots++
            }
        }
        for (d in want) {
            if (d in keep || d.side in stuck || (stuck.isNotEmpty() && d.kind == "entry")) continue
            if (d.kind == "entry" && slots <= 0) continue
            if (d.kind == "entry" && ctx.gate(slug) != null) continue   // locked: exits still go out
            var expires = now.plusSeconds((r.orderTtlMin * 60).toLong())
            if (start != null && start.isAfter(now)) expires = minOf(expires, start.minusSeconds((r.eventCutoffMin * 60).toLong()))
            if (!expires.isAfter(now.plusSeconds(60))) continue
            val ahead = (if (d.side == "bid") bids else asks).filter { abs(it.price - d.price) < 1e-9 }.sumOf { it.qty }
            val oid = br.place(slug, d.side, d.price, d.qty, d.intent, expires, ahead)
            if (oid == null) { ctx.warn("place", "Order refused: $slug ${d.side} ${"%.3f".format(d.price)} x ${"%.2f".format(d.qty)}"); continue }
            resting += Triple(d.side, d.price, d.qty)
            if (d.kind == "entry") slots--
            ctx.trade(if (br.live) "order" else "paper_quote", "${if (br.live) "" else "Paper: "}${if (d.side == "bid") "bid" else "offered"} ${"%.0f".format(d.qty)} at ${"%.1f".format(d.price * 100)}c on $slug" +
                if (d.kind == "exit") " to close the position" else "",
                mapOf("best_bid" to Sourced(bids.firstOrNull()?.price ?: 0.0, Source.POLYMARKET_API, "GET /v1/markets/$slug/book")))
        }
        var rate = 0.0
        if (cand != null) for ((side, price, qty) in resting) for (p in cand.periods)
            rate += RewardsMath.estimateQuote(bids, asks, side, price, qty, m.tick, p.target, p.discount, p.maxSpread, p.sideRate).usdPerDay
        return rate to slots
    }

    private fun roundPx(price: Double, tick: Double, up: Boolean): Double {
        val n = price / tick
        return RewardsMath.r6((if (up) ceil(n - 1e-9) else floor(n + 1e-9)) * tick)
    }

    private suspend fun settlements(ctx: StrategyContext, rt: Rt, br: FarmBroker, now: Instant) {
        if (br.live || Duration.between(rt.lastSettleCheck, now) < Duration.ofMinutes(30)) return
        rt.lastSettleCheck = now
        for (slug in br.positions().keys.take(40)) {
            val v = runCatching { ctx.polymarket.settlement(slug) }.getOrNull() ?: continue
            val pnl = br.settle(slug, v.value) ?: continue
            ctx.trade("paper_settlement", "Paper $slug settled at ${v.value}: ${money(pnl)}", mapOf("settlement" to v))
        }
    }

    private suspend fun accrue(ctx: StrategyContext, now: Instant, usd: Double, seconds: Double) {
        val day = LocalDate.ofInstant(now, ZoneOffset.UTC).toString()
        val key = k(ctx, "estimates")
        val map = ctx.stores.state.get(key)?.let { runCatching { JSON.decodeFromString(EST, it) }.getOrNull() }?.toMutableMap() ?: mutableMapOf()
        map[day] = (map[day] ?: 0.0) + usd
        ctx.stores.state.put(key, JSON.encodeToString(EST, map.toSortedMap().entries.toList().takeLast(120).associate { it.key to it.value }))
        if (seconds > 3600) ctx.warn("gap", "A ${"%.0f".format(seconds / 60)} min gap between passes: the phone likely slept. Estimated rewards for that gap are not counted as quoting time.")
    }

    private fun inMaintenance(now: Instant): Boolean {
        val et = ZonedDateTime.ofInstant(now, com.marketbot.core.util.ET)
        return et.dayOfWeek.value == 4 && et.hour in 6..7
    }

    override suspend fun onStop(ctx: StrategyContext) {
        if (ctx.mode == Mode.OFF) return
        val rt = rts[mode(ctx)] ?: Rt()
        runCatching { broker(ctx, rt).cancelAll() }
        savePaper(ctx, rt)
        rt.prints?.stop(); rt.prints = null
    }

    suspend fun resetKill(ctx: StrategyContext) { ctx.stores.state.put(k(ctx, "risk"), null) }

    /** The live broker's own record of the orders it placed (the farm keeps no ledger rows for quotes). */
    override suspend fun extraOrderIds(ctx: StrategyContext): Set<String> =
        ctx.stores.state.get("$id.live.owned_orders")?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    override suspend fun liveRequirements(ctx: StrategyContext): List<Requirement> {
        val creds = ctx.polymarket.hasCredentials
        val pre = ctx.stores.state.get("$id.preflight_ok_at")?.let { Instant.parse(it) }
        val preOk = pre != null && Duration.between(pre, ctx.clock.now()) < Duration.ofDays(7)
        val cal = ctx.config.num("reward_calibration")
        return listOf(
            Requirement("creds", "Polymarket API key saved", creds, true, if (creds) "Key loaded." else "Add your key under Accounts."),
            Requirement("preflight", "Preflight passed in the last 7 days", preOk, true, if (preOk) "Last passed $pre." else "Run Preflight."),
            Requirement("calibrated", "Calibration set from real payouts", cal != 1.0, false,
                "The \$/day estimates have never been checked against payouts. The only data point paid \$0.26 against ~\$108/day estimated."),
            Requirement("risk", "Accept: fills can lose more than rewards pay", false, false,
                "Resting bids fill when informed sellers hit them. The kill switch halts at the drawdown you set; it does not prevent losses."),
        )
    }

    override suspend fun preflight(ctx: StrategyContext): PreflightResult {
        val steps = mutableListOf<Pair<String, Boolean>>()
        try {
            val b = ctx.polymarket.balance()
            steps += "Read balance \$${"%.2f".format(b.currentBalance.value)} (${b.currentBalance.origin})" to true
            val pos = ctx.polymarket.positions()
            steps += "Read ${pos.size} position(s)" to true
            steps += "Every position has an exchange cashValue" to pos.all { it.cashValue != null }
            val oo = ctx.polymarket.openOrders()
            steps += "Read ${oo.size} open order(s)" to true
            val earnings = ctx.polymarket.earnings()
            steps += "Read reward earnings (${earnings.size} rows)" to true
            val progs = ctx.polymarket.liquidityPrograms(maxPages = 1)
            steps += "Read liquidity programs (${progs.size})" to progs.isNotEmpty()
            if (ctx.config.bool("short_quotes")) {
                val verdict = noConvention(ctx, progs.mapNotNull { it.str("marketSlug") ?: it.str("slug") })
                ctx.stores.state.put("$id.no_convention", verdict.first)
                steps += "NO-side price convention: ${verdict.second}" to (verdict.first == NoConvention.YES_PRICE)
            }
        } catch (e: Exception) { steps += "Error: ${e.message}" to false }
        val ok = steps.all { it.second }
        if (ok) ctx.stores.state.put("$id.preflight_ok_at", ctx.clock.now().toString())
        ctx.info("preflight", "Preflight ${if (ok) "passed" else "FAILED"}: ${steps.joinToString("; ") { (s, b) -> (if (b) "ok " else "FAIL ") + s }}")
        return PreflightResult(ok, steps, if (ok) "All checks passed." else "Some checks failed.")
    }

    object NoConvention { const val YES_PRICE = "yes_price"; const val NO_PRICE = "no_price"; const val UNVERIFIED = "unverified" }

    /**
     * pmfarm's preflight check, decided rather than printed: preview a 1-contract
     * BUY_SHORT at the YES ask and at 1 - ask. The docs say a short's price is the
     * YES price; that is confirmed when the YES-price preview is accepted and costs
     * about 1 - ask, or when only it is accepted. Anything else stays unverified.
     */
    suspend fun noConvention(ctx: StrategyContext, slugs: List<String>): Pair<String, String> {
        for (slug in slugs.take(10)) {
            val b = runCatching { ctx.polymarket.book(slug) }.getOrNull() ?: continue
            val ask = b.bestAsk ?: continue
            if (b.bestBid == null || ask <= 0.05 || ask >= 0.95) continue
            val yes = runCatching { ctx.polymarket.preview(slug, "ORDER_INTENT_BUY_SHORT", ask, 1.0) }
            val no = runCatching { ctx.polymarket.preview(slug, "ORDER_INTENT_BUY_SHORT", RewardsMath.r6(1 - ask), 1.0) }
            val cost = yes.getOrNull()?.let { previewCost(it) }
            val detail = "$slug ask ${"%.3f".format(ask)}: YES-price preview ${if (yes.isSuccess) "accepted" + (cost?.let { " (cost ${"%.3f".format(it)})" } ?: "") else "rejected"}, " +
                "NO-price preview ${if (no.isSuccess) "accepted" else "rejected"}"
            return when {
                cost != null && kotlin.math.abs(cost - (1 - ask)) <= 0.02 -> NoConvention.YES_PRICE to "$detail. Confirmed: shorts are priced as YES."
                cost != null && kotlin.math.abs(cost - ask) <= 0.02 -> NoConvention.NO_PRICE to "$detail. Shorts are priced as NO: NO-side quoting stays off."
                yes.isSuccess && no.isFailure -> NoConvention.YES_PRICE to "$detail. Only the YES-price form is accepted."
                else -> NoConvention.UNVERIFIED to "$detail. Not conclusive: NO-side quoting stays off live."
            }
        }
        return NoConvention.UNVERIFIED to "no market with a two-sided book between 5c and 95c to test on"
    }

    /** The cost of a previewed order, from the first field that carries one. */
    private fun previewCost(o: kotlinx.serialization.json.JsonObject): Double? {
        val p = o.obj("preview") ?: o.obj("order") ?: o
        for (k in listOf("cost", "totalCost", "estimatedCost", "notional", "cashRequired", "collateral", "buyingPowerRequired"))
            (p.num(k) ?: p.obj(k)?.num("value"))?.let { return it }
        return null
    }

    override suspend fun stats(ctx: StrategyContext): List<Stat> {
        val out = mutableListOf<Stat>()
        if (ctx.mode == Mode.PAPER) {
            val p = rts[Mode.PAPER]?.prints
            out += Stat("Paper fill model", if (p?.connected == true)
                Sourced.derived("trade prints + books", "Polymarket markets websocket (${p.received} prints) and book snapshots",
                    "prints say which book shrinkage was trading and which was cancels, as pmfarm does")
            else Sourced.derived("book snapshots only", "public order books",
                "optimistic: without trade prints a cancel ahead of us looks like a trade, so paper fills more than live would. Add a Polymarket key to get prints."))
        }
        for (m in listOf(Mode.LIVE, Mode.PAPER)) {
            val est = ctx.stores.state.get("$id.${m.name.lowercase()}.estimates")?.let { runCatching { JSON.decodeFromString(EST, it) }.getOrNull() } ?: continue
            out += Stat("${m.name.lowercase().replaceFirstChar { it.uppercase() }} estimated rewards (uncalibrated x calibration)",
                Sourced.derived(money(est.values.sum()), "sum of per-day estimates", "program formula; not money received"))
        }
        if (ctx.polymarket.hasCredentials) runCatching { ctx.polymarket.earnings() }.getOrNull()?.let { rows ->
            val e = earningsSummary(rows)
            out += if (e == null) Stat("Rewards earned (Polymarket)", Sourced.derived("unreadable", "GET /v1/incentives/earnings",
                "a row had no numeric 'reward' field; not shown rather than guessed"))
            else Stat("Rewards earned (Polymarket)", Sourced.polymarket(money(e.first) + e.second, "GET /v1/incentives/earnings: sum of reward over ${rows.size} rows"))
        }
        ctx.stores.state.get("$id.paper.paper_state")?.let { PaperFarmBroker.fromJson(it, 0.0).s }?.let { s ->
            out += Stat("Paper cash", Sourced.paper(money(s.cash), "paper broker"))
            out += Stat("Paper realised fill P&L", Sourced.paper(money(s.realized), "paper broker"))
            out += Stat("Paper maker rebates", Sourced.derived(money(s.rebates), "0.0125 x C x p x (1-p) per fill", "documented maker rebate formula"))
        }
        for (m in listOf(Mode.LIVE, Mode.PAPER)) riskState(ctxFor(ctx, m))?.let {
            out += Stat("${m.name.lowercase()} high-water / halted", Sourced.config("${money(it.highWater)} / ${if (it.halted) "HALTED: ${it.reason}" else "no"}", "risk state"))
        }
        return out
    }

    private fun ctxFor(ctx: StrategyContext, m: Mode) = StrategyContext(ctx.strategyId, m, ctx.config, ctx.stores, ctx.clock, ctx.kalshi,
        ctx.polymarket, ctx.btcIndex, m == Mode.LIVE)

    @kotlinx.serialization.Serializable
    private data class RiskJson(val highWater: Double, val halted: Boolean, val reason: String)

    companion object {
        const val ID = "polymarket_reward_farm"
        /**
         * Total of the `reward` field (the field pmfarm's calibrate reads) and a
         * per-status breakdown, or null if any row lacks a numeric reward: a
         * figure built from guessed fields is worse than no figure.
         */
        fun earningsSummary(rows: List<kotlinx.serialization.json.JsonObject>): Pair<Double, String>? {
            var total = 0.0
            val byStatus = sortedMapOf<String, Double>()
            for (r in rows) {
                val v = r.num("reward") ?: return null
                total += v
                val st = r.str("status") ?: "unknown"
                byStatus[st] = (byStatus[st] ?: 0.0) + v
            }
            val detail = if (byStatus.size > 1 || byStatus.keys.any { it != "unknown" })
                " (" + byStatus.entries.joinToString { "${it.key.lowercase().removePrefix("reward_status_")} ${money(it.value)}" } + ")" else ""
            return total to detail
        }

        private val EST = MapSerializer(String.serializer(), Double.serializer())
    }

}
