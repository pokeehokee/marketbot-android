package com.marketbot.core.strategies.longshot

import com.marketbot.core.contract.Vocabulary
import com.marketbot.core.kalshi.makerFeeEstimate
import com.marketbot.core.polymarket.PmBook
import com.marketbot.core.provenance.Source
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.strategy.BookFillTracker
import com.marketbot.core.strategy.Evidence
import com.marketbot.core.strategy.EvidenceLevel
import com.marketbot.core.strategy.Expectation
import com.marketbot.core.strategy.Help
import com.marketbot.core.strategy.Param
import com.marketbot.core.strategy.PreflightResult
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.strategy.TapeFillModel
import com.marketbot.core.strategy.Venue
import com.marketbot.core.strategy.variantName
import java.time.Instant

private const val SEARCH = "weatherbot scripts/pool_search/results (2026-10-29)"

/**
 * Candidate 1 of the 2026-10-29 pool search: the weather longshot's trade on Polymarket US's daily
 * temperature buckets (NYC high/low, Miami, Chicago Midway high/low, LA, SF, Philadelphia), priced
 * one hour before the measured day begins. Paper only.
 */
class PolymarketTemperatureLongshot(id: String = ID, baseId: String? = null) : PaperRangeLongshot(id, baseId) {
    override val name = variantName("Polymarket temperature longshot", id, baseId)
    override val venue = Venue.POLYMARKET
    override val venueKey = "polymarket"
    override val apiSource = Source.POLYMARKET_API
    override val measuredLead = "the start of the day being measured"
    override val summary = "Paper test: sells the cheap side of Polymarket US's daily temperature buckets by resting at the maker price, an hour before the day begins."
    override val help = Help(
        makes = "The same bias as the weather longshot, on a second exchange: cheap temperature buckets settle YES less often than their price says. " +
            "In the history it settled YES at 0.58 of the implied rate. The order rests at the maker price; Polymarket charges makers nothing and pays a rebate.",
        loses = "A bucket that hits pays the full dollar. The books are thinner than Kalshi's (spreads around 5c, some buckets with no bid), " +
            "so fewer markets pass the spread rule and paper fills from book snapshots are optimistic.",
        notes = listOf(
            "Reference time is the start of the measured day (the market's game start); offers rest from 40 to 120 minutes before it and are withdrawn unfilled at it.",
            "Graded from Polymarket's settlement endpoint once the market resolves.",
            "Paper only: there is no live order path until the paper record shows the edge.",
        ),
    )
    override val evidence = Evidence(
        EvidenceLevel.BACKTEST_ONLY,
        "History, held-out newest third: +3.53c/contract (p 0.008) resting at the maker price; +1.44c under the weather adverse-fill stress.",
        listOf(
            "822 markets on 176 days (Apr - Oct 2026), loss ratio 0.58 of implied, one hour before the measured day.",
            "Passed the category test, the neighbouring-lead check and the untouched holdout of the 2026-10-29 search; six hours out it did not survive the holdout.",
            "Live books on 2026-10-29: 46 of 144 open buckets in the cheap band, median 21.5 contracts resting at the ask, median spread 5c, 12 inside the spread rule.",
            "Small: a fraction of Kalshi weather's daily volume.",
        ),
        SEARCH,
        Expectation(0.0353, null, null, "pool search holdout, resting at the maker price; fills unmeasured"),
    )
    override val defaultMinLeadMin = 40
    override val defaultMaxLeadMin = 120
    override val defaultEveryMin = 15

