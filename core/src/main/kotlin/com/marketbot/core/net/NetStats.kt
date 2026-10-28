package com.marketbot.core.net

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Per-host request counts and bytes for today (UTC day), and how far the phone's
 * clock is from each server's. Kept in memory: it feeds the data-usage card and
 * the request signers, and a restart losing today's counters costs nothing.
 */
object NetStats {
    data class Usage(val requests: Long, val bytes: Long)

    private val usage = ConcurrentHashMap<String, Usage>()
    private var day: LocalDate = LocalDate.now(ZoneOffset.UTC)
    private val skew = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun record(host: String, bytes: Long, dateHeader: String?, sentAtMs: Long, receivedAtMs: Long) {
        val today = LocalDate.now(ZoneOffset.UTC)
        if (today != day) { usage.clear(); day = today }
        usage.merge(host, Usage(1, bytes)) { a, b -> Usage(a.requests + b.requests, a.bytes + b.bytes) }
        val server = dateHeader?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
        if (server != null) {
            // The Date header has one-second resolution and is stamped somewhere in the round trip:
            // compare it with the midpoint, plus half a second for the truncation.
            val local = (sentAtMs + receivedAtMs) / 2
            val sample = server + 500 - local
            skew.merge(host, sample) { old, new -> (old * 3 + new) / 4 }
        }
    }

    fun today(): Map<String, Usage> = usage.toMap()

    /** Server time minus phone time for [host], in ms, or null before the first response. */
    fun skewMs(host: String): Long? = skew[host]

    /**
     * Milliseconds to add to the phone clock when signing for [host]. Only applied
     * beyond 1.5 s, inside the Date header's precision it would add noise.
     */
    fun correctionMs(host: String): Long = skewMs(host)?.takeIf { abs(it) > 1_500 } ?: 0

    /** The worst skew seen across hosts, for the warning banner. */
    fun worstSkewMs(): Long? = skew.values.maxByOrNull { abs(it) }

    fun resetForTest() { usage.clear(); skew.clear() }

}
