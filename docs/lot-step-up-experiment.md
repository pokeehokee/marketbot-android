# The lot step-up: 5 → 7 → 10 as a measured experiment

Written 2026-11-18, after the capital review found that every "free" lever on the BTC deep-ITM
ladder died on measurement. The lot is the one remaining lever with real money in it, and it is
**not** free: it is an unmeasured fill assumption and a linear increase in the worst hour. This
document is the protocol that makes it a measurement rather than a hope.

Nothing here is shipped. `lot: 5` stands until step 1's gate passes.

## The baseline, from the exchange's own settlements

2,094 settled `KXBTCD` positions, 10,556 contracts, 24 days, graded from
`/portfolio/settlements` payloads (never from local arithmetic):

| | |
| --- | --- |
| per contract | **+1.77c**, 90 losing positions |
| throughput | 440 contracts/day, **+$7.79/day** |
| fees | $0.00 — 130 of 130 app-era fills were maker |
| positions at exactly 5 contracts | 1,933 of 2,094 (92.3%) |
| **partial fills** | **88 of 2,094 positions (4.2%)** |
| strikes filled per hour | mean 5.87, median 7, **8 (the cap) in 156 of 357 hours** |
| collateral per contract | median $0.94, max $0.96 |
| **peak collateral in one hour** | **$38.35** = 8 × 5 × $0.96, i.e. the cap is hard |
| worst single hour | **−$17.35** |
| negative hours | 63 of 357 (17.6%) |
| losing days | 3 of 24 |
| losses per day | 3.75 |

> The capital review quoted a $56.20 peak. That was a legacy desktop 10-lot hour, not this
> configuration. Restricted to hours whose largest position is 5 contracts, the peak is $38.35 and
> the p99 equals the max — the 8-slot cap binds exactly, which is what makes the scaling below
> arithmetic rather than extrapolation.

## What the step costs, in collateral and in the worst hour

Peak hour = `8 slots × lot × $0.96`. Shard-2 equity is $89.57 (cash $74.72 + marked positions
$14.85); $11.86 sits idle on shard 0 and Kalshi moves nothing between shards by itself.

| lot | peak hour | % of shard-2 equity | % with the $11.86 moved | worst hour (scaled) | single strike | stop ÷ strike |
| --- | --- | --- | --- | --- | --- | --- |
| **5 (today)** | $38.40 | 43% | 38% | −$17.35 | $4.80 | 3.1 |
| 7 | $53.76 | 60% | 53% | −$24.29 | $6.72 | **2.2** |
| 10 | $76.80 | **86%** | 76% | **−$34.70** | $9.60 | **1.6** |
| 12 | $92.16 | 103% — unfundable | 91% | −$41.64 | $11.52 | 1.3 |

Two things this table decides.

**Lot 10 is fundable but only just, and the binding objection is not funding — it is the worst
hour.** All strikes of one BTC hour settle off one price move, so a bad hour loses most of the book
at once: −$34.70 is 39% of shard-2 equity in a single hour, against −$17.35 (19%) today. No
realised-P&L stop prevents that first batch; the stop only prevents the next one.

**Lot 10 requires the daily stop to move, and that is a loosening.** `$15 ÷ $9.60 = 1.6` single
strikes, below the two-to-three-typical-losses floor the 2026-11-14 entry pins. So the stop would
have to go to ~$25 — and loosening a risk control is permitted only for a benefit that has been
**observed, not modelled**. That is precisely what step 1 exists to observe. The order is not
negotiable: lot 7 must earn its money before lot 10 may be configured at all.

## Why the lot and not `max_open`

`max_open: 8` binds in 156 of 357 hours (44%), which looks like the obvious cheaper lever — it adds
strikes, each with its own queue, so it carries none of the lot's fill unknown. The settlements say
otherwise:

| | hours | contracts | per contract |
| --- | --- | --- | --- |
| hours at the 8-slot cap | 156 | 6,142 | **+1.61c** |
| hours under the cap | 201 | 4,414 | **+2.00c** |

**The hours where the cap binds are the worse hours**, and raising the cap adds contracts in exactly
those hours — at the lower rate, and plausibly lower still, since the marginal 9th–12th strikes are
ones `slot_order` already passed over (the `<0.94` band pays +1.64c against +1.85c above it).

The honest reading is that capped hours are capped because the pool was rich, which correlates with
volatility, which correlates with losses — so this is a property of those hours rather than proof
about the marginal strike. But it is not the free lever the review called it, and it costs the same
collateral per added contract as the lot does. The account can fund roughly one step of either.
The lot is the larger one (+40% of contracts across every hour, against +30% of positions in 44% of
hours), so it goes first.

## The protocol

One change at a time. `max_open`, `slot_order`, `band_lo`, `band_hi` and `skip_hours_et` are frozen
for the duration — a step that moves two things measures neither.

### Step 1: lot 5 → 7

Prerequisite: **transfer the $11.86 from shard 0 to shard 2** (a tap in the app). At 60% of
shard-2 equity the step is fundable without it, but the margin for a settlement burst arriving late
is thin, and idle cash on the wrong shard earns nothing in any case.

Run for **≥ 8 days AND ≥ 30 losing positions at the new lot** (3.75 losses/day, so the loss count
is the binding one and lands around day 8). Expected, if per-contract holds: 616 contracts/day,
**+$10.90/day, i.e. +$3.11 over the baseline.**

