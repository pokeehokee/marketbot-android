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

    @Test fun `the deep-ITM book runs the regime bounds check when live`() = runTest {
        val n = names(KalshiBtcDeepItm(), Mode.LIVE)
        assertTrue("Trading inside measured conditions" in n, "regime flag not reachable: $n")
    }

    @Test fun `the deep-ITM book runs the fill-limit check when live`() = runTest {
        val n = names(KalshiBtcDeepItm(), Mode.LIVE)
        assertTrue("Kalshi is accepting this book's orders" in n, "fill-limit guard not reachable: $n")
    }

    @Test fun `neither guard runs on a paper book`() = runTest {
        for (s in listOf(KalshiBtcDeepItm(), KalshiCommodityLongshot())) {
            val n = names(s, Mode.PAPER)
            assertTrue("No other trader on this account" !in n, "${s.id} reads the live account in paper: $n")
            assertTrue("Every writer still writing" !in n, "${s.id} checks the live writer in paper: $n")
            assertTrue("Trading inside measured conditions" !in n, "${s.id} checks the regime in paper: $n")
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

    // ---- the fill limit, the control that held a live book for 23h 50m in silence ----

    private fun fl(limit: Int) = com.marketbot.core.kalshi.FillLimit(stores.state, { limit })

    @Test fun `a tripped shard FAILS and says how long the book has been held`() = runTest {
        val f = fl(40)
        f.markTripped(2, clock.now(), lastFillAt = clock.now().minusSeconds(3600))
        val c = LiveGuards.fillLimitClear(fake.client(true).also { it.fillLimit = f }, clock.now(), lot = 7)
        assertEquals(Check.Verdict.FAIL, c.verdict, c.detail)
        assertTrue("shard 2" in c.detail, c.detail)
        assertTrue("1h 0m ago" in c.detail, "it must say how long it has been held: ${c.detail}")
    }

    /**
     * The trip is discovered on the next order the book tries to place, and the
     * book places nothing while its slots are full. On 2026-11-20 that put 66
     * minutes between the real trip (15:20:07Z) and the stamp (16:26:35Z).
     */
    @Test fun `the trip time is the fill, not the moment it was noticed`() = runTest {
        val f = fl(40)
        val realTrip = Instant.parse("2026-11-20T15:20:07Z")
        val noticed = Instant.parse("2026-11-20T16:26:35Z")
        f.markTripped(2, noticed, lastFillAt = realTrip)
        assertEquals(realTrip, f.trippedAt(2))
        assertEquals(noticed, f.noticedAt(2))
    }

    @Test fun `a reset clears the noticed time too, so a later trip cannot inherit it`() = runTest {
        val f = fl(40)
        f.markTripped(2, clock.now(), lastFillAt = clock.now())
        f.reset(fake.client(true), 2)
        assertEquals(null, f.trippedAt(2))
        assertEquals(null, f.noticedAt(2))
    }

    /**
     * The 11-20 outage in one assertion: a limit of 20 contracts allows only two
     * 7-lots, and this book fills several strikes off one BTC move. The control
     * fires on ordinary trading, and then the book places nothing until a human
     * taps Reset. Nothing caught this because the limit counts CONTRACTS and the
     * setting that moved was the LOT.
     */
    @Test fun `a limit that cannot hold four lots FAILS before it ever trips`() = runTest {
        val c = LiveGuards.fillLimitClear(fake.client(true).also { it.fillLimit = fl(20) }, clock.now(), lot = 7)
        assertEquals(Check.Verdict.FAIL, c.verdict, c.detail)
        assertTrue("2 strikes" in c.detail, c.detail)
        assertTrue("28" in c.detail, "it must name the limit that would hold four lots: ${c.detail}")
    }

    @Test fun `the limit the account actually carries leaves room for four 7-lots`() = runTest {
        val c = LiveGuards.fillLimitClear(fake.client(true).also { it.fillLimit = fl(40) }, clock.now(), lot = 7)
        assertEquals(Check.Verdict.PASS, c.verdict, c.detail)
        assertTrue("5 strikes" in c.detail, c.detail)
    }

    @Test fun `the old limit was coherent at the lot it was written for`() = runTest {
        val c = LiveGuards.fillLimitClear(fake.client(true).also { it.fillLimit = fl(20) }, clock.now(), lot = 5)
        assertEquals(Check.Verdict.PASS, c.verdict, c.detail)
    }

    @Test fun `a state store that cannot be read is INSUFFICIENT, never PASS`() = runTest {
        val broken = com.marketbot.core.kalshi.FillLimit(
            object : com.marketbot.core.store.StateStore {
                override suspend fun get(key: String) = throw IllegalStateException("state store unavailable")
                override suspend fun put(key: String, value: String?) = throw IllegalStateException("state store unavailable")
            },
            { 40 },
        )
        val c = LiveGuards.fillLimitClear(fake.client(true).also { it.fillLimit = broken }, clock.now(), lot = 7)
        assertEquals(Check.Verdict.INSUFFICIENT, c.verdict, c.detail)
    }

    @Test fun `the fill-limit check can never kill a working book`() = runTest {
        for (limit in listOf(0, 1, 20, 40, 400)) {
            val f = fl(limit); f.markTripped(2, clock.now(), lastFillAt = clock.now())
            val c = LiveGuards.fillLimitClear(fake.client(true).also { it.fillLimit = f }, clock.now(), lot = 7)
            assertTrue(c.verdict != Check.Verdict.KILL, "limit $limit returned KILL: ${c.detail}")
        }
    }
}
