package com.marketbot.core.kalshi

import com.marketbot.core.net.ApiException
import com.marketbot.core.net.JsonHttp
import com.marketbot.core.net.RateLimiter
import com.marketbot.core.net.RequestSigner
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.util.Clock
import com.marketbot.core.util.num
import com.marketbot.core.util.obj
import com.marketbot.core.util.objects
import com.marketbot.core.util.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID

const val KALSHI_BASE = "https://external-api.kalshi.com/trade-api/v2"

/** What an order create returned. `filtered` = refused because it would have crossed (post-only). */
data class PlacedOrder(
    val orderId: String?,
    val clientOrderId: String,
    val filtered: Boolean = false,
    val reason: String? = null,
    val raw: JsonObject? = null,
)

/**
 * Kalshi REST client. Public market data needs no key; everything under
 * /portfolio is signed. Read and balance calls are spaced to stay inside the
 * Basic tier (Read 200 tokens/s = 20 calls/s; balance 1/s).
 */
class KalshiClient(
    private val signer: RequestSigner?,
    base: String = KALSHI_BASE,
    private val clock: Clock = Clock.SYSTEM,
    http: JsonHttp? = null,
) {
    private val http = http ?: JsonHttp(base, limiter = RateLimiter(8.0))
    private val balanceLimiter = RateLimiter(0.9)

    val hasCredentials: Boolean get() = signer != null

    private fun requireSigner(): RequestSigner =
        signer ?: throw IllegalStateException("Kalshi API key not configured")

    // ---------------------------------------------------------------- public
    suspend fun exchangeStatus(): JsonObject = http.get("/exchange/status")

    /** Trading open on the given shard (and the exchange as a whole). */
    suspend fun tradingActive(exchangeIndex: Int = 0): Sourced<Boolean> {
        val s = exchangeStatus()
        val shard = s.objects("exchange_index_statuses").firstOrNull { it.num("exchange_index")?.toInt() == exchangeIndex }
        val ok = (s.str("trading_active") == "true") && (s.str("exchange_active") != "false") &&
            (shard == null || (shard.str("trading_active") == "true" && shard.str("exchange_active") != "false"))
        return Sourced.kalshi(ok, "GET /exchange/status#trading_active (shard $exchangeIndex)", clock.now())
    }

    suspend fun series(ticker: String): JsonObject = http.get("/series/$ticker").obj("series") ?: JsonObject(emptyMap())

    suspend fun seriesFeeType(ticker: String): Sourced<String>? =
        series(ticker).str("fee_type")?.let { Sourced.kalshi(it, "GET /series/$ticker#fee_type", clock.now()) }

    suspend fun market(ticker: String): KalshiMarket =
        KalshiMarket(http.get("/markets/$ticker").obj("market") ?: JsonObject(emptyMap()))

    suspend fun markets(query: List<Pair<String, String>>, maxPages: Int = 50): List<KalshiMarket> =
        paginate("/markets", "markets", query, maxPages, signed = false).map { KalshiMarket(it) }

    suspend fun openMarkets(seriesTicker: String) =
        markets(listOf("series_ticker" to seriesTicker, "status" to "open", "limit" to "200"))

    suspend fun eventMarkets(eventTicker: String) =
        markets(listOf("event_ticker" to eventTicker, "limit" to "200"))

    suspend fun trades(ticker: String, minTs: Long, maxPages: Int = 20): List<JsonObject> =
        paginate("/markets/trades", "trades",
            listOf("ticker" to ticker, "min_ts" to minTs.toString(), "limit" to "1000"), maxPages, signed = false)

    // ---------------------------------------------------------------- signed
    suspend fun balance(exchangeIndex: Int? = null): KalshiBalance {
        balanceLimiter.acquire()
        val q = exchangeIndex?.let { listOf("exchange_index" to it.toString()) } ?: emptyList()
        return KalshiBalance.parse(http.get("/portfolio/balance", q, requireSigner()), clock.now())
    }

    suspend fun order(orderId: String): JsonObject? = try {
        val p = http.get("/portfolio/orders/$orderId", signer = requireSigner())
        p.obj("order") ?: p.takeIf { it.isNotEmpty() }
    } catch (e: ApiException) {
        if (e.status == 404) null else throw e
    }

    suspend fun orders(status: String, maxPages: Int = 20): List<JsonObject> =
        paginate("/portfolio/orders", "orders", listOf("status" to status, "limit" to "200"), maxPages, signed = true)

    suspend fun positions(): List<JsonObject> =
        paginate("/portfolio/positions", "market_positions", listOf("limit" to "200", "count_filter" to "position"), 20, signed = true)

    suspend fun queuePosition(orderId: String): Sourced<Double>? =
        http.get("/portfolio/orders/$orderId/queue_position", signer = requireSigner()).num("queue_position_fp")
            ?.let { Sourced.kalshi(it, "GET /portfolio/orders/$orderId/queue_position#queue_position_fp", clock.now()) }

    /** Every settlement, newest first, keyed by ticker (first = latest wins). Paginated: the bare call returns 100. */
    suspend fun settlementsByTicker(maxPages: Int = 50): Map<String, JsonObject> {
        val out = LinkedHashMap<String, JsonObject>()
        for (s in paginate("/portfolio/settlements", "settlements", listOf("limit" to "200"), maxPages, signed = true)) {
            val t = s.str("ticker") ?: continue
            out.putIfAbsent(t, s)
        }
        return out
    }

    suspend fun fills(ticker: String? = null, maxPages: Int = 20): List<JsonObject> =
        paginate("/portfolio/fills", "fills",
            listOfNotNull("limit" to "200", ticker?.let { "ticker" to it }), maxPages, signed = true)

    /** The order carrying [clientOrderId], or null. Kalshi has no filter for it, so recent orders are walked. */
    suspend fun findByClientOrderId(clientOrderId: String): JsonObject? {
        for (status in listOf("resting", "executed", "canceled")) {
            val hit = runCatching { orders(status, maxPages = 5) }.getOrNull()
                ?.firstOrNull { it.str("client_order_id") == clientOrderId }
            if (hit != null) return hit
        }
        return null
    }

    /**
     * Post-only limit order via the V2 endpoint. `side` is the YES book side:
     * "bid" buys YES, "ask" sells YES (= buys NO at 1 - price). The
     * exchange_index is the market's own field -- the shard the order must be
     * routed to -- and the market ticker is always sent, so routing can never
     * fall back to shard 0 for a crypto market.
     *
     * Never retried. If the response is lost or rejected with 400/409, the
     * order is looked up by our client_order_id before anything is concluded.
     */
    suspend fun placeOrder(
        ticker: String,
        side: String,
        count: Int,
        price: Double,
        tick: Double,
        exchangeIndex: Int?,
        postOnly: Boolean = true,
        expirationEpochS: Long? = null,
    ): PlacedOrder {
        require(side == "bid" || side == "ask") { "side must be bid or ask" }
        require(count >= 1) { "refusing an order of $count contracts" }
        val px = roundToTick(price, tick, if (side == "bid") Round.DOWN else Round.UP)
        val coid = UUID.randomUUID().toString()
        val body = buildJsonObject {
            put("ticker", ticker)
            put("client_order_id", coid)
            put("side", side)
            put("count", count.toString())
            put("price", formatPrice(px, tick))
            put("time_in_force", "good_till_canceled")
            put("self_trade_prevention_type", "taker_at_cross")
            put("post_only", postOnly)
            if (exchangeIndex != null) put("exchange_index", exchangeIndex)
            if (expirationEpochS != null && expirationEpochS > clock.now().epochSecond) put("expiration_time", expirationEpochS)
        }
        return try {
            val r = http.post("/portfolio/events/orders", body, requireSigner())
            val id = r.str("order_id") ?: r.obj("order")?.str("order_id")
            PlacedOrder(id ?: findByClientOrderId(coid)?.str("order_id"), coid, raw = r)
        } catch (e: ApiException) {
            val text = (e.message ?: "").lowercase() + " " + (e.code ?: "")
            if ("cross" in text || "post_only" in text || "post only" in text)
                return PlacedOrder(null, coid, filtered = true, reason = e.code ?: "post_only_cross")
            if (e.status == 400 || e.status == 409 || e.status >= 500) {
                findByClientOrderId(coid)?.let { return PlacedOrder(it.str("order_id"), coid, raw = it) }
            }
            throw e
        } catch (e: IOException) {
            findByClientOrderId(coid)?.let { return PlacedOrder(it.str("order_id"), coid, raw = it) }
            throw e
        }
    }

    /** Cancel, auto-routed by the market ticker (an order id alone cannot identify the shard). */
    suspend fun cancelOrder(orderId: String, marketTicker: String): Sourced<Double>? {
        val r = http.delete("/portfolio/events/orders/$orderId", listOf("market_ticker" to marketTicker), requireSigner())
        return r.num("reduced_by")?.let { Sourced.kalshi(it, "DELETE /portfolio/events/orders/$orderId#reduced_by", clock.now()) }
    }

    // ---------------------------------------------------------------- paging
    private suspend fun paginate(
        path: String, key: String, query: List<Pair<String, String>>, maxPages: Int, signed: Boolean,
    ): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var cursor: String? = null
        repeat(maxPages) {
            val q = if (cursor != null) query + ("cursor" to cursor!!) else query
            val page = http.get(path, q, if (signed) requireSigner() else null)
            val batch = page.objects(key)
            out += batch
            cursor = page.str("cursor")?.takeIf { it.isNotEmpty() }
            if (cursor == null || batch.isEmpty()) return out
        }
        throw TruncatedListing("$path still had a cursor after $maxPages pages")
    }
}

/** A listing that hit the page guard. Raised rather than returned, so a cap can never pass for complete data. */
class TruncatedListing(message: String) : IOException(message)
