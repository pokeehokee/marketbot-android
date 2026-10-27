package com.marketbot.core.engine

import com.marketbot.core.btc.BtcIndex
import com.marketbot.core.kalshi.KalshiClient
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
) {
    private val jobs = mutableMapOf<String, Job>()
    private val lock = Mutex()
    private var scope: CoroutineScope? = null
    private var kalshi: KalshiClient
    private var polymarket: PolymarketClient
    val btcIndex = BtcIndex()

    private val _status = MutableStateFlow<Map<String, StrategyStatus>>(emptyMap())
    val status: StateFlow<Map<String, StrategyStatus>> = _status
    private val _globalKill = MutableStateFlow(false)
    val globalKill: StateFlow<Boolean> = _globalKill

    init {
        val (k, p) = clientFactory(Credentials())
        kalshi = k; polymarket = p
    }

    val kalshiClient get() = kalshi
    val polymarketClient get() = polymarket

    suspend fun setCredentials(c: Credentials) = lock.withLock {
        val (k, p) = clientFactory(c); kalshi = k; polymarket = p
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
        scope.launch {
            _globalKill.value = isKilled()
            for (s in strategies) if (mode(s.id) != Mode.OFF) launchStrategy(s)
            ensureIndex()
        }
    }

    suspend fun stopAll() {
        for (s in strategies) stopStrategy(s, callOnStop = true)
        btcIndex.stop()
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
            for (s in strategies) {
                val m = mode(s.id)
                stopStrategy(s, callOnStop = false)
                runCatching { s.cancelAllResting(context(s, m, config(s), armRecord(s.id) != null, killed = false)) }
                    .onFailure { log(s.id, LogEvent.Level.ERROR, "kill", "Cancel failed: ${it.message}. Check the venue app.") }
                stores.state.put("strategy.${s.id}.armed", null)
                if (m == Mode.LIVE) stores.state.put("strategy.${s.id}.mode", Mode.PAPER.name)
            }
            notifier("Kill switch on", "All resting orders were cancelled and live trading is off.")
        }
        for (s in strategies) if (mode(s.id) != Mode.OFF) launchStrategy(s)
    }

    suspend fun runNow(s: Strategy) {
        val c = ctx(s)
        runPass(s, c)
    }

    suspend fun preflight(s: Strategy): PreflightResult {
        ensureIndex(force = s.needsBtcIndex)
        return s.preflight(ctx(s, Mode.LIVE))
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
                while (isActive) {
                    val c = ctx(s)
                    if (c.mode == Mode.OFF) break
                    runPass(s, c)
                    val d = runCatching { s.nextDelay(c) }.getOrDefault(java.time.Duration.ofMinutes(1))
                    publish(s.id) { it.copy(nextRunAt = clock.now().plus(d)) }
                    delay(d.toMillis().coerceAtLeast(500))
                }
            }
        }
    }

    private suspend fun runPass(s: Strategy, c: StrategyContext) {
        val started = clock.now()
        try {
            val r = withTimeoutOrNull(10 * 60_000L) { s.runOnce(c) }
            publish(s.id) { it.copy(lastRunAt = started, lastSummary = r?.summary ?: "timed out", lastError = if (r == null) "pass timed out after 10 min" else null,
                mode = c.mode, armed = c.liveArmed) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(s.id, LogEvent.Level.ERROR, "error", "Pass failed: ${e.javaClass.simpleName}: ${e.message}")
            publish(s.id) { it.copy(lastRunAt = started, lastError = e.message ?: e.javaClass.simpleName) }
        }
    }

    private suspend fun stopStrategy(s: Strategy, callOnStop: Boolean) {
        val job = lock.withLock { jobs.remove(s.id) }
        job?.cancel()
        job?.join()
        if (callOnStop) withContext(NonCancellable) {
            runCatching { s.onStop(ctx(s)) }.onFailure { log(s.id, LogEvent.Level.ERROR, "stop", "Stop failed: ${it.message}") }
        }
        publish(s.id) { it.copy(running = false, nextRunAt = null) }
    }

    private suspend fun publish(id: String, f: suspend (StrategyStatus) -> StrategyStatus) {
        val cur = _status.value[id] ?: StrategyStatus(id, mode(id), armRecord(id) != null, false, null, null, null, null)
        _status.value = _status.value + (id to f(cur))
    }

    private suspend fun log(id: String?, level: LogEvent.Level, kind: String, msg: String) =
        stores.events.log(LogEvent(clock.now(), id, level, kind, msg))

    companion object {
        private val CFG = MapSerializer(String.serializer(), String.serializer())

        fun defaultClients(c: Credentials): Pair<KalshiClient, PolymarketClient> {
            val ks = if (!c.kalshiKeyId.isNullOrBlank() && !c.kalshiPem.isNullOrBlank()) KalshiSigner(c.kalshiKeyId, c.kalshiPem) else null
            val ps = if (!c.polymarketKeyId.isNullOrBlank() && !c.polymarketSecret.isNullOrBlank()) PolymarketSigner(c.polymarketKeyId, c.polymarketSecret) else null
            return KalshiClient(ks) to PolymarketClient(ps)
        }
    }
}
