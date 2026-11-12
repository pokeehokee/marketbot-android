package com.marketbot.core.engine

import com.marketbot.core.btc.BtcIndex
import com.marketbot.core.kalshi.KALSHI_BASE
import com.marketbot.core.kalshi.KALSHI_DEMO_BASE
import com.marketbot.core.kalshi.KALSHI_DEMO_WS
import com.marketbot.core.kalshi.KALSHI_WS
import com.marketbot.core.kalshi.KalshiClient
import com.marketbot.core.net.NetStats
import com.marketbot.core.net.KalshiSigner
import com.marketbot.core.net.PolymarketSigner
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.store.LogEvent
import com.marketbot.core.store.Stores
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.PreflightResult
import com.marketbot.core.strategy.Requirement
import com.marketbot.core.strategy.Stat
import com.marketbot.core.strategy.Strategy
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.strategy.StrategyRegistry
import com.marketbot.core.util.Clock
import com.marketbot.core.util.JSON
import com.marketbot.core.util.str
import com.marketbot.core.util.num
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.marketbot.core.store.EquitySnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Instant

/** Credentials as the app holds them. Null fields mean "not configured". */
data class Credentials(
    val kalshiKeyId: String? = null,
    val kalshiPem: String? = null,
    val polymarketKeyId: String? = null,
    val polymarketSecret: String? = null,
    /** Trade on Kalshi's demo exchange (mock funds, separate account and keys). */
    val kalshiDemo: Boolean = false,
    /** Kalshi subaccount the bot trades in; 0 is the primary account. */
    val kalshiSubaccount: Int = 0,
    /** RSA-PSS by a key that never leaves the phone's secure hardware; replaces the PEM when set. */
    val kalshiSignBytes: ((ByteArray) -> ByteArray)? = null,
)

data class StrategyStatus(
    val id: String,
    val mode: Mode,
    val armed: Boolean,
    val running: Boolean,
    val lastRunAt: Instant?,
    val lastSummary: String?,
    val nextRunAt: Instant?,
    val lastError: String?,
    /** How the last pass narrowed the markets it looked at down to the orders it placed. */
    val funnel: List<com.marketbot.core.strategy.FunnelStep> = emptyList(),
    /** ACTIVE, REDUCING or HALTED, and why; null while off. */
    val state: com.marketbot.core.strategy.TradingState? = null,
    val reason: com.marketbot.core.strategy.Reason? = null,
)

@Serializable
data class ArmRecord(val at: String, val acknowledged: List<String>)

/**
 * Runs every switched-on strategy on its own schedule. The single place that
 * decides whether real orders may be sent: a strategy is LIVE only when its
 * mode is LIVE, it has been armed through its checklist, and the global kill
 * switch is off.
 */
