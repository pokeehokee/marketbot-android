package com.marketbot.core.strategy

/**
 * The live portfolio as one point per hour: at the end of each hour, the sum over venues of each
 * venue's latest recorded value at or before that moment. A venue that has not been recorded yet
 * is left out until it is (never counted as zero); an hour with no new reading carries the last
 * one forward, and [Hourly.recordedHours] says how many hours actually had a reading.
 */
object HourlyEquity {
    const val HOUR_MS = 3_600_000L

    /** One reading: when, which venue, what it held. */
    data class Reading(val at: Long, val venue: String, val value: Double)

    data class Hourly(
        /** (end of hour in epoch ms, total), oldest first; the last point is [to] itself. */
        val points: List<Pair<Long, Double>>,
        /** Hours in the window that had at least one reading of their own. */
        val recordedHours: Int,
        /** Hours in the window. */
        val hours: Int,
    )

    fun of(readings: List<Reading>, from: Long, to: Long): Hourly {
        val sorted = readings.sortedBy { it.at }
        val ends = buildList {
            var t = (from / HOUR_MS + 1) * HOUR_MS
            while (t <= to) { add(t); t += HOUR_MS }
            if (isEmpty() || last() < to) add(to)
        }
        val latest = HashMap<String, Double>()
        val out = ArrayList<Pair<Long, Double>>(ends.size)
        var i = 0
        for (end in ends) {
            while (i < sorted.size && sorted[i].at <= end) { latest[sorted[i].venue] = sorted[i].value; i++ }
            if (latest.isNotEmpty()) out += end to latest.values.sum()
        }
        val recorded = sorted.filter { it.at in from..to }.map { it.at / HOUR_MS }.distinct().size
        val hours = ((to - from + HOUR_MS - 1) / HOUR_MS).toInt()
        return Hourly(out, recorded, hours)
    }
}
