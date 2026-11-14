# CLAUDE.md

Android app (Kotlin, Compose) that runs the Kalshi/Polymarket strategies from `pokeehokee/weatherbot`
and `pokeehokee/polymarket` on the phone.

## Layout

- `core/`: pure Kotlin/JVM. API clients (`kalshi/`, `polymarket/`, `btc/`), signers (`net/Signers.kt`),
  provenance (`provenance/Sourced.kt`), strategy framework (`strategy/`), the four strategies (`strategies/`), and the engine (`engine/Engine.kt`).
- `app/`: Room stores implementing the core store interfaces, a Keystore-encrypted credential vault, `EngineService` (foreground service), and the Compose UI.

## Rules

- **API first.** Every value that a venue's API provides is read from that field and tagged with its origin. Calculate a value
  only when no field exists, and tag it `DERIVED` with the formula and the reason. Missing values are never zero.
- **Never grade live P&L locally.** Kalshi: `/portfolio/settlements` (`settlementPnl`) only; defer until readable.
- **Paper and live never mix.** Every ledger query filters by mode.
- **Order creates are never retried.** Recover a lost response by `client_order_id`.
- **Kalshi orders carry the market's `exchange_index`, and cancels send `market_ticker`.**
- **Strategy ports are golden-tested** against the original Python (`core/src/test/resources/golden`, regenerate with
  `golden.py`). If you change a port, the golden test must still pass or the divergence must be documented.
- Defaults are measured values from the source repos' CLAUDE.md; change them only with a new measurement.
- **Exact venue words live in `core/contract/Vocabulary.kt`.** Any enum value, field name, slug or ticker shape the code
  branches on is defined there (or pointed at from there), and the daily API wording check (`./gradlew :core:contractTest`,
  `.github/workflows/api-contract.yml`, and once a day in the app) compares live answers and Kalshi's OpenAPI spec against it.
  A refusal the app recognises only by its words must fail safe when the words change.

## Kalshi API

`.claude/skills/kalshi-api/` holds the API as the official docs define it, reviewed in full on
2026-11-11 (the OpenAPI spec plus all 287 doc pages and the changelog). Read it before writing or
changing any Kalshi call. The rule it exists for:

- **A missing settlement is not a missing position.** Kalshi partitions portfolio data into a live
  and a historical tier. `GET /portfolio/settlements` returns only records still in the live set, so
  grading a settled position takes up to three calls in order: that endpoint, then
  `GET /portfolio/positions?settlement_status=settled` (the endpoint defaults to `unsettled`, so a
  settled position is otherwise invisible), then `GET /historical/positions`. Reading only the first
  left every filled live row of the BTC ladder ungraded.
- `determined` is not `finalized`: `result` is set before any payout, with a settlement timer still
  running, so a readable result does not imply a settlement record exists.
- Crypto and commodities trade on exchange shard 2 and **API orders do not fund a shard
  automatically**; `balance_breakdown` is the per-shard view.

## BTC deep-ITM: the band is not the risk control, the slot order is

**Corrected 2026-11-12.** An earlier version of this section said bids at or under 0.94 had "no
edge" and were "net negative", and recommended raising `band_lo`. That rested on the paper book:
356 settled fills, 13 losses, 11 days. It is wrong, and the correction came from finding a much
larger live sample of the same strategy.

**The original desktop run was recovered from the exchange**, not from any local record: every
`KXBTCD` fill on the account whose `order_id` the app never placed, joined to its settlement
payload (`/portfolio/fills` + `/portfolio/settlements`). 1,612 fills, 1,535 positions,
2026-09-21 to 10-04. Its parameters, recovered from the fills alone, are **identical to this
strategy's**:

