package com.marketbot.core.net

import java.time.Instant
import java.util.ArrayDeque

/**
 * An on-demand record of API traffic (after poly-maker's event journal), for
 * working out afterwards what the exchange said. Off unless started, for at most
 * the chosen time, and at most [MAX] entries in memory (oldest dropped). It never
 * keeps request headers, so the signatures and keys are never in it; bodies and
 * responses are cut to [EXCERPT] characters.
 */
object ApiJournal {
    const val MAX = 1_000
    const val EXCERPT = 2_000

    data class Entry(val at: Instant, val method: String, val url: String, val status: Int?, val ms: Long, val request: String?, val response: String?, val error: String?)

    private val entries = ArrayDeque<Entry>()
    @Volatile var until: Instant? = null; private set

    val recording: Boolean get() = until?.isAfter(Instant.now()) == true

    @Synchronized fun start(minutes: Long, now: Instant = Instant.now()) { entries.clear(); until = now.plusSeconds(minutes * 60) }
    @Synchronized fun stop() { until = null }
    @Synchronized fun size() = entries.size

    @Synchronized fun record(e: Entry) {
        if (!recording) return
        entries.addLast(e.copy(request = e.request?.take(EXCERPT), response = e.response?.take(EXCERPT)))
        while (entries.size > MAX) entries.removeFirst()
    }

    /** One JSON object per line, oldest first. */
    @Synchronized fun export(): String = buildString {
        for (e in entries) {
            val o = kotlinx.serialization.json.buildJsonObject {
                put("at", kotlinx.serialization.json.JsonPrimitive(e.at.toString()))
                put("method", kotlinx.serialization.json.JsonPrimitive(e.method))
                put("url", kotlinx.serialization.json.JsonPrimitive(e.url))
                e.status?.let { put("status", kotlinx.serialization.json.JsonPrimitive(it)) }
                put("ms", kotlinx.serialization.json.JsonPrimitive(e.ms))
                e.request?.let { put("request", kotlinx.serialization.json.JsonPrimitive(it)) }
                e.response?.let { put("response", kotlinx.serialization.json.JsonPrimitive(it)) }
                e.error?.let { put("error", kotlinx.serialization.json.JsonPrimitive(it)) }
            }
            append(o.toString()).append('\n')
        }
    }

    init { com.marketbot.core.util.Diagnostics.track(this, "API recording entries") { it.size() } }
}
