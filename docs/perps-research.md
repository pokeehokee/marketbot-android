# Kalshi perps: measured 2026-11-12, not traded

What the "Invest" product is, what was measured, and why the app records it instead of trading it.
Every number here comes from Kalshi's public API on 2026-11-11/12; none needs an account.

## The product

Perpetual futures on a separate exchange (`/margin`, its own `perps_openapi.yaml`, ~55 endpoints).
127 markets listed, 86 with a two-sided book: 58 equities, 19 crypto, 6 metals, 2 indices, 1 energy.
Funding is charged every 8 hours (05:00, 13:00, 21:00 UTC) instead of settlement.

Contract sizes are small enough for a retail account: a BTC perp contract is ~$7.37, gold ~$4.00,
Apple ~$7.12, NVDA ~$3.20. Leverage estimates run 2.9x to 15.3x; initial margin multiplier 1.3.

## Why not trade it

**1. The books are professionally made.** Spreads: median 9.9 bps, p25 5.0. BTC perp trades
$438M/day notional at 0.3 bps. Every edge this project has found rested on a thin book letting a
small resting order fill, and three separate measurements (sports, Polymarket temperature, Kalshi
non-weather) closed on exactly that wall. Here we would be the slowest participant.

**2. Funding carry is not an edge at this size.** An unhedged short collects funding and pays the
price move. Decomposed over every published funding period:

| market | periods | from funding | from price | funding's share | 1st half | 2nd half |
| --- | --- | --- | --- | --- | --- | --- |
| KXBTCPERP | 483 | +4.46% | -15.93% | 22% | -75%/yr | +23%/yr |
| KXETHPERP | 483 | -0.03% | -19.79% | 0.2% | -149%/yr | +59%/yr |
| KXSOLPERP | 483 | +1.07% | -23.23% | 4% | -136%/yr | +35%/yr |
| KXGOLDPERP | 61 | +1.33% | +8.79% | 13% | +185%/yr | +178%/yr |
| KXSILVERPERP | 61 | +1.31% | +21.14% | 6% | +256%/yr | +546%/yr |
| KXUS500PERP | 36 | +0.48% | +2.41% | 17% | +163%/yr | +13%/yr |
| KXUSTECH100PERP | 28 | +0.40% | +5.18% | 7% | +74%/yr | +362%/yr |
| KXWTIFUTURE | 28 | +0.55% | +8.25% | 6% | -672%/yr | +1361%/yr |

The headline figures look spectacular — short gold reads +182%/yr at Sharpe 5.2, silver +403%/yr at
6.9, and "short only when funding exceeds the deadband" reads +813%/yr at Sharpe 13 on silver — and
they are an artifact. **The carry is 0.8-2% of the 8-hour price standard deviation**, so the P&L is
direction with a rounding error of subsidy. Gold and silver are consistent across halves only
because both fell through the single 20-day window the exchange publishes; WTI's halves differ by
2,000 points. 61 funding periods over one regime measures the regime.

**3. The hedge does not exist inside Kalshi.** A real carry trade is short perp plus long spot.
Kalshi lists no spot, and across all 127 markets no underlying appears as both a perp and a dated
future (the four futures are Brent, Henry Hub gas, TTF and WTI; none has a matching perp). Hedging
means a second venue with its own capital.

**4. The frictions are the same size as the carry.** Per 8-hour period:

| | premium | spread | 8h funding | round trip (spread only) |
| --- | --- | --- | --- | --- |
| KXBTCPERP | 0.0042% | 0.0054% | 0.0125% | >= 0.0108% |
| KXETHPERP | 0.0083% | 0.0049% | 0.0000% | >= 0.0097% |

One round trip costs about one whole funding period before fees (perp fee tiers need an account key
and were not read). The premium is also *smaller* than the spread, so premium mean-reversion is not
tradeable either.

**5. Leverage breaks the app's invariant.** The risk controls, the daily brake and three tests all
assume collateral locked is the most a position can lose. Perps have liquidation prices. Arming them
would need a different risk model, not a new strategy.

Separately: the 94 equity perps launched around 2026-11-05 and have ~7 funding observations each.
There is nothing there to measure yet.

## What the app does instead

`core/research/PerpsFunding.kt` records the inputs forward, at no risk: public data, no keys, no
orders, no leverage. Off by default; switched on in Settings > Claude sync.

- **Samples** every 15 minutes for the 25 busiest two-sided markets: book, reference price, premium
  index, in-progress funding estimate, open interest, 24h notional, leverage, contract size.
- **Finalized funding** periods past a per-ticker watermark, so repeated passes append rather than
  duplicate. Kept 400 days: the long series is the point.

Out through the Claude sync as `exports/perps-latest.csv` and `exports/perps-funding.csv`.

## When to look again

Re-run the decomposition once the funding file holds a few months across more than one regime, and
require what every other candidate here had to clear: a result that holds on both halves of the
window, with an interval excluding zero. Until then the honest answer is that perps have no
measured edge for this account, and the recorder exists so that answer can change on evidence.
