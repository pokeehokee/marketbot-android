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

## Capital and throughput review, 2026-11-18 — two "free" levers died on measurement

Asked to optimise the live books for dollars per day. Read from the exchange's own settlements
(3,180 records) rather than any local table. **Two changes were offered and both failed when
checked. Neither was made.** What the review did establish is where the ceiling actually is.

| book | contracts | P&L | per contract | losses | |
| --- | --- | --- | --- | --- | --- |
| **BTC deep-ITM ladder** | 10,551 | **+$186.72** | **+1.77c** | 90 | 440 ct/day, **+$7.78/day**, $0.00 fees |
| commodity longshot | 28 | -$0.69 | -2.46c | 3 | two days old; 3 losses against 2.31 implied |
| weather (OFF) | 4,620 | +$11.47 | +0.25c | 115 | correctly retired |

**Capital is not the constraint.** Equity $100.62; the ladder's peak ever locked in a single hour
is **$56.20**, 65% of shard 2, and far less most hours. $11.86 sits on shard 0, where no live
strategy trades since weather went off -- Kalshi moves nothing between shards by itself, so that is
idle until someone transfers it. More money would sit idle too.

### The dearest-band advantage is ~5x smaller than this file claimed, and has no room left

The deep-ITM section above says `>=0.94` beats `<0.94` by **"+1.4c/contract, CI [-0.13, +3.00],
P(better) 97%"**. Recomputed on the account's settlements, weighted by contracts:

| | contracts | per contract | losses |
| --- | --- | --- | --- |
| < 0.94 | 4,185 | +1.64c | 55 |
| >= 0.94 | 6,366 | +1.85c | 35 |

**+0.21c pooled** (+0.30c desktop era, +0.71c app era) with overlapping intervals -- the same
attenuation-as-the-sample-grows that this file records for the first Polymarket pull. The per-band
table has moved too: 0.94-0.955 now reads +1.34c [-0.42, +2.98] against the +3.21c recorded above.

And the lever has nowhere to go: **the app era already fills 87% of its contracts at >=0.94**
(2,591 of 2,966) under `slot_order: cheapest`, against 50% in the desktop era. Only 13% of
contracts, about 56 a day, could move. At +0.21c that is **+$0.12/day.** `slot_order` was left on
`cheapest`: changing a live book's behaviour for twelve cents is not worth the churn, and the
measurement does not establish the direction anyway.

### The daily stop is not "outrun by one hour", and must not be widened on this evidence

The first reading of the risk was wrong and is recorded here so it is not repeated. All strikes of
one BTC hour settle off one price move, and three hours in the record lost more than the whole
$15 daily stop in a single burst (-$18.50, -$17.35, -$16.73). That looked like a broken control.
It is not: **no realised-P&L stop can prevent the first batch**, only the next one, and at
`lot: 5` one strike risks ~$4.65, so the stop does halt the day before another hour can land. The
invariant holds.

Walking each day's settlements in time order, the $15 stop **would have tripped once in 24 days**
(2026-09-27) -- and that day recovered **+$11.34** after the trip point, finishing at -$6.39
instead of the -$17.73 the stop would have locked in. So its measured record is one trip, $11.34
forfeited, nothing saved. That mildly favours a wider stop and **is not a reason to widen it**: one
observation, and widening is loosening a risk control, which the 2026-11-14 entry forbids on
anything less than observed benefit. Empirically the book loses in 18% of hours but only 3 of 24
days: bad hours get recovered within the day, which is why the stop has stayed quiet.

### Hour-of-day is still not a selector, and the skip list is still unverified

Per-contract by ET close hour ranges from **+5.98c (hour 20)** to **-3.73c (hour 5)** across 22
traded hours, on ~500 contracts and a handful of losses each. That is the shape this file already
rejected for 04-05 ET, which flipped sign between halves, out of 18 cells examined. **Do not
select on it.** Hours **3 and 10 ET are never traded** (as `skip_hours_et` configures) and 21 and
23 carry 40 contracts each; the setting's own description claims "session opens, where losses ran
3-7x higher", and that claim is supported by nothing in this repo. Unskipping 3 and 10 is worth
about 8% of throughput and is a **measurement to run, not a change to make.**

### No second ladder exists

Six other hourly crypto ladders carry deep-ITM strikes, and only one has flow. 24-hour volume on
the in-band strikes (YES bid 0.90-0.96), measured live:

| KXBTCD | KXETHD | KXSOLD | KXHYPED | KXBNBD | KXXRPD | KXDOGE |
| --- | --- | --- | --- | --- | --- | --- |
| **87,620** | 2,455 | 514 | 186 | 56 | 0 | 0 |

The **median in-band strike outside BTC trades zero**. No flow, no fills -- the same wall that
closed sports and Polymarket-US. BTC is the pool, and the ladder already sits on it.

### What is actually left

`max_open: 8` is reached in **156 of 356 traded hours (44%)**, and the ladder fills ~5.9 strikes an
hour at lot 5. So the book is **size- and slot-limited, not opportunity-limited**, on an account
that funds roughly twice what it deploys. Every remaining lever is therefore some form of "more of
the one thing that is measured", and each needs its own measurement before it ships:

- **lot 5 -> 10** is the only large one (~+$7.78/day if the per-contract fill rate holds). The
  median queue ahead of us on an in-band BTC strike is **3,691 contracts**, so 5 versus 10 is noise
  against it and the binding factor is flow reaching our price, not our size -- but that is
  reasoning, and fills above 5 remain unmeasured. It also doubles the worst hour and would force
  the stop question above.
- **max_open 8 -> 12**, binding 44% of hours.
- **commodity `max_lead_min` 90 -> 120**: the 75-120 minute bucket measured +3.63c [+2.53, +4.67],
  so it stays on measured ground. Worth ~+$0.6/day, but the real argument is reaching the 30-loss
  verdict sooner -- at ~1.15 implied losses a day that is otherwise **~26 days** away.

### The lot step-up, written up 2026-11-18 — and `max_open` is NOT the cheap one

`docs/lot-step-up-experiment.md` holds the protocol for `lot: 5 -> 7 -> 10`: the gate (six
thresholds, 8 days and >= 30 losses per step), the abort conditions, and the collateral and
worst-hour arithmetic. Nothing is shipped; `lot: 5` stands until step 1's gate passes. Three
corrections to the review above came out of writing it.

**The peak hour is $38.35, not $56.20.** That figure was a legacy desktop 10-lot hour. Restricted
to hours whose largest position is 5 contracts (308 of 357), the peak equals the p99 equals
`8 x 5 x $0.96` -- the slot cap binds exactly, so scaling the lot is arithmetic rather than
extrapolation: $53.76 at lot 7 (60% of shard-2 equity), $76.80 at lot 10 (86%), unfundable at 12.

**Lot 10's objection is the worst hour, not funding.** One BTC hour's strikes settle off one price
move, so the worst hour scales linearly: -$17.35 today, -$34.70 at lot 10, which is 39% of
shard-2 equity in one hour. And `$15 / $9.60` is **1.6 single strikes**, below the
two-to-three-typical-losses floor, so lot 10 cannot be configured without raising the stop --
which the 2026-11-14 rule permits only on **observed** benefit. Hence the order: lot 7 earns the
stop raise, or lot 10 never happens.

**`max_open 8 -> 12` was listed as the lever with no fill unknown. The settlements say the hours
it binds in are the worse hours:** capped hours (156) run **+1.61c** on 6,142 contracts against
**+2.00c** on 4,414 in the 201 uncapped hours. Raising the cap adds contracts in exactly the
hours that pay least, at strikes `slot_order` already passed over (the `<0.94` band pays +1.64c
against +1.85c). Capped hours are presumably capped because the pool was rich, which correlates
with volatility and so with losses -- a property of those hours rather than proof about the
marginal strike -- but it is not free, it costs the same collateral per added contract as the lot,
and the account can fund about one step of either. The lot is the larger one (+40% of contracts in
every hour against +30% of positions in 44% of them), so it goes first and `max_open` is frozen
while it runs.

