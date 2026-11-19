# 50 Kalshi methods, ranked by what is actually decidable

An operator-supplied list of 50 hypotheses, with a request to "backtest all of them
and rank them". This is the honest version of that: **17 are already answered by
measurements in this repo or `weatherbot`, 6 were answered on 2026-11-19, 9 are
testable with reachable data, and 18 cannot be backtested with any data this
project can reach.** Nothing below is a new backtest except where it says so.

Two cross-cutting facts, both verified live on 2026-11-19, that re-rank the list
before any individual method is considered.

### The Economics block rests on maker-fee series

`fee_type` across every series with open markets:

| category | `quadratic` | `quadratic_with_maker_fees` |
| --- | --- | --- |
| Sports | 4,197 | **107** |
| Financials | 1,284 | **13** |
| Economics | 937 | **10** |
| Crypto | 301 | **2** |

The ten Economics series carrying maker fees are exactly the ones methods #28–32
target: `KXCPI`, `KXCPIYOY`, `KXPAYROLLS`, `KXU3`, `KXGDP`, `KXFED`,
`KXFEDDECISION`, `KXRATECUTCOUNT`, `KXAAAGASM`, `KXEGGS`. **Resting an order
there is not free.** Both live books in this repo earn what they earn because a
resting fill pays nothing — 130 of 130 app-era fills settled at `fee_cost = 0`.
Any passive strategy on those tickers pays on both legs instead, which removes
the structural advantage before the hypothesis is even tested.