class Engine(
    val stores: Stores,
    private val clock: Clock = Clock.SYSTEM,
    private val notifier: suspend (String, String) -> Unit = { _, _ -> },
    val strategies: List<Strategy> = StrategyRegistry.all,
    private val clientFactory: (Credentials) -> Pair<KalshiClient, PolymarketClient> = ::defaultClients,
    /** Whether the phone has a usable connection. Offline, passes wait instead of failing over and over. */
    private val online: () -> Boolean = { true },
    /** Fill notices (the "Trades" channel), separate from alerts. */
    private val tradeNotifier: suspend (String, String) -> Unit = notifier,
    /** Notifications with their kind; when set, used instead of [notifier] for the engine's own alerts. */
    private val kindedNotifier: (suspend (kind: String, title: String, body: String) -> Unit)? = null,
    /** Opens Kalshi's private fill channel; null disables it (tests). */
    private val fillStreamFactory: ((KalshiClient, (com.marketbot.core.kalshi.KalshiFill) -> Unit) -> com.marketbot.core.kalshi.KalshiFillStream?)? = ::defaultFillStream,
) {
    private var fills: com.marketbot.core.kalshi.KalshiFillStream? = null
    val fillStreamConnected: Boolean get() = fills?.connected == true
    private val jobs = mutableMapOf<String, Job>()
    private val lock = Mutex()
    /** One pass of a strategy at a time: a scheduled pass, Run now, stop and the kill switch all take this lock. */
    private val passLocks = mutableMapOf<String, Mutex>()
    private fun passLock(id: String) = synchronized(passLocks) { passLocks.getOrPut(id) { Mutex() } }
    /** Poked by the OS alarm (or anything else) to re-check due strategies after the CPU slept. */
    private val pokes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val failures = mutableMapOf<String, Int>()
    private val delays = mutableMapOf<String, java.time.Duration>()
    private var scope: CoroutineScope? = null
    private var kalshi: KalshiClient
    private var polymarket: PolymarketClient
    val btcIndex = BtcIndex()

    private val _status = MutableStateFlow<Map<String, StrategyStatus>>(emptyMap())
    val status: StateFlow<Map<String, StrategyStatus>> = _status
    private val _globalKill = MutableStateFlow(false)
    val globalKill: StateFlow<Boolean> = _globalKill
    private val _busy = MutableStateFlow(0)
    /** Passes in progress right now. A wake lock taken for an alarm is held until this returns to 0. */
    val busy: StateFlow<Int> = _busy
    private val _running = MutableStateFlow(false)
    /** True between [start] and [stopAll]. */
    val running: StateFlow<Boolean> = _running

    /** Wake the strategy loops: they re-check, by wall clock, whether a pass is due. */
    fun poke() { pokes.tryEmit(Unit) }

    private val reschedules = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /**
     * The phone's clock or time zone changed: every waiting strategy recomputes when
     * its next pass is due (a "run at :05 local" schedule moves with the zone) instead
     * of sleeping until a time computed on the old clock. A pass in progress is not
     * interrupted.
     */
    fun reschedule() { reschedules.tryEmit(Unit) }

    /**
     * Whether any running strategy re-runs within [CONTINUOUS_BELOW]: only then must the
     * CPU be held awake. Hourly strategies are woken by an OS alarm at [nextWakeAt] instead.
     */
    fun needsContinuousWake(): Boolean = synchronized(delays) {
        _status.value.values.filter { it.running }.any { (delays[it.id] ?: java.time.Duration.ZERO) < CONTINUOUS_BELOW }
    }

    /** The earliest scheduled pass across running strategies, by wall clock. */
    fun nextWakeAt(): Instant? = _status.value.values.filter { it.running }.mapNotNull { it.nextRunAt }.minOrNull()

    init {
        val (k, p) = clientFactory(Credentials())
        kalshi = k; polymarket = p
        wire()
    }

    /** What every client gets from the engine: the fill limit and the kill-switch order gate. */
    private fun wire() {
        kalshi.fillLimit = fillLimit()
        val gate = { if (_globalKill.value) com.marketbot.core.strategy.Reason(com.marketbot.core.strategy.ReasonCode.KILL_SWITCH) else null }
        kalshi.orderGate = gate; polymarket.orderGate = gate
    }

    /** The app-wide Kalshi fill limit (order groups), from the setting; 0 turns it off. */
    private fun fillLimit() = com.marketbot.core.kalshi.FillLimit(stores.state,
        { stores.state.get(FILL_LIMIT_KEY)?.toIntOrNull() ?: DEFAULT_FILL_LIMIT },
        onTrip = { shard ->
            log(null, LogEvent.Level.ERROR, "fill_limit", "Kalshi's fill limit tripped on shard $shard: more contracts filled within 15 s than allowed. " +
                "Kalshi cancelled the remaining orders there and refuses new ones until you reset it under Settings.")
            notify("safety", "Kalshi fill limit tripped", "A burst of fills on shard $shard. Kalshi cancelled the rest; review, then reset it in Settings.")
        })

    val kalshiFillLimit get() = kalshi.fillLimit

    val kalshiClient get() = kalshi
    val polymarketClient get() = polymarket

    suspend fun setCredentials(c: Credentials) {
        lock.withLock { val (k, p) = clientFactory(c); kalshi = k; polymarket = p; wire() }
        if (_running.value) restartFillStream()
    }

    /** Kalshi's fill channel, while the engine runs with a Kalshi key. Reports only; the ledger reads fills from the order. */
    private fun restartFillStream() {
        fills?.stop(); fills = null
        val sc = scope ?: return
        fills = fillStreamFactory?.invoke(kalshi) { f -> sc.launch { onKalshiFill(f) } }?.also { it.start() }
    }

    private suspend fun onKalshiFill(f: com.marketbot.core.kalshi.KalshiFill) {
        val q = strategies.firstNotNullOfOrNull { s -> stores.ledger.open(s.id, Mode.LIVE).firstOrNull { it.orderId == f.orderId } }
        val what = "${f.action} ${f.side} ${"%.0f".format(f.count)} x ${f.ticker}" + (f.yesPrice?.let { " at YES ${"%.2f".format(it)}" } ?: "")
        log(q?.strategyId, LogEvent.Level.TRADE, "fill", "Filled: $what",
            buildMap {
                put("count", com.marketbot.core.provenance.Sourced.kalshi(f.count, "ws fill#count_fp", clock.now()))
                f.yesPrice?.let { put("yes_price", com.marketbot.core.provenance.Sourced.kalshi(it, "ws fill#yes_price_dollars", clock.now())) }
            })
        tradeNotifier("Filled on Kalshi", what)
        poke()
    }

    /** The engine's clock, for collaborators outside the engine (the Claude sync). */
    fun now(): Instant = clock.now()

    // ------------------------------------------------------------------ persisted settings
    suspend fun mode(id: String): Mode = stores.state.get("strategy.$id.mode")?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.OFF

    suspend fun config(s: Strategy): StrategyConfig {
        val raw = stores.state.get("strategy.${s.id}.config")
        val map = raw?.let { runCatching { JSON.decodeFromString(CFG, it) }.getOrNull() } ?: emptyMap()
        return StrategyConfig(s.params, map)
    }

    suspend fun saveConfig(s: Strategy, values: Map<String, String>) {
        stores.state.put("strategy.${s.id}.config", JSON.encodeToString(CFG, values))
        log(s.id, LogEvent.Level.INFO, "settings", "Settings changed: ${values.entries.joinToString { "${it.key}=${it.value}" }}")
    }

    suspend fun armRecord(id: String): ArmRecord? = stores.state.get("strategy.$id.armed")?.let { runCatching { JSON.decodeFromString(ArmRecord.serializer(), it) }.getOrNull() }

    suspend fun isKilled(): Boolean = stores.state.get("global.kill") == "true"

    val locks = com.marketbot.core.strategy.LockStore(stores.state)

    fun context(s: Strategy, mode: Mode, cfg: StrategyConfig, armed: Boolean, killed: Boolean) = StrategyContext(
        s.id, mode, cfg, stores, clock, kalshi, polymarket, btcIndex,
        liveArmed = mode == Mode.LIVE && armed && !killed, notify = { t, b -> notify("safety", t, b) }, tradeNotify = tradeNotifier,
        gateFn = { ticker ->
            val book = if (mode == Mode.LIVE && armed && !killed) Mode.LIVE else Mode.PAPER
            if (killed && mode == Mode.LIVE) com.marketbot.core.strategy.Reason(com.marketbot.core.strategy.ReasonCode.KILL_SWITCH)
            else locks.denies(s.id, book, ticker, clock.now()) ?: if (book == Mode.LIVE) exposureDenies(ticker) else null
        })

    /**
     * The live book's worst-case loss on [ticker]'s city and day, summed over every strategy, against
     * the cap in Settings. Paper is never capped: it would change what the paper record measures.
     */
    suspend fun exposureDenies(ticker: String): com.marketbot.core.strategy.Reason? {
        val key = com.marketbot.core.strategy.CorrelatedExposure.key(ticker) ?: return null
        val cap = stores.state.get(com.marketbot.core.strategy.CorrelatedExposure.CAP_KEY)?.toDoubleOrNull() ?: com.marketbot.core.strategy.CorrelatedExposure.DEFAULT_CAP
        if (cap <= 0) return null
        val at = liveExposure()[key] ?: 0.0
        return if (at >= cap) com.marketbot.core.strategy.Reason(com.marketbot.core.strategy.ReasonCode.EXPOSURE_CAP,
            "$key has ${com.marketbot.core.strategy.money(at)} at risk across strategies (cap ${com.marketbot.core.strategy.money(cap)})") else null
    }

    /** Worst-case loss per city and day on the live book, every strategy together. */
    suspend fun liveExposure(): Map<String, Double> =
        com.marketbot.core.strategy.CorrelatedExposure.byKey(strategies.flatMap { stores.ledger.open(it.id, Mode.LIVE) })

    /**
     * Notifications by kind ("safety", "reconcile", "fills", "daily"), so the app can
     * route each to its own on / silent / off setting. Falls back to [notifier].
     */
    private suspend fun notify(kind: String, title: String, body: String) = (kindedNotifier ?: { _, t, b -> notifier(t, b) })(kind, title, body)

    private suspend fun ctx(s: Strategy, mode: Mode? = null): StrategyContext {
        val m = mode ?: mode(s.id)
        return context(s, m, config(s), armRecord(s.id) != null, isKilled())
    }

    // ------------------------------------------------------------------ control
    fun start(scope: CoroutineScope) {
        this.scope = scope
        _running.value = true
        scope.launch {
            _globalKill.value = isKilled()
            for (s in strategies) if (mode(s.id) != Mode.OFF) launchStrategy(s)
            ensureIndex()
            restartFillStream()
        }
    }

    /**
     * Stop every strategy. Returns what stays at the venues afterwards: longshot
     * offers deliberately keep resting (their queue position is the edge) until
     * they fill or their exchange-side expiry closes them.
     */
    suspend fun stopAll(): String {
        for (s in strategies) stopStrategy(s, callOnStop = true)
        val resting = strategies.sumOf { s -> stores.ledger.open(s.id, Mode.LIVE).count { it.resting } }
        btcIndex.stop()
        fills?.stop(); fills = null
        _running.value = false
        scope = null
        return if (resting == 0) "Engine stopped. No live orders are resting."
        else "Engine stopped. $resting live offer(s) remain at the exchange until they fill or expire on their own."
    }

    /** Switch a strategy's mode. LIVE needs [arm] first; switching away from LIVE disarms it. */
    suspend fun setMode(s: Strategy, mode: Mode) {
        require(mode in s.supportedModes) { "${s.name} does not support $mode" }
        val prev = mode(s.id)
        if (prev == mode) return
        stopStrategy(s, callOnStop = true)
        if (prev == Mode.LIVE) stores.state.put("strategy.${s.id}.armed", null)
        stores.state.put("strategy.${s.id}.mode", mode.name)
        log(s.id, LogEvent.Level.INFO, "mode", "Mode changed $prev -> $mode")
        if (mode != Mode.OFF) launchStrategy(s)
        ensureIndex()
        publish(s.id) { it.copy(mode = mode, armed = mode == Mode.LIVE && armRecord(s.id) != null) }
    }

    suspend fun requirements(s: Strategy): List<Requirement> = s.liveRequirements(ctx(s, Mode.LIVE))

    /**
     * Arm live trading. Every hard requirement must be met; every soft one must
     * be in [acknowledged]. The record (time and what was acknowledged) is kept
     * and logged.
     */
    suspend fun arm(s: Strategy, acknowledged: Set<String>): Pair<Boolean, String> {
        if (isKilled()) return false to "The kill switch is on. Turn it off first."
        val reqs = requirements(s)
        val missing = reqs.filter { it.hard && !it.met }
        if (missing.isNotEmpty()) return false to "Not met: ${missing.joinToString { it.label }}"
        val unack = reqs.filter { !it.hard && !it.met && it.id !in acknowledged }
        if (unack.isNotEmpty()) return false to "Please acknowledge: ${unack.joinToString { it.label }}"
        val rec = ArmRecord(clock.now().toString(), acknowledged.sorted())
        stores.state.put("strategy.${s.id}.armed", JSON.encodeToString(ArmRecord.serializer(), rec))
        log(s.id, LogEvent.Level.WARN, "armed", "LIVE trading armed. Acknowledged: ${rec.acknowledged.joinToString().ifEmpty { "nothing outstanding" }}")
        stopStrategy(s, callOnStop = false)
        stores.state.put("strategy.${s.id}.mode", Mode.LIVE.name)
        launchStrategy(s)
        ensureIndex()
        return true to "Live trading armed."
    }

    suspend fun disarm(s: Strategy) {
        stores.state.put("strategy.${s.id}.armed", null)
        if (mode(s.id) == Mode.LIVE) setMode(s, Mode.PAPER)
        log(s.id, LogEvent.Level.WARN, "disarmed", "Live trading disarmed; switched to paper.")
    }

    /** Kill switch: cancel every resting order of every strategy, live and paper, and stop live trading. */
    suspend fun setGlobalKill(on: Boolean) {
        stores.state.put("global.kill", on.toString())
        _globalKill.value = on
        log(null, LogEvent.Level.WARN, "kill", if (on) "KILL SWITCH ON: cancelling every resting order." else "Kill switch off.")
        if (on) {
            val wasLive = mutableListOf<String>()
            for (s in strategies) {
                val m = mode(s.id)
                stopStrategy(s, callOnStop = false)
                passLock(s.id).withLock {
                    runCatching { s.cancelAllResting(context(s, m, config(s), armRecord(s.id) != null, killed = false)) }
                        .onFailure { log(s.id, LogEvent.Level.ERROR, "kill", "Cancel failed: ${it.message}. Check the venue app.") }
                }
                stores.state.put("strategy.${s.id}.armed", null)
                if (m == Mode.LIVE) { stores.state.put("strategy.${s.id}.mode", Mode.PAPER.name); wasLive += s.id }
            }
            stores.state.put("global.kill.was_live", wasLive.joinToString(","))
            val sweep = sweepExchanges()
            lastSweep = sweep
            log(null, if (sweep.errors.isEmpty() && sweep.unknown.isEmpty()) LogEvent.Level.WARN else LogEvent.Level.ERROR, "kill", sweep.summary)
            runCatching { reconcileOrders() }
            notify("safety", "Kill switch on", "Live trading is off. ${sweep.summary}")
        } else {
            // The kill switch tripped Kalshi's order groups itself; turning it off re-opens them.
            // A trip caused by a burst of fills stays until reset by hand.
            kalshi.fillLimit?.let { fl -> for ((shard, _) in fl.trippedShards()) if (fl.reason(shard) == "kill") runCatching { fl.reset(kalshi, shard) } }
        }
        for (s in strategies) if (mode(s.id) != Mode.OFF) launchStrategy(s)
    }

    /**
     * Cancel one resting order by hand. Fills are read from the exchange first, so
     * what already filled is recorded, never written off. Runs under the strategy's
     * pass lock so it cannot race a pass that is re-quoting the same market.
     */
    suspend fun cancelOne(strategyId: String, rowId: Long): String = passLock(strategyId).withLock {
        val q = stores.ledger.byId(rowId) ?: return@withLock "That order is gone."
        if (!q.resting) return@withLock "That order is no longer resting."
        val now = clock.now()
        if (q.mode != Mode.LIVE || q.orderId == null) {
            stores.ledger.update(q.copy(cancelledAt = now, filledContracts = q.filledContracts ?: 0.0))
            log(strategyId, LogEvent.Level.INFO, "cancel", "Withdrew a paper order on ${com.marketbot.core.util.Tickers.describe(q.ticker)} by hand.")
            return@withLock "Paper order withdrawn."
        }
        when (q.venue) {
            "kalshi" -> {
                val fs = runCatching { kalshi.order(q.orderId) }.getOrNull()?.let { com.marketbot.core.kalshi.FillState.of(it) }
                    ?: return@withLock "Couldn't read the order's fills from Kalshi, so it was left alone. Try again."
                kalshi.cancelOrder(q.orderId, q.ticker)
                val n = fs.contracts ?: if (fs.filled == true) q.contracts else 0.0
                stores.ledger.update(q.copy(cancelledAt = now, filledContracts = n, fillSource = "Kalshi API ${fs.origin}", fillCheckedAt = now))
            }
            else -> {
                polymarket.cancel(q.orderId, q.ticker)
                stores.ledger.update(q.copy(cancelledAt = now))
            }
        }
        log(strategyId, LogEvent.Level.TRADE, "cancel", "Cancelled an order on ${com.marketbot.core.util.Tickers.describe(q.ticker)} by hand.")
        "Order cancelled at ${if (q.venue == "kalshi") "Kalshi" else "Polymarket"}."
    }

    /**
     * Yesterday in one notification, once a day after 08:00 local: realised P&L per
     * strategy, live and paper apart, from settled positions only. Null when it was
     * already sent today or nothing settled.
     */
    suspend fun dailySummaryIfDue(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Pair<String, String>? {
        val now = clock.now().atZone(zone)
        if (now.hour < 8) return null
        val today = now.toLocalDate().toString()
        if (stores.state.get("engine.summary_day") == today) return null
        stores.state.put("engine.summary_day", today)
        val from = now.toLocalDate().minusDays(1).atStartOfDay(zone).toInstant(); val to = now.toLocalDate().atStartOfDay(zone).toInstant()
        val lines = mutableListOf<String>(); var live = 0.0; var paper = 0.0
        for (s in strategies) for (m in listOf(Mode.LIVE, Mode.PAPER)) {
            val rows = stores.ledger.all(s.id, m).filter { it.resolvedAt != null && it.hypothetical == false && it.pnl != null &&
                !it.resolvedAt.isBefore(from) && it.resolvedAt.isBefore(to) }
            if (rows.isEmpty()) continue
            val p = rows.sumOf { it.pnl!! }
            if (m == Mode.LIVE) live += p else paper += p
            lines += "${s.name} (${m.name.lowercase()}): ${com.marketbot.core.strategy.money(p)} on ${rows.size} settled, ${rows.count { it.pnl!! < 0 }} lost"
        }
        if (lines.isEmpty()) return null
        val title = "Yesterday: " + listOfNotNull(
            "live ${com.marketbot.core.strategy.money(live)}".takeIf { lines.any { "(live)" in it } },
            "paper ${com.marketbot.core.strategy.money(paper)}".takeIf { lines.any { "(paper)" in it } }).joinToString(", ")
        return title to lines.joinToString("\n")
    }

    @Volatile var lastSweep: SweepReport? = null; private set

    /**
     * After each strategy has cancelled what its ledger knows, ask the exchanges what
     * is actually resting and cancel everything in the app's markets: an order whose
     * create response was lost is in no ledger, but it is at the exchange. Orders in
     * markets no strategy trades (placed by hand) are left alone and counted. Kalshi's
     * order groups are triggered too, so Kalshi itself refuses new orders.
     */
    suspend fun sweepExchanges(): SweepReport {
        val known = knownOrderIds()
        fun owned(venue: String, t: String) = strategies.any { it.ownsMarket(venue, t) }
        var cancelled = 0; var outside = 0
        val unknown = mutableListOf<String>(); val errors = mutableListOf<String>()
        if (kalshi.hasCredentials) {
            kalshi.fillLimit?.triggerAll(kalshi)?.forEach { errors += "Kalshi fill-limit trigger, $it" }
            val resting = runCatching { kalshi.orders("resting") }.getOrElse { errors += "Kalshi: could not list resting orders (${it.message})"; emptyList() }
            val mine = resting.mapNotNull { o -> val id = o.str("order_id"); val t = o.str("ticker"); if (id != null && t != null) Triple(id, t, owned("kalshi", t) || id in known) else null }
            outside += mine.count { !it.third }
            val res = kalshi.batchCancel(mine.filter { it.third }.map { it.first to it.second })
            for ((id, t, _) in mine.filter { it.third }) {
                val r = res[id]
                if (r?.isSuccess == true) { cancelled++; if (id !in known) unknown += "Kalshi $t" }
                else errors += "Kalshi $t: ${r?.exceptionOrNull()?.message ?: "not cancelled"}"
            }
        }
        if (polymarket.hasCredentials) {
            val open = runCatching { polymarket.openOrders() }.getOrElse { errors += "Polymarket: could not list open orders (${it.message})"; emptyList() }
            for (o in open) {
                if (!(owned("polymarket", o.slug) || o.id in known)) { outside++; continue }
                runCatching { polymarket.cancel(o.id, o.slug) }
                    .onSuccess { cancelled++; if (o.id !in known) unknown += "Polymarket ${o.slug}" }
                    .onFailure { errors += "Polymarket ${o.slug}: ${it.message}" }
            }
        }
        return SweepReport(cancelled, unknown, outside, errors)
    }

    private suspend fun knownOrderIds(): Set<String> = buildSet {
        for (s in strategies) {
            stores.ledger.all(s.id, Mode.LIVE).forEach { q -> q.orderId?.let { add(it) } }
            addAll(runCatching { s.extraOrderIds(ctx(s, Mode.LIVE)) }.getOrDefault(emptySet()))
        }
    }

    @Volatile var lastOrderRecon: OrderReconciliation? = null; private set
    private var lastOrderReconAt: Instant = Instant.EPOCH

    /**
     * Every few minutes while anything is live: the ledger's resting orders against
     * the exchange's, both ways. An order the ledger thinks is resting but the
     * exchange has finished is read (fills first) and closed in the ledger; an order
     * resting at the exchange in the app's markets that no ledger knows is reported
     * (once per order) and left alone, since another device may own it.
     */
    // ---------------------------------------------------------------- daily API wording check
    @Volatile var lastContract: com.marketbot.core.contract.ApiContract.Report? = null; private set
    private val contractMutex = kotlinx.coroutines.sync.Mutex()
    /** What the daily check runs; replaced in tests, which must not reach the venues. */
    var contractRunner: suspend (KalshiClient, PolymarketClient, Instant) -> com.marketbot.core.contract.ApiContract.Report =
        { k, p, t -> com.marketbot.core.contract.ApiContract.run(k, p, t) }

    /** The last report, from memory or the state store. */
    suspend fun contractReport(): com.marketbot.core.contract.ApiContract.Report? = lastContract
        ?: stores.state.get(CONTRACT_KEY)?.let { runCatching { JSON.decodeFromString(com.marketbot.core.contract.ApiContract.Report.serializer(), it) }.getOrNull() }
            ?.also { lastContract = it }

    /**
     * Once a day: are the venues still answering in the words the app relies on
     * (com.marketbot.core.contract.ApiContract)? Read-only. A finding that is new since the
     * last run is notified once as a safety notice; a run that only skipped (offline) is
     * not recorded, so it does not hide yesterday's result.
     */
    suspend fun contractCheckIfDue(every: java.time.Duration = java.time.Duration.ofHours(24)): com.marketbot.core.contract.ApiContract.Report? {
        val prev = contractReport()
        if (prev != null && java.time.Duration.between(Instant.parse(prev.at), clock.now()) < every) return null
        return contractCheck()
    }

    suspend fun contractCheck(): com.marketbot.core.contract.ApiContract.Report = contractMutex.withLock {
        val prev = contractReport()
        val r = contractRunner(kalshi, polymarket, clock.now())
        if (r.findings.all { it.verdict == com.marketbot.core.contract.ApiContract.Verdict.SKIP }) return@withLock r
        lastContract = r
        stores.state.put(CONTRACT_KEY, JSON.encodeToString(com.marketbot.core.contract.ApiContract.Report.serializer(), r))
        val bad = setOf(com.marketbot.core.contract.ApiContract.Verdict.FAIL, com.marketbot.core.contract.ApiContract.Verdict.WARN)
        fun key(f: com.marketbot.core.contract.ApiContract.Finding) = "${f.area}/${f.check}/${f.verdict}"
        val seen = prev?.findings?.filter { it.verdict in bad }?.map(::key)?.toSet() ?: emptySet()
        val fresh = r.findings.filter { it.verdict in bad && key(it) !in seen }
        if (fresh.isNotEmpty()) {
            val fail = fresh.any { it.verdict == com.marketbot.core.contract.ApiContract.Verdict.FAIL }
            stores.events.log(com.marketbot.core.store.LogEvent(clock.now(), null, if (fail) com.marketbot.core.store.LogEvent.Level.ERROR else com.marketbot.core.store.LogEvent.Level.WARN,
                "api_contract", fresh.joinToString("\n") { it.line }))
            notify("safety", if (fail) "A venue changed something the app relies on" else "A venue answered with something new",
                fresh.joinToString("\n") { "${it.area}: ${it.check}. ${it.detail}" }.take(900))
        }
        r
    }

    private var lastScorecardAt: Instant = Instant.EPOCH

    /** One strategy's scorecard: its record against its evidence, and (in paper) its graduation gates. */
    data class Scorecard(val strategyId: String, val name: String, val mode: Mode,
                         val health: com.marketbot.core.strategy.RecordHealth.Health?,
                         val gates: List<com.marketbot.core.strategy.Check>) {
        val ready: Boolean get() = mode == Mode.PAPER && com.marketbot.core.strategy.Graduation.ready(gates)
        val alarming: Boolean get() = health?.verdict == com.marketbot.core.strategy.RecordHealth.Verdict.LOSING ||
            health?.verdict == com.marketbot.core.strategy.RecordHealth.Verdict.BELOW
    }

    suspend fun scorecard(s: Strategy): Scorecard {
        val mode = mode(s.id)
        val book = if (mode == Mode.LIVE) Mode.LIVE else Mode.PAPER
        val rows = stores.ledger.all(s.id, book)
        val health = com.marketbot.core.strategy.RecordHealth.of(book, rows, s.evidence.expectation)
        val gates = if (book == Mode.PAPER) com.marketbot.core.strategy.Graduation.gates(rows, clock.now()) else emptyList()
        return Scorecard(s.id, s.name, mode, health, gates)
    }

    /**
     * Every few hours: a running strategy whose record has fallen below its evidence (or is
     * losing outright), or a paper strategy that has passed every graduation gate, notifies
     * once per change, never on every pass. Returns the notices sent.
     */
    suspend fun scorecardAlertsIfDue(every: java.time.Duration = java.time.Duration.ofHours(3)): List<String> {
        val now = clock.now()
        if (java.time.Duration.between(lastScorecardAt, now) < every) return emptyList()
        lastScorecardAt = now
        val sent = mutableListOf<String>()
        for (s in strategies) {
            if (mode(s.id) == Mode.OFF) continue
            val c = scorecard(s)
            val key = "scorecard.${s.id}"
            val was = stores.state.get(key)
            val now_ = "${c.health?.verdict ?: "NONE"}|${c.ready}"
            if (was == now_) continue
            stores.state.put(key, now_)
            val wasVerdict = was?.substringBefore('|'); val wasReady = was?.substringAfter('|') == "true"
            if (c.alarming && wasVerdict != c.health!!.verdict.name) {
                val t = "${s.name}: ${c.health.verdict.label.lowercase()} in ${c.health.mode.name.lowercase()}"
                notify("safety", t, c.health.line() + ". Open the strategy to pause it or compare settings.")
                log(s.id, LogEvent.Level.WARN, "scorecard", t + ". " + c.health.line()); sent += t
            }
            if (c.ready && !wasReady) {
                val t = "${s.name} passed every paper gate"
                notify("safety", t, "Four weeks, 100+ fills and its loss ratio say it has earned a look at live money. Review it before going live.")
                log(s.id, LogEvent.Level.INFO, "scorecard", t); sent += t
            }
        }
        return sent
    }

    /** Where the cross-venue research rows go (the app appends them to a file); null records nothing. */
    var bookSink: (suspend (List<com.marketbot.core.research.CrossVenueBooks.Row>) -> Unit)? = null
    /** Where the recorded public trades go. */
    var tradeSink: (suspend (List<com.marketbot.core.research.CrossVenueBooks.Trade>) -> Unit)? = null
    private var lastBooksAt: Instant = Instant.EPOCH

    /** Every [every], unless turned off in Settings: Kalshi and Polymarket temperature books read at one moment. */
    suspend fun recordBooksIfDue(every: java.time.Duration = java.time.Duration.ofMinutes(30)): Int {
        val sink = bookSink ?: return 0
        val now = clock.now()
        if (java.time.Duration.between(lastBooksAt, now) < every) return 0
        if (stores.state.get(BOOKS_SETTING) == "false") return 0
        val since = if (lastBooksAt == Instant.EPOCH) now.minus(every) else lastBooksAt
        lastBooksAt = now
        val rows = com.marketbot.core.research.CrossVenueBooks.read(kalshi, polymarket, now)
        if (rows.isNotEmpty()) sink(rows)
        tradeSink?.let { ts -> val t = com.marketbot.core.research.CrossVenueBooks.trades(kalshi, rows, since, now); if (t.isNotEmpty()) ts(t) }
        return rows.size
    }

    /** Where the perps research rows go; null records nothing. */
    var perpSampleSink: (suspend (List<com.marketbot.core.research.PerpsFunding.Sample>) -> Unit)? = null
    var perpFundingSink: (suspend (List<com.marketbot.core.research.PerpsFunding.Funding>) -> Unit)? = null
    private var lastPerpsAt: Instant = Instant.EPOCH
    /** Read-only: perps market data needs no key, and this client holds no signer, so it cannot trade. */
    private val margin by lazy { com.marketbot.core.margin.MarginClient() }

    /**
     * Every [every], when switched on in Settings: funding, premium and top of book for the busiest
     * perps. Public data, no keys, no orders, and nothing in the app trades perps — this records the
     * series the carry question needs, because the exchange publishes only ~60 funding periods and
     * that is one regime. Finalized periods are appended past a per-ticker watermark, so repeated
     * passes cannot duplicate them.
     */
    suspend fun recordPerpsIfDue(every: java.time.Duration = java.time.Duration.ofMinutes(15)): Int {
        val sink = perpSampleSink ?: return 0
        val now = clock.now()
        if (java.time.Duration.between(lastPerpsAt, now) < every) return 0
        if (stores.state.get(PERPS_SETTING) != "true") return 0      // off unless asked for
        lastPerpsAt = now
        val seen = perpWatermarks()
        val pass = runCatching { com.marketbot.core.research.PerpsFunding.read(margin, now, seen) }
            .onFailure { stores.events.log(LogEvent(now, null, LogEvent.Level.WARN, "perps", "Perps recorder failed: ${it.message}")) }
            .getOrNull() ?: return 0
        if (pass.samples.isNotEmpty()) sink(pass.samples)
        if (pass.funding.isNotEmpty()) {
            perpFundingSink?.invoke(pass.funding)
            savePerpWatermarks(seen + com.marketbot.core.research.PerpsFunding.watermarks(pass.funding))
        }
        return pass.samples.size
    }

    private val perpMapSer = kotlinx.serialization.builtins.MapSerializer(
        kotlinx.serialization.serializer<String>(), kotlinx.serialization.serializer<String>())

    private suspend fun perpWatermarks(): Map<String, Instant> =
        stores.state.get(PERPS_WATERMARKS)?.let { raw ->
            runCatching {
                com.marketbot.core.util.JSON.decodeFromString(perpMapSer, raw).mapValues { Instant.parse(it.value) }
            }.getOrNull()
        } ?: emptyMap()

    private suspend fun savePerpWatermarks(m: Map<String, Instant>) =
        stores.state.put(PERPS_WATERMARKS,
            com.marketbot.core.util.JSON.encodeToString(perpMapSer, m.mapValues { it.value.toString() }))

    private var lastWatchAt: Instant = Instant.EPOCH

    /** The YES mid of a market on either venue, or null when its book is empty or unreadable. */
    suspend fun mid(venue: String, ticker: String): Double? = runCatching {
        if (venue == "kalshi") kalshi.market(ticker).book?.mid
        else polymarket.book(ticker).let { b -> val bid = b.bestBid; val ask = b.bestAsk; if (bid != null && ask != null) (bid + ask) / 2 else null }
    }.getOrNull()

    /** Every [every]: each watched market's mid against its reference; a move past its threshold notifies once and becomes the new reference. */
    suspend fun watchlistIfDue(every: java.time.Duration = java.time.Duration.ofMinutes(5)): List<String> {
        val now = clock.now()
        if (java.time.Duration.between(lastWatchAt, now) < every) return emptyList()
        lastWatchAt = now
        val items = com.marketbot.core.strategy.WatchItem.load(stores.state)
        if (items.isEmpty()) return emptyList()
        val sent = mutableListOf<String>()
        val next = items.map { w ->
            val m = mid(w.venue, w.ticker) ?: return@map w
            val a = w.alert(m)
            when {
                w.ref == null -> w.copy(ref = m)
                a != null -> { notify("watch", "Watchlist", a); sent += a; w.copy(ref = m) }
                else -> w
            }
        }
        if (next != items) com.marketbot.core.strategy.WatchItem.save(stores.state, next)
        return sent
    }

    private var lastBalanceSnapAt: Instant = Instant.EPOCH

    /**
     * The live accounts' value into the equity history every [every], whatever the strategies'
     * modes: the Home chart needs a reading every hour even when nothing trades live (strategies
     * only record live equity while they run live). Kalshi: cash + portfolio_value; Polymarket:
     * currentBalance, the same figures the Home header shows. Returns the venues recorded.
     */
    suspend fun recordLiveBalancesIfDue(every: java.time.Duration = java.time.Duration.ofMinutes(15)): Int {
        val now = clock.now()
        if (java.time.Duration.between(lastBalanceSnapAt, now) < every) return 0
        if (!(kalshi.hasCredentials || polymarket.hasCredentials)) return 0
        lastBalanceSnapAt = now
        return recordLiveBalances()
    }

    suspend fun recordLiveBalances(): Int {
        val now = clock.now()
        var n = 0
        if (kalshi.hasCredentials) runCatching { kalshi.balance() }.getOrNull()?.let { b ->
            stores.equity.record(EquitySnapshot(now, "kalshi", Mode.LIVE, b.cash, b.portfolioValue, b.equity)); n++
        }
        if (polymarket.hasCredentials) runCatching { polymarket.balance() }.getOrNull()?.let { b ->
            stores.equity.record(EquitySnapshot(now, "polymarket", Mode.LIVE, b.currentBalance, null, b.currentBalance)); n++
        }
        return n
    }

    suspend fun reconcileOrdersIfDue(every: java.time.Duration = java.time.Duration.ofMinutes(5)): OrderReconciliation? {
        val now = clock.now()
        if (java.time.Duration.between(lastOrderReconAt, now) < every) return null
        val anyLive = strategies.any { s -> mode(s.id) == Mode.LIVE || stores.ledger.open(s.id, Mode.LIVE).any { it.resting } }
        if (!anyLive || !(kalshi.hasCredentials || polymarket.hasCredentials)) return null
        lastOrderReconAt = now
        return reconcileOrders()
    }

    suspend fun reconcileOrders(): OrderReconciliation {
        val now = clock.now()
        val errors = mutableListOf<String>(); val corrected = mutableListOf<String>(); val unknown = mutableListOf<String>()
        val kRest = if (kalshi.hasCredentials) runCatching { kalshi.orders("resting") }.getOrElse { errors += "Kalshi: ${it.message}"; null } else null
        val pOpen = if (polymarket.hasCredentials) runCatching { polymarket.openOrders() }.getOrElse { errors += "Polymarket: ${it.message}"; null } else null
        val kIds = kRest?.mapNotNull { it.str("order_id") }?.toSet()
        val pIds = pOpen?.map { it.id }?.toSet()
        var checked = 0
        for (s in strategies) {
            val waiting = stores.ledger.open(s.id, Mode.LIVE).filter { it.resting && it.orderId != null && (it.filledContracts ?: 0.0) < it.contracts }
            if (waiting.isEmpty()) continue
            // Under the strategy's pass lock, so a pass re-quoting the same order cannot race the correction.
            withTimeoutOrNull(5_000) {
                passLock(s.id).withLock {
                    for (q0 in waiting) {
                        val q = stores.ledger.byId(q0.id) ?: continue
                        if (!q.resting) continue
                        val atExchange = when (q.venue) { "kalshi" -> kIds; else -> pIds } ?: continue
                        checked++
                        if (q.orderId in atExchange) continue
                        val fix = runCatching { finishedState(q, now) }.getOrElse { errors += "${q.ticker}: ${it.message}"; null } ?: continue
                        stores.ledger.update(fix)
                        corrected += "${com.marketbot.core.util.Tickers.describe(q.ticker)}: ${if (fix.cancelledAt != null) "closed at the exchange" else "fully filled"}"
                    }
                }
            } ?: run { errors += "${s.name}: busy, checked next time" }
        }
        val known = knownOrderIds()
        fun owned(venue: String, t: String) = strategies.any { it.ownsMarket(venue, t) }
        kRest?.forEach { o -> val id = o.str("order_id") ?: return@forEach; val t = o.str("ticker") ?: return@forEach
            if (owned("kalshi", t) && id !in known) unknown += "Kalshi $t ($id)" }
        pOpen?.forEach { o -> if (owned("polymarket", o.slug) && o.id !in known) unknown += "Polymarket ${o.slug} (${o.id})" }
        val r = OrderReconciliation(now.toString(), checked, corrected, unknown, errors)
        lastOrderRecon = r
        stores.state.put("engine.order_recon_last", JSON.encodeToString(OrderReconciliation.serializer(), r))
        if (corrected.isNotEmpty()) log(null, LogEvent.Level.WARN, "reconcile", "Ledger corrected from the exchange: ${corrected.joinToString("; ")}.")
        val seen = stores.state.get("engine.order_recon_unknown_seen")?.split('\n')?.toSet() ?: emptySet()
        val fresh = unknown.filter { it !in seen }
        if (fresh.isNotEmpty()) {
            log(null, LogEvent.Level.ERROR, "reconcile", "Resting at the exchange in this app's markets but in no ledger: ${fresh.joinToString("; ")}. Another device or a lost order response; check before trading live.")
            notify("reconcile", "Unknown orders at the exchange", "${fresh.size} order(s) in this app's markets that the app did not place or lost track of.")
            stores.state.put("engine.order_recon_unknown_seen", (seen + fresh).toList().takeLast(200).joinToString("\n"))
        }
        return r
    }

    /** What the exchange says became of an order the ledger still shows resting. Null when it cannot be read. */
    private suspend fun finishedState(q: com.marketbot.core.store.QuoteRow, now: Instant): com.marketbot.core.store.QuoteRow? = when (q.venue) {
        "kalshi" -> {
            val o = kalshi.order(q.orderId!!)
            if (o == null) q.copy(cancelledAt = now, filledContracts = q.filledContracts ?: 0.0, fillSource = "reconcile: Kalshi no longer has the order", fillCheckedAt = now)
            else {
                val fs = com.marketbot.core.kalshi.FillState.of(o)
                val n = fs.contracts ?: if (fs.filled == true) q.contracts else null
                when {
                    n == null -> null
                    n >= q.contracts -> q.copy(filledContracts = q.contracts, fillSource = "Kalshi API ${fs.origin} (reconcile)", fillCheckedAt = now)
                    else -> q.copy(cancelledAt = now, filledContracts = n, fillSource = "Kalshi API ${fs.origin} (reconcile: ${o.str("status") ?: "closed"})", fillCheckedAt = now)
                }
            }
        }
        else -> {
            val o = polymarket.order(q.orderId!!)
            val n = o?.num("cumQuantity")
            when {
                o == null -> q.copy(cancelledAt = now, filledContracts = q.filledContracts ?: 0.0, fillSource = "reconcile: Polymarket no longer has the order", fillCheckedAt = now)
                n == null -> null
                n >= q.contracts -> q.copy(filledContracts = q.contracts, fillSource = "Polymarket API order#cumQuantity (reconcile)", fillCheckedAt = now)
                else -> q.copy(cancelledAt = now, filledContracts = n, fillSource = "Polymarket API order#cumQuantity (reconcile: ${o.str("state") ?: "closed"})", fillCheckedAt = now)
            }
        }
    }

    private var lastMarkoutAt: Instant = Instant.EPOCH

    /**
     * Once a minute: for orders that filled in the last three hours, note when the
     * fill was first seen, then mark the position at the mid 5, 30 and 60 minutes
     * later. Written under the strategy's pass lock, re-reading the row first, so a
     * pass updating the same row is never overwritten. Returns markouts recorded.
     */
    suspend fun trackMarkoutsIfDue(maxRows: Int = 40): Int {
        val now = clock.now()
        if (java.time.Duration.between(lastMarkoutAt, now).seconds < 60) return 0
        lastMarkoutAt = now
        val mids = HashMap<String, Double?>()
        suspend fun mid(q: com.marketbot.core.store.QuoteRow): Double? = mids.getOrPut(q.venue + ":" + q.ticker) {
            runCatching {
                if (q.venue == "kalshi") kalshi.market(q.ticker).book?.let { b -> (b.yesBid.value + b.yesAsk.value) / 2 }
                else polymarket.book(q.ticker).let { b -> val bid = b.bestBid; val ask = b.bestAsk; if (bid != null && ask != null) (bid + ask) / 2 else null }
            }.getOrNull()
        }
        var recorded = 0; var touched = 0
        for (s in strategies) for (m in listOf(Mode.LIVE, Mode.PAPER)) {
            // Orders can rest for hours before filling: look back far enough to see the fill when it comes.
            val rows = stores.ledger.all(s.id, m, now.minus(java.time.Duration.ofDays(2)))
                .filter { com.marketbot.core.strategy.Outcomes.binary(it) && (it.filledContracts ?: 0.0) > 0 && it.resolvedAt == null }
                .filter { com.marketbot.core.strategy.Markout.seenAt(it) == null || com.marketbot.core.strategy.Markout.due(it, now).isNotEmpty() }
            if (rows.isEmpty()) continue
            withTimeoutOrNull(5_000) {
                passLock(s.id).withLock {
                    for (r in rows) {
                        if (touched >= maxRows) break
                        val q = stores.ledger.byId(r.id) ?: continue
                        var extra = q.extra
                        if (com.marketbot.core.strategy.Markout.seenAt(q) == null)
                            extra = com.marketbot.core.strategy.Markout.with(extra, com.marketbot.core.strategy.Markout.SEEN to kotlinx.serialization.json.JsonPrimitive(now.toString()))
                        for (h in com.marketbot.core.strategy.Markout.due(q.copy(extra = extra), now)) {
                            val v = mid(q) ?: continue
                            extra = com.marketbot.core.strategy.Markout.with(extra, com.marketbot.core.strategy.Markout.key(h) to
                                kotlinx.serialization.json.JsonPrimitive(Math.round(com.marketbot.core.strategy.Markout.cents(q, v) * 100) / 100.0))
                            recorded++
                        }
                        if (extra != q.extra) { stores.ledger.update(q.copy(extra = extra)); touched++ }
                    }
                }
            }
        }
        return recorded
    }

    /** Strategies that were live when the kill switch went on (they stay in paper until re-armed). */
    suspend fun liveBeforeKill(): List<String> = stores.state.get("global.kill.was_live")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

    suspend fun runNow(s: Strategy) {
        val c = ctx(s)
        runPass(s, c)
    }

    suspend fun preflight(s: Strategy): PreflightResult {
        ensureIndex(force = s.needsBtcIndex)
        return passLock(s.id).withLock { s.preflight(ctx(s, Mode.LIVE)) }
    }

    private val lastStats = java.util.concurrent.ConcurrentHashMap<String, List<Stat>>()

    /**
     * A strategy's figures, read under its pass lock so they never observe a pass
     * half-way (strategies keep state that a pass mutates). If a pass holds the lock
     * for more than a few seconds, the last complete reading is shown instead.
     */
    suspend fun stats(s: Strategy): List<Stat> = runCatching {
        kotlinx.coroutines.withTimeoutOrNull(3_000) { passLock(s.id).withLock { s.stats(ctx(s)) } }?.also { lastStats[s.id] = it }
            ?: lastStats[s.id] ?: emptyList()
    }.getOrElse { lastStats[s.id] ?: emptyList() }

    // ------------------------------------------------------------------ internals
    private suspend fun ensureIndex(force: Boolean = false) {
        val need = force || strategies.any { it.needsBtcIndex && mode(it.id) != Mode.OFF }
        val sc = scope ?: return
        if (need && !btcIndex.running) btcIndex.start(sc)
        if (!need && btcIndex.running) btcIndex.stop()
    }

    private suspend fun launchStrategy(s: Strategy) {
        val sc = scope ?: return
        lock.withLock {
            if (jobs[s.id]?.isActive == true) return
            jobs[s.id] = sc.launch {
                publish(s.id) { it.copy(running = true) }
                var offlineLogged = false
                while (isActive) {
                    val c = ctx(s)
                    if (c.mode == Mode.OFF) break
                    if (!online()) {
                        if (!offlineLogged) { log(s.id, LogEvent.Level.WARN, "offline", "No connection: waiting to run until the phone is back online."); offlineLogged = true }
                        publish(s.id) { it.copy(lastSummary = "Offline: waiting for a connection") }
                        waitUntil(clock.now().plusSeconds(15))
                        continue
                    }
                    if (offlineLogged) { log(s.id, LogEvent.Level.INFO, "online", "Connection is back."); offlineLogged = false }
                    val ok = runPass(s, c)
                    var d = runCatching { s.nextDelay(c) }.getOrDefault(java.time.Duration.ofMinutes(1))
                    val n = synchronized(failures) { if (ok) { failures.remove(s.id); 0 } else (failures.merge(s.id, 1, Int::plus) ?: 1) }
                    if (n > 0) d = maxOf(d, backoff(n))
                    synchronized(delays) { delays[s.id] = d }
                    var dueAt = clock.now().plus(d)
                    while (true) {
                        publish(s.id) { it.copy(nextRunAt = dueAt) }
                        if (!waitUntil(dueAt)) break
                        // Rescheduled: the delay is worked out again on the new clock, the failure backoff kept.
                        d = runCatching { s.nextDelay(ctx(s)) }.getOrDefault(d).let { if (n > 0) maxOf(it, backoff(n)) else it }
                        synchronized(delays) { delays[s.id] = d }
                        dueAt = clock.now().plus(d)
                    }
                    noteLateness(s, d, dueAt)
                }
            }
        }
    }

    /**
     * Wait until the wall clock reaches [dueAt]. Waits in short slices and wakes on
     * [poke], because a coroutine delay runs on the monotonic clock, which stops
     * while the phone's CPU sleeps; the OS alarm pokes us when the pass is due.
     */
    private suspend fun waitUntil(dueAt: Instant): Boolean = kotlinx.coroutines.coroutineScope {
        // Listening before the first check, so a reschedule sent while this starts is not missed.
        val moved = kotlinx.coroutines.CompletableDeferred<Unit>()
        val listener = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { reschedules.first(); moved.complete(Unit) }
        try {
            while (true) {
                if (moved.isCompleted) return@coroutineScope true
                val left = java.time.Duration.between(clock.now(), dueAt).toMillis()
                if (left <= 0) return@coroutineScope false
                withTimeoutOrNull(minOf(left, 30_000L).coerceAtLeast(200)) {
                    kotlinx.coroutines.coroutineScope {
                        val poked = async { pokes.first() }
                        val rescheduled = async { moved.await() }
                        kotlinx.coroutines.selects.select<Unit> { poked.onAwait { }; rescheduled.onAwait { } }
                        poked.cancel(); rescheduled.cancel()
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") false
        } finally { listener.cancel() }
    }

    /**
     * A pass that starts well after it was due means the phone stopped the app
     * (deep sleep without an alarm, the maker's battery manager, a force stop).
     * Say so, once per gap, with the length of the gap.
     */
    private suspend fun noteLateness(s: Strategy, delay: java.time.Duration, dueAt: Instant) {
        val late = java.time.Duration.between(dueAt, clock.now())
        val slack = maxOf(java.time.Duration.ofMinutes(2), delay.dividedBy(10))
        if (late > slack) {
            val mins = late.toMinutes()
            log(s.id, LogEvent.Level.WARN, "late", "Ran ${if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"} late: the phone paused the app. Check battery settings for Marketbot.")
            stores.state.put("engine.last_gap", "${clock.now()}|${late.seconds}|${s.id}")
        }
    }

    private fun backoff(failures: Int): java.time.Duration =
        java.time.Duration.ofSeconds(minOf(300L, 5L shl minOf(failures, 6)))

    /** Returns false when the pass failed (an exception or a timeout). */
    private suspend fun runPass(s: Strategy, c: StrategyContext): Boolean = passLock(s.id).withLock {
        val started = clock.now()
        _busy.update { it + 1 }
        try {
            val r = withTimeoutOrNull(10 * 60_000L) { s.runOnce(c) }
            val book = if (c.live) Mode.LIVE else Mode.PAPER
            runCatching { applyProtections(s, book) }
            val reason = stateReason(s, c, book, r?.summary ?: "timed out")
            publish(s.id) { it.copy(lastRunAt = started, lastSummary = r?.summary ?: "timed out", lastError = if (r == null) "pass timed out after 10 min" else null,
                mode = c.mode, armed = c.liveArmed, funnel = r?.funnel?.takeIf { f -> f.isNotEmpty() } ?: it.funnel,
                state = reason?.state ?: com.marketbot.core.strategy.TradingState.ACTIVE, reason = reason) }
            if (c.liveArmed) disarmIfKilled(s, c)
            r != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = com.marketbot.core.strategy.Reasons.ofError(e)
            log(s.id, LogEvent.Level.ERROR, "error", "Pass failed [${reason.code}]: ${e.javaClass.simpleName}: ${e.message}")
            publish(s.id) { it.copy(lastRunAt = started, lastError = e.message ?: e.javaClass.simpleName, state = reason.state, reason = reason) }
            false
        } finally {
            _busy.update { it - 1 }
        }
    }

    /** The state a pass leaves the strategy in: the kill switch, then a lock on the whole strategy, then what the pass said. */
    private suspend fun stateReason(s: Strategy, c: StrategyContext, book: Mode, summary: String): com.marketbot.core.strategy.Reason? {
        if (c.mode == Mode.LIVE && isKilled()) return com.marketbot.core.strategy.Reason(com.marketbot.core.strategy.ReasonCode.KILL_SWITCH)
        locks.strategyLock(s.id, book, clock.now())?.let { l ->
            return com.marketbot.core.strategy.Reason(com.marketbot.core.strategy.ReasonCode.LOCKED, "${l.reason}, until ${l.until.take(16).replace('T', ' ')} UTC") }
        return com.marketbot.core.strategy.Reasons.ofSummary(summary)
    }

    /** Runs the enabled protections on the strategy's settled book; new locks are logged and notified. */
    private suspend fun applyProtections(s: Strategy, book: Mode) {
        val ps = com.marketbot.core.strategy.Protection.load(stores.state).filter { it.enabled }
        if (ps.isEmpty()) return
        val rows = stores.ledger.all(s.id, book)
        val now = clock.now()
        for (p in ps) for (l in p.evaluate(s.id, book, rows, now)) if (locks.add(l, now)) {
            val scope = l.series?.let { "$it markets" } ?: "all new orders"
            log(s.id, LogEvent.Level.WARN, "lock", "${p.type.label} locked $scope (${book.name.lowercase()}) until ${l.until.take(16).replace('T', ' ')} UTC: ${l.reason}.")
            notify("safety", "${s.name}: ${p.type.label}", "Locked $scope until ${l.until.take(16).replace('T', ' ')} UTC: ${l.reason}.")
        }
    }

    private val lastHealth = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    /**
     * At most hourly while a strategy trades live: if one of its kill criteria fires
     * on the live record, live trading is disarmed (it drops to paper) and the user
     * is told which criterion and why. Re-arming goes through the checklist, which
     * shows the same criterion, so it cannot be waved through by accident.
     */
    private suspend fun disarmIfKilled(s: Strategy, c: StrategyContext) {
        val now = clock.now()
        val last = lastHealth[s.id]
        if (last != null && java.time.Duration.between(last, now) < java.time.Duration.ofHours(1)) return
        lastHealth[s.id] = now
        val kill = runCatching { s.healthChecks(c, Mode.LIVE) }.getOrNull()?.firstOrNull { it.verdict == com.marketbot.core.strategy.Check.Verdict.KILL } ?: return
        log(s.id, LogEvent.Level.ERROR, "kill", "Kill criterion fired on the live record: ${kill.name} (${kill.detail}). Live trading disarmed; the strategy continues in paper.")
        notify("safety", "${s.name} disarmed", "Kill criterion: ${kill.name}. ${kill.detail}")
        // As a manual switch to paper does: stop while still live (so live orders are handled as on any stop), then paper.
        // Launched, because this runs inside the pass that the stop waits for.
        scope?.launch {
            stopStrategy(s, callOnStop = true)
            stores.state.put("strategy.${s.id}.armed", null)
            stores.state.put("strategy.${s.id}.mode", Mode.PAPER.name)
            launchStrategy(s)
            publish(s.id) { it.copy(mode = Mode.PAPER, armed = false) }
        }
    }

    /** Health of a strategy's record in [mode], for its screen. */
    suspend fun healthChecks(s: Strategy, mode: Mode): List<com.marketbot.core.strategy.Check> =
        runCatching { s.healthChecks(ctx(s, mode), mode) }.getOrElse { listOf(com.marketbot.core.strategy.Check("Health checks", com.marketbot.core.strategy.Check.Verdict.INSUFFICIENT, "could not be computed: ${it.message}")) }

    /** Compare live Kalshi P&L in the ledger with Kalshi's settlement figures; once a day from the service, or on demand. */
    suspend fun reconcilePnl(): com.marketbot.core.strategy.PnlReconciliation {
        val rows = strategies.flatMap { stores.ledger.all(it.id, Mode.LIVE) }
        val r = com.marketbot.core.strategy.PnlReconcile.kalshi(kalshi, rows, clock.now())
        stores.state.put("engine.recon_last", JSON.encodeToString(com.marketbot.core.strategy.PnlReconciliation.serializer(), r))
        val lvl = if (r.error != null || !r.clean) LogEvent.Level.WARN else LogEvent.Level.INFO
        if (r.compared > 0 || r.error != null || r.notAtExchange.isNotEmpty()) log(null, lvl, "reconcile", r.summary)
        if (!r.clean && r.error == null) notify("reconcile", "P&L differs from Kalshi", r.summary)
        return r
    }

    /** The daily reconciliation, after 09:00 local, when a Kalshi key is set. Returns the result when it ran. */
    suspend fun reconcileIfDue(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): com.marketbot.core.strategy.PnlReconciliation? {
        if (!kalshi.hasCredentials) return null
        val now = clock.now().atZone(zone)
        if (now.hour < 9) return null
        val today = now.toLocalDate().toString()
        if (stores.state.get("engine.recon_day") == today) return null
        stores.state.put("engine.recon_day", today)
        return reconcilePnl()
    }

    suspend fun lastReconciliation(): com.marketbot.core.strategy.PnlReconciliation? = stores.state.get("engine.recon_last")
        ?.let { runCatching { JSON.decodeFromString(com.marketbot.core.strategy.PnlReconciliation.serializer(), it) }.getOrNull() }

    private suspend fun stopStrategy(s: Strategy, callOnStop: Boolean) {
        val job = lock.withLock { jobs.remove(s.id) }
        job?.cancel()
        job?.join()
        if (callOnStop) withContext(NonCancellable) {
            passLock(s.id).withLock {
                runCatching { s.onStop(ctx(s)) }.onFailure { log(s.id, LogEvent.Level.ERROR, "stop", "Stop failed: ${it.message}") }
            }
        }
        publish(s.id) { it.copy(running = false, nextRunAt = null) }
    }

    private suspend fun publish(id: String, f: suspend (StrategyStatus) -> StrategyStatus) {
        val cur = _status.value[id] ?: StrategyStatus(id, mode(id), armRecord(id) != null, false, null, null, null, null)
        _status.value = _status.value + (id to f(cur))
    }

    private suspend fun log(id: String?, level: LogEvent.Level, kind: String, msg: String,
                            values: Map<String, com.marketbot.core.provenance.Sourced<*>> = emptyMap()) =
        stores.events.log(LogEvent(clock.now(), id, level, kind, msg, values))

    companion object {
        const val BOOKS_SETTING = "research.cross_venue_books"
        const val PERPS_SETTING = "research.perps_funding"
        const val PERPS_WATERMARKS = "research.perps_watermarks"
        private val CFG = MapSerializer(String.serializer(), String.serializer())
        const val FILL_LIMIT_KEY = "engine.fill_limit"
        const val CONTRACT_KEY = "engine.api_contract_last"
        /** Contracts per rolling 15 s per Kalshi shard: four 5-lots. Above that, fills are a burst, and bursts are how losers arrive. */
        const val DEFAULT_FILL_LIMIT = 20

        /** Strategies that re-run sooner than this keep the CPU awake; slower ones are woken by an alarm. */
        val CONTINUOUS_BELOW: java.time.Duration = java.time.Duration.ofMinutes(5)

        fun defaultFillStream(k: KalshiClient, onFill: (com.marketbot.core.kalshi.KalshiFill) -> Unit): com.marketbot.core.kalshi.KalshiFillStream? =
            k.signerForStream?.let { s ->
                // Only the two real exchanges have a websocket; any other base (a test server) gets none.
                val url = when (k.base) { KALSHI_BASE -> KALSHI_WS; KALSHI_DEMO_BASE -> KALSHI_DEMO_WS; else -> null }
                url?.let { com.marketbot.core.kalshi.KalshiFillStream(s, it, onFill) }
            }

        fun defaultClients(c: Credentials): Pair<KalshiClient, PolymarketClient> {
            val base = if (c.kalshiDemo) KALSHI_DEMO_BASE else KALSHI_BASE
            val kHost = base.removePrefix("https://").substringBefore('/')
            // Signed timestamps follow the server's clock when the phone's has drifted.
            val kNow = { System.currentTimeMillis() + NetStats.correctionMs(kHost) }
            val pNow = { System.currentTimeMillis() + NetStats.correctionMs("api.polymarket.us") }
            val ks = when {
                c.kalshiKeyId.isNullOrBlank() -> null
                c.kalshiSignBytes != null -> KalshiSigner.external(c.kalshiKeyId, c.kalshiSignBytes, kNow)
                !c.kalshiPem.isNullOrBlank() -> KalshiSigner(c.kalshiKeyId, c.kalshiPem, kNow)
                else -> null
            }
            val ps = if (!c.polymarketKeyId.isNullOrBlank() && !c.polymarketSecret.isNullOrBlank()) PolymarketSigner(c.polymarketKeyId, c.polymarketSecret, pNow) else null
            return KalshiClient(ks, base, subaccount = c.kalshiSubaccount) to PolymarketClient(ps)
        }
    }
}

/** What the kill switch found and cancelled at the exchanges. */
@Serializable
data class SweepReport(val cancelled: Int, val unknown: List<String>, val outside: Int, val errors: List<String>) {
    val summary: String get() = buildString {
        append("Exchanges: $cancelled order(s) cancelled")
        if (unknown.isNotEmpty()) append(", ${unknown.size} of them unknown to the app (${unknown.take(4).joinToString()})")
        if (outside > 0) append("; $outside order(s) in markets the app does not trade were left alone")
        if (errors.isNotEmpty()) append(". NOT cancelled: ${errors.take(4).joinToString("; ")}. Check the venue apps")
        append(".")
    }
}

/** The last comparison of resting orders between the ledger and the exchanges. */
@Serializable
data class OrderReconciliation(val at: String, val checked: Int, val corrected: List<String>, val unknown: List<String>, val errors: List<String>) {
    val clean: Boolean get() = unknown.isEmpty() && errors.isEmpty()
    val summary: String get() = when {
        errors.isNotEmpty() && checked == 0 -> "Could not compare orders: ${errors.first()}"
        else -> "$checked resting order(s) checked" + (if (corrected.isNotEmpty()) ", ${corrected.size} corrected from the exchange" else "") +
            (if (unknown.isNotEmpty()) "; ${unknown.size} at the exchange the app does not know" else "") +
            (if (errors.isNotEmpty()) "; ${errors.size} could not be read" else "") + "."
    }
}
