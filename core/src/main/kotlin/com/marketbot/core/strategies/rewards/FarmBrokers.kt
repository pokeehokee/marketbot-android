package com.marketbot.core.strategies.rewards

import com.marketbot.core.polymarket.PM_MAKER_THETA
import com.marketbot.core.polymarket.PmBook
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.polymarket.pmTradeFee
import com.marketbot.core.provenance.Source
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.provenance.ValueUnavailable
import com.marketbot.core.util.JSON
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

data class FarmBalance(
    /** Equity for risk: cash + positions valued at what they would fetch now (longs at the bid, shorts at the ask). */
    val equity: Sourced<Double>,
    val freeCash: Sourced<Double>,
    /** The exchange's own valuation (cash + cashValue), shown beside equity for comparison. */
    val exchangeEquity: Sourced<Double>?,
)

data class FarmPosition(val slug: String, val qty: Double, val avgPx: Double)
data class FarmOrder(val id: String, val slug: String, val side: String, val price: Double, val qtyLeft: Double, val intent: String)
data class FarmFill(val slug: String, val side: String, val price: Double, val qty: Double, val why: String)

interface FarmBroker {
    val live: Boolean
    suspend fun balance(books: Map<String, PmBook>): FarmBalance
    suspend fun positions(): Map<String, FarmPosition>
    suspend fun openOrders(): List<FarmOrder>
    suspend fun place(slug: String, side: String, price: Double, qty: Double, intent: String, expires: Instant?, queueAhead: Double): String?
    suspend fun cancel(slug: String, id: String): Boolean
    suspend fun cancelAll()
    /** Paper only: apply a fresh book (marks, queue progress, fills). */
    fun onBook(book: PmBook): List<FarmFill> = emptyList()
    /** Paper only: apply one public trade print. */
    fun onTrade(t: com.marketbot.core.polymarket.PmTrade): List<FarmFill> = emptyList()
    fun settle(slug: String, value: Double): Double? = null
}

/**
 * Real orders through the Polymarket US API. Equity values each position at the
 * public book, never at a guess.
 *
 * Only orders this app placed ([owned], persisted by the caller) are ever seen,
 * repriced or cancelled: orders you place by hand in the Polymarket app are left
 * alone, including by the kill switch. One instance lives for the whole run, so
 * the last good price of each book side survives a momentarily empty book.
 */
class LiveFarmBroker(
    private val pm: PolymarketClient,
    val owned: MutableSet<String> = mutableSetOf(),
    private val persist: suspend (Set<String>) -> Unit = {},
) : FarmBroker {
    override val live = true
    private val lastMarks = mutableMapOf<String, Pair<Double?, Double?>>()

    override suspend fun balance(books: Map<String, PmBook>): FarmBalance {
        val b = pm.balance()
        val pos = pm.positions()
        var held = 0.0
        for (p in pos) {
            val book = books[p.slug] ?: runCatching { pm.book(p.slug) }.getOrNull()
            val prev = lastMarks[p.slug] ?: (null to null)
            val bid = book?.bestBid ?: prev.first; val ask = book?.bestAsk ?: prev.second
            lastMarks[p.slug] = bid to ask
            val mark = (if (p.qty.value > 0) bid else ask) ?: throw ValueUnavailable("mark for ${p.slug}", "no ${if (p.qty.value > 0) "bid" else "ask"} ever seen")
            held += p.qty.value * mark
        }
        val cashValue = if (pos.all { it.cashValue != null }) pos.sumOf { it.cashValue!!.value } else null
        val free = minOf(b.buyingPower.value + (b.openOrders?.value ?: 0.0), b.currentBalance.value)
        return FarmBalance(
            Sourced.derived(b.currentBalance.value + held, "currentBalance + sum(qty x bid|ask from the public book)",
                "liquidation value; the exchange's cashValue marks every position at the ask, which overstates longs by the spread"),
            Sourced.derived(free, "min(buyingPower + openOrders, currentBalance)", "buying power semantics are partly undocumented; clamped so a misread can only under-size"),
            cashValue?.let { Sourced(b.currentBalance.value + it, Source.POLYMARKET_API, "GET /v1/account/balances#currentBalance + positions#cashValue") },
        )
    }

    override suspend fun positions() = pm.positions().associate {
        it.slug to FarmPosition(it.slug, it.qty.value, it.avgPx?.value ?: throw ValueUnavailable("avgPx ${it.slug}", "missing"))
    }

    override suspend fun openOrders(): List<FarmOrder> {
        val open = pm.openOrders()
        val ids = open.map { it.id }.toSet()
        // Forget ids the exchange no longer lists as open (filled, expired, cancelled).
        if (owned.retainAll(ids)) persist(owned.toSet())
        return open.filter { it.id in owned }.map { FarmOrder(it.id, it.slug, it.side, it.price, it.qtyLeft, it.intent) }
    }

    override suspend fun place(slug: String, side: String, price: Double, qty: Double, intent: String, expires: Instant?, queueAhead: Double): String? {
        val id = runCatching { pm.placeOrderRecovering(slug, intent, price, qty, expires, known = owned.toSet()) }.getOrNull() ?: return null
        owned += id
        persist(owned.toSet())
        return id
    }

    override suspend fun cancel(slug: String, id: String) = runCatching { pm.cancel(id, slug) }.isSuccess

    /** Cancels this app's orders one by one. Never the account-wide cancel-all. */
    override suspend fun cancelAll() {
        val mine = runCatching { pm.openOrders() }.getOrNull()?.filter { it.id in owned } ?: return
        for (o in mine) runCatching { pm.cancel(o.id, o.slug) }
    }
}

