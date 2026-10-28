package com.marketbot.core.polymarket

import com.marketbot.core.net.ApiException
import com.marketbot.core.net.JsonHttp
import com.marketbot.core.net.RateLimiter
import com.marketbot.core.net.RequestSigner
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.provenance.ValueUnavailable
import com.marketbot.core.util.Clock
import com.marketbot.core.util.amount
import com.marketbot.core.util.bool
import com.marketbot.core.util.num
import com.marketbot.core.util.obj
import com.marketbot.core.util.objects
import com.marketbot.core.util.parseInstant
import com.marketbot.core.util.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

const val PM_GATEWAY = "https://gateway.polymarket.us"
const val PM_API = "https://api.polymarket.us"

/** Taker fee coefficient from docs.polymarket.us/fees (checked 2026-10-03), used only when a market omits `feeCoefficient`. */
const val PM_TAKER_THETA_FALLBACK = 0.0695
/** Maker rebate coefficient (negative = paid to the maker). The API exposes no field for it. */
const val PM_MAKER_THETA = -0.0125

data class Level(val price: Double, val qty: Double)

data class PmBook(val slug: String, val bids: List<Level>, val asks: List<Level>, val state: String, val at: Instant) {
    val bestBid: Double? get() = bids.firstOrNull()?.price
    val bestAsk: Double? get() = asks.firstOrNull()?.price
    val open: Boolean get() = state.isEmpty() || state == "MARKET_STATE_OPEN"

    companion object {
        fun parse(slug: String, md: JsonObject, at: Instant): PmBook {
            fun lv(key: String, desc: Boolean) = md.objects(key).mapNotNull { r ->
                val px = r.amount("px") ?: return@mapNotNull null
                val q = r.num("qty") ?: return@mapNotNull null
                Level(px, q)
            }.let { if (desc) it.sortedByDescending { l -> l.price } else it.sortedBy { l -> l.price } }
            return PmBook(slug, lv("bids", true), lv("offers", false), md.str("state") ?: "", at)
        }
    }
}

data class PmMarketMeta(val raw: JsonObject) {
    val slug: String get() = raw.str("slug") ?: ""
    val tick: Double get() = raw.num("orderPriceMinTickSize") ?: 0.01
    val minQty: Double get() = raw.num("minimumTradeQty") ?: 1.0
    val closed: Boolean get() = raw.bool("closed") ?: false
    val active: Boolean get() = raw.bool("active") ?: true
    val title: String get() = raw.str("question") ?: raw.str("title") ?: ""
    val gameStart: Instant? get() = parseInstant(raw.str("gameStartTime"))

    /** Per-market taker fee coefficient from the API; the documented constant only when absent. */
    val takerTheta: Sourced<Double>
        get() = raw.num("feeCoefficient")?.let { Sourced.polymarket(it, "market $slug#feeCoefficient") }
            ?: Sourced.derived(PM_TAKER_THETA_FALLBACK, "docs.polymarket.us/fees taker theta", "market $slug has no feeCoefficient")

    /** For asset-price markets ("BTC above $X at 5PM ET"): strike and settle time from `assetPriceTerms`. */
    val assetTerms: JsonObject? get() = raw.obj("assetPriceTerms")
    val strike: Double? get() = assetTerms?.amount("strike")
    val windowEnd: Instant? get() = parseInstant(assetTerms?.str("windowEnd"))
    val assetMarketType: String? get() = assetTerms?.str("marketType")
    val indexSymbol: String? get() = assetTerms?.str("indexSymbol")
}

data class PmBalance(
    val currentBalance: Sourced<Double>,
    val buyingPower: Sourced<Double>,
    val openOrders: Sourced<Double>?,
    val raw: JsonObject,
)

data class PmPosition(
    val slug: String,
    val qty: Sourced<Double>,
    val avgPx: Sourced<Double>?,
    val cashValue: Sourced<Double>?,
    val raw: JsonObject,
)

data class PmOpenOrder(val id: String, val slug: String, val side: String, val price: Double, val qtyLeft: Double, val intent: String, val state: String)

/** Order states known to be finished. Anything else is treated as possibly live. */
val PM_TERMINAL = setOf("ORDER_STATE_FILLED", "ORDER_STATE_CANCELED", "ORDER_STATE_REPLACED", "ORDER_STATE_REJECTED", "ORDER_STATE_EXPIRED")

