package com.marketbot.core.strategy

import com.marketbot.core.polymarket.Level
import com.marketbot.core.util.num
import com.marketbot.core.util.parseInstant
import com.marketbot.core.util.str
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * Paper fill model on Kalshi's PUBLIC trade tape, the one the deep-ITM rebuild
 * used. A paper quote is credited a fill only by real prints after it was
 * placed (and before it was cancelled), and only after the size that was
 * already resting at its price when it joined has traded (FIFO). A print
 * strictly through its price fills it entirely.
 *
 * Orders are expressed as the outcome BOUGHT and its price:
 *  - buy YES at q  is hit by takers SELLING yes (taker_side "no"), print price = yes_price
 *  - buy NO at q   is hit by takers BUYING yes  (taker_side "yes"), print price = no_price
 * Selling YES at p (the longshot) is buying NO at 1 - p.
 *
 * Measured on the original's live record, this model is PESSIMISTIC for
 * small clips; a paper result that beats live is a reason to look for a bug.
 */
object TapeFillModel {
    /** (volume, strictlyThrough) a print contributes to OUR bid, or (0, false). Port of `qualifying`. */
    fun qualifying(trade: JsonObject, side: String, price: Double): Pair<Double, Boolean> {
        val n = trade.num("count_fp") ?: trade.num("count") ?: 0.0
        val taker = com.marketbot.core.contract.Vocabulary.kalshiTakerSide(trade)
        val px = when {
            side == "yes" && taker == "no" -> trade.num("yes_price_dollars")
            side == "no" && taker == "yes" -> trade.num("no_price_dollars")
            else -> return 0.0 to false
        } ?: return 0.0 to false
        if (px > price + 1e-9) return 0.0 to false
        return n to (px < price - 1e-9)
    }

    data class TapeFill(val filled: Double, val touched: Boolean, val firstFill: String?)

    /** Port of `fill_from_tape`. */
    fun fill(placedAt: Instant, cancelledAt: Instant?, side: String, price: Double, count: Double, queueAhead: Double, trades: List<JsonObject>): TapeFill {
        var vol = 0.0; var filled = 0.0; var touched = false; var first: String? = null
        for (t in trades.sortedBy { it.str("created_time") ?: "" }) {
            val at = parseInstant(t.str("created_time")) ?: continue
            if (!at.isAfter(placedAt) || (cancelledAt != null && at.isAfter(cancelledAt))) continue
            val (n, through) = qualifying(t, side, price)
            if (n <= 0) continue
            touched = true
            vol += n
            val new = if (through) count else min(count, max(0.0, vol - queueAhead))
            if (new > filled) { filled = new; if (first == null) first = t.str("created_time") }
        }
        return TapeFill(filled, touched, first)
    }
}

/**
 * Paper fill model from successive BOOK snapshots, for venues without a
 * public tape (Polymarket US). Port of pmfarm `PaperBroker.on_book` without
 * prints: a shrinking level is assumed to trade from the front, so the size
 * ahead goes first; a quote whose price the opposite side reaches fills
 * entirely. Cancellations and trades look the same in a book, so this
 * over-credits fills when the size ahead cancels -- it is optimistic, and the
 * app says so beside every paper result it produces.
 */
class BookFillTracker(var ahead: Double, var level: Double) {
    /**
     * @param side "bid" (we rest a YES bid) or "ask" (we rest a YES ask)
     * @return contracts filled by this snapshot
     */
    fun onBook(side: String, price: Double, remaining: Double, bids: List<Level>, asks: List<Level>): Double {
        val crossed = if (side == "bid") asks.firstOrNull()?.let { it.price <= price + 1e-9 } == true
        else bids.firstOrNull()?.let { it.price >= price - 1e-9 } == true
        if (crossed) return remaining
        val now = (if (side == "bid") bids else asks).filter { kotlin.math.abs(it.price - price) < 1e-9 }.sumOf { it.qty }
        val shrink = max(0.0, level - now)
        var fill = 0.0
        if (shrink > 0) {
            val used = min(ahead, shrink)
            ahead -= used
            fill = min(remaining, shrink - used)
        }
        level = now
        return fill
    }
}