| parameter | recovered from fills | shipped setting |
| --- | --- | --- |
| lot | 5 (1,396 of 1,612; rest partials) | `lot: 5` |
| price band | 0.90-0.96 | `band_lo: 0.90`, `band_hi: 0.96` |
| concurrent strikes per event-hour | 8, a hard ceiling | `max_open: 8` |
| seconds to close at fill | min 342, max 2999, **100% inside [300,3000]** | `ttc_min: 300`, `ttc_max: 3000` |
| market close hours skipped (ET) | 03 and 10 exactly zero; 21 and 23 near-zero | `skip_hours_et: 3,10,21,23` |
| execution | 0 of 1,612 taker | post-only |
| sides | 794 yes / 818 no | both |

So the app is a faithful port, and that run is 1,535 live positions and **73 real losses** of
exactly this configuration against the paper book's 13.

**On it, the sub-0.94 band is profitable.** Clustered by day:

| band | n | lost | ratio | per contract |
| --- | --- | --- | --- | --- |
| 0.90-0.92 | 387 | 31 | 0.89 | +1.60c |
| 0.92-0.94 | 554 | 30 | 0.77 | +1.33c |
| 0.94-0.955 | 236 | 5 | 0.40 | +3.21c |
| 0.955+ | 358 | 7 | 0.71 | +1.99c |

Below 0.94: **+1.45c/contract, 95% CI [+0.65, +2.27]** on 941 positions. Pooled with the app's own
130 graded live fills: +1.31c on 971 positions and 64 losses. A floor at 0.94 would discard a
positive-expectancy trade. The paper book's "ratio 1.01" was 13 losses of noise.

**What survives is the ordering argument, and it is stronger than the floor one ever was.** The
pool (20-170) exceeds `max_open` in 127 of 135 hours, so order decides which markets get capital.
`>=0.94` beats `<0.94` by **+1.4c/contract, CI [-0.13, +3.00], P(better) 97%** pooled live. That is
an argument for spending scarce slots on the dearer band, not for refusing the cheaper one -- and
`slot_order: dearest` does exactly that while still filling the lower band when the better one is
empty, which a hard floor cannot. It runs as a paper variant; `VariantVerdict` decides.

Limits to keep attached to these numbers: the advantage CI touches zero; only 39% of the original
run's fills were >=0.94, so `dearest` may not fill all 8 slots in thin hours; the desktop window is
14 days in one regime and its own second half was weaker (+0.84c, CI [-0.61, +2.07]).

**The app's live book is graded and it agrees.** 130 filled live rows, matched to exchange fills on
`order_id` with **zero fill-count disagreements**, graded from settlement payloads: **+$17.05,
127 wins / 3 losses, +2.62c/contract, $0.00 in fees** (maker fills pay nothing, 130 of 130). All
three losses were bought below 0.94 -- which is what made the small sample look like a dead band.

> **Why the paper book misled.** 13 losses cannot resolve a trade whose result is dominated by rare
> losses; five of them fell on one day. The repo already records this failure mode for the KXLOW
> six-date sample and the first Polymarket pull. The rule it implies: before acting on a band split,
> count the LOSSES, not the fills.

## Commodity range longshot: the edge is at 60 minutes, and the book is under-deployed

Measured 2026-11-13 against settled history, because the paper book could not answer the question:
101 settled fills looked like ratio 0.81 and +2.14c/contract, but they spanned only **7 close-dates**
and every band split inside them rested on 0-3 losses. A commodity range settles off one price move,
so a day's strikes win or lose together and 101 fills are not 101 observations.

`research/commodity/commodity_sample.py` prices **every settled market** of the traded series at two leads
with zero lookahead -- the candle used is the last whose period ENDS at or before `close - lead`, and
the request window itself ends there -- and `score_commodity.py` scores selling the cheap side at the
resting price, spread gate <= 5c, CIs bootstrapped **clustered by close-date**. Resting fills pay no
fee on these series; both scripts are committed under `research/commodity/` with the two
pricing rules they depend on. All **12 of 12** traded series are sampled (Brent, WTI, nat gas, gold,
silver, copper -- daily and weekly); silver and copper, added last, moved nothing material.

