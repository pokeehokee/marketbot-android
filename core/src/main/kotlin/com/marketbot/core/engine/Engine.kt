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
    }

    val kalshiClient get() = kalshi
    val polymarketClient get() = polymarket

    suspend fun setCredentials(c: Credentials) {
        lock.withLock { val (k, p) = clientFactory(c); kalshi = k; polymarket = p }
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

    fun context(s: Strategy, mode: Mode, cfg: StrategyConfig, armed: Boolean, killed: Boolean) = StrategyContext(
        s.id, mode, cfg, stores, clock, kalshi, polymarket, btcIndex,
        liveArmed = mode == Mode.LIVE && armed && !killed, notify = notifier)

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
            notifier("Kill switch on", "All resting orders were cancelled and live trading is off.")
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

    suspend fun stats(s: Strategy): List<Stat> = runCatching { s.stats(ctx(s)) }.getOrElse { emptyList() }

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
                    val dueAt = clock.now().plus(d)
                    publish(s.id) { it.copy(nextRunAt = dueAt) }
                    waitUntil(dueAt)
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
    private suspend fun waitUntil(dueAt: Instant) {
        while (true) {
            val left = java.time.Duration.between(clock.now(), dueAt).toMillis()
            if (left <= 0) return
            withTimeoutOrNull(minOf(left, 30_000L).coerceAtLeast(200)) { pokes.first() }
        }
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
            publish(s.id) { it.copy(lastRunAt = started, lastSummary = r?.summary ?: "timed out", lastError = if (r == null) "pass timed out after 10 min" else null,
                mode = c.mode, armed = c.liveArmed) }
            r != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(s.id, LogEvent.Level.ERROR, "error", "Pass failed: ${e.javaClass.simpleName}: ${e.message}")
            publish(s.id) { it.copy(lastRunAt = started, lastError = e.message ?: e.javaClass.simpleName) }
            false
        } finally {
            _busy.update { it - 1 }
        }
    }

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
        private val CFG = MapSerializer(String.serializer(), String.serializer())
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
