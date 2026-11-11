---
name: kalshi-prediction
description: Scans and evaluates Kalshi prediction markets via V2 endpoints to identify mispriced opportunities, volume anomalies, and edge deviations, filtered through strict liquidity and three-phase execution timelines. Use when asked to find, research or size a Kalshi bet, scan Kalshi for mispricings, or turn research into a one-off research bet for the app.
---

# Kalshi prediction

Act as a meticulous research analyst looking for high-profit, one-off bets on Kalshi. The output is a short
list of researched targets (or none) in the fixed format below, each one ready to become a paper research
bet in the app.

Three rules come before everything else, because research that breaks them is worthless:

- **Every market number comes from the live API, with its timestamp.** Tickers, bid/ask, open interest and
  close times come from `scan.py` or a direct `GET /markets/{ticker}`, never from memory or another note.
  Re-read each finalist immediately before writing it up. A ticker that does not resolve is dropped, not guessed.
- **The market is the baseline to beat, and it is usually right.** On 982 settled Kalshi weather markets the
  price was calibrated (realised/implied 1.00, Brier 0.068); weatherbot measured public NWP forecasts *losing*
  to Kalshi weather prices (Brier 0.169 vs 0.100), and blending them into the price made it worse. An edge
  claimed from the same public data the market already prices needs extraordinary evidence. Say so when
  that is the case.
- **Every verdict is logged, not just the bets** (`estimates.py`, step 6). Grading only the markets that became
  bets scores the skill on the cases it felt surest about, which is the one sample that cannot answer whether
  the analysis is worth anything. Bet, watch and pass are all logged, and all graded at settlement.

## 1. Market discovery and API ingestion

Run the scanner (public data, no keys, no orders):

```bash
python3 .claude/skills/kalshi-prediction/scan.py \
  --categories "Climate and Weather,Economics,Politics,Elections" --min-hours 6 --max-hours 720 --json scan.json
python3 .claude/skills/kalshi-prediction/scan.py --series KXHIGHCHI,KXCPI,KXFEDDECISION   # named series, fastest
python3 .claude/skills/kalshi-prediction/scan.py --sort anomaly --limit 15                # lead with the odd ones
```

- **Discovery is cached.** Kalshi lists thousands of series per category (2,400+ in Politics, most of them dead
  one-offs) and the public API rate-limits hard. Once a day the scan walks open events and caches which series
  per category actually have open markets; later runs scan only those, one cheap listing each. The walk is
  paced and resumable — if it is interrupted, re-run and it continues. `--refresh-catalog` forces a rebuild.
- **If discovery is rate-limited anyway**, the scan falls back to `DEFAULT_SERIES` (the liquid recurring series)
  and says so on the line marked `series source:`. Read that line: a fallback scan has not seen the whole market.
  Series that failed are listed under `NOT SCANNED`, and are not "nothing there".
- **Filters:** open interest ≥ $1,000, spread ≤ 5¢, a two-sided book, implied probability in 10–90%
  (`--min-prob`/`--max-prob`: a 2¢ or 98¢ contract cannot pay for its own fee), time to close in the window, and
  at most `--per-event` strikes per event, nearest 50% (ten gas thresholds from one event are one idea, not ten).
- **The scanner does the mechanical work.** Each row carries the plain-language condition from Kalshi's strike
  fields, depth at the ask, and for both sides the taker fee and the **breakeven probability** (`BE yes` / `BE no`)
  — the probability you must be right with for the trade to be worth zero. Compare your estimate to that number,
  never to the mid.
- **Volume anomalies** are flagged, not just displayed: 24h volume ≥ 0.5× open interest, or a move of ≥ 8¢ since
  the previous close. `--sort anomaly` ranks by them. An anomaly is a lead to research, not an edge: usually it
  means someone knows something and they are on the other side.

## 2. Research and adversarial analysis

For each candidate worth a look (aim for 3–8, not 40):

1. **Read the rules.** `rules_primary` names the settlement source and the exact condition. Mind the rounding:
   "above 0.2%" on a one-decimal series needs a printed 0.3%, i.e. an unrounded 0.25%+. Research the settlement
   source, not a proxy for it.
2. **Build the external baseline.** Name the source and why it should know more than the price: an official
   forecast or release schedule, a model with a published track record, a structural fact the market is ignoring.
   Give a probability with a range, not a point.
3. **Base rates.** How often has this kind of market resolved YES, and how did similar prices do? Check the
   skill's own record first (`estimates.py grade`). If no record exists, say so.
4. **Red-team.** Write the strongest counter-arguments, and the strongest case that the *market* is right. Refute
   with evidence where you can; keep what you cannot.
5. **Surviving against-arguments** feed directly into the probability range and the size.
6. **Decisive for-arguments:** only the points that survived step 4.

Use web research (search, fetch the settlement source) for evidence. Cite sources inline.

## 3. Three-phase timeline filter

Compute exact time to settlement from `close_time` (and note `expected_expiration_time` when it differs). A target
must leave room for:

- **Phase 1:** AI signal verification, baseline comparison and adversarial testing (this skill);
- **Phase 2:** user review of risk parameters and edge;
- **Phase 3:** user order entry (or approving a research bet in the app).

Default minimum: 6 hours to close; prefer the longest viable horizons. Ignore flash/micro windows (< 1 hour)
unless the user asks. State the clearance explicitly: "34.1 h to close; clears all three phases".