@Serializable
data class PaperOrder(val slug: String, val side: String, val price: Double, var qty: Double, val intent: String,
                      val ts: Long, var ahead: Double, var level: Double, var traded: Double = 0.0)

@Serializable
data class PaperPos(var qty: Double, var avgPx: Double)

@Serializable
data class PaperFarmState(
    var cash: Double,
    val orders: MutableMap<String, PaperOrder> = mutableMapOf(),
    val positions: MutableMap<String, PaperPos> = mutableMapOf(),
    val marks: MutableMap<String, Pair<Double?, Double?>> = mutableMapOf(),
    var realized: Double = 0.0,
    var rebates: Double = 0.0,
    var startingCash: Double = 0.0,
    /** Contracts filled since the start, for the shared ledger's per-day rows. */
    var filled: Double = 0.0,
)

/**
 * Simulated maker orders against live public books, with queue position.
 * Port of pmfarm `PaperBroker` (book-snapshot branch: no trade prints are
 * public on Polymarket US). Positions are valued at their liquidation side;
 * an opening fill that cash cannot fund cancels the order, as the exchange does.
 */
class PaperFarmBroker(var s: PaperFarmState, private val clockMs: () -> Long = { System.currentTimeMillis() }) : FarmBroker {
    override val live = false

    fun toJson(): String = JSON.encodeToString(PaperFarmState.serializer(), s)

    companion object {
        fun fromJson(text: String?, startingCash: Double): PaperFarmBroker =
            PaperFarmBroker(text?.let { runCatching { JSON.decodeFromString(PaperFarmState.serializer(), it) }.getOrNull() }
                ?: PaperFarmState(startingCash, startingCash = startingCash))
    }

    private fun freeCash() = s.cash - s.positions.values.sumOf { maxOf(-it.qty, 0.0) }
    private fun locked(): Double {
        val per = mutableMapOf<String, Double>()
        for (o in s.orders.values) {
            var unit = if (o.side == "bid") o.price else 1 - o.price
            if ("SELL_LONG" in o.intent || "SELL_SHORT" in o.intent) unit = 0.0
            per[o.slug] = maxOf(per[o.slug] ?: 0.0, unit * o.qty)
        }
        return per.values.maxOrNull() ?: 0.0
    }

    override suspend fun balance(books: Map<String, PmBook>): FarmBalance {
        var pv = 0.0
        for ((slug, p) in s.positions) {
            if (abs(p.qty) <= 1e-9) continue
            val m = s.marks[slug] ?: throw ValueUnavailable("paper mark $slug", "never priced")
            val px = (if (p.qty > 0) m.first else m.second) ?: throw ValueUnavailable("paper mark $slug", "side never priced")
            pv += p.qty * px
        }
        return FarmBalance(Sourced.paper(s.cash + pv, "paper cash + positions at bid/ask"),
            Sourced.paper(freeCash() - locked(), "paper cash - short margin - locked"), null)
    }

    override suspend fun positions() = s.positions.filter { abs(it.value.qty) > 1e-9 }.mapValues { FarmPosition(it.key, it.value.qty, it.value.avgPx) }
    override suspend fun openOrders() = s.orders.map { (id, o) -> FarmOrder(id, o.slug, o.side, o.price, o.qty, o.intent) }

    override suspend fun place(slug: String, side: String, price: Double, qty: Double, intent: String, expires: Instant?, queueAhead: Double): String {
        val id = UUID.randomUUID().toString().take(12)
        s.orders[id] = PaperOrder(slug, side, price, qty, intent, clockMs(), maxOf(0.0, queueAhead), maxOf(0.0, queueAhead))
        return id
    }

    override suspend fun cancel(slug: String, id: String): Boolean { s.orders.remove(id); return true }
    override suspend fun cancelAll() { s.orders.clear() }

    private fun openCost(o: PaperOrder, qty: Double) = qty * if (o.side == "bid") o.price else 1 - o.price

    private fun fill(id: String, o: PaperOrder, q0: Double, why: String, out: MutableList<FarmFill>) {
        val qty = minOf(q0, o.qty)
        if (qty <= 1e-9) return
        val cur = s.positions[o.slug]?.qty ?: 0.0
        val after = cur + if (o.side == "bid") qty else -qty
        val m = s.marks[o.slug]
        if (abs(after) > 1e-9 && (if (after > 0) m?.first else m?.second) == null) return
        if ("SELL_" !in o.intent && openCost(o, qty) > freeCash() + 1e-9) { s.orders.remove(id); return }
        apply(o, qty)
        out += FarmFill(o.slug, o.side, o.price, qty, why)
        o.qty -= qty
        if (o.qty <= 1e-9) s.orders.remove(id)
    }

