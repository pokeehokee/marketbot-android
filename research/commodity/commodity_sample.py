#!/usr/bin/env python3
"""Price every settled commodity-range market at two leads with ZERO lookahead, and score selling
the cheap tail at the price a resting order would get.

Zero lookahead: the candle used is the last whose period ENDS at or before (close - lead), and the
request window itself ends there.
Empty-book sentinel: Kalshi reports a side with no orders as yes_ask 1.00 / yes_bid 0.00, the same
as a closed market, so those are treated as ABSENT, never as a price.
"""
import json, urllib.request, urllib.error, time, datetime, os, sys

B = "https://api.elections.kalshi.com/trade-api/v2"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "commodity_sample.jsonl")
SERIES = "KXBRENTD KXBRENTW KXWTI KXWTIW KXNATGASD KXNATGASW KXGOLDD KXGOLDW KXSILVERD KXSILVERW KXCOPPERD KXCOPPERW".split()
LEADS = {"60m": 3600, "720m": 43200}

def get(u, tries=6):
    for i in range(tries):
        try:
            return json.load(urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": "research"}), timeout=45))
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 502, 503, 504): time.sleep(min(30, 3 * 2 ** i)); continue
            return {"_err": f"HTTP {e.code}"}
        except Exception as e:
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
            f"?start_ts={mark - 60*60*8}&end_ts={mark}&period_interval=60")
    for c in reversed(d.get("candlesticks") or []):
        if (c.get("end_period_ts") or 0) > mark: continue
        yb, ya = c.get("yes_bid") or {}, c.get("yes_ask") or {}
        bid, ask = f(yb.get("close_dollars")), f(ya.get("close_dollars"))
        if bid is None or ask is None: continue
        if ask >= 1.0 or ask <= 0.0 or bid < 0.0 or bid > ask: continue   # empty / closed / crossed
        return bid, ask, c.get("end_period_ts")
    return None

n = 0
with open(OUT, "a") as fh:
    for s in SERIES:
        cursor, seen = None, 0
        while True:
            u = f"{B}/markets?series_ticker={s}&status=settled&limit=1000"
            if cursor: u += f"&cursor={cursor}"
            d = get(u)
            ms = d.get("markets") or []
            for m in ms:
                if m.get("result") not in ("yes", "no") or not m.get("close_time"): continue
                ct = ts(m["close_time"])
                row = {"series": s, "ticker": m["ticker"], "close": m["close_time"],
                       "result": m["result"], "volume": f(m.get("volume_fp")) or 0.0,
                       "oi": f(m.get("open_interest_fp")) or 0.0}
                any_lead = False
                for lab, lead in LEADS.items():
                    b = book_at(s, m["ticker"], ct, lead)
                    time.sleep(0.12)
                    if b:
                        row[lab] = {"bid": b[0], "ask": b[1], "at": b[2]}
                        any_lead = True
                if any_lead:
                    fh.write(json.dumps(row) + "\n"); n += 1
                seen += 1
            fh.flush()
            cursor = d.get("cursor") or ""
            print(f"  {s}: {seen} scanned, {n} priced", flush=True)
            if not cursor or not ms or seen >= 600: break
print(f"done: {n} priced rows -> {OUT}", flush=True)