| lead | markets | close-dates | losses / implied | ratio | EV/contract | 95% CI |
| --- | --- | --- | --- | --- | --- | --- |
| **60m (the shipped 40-90 min window)** | 2,733 | 49 | **29 / 135.0** | **0.21** | **+3.88c** | **[+3.47, +4.22]** |
| 720m (variant B's 660-780 min) | 2,886 | 48 | 156 / 217.8 | 0.72 | +2.14c | [+0.35, +3.75] |

29 realised losses where 135.0 were implied is the mispricing, and it holds across halves (0.18 then
0.23) with **48 of 49 days positive** and a worst day of -3.6c/contract. Every one of the 12 series
is positive at 60m, the weakest at +2.97c (KXWTI, ratio 0.36).

**The edge decays with lead, and variant B sits on the wrong side of it.** Out to 12 hours the ratio
runs to 0.72, the two halves disagree (0.49 then 0.82, and the second half's CI runs through zero at
+1.38c [-0.96, +3.65]), 15 of 48 days are
negative with a worst day of -53.2c, and **two series read ABOVE fair** -- KXWTI 1.12 and KXGOLDD 1.10,
i.e. a seller loses. That is the sign flip weatherbot's CLAUDE.md records for the five liquid daily
financial ranges at a 24h lead (ratios 1.2-3.1, "-5 to -15c per contract to a seller") against the
favourable reading in the last hours. Variant B's 11-13h window is in the decay region. It is paper
and disarmed so it costs nothing, but its record will take hundreds of settlements to distinguish
from the main book and the question it was asked is already answered. **This strategy is structurally
the BTC deep-ITM trade, not the weather longshot: residual premium on an almost-decided contract.
The two respond OPPOSITELY to a lead change -- weather's bias GROWS with lead.**

**Every loss lives where the volume is, and the premium does not.** At 60m, all 29 losses fall in the
markets with 1,000+ contracts of total volume (ratio 0.30, EV +3.92c [+3.30, +4.42]); the quieter
buckets carry **zero** losses because they were never contested -- certainty with a few cents of
premium that nobody has reason to lift. But per contract the three volume buckets pay the same
(+3.77c under 50, +3.83c at 50-1,000, +3.92c above), so volume predicts where the variance is, not
where the money is, and there is no selection lever here. This is
the opposite of the sports and Polymarket-US failure mode, where the edge sat behind an unfillable
queue.

**Fills are NOT adversely selected here.** 269 paper quotes carry a fill verdict, and realised
per-contract P&L lands on the population figure in three of four bands:

| cheap band | quotes | filled | fill rate | paper realised | population @60m |
| --- | --- | --- | --- | --- | --- |
| 0.02-0.05 | 96 | 33 | **34%** | +3.05c | +3.15c |
| 0.05-0.10 | 38 | 19 | 50% | +7.16c | +5.76c |
| 0.10-0.15 | 26 | 11 | 42% | -17.20c | +7.47c |
| 0.15-0.25 | 54 | 39 | **72%** | +3.90c | +4.15c |

+3.05c against +3.06c is as close as that comparison gets. Unlike the weather book, whose fills
carried ratio 0.96 against a population 0.73 (a x1.3 selection penalty), these fills look like the
population. So the +2.14c-vs-+3.88c gap is **not** a structural leak: it is the 0.10-0.15 band's
-$8.60 on 3 losses over 50 contracts, plus mix (63% of quotes go to the cheap half, which earns
least). Fill rate rises monotonically with price, which is worth knowing and currently has no lever
attached.

> **A band priority was drafted and withdrawn, and the reason is the point.** Cheap quotes fill least
> (34% vs 72%) and lock the most collateral (96.5c vs 80c), so per dollar of collateral per quote the
> cheap band yields 1.09c against the dear band's 4.26c -- a 4x gap, and the same shape as weatherbot's
> band-priority finding. **But a priority rule only matters when capital is scarce, and here it is
> not.** Across 8 paper days the book locked $41-$262 of a $300 budget (median $122, 41%), with one
> day near the cap, and the tradeable pool is ~35 markets a day against ~63 the budget funds.
> Deprioritising a band promotes no better market into a book that already funds everything it
> qualifies; it would delete 33 fills worth +$4.99, **46% of the strategy's realised P&L**, and leave
> the collateral idle. I had imported weatherbot's constraint ("the selector accepts ~80-90 markets a
> day and a $300 budget backs ~40") into a strategy where the pool is SMALLER than the budget, and
> built a per-dollar-of-collateral table whose denominator was not the binding resource. **Check which
> resource binds before proposing an allocation rule.** This is the same error class as the
> 2026-11-12 `LOT_CAP` raise, which also rested on a replay whose constraint did not hold live.
> **The lever for an under-deployed book is to ADD markets, not remove them.**

**The 0.25-0.40 band is unmeasured by the strategy and positive in the sample.** `max_cheap` is 0.25
and the setting allows 0.40:

| band @60m | n | losses / implied | ratio | EV/contract | 95% CI | negative days | collateral/ct |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 0.02-0.25 (shipped) | 2,733 | 29 / 135.0 | 0.21 | +3.88c | [+3.47, +4.22] | 1 of 49 | 95c |
| 0.10-0.40 | 387 | 60 / 83.0 | 0.72 | +5.95c | [+2.37, +9.20] | 13 of 48 | 79c |
| 0.25-0.40 (the extension) | 137 | 36 / 43.7 | 0.82 | +5.64c | **[-0.11, +10.44]** | 13 of 39 | 68c |

60 losses makes 0.10-0.40 the best-sampled slice in the study, and it clears zero. It also locks LESS
collateral per contract, so the worst single-market loss falls from $4.75 to ~$3.40 at a 5-lot and the
$10 daily brake still stops the day before a second maximum loss -- the brake invariant gets safer,
not looser.

> **Silver and copper weakened the extension band, and it now matters which claim the variant
> rests on.** On the first eight series the 0.25-0.40 band read ratio 0.74 and +8.32c
> [+1.48, +14.05]; completing the sample took it to 0.82 and +5.64c **[-0.11, +10.44]** -- the
> interval now touches zero. The pooled 0.10-0.40 slice still clears it, so nothing is reversed, but
> the extension band alone is no longer a measured edge and must not be reported as one. Ten more
> losses moved the point estimate 2.7c; that is what a 36-loss sample is worth.

Proposed as **variant C at `min_cheap` 0.10 / `max_cheap` 0.40** (`2026-11-13-commodity-dear-half`),
paper, main book unchanged. The case for running it is now the **fill rate above 0.25, which is
unmeasured** -- the 34%-to-72% trend should extend, but that is extrapolation and establishing it is
what a paper variant is for -- not the band's EV. Honest limits: the losing-day profile is far worse
(13 of 48 against 1 of 49, worst day -61.0c/contract against -3.6c) because this band loses about a
quarter of the time per market instead of 1% -- more frequent, larger losses against a far larger
premium, the same trade-off the weather book records for its own extended band; and the pool is ~8
markets a day, so ~$30/day of collateral. `min_cheap` cannot express a dear-band-only test: its range
stops at 0.10.

## Perps: recorded, never traded

Kalshi's perps (the "Invest" product) are leveraged perpetual futures on the separate `/margin`
exchange. **The app does not trade them**, and `core/margin/MarginClient.kt` holds no signer and
makes no write calls, which `PerpsFundingTest` asserts. Two reasons, both measured on 2026-11-12
(`docs/perps-research.md`):

- A leveraged position can be liquidated for more than it cost, which breaks the invariant the risk
  controls and the daily brake rest on: collateral locked is the most a position can lose.
- The obvious strategy, funding carry, is not measurable from what the exchange publishes. Funding
  supplied 0.2-22% of an unhedged short's return and the price move supplied the rest; the carry is
  0.8-2% of the 8-hour price standard deviation; the two halves of the window disagree (WTI
  -672%/yr then +1361%/yr). Gold and silver read +180-400%/yr only because both fell through the one
  20-day window on record. A hedged carry is impossible inside Kalshi: no spot, and no perp and
  dated future on the same underlying.

`core/research/PerpsFunding.kt` records the inputs forward instead (off by default, switched on in
Settings > Claude sync): funding, premium and top of book every 15 minutes, plus every finalized
8-hour funding period past a per-ticker watermark. Out through the sync as
`exports/perps-latest.csv` and `exports/perps-funding.csv`; the funding file is kept for 400 days
because the long series is the whole point.

## Claude sync

Settings > Claude sync links the app to a private GitHub repository (`core/sync`). The app writes
`snapshot/latest.json` (every strategy's mode, settings and record) and `exports/orders-latest.csv`
once a day or on **Sync now**. It reads `proposals/pending.json`:

```json
{"proposals": [{"id": "2026-11-05-a", "strategy": "kalshi_weather_longshot@b",
  "set": {"max_mid_high": "0.15"}, "mode": "PAPER", "reason": "why"}]}
```

It writes each outcome to `proposals/results.json`. Nothing applies without the user's approval: a tap
for a setting change, the screen lock for a mode change. `ProposalPolicy` refuses, whatever the file
says:
- anything on a live strategy;
- any switch to live;
- raising a lot, loss limit or collateral share;
- values outside a setting's range;
- unknown settings.

A proposal may instead carry a one-off **research bet** for the `research_bets` strategy (paper only):

```json
{"proposals": [{"id": "bet-2026-11-09-chi", "strategy": "research_bets", "reason": "why",
  "bet": {"ticker": "KXHIGHCHI-26NOV10-T65", "side": "yes", "maxPrice": 0.47, "stakeUsd": 2.40,
          "probability": 0.54, "thesis": "...", "against": "...", "source": "..."}}]}
```

The app takes only the exact ticker and side named, buys at the ask once it is at or under `maxPrice` (taker fee), holds to
settlement, and writes every bet's price, outcome, `predictionCorrect`, Brier and paper P&L to `bets/results.json`. A bet
must fit the strategy's paper wallet ($30 default); approving the first one turns the strategy on in paper (screen lock).

A proposal may instead carry a **watch** item for the app's watchlist (`"strategy": "watchlist"`), which alerts
when a market's mid moves by `moveCents`:

```json
{"proposals": [{"id": "watch-2026-11-09-gas", "strategy": "watchlist", "reason": "re-check after Thursday's AAA print",
  "watch": {"venue": "kalshi", "ticker": "KXAAAGASM-26NOV30-3.70", "moveCents": 4, "label": "gas above 3.70"}}]}
```

It reads public prices and trades nothing, so it needs only a tap; the reference price is whatever the app reads
first, never the file's. `MAX_WATCH` caps the list at 50.

To find and research such bets, use the `kalshi-prediction` skill (`.claude/skills/kalshi-prediction/`): `scan.py` lists
live Kalshi markets that pass the liquidity, probability-band and runway filters (with breakeven prices and volume
anomalies), `estimates.py` records every verdict and grades it against the market's own price at settlement, and the
skill writes each target up in a fixed format ending in a bet or watch proposal. Both go into
`proposals/held-bets.json` until the user says to send them.

Snapshots and exports never carry keys.

## Commands

```bash
./gradlew :core:test                 # fast, pure JVM
./gradlew :app:testDebugUnitTest     # Robolectric; writes screenshots to app/build/screens
./gradlew :app:assembleDebug
```

In this sandbox Maven Central rate-limits, so `~/.gradle/init.d/mirror.gradle` and the `robolectricRepo` property point at
Google's Maven Central mirror. CI needs neither.
