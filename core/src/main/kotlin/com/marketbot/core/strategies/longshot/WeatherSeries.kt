package com.marketbot.core.strategies.longshot

/**
 * Kalshi's 40 daily temperature series (20 cities x HIGH/LOW), exactly as
 * weatherbot's human-verified `BASELINE_KALSHI_SERIES`. The names are
 * irregular (HIGH may or may not carry a T, NYC is NY for HIGH and NYC for
 * LOW) and cannot be derived, which is why they are a table.
 */
object WeatherSeries {
    val BY_CITY: Map<String, Map<String, String>> = mapOf(
        "ATL" to mapOf("HIGH" to "KXHIGHTATL", "LOW" to "KXLOWTATL"),
        "AUS" to mapOf("HIGH" to "KXHIGHAUS", "LOW" to "KXLOWTAUS"),
        "BOS" to mapOf("HIGH" to "KXHIGHTBOS", "LOW" to "KXLOWTBOS"),
        "CHI" to mapOf("HIGH" to "KXHIGHCHI", "LOW" to "KXLOWTCHI"),
        "DAL" to mapOf("HIGH" to "KXHIGHTDAL", "LOW" to "KXLOWTDAL"),
        "DC" to mapOf("HIGH" to "KXHIGHTDC", "LOW" to "KXLOWTDC"),
        "DEN" to mapOf("HIGH" to "KXHIGHDEN", "LOW" to "KXLOWTDEN"),
        "HOU" to mapOf("HIGH" to "KXHIGHTHOU", "LOW" to "KXLOWTHOU"),
        "LAX" to mapOf("HIGH" to "KXHIGHLAX", "LOW" to "KXLOWTLAX"),
        "LV" to mapOf("HIGH" to "KXHIGHTLV", "LOW" to "KXLOWTLV"),
        "MIA" to mapOf("HIGH" to "KXHIGHMIA", "LOW" to "KXLOWTMIA"),
        "MIN" to mapOf("HIGH" to "KXHIGHTMIN", "LOW" to "KXLOWTMIN"),
        "NOLA" to mapOf("HIGH" to "KXHIGHTNOLA", "LOW" to "KXLOWTNOLA"),
        "NYC" to mapOf("HIGH" to "KXHIGHNY", "LOW" to "KXLOWTNYC"),
        "OKC" to mapOf("HIGH" to "KXHIGHTOKC", "LOW" to "KXLOWTOKC"),
        "PHIL" to mapOf("HIGH" to "KXHIGHPHIL", "LOW" to "KXLOWTPHIL"),
        "PHX" to mapOf("HIGH" to "KXHIGHTPHX", "LOW" to "KXLOWTPHX"),
        "SAT" to mapOf("HIGH" to "KXHIGHTSATX", "LOW" to "KXLOWTSATX"),
        "SEA" to mapOf("HIGH" to "KXHIGHTSEA", "LOW" to "KXLOWTSEA"),
        "SFO" to mapOf("HIGH" to "KXHIGHTSFO", "LOW" to "KXLOWTSFO"),
    )

    /** Old names, kept so stored tickers still resolve. */
    private val RETIRED = mapOf("KXHIGHTCHI" to ("CHI" to "HIGH"))

    val SERIES_TO_CITY: Map<String, Pair<String, String>> =
        BY_CITY.flatMap { (city, kinds) -> kinds.map { (kind, s) -> s to (city to kind) } }.toMap()

    val ALL: List<String> = SERIES_TO_CITY.keys.sorted()

    private fun entry(ticker: String?): Pair<String, String>? {
        val series = ticker?.trim()?.uppercase()?.substringBefore('-')?.takeIf { it.isNotEmpty() } ?: return null
        return SERIES_TO_CITY[series] ?: RETIRED[series]
    }

    fun kind(ticker: String?): String? = entry(ticker)?.second
    fun city(ticker: String?): String? = entry(ticker)?.first
}
