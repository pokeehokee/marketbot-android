package com.marketbot.core.store

import com.marketbot.core.provenance.Sourced
import com.marketbot.core.strategy.Mode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** In-memory stores: used by tests, and as the reference behaviour the Room stores must match. */
class InMemoryLedger : Ledger {
    private val rows = linkedMapOf<Long, QuoteRow>()
    private var next = 1L
    private val m = Mutex()
    override suspend fun insert(q: QuoteRow): Long = m.withLock { val id = next++; rows[id] = q.copy(id = id); id }
    override suspend fun update(q: QuoteRow) = m.withLock { require(q.id in rows) { "no quote ${q.id}" }; rows[q.id] = q }
    override suspend fun open(strategyId: String, mode: Mode) = m.withLock {
        rows.values.filter { it.strategyId == strategyId && it.mode == mode && it.resolvedAt == null }
    }
    override suspend fun all(strategyId: String, mode: Mode?, since: Instant?) = m.withLock {
        rows.values.filter { it.strategyId == strategyId && (mode == null || it.mode == mode) && (since == null || !it.placedAt.isBefore(since)) }
    }
    override suspend fun byId(id: Long) = m.withLock { rows[id] }
}

class InMemoryEventLog : EventLog {
    val events = mutableListOf<LogEvent>()
    override suspend fun log(e: LogEvent) { synchronized(events) { events += e } }
    override suspend fun recent(limit: Int, strategyId: String?) = synchronized(events) {
        events.filter { strategyId == null || it.strategyId == strategyId }.takeLast(limit).reversed()
    }
}

class InMemoryState : StateStore {
    val map = mutableMapOf<String, String>()
    override suspend fun get(key: String) = synchronized(map) { map[key] }
    override suspend fun put(key: String, value: String?) { synchronized(map) { if (value == null) map.remove(key) else map[key] = value } }
}

class InMemoryEquity : EquityStore {
    val snaps = mutableListOf<EquitySnapshot>()
    override suspend fun record(s: EquitySnapshot) { synchronized(snaps) { snaps += s } }
    override suspend fun history(venue: String, mode: Mode, since: Instant) = synchronized(snaps) {
        snaps.filter { it.venue == venue && it.mode == mode && !it.at.isBefore(since) }
    }
}

class InMemoryAudit : AuditStore {
    val map = linkedMapOf<String, Sourced<String>>()
    override suspend fun record(key: String, value: Sourced<*>) { synchronized(map) { map[key] = value.map { it.toString() } } }
    override suspend fun latest() = synchronized(map) { map.toMap() }
}

fun inMemoryStores() = Stores(InMemoryLedger(), InMemoryEventLog(), InMemoryState(), InMemoryEquity(), InMemoryAudit())
