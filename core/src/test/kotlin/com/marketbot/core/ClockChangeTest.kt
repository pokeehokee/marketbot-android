package com.marketbot.core

import com.marketbot.core.engine.Engine
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.strategy.Venue
import com.marketbot.core.store.inMemoryStores
import com.marketbot.core.strategies.deepitm.DeepItmRule
import com.marketbot.core.strategies.deepitm.KalshiBtcLadder
import com.marketbot.core.strategies.deepitm.PolymarketBtcLadder
import com.marketbot.core.strategy.Evidence
import com.marketbot.core.strategy.EvidenceLevel
import com.marketbot.core.strategy.PreflightResult
import com.marketbot.core.strategy.Requirement
import com.marketbot.core.strategy.RunReport
import com.marketbot.core.strategy.Stat
import com.marketbot.core.strategy.Strategy
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.strategy.untilNextMinuteOfHour
import com.marketbot.core.util.MutableClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Daylight-saving nights and a phone that changes its clock or zone. */
class ClockChangeTest {
    private val fake = FakeKalshi()
    @AfterTest fun close() = fake.close()
    private val ny = ZoneId.of("America/New_York")

    @Test fun `the Polymarket 5 PM ET ladder closes at 5 PM Eastern on both changeover days`() {
        val pm = PolymarketBtcLadder(PolymarketClient(null))
        assertEquals("btc-above-day-2026-11-01" to Instant.parse("2026-11-01T22:00:00Z"), pm.eventSlug(Instant.parse("2026-11-01T20:30:00Z")), "EST after fall-back")
        assertEquals("btc-above-day-2026-03-08" to Instant.parse("2026-03-08T21:00:00Z"), pm.eventSlug(Instant.parse("2026-03-08T20:30:00Z")), "EDT after spring-forward")
    }

    @Test fun `the hourly label follows Eastern time, and the repeated 1 AM hour is not traded twice`() = runBlocking {
        val first = DeepItmRule.nextHourEvent(Instant.parse("2026-11-01T04:30:00Z"))    // closes 01:00 EDT
        val second = DeepItmRule.nextHourEvent(Instant.parse("2026-11-01T05:30:00Z"))   // closes 01:00 EST
        assertEquals("KXBTCD-26NOV0101", first.first)
        assertEquals(first.first, second.first, "both hours carry the same ET label")
        // The listing for that label returns the first hour's strikes; asked about the second hour, none are used.
        fake.markets["event:${first.first}"] = listOf(
            """{"ticker":"${first.first}-T70000","event_ticker":"${first.first}","status":"active","close_time":"${first.second}",
               "strike_type":"greater","floor_strike":70000,"yes_bid_dollars":"0.95","no_bid_dollars":"0.03","exchange_index":2,
               "price_ranges":[{"start":"0","end":"1","step":"0.01"}]}""")
        val ladder = KalshiBtcLadder(fake.client(signed = false))
        assertEquals(1, ladder.ladder(Instant.parse("2026-11-01T04:30:00Z"), 75_000.0, 0.001).size)
        assertTrue(ladder.ladder(Instant.parse("2026-11-01T05:30:00Z"), 75_000.0, 0.001).isEmpty(), "the closed hour's strikes are not the next hour's")
    }

    @Test fun `a run-at-minute schedule spans the missing and the repeated hour by real time`() {
        // 01:30 EST, the night 02:00 does not exist: next :05 is 03:05 EDT, 35 real minutes later.
        assertEquals(Duration.ofMinutes(35), untilNextMinuteOfHour(Instant.parse("2026-03-08T06:30:00Z"), 5, ny))
        // 01:30 EDT, the night 01:00-02:00 happens twice: next :05 is 01:05 EST, 35 real minutes later, not 95.
        assertEquals(Duration.ofMinutes(35), untilNextMinuteOfHour(Instant.parse("2026-11-01T05:30:00Z"), 5, ny))
    }

    /** Runs at minute 5 of the hour in whatever zone [zone] holds now. */
    private class AtMinute(var zone: ZoneId) : Strategy {
        val passes = AtomicInteger()
        override val id = "at_minute"; override val name = id; override val venue = Venue.KALSHI; override val summary = ""
        override val evidence = Evidence(EvidenceLevel.UNPROVEN, "", emptyList(), "")
        override val params = emptyList<com.marketbot.core.strategy.Param>()
        override fun nextDelay(ctx: StrategyContext) = untilNextMinuteOfHour(ctx.clock.now(), 5, zone)
        override suspend fun runOnce(ctx: StrategyContext): RunReport { passes.incrementAndGet(); return RunReport("ok") }
        override suspend fun liveRequirements(ctx: StrategyContext) = emptyList<Requirement>()
        override suspend fun preflight(ctx: StrategyContext) = PreflightResult(true, emptyList(), "")
        override suspend fun stats(ctx: StrategyContext) = emptyList<Stat>()
    }

    @Test fun `a time zone change moves the next pass without running one`() = runBlocking {
        val stores = inMemoryStores()
        val clock = MutableClock(Instant.parse("2026-10-27T20:00:00Z"))
        val s = AtMinute(ZoneId.of("UTC"))
        val engine = Engine(stores, clock, strategies = listOf(s), clientFactory = { _ -> fake.client(false) to PolymarketClient(null) }, fillStreamFactory = null)
        stores.state.put("strategy.at_minute.mode", "PAPER")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine.start(scope)
        withTimeout(5_000) { while (engine.nextWakeAt() == null) delay(20) }
        assertEquals(Instant.parse("2026-10-27T20:05:00Z"), engine.nextWakeAt())
        // The phone moves to India (UTC+5:30): minute 5 local is now :35 UTC.
        s.zone = ZoneId.of("Asia/Kolkata")
        engine.reschedule()
        withTimeout(5_000) { while (engine.nextWakeAt() != Instant.parse("2026-10-27T20:35:00Z")) delay(20) }
        assertEquals(1, s.passes.get(), "rescheduling does not run a pass")
        scope.cancel()
    }
}
