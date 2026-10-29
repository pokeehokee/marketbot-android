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

data class Evidence(val level: EvidenceLevel, val headline: String, val details: List<String>, val source: String,
                    val expectation: Expectation? = null)

/** A strategy's help page: [makes] and [loses] money, then [notes] worth knowing before going live. */
data class Help(val makes: String, val loses: String, val notes: List<String>)

/** What the evidence says one contract should earn, in dollars, with its interval when one was measured. */
data class Expectation(val perContract: Double, val low: Double?, val high: Double?, val basis: String)

/** This app's own live (or paper) record per contract, with a 95% interval, against the evidence. */
data class RecordVsExpected(val n: Int, val contracts: Double, val perContract: Double, val low: Double, val high: Double) {
    companion object {
        /**
         * Settled real fills only (never hypothetical). The interval treats each
         * position as one draw of its per-contract result, weighted by contracts,
         * so a single large position cannot look like many independent ones.
         */
        fun of(rows: List<com.marketbot.core.store.QuoteRow>): RecordVsExpected? {
            val r = rows.filter { it.resolvedAt != null && it.hypothetical == false && it.pnl != null }
                .map { (it.filledContracts ?: it.contracts) to it.pnl!! }.filter { it.first > 0 }
            if (r.size < 2) return null
            val c = r.sumOf { it.first }
            val m = r.sumOf { it.second } / c
            val se = kotlin.math.sqrt(r.sumOf { (w, p) -> w * w * (p / w - m) * (p / w - m) }) / c
            return RecordVsExpected(r.size, c, m, m - 1.96 * se, m + 1.96 * se)
        }
    }
}

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
        val kind: TextKind = TextKind.FREE,
    ) : Param

    /** How a text setting is edited: free text, or one of the structured pickers. */
    enum class TextKind { FREE, HOURS, KINDS, ZONE, BANDS }

    /** Why [raw] is not acceptable for this setting, or null when it is. */
    fun problem(raw: String): String? = when (this) {
        is Num -> when (val v = raw.trim().toDoubleOrNull()) {
            null -> "$label must be a number."
            else -> if (v < min || v > max) "$label must be between $min and $max." else null
        }
        is Int_ -> when (val v = raw.trim().toIntOrNull()) {
            null -> "$label must be a whole number."
            else -> if (v < min || v > max) "$label must be between $min and $max." else null
        }
        is Bool -> if (raw.toBooleanStrictOrNull() == null) "$label must be on or off." else null
        is Text -> when (kind) {
            TextKind.HOURS -> raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .firstOrNull { h -> h.toIntOrNull()?.let { it !in 0..23 } ?: true }?.let { "$label: \"$it\" is not an hour 0-23." }
            TextKind.KINDS -> raw.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
                .firstOrNull { it !in setOf("HIGH", "LOW") }?.let { "$label: only HIGH and LOW." }
            TextKind.ZONE -> raw.trim().takeIf { it.isNotEmpty() }?.let { z ->
                if (runCatching { java.time.ZoneId.of(z) }.isFailure) "\"$z\" is not a time zone name; try America/New_York." else null }
            TextKind.BANDS -> raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.firstOrNull { b ->
                val p = b.split('-').mapNotNull { it.trim().toDoubleOrNull() }
                p.size != 2 || p[0] < 0 || p[1] > 1 || p[0] >= p[1] }?.let { "$label: \"$it\" should look like 0.10-0.15." }
            TextKind.FREE -> null
        }
    }
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

data class RunReport(val summary: String, val values: Map<String, Sourced<*>> = emptyMap(), val funnel: List<FunnelStep> = emptyList())

/**
 * One step of a pass's selection, for "why didn't it trade?": how many markets
 * were still in the running after it. The first step is what was looked at.
 */
@kotlinx.serialization.Serializable
data class FunnelStep(val label: String, val count: Int)

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
    /** Trade notices (fills, settlements): a quieter channel than [notify]'s alerts. */
    val tradeNotify: suspend (title: String, body: String) -> Unit = { _, _ -> },
) {
    suspend fun log(level: LogEvent.Level, kind: String, message: String, values: Map<String, Sourced<*>> = emptyMap()) {
        stores.events.log(LogEvent(clock.now(), strategyId, level, kind, message, values))
        for ((k, v) in values) stores.audit.record("$strategyId.$k", v)
    }
    suspend fun info(kind: String, msg: String, values: Map<String, Sourced<*>> = emptyMap()) = log(LogEvent.Level.INFO, kind, msg, values)
    suspend fun warn(kind: String, msg: String, values: Map<String, Sourced<*>> = emptyMap()) = log(LogEvent.Level.WARN, kind, msg, values)
    suspend fun error(kind: String, msg: String, values: Map<String, Sourced<*>> = emptyMap()) = log(LogEvent.Level.ERROR, kind, msg, values)
    suspend fun trade(kind: String, msg: String, values: Map<String, Sourced<*>> = emptyMap()) {
        log(LogEvent.Level.TRADE, kind, msg, values)
        // Live settlements reach the phone's Trades notifications; offers would be dozens an hour, so they stay in Activity.
        if (live && kind == "settled") tradeNotify("Settled", msg)
    }

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
    /** Plain-language help: how it makes money, how it loses it. Settings are explained from [params]. */
    val help: Help get() = Help(summary, "See the evidence below.", emptyList())

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

    /**
     * What these settings mean in money, before saving: the worst single loss,
     * how many the brake allows, what can be locked up. Plain sentences.
     */
    fun effects(config: StrategyConfig): List<String> = emptyList()

    /** The go-live checklist. */
    suspend fun liveRequirements(ctx: StrategyContext): List<Requirement>

    /** Read-only checks plus, where the venue allows it, a tiny test order placed and cancelled. */
    suspend fun preflight(ctx: StrategyContext): PreflightResult

    /** Headline numbers for the strategy screen. */
    suspend fun stats(ctx: StrategyContext): List<Stat>

    /**
     * Kill criteria and gates judged on this strategy's own [mode] record. A KILL on
     * the live record disarms live trading (the engine does it, once per finding).
     */
    suspend fun healthChecks(ctx: StrategyContext, mode: Mode): List<Check> = emptyList()

    /** Price bands for the fills-by-outcome table, or null when the strategy has no binary orders. */
    val outcomeBands: List<Pair<Double, Double>>? get() = null
}

/** Every problem with [config]: each setting's own range or format, then the strategy's cross-checks. */
fun Strategy.problems(config: StrategyConfig): List<String> {
    val own = params.mapNotNull { p -> config.asMap[p.key]?.let { p.problem(it) } }
    return own + if (own.isEmpty()) runCatching { validate(config) }.getOrElse { listOf(it.message ?: "invalid settings") } else emptyList()
}
