package com.marketbot.core.strategies.longshot

import com.marketbot.core.contract.Vocabulary
import com.marketbot.core.kalshi.FillState
import com.marketbot.core.kalshi.makerFeeEstimate
import com.marketbot.core.kalshi.positionPnl
import com.marketbot.core.kalshi.priceRanges
import com.marketbot.core.kalshi.settlementPnl
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
import com.marketbot.core.util.num
import com.marketbot.core.util.str
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
 * silver, copper) priced one hour before close.
 *
 * Live since 2026-11-14, on the settled-history measurement rather than the paper book: every
 * settled market of all 12 series priced at a 60-minute lead with zero lookahead is 2,733 markets
 * over 49 close-dates with **29 losses against 135.0 implied** (ratio 0.21, +3.88c/contract,
 * CI [+3.47, +4.22], 48 of 49 days positive). The paper book agrees in sign but holds too few
 * losses to resolve a trade whose result is dominated by rare losses, which is the repo's standing
 * rule: count the losses, not the fills.
 */
class KalshiCommodityLongshot(id: String = ID, baseId: String? = null) : KalshiPaperRange(id, baseId) {
    override val name = variantName("Commodity range longshot", id, baseId)
    override val venue = Venue.KALSHI
    override val venueKey = "kalshi"
    override val apiSource = Source.KALSHI_API
    override val measuredLead = "the market's close"
    override val summary = "Sells the cheap side of Kalshi's commodity price ranges by resting at the maker price, about an hour before close."
    override val liveCapable = true
    override val help = Help(
        makes = "In the last hour a commodity range that is nearly decided still prices its long shots too high: over 2,733 settled markets they settled YES at about a fifth of the implied rate.",
        loses = "A loss pays the full dollar against a few cents of premium, so one bad market costs what twenty good ones make. The last hour is also when prices move fastest.",
        notes = listOf(
            "Series: Brent, WTI, natural gas, gold, silver and copper, daily and weekly ranges.",
            "Offers rest from 40 to 90 minutes before close; unfilled ones are withdrawn at close.",
            "The edge DECAYS with lead here, unlike the weather book, where it grows: this is residual premium on an almost-decided contract, so the window is not a preference.",
            "Live fills can be worse than the measurement: a paper fill never sat in the real book. Start small and watch the loss ratio against 0.21.",
        ),
    )
    override val evidence = Evidence(
        EvidenceLevel.BACKTEST_ONLY,
        "Every settled market of all 12 series at a 60-minute lead with zero lookahead: 29 losses against 135.0 implied (ratio 0.21), +3.88c/contract, 95% CI [+3.47, +4.22].",
        listOf(
            "2,733 markets over 49 close-dates; 48 of 49 days positive, worst day -3.6c/contract; all 12 series positive, weakest +2.97c.",
            "Holds across halves (0.18 then 0.23). Clustered by close-date, because a day's strikes settle off one price move.",
            "Decays with lead: ratio 0.72 at 12 hours, where WTI and gold read ABOVE fair. Matches weatherbot's finding that financial ranges reverse a day out.",
            "Not measured live: a resting order that really sits in the book can be adversely selected. On the weather book that took a 0.73 population ratio to 0.96 on fills.",
        ),
        SEARCH,
        Expectation(0.0388, 0.0347, 0.0422, "all 12 series, every settled market priced at a 60-min lead with zero lookahead (2,733 markets, 49 close-dates); CI clustered by close-date; resting fills pay no fee"),
    )
    override val defaultMinLeadMin = 40
    override val defaultMaxLeadMin = 90
    override val defaultEveryMin = 10

    override val defaultSeries get() = SERIES.joinToString(",")
    override val seriesHelp get() = "Kalshi series to trade, comma-separated. These twelve are the ones the search measured."

    override fun ownsMarket(venue: String, ticker: String) = venue == "kalshi" && ticker.substringBefore('-') in SERIES

    override suspend fun preflight(ctx: StrategyContext): PreflightResult {
        val steps = mutableListOf<Pair<String, Boolean>>()
        var anyShard: Int? = null
        for (s in listOf("KXGOLDD", "KXBRENTD")) {
            val ms = runCatching { ctx.kalshi.openMarkets(s) }
            steps += "List open $s markets (${ms.getOrNull()?.size ?: "failed: ${ms.exceptionOrNull()?.message}"})" to (ms.isSuccess)
            ms.getOrNull()?.firstOrNull()?.exchangeIndex?.let { anyShard = it }
            val fee = runCatching { ctx.kalshi.seriesFeeType(s) }.getOrNull()
            steps += "$s fee type is quadratic (makers pay nothing): ${fee?.value}" to (fee?.value == "quadratic")
        }
        if (!ctx.kalshi.hasCredentials) {
            steps += "Kalshi API key saved (needed for the live checks)" to false
            return PreflightResult(false, steps, "Add your Kalshi key under Accounts to check the live path.")
        }
        // Orders are routed to the shard the markets name, and Kalshi does not fund a shard
        // automatically: cash sitting on the default shard cannot back an order here.
        steps += "Markets report an exchange shard (orders route there): ${anyShard ?: "none"}" to (anyShard != null)
        val sh = anyShard
        if (sh != null) {
            val b = runCatching { ctx.kalshi.balance(sh) }.getOrNull()
            val cash = b?.byExchangeIndex?.get(sh)?.value ?: b?.cash?.value
            steps += "Shard $sh cash: ${cash?.let { "$" + "%.2f".format(it) } ?: "unreadable"}" to ((cash ?: 0.0) >= 5.0)
            steps += "Shard $sh trading is open" to (runCatching { ctx.kalshi.tradingActive(sh).value }.getOrDefault(false))
        }
        val ok = steps.all { it.second }
        if (ok) ctx.stores.state.put("$id.preflight_ok_at", ctx.clock.now().toString())
        ctx.info("preflight", "Preflight ${if (ok) "passed" else "FAILED"}: ${steps.joinToString("; ") { (s, k) -> (if (k) "ok " else "FAIL ") + s }}")
        return PreflightResult(ok, steps, if (ok) "Ready to trade." else "Kalshi did not answer as expected; live stays blocked.")
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
                    m.strikeType,
                    tick = com.marketbot.core.kalshi.tickSize(book?.yesAsk?.value ?: 0.5, m.raw.priceRanges()).value,
                    exchangeIndex = m.exchangeIndex)
                m.exchangeIndex?.let { shardSeen = it }
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

