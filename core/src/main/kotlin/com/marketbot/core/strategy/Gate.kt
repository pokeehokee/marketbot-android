package com.marketbot.core.strategy

import com.marketbot.core.store.QuoteRow
import com.marketbot.core.store.StateStore
import com.marketbot.core.util.JSON
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.Duration
import java.time.Instant

/**
 * One trading state per strategy, after NautilusTrader's risk engine: ACTIVE trades
 * normally; REDUCING manages what it has (fills, settlements, cancels) but opens
 * nothing new; HALTED does nothing until a person acts.
 */
enum class TradingState { ACTIVE, REDUCING, HALTED }

/** Why a strategy is not trading normally. Every block has one code, so screens and alerts read it the same way. */
enum class ReasonCode(val state: TradingState, val label: String) {
    // ---- halted: needs a person
    KILL_SWITCH(TradingState.HALTED, "Kill switch on"),
    TOTAL_KILL(TradingState.HALTED, "Total loss limit reached"),
    KILL_CRITERION(TradingState.HALTED, "Kill criterion fired"),
    FEE_TYPE_CHANGED(TradingState.HALTED, "Maker fee appeared"),
    FILL_LIMIT_TRIPPED(TradingState.HALTED, "Kalshi fill limit tripped"),
    CONFIG_INVALID(TradingState.HALTED, "Settings need fixing"),
    NOT_ARMED(TradingState.HALTED, "Live not armed"),
    // ---- reducing: keeps managing, opens nothing new
    BRAKE_DAILY(TradingState.REDUCING, "Daily loss brake"),
    LOCKED(TradingState.REDUCING, "Locked by a protection"),
    SETTLEMENTS_STALE(TradingState.REDUCING, "Settlements not read recently"),
    INDEX_STALE(TradingState.REDUCING, "BTC price stale"),
    TOO_FEW_VENUES(TradingState.REDUCING, "Too few price sources"),
    WARMING_UP(TradingState.REDUCING, "Warming up"),
    EXCHANGE_CLOSED(TradingState.REDUCING, "Exchange closed or paused"),
    MAINTENANCE(TradingState.REDUCING, "Exchange maintenance"),
    BUDGET_SPENT(TradingState.REDUCING, "Budget spent"),
    EXCHANGE_STATE_UNAVAILABLE(TradingState.REDUCING, "Exchange state unavailable"),
    // ---- active but degraded: the last pass failed; the next one tries again
    LISTING_FAILED(TradingState.ACTIVE, "Market list unavailable"),
    RATE_LIMITED(TradingState.ACTIVE, "Rate limited by the exchange"),
    EXCHANGE_ERROR(TradingState.ACTIVE, "Exchange error"),
    REJECTED(TradingState.ACTIVE, "Request refused by the exchange"),
    NETWORK(TradingState.ACTIVE, "Network problem"),
    TIMEOUT(TradingState.ACTIVE, "Pass timed out"),
    OFFLINE(TradingState.ACTIVE, "No connection"),
    ERROR(TradingState.ACTIVE, "Pass failed"),
}

@Serializable
data class Reason(val code: ReasonCode, val detail: String = "") {
    val state: TradingState get() = code.state
    val text: String get() = if (detail.isBlank()) code.label else "${code.label}: $detail"
}

object Reasons {
    /** The reason a pass's own summary gives, for the summaries the strategies return when they hold back. */
    fun ofSummary(summary: String?): Reason? {
        val s = summary?.trim()?.lowercase() ?: return null
        val code = when {
            s == "live not armed" -> ReasonCode.NOT_ARMED
            s == "invalid settings" -> ReasonCode.CONFIG_INVALID
            s == "settlements stale" -> ReasonCode.SETTLEMENTS_STALE
            s == "kill criterion" -> ReasonCode.KILL_CRITERION
            s == "fee type changed" -> ReasonCode.FEE_TYPE_CHANGED
            s == "brake" || s == "daily_stop" -> ReasonCode.BRAKE_DAILY
            s.startsWith("budget spent") -> ReasonCode.BUDGET_SPENT
            s == "killed" || s == "total_kill" || s == "halted" -> ReasonCode.TOTAL_KILL
            s == "stale_index" -> ReasonCode.INDEX_STALE
            s == "too_few_venues" -> ReasonCode.TOO_FEW_VENUES
            s == "warming_up" -> ReasonCode.WARMING_UP
            s == "exchange_closed" -> ReasonCode.EXCHANGE_CLOSED
            s == "maintenance" -> ReasonCode.MAINTENANCE
            s == "paused" -> ReasonCode.EXCHANGE_STATE_UNAVAILABLE
            s == "listing_failed" || s == "listing_retry" -> ReasonCode.LISTING_FAILED
            s == "fill limit" -> ReasonCode.FILL_LIMIT_TRIPPED
            s == "timed out" -> ReasonCode.TIMEOUT
            else -> return null
        }
        return Reason(code)
    }

