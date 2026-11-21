package com.marketbot.core.strategy

import com.marketbot.core.kalshi.KalshiClient
import com.marketbot.core.kalshi.settlementPnl
import com.marketbot.core.store.QuoteRow
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/**
 * Read-outs computed from the ledger (and, for reconciliation, the exchange):
 * what a strategy's own record says about whether its edge is still there.
 * Everything here works on any strategy whose rows are binary-outcome orders
 * (sell_yes, buy_yes, buy_no); the reward farm's two-sided quoting is not one.
 */
object Outcomes {
    fun binary(q: QuoteRow) = q.action == "sell_yes" || q.action == "buy_yes" || q.action == "buy_no"

    /** Whether the position lost at settlement, or null before it settled. */
    fun lost(q: QuoteRow): Boolean? = when (q.result) {
        null -> null
        else -> when (q.action) { "sell_yes" -> q.result == "yes"; "buy_yes" -> q.result == "no"; "buy_no" -> q.result == "yes"; else -> null }
    }

    /**
     * The market's own probability that the position loses, when it was placed.
     * Selling YES at p loses with probability p (the quoted mid where the row kept
     * it); buying at p loses with probability 1 - p.
     */
    fun impliedLoss(q: QuoteRow): Double = when (q.action) {
        "sell_yes" -> com.marketbot.core.strategies.longshot.StageGates.quotedMid(q)
        else -> 1.0 - q.price
    }

    /** Money locked per contract until settlement: (1 - p) to sell YES at p, p to buy at p. */
    fun lockPerContract(q: QuoteRow): Double = if (q.action == "sell_yes") 1.0 - q.price else q.price
}

/** One health line: a kill criterion or a stage gate, and its verdict on the record so far. */
@Serializable
data class Check(val name: String, val verdict: Verdict, val detail: String) {
    enum class Verdict { PASS, CLEAR, INSUFFICIENT, FAIL, KILL }
}

object KillCriteria {
    /**
     * The generic criterion for binary strategies: over the last [days], positions
     * that lost against the number the prices implied. At or above 1.0 the market's
     * prices were right (or worse) and the edge the strategy exists for is gone.
     * Needs [minLosses] real losses before it can say so, because a handful of
     * losses is noise in either direction.
     */
    /**
     * Filled live positions whose market has closed but which carry no realised figure. A strategy
     * whose results cannot be read has no record at all, so this is reported on the strategy screen
     * rather than left to the log. [alarmHours] past close it fails.
     */
    fun ungradedPositions(rows: List<QuoteRow>, now: Instant, alarmHours: Long = 12): Check {
        val stuck = rows.filter {
            it.mode == Mode.LIVE && it.resolvedAt == null && (it.filledContracts ?: 0.0) > 0 &&
                it.closeTime != null && !it.closeTime.isAfter(now)
        }
        if (stuck.isEmpty()) return Check("Every filled position graded", Check.Verdict.PASS, "nothing is waiting on a realised figure")
        val oldest = stuck.minOf { Duration.between(it.closeTime, now).toHours() }
        val why = stuck.mapNotNull { (it.extra["grade_blocked"] as? kotlinx.serialization.json.JsonPrimitive)?.content }.firstOrNull()
        val detail = "${stuck.size} filled position(s) closed with no realised figure, oldest ${oldest}h ago" +
            (why?.let { "; $it" } ?: "")
        return Check("Every filled position graded", if (oldest >= alarmHours) Check.Verdict.FAIL else Check.Verdict.INSUFFICIENT, detail)
    }

    fun lossRatio(rows: List<QuoteRow>, now: Instant, days: Long = 60, minLosses: Int = 30): Check {
        val settled = rows.filter { Outcomes.binary(it) && it.resolvedAt != null && it.hypothetical == false &&
            !it.resolvedAt.isBefore(now.minus(Duration.ofDays(days))) && Outcomes.lost(it) != null }
        val losses = settled.count { Outcomes.lost(it) == true }
        val implied = settled.sumOf { Outcomes.impliedLoss(it) }
        val ratio = if (implied > 0) losses / implied else null
        val v = when { losses < minLosses -> Check.Verdict.INSUFFICIENT; (ratio ?: 0.0) >= 1.0 -> Check.Verdict.KILL; else -> Check.Verdict.CLEAR }
        return Check("$days-day losses below what prices implied", v,
            if (settled.isEmpty()) "no settled fills yet" else "$losses losses against %.1f implied (ratio %s; needs $minLosses losses to judge)"
                .format(implied, ratio?.let { "%.2f".format(it) } ?: "n/a"))
    }

