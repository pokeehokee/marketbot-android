# Commodity range longshot: the evidence base

Committed so the numbers in `CLAUDE.md` can be audited, rather than resting on an ad-hoc command
nobody can review. Every script here is **read-only** and unauthenticated: they call the public
Kalshi market, candlestick and orderbook endpoints only, hold no credentials, and place nothing.

```bash
python3 commodity_sample.py          # builds commodity_sample.jsonl beside itself (slow, ~1h/6 series)
python3 score_commodity.py           # scores it; run from the directory holding the .jsonl
python3 commodity_leads.py           # re-prices the sample at 30m/15m/5m, paired
python3 score_leads.py               # the paired lead comparison
python3 book_collector.py            # records live order books forward (see DEPLOY.md)
python3 book_collector.py --stats    # what the collector has gathered so far
python3 -m unittest discover -p 'test_*.py' -v    # the payload rules, frozen
```

## `book_collector.py`: the only way the lead question gets answered

`commodity_leads.py` established that the lead curve **below 60 minutes is not measurable from
candles**: the history is sparse, so every nominal lead resolves to "the last quote at or before the
mark" (median candle age at the 60m mark: 90 minutes), and the 15m and 5m leads resolve to the
identical candle on 100% of markets. Zero lookahead still held — nothing was biased forward — the
question simply cannot be asked of that data.

Kalshi serves historical candles but **not historical order books**, so the only way to get them is
to write them down as they happen. That is what the collector does: top of book for every market
within 120 minutes of its close, every 60 seconds, plus full ladders for the cheap band. It needs a
machine that stays up — see `DEPLOY.md`. A day missed is a day that cannot be recovered.

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
  `book_at()` rejects `ask >= 1.0`, `ask <= 0.0` and any crossed book, treating them as ABSENT. In
  the **orderbook** payload the same thing appears as an *empty list* (`yes_dollars: []`) rather
  than a missing key, which the docs do not spell out; the collector stores NULL for both.

Two more that only the collector depends on, both verified live on 2026-11-16:

- **The orderbook carries only bids.** `orderbook_fp.yes_dollars` are YES bids, `no_dollars` are NO
  bids, as `[price_dollars, count_fp]` strings. So **the YES ask is `1 − best NO bid`** and the size
  resting at that ask is that NO level's size. Checked against the market object twice:
  KXGOLDD-26NOV1617-T4087 gave 1−0.99 = 0.01 / 537 against `yes_ask_dollars 0.0100` /
  `yes_ask_size_fp 537.00`, and KXWTI-26NOV1614-T88.49 gave 1−0.59 = 0.41 / 204 against 0.41 / 204.
- **`tickers=A,B` on the batch orderbook endpoint is a trap.** It is accepted and returns **one**
  entry whose `ticker` is the literal string `"A,B"` with both sides empty — indistinguishable from
  a market with nothing resting. It does not fail; it fabricates empty books for every market asked
  about. The parameter must be repeated (`tickers=A&tickers=B`), and entries must be keyed by the
  ticker the response names, never paired by input order.

## What the scorer does

Sells the cheap side at the resting price: sell YES at the ask when YES is cheap, or buy YES at the
bid when YES is dear (= selling the cheap NO at `1 - bid`). Spread gate 5c. A seller of the cheap
side at `p` collects `p` and pays 1 if that side comes in, so `EV/contract = p - P(side)` and
"implied" losses are the sum of `p`. Resting fills pay no fee on these series. **CIs are bootstrapped
clustered by close-date**, because a commodity range settles off one price move and a day's strikes
win or lose together — per-fill CIs on this instrument are meaningless.
