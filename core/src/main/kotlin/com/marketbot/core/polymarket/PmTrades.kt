package com.marketbot.core.polymarket

import com.marketbot.core.net.RequestSigner
import com.marketbot.core.net.SignedStream
import com.marketbot.core.util.num
import com.marketbot.core.util.obj
import com.marketbot.core.util.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

const val PM_WS_MARKETS = "wss://api.polymarket.us/v1/ws/markets"

/** One public trade print. [takerSide] is ORDER_SIDE_BUY or ORDER_SIDE_SELL. */
data class PmTrade(val slug: String, val price: Double, val qty: Double, val takerSide: String) {
    companion object {
        /** A `{"trade": {...}}` websocket message, as the official SDK emits it; null when malformed. */
        fun parse(message: JsonObject): PmTrade? {
            val t = message.obj("trade") ?: return null
            val slug = t.str("marketSlug") ?: return null
            val px = t.obj("price")?.num("value") ?: t.num("price") ?: return null
            val q = t.obj("quantity")?.num("value") ?: (t["quantity"] as? JsonPrimitive)?.doubleOrNull ?: return null
            return PmTrade(slug, px, q, t.obj("taker")?.str("side") ?: "")
        }

        fun unsubscribe(requestId: String): JsonObject = buildJsonObject { put("unsubscribe", buildJsonObject { put("requestId", requestId) }) }

        fun subscribe(requestId: String, slugs: Collection<String>): JsonObject = buildJsonObject {
            put("subscribe", buildJsonObject {
                put("requestId", requestId); put("subscriptionType", "SUBSCRIPTION_TYPE_TRADE")
                put("marketSlugs", buildJsonArray { slugs.sorted().forEach { add(JsonPrimitive(it)) } })
            })
        }
    }
}

/**
 * Public trade prints for the markets the farm works, over the authenticated
 * markets websocket (pmfarm's TradeStream). Prints wait in an inbox until the
 * next tick drains them; the paper broker uses them to tell trades from cancels.
 */
class PmTradeStream(signer: RequestSigner, url: String = PM_WS_MARKETS) {
    private val inbox = mutableMapOf<String, MutableList<PmTrade>>()
    @Volatile private var want: Set<String> = emptySet()
    @Volatile private var active: String? = null
    private var n = 0
    private fun nextId() = synchronized(this) { "mb-trades-${++n}" }
    private val stream = SignedStream(url, signer, {
        // On every (re)connect: one subscription for the current set.
        if (want.isEmpty()) { active = null; emptyList() } else listOf(PmTrade.subscribe(nextId().also { active = it }, want))
    }, { m ->
        PmTrade.parse(m)?.let { t -> synchronized(inbox) { inbox.getOrPut(t.slug) { mutableListOf() }.add(t) } }
    })
    val connected get() = stream.connected
    val received get() = stream.received.get()

    fun start() = stream.start()
    fun stop() = stream.stop()

    fun setMarkets(slugs: Set<String>) {
        if (slugs == want) return
        want = slugs
        // Replace the subscription, never stack a second one, or every print would arrive twice.
        active?.let { stream.send(PmTrade.unsubscribe(it)) }
        active = null
        if (slugs.isNotEmpty()) nextId().let { id -> if (stream.send(PmTrade.subscribe(id, slugs))) active = id }
    }

    fun drain(slug: String): List<PmTrade> = synchronized(inbox) { inbox.remove(slug) ?: emptyList() }
}
