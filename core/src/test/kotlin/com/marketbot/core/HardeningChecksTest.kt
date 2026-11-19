package com.marketbot.core

import com.marketbot.core.store.QuoteRow
import com.marketbot.core.strategy.Check
import com.marketbot.core.strategy.KillCriteria
import com.marketbot.core.strategy.Mode
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two controls from docs/hardening.md sections 3 and 4. Both exist because
 * the failure they catch has already happened on this account or in the source
 * repo, so each test names the incident it is standing in for.
 */
class HardeningChecksTest {

    private val now: Instant = Instant.parse("2026-11-19T12:00:00Z")

    private fun row(
        ticker: String,
        filled: Double? = 5.0,
        mode: Mode = Mode.LIVE,
        resolved: Instant? = null,
    ) = QuoteRow(
        id = ticker.hashCode().toLong(), strategyId = "kalshi_btc_deep_itm", mode = mode,
        venue = "kalshi", ticker = ticker, action = "buy_yes", price = 0.94, contracts = 5.0,
        placedAt = now.minus(Duration.ofHours(2)), filledContracts = filled, resolvedAt = resolved,
    )

    // ---- section 3: exchange-vs-ledger reconciliation -----------------------

    @Test
    fun `a position the ledger has never seen fails the check`() {
        // The 1,612 orphan fills of 2026-11-12, in miniature.
        val c = KillCriteria.orphanPositions(
            exchange = mapOf("KXBTCD-26NOV1917-T59999.99" to 5.0),
            rows = emptyList(),
            ownedPrefixes = listOf("KXBTCD"),
            now = now,
        )
        assertEquals(Check.Verdict.FAIL, c.verdict)
        assertTrue(c.detail.contains("never seen"), c.detail)
    }

    @Test
    fun `another book's series is not this book's orphan`() {
        // One Kalshi account carries several books. Alarming on someone else's
        // position is noise, and a noisy check is an ignored check.
        val c = KillCriteria.orphanPositions(
            exchange = mapOf("KXHIGHNY-26NOV19-B63.5" to 10.0),
            rows = emptyList(),
            ownedPrefixes = listOf("KXBTCD"),
            now = now,
        )
        assertEquals(Check.Verdict.PASS, c.verdict)
    }

    @Test
    fun `a graded row still counts as known`() {
        // Between the fill and settlement the venue can still report a position.
        // That is not an orphan, and treating it as one would fire every hour.
        val t = "KXBTCD-26NOV1917-T59999.99"
        val c = KillCriteria.orphanPositions(
            exchange = mapOf(t to 5.0),
            rows = listOf(row(t, resolved = now.minus(Duration.ofMinutes(5)))),
            ownedPrefixes = listOf("KXBTCD"),
            now = now,
        )
        assertEquals(Check.Verdict.PASS, c.verdict)
    }

    @Test
    fun `a position recorded smaller than the venue holds fails`() {
        // A partial fill booked at the wrong size: the repo's 2026-08-31 entry
        // records 3-of-20 fills recorded as 20. This is the same gap inverted.
        val t = "KXBTCD-26NOV1917-T59749.99"
        val c = KillCriteria.orphanPositions(
            exchange = mapOf(t to 7.0),
            rows = listOf(row(t, filled = 5.0)),
            ownedPrefixes = listOf("KXBTCD"),
            now = now,
        )
        assertEquals(Check.Verdict.FAIL, c.verdict)
        assertTrue(c.detail.contains("smaller"), c.detail)
    }

    @Test
    fun `a zero-size venue position is not an orphan`() {
        val c = KillCriteria.orphanPositions(
            exchange = mapOf("KXBTCD-26NOV1917-T1" to 0.0),
            rows = emptyList(),
            ownedPrefixes = listOf("KXBTCD"),
            now = now,
        )
        assertEquals(Check.Verdict.PASS, c.verdict)
    }

    @Test
    fun `claiming no series cannot silently pass`() {
        // If the owned-series list is empty the check can prove nothing, and
        // must not report PASS -- a check that cannot observe its target is not
        // a check (the lesson from the mis-specified mid-price control).
        val c = KillCriteria.orphanPositions(
            exchange = mapOf("KXBTCD-26NOV1917-T1" to 5.0),
            rows = emptyList(),
            ownedPrefixes = emptyList(),
            now = now,
        )
        assertEquals(Check.Verdict.INSUFFICIENT, c.verdict)
    }

    // ---- section 4: per-table write freshness -------------------------------

    @Test
    fun `a writer frozen past its own cadence fails and names itself`() {
        // settlements froze for 4 days behind a NameError in an alert.
        val c = KillCriteria.staleWrites(
            newest = mapOf(
                "settlements" to now.minus(Duration.ofDays(4)),
                "orders" to now.minus(Duration.ofMinutes(3)),
            ),
            expected = mapOf("settlements" to Duration.ofHours(1), "orders" to Duration.ofMinutes(5)),
            now = now,
        )
        assertEquals(Check.Verdict.FAIL, c.verdict)
        assertTrue(c.detail.contains("settlements"), c.detail)
    }

    @Test
    fun `thresholds are per writer, so a slow writer is not stale`() {
        // A single global threshold either misses the slow writers or cries wolf
        // on the fast ones. The nightly backup is 24h-cadence and healthy here.
        val c = KillCriteria.staleWrites(
            newest = mapOf(
                "backup" to now.minus(Duration.ofHours(20)),
                "orders" to now.minus(Duration.ofMinutes(4)),
            ),
            expected = mapOf("backup" to Duration.ofHours(24), "orders" to Duration.ofMinutes(5)),
            now = now,
        )
        assertEquals(Check.Verdict.PASS, c.verdict)
    }

    @Test
    fun `a writer that has never run is insufficient, not failed`() {
        val c = KillCriteria.staleWrites(
            newest = mapOf("equity" to null, "orders" to now.minus(Duration.ofMinutes(1))),
            expected = mapOf("equity" to Duration.ofMinutes(15), "orders" to Duration.ofMinutes(5)),
            now = now,
        )
        assertEquals(Check.Verdict.INSUFFICIENT, c.verdict)
        assertTrue(c.detail.contains("equity"), c.detail)
    }

    @Test
    fun `a writer with no registered cadence is skipped rather than guessed at`() {
        val c = KillCriteria.staleWrites(
            newest = mapOf("mystery" to now.minus(Duration.ofDays(30))),
            expected = emptyMap(),
            now = now,
        )
        assertEquals(Check.Verdict.PASS, c.verdict)
    }

    @Test
    fun `no writers registered cannot pass`() {
        val c = KillCriteria.staleWrites(emptyMap(), emptyMap(), now)
        assertEquals(Check.Verdict.INSUFFICIENT, c.verdict)
    }
}