    /** Book snapshots per paper quote (the same optimistic model the Polymarket BTC ladder uses). */
    private val trackers = object : LinkedHashMap<Long, BookFillTracker>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, BookFillTracker>?) = size > 500
    }

    override suspend fun candidates(ctx: StrategyContext): List<RangeCandidate> {
        val now = ctx.clock.now()
        val maxLead = ctx.config.int("max_lead_min").toLong()
        val metas = ctx.polymarket.openMarkets(Vocabulary.PM_TEMP_CATEGORY)
            .filter { Vocabulary.pmTemperature(it.slug) != null && it.active && !it.closed }
        val out = mutableListOf<RangeCandidate>()
        for (m in metas) {
            val ref = m.gameStart ?: continue
            val lead = java.time.Duration.between(now, ref).toMinutes()
            // Only markets inside (or about to enter) the window cost a book request.
            if (lead < 0 || lead > maxLead) { out += RangeCandidate(m.slug, null, null, ref, m.endDate, null, null, Vocabulary.pmTemperature(m.slug)?.second); continue }
            val b: PmBook = runCatching { ctx.polymarket.book(m.slug) }.getOrNull() ?: continue
            out += RangeCandidate(m.slug, b.bestBid, b.bestAsk, ref, m.endDate, b.asks.firstOrNull()?.qty, b.bids.firstOrNull()?.qty,
                Vocabulary.pmTemperature(m.slug)?.second)
        }
        return out
    }

    override suspend fun paperFill(ctx: StrategyContext, q: QuoteRow): Double? {
        val b = runCatching { ctx.polymarket.book(q.ticker) }.getOrNull() ?: return null
        val side = if (q.action == "sell_yes") "ask" else "bid"
        val t = trackers.getOrPut(q.id) { BookFillTracker(q.queueAhead ?: 0.0, q.queueAhead ?: 0.0) }
        val already = q.filledContracts ?: 0.0
        return already + t.onBook(side, q.price, q.contracts - already, b.bids, b.asks)
    }

    override suspend fun result(ctx: StrategyContext, ticker: String): String? {
        val v = ctx.polymarket.settlement(ticker)?.value ?: return null
        return when { v >= 0.99 -> "yes"; v <= 0.01 -> "no"; else -> null }
    }

    override fun ownsMarket(venue: String, ticker: String) = venue == "polymarket" && ticker.startsWith(Vocabulary.PM_TEMP_PREFIX)

    override suspend fun preflight(ctx: StrategyContext): PreflightResult {
        val steps = mutableListOf<Pair<String, Boolean>>()
        val ms = runCatching { ctx.polymarket.openMarkets(Vocabulary.PM_TEMP_CATEGORY).filter { Vocabulary.pmTemperature(it.slug) != null } }
        steps += "List open temperature buckets (${ms.getOrNull()?.size ?: "failed: ${ms.exceptionOrNull()?.message}"})" to (ms.getOrNull()?.isNotEmpty() == true)
        ms.getOrNull()?.firstOrNull()?.let { m ->
            val b = runCatching { ctx.polymarket.book(m.slug) }.getOrNull()
            steps += "Read a book (${m.slug})" to (b != null)
            steps += "Game start (the reference time) is set" to (m.gameStart != null)
        }
        val ok = steps.all { it.second }
        return PreflightResult(ok, steps, if (ok) "Ready to paper-trade." else "Polymarket did not answer as expected.")
    }

    companion object { const val ID = "polymarket_temperature_longshot" }
}

/**
 * Candidate 2 of the pool search: Kalshi's daily and weekly commodity ranges (oil, gas, gold,
 * silver, copper) priced one hour before close. Thin evidence (7 losses against ~21 implied), so
 * paper only, and the price paid for being wrong is the paper account's.
 */
class KalshiCommodityLongshot(id: String = ID, baseId: String? = null) : KalshiPaperRange(id, baseId) {
    override val name = variantName("Commodity range longshot", id, baseId)
    override val venue = Venue.KALSHI
    override val venueKey = "kalshi"
    override val apiSource = Source.KALSHI_API
    override val measuredLead = "the market's close"
    override val summary = "Paper test: sells the cheap side of Kalshi's commodity price ranges by resting at the maker price, about an hour before close."
    override val help = Help(
        makes = "In the last hour a commodity range that is nearly decided still prices its long shots too high: in the history they settled YES at about a quarter of the implied rate.",
        loses = "The evidence is seven losses: real enough to test, too few to trust. A loss pays the full dollar, and the last hour is when prices move fastest.",
        notes = listOf(
            "Series: Brent, WTI, natural gas, gold, silver and copper, daily and weekly ranges.",
            "Offers rest from 40 to 90 minutes before close; unfilled ones are withdrawn at close. Paper fills come from Kalshi's public tape.",
            "Paper only: there is no live order path until the paper record shows the edge.",
        ),
    )
    override val evidence = Evidence(
        EvidenceLevel.BACKTEST_ONLY,
        "History through 2026-10-29, held-out newest third: +6.69c/contract (p 0.031) one hour before close; +5.44c under the weather adverse-fill stress.",
        listOf(
            "220 markets on 127 days across 12 series; losses at 0.23 of the rate the prices implied.",
            "Matches weatherbot's 2026-09-08 finding: the deepest bin is overpriced in the last hours, and the bias reverses a day out.",
            "Thin: the holdout p-value is 0.031 and it rests on a handful of losing markets.",
        ),
        SEARCH,
        Expectation(0.0669, null, null, "pool search holdout through 2026-10-29 (220 markets), resting at the maker price; +5.44c with adverse fills; fills unmeasured"),
    )
    override val defaultMinLeadMin = 40
    override val defaultMaxLeadMin = 90
    override val defaultEveryMin = 10

    override val defaultSeries get() = SERIES.joinToString(",")
    override val seriesHelp get() = "Kalshi series to trade, comma-separated. These twelve are the ones the search measured."

    override fun ownsMarket(venue: String, ticker: String) = venue == "kalshi" && ticker.substringBefore('-') in SERIES

