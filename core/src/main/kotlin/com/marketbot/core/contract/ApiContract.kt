package com.marketbot.core.contract

import com.marketbot.core.btc.Venues
import com.marketbot.core.btc.VenueQuote
import com.marketbot.core.kalshi.KalshiBalance
import com.marketbot.core.kalshi.KalshiClient
import com.marketbot.core.kalshi.FillState
import com.marketbot.core.kalshi.TopOfBook
import com.marketbot.core.kalshi.priceRanges
import com.marketbot.core.kalshi.tickSize
import com.marketbot.core.net.ApiException
import com.marketbot.core.net.JsonHttp
import com.marketbot.core.polymarket.PmBook
import com.marketbot.core.polymarket.PmMarketMeta
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.provenance.Source
import com.marketbot.core.strategies.deepitm.DeepItmRule
import com.marketbot.core.strategies.deepitm.PolymarketBtcLadder
import com.marketbot.core.strategies.longshot.WeatherSeries
import com.marketbot.core.strategies.rewards.FarmRules
import com.marketbot.core.strategies.rewards.FarmScanner
import com.marketbot.core.util.Tickers
import com.marketbot.core.util.num
import com.marketbot.core.util.objects
import com.marketbot.core.util.parseInstant
import com.marketbot.core.util.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * The daily check that the venues still answer in the words the app relies on.
 * Each probe runs the app's own request and parser against the live venue and
 * reports what it could not read, with the strategy that would be affected.
 *
 * - PASS: read as expected.
 * - WARN: readable, but something is new or empty (a value the app has not been told
 *   about, nothing listed right now); the app keeps working, but someone should look.
 * - FAIL: something the app reads is gone or renamed, so a strategy would stop
 *   working or work wrongly.
 * - SKIP: could not be checked (no network, rate-limited, no key for an account read).
 *
 * Read-only throughout: no probe here places, amends or cancels anything.
 */
object ApiContract {
    enum class Verdict { PASS, WARN, FAIL, SKIP }

    @Serializable
    data class Finding(val area: String, val check: String, val verdict: Verdict, val detail: String, val affects: String) {
        val line: String get() = "$verdict $area / $check: $detail" + (if (verdict == Verdict.WARN || verdict == Verdict.FAIL) " (affects: $affects)" else "")
    }

    @Serializable
    data class Report(val at: String, val findings: List<Finding>) {
        val failures get() = findings.filter { it.verdict == Verdict.FAIL }
        val warnings get() = findings.filter { it.verdict == Verdict.WARN }
        val summary: String get() = when {
            failures.isNotEmpty() -> "${failures.size} check(s) failed: ${failures.joinToString("; ") { "${it.area} ${it.check}" }}"
            warnings.isNotEmpty() -> "All readable; ${warnings.size} to look at: ${warnings.joinToString("; ") { "${it.area} ${it.check}" }}"
            else -> "All ${findings.count { it.verdict == Verdict.PASS }} checks read as expected" +
                (findings.count { it.verdict == Verdict.SKIP }.takeIf { it > 0 }?.let { ", $it skipped" } ?: "") + "."
        }
    }

    /** The verdict of one probe body: what it saw. */
    class Outcome(val verdict: Verdict, val detail: String)
    private fun pass(d: String) = Outcome(Verdict.PASS, d)
    private fun warn(d: String) = Outcome(Verdict.WARN, d)
    private fun fail(d: String) = Outcome(Verdict.FAIL, d)
    private fun skip(d: String) = Outcome(Verdict.SKIP, d)

