package com.marketbot.core

import com.marketbot.core.strategy.HourlyEquity
import com.marketbot.core.strategy.HourlyEquity.HOUR_MS
import com.marketbot.core.strategy.HourlyEquity.Reading
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Home chart's live portfolio, one point per hour. */
class HourlyEquityTest {
    private val t0 = 1_800_000_000_000L / HOUR_MS * HOUR_MS   // on the hour

    @Test fun `each hour sums every venue's latest reading, carrying it forward`() {
        val r = listOf(
            Reading(t0 + 10 * 60_000, "kalshi", 400.0),
            Reading(t0 + 20 * 60_000, "polymarket", 50.0),
            Reading(t0 + HOUR_MS + 5 * 60_000, "kalshi", 410.0),
            Reading(t0 + HOUR_MS + 50 * 60_000, "kalshi", 405.0),   // the hour's last reading wins
        )
        val h = HourlyEquity.of(r, t0, t0 + 3 * HOUR_MS)
        assertEquals(listOf(t0 + HOUR_MS to 450.0, t0 + 2 * HOUR_MS to 455.0, t0 + 3 * HOUR_MS to 455.0), h.points)
        assertEquals(2, h.recordedHours, "the third hour had no reading of its own")
        assertEquals(3, h.hours)
    }

    @Test fun `a venue not yet recorded is left out, not counted as zero, and nothing is drawn before the first reading`() {
        val r = listOf(Reading(t0 + 2 * HOUR_MS + 1, "kalshi", 300.0), Reading(t0 + 3 * HOUR_MS - 1, "polymarket", 20.0))
        val h = HourlyEquity.of(r, t0, t0 + 4 * HOUR_MS)
        assertEquals(listOf(t0 + 3 * HOUR_MS to 320.0, t0 + 4 * HOUR_MS to 320.0), h.points)
    }

    @Test fun `the last point is now, and a reading before the window seeds the first hour`() {
        val now = t0 + 2 * HOUR_MS + 30 * 60_000
        val h = HourlyEquity.of(listOf(Reading(t0 - 1, "kalshi", 100.0), Reading(now - 1, "kalshi", 120.0)), t0, now)
        assertEquals(listOf(t0 + HOUR_MS to 100.0, t0 + 2 * HOUR_MS to 100.0, now to 120.0), h.points)
        assertEquals(3, h.hours); assertEquals(1, h.recordedHours)
    }
}

/** The engine records live balances on its own, so the chart has hourly readings with every strategy in paper. */
class LiveBalanceRecorderTest {
    @Test fun `live balances are recorded every 15 minutes whatever the modes, and not without a key`() = kotlinx.coroutines.test.runTest {
        FakeKalshi().use { fake ->
            val clock = com.marketbot.core.util.MutableClock(java.time.Instant.parse("2026-11-08T12:00:00Z"))
            val stores = com.marketbot.core.store.inMemoryStores()
            val engine = com.marketbot.core.engine.Engine(stores, clock, clientFactory = { _ -> fake.client(true) to com.marketbot.core.polymarket.PolymarketClient(null) })
            assertEquals(1, engine.recordLiveBalancesIfDue())
            assertEquals(0, engine.recordLiveBalancesIfDue(), "not again within 15 minutes")
            clock.advanceSeconds(16 * 60)
            assertEquals(1, engine.recordLiveBalancesIfDue())
            val h = stores.equity.history("kalshi", com.marketbot.core.strategy.Mode.LIVE, java.time.Instant.EPOCH)
            assertEquals(2, h.size); assertEquals(500.0, h.last().cash.value, 1e-9)

            val none = com.marketbot.core.engine.Engine(com.marketbot.core.store.inMemoryStores(), clock,
                clientFactory = { _ -> fake.client(false) to com.marketbot.core.polymarket.PolymarketClient(null) })
            assertEquals(0, none.recordLiveBalancesIfDue(), "no key, no reading")
        }
    }
}