### Kalshi runs no volume-incentive pool, and our series are in none (checked 2026-11-18)

The weatherbot notes record a **Volume Incentive Program** paying up to $0.005/contract on
order-book trades priced 0.03-0.97 -- our band -- and the capital review put it at ~$2.20/day of
free yield for an operator to confirm on the website. The public, unauthenticated endpoints settle
it: `GET /incentive_programs` (1,000 records, 100 distinct series) and `GET /incentive_schedules`
(636) are **`incentive_type: liquidity` on every single row**, with `target_size_fp: "1000.00"`
throughout. There is no volume type in either listing, and **no commodity or crypto series we
trade appears in any pool** -- the categories are Elections 174, Financials 146, Economics 90,
Entertainment 84, Politics 55, Sports 38, Science and Technology 35, Climate and Weather 8,
Companies 6, and the crypto-adjacent hits are `KXHEGSETHIMPEACH` and `KXIAETHANOL`. The one
programme that exists needs 1,000 contracts resting per side, which is the retail-excluded wall
weatherbot already recorded. **So there is nothing to collect and the ~$2.20/day should not be
counted.** Re-check with those two endpoints rather than from the help pages; a pool appearing for
`KXBTCD` would be free yield on a book that already rests all day.

## Buying the >=20x crypto tail, 2026-11-19 — a loss as a taker, fair as a maker

Operator question: put $1 on a >20x outcome that still has a chance, and find a
way to tell when the market is about to turn so the bet goes on before it does.

The question splits, and only one half needed answering first: **is the cheap
tail underpriced at all?** If it is, no timing is needed. If it is not, no
pattern rescues it, because you would be paying spread and fee on a fair bet.
Timing is also the single most closed avenue in this repo — Kronos, a 102M-
parameter K-line model fine-tuned on BTC 5-minute bars, could not beat a
driftless random walk at a 30-minute lead on exactly `KXBTCD`, where the
market's reliability measured 0.0004.

`research/crypto/` prices every cheap-tail strike (buy price <= 0.05, both
tails) of each settled `KXBTCD` hour at a 30-minute lead with zero lookahead,
unauthenticated and read-only. **3,571 quotes, 257 hours, 33 days, 37 wins
against 62.2 implied by the prices paid — ratio 0.59.** CIs bootstrapped
clustered by DAY:

| | ratio | EV/contract | 95% CI |
| --- | --- | --- | --- |
| **buy at the ask (taker)** | 0.59 | **-0.83c** | **[-1.40, -0.10]** — excludes zero |
| rest a bid (no fee) | 0.59 | -0.71c | [-1.27, +0.00] — touches zero |
| ask 0.010-0.015 | 0.47 | -0.60c | [-1.02, -0.04] |
| ask 0.025-0.035 | 1.08 | +0.01c | [-1.71, +2.05] |
| ask 0.045-0.055 | 0.43 | -3.21c | [-5.35, -0.59] |

**Taking the tail is a measured loss; resting a bid for it is a coin flip.** The
fee is the difference: a $1 stake at 0.02 buys 50 contracts and pays
`ceil(0.07 x 50 x 0.02 x 0.98)` = $0.07, 7% of stake, because Kalshi rounds the
fee up per ORDER.

**And the direction is corroborated by live money on the other side.** Buying
YES at 0.94 and selling NO at 0.06 are the same payoff, so the deep-ITM ladder
IS the short-the-cheap-tail trade on these very markets, earning **+1.77c/
contract over 10,551 contracts**. The buyer's -0.83c and the ladder's +1.77c are
the same phenomenon from two sides. **This idea is the inverse of the account's
best strategy.**

Why it fails is geometric, not statistical: over a 30-minute lead BTC's median
move is **$117** (p90 $329, max $503 across 67 hours) against a median
**$738** needed to win. The multiplier is 20-100x *because* the move required is
six times the typical one.

Three things NOT established, each of which looked established at some point:

- **The wins cluster and the split is unstable.** 20 of 37 wins fall on three
  days, 21 of 33 days have zero, and one day paid **5.4x** its implied rate.
  Earlier half ratio 0.84, later half 0.37. The side asymmetry (strike above
  spot 0.36, below 0.82) is almost certainly the window's direction — BTC fell
  from ~$72.8k to ~$63.2k across it — not a tradeable fact.
- **Volatility conditioning does not harvest the clustering.** Trailing 60-min
  realised vol at the quote, quartiled: ratios 0.50 / 0.14 / 0.78 / 0.20, non-
  monotonic, all EV negative. The reason is in the implied column, which rises
  **6.0 -> 7.1 -> 7.7 -> 9.9** across the quartiles: the market already widens
  the ask on volatile days by about the right amount.
- **The long-horizon tail is liquid and cannot be measured.** `KXBTCY` (22
  cheap markets, OI median **483,952**), `KXBTC2026200` (OI **5,312,824**),
  `KXBTCMAXMON`, `KXBTCMAXY` carry months of runway and enormous open interest —
  the shape the >=20x question actually wants. But a 14-month one-touch yields
  one observation per horizon and every `BTC-above-X-by-2027` market settles off
  the SAME price path, so n ~ 1 per year. The >= 30-loss bar cannot be reached
  when the cluster is the sample. Buying them is a view, not a measured edge.
  The *daily* crypto series that would be measurable (`BTCD`, `BTC`, `ETHD`,
  `KXBTCMAXD`) return **zero markets and zero events of any status** — listed
  shells, as weatherbot records for international weather series.

> **Three claims were made during this work and withdrawn, all the same root
> cause: a number reported before the field or the clustering behind it was
> checked.** (1) Two Poisson-binomial p-values (3.9e-6, 2.6e-4) assumed quotes
> were independent draws when 20 of 37 wins sit on three days; hour clustering
> gave a ratio CI of [0.17, 0.98] against day clustering's [0.27, 1.02].
> (2) "The cheap band has median 24h volume of zero, probably fatal" came from
> a field that is **not populated**: of 200 settled `KXBTCD` markets, zero
> report `volume > 0`, including strikes this account demonstrably traded, and
> `open_interest` and `volume_24h` on the market object are dead too. From the
> candlestick the band carries a median **3,754** contracts of OI. (3) The
> long-horizon series were called dead on that same invalid read. **Never
> report a liquidity figure without first checking the field populates on a
> case known to have traded** — a zero meaning "not reported" and a zero
> meaning "no trades" are indistinguishable downstream. The mid-price control
> was also mis-specified at first: it reused the cheap band's $100-$1500
> distance-from-spot window, so it could not see 0.30-0.70 strikes at all
> (4 hours, 2 quotes). A control that cannot observe its own target is not a
> control; it was replaced by checking every quote's exchange result against
> realised BTC spot, which agreed **830 of 831** times.

Tests: `research/crypto/test_crypto_tail.py` (17, stdlib unittest) freeze the
zero-lookahead candle rule, the empty-book sentinel, `no_ask = 1 - yes_bid`, the
per-order fee, day clustering, the 429 backoff and the read-only guard.

## What the two live books are SET to, 2026-11-15 — three stale benchmarks

Asked to check everything was correctly set, and the settings had drifted from the evidence in
three places. All three are the same shape: **a number measured on history outlived the live
record that replaced it.** The repo already records this failure mode for stale comments on
live trading paths; an `Expectation` is worse than a comment, because
`Scorecard` grades real fills against it.