    /**
     * Runs one probe. An endpoint that is gone (404) or a parser that throws is a FAIL;
     * a network failure, a rate limit or a 5xx is a SKIP, because an outage is not a
     * change of wording and must not read as one.
     */
    suspend fun probe(area: String, check: String, affects: String, body: suspend () -> Outcome): Finding {
        val o = try { body() } catch (e: ApiException) {
            when {
                e.status == 404 -> fail("endpoint answered 404: ${e.message}")
                e.status == 401 || e.status == 403 -> skip("not authorised: ${e.message}")
                e.retryable -> skip("venue unavailable (${e.status}): ${e.message}")
                else -> fail("venue refused the request (${e.status}): ${e.message}")
            }
        } catch (e: IOException) {
            skip("unreachable: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("could not be parsed: ${e.javaClass.simpleName}: ${e.message}")
        }
        return Finding(area, check, o.verdict, o.detail, affects)
    }

    /** Spacing between the 40 series reads: well inside Kalshi's Basic read limit, about 10 s in all. */
    var seriesPaceMs = 250L

    private fun unknown(seen: Collection<String?>, known: Set<String>) = seen.filterNotNull().filter { it !in known }.toSortedSet()

    // ======================================================================== Kalshi, public
    private const val LONGSHOT = "Weather longshot"
    private const val KDEEP = "BTC deep-ITM ladder (Kalshi)"
    private const val PDEEP = "BTC deep-ITM (Polymarket)"
    private const val FARM = "Liquidity-reward farming"
    private const val ALL_KALSHI = "every Kalshi strategy"

    suspend fun kalshiPublic(k: KalshiClient, now: Instant): List<Finding> {
        val out = mutableListOf<Finding>()
        out += probe("Kalshi", "exchange status fields", "$ALL_KALSHI (the trading-hours gate)") {
            val s = k.exchangeStatus()
            val missing = listOf("trading_active", "exchange_active").filter { s.str(it)?.toBooleanStrictOrNull() == null }
            val shards = s.objects("exchange_index_statuses")
            when {
                missing.isNotEmpty() -> fail("no boolean ${missing.joinToString()} in /exchange/status: the app would read trading as closed")
                shards.none { it.num("exchange_index")?.toInt() == Vocabulary.KXBTCD_SHARD } ->
                    warn("no exchange_index_statuses entry for shard ${Vocabulary.KXBTCD_SHARD}; the shard check falls back to the exchange-wide flag")
                else -> pass("trading_active and exchange_active present, ${shards.size} shard entries")
            }
        }

        out += probe("Kalshi", "weather series and fee type", "$LONGSHOT (series table and the fee kill criterion)") {
            val missing = mutableListOf<String>(); val noFee = mutableListOf<String>(); val maker = mutableListOf<String>(); val odd = mutableMapOf<String, String>()
            for (t in WeatherSeries.ALL) {
                kotlinx.coroutines.delay(seriesPaceMs)   // 40 reads in a burst draw a 429 that hides everything after it
                val s = try { k.series(t) } catch (e: ApiException) { if (e.status == 404) { missing += t; continue } else throw e }
                when (val f = s.str("fee_type")) {
                    null -> if (s.isEmpty()) missing += t else noFee += t
                    Vocabulary.KALSHI_FEE_OK -> {}
                    in Vocabulary.KALSHI_FEE_TYPES -> maker += t
                    else -> odd[t] = f
                }
            }
            when {
                missing.isNotEmpty() -> fail("series not found: ${missing.joinToString()} (renamed? the app's series table must change)")
                noFee.isNotEmpty() -> fail("no fee_type on ${noFee.joinToString()}: the fee kill criterion cannot be checked")
                odd.isNotEmpty() -> fail("fee_type values the app does not know: ${odd.entries.joinToString { "${it.key}=${it.value}" }}")
                maker.isNotEmpty() -> warn("maker fees on ${maker.joinToString()}: the longshot's kill criterion stops live quoting there")
                else -> pass("all ${WeatherSeries.ALL.size} series found, fee_type ${Vocabulary.KALSHI_FEE_OK}")
            }
        }

        out += probe("Kalshi", "commodity range series", "Commodity range longshot (paper)") {
            val missing = mutableListOf<String>(); val notQuad = mutableListOf<String>()
            for (t in com.marketbot.core.strategies.longshot.KalshiCommodityLongshot.SERIES) {
                kotlinx.coroutines.delay(seriesPaceMs)
                val s = try { k.series(t) } catch (e: ApiException) { if (e.status == 404) { missing += t; continue } else throw e }
                if (s.isEmpty()) missing += t else if (s.str("fee_type") != Vocabulary.KALSHI_FEE_OK) notQuad += "$t=${s.str("fee_type")}"
            }
            when {
                missing.isNotEmpty() -> fail("series not found: ${missing.joinToString()}")
                notQuad.isNotEmpty() -> warn("fee type not quadratic (a resting fill may pay a fee): ${notQuad.joinToString()}")
                else -> pass("all ${com.marketbot.core.strategies.longshot.KalshiCommodityLongshot.SERIES.size} series found, fee_type quadratic")
            }
        }

        val sample = listOf(WeatherSeries.BY_CITY.getValue("NYC").getValue("HIGH"), WeatherSeries.BY_CITY.getValue("CHI").getValue("LOW"),
            WeatherSeries.BY_CITY.getValue("MIA").getValue("HIGH"))
        var tradeTicker: String? = null
        out += probe("Kalshi", "weather market listing", LONGSHOT) {
            val ms = sample.flatMap { k.openMarkets(it) }
            if (ms.isEmpty()) return@probe warn("no open markets in ${sample.joinToString()} right now")
            val noCity = ms.filter { WeatherSeries.city(it.ticker) == null }.map { it.ticker }
            val undescribed = ms.filter { "°F" !in Tickers.describe(it.ticker) }.map { it.ticker }
            val noClose = ms.filter { it.closeTime == null }.map { it.ticker }
            val statuses = unknown(ms.map { it.status }, Vocabulary.KALSHI_MARKET_STATUSES)
            val booked = ms.filter { it.book != null }
            val ticks = ms.map { tickSize(0.05, it.raw.priceRanges()) }
            tradeTicker = ms.maxByOrNull { it.num("volume_24h_fp") ?: it.num("volume_fp") ?: it.num("volume") ?: 0.0 }?.ticker
            when {
                noCity.isNotEmpty() -> fail("tickers the series table does not map: ${noCity.take(3).joinToString()}")
                noClose.isNotEmpty() -> fail("no parsable close_time on ${noClose.take(3).joinToString()}: the lead-time gate rejects them")
                undescribed.isNotEmpty() -> fail("ticker strikes not in the B<mid>/T<edge> form: ${undescribed.take(3).joinToString()}")
                booked.isEmpty() -> fail("no market has yes_bid_dollars/yes_ask_dollars (or no_bid_dollars): every market reads as having no book")
                statuses.isNotEmpty() -> warn("market status values the app has not been told about: ${statuses.joinToString()}")
                ticks.all { it.source == Source.DERIVED } -> warn("no price_ranges on any market: tick size falls back to 1c")
                else -> pass("${ms.size} markets: tickers, close times, books (${booked.size}) and price_ranges read")
            }
        }

        out += probe("Kalshi", "settled market wording", "$LONGSHOT and $KDEEP (paper grading and the resolver)") {
            val ms = k.marketsSample(listOf("series_ticker" to sample[0], "status" to "settled", "limit" to "20"))
            if (ms.isEmpty()) return@probe warn("no settled markets returned for status=settled")
            val results = unknown(ms.map { it.result ?: "" }, Vocabulary.KALSHI_RESULTS)
            val notFinal = ms.filter { it.status?.lowercase() !in Vocabulary.KALSHI_SETTLED }.map { "${it.ticker}=${it.status}" }
            when {
                results.isNotEmpty() -> fail("result values the app cannot grade: ${results.joinToString()}")
                ms.none { it.result == "yes" || it.result == "no" } -> fail("no settled market carries result yes/no")
                notFinal.isNotEmpty() -> fail("settled markets whose status the resolver does not treat as final: ${notFinal.take(3).joinToString()}")
                else -> pass("${ms.size} settled markets: result and status in the words the resolver grades")
            }
        }

        out += probe("Kalshi", "public trade tape", "$LONGSHOT and $KDEEP (paper fills)") {
            val t = tradeTicker ?: return@probe skip("no open weather market to read trades from")
            val trades = k.trades(t, now.minus(Duration.ofDays(2)).epochSecond, maxPages = 1)
            if (trades.isEmpty()) return@probe warn("no trades on $t in two days")
            val sides = unknown(trades.map { Vocabulary.kalshiTakerSide(it) }, Vocabulary.KALSHI_SIDES)
            val noSide = trades.count { Vocabulary.kalshiTakerSide(it) == null }
            val legacyOnly = trades.count { it.str("taker_outcome_side") == null && it.str("taker_book_side") == null }
            val noPx = trades.count { it.num("yes_price_dollars") == null || it.num("no_price_dollars") == null }
            val noCount = trades.count { it.num("count_fp") == null && it.num("count") == null }
            val noTime = trades.count { parseInstant(it.str("created_time")) == null }
            when {
                noSide > 0 -> fail("$noSide of ${trades.size} trades have no taker side (taker_outcome_side, taker_book_side or taker_side): paper fills cannot tell who lifted the offer")
                sides.isNotEmpty() -> fail("taker_side values the app does not know: ${sides.joinToString()}")
                noPx > 0 -> fail("$noPx trades without yes_price_dollars/no_price_dollars")
                noCount > 0 || noTime > 0 -> fail("trades without count_fp ($noCount) or created_time ($noTime)")
                legacyOnly > 0 -> warn("$legacyOnly trades carry only the deprecated taker_side")
                else -> pass("${trades.size} trades on $t read")
            }
        }

        out += probe("Kalshi", "KXBTCD hourly event names", KDEEP) {
            // The strategy builds the next hour's event ticker itself; compare that rule with what Kalshi lists.
            val evs = k.eventsSample(listOf("series_ticker" to "KXBTCD", "status" to "open", "limit" to "50"))
                .mapNotNull { e -> parseInstant(e.str("strike_date"))?.let { e.str("event_ticker") to it } }
            if (evs.isEmpty()) return@probe warn("no open KXBTCD events listed")
            val wrong = evs.filter { (t, close) -> DeepItmRule.nextHourEvent(close.minusSeconds(60)).first != t }
            if (wrong.isNotEmpty()) fail("event tickers the app would not build: ${wrong.take(3).joinToString { "${it.first} for ${it.second}" }} " +
                "(the app builds ${wrong.first().second.let { DeepItmRule.nextHourEvent(it.minusSeconds(60)).first }})")
            else pass("${evs.size} open events, all named as the app builds them")
        }

        out += probe("Kalshi", "KXBTCD ladder markets", KDEEP) {
            val evs = k.eventsSample(listOf("series_ticker" to "KXBTCD", "status" to "open", "limit" to "50"))
                .mapNotNull { e -> parseInstant(e.str("strike_date"))?.let { e.str("event_ticker") to it } }.sortedBy { it.second }
            val event = evs.firstOrNull { it.second.isAfter(now) }?.first ?: return@probe warn("no open KXBTCD event to read")
            val ms = k.eventMarkets(event)
            if (ms.isEmpty()) return@probe fail("event $event lists no markets")
            val greater = ms.filter { it.strikeType == Vocabulary.KXBTCD_STRIKE_TYPE }
            val shards = ms.mapNotNull { it.exchangeIndex }.toSet()
            when {
                greater.isEmpty() -> fail("no market has strike_type \"${Vocabulary.KXBTCD_STRIKE_TYPE}\" (seen: ${ms.mapNotNull { it.strikeType }.toSet()}): the ladder drops every market")
                greater.any { it.floorStrike == null } -> fail("floor_strike missing: the ladder drops those markets")
                shards.isNotEmpty() && shards != setOf(Vocabulary.KXBTCD_SHARD) -> fail("exchange_index $shards, the trading-hours read uses shard ${Vocabulary.KXBTCD_SHARD}")
                ms.none { "BTC above" in Tickers.describe(it.ticker) } -> warn("tickers no longer in the KXBTCD-<date><hour>-T<strike> form; names show raw")
                else -> pass("${ms.size} markets under $event, ${greater.size} \"greater\", shard ${shards.joinToString()}")
            }
        }

        out += probe("Kalshi", "error envelope", "$ALL_KALSHI (fill-limit and post-only refusals are read from it)") {
            val r = try { JsonHttp(k.base, maxRetries = 0).get("/portfolio/balance"); null } catch (e: ApiException) { e }
            when {
                r == null -> fail("an unsigned /portfolio/balance was answered: the auth check changed")
                r.status != 401 && r.status != 403 -> fail("unsigned request answered ${r.status}, expected 401")
                r.code == null -> warn("error has no code field (the app reads error.code / code)")
                else -> pass("401 with code \"${r.code}\"")
            }
        }
        return out
    }

    // ======================================================================== Kalshi, account (read-only)
    suspend fun kalshiAccount(k: KalshiClient): List<Finding> {
        if (!k.hasCredentials) return listOf(Finding("Kalshi account", "account reads", Verdict.SKIP, "no Kalshi key in this run", ALL_KALSHI))
        val out = mutableListOf<Finding>()
        out += probe("Kalshi account", "balance fields", "$ALL_KALSHI (sizing and equity)") {
            val b = k.balance()
            when {
                "cents" in b.cash.origin -> warn("balance_dollars missing; read from legacy integer cents")
                b.portfolioValue == null -> warn("no portfolio_value: equity reads as unknown")
                else -> pass("balance_dollars and portfolio_value present")
            }
        }
        out += probe("Kalshi account", "settlement payload", "every Kalshi strategy's realised P&L (graded from it only)") {
            val s = k.accountSample("/portfolio/settlements", "settlements")
            if (s.isEmpty()) return@probe skip("no settlements on this account yet")
            val need = Vocabulary.KALSHI_FIELDS.getValue("Settlement")
            val missing = need.filter { f -> s.all { it[f] == null } }
            val results = unknown(s.map { it.str("market_result") }, setOf("yes", "no", "scalar"))
            when {
                missing.isNotEmpty() -> fail("settlement rows lack ${missing.joinToString()}: realised P&L cannot be read")
                results.isNotEmpty() -> fail("market_result values the app does not know: ${results.joinToString()}")
                else -> pass("${s.size} settlements carry every field P&L is read from")
            }
        }
        out += probe("Kalshi account", "order statuses and fill counts", "order reconciliation and fill grading") {
            val os = k.accountSample("/portfolio/orders", "orders", listOf("status" to "executed")) +
                k.accountSample("/portfolio/orders", "orders", listOf("status" to "canceled"))
            if (os.isEmpty()) return@probe skip("no finished orders on this account yet")
            val statuses = unknown(os.map { it.str("status") }, Vocabulary.KALSHI_ORDER_STATUSES + Vocabulary.KALSHI_ORDER_STATUS_ALIASES)
            val unread = os.count { FillState.of(it).filled == null }
            when {
                statuses.isNotEmpty() -> fail("order status values the app does not know: ${statuses.joinToString()}")
                unread > 0 -> fail("$unread of ${os.size} finished orders whose fill the app cannot read")
                else -> pass("${os.size} finished orders read")
            }
        }
        out += probe("Kalshi account", "order groups listing", "the Kalshi fill limit") {
            val g = k.orderGroups()
            if (g.any { it.str("id") == null }) fail("order groups without id") else pass("${g.size} groups listed")
        }
        return out
    }

    // ======================================================================== Polymarket US, public
    suspend fun polymarketPublic(pm: PolymarketClient, now: Instant): List<Finding> {
        val out = mutableListOf<Finding>()
        var slug: String? = null
        out += probe("Polymarket", "liquidity reward programs", FARM) {
            val ps = pm.liquidityPrograms(maxPages = 1)
            if (ps.isEmpty()) return@probe warn("no active liquidity programs listed")
            val periods = ps.flatMap { it.objects("timePeriods") }
            val kinds = unknown(periods.map { it.str("period") }, Vocabulary.PM_QUOTABLE_PERIODS)
            val types = unknown(periods.map { it.str("programType") }, setOf(Vocabulary.PM_PROGRAM_TYPE))
            val cands = ps.mapNotNull { FarmScanner.activePeriods(it, now, FarmRules()) }
            slug = cands.firstOrNull()?.slug ?: ps.firstNotNullOfOrNull { it.str("marketSlug") }
            when {
                ps.all { it.str("marketSlug") == null } -> fail("programs carry no marketSlug")
                periods.isEmpty() -> fail("programs carry no timePeriods")
                periods.none { it.str("rewardPool") != null || it.num("rewardPool") != null } -> fail("no period has rewardPool: every payout estimates to 0")
                cands.isEmpty() && kinds.isNotEmpty() -> fail("no program is quotable, and period kinds the farm skips appeared: ${kinds.joinToString()}")
                cands.isEmpty() -> warn("no program quotable right now (${ps.size} listed)")
                kinds.isNotEmpty() || types.isNotEmpty() -> warn("new words the farm skips: periods ${kinds.joinToString()} ${types.joinToString()}".trim())
                else -> pass("${ps.size} programs, ${cands.size} quotable")
            }
        }
        out += probe("Polymarket", "market metadata and book", "$FARM and $PDEEP") {
            val s = slug ?: return@probe skip("no market slug to read")
            val meta = pm.marketsBulk(listOf(s))[s] ?: return@probe fail("/v1/markets?slug=$s returned no row for the slug")
            val m: PmMarketMeta = meta.first
            val book: PmBook = pm.book(s)
            when {
                m.raw.num("orderPriceMinTickSize") == null -> fail("no orderPriceMinTickSize: tick falls back to 1c")
                m.raw.num("minimumTradeQty") == null -> warn("no minimumTradeQty: falls back to 1")
                book.bids.isEmpty() && book.asks.isEmpty() -> fail("book parsed with no levels (marketData.bids/offers with px/qty)")
                book.state.isNotEmpty() && book.state != Vocabulary.PM_BOOK_OPEN && m.active && !m.closed ->
                    warn("book state \"${book.state}\" on an active market: the app treats it as closed")
                else -> pass("tick ${m.tick}, ${book.bids.size} bids / ${book.asks.size} offers, state ${book.state.ifEmpty { "-" }}")
            }
        }
        out += probe("Polymarket", "daily BTC ladder event", PDEEP) {
            // Polymarket lists the next 5 PM ET event only some hours ahead (the 2026-10-29 one was
            // created about 21 h before its close), while the strategy needs it in the last 52 min.
            // So the format is checked on the latest listed event, and a missing next event is only
            // a finding once it is close enough to matter.
            val ladder = PolymarketBtcLadder(pm)
            val (next, close) = ladder.eventSlug(now)
            val (prev, _) = ladder.eventSlug(close.minus(java.time.Duration.ofHours(30)))
            suspend fun read(slug: String) = try { pm.event(slug).objects("markets").map { PmMarketMeta(it) } } catch (e: ApiException) { if (e.status == 404) null else throw e }
            val nextMs = read(next)
            val event = if (nextMs != null) next else prev
            val ms = nextMs ?: read(prev) ?: return@probe fail("neither $next nor $prev is listed: the event slug format may have changed")
            val hoursLeft = java.time.Duration.between(now, close).toMinutes() / 60.0
            when {
                nextMs == null && hoursLeft <= 2.0 -> warn("$next is not listed ${"%.1f".format(hoursLeft)} h before its close; the strategy quotes it in the last 52 min")
                ms.isEmpty() -> fail("event $event has no markets (slug format changed?)")
                ms.any { !Vocabulary.isPmBtcMarket(it.slug) } -> fail("market slugs the app does not recognise as its BTC ladder (e.g. ${ms.first { !Vocabulary.isPmBtcMarket(it.slug) }.slug}): " +
                    "the kill sweep and order check would not claim them")
                ms.all { it.strike == null } -> fail("no assetPriceTerms.strike: the ladder drops every market")
                ms.any { it.windowEnd == null } -> warn("assetPriceTerms.windowEnd missing on some markets: the event close is used")
                else -> pass("${ms.size} markets under $event with strikes" + if (nextMs == null) " ($next not listed yet, ${"%.0f".format(hoursLeft)} h before its close)" else "")
            }
        }
        out += probe("Polymarket", "daily temperature buckets", "Polymarket temperature longshot (paper)") {
            val ms = pm.openMarkets(Vocabulary.PM_TEMP_CATEGORY)
            val dated = ms.filter { Vocabulary.pmTemperature(it.slug) != null }
            when {
                ms.isEmpty() -> warn("no open markets in category ${Vocabulary.PM_TEMP_CATEGORY}")
                dated.isEmpty() -> fail("no slug in the tc-temp-<city><high|low>-<date>- form among ${ms.size} climate markets (e.g. ${ms.first().slug})")
                dated.any { it.gameStart == null } -> fail("gameStartTime missing: the strategy times its window from it")
                dated.any { it.endDate == null } -> warn("endDate missing on some buckets: grading waits for the settlement endpoint only")
                else -> pass("${dated.size} dated buckets in ${dated.map { Vocabulary.pmTemperature(it.slug) }.toSet().size} city/kind series")
            }
        }
        out += probe("Polymarket", "settlement endpoint", "$PDEEP and $FARM (grading)") {
            val (event, _) = PolymarketBtcLadder(pm).eventSlug(now.minus(Duration.ofDays(2)))
            val m = pm.event(event).objects("markets").firstOrNull()?.str("slug") ?: return@probe skip("no past BTC market to read")
            val v = pm.settlement(m)
            if (v == null) warn("no settlement for $m two days after its close") else pass("$m settled at ${v.value}")
        }
        return out
    }

    // ======================================================================== BTC index venues
    suspend fun btcVenues(venues: Map<String, VenueQuote> = Venues.all(), candles: suspend () -> List<List<Double>> = { Venues.coinbaseMinuteCandles() }): List<Finding> {
        val out = mutableListOf<Finding>()
        val px = mutableMapOf<String, Double>()
        for ((name, v) in venues) out += probe("BTC index", "$name price", "$KDEEP and $PDEEP (index needs 3 of 4)") {
            val p = v.last()
            if (p < 1_000 || p > 10_000_000) fail("price $p is not a BTC/USD price") else { px[name] = p; pass("%.0f".format(p)) }
        }
        if (px.size >= 2) {
            val med = px.values.sorted().let { it[it.size / 2] }
            px.filter { kotlin.math.abs(it.value / med - 1) > 0.03 }.forEach { (n, p) ->
                out += Finding("BTC index", "$n agrees with the others", Verdict.FAIL, "%.0f against a median of %.0f".format(p, med), "$KDEEP and $PDEEP")
            }
        }
        out += probe("BTC index", "Coinbase 1-minute candles", "$KDEEP and $PDEEP (sigma needs them)") {
            val c = candles()
            when {
                c.size < 16 -> fail("${c.size} candles, at least 16 needed")
                c.any { it.size < 5 } -> fail("candle rows shorter than [time, low, high, open, close]")
                else -> pass("${c.size} candles")
            }
        }
        return out
    }

    // ======================================================================== Kalshi demo: refusal wording
    /**
     * The two refusals the app recognises only by their words, provoked on Kalshi's
     * DEMO exchange (mock money) because nothing else can make Kalshi say them:
     * a post-only order that would cross, and an order in a triggered order group.
     * Refuses to run against the real exchange.
     */
    suspend fun kalshiDemoWording(k: KalshiClient): List<Finding> {
        if (!k.demo) throw IllegalArgumentException("the refusal probes place orders and run on Kalshi's demo exchange only")
        if (!k.hasCredentials) return listOf(Finding("Kalshi demo", "refusal wording", Verdict.SKIP,
            "no demo key (secrets KALSHI_DEMO_KEY_ID and KALSHI_DEMO_PRIVATE_KEY)", "the fill limit and post-only handling"))
        val out = mutableListOf<Finding>()
        val market = try {
            k.markets(listOf("status" to "open", "limit" to "200"), maxPages = 3)
                .filter { m -> m.book?.let { it.yesBid.value >= 0.03 && it.yesAsk.value - it.yesBid.value >= 0.02 } == true }
                .maxByOrNull { it.book!!.yesBid.value }
        } catch (e: Exception) { null }
        if (market == null) return listOf(Finding("Kalshi demo", "refusal wording", Verdict.SKIP, "no demo market with a two-sided book to test on", "the fill limit and post-only handling"))
        val tick = tickSize(market.book!!.yesBid.value, market.raw.priceRanges()).value
        val shard = market.exchangeIndex ?: 0
        suspend fun cleanUp(id: String?) { if (id != null) runCatching { k.cancelOrder(id, market.ticker) } }

        out += probe("Kalshi demo", "post-only cross refusal", "every resting strategy (a refused cross is skipped, not an error)") {
            val bid = market.book!!.yesBid.value
            val r = try { k.placeOrder(market.ticker, "ask", 1, bid, tick, shard, postOnly = true) } catch (e: ApiException) {
                return@probe fail("Kalshi refused it in words the app does not recognise: \"${e.message}\" (code ${e.code}); " +
                    "the app expects one of ${com.marketbot.core.kalshi.KalshiWording.POST_ONLY_WORDS}")
            }
            if (r.filtered) pass("refused as a cross (${r.reason})") else { cleanUp(r.orderId); fail("a post-only ask at the bid was accepted (order ${r.orderId}, cancelled)") }
        }

        out += probe("Kalshi demo", "triggered order group refusal", "the Kalshi fill limit") {
            val fl = com.marketbot.core.kalshi.FillLimit(com.marketbot.core.store.InMemoryState(), { 1 })
            k.fillLimit = fl
            var group: String? = null
            try {
                group = fl.groupFor(k, shard) ?: return@probe fail("could not create an order group")
                k.triggerOrderGroup(group, shard)
                val far = maxOf(tick, 0.01)
                val r = try { k.placeOrder(market.ticker, "bid", 1, far, tick, shard, postOnly = true) }
                    catch (e: com.marketbot.core.kalshi.FillLimitTripped) {
                        return@probe if (fl.lastError == null) pass("refused and recognised as a trip")
                        else warn("refused in words the app no longer recognises; the fail-safe treated it as a trip. ${fl.lastError}")
                    }
                cleanUp(r.orderId)
                fail("an order in a triggered group was accepted (order ${r.orderId}, cancelled)")
            } finally {
                k.fillLimit = null
                group?.let { g -> runCatching { k.deleteOrderGroup(g, shard) } }
            }
        }
        return out
    }

    /** Everything that runs without placing an order. */
    suspend fun run(k: KalshiClient, pm: PolymarketClient, now: Instant = Instant.now(), btc: Boolean = true): Report =
        Report(now.toString(), kalshiPublic(k, now) + kalshiAccount(k) + polymarketPublic(pm, now) + (if (btc) btcVenues() else emptyList()))
}

/**
 * Kalshi's published OpenAPI spec against [Vocabulary]: a field the app reads that
 * the spec no longer has, or marks deprecated, and enum values the spec now allows
 * that the app has not been told about. Catches a change on the day Kalshi
 * documents it, before payloads change. [spec] is the parsed YAML/JSON document.
 */
object KalshiSpecCheck {
    @Suppress("UNCHECKED_CAST")
    fun check(spec: Map<String, Any?>): List<ApiContract.Finding> {
        val schemas = ((spec["components"] as? Map<String, Any?>)?.get("schemas") as? Map<String, Any?>)
            ?: return listOf(ApiContract.Finding("Kalshi spec", "schemas", ApiContract.Verdict.FAIL, "no components.schemas in the spec", "the spec check itself"))
        fun props(n: String) = ((schemas[n] as? Map<String, Any?>)?.get("properties") as? Map<String, Any?>)
        fun enumOf(p: Map<String, Any?>): List<String>? = (p["enum"] as? List<*>)?.map { it.toString() }
            ?: (p["\$ref"] as? String)?.substringAfterLast('/')?.let { (schemas[it] as? Map<String, Any?>)?.get("enum") as? List<*> }?.map { it.toString() }
        val out = mutableListOf<ApiContract.Finding>()
        for ((schema, fields) in Vocabulary.KALSHI_FIELDS) {
            val p = props(schema)
            if (p == null) { out += ApiContract.Finding("Kalshi spec", "$schema fields", ApiContract.Verdict.FAIL, "schema $schema is gone from the spec", "every reader of $schema"); continue }
            val missing = fields.filter { it !in p }
            val deprecated = fields.filter { (p[it] as? Map<*, *>)?.get("deprecated") == true || ((p[it] as? Map<*, *>)?.get("description") as? String)?.startsWith("Deprecated") == true }
            out += ApiContract.Finding("Kalshi spec", "$schema fields", when {
                missing.isNotEmpty() -> ApiContract.Verdict.FAIL
                deprecated.isNotEmpty() -> ApiContract.Verdict.WARN
                else -> ApiContract.Verdict.PASS
            }, when {
                missing.isNotEmpty() -> "fields the app reads are not in the spec: ${missing.joinToString()}"
                deprecated.isNotEmpty() -> "fields the app reads are deprecated: ${deprecated.joinToString()} (migrate before Kalshi removes them)"
                else -> "all ${fields.size} fields present"
            }, "every reader of $schema")
        }
        for ((schema, enums) in Vocabulary.KALSHI_ENUMS) for ((prop, known) in enums) {
            val p = props(schema)?.get(prop) as? Map<String, Any?> ?: continue
            val values = enumOf(p) ?: continue
            val new = values.filter { it !in known }
            out += ApiContract.Finding("Kalshi spec", "$schema.$prop values", if (new.isEmpty()) ApiContract.Verdict.PASS else ApiContract.Verdict.WARN,
                if (new.isEmpty()) "${values.size} values, all known" else "values the app has not been told about: ${new.joinToString()}",
                "code that branches on $schema.$prop")
        }
        return out
    }
}
