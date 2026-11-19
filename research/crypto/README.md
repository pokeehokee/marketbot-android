# Is the >=20x tail of Kalshi's hourly crypto ladder worth BUYING?

The question an operator actually asks: put $1 on something paying 20x or more,
and win often enough to come out ahead. It is the **opposite side** of the trade
the app already runs — buying YES at 0.94 and selling NO at 0.06 are the same
payoff, so the deep-ITM ladder IS the short-the-cheap-tail trade on these
markets, and it earns +1.77c/contract over 10,556 live contracts.

## What is here

| file | role |
| --- | --- |
| `crypto_tail_sample.py` | prices every cheap-tail strike of each settled `KXBTCD` hour at a fixed lead with zero lookahead, into `crypto_tail.db` |
| `score_crypto_tail.py` | realised wins vs implied, EV per contract, CIs clustered by event hour |
| `test_crypto_tail.py` | 15 stdlib `unittest` tests freezing the rules below |

Both scripts are **unauthenticated and read-only**: the settled-market listing
and the candlesticks are public, `assert_read_only()` whitelists only those two
path prefixes and refuses anything order- or portfolio-shaped, and no
credentials are read. Run them with no setup:

```bash
python3 research/crypto/crypto_tail_sample.py 32 1800 3   # days, lead_s, every-Nth-hour
python3 research/crypto/score_crypto_tail.py 1800
python3 -m unittest discover -s research/crypto -p 'test_*.py'
```

## The rules it depends on, and why each exists

- **Zero lookahead.** The candle used is the last whose period *ENDS* at or
  before `close - lead`, and the request window itself ends there. A candle
  merely *containing* the mark leaks the future; that is the shape of
  weatherbot's "13:00 prices that actually spanned 04:45 to 13:45" near-miss.
- **The empty-book sentinel is never a price.** `yes_ask 1.00` / `yes_bid 0.00`
  means "no orders on that side" — the same shape a *closed* market quotes.
  Read as a price it once made a stop-loss replay report a 97% trigger rate at
  −72c/contract.
- **A buyer of NO pays `1 - yes_bid`.** So `yes_bid = 0` means the NO side costs
  a dollar and is unbuyable, not a 100x bargain. Inverting this invents an edge
  out of an empty book.
- **The fee is per ORDER, rounded up.** A $1 stake at 0.02 buys 50 contracts and
  pays `ceil(0.07 x 50 x 0.02 x 0.98)` = **$0.07**, 7% of stake. A per-contract
  fee understates a cheap bet's cost several-fold.
- **Every CI clusters by event hour.** All strikes of one BTC hour settle off a
  single price move and are not independent draws.
- **429 is retried inside `get()`.** A rate-limited *listing* silently yields an
  empty day, which is indistinguishable from "that day had no markets".

## The grading control, which is what makes the result believable

The finding is "wins far below implied", and a grading bug produces exactly the
same zero. So every quote's exchange `result` is checked against where BTC
actually finished (Coinbase 1m): **830 of 831 agreed (99.88%)**, the one
exception being a strike **30 cents** from the close — BRTI-vs-Coinbase basis,
not a bug.

> The first control was mis-specified and proved nothing. It reused the cheap
> band's `$100-$1500` distance-from-spot window, but a 0.30-0.70 strike sits
> *within* ~$100 of spot, so it could not find any: 4 hours scanned, 2 quotes.
> **A control that cannot observe its own target is not a control.** A
> mid-price run needs `NEAR = 0`.
