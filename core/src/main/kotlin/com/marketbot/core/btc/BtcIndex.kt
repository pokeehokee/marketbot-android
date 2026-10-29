package com.marketbot.core.btc

import com.marketbot.core.net.JsonHttp
import com.marketbot.core.net.RateLimiter
import com.marketbot.core.provenance.Source
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.util.arr
import com.marketbot.core.util.num
import com.marketbot.core.util.obj
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** A venue quote fetch: returns the last BTC/USD price. */
fun interface VenueQuote { suspend fun last(): Double }

const val STALE_S = 5.0
const val MIN_SOURCES = 2
const val VOL_WINDOW_S = 900.0
const val MIN_VOL_SAMPLES = 300
const val SIGMA_STEP_S = 10.0
const val CANDLE_REFRESH_S = 60.0

/** Median of venue prices fresher than [staleS], or null when fewer than [minSources] are fresh. Port of `median_index`. */
fun medianIndex(quotes: Map<String, Pair<Double, Double>>, now: Double, staleS: Double = STALE_S, minSources: Int = MIN_SOURCES): Double? {
    val fresh = quotes.values.filter { (ts, p) -> p > 0 && now - ts <= staleS }.map { it.second }.sorted()
    if (fresh.size < minSources) return null
    val n = fresh.size
    return if (n % 2 == 1) fresh[n / 2] else (fresh[n / 2 - 1] + fresh[n / 2]) / 2.0
}

/**
 * Per-sqrt-second vol of log returns over the trailing window, thinned to one
 * point per [stepS]; returns scaled by actual spacing so a dropped second does
 * not inflate it. Port of `realised_sigma`.
 */
fun realisedSigma(samples: List<Pair<Double, Double>>, windowS: Double = VOL_WINDOW_S, minSamples: Int = MIN_VOL_SAMPLES, stepS: Double = 1.0): Double? {
    if (samples.isEmpty()) return null
    val end = samples.last().first
    var pts = samples.filter { (t, p) -> end - t <= windowS && p > 0 }
    if (pts.size < minSamples) return null
    if (stepS > 1) {
        val thin = mutableListOf<Pair<Double, Double>>(); var last: Double? = null
        for ((t, p) in pts) if (last == null || t - last >= stepS - 0.5) { thin += t to p; last = t }
        pts = thin
    }
    var varSum = 0.0; var dtSum = 0.0
    for (i in 0 until pts.size - 1) {
        val (t0, p0) = pts[i]; val (t1, p1) = pts[i + 1]
        val dt = t1 - t0
        if (dt <= 0 || dt > 3 * max(stepS, 1.0) + 5) continue
        val r = ln(p1 / p0)
        varSum += r * r; dtSum += dt
    }
    if (dtSum <= 0) return null
    return sqrt(varSum / dtSum)
}

/** Per-sqrt-second vol from 1-minute candles `[time, low, high, open, close, vol]`. Port of `candle_sigma`. */
fun candleSigma(candles: List<List<Double>>, nReturns: Int = 15): Double? {
    val rows = candles.filter { it.size >= 5 && it[4] != 0.0 }
        .sortedWith(compareBy<List<Double>>({ it[0] }, { it[1] }, { it[2] }, { it[3] }, { it[4] }))
        .takeLast(nReturns + 1)
    if (rows.size < nReturns + 1) return null
    val rets = rows.zipWithNext().filter { (a, b) -> b[0] - a[0] == 60.0 }.map { (a, b) -> ln(b[4] / a[4]) }
    if (rets.size < nReturns - 2) return null
    return sqrt(rets.sumOf { it * it } / rets.size / 60.0)
}

/** The four venues the original index used. All public, no keys. */
object Venues {
    private val http = mapOf(
        "coinbase" to JsonHttp("https://api.exchange.coinbase.com", limiter = RateLimiter(3.0)),
        "kraken" to JsonHttp("https://api.kraken.com", limiter = RateLimiter(3.0)),
        "bitstamp" to JsonHttp("https://www.bitstamp.net", limiter = RateLimiter(3.0)),
        "gemini" to JsonHttp("https://api.gemini.com", limiter = RateLimiter(3.0)),
    )

    fun all(): Map<String, VenueQuote> = mapOf(
        "coinbase" to VenueQuote { http.getValue("coinbase").get("/products/BTC-USD/ticker").num("price") ?: error("no price") },
        "kraken" to VenueQuote {
            val r = http.getValue("kraken").get("/0/public/Ticker", listOf("pair" to "XBTUSD")).obj("result") ?: error("no result")
            val c = (r.values.first() as kotlinx.serialization.json.JsonObject).arr("c") ?: error("no c")
            (c[0] as JsonPrimitive).content.toDouble()
        },
        "bitstamp" to VenueQuote { http.getValue("bitstamp").get("/api/v2/ticker/btcusd/").num("last") ?: error("no last") },
        "gemini" to VenueQuote { http.getValue("gemini").get("/v1/pubticker/btcusd").num("last") ?: error("no last") },
    )