### Step 2: lot 7 → 10

Only if step 1's gate passes in full, and only with the daily stop raised to **$25** in the same
change — the stop is the price of the step, and step 1's observed benefit is what justifies it.
Expected: 880 contracts/day, **+$15.58/day, +$7.79 over the baseline.** Same duration rule.

There is no step 3. Lot 12 is unfundable at this equity, and fills at 12 are a different question
again.

## The gate: all six must hold

Measured at the new lot only, from exchange settlements and fills, never from local size and price.

| # | threshold | why that number |
| --- | --- | --- |
| 1 | per contract **≥ +1.00c** over ≥ 30 losses | baseline +1.77c with a lower CI bound of +1.09c; +1.00c says the edge survived the size |
| 2 | partial-fill rate **≤ 10%** of positions | baseline 4.2%; partials are what a binding queue looks like first |
| 3 | strikes filled per hour **≥ 5.5** | baseline 5.87; a fall means flow cannot absorb the size at all |
| 4 | **zero** order rejections for insufficient collateral | one rejection means the step is not funded, and the sample is then biased toward cheap hours |
| 5 | worst single hour **≥ −25% of shard-2 equity** | −$17.35 is 19% today; 25% is the tolerance before the account is one bad hour from a rebuild |
| 6 | daily stop **≥ 2× a single-strike max loss**, unraised | the brake invariant: one market can never outrun the brake |

## Abort at once, without waiting for the 8 days

- any order rejected for insufficient collateral (gate 4 is a kill, not a tally);
- partial-fill rate **> 20%** over the first 50 positions — the queue is binding and the remaining
  days will only add expensive confirmation;
- any single hour worse than **−35% of shard-2 equity**;
- any traded series moving to `quadratic_with_maker_fees` (`GET /series/fee_changes`) — the whole
  edge is that a resting fill pays nothing.

On an abort, revert to the previous lot the same day and record what tripped. A reverted step is a
result, not a failure.

## What this cannot tell you

The median queue ahead of an in-band BTC strike is **3,691 contracts**, so 5 against 10 is noise
beside it and the binding factor is whether flow reaches our price at all, not our size. That is
**reasoning, not a measurement** — it is the reason to expect the step to work, and the reason the
gate measures fills rather than assuming them.

And the ceiling is not moved by any of this. At lot 10 the ladder earns ~$15.58/day on ~$77 of peak
collateral. Scaling to $30/day needs roughly double again — lot 20, ~$154 of peak collateral,
~$200 of shard-2 equity, and a worst hour near −$70. **The remaining headroom at today's ~$101 of
equity is about +$3/day for step 1 and +$8/day for step 2, and then the account is the constraint
again.** No ordering, band or hour filter found in this repo adds to that; money does.

## Step 1 started: 2026-11-18

`lot: 7` set by the operator on 2026-11-18 (~05:10Z). Everything measured from here is step 1's;
nothing before it counts toward the gate.

**Starting state, read from the exchange, not from any local table:**

| | |
| --- | --- |
| equity | **$101.58**, all of it on shard 2 |
| shard 0 | $0.00 — the idle $11.86 was transferred (`4dc9661a`, `event_contract/0 -> /2`, complete) |
| open positions | none; `portfolio_value` 0 |
| lot-5 record it replaces | 2,094 positions, 10,556 contracts, +$186.92, **+1.77c**, 90 losses, 24 days |
| peak hour expected at lot 7 | $53.76 = 53% of equity (was 43% at lot 5 against a smaller balance) |

**Earliest possible verdict: 2026-11-26** — 8 days, and >= 30 losing positions at the new lot. At
the lot-5 cadence of 3.75 losses/day the loss count lands around day 8, so the two bind together.
Read the six gate thresholds and the four abort conditions above; the gate is read on **positions
placed at lot 7**, by placement time, never by settlement date. (Cutting by resolution date smears
configurations together -- the weatherbot CLAUDE.md records exactly that error costing a correct
verdict.)

One measurement note the first hour already raised: the 04:41Z fills show a single 5-lot filling
as 2.44 + 1.56 + 1.00. **Gate 2 counts partial POSITIONS, not partial fills** -- an order that
fills in three pieces to its full size is not a partial. The baseline 4.2% is positions whose
filled count fell short of the lot.

**Confirmed at the exchange, 05:31Z.** The first lot-7 fills are on the record:
`KXBTCD-26NOV1801-T64999.99` at 05:30:24Z and `-T64399.99` at 05:31:39Z, both **7.00** contracts.
Shard-2 cash $88.28 with `portfolio_value` $13.65 — the two 7-lots locked ~$13.30, which is the
arithmetic behaving. Equity $101.93. Step 1 is running from here; the clock started at 05:30Z.

A false alarm worth recording, because the next person to watch a step start will see the same
thing. Between 05:12Z and 05:14Z there were **zero resting orders account-wide** and the newest
fill was 04:41Z at size 5 — about 35 quiet minutes on a book that fills ~5.9 strikes an hour,
which read like an engine that had stopped placing after the setting change. It had not. The
ladder only quotes inside `ttc` (300–3000 s to close), so between one hour's last fill and the
next hour's window opening there is nothing resting by design, and both of the next orders filled
on arrival rather than sitting. **Zero resting orders is not evidence of a stalled engine; a
missing FILL across a whole placement window would be.** Check against `ttc`, not against the
clock.