    /** The reason a pass that threw gives, by what kind of failure it was. */
    fun ofError(e: Throwable): Reason = when (e) {
        is com.marketbot.core.kalshi.FillLimitTripped -> Reason(ReasonCode.FILL_LIMIT_TRIPPED, "shard ${e.shard}")
        is com.marketbot.core.net.ApiException -> when {
            e.status == 429 -> Reason(ReasonCode.RATE_LIMITED, e.message ?: "")
            e.status >= 500 -> Reason(ReasonCode.EXCHANGE_ERROR, "HTTP ${e.status}")
            else -> Reason(ReasonCode.REJECTED, e.code ?: "HTTP ${e.status}")
        }
        is java.net.SocketTimeoutException -> Reason(ReasonCode.TIMEOUT, e.message ?: "")
        is java.io.IOException -> Reason(ReasonCode.NETWORK, e.message ?: e.javaClass.simpleName)
        else -> Reason(ReasonCode.ERROR, e.message ?: e.javaClass.simpleName)
    }
}

/** An order the engine refused before it reached the exchange. */
class OrderDenied(val reason: Reason) : java.io.IOException("Order denied: ${reason.text}")

// =================================================================== locks and protections

/**
 * A time-limited lock, after freqtrade's protections: no new orders for a strategy
 * (or one market series of it) until [until]. It lifts by itself; a person can lift
 * it sooner.
 */
@Serializable
data class Lock(val id: String, val strategyId: String, val mode: Mode, val series: String?, val until: String, val reason: String,
                /** A lock on every strategy ([strategyId] "*") in both modes: the "pause new orders" quick action. */
                val allModes: Boolean = false) {
    val untilInstant: Instant get() = Instant.parse(until)
    fun covers(sid: String, m: Mode, ticker: String) = (strategyId == ALL || sid == strategyId) && (allModes || m == mode) &&
        (series == null || seriesOf(ticker) == series)

    companion object {
        const val ALL = "*"
        const val PAUSE_ID = "pause:*"
        /** Pause every strategy's new orders for [hours] (null = until resumed). */
        fun pauseAll(now: Instant, hours: Long?) = Lock(PAUSE_ID, ALL, Mode.LIVE, null,
            (if (hours == null) now.plus(Duration.ofDays(3650)) else now.plus(Duration.ofHours(hours))).toString(), "paused by you", allModes = true)
    }
}

/** The market family a ticker belongs to: a Kalshi series, or the market itself on Polymarket. */
fun seriesOf(ticker: String): String = when {
    ticker.startsWith("KX") -> ticker.substringBefore('-')
    ticker.startsWith("btc-above-") -> "btc-above"
    else -> ticker
}

