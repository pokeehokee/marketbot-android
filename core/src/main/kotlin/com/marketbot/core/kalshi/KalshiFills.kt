package com.marketbot.core.kalshi

import com.marketbot.core.net.RequestSigner
import com.marketbot.core.net.SignedStream
import com.marketbot.core.util.num
import com.marketbot.core.util.obj
import com.marketbot.core.util.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One of our fills, as Kalshi's `fill` channel reports it. */
data class KalshiFill(
    val tradeId: String, val orderId: String, val ticker: String, val side: String, val action: String,
    val count: Double, val yesPrice: Double?, val isTaker: Boolean?, val tsMs: Long?,
) {
    companion object {
        fun parse(message: JsonObject): KalshiFill? {
            if (message.str("type") != "fill") return null
            val m = message.obj("msg") ?: return null
            return KalshiFill(
                m.str("trade_id") ?: return null, m.str("order_id") ?: return null, m.str("market_ticker") ?: return null,
                m.str("side") ?: "", m.str("action") ?: "", m.num("count_fp") ?: m.num("count") ?: return null,
                m.num("yes_price_dollars"), (m["is_taker"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
                (m.num("ts_ms") ?: m.num("ts")?.times(1000))?.toLong(),
            )
        }

        val SUBSCRIBE: JsonObject = buildJsonObject {
            put("id", 1); put("cmd", "subscribe")
            put("params", buildJsonObject { put("channels", buildJsonArray { add(JsonPrimitive("fill")) }) })
        }
    }
}

/**
 * Kalshi's private fill channel: a notice the moment one of our orders trades,
 * instead of finding out at the next poll. It only reports; the ledger's fill
 * counts still come from the order itself at the exchange, so a missed or
 * duplicated message cannot corrupt them.
 */
class KalshiFillStream(signer: RequestSigner, url: String, onFill: (KalshiFill) -> Unit) {
    private val seen = LinkedHashSet<String>()
    private val stream = SignedStream(url, signer, { listOf(KalshiFill.SUBSCRIBE) }, { m ->
        KalshiFill.parse(m)?.let { f ->
            val fresh = synchronized(seen) { seen.add(f.tradeId).also { if (seen.size > 2_000) seen.remove(seen.first()) } }
            if (fresh) onFill(f)
        }
    })
    val connected get() = stream.connected
    fun start() = stream.start()
    fun stop() = stream.stop()
}
