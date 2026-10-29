package com.marketbot.core.strategies.deepitm

import com.marketbot.core.kalshi.FillState
import com.marketbot.core.kalshi.KalshiClient
import com.marketbot.core.kalshi.KalshiMarket
import com.marketbot.core.kalshi.priceRanges
import com.marketbot.core.kalshi.settlementPnl
import com.marketbot.core.kalshi.tickSize
import com.marketbot.core.polymarket.PmBook
import com.marketbot.core.polymarket.PmMarketMeta
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.polymarket.pmIntentFor
import com.marketbot.core.provenance.Source
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.strategy.BookFillTracker
import com.marketbot.core.strategy.TapeFillModel
import com.marketbot.core.util.ET
import com.marketbot.core.util.num
import com.marketbot.core.util.objects
import com.marketbot.core.util.str
import java.time.Instant
import java.time.ZonedDateTime

/** Paper or live fill reading for one quote. */
data class FillRead(val filled: Double, val source: String)

/**
 * What the deep-ITM engine needs from a venue. Each value it returns is the
 * venue's own field where one exists.
 */
interface LadderVenue {
    val venueKey: String
    val label: String
    suspend fun tradingOpen(): Boolean

    /** The strikes of the ladder that settles next inside the rule's window, or empty when none does. */
    suspend fun ladder(now: Instant, spot: Double, sigma: Double): List<LadderMarket>

    suspend fun paperFill(q: QuoteRow, now: Instant, book: LadderMarket?): FillRead?

    /** The market result once settled ("yes"/"no"), else null. Always the venue's field. */
    suspend fun result(ticker: String): Sourced<String>?

    // live
    /** Rest a bid that the venue itself cancels at [expiresAt] unless renewed (the dead-man's switch). */
    suspend fun place(i: Intent, m: LadderMarket, expiresAt: Instant): Pair<String?, String?>

    /**
     * Push a resting order's expiry out to [expiresAt] without losing queue position.
     * Returns false when the venue cannot do that; the caller then lets the order lapse
     * and re-places it.
     */
    suspend fun renew(q: QuoteRow, expiresAt: Instant): Boolean
    suspend fun cancel(q: QuoteRow)
    suspend fun liveFill(q: QuoteRow): FillRead?
    suspend fun livePnl(q: QuoteRow, result: String): Sourced<Double>?
    suspend fun preflight(): List<Pair<String, Boolean>>
}

// =========================================================================== Kalshi KXBTCD
private const val TAPE_TTL_S = 15L

class KalshiBtcLadder(private val k: KalshiClient, private val series: String = "KXBTCD") : LadderVenue {
    override val venueKey = "kalshi"
    override val label = "Kalshi $series"
    private var cachedEvent: String? = null
    private var settlements: Map<String, kotlinx.serialization.json.JsonObject> = emptyMap()
    private var settlementsAt = Instant.EPOCH

    /** The crypto shard. KXBTCD markets carry exchange_index 2; the status read is per shard. */
    override suspend fun tradingOpen(): Boolean = k.tradingActive(com.marketbot.core.contract.Vocabulary.KXBTCD_SHARD).value

    override suspend fun ladder(now: Instant, spot: Double, sigma: Double): List<LadderMarket> {
        val (event, close) = DeepItmRule.nextHourEvent(now, series)
        cachedEvent = event
        // Only markets closing at this hour: on the night clocks go back, two hours carry the same ET label,
        // and the listing for the second must never return the first one's (already closed) strikes.
        return k.eventMarkets(event).mapNotNull { toLadder(it) }.filter { kotlin.math.abs(java.time.Duration.between(it.close, close).seconds) <= 60 }
    }

    private class Tape(val minTs: Long, val at: Instant, val trades: List<kotlinx.serialization.json.JsonObject>)
    private val tapes = mutableMapOf<String, Tape>()