- **The BTC ladder was still described as having no edge.** `KalshiBtcDeepItm`'s evidence read
  `EvidenceLevel.NO_EDGE`, "the reported +2c did not reproduce", with
  `Expectation(-0.0122)` from the 2026-10-30 replay — and the go-live checklist asked the
  operator to "accept: the historical replay found no edge". Meanwhile the account's own
  settlement payloads carry **1,805 positions, 9,028 contracts, +$162.65, +1.80c/contract with
  81 losing positions over 21 days, 95% CI [+1.09c, +2.43c]** clustered by settle-date. Split at
  2026-10-01 it holds: **+1.75c then +1.94c**. The 2026-11-12 correction further up this file
  recovered the original run and found the parameters identical; what it did not do is update
  the `Evidence` object, so the app went on telling the operator its best strategy was dead.
  Now `LIVE_PROVEN` at `Expectation(0.0180, 0.0109, 0.0243)`, sourced from the payloads.
  The replay's two other claims die with it: live, **NO is +1.88c and YES +1.73c** (it had
  called the NO side the loser), and there is no "-1.6c to -4.6c since 2026-08-30".
  Why it was wrong: the pessimistic model only filled orders that traded through the bid, but
  resting orders fill as makers and pay nothing — 130 of 130 of the app's own fills settled at
  `fee_cost = 0`.
- **The commodity book's expectation was the blended figure.** `Expectation(0.0388)` is the
  number the section below says must NOT be quoted as the live expectation, because 1,277 of
  those 2,733 markets had gone unquoted for hours and carry 3 losses against 50.3 implied — a
  book nobody is pricing, where a resting order never fills. Fills come from contested books.
  Now `Expectation(0.0422, 0.0348, 0.0493)` on the **contested subset** (1,098 markets, 16 real
  losses, ratio 0.26), which holds across halves too (+4.70c then +4.05c).
- **The commodity lot was 5, not the 1 its own live-start plan specifies.** `lot_cap` is a
  shared `PaperRangeLongshot` param and its default was hardcoded; it is now
  `open val defaultLotCap`, overridden to **1** for this book only. At lot 1 one day's whole
  ~56-market pool costs ~$53 of collateral, so the pool is funded in full and the losses that
  decide the verdict arrive as fast as the market makes them; at lot 5 the same pool wants ~$266
  and would be collateral-bound at today's balance, funding an arbitrary subset by no measured
  rule. Worst single market is then ~$0.98 against the $10 brake.

**Hours do not select, and the attempt to make them is recorded here so it is not retried.**
04–05 ET looks like the ladder's worst hour in-sample (−2.27c, 15 losses) and reads **+5.28c
with zero losses** in the second half — a complete sign flip, so filtering it is overfitting,
and the filter indeed improves the first half while hurting the second. 12–13 ET looks
exceptional (+5.40c) and does hold (+6.08c then +3.97c) but rests on **one loss in each half**:
suggestive, not actionable, and 18 cells were examined. The 0.92–0.94 band is the weakest in
both halves and the only one that goes negative, on too few losses to drop. **Nothing about the
ladder's configuration changed; only its description did.**

> **A first-occurrence replace put the new lot on the wrong strategy.**
> `override val defaultMinLeadMin = 40` appears in BOTH `PolymarketTemperatureLongshot` and
> `KalshiCommodityLongshot`, so anchoring an edit on it and replacing once moved the Polymarket
> weather book to lot 1 and left the commodity book at 5 — exactly inverted, and silent.
> `ConfiguredDefaultsTest` caught it on its first run, which is the only reason it is a
> footnote. An edit anchored on a line that is not unique to its class is not an edit.

Tests: `core/src/test/kotlin/com/marketbot/core/ConfiguredDefaultsTest.kt` freezes all three
settings and that lowering one book's lot does not move another's. `CommodityLiveTest` and
`SearchCandidatesTest` now pin `lot_cap` explicitly, because they assert on fills sized in the
fake tape and must not change meaning when a shipped default does.

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

### The shipped edge is blended with dead markets, 2026-11-14

Asked whether quoting closer to close pays more (the edge runs the right way: ratio 0.72 at 720m,
0.21 at 60m), `research/commodity/commodity_leads.py` re-priced the 2,863 markets whose cheap side
was reachable at 60m, paired, at 30m / 15m / 5m. **The question is unanswerable from candles, and
the attempt corrected the headline instead.**

**Kalshi's 1-minute candle history for these series is sparse, so every lead is really "the last
quote at or before the mark".** The candle actually used, in minutes before close:

| nominal lead | median age | p90 | max |
| --- | --- | --- | --- |
| 60m | **90 min** | 420 | 540 |
| 30m | 60 min | 390 | 510 |
| 15m / 5m | 60 min | 390 | 480 |

15m and 5m resolve to the **identical candle on 100% of markets**, which is what exposed it. Zero
lookahead still holds (the candle ends at or before the mark), so nothing is biased forward -- but
a shorter nominal lead buys no fresher price, and the lead curve below 60m cannot be measured this
way at all.

**What it does to +3.88c.** Split the shipped 60m population by how old the quote was:

| quote age at the 60m mark | n | losses / implied | ratio | EV/contract | 95% CI |
| --- | --- | --- | --- | --- | --- |
| **60-75 min (contested)** | **1,098** | **16 / 62.3** | **0.26** | **+4.22c** | **[+3.50, +4.94]** |
| 75-120 min | 624 | 13 / 35.7 | 0.36 | +3.63c | [+2.53, +4.67] |
| 2-6 h | 630 | **0** / 24.5 | 0.00 | +3.88c | [+3.50, +4.25] |
| > 6 h | 381 | **0** / 12.6 | 0.00 | +3.30c | [+3.06, +3.57] |
| all (the headline) | 2,733 | 29 / 135.0 | 0.21 | +3.88c | [+3.47, +4.22] |

**1,011 of 2,733 markets -- 37% -- carry ZERO losses**, and their tight intervals are the
zero-loss fake tightness this file already warns about for the KXLOW six-date sample, the first
Polymarket pull and the BTC paper book. The mechanism: a book stops updating because the outcome
has become certain, so the last quote **overstates the tail it is still nominally pricing**. The
"implied" losses on those rows are computed from a price that is simply wrong, which is why the
ratio reads 0.00 against 37 expected losses. That is not an edge; it is a market nobody is
quoting, and a resting order there is one that does not fill.

Two consequences, and the first is good news:

- **The edge is real where it is tradeable, and bigger.** On contested markets it is +4.22c at
  ratio 0.26 with **16 real losses**, against +3.88c blended. The part you can actually transact
  in is the part that pays.
