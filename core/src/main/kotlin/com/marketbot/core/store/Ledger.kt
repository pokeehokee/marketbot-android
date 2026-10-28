package com.marketbot.core.store

import com.marketbot.core.provenance.Source
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.strategy.Mode
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * One order a strategy placed (live) or would have placed (paper).
 *
 * Every number that is not the strategy's own intent carries its source:
 * the fill count says whether the exchange reported it (`fillSource` = an API
 * origin) or the paper simulator produced it; the P&L says whether it is the
 * exchange's settlement figure or a local calculation. `hypothetical = true`
 * rows are never summed into realised P&L.
 */
data class QuoteRow(
    val id: Long = 0,
    val strategyId: String,
    val mode: Mode,
    val venue: String,
    val ticker: String,
    /** What the order does, in plain terms: sell_yes, buy_yes, buy_no. */
    val action: String,
    val price: Double,
    val contracts: Double,
    val placedAt: Instant,
    val closeTime: Instant? = null,
    val orderId: String? = null,
    val clientOrderId: String? = null,
    val marketKind: String? = null,
    val queueAhead: Double? = null,
    val filledContracts: Double? = null,
    val fillSource: String? = null,
    val fillCheckedAt: Instant? = null,
    val cancelledAt: Instant? = null,
    val resolvedAt: Instant? = null,
    val result: String? = null,
    val pnl: Double? = null,
    val pnlSource: Source? = null,
    val pnlOrigin: String? = null,
    val hypothetical: Boolean? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    val open: Boolean get() = resolvedAt == null
    val resting: Boolean get() = resolvedAt == null && cancelledAt == null
}

data class LogEvent(
    val at: Instant,
    val strategyId: String?,
    val level: Level,
    val kind: String,
    val message: String,
    /** The values this line reports, each with where it came from. */
    val values: Map<String, Sourced<*>> = emptyMap(),
) {
    enum class Level { DEBUG, INFO, WARN, ERROR, TRADE }
}

data class EquitySnapshot(
    val at: Instant,
    val venue: String,
    val mode: Mode,
    val cash: Sourced<Double>,
    val positionsValue: Sourced<Double>?,
    val total: Sourced<Double>?,
)

interface Ledger {
    suspend fun insert(q: QuoteRow): Long
    suspend fun update(q: QuoteRow)
    suspend fun open(strategyId: String, mode: Mode): List<QuoteRow>
    suspend fun all(strategyId: String, mode: Mode? = null, since: Instant? = null): List<QuoteRow>
    suspend fun byId(id: Long): QuoteRow?
}

interface EventLog {
    suspend fun log(e: LogEvent)
    suspend fun recent(limit: Int = 500, strategyId: String? = null): List<LogEvent>
}

/** Small persistent key/value state: risk high-water marks, kill flags, paper broker state, settings. */
interface StateStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String?)
}

interface EquityStore {
    suspend fun record(s: EquitySnapshot)
    suspend fun history(venue: String, mode: Mode, since: Instant): List<EquitySnapshot>
}

/** The latest value of every figure the app displays, with its source. Feeds the "Data sources" screen. */
interface AuditStore {
    suspend fun record(key: String, value: Sourced<*>)
    suspend fun latest(): Map<String, Sourced<String>>
}

class Stores(
    val ledger: Ledger,
    val events: EventLog,
    val state: StateStore,
    val equity: EquityStore,
    val audit: AuditStore,
)