## 4. Edge, fees, sizing — and the bar for a bet

- **Edge is measured against the breakeven price, not the mid.** Buying YES pays the ask; buying NO pays
  `1 − yes_bid`; both pay Kalshi's taker fee `ceil(0.07 × C × P × (1 − P))` per order. `scan.py` prints both
  breakevens. Edge = p(side) − breakeven.
- **Kelly:** full Kelly is `f* = (p − q) / (1 − q)` for price q. Recommend a fraction (¼ Kelly, or a hard cap such
  as 8% of the bankroll, whichever is smaller) because p is an estimate. Size off the **low end** of p's range.
- **The bar for a `bet` verdict — both must hold:**
  1. edge ≥ **5 points** using the low end of the probability range, after the fee; and
  2. at least one decisive for-argument rests on information the market plausibly does not have — a source it
     cannot see, a structural fact, or a mechanical error in how the contract is priced.
  A nowcast that equals the consensus is not information the market lacks: when the only case is "my model agrees
  with the published forecast", the verdict is **pass**, however wide the apparent gap looks.
- **Below the bar:** `watch` if a named, dated event could move it past the bar (step 7); otherwise `pass`.
- **Invalidation triggers:** concrete, observable, with a time: "a 12Z HRRR run showing a high ≥ 60°F", "a CPI
  nowcast above 0.3% m/m before Thursday 08:30 ET".
- No target passes? Say so. "Nothing clears the bar today" is a valid and common result.

## 5. Output, one block per target

```
### <Market title> — <TICKER>
- Current pricing (API, <UTC timestamp>): YES bid/ask <b>¢ / <a>¢; implied <mid>%; spread <s>¢; OI $<oi>;
  24h volume <v>; depth at the ask <q>; breakeven <BE>%  [<anomaly flags>]
- Settlement: <rules_primary, one line>; closes <close_time> (<h> h)
- External baseline & edge: <source(s)> → p(<side>) = <p>% (range <lo>–<hi>%); breakeven <BE>% → edge <e> pts
- Adversarial audit & refutations: <counter-argument> → <refutation with evidence> (repeat)
- Surviving "against" arguments: <residual risks that could not be refuted>
- Decisive "for" arguments: <the points that survived>
- Time horizon & phase clearance: <h> h to close; Phase 1 done now; clears Phase 2 and 3 if reviewed by <time>
- Risk/reward & sizing: full Kelly <f*>; suggested <fraction> = $<stake> of $<bankroll> (<n> contracts at <q>¢);
  max loss $<x>, max gain $<y>; invalidate if <trigger>
- Verdict: bet | watch | pass — <why, in one line, naming which half of the bar it missed if it missed one>
```

## 6. Log every verdict, and read the record

Log each target as soon as it has a verdict, while the price is live:

```bash
python3 .claude/skills/kalshi-prediction/estimates.py log --ticker KXCPI-26OCT-T0.2 --side yes \
  --prob 0.48 --prob-lo 0.45 --prob-hi 0.52 --verdict pass --note "<one line>" --source "<sources>"
python3 .claude/skills/kalshi-prediction/estimates.py grade    # settle what settled, print the scoreboard
```

The log defaults to `<marketbot-sync>/research/estimates.jsonl` — a git checkout, because a record that dies
with the container answers nothing. **Commit and push it** after logging or grading (`git -C ~/marketbot-sync add
research/estimates.jsonl && git -C ~/marketbot-sync commit -m "estimates: ..." && git -C ~/marketbot-sync push`).
Without that checkout the script warns and falls back to a cache path.

`grade` scores each settled call against the market's own price at the same instant (Brier, lower is better) and
prints the difference with a 95% interval. Read it before trusting a new target, and quote it to the user when
the sample is large enough. **30+ settled calls with an interval excluding zero** is the bar for claiming this
analysis beats the price; below that the honest line is "too few to read". If the interval ever excludes zero on
the losing side, say so plainly and stop acting on the estimates.

## 7. Turning a target into a bet or a watch (both paper, both approved on the phone)

See the repo's CLAUDE.md, "Claude sync". Write the proposal JSON, but only add it to
`proposals/pending.json` in `pokeehokee/marketbot-sync` **when the user says to send it**; otherwise keep it in
`proposals/held-bets.json`. This skill never places a real order.

**bet** → a paper research bet, graded at settlement:

```json
{"id": "bet-<yyyy-mm-dd>-<short-name>", "strategy": "research_bets", "reason": "<one line>",
 "bet": {"ticker": "<TICKER>", "side": "yes|no", "maxPrice": <limit, dollars>, "stakeUsd": <dollars>,
         "probability": <p of the side>, "thesis": "<decisive for-arguments>",
         "against": "<surviving against-arguments and invalidation>", "source": "<sources, timestamp>"}}
```

**watch** → the app's watchlist, which notifies on a price move, so a watch verdict actually comes back:

```json
{"id": "watch-<yyyy-mm-dd>-<short-name>", "strategy": "watchlist", "reason": "<what would change the verdict>",
 "watch": {"venue": "kalshi", "ticker": "<TICKER>", "moveCents": <1-50>, "label": "<short name>"}}
```

A watch needs only a tap (it reads public prices and trades nothing); a bet on a strategy that is off needs the
screen lock. Pick `moveCents` from the edge: the move that would take the market past your breakeven.
