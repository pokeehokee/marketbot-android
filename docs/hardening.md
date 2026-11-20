# Hardening: regime shifts, liquidity dry-ups, and silent 3 AM failures

Written 2026-11-19. Everything here is measured against this account's own
settlement history or this repo's own incident record. The ordering is by
expected cost of the failure, not by how interesting it is.

---

## 1. The per-hour loss cap is structural, and it is roughly double what the step-up doc says

`docs/lot-step-up-experiment.md` states lot 10's worst hour as **−$34.70**,
obtained by doubling the worst hour *observed* in 24 days. **That is
calibrating risk to a benign sample.** The structural figure:

| | |
| --- | --- |
| event-hours on record | 384 |
| hours with zero losses | 318 of 384 |
| losing hours | 66, **all one-sided** — zero mixed |
| worst hour | −$18.50 against **$19.10** of losing-side collateral: 97% of the exposed side |
| max one-side collateral seen | **$35.51** = 92% of the 8-strike theoretical $38.40 |

**Both sides cannot lose on the same close, and this is structural rather than
lucky.** `KXBTCD` settles on the top-of-hour price, so a single close can only
sit beyond the strikes on one side of spot. The ladder holds YES on strikes
below spot and NO on strikes above, so a directional move kills one side and
*helps* the other. All 66 losing hours confirm it.

The cap is therefore **one side swept**, and the book does sometimes put nearly
its whole allowance on one side ($35.51 of a possible $38.40):

| lot | one side swept | share of ~$101 equity |
| --- | --- | --- |
| 5 | ~$38 | 38% |
| 7 | ~$54 | 53% |
| **10** | **~$77** | **76%** |

The worst hour observed was small because that hour's *exposure* was small, not
because the loss rate was mild — it lost 97% of what it had on the line. **No
realised-P&L stop can prevent this batch**, because `daily_stop` reads settled
P&L and settlement happens after the hour closes. The stop governs the *second*
hour; the first is uncapped up to the one-side book.

Action: the lot-10 gate in the step-up protocol should read the structural cap,
not the scaled observation. At 76% of equity in one unpreventable hour, lot 10
is a different decision from the one that doc describes.

## 2. The shock that sweeps a side is not rare -- measured properly

The honest statistic is the **net move to the close**, not the intra-hour range:
settlement is the top-of-hour print, so a whipsaw that returns settles as a win
and range overstates the risk. Net |move| to the settlement instant, 8,821
hourly boundaries over 368 days of 5-minute BTC closes:

