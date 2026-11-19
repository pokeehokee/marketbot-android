# Is there a second deep-ITM pool on Kalshi?

The account's most profitable book buys **0.90–0.96 on an almost-decided
contract** and rests the bid (post-only). Every screen this repo had run looked
for the opposite shape — `screen_kalshi_pools.py` filters for a cheap tail at
0.02–0.25 to SELL. This screens for the shape that actually makes the money.

```bash
python3 research/screen/deep_itm_screen.py
```

Unauthenticated and read-only: `assert_read_only()` whitelists `/series` and
`/markets` and refuses anything portfolio- or order-shaped. 429 is retried
inside `get()`, because a rate-limited listing silently yields an empty answer
that reads exactly like "this series has no markets".

## The control is the whole design

`KXBTCD` must pass. The repo already records a screen that reported **zero
pools including its own known-good case** (`screen_kalshi_pools.py` required 6
cheap markets per series when a weather series carries 3–5), and this one
repeated the mistake in a new way on its first run:

> It filtered on **`ask` ∈ [0.90, 0.96]**. The ladder is **post-only — it rests
> a BID** and never lifts the ask. On the live book that is `bid 0.95 / ask
> 0.99`, so the real `KXBTCD` was rejected by a screen written to find it, and
> the run reported **0 of 357 series**. Only the control caught it. The
> condition is `bid ∈ band`.

A screen that fails its known-good case is measuring its thresholds, not the
market, and must not be reported as a result.

## Result, 2026-11-19

357 series settle hourly, 15-minutely or daily (of 15,126 total — the rest are
`one_off`, `custom` or annual and cannot support a recycling book). Of those,
**5** carry any two-sided market with the bid in band:

| series | category | freq | in-band | median 24h vol | median OI |
| --- | --- | --- | --- | --- | --- |
| `KXTXERCOTPEAKD` ERCOT peak demand | Economics | daily | 5 | **0** | **0** |
| **`KXBTCD`** (control) | Crypto | hourly | 4 | **7,996** | **6,027** |
| `KXETHD` | Crypto | hourly | 3 | — | — |
| `KXTSAD` TSA throughput | Economics | daily | 1 | 116 | 116 |
| `KXAAAGASCDMD` gas price | Economics | daily | 1 | 95 | 71 |

**A two-sided quote is not a fill.** Checked against settled history, because a
single snapshot of a *daily* series catches it at one phase of its life while an
hourly series is caught at every phase:

| series | settled markets that traded at all | median lifetime volume |
| --- | --- | --- |
| `KXTSAD` | **1 of 12** | **0** |
| `KXTXERCOTPEAKD` | 7 of 12 | **2** (flow is near the money; the deep-ITM strikes trade 0–5) |
| `KXAAAGASCDMD` | 12 of 12 | 243 |

ERCOT quotes two-sided on five deep-ITM strikes and has **never traded one** —
zero volume and zero open interest, a market maker's standing pair with nothing
behind it. Gas is the only other series with real flow, and throughput kills it:
~250 contracts per market against `KXBTCD`'s ~8,000, with about one in-band
market a day, so a resting 5-lot earns cents.

**BTC remains the only deep-ITM pool.** This agrees with the 2026-11-18 capital
review, which reached the same conclusion from the other direction (in-band
24h volume: KXBTCD 87,620 against 2,455 for the next best).

## What this does not establish

The snapshot is biased toward hourly series. To screen a *daily* series fairly
you must sample within an hour or two of ITS close, when its contracts are
near-decided; this run sampled ERCOT ~24h out. That caveat does not rescue any
of the three here (ERCOT's zero OI means it never traded at any phase, and the
other two are settled-history-checked), but a future run looking for daily pools
should be scheduled against each series' own close time.