    override suspend fun preflight(ctx: StrategyContext): PreflightResult {
        val steps = mutableListOf<Pair<String, Boolean>>()
        for (s in listOf("KXGOLDD", "KXBRENTD")) {
            val ms = runCatching { ctx.kalshi.openMarkets(s) }
            steps += "List open $s markets (${ms.getOrNull()?.size ?: "failed: ${ms.exceptionOrNull()?.message}"})" to (ms.isSuccess)
            val fee = runCatching { ctx.kalshi.seriesFeeType(s) }.getOrNull()
            steps += "$s fee type is quadratic (makers pay nothing): ${fee?.value}" to (fee?.value == "quadratic")
        }
        val ok = steps.all { it.second }
        return PreflightResult(ok, steps, if (ok) "Ready to paper-trade." else "Kalshi did not answer as expected.")
    }

    companion object {
        const val ID = "kalshi_commodity_longshot"
        val SERIES = listOf("KXBRENTD", "KXBRENTW", "KXWTI", "KXWTIW", "KXNATGASD", "KXNATGASW", "KXGOLDD", "KXGOLDW", "KXSILVERD", "KXSILVERW", "KXCOPPERD", "KXCOPPERW")
    }
}

/** The Kalshi side of a paper range strategy: series from settings, the public tape for fills, the market for results. */
abstract class KalshiPaperRange(id: String, baseId: String?) : PaperRangeLongshot(id, baseId) {
    override val venue = Venue.KALSHI
    override val venueKey = "kalshi"
    override val apiSource = Source.KALSHI_API
    open val defaultSeries: String = ""
    open val seriesHelp: String = "Kalshi series to trade, comma-separated (for example KXHIGHNY,KXGOLDD)."

    override val extraParams: List<Param> get() = listOf(Param.Text("series", "Series", seriesHelp, defaultSeries)) + moreParams
    open val moreParams: List<Param> get() = emptyList()

    protected fun series(ctx: StrategyContext) = series(ctx.config)
    protected fun series(c: com.marketbot.core.strategy.StrategyConfig) = c.text("series").split(',', ' ').map { it.trim().uppercase() }.filter { it.isNotEmpty() }

    override suspend fun candidates(ctx: StrategyContext): List<RangeCandidate> {
        val out = mutableListOf<RangeCandidate>()
        for (s in series(ctx)) {
            val ms = runCatching { ctx.kalshi.openMarkets(s) }.onFailure { ctx.warn("listing", "$s listing failed: ${it.message}") }.getOrNull() ?: continue
            for (m in ms) {
                val close = m.closeTime ?: continue
                val book = m.book
                out += RangeCandidate(m.ticker, book?.yesBid?.value, book?.yesAsk?.value, close, close, m.num("yes_ask_size_fp"), m.num("yes_bid_size_fp"), s,
                    m.strikeType)
            }
        }
        return out
    }

    override suspend fun paperFill(ctx: StrategyContext, q: QuoteRow): Double? {
        val trades = runCatching { ctx.kalshi.trades(q.ticker, q.placedAt.epochSecond) }.getOrNull() ?: return null
        // Selling YES at p is buying NO at 1 - p; buying YES or NO rests on that side at p.
        val f = when (q.action) {
            "sell_yes" -> TapeFillModel.fill(q.placedAt, q.cancelledAt, "no", 1.0 - q.price, q.contracts, q.queueAhead ?: 0.0, trades)
            "buy_no" -> TapeFillModel.fill(q.placedAt, q.cancelledAt, "no", q.price, q.contracts, q.queueAhead ?: 0.0, trades)
            else -> TapeFillModel.fill(q.placedAt, q.cancelledAt, "yes", q.price, q.contracts, q.queueAhead ?: 0.0, trades)
        }
        return f.filled
    }

    override suspend fun result(ctx: StrategyContext, ticker: String): String? {
        val m = ctx.kalshi.market(ticker)
        val st = m.status?.lowercase() ?: return null
        return if (st in Vocabulary.KALSHI_SETTLED && (m.result == "yes" || m.result == "no")) m.result else null
    }

    private val fees = mutableMapOf<String, Sourced<String>?>()
    override suspend fun makerFee(ctx: StrategyContext, ticker: String, price: Double, contracts: Int): Sourced<Double> {
        val s = ticker.substringBefore('-')
        val ft = fees.getOrPut(s) { runCatching { ctx.kalshi.seriesFeeType(s) }.getOrNull() }
        return makerFeeEstimate(ft, price, maxOf(1, contracts))
    }

    override suspend fun takerFee(ctx: StrategyContext, ticker: String, price: Double, contracts: Int): Sourced<Double> =
        Sourced.derived(com.marketbot.core.kalshi.kalshiFee(price, maxOf(1, contracts)), "ceil(0.07*C*P*(1-P))/C", "Kalshi's taker fee, rounded up per order")
}