    /**
     * The public tape since [minTs], shared by every quote on [ticker] for [TAPE_TTL_S].
     * Re-reading up to 20,000 trades per quote on every 2-s pass churned the heap
     * and the data plan; the fill model filters by each quote's own placement time.
     */
    private suspend fun tape(ticker: String, minTs: Long, now: Instant): List<kotlinx.serialization.json.JsonObject> {
        tapes.values.removeIf { java.time.Duration.between(it.at, now).seconds > 600 }
        tapes[ticker]?.takeIf { it.minTs <= minTs && java.time.Duration.between(it.at, now).seconds < TAPE_TTL_S }?.let { return it.trades }
        return k.trades(ticker, minTs).also { tapes[ticker] = Tape(minTs, now, it) }
    }

    internal val cachedTapes get() = tapes.size
    init { com.marketbot.core.util.Diagnostics.track(this, "Kalshi BTC paper tapes cached") { v -> v.tapes.values.sumOf { it.trades.size } } }

    override suspend fun paperFill(q: QuoteRow, now: Instant, book: LadderMarket?): FillRead? {
        val trades = tape(q.ticker, q.placedAt.epochSecond, now)
        val side = if (q.action == "buy_yes") "yes" else "no"
        val f = TapeFillModel.fill(q.placedAt, q.cancelledAt, side, q.price, q.contracts, q.queueAhead ?: 0.0, trades)
        return FillRead(f.filled, "paper: Kalshi public tape, FIFO")
    }

    override suspend fun result(ticker: String): Sourced<String>? {
        val m = k.market(ticker)
        val st = m.status?.lowercase()
        return if (m.result in setOf("yes", "no") && st in setOf("settled", "finalized", "determined"))
            Sourced.kalshi(m.result!!, "GET /markets/$ticker#result") else null
    }

    override suspend fun place(i: Intent, m: LadderMarket, expiresAt: Instant): Pair<String?, String?> {
        // buy YES at q = YES bid at q; buy NO at q = YES ask at 1 - q.
        val (side, px) = if (i.side == "yes") "bid" to i.price else "ask" to (1.0 - i.price)
        val p = k.placeOrder(i.ticker, side, i.count, px, m.tick, m.exchangeIndex, expirationEpochS = expiresAt.epochSecond)
        if (p.filtered) return null to null
        return p.orderId to p.clientOrderId
    }

