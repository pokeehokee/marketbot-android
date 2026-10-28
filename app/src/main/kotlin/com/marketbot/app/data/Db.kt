package com.marketbot.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "quotes", indices = [Index("strategyId", "mode", "resolvedAt")])
data class QuoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val strategyId: String, val mode: String, val venue: String, val ticker: String, val action: String,
    val price: Double, val contracts: Double, val placedAt: Long, val closeTime: Long?,
    val orderId: String?, val clientOrderId: String?, val marketKind: String?, val queueAhead: Double?,
    val filledContracts: Double?, val fillSource: String?, val fillCheckedAt: Long?, val cancelledAt: Long?,
    val resolvedAt: Long?, val result: String?, val pnl: Double?, val pnlSource: String?, val pnlOrigin: String?,
    val hypothetical: Boolean?, val extra: String,
)

@Serializable
@Entity(tableName = "events", indices = [Index("at"), Index("strategyId")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long, val strategyId: String?, val level: String, val kind: String, val message: String, val values: String,
)

@Serializable
@Entity(tableName = "state")
data class StateEntity(@PrimaryKey val key: String, val value: String)

@Serializable
@Entity(tableName = "equity", indices = [Index("venue", "mode", "at")])
data class EquityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long, val venue: String, val mode: String,
    val cash: Double, val cashSource: String, val cashOrigin: String,
    val positions: Double?, val positionsSource: String?, val positionsOrigin: String?,
    val total: Double?, val totalSource: String?, val totalOrigin: String?,
)

@Serializable
@Entity(tableName = "audit")
data class AuditEntity(@PrimaryKey val key: String, val value: String, val source: String, val origin: String, val note: String?, val at: Long)

@Dao
interface QuoteDao {
    @Insert suspend fun insert(q: QuoteEntity): Long
    @Update suspend fun update(q: QuoteEntity)
    @Query("SELECT * FROM quotes WHERE strategyId = :s AND mode = :m AND resolvedAt IS NULL ORDER BY id")
    suspend fun open(s: String, m: String): List<QuoteEntity>
    @Query("SELECT * FROM quotes WHERE strategyId = :s AND (:m IS NULL OR mode = :m) AND (:since IS NULL OR placedAt >= :since) ORDER BY id")
    suspend fun all(s: String, m: String?, since: Long?): List<QuoteEntity>
    @Query("SELECT * FROM quotes WHERE id = :id") suspend fun byId(id: Long): QuoteEntity?
    @Query("SELECT * FROM quotes WHERE strategyId = :s ORDER BY id DESC LIMIT :limit")
    fun watch(s: String, limit: Int): Flow<List<QuoteEntity>>
    @Query("SELECT * FROM quotes ORDER BY id DESC LIMIT :limit") fun watchAll(limit: Int): Flow<List<QuoteEntity>>
    /** Settled real positions of every strategy, oldest first: the source of realised P&L and its charts. */
    @Query("SELECT * FROM quotes WHERE resolvedAt IS NOT NULL AND hypothetical = 0 AND pnl IS NOT NULL ORDER BY resolvedAt")
    fun watchSettled(): Flow<List<QuoteEntity>>
}

@Dao
interface EventDao {
    @Insert suspend fun insert(e: EventEntity)
    @Query("SELECT * FROM events WHERE (:s IS NULL OR strategyId = :s) ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int, s: String?): List<EventEntity>
    @Query("SELECT * FROM events ORDER BY id DESC LIMIT :limit") fun watch(limit: Int): Flow<List<EventEntity>>
    @Query("DELETE FROM events WHERE at < :before") suspend fun prune(before: Long)
    /** Routine lines (DEBUG, INFO) go after [before]; warnings, errors and trades are kept as the record. */
    @Query("DELETE FROM events WHERE at < :before AND level IN ('DEBUG', 'INFO')") suspend fun pruneRoutine(before: Long)
}

@Dao
interface StateDao {
    @Query("SELECT value FROM state WHERE `key` = :k") suspend fun get(k: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(e: StateEntity)
    @Query("DELETE FROM state WHERE `key` = :k") suspend fun delete(k: String)
    @Query("SELECT * FROM state") fun watchAll(): Flow<List<StateEntity>>
}

@Dao
interface EquityDao {
    @Insert suspend fun insert(e: EquityEntity)
    @Query("SELECT * FROM equity WHERE venue = :v AND mode = :m AND at >= :since ORDER BY at")
    suspend fun history(v: String, m: String, since: Long): List<EquityEntity>
    @Query("SELECT MAX(at) FROM equity WHERE venue = :v AND mode = :m") suspend fun lastAt(v: String, m: String): Long?
    @Query("SELECT * FROM equity WHERE at >= :since ORDER BY at") fun watchSince(since: Long): Flow<List<EquityEntity>>
    @Query("SELECT * FROM equity WHERE id IN (SELECT MAX(id) FROM equity GROUP BY venue, mode)") fun watchLatest(): Flow<List<EquityEntity>>
}

@Dao
interface AuditDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(a: AuditEntity)
    @Query("SELECT * FROM audit ORDER BY `key`") suspend fun all(): List<AuditEntity>
    @Query("SELECT * FROM audit ORDER BY `key`") fun watch(): Flow<List<AuditEntity>>
}

/** Whole-table reads and writes for backup and restore. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM quotes ORDER BY id") suspend fun quotes(): List<QuoteEntity>
    @Query("SELECT * FROM events WHERE level NOT IN ('DEBUG', 'INFO') ORDER BY id") suspend fun keyEvents(): List<EventEntity>
    @Query("SELECT * FROM state") suspend fun state(): List<StateEntity>
    @Query("SELECT * FROM equity ORDER BY id") suspend fun equity(): List<EquityEntity>
    @Query("SELECT * FROM audit") suspend fun audit(): List<AuditEntity>
    @Insert suspend fun putQuotes(r: List<QuoteEntity>)
    @Insert suspend fun putEvents(r: List<EventEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putState(r: List<StateEntity>)
    @Insert suspend fun putEquity(r: List<EquityEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAudit(r: List<AuditEntity>)
    @Query("DELETE FROM quotes") suspend fun clearQuotes()
    @Query("DELETE FROM events") suspend fun clearEvents()
    @Query("DELETE FROM state") suspend fun clearState()
    @Query("DELETE FROM equity") suspend fun clearEquity()
    @Query("DELETE FROM audit") suspend fun clearAudit()
}

@Database(entities = [QuoteEntity::class, EventEntity::class, StateEntity::class, EquityEntity::class, AuditEntity::class], version = MarketbotDb.VERSION, exportSchema = true)
abstract class MarketbotDb : RoomDatabase() {
    abstract fun quotes(): QuoteDao
    abstract fun events(): EventDao
    abstract fun state(): StateDao
    abstract fun equity(): EquityDao
    abstract fun audit(): AuditDao
    abstract fun backup(): BackupDao

    companion object {
        /** Bump with a new exported schema in app/schemas and a migration; the schema test enforces both. */
        const val VERSION = 1

        fun open(ctx: Context): MarketbotDb = Room.databaseBuilder(ctx, MarketbotDb::class.java, "marketbot.db").build()
        fun inMemory(ctx: Context): MarketbotDb = Room.inMemoryDatabaseBuilder(ctx, MarketbotDb::class.java).allowMainThreadQueries().build()
    }
}