    /**
     * Risk the EXCHANGE holds that the ledger does not know about. This is the
     * mirror of [ungradedPositions] and the more dangerous direction: an unknown
     * position is invisible to every brake, stop and exposure figure in the app,
     * so it accrues loss silently and nothing stops the day.
     *
     * The hazard is not hypothetical on this account. Recovering "the original
     * desktop run" turned up **1,612 fills and 1,535 positions whose `order_id`
     * the app never placed** (2026-11-12). That was a different runner rather
     * than a fault, but it is exactly this signature. Live, it arrives as a lost
     * order response (the repo forbids retrying a create and says to recover by
     * `client_order_id` -- nothing currently checks that the recovery happened),
     * a manual trade, a second process on the same account, or a partial fill
     * recorded at the wrong size.
     *
     * [exchange] is ticker -> contracts the venue reports held, [rows] every
     * ledger row for this strategy in any mode or state. [ownedPrefixes] limits
     * the comparison to series this strategy is responsible for, because one
     * Kalshi account carries other books' positions and alarming on those would
     * be noise that gets the check ignored.
     */
    fun orphanPositions(
        exchange: Map<String, Double>,
        rows: List<QuoteRow>,
        ownedPrefixes: List<String>,
        now: Instant = Instant.now(),
    ): Check {
        val name = "Exchange holds nothing the ledger has missed"
        if (ownedPrefixes.isEmpty()) {
            return Check(name, Check.Verdict.INSUFFICIENT, "no series claimed, so nothing can be reconciled")
        }
        val mine = exchange.filter { (t, n) -> n != 0.0 && ownedPrefixes.any { t.startsWith(it) } }
        if (mine.isEmpty()) return Check(name, Check.Verdict.PASS, "the exchange reports no open position in this book's series")
        // Known = the ledger has ANY row for that ticker. A graded row still
        // counts as known: the venue can report a position between the fill and
        // settlement, and that is not an orphan.
        val known = rows.groupBy { it.ticker }
        val unknown = mine.keys.filter { known[it].isNullOrEmpty() }.sorted()
        // Under-recorded: the venue holds more than the ledger's filled counts.
        val short = mine.filter { (t, n) ->
            val k = known[t] ?: return@filter false
            val recorded = k.sumOf { it.filledContracts ?: 0.0 }
            recorded + 1e-9 < n
        }.keys.sorted()
        if (unknown.isEmpty() && short.isEmpty()) {
            return Check(name, Check.Verdict.PASS, "${mine.size} open position(s), all present in the ledger at the venue's size")
        }
        val parts = buildList {
            if (unknown.isNotEmpty()) add("${unknown.size} position(s) the ledger has never seen: ${unknown.take(3).joinToString()}")
            if (short.isNotEmpty()) add("${short.size} recorded smaller than the venue holds: ${short.take(3).joinToString()}")
        }
        return Check(name, Check.Verdict.FAIL, parts.joinToString("; ") + " -- unknown risk is invisible to the brake")
    }

    /**
     * Per-table write freshness. Three separate incidents in the source repo were
     * the same shape: **a table silently stopped being written while everything
     * else kept trading** -- `settlements` froze for 4 days behind a NameError in
     * an alert, `performance_by_category` for 2 weeks behind a column the schema
     * never had, and calibration updates were dropped on lock collisions. Each
     * was found by a human reading logs.
     *
     * This is the generic control that catches all three without knowing what
     * they are. [newest] is writer -> the newest row's timestamp (null when the
     * writer has never run), [expected] that writer's own cadence. The threshold
     * is per writer deliberately: a single global one either misses the slow
     * writers or cries wolf on the fast ones, and an alarm that fires when
     * nothing happened is an alarm that gets ignored the day it matters.
     *
     * A writer that has never run is INSUFFICIENT, not FAIL: that is a fresh
     * install, not a freeze.
     */
    fun staleWrites(
        newest: Map<String, Instant?>,
        expected: Map<String, Duration>,
        now: Instant,
        tolerance: Double = 3.0,
    ): Check {
        val name = "Every writer still writing"
        if (newest.isEmpty()) return Check(name, Check.Verdict.INSUFFICIENT, "no writers registered")
        val never = newest.filterValues { it == null }.keys.sorted()
        val stale = mutableListOf<Pair<String, Duration>>()
        for ((who, at) in newest) {
            if (at == null) continue
            val cadence = expected[who] ?: continue
            val age = Duration.between(at, now)
            if (age > Duration.ofMillis((cadence.toMillis() * tolerance).toLong())) stale += who to age
        }
        stale.sortByDescending { it.second }
        if (stale.isEmpty()) {
            val d = if (never.isEmpty()) "${newest.size} writer(s) fresh"
            else "${newest.size - never.size} fresh; not yet run: ${never.joinToString()}"
            return Check(name, if (never.isEmpty()) Check.Verdict.PASS else Check.Verdict.INSUFFICIENT, d)
        }
        val worst = stale.first()
        return Check(name, Check.Verdict.FAIL,
            "${stale.size} writer(s) stale, worst ${worst.first} last wrote ${worst.second.toHours()}h ${worst.second.toMinutesPart()}m ago " +
                "(cadence ${expected[worst.first]?.toMinutes()}m) -- the book keeps trading while its accounting is frozen")
    }
}

