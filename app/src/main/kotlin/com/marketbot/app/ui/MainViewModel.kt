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
import com.marketbot.core.strategies.deepitm.DeepItmStrategy
import com.marketbot.core.strategies.rewards.RewardFarm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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

    private val _accounts = MutableStateFlow(AccountReading())
    val accounts = _accounts.asStateFlow()
    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asStateFlow()
    fun toastShown() { _toast.value = null }
    private fun say(s: String) { _toast.value = s }

    fun hasKalshi() = a.vault.credentials().let { !it.kalshiKeyId.isNullOrBlank() && !it.kalshiPem.isNullOrBlank() }
    fun hasPolymarket() = a.vault.credentials().let { !it.polymarketKeyId.isNullOrBlank() && !it.polymarketSecret.isNullOrBlank() }
    fun kalshiHint() = a.vault.kalshiHint()
    fun polymarketHint() = a.vault.polymarketHint()

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
        a.vault.saveKalshi(keyId, pem); engine.setCredentials(a.vault.credentials()); say("Kalshi key saved."); refreshAccounts()
    }

    fun savePolymarket(keyId: String, secret: String) = viewModelScope.launch(Dispatchers.IO) {
        val err = runCatching { if (secret.isNotBlank()) com.marketbot.core.net.PolymarketSigner(keyId, secret) }.exceptionOrNull()
        if (err != null) { say(err.message ?: "That key could not be read."); return@launch }
        a.vault.savePolymarket(keyId, secret); engine.setCredentials(a.vault.credentials()); say("Polymarket key saved."); refreshAccounts()
    }

    fun clearKalshi() = viewModelScope.launch(Dispatchers.IO) { a.vault.saveKalshi(null, null); engine.setCredentials(a.vault.credentials()); say("Kalshi key removed.") }
    fun clearPolymarket() = viewModelScope.launch(Dispatchers.IO) { a.vault.savePolymarket(null, null); engine.setCredentials(a.vault.credentials()); say("Polymarket key removed.") }

    fun pruneLogs(days: Int) = viewModelScope.launch(Dispatchers.IO) {
        a.db.events().prune(System.currentTimeMillis() - days * 86_400_000L); say("Activity older than $days days deleted.")
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