- **+3.88c must not be quoted as the expected live figure.** It is a population number containing
  37% unfillable rows. This is the same fact the volume split already recorded ("all 29 losses
  fall in the markets with 1,000+ contracts of volume; the quieter buckets carry zero losses
  because they were never contested"), seen through a sharper lens, and it explains part of the
  paper book's +2.14c realised against +3.88c population.

The lead question stays open, and candles cannot close it. Answering it needs **live book
snapshots** of open commodity markets at a few minutes' resolution -- weatherbot's
`market_flow_collector.py` is the shape -- after which the curve below 60m becomes measurable on
books that really existed. Nothing about the shipped 40-90 min window changes meanwhile: the
contested bucket IS the 60-75 minute one.

### Order books are recorded forward now, 2026-11-16

The lead question above is open because **candles cannot answer it** and Kalshi serves no historical
order books. `research/commodity/book_collector.py` writes them down as they happen: top of book
for every market within 120 minutes of its close, every 60 seconds, plus full ladders for the cheap
band. Read-only (`assert_read_only()` whitelists the market listing and the two orderbook reads and
raises on anything else), unauthenticated, its own database, places nothing.

Shape of the problem, measured on 2026-11-16: the 12 series carry **537 open markets**, but closes
cluster into about **two times a day** — 19:30Z with 30 markets and 22:00Z with 270. So the
collector idles ~20 hours a day and works two 2-hour windows. Top of book for every market comes
from the 12 per-series listings — 12 calls a cycle however many markets exist — and that alone is
what pricing the cheap side needs (`yes_ask_dollars`, `yes_bid_dollars` and both sizes). Ladders
cost a call each, so they go through the batch endpoint, cheap band only, capped. ~140 Read tokens
and ~2 s a cycle, ~100 MB a month. It needs a machine that stays up (`DEPLOY.md`: systemd user
service with linger, after what the pm2 daemon did to weatherbot on 2026-09-09); **a day missed is
a day that cannot be recovered.**

Three payload facts it depends on, all verified live against the exchange rather than assumed:

- **The orderbook carries only bids**, so **the YES ask is `1 − best NO bid`** and the queue at that
  ask is that NO level's size. Confirmed against the market object twice, independently:
  KXGOLDD-26NOV1617-T4087 ladder best NO bid 0.99/537 → 0.01/537 against `yes_ask_dollars 0.0100` /
  `yes_ask_size_fp 537.00`; KXWTI-26NOV1614-T88.49 → 0.41/204 against 0.41/204.
- **An empty side is an EMPTY LIST, not a missing key** (`yes_dollars: []`), which the docs do not
  spell out, and on the market object it is `yes_bid_dollars '0.0000'` / `yes_ask_dollars '1.0000'`
  — the same shape a closed market quotes. All of it stores as NULL. This is the sentinel that once
  made a stop-loss replay report a 97% trigger rate at −72c/contract.
- **`tickers=A,B` on `/markets/orderbooks` is a trap, and it does not fail.** It returns **one**
  entry whose `ticker` is the literal string `"A,B"` with both sides empty — which reads as a market
  with nothing resting. Called that way the collector would have recorded a fabricated empty book
  for every market it tracked, and the dataset would have looked complete. The parameter must be
  repeated (`tickers=A&tickers=B`), and an entry naming a ticker that was not asked for is
  discarded rather than stored. Found by checking the batch result against the per-market endpoint,
  which is the only reason it was found at all.

> **It also records when each row was really read.** The series listing and the ladder are separate
> HTTP calls, so they land a second or more apart and the book moves in between: observed on
> KXWTI-26NOV1614-T88.49, where the listing reported 167 contracts at the best YES bid and the
> ladder read 15 a moment later. Both are correct for their own instant. `book_top.fetched_ts` and
> `book_depth.fetched_ts` carry the real arrival time per row, with `snapshot_ts` kept only as the
> cycle id for joining, so the skew (1.7 s measured within a cycle) is measurable instead of
> assumed. This is the "13:00 prices that actually spanned 04:45 to 13:45" mistake, pre-empted.

What it is for, in order: the **lead curve below 60 minutes** on books that really existed; and how
the **queue at the ask** evolves through the window, which is the input to a real fill model — the
current one rests on 707 fill-checked quotes and a queue-depth relationship the data says is *not*
established. Give it a week covering both close times before scoring anything, and cluster by
close-date.

Tests: `research/commodity/test_book_collector.py` (22 tests, stdlib `unittest`, run in CI before
the Kotlin suites) freezes the sentinel, the ask derivation, best-level selection that does not rely
on server ordering, the comma-batch trap, the read-only guard, and idempotent inserts.

### The live order path, 2026-11-14

`PaperRangeLongshot` had no live path by construction -- "a candidate from the pool search is
forward-tested in paper before anything else, and LIVE is not offered". That invariant was right
while the only evidence was the paper book. It is not what decided this: **the paper book never
had enough losses to decide anything.** 101 settled fills over 7 close-dates, every band split
resting on 0-3 losses, against a settled-history measurement carrying 29 losses over 49
close-dates. Count the LOSSES, not the fills -- the same rule the deep-ITM band correction turned on.

So the gate moved rather than opening: the base class is live-capable only where a subclass
implements every venue hook AND sets `liveCapable`, a **variant is never live** whatever its base
can do, and `kalshi_commodity_longshot` is the only one that opts in. The Polymarket temperature
book is untouched and still paper-only.

The path is `KalshiBtcLadder`'s, not a new one -- that order code has 1,500+ live positions behind
it. Four things it carries that a fresh path would have had to rediscover:

- **The shard.** Commodities route to the same non-default exchange shard as the crypto ladder, and
  Kalshi does not fund a shard automatically. The shard is read off each market's `exchange_index`
  rather than assumed, orders and fill reads carry it, and preflight fails if that shard has no cash.
- **Live size comes from the exchange or is not taken.** `liveEquity` reads the shard's own cash;
  a balance that cannot be read **aborts the pass** (`balance unreadable`) instead of falling back
  to a local number. The budget subtracts only collateral behind orders still RESTING, because the
  exchange has already deducted a filled position's from cash -- subtracting both under-deploys by
  the size of the book, which is the mirror of weatherbot's 2026-09-08 double-count.
- **A withdrawal is a cancel at the exchange, and never a result.** The order is cancelled at
  Kalshi first, the fill state is re-read after (a cancel races a fill), and the row is handed to
  the settlement pass with no result written. This is the August TTL sweep that wrote 28 quotes off
  as expired without checking fills, 18 of which were real filled positions.
- **Live P&L is the exchange's figure or nothing.** The three-tier chain (settlements, then
  `positions?settlement_status=settled`, then `/historical/positions`), and a filled row no store
  has a figure for is left **ungraded and reported**, never derived from the local size and price.
  Only the venue's own fill record reading zero grades a row unfilled.

**How to start it, and why small.** The verdict depends on the number of LOSSES, not the lot, so
take the whole pool at the smallest size: lot 1, the shipped 0.02-0.25 band, ~56 markets a day,
~$50/day of collateral, the $10 brake unchanged (one market can lose ~$0.95, so the brake stops the
day well before a second). Then watch the one thing the measurement cannot cover: **the live loss
ratio against the population's 0.21.** A paper fill never sat in the real book -- it is modelled
from the public tape behind our queue position, so it cannot be lifted by flow reacting to it. On
the weather book that selection took a 0.73 population ratio to **0.96 on fills**, which is most of
an edge. Size up on the live number, not on this file.

Tests: `core/src/test/kotlin/com/marketbot/core/CommodityLiveTest.kt` freezes the post-only order on the market's own shard, the
aborted pass on an unreadable balance, the cancel-before-withdraw, grading from the payload rather
than local arithmetic, the ungraded-not-guessed branch, the never-filled reconciliation, and that
live and paper books never mix.

## Unfilled bids sat unsettled for ever, 2026-11-16

Reported from the app: the deep-ITM Orders list showed a lot of orders as not settled, and the
operator's reading was right -- they never filled. The app agreed about the fill; it just never
stamped them finished.

**Only `grade()` ever writes `resolvedAt`**, and it had two gates that stranded rows permanently:

- `runOnce` returns at `if (ctx.mode == Mode.OFF)`, so **switching the strategy off froze every
  open row exactly as it stood**, with nothing to come back and settle it;
- it read `ledger.open(id, bookMode(ctx))` and branched on `ctx.live`, i.e. **only the book of the
  mode the pass happened to be in**, so switching between paper and live left the other book's
  open rows with nobody to visit them.

Nothing else closes a row. The engine's 5-minute `reconcileOrders()` does notice when Kalshi no
longer has the order and stamps `cancelledAt` -- which is why these read "Withdrawn" rather than
"Resting" -- but it never resolves, and it skips OFF strategies too. And
`KillCriteria.ungradedPositions` only alarms on rows with `filledContracts > 0`, by design, so an
unfilled backlog raised nothing. Three mechanisms each did their own job correctly and the row
still never finished.

Fixed three ways:

- `grade()` now reads **both books** and chooses the grading path from **`q.mode`**, not from
  `ctx.live`: a paper row is graded by the paper model and a live row from the exchange's figure,
  whichever mode the pass is in. Live rows are skipped while no credentials exist to read them
  with, rather than failing into the ungraded path.
