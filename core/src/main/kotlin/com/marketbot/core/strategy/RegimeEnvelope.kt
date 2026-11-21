package com.marketbot.core.strategy

import kotlin.math.sqrt

/**
 * Are we trading in conditions the edge was actually measured in?
 *
 * This is a SAFETY FLAG, not a selector, and the distinction is the whole
 * design. It answers "has this strategy ever been measured here?" -- a question
 * about our evidence. It does NOT answer "is the edge better or worse now?",
 * because that was measured and the answer is that volatility does not predict
 * it.
 *
 * MEASURED 2026-11-21, the ladder's 398 settled event-hours bucketed by trailing
 * 12-hour realised volatility at entry (zero lookahead, Coinbase hourly closes):
 *
 * | vol quartile | hours | per contract | loss ratio | premium/ct |
 * | --- | --- | --- | --- | --- |
 * | Q1 calm | 99 | +1.08c | 0.84 | 6.59c |
 * | Q2      | 100 | **+2.95c** | 0.52 | 6.12c |
 * | Q3      | 99 | +1.09c | 0.82 | 5.94c |
 * | Q4 wild | 100 | +1.22c | 0.77 | 5.22c |
 *
 * CORRECTED 2026-11-21. The first version of this table used an estimator whose
 * newest hourly return ended AT the settling hour -- Coinbase candle keys are
 * the bucket START, so `H[hour]` closes 3,600s LATER. It read Q4 at -0.16c with
 * a ratio of 1.03, which would have looked like "wild hours lose". They do not:
 * strictly-prior returns put Q4 at +1.22c, ratio 0.77. The conclusion is
 * unchanged and was conservative either way, but the numbers were wrong.
 *
 * **Non-monotonic, and the wildest quartile beats the calmest.** Q2 is one cell
 * of four and has the shape this repo rejects as overfitting -- the 04-05 ET
 * hour read -2.27c in-sample and +5.28c out. So there is no vol-conditioned
 * parameter to set, and anything built on that table would be fitting noise.
 *
 * The last column says why, and it is the useful part: premium per contract
 * FALLS as volatility rises (6.48c -> 5.23c), because the book selects on PRICE
 * and a 0.94 bid sits further from spot when vol is higher. **The price band is
 * already a volatility classifier**, continuously repriced by the market, which
 * is a better one than anything fitted here -- the same reason the cheap-tail
 * study found "the market already widens the ask on volatile days by about the
 * right amount".
 *
 * So what is left for a flag to do is narrow and honest: notice conditions
 * OUTSIDE the range where any of those four measurements exist. Inside the
 * range the flag stays quiet and makes no claim.
 */
object RegimeEnvelope {
    /**
     * Trailing 12-hour realised volatility of hourly BTC log returns, as a fraction.
     *
     * Every return used closes STRICTLY BEFORE the hour being judged. Coinbase
     * candle keys are the bucket START and the close is at `key + granularity`,
     * so the newest usable bucket starts at `floor(t/3600) - 3600`. Taking
     * `H[floor(t/3600)]` instead reads a price up to an hour into the future --
     * which is how the first cut of this table got Q4 wrong, and how a separate
     * factor study the same day produced a spurious -4.20c "finding" that
     * vanished once the spot was moved before the entry.
     */
    const val MEASURED_MIN = 0.000582
    const val MEASURED_P05 = 0.001181
    const val MEASURED_MEDIAN = 0.003831
    const val MEASURED_P95 = 0.007442
    const val MEASURED_MAX = 0.014650

    /** Event-hours behind the envelope, 2026-09-13 to 2026-11-20. */
    const val MEASURED_HOURS = 398

    const val NAME = "Trading inside measured conditions"

    /**
     * `BtcIndex.sigma()` is per-sqrt-second; the envelope is per hour.
     * sigma_hour = sigma_s * sqrt(3600).
     */
    fun hourlyFromIndexSigma(sigmaPerSqrtSecond: Double): Double = sigmaPerSqrtSecond * sqrt(3600.0)

    /**
     * Where `vol` sits in the measured range, 0.0 at the floor and 1.0 at the
     * ceiling. Reported so the operator can see the approach to an edge instead
     * of only the crossing.
     */
    fun position(vol: Double): Double =
        ((vol - MEASURED_MIN) / (MEASURED_MAX - MEASURED_MIN)).coerceIn(0.0, 1.0)

    private fun pct(v: Double) = "%.3f%%".format(v * 100)

    /**
     * FAIL only OUTSIDE the range ever observed -- which is rare by
     * construction, and deliberately so. An earlier guard in this repo warned
     * whenever a value sat within a fixed margin of its window and so would have
     * fired on every sample; a guard that always fires is one that gets ignored.
     * Flagging the middle 90% would fire one hour in ten for no new information.
     *
     * It can never return KILL. Nothing here is strong enough to halt a book
     * that is working, and the verdict rests on 398 hours in a single market
     * regime (BTC fell from ~$72.8k to ~$63.2k across the whole window), so the
     * envelope is what we have seen rather than what is possible.
     */
    fun check(hourlyVol: Double?): Check {
        if (hourlyVol == null || hourlyVol.isNaN() || hourlyVol <= 0.0) {
            return Check(NAME, Check.Verdict.INSUFFICIENT,
                "volatility could not be read, so it is unknown whether conditions are ones the edge was measured in")
        }
        val where = "now ${pct(hourlyVol)} hourly against a measured range of " +
            "${pct(MEASURED_MIN)}-${pct(MEASURED_MAX)} over $MEASURED_HOURS hours (median ${pct(MEASURED_MEDIAN)})"
        return when {
            hourlyVol > MEASURED_MAX -> Check(NAME, Check.Verdict.FAIL,
                "MORE volatile than anything the edge was measured in -- $where. " +
                    "This is not a prediction that the edge is gone: inside the measured range volatility does not " +
                    "predict it at all (quartiles +1.08c / +2.95c / +1.09c / +1.22c, non-monotonic). It says the " +
                    "evidence does not reach here, and the worst hours are one-sided sweeps, which need a large move.")
            hourlyVol < MEASURED_MIN -> Check(NAME, Check.Verdict.FAIL,
                "CALMER than anything the edge was measured in -- $where. Thin conditions can mean the deep-ITM " +
                    "strikes carry no flow to fill against rather than any change in the edge.")
            else -> Check(NAME, Check.Verdict.PASS,
                "$where, at the ${"%.0f".format(position(hourlyVol) * 100)}th percentile of it. " +
                    "Volatility does not predict the edge inside this range, so this is a bounds check, not a signal.")
        }
    }
}