/**
 * Fill rate split by how the market settled, per price band. A resting order is
 * adversely selected when it fills more often on markets that go against it:
 * the "penalty" is fill-rate-when-lost over fill-rate-when-won. Measured live in
 * the source repo at x1.15 to x1.49 by band (2026-09-13).
 */
object AdverseSelection {
    data class Band(val lo: Double, val hi: Double, val quotes: Int, val lostQuotes: Int, val fillWhenLost: Double?, val fillWhenWon: Double?) {
        val penalty: Double? get() = if (fillWhenLost != null && fillWhenWon != null && fillWhenWon > 0) fillWhenLost / fillWhenWon else null
        val label: String get() = "%.0f-%.0fc".format(lo * 100, hi * 100)
    }

    val LONGSHOT_BANDS = listOf(0.02 to 0.05, 0.05 to 0.10, 0.10 to 0.15, 0.15 to 0.25)
    val DEEP_BANDS = listOf(0.85 to 0.94, 0.94 to 0.97, 0.97 to 1.0)

    /** Rows with a fill verdict (the fill count is known and the order is finished) and a result. */
    fun table(rows: List<QuoteRow>, bands: List<Pair<Double, Double>>): List<Band> = bands.map { (lo, hi) ->
        val r = rows.filter { Outcomes.binary(it) && it.price >= lo && it.price < hi && it.filledContracts != null &&
            (it.resolvedAt != null || it.cancelledAt != null) && Outcomes.lost(it) != null }
        val lostRows = r.filter { Outcomes.lost(it) == true }; val wonRows = r.filter { Outcomes.lost(it) == false }
        fun rate(x: List<QuoteRow>) = if (x.isEmpty()) null else x.count { (it.filledContracts ?: 0.0) > 0 }.toDouble() / x.size
        Band(lo, hi, r.size, lostRows.size, rate(lostRows), rate(wonRows))
    }
}

/** Money locked by open and held positions over time, from the ledger. */
object Collateral {
    /** Locked collateral sampled every [step] from [from] to [to]. */
    fun series(rows: List<QuoteRow>, from: Instant, to: Instant, step: Duration = Duration.ofMinutes(30)): List<Pair<Instant, Double>> {
        val binary = rows.filter { Outcomes.binary(it) && (it.resolvedAt == null || it.resolvedAt.isAfter(from)) && it.placedAt.isBefore(to) }
        val out = ArrayList<Pair<Instant, Double>>()
        var t = from
        while (!t.isAfter(to)) { out += t to lockedAt(binary, t); t = t.plus(step) }
        return out
    }

    /**
     * Locked at [t]: the whole order from placement until it is withdrawn, then only
     * what filled, until settlement.
     */
    fun lockedAt(rows: List<QuoteRow>, t: Instant): Double = rows.sumOf { q ->
        if (t.isBefore(q.placedAt) || (q.resolvedAt != null && !t.isBefore(q.resolvedAt))) 0.0
        else {
            val n = if (q.cancelledAt != null && !t.isBefore(q.cancelledAt)) (q.filledContracts ?: 0.0) else q.contracts
            n * Outcomes.lockPerContract(q)
        }
    }
}

/** Ledger P&L against the exchange's own settlement figures, per market. */
@Serializable
data class PnlReconciliation(
    val at: String, val compared: Int, val mismatches: List<Mismatch>, val notAtExchange: List<String>, val error: String? = null,
) {
    @Serializable data class Mismatch(val ticker: String, val ledger: Double, val exchange: Double)
    val clean: Boolean get() = error == null && mismatches.isEmpty() && notAtExchange.isEmpty()
    val summary: String get() = when {
        error != null -> "Could not read Kalshi's settlements: $error"
        compared == 0 && notAtExchange.isEmpty() -> "Nothing to compare yet: no live Kalshi position has settled."
        clean -> "All $compared settled market(s) match Kalshi's settlement figures to the cent."
        else -> "${mismatches.size} of $compared market(s) differ from Kalshi" +
            (if (notAtExchange.isNotEmpty()) "; ${notAtExchange.size} settled in the app but not found at Kalshi" else "") +
            ". Kalshi's figure is the true one; a market you also traded by hand differs by design."
    }
}

