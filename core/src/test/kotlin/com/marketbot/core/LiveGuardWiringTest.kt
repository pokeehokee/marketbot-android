package com.marketbot.core

import com.marketbot.core.btc.BtcIndex
import com.marketbot.core.btc.VenueQuote
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.store.inMemoryStores
import com.marketbot.core.strategies.deepitm.KalshiBtcDeepItm
import com.marketbot.core.strategies.longshot.KalshiCommodityLongshot
import com.marketbot.core.strategy.Check
import com.marketbot.core.strategy.LiveGuards
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.Strategy
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.strategy.Venue
import com.marketbot.core.util.MutableClock
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A control nothing calls is not a control.
 *
 * `reconcileKalshi` was reachable only from the retired weather strategy and
 * `KillCriteria.staleWrites` was reachable from nothing at all -- both shipped
 * in the PR whose own docs say an orphaned control is worse than none, because
 * the docs then read as though the ground is covered. Correctness tests cannot
 * catch that: the functions were already correct. These tests assert
 * REACHABILITY from the two live-capable books, so deleting a call site fails
 * the build rather than silently removing a guard.
 */
class LiveGuardWiringTest {
    private val fake = FakeKalshi()
    private val stores = inMemoryStores()
    private val clock = MutableClock(Instant.parse("2026-11-20T12:00:00Z"))

    @AfterTest fun close() = fake.close()

    private fun idx(): BtcIndex =
        BtcIndex(mapOf("a" to VenueQuote { 75_000.0 }), { emptyList() }, nowS = { 1_000.0 })

    private fun ctx(s: Strategy, mode: Mode, creds: Boolean = true) = StrategyContext(
        s.id, mode, StrategyConfig(s.params), stores, clock,
        fake.client(creds), PolymarketClient(null), idx(), false,
    )

    private suspend fun names(s: Strategy, mode: Mode, creds: Boolean = true) =
        s.healthChecks(ctx(s, mode, creds), mode).map { it.name }

    // ---- reachability, which is the whole point ----

    @Test fun `the deep-ITM book runs both outside-the-ledger guards when live`() = runTest {
        val n = names(KalshiBtcDeepItm(), Mode.LIVE)
        assertTrue("No other trader on this account" in n, "reconcile guard not reachable: $n")
        assertTrue("Every writer still writing" in n, "stale-writer guard not reachable: $n")
    }

    @Test fun `the commodity book runs both outside-the-ledger guards when live`() = runTest {
        val n = names(KalshiCommodityLongshot(), Mode.LIVE)
        assertTrue("No other trader on this account" in n, "reconcile guard not reachable: $n")
        assertTrue("Every writer still writing" in n, "stale-writer guard not reachable: $n")
    }

    @Test fun `neither guard runs on a paper book`() = runTest {
        for (s in listOf(KalshiBtcDeepItm(), KalshiCommodityLongshot())) {
            val n = names(s, Mode.PAPER)
            assertTrue("No other trader on this account" !in n, "${s.id} reads the live account in paper: $n")
            assertTrue("Every writer still writing" !in n, "${s.id} checks the live writer in paper: $n")
        }
    }

    // ---- the verdicts the callers depend on ----

    @Test fun `a foreign resting order is a FAIL, not a pass`() = runTest {
        fake.restingOrders = """{"orders":[{"order_id":"not-ours","ticker":"KXBTCD-26NOV2012-T90000"}],"cursor":""}"""
        val c = KalshiBtcDeepItm().let { s -> s.healthChecks(ctx(s, Mode.LIVE), Mode.LIVE) }
            .first { it.name == "No other trader on this account" }
        assertEquals(Check.Verdict.FAIL, c.verdict, c.detail)
        assertTrue("not-ours" !in c.detail, "the detail should name the market, not the order id: ${c.detail}")
    }

    @Test fun `another book's order is not this book's foreign order`() = runTest {
        // A commodity order is in the account but out of the deep-ITM book's scope.
        fake.restingOrders = """{"orders":[{"order_id":"commodity-1","ticker":"KXGOLDD-26NOV2017-T3850"}],"cursor":""}"""
        val c = KalshiBtcDeepItm().let { s -> s.healthChecks(ctx(s, Mode.LIVE), Mode.LIVE) }
            .first { it.name == "No other trader on this account" }
        assertEquals(Check.Verdict.PASS, c.verdict, c.detail)
    }

    @Test fun `no credentials cannot read the account, so it is INSUFFICIENT not PASS`() = runTest {
        val c = LiveGuards.noOtherTrader(fake.client(false), { true }, emptyList())
        assertEquals(Check.Verdict.INSUFFICIENT, c.verdict, c.detail)
    }

    @Test fun `an account read that fails is INSUFFICIENT, never a clean account`() = runTest {
        fake.close()   // every request now errors
        val c = LiveGuards.noOtherTrader(fake.client(true), { true }, emptyList())
        assertEquals(Check.Verdict.INSUFFICIENT, c.verdict, c.detail)
    }

    // ---- the balance writer, the one that freezes in silence ----

    @Test fun `a balance writer that never wrote is INSUFFICIENT`() = runTest {
        val s = KalshiBtcDeepItm()
        val c = LiveGuards.balanceWriterFresh(ctx(s, Mode.LIVE), Venue.KALSHI, clock.now())
        assertEquals(Check.Verdict.INSUFFICIENT, c.verdict, c.detail)
        assertTrue("not yet run" in c.detail, c.detail)
    }

    @Test fun `a balance writer quiet for hours FAILS against its 15 minute cadence`() = runTest {
        val s = KalshiBtcDeepItm()
        stores.equity.record(snapshot(clock.now().minusSeconds(4 * 3600)))
        val c = LiveGuards.balanceWriterFresh(ctx(s, Mode.LIVE), Venue.KALSHI, clock.now())
        assertEquals(Check.Verdict.FAIL, c.verdict, c.detail)
        assertTrue("accounting is frozen" in c.detail, c.detail)
    }

    @Test fun `a balance written on cadence PASSES`() = runTest {
        val s = KalshiBtcDeepItm()
        stores.equity.record(snapshot(clock.now().minusSeconds(600)))
        val c = LiveGuards.balanceWriterFresh(ctx(s, Mode.LIVE), Venue.KALSHI, clock.now())
        assertEquals(Check.Verdict.PASS, c.verdict, c.detail)
    }

    @Test fun `a paper balance snapshot does not satisfy the live writer`() = runTest {
        val s = KalshiBtcDeepItm()
        stores.equity.record(snapshot(clock.now(), Mode.PAPER))
        val c = LiveGuards.balanceWriterFresh(ctx(s, Mode.LIVE), Venue.KALSHI, clock.now())
        assertEquals(Check.Verdict.INSUFFICIENT, c.verdict, "practice money must not mark the live writer fresh: ${c.detail}")
    }

    private fun snapshot(at: Instant, mode: Mode = Mode.LIVE) = com.marketbot.core.store.EquitySnapshot(
        at, "kalshi", mode,
        com.marketbot.core.provenance.Sourced(100.0, com.marketbot.core.provenance.Source.KALSHI_API, "test"),
        null, null,
    )
}
