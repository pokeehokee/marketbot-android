package com.marketbot.core

import com.marketbot.core.btc.BtcIndex
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.store.inMemoryStores
import com.marketbot.core.strategies.bets.BetSpec
import com.marketbot.core.strategies.bets.ResearchBets
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.strategies.longshot.RuleLab
import com.marketbot.core.sync.Proposal
import com.marketbot.core.sync.ProposalPolicy
import com.marketbot.core.util.MutableClock
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResearchBetsTest {
    private val s = ResearchBets()
    private val fake = FakeKalshi()
    private val stores = inMemoryStores()
    private val clock = MutableClock(Instant.parse("2026-11-09T18:00:00Z"))
    private val t = "KXHIGHCHI-26NOV10-T65"
    private val spec = BetSpec(t, "yes", 0.47, 2.40, 0.54, "ensemble says warmer", "cloud cover", "user research")
    @AfterTest fun close() = fake.close()

    private fun ctx() = StrategyContext(s.id, Mode.PAPER, StrategyConfig(s.params), stores, clock, fake.client(false), PolymarketClient(null),
        BtcIndex(emptyMap(), { emptyList() }), false)
    private fun market(status: String, bid: Double, ask: Double, result: String = "") {
        fake.marketByTicker[t] = """{"ticker":"$t","status":"$status","result":"$result","close_time":"2026-11-11T07:00:00Z",
            "yes_bid_dollars":"$bid","yes_ask_dollars":"$ask"}"""
    }

    @Test fun `the policy takes a sound bet for Research bets only and turns it on with the screen lock`() {
        val cfg = StrategyConfig(s.params)
        val ok = ProposalPolicy.check(Proposal("b1", ResearchBets.ID, bet = spec), s, cfg, Mode.OFF)
        assertIs<ProposalPolicy.Verdict.Ok>(ok); assertEquals(Mode.PAPER, ok.mode); assertTrue(ok.needsStrongConfirm); assertEquals(spec, ok.bet)
        val on = ProposalPolicy.check(Proposal("b1", ResearchBets.ID, bet = spec), s, cfg, Mode.PAPER)
        assertIs<ProposalPolicy.Verdict.Ok>(on); assertEquals(null, on.mode)
        fun refused(b: BetSpec) = ProposalPolicy.check(Proposal("x", ResearchBets.ID, bet = b), s, cfg, Mode.PAPER) is ProposalPolicy.Verdict.Refused
        assertTrue(refused(spec.copy(ticker = "KXCHI-*")))
        assertTrue(refused(spec.copy(side = "maybe")))
        assertTrue(refused(spec.copy(stakeUsd = 31.0)), "more than the 30 dollar wallet")
        assertTrue(refused(spec.copy(maxPrice = 1.2)))
        val lab = RuleLab()
        assertIs<ProposalPolicy.Verdict.Refused>(ProposalPolicy.check(Proposal("x", RuleLab.ID, bet = spec), lab, StrategyConfig(lab.params), Mode.PAPER))
    }

    @Test fun `a bet waits above its limit, buys at the ask under it, and records whether the research was right`() = runTest {
        ResearchBets.add(stores.state, "b1", spec, clock.now())
        market("active", 0.48, 0.50)
        s.runOnce(ctx())
        assertEquals("waiting", ResearchBets.load(stores.state).single().status)
        assertTrue(stores.ledger.all(s.id, Mode.PAPER).isEmpty())

        market("active", 0.44, 0.47)
        s.runOnce(ctx())
        val q = stores.ledger.all(s.id, Mode.PAPER).single()
        assertEquals("buy_yes", q.action); assertEquals(0.47, q.price, 1e-9); assertEquals(5.0, q.contracts, "floor(2.40 / 0.47)")
        s.runOnce(ctx())
        assertEquals(1, stores.ledger.all(s.id, Mode.PAPER).size, "one bet, one order")

        market("finalized", 0.0, 1.0, "yes")
        s.runOnce(ctx())
        val r = ResearchBets.report(stores).single()
        assertEquals("settled", r.status); assertEquals("yes", r.result); assertEquals(true, r.predictionCorrect)
        val fee = com.marketbot.core.kalshi.kalshiFee(0.47, 5) * 5
        assertEquals(5 * (1 - 0.47) - fee, r.paperPnl!!, 1e-9)
        assertEquals((0.54 - 1.0) * (0.54 - 1.0), r.brier!!, 1e-9)
        assertTrue(ResearchBets.resultsJson(listOf(r)).contains("\"predictionCorrect\": true"))
    }

    @Test fun `a bet whose price never came in is recorded as missed, with the market's result`() = runTest {
        ResearchBets.add(stores.state, "b2", spec.copy(side = "no", maxPrice = 0.40), clock.now())
        market("active", 0.44, 0.47)                       // NO ask = 1 - 0.44 = 56c, above 40c
        s.runOnce(ctx())
        assertTrue(ResearchBets.load(stores.state).single().detail.contains("above"))
        market("finalized", 0.0, 1.0, "no")
        s.runOnce(ctx())
        val r = ResearchBets.report(stores).single()
        assertEquals("missed", r.status); assertEquals("no", r.result); assertEquals(true, r.predictionCorrect); assertEquals(null, r.paperPnl)
    }
}