    // ----------------------------------------------------------- the live path
    // Every call below mirrors KalshiBtcLadder, which is the order path this account has already
    // run live. Commodities sit on the same non-default exchange shard as the crypto ladder, and
    // the shard is read off the markets themselves rather than assumed.

    /** The shard the listing reported, remembered for the balance and preflight reads. */
    private var shardSeen: Int? = null
    protected val shard: Int? get() = shardSeen

    override fun venueHasCredentials(ctx: StrategyContext) = ctx.kalshi.hasCredentials

    private fun shardOf(q: QuoteRow): Int? =
        (q.extra["exchange_index"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: shardSeen

    /**
     * Rest the cheap side post-only. Kalshi's `side` names the YES book: selling YES at p is the
     * YES ASK at p; buying YES at p is the YES BID at p; buying NO at p is the YES ask at 1 - p.
     * Not retried on failure -- `placeOrder` recovers a lost response by client_order_id itself.
     */
    override suspend fun place(ctx: StrategyContext, m: RangeCandidate, p: CheapPick, n: Int): Pair<String?, String?>? {
        val (side, px) = when (p.action) {
            "sell_yes" -> "ask" to p.price
            "buy_no" -> "ask" to (1.0 - p.price)
            else -> "bid" to p.price
        }
        val placed = ctx.kalshi.placeOrder(m.ticker, side, n, px, m.tick ?: 0.01, m.exchangeIndex,
            postOnly = !p.taker, expirationEpochS = null)
        if (placed.filtered) return null
        return placed.orderId to placed.clientOrderId
    }

    override suspend fun cancel(ctx: StrategyContext, q: QuoteRow) {
        val id = q.orderId ?: return
        ctx.kalshi.cancelOrder(id, q.ticker)
    }

    override suspend fun liveFill(ctx: StrategyContext, q: QuoteRow): Sourced<Double>? {
        val o = q.orderId?.let { ctx.kalshi.order(it) } ?: return null
        val f = FillState.of(o)
        val n = f.contracts ?: if (f.filled == true) q.contracts else return null
        return Sourced.kalshi(n, "GET /portfolio/orders/${q.orderId} ${f.origin}", ctx.clock.now())
    }

    /**
     * The exchange's own realised figure, from whichever of its three stores still holds this
     * market: `/portfolio/settlements` returns only records still in the LIVE tier, so a settled
     * position that has been archived is absent there and present in one of the other two.
     */
    override suspend fun livePnl(ctx: StrategyContext, q: QuoteRow, result: String): Sourced<Double>? {
        runCatching { ctx.kalshi.settlementFor(q.ticker) }.getOrNull()?.let { return settlementPnl(it) }
        runCatching { ctx.kalshi.positions(settlementStatus = "settled", ticker = q.ticker) }.getOrNull()
            ?.firstOrNull { it.str("ticker") == q.ticker }
            ?.let { positionPnl(it, "/portfolio/positions?settlement_status=settled") }?.let { return it }
        return runCatching { ctx.kalshi.historicalPositions(ticker = q.ticker) }.getOrNull()
            ?.firstOrNull { it.str("ticker") == q.ticker }
            ?.let { positionPnl(it, "/historical/positions") }
    }

    override suspend fun confirmedFills(ctx: StrategyContext, q: QuoteRow): Sourced<Double>? {
        val id = q.orderId ?: return null
        val sh = shardOf(q)
        val fills = ctx.kalshi.fills(ticker = q.ticker, maxPages = 5, orderId = id, exchangeIndex = sh)
            .filter { (it.str("order_id") ?: id) == id }
        val n = fills.sumOf { it.num("count_fp") ?: it.num("count") ?: 0.0 }
        return Sourced.kalshi(n, "GET /portfolio/fills?order_id=$id${sh?.let { "&exchange_index=$it" } ?: ""} (${fills.size} fills)", ctx.clock.now())
    }

    /**
     * Free cash on the shard these markets route to. Orders are funded per shard and the exchange
     * does not move money between them, so the account-wide figure would overstate what can back
     * an order here. Resting collateral is subtracted by the caller; filled positions are already
     * out of this number.
     */
    override suspend fun liveEquity(ctx: StrategyContext): Sourced<Double>? {
        val b = runCatching { ctx.kalshi.balance(shardSeen) }.getOrNull() ?: return null
        shardSeen?.let { s -> b.byExchangeIndex[s]?.let { return it } }
        return b.cash
    }
}