    override suspend fun renew(q: QuoteRow, expiresAt: Instant): Boolean {
        val id = q.orderId ?: return false
        val (side, px) = if (q.action == "buy_yes") "bid" to q.price else "ask" to (1.0 - q.price)
        val tick = (q.extra["tick"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.01
        val shard = (q.extra["exchange_index"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
        return runCatching { k.renewExpiration(id, q.ticker, side, px, tick, q.contracts, shard, expiresAt.epochSecond) }.isSuccess
    }

    override suspend fun cancel(q: QuoteRow) { q.orderId?.let { k.cancelOrder(it, q.ticker) } }

    override suspend fun liveFill(q: QuoteRow): FillRead? {
        val o = q.orderId?.let { k.order(it) } ?: return null
        val f = FillState.of(o)
        val n = f.contracts ?: if (f.filled == true) q.contracts else return null
        return FillRead(n, "Kalshi API ${f.origin}")
    }

    override suspend fun livePnl(q: QuoteRow, result: String): Sourced<Double>? {
        if (java.time.Duration.between(settlementsAt, Instant.now()).seconds > 60) {
            settlements = k.settlementsByTicker(maxPages = 5); settlementsAt = Instant.now()
        }
        return settlements[q.ticker]?.let { settlementPnl(it) }
    }

    override suspend fun preflight(): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        val b = k.balance(2)
        out += "Crypto shard (index 2) cash: \$${"%.2f".format(b.cash.value)} from ${b.cash.origin}" to (b.cash.value >= 5.0)
        val fee = k.seriesFeeType(series)
        out += "$series fee type: ${fee?.value}" to (fee?.value == "quadratic")
        val (event, _) = DeepItmRule.nextHourEvent(Instant.now(), series)
        val ms = k.eventMarkets(event)
        out += "List $event (${ms.size} strikes)" to ms.isNotEmpty()
        val shard = ms.firstOrNull()?.exchangeIndex
        out += "Markets report exchange_index $shard (orders are routed there)" to (shard != null)
        val target = ms.mapNotNull { toLadder(it) }.firstOrNull { (it.yesBid ?: 0.0) > 0.05 }
        if (target != null) {
            val placed = k.placeOrder(target.ticker, "bid", 1, 0.01, target.tick, target.exchangeIndex)
            out += "Place 1-contract post-only 1c YES bid on ${target.ticker} on shard $shard" to (placed.orderId != null)
            placed.orderId?.let {
                out += "Exchange shows it on shard ${k.order(it)?.num("exchange_index")?.toInt()}" to true
                out += "Cancel it (reduced by ${k.cancelOrder(it, target.ticker)?.value})" to true
            }
        } else out += "No strike with a bid above 5c to test on right now" to false
        return out
    }

    private fun toLadder(m: KalshiMarket): LadderMarket? {
        if (m.strikeType != com.marketbot.core.contract.Vocabulary.KXBTCD_STRIKE_TYPE) return null
        val strike = m.floorStrike ?: return null
        val close = m.closeTime ?: return null
        return LadderMarket(m.ticker, strike, close, m.num("yes_bid_dollars"), m.num("no_bid_dollars"),
            m.num("yes_bid_size_fp"), m.num("yes_ask_size_fp"), m.exchangeIndex, tickSize(0.5, m.raw.priceRanges()).value)
    }
}

// =========================================================================== Polymarket "BTC above $X at 5PM ET"
/**
 * Polymarket US lists a daily "BTC price at 5:00PM ET" ladder
 * (`btc-above-day-YYYY-MM-DD`), settling on the 60-second average of CF BRTI.
 * No hourly ladder exists there, so this variant is active only in the
 * 300-3000 s before 5 PM ET. NEVER MEASURED: the original rule was calibrated
 * on Kalshi's hourly ladder. Paper first.
 */
class PolymarketBtcLadder(private val pm: PolymarketClient) : LadderVenue {
    /** Set from the strategy's setting on every pass. */
    var allowNoLive: Boolean = false
    override val venueKey = "polymarket"
    override val label = "Polymarket BTC 5PM ET"
    // Bounded: one tracker per open quote, and books only while they are fresh enough to reuse.
    private val trackers = object : LinkedHashMap<Long, BookFillTracker>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, BookFillTracker>?) = size > 500
    }
    private val books = mutableMapOf<String, PmBook>()
    internal val cachedBooks get() = books.size
    init { com.marketbot.core.util.Diagnostics.track(this, "Polymarket BTC books + fill trackers") { it.books.size + it.trackers.size } }

    override suspend fun tradingOpen() = true

    fun eventSlug(now: Instant): Pair<String, Instant> {
        var day = ZonedDateTime.ofInstant(now, ET).withHour(17).withMinute(0).withSecond(0).withNano(0)
        if (!day.toInstant().isAfter(now)) day = day.plusDays(1)
        return (com.marketbot.core.contract.Vocabulary.PM_BTC_EVENT_PREFIX + "%04d-%02d-%02d").format(day.year, day.monthValue, day.dayOfMonth) to day.toInstant()
    }

    override suspend fun ladder(now: Instant, spot: Double, sigma: Double): List<LadderMarket> {
        val (slug, close) = eventSlug(now)
        val ttc = (close.toEpochMilli() - now.toEpochMilli()) / 1000.0
        if (ttc > 3100) return emptyList()           // outside the window: do not spend requests
        // The event payload already carries each market's assetPriceTerms (strike, windowEnd): one request for the ladder.
        val metas = pm.event(slug).objects("markets").map { PmMarketMeta(it) }.filter { !it.closed && it.active && it.strike != null }
        // Screen by distance first, so only strikes that can be deep in the money cost a book request.
        val near = metas.filter { m ->
            val fy = DeepItmRule.fairYes(spot, m.strike!!, sigma, ttc)?.first ?: return@filter false
            fy >= 0.95 || fy <= 0.05
        }.filter { m -> val fy = DeepItmRule.fairYes(spot, m.strike!!, sigma, ttc)!!.first; fy < 0.99995 && fy > 0.00005 }
        return near.take(16).map { m ->
            val b = pm.book(m.slug).also { books[m.slug] = it }
            LadderMarket(m.slug, m.strike!!, m.windowEnd ?: close,
                yesBid = b.bestBid, noBid = b.bestAsk?.let { 1.0 - it },
                yesBidSize = b.bids.firstOrNull()?.qty, noBidSize = b.asks.firstOrNull()?.qty, tick = m.tick)
        }
    }

    override suspend fun paperFill(q: QuoteRow, now: Instant, book: LadderMarket?): FillRead? {
        books.values.removeIf { java.time.Duration.between(it.at, now).seconds > 600 }
        val b = books[q.ticker]?.takeIf { java.time.Duration.between(it.at, now).seconds < 5 } ?: pm.book(q.ticker).also { books[q.ticker] = it }
        // buy YES = rest a YES bid at q; buy NO at q = rest a YES ask at 1 - q.
        val (side, px) = if (q.action == "buy_yes") "bid" to q.price else "ask" to (1.0 - q.price)
        val t = trackers.getOrPut(q.id) { BookFillTracker(q.queueAhead ?: 0.0, q.queueAhead ?: 0.0) }
        val already = q.filledContracts ?: 0.0
        val add = if (q.cancelledAt == null) t.onBook(side, px, q.contracts - already, b.bids, b.asks) else 0.0
        return FillRead(already + add, "paper: Polymarket book snapshots (optimistic: cancels look like trades)")
    }

    override suspend fun result(ticker: String): Sourced<String>? =
        pm.settlement(ticker)?.let { s -> Sourced(if (s.value >= 0.5) "yes" else "no", Source.POLYMARKET_API, s.origin + " (value ${s.value})") }

    override suspend fun place(i: Intent, m: LadderMarket, expiresAt: Instant): Pair<String?, String?> {
        if (i.side == "no" && !allowNoLive) return null to null
        val intent = pmIntentFor(if (i.side == "yes") "bid" else "ask", 0.0, false)
        val wire = if (i.side == "yes") i.price else 1.0 - i.price
        return pm.placeOrderRecovering(i.ticker, intent, wire, i.count.toDouble(), expiresAt) to null
    }

    /** Polymarket can modify price and size but not move an expiry; the order lapses and is re-placed. */
    override suspend fun renew(q: QuoteRow, expiresAt: Instant): Boolean = false

    override suspend fun cancel(q: QuoteRow) { q.orderId?.let { pm.cancel(it, q.ticker) } }

    override suspend fun liveFill(q: QuoteRow): FillRead? {
        val o = q.orderId?.let { pm.order(it) } ?: return null
        val n = o.num("cumQuantity") ?: return null
        return FillRead(n, "Polymarket API order#cumQuantity")
    }

    override suspend fun livePnl(q: QuoteRow, result: String): Sourced<Double>? {
        val v = pm.settlement(q.ticker) ?: return null
        val n = q.filledContracts ?: return null
        val payout = if (q.action == "buy_yes") v.value else 1.0 - v.value
        return Sourced.derived(n * (payout - q.price), "filled x (settlement value - price)",
            "settlement value from the API (${v.origin}); Polymarket reports no per-position realised P&L field")
    }

    override suspend fun preflight(): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        val b = pm.balance()
        out += "Read balance: \$${"%.2f".format(b.currentBalance.value)} from ${b.currentBalance.origin}" to true
        val (slug, _) = eventSlug(Instant.now())
        val ev = runCatching { pm.event(slug) }.getOrNull()
        out += "Find today's ladder $slug" to (ev != null)
        return out
    }
}
