package com.marketbot.core

import com.marketbot.core.btc.candleSigma
import com.marketbot.core.btc.medianIndex
import com.marketbot.core.btc.realisedSigma
import com.marketbot.core.kalshi.KalshiMarket
import com.marketbot.core.polymarket.Level
import com.marketbot.core.polymarket.pmIntentFor
import com.marketbot.core.polymarket.pmTradeFee
import com.marketbot.core.strategies.deepitm.DeepItmRule
import com.marketbot.core.strategies.deepitm.Intent
import com.marketbot.core.strategies.deepitm.LadderMarket
import com.marketbot.core.strategies.rewards.FarmCandidate
import com.marketbot.core.strategies.rewards.FarmRisk
import com.marketbot.core.strategies.rewards.FarmRiskState
import com.marketbot.core.strategies.rewards.FarmRules
import com.marketbot.core.strategies.rewards.FarmScanner
import com.marketbot.core.strategies.rewards.Period
import com.marketbot.core.strategies.rewards.RewardsMath
import com.marketbot.core.strategy.TapeFillModel
import com.marketbot.core.util.parseInstant
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepItmAndRewardsGoldenTest {
    private fun ladder(m: JsonObject): LadderMarket? {
        val k = KalshiMarket(m)
        if (k.strikeType != "greater") return null
        return LadderMarket(k.ticker, k.floorStrike ?: return null, k.closeTime ?: return null, k.num("yes_bid_dollars"),
            k.num("no_bid_dollars"), k.num("yes_bid_size_fp"), k.num("yes_ask_size_fp"), k.exchangeIndex)
    }

    private fun intent(o: JsonObject) = Intent(o["ticker"].s!!, o["side"].s!!, o["price"].dd, o["count"].i, o["fair"].dd, o["z"].dd, o["queue_ahead"].dd)

    @Test fun `erf-based fair value matches Python math_erf`() {
        for (c in goldenCases("deepitm_fair")) {
            val r = DeepItmRule.fairYes(c["spot"].dd, c["strike"].dd, c["sigma"].d, c["ttc"].dd)
            if (c["p"].isNull) assertNull(r, "$c") else {
                assertClose(c["p"].dd, r!!.first, 1e-11) { "p $c" }
                assertClose(c["z"].dd, r.second, 1e-12) { "z $c" }
            }
        }
    }

    @Test fun `candidates and selection match Python on real KXBTCD payloads`() {
        for (c in goldenCases("deepitm_candidates")) {
            val ms = c["markets"].list().mapNotNull { ladder(it.o()) }
            val now = parseInstant(c["now"].s)!!
            val got = DeepItmRule.candidates(ms, c["spot"].dd, c["sigma"].dd, now)
            val exp = c["candidates"].list().map { intent(it.o()) }
            assertEquals(exp.map { it.ticker to it.side }, got.map { it.ticker to it.side }, "candidates at $now")
            exp.zip(got).forEach { (e, g) -> assertClose(e.price, g.price); assertClose(e.fair, g.fair, 1e-11); assertClose(e.queueAhead, g.queueAhead) }
            val sel = DeepItmRule.select(got, c["held"].list().map { it.s!! }.toSet())
            assertEquals(c["selected"].list().map { it.o()["ticker"].s }, sel.map { it.ticker })
        }
    }

    @Test fun `hourly event names match Python`() {
        for (c in goldenCases("deepitm_next_hour_event")) {
            val (e, close) = DeepItmRule.nextHourEvent(parseInstant(c["now"].s)!!)
            assertEquals(c["event"].s, e); assertEquals(parseInstant(c["close"].s), close)
        }
    }

    @Test fun `tape fill model matches Python fill_from_tape`() {
        for (c in goldenCases("deepitm_fill_from_tape")) {
            val q = c["quote"].o()
            val f = TapeFillModel.fill(parseInstant(q["placed_at"].s)!!, parseInstant(q["cancelled_at"].s), q["side"].s!!, q["price"].dd,
                q["count"].dd, q["queue_ahead"].dd, c["trades"].list().map { it.o() })
            assertClose(c["filled"].dd, f.filled) { "$c" }
            assertEquals(c["touched"].b, f.touched)
            assertEquals(c["first"].s, f.firstFill)
        }
    }

    @Test fun `deep ITM settlement P&L matches Python`() {
        for (c in goldenCases("deepitm_pnl"))
            assertClose(c["pnl"].dd, DeepItmRule.pnl(c["side"].s!!, c["price"].dd, c["filled"].dd, c["result"].s))
    }

    @Test fun `BTC index median and volatility match Python`() {
        for (c in goldenCases("btc_median_index")) {
            val q = c["quotes"].o().mapValues { val a = it.value.list(); a[0].dd to a[1].dd }
            assertClose(c["index"].d, medianIndex(q, c["now"].dd))
        }
        for (c in goldenCases("btc_realised_sigma")) {
            val s = c["samples"].list().map { val a = it.list(); a[0].dd to a[1].dd }
            assertClose(c["sigma1"].d, realisedSigma(s), 1e-10)
            assertClose(c["sigma10"].d, realisedSigma(s, stepS = 10.0), 1e-10)
        }
        for (c in goldenCases("btc_candle_sigma"))
            assertClose(c["sigma"].d, candleSigma(c["candles"].list().map { r -> r.list().map { it.dd } }), 1e-10)
    }

    private fun levels(e: kotlinx.serialization.json.JsonElement?) = e.list().map { val a = it.list(); Level(a[0].dd, a[1].dd) }

    @Test fun `reward side walk and quote estimate match pmfarm`() {
        for (c in goldenCases("pm_walk_side")) {
            val r = RewardsMath.walkSide(levels(c["levels"]), 0.01, c["target"].dd, c["discount"].dd)
            assertClose(c["score"].dd, r.score, 1e-12); assertClose(c["depth"].dd, r.depth)
            assertClose(c["adjusted"].d, r.adjustedPrice); assertClose(c["best"].d, r.best)
        }
        for (c in goldenCases("pm_estimate_quote")) {
            val e = RewardsMath.estimateQuote(levels(c["bids"]), levels(c["asks"]), c["side"].s!!, c["price"].dd, c["qty"].dd,
                0.01, c["target"].dd, 0.5, c["max_spread"].d, 12.5)
            assertEquals(c["pays"].b, e.pays, "$c")
            assertClose(c["share"].dd, e.share, 1e-12); assertClose(c["usd_per_day"].dd, e.usdPerDay, 1e-12)
        }
    }

    @Test fun `active reward periods match pmfarm on 500 real programs`() {
        val g = golden("pm_active_periods").o()
        val now = parseInstant(g["now"].s)!!
        for (c in g["cases"].list().map { it.o() }) {
            val got = FarmScanner.activePeriods(c["market"].o(), now, FarmRules())
            val exp = c["candidate"]
            if (exp.isNull) assertNull(got, "${c["market"].o()["marketSlug"]}") else {
                got!!
                assertEquals(exp.o()["event_key"].s, got.eventKey)
                assertClose(exp.o()["side_rate"].dd, got.sideRate, 1e-12)
                assertEquals(exp.o()["periods"].list().size, got.periods.size)
            }
        }
    }

    @Test fun `quote planning matches pmfarm`() {
        for (c in goldenCases("pm_plan_quote")) {
            val periods = c["periods"].list().map { val p = it.o()
                Period(p["program_id"].s ?: "p", p["kind"].s!!, p["pool"].dd, p["period_seconds"].dd, null, p["target"].dd, p["discount"].dd, p["max_spread"].d) }
            val cand = FarmCandidate("slug-x", "fam:slug-x", null, periods)
            val plan = FarmScanner.planQuote(cand, 0.01, c["min_qty"].dd, false, true, levels(c["bids"]), levels(c["asks"]),
                FarmRules(allowShortQuotes = c["allow_short"].b!!), c["budget"].dd)
            val exp = c["plan"]
            if (exp.isNull) assertNull(plan, "$c") else {
                plan!!
                assertEquals(exp.o()["side"].s, plan.side)
                assertClose(exp.o()["price"].dd, plan.price); assertClose(exp.o()["qty"].dd, plan.qty, 1e-9)
                assertClose(exp.o()["usd_per_day"].dd, plan.usdPerDay, 1e-12)
            }
        }
    }

    @Test fun `event keys, fees, intents and risk limits match pmfarm`() {
        for (c in goldenCases("pm_event_key")) assertEquals(c["key"].s, FarmScanner.eventKey(c["slug"].s!!))
        for (c in goldenCases("pm_trade_fee")) assertClose(c["fee"].dd, pmTradeFee(c["theta"].dd, c["qty"].dd, c["price"].dd), 1e-12)
        for (c in goldenCases("pm_intent")) assertEquals(c["intent"].s, pmIntentFor(c["side"].s!!, c["pos"].dd, c["exit"].b!!))
        for (c in goldenCases("pm_risk")) {
            val r = FarmRules(startEquity = c["start_equity"].dd)
            assertClose(c["floor"].dd, FarmRisk.floor(FarmRiskState(c["high_water"].dd), r), 1e-12)
            assertClose(c["entry_budget"].dd, FarmRisk.entryBudget(c["equity"].dd, c["free_cash"].dd, r), 1e-12)
        }
    }

    @Suppress("unused") private val anchor: Instant = Instant.EPOCH
}
