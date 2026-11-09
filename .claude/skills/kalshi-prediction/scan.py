#!/usr/bin/env python3
"""
Kalshi market scan for the kalshi-prediction skill: public V2 data only, no keys, no orders.

Lists open markets in the chosen categories and keeps only those that pass the liquidity and
runway filters. Every number it prints is a field from the API at the printed timestamp, so the
analysis that follows starts from real prices, not remembered ones.

    python3 scan.py --categories "Climate and Weather,Economics" --min-oi 1000 --max-spread 0.05 \
        --min-hours 6 --max-hours 168 --limit 40 [--json out.json]

Open interest in dollars = open_interest_fp (contracts) x notional_value_dollars ($1 on Kalshi).
Spread = yes_ask - yes_bid. A side with no orders reads 0.00 bid / 1.00 ask and is excluded.
"""
import argparse
import json
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone

BASE = "https://api.elections.kalshi.com/trade-api/v2"


def get(path, **q):
    url = f"{BASE}{path}?{urllib.parse.urlencode({k: v for k, v in q.items() if v is not None})}"
    for attempt in range(7):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "kalshi-prediction-skill"})
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except Exception as e:  # rate limit or network: back off, then give up loudly
            if attempt == 6:
                raise RuntimeError(f"GET {url} failed: {e}")
            time.sleep(min(60, 3 * 2 ** attempt))


def paged(path, key, **q):
    cursor = None
    while True:
        d = get(path, cursor=cursor, limit=200, **q)
        yield from d.get(key, [])
        cursor = d.get("cursor")
        if not cursor:
            return
        time.sleep(1.0)   # public API is rate-limited


def f(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--categories", default="Climate and Weather,Economics,Politics,Elections",
                    help="comma-separated Kalshi series categories (exact words from /series)")
    ap.add_argument("--series", default="", help="comma-separated series tickers; overrides --categories")
    ap.add_argument("--min-oi", type=float, default=1000.0, help="minimum open interest, dollars")
    ap.add_argument("--max-spread", type=float, default=0.05, help="maximum yes ask - yes bid, dollars")
    ap.add_argument("--min-hours", type=float, default=1.0, help="minimum hours to close (the three-phase runway)")
    ap.add_argument("--max-hours", type=float, default=24 * 30, help="maximum hours to close")
    ap.add_argument("--limit", type=int, default=50)
    ap.add_argument("--json", help="also write the rows here")
    a = ap.parse_args()

    now = datetime.now(timezone.utc)
    cats = {c.strip().lower() for c in a.categories.split(",") if c.strip()}
    wanted = {s.strip().upper() for s in a.series.split(",") if s.strip()}
    lo, hi = now.timestamp() + a.min_hours * 3600, now.timestamp() + a.max_hours * 3600
    rows, seen, dropped = [], 0, {"no two-sided book": 0, "open interest": 0, "spread": 0, "runway": 0}
    series = set()
    # Open events carry their category and, nested, their markets: far fewer pages than /markets,
    # which is dominated by sports props and multi-leg combos.
    def markets():
        if wanted:   # named series: one cheap listing each
            for s in sorted(wanted):
                series.add(s)
                for m in paged("/markets", "markets", series_ticker=s, status="open"):
                    c = datetime.fromisoformat(m["close_time"].replace("Z", "+00:00")).timestamp()
                    if lo <= c <= hi:
                        yield s, m
            return
        for e in paged("/events", "events", status="open", with_nested_markets="true"):
            s = e.get("series_ticker") or e["event_ticker"].split("-")[0]
            if wanted and s not in wanted:
                continue
            if not wanted and (e.get("category") or "").lower() not in cats:
                continue
            series.add(s)
            for m in e.get("markets") or []:
                if m.get("status") not in ("active", "open"):
                    continue
                c = datetime.fromisoformat(m["close_time"].replace("Z", "+00:00")).timestamp()
                if lo <= c <= hi:
                    yield s, m
    for s, m in markets():
            seen += 1
            bid, ask = f(m.get("yes_bid_dollars")), f(m.get("yes_ask_dollars"))
            if bid is None or ask is None or bid <= 0 or ask >= 1 or ask <= bid:
                dropped["no two-sided book"] += 1; continue
            oi = (f(m.get("open_interest_fp")) or 0) * (f(m.get("notional_value_dollars")) or 1.0)
            if oi < a.min_oi:
                dropped["open interest"] += 1; continue
            if ask - bid > a.max_spread + 1e-9:
                dropped["spread"] += 1; continue
            close = datetime.fromisoformat(m["close_time"].replace("Z", "+00:00"))
            hours = (close - now).total_seconds() / 3600
            if hours < a.min_hours or hours > a.max_hours:
                dropped["runway"] += 1; continue
            rows.append({
                "ticker": m["ticker"], "title": m.get("title"), "yes_sub_title": m.get("yes_sub_title"),
                "series": s, "yes_bid": bid, "yes_ask": ask, "mid": round((bid + ask) / 2, 4), "spread": round(ask - bid, 4),
                "open_interest_usd": round(oi, 2), "volume_24h": f(m.get("volume_24h_fp")),
                "close_time": m["close_time"], "hours_to_close": round(hours, 2),
                "strike_type": m.get("strike_type"), "floor_strike": m.get("floor_strike"), "cap_strike": m.get("cap_strike"),
                "rules_primary": m.get("rules_primary"),
            })
    rows.sort(key=lambda r: -r["open_interest_usd"])
    rows = rows[: a.limit]
    print(f"Kalshi scan at {now.isoformat(timespec='seconds')}: {len(series)} series, {seen} open markets, "
          f"{len(rows)} kept; dropped: " + ", ".join(f"{k} {v}" for k, v in dropped.items()))
    print(f"{'ticker':40} {'bid':>5} {'ask':>5} {'impl%':>6} {'OI $':>10} {'hrs':>7}  outcome")
    for r in rows:
        print(f"{r['ticker']:40} {r['yes_bid']*100:4.0f}c {r['yes_ask']*100:4.0f}c {r['mid']*100:5.1f}% "
              f"{r['open_interest_usd']:10,.0f} {r['hours_to_close']:7.1f}  {r['yes_sub_title'] or r['title']}")
    if a.json:
        with open(a.json, "w") as fh:
            json.dump({"scanned_at": now.isoformat(), "filters": vars(a), "markets": rows}, fh, indent=2)


if __name__ == "__main__":
    main()