- `onStop()` grades after cancelling, so switching off settles what has already closed.
- The Orders row no longer calls an order on a **closed** market "Resting ... 3h 12m". That was a
  false claim about live state; it now reads "Not filled, awaiting settlement".

> **The first hypothesis was wrong, and testing it is what saved the fix.** The obvious suspect was
> the repo's own rule that Kalshi partitions markets into live and historical tiers, since
> `result()` reads a single `GET /markets/{ticker}`. Probed against real tickers from this
> account's settlements at 1 day, 3 weeks and 8 weeks old, **every one still returns
> `status=finalized` with a result.** The tier is not the problem and no three-tier chain was
> needed here. Had that gone in on reasoning alone it would have added a fallback nothing uses and
> left the real cause untouched.

What this does NOT fix: a market that closes while the strategy is OFF still waits for the next
pass in either mode, because no pass runs at all. Turning the strategy on settles it.

Tests: five in `DeepItmEngineTest`. Two of them fail against the old code (the mode switch and the
stop); the other three are guards that must pass either way -- a closed market settles, an open one
is not settled early, and a live row is never graded by the paper model.

## The board: words under every number (2026-11-14)

Home opens on a board, and each strategy on a Bottom line, built from three layers of words: one
headline sentence; under every number one line saying what it means (the **Explain** switch,
kept in the `ui` preferences, turns these back into terse figures); and a sheet behind every
tile. The look is the dark control room; the words came from the "your bots" exploration, after
the operator said the app "just looks like a bunch of numbers".

**Every sentence is written in `core/strategy/Narration.kt`, from the ledger, and nowhere else.**
`NarrationTest` freezes the rules it keeps:

- **No sentence outruns its data.** A strategy is "beating odds" only when losses are at most 0.90
  of what the prices implied, and the word stays neutral, with "too few losses to be sure", until
  **30 losses** -- the stage gates' bar and the deep-ITM lesson. Green is earned, not assumed.
- **Bad news is never hidden behind "too early".** A comparison opens once EITHER the expected or
  the actual losses reach 3. Gating on the expected count alone read 8 losses against 2.3
  expected as "too early", which is the book going wrong described as nothing.
- **Real and practice money are never summed.** With no live book the headline says "No real
  money is trading" and every tile relabels itself "Practice".
- **There is no account-wide daily limit.** Each strategy has its own (`daily_loss_limit`, or
  `daily_stop` on deep-ITM, same meaning); the tile shows the live strategy closest to its own,
  and names it.

Home kept everything it had (charts, quick actions, exposure, periods, capital, kill switch,
accounts) below the board; only the old strategies list went, replaced by the board's table. The
Strategies tab still has the full list.

## The notification carries the money, 2026-11-16

The persistent notification led with the engine's status ("1 live, 3 paper"). It now leads with
the account: **`$39.40 · up $1.20 in 24h`**, with the status and next pass moved into the body so
the live/paper split and the kill state are never lost from the shade. When the kill switch is on
the title still says so first.

**It makes no API call.** The figures come from the `equity` store, which the engine fills from
`/portfolio/balance` every 15 minutes whatever the strategies' modes. `/portfolio/balance` has its
own 1/s bucket and, as the API skill records, changes only on fills, settlements and transfers --
polling it to paint a notification would be waste, and on a phone that redraws the shade often it
would be a lot of waste.

Every figure and every word is `Narration.balance()` / `Narration.balanceLine()`, keeping the
board's rule that sentences are written in `Narration.kt` and nowhere else. Four things it refuses
to do, each with a test:

- **A withdrawal is not a loss.** The change subtracts every confirmed `CashFlow` inside the
  window. Without that, the $200 withdrawal of 2026-11-14 reads as the worst day the account ever
  had -- `a withdrawal is not a loss` asserts the naive figure (-$198.50) and the right one
  (+$1.50) side by side. Deposits are symmetric.
- **A missing reading is not $0.00.** No snapshot means the line reads "Balance not read yet" and
  the notification falls back to the old status title; one snapshot shows the balance and says
  "24h change needs a day of history" rather than inventing a change.
- **The span is the real span.** With only six hours of history it says "up $0.40 in 6h", not
  "in 24h". The anchor is the newest reading at or before the window opens, so two days of history
  still reports 24 hours. This is the same discipline as `fetched_ts` on the book collector and the
  "13:00 prices that spanned nine hours" near-miss in weatherbot.
- **Practice money is never added in.** `Mode.PAPER` snapshots are filtered out, so the $400
  paper wallet cannot reach a line about the real account.

Equity per venue is the exchange's own mark (`total` = cash + portfolio value), falling back to
cash only where a venue reports no mark -- matching `CashFlows.candidates`. Venues are summed, and
each venue's flows are netted against that venue only. A reading older than
`BALANCE_STALE_AFTER` (45 min, three snapshot intervals) is labelled with its time rather than
presented as current.

Tests: the `NarrationTest` balance block (12 cases).

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

**The CSV exports are gzipped (2026-11-20).** The daily sync had been failing
with a timeout, and the cause was size, not the network: the four exports had
grown to **23 MB** between them (orders 8.49, trades 8.10, books 6.23, perps
1.04), and the contents API takes base64, so each sync pushed **31 MB in four
PUTs under a 60-second per-call budget** — the largest single file 11.3 MB.
Gzipped the same payload is **5.2 MB of base64** (orders 7.5x, books 10.6x,
trades 3.9x, perps 4.6x). `exports/*.csv.gz` via `writeBytes`; the JSON files
stay plain text because a person reads and edits `proposals/pending.json` in the
GitHub UI. `GitHubMailbox.RETIRED` holds the uncompressed paths and the sync
deletes them once, so the repository does not carry both copies — a failure
there is swallowed, because a stale file is untidy rather than harmful.

It also bounds the repository. Every sync rewrites each file **whole**, so each
one is a new blob for ever: at 120 commits `.git` was already **59 MB**, gaining
~24 MB per sync, which daily would have passed a gigabyte within six weeks.
Gzipped that is ~4 MB a sync. `SyncPayload.gzip` is deterministic — Java's
`GZIPOutputStream` leaves the header's MTIME at zero — so an unchanged export
produces identical bytes and a quiet day writes no commit at all; a test crosses
a whole second to pin that.

What was NOT the problem, checked before the fix: nothing reads an export back
before writing it. Every one is rebuilt from the app's own stores
(`perpFunding?.invoke()`), so the 400-day funding series lives in the device
database and a read returning empty could not truncate it. Worth knowing because
`GET /contents` returns `content: ""` with `encoding: "none"` for any file over
1 MB — all four exports were past that — so `read()` had been silently yielding
empty text for them. Only the sha is used, so nothing broke; anything that
later reads an export back must not go through `read()`.

Still open: the contents API needs a GET for each file's sha before each PUT, so
a sync is two calls per file and six commits. The Git Data API (blobs, one tree,
one commit, update the ref) would make it one commit and drop the sha reads. Not
done; gzip is what the timeout needed.

## Commands

```bash
./gradlew :core:test                 # fast, pure JVM
./gradlew :app:testDebugUnitTest     # Robolectric; writes screenshots to app/build/screens
./gradlew :app:assembleDebug
```

In this sandbox Maven Central rate-limits, so `~/.gradle/init.d/mirror.gradle` and the `robolectricRepo` property point at
Google's Maven Central mirror. CI needs neither.

## Four corrections, 2026-11-20 — three to this file's own numbers, one to a control that did not exist

Asked to act on a list of recommendations and to close out the items behind
them. Everything here is measured from `/portfolio/settlements` (3,271 records)
or proven from the code; nothing rests on a local table.

### The commodity book's live record, from the exchange

This file carried two disagreeing figures for it (+$0.84 on 51 settled from
the sync snapshot, -$0.91 on 15 from the raw order journal) and **neither was
right**. The exchange's own settlements:

