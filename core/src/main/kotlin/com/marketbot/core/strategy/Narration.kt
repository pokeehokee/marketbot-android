package com.marketbot.core.strategy

import com.marketbot.core.store.QuoteRow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The plain-English layer of the board: every sentence on Home and in a strategy's Bottom line
 * is written here, from the ledger, and nowhere else.
 *
 * Two rules it keeps:
 *  - **No sentence outruns its data.** A judgement against the odds is offered only once the
 *    prices imply enough losses to compare with, and it says "too few losses to be sure" until
 *    [CONFIDENT_LOSSES] have actually happened -- the repo's standing rule that a trade dominated
 *    by rare losses is judged on its LOSSES, not its fills.
 *  - **Real and practice money are never added together.** Every function takes one book's rows;
 *    callers pass a single mode.
 */
object Narration {
    /** Losses before a verdict against the odds is stated without a caveat (the stage gates' bar). */
    const val CONFIDENT_LOSSES = 30
    /** Implied losses below which there is nothing to compare: "too early". */
    const val MIN_IMPLIED = 3.0

    enum class Tone { GOOD, NEUTRAL, WARN, BAD, QUIET }
    data class Line(val text: String, val tone: Tone)

    /** Settled bets with a real outcome: the only rows any sentence here is built from. */
    fun graded(rows: List<QuoteRow>): List<QuoteRow> =
        rows.filter { it.resolvedAt != null && it.hypothetical == false && it.pnl != null }

    // ------------------------------------------------------------------ against the odds

    data class Odds(val settled: Int, val losses: Int, val implied: Double) {
        val ratio: Double? get() = if (implied > 0) losses / implied else null
        val confident: Boolean get() = losses >= CONFIDENT_LOSSES
        /**
         * Enough to compare with the odds once EITHER count reaches [MIN_IMPLIED]. Gating on the
         * expected count alone would read 8 losses against 2.3 expected as "too early" -- hiding
         * exactly the book that is going wrong behind a neutral word.
         */
        val comparable: Boolean get() = settled > 0 && (implied >= MIN_IMPLIED || losses >= MIN_IMPLIED)
    }

    fun odds(rows: List<QuoteRow>): Odds {
        val g = graded(rows).filter { Outcomes.binary(it) && Outcomes.lost(it) != null }
        return Odds(g.size, g.count { Outcomes.lost(it) == true }, g.sumOf { Outcomes.impliedLoss(it) })
    }

    /** The short word for a table cell. Colour carries confidence: green only once it is earned. */
    fun vsOddsWord(o: Odds): Line {
        val r = o.ratio
        return when {
            o.settled == 0 -> Line("no bets yet", Tone.QUIET)
            !o.comparable || r == null -> Line("too early", Tone.QUIET)
            r <= 0.90 -> Line("beating odds", if (o.confident) Tone.GOOD else Tone.NEUTRAL)
            r < 1.10 -> Line("about even", Tone.NEUTRAL)
            else -> Line("behind odds", if (o.confident) Tone.BAD else Tone.WARN)
        }
    }

    /** One or two sentences explaining [vsOddsWord]. */
    fun vsOddsSentence(o: Odds): String {
        val r = o.ratio
        if (o.settled == 0) return "No bets have finished yet."
        if (!o.comparable || r == null) return "Too few bets have finished to compare with the odds."
        val judgement = when {
            r <= 0.90 -> "Fewer losses than the odds expected."
            r < 1.10 -> "About as many losses as the odds expected."
            else -> "More losses than the odds expected."
        }
        val counts = "${count(o.losses, "loss", "losses")}, where the prices expected about ${o.implied.roundToInt()}."
        return "$judgement ${counts.replaceFirstChar { it.uppercase() }}" + if (o.confident) "" else " Too few losses to be sure yet."
    }

    // ------------------------------------------------------------------ the daily brake

    /**
     * One strategy's daily brake, today. [lostToday] is the net realised loss as a positive number
     * (the brake reads net realised P&L); [typicalLoss] the average size of today's losing bets.
     */
    data class Brake(val strategy: String, val lostToday: Double, val limit: Double, val lossesToday: Int, val typicalLoss: Double?) {
        val fraction: Double get() = if (limit > 0) (lostToday / limit).coerceIn(0.0, 1.0) else 0.0
        val reached: Boolean get() = limit > 0 && lostToday >= limit
    }

