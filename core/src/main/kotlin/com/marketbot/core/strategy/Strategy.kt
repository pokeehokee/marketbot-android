package com.marketbot.core.strategy

import com.marketbot.core.btc.BtcIndex
import com.marketbot.core.kalshi.KalshiClient
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.store.LogEvent
import com.marketbot.core.store.Stores
import com.marketbot.core.util.Clock
import java.time.Duration

enum class Venue(val label: String) { KALSHI("Kalshi"), POLYMARKET("Polymarket") }

/** OFF does nothing. PAPER simulates against live public data. LIVE places real orders. */
enum class Mode { OFF, PAPER, LIVE }

/** How much measured evidence stands behind a method. Shown on every strategy card. */
enum class EvidenceLevel(val label: String) {
    LIVE_PROVEN("Made money live"),
    REPORTED("Reported live result, not verifiable"),
    BACKTEST_ONLY("Backtest only"),
    UNPROVEN("Unproven"),
}

data class Evidence(val level: EvidenceLevel, val headline: String, val details: List<String>, val source: String)

/** One user-editable setting. The settings screen is generated from these. */
sealed interface Param {
    val key: String
    val label: String
    val help: String
    val default: String

    data class Num(
        override val key: String, override val label: String, override val help: String,
        val defaultValue: Double, val min: Double, val max: Double, val unit: String = "", val decimals: Int = 2,
    ) : Param { override val default get() = defaultValue.toString() }

    data class Int_(
        override val key: String, override val label: String, override val help: String,
        val defaultValue: Int, val min: Int, val max: Int, val unit: String = "",
    ) : Param { override val default get() = defaultValue.toString() }

    data class Bool(
        override val key: String, override val label: String, override val help: String, val defaultValue: Boolean,
    ) : Param { override val default get() = defaultValue.toString() }

    data class Text(
        override val key: String, override val label: String, override val help: String, override val default: String,
    ) : Param
}

/** A strategy's settings: defaults overlaid with whatever the user saved. Out-of-range values are clamped. */
class StrategyConfig(private val params: List<Param>, private val values: Map<String, String> = emptyMap()) {
    private fun p(key: String) = params.firstOrNull { it.key == key } ?: error("unknown setting $key")
    fun raw(key: String): String = values[key] ?: p(key).default
    fun num(key: String): Double {
        val prm = p(key) as Param.Num
        return (raw(key).toDoubleOrNull() ?: prm.defaultValue).coerceIn(prm.min, prm.max)
    }
    fun int(key: String): Int {
        val prm = p(key) as Param.Int_
        return (raw(key).toDoubleOrNull()?.toInt() ?: prm.defaultValue).coerceIn(prm.min, prm.max)
    }
    fun bool(key: String): Boolean = raw(key).toBooleanStrictOrNull() ?: (p(key) as Param.Bool).defaultValue
    fun text(key: String): String = raw(key)
    fun with(key: String, value: String) = StrategyConfig(params, values + (key to value))
    fun sourced(key: String): Sourced<String> = Sourced.config(raw(key), "setting $key")
    val asMap: Map<String, String> get() = params.associate { it.key to raw(it.key) }
}

/** A go-live checklist item. Hard items block live trading; soft items need an explicit acknowledgement. */
data class Requirement(val id: String, val label: String, val met: Boolean, val hard: Boolean, val detail: String)

data class PreflightResult(val ok: Boolean, val steps: List<Pair<String, Boolean>>, val message: String)

data class RunReport(val summary: String, val values: Map<String, Sourced<*>> = emptyMap())

/** A one-line stat for the strategy screen, with its source. */
data class Stat(val label: String, val value: Sourced<String>)

/**
 * Everything a strategy may touch. Credentials live outside; a client without
 * a signer can only read public data, so a PAPER run physically cannot trade.
 */
class StrategyContext(
    val strategyId: String,
    val mode: Mode,
    val config: StrategyConfig,
    val stores: Stores,
    val clock: Clock,
    val kalshi: KalshiClient,
    val polymarket: PolymarketClient,
    val btcIndex: BtcIndex,
    /** False whenever the global kill switch is on or live has not been armed for this strategy. */
    val liveArmed: Boolean,
    val notify: suspend (title: String, body: String) -> Unit = { _, _ -> },
) {
    suspend fun log(level: LogEvent.Level, kind: String, message: String, values: Map<String, Sourced<*>> = emptyMap()) {
        stores.events.log(LogEvent(clock.now(), strategyId, level, kind, message, values))
        for ((k, v) in values) stores.audit.record("$strategyId.$k", v)
    }
    suspend fun info(kind: String, msg: String, values: Map<String, Sourced<*>> = emptyMap()) = log(LogEvent.Level.INFO, kind, msg, values)
    suspend fun warn(kind: String, msg: String, values: Map<String, Sourced<*>> = emptyMap()) = log(LogEvent.Level.WARN, kind, msg, values)
    suspend fun error(kind: String, msg: String, values: Map<String, Sourced<*>> = emptyMap()) = log(LogEvent.Level.ERROR, kind, msg, values)
    suspend fun trade(kind: String, msg: String, values: Map<String, Sourced<*>> = emptyMap()) = log(LogEvent.Level.TRADE, kind, msg, values)

    /** The book a row belongs to. Paper and live are never mixed in any sum. */
    val live: Boolean get() = mode == Mode.LIVE && liveArmed
}

/**
 * A trading method. Add a new one by implementing this and registering it in
 * [StrategyRegistry]; the app builds its card, settings form, go-live
 * checklist and stats screen from these members.
 */
interface Strategy {
    val id: String
    val name: String
    val venue: Venue
    val summary: String
    val evidence: Evidence
    /** What happens to orders at the venue when this strategy leaves live, in one sentence for the confirmation. */
    val onLeaveLive: String get() = "Its resting orders are cancelled at the exchange now."
    val params: List<Param>

    /** Modes this strategy may be set to. A method with no live order path omits LIVE. */
    val supportedModes: Set<Mode> get() = setOf(Mode.OFF, Mode.PAPER, Mode.LIVE)

    /** Shared state that must be running while the strategy is on (e.g. the BTC index). */
    val needsBtcIndex: Boolean get() = false

    /** How long to wait after a pass before the next one. */
    fun nextDelay(ctx: StrategyContext): Duration

    /** One pass. Must never throw for an expected condition; log it and return. */
    suspend fun runOnce(ctx: StrategyContext): RunReport

    /** Called when the strategy is switched off or the engine stops. */
    suspend fun onStop(ctx: StrategyContext) {}

    /** The kill switch: cancel every resting order of this strategy now. Fills are held. */
    suspend fun cancelAllResting(ctx: StrategyContext) = onStop(ctx)

    /** Settings problems that must be fixed before the strategy can run. */
    fun validate(config: StrategyConfig): List<String> = emptyList()

    /** The go-live checklist. */
    suspend fun liveRequirements(ctx: StrategyContext): List<Requirement>

    /** Read-only checks plus, where the venue allows it, a tiny test order placed and cancelled. */
    suspend fun preflight(ctx: StrategyContext): PreflightResult

    /** Headline numbers for the strategy screen. */
    suspend fun stats(ctx: StrategyContext): List<Stat>
}
