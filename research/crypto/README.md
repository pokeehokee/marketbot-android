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

## The `volume` field on these markets is NOT populated, and it voided a claim

A first pass concluded the cheap band was unfillable — "132 of 200 open
`KXBTCD` markets sit at <=5c with median 24h volume of zero" — and called that
probably fatal. **It was an artifact.** The control that should have been run
first: of 200 *settled* `KXBTCD` markets, **zero** report `volume > 0`,
including near-the-money strikes that demonstrably traded (this account's own
deep-ITM ladder filled 10,556 contracts on these series). The field reads zero
whether or not the market traded — the same shape as `yes_bid` coming back
`None`, which this repo already documents.

Read liquidity from the **candlestick** instead (`volume_fp`,
`open_interest_fp`), which the sampler stores per quote. On 2,791 cheap-band
quotes at the 30-minute mark:

| | |
| --- | --- |
| open interest | median **3,754** contracts, p90 20,835, max 94,736 |
| quotes with any open interest | **85%** |
| 1-minute candle volume | median 0, p90 **572**, max 6,386 |

Open interest rises with price across the band (1,006 at 0.010-0.015 to 16,755
at 0.035-0.055). The cheap band is **deeply traded**, not dead.

Two lessons, both of which cost a wrong conclusion here:

- **Never report a liquidity figure without first checking the field populates
  on a case known to have traded.** A zero that means "not reported" and a zero
  that means "no trades" are indistinguishable downstream.
- The same invalid read was applied to the long-horizon series
  (`KXBTCY`, `KXBTCMAXMON`, `KXBTCHITBY`, ...), reported as "60 open markets, 50
  cheap, total 24h volume 0". **That is unmeasured, not zero.** What does hold
  for the *daily* crypto series (`BTCD`, `BTC`, `ETHD`, `KXBTCMAXD`) is checked
  two ways and solid: zero markets AND zero events of any status — listed
  shells, the same pattern weatherbot records for international weather series.

## The long-horizon series are the opposite of dead -- and structurally unmeasurable

Re-measured with the candlestick (the market object's `open_interest` and
`volume_24h` are BOTH dead -- zero even on heavily traded open `KXBTCD`
markets, so neither can be used for anything):

| series | cheap-band markets | OI median | OI max | closes |
| --- | --- | --- | --- | --- |
| `KXBTCY` | 22 | **483,952** | 546,122 | 2027-01-01 |
| `KXBTC2026200` | 1 | **5,312,824** | — | 2027-01-01 |
| `KXBTCMAX150` | 1 | 874,937 | — | 2027-01-01 |
| `KXBTCMAXY` | 7 | 168,143 | 550,206 | 2027-01-01 |
| `KXBTCMAXMON` | 7 | 118,209 | 294,383 | 2026-12-01 |

An earlier pass called these "60 open markets, 50 cheap, total 24h volume 0".
They are among the most heavily held markets on the exchange, and they are
exactly the shape the >=20x question wants: a large multiplier with months of
runway, so the required move is possible rather than near-impossible (the
hourly ladder needs a $738 median move against a $117 median realised one).

**And they cannot be measured, now or ever.** A 14-month one-touch yields one
observation per horizon, and every `BTC-above-X-by-2027` market settles off the
SAME single price path, so the effective sample is n ~ 1 per year rather than 22
markets. The bar this repo applies everywhere -- >= 30 losses, clustered by
whatever drives the outcome -- is unreachable when the cluster IS the sample.
Buying them is a view, not a measured edge; that distinction is the whole
difference between this repo's two live books and everything it has closed.

So the measurable end is the short end, which is what `crypto_tail_sample.py`
covers. Anyone extending this should NOT try to validate a long-horizon tail
from settled history: there is no history to validate it with.
