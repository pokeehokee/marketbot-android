---
name: kalshi-prediction
description: Scans and evaluates Kalshi prediction markets via V2 endpoints to identify mispriced opportunities, volume anomalies, and edge deviations, filtered through strict liquidity and three-phase execution timelines. Use when asked to find, research or size a Kalshi bet, scan Kalshi for mispricings, or turn research into a one-off research bet for the app.
---

# Kalshi prediction

Act as a meticulous research analyst looking for high-profit, one-off bets on Kalshi. The output is a short
list of researched targets (or none) in the fixed format below, each one ready to become a paper research
bet in the app.

Two rules come before everything else, because research that breaks them is worthless:

- **Every market number comes from the live API, with its timestamp.** Tickers, bid/ask, open interest and
  close times come from `scan.py` or a direct `GET /markets/{ticker}`, never from memory or another note.
  Re-read each finalist immediately before writing it up. A ticker that does not resolve is dropped, not guessed.
- **The market is the baseline to beat, and it is usually right.** On 982 settled Kalshi weather markets the
  price was calibrated (realised/implied 1.00, Brier 0.068); weatherbot measured public NWP forecasts *losing*
  to Kalshi weather prices (Brier 0.169 vs 0.100), and blending them into the price made it worse. An edge
  claimed from the same public data the market already prices needs extraordinary evidence. Say so when
  that is the case.

## 1. Market discovery and API ingestion

Run the scanner (public data, no keys, no orders):

```bash
python3 .claude/skills/kalshi-prediction/scan.py \
  --categories "Climate and Weather,Economics,Politics,Elections" \
  --min-oi 1000 --max-spread 0.05 --min-hours 6 --max-hours 720 --limit 40 --json scan.json
# or a named set of series (much faster):
python3 .claude/skills/kalshi-prediction/scan.py --series KXHIGHCHI,KXHIGHNY,KXFED --min-hours 6
```

- Category words are Kalshi's own (`GET /series?category=...`): e.g. `Climate and Weather`, `Economics`,
  `Financials`, `Politics`, `Elections`, `Science and Technology`. The scan walks open events, keeps those
  categories, and reads their nested markets.
- Filters (defaults match the brief): open interest **≥ $1,000** (`open_interest_fp` contracts × `notional_value_dollars`),
  spread **≤ 5¢** (`yes_ask_dollars − yes_bid_dollars`), a two-sided book (a side with no orders reads 0.00/1.00 and is
  excluded), and a time-to-close window.
- The public API rate-limits. If the category scan returns 429s, narrow it with `--series`.
- **Volume anomalies:** compare `volume_24h_fp` with open interest and with sibling markets in the same event. A sudden
  spike is a lead to research, not an edge: it usually means someone knows something, and they are on the other side.

## 2. Research and adversarial analysis

For each candidate worth a look (aim for 3–8, not 40):

1. **Read the rules.** `rules_primary` names the settlement source and the exact condition (e.g. "maximum temperature
   at Chicago (CLIMDW) … less than 58°"). Research the settlement source, not a proxy for it.
2. **Build the external baseline.** Name the source and why it should know more than the price: an official
   forecast or release schedule, a model with a published track record, a structural fact the market is ignoring.
   Give a probability with a range, not a point.
3. **Base rates.** How often has this kind of market resolved YES, and how did similar prices do? If no record exists,
   say so.
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

## 4. Edge, fees and sizing

- **Edge is measured against the price you would pay, not the mid.** Buying YES pays the ask; buying NO pays
  `1 − yes_bid`. Edge = p(side) − price − fee per contract.
- **Taker fee** (crossing): `ceil(0.07 × C × P × (1 − P))` dollars per order of C contracts at price P. A resting
  (maker) order on most weather series pays none but may not fill, and fills most often when it is wrong.
- **Kelly:** for a binary bought at price q with win probability p, full Kelly is `f* = (p − q) / (1 − q)`.
  Recommend a fraction (¼ Kelly or a hard cap such as 8% of the bankroll, whichever is smaller) because p is an
  estimate. Use the low end of p's range for sizing.
- **Invalidation triggers:** concrete, observable, with a time: "a 12Z HRRR run showing a high ≥ 60°F", "a CPI
  nowcast above 0.3% m/m before Thursday 08:30 ET".
- No target passes? Say so. "Nothing clears the bar today" is a valid and common result.

## 5. Output, one block per target

```
### <Market title> — <TICKER>
- Current pricing (API, <UTC timestamp>): YES bid/ask <b>¢ / <a>¢; implied <mid>% ; spread <s>¢; OI $<oi>; 24h volume <v>
- Settlement: <rules_primary, one line>; closes <close_time> (<h> h)
- External baseline & edge: <source(s)> → p(<side>) = <p>% (range <lo>–<hi>%); price paid <q>¢ + fee <f>¢ → edge <e> pts
- Adversarial audit & refutations: <counter-argument> → <refutation with evidence> (repeat)
- Surviving "against" arguments: <residual risks that could not be refuted>
- Decisive "for" arguments: <the points that survived>
- Time horizon & phase clearance: <h> h to close; Phase 1 done now; clears Phase 2 and 3 if reviewed by <time>
- Risk/reward & sizing: full Kelly <f*>; suggested <fraction> = $<stake> of $<bankroll> (<n> contracts at <q>¢);
  max loss $<x>, max gain $<y>; invalidate if <trigger>
```

End with a one-line verdict per target: **bet / watch / pass**, and why.

## 6. Turning a target into a research bet (paper)

The marketbot app records one-off bets as paper trades and grades them at settlement (see the repo's CLAUDE.md,
"Claude sync"). For each target the user wants to track, produce:

```json
{"id": "bet-<yyyy-mm-dd>-<short-name>", "strategy": "research_bets", "reason": "<one line>",
 "bet": {"ticker": "<TICKER>", "side": "yes|no", "maxPrice": <limit, dollars>, "stakeUsd": <dollars>,
         "probability": <p of the side>, "thesis": "<decisive for-arguments>",
         "against": "<surviving against-arguments and invalidation>", "source": "<sources, timestamp>"}}
```

Only add it to `proposals/pending.json` in the user's sync repository (`pokeehokee/marketbot-sync`) when the user
says to send it; otherwise keep it in `proposals/held-bets.json`. The app only ever trades that exact ticker and side,
in paper, after the user approves it on the phone. This skill never places a real order.
