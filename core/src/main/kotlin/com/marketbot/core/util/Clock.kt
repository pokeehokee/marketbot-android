package com.marketbot.core.util

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** Injectable time, so every time-dependent rule can be tested at a fixed instant. */
fun interface Clock {
    fun now(): Instant

    companion object {
        val SYSTEM = Clock { Instant.now() }
        fun fixed(at: Instant) = Clock { at }
    }
}

class MutableClock(var at: Instant) : Clock {
    override fun now(): Instant = at
    fun advanceSeconds(s: Long) { at = at.plusSeconds(s) }
}

val ET: ZoneId = ZoneId.of("America/New_York")

/** ISO-8601 with `Z` or an offset; null when unparseable. Never throws. */
fun parseInstant(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    val v = raw.trim().replace(' ', 'T')
    return try {
        Instant.parse(v)
    } catch (_: DateTimeParseException) {
        try {
            val fixed = if (Regex("[+-]\\d{2}$").containsMatchIn(v)) "$v:00" else v
            OffsetDateTime.parse(fixed).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
