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
/** Kalshi's demo exchange: the same API with mock funds and its own accounts and keys. */
const val KALSHI_DEMO_BASE = "https://external-api.demo.kalshi.co/trade-api/v2"
const val KALSHI_WS = "wss://external-api-ws.kalshi.com/trade-api/ws/v2"
const val KALSHI_DEMO_WS = "wss://external-api-ws.demo.kalshi.co/trade-api/ws/v2"

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
    val base: String = KALSHI_BASE,
    private val clock: Clock = Clock.SYSTEM,
    http: JsonHttp? = null,
    /** 0 is the primary account. A numbered subaccount isolates the bot's money and positions. */
    val subaccount: Int = 0,
) {
    val demo: Boolean get() = base.contains(".demo.") || base.contains("demo-api")
    val signerForStream: RequestSigner? get() = signer
    private fun sub(q: List<Pair<String, String>>) = if (subaccount > 0) q + ("subaccount" to subaccount.toString()) else q
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
        return KalshiBalance.parse(http.get("/portfolio/balance", sub(q), requireSigner()), clock.now())
    }

    suspend fun order(orderId: String): JsonObject? = try {
        val p = http.get("/portfolio/orders/$orderId", sub(emptyList()), requireSigner())
        p.obj("order") ?: p.takeIf { it.isNotEmpty() }
    } catch (e: ApiException) {
        if (e.status == 404) null else throw e
    }

    suspend fun orders(status: String, maxPages: Int = 20): List<JsonObject> =
        paginate("/portfolio/orders", "orders", sub(listOf("status" to status, "limit" to "200")), maxPages, signed = true)

    suspend fun positions(): List<JsonObject> =
        paginate("/portfolio/positions", "market_positions", sub(listOf("limit" to "200", "count_filter" to "position")), 20, signed = true)

    suspend fun queuePosition(orderId: String): Sourced<Double>? =
        http.get("/portfolio/orders/$orderId/queue_position", signer = requireSigner()).num("queue_position_fp")
            ?.let { Sourced.kalshi(it, "GET /portfolio/orders/$orderId/queue_position#queue_position_fp", clock.now()) }

    /** Every settlement, newest first, keyed by ticker (first = latest wins). Paginated: the bare call returns 100. */
    suspend fun settlementsByTicker(maxPages: Int = 50): Map<String, JsonObject> {
        val out = LinkedHashMap<String, JsonObject>()
        for (s in paginate("/portfolio/settlements", "settlements", sub(listOf("limit" to "200")), maxPages, signed = true)) {
            val t = s.str("ticker") ?: continue
            out.putIfAbsent(t, s)
        }
        return out
    }

    suspend fun fills(ticker: String? = null, maxPages: Int = 20): List<JsonObject> =
        paginate("/portfolio/fills", "fills",
            sub(listOfNotNull("limit" to "200", ticker?.let { "ticker" to it })), maxPages, signed = true)

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
        cancelOnPause: Boolean = true,
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
            // The exchange pulls the order if trading pauses: the phone may not be awake to react.
            put("cancel_order_on_pause", cancelOnPause)
            if (exchangeIndex != null) put("exchange_index", exchangeIndex)
            if (expirationEpochS != null && expirationEpochS > clock.now().epochSecond) put("expiration_time", expirationEpochS)
            if (subaccount > 0) put("subaccount", subaccount)
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

    /**
     * Move an order's expiry without touching price or size. Kalshi documents an
     * expiry-only amend (current price and total count resent) as preserving queue
     * position, which is what makes a short dead-man expiry affordable.
     */
    suspend fun renewExpiration(orderId: String, ticker: String, side: String, price: Double, tick: Double,
                                totalCount: Double, exchangeIndex: Int?, expirationEpochS: Long): JsonObject {
        val body = buildJsonObject {
            put("ticker", ticker)
            put("side", side)
            put("price", formatPrice(price, tick))
            put("count", "%.2f".format(java.util.Locale.ROOT, totalCount))
            put("expiration_time", expirationEpochS)
            if (exchangeIndex != null) put("exchange_index", exchangeIndex)
            if (subaccount > 0) put("subaccount", subaccount)
        }
        return http.post("/portfolio/events/orders/$orderId/amend", body, requireSigner())
    }

    /** Cancel, auto-routed by the market ticker (an order id alone cannot identify the shard). */
    suspend fun cancelOrder(orderId: String, marketTicker: String): Sourced<Double>? {
        val r = http.delete("/portfolio/events/orders/$orderId", sub(listOf("market_ticker" to marketTicker)), requireSigner())
        return r.num("reduced_by")?.let { Sourced.kalshi(it, "DELETE /portfolio/events/orders/$orderId#reduced_by", clock.now()) }
    }

    /**
     * Move cash between exchange shards of this account (e.g. into the crypto
     * shard, index 2, that BTC orders draw on). [amountDollars] is converted to
     * the endpoint's centicents. Processed asynchronously by the exchange.
     */
    suspend fun transferBetweenShards(amountDollars: Double, fromShard: Int, toShard: Int): String? {
        require(amountDollars > 0) { "transfer amount must be positive" }
        val body = buildJsonObject {
            put("source", "event_contract"); put("destination", "event_contract")
            put("amount", Math.round(amountDollars * 10_000))
            put("source_exchange_shard", fromShard); put("destination_exchange_shard", toShard)
            if (subaccount > 0) { put("source_subaccount", subaccount); put("destination_subaccount", subaccount) }
            else put("client_transfer_id", UUID.randomUUID().toString())
        }
        return http.post("/portfolio/intra_exchange_instance_transfer", body, requireSigner()).str("transfer_id")
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