object PnlReconcile {
    /**
     * Sums the app's realised P&L per Kalshi market and compares it with the
     * settlement payload (revenue - cost - fee). A truncated listing is an error,
     * never a "missing" verdict.
     */
    suspend fun kalshi(client: KalshiClient, liveRows: List<QuoteRow>, now: Instant, lookbackDays: Long = 30): PnlReconciliation {
        val mine = liveRows.filter { it.venue == "kalshi" && it.resolvedAt != null && it.hypothetical == false && it.pnl != null &&
            !it.resolvedAt.isBefore(now.minus(Duration.ofDays(lookbackDays))) }
        if (mine.isEmpty()) return PnlReconciliation(now.toString(), 0, emptyList(), emptyList())
        val exchange = try { client.settlementsByTicker() } catch (e: Exception) {
            return PnlReconciliation(now.toString(), 0, emptyList(), emptyList(), e.message ?: e.javaClass.simpleName)
        }
        val byTicker = mine.groupBy { it.ticker }.mapValues { (_, r) -> r.sumOf { it.pnl!! } }
        val mismatches = mutableListOf<PnlReconciliation.Mismatch>(); val missing = mutableListOf<String>(); var compared = 0
        for ((t, ledger) in byTicker) {
            val s = exchange[t]
            if (s == null) { missing += t; continue }
            compared++
            val ex = settlementPnl(s).value
            if (kotlin.math.abs(ex - ledger) > 0.005) mismatches += PnlReconciliation.Mismatch(t, ledger, ex)
        }
        return PnlReconciliation(now.toString(), compared, mismatches, missing)
    }
}

/**
 * The two guards that read the EXCHANGE and the app's own writers rather than
 * the ledger alone, adapted to [Check] so a strategy's health panel and the
 * engine's kill path can reach them.
 *
 * Both existed as pure functions before they existed as controls, which is the
 * failure `docs/hardening.md` is about: `reconcileKalshi` was called only from
 * the retired weather strategy, and [KillCriteria.staleWrites] was called by
 * nothing at all. A control nothing invokes is indistinguishable from no
 * control, and worse, because the docs read as though the ground is covered.
 *
 * Verdicts here keep one distinction the callers depend on: **"I could not
 * check" is INSUFFICIENT, never PASS and never FAIL.** A missing credential or
 * a failed read must not look like a clean account, and must not halt a book
 * that is working.
 */
object LiveGuards {
    /** The engine's balance writer, the one whose silence freezes every figure the app shows. */
    const val BALANCE_WRITER = "balance snapshots"

    /** How often [com.marketbot.core.engine.Engine.recordLiveBalances] is supposed to write. */
    val BALANCE_CADENCE: Duration = Duration.ofMinutes(15)

    /**
     * Is the balance writer still writing?
     *
     * `recordLiveBalances` wraps its fetch in `runCatching { }.getOrNull()?.let`,
     * so a venue that stops answering writes NOTHING and raises NOTHING: the
     * notification keeps showing the last balance it read, and `Narration`
     * computes a 24h change from frozen history. Nothing else notices, because
     * every other control reads the ledger, which a balance outage does not touch.
     */
    suspend fun balanceWriterFresh(ctx: StrategyContext, venue: Venue, now: Instant): Check {
        val read = runCatching { ctx.stores.equity.history(venue.name.lowercase(), Mode.LIVE, now.minus(Duration.ofDays(7))) }
        read.exceptionOrNull()?.let {
            return Check("Every writer still writing", Check.Verdict.INSUFFICIENT,
                "the equity store could not be read: ${it.message ?: it.javaClass.simpleName}")
        }
        return KillCriteria.staleWrites(
            newest = mapOf(BALANCE_WRITER to read.getOrNull()?.maxByOrNull { it.at }?.at),
            expected = mapOf(BALANCE_WRITER to BALANCE_CADENCE),
            now = now,
        )
    }

