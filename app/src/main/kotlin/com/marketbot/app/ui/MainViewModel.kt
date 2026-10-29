package com.marketbot.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.marketbot.app.EngineService
import com.marketbot.app.MarketbotApp
import com.marketbot.app.data.AuditEntity
import com.marketbot.app.data.EquityEntity
import com.marketbot.app.data.EventEntity
import com.marketbot.core.kalshi.KalshiBalance
import com.marketbot.core.polymarket.PmBalance
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.PreflightResult
import com.marketbot.core.strategy.Requirement
import com.marketbot.core.strategy.Stat
import com.marketbot.core.strategy.Strategy
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.money
import com.marketbot.core.strategy.problems
import com.marketbot.core.strategies.deepitm.DeepItmStrategy
import com.marketbot.core.strategies.rewards.RewardFarm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import com.marketbot.app.data.toRow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keys of actions in progress; [claim] is atomic, so two taps in one frame still run once. */
class InFlight {
    private val _keys = MutableStateFlow<Set<String>>(emptySet())
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()
    fun claim(key: String): Boolean { var won = false; _keys.update { if (key in it) it else { won = true; it + key } }; return won }
    fun release(key: String) = _keys.update { it - key }
}

data class AccountReading(
    val kalshi: KalshiBalance? = null, val kalshiError: String? = null,
    val polymarket: PmBalance? = null, val polymarketError: String? = null,
    val readAt: java.time.Instant? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val a = app as MarketbotApp
    val engine = a.engine
    val strategies: List<Strategy> = engine.strategies

    val status = engine.status
    val killed = engine.globalKill
    val events: StateFlow<List<EventEntity>> = a.db.events().watch(400).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val equity: StateFlow<List<EquityEntity>> = a.db.equity().watchLatest().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val audit: StateFlow<List<AuditEntity>> = a.db.audit().watch().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val modes = a.db.state().watchAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun quotes(id: String) = a.db.quotes().watch(id, 300)

    /** Every settled real position, as ledger rows: the one source for realised P&L, today's figure and the charts. */
    val settled: StateFlow<List<com.marketbot.core.store.QuoteRow>> = a.db.quotes().watchSettled()
        .map { l -> l.map { it.toRow() } }
        .flowOn(Dispatchers.Default)
        .resilient("settled positions")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The last error a database stream hit (it is retried); shown nowhere, kept for diagnostics. */
    @Volatile var lastStreamError: Throwable? = null
        private set

    /**
     * A failing stream would otherwise leave every number on screen frozen at its
     * initial value without a word. Record the error and resubscribe after a pause.
     */
    private fun <T> kotlinx.coroutines.flow.Flow<T>.resilient(what: String) = retryWhen { e, attempt ->
        lastStreamError = e
        android.util.Log.w("marketbot", "$what stream failed (attempt $attempt)", e)
        kotlinx.coroutines.delay(minOf(30_000L, 1_000L shl attempt.toInt().coerceAtMost(5))); true
    }

    data class Portfolio(val liveToday: Double = 0.0, val liveTotal: Double = 0.0, val paperToday: Double = 0.0, val paperTotal: Double = 0.0,
                         val liveCurve: Series = emptyList(), val paperCurve: Series = emptyList(), val liveN: Int = 0, val paperN: Int = 0)

    val portfolio: StateFlow<Portfolio> = settled.map { rows ->
        val today = com.marketbot.core.strategy.startOfLocalDay(java.time.Instant.now())
        fun of(m: Mode) = rows.filter { it.mode == m }
        val live = of(Mode.LIVE); val paper = of(Mode.PAPER)
        fun curve(r: List<com.marketbot.core.store.QuoteRow>) = cumulative(r.map { it.resolvedAt!!.toEpochMilli() to it.pnl!! })
        Portfolio(com.marketbot.core.strategy.realisedSince(live, today), live.sumOf { it.pnl!! },
            com.marketbot.core.strategy.realisedSince(paper, today), paper.sumOf { it.pnl!! }, curve(live), curve(paper), live.size, paper.size)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Portfolio())

    /** 30 days of equity snapshots, per venue and mode; total where the venue reports positions, else cash. */
    val equityCurves: StateFlow<Map<Pair<String, String>, Series>> = a.db.equity().watchSince(System.currentTimeMillis() - 30 * 86_400_000L)
        .map { l -> l.groupBy { it.venue to it.mode }.mapValues { (_, v) -> thin(v.map { it.at to (it.total ?: it.cash) }, 400) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun strategyCurve(id: String, mode: Mode): Series = cumulative(settled.value.filter { it.strategyId == id && it.mode == mode }
        .map { it.resolvedAt!!.toEpochMilli() to it.pnl!! })

    fun record(id: String, mode: Mode) = com.marketbot.core.strategy.RecordVsExpected.of(settled.value.filter { it.strategyId == id && it.mode == mode })

    // ---------------------------------------------------------------- one at a time
    private val actions = InFlight()
    /** Actions running now, by key ("cancel:12", "run:longshot", "kill"). Their buttons are disabled meanwhile. */
    val inFlight = actions.keys

    /**
     * Run [block] unless an action with the same [key] is already running: a second
     * tap on Cancel used to send a second DELETE. Returns null when it was ignored.
     */
    fun once(key: String, block: suspend () -> Unit): kotlinx.coroutines.Job? {
        if (!actions.claim(key)) return null
        return viewModelScope.launch(Dispatchers.IO) { try { block() } finally { actions.release(key) } }
    }

    /** Cancel one resting order (swipe on Orders); the engine reads fills first. */
    fun cancelOrder(strategyId: String, rowId: Long) = once("cancel:$rowId") {
        say(runCatching { engine.cancelOne(strategyId, rowId) }.getOrElse { "Cancel failed. " + plainError(it.message) })
    }

    private val _accounts = MutableStateFlow(AccountReading())
    val accounts = _accounts.asStateFlow()
    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asStateFlow()
    fun toastShown() { _toast.value = null }
    private fun say(s: String) { _toast.value = s }

    /** What keys exist, read once and after each change: decrypting the vault on every redraw caused jank. */
    data class KeyFlags(val kalshi: Boolean, val polymarket: Boolean, val kalshiHint: String?, val polymarketHint: String?, val kalshiInHardware: Boolean)
    private fun readKeys() = a.vault.credentials().let { c ->
        KeyFlags(!c.kalshiKeyId.isNullOrBlank() && (!c.kalshiPem.isNullOrBlank() || c.kalshiSignBytes != null),
            !c.polymarketKeyId.isNullOrBlank() && !c.polymarketSecret.isNullOrBlank(), a.vault.kalshiHint(), a.vault.polymarketHint(), a.vault.kalshiInHardware())
    }
    private val _keys = MutableStateFlow(readKeys())
    val keys = _keys.asStateFlow()
    private fun refreshKeys() { _keys.value = readKeys() }
    fun hasKalshi() = _keys.value.kalshi
    fun kalshiInHardware() = _keys.value.kalshiInHardware
    fun hasPolymarket() = _keys.value.polymarket
    fun kalshiHint() = _keys.value.kalshiHint
    fun polymarketHint() = _keys.value.polymarketHint

    // ---------------------------------------------------------------- per-strategy screen state, kept across rotation
    data class StrategyUi(
        val stats: List<Stat>? = null, val reqs: List<Requirement>? = null, val arm: com.marketbot.core.engine.ArmRecord? = null,
        val preflight: PreflightResult? = null, val preflightBusy: Boolean = false, val message: String? = null,
    )
    private val _ui = MutableStateFlow<Map<String, StrategyUi>>(emptyMap())
    val strategyUi = _ui.asStateFlow()
    private fun edit(id: String, f: (StrategyUi) -> StrategyUi) = _ui.update { m -> m + (id to f(m[id] ?: StrategyUi())) }

    fun loadStats(s: Strategy) = once("stats:${s.id}") { val st = stats(s); edit(s.id) { it.copy(stats = st) } }

    /** The go-live checklist; some checks call the exchange, so only on first open or when asked. */
    fun loadGoLive(s: Strategy, force: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        if (!force && _ui.value[s.id]?.reqs != null) return@launch
        val r = requirements(s); val arm = engine.armRecord(s.id)
        edit(s.id) { it.copy(reqs = r, arm = arm) }
    }

    fun runPreflight(s: Strategy) = once("preflight:${s.id}") {
        edit(s.id) { it.copy(preflightBusy = true) }
        val p = preflight(s)
        edit(s.id) { it.copy(preflight = p, preflightBusy = false) }
        loadGoLive(s, force = true)
    }

    fun armLive(s: Strategy, acks: Set<String>) = once("arm:${s.id}") {
        val (ok, m) = arm(s, acks)
        edit(s.id) { it.copy(message = m) }
        if (ok) loadGoLive(s, force = true)
    }

    fun disarmLive(s: Strategy) = viewModelScope.launch(Dispatchers.IO) { engine.disarm(s); say("Live trading disarmed."); loadGoLive(s, force = true) }

    /** BTC index warm-up: (share of the 5-minute minimum, candle volatility loaded, fresh venues). */
    fun btcWarmup(): Triple<Double, Boolean, Int> = engine.btcIndex.warmup().let { (f, c) -> Triple(f, c, engine.btcIndex.freshVenues()) }

    fun modeOf(id: String): Mode = modes.value.firstOrNull { it.key == "strategy.$id.mode" }?.value?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.OFF

    fun setMode(s: Strategy, m: Mode) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { engine.setMode(s, m) }.onFailure { say(it.message ?: "Could not change mode") }
        if (m != Mode.OFF) ensureService()
    }

    val engineRunning = engine.running
    fun ensureService() { if (!engine.running.value) EngineService.start(getApplication()) }
    fun stopService() = EngineService.stop(getApplication())

    private val _killAsked = MutableStateFlow(false)
    /** Set by the kill-switch shortcut: Home opens its confirmation. */
    val killAsked = _killAsked.asStateFlow()
    fun askKill() { _killAsked.value = true }
    fun killAskHandled() { _killAsked.value = false }

    fun kill(on: Boolean) = once("kill") {
        engine.setGlobalKill(on)
        say(if (on) "Kill switch on. ${engine.lastSweep?.summary ?: ""}".trim() else "Kill switch off.")
    }

    suspend fun config(s: Strategy): StrategyConfig = withContext(Dispatchers.IO) { engine.config(s) }
    fun saveConfig(s: Strategy, values: Map<String, String>, done: (List<String>) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val errs = s.problems(StrategyConfig(s.params, values))
        if (errs.isEmpty()) { engine.saveConfig(s, values); say("Settings saved.") }
        withContext(Dispatchers.Main) { done(errs) }
    }

    /** Unsaved parameter edits per strategy, kept across tab switches and rotation. */
    val drafts = androidx.compose.runtime.mutableStateMapOf<String, Map<String, String>>()
    private val _saved = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val savedConfigs = _saved.asStateFlow()
    fun loadConfig(s: Strategy) = viewModelScope.launch(Dispatchers.IO) { val c = engine.config(s).asMap; _saved.update { it + (s.id to c) } }
    fun saveDraft(s: Strategy, done: (List<String>) -> Unit) {
        val d = drafts[s.id] ?: return
        saveConfig(s, d) { errs -> if (errs.isEmpty()) { drafts.remove(s.id); _saved.update { it + (s.id to d) } }; done(errs) }
    }

    suspend fun stats(s: Strategy): List<Stat> = withContext(Dispatchers.IO) { engine.stats(s) }
    suspend fun requirements(s: Strategy): List<Requirement> = withContext(Dispatchers.IO) { runCatching { engine.requirements(s) }.getOrElse { emptyList() } }
    suspend fun preflight(s: Strategy): PreflightResult = withContext(Dispatchers.IO) {
        runCatching { engine.preflight(s) }.getOrElse { PreflightResult(false, listOf("Error: ${it.message}" to false), it.message ?: "failed") }
    }
    suspend fun arm(s: Strategy, acks: Set<String>): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val r = engine.arm(s, acks); if (r.first) withContext(Dispatchers.Main) { ensureService() }; r
    }
    fun disarm(s: Strategy) = viewModelScope.launch(Dispatchers.IO) { engine.disarm(s); say("Live trading disarmed.") }
    suspend fun armRecord(s: Strategy) = withContext(Dispatchers.IO) { engine.armRecord(s.id) }
    fun runNow(s: Strategy) = once("run:${s.id}") { say("Running ${s.name}..."); engine.runNow(s); say("${s.name}: pass finished.") }
    fun resetKill(s: Strategy) = viewModelScope.launch(Dispatchers.IO) {
        val ctx = engine.context(s, Mode.PAPER, engine.config(s), false, false)
        val live = engine.context(s, Mode.LIVE, engine.config(s), true, false)
        when (s) { is DeepItmStrategy -> { s.resetKill(ctx); s.resetKill(live) }; is RewardFarm -> { s.resetKill(ctx); s.resetKill(live) } }
        say("Stop reset for ${s.name}.")
    }

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    fun refreshAccounts() = viewModelScope.launch(Dispatchers.IO) {
        _refreshing.value = true
        try { readAccounts() } finally { _refreshing.value = false }
    }

    private suspend fun readAccounts() {
        val k = engine.kalshiClient; val p = engine.polymarketClient
        val kb = if (k.hasCredentials) runCatching { k.balance() } else null
        val pb = if (p.hasCredentials) runCatching { p.balance() } else null
        _accounts.value = AccountReading(kb?.getOrNull(), kb?.exceptionOrNull()?.message, pb?.getOrNull(), pb?.exceptionOrNull()?.message, java.time.Instant.now())
        kb?.getOrNull()?.let { engine.stores.audit.record("kalshi.cash", it.cash); it.portfolioValue?.let { v -> engine.stores.audit.record("kalshi.portfolio_value", v) } }
        pb?.getOrNull()?.let { engine.stores.audit.record("polymarket.current_balance", it.currentBalance); engine.stores.audit.record("polymarket.buying_power", it.buyingPower) }
    }

    fun saveKalshi(keyId: String, pem: String) = viewModelScope.launch(Dispatchers.IO) {
        val err = runCatching { if (pem.isNotBlank()) com.marketbot.core.net.KalshiSigner(keyId, pem) }.exceptionOrNull()
        if (err != null) { say(err.message ?: "That key could not be read."); return@launch }
        a.vault.saveKalshi(keyId, pem); engine.setCredentials(a.vault.credentials()); refreshKeys(); say(if (a.vault.kalshiInHardware()) "Kalshi key saved in secure hardware." else "Kalshi key saved."); refreshAccounts()
    }

    fun savePolymarket(keyId: String, secret: String) = viewModelScope.launch(Dispatchers.IO) {
        val err = runCatching { if (secret.isNotBlank()) com.marketbot.core.net.PolymarketSigner(keyId, secret) }.exceptionOrNull()
        if (err != null) { say(err.message ?: "That key could not be read."); return@launch }
        a.vault.savePolymarket(keyId, secret); engine.setCredentials(a.vault.credentials()); refreshKeys(); say("Polymarket key saved."); refreshAccounts()
    }

    fun clearKalshi() = viewModelScope.launch(Dispatchers.IO) { a.vault.saveKalshi(null, null); engine.setCredentials(a.vault.credentials()); refreshKeys(); say("Kalshi key removed.") }
    fun clearPolymarket() = viewModelScope.launch(Dispatchers.IO) { a.vault.savePolymarket(null, null); engine.setCredentials(a.vault.credentials()); refreshKeys(); say("Polymarket key removed.") }

    // ---------------------------------------------------------------- Kalshi account options
    fun kalshiDemo() = a.vault.kalshiDemo()
    fun kalshiSubaccount() = a.vault.kalshiSubaccount()
    private val _demo = MutableStateFlow(a.vault.kalshiDemo())
    val demo = _demo.asStateFlow()

    fun setKalshiDemo(on: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        a.vault.setKalshiDemo(on); _demo.value = on
        engine.setCredentials(a.vault.credentials())
        a.note(com.marketbot.core.store.LogEvent.Level.WARN, "account", if (on) "Kalshi switched to the DEMO exchange (no real money)." else "Kalshi switched to the REAL exchange.")
        say(if (on) "Kalshi demo exchange: orders use fake money. Demo needs its own API key." else "Kalshi real exchange.")
        refreshAccounts()
    }

    fun setKalshiSubaccount(n: Int) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { a.vault.setKalshiSubaccount(n) }.onFailure { say(it.message ?: "Invalid subaccount"); return@launch }
        engine.setCredentials(a.vault.credentials())
        a.note(com.marketbot.core.store.LogEvent.Level.WARN, "account", "Kalshi subaccount set to ${if (n == 0) "primary" else "#$n"}.")
        say(if (n == 0) "Trading the primary Kalshi account." else "Trading Kalshi subaccount #$n."); refreshAccounts()
    }

    /** Move cash between Kalshi exchange shards (e.g. into the crypto shard for BTC deep-ITM). */
    fun transferShards(amount: Double, from: Int, to: Int) = once("transfer") {
        runCatching { engine.kalshiClient.transferBetweenShards(amount, from, to) }
            .onSuccess { id ->
                a.note(com.marketbot.core.store.LogEvent.Level.TRADE, "transfer", "Moved ${money(amount)} from shard $from to shard $to (transfer ${id ?: "accepted"}).")
                say("Transfer requested. Kalshi processes it in the background; refresh in a minute.")
            }
            .onFailure { say("Transfer failed. " + plainError(it.message)) }
        kotlinx.coroutines.delay(3_000); refreshAccounts()
    }

    // ---------------------------------------------------------------- the record: health, outcomes, collateral, reconciliation
    val reconciliations = a.db.checks().watch("pnl_recon", 10).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun reconcileNow() = once("recon") { val r = engine.reconcilePnl(); a.recordCheck(r); say(r.summary) }
    suspend fun health(s: Strategy, mode: Mode) = withContext(Dispatchers.IO) { engine.healthChecks(s, mode) }

    /** The rows a record read-out uses: live when there are live rows, else paper. */
    suspend fun recordRows(s: Strategy): Pair<Mode, List<com.marketbot.core.store.QuoteRow>> = withContext(Dispatchers.IO) {
        val live = engine.stores.ledger.all(s.id, Mode.LIVE)
        if (live.isNotEmpty()) Mode.LIVE to live else Mode.PAPER to engine.stores.ledger.all(s.id, Mode.PAPER)
    }

    // ---------------------------------------------------------------- exposure by venue
    data class Exposure(val venue: String, val resting: Int, val restingLocked: Double, val held: Int, val heldAtRisk: Double, val realisedToday: Double)

    /** Live money tied up per venue, from the ledger (the reward farm's own book is not in it). */
    suspend fun exposure(): List<Exposure> = withContext(Dispatchers.IO) {
        val today = com.marketbot.core.strategy.startOfLocalDay(java.time.Instant.now())
        val rows = strategies.flatMap { engine.stores.ledger.all(it.id, Mode.LIVE) }.filter { com.marketbot.core.strategy.Outcomes.binary(it) }
        rows.groupBy { it.venue }.map { (venue, l) ->
            val open = l.filter { it.resolvedAt == null }
            val resting = open.filter { it.cancelledAt == null && (it.filledContracts ?: 0.0) < it.contracts }
            val held = open.filter { (it.filledContracts ?: 0.0) > 0 }
            Exposure(venue, resting.size, resting.sumOf { (it.contracts - (it.filledContracts ?: 0.0)) * com.marketbot.core.strategy.Outcomes.lockPerContract(it) },
                held.size, held.sumOf { it.filledContracts!! * com.marketbot.core.strategy.Outcomes.lockPerContract(it) },
                com.marketbot.core.strategy.realisedSince(l, today))
        }.sortedBy { it.venue }
    }

    // ---------------------------------------------------------------- reports, locks, protections
    suspend fun periods(mode: Mode, unit: com.marketbot.core.strategy.Periods.Unit, n: Int) = withContext(Dispatchers.Default) {
        val rows = strategies.flatMap { engine.stores.ledger.all(it.id, mode) }
        com.marketbot.core.strategy.Periods.table(rows, unit, n, java.time.ZoneId.systemDefault(), java.time.Instant.now())
    }
    private val _locksChanged = MutableStateFlow(0)
    val locksChanged = _locksChanged.asStateFlow()
    suspend fun activeLocks() = withContext(Dispatchers.IO) { engine.locks.active(java.time.Instant.now()) }

    /** Quick action (freqtrade's /stopentry): no new orders anywhere; fills, exits and settlements carry on. */
    fun pauseAll(hours: Long?) = once("pause") {
        engine.locks.add(com.marketbot.core.strategy.Lock.pauseAll(java.time.Instant.now(), hours), java.time.Instant.now())
        a.note(com.marketbot.core.store.LogEvent.Level.WARN, "pause", "New orders paused for every strategy" + (hours?.let { " for $it h" } ?: " until resumed") + ".")
        _locksChanged.update { it + 1 }; say("New orders paused" + (hours?.let { " for $it h" } ?: "") + ". Resting orders stay.")
    }
    fun resumeAll() = once("pause") {
        engine.locks.remove(com.marketbot.core.strategy.Lock.PAUSE_ID, java.time.Instant.now())
        a.note(com.marketbot.core.store.LogEvent.Level.WARN, "pause", "New orders resumed."); _locksChanged.update { it + 1 }; say("New orders resumed.")
    }
    suspend fun paused(): com.marketbot.core.strategy.Lock? = withContext(Dispatchers.IO) {
        engine.locks.active(java.time.Instant.now()).firstOrNull { it.id == com.marketbot.core.strategy.Lock.PAUSE_ID } }
    /** Quick action: run every switched-on strategy's pass now. */
    fun runAll() = once("run_all") {
        val on = strategies.filter { modeOf(it.id) != Mode.OFF }
        say("Running ${on.size} strateg${if (on.size == 1) "y" else "ies"}...")
        for (s in on) runCatching { engine.runNow(s) }
        say("Passes finished.")
    }
    fun unlock(l: com.marketbot.core.strategy.Lock) = once("unlock:${l.id}") {
        engine.locks.remove(l.id, java.time.Instant.now())
        a.note(com.marketbot.core.store.LogEvent.Level.WARN, "lock", "Lock lifted by hand: ${l.reason} (${l.strategyId}${l.series?.let { ", $it" } ?: ""}).")
        _locksChanged.update { it + 1 }; say("Lock lifted.")
    }
    suspend fun protections() = withContext(Dispatchers.IO) { com.marketbot.core.strategy.Protection.load(engine.stores.state) }
    fun saveProtection(p: com.marketbot.core.strategy.Protection) = once("protection") {
        val all = com.marketbot.core.strategy.Protection.load(engine.stores.state).map { if (it.type == p.type) p else it }
        com.marketbot.core.strategy.Protection.save(engine.stores.state, all)
        a.note(com.marketbot.core.store.LogEvent.Level.WARN, "protection", "${p.type.label} ${if (p.enabled) "on" else "off"}: look-back ${p.lookbackHours} h, " +
            "limit ${p.tradeLimit}, lock ${p.stopHours} h" + (if (p.type == com.marketbot.core.strategy.Protection.Type.MAX_DRAWDOWN) ", drawdown ${money(p.maxDrawdownUsd)}" else "") + ".")
        _locksChanged.update { it + 1 }
    }

    // ---------------------------------------------------------------- paper variants
    /** Strategies for lists: every base strategy, plus any variant that is switched on. */
    fun listed(): List<Strategy> = strategies.filter { it.baseId == null || modeOf(it.id) != Mode.OFF }
    fun variantsOf(baseId: String) = strategies.filter { it.baseId == baseId }
    suspend fun farmPaperRecord(id: String) = withContext(Dispatchers.IO) {
        com.marketbot.core.strategies.rewards.RewardFarm.paperRecord(engine.stores.state, id) }
    fun paperComparison(id: String) = com.marketbot.core.strategy.VariantRecord.of(settled.value.filter { it.strategyId == id })
    fun copyConfig(from: Strategy, to: Strategy) = once("copy_config:${to.id}") {
        val c = engine.config(from).asMap
        engine.saveConfig(to, c); drafts.remove(to.id); _saved.update { it + (to.id to c) }
        say("Settings copied to ${to.name}.")
    }

    // ---------------------------------------------------------------- deposits and withdrawals
    private val _flowsChanged = MutableStateFlow(0)
    val flowsChanged = _flowsChanged.asStateFlow()
    suspend fun cashFlows() = withContext(Dispatchers.IO) { com.marketbot.core.strategy.CashFlows.load(engine.stores.state) }

    /** Equity jumps over 30 days that settled P&L does not explain, per live venue. */
    suspend fun cashFlowCandidates(): List<com.marketbot.core.strategy.CashFlow> = withContext(Dispatchers.IO) {
        val since = java.time.Instant.now().minus(java.time.Duration.ofDays(30))
        val snaps = listOf("kalshi", "polymarket").flatMap { engine.stores.equity.history(it, Mode.LIVE, since) }
        val rows = strategies.flatMap { engine.stores.ledger.all(it.id, Mode.LIVE) }
        val dismissed = engine.stores.state.get(com.marketbot.core.strategy.CashFlows.DISMISSED)?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        com.marketbot.core.strategy.CashFlows.candidates(snaps, rows, com.marketbot.core.strategy.CashFlows.load(engine.stores.state), dismissed)
    }

    fun addCashFlow(f: com.marketbot.core.strategy.CashFlow) = once("cash_flow") {
        val st = engine.stores.state
        com.marketbot.core.strategy.CashFlows.save(st, com.marketbot.core.strategy.CashFlows.load(st) + f.copy(note = f.note.removePrefix("suggested: ").let { if (f.note.startsWith("suggested")) "confirmed: $it" else it }))
        a.note(com.marketbot.core.store.LogEvent.Level.INFO, "cash_flow", "${if (f.amount >= 0) "Deposit" else "Withdrawal"} of ${money(kotlin.math.abs(f.amount))} recorded on ${f.venue}.")
        _flowsChanged.update { it + 1 }; say("Recorded.")
    }
    fun removeCashFlow(f: com.marketbot.core.strategy.CashFlow) = once("cash_flow") {
        val st = engine.stores.state
        com.marketbot.core.strategy.CashFlows.save(st, com.marketbot.core.strategy.CashFlows.load(st) - f)
        _flowsChanged.update { it + 1 }
    }
    fun dismissCashFlow(f: com.marketbot.core.strategy.CashFlow) = once("cash_flow") {
        val st = engine.stores.state
        val d = st.get(com.marketbot.core.strategy.CashFlows.DISMISSED)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        st.put(com.marketbot.core.strategy.CashFlows.DISMISSED, (d + f.at).takeLast(200).joinToString("\n"))
        _flowsChanged.update { it + 1 }
    }

    // ---------------------------------------------------------------- daily API wording check
    private val _contractRuns = MutableStateFlow(0)
    val contractRuns = _contractRuns.asStateFlow()
    suspend fun contractReport() = withContext(Dispatchers.IO) { engine.contractReport() }
    fun runContractCheck() = once("contract") {
        val r = withContext(Dispatchers.IO) { engine.contractCheck() }
        _contractRuns.update { it + 1 }; say(r.summary)
    }

    // ---------------------------------------------------------------- safety at the exchanges
    suspend fun fillLimit(): Int = withContext(Dispatchers.IO) {
        engine.stores.state.get(com.marketbot.core.engine.Engine.FILL_LIMIT_KEY)?.toIntOrNull() ?: com.marketbot.core.engine.Engine.DEFAULT_FILL_LIMIT }
    fun setFillLimit(n: Int) = once("fill_limit") {
        engine.stores.state.put(com.marketbot.core.engine.Engine.FILL_LIMIT_KEY, n.coerceIn(0, 1000).toString())
        a.note(com.marketbot.core.store.LogEvent.Level.WARN, "fill_limit", if (n <= 0) "Kalshi fill limit turned off." else "Kalshi fill limit set to $n contracts per 15 s per shard.")
        say(if (n <= 0) "Fill limit off." else "Fill limit: $n contracts per 15 s.")
    }
    suspend fun fillLimitState(): Pair<Map<Int, java.time.Instant>, String?> = withContext(Dispatchers.IO) {
        val fl = engine.kalshiFillLimit; (fl?.trippedShards() ?: emptyMap()) to fl?.lastError }
    fun resetFillLimit(shard: Int) = once("fill_reset:$shard") {
        val fl = engine.kalshiFillLimit ?: return@once
        runCatching { fl.reset(engine.kalshiClient, shard) }
            .onSuccess { a.note(com.marketbot.core.store.LogEvent.Level.WARN, "fill_limit", "Kalshi fill limit on shard $shard reset by hand."); say("Fill limit reset: orders allowed again.") }
            .onFailure { say("Reset failed. " + plainError(it.message)) }
    }
    suspend fun lastOrderRecon(): com.marketbot.core.engine.OrderReconciliation? = withContext(Dispatchers.IO) {
        engine.lastOrderRecon ?: engine.stores.state.get("engine.order_recon_last")?.let {
            runCatching { com.marketbot.core.util.JSON.decodeFromString(com.marketbot.core.engine.OrderReconciliation.serializer(), it) }.getOrNull() } }
    private val _orderRecon = MutableStateFlow(0)
    /** Bumps after a manual check so the card re-reads. */
    val orderReconRuns = _orderRecon.asStateFlow()
    fun reconcileOrdersNow() = once("order_recon") { val r = engine.reconcileOrders(); _orderRecon.update { it + 1 }; say(r.summary) }
    fun heartbeatUrl() = a.vault.heartbeatUrl()
    fun saveHeartbeatUrl(u: String) {
        val v = u.trim().ifEmpty { null }
        if (v != null && !com.marketbot.app.Heartbeat.valid(v)) { say("Use the full https:// address of the check."); return }
        a.vault.saveHeartbeatUrl(v); say(if (v == null) "Outside heartbeat off." else "Outside heartbeat saved.")
    }
    fun testHeartbeat() = once("heartbeat") {
        val u = a.vault.heartbeatUrl() ?: run { say("Save a check address first."); return@once }
        say(if (com.marketbot.app.Heartbeat.ping(u)) "Ping delivered: ${com.marketbot.app.Heartbeat.lastResult}" else "Ping failed: ${com.marketbot.app.Heartbeat.lastResult}")
    }

    // ---------------------------------------------------------------- health and system
    /** Server minus phone time, worst across hosts. Signing corrects for it, but past a few seconds the phone's clock is wrong. */
    fun clockSkewMs(): Long? = com.marketbot.core.net.NetStats.worstSkewMs()
    fun netUsage() = com.marketbot.core.net.NetStats.today()
    suspend fun liveBeforeKill(): List<String> = withContext(Dispatchers.IO) {
        engine.liveBeforeKill().mapNotNull { id -> strategies.firstOrNull { it.id == id }?.name }
    }
    fun wifiOnly() = com.marketbot.app.Connectivity.wifiOnly(getApplication())
    fun setWifiOnly(on: Boolean) { com.marketbot.app.Connectivity.setWifiOnly(getApplication(), on); say(if (on) "Strategies wait for Wi-Fi." else "Strategies run on any connection.") }
    fun lastCrash() = com.marketbot.app.Health.lastCrash(getApplication())
    fun clearCrash() = com.marketbot.app.Health.clearCrash(getApplication())

    // ---------------------------------------------------------------- updates
    private val _update = MutableStateFlow<String?>(null)
    val update = _update.asStateFlow()
    private val _updateUrl = MutableStateFlow<String?>(null)
    val updateUrl = _updateUrl.asStateFlow()
    fun hasGithubToken() = !a.vault.githubToken().isNullOrBlank()
    fun saveGithubToken(t: String) { a.vault.saveGithubToken(t.ifBlank { null }); say(if (t.isBlank()) "Token removed." else "Token saved.") }

    fun checkUpdates() = viewModelScope.launch(Dispatchers.IO) {
        _update.value = "Checking..."
        com.marketbot.app.Updates.check(a.vault.githubToken())
            .onSuccess { r ->
                val mine = com.marketbot.app.BuildConfig.VERSION_CODE
                when {
                    r == null -> { _update.value = "No builds published yet."; _updateUrl.value = null }
                    r.build > mine -> { _update.value = "Build ${r.build} is available (this is $mine)."; _updateUrl.value = r.apkUrl ?: r.pageUrl }
                    else -> { _update.value = "Up to date (build $mine)."; _updateUrl.value = null }
                }
            }
            .onFailure { _update.value = it.message; _updateUrl.value = null }
    }

    // ---------------------------------------------------------------- backup
    fun backup(uri: android.net.Uri, password: CharArray) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val blob = com.marketbot.app.data.Backup.export(a.db, com.marketbot.app.BuildConfig.VERSION_NAME, password)
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(blob) }
            blob.size
        }.onSuccess { say("Backup saved (${it / 1024} KB). Keep the password: it cannot be recovered.") }
            .onFailure { say("Backup failed. " + plainError(it.message)) }
        password.fill(' ')
    }

    fun restore(uri: android.net.Uri, password: CharArray) = viewModelScope.launch(Dispatchers.IO) {
        if (engine.running.value) { say("Stop the engine before restoring."); return@launch }
        runCatching {
            val blob = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            com.marketbot.app.data.Backup.restore(a.db, blob, password)
        }.onSuccess { c ->
            a.note(com.marketbot.core.store.LogEvent.Level.WARN, "restore", "Restored a backup from ${java.time.Instant.ofEpochMilli(c.createdAt)}: ${c.quotes.size} orders. Every strategy is in paper; API keys must be entered again.")
            say("Restored ${c.quotes.size} orders. Strategies are in paper; re-enter keys under Accounts.")
        }.onFailure { say(it.message ?: "Restore failed") }
        password.fill(' ')
    }

    fun pruneLogs(days: Int) = viewModelScope.launch(Dispatchers.IO) {
        a.db.events().prune(System.currentTimeMillis() - days * 86_400_000L); say("Activity older than $days days deleted.")
    }

    /** The API recording as a file for the share sheet. */
    suspend fun apiRecordingUri(): android.net.Uri = withContext(Dispatchers.IO) {
        val dir = java.io.File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        val f = java.io.File(dir, "marketbot-api-${java.time.LocalDateTime.now().withNano(0).toString().replace(':', '-')}.jsonl")
        f.writeText(com.marketbot.core.net.ApiJournal.export())
        androidx.core.content.FileProvider.getUriForFile(getApplication(), "${getApplication<Application>().packageName}.files", f)
    }

    /** The CSV written to app cache and shared as a file (a long ledger does not fit in an Intent extra). */
    suspend fun exportCsvUri(): android.net.Uri = withContext(Dispatchers.IO) {
        val dir = java.io.File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        val f = java.io.File(dir, "marketbot-orders-${java.time.LocalDate.now()}.csv")
        f.writeText(exportCsv())
        androidx.core.content.FileProvider.getUriForFile(getApplication(), "${getApplication<Application>().packageName}.files", f)
    }

    /** CSV of every ledger row with its sources, for the share sheet. */
    suspend fun exportCsv(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder("strategy,mode,venue,ticker,action,price,contracts,placed_at,order_id,filled,fill_source,cancelled_at,resolved_at,result,pnl,pnl_source,pnl_origin,hypothetical\n")
        for (s in strategies) for (q in engine.stores.ledger.all(s.id)) {
            fun c(x: Any?) = "\"" + (x?.toString() ?: "").replace("\"", "\"\"") + "\""
            sb.append(listOf(q.strategyId, q.mode, q.venue, q.ticker, q.action, q.price, q.contracts, q.placedAt, q.orderId, q.filledContracts,
                q.fillSource, q.cancelledAt, q.resolvedAt, q.result, q.pnl, q.pnlSource, q.pnlOrigin, q.hypothetical).joinToString(",") { c(it) }).append('\n')
        }
        sb.toString()
    }

}
