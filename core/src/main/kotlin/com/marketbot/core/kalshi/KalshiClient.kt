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

    /**
     * Kalshi's own fill circuit breaker (order groups). When set, every order joins
     * the group for its shard, and Kalshi cancels the group's resting orders and
     * refuses new ones once more than the limit fills within 15 seconds.
     */
    @Volatile var fillLimit: FillLimit? = null

    /** The last check before any order leaves the phone: a non-null reason refuses it (the kill switch). */
    @Volatile var orderGate: (() -> com.marketbot.core.strategy.Reason?)? = null

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

    /** The first page only, for sampling (the daily API check); never where a complete listing matters. */
    suspend fun marketsSample(query: List<Pair<String, String>>): List<KalshiMarket> =
        http.get("/markets", query).objects("markets").map { KalshiMarket(it) }

    /** The first page of a series' events, for sampling. */
    suspend fun eventsSample(query: List<Pair<String, String>>): List<JsonObject> = http.get("/events", query).objects("events")

    /** The first page of a market's public trades, for sampling (the daily API check). */
    suspend fun tradesSample(ticker: String, minTs: Long): List<JsonObject> =
        http.get("/markets/trades", listOf("ticker" to ticker, "min_ts" to minTs.toString(), "limit" to "200")).objects("trades")

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

    /** The first page of an account listing (`orders`, `settlements`, `fills`), for sampling only. */
    suspend fun accountSample(path: String, key: String, query: List<Pair<String, String>> = emptyList()): List<JsonObject> =
        http.get(path, sub(query + ("limit" to "100")), requireSigner()).objects(key)

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

    suspend fun fills(ticker: String? = null, maxPages: Int = 20, orderId: String? = null, exchangeIndex: Int? = null): List<JsonObject> =
        paginate("/portfolio/fills", "fills",
            sub(listOfNotNull("limit" to "200", ticker?.let { "ticker" to it }, orderId?.let { "order_id" to it },
                exchangeIndex?.let { "exchange_index" to it.toString() })), maxPages, signed = true)

    /** The settlement of one market, asked for by ticker so it never falls off the end of a paged listing. */
    suspend fun settlementFor(ticker: String): JsonObject? =
        http.get("/portfolio/settlements", sub(listOf("ticker" to ticker, "limit" to "100")), requireSigner())
            .objects("settlements").firstOrNull { it.str("ticker") == ticker }

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
        orderGate?.invoke()?.let { throw com.marketbot.core.strategy.OrderDenied(it) }
        val shard = exchangeIndex ?: 0
        val group = fillLimit?.let { fl ->
            fl.trippedAt(shard)?.let { throw FillLimitTripped(shard, it) }
            // Fails open: the limit is an extra guard, and an order group that cannot be created
            // must not stop trading. The failure is kept for the settings screen.
            runCatching { fl.groupFor(this, shard) }.onFailure { fl.lastError = it.message ?: it.javaClass.simpleName }.getOrNull()
        }
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
            if (group != null) put("order_group_id", group)
        }
        return try {
            val r = http.post("/portfolio/events/orders", body, requireSigner())
            val id = r.str("order_id") ?: r.obj("order")?.str("order_id")
            PlacedOrder(id ?: findByClientOrderId(coid)?.str("order_id"), coid, raw = r)
        } catch (e: ApiException) {
            val text = (e.message ?: "").lowercase() + " " + (e.code ?: "")
            // Kalshi refuses orders in a group that has tripped: remember it, never route around it.
            // Kalshi gives no structured "triggered" field, so the message is all there is. When it
            // mentions the group but not in the words below, the group is looked up: gone (404) means
            // a new one is created; still there means the refusal is treated as a trip, because
            // creating a fresh group would route around Kalshi's own limit.
            if (group != null && "group" in text) {
                if (KalshiWording.groupTripped(text)) { fillLimit?.markTripped(shard, clock.now()); throw FillLimitTripped(shard, clock.now()) }
                val gone = try { orderGroup(group, shard); false } catch (g: ApiException) { g.status == 404 } catch (g: IOException) { false }
                if (gone) fillLimit?.forget(shard)
                else {
                    fillLimit?.lastError = "Kalshi refused an order in the fill-limit group with a message the app does not recognise " +
                        "(\"${e.message}\"); treated as tripped. Check it, then Reset."
                    fillLimit?.markTripped(shard, clock.now()); throw FillLimitTripped(shard, clock.now())
                }
            }
            if (KalshiWording.postOnlyCross(text))
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

    // ---------------------------------------------------------------- order groups (exchange-side fill limit)
    /** Create a group allowing at most [contracts] fills per rolling 15 s on [exchangeIndex]; returns its id. */
    suspend fun createOrderGroup(contracts: Int, exchangeIndex: Int): String {
        val body = buildJsonObject {
            put("contracts_limit", contracts); put("exchange_index", exchangeIndex)
            if (subaccount > 0) put("subaccount", subaccount)
        }
        return http.post("/portfolio/order_groups/create", body, requireSigner()).str("order_group_id")
            ?: throw IOException("order group created without an order_group_id")
    }

    /** One group: `is_auto_cancel_enabled`, `contracts_limit_fp`, `orders`. 404 once it is deleted. */
    suspend fun orderGroup(id: String, exchangeIndex: Int): JsonObject =
        http.get("/portfolio/order_groups/$id", groupQuery(exchangeIndex), requireSigner())

    /** Delete a group; Kalshi cancels the orders in it. */
    suspend fun deleteOrderGroup(id: String, exchangeIndex: Int) {
        http.delete("/portfolio/order_groups/$id", groupQuery(exchangeIndex), requireSigner())
    }

    /** This account's groups: id, limit, shard. */
    suspend fun orderGroups(): List<JsonObject> =
        http.get("/portfolio/order_groups", listOf("subaccount" to subaccount.toString()), requireSigner()).objects("order_groups")

    private fun groupQuery(exchangeIndex: Int) = listOf("subaccount" to subaccount.toString(), "exchange_index" to exchangeIndex.toString())

    /** Cancel every resting order in the group at the exchange and refuse new ones until [resetOrderGroup]. */
    suspend fun triggerOrderGroup(id: String, exchangeIndex: Int) {
        http.put("/portfolio/order_groups/$id/trigger", buildJsonObject { }, groupQuery(exchangeIndex), requireSigner())
    }

    suspend fun resetOrderGroup(id: String, exchangeIndex: Int) {
        http.put("/portfolio/order_groups/$id/reset", buildJsonObject { }, groupQuery(exchangeIndex), requireSigner())
    }

    suspend fun updateOrderGroupLimit(id: String, contracts: Int, exchangeIndex: Int) {
        http.put("/portfolio/order_groups/$id/limit", buildJsonObject { put("contracts_limit", contracts) }, groupQuery(exchangeIndex), requireSigner())
    }

    /**
     * Cancel many orders in one call, each routed by its market ticker. Returns,
     * per order id, the contracts cancelled, or the error the exchange gave.
     */
    suspend fun batchCancel(orders: List<Pair<String, String>>, chunk: Int = 20): Map<String, Result<Double>> {
        val out = LinkedHashMap<String, Result<Double>>()
        for (part in orders.chunked(chunk)) {
            val body = buildJsonObject {
                put("orders", kotlinx.serialization.json.buildJsonArray {
                    for ((id, ticker) in part) add(buildJsonObject {
                        put("order_id", id); put("market_ticker", ticker)
                        if (subaccount > 0) put("subaccount", subaccount)
                    })
                })
            }
            val r = runCatching { http.deleteWithBody("/portfolio/events/orders/batched", body, requireSigner()) }
            val rows = r.getOrNull()?.objects("orders").orEmpty().associateBy { it.str("order_id") }
            for ((id, _) in part) out[id] = when {
                r.isFailure -> Result.failure(r.exceptionOrNull()!!)
                rows[id]?.obj("error") != null -> Result.failure(IOException(rows[id]!!.obj("error")!!.str("message") ?: "cancel refused"))
                else -> Result.success(rows[id]?.num("reduced_by") ?: 0.0)
            }
        }
        return out
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


/** An order was refused because the shard's fill limit at Kalshi has tripped and has not been reset. */
class FillLimitTripped(val shard: Int, val at: java.time.Instant) :
    IOException("Kalshi's fill limit on shard $shard tripped at $at: Kalshi cancelled the group's orders and refuses new ones until it is reset")

/**
 * The app-wide Kalshi fill limit: one order group per shard, its id and trip time
 * kept in the state store so they survive restarts. [limit] is read each time, so
 * a settings change applies at the next order; 0 turns the limit off.
 */
class FillLimit(private val state: com.marketbot.core.store.StateStore, private val limit: suspend () -> Int,
                private val onTrip: suspend (Int) -> Unit = {}) {
    private val cache = java.util.concurrent.ConcurrentHashMap<Int, String>()
    /** The last time a group could not be created or updated, for the settings screen. */
    @Volatile var lastError: String? = null

    suspend fun forget(shard: Int) { cache.remove(shard); state.put(key(shard, "id"), null); state.put(key(shard, "limit"), null) }
    private val tripped = java.util.concurrent.ConcurrentHashMap<Int, java.time.Instant>()
    private fun key(shard: Int, what: String) = "kalshi.fill_limit.$shard.$what"

    suspend fun trippedAt(shard: Int): java.time.Instant? = tripped[shard]
        ?: state.get(key(shard, "tripped"))?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }?.also { tripped[shard] = it }

    /** [reason] is "fills" when Kalshi tripped it, "kill" when the kill switch did (only those reset with the kill switch). */
    suspend fun markTripped(shard: Int, at: java.time.Instant, reason: String = "fills") {
        if (tripped.putIfAbsent(shard, at) == null) {
            state.put(key(shard, "tripped"), at.toString()); state.put(key(shard, "reason"), reason)
            if (reason == "fills") onTrip(shard)
        }
    }

    suspend fun reason(shard: Int): String? = state.get(key(shard, "reason"))

    /** The group for [shard], created (or its limit updated) when needed; null when the limit is off. */
    suspend fun groupFor(client: KalshiClient, shard: Int): String? {
        val n = limit()
        if (n <= 0) return null
        val id = cache[shard] ?: state.get(key(shard, "id"))
        val have = state.get(key(shard, "limit"))?.toIntOrNull()
        if (id != null && have == n) return id.also { cache[shard] = it }
        if (id != null && runCatching { client.updateOrderGroupLimit(id, n, shard) }.isSuccess) {
            state.put(key(shard, "limit"), n.toString()); cache[shard] = id; return id
        }
        val created = client.createOrderGroup(n, shard)
        state.put(key(shard, "id"), created); state.put(key(shard, "limit"), n.toString()); cache[shard] = created
        lastError = null
        return created
    }

    /** Groups this app created, by shard. */
    suspend fun groups(): Map<Int, String> = (0..3).mapNotNull { s -> (cache[s] ?: state.get(key(s, "id")))?.let { s to it } }.toMap()

    /** Kill switch: Kalshi itself cancels everything in every group. */
    suspend fun triggerAll(client: KalshiClient): List<String> = groups().mapNotNull { (shard, id) ->
        runCatching { client.triggerOrderGroup(id, shard); markTripped(shard, java.time.Instant.now(), "kill") }.exceptionOrNull()?.let { "shard $shard: ${it.message}" }
    }

    /** After review: reset at Kalshi, then allow orders again. A group Kalshi no longer has is forgotten (the next order makes a new one). */
    suspend fun reset(client: KalshiClient, shard: Int) {
        groups()[shard]?.let { id ->
            try { client.resetOrderGroup(id, shard) } catch (e: ApiException) { if (e.status == 404) forget(shard) else throw e }
        }
        tripped.remove(shard); state.put(key(shard, "tripped"), null); state.put(key(shard, "reason"), null)
    }

    suspend fun trippedShards(): Map<Int, java.time.Instant> = (0..3).mapNotNull { s -> trippedAt(s)?.let { s to it } }.toMap()
}

/**
 * The exact words the app relies on in Kalshi's error messages, in one place so the
 * daily API check and the tests exercise the same rules the order path uses.
 */
object KalshiWording {
    /** Words Kalshi has used when an order group has hit its limit or been triggered. */
    val GROUP_TRIP_WORDS = listOf("trigger", "limit", "exceed")
    val POST_ONLY_WORDS = listOf("cross", "post_only", "post only")
    fun groupTripped(text: String) = "group" in text && GROUP_TRIP_WORDS.any { it in text }
    fun postOnlyCross(text: String) = POST_ONLY_WORDS.any { it in text }
}