    /**
     * Is a Kalshi shard's fill limit tripped, i.e. is this book refusing its own
     * orders?
     *
     * Measured cost of not having this check: **23h 50m** of a dead live book,
     * 2026-11-20T15:20:07Z to 2026-11-21T15:10:37Z. Three 7-lots filled inside
     * six seconds (21 contracts against a limit of 20), Kalshi cancelled the
     * group and the trip latched in the state store, where only a manual Reset
     * clears it. One notification fired at trip time and then nothing: every
     * other control reads the ledger, and a book that places no orders writes
     * no rows, so silence looked exactly like a quiet market. At the ladder's
     * measured +$7.78/day that outage cost about a day's earnings -- larger
     * than any ordering or band lever in this repo.
     *
     * FAIL rather than KILL: a tripped limit is Kalshi holding the book, not
     * evidence the edge is gone. An unreadable state store is INSUFFICIENT,
     * never PASS -- "I could not check" is not "nothing is wrong".
     */
    /** Lots that must be able to fill together before the limit holds the book: the setting's own intent. */
    const val LOTS_PER_FILL_LIMIT = 4

    suspend fun fillLimitClear(client: KalshiClient, now: Instant, lot: Int? = null): Check {
        val name = "Kalshi is accepting this book's orders"
        val fl = client.fillLimit
            ?: return Check(name, Check.Verdict.PASS, "the fill limit is off, so no order group can hold the book")
        val tripped = runCatching { fl.trippedShards() }.getOrElse {
            return Check(name, Check.Verdict.INSUFFICIENT,
                "the trip state could not be read: ${it.message ?: it.javaClass.simpleName}")
        }
        if (tripped.isEmpty()) {
            // Nothing is tripped, so ask the question that matters next: can it trip on
            // ordinary trading? The limit counts CONTRACTS and the book quotes LOTS, so
            // the two drift apart whenever the lot moves and the limit does not -- which
            // is exactly what caused the 11-20 outage.
            val limit = runCatching { fl.configuredLimit() }.getOrNull()
            if (lot == null || limit == null || limit <= 0) {
                return Check(name, Check.Verdict.PASS, "no shard's fill limit is tripped")
            }
            val strikes = limit / lot
            return if (strikes < LOTS_PER_FILL_LIMIT) {
                Check(name, Check.Verdict.FAIL,
                    "nothing is tripped yet, but the limit of $limit contracts only allows $strikes strikes of $lot " +
                        "to fill together, under the $LOTS_PER_FILL_LIMIT this setting is meant to allow. This book " +
                        "fills several strikes off one price move, so it will trip on ordinary trading and then place " +
                        "nothing until it is reset by hand. Raise the limit to ${LOTS_PER_FILL_LIMIT * lot} or lower the lot.")
            } else {
                Check(name, Check.Verdict.PASS,
                    "no shard's fill limit is tripped, and $limit contracts leaves room for $strikes strikes of $lot to fill together")
            }
        }
        // joinToString's lambda is not a suspend context, so the reasons are read first.
        val parts = mutableListOf<String>()
        for ((shard, at) in tripped) {
            val held = Duration.between(at, now)
            val why = runCatching { fl.reason(shard) }.getOrNull()
            parts += "shard $shard tripped $at" +
                (if (held.toMinutes() >= 1) " (${held.toHours()}h ${held.toMinutesPart()}m ago)" else "") +
                (if (why == "kill") ", by the kill switch" else "")
        }
        val detail = parts.joinToString("; ")
        return Check(name, Check.Verdict.FAIL,
            "$detail. Kalshi cancelled the group's resting orders and refuses new ones, and the app refuses them " +
                "locally too, so this book is placing nothing until it is reset under Settings.")
    }

    /**
     * Does the exchange hold a resting order or position in this strategy's
     * markets that the app's ledger does not know?
     *
     * That means a second trader on the same account -- the old desktop run, a
     * restored backup, a second phone -- quoting the same markets. The desktop's
     * file lock only ever covered one machine. `inScope` is the strategy's own
     * `ownsMarket`, so one book never reports another book's orders.
     */
    suspend fun noOtherTrader(
        client: KalshiClient,
        inScope: (String) -> Boolean,
        liveRows: List<QuoteRow>,
    ): Check {
        val name = "No other trader on this account"
        if (!client.hasCredentials) {
            return Check(name, Check.Verdict.INSUFFICIENT, "no Kalshi credentials, so the account cannot be read")
        }
        val r = reconcileKalshi(
            client, inScope,
            knownOrderIds = liveRows.mapNotNull { it.orderId }.toSet(),
            knownTickers = liveRows.map { it.ticker }.toSet(),
        )
        return when {
            r.error != null -> Check(name, Check.Verdict.INSUFFICIENT, r.message)
            r.clean -> Check(name, Check.Verdict.PASS, r.message)
            else -> Check(name, Check.Verdict.FAIL, r.message)
        }
    }
}
