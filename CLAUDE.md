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

## Commands

```bash
./gradlew :core:test                 # fast, pure JVM
./gradlew :app:testDebugUnitTest     # Robolectric; writes screenshots to app/build/screens
./gradlew :app:assembleDebug
```

In this sandbox Maven Central rate-limits, so `~/.gradle/init.d/mirror.gradle` and the `robolectricRepo` property point at
Google's Maven Central mirror. CI needs neither.
