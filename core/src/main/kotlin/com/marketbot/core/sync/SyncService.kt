package com.marketbot.core.sync

import com.marketbot.core.engine.Engine
import com.marketbot.core.store.LogEvent
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.MethodRecord
import com.marketbot.core.util.JSON
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Duration
import java.time.Instant

/**
 * Runs the Claude sync for the engine: push the snapshot and the ledger, pull the
 * proposals, keep the ones still waiting for the user, and apply or reject one
 * when the user says so. Every proposal is checked again by [ProposalPolicy] at
 * the moment it is applied, against the settings and mode as they are then.
 */
class SyncService(
    private val engine: Engine,
    /** The cross-venue research rows (last 7 days, CSV), sent as exports/books-latest.csv when there are any. */
    private val books: (() -> String?)? = null,
    /** The recorded public trades on the quoted markets (last 3 days, CSV), sent as exports/trades-latest.csv. */
    private val trades: (() -> String?)? = null,
    private val mailbox: (repo: String, token: String) -> GitHubMailbox = { r, t -> GitHubMailbox(r, t) },
) {

    @Serializable
    data class Pending(val proposal: Proposal, val refused: String? = null, val strongConfirm: Boolean = false)

    @Serializable
    data class Status(val at: String, val ok: Boolean, val detail: String)

    private val st get() = engine.stores.state
    private val pendSer = ListSerializer(Pending.serializer())
    private val outSer = MapSerializer(String.serializer(), ProposalOutcome.serializer())

    suspend fun pending(): List<Pending> = st.get(PENDING)?.let { runCatching { JSON.decodeFromString(pendSer, it) }.getOrNull() } ?: emptyList()
    suspend fun status(): Status? = st.get(STATUS)?.let { runCatching { JSON.decodeFromString(Status.serializer(), it) }.getOrNull() }
    private suspend fun decided(): Map<String, ProposalOutcome> = st.get(DECIDED)?.let { runCatching { JSON.decodeFromString(outSer, it) }.getOrNull() } ?: emptyMap()
    private suspend fun outbox(): Map<String, ProposalOutcome> = st.get(OUTBOX)?.let { runCatching { JSON.decodeFromString(outSer, it) }.getOrNull() } ?: emptyMap()

    /** Whether a daily sync is due (none in the last 23 h). */
    suspend fun due(now: Instant): Boolean = status()?.let { Duration.between(Instant.parse(it.at), now) >= Duration.ofHours(23) } ?: true

    /** Push, then pull. Returns a one-line summary; the same line is kept as the last status. */
    suspend fun sync(repo: String, token: String, appVersion: String): String {
        val now = engine.now()
        val r = runCatching {
            val box = mailbox(repo, token)
            val snaps = engine.strategies.map { s ->
                val rows = listOf(Mode.LIVE, Mode.PAPER).flatMap { engine.stores.ledger.all(s.id, it) }
                val mode = engine.mode(s.id)
                StrategySnapshot(s.id, s.name, s.baseId, mode.name, engine.armRecord(s.id) != null, engine.config(s).asMap,
                    MethodRecord.preferred(mode, rows, now.minus(Duration.ofDays(1)))?.line())
            }
            box.write(GitHubMailbox.SNAPSHOT, SyncPayload.snapshot(now, appVersion, snaps), "app: snapshot $now")
            val rows = engine.strategies.flatMap { s -> engine.stores.ledger.all(s.id) }
            box.write(GitHubMailbox.ORDERS, SyncPayload.ordersCsv(rows.asSequence()), "app: orders $now")
            books?.invoke()?.let { box.write(GitHubMailbox.BOOKS, it, "app: cross-venue books $now") }
            trades?.invoke()?.let { box.write(GitHubMailbox.TRADES, it, "app: trade tape $now") }
            val out = outbox()
            box.recordOutcomes(out)
            st.put(OUTBOX, null)
            val done = decided()
            val fresh = box.proposals().filter { it.id !in done }
            val list = fresh.map { p ->
                val s = engine.strategies.firstOrNull { it.id == p.strategy }
                when (val v = ProposalPolicy.check(p, s, s?.let { engine.config(it) }, engine.mode(p.strategy))) {
                    is ProposalPolicy.Verdict.Ok -> Pending(p, null, v.needsStrongConfirm)
                    is ProposalPolicy.Verdict.Refused -> Pending(p, v.why)
                }
            }
            st.put(PENDING, JSON.encodeToString(pendSer, list))
            "Synced: snapshot and ${snaps.size} strategies sent, ${out.size} outcome(s) reported, ${list.size} proposal(s) waiting" +
                (list.count { it.refused != null }.takeIf { it > 0 }?.let { " ($it refused by the app's rules)" } ?: "") + "."
        }
        val line = r.getOrElse { "Sync failed: ${it.message ?: it.javaClass.simpleName}" }
        st.put(STATUS, JSON.encodeToString(Status.serializer(), Status(now.toString(), r.isSuccess, line)))
        engine.stores.events.log(LogEvent(now, null, if (r.isSuccess) LogEvent.Level.INFO else LogEvent.Level.WARN, "sync", line))
        return line
    }

    /** Apply an approved proposal. Returns what happened, in a line for the user. */
    suspend fun approve(id: String): String {
        val p = pending().firstOrNull { it.proposal.id == id }?.proposal ?: return "That proposal is no longer waiting."
        val s = engine.strategies.firstOrNull { it.id == p.strategy }
        val outcome = when (val v = ProposalPolicy.check(p, s, s?.let { engine.config(it) }, engine.mode(p.strategy))) {
            is ProposalPolicy.Verdict.Refused -> ProposalOutcome("refused", engine.now().toString(), v.why)
            is ProposalPolicy.Verdict.Ok -> {
                s!!
                if (v.changes.isNotEmpty()) engine.saveConfig(s, engine.config(s).asMap + v.changes)
                v.mode?.let { engine.setMode(s, it) }
                ProposalOutcome("applied", engine.now().toString(),
                    (v.changes.entries.joinToString { "${it.key}=${it.value}" } + (v.mode?.let { " mode=$it" } ?: "")).trim())
            }
        }
        finish(p, outcome)
        return if (outcome.status == "applied") "Applied to ${s?.name}: ${outcome.detail}." else "Not applied: ${outcome.detail}."
    }

    suspend fun reject(id: String): String {
        val p = pending().firstOrNull { it.proposal.id == id }?.proposal ?: return "That proposal is no longer waiting."
        finish(p, ProposalOutcome("rejected", engine.now().toString(), "rejected on the phone"))
        return "Rejected."
    }

    private suspend fun finish(p: Proposal, o: ProposalOutcome) {
        st.put(DECIDED, JSON.encodeToString(outSer, (decided() + (p.id to o)).entries.toList().takeLast(500).associate { it.key to it.value }))
        st.put(OUTBOX, JSON.encodeToString(outSer, outbox() + (p.id to o)))
        st.put(PENDING, JSON.encodeToString(pendSer, pending().filter { it.proposal.id != p.id }))
        engine.stores.events.log(LogEvent(engine.now(), p.strategy, LogEvent.Level.WARN, "sync",
            "Claude proposal ${p.id} ${o.status}: ${o.detail}${if (p.reason.isNotBlank()) " (reason given: ${p.reason})" else ""}"))
    }

    companion object {
        const val PENDING = "sync.pending"
        const val STATUS = "sync.status"
        const val DECIDED = "sync.decided"
        const val OUTBOX = "sync.outbox"
    }
}