    suspend fun coinbaseMinuteCandles(): List<List<Double>> {
        val d = http.getValue("coinbase").get("/products/BTC-USD/candles", listOf("granularity" to "60"))
        val arr = d["data"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { row -> (row as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } }
    }
}

/**
 * A 1 Hz BTC/USD index (median of four spot venues) and its volatility.
 * Sigma is the larger of index vol (sampled every 10 s) and Coinbase 1-minute
 * candle vol over 15 minutes, and BOTH are required: the 1 s index estimate
 * was measured to run at about half the candle figure, and underestimating
 * sigma is the one error deep-ITM cannot survive.
 *
 * The contract settles on CF Benchmarks' BRTI (Kalshi and Polymarket alike),
 * which is not public. This index is a proxy, labelled as such.
 */
class BtcIndex(
    private val venues: Map<String, VenueQuote> = Venues.all(),
    private val candles: suspend () -> List<List<Double>> = { Venues.coinbaseMinuteCandles() },
    private val nowS: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    private val quotes = mutableMapOf<String, Pair<Double, Double>>()
    private val samples = ArrayDeque<Pair<Double, Double>>()
    init { com.marketbot.core.util.Diagnostics.track(this, "BTC index samples") { synchronized(it.lock) { it.samples.size } } }
    private var candleSig: Double? = null
    private var candleAt = 0.0
    val errors = mutableMapOf<String, Int>()
    private var job: Job? = null
    private val lock = Any()

    suspend fun pollOnce(): Double? {
        val now = nowS()
        coroutineScope {
            venues.map { (name, v) -> async { name to runCatching { v.last() } } }.awaitAll()
        }.forEach { (name, r) ->
            r.onSuccess { p -> synchronized(lock) { quotes[name] = now to p } }
                .onFailure { synchronized(lock) { errors[name] = (errors[name] ?: 0) + 1 } }
        }
        if (now - candleAt >= CANDLE_REFRESH_S) {
            runCatching { candleSigma(candles()) }
                .onSuccess { s -> synchronized(lock) { candleSig = s; candleAt = now } }
                .onFailure {
                    synchronized(lock) {
                        errors["candles"] = (errors["candles"] ?: 0) + 1
                        if (now - candleAt > 5 * CANDLE_REFRESH_S) candleSig = null
                    }
                }
        }
        val px = price(now)
        if (px != null) synchronized(lock) {
            samples.addLast(now to px)
            while (samples.size > VOL_WINDOW_S.toInt() + 120) samples.removeFirst()
        }
        return px
    }

    fun price(now: Double = nowS()): Double? = synchronized(lock) { medianIndex(quotes.toMap(), now) }

    fun sourcedPrice(now: Double = nowS()): Sourced<Double>? = synchronized(lock) {
        val fresh = quotes.filter { (_, v) -> v.second > 0 && now - v.first <= STALE_S }.keys.sorted()
        medianIndex(quotes.toMap(), now)?.let {
            Sourced(it, Source.DERIVED, "median of ${fresh.joinToString("/")} last prices",
                "settlement index (CF BRTI) is not public; spot-venue median is the proxy", Instant.ofEpochMilli((now * 1000).toLong()))
        }
    }

    fun sigma(): Double? = synchronized(lock) {
        val fine = realisedSigma(samples.toList(), stepS = SIGMA_STEP_S, minSamples = MIN_VOL_SAMPLES)
        val coarse = candleSig
        if (fine == null || coarse == null) null else max(fine, coarse)
    }

    fun age(now: Double = nowS()): Double = synchronized(lock) { samples.lastOrNull()?.let { now - it.first } ?: Double.POSITIVE_INFINITY }

    /** Warm-up progress: share of the 5-minute minimum of index samples, and whether candle volatility is in. */
    fun warmup(): Pair<Double, Boolean> = synchronized(lock) {
        val end = samples.lastOrNull()?.first ?: return 0.0 to (candleSig != null)
        val n = samples.count { end - it.first <= VOL_WINDOW_S }
        (n.toDouble() / MIN_VOL_SAMPLES).coerceAtMost(1.0) to (candleSig != null)
    }

    fun freshVenues(now: Double = nowS()): Int = synchronized(lock) { quotes.values.count { now - it.first <= STALE_S } }

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            var dead = 0
            while (isActive) {
                val t0 = System.currentTimeMillis()
                val ok = runCatching { pollOnce() }.getOrNull() != null || freshVenues() > 0
                // Every venue failing means no network, not four broken exchanges: back off to 30 s instead of 4 requests a second.
                dead = if (ok) 0 else dead + 1
                val wait = if (dead < 5) 1000L else minOf(30_000L, 1000L shl minOf(dead - 4, 5))
                delay(max(0L, wait - (System.currentTimeMillis() - t0)))
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
    val running: Boolean get() = job?.isActive == true
}
