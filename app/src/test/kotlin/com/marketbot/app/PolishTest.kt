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
