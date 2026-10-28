package com.marketbot.app.data

import com.marketbot.core.provenance.Source
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.store.AuditStore
import com.marketbot.core.store.EquitySnapshot
import com.marketbot.core.store.EquityStore
import com.marketbot.core.store.EventLog
import com.marketbot.core.store.Ledger
import com.marketbot.core.store.LogEvent
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.store.StateStore
import com.marketbot.core.store.Stores
import com.marketbot.core.strategy.Mode
import com.marketbot.core.util.JSON
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** A Sourced value as stored: the value as text plus where it came from. */
@Serializable
data class StoredValue(val key: String, val value: String, val source: String, val origin: String, val note: String? = null, val at: Long)

private val VALUES = ListSerializer(StoredValue.serializer())

fun Instant?.ms(): Long? = this?.toEpochMilli()
fun Long?.inst(): Instant? = this?.let { Instant.ofEpochMilli(it) }

fun QuoteRow.toEntity() = QuoteEntity(id, strategyId, mode.name, venue, ticker, action, price, contracts, placedAt.toEpochMilli(),
    closeTime.ms(), orderId, clientOrderId, marketKind, queueAhead, filledContracts, fillSource, fillCheckedAt.ms(), cancelledAt.ms(),
    resolvedAt.ms(), result, pnl, pnlSource?.name, pnlOrigin, hypothetical, JSON.encodeToString(JsonObject.serializer(), extra))

fun QuoteEntity.toRow() = QuoteRow(id, strategyId, Mode.valueOf(mode), venue, ticker, action, price, contracts, Instant.ofEpochMilli(placedAt),
    closeTime.inst(), orderId, clientOrderId, marketKind, queueAhead, filledContracts, fillSource, fillCheckedAt.inst(), cancelledAt.inst(),
    resolvedAt.inst(), result, pnl, pnlSource?.let { runCatching { Source.valueOf(it) }.getOrNull() }, pnlOrigin, hypothetical,
    runCatching { JSON.parseToJsonElement(extra) as JsonObject }.getOrDefault(JsonObject(emptyMap())))

fun EventEntity.values(): List<StoredValue> = runCatching { JSON.decodeFromString(VALUES, values) }.getOrDefault(emptyList())

class RoomLedger(private val dao: QuoteDao) : Ledger {
    override suspend fun insert(q: QuoteRow) = dao.insert(q.toEntity().copy(id = 0))
    override suspend fun update(q: QuoteRow) = dao.update(q.toEntity())
    override suspend fun open(strategyId: String, mode: Mode) = dao.open(strategyId, mode.name).map { it.toRow() }
    override suspend fun all(strategyId: String, mode: Mode?, since: Instant?) = dao.all(strategyId, mode?.name, since.ms()).map { it.toRow() }
    override suspend fun byId(id: Long) = dao.byId(id)?.toRow()
}

class RoomEventLog(private val dao: EventDao) : EventLog {
    override suspend fun log(e: LogEvent) {
        val vals = e.values.map { (k, v) -> StoredValue(k, v.value.toString(), v.source.name, v.origin, v.note, v.at.toEpochMilli()) }
        dao.insert(EventEntity(0, e.at.toEpochMilli(), e.strategyId, e.level.name, e.kind, e.message, JSON.encodeToString(VALUES, vals)))
    }

    override suspend fun recent(limit: Int, strategyId: String?) = dao.recent(limit, strategyId).map { ev ->
        LogEvent(Instant.ofEpochMilli(ev.at), ev.strategyId, LogEvent.Level.valueOf(ev.level), ev.kind, ev.message,
            ev.values().associate { it.key to Sourced(it.value, Source.valueOf(it.source), it.origin, it.note, Instant.ofEpochMilli(it.at)) })
    }
}

class RoomState(private val dao: StateDao) : StateStore {
    override suspend fun get(key: String) = dao.get(key)
    override suspend fun put(key: String, value: String?) { if (value == null) dao.delete(key) else dao.put(StateEntity(key, value)) }
}

class RoomEquity(private val dao: EquityDao, private val minGapMs: Long = 5 * 60_000L) : EquityStore {
    /** One snapshot per venue and mode every [minGapMs]: a 5-second strategy would otherwise write 17,000 rows a day. */
    override suspend fun record(s: EquitySnapshot) {
        val last = dao.lastAt(s.venue, s.mode.name)
        if (last != null && s.at.toEpochMilli() - last < minGapMs) return
        dao.insert(EquityEntity(0, s.at.toEpochMilli(), s.venue, s.mode.name,
        s.cash.value, s.cash.source.name, s.cash.origin, s.positionsValue?.value, s.positionsValue?.source?.name, s.positionsValue?.origin,
        s.total?.value, s.total?.source?.name, s.total?.origin))
    }

    override suspend fun history(venue: String, mode: Mode, since: Instant) = dao.history(venue, mode.name, since.toEpochMilli()).map { it.toSnapshot() }
}

fun EquityEntity.toSnapshot() = EquitySnapshot(Instant.ofEpochMilli(at), venue, Mode.valueOf(mode),
    Sourced(cash, Source.valueOf(cashSource), cashOrigin, at = Instant.ofEpochMilli(at)),
    positions?.let { Sourced(it, Source.valueOf(positionsSource!!), positionsOrigin!!, at = Instant.ofEpochMilli(at)) },
    total?.let { Sourced(it, Source.valueOf(totalSource!!), totalOrigin!!, at = Instant.ofEpochMilli(at)) })

class RoomAudit(private val dao: AuditDao) : AuditStore {
    override suspend fun record(key: String, value: Sourced<*>) =
        dao.put(AuditEntity(key, value.value.toString(), value.source.name, value.origin, value.note, value.at.toEpochMilli()))

    override suspend fun latest() = dao.all().associate { it.key to Sourced(it.value, Source.valueOf(it.source), it.origin, it.note, Instant.ofEpochMilli(it.at)) }
}

fun roomStores(db: MarketbotDb) = Stores(RoomLedger(db.quotes()), RoomEventLog(db.events()), RoomState(db.state()), RoomEquity(db.equity()), RoomAudit(db.audit()))
