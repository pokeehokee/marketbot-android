package com.marketbot.core

import com.marketbot.core.btc.BtcIndex
import com.marketbot.core.engine.Engine
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.store.inMemoryStores
import com.marketbot.core.strategies.longshot.WeatherLongshot
import com.marketbot.core.strategy.AdverseSelection
import com.marketbot.core.strategy.Check
import com.marketbot.core.strategy.Collateral
import com.marketbot.core.strategy.Evidence
import com.marketbot.core.strategy.EvidenceLevel
import com.marketbot.core.strategy.KillCriteria
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.Outcomes
import com.marketbot.core.strategy.PnlReconcile
import com.marketbot.core.strategy.PreflightResult
import com.marketbot.core.strategy.Requirement
import com.marketbot.core.strategy.RunReport
import com.marketbot.core.strategy.Stat
import com.marketbot.core.strategy.Strategy
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.strategy.Venue
import com.marketbot.core.util.MutableClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tier 3: what the ledger says about the edge, and what the app does about it. */
class InsightsTest {
    private val fake = FakeKalshi()
    @AfterTest fun close() = fake.close()
    private val t0 = Instant.parse("2026-10-20T12:00:00Z")

    private fun row(action: String, price: Double, result: String?, filled: Double? = 5.0, id: Long = 0, ticker: String = "T$id",
                    resolved: Instant? = t0.plusSeconds(86_400), cancelled: Instant? = null, pnl: Double? = 0.0, hypothetical: Boolean? = filled == 0.0) =
        QuoteRow(id = id, strategyId = "s", mode = Mode.LIVE, venue = "kalshi", ticker = ticker, action = action, price = price, contracts = 5.0,
            placedAt = t0, filledContracts = filled, cancelledAt = cancelled, resolvedAt = if (result != null) resolved else null, result = result,
            pnl = if (result != null) pnl else null, hypothetical = hypothetical)

    @Test fun `a loss, and what the price implied, are read the same way for every side`() {
        assertEquals(true, Outcomes.lost(row("sell_yes", 0.07, "yes"))); assertEquals(false, Outcomes.lost(row("sell_yes", 0.07, "no")))
        assertEquals(true, Outcomes.lost(row("buy_yes", 0.95, "no"))); assertEquals(true, Outcomes.lost(row("buy_no", 0.95, "yes")))
        assertNull(Outcomes.lost(row("sell_yes", 0.07, null)))
        assertEquals(0.07, Outcomes.impliedLoss(row("sell_yes", 0.07, "no")), 1e-9)
        assertEquals(0.05, Outcomes.impliedLoss(row("buy_yes", 0.95, "no")), 1e-9)
        assertEquals(0.93, Outcomes.lockPerContract(row("sell_yes", 0.07, null)), 1e-9)
        assertEquals(0.95, Outcomes.lockPerContract(row("buy_no", 0.95, null)), 1e-9)
    }

    @Test fun `the loss-ratio criterion waits for 30 losses, then kills at prices-were-right`() {
        val now = t0.plusSeconds(10 * 86_400)
        // 600 deep-ITM fills at 95c imply 30 losses; 29 losses is too few to judge, 31 is a kill.
        fun book(losses: Int) = (0 until 600).map { i -> row("buy_yes", 0.95, if (i < losses) "no" else "yes", id = i.toLong(), hypothetical = false) }
        assertEquals(Check.Verdict.INSUFFICIENT, KillCriteria.lossRatio(book(29), now).verdict)
        assertEquals(Check.Verdict.KILL, KillCriteria.lossRatio(book(31), now).verdict)
        val healthy = (0 until 1200).map { i -> row("buy_yes", 0.95, if (i < 35) "no" else "yes", id = i.toLong(), hypothetical = false) }
        assertEquals(Check.Verdict.CLEAR, KillCriteria.lossRatio(healthy, now).verdict, "35 losses against 60 implied")
        assertEquals(Check.Verdict.INSUFFICIENT, KillCriteria.lossRatio(book(31), now.plus(Duration.ofDays(90))).verdict, "outside the 60-day window")
    }

    @Test fun `fills by outcome measure how much more often losers fill`() {
        val rows = (0 until 10).map { row("sell_yes", 0.07, "yes", filled = if (it < 9) 5.0 else 0.0, id = it.toLong()) } +
            (10 until 30).map { row("sell_yes", 0.07, "no", filled = if (it < 22) 5.0 else 0.0, id = it.toLong()) } +
            listOf(row("sell_yes", 0.07, null, id = 99))   // unsettled: no verdict
        val b = AdverseSelection.table(rows, AdverseSelection.LONGSHOT_BANDS)[1]
        assertEquals(30, b.quotes); assertEquals(10, b.lostQuotes)
        assertEquals(0.9, b.fillWhenLost!!, 1e-9); assertEquals(0.6, b.fillWhenWon!!, 1e-9)
        assertEquals(1.5, b.penalty!!, 1e-9)
        assertEquals(0, AdverseSelection.table(rows, AdverseSelection.LONGSHOT_BANDS)[0].quotes)
    }