    fun brake(strategy: String, rows: List<QuoteRow>, limit: Double, dayStart: Instant): Brake {
        val today = graded(rows).filter { !it.resolvedAt!!.isBefore(dayStart) }
        val losing = today.filter { it.pnl!! < 0 }
        val net = today.sumOf { it.pnl!! }
        return Brake(strategy, if (net < 0) -net else 0.0, limit, losing.size, losing.map { -it.pnl!! }.takeIf { it.isNotEmpty() }?.average())
    }

    /** The tile caption: how far from a full stop, in losses the person has already seen today. */
    fun brakeLine(b: Brake): String {
        if (b.limit <= 0) return "No daily limit is set."
        if (b.reached) return "Limit reached. No new bets until midnight."
        if (b.lostToday <= 0) return "No losses today. It stops itself at ${money(b.limit)}."
        val room = b.limit - b.lostToday
        val typical = b.typicalLoss?.takeIf { it > 0 } ?: return "${money(room)} of room left today."
        val k = ceil(room / typical - 1e-9).toInt().coerceAtLeast(1)
        return if (k == 1) "One more loss like today's would reach it."
        else "${word(k).replaceFirstChar { it.uppercase() }} more losses like today's and it stops itself."
    }

    // ------------------------------------------------------------------ headline

    /**
     * The one sentence at the top of Home. [liveWeek] is null when no real money is trading, and
     * then the sentence says so and talks about practice by name -- never one number for both.
     */
    fun headline(liveWeek: Double?, practiceWeek: Double, brake: Brake?): String {
        if (liveWeek == null) {
            return if (abs(practiceWeek) < 0.005) "No real money is trading, and practice has nothing settled this week."
            else "No real money is trading. Practice strategies are ${upDown(practiceWeek)} this week."
        }
        val first = when {
            liveWeek >= 0.005 -> "Up ${money(liveWeek)} this week with real money."
            liveWeek <= -0.005 -> "Down ${money(-liveWeek)} this week with real money."
            else -> "Flat this week with real money."
        }
        val second = brake?.let {
            when {
                it.reached -> "The daily limit is reached: no new bets until midnight."
                it.lossesToday == 0 -> "No losses today."
                it.fraction >= 0.6 -> "${count(it.lossesToday, "loss", "losses").replaceFirstChar { c -> c.uppercase() }} today; close to the daily limit."
                else -> "${count(it.lossesToday, "loss", "losses").replaceFirstChar { c -> c.uppercase() }} today, still well inside the daily limit."
            }
        }
        return if (second == null) first else "$first $second"
    }

    // ------------------------------------------------------------------ days

    /** Net P&L per local day over the last [days] days, oldest first; days with nothing settled are null. */
    fun daily(rows: List<QuoteRow>, now: Instant, zone: ZoneId, days: Int): List<Pair<LocalDate, Double?>> {
        val today = now.atZone(zone).toLocalDate()
        val byDay = graded(rows).groupBy { it.resolvedAt!!.atZone(zone).toLocalDate() }
        return (days - 1 downTo 0).map { back -> today.minusDays(back.toLong()).let { d -> d to byDay[d]?.sumOf { it.pnl!! } } }
    }

    /** The 7-day tile caption: how many days were up, and which was worst. */
    fun weekLine(rows: List<QuoteRow>, now: Instant, zone: ZoneId): String {
        val d = daily(rows, now, zone, 7).filter { it.second != null }
        if (d.isEmpty()) return "Nothing settled in the last 7 days."
        val up = d.count { it.second!! > 0 }
        val worst = d.minBy { it.second!! }
        if (worst.second!! >= 0) return "Up on ${if (up == d.size) "every" else "$up of ${d.size}"} day${if (d.size == 1) "" else "s"} with results."
        val day = worst.first.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return "Up $up of ${d.size} days; the worst was $day (${money(worst.second!!)})."
    }