class LockStore(private val state: StateStore) {
    private val ser = ListSerializer(Lock.serializer())
    suspend fun all(): List<Lock> = state.get(KEY)?.let { runCatching { JSON.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()
    suspend fun active(now: Instant): List<Lock> = all().filter { it.untilInstant.isAfter(now) }
    private suspend fun save(l: List<Lock>) = state.put(KEY, JSON.encodeToString(ser, l))

    /** Adds or extends a lock; expired ones are dropped on the way. True when it is new. */
    suspend fun add(l: Lock, now: Instant): Boolean {
        val cur = active(now)
        val old = cur.firstOrNull { it.id == l.id }
        if (old != null && !l.untilInstant.isAfter(old.untilInstant)) return false
        save(cur.filter { it.id != l.id } + l)
        return old == null
    }

    suspend fun remove(id: String, now: Instant) = save(active(now).filter { it.id != id })

    /** The lock that stops new orders on [ticker], if any. */
    suspend fun denies(sid: String, mode: Mode, ticker: String, now: Instant): Reason? =
        active(now).firstOrNull { it.covers(sid, mode, ticker) }?.let { Reason(ReasonCode.LOCKED, "${it.reason}, until ${it.until.take(16).replace('T', ' ')} UTC") }

    /** A whole-strategy lock (series null), which puts the strategy in REDUCING. */
    suspend fun strategyLock(sid: String, mode: Mode, now: Instant): Lock? =
        active(now).firstOrNull { (it.strategyId == sid || it.strategyId == Lock.ALL) && (it.allModes || it.mode == mode) && it.series == null }

    companion object { const val KEY = "engine.locks" }
}

/**
 * The protections, after freqtrade's: each looks back over settled positions and,
 * when its condition holds, locks the strategy or one market series for a while.
 * All start off: none has been measured on these strategies, so they are for paper
 * first, and the screen says so.
 */
@Serializable
data class Protection(
    val type: Type, val enabled: Boolean = false, val strategyId: String? = null,
    val lookbackHours: Int = 24, val tradeLimit: Int = 3, val stopHours: Int = 6,
    val maxDrawdownUsd: Double = 10.0, val requiredProfitUsd: Double = 0.0,
) {
    enum class Type(val label: String, val help: String) {
        LOSS_GUARD("Loss guard", "Locks the strategy when this many positions lose within the look-back."),
        MAX_DRAWDOWN("Drawdown lock", "Locks the strategy when realised P&L falls this far from its high within the look-back."),
        COOLDOWN("Series cooldown", "After a loss in a market series (e.g. one city's highs), locks that series for a while."),
        LOW_PROFIT("Losing series", "Locks a market series whose P&L over the look-back is below the floor, after enough positions."),
    }

    fun key(sid: String, mode: Mode, series: String?) = "${type.name}:$sid:${mode.name}:${series ?: "*"}"

    /** New locks this protection calls for on [rows] (one strategy's settled book in one mode). */
    fun evaluate(sid: String, mode: Mode, rows: List<QuoteRow>, now: Instant): List<Lock> {
        if (!enabled || (strategyId != null && strategyId != sid)) return emptyList()
        val since = now.minus(Duration.ofHours(lookbackHours.toLong()))
        val settled = rows.filter { it.resolvedAt != null && it.hypothetical == false && it.pnl != null && it.resolvedAt.isAfter(since) }
            .sortedBy { it.resolvedAt }
        fun lock(series: String?, from: Instant, why: String) = Lock(key(sid, mode, series), sid, mode, series,
            from.plus(Duration.ofHours(stopHours.toLong())).toString(), why)
        return when (type) {
            Type.LOSS_GUARD -> {
                val losses = settled.filter { it.pnl!! < 0 }
                if (losses.size >= tradeLimit) listOf(lock(null, losses.last().resolvedAt!!, "${losses.size} losses in $lookbackHours h")) else emptyList()
            }
            Type.MAX_DRAWDOWN -> {
                if (settled.size < tradeLimit) return emptyList()
                var cum = 0.0; var peak = 0.0; var dd = 0.0
                for (r in settled) { cum += r.pnl!!; peak = maxOf(peak, cum); dd = maxOf(dd, peak - cum) }
                if (dd >= maxDrawdownUsd) listOf(lock(null, settled.last().resolvedAt!!, "drawdown ${money(dd)} in $lookbackHours h")) else emptyList()
            }
            Type.COOLDOWN -> settled.filter { it.pnl!! < 0 }.groupBy { seriesOf(it.ticker) }
                .map { (series, l) -> lock(series, l.last().resolvedAt!!, "loss in $series") }
                .filter { Instant.parse(it.until).isAfter(now) }
            Type.LOW_PROFIT -> settled.groupBy { seriesOf(it.ticker) }
                .filter { (_, l) -> l.size >= tradeLimit && l.sumOf { it.pnl!! } < requiredProfitUsd }
                .map { (series, l) -> lock(series, l.last().resolvedAt!!, "$series made ${money(l.sumOf { it.pnl!! })} on ${l.size}") }
        }.filter { Instant.parse(it.until).isAfter(now) }
    }

    companion object {
        const val KEY = "engine.protections"
        val DEFAULTS = Type.entries.map { Protection(it) }
        private val ser = ListSerializer(serializer())
        suspend fun load(state: StateStore): List<Protection> =
            state.get(KEY)?.let { runCatching { JSON.decodeFromString(ser, it) }.getOrNull() } ?: DEFAULTS
        suspend fun save(state: StateStore, l: List<Protection>) = state.put(KEY, JSON.encodeToString(ser, l))
    }
}
