#!/usr/bin/env python3
"""Re-price the EXISTING commodity sample at shorter leads, with zero lookahead.

Why paired: the market list comes from commodity_sample.jsonl, not a fresh enumeration, so every
market is priced at 60m AND at each short lead. An unpaired lead curve is meaningless here -- a
market only appears at a short lead if it was still quoted there, which selects on outcome.

Zero lookahead (identical rule to commodity_sample.py): the candle used is the last whose period
ENDS at or before (close - lead), and the request window itself ends at that mark.
Empty-book sentinel: Kalshi reports a side with no resting orders as yes_ask 1.00 / yes_bid 0.00,
the same shape a closed market quotes, so it is treated as ABSENT and never as a price.

Resumable: tickers already in the output file are skipped, so a restart costs nothing.
"""
import json, urllib.request, urllib.error, time, datetime, os

B = "https://api.elections.kalshi.com/trade-api/v2"
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "commodity_sample.jsonl")
OUT = os.path.join(HERE, "commodity_leads.jsonl")

# 60m is the shipped window. 5m is not tradeable (no time to rest an order); it is sampled to show
# where the edge peaks and whether it dies before close.
LEADS = {"30m": 1800, "15m": 900, "5m": 300}
WORKERS = 6          # Kalshi tolerates this on the public candlestick endpoint; get() still backs off
MAX_CHEAP = 0.40     # only markets the strategy could have quoted at 60m -- see the note below


def get(u, tries=6):
    for i in range(tries):
        try:
            return json.load(urllib.request.urlopen(
                urllib.request.Request(u, headers={"User-Agent": "research"}), timeout=45))
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 502, 503, 504):
                time.sleep(min(30, 3 * 2 ** i)); continue
            return {"_err": f"HTTP {e.code}"}
        except Exception:
            time.sleep(2 * (i + 1))
    return {"_err": "retries"}


def f(x):
    try: return float(x)
    except (TypeError, ValueError): return None


def ts(s):
    return int(datetime.datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp())


def book_at(series, ticker, close_ts, lead_s):
    mark = close_ts - lead_s
    d = get(f"{B}/series/{series}/markets/{ticker}/candlesticks"
            f"?start_ts={mark - 60 * 60 * 8}&end_ts={mark}&period_interval=60")
    for c in reversed(d.get("candlesticks") or []):
        if (c.get("end_period_ts") or 0) > mark: continue
        yb, ya = c.get("yes_bid") or {}, c.get("yes_ask") or {}
        bid, ask = f(yb.get("close_dollars")), f(ya.get("close_dollars"))
        if bid is None or ask is None: continue
        if ask >= 1.0 or ask <= 0.0 or bid < 0.0 or bid > ask: continue   # empty / closed / crossed
        return bid, ask, c.get("end_period_ts")
    return None


# The markets to re-price. NOT the whole sample: only those whose cheap side was inside the
# strategy's reachable band at 60m (<= 0.40, which covers the shipped 0.02-0.25 and the proposed
# extension). That is the decision-relevant population -- "for the markets I would have quoted at
# 60m, is it better to wait and quote closer?" Markets that only become cheap later are a separate,
# additive question, and pricing all 5,947 at three leads serially ran at ~20 hours.
def cheap_at(b):
    if not b: return None
    bid, ask = b["bid"], b["ask"]
    if ask - bid > 0.05: return None
    if 0.02 <= ask <= MAX_CHEAP: return ask
    if 0.02 <= 1 - bid <= MAX_CHEAP: return 1 - bid
    return None


src = {}
for line in open(SRC):
    try: r = json.loads(line)
    except Exception: continue
    src[(r["series"], r["ticker"])] = r

done = set()
if os.path.exists(OUT):
    for line in open(OUT):
        try: r = json.loads(line)
        except Exception: continue
        done.add((r["series"], r["ticker"]))

todo = [k for k, r in src.items() if k not in done and cheap_at(r.get("60m")) is not None]
print(f"{len(src)} in the sample, {len(todo)} reachable at 60m and not yet priced "
      f"({len(done)} already done)", flush=True)

import threading
from concurrent.futures import ThreadPoolExecutor

lock = threading.Lock()
written = [0]


def one(key, fh):
    r = src[key]
    ct = ts(r["close"])
    row = {"series": r["series"], "ticker": r["ticker"], "close": r["close"],
           "result": r["result"], "volume": r.get("volume", 0.0), "oi": r.get("oi", 0.0)}
    if r.get("60m"): row["60m"] = r["60m"]
    for lab, lead in LEADS.items():
        b = book_at(r["series"], r["ticker"], ct, lead)
        if b: row[lab] = {"bid": b[0], "ask": b[1], "at": b[2]}
    with lock:
        fh.write(json.dumps(row) + "\n")
        written[0] += 1
        if written[0] % 100 == 0:
            fh.flush()
            print(f"  {written[0]}/{len(todo)}", flush=True)


with open(OUT, "a") as fh:
    with ThreadPoolExecutor(max_workers=WORKERS) as pool:
        list(pool.map(lambda k: one(k, fh), todo))
print(f"done: {written[0]} rows -> {OUT}", flush=True)
