# Commodity range longshot: the evidence base

Committed so the numbers in `CLAUDE.md` can be audited, rather than resting on an ad-hoc command
nobody can review. Both scripts are **read-only** and unauthenticated: they call the public Kalshi
market and candlestick endpoints only, hold no credentials, and place nothing.

```bash
python3 commodity_sample.py          # builds commodity_sample.jsonl beside itself (slow, ~1h/6 series)
python3 score_commodity.py           # scores it; run from the directory holding the .jsonl
```

`commodity_sample.jsonl` is not committed (it is rebuildable and large). The sample is appended, so a
re-run adds rows; `score_commodity.py` de-duplicates on `(series, ticker)` and a partial series is
therefore safe to leave in place — but **do not read a pooled score while a series is mid-pull**: a
half-pulled series biases date coverage, which is the partial-extension trap the weatherbot repo
records.

## The two rules the pricing depends on

- **Zero lookahead.** The candle used is the last whose period ENDS at or before `close - lead`, and
  the request window itself ends at that mark. A candle merely *containing* the mark leaks up to a
  minute of the future.
- **The empty-book sentinel.** Kalshi reports a side with no resting orders as `yes_ask 1.00` /
  `yes_bid 0.00` — identical to a closed market. Read as a price it manufactures a huge fake edge.
  `book_at()` rejects `ask >= 1.0`, `ask <= 0.0` and any crossed book, treating them as ABSENT.

## What the scorer does

Sells the cheap side at the resting price: sell YES at the ask when YES is cheap, or buy YES at the
bid when YES is dear (= selling the cheap NO at `1 - bid`). Spread gate 5c. A seller of the cheap
side at `p` collects `p` and pays 1 if that side comes in, so `EV/contract = p - P(side)` and
"implied" losses are the sum of `p`. Resting fills pay no fee on these series. **CIs are bootstrapped
clustered by close-date**, because a commodity range settles off one price move and a day's strikes
win or lose together — per-fill CIs on this instrument are meaningless.
