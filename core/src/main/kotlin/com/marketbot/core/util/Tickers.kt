package com.marketbot.core.util

import com.marketbot.core.strategies.longshot.WeatherSeries
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Exchange tickers in words, for people: `KXHIGHNY-26OCT28-B70.5` reads
 * "NYC high 70-71°F, Wed Oct 28". The ticker itself stays one tap away;
 * anything not recognised is shown as it is rather than guessed at.
 */
object Tickers {
    val ET: ZoneId = ZoneId.of("America/New_York")

    private val CITY = mapOf(
        "ATL" to "Atlanta", "AUS" to "Austin", "BOS" to "Boston", "CHI" to "Chicago", "DAL" to "Dallas", "DC" to "Washington DC",
        "DEN" to "Denver", "HOU" to "Houston", "LAX" to "Los Angeles", "LV" to "Las Vegas", "MIA" to "Miami", "MIN" to "Minneapolis",
        "NOLA" to "New Orleans", "NYC" to "NYC", "OKC" to "Oklahoma City", "PHIL" to "Philadelphia", "PHX" to "Phoenix",
        "SAT" to "San Antonio", "SEA" to "Seattle", "SFO" to "San Francisco",
    )
    private val MON = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    private val DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

    /** `26OCT28` -> 2026-10-28, or null. */
    fun date(code: String): LocalDate? {
        if (code.length < 7) return null
        val y = code.take(2).toIntOrNull() ?: return null
        val m = MON.indexOf(code.substring(2, 5).uppercase()).takeIf { it >= 0 } ?: return null
        val d = code.substring(5, 7).toIntOrNull() ?: return null
        return runCatching { LocalDate.of(2000 + y, m + 1, d) }.getOrNull()
    }

    private fun num(x: Double) = if (x == Math.floor(x)) "%,.0f".format(Locale.US, x) else "%,.1f".format(Locale.US, x)

    fun describe(ticker: String): String {
        val parts = ticker.split('-')
        WeatherSeries.city(ticker)?.let { city ->
            val kind = if (WeatherSeries.kind(ticker) == "LOW") "low" else "high"
            val day = parts.getOrNull(1)?.let(::date)?.let { DAY.format(it) }
            val strike = parts.getOrNull(2)
            val what = when {
                strike == null -> null
                strike.startsWith("B") -> strike.drop(1).toDoubleOrNull()?.let { m -> "${num(m - 0.5)}-${num(m + 0.5)}°F" }
                strike.startsWith("T") -> strike.drop(1).toDoubleOrNull()?.let { "tail at ${num(it)}°F" }
                else -> null
            }
            return listOfNotNull("${CITY[city] ?: city} $kind" + (what?.let { " $it" } ?: ""), day).joinToString(", ")
        }
        if (parts.size == 3 && parts[0] == "KXBTCD" && parts[2].startsWith("T")) {
            val code = parts[1]                                  // 26OCT2717: date then the ET hour
            val d = date(code.take(7)); val h = code.drop(7).toIntOrNull()
            val strike = parts[2].drop(1).toDoubleOrNull()
            if (d != null && h != null && strike != null) {
                val at = LocalDateTime.of(d, java.time.LocalTime.of(h % 24, 0))
                val hour = DateTimeFormatter.ofPattern("h a", Locale.US).format(at)
                return "BTC above \$${num(strike)} at $hour ET, ${DateTimeFormatter.ofPattern("MMM d", Locale.US).format(d)}"
            }
        }
        return ticker
    }
}
