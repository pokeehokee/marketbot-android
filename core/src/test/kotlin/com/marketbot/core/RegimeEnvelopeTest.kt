package com.marketbot.core

import com.marketbot.core.strategy.Check
import com.marketbot.core.strategy.RegimeEnvelope
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The flag is a bounds check on our EVIDENCE, and these tests exist mostly to
 * stop it quietly becoming a selector.
 *
 * Volatility was measured against the ladder's 398 settled event-hours and does
 * NOT predict the edge: quartiles read +0.79c / +3.24c / +0.91c / +1.34c, which
 * is non-monotonic with the wildest quartile beating the calmest. The reason is
 * that premium per contract FALLS as vol rises (6.59c -> 5.22c) because the book
 * selects on price, so the band is already a vol classifier. Anything that reads
 * this flag as "trade less when wild" is acting on a table that does not say so.
 */
class RegimeEnvelopeTest {

    @Test fun `inside the measured range it passes and claims nothing`() {
        val c = RegimeEnvelope.check(RegimeEnvelope.MEASURED_MEDIAN)
        assertEquals(Check.Verdict.PASS, c.verdict, c.detail)
        assertTrue("not a signal" in c.detail, "it must say it is not a signal: ${c.detail}")
    }

    @Test fun `it does not fire across the middle of the range`() {
        // A guard that fires one hour in ten is one that gets ignored -- the same
        // mistake as the inner clipping guard dropped from skipped_hours.py.
        for (f in listOf(0.06, 0.2, 0.5, 0.8, 0.94)) {
            val vol = RegimeEnvelope.MEASURED_MIN + f * (RegimeEnvelope.MEASURED_MAX - RegimeEnvelope.MEASURED_MIN)
            assertEquals(Check.Verdict.PASS, RegimeEnvelope.check(vol).verdict, "fired at f=$f")
        }
    }

    @Test fun `beyond the most volatile hour measured it fails, and says it is not a prediction`() {
        val c = RegimeEnvelope.check(RegimeEnvelope.MEASURED_MAX * 1.2)
        assertEquals(Check.Verdict.FAIL, c.verdict)
        assertTrue("not a prediction" in c.detail, c.detail)
        assertTrue("non-monotonic" in c.detail, "it must carry the finding that vol does not predict: ${c.detail}")
    }

    @Test fun `below the calmest hour measured it fails for a different reason`() {
        val c = RegimeEnvelope.check(RegimeEnvelope.MEASURED_MIN * 0.5)
        assertEquals(Check.Verdict.FAIL, c.verdict)
        assertTrue("CALMER" in c.detail, c.detail)
        assertTrue("no flow" in c.detail, "the calm risk is fills, not the edge: ${c.detail}")
    }

    @Test fun `an unreadable volatility is INSUFFICIENT, never a pass`() {
        for (v in listOf(null, Double.NaN, 0.0, -1.0)) {
            assertEquals(Check.Verdict.INSUFFICIENT, RegimeEnvelope.check(v).verdict, "for $v")
        }
    }

    @Test fun `it can never halt a working book`() {
        val verdicts = listOf(null, Double.NaN, RegimeEnvelope.MEASURED_MIN * 0.5, RegimeEnvelope.MEASURED_MEDIAN,
            RegimeEnvelope.MEASURED_MAX * 2).map { RegimeEnvelope.check(it).verdict }
        assertTrue(Check.Verdict.KILL !in verdicts, "nothing here is strong enough to KILL: $verdicts")
    }

    @Test fun `the index's per-root-second sigma converts to hourly`() {
        // BtcIndex.sigma() is per sqrt(second); the envelope is per hour.
        assertEquals(60.0, RegimeEnvelope.hourlyFromIndexSigma(1.0), 1e-9)
        assertEquals(sqrt(3600.0), RegimeEnvelope.hourlyFromIndexSigma(1.0), 1e-9)
        // A realistic reading: 0.38% hourly is the measured median.
        val perSecond = RegimeEnvelope.MEASURED_MEDIAN / 60.0
        assertEquals(Check.Verdict.PASS,
            RegimeEnvelope.check(RegimeEnvelope.hourlyFromIndexSigma(perSecond)).verdict)
    }

    @Test fun `the envelope is ordered and the percentiles are the measured ones`() {
        assertTrue(RegimeEnvelope.MEASURED_MIN < RegimeEnvelope.MEASURED_P05)
        assertTrue(RegimeEnvelope.MEASURED_P05 < RegimeEnvelope.MEASURED_MEDIAN)
        assertTrue(RegimeEnvelope.MEASURED_MEDIAN < RegimeEnvelope.MEASURED_P95)
        assertTrue(RegimeEnvelope.MEASURED_P95 < RegimeEnvelope.MEASURED_MAX)
        assertEquals(398, RegimeEnvelope.MEASURED_HOURS)
    }

    @Test fun `position reports where in the range we are, clamped`() {
        assertEquals(0.0, RegimeEnvelope.position(RegimeEnvelope.MEASURED_MIN), 1e-9)
        assertEquals(1.0, RegimeEnvelope.position(RegimeEnvelope.MEASURED_MAX), 1e-9)
        assertEquals(0.0, RegimeEnvelope.position(0.0), 1e-9)
        assertEquals(1.0, RegimeEnvelope.position(99.0), 1e-9)
    }
}
