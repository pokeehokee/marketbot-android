package com.marketbot.app

import android.content.Context

/**
 * How often this phone lets the engine run in the background (DontKillMyApp's
 * benchmark, on the app's own service instead of a test app). While a run is on,
 * every engine-loop tick and alarm marks the minute it ran in; the result is the
 * share of minutes with any sign of life and the longest silence. A vendor that
 * kills or freezes background apps shows up as long gaps.
 */
object Benchmark {
    private const val PREFS = "benchmark"
    data class Result(val startedAt: Long, val endsAt: Long, val minutes: Int, val alive: Int, val longestGapMin: Int, val gaps: List<Pair<Long, Int>>, val running: Boolean) {
        val share: Double get() = if (minutes == 0) 0.0 else alive.toDouble() / minutes
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun start(ctx: Context, hours: Int, now: Long = System.currentTimeMillis()) {
        prefs(ctx).edit().clear().putLong("start", now).putLong("end", now + hours * 3_600_000L).putString("ticks", "0").apply()
    }

    fun stop(ctx: Context, now: Long = System.currentTimeMillis()) {
        val p = prefs(ctx); if (p.getLong("start", 0) == 0L) return
        if (now < p.getLong("end", 0)) p.edit().putLong("end", now).apply()
    }

    fun running(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs(ctx); val s = p.getLong("start", 0); return s != 0L && now in s until p.getLong("end", 0)
    }

    /** Called from the engine loop and the alarm receiver. Cheap: one write per new minute at most. */
    fun beat(ctx: Context, now: Long = System.currentTimeMillis()) {
        val p = prefs(ctx); val s = p.getLong("start", 0)
        if (s == 0L || now < s || now >= p.getLong("end", 0)) return
        val m = ((now - s) / 60_000L).toInt()
        val ticks = p.getString("ticks", "").orEmpty()
        if (ticks.substringAfterLast(',') == m.toString()) return
        p.edit().putString("ticks", if (ticks.isEmpty()) "$m" else "$ticks,$m").apply()
    }

    fun result(ctx: Context, now: Long = System.currentTimeMillis()): Result? {
        val p = prefs(ctx); val s = p.getLong("start", 0); if (s == 0L) return null
        val end = p.getLong("end", 0); val upTo = minOf(now, end)
        val minutes = ((upTo - s) / 60_000L).toInt().coerceAtLeast(0)
        val seen = p.getString("ticks", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }.filter { it < minutes }.toSortedSet()
        // Silences of more than five minutes, including any still running at the end.
        val marks = listOf(-1) + seen.toList() + listOf(minutes)
        val gaps = marks.zipWithNext().map { (a, b) -> a + 1 to (b - a - 1) }.filter { it.second > 5 }
            .map { (from, len) -> (s + from * 60_000L) to len }
        return Result(s, end, minutes, seen.size, gaps.maxOfOrNull { it.second } ?: 0, gaps, now < end)
    }
}