| | |
| --- | --- |
| settled | **57** (every settlement is live; the exchange settles nothing else) |
| contracts | 57 — all lot 1, as `defaultLotCap = 1` configures |
| P&L | **-$0.66**, 51 wins / 6 losses |
| fees | **$0.00** — 57 of 57 maker fills paid nothing, as the `quadratic` fee type implies |
| per contract | **-1.16c** |
| losses vs implied | **6 vs 5.3, ratio 1.12** |

The disagreement is now explained: **the snapshot's "51 settled" is the WIN
count.** Any figure read off it was a different quantity wearing the same word.

`Expectation(0.0422)` — the contested-subset backtest, +4.22c at ratio 0.26 —
is **not changed by this**, and it must not be. Six losses is the same
non-sample this file rejects for the KXLOW six dates, the first Polymarket
pull and the BTC paper book; the bar is 30 losses and ratio <= 0.90, and at
~1.5 losses a day that is about two more weeks. What IS now true and was not
recorded: the live book is running at **1.12, not 0.26**, i.e. slightly worse
than its own prices rather than four times better. That is the number to watch,
and it is the one the stage gate will read.

### `max_open` is a total, not per-side — proven, not inferred

The 2026-11-18 lot-step-up arithmetic assumed it. `DeepItmRule.select` settles
it: candidates are de-duplicated with `seen.add(c.ticker)` ("one side per
ticker") and the free slots are `maxOpen - openTickers.size`, counting distinct
TICKERS. So `max_open: 8` is 8 distinct strikes in total, one side each, and
the structural per-hour cap stands exactly as written — `8 x lot x ~$0.96`, i.e.
**$38.40 at lot 5, $53.76 at 7, $76.80 at 10**. Had it been per-side every one
of those numbers was out by 2x, in a risk document. The settlements agree the
cap binds: **158 of 389 hours hold exactly 8 positions.**

### The one-sided sweep is now 67 for 67 — and a full sweep has never happened

The worst-hour argument rested on a single observed hour. Across every losing
BTC hour on record: **67 of 67 had all their losers on ONE side of spot. Zero
mixed.** One price move decides one side, so the hours behave exactly as the
structural argument says, and that part of the claim is no longer one
observation.

The claim it does NOT support is the magnitude. The worst hours lose **2 to 4
of 7-8 positions, never all 8**: realised worst hours are -$18.50, -$17.35,
-$16.73, with 3, 4 and 4 losers. The next section says why that is structural
rather than lucky, and what it does to the worst-hour figures.

### The sweep geometry, measured properly — and the book straddles spot

"1 hour in 9" used **half the median strike span ($450)** as a stand-in for how
far spot must travel to sweep one side. My first attempt to fix that measured
the **span** between held strikes (median $300) and concluded from it that
sweeps were MORE reachable than stated. That was the same mistake again: the
span between our strikes is not the distance from spot to the furthest one.

Measured against BTC spot an hour before each close (Coinbase hourly closes,
389 hours):

| | |
| --- | --- |
| entry spot -> furthest same-side strike | **median $393** (p10 $159, p90 $839) |
| realised 1-hour move | median $150, p90 $488, max $1,379 |
| hours where the adverse move reached the furthest strike | **28 of 389 = 1 in 13.9** |

So the $450 proxy was approximately right ($393), and **1-in-9 overstated the
sweep frequency**: the measured rate is 1 in 13.9. Both of this file's
geometry guesses erred toward MORE risk than exists, not less.

**And a full 8-strike sweep is structurally impossible as the book is built.**
91% of hours (353 of 389) hold **both** sides at once — a YES strike below spot
and a NO strike above spot are both deep in the money — and of the 158 hours
that reach the `max_open` cap, the splits are 4/4 (67), 5/3 (82) and 6/2 (9).
**Never 8/0, in 389 hours.** One price move therefore takes one side while the
other side wins, which is exactly the observed pattern of losing 2-4 positions
of 7-8 and why the worst realised hours are only -$18.50, -$17.35 and -$16.73.

The consequence for the step-up arithmetic: `max_open` caps **collateral** at
`8 x lot x ~$0.96` as written, but it does **not** cap the worst-hour **loss**
there. The loss ceiling is `(largest one-side holding) x ~$0.93`, and the
largest one-side holding ever observed is 6 markets:

| lot | collateral cap (8 strikes) | worst-hour loss, 6 of 8 on one side | typical, 5 of 8 |
| --- | --- | --- | --- |
| 5 | $38.40 | ~$23 (observed max one side: 25 ct, $23.25) | ~$19 |
| 7 | $53.76 | ~$33 | ~$27 |
| 10 | $76.80 | **~$56** | ~$47 |

The **$76.80 worst-hour figure previously used for lot 10 cannot occur**; the
real exposure is about $56. This does not change the step-up's ordering, because
the binding objection to lot 10 was never the worst hour: `$15 / $9.60` is 1.6
single strikes, below the two-to-three-typical-losses floor a brake needs, so
lot 10 still cannot be configured until lot 7 has earned a stop raise on
observed benefit. It does mean the risk of the later steps was being
**overstated**, and anyone reading the old table was reading a case the book
cannot reach.

### Two controls that nothing called

`reconcileKalshi` was reachable only from the retired weather strategy, and
`KillCriteria.staleWrites` was reachable from **nothing at all** — both shipped
in the 2026-11-19 hardening work whose own documentation says an orphaned
control is worse than none, because the docs then read as though the ground is
covered. They are now wired to both live-capable books through
`LiveGuards.noOtherTrader` and `LiveGuards.balanceWriterFresh`, live mode only,
and neither can return KILL, so neither can halt a working book. Both are
INSUFFICIENT when they cannot read — a missing credential or a failed fetch must
never look like a clean account.

The guard worth naming: `Engine.recordLiveBalances` wraps its fetch in
`runCatching { }.getOrNull()?.let`, so a venue that stops answering **writes
nothing and raises nothing**. The notification keeps showing the last balance it
read and `Narration` computes a 24h change from frozen history, while every
other control reads the ledger, which a balance outage does not touch. That is
the 3 AM silent failure, and it now has a check against a 15-minute cadence.

`LiveGuardWiringTest` asserts REACHABILITY, not correctness — the functions were
already correct, which is exactly why no test caught this. Deleting a call site
fails three tests with "reconcile guard not reachable".

### The book collector had no host, so nothing was ever recorded

`research/commodity/DEPLOY.md` describes a systemd user service; no such service
exists and **`commodity_book.db` does not exist anywhere.** The sub-60-minute
lead curve was "waiting on a week of data" that was never being collected.
The repo is public, so Actions minutes are free: `.github/workflows/book-collector.yml`
runs one job from 17:25Z for ~295 cycles, which covers **both** daily close
clusters (19:30Z, ~30 markets; 22:00Z, ~270) inside the 6-hour job limit, and
uploads gzipped CSV (`--export`) because the runner's database dies with the
job. A missed day is still unrecoverable; re-run a failed job only while a
window is open.

### The whole account, from the exchange, 2026-11-20

`research/screen/account_record.py` prints this from `/portfolio/settlements`
and nothing else. It exists because this repo has now twice reported a book's
record from a local table and been wrong.

| book | settled | contracts | P&L | per ct | lost ct | implied | ratio | fees |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| BTC deep-ITM ladder | 2,282 | 11,903 | **+$193.21** | +1.62c | 518 | 710.8 | **0.73** | $0.00 |
| commodity longshot | 57 | 57 | -$0.66 | -1.16c | 6 | 5.3 | **1.12** | $0.00 |
| weather (retired) | 961 | 4,212 | +$19.88 | +0.47c | 444 | 463.9 | **0.96** | $0.00 |
| manual 15-minute crypto | 10 | 811 | **+$0.26** | +0.03c | 763 | 756.2 | 1.01 | $1.11 |
| **total** | **3,310** | **16,983** | **+$212.69** | | | | | |

> **The "other (legacy)" row this replaces read -$22.05 and was wrong**, as was
> everything derived from it. It is not legacy and not a bot: it is the
> operator's own manual 15-minute crypto trades, and it is roughly break-even.
> See the 2026-11-20 evening correction below.

> **The script's first run reported ratios of 0.14, 0.23 and 0.01, and they were
> a unit error:** it counted losing **markets** against an implied figure summed
> over **contracts**. At lot 1 the two coincide, which is why the commodity row
> was right and every other row was flattered by roughly the lot. The ladder's
> real ratio is **0.72, not 0.14**. Fixed before the script was committed, and
> the comment in it says so. Any ratio in this file should be checked for which
> unit is on each side of the division.

The ladder has added ~1,072 contracts since the 2026-11-18 review (+$186.72 on
10,551 at +1.77c), earning +$8.52 at **+0.79c/contract** — below its +1.68c
lifetime average, on too few losses to mean anything yet. That is the figure to
watch through the lot-7 step, since step 1's gate is a per-contract one.

The retired weather book's **0.96** is worth keeping visible: it is the
adverse-selection number weatherbot measured on fills (a 0.73 population ratio
arriving as 0.96 once only the lifted quotes count), and it is the reason the
commodity book's live 1.12 against a 0.26 backtest is being watched rather than
explained away.


## Two hosts that were not hosts, 2026-11-20 evening

Both of the day's long-running jobs were put somewhere that cannot keep them,
and both failures are the lesson this file already carried for weatherbot's pm2
daemon -- re-learned twice in one day.

**A Claude session's container is not a host.** The skipped-hours sampler
(`research/crypto/skipped_hours.py`, ~90 minutes for 320 event-hours) was
launched detached with `nohup` on the assumption it would outlive the turn. It
died at **17 of 320 event-hours**: `nohup` survives a parent shell, not the
container being reclaimed. Nothing in a session container should be relied on
past the turn that starts it.

**And GitHub's `schedule` is not a host for a job that cannot miss a day.** The
book collector was given one daily fire at 17:25Z, and on its first day it
**never ran -- zero runs**, while a manual `workflow_dispatch` of the identical
file worked immediately. The workflow was registered and `active`; the schedule
simply did not fire. GitHub documents `schedule` as best-effort ("can be delayed
during periods of high load"), so a design that spends its whole day on one fire
loses the whole day when that fire is dropped -- for a collector whose own
documentation says **a missed day cannot be recovered.**

Fixed by changing the shape rather than the file:

| | was | is |
| --- | --- | --- |
| book collector | one fire, 17:25Z, ~295 cycles, 330-min timeout | `*/15 17-22 * * *`, ~14 cycles a run, 25-min timeout |
| cost of a dropped fire | **the whole day** | 15 minutes |

Twenty-four short runs tile 17:00-22:59Z, which spans both close clusters
(19:30Z and 22:00Z) with the 120-minute tracking window ahead of each. The
`concurrency` group still prevents two collectors on one window.

The samplers now run on `.github/workflows/research-sample.yml`, dispatch-only,
with the sampler's database cached between runs so a re-dispatch RESUMES (the
sampler skips any event-hour already in `hour_done`) and the scoring can be
redone without re-fetching. 2026-11-20 data is lost either way: the collector
recorded nothing that day, and the first real coverage starts whenever the new
schedule fires.

> **The sampler's own result is still not in.** 17 of 320 hours is nothing, and
> on the 6-hour smoke test the control read 2.80 against the account's 0.72 and
> **withheld the verdict**, which is the control working. Nothing about
> `skip_hours_et` is established, and nothing about it changes.


## The P&L derivation was wrong twice in one day, 2026-11-20 evening

The operator asked a plain question -- "I've lost money the last two days, is it
something we changed?" -- and the first two answers were both wrong, in opposite
directions, from the same documented trap: **`side` is the outcome ACQUIRED, not
the trade direction.** This file quotes that rule. It did not stop either error.

### 1. The settlement row double-counts a position closed before expiry

`revenue - cost - fee` is correct only while the position is still on at expiry.
A round trip books the EXIT as a second purchase:

```
KXBTC15M-26NOV190930-30
  yes_count_fp 16.39   yes_total_cost_dollars 2.798860   <- the buys
  no_count_fp  16.39   no_total_cost_dollars  8.686600   <- the SELLS
  revenue 0                                              <- nothing held at expiry
```

Read that way: **-$11.90**. From the fills -- bought $2.80, sold $8.69, fees
$0.42 -- it is **+$5.47**. An error of **$17.37 on one market**, landing on a day
the operator was asking about, and it turned a winning scalp into the single
worst line of the week.

### 2. Grading everything from fills is far worse

The obvious repair printed **+$11,027.80 on the BTC ladder at +179c per
contract** -- on an account holding about $100, against a theoretical maximum of
100c. It read `action: "sell"` as closing a position. The ladder's fills:

| action, outcome_side, book_side | n |
| --- | --- |
| `('buy', 'yes', 'bid')` | 1,281 |
| **`('sell', 'no', 'ask')`** | **1,207 -- also position-OPENING** |

Buying NO at 0.04 and selling YES at 0.96 are the same primitive, and the
exchange records the NO purchase as a SELL. So 1,207 openings were read as
disposals. **No code may infer "closed" from `action`.**

### What it does now, and the two checks that were missing

The exchange's settlement arithmetic stands -- it is the exchange's accounting
rather than a reconstruction, which is what "never grade P&L from local size and
price" always meant. Fills are used for exactly one thing: the markets that were
genuinely flat at expiry, detected as `yes_count_fp > 0 AND no_count_fp > 0`,
which is **2 of 3,310** settlements, both manual. Fees are counted once --
settlement `fee_cost` and the sum of fill fees agree on **3,248 of 3,248**
markets that have both.

- **A sanity check that refuses to print.** Any total beyond $2,000, or any book
  outside +/-100c per contract, fails the run. Its absence is the only reason
  +$11,027 was ever shown to anyone.
- **The old control was vacuous.** It compared the new derivation against the old
  one only on markets where the two are identical by construction, so it passed
  1,262 of 1,262 while the live path was out by five orders of magnitude. A
  control that cannot fail is not a control -- the same shape as the inner
  clipping guard dropped from `skipped_hours.py` earlier the same day.

### The answer to the question that prompted it

| | 11-19 | 11-20 | two days |
| --- | --- | --- | --- |
| BTC ladder | -$5.51 | +$1.82 | **-$3.69** |
| commodity | -$0.31 | -$1.53 | -$1.84 |
| manual 15-minute crypto | +$2.83 | -$2.87 | -$0.04 |
| **total** | **-$2.99** | **-$2.58** | **-$5.57** |

About **$5.57 over two days, not the $23 first reported**: a mildly bad stretch
on the bots, with the manual trades a wash. Nothing to do with the lot-7 step,
which is +$8.18 over 1,392 contracts at ratio 0.87.

The manual 15-minute crypto book is **+$0.26 lifetime over 10 markets, 8 of them
losing** -- eight ~$1 longshot losses paid for by two scalps closed before
expiry. The "10 of 10 losses" reported earlier was false. The cheap-tail finding
(-0.83c/contract as a taker) still rests on its own 3,571-quote sample, but the
live record was cited as corroboration and does not provide it.

Tests: `research/screen/test_account_record.py` (27) freezes the real trade's
figures, that a round trip counts its contracts once and implies no loss, that a
NO fill is priced off the NO side, cash direction, fees once, and that the sanity
ceiling would have caught the +$11,027.


## Market regime, 2026-11-21 — measured, and it is not a selector

Operator question: would identifying the market's state let the parameters be
tuned per state for more profit? **No as a selector, measured. Yes as a narrow
safety flag, which is what shipped.**

### Volatility does not predict the ladder's edge

The ladder's 398 settled event-hours, bucketed by trailing 12-hour realised
volatility of hourly BTC log returns at entry, zero lookahead (Coinbase hourly
closes):

| vol quartile | hours | contracts | per contract | loss ratio | premium/ct |
| --- | --- | --- | --- | --- | --- |
| Q1 calm | 99 | 2,794 | +0.79c | 0.88 | 6.48c |
| Q2 | 100 | 3,256 | **+3.24c** | 0.48 | 6.27c |
| Q3 | 99 | 3,000 | +0.91c | 0.84 | 5.87c |
| Q4 wild | 100 | 2,853 | +1.34c | 0.74 | 5.23c |

**Non-monotonic, and the wildest quartile beats the calmest.** Q2 is one cell of
four and has exactly the shape this file already rejects: 04-05 ET read -2.27c
in-sample and +5.28c out, out of 18 cells examined.

**The last column is the finding.** Premium per contract FALLS as volatility
rises, 6.48c to 5.23c, because the book selects on PRICE and a 0.94 bid sits
further from spot when vol is higher. **The price band is already a volatility
classifier**, repriced continuously by the market -- a better one than anything
fitted here. Same mechanism the cheap-tail study found: "the market already
widens the ask on volatile days by about the right amount."

Two structural reasons this was never going to work, beyond the table:

- **There is one regime in the sample.** BTC fell from ~$72.8k to ~$63.2k across
  the entire window. Per-regime parameters cannot be fitted on data holding one
  regime; it would be fitting the calendar.
- **Multiple comparisons against 518 losses.** A classifier multiplies the
  search space against the thinnest sample in the account.

The base rate for this idea here is now **0 for 9**: hour-of-day, vol quartiles
on the tail, the loss classifier (AUC 0.709 against price alone at 0.747), the
post-fill path model (0.898 against the current ask's 0.909), Kronos, the
weather ensemble, queue depth, forecast distance, and this. Every one failed the
same way -- **the price already contains the state.**

### Lot 7's softness is the conditions, not the lot

Worth knowing before the 2026-11-28 verdict. The lot-7 window reads +0.59c at
ratio 0.87 against a +1.62c lifetime average, which looked like degradation:

| | hours | contracts | per ct | ratio | median vol |
| --- | --- | --- | --- | --- | --- |
| lot 7 (11-18 ->) | 44 | 1,392 | +0.59c | **0.87** | 0.451% |
| lot 5 (11-08..17) | 133 | 2,885 | +1.43c | 0.71 | 0.538% |
| lot 5 (all, to 11-17) | 354 | 10,510 | +1.76c | 0.71 | 0.363% |

**0.87 is unremarkable against a per-quartile spread of 0.48 to 0.88.** The
lot-7 window simply holds almost no Q1 hours (quartile counts 1 / 12 / 13 / 18).
No claim that lot 7 is fine -- 44 hours decides nothing, and the gate is still
8 days and >= 30 losses -- but the softness is not evidence against the lot.
Stated narrowly on purpose: a tighter comparison needed a vol-bucket cut chosen
after seeing lot 7's distribution, which is a degree of freedom, so the
defensible claim is only that 0.87 sits inside the measured range.

### What shipped: a bounds check on the evidence

`core/strategy/RegimeEnvelope.kt`, live mode only on the ladder, reachable
through `healthChecks` and rendered by `HealthSection`.

| | hourly realised vol |
| --- | --- |
| measured min | 0.057% |
| p05 | 0.117% |
| median | 0.381% |
| p95 | 0.766% |
| measured max | 1.464% |

It FAILS only **outside** that range -- rare by construction, and deliberately
so. Flagging the middle 90% would fire one hour in ten for no new information,
which is the always-firing-guard mistake made and corrected the previous day in
`skipped_hours.py`. It cannot return KILL: nothing here is strong enough to halt
a working book. Unreadable volatility is INSUFFICIENT, never PASS.

What it says when it fires is deliberately not a forecast: "the evidence does
not reach here", with the non-monotonic quartile table carried in the detail so
nobody reads it as "trade less when wild". The two directions differ in
meaning -- above the range the concern is an unprecedented one-sided sweep;
below it, that the deep-ITM strikes carry no flow to fill against, which is a
fill problem rather than an edge problem.

Tests: `RegimeEnvelopeTest` (9) freezes that it passes across the middle, fails
only outside, never KILLs, carries the "not a prediction" wording, and converts
`BtcIndex.sigma()` (per sqrt-second) to hourly by sqrt(3600).
`LiveGuardWiringTest` asserts REACHABILITY -- removing the call site fails with
"regime flag not reachable", verified before committing.


## `skip_hours_et`: measured 2026-11-21, and it is a NO FINDING

The 2026-11-18 review called unskipping hours 03 and 10 ET "a measurement to
run, not a change to make", worth about 8% of throughput, on a setting whose own
description ("session opens, where losses ran 3-7x higher") this file says is
supported by nothing. The measurement ran on CI: 320 event-hours over 30 days,
944 picks, the ladder's own selector replayed from public candles at a 1800s
lead with zero lookahead (`research/crypto/skipped_hours.py`).

**The control passed** -- the traded hours reproduce 0.85 against the account's
realised 0.72, so the method does reach the hours the ladder really trades --
and then the result refuses to say anything:

| | picks | hours | losses | implied | ratio | 95% CI |
| --- | --- | --- | --- | --- | --- | --- |
| traded (control) | 757 | 266 | 40 | 46.9 | 0.85 | [0.56, 1.18] |
| skipped (all four) | 187 | 54 | **5** | 11.6 | 0.43 | **[0.00, 1.08]** |
| 03 ET | 29 | 12 | 1 | 1.8 | 0.55 | [0.00, 1.67] |
| 10 ET | 61 | 14 | 3 | 4.0 | 0.76 | [0.00, 2.47] |
| 21 ET | 46 | 14 | **0** | 2.8 | 0.00 | **[0.00, 0.00]** |
| 23 ET | 51 | 14 | 1 | 3.1 | 0.33 | [0.00, 1.20] |

**The skipped hours' interval contains both fair pricing (1.0) AND the traded
hours' 0.85.** Five losses across four hours decides nothing against a 30-loss
bar. The aggregate 0.43 is also pulled down by hour 21's **zero-loss cell with
an interval of [0.00, 0.00]** -- the fake tightness this file records for the
KXLOW six dates, the first Polymarket pull, the BTC paper book and the
unquoted commodity markets. A bootstrap cannot resample a loss that is absent.

And the per-hour field is a noise field, which is the deeper point: **24 cells
carrying 45 losses between them, 1.9 per cell.** Five hours show zero losses and
read [0.00, 0.00]; hour 13 ET reads 2.34 [0.44, 4.79] and hours 00, 08 and 20
all exceed 1.5. Picking winners out of that is the 04-05 ET mistake with more
decimal places.

**So: nothing changes.** `skip_hours_et` stays as it is -- not because the skip
is justified (it still is not), but because unskipping is not justified either,
and the repo's rule is that a parameter moves on a measurement rather than on
the absence of one. One thing the run does establish, weakly: hours 03 and 10
carry 29 and 61 in-band picks over 12 and 14 hours, so the pool is real and the
~8% throughput claim is not imaginary. The edge in those hours is simply
unmeasured.

What it would take to answer: roughly six times the data, i.e. a year of
settled hours rather than a month, or pooling the four skipped hours against
the whole traded set and accepting that as the only question with enough losses
to decide -- which is the 0.43 [0.00, 1.08] row, and it already declines to
answer. **This question should be treated as closed by underpowering, not
re-asked each time someone notices 8% of throughput sitting idle.**

Run: `.github/workflows/research-sample.yml`, dispatch-only, 58 minutes; the
database is cached so a re-dispatch resumes and extends the sample rather than
starting over. `window OK: the furthest in-band strike sits $1410 from spot,
inside $2000` -- the fetch window did not clip the band.