class PolymarketClient(
    private val signer: RequestSigner?,
    private val clock: Clock = Clock.SYSTEM,
    gateway: JsonHttp? = null,
    api: JsonHttp? = null,
) {
    // Cloudflare in front of the public gateway banned the original recorder above ~20 rps.
    private val gw = gateway ?: JsonHttp(PM_GATEWAY, limiter = RateLimiter(3.0))
    private val api = api ?: JsonHttp(PM_API, limiter = RateLimiter(5.0))
    val hasCredentials get() = signer != null
    private fun s() = signer ?: throw IllegalStateException("Polymarket API key not configured")

    // ---------------------------------------------------------------- public gateway
    suspend fun liquidityPrograms(maxPages: Int = 200): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var token: String? = null
        repeat(maxPages) {
            val q = mutableListOf("page_size" to "500", "statuses" to "active", "program_type" to "liquidityProgram")
            token?.let { q += "page_token" to it }
            val d = gw.get("/v1/incentives", q)
            out += d.objects("programs")
            token = d.str("nextPageToken")?.takeIf { it.isNotEmpty() } ?: return out
        }
        return out
    }

    suspend fun market(slug: String): PmMarketMeta {
        val m = gw.get("/v1/market/slug/$slug")
        return PmMarketMeta(m.obj("market") ?: m)
    }

    suspend fun marketsBySlug(slugs: List<String>): List<PmMarketMeta> {
        if (slugs.isEmpty()) return emptyList()
        val q = slugs.map { "slug" to it } + ("limit" to slugs.size.toString())
        return gw.get("/v1/markets", q).objects("markets").map { PmMarketMeta(it) }
    }

    suspend fun book(slug: String): PmBook =
        PmBook.parse(slug, gw.get("/v1/markets/$slug/book").obj("marketData") ?: JsonObject(emptyMap()), clock.now())

    /** Settlement value per YES contract once resolved (API), or null while unresolved (404). */
    suspend fun settlement(slug: String): Sourced<Double>? = try {
        gw.get("/v1/markets/$slug/settlement").num("settlement")
            ?.let { Sourced.polymarket(it, "GET /v1/markets/$slug/settlement#settlement", clock.now()) }
    } catch (e: ApiException) {
        if (e.status == 404) null else throw e
    }

    suspend fun search(query: String, limit: Int = 50): JsonObject = gw.get("/v1/search", listOf("query" to query, "limit" to "$limit"))

    suspend fun event(slug: String): JsonObject = gw.get("/v1/events/slug/$slug").let { it.obj("event") ?: it }

    // ---------------------------------------------------------------- authenticated
    suspend fun balance(): PmBalance {
        val rows = api.get("/v1/account/balances", signer = s()).objects("balances")
        val b = (if (rows.size > 1) rows.filter { it.str("currency") == "USD" } else rows).firstOrNull()
            ?: throw ValueUnavailable("Polymarket balance", "no USD balance row")
        val at = clock.now()
        fun req(k: String) = b.num(k)?.let { Sourced.polymarket(it, "GET /v1/account/balances#$k", at) }
            ?: throw ValueUnavailable("Polymarket $k", "missing from /v1/account/balances")
        return PmBalance(req("currentBalance"), req("buyingPower"),
            b.num("openOrders")?.let { Sourced.polymarket(it, "GET /v1/account/balances#openOrders", at) }, b)
    }

    suspend fun positions(): List<PmPosition> {
        val out = mutableListOf<PmPosition>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        while (true) {
            val q = mutableListOf("limit" to "100"); cursor?.let { q += "cursor" to it }
            val r = api.get("/v1/portfolio/positions", q, s())
            val at = clock.now()
            r.obj("positions")?.forEach { (slug, el) ->
                val p = el as? JsonObject ?: return@forEach
                val qty = p.num("netPositionDecimal")?.let { Sourced.polymarket(it, "positions[$slug]#netPositionDecimal", at) }
                    ?: p.num("netPosition")?.let { Sourced.polymarket(it, "positions[$slug]#netPosition", at) }
                    ?: throw ValueUnavailable("position $slug", "no netPosition")
                if (kotlin.math.abs(qty.value) < 1e-9 || p.bool("expired") == true) return@forEach
                out += PmPosition(slug, qty,
                    p.amount("avgPx")?.let { Sourced.polymarket(it, "positions[$slug]#avgPx", at) },
                    p.amount("cashValue")?.let { Sourced.polymarket(it, "positions[$slug]#cashValue", at) }, p)
            }
            cursor = r.str("nextCursor")?.takeIf { it.isNotEmpty() }
            if (r.bool("eof") == true || cursor == null) return out
            if (!seen.add(cursor!!)) throw ValueUnavailable("Polymarket positions", "cursor repeated")
        }
    }

    suspend fun openOrders(): List<PmOpenOrder> = api.get("/v1/orders/open", signer = s()).objects("orders").mapNotNull { o ->
        val state = o.str("state") ?: throw ValueUnavailable("order ${o.str("id")}", "no state")
        if (state in PM_TERMINAL) return@mapNotNull null
        val side = when (o.str("side")) { "ORDER_SIDE_BUY" -> "bid"; "ORDER_SIDE_SELL" -> "ask"; else -> throw ValueUnavailable("order ${o.str("id")}", "unknown side") }
        PmOpenOrder(o.str("id") ?: return@mapNotNull null, o.str("marketSlug") ?: return@mapNotNull null, side,
            o.amount("price") ?: throw ValueUnavailable("order price", "missing"),
            o.num("leavesQuantity") ?: throw ValueUnavailable("order leavesQuantity", "missing"),
            o.str("intent") ?: "", state)
    }

    suspend fun order(id: String): JsonObject? = try {
        api.get("/v1/order/$id", signer = s()).let { it.obj("order") ?: it }
    } catch (e: ApiException) { if (e.status == 404) null else throw e }

    /**
     * Maker-only limit order (`participateDontInitiate`). Prices are sent as
     * the instrument (YES) price; the short-side convention is unverified in the
     * docs, which is why NO/short orders are blocked until the preflight check
     * confirms it with `/v1/order/preview`.
     */
    suspend fun placeOrder(slug: String, intent: String, wirePrice: Double, qty: Double, goodTill: Instant? = null): String? {
        val body = buildJsonObject {
            put("marketSlug", slug)
            put("intent", intent)
            put("type", "ORDER_TYPE_LIMIT")
            putJsonObject("price") { put("value", "%.4f".format(java.util.Locale.ROOT, wirePrice)); put("currency", "USD") }
            put("quantity", qty)
            put("tif", if (goodTill != null) "TIME_IN_FORCE_GOOD_TILL_DATE" else "TIME_IN_FORCE_GOOD_TILL_CANCEL")
            goodTill?.let { put("goodTillTime", it.toString().substringBefore('.').removeSuffix("Z") + "Z") }
            put("participateDontInitiate", true)
            put("manualOrderIndicator", "MANUAL_ORDER_INDICATOR_AUTOMATIC")
        }
        return api.post("/v1/orders", body, s()).str("id")
    }

    /**
     * [placeOrder], but a create whose response is lost (timeout, reset, 5xx) is
     * looked up before anything is concluded. Polymarket's create takes no client
     * order id, so the match is this market, intent, price and size among open
     * orders that [known] does not already hold. Exactly one match is adopted;
     * none or several rethrow, because guessing would mean managing an order we
     * cannot prove is ours.
     */
    suspend fun placeOrderRecovering(slug: String, intent: String, wirePrice: Double, qty: Double, goodTill: Instant? = null,
                                     known: Set<String> = emptySet()): String? = try {
        placeOrder(slug, intent, wirePrice, qty, goodTill)
    } catch (e: Exception) {
        val clientError = e is ApiException && e.status in 400..499
        if (clientError) throw e
        val side = if (intent == "ORDER_INTENT_BUY_LONG" || intent == "ORDER_INTENT_SELL_SHORT") "bid" else "ask"
        val matches = runCatching { openOrders() }.getOrNull()?.filter {
            it.slug == slug && it.intent == intent && it.side == side && kotlin.math.abs(it.price - wirePrice) < 1e-6 &&
                kotlin.math.abs(it.qtyLeft - qty) < 1e-6 && it.id !in known
        } ?: throw e
        if (matches.size == 1) matches[0].id else throw e
    }

    suspend fun preview(slug: String, intent: String, wirePrice: Double, qty: Double): JsonObject =
        api.post("/v1/order/preview", buildJsonObject {
            putJsonObject("request") {
                put("marketSlug", slug); put("intent", intent); put("type", "ORDER_TYPE_LIMIT")
                putJsonObject("price") { put("value", "%.4f".format(java.util.Locale.ROOT, wirePrice)); put("currency", "USD") }
                put("quantity", qty); put("tif", "TIME_IN_FORCE_GOOD_TILL_CANCEL")
            }
        }, s())

    suspend fun cancel(orderId: String, slug: String) {
        api.post("/v1/order/$orderId/cancel", buildJsonObject { put("marketSlug", slug) }, s())
    }

    suspend fun cancelAll() { api.post("/v1/orders/open/cancel", buildJsonObject { }, s()) }

    /** Actual reward payouts: the ground truth for the reward estimates. */
    suspend fun earnings(startDate: String? = null): List<JsonObject> =
        api.get("/v1/incentives/earnings", startDate?.let { listOf("start_date" to it) } ?: emptyList(), s()).objects("rewards")
}

/** theta x C x p x (1 - p); negative is a rebate. */
fun pmTradeFee(theta: Double, qty: Double, price: Double) = theta * qty * price * (1.0 - price)

/** Exchange intent for an order: entries open, exits reduce. */
fun pmIntentFor(side: String, positionQty: Double, isExit: Boolean): String =
    if (side == "bid") (if (isExit && positionQty < 0) "ORDER_INTENT_SELL_SHORT" else "ORDER_INTENT_BUY_LONG")
    else (if (isExit && positionQty > 0) "ORDER_INTENT_SELL_LONG" else "ORDER_INTENT_BUY_SHORT")
