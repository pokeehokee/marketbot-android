package com.marketbot.core.provenance

import java.time.Instant

/**
 * Where a number came from. Every value the app shows, logs, sizes with or
 * risk-checks against carries one of these, so a figure read off an exchange
 * can never be confused with one the app worked out itself.
 *
 * The rule, in order: read it from the venue's API; only when the API does not
 * provide it, compute it from API inputs (DERIVED) and say why.
 */
enum class Source(val label: String, val authoritative: Boolean) {
    /** Read verbatim from a Kalshi API response field. */
    KALSHI_API("Kalshi API", true),

    /** Read verbatim from a Polymarket US API response field. */
    POLYMARKET_API("Polymarket API", true),

    /** Read from a public spot venue (Coinbase, Kraken, Bitstamp, Gemini). */
    VENUE_API("Spot venue API", true),

    /** Computed by the app from API inputs because the API does not provide it. */
    DERIVED("Calculated", false),

    /** Produced by the paper-trading simulator. Never real money. */
    PAPER_MODEL("Paper simulation", false),

    /** A setting the user chose, or a constant from the strategy's measurements. */
    CONFIG("Setting", false),
}

/**
 * A value plus its provenance.
 *
 * @param origin the endpoint and field it was read from (`GET /portfolio/balance#balance_dollars`)
 *   or the formula used (`cash + portfolio_value`).
 * @param note why a fallback was needed, when one was.
 */
data class Sourced<out T>(
    val value: T,
    val source: Source,
    val origin: String,
    val note: String? = null,
    val at: Instant = Instant.now(),
) {
    fun <R> map(origin: String = this.origin, f: (T) -> R): Sourced<R> =
        Sourced(f(value), source, origin, note, at)

    /** True when the value is the venue's own word rather than the app's arithmetic. */
    val fromApi: Boolean get() = source == Source.KALSHI_API || source == Source.POLYMARKET_API || source == Source.VENUE_API

    companion object {
        fun <T> kalshi(value: T, origin: String, at: Instant = Instant.now()) = Sourced(value, Source.KALSHI_API, origin, at = at)
        fun <T> polymarket(value: T, origin: String, at: Instant = Instant.now()) = Sourced(value, Source.POLYMARKET_API, origin, at = at)
        fun <T> derived(value: T, formula: String, why: String, at: Instant = Instant.now()) =
            Sourced(value, Source.DERIVED, formula, why, at)
        fun <T> config(value: T, name: String) = Sourced(value, Source.CONFIG, name)
        fun <T> paper(value: T, model: String, at: Instant = Instant.now()) = Sourced(value, Source.PAPER_MODEL, model, at = at)
    }
}

/** The result of looking for a value that may be missing. Missing is never zero. */
sealed interface Lookup<out T> {
    data class Found<T>(val value: Sourced<T>) : Lookup<T>
    data class Missing(val what: String, val reason: String) : Lookup<Nothing>
}

class ValueUnavailable(val what: String, reason: String) : RuntimeException("$what unavailable: $reason")