    @Test fun `collateral locks the whole order until it is withdrawn, then only what filled, until settlement`() {
        val placed = t0; val cancelled = t0.plusSeconds(3600); val settled = t0.plusSeconds(7200)
        val q = row("sell_yes", 0.10, "no", filled = 2.0, resolved = settled, cancelled = cancelled)
        assertEquals(0.0, Collateral.lockedAt(listOf(q), placed.minusSeconds(1)), 1e-9)
        assertEquals(5 * 0.9, Collateral.lockedAt(listOf(q), placed.plusSeconds(10)), 1e-9)
        assertEquals(2 * 0.9, Collateral.lockedAt(listOf(q), cancelled.plusSeconds(10)), 1e-9)
        assertEquals(0.0, Collateral.lockedAt(listOf(q), settled), 1e-9)
        assertEquals(4, Collateral.series(listOf(q), placed, settled, Duration.ofMinutes(40)).size)
    }

    @Test fun `reconciliation compares per market with Kalshi's figure, and a truncated listing is an error`() = runTest {
        fake.settlements = """{"settlements":[
            {"ticker":"A","revenue":500,"yes_total_cost_dollars":"0","no_total_cost_dollars":"4.65","fee_cost":"0","yes_count_fp":"0","no_count_fp":"5"},
            {"ticker":"B","revenue":0,"yes_total_cost_dollars":"0","no_total_cost_dollars":"4.65","fee_cost":"0","yes_count_fp":"0","no_count_fp":"5"}],"cursor":""}"""
        val now = t0.plusSeconds(2 * 86_400)
        val rows = listOf(row("sell_yes", 0.07, "no", ticker = "A", pnl = 0.35, hypothetical = false),
            row("sell_yes", 0.07, "no", ticker = "B", pnl = 0.35, hypothetical = false),          // Kalshi says it lost
            row("sell_yes", 0.07, "no", ticker = "C", pnl = 0.35, hypothetical = false))          // not at Kalshi
        val r = PnlReconcile.kalshi(fake.client(signed = true), rows, now)
        assertEquals(2, r.compared)
        assertEquals(listOf("B"), r.mismatches.map { it.ticker })
        assertEquals(-4.65, r.mismatches.single().exchange, 1e-9)
        assertEquals(listOf("C"), r.notAtExchange)
        assertTrue(!r.clean && "differ" in r.summary, r.summary)
        fake.settlements = null   // the fake answers 503
        assertTrue(PnlReconcile.kalshi(fake.client(signed = true), rows, now).error != null)
    }

    /** A live strategy whose record says the edge is gone. */
    private class Doomed : Strategy {
        override val id = "doomed"; override val name = "Doomed"; override val venue = Venue.KALSHI; override val summary = ""
        override val evidence = Evidence(EvidenceLevel.UNPROVEN, "", emptyList(), "")
        override val params = emptyList<com.marketbot.core.strategy.Param>()
        override fun nextDelay(ctx: StrategyContext) = Duration.ofHours(1)
        override suspend fun runOnce(ctx: StrategyContext) = RunReport("ok")
        override suspend fun liveRequirements(ctx: StrategyContext) = emptyList<Requirement>()
        override suspend fun preflight(ctx: StrategyContext) = PreflightResult(true, emptyList(), "")
        override suspend fun stats(ctx: StrategyContext) = emptyList<Stat>()
        override suspend fun healthChecks(ctx: StrategyContext, mode: Mode) =
            listOf(Check("losses below implied", if (mode == Mode.LIVE) Check.Verdict.KILL else Check.Verdict.CLEAR, "40 losses, ratio 1.20"))
    }

    @Test fun `a kill on the live record disarms live trading and says why`() = runBlocking {
        val stores = inMemoryStores()
        val notes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val engine = Engine(stores, MutableClock(t0), notifier = { t, b -> notes += "$t: $b" }, strategies = listOf(Doomed()),
            clientFactory = { _ -> fake.client(false) to PolymarketClient(null) }, fillStreamFactory = null)
        stores.state.put("strategy.doomed.mode", "LIVE")
        stores.state.put("strategy.doomed.armed", """{"at":"$t0","acknowledged":[]}""")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine.start(scope)
        withTimeout(5_000) { while (stores.state.get("strategy.doomed.mode") != "PAPER") delay(20) }
        assertNull(stores.state.get("strategy.doomed.armed"))
        assertTrue(notes.any { "Doomed disarmed" in it && "ratio 1.20" in it }, notes.toString())
        scope.cancel()
    }

    @Test fun `the longshot pass says where every market went`() = runTest {
        val s = WeatherLongshot()
        val farClose = "2026-10-29T05:00:00Z"
        fake.markets["KXHIGHNY"] = listOf(
            FakeKalshi.market("KXHIGHNY-26OCT28-B70.5", 0.06, 0.07, farClose),
            FakeKalshi.market("KXHIGHNY-26OCT28-B72.5", 0.11, 0.12, farClose),
            FakeKalshi.market("KXHIGHNY-26OCT28-B74.5", 0.00, 0.01, farClose),
            FakeKalshi.market("KXHIGHNY-26OCT28-B76.5", 0.03, 0.04, "2026-10-28T10:00:00Z"))
        val ctx = StrategyContext(s.id, Mode.PAPER, StrategyConfig(s.params), inMemoryStores(), MutableClock(Instant.parse("2026-10-27T20:00:00Z")),
            fake.client(false), PolymarketClient(null), BtcIndex(venues = emptyMap(), candles = { emptyList() }), liveArmed = false)
        val f = s.runOnce(ctx).funnel
        assertEquals(4, f.first().count)
        assertEquals(listOf(4, 4, 4, 3, 2, 2, 1, 1, 1), f.map { it.count }, f.toString())
        assertTrue(f.zipWithNext().all { (a, b) -> b.count <= a.count }, "each step can only narrow")
    }
}