    /** The 14-day strip's caption: what a red day was made of. */
    fun redDaysLine(rows: List<QuoteRow>, now: Instant, zone: ZoneId): String {
        val g = graded(rows)
        val red = daily(g, now, zone, 14).filter { (it.second ?: 0.0) < 0 }.map { it.first }
        if (red.isEmpty()) return "No losing days in two weeks."
        val lossesOn = g.filter { it.pnl!! < 0 }.groupBy { it.resolvedAt!!.atZone(zone).toLocalDate() }.mapValues { it.value.size }
        val single = red.count { (lossesOn[it] ?: 0) == 1 }
        return when {
            single == red.size && red.size == 1 -> "The one red day was a single loss: many small wins, the odd bigger loss."
            single == red.size -> "Each red day was a single loss: many small wins, the odd bigger loss."
            else -> "${word(red.size).replaceFirstChar { it.uppercase() }} red days; ${word(single)} of them a single loss."
        }
    }

    // ------------------------------------------------------------------ one strategy

    /** "A loss costs about what 15 wins make", from the average losing and winning bet. */
    fun lossCostLine(rows: List<QuoteRow>): String? {
        val g = graded(rows)
        val wins = g.filter { it.pnl!! > 0 }.map { it.pnl!! }
        val losses = g.filter { it.pnl!! < 0 }.map { -it.pnl!! }
        if (wins.isEmpty() || losses.isEmpty()) return null
        val n = (losses.average() / wins.average()).roundToInt()
        return if (n <= 1) "A loss costs about what a win makes." else "A loss costs about what $n wins make."
    }

    /** P&L per contract over graded rows, or null with none. */
    fun perContract(rows: List<QuoteRow>): Double? {
        val g = graded(rows).mapNotNull { q -> (q.filledContracts ?: q.contracts).takeIf { it > 0 }?.let { q.pnl!! to it } }
        val n = g.sumOf { it.second }
        return if (n > 0) g.sumOf { it.first } / n else null
    }

    /**
     * The Bottom line at the top of a strategy: its mode, how it stands against the odds, and
     * whether each bet has recently been earning much less than its overall figure.
     */
    fun bottomLine(mode: Mode, rows: List<QuoteRow>, now: Instant): Line {
        val o = odds(rows)
        if (o.settled == 0) return Line(when (mode) {
            Mode.LIVE -> "Trading real money; no bets have finished yet."
            Mode.PAPER -> "Practising; no bets have finished yet."
            Mode.OFF -> "Off, with no record yet."
        }, Tone.QUIET)
        val who = when (mode) { Mode.LIVE -> "Trading real money"; Mode.PAPER -> "Practising"; Mode.OFF -> "Off" }
        val w = vsOddsWord(o)
        val stand = when (w.text) {
            "beating odds" -> ", and beating the odds"
            "about even" -> ", about level with the odds"
            "behind odds" -> ", and losing more often than the odds say"
            else -> ", too early to judge"
        }
        val all = perContract(rows)
        val recent = perContract(rows.filter { it.resolvedAt != null && !it.resolvedAt.isBefore(now.minus(Duration.ofDays(7))) })
        val shrinking = all != null && recent != null && all > 0 && recent < all * 0.5 &&
            graded(rows).count { it.resolvedAt!!.isAfter(now.minus(Duration.ofDays(7))) } >= 10
        val sb = StringBuilder("$who$stand.")
        if (shrinking) sb.append(" Each bet has earned less lately: ${cents(recent!!)} a contract this week against ${cents(all!!)} overall.")
        if (o.comparable && !o.confident) sb.append(" Too few losses to be sure yet.")
        val tone = when {
            w.tone == Tone.BAD -> Tone.BAD
            shrinking || w.tone == Tone.WARN -> Tone.WARN
            w.tone == Tone.GOOD -> Tone.GOOD
            else -> Tone.NEUTRAL
        }
        return Line(sb.toString(), tone)
    }

    // ------------------------------------------------------------------ words

    private val WORDS = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
    fun word(n: Int) = WORDS.getOrNull(n) ?: n.toString()
    fun count(n: Int, one: String, many: String) = "${word(n)} ${if (n == 1) one else many}"
    private fun upDown(v: Double) = if (v >= 0) "up ${money(v)}" else "down ${money(-v)}"
}
