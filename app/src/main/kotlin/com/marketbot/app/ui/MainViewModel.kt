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
import com.marketbot.core.strategies.deepitm.DeepItmStrategy
import com.marketbot.core.strategies.rewards.RewardFarm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import com.marketbot.app.data.toRow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        .map { l -> l.map { it.toRow() } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    data class Portfolio(val liveToday: Double = 0.0, val liveTotal: Double = 0.0, val paperToday: Double = 0.0, val paperTotal: Double = 0.0,
                         val liveCurve: Series = emptyList(), val paperCurve: Series = emptyList(), val liveN: Int = 0, val paperN: Int = 0)

    val portfolio: StateFlow<Portfolio> = settled.map { rows ->
        val today = com.marketbot.core.strategy.startOfLocalDay(java.time.Instant.now())
        fun of(m: Mode) = rows.filter { it.mode == m }
        val live = of(Mode.LIVE); val paper = of(Mode.PAPER)
        fun curve(r: List<com.marketbot.core.store.QuoteRow>) = cumulative(r.map { it.resolvedAt!!.toEpochMilli() to it.pnl!! })
        Portfolio(com.marketbot.core.strategy.realisedSince(live, today), live.sumOf { it.pnl!! },
            com.marketbot.core.strategy.realisedSince(paper, today), paper.sumOf { it.pnl!! }, curve(live), curve(paper), live.size, paper.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Portfolio())

    /** 30 days of equity snapshots, per venue and mode; total where the venue reports positions, else cash. */
    val equityCurves: StateFlow<Map<Pair<String, String>, Series>> = a.db.equity().watchSince(System.currentTimeMillis() - 30 * 86_400_000L)
        .map { l -> l.groupBy { it.venue to it.mode }.mapValues { (_, v) -> v.map { it.at to (it.total ?: it.cash) } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun strategyCurve(id: String, mode: Mode): Series = cumulative(settled.value.filter { it.strategyId == id && it.mode == mode }
        .map { it.resolvedAt!!.toEpochMilli() to it.pnl!! })

    fun record(id: String, mode: Mode) = com.marketbot.core.strategy.RecordVsExpected.of(settled.value.filter { it.strategyId == id && it.mode == mode })

    /** Cancel one resting order (swipe on Orders); the engine reads fills first. */
    fun cancelOrder(strategyId: String, rowId: Long) = viewModelScope.launch(Dispatchers.IO) {
        say(runCatching { engine.cancelOne(strategyId, rowId) }.getOrElse { "Cancel failed: ${it.message}" })
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

    fun loadStats(s: Strategy) = viewModelScope.launch(Dispatchers.IO) { val st = stats(s); edit(s.id) { it.copy(stats = st) } }

    /** The go-live checklist; some checks call the exchange, so only on first open or when asked. */
    fun loadGoLive(s: Strategy, force: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        if (!force && _ui.value[s.id]?.reqs != null) return@launch
        val r = requirements(s); val arm = engine.armRecord(s.id)
        edit(s.id) { it.copy(reqs = r, arm = arm) }
    }

    fun runPreflight(s: Strategy) = viewModelScope.launch(Dispatchers.IO) {
        edit(s.id) { it.copy(preflightBusy = true) }
        val p = preflight(s)
        edit(s.id) { it.copy(preflight = p, preflightBusy = false) }
        loadGoLive(s, force = true)
    }

    fun armLive(s: Strategy, acks: Set<String>) = viewModelScope.launch(Dispatchers.IO) {
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

    fun kill(on: Boolean) = viewModelScope.launch(Dispatchers.IO) { engine.setGlobalKill(on); say(if (on) "Kill switch on: orders cancelled." else "Kill switch off.") }

    suspend fun config(s: Strategy): StrategyConfig = withContext(Dispatchers.IO) { engine.config(s) }
    fun saveConfig(s: Strategy, values: Map<String, String>, done: (List<String>) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val errs = s.validate(StrategyConfig(s.params, values))
        if (errs.isEmpty()) { engine.saveConfig(s, values); say("Settings saved.") }
        withContext(Dispatchers.Main) { done(errs) }
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
    fun runNow(s: Strategy) = viewModelScope.launch(Dispatchers.IO) { say("Running ${s.name}..."); engine.runNow(s); say("${s.name}: pass finished.") }
    fun resetKill(s: Strategy) = viewModelScope.launch(Dispatchers.IO) {
        val ctx = engine.context(s, Mode.PAPER, engine.config(s), false, false)
        val live = engine.context(s, Mode.LIVE, engine.config(s), true, false)
        when (s) { is DeepItmStrategy -> { s.resetKill(ctx); s.resetKill(live) }; is RewardFarm -> { s.resetKill(ctx); s.resetKill(live) } }
        say("Stop reset for ${s.name}.")
    }

    fun refreshAccounts() = viewModelScope.launch(Dispatchers.IO) {
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
    fun transferShards(amount: Double, from: Int, to: Int) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { engine.kalshiClient.transferBetweenShards(amount, from, to) }
            .onSuccess { id ->
                a.note(com.marketbot.core.store.LogEvent.Level.TRADE, "transfer", "Moved ${money(amount)} from shard $from to shard $to (transfer ${id ?: "accepted"}).")
                say("Transfer requested. Kalshi processes it in the background; refresh in a minute.")
            }
            .onFailure { say("Transfer failed: ${it.message}") }
        kotlinx.coroutines.delay(3_000); refreshAccounts()
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
            .onFailure { say("Backup failed: ${it.message}") }
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