The two maker-fee Crypto series are `KXBTCMAX150` and `KXBTCMAX125`. **`KXBTCD`,
`KXETHD`, `KXWTI` and `KXGOLD` are all still `quadratic`**, so the ladder's
standing kill criterion ("any traded series moves to
`quadratic_with_maker_fees`") is clear as of this date. Re-check it; it is a
one-call test and it is the single thing that would end the ladder.

### The demo exchange is real

`https://demo-api.kalshi.co/trade-api/v2/exchange/status` returns 200.
`demo-api.kalshi.com` and `api.demo.kalshi.com` do not resolve. The operator's
advice to start there is sound and costs nothing.

---

## Tier 0 — PROVEN POSITIVE, already live

| # | method | verdict |
| --- | --- | --- |
| **45** | Near-expiry "sure thing" (buy 97–99c) | **This IS the BTC deep-ITM ladder.** +1.77c/contract over 10,551 settled contracts, 90 losses, $0.00 fees. The list's own highest-rated structural idea is the thing already running. Its band is 0.90–0.96 rather than 0.97–0.99, which the per-band table supports |
| **3** | Fee-aware passive entries | Not a strategy but the design rule both live books already follow: post-only, and the measured fee on 130 of 130 maker fills is $0.00. The 2026-10-30 replay that called the ladder dead was wrong *precisely* because it modelled taker fills |

## Tier 1 — ANSWERED, negative. Do not rebuild these.

| # | method | the measurement that closed it |
| --- | --- | --- |
| **1** | Liquidity-incentive farming | `GET /incentive_programs` (1,000 records) and `/incentive_schedules` (636) are `incentive_type: liquidity` on **every row**, `target_size_fp: "1000.00"`. Needs 1,000 contracts resting per side; no series we trade is in any pool. There is **no volume-rebate programme at all** |
| **4** | Two-sided spread capture | 7,050 **real** resting-buy fills scored against settlement: **−6.01c/contract**. Mean quoted spread 1.97c against a 2c minimum round-trip fee, so 84.3% of snapshots are unprofitable before adverse selection |
| **23** | Model-blend temperature | Brier 0.1693 vs market **0.1002**; worse than a constant base rate. Reliability 0.0345 vs 0.0025, resolution 0.0049 vs 0.0403 — the model carries ~8x less information. Blending into the price makes it monotonically worse |
| **25** | Station-specific bias | Real and already exploited: per-station rise cuts residual sd ~15%, and METAR understates the CLI daily max by 0.86°F. Wired into the live probability layer. **Still no edge** — the combined predictor is −0.0280 Brier but blending into the price is +0.0004, P(beat) 38.6% |
| **26** | Late-day observation trading | The market gets **sharper** as the day is observed (resolution 0.0403 day-ahead → 0.0730 at 13:00 → 0.0941 on the 13:00–14:00 subset). A rule built from observed temperature scores 0.1452 against the market's 0.0782 and degrades the price at every blend weight |
| **40** | Favorite-longshot, sports | The bias is **real**: +2.28c/contract, 95% CI [+0.49, +4.04], positive on both date halves. **Unfillable**: median resting ask on in-band ATP exact-score markets is **1,105 contracts** (p25 357) against median 24h volume of 39. A 5-lot behind that queue fills at a rate indistinguishable from zero |
| **43** | Mutually-exclusive set arb | 694 complete bucket partitions priced in full, 21 gross dislocations, **zero profitable after fees** (median −5c). Six legs of fees swamp a 1–3c gap |
| **47** | Event-driven news latency | On 17 markets where a METAR observation made YES logically impossible, the median mid **before** that observation was already 0.005 and did not move after. The market anticipates the release; there is no stale-quote premium |
| **14** | Funding-rate carry | `docs/perps-research.md`: funding supplied 0.2–22% of an unhedged short's return and the price move supplied the rest; carry is 0.8–2% of the 8-hour price sd; the two halves of the window disagree (WTI −672%/yr then +1361%/yr). A hedged carry is **impossible inside Kalshi** — no spot, and no perp and dated future on the same underlying |

## Tier 2 — ANSWERED on 2026-11-19

| # | method | result |
| --- | --- | --- |
| **44** | Ladder monotonicity arb | Violations are real and large — `KXBTCHITBY` asks *rise* with strike (100k at 0.04, 105k at 0.91) when a higher threshold is strictly harder. **Not tradeable: every bid is 0.00.** A lock needs the dominated leg sold and nothing bids. Those asks are lone orders in an empty book |
| **49** | Cross-venue Kalshi vs Polymarket | Stations match exactly (KNYC Central Park, KMDW, KLAX, KMIA, all "as reported by the NWS Climatological Report"). Aligned by bucket label across 4 cities and ~20 buckets, the **best gross gap is +1c** (Miami 80–81: sell PM 0.43, buy KX 0.42) against combined taker fees of **~2.9c**. Chicago's grids are offset 1°F so no replication exists there. Polymarket touch depth is often **0.01 shares**. And the venues settle through different authorities — Kalshi via The Weather Company since 2026-09-04, Polymarket via NWS CLI — so even a real gap is a ±100c variance bet, not a lock |
| **16** | Perp / range-market distribution | Partially answered from the other side: the daily financial range **tails** are underpriced at a 24h lead (ratio 1.2–3.1) and overpriced at 4h, and the hourly BTC cheap tail is a measured loss as a taker (−0.83c, CI [−1.40, −0.10] on 3,571 quotes). The distribution is not obviously mispriced in a direction that survives fees |
| **5** | Cross-exchange quoting | Falls with #49: the hedge leg prices within 1c of Kalshi, inside both spreads and well inside the fee |
| **48** | Calendar spreads on a series | Falls with #44: the long-dated series that would form the term structure (`KXBTCY`, `KXBTCHITBY`, `KXBTCMAX*`) carry huge open interest but **no bids**, so the near leg cannot be sold |
| **46** | Settlement-rule edge | Not a backtest, and it has already paid once: the series refresh found **Dallas settling on `CLIDFW` while the repo forecast Love Field (`KDAL`)**. Keep doing it — `verify_kalshi_series.py` runs daily — but it is a correctness practice, not a strategy with an EV |

## Tier 3 — TESTABLE, ranked by expected value per unit of work

| rank | # | method | why it ranks here | the honest obstacle |
| --- | --- | --- | --- | --- |
| **1** | 30 | Index daily range vs options-implied vol | SPX/VIX options are public and liquid; Kalshi index brackets are priced off the same underlying. This is the one econ/finance idea whose inputs are both free and plentiful | `KXINXY` and the weekly/monthly index series carry **maker fees**; the daily ones to check are `KXINX`-family. Needs an options chain source |
| **2** | 29 | Fed decision vs fed-funds futures | Two prices for one event, both public; the comparison is arithmetic, not a model | `KXFED` and `KXFEDDECISION` are **maker-fee** series, so a passive version pays twice. Events are ~8/year, so a verdict takes years |
| **3** | 24 | Model run-time arbitrage (00/06/12/18Z) | The only weather idea NOT closed. #23 and #26 kill the *level*; this is about the **reaction speed** to a discrete event, which is different and unmeasured | Needs minute-resolution Kalshi weather quotes around each run. `market_flow_collector.py` is the right shape; data must be collected forward |
| **4** | 31 | Gas markets from wholesale futures | A 1–2 week lag is a strong, checkable prior, and `KXAAAGASM` has real volume | Maker-fee series. Weekly settlement, so ~50 observations a year |
| **5** | 18 | Gold/silver ratio pairs | Mean reversion over weeks is the best-documented effect in the list | Needs perps, which the repo refuses on an invariant: a leveraged position can be liquidated for more than it cost, breaking the brake's premise |
| **6** | 41 | Pregame sell-the-hype drift | Measurable from Kalshi candles alone, no external data | Sports books are the 1,105-contract queue problem (#40). Likely unfillable even if real |
| **7** | 19 | US 500 perp vs S&P close-above | Same underlying, two products, both on Kalshi | Perps invariant again; and the index series are maker-fee |
| **8** | 32 | Jobless-claims seasonality | A real, documented statistical quirk | ~6 holiday weeks a year. A verdict is a decade away |
| **9** | 38 | Sharp-sportsbook anchoring | The strongest prior in the sports block — sharp lines are genuinely predictive | Needs a sportsbook odds feed this project does not have, and runs into the same queue wall |

## Tier 4 — NOT TESTABLE with reachable data

| # | why not |
| --- | --- |
| **6, 7, 8, 9, 10, 11, 12, 13** (all combos) | Combos price through **RFQ**: nothing trades unless a quoter answers, and there is no public history of combo quotes or fills. There is no tape to backtest. #8's premise (combo ≠ product of legs) is only observable by *requesting* quotes, which is a live experiment, not a backtest. The maker-fee and prop-leg-incentive claims are plausible but unverifiable from the API — and note the prop-leg programme pays **market makers in the legs**, which is the 1,000-contract tier again |
| **15, 17, 20, 21, 22** (perps) | Blocked by the repo's own invariant, not by data: a leveraged position can lose more than its collateral, which breaks the premise every risk control here rests on. #17's delta hedge would need the perp, so it inherits this |
| **33, 34, 35, 36, 37** | One-off or seasonal events with n in the tens, needing external corpora (transcripts, presales trackers, guild results, chart scrapes). Even a correct model cannot be *validated* at that sample size — the repo's own bar is ≥30 losses clustered by what drives the outcome |
| **39** | Needs a play-by-play feed and sub-second latency; infrastructure-bound, and the list concedes it |
| **42** | Needs a projection-model subscription; and props are the thick-queue wall |
| **50** | A meta-rule, not a method. Worth adopting: this repo already tracks each book's P&L separately and kills on measured criteria |

## What this says overall

The list is good — it is a fair map of the space. But **17 of 50 were already
decided here, and the four the operator picked to start with are all among the
negatives.** That is not a criticism of the list; it is the value of having two
repos' worth of measurements, and the reason to read them before building.

The deeper pattern: of everything measured across both repos, **the only things
that make money are residual premium on an almost-decided contract in a book
thin enough to fill but with real flow** — the BTC ladder (#45) and the
commodity range book. Method #45 is on the list and already running. Every
other idea either has no edge, no fill, no tape, or no sample.

The one measured-positive thing **not yet shipped** is not on this list at all:
the commodity **0.10–0.40 band**, +5.95c/contract with a 95% CI of [+2.37,
+9.20] on **60 realised losses** — the best-sampled slice in that study. Its
unknown is the fill rate above 0.25, which is what a paper variant establishes.
