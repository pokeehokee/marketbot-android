package com.marketbot.app

import com.marketbot.app.ui.InFlight
import com.marketbot.app.ui.isStale
import com.marketbot.app.ui.staleAfter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tier-1 behaviour: busy guards, staleness, shortcuts and the crash report. */
@RunWith(RobolectricTestRunner::class)
class PolishTest {
    @Test fun `a second claim on a running action is refused until it is released`() {
        val f = InFlight()
        assertTrue(f.claim("cancel:7"))
        assertFalse(f.claim("cancel:7"), "a double tap sends one cancel")
        assertTrue(f.claim("cancel:8"), "other rows are independent")
        f.release("cancel:7")
        assertTrue(f.claim("cancel:7"))
        assertEquals(setOf("cancel:7", "cancel:8"), f.keys.value)
    }

    @Test fun `readings go stale after three of the strategy's periods, never under two minutes`() {
        val t = Instant.parse("2026-10-29T12:00:00Z")
        assertEquals(Duration.ofHours(3), staleAfter(t, t.plusSeconds(3600)), "hourly strategy")
        assertEquals(Duration.ofMinutes(2), staleAfter(t, t.plusSeconds(2)), "2-s loop floors at two minutes")
        assertEquals(Duration.ofMinutes(15), staleAfter(null, null))
        assertTrue(isStale(t, t.plusSeconds(181), Duration.ofMinutes(3)))
        assertFalse(isStale(t, t.plusSeconds(179), Duration.ofMinutes(3)))
        assertFalse(isStale(null, t, Duration.ofMinutes(3)), "a value with no read time is never called stale")
        assertFalse(isStale(t, t.plusSeconds(99_999), null))
    }

    @Test fun `only known shortcuts are accepted`() {
        Shortcuts.requested.value = null
        Shortcuts.from(android.content.Intent().putExtra("shortcut", "transfer_everything"))
        assertNull(Shortcuts.requested.value)
        Shortcuts.from(android.content.Intent().putExtra("shortcut", "kill"))
        assertEquals("kill", Shortcuts.requested.value)
        Shortcuts.requested.value = null
    }

    @Test fun `a crash report names the commit and, for out of memory, the heap and the caches`() {
        val owner = Any()
        com.marketbot.core.util.Diagnostics.track(owner, "test cache") { 42 }
        val r = Health.crashReport("main", OutOfMemoryError("Failed to allocate"))
        assertTrue(BuildConfig.GIT_SHA in r, r)
        assertTrue("MB used of" in r, r)
        assertTrue("test cache: 42" in r, r)
        assertFalse("test cache" in Health.crashReport("main", IllegalStateException("x")), "ordinary crashes stay short")
    }
}

class WidgetBeatTest {
    private val now = 1_000_000_000L
    @org.junit.Test fun `the widget dot tells a fresh engine from a late, a killed-by-the-phone and a stopped one`() {
        kotlin.test.assertEquals("Engine running", WidgetBeat.of(true, true, now - 5_000, now).text)
        kotlin.test.assertTrue(WidgetBeat.of(true, true, now - 600_000, now).text.startsWith("Engine slow: last beat 10 min"))
        kotlin.test.assertEquals("Engine stopped by the phone", WidgetBeat.of(false, true, now - 5_000, now).text)
        kotlin.test.assertEquals("Engine stopped", WidgetBeat.of(false, false, null, now).text)
    }
}

class HeartbeatTest {
    @org.junit.Test fun `only an https check address is ever pinged`() {
        kotlin.test.assertTrue(Heartbeat.valid("https://hc-ping.com/0f3c2d0a-1111-2222-3333-444455556666"))
        kotlin.test.assertFalse(Heartbeat.valid("http://hc-ping.com/abc"), "the token in the address must not travel in the clear")
        kotlin.test.assertFalse(Heartbeat.valid("hc-ping.com/abc"))
        kotlin.test.assertFalse(Heartbeat.valid(null))
        kotlin.test.assertFalse(Heartbeat.beatIfDue(null), "no address, no ping")
    }
}

@RunWith(RobolectricTestRunner::class)
class NotifyPrefsTest {
    @org.junit.Test fun `each kind follows its own setting, and safety starts on`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        kotlin.test.assertEquals(NotifyPrefs.Level.ON, NotifyPrefs.get(ctx, "safety"))
        NotifyPrefs.set(ctx, "fills", NotifyPrefs.Level.SILENT)
        NotifyPrefs.set(ctx, "daily", NotifyPrefs.Level.OFF)
        kotlin.test.assertEquals(NotifyPrefs.Level.SILENT, NotifyPrefs.get(ctx, "fills"))
        MarketbotApp.createChannels(ctx)
        val nm = org.robolectric.Shadows.shadowOf(ctx.getSystemService(android.app.NotificationManager::class.java))
        MarketbotApp.post(ctx, "daily", "Yesterday", "x")
        kotlin.test.assertEquals(0, nm.allNotifications.size, "off posts nothing")
        MarketbotApp.post(ctx, "fills", "Filled", "y")
        kotlin.test.assertEquals(MarketbotApp.CH_QUIET, nm.allNotifications.single().channelId)
    }
}

@RunWith(RobolectricTestRunner::class)
class BenchmarkTest {
    @org.junit.Test fun `the background test counts minutes alive and reports silences`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val t0 = 1_700_000_000_000L
        Benchmark.start(ctx, 1, t0)
        kotlin.test.assertTrue(Benchmark.running(ctx, t0 + 1_000))
        // Alive for the first 10 minutes (several beats per minute count once), then silent 20, then alive to 40.
        for (m in 0 until 10) { Benchmark.beat(ctx, t0 + m * 60_000L); Benchmark.beat(ctx, t0 + m * 60_000L + 5_000) }
        for (m in 30 until 40) Benchmark.beat(ctx, t0 + m * 60_000L)
        val r = Benchmark.result(ctx, t0 + 40 * 60_000L)!!
        kotlin.test.assertEquals(40, r.minutes); kotlin.test.assertEquals(20, r.alive)
        kotlin.test.assertEquals(20, r.longestGapMin); kotlin.test.assertEquals(listOf(t0 + 10 * 60_000L to 20), r.gaps)
        kotlin.test.assertEquals(0.5, r.share, 1e-9)
        Benchmark.stop(ctx, t0 + 40 * 60_000L)
        kotlin.test.assertFalse(Benchmark.running(ctx, t0 + 41 * 60_000L))
        Benchmark.beat(ctx, t0 + 45 * 60_000L)
        kotlin.test.assertEquals(20, Benchmark.result(ctx, t0 + 50 * 60_000L)!!.alive, "beats after the end are not counted")
    }
}

class DrawdownTest {
    @org.junit.Test fun `the biggest drop is measured from the highest point before it`() {
        val pts = listOf(0L to 0.0, 1L to 5.0, 2L to 2.0, 3L to 8.0, 4L to 1.0, 5L to 9.0)
        kotlin.test.assertEquals(Triple(7.0, 3L, 4L), com.marketbot.app.ui.maxDrawdown(pts))
        kotlin.test.assertNull(com.marketbot.app.ui.maxDrawdown(listOf(0L to 0.0, 1L to 1.0)), "never fell")
    }
}
