# Method #30: Kalshi daily S&P range brackets vs an options-implied distribution

The highest-ranked of the nine testable ideas in `docs/fifty-methods-ranked.md`:
Kalshi's `KXINX` daily range brackets imply a distribution for the S&P close,
and so does the options market. Trade the gap.

```bash
python3 research/index/index_vol_edge.py            # build + score
python3 research/index/index_vol_edge.py --score    # score the cached sample
```

Unauthenticated and read-only (`assert_read_only` whitelists `/markets`,
`/historical/markets` and `/series/`, refuses anything portfolio- or
order-shaped), with 429 retried inside `get()`. Needs `yf_GSPC.json` and
`yf_VIX1D.json` — daily closes of ^GSPC and ^VIX1D, one object of
`{"YYYY-MM-DD": close}` each.

## Verdict, 2026-11-19: NEGATIVE, and the interval excludes zero

1,336 brackets over **48 event-days**, priced at 13:30Z with zero lookahead:

| | |
| --- | --- |
| Brier, 394 two-sided brackets | model **0.0974** vs market mid **0.0906** — market better |
| Brier, all 1,336 | model 0.0318 vs market **ask** 0.0314 — market better even against the ask, which overstates P(YES) |
| buy-at-ask when model > ask + 0.03 | **−7.42c/contract**, 95% CI by day **[−8.17, −6.74]** |
| same at a 0.05 gate / 0.08 gate | −6.97c [−8.00, −6.00] / −7.08c [−8.75, −5.54] |
| gates >= 0.12 | **zero trades** — the model never disagrees with the book that much |

A VIX1D-implied lognormal is a **worse** forecaster than the Kalshi price, and
trading the disagreements loses about 7c a contract. This is the same shape as
every forecasting attempt in these repos: the market's reliability is what
cannot be beaten.

`KXINX` itself is `quadratic`, so a maker fill there would pay nothing — the
execution advantage is available. It does not help, because there is no edge to
execute.

## What is rejected, and what is not

**Rejected:** a flat lognormal driven by a single implied-vol number (VIX1D).

**NOT tested:** the full SPX option chain's risk-neutral density. Index put
skew is large and persistent, and a one-number vol model throws all of it away,
so a skewed density would price the downside brackets materially differently.
That is the only version worth reopening, and it needs an option chain this
project does not currently pull.

## Three design facts that cost a run each

- **`KXINX` opens 03:30Z and closes 21:00Z the same day.** There is no
  prior-close instant at which the market is listed, so a "price it at
  yesterday's close" design is impossible. The pricing mark is 13:30Z on the
  settlement day — inside the market's life and still before the 14:30Z open.
- **Same-day VIX1D is lookahead.** It is computed from that day's option prices
  and published after the close. The inputs are the PREVIOUS session's SPX and
  VIX1D closes, both published at 21:00Z the day before, which is also why
  `TAU` is one trading day with `S_0` at the prior close: the overnight gap
  belongs inside the move VIX1D is quoting.
- **A bid of 0.0000 is NOT the empty-book sentinel here.** Overnight the
  brackets are genuinely **one-sided** — a real ask with nothing behind it.
  Treating a zero bid as absent discarded every bracket on 29 straight days and
  the run reported nothing. An earlier probe that called 40 of 40 days
  "priceable" had counted *has an ask*, not *has a two-sided quote*; the two
  are different questions and only the first is true overnight. The consequence
  for the strategy is structural: **only the buy leg is executable**, so the
  scorer trades the ask alone rather than assuming a mid it cannot transact at.