    private fun apply(o: PaperOrder, q: Double) {
        val qty = if (o.side == "bid") q else -q
        val p = s.positions.getOrPut(o.slug) { PaperPos(0.0, o.price) }
        s.cash -= qty * o.price
        val fee = pmTradeFee(PM_MAKER_THETA, q, o.price)
        s.cash -= fee; s.rebates -= fee
        s.filled += q
        val new = p.qty + qty
        if (p.qty == 0.0 || (p.qty > 0) == (qty > 0)) {
            p.avgPx = if (new != 0.0) (p.qty * p.avgPx + qty * o.price) / new else o.price
        } else {
            val closed = minOf(abs(qty), abs(p.qty))
            s.realized += closed * (o.price - p.avgPx) * (if (p.qty > 0) 1 else -1)
            if (abs(qty) > abs(p.qty)) p.avgPx = o.price
        }
        p.qty = new
    }

    /** Markets with a live trade-print feed: their book shrinkage is then split into trades and cancels. */
    private val tradeFed = mutableSetOf<String>()
    fun tradeFed(slug: String) = slug in tradeFed

    /**
     * One public trade print (pmfarm `on_trade`). A print against our side at our
     * price first consumes the size ahead of us, then fills us; a print at a worse
     * price swept through our level and fills us whole.
     */
    override fun onTrade(t: com.marketbot.core.polymarket.PmTrade): List<FarmFill> {
        tradeFed += t.slug
        val fills = mutableListOf<FarmFill>()
        for ((id, o) in s.orders.entries.filter { it.value.slug == t.slug }.sortedBy { it.value.ts }) {
            if (id !in s.orders) continue
            val hitsUs = (o.side == "bid" && t.takerSide == "ORDER_SIDE_SELL") || (o.side == "ask" && t.takerSide == "ORDER_SIDE_BUY")
            if (!hitsUs) continue
            val m = s.marks[t.slug] ?: (null to null)
            if ((if (o.side == "bid") m.first else m.second) == null)
                s.marks[t.slug] = if (o.side == "bid") t.price to m.second else m.first to t.price
            val through = if (o.side == "bid") t.price < o.price - 1e-9 else t.price > o.price + 1e-9
            if (through) fill(id, o, o.qty, "trade-through", fills)
            else if (abs(t.price - o.price) < 1e-9) {
                val used = minOf(o.ahead, t.qty)
                o.ahead -= used
                o.traded += t.qty
                fill(id, o, t.qty - used, "queue", fills)
            }
        }
        val keep = s.positions.filter { abs(it.value.qty) > 1e-9 }.keys + s.orders.values.map { it.slug }
        s.marks.keys.retainAll(keep)
        return fills
    }

    override fun onBook(book: PmBook): List<FarmFill> {
        val live = s.positions.filter { abs(it.value.qty) > 1e-9 }.keys + s.orders.values.map { it.slug }
        if (book.slug in live) {
            val prev = s.marks[book.slug]
            s.marks[book.slug] = (book.bestBid ?: prev?.first) to (book.bestAsk ?: prev?.second)
        }
        val fills = mutableListOf<FarmFill>()
        for ((id, o) in s.orders.entries.toList()) {
            if (o.slug != book.slug || id !in s.orders) continue
            val crossed = (o.side == "bid" && book.bestAsk != null && book.bestAsk!! <= o.price + 1e-9) ||
                (o.side == "ask" && book.bestBid != null && book.bestBid!! >= o.price - 1e-9)
            if (crossed) { fill(id, o, o.qty, "cross", fills); continue }
            val level = (if (o.side == "bid") book.bids else book.asks).filter { abs(it.price - o.price) < 1e-9 }.sumOf { it.qty }
            val prevLevel = o.level
            val shrink = maxOf(0.0, prevLevel - level)
            if (shrink > 0) {
                if (book.slug in tradeFed) {
                    // Prints already consumed the queue; the rest of the shrinkage is cancels, spread over ahead and behind.
                    val cancels = maxOf(0.0, shrink - o.traded)
                    o.ahead = maxOf(0.0, o.ahead - cancels * (if (prevLevel > 0) o.ahead / prevLevel else 0.0))
                } else {
                    // Without prints, trades and cancels look the same: only size beyond what was ahead fills us.
                    val used = minOf(o.ahead, shrink)
                    o.ahead -= used
                    fill(id, o, shrink - used, "queue", fills)
                }
            }
            if (id in s.orders) { o.level = level; o.traded = 0.0 }
        }
        val keep = s.positions.filter { abs(it.value.qty) > 1e-9 }.keys + s.orders.values.map { it.slug }
        s.marks.keys.retainAll(keep)
        return fills
    }

    override fun settle(slug: String, value: Double): Double? {
        s.orders.entries.removeIf { it.value.slug == slug }
        val p = s.positions.remove(slug); s.marks.remove(slug)
        if (p == null || abs(p.qty) < 1e-9) return null
        val pnl = p.qty * (value - p.avgPx)
        s.cash += p.qty * value
        s.realized += pnl
        return pnl
    }
}