| lead | median | p90 | p99 | max |
| --- | --- | --- | --- | --- |
| **50 min** (the ladder's `ttc_max`) | **$131** | $467 | **$1,138** | **$4,013** |
| 30 min | $99 | $344 | $869 | $2,152 |
| 5 min | $50 | $163 | $417 | $1,392 |

How often the move reaches a given sweep distance:

| net 50-min move | share of 8,821 hours | roughly |
| --- | --- | --- |
| >= $200 | **34.7%** | 1 in 3 hours |
| >= $300 | 20.8% | 1 in 5 |
| **>= $450** (half the median strike span) | **10.7%** | **1 in 9 hours** |
| >= $600 | 5.6% | 1 in 18 |
| >= $900 | 2.1% | 1 in 47 |

**Sweeping one side is a 1-in-9-hours event, not a tail.** It also requires the
book to be concentrated on the side that moves, which is what keeps the realised
record milder: 66 losing hours of 384, the worst losing 97% of its exposed side.

## 2b. Earlier range-based figures, kept for contrast

What move is needed, and how often BTC supplies it:

| | |
| --- | --- |
| strike span within one hour's book | median **$900**, p90 $1,500, max $2,900 |
| BTC intra-hour high-low range (9,024 hours, 376 days) | median $152, p90 $531, **p99 $1,300**, max **$3,807** |
| hours whose range exceeded the median full span | 280 of 9,024 = **3.1%** |

Sweeping *one* side needs roughly half the span plus the distance from spot to
the nearest strike. The deep-ITM band (0.90–0.96) sits close to the money, so
that distance is small — on the order of a few hundred dollars at a 50-minute
horizon. **A move of that size is an ordinary bad hour, not a tail event.**

Note the correct statistic for this market is the **net move to the close**, not
the intra-hour range: settlement is the top-of-hour price, so a whipsaw that
returns settles as a win. The range figures above bound the move; they do not
equal it. A net-move distribution keyed to each market's own `ttc` window is the
measurement to run next.

## 3. Exchange-vs-ledger reconciliation EXISTS -- and is wired only to the retired book

**Corrected 2026-11-19, after proposing to build it.** `core/strategy/Reconcile.kt`
already does this: `reconcileKalshi` reads the venue's resting orders and held
positions in a strategy's own markets and reports anything the ledger does not
know. It is a port of weatherbot's `longshot_reconcile --check` and its comment
names the exact hazard ("a second trader on the same account... the file lock on
the desktop only ever covered one machine").

**It is called by exactly one strategy: `WeatherLongshot`, which is retired and
OFF.** Both books that trade real money have no reconciliation at all. The
control was built, the strategy it guarded was retired, and it was never moved
to the books that replaced it.

That is the same failure shape this repo keeps recording -- the `Evidence`
object that outlived its measurement, the `ecosystem.config.js` comment that
denied a risk control existed. **A safety control orphaned by a refactor is
indistinguishable from no control, and worse than none, because the docs read as
though the ground is covered.** Any audit of this system should check not just
that a control exists but that something live calls it.

What is now added is narrower and complementary, not a replacement:

| | `reconcileKalshi` (existing) | `KillCriteria.orphanPositions` (added) |
| --- | --- | --- |
| when | pre-flight, before live quoting | continuous, inside `healthChecks` |
| resting orders | **yes** | no |
| unknown positions | yes | yes |
| **size mismatch** | **no** | **yes** |
| surfaces as a `Check` | no | yes |

`DeepItm` now runs `orphanPositions` on every live health check, reading
`/portfolio/positions` and comparing per ticker against the ledger, scoped to
`KXBTCD-`. A read that fails reports INSUFFICIENT; it is never assumed clean.
**`reconcileKalshi` should additionally be wired to both live books as the
pre-flight gate it was written to be** -- that part is still undone, and it is
the half that covers resting orders.

## 3b. Why the control matters: the hazard is not hypothetical

**This account has already suffered this failure, at scale.** The 2026-11-12
entry records recovering "the original desktop run" from the exchange: **1,612
fills and 1,535 positions whose `order_id` the app never placed.** The app had
no idea they existed. That was a different runner rather than a fault, but it is
the exact signature of the hazard — *the exchange holds risk the ledger does not
know about* — and nothing currently alarms on it.

How it happens live, in order of likelihood:
- an order response is lost in flight, the order rests and fills, and the app
  never learns its id (the repo already forbids retrying a create and says to
  recover by `client_order_id` — but nothing *checks* that recovery happened);
- a manual trade in the Kalshi app;
- a second process on the same account (weatherbot's runner and this app share
  one Kalshi account; the separation between their series is convention, not an
  enforced invariant);
- a partial fill recorded at the wrong size.

The control: on every pass, diff `GET /portfolio/positions` against the open
ledger rows per ticker, and alarm on **any** ticker the exchange holds that the
ledger does not, or any size mismatch. `KillCriteria` already alarms on ungraded
positions with `filledContracts > 0`; this is the mirror and the more dangerous
direction, because an unknown position accrues risk silently and is invisible to
every brake, stop and exposure number in the app.

## 4. Staleness alarms per table, not per process

Three separate incidents in `weatherbot` were the same shape — **a table
silently stopped being written while everything else kept running**:

| incident | how long before anyone noticed |
| --- | --- |
| `settlements` froze (NameError in an alert aborted the loop and rolled back the transaction) | **4 days**, 27 settlements missing |
| `performance_by_category` froze (writer read a column the schema never had) | **2 weeks**, category gates judging on stale records |
| calibration updates dropped on lock collisions | 23/day, indefinitely |

Each was found by a human reading logs. The generic control that catches all
three without knowing what they are: **for every table a component writes,
assert the newest row is younger than N x its expected interval, and alarm
otherwise.** The operator watch does this for the runner's *passes*; it does not
do it per table. The cost of the gap is that the system keeps trading while its
accounting is frozen, which is strictly worse than stopping.

Related, and already learned here: an alarm that fires when nothing happened is
an alarm that gets ignored the day it matters (the 2026-09-16 fingerprint
false-alarm on a bare republish). Freshness alarms must be keyed to each
writer's real cadence, not a single global threshold.

## 5. Staleness of the exchange's own view

`GET /exchange/user_data_timestamp` reports when balance, orders, fills and
positions were last validated — "the API lags the exchange", per the API skill.
Nothing in the app reads it. Any exposure or budget computed from a portfolio
read is therefore of unknown age, and the failure mode is silent: a stale
positions read makes the book look smaller than it is, which *permits* more
orders. Read it, and refuse to size from a view older than one pass interval.

The same discipline the repo already applies elsewhere: `fetched_ts` per row on
the book collector, the 45-minute `BALANCE_STALE_AFTER` on the notification, and
the "13:00 prices that actually spanned 04:45 to 13:45" near-miss.

## 6. Multiple comparisons: the hypothesis count is now large enough to matter

This repo is unusually disciplined per hypothesis — zero lookahead, CIs
clustered by the thing that drives outcomes, "count the losses not the fills", a
30-loss bar. What it does not track is **how many hypotheses have been tested in
total.** Across both repos: dozens of band splits, hour-of-day cells (18
examined and correctly rejected), orderings, lead curves, kinds, and most
recently a 50-method list.

At a 5% threshold, 50 independent tests produce ~2.5 apparent winners by chance
alone. The practices already in place — requiring both halves of a window to
agree, demanding 30 losses, preferring intervals that exclude zero by a margin
— are the right defences, and they are why most things here were correctly
rejected. The missing piece is a **register**: one line per hypothesis tested,
with its verdict, so that any future positive can be discounted by the number of
looks that preceded it. `docs/fifty-methods-ranked.md` is the beginning of one.

## 7. Untested recovery is not recovery

The backup entry describes restore as "gunzip and copy into `data/`, with pm2
stopped" and warns that starting the runner against an empty `longshot.db`
re-quotes markets the account already holds. **That restore has never been
executed.** An untested restore is a plan, not a capability; the usual failure
is a schema or path assumption that only appears under real conditions.

The drill: restore the newest snapshot into a scratch directory, run the
resolver and runner against it in paper, and confirm the open-position count
matches the exchange. Once, on a calendar.

## 8. Smaller items, each with a concrete trigger

- **Correlated books.** The BTC ladder and the commodity range book both earn
  residual premium on almost-decided contracts, and both lose when a price moves
  hard. Their *joint* worst day has never been measured; each has its own brake
  and there is no account-level one. The board already says "there is no
  account-wide daily limit" — correct as a description, and worth revisiting now
  that two books trade.
- **`dead_man_s` covers resting orders, not filled positions.** If the phone
  dies mid-hour, resting bids expire at the exchange (genuinely good design) but
  anything already filled is held to settlement with nobody watching. That is
  the right behaviour given buying back was measured and rejected — it should be
  stated as a deliberate choice rather than left implicit.
- **Fee-type drift is monitored; fee *multiplier* drift is not.** The contract
  check watches `fee_type`. `fee_multiplier` is read as 1 everywhere and would
  silently change the economics if it moved.
- **Clock skew.** Kalshi signs `timestamp_ms`; a phone whose clock drifts past
  the tolerance fails auth. The failure is loud, but the diagnosis is not
  obvious at 3 AM — worth naming in the error text.
- **One account, two trading processes.** weatherbot's runner has a `flock`
  single-instance guard. There is no equivalent guard *between* weatherbot and
  this app, and they share a Kalshi account. The separation of series is
  convention. An assertion that a strategy only touches its own series would
  make it structural.

## Resolved 2026-11-20: the two controls this document left orphaned

Section 3 argued that a control orphaned by a refactor is worse than none,
because the documentation then reads as though the ground is covered. This
document then shipped exactly that twice over: `reconcileKalshi` reachable only
from the retired weather strategy, and `KillCriteria.staleWrites` reachable from
nothing at all.

Both are now wired to the two live-capable books via `LiveGuards.noOtherTrader`
and `LiveGuards.balanceWriterFresh` (live mode only; neither can return KILL, so
neither can halt a working book; both INSUFFICIENT rather than PASS when they
cannot read). `LiveGuardWiringTest` asserts **reachability** — removing a call
site fails with "reconcile guard not reachable" — because correctness tests
could not catch this: the functions were already correct.

The per-table staleness section is likewise no longer hypothetical for the one
writer that matters: `Engine.recordLiveBalances` swallows a failed fetch with
`runCatching { }.getOrNull()?.let`, writing nothing and raising nothing, so a
venue going quiet freezes every balance figure in the app in silence. That is
now checked against its 15-minute cadence.

Still undone from this document: per-table staleness for the ledger and event
writers (a quiet book is not a fault, so "expected cadence" needs defining per
writer before a check means anything), and `exchange/user_data_timestamp` as a
cross-check on the positions read.
