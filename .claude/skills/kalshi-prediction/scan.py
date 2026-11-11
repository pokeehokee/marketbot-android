#!/usr/bin/env python3
"""
Kalshi market scan for the kalshi-prediction skill: public V2 data only, no keys, no orders.

    python3 scan.py --categories "Climate and Weather,Economics,Politics" --min-hours 6 --max-hours 720 --json scan.json
    python3 scan.py --series KXHIGHCHI,KXCPI,KXFEDDECISION            # named series, fastest
    python3 scan.py --refresh-catalog                                 # rebuild the active-series catalog now

Discovery. Kalshi lists thousands of series per category, most of them dead one-offs, and the
public API rate-limits. So once a day the scan walks the open events (paced, resumable) and caches
which series in each category have open markets; later runs scan only those series, one cheap
listing each. If the API refuses even that, it falls back to DEFAULT_SERIES and says so.

Filters. Open interest >= --min-oi dollars (open_interest_fp x notional_value_dollars), spread <=
--max-spread, a two-sided book (an empty side reads 0.00/1.00), implied probability inside
[--min-prob, --max-prob] (tails leave no room for edge after fees), time to close inside the window,
and at most --per-event strikes per event (closest to 50%).

Every row carries the numbers the analysis needs, all from API fields at the printed timestamp:
the plain-language condition, depth at each side, the taker fee and breakeven probability for
buying YES at the ask and NO at 1 - bid, and anomaly flags (volume against open interest, move
since the previous close).
"""
import argparse
import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

BASE = "https://api.elections.kalshi.com/trade-api/v2"
CACHE = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "kalshi-prediction"
CATALOG_TTL_H = 24
# Used when discovery is rate-limited: the liquid recurring series of each category.
DEFAULT_SERIES = {
    "climate and weather": ["KXHIGHNY", "KXHIGHCHI", "KXHIGHMIA", "KXHIGHLAX", "KXHIGHAUS", "KXHIGHDEN", "KXHIGHPHIL",
                            "KXHIGHTDC", "KXHIGHTBOS", "KXHIGHTATL", "KXHIGHTDAL", "KXHIGHTHOU", "KXHIGHTSFO", "KXHIGHTSEA",
                            "KXHIGHTPHX", "KXHIGHTLV", "KXHIGHTMIN", "KXHIGHTOKC", "KXHIGHTSATX", "KXHIGHTNOLA"],
    "economics": ["KXFED", "KXFEDDECISION", "KXCPI", "KXCPIYOY", "KXCPICORE", "KXPAYROLLS", "KXU3", "KXGDP",
                  "KXJOBLESSCLAIMS", "KXAAAGASW", "KXAAAGASM", "KXRATECUTCOUNT", "KXMORTGAGERATE", "KXPCE"],
    "financials": ["KXINX", "KXINXU", "KXNASDAQ100", "KXBTCD", "KXETHD", "KXWTI", "KXGOLDD", "KXEURUSD", "KXUSDJPY"],
    "politics": ["KXTRUMPAPPROVAL", "KXSHUTDOWN", "KXEOWEEK"],
    "elections": [],
}
PACE_S = 0.35


class RateLimited(RuntimeError):
    pass


def get(path, **q):
    url = f"{BASE}{path}?{urllib.parse.urlencode({k: v for k, v in q.items() if v is not None})}"
    for attempt in range(6):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "kalshi-prediction-skill"})
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return {}
            if e.code != 429 and e.code < 500:
                raise RuntimeError(f"GET {url}: HTTP {e.code}")
            wait = float(e.headers.get("Retry-After") or 0) or min(60, 4 * 2 ** attempt)
        except Exception:
            wait = min(60, 4 * 2 ** attempt)
        time.sleep(wait)
    raise RateLimited(f"GET {url} kept failing (rate limit or network)")


def paged(path, key, pace=PACE_S, state=None, **q):
    """Pages of a listing. With [state] (a dict), the cursor is kept so a rate-limited walk can resume."""
    cursor = (state or {}).get("cursor")
    while True:
        d = get(path, cursor=cursor, limit=200, **q)
        yield from d.get(key, [])
        cursor = d.get("cursor")
        if state is not None:
            state["cursor"] = cursor
        if not cursor:
            return
        time.sleep(pace)


def f(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def iso(ts):
    return datetime.fromisoformat(ts.replace("Z", "+00:00"))


# ------------------------------------------------------------------ catalog

def load_catalog():
    p = CACHE / "catalog.json"
    if p.exists():
        try:
            return json.loads(p.read_text())
        except ValueError:
            pass
    return {}


def save_catalog(c):
    CACHE.mkdir(parents=True, exist_ok=True)
    (CACHE / "catalog.json").write_text(json.dumps(c, indent=1))


def refresh_catalog(log):
    """Walk the open events once (paced, resumable) and record, per category, the series with open markets."""
    c = load_catalog()
    walk = c.get("walk") or {}
    if not walk.get("cursor"):
        walk = {"started": datetime.now(timezone.utc).isoformat(), "cursor": None, "series": {}}
    pages = 0
    try:
        for e in paged("/events", "events", pace=1.0, state=walk, status="open"):
            s = e.get("series_ticker") or e["event_ticker"].split("-")[0]
            walk["series"][s] = (e.get("category") or "").lower()
            pages += 1
            if pages % 400 == 0:
                c["walk"] = walk; save_catalog(c)
    except RateLimited as ex:
        c["walk"] = walk; save_catalog(c)
        log(f"catalog walk paused by the rate limit after {len(walk['series'])} series; re-run to resume ({ex})")
        return False
    by_cat = defaultdict(list)
    for s, cat in walk["series"].items():
        by_cat[cat].append(s)
    c = {"built": datetime.now(timezone.utc).isoformat(), "categories": {k: sorted(v) for k, v in by_cat.items()}}
    save_catalog(c)
    log(f"catalog rebuilt: {len(walk['series'])} active series across {len(by_cat)} categories")
    return True


def series_for(categories, refresh, log):
    c = load_catalog()
    fresh = c.get("built") and (datetime.now(timezone.utc) - iso(c["built"])).total_seconds() < CATALOG_TTL_H * 3600
    if refresh or not fresh:
        if refresh_catalog(log):
            c = load_catalog()
            fresh = True
    out, source = [], {}
    for cat in categories:
        k = cat.lower()
        if fresh or (c.get("categories") or {}).get(k):
            got = (c.get("categories") or {}).get(k, [])
            out += got; source[cat] = f"catalog ({len(got)} active series, built {c.get('built', '?')[:16]})"
        else:
            got = DEFAULT_SERIES.get(k, [])
            out += got; source[cat] = f"DEFAULT_SERIES fallback ({len(got)}): catalog unavailable, discovery was rate-limited"
    return sorted(set(out)), source


# ------------------------------------------------------------------ per market

def kalshi_fee(price, n=1):
    """Taker fee in dollars per contract for an order of n at price: ceil(0.07 n p (1-p) * 100) / 100 / n."""
    return math.ceil(round(0.07 * n * price * (1 - price) * 100, 6)) / 100 / n


def condition(m):
    """What YES needs, from Kalshi's strike fields. The rules text is authoritative; this is a reading aid."""
    st, lo, hi = m.get("strike_type"), m.get("floor_strike"), m.get("cap_strike")
    sub = m.get("yes_sub_title") or ""
    if st == "greater" and lo is not None:
        return f"> {lo} ({sub})"
    if st == "greater_or_equal" and lo is not None:
        return f">= {lo} ({sub})"
    if st == "less" and hi is not None:
        return f"< {hi} ({sub})"
    if st == "less_or_equal" and hi is not None:
        return f"<= {hi} ({sub})"
    if st == "between" and lo is not None and hi is not None:
        return f"{lo} to {hi} inclusive ({sub})"
    return sub or (m.get("title") or "")


def row(s, m, now, n_for_fee):
    bid, ask = f(m.get("yes_bid_dollars")), f(m.get("yes_ask_dollars"))
    mid = (bid + ask) / 2
    oi = (f(m.get("open_interest_fp")) or 0) * (f(m.get("notional_value_dollars")) or 1.0)
    vol = f(m.get("volume_24h_fp")) or 0.0
    prev = f(m.get("previous_price_dollars"))
    last = f(m.get("last_price_dollars"))
    no_ask = 1 - bid
    fee_y, fee_n = kalshi_fee(ask, n_for_fee), kalshi_fee(no_ask, n_for_fee)
    contracts_oi = f(m.get("open_interest_fp")) or 0.0
    flags = []
    vol_ratio = vol / contracts_oi if contracts_oi > 0 else None
    if vol_ratio is not None and vol_ratio >= 0.5:
        flags.append(f"volume {vol_ratio:.1f}x open interest")
    move = None
    if prev and prev > 0 and last is not None:
        move = last - prev
        if abs(move) >= 0.08:
            flags.append(f"moved {move*100:+.0f}c since previous close")
    hours = (iso(m["close_time"]) - now).total_seconds() / 3600
    return {
        "ticker": m["ticker"], "event": m.get("event_ticker"), "series": s, "title": m.get("title"),
        "condition": condition(m), "rules_primary": m.get("rules_primary"),
        "yes_bid": bid, "yes_ask": ask, "mid": round(mid, 4), "spread": round(ask - bid, 4),
        "yes_bid_size": f(m.get("yes_bid_size_fp")), "yes_ask_size": f(m.get("yes_ask_size_fp")),
        "open_interest_usd": round(oi, 2), "volume_24h": vol, "last_price": last, "previous_price": prev,
        "buy_yes": {"price": ask, "fee": round(fee_y, 4), "breakeven_p": round(ask + fee_y, 4)},
        "buy_no": {"price": round(no_ask, 4), "fee": round(fee_n, 4), "breakeven_p": round(no_ask + fee_n, 4)},
        "close_time": m["close_time"], "expected_expiration_time": m.get("expected_expiration_time"),
        "hours_to_close": round(hours, 2), "flags": flags,
        "anomaly_score": round((vol_ratio or 0) + (abs(move) * 10 if move else 0), 3),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--categories", default="Climate and Weather,Economics,Politics,Elections",
                    help="comma-separated Kalshi categories (exact words from /series)")
    ap.add_argument("--series", default="", help="comma-separated series tickers; skips discovery")
    ap.add_argument("--refresh-catalog", action="store_true", help="rebuild the active-series catalog first")
    ap.add_argument("--min-oi", type=float, default=1000.0, help="minimum open interest, dollars")
    ap.add_argument("--max-spread", type=float, default=0.05, help="maximum yes ask - yes bid, dollars")
    ap.add_argument("--min-prob", type=float, default=0.10, help="minimum implied probability (mid)")
    ap.add_argument("--max-prob", type=float, default=0.90, help="maximum implied probability (mid)")
    ap.add_argument("--min-hours", type=float, default=6.0, help="minimum hours to close (the three-phase runway)")
    ap.add_argument("--max-hours", type=float, default=24 * 30, help="maximum hours to close")
    ap.add_argument("--per-event", type=int, default=3, help="strikes kept per event, closest to 50%%")
    ap.add_argument("--fee-contracts", type=int, default=5, help="order size the taker fee is computed for")
    ap.add_argument("--sort", choices=["oi", "anomaly", "close"], default="oi")
    ap.add_argument("--limit", type=int, default=40)
    ap.add_argument("--json", help="also write the rows here")
    a = ap.parse_args()
    log = lambda msg: print(msg, file=sys.stderr)

    now = datetime.now(timezone.utc)
    if a.series:
        series = sorted({s.strip().upper() for s in a.series.split(",") if s.strip()})
        source = {"--series": f"{len(series)} named"}
    else:
        series, source = series_for([c.strip() for c in a.categories.split(",") if c.strip()], a.refresh_catalog, log)
    lo, hi = int(now.timestamp() + a.min_hours * 3600), int(now.timestamp() + a.max_hours * 3600)
    dropped = defaultdict(int)
    kept, seen, failed = [], 0, []
    for s in series:
        try:
            markets = list(paged("/markets", "markets", series_ticker=s, status="open", min_close_ts=lo, max_close_ts=hi))
        except (RateLimited, RuntimeError) as e:
            failed.append(s); log(f"skip {s}: {e}"); continue
        time.sleep(PACE_S)
        for m in markets:
            seen += 1
            bid, ask = f(m.get("yes_bid_dollars")), f(m.get("yes_ask_dollars"))
            if bid is None or ask is None or bid <= 0 or ask >= 1 or ask <= bid:
                dropped["no two-sided book"] += 1; continue
            oi = (f(m.get("open_interest_fp")) or 0) * (f(m.get("notional_value_dollars")) or 1.0)
            if oi < a.min_oi:
                dropped["open interest"] += 1; continue
            if ask - bid > a.max_spread + 1e-9:
                dropped["spread"] += 1; continue
            mid = (bid + ask) / 2
            if mid < a.min_prob or mid > a.max_prob:
                dropped["implied probability outside band"] += 1; continue
            kept.append(row(s, m, now, a.fee_contracts))
    # At most --per-event strikes per event, those nearest 50%.
    by_event = defaultdict(list)
    for r in kept:
        by_event[r["event"]].append(r)
    rows = []
    for ev, rs in by_event.items():
        rs.sort(key=lambda r: abs(r["mid"] - 0.5))
        rows += rs[: a.per_event]
        dropped["more strikes in the same event"] += max(0, len(rs) - a.per_event)
    key = {"oi": lambda r: -r["open_interest_usd"], "anomaly": lambda r: -r["anomaly_score"], "close": lambda r: r["hours_to_close"]}[a.sort]
    rows.sort(key=key)
    rows = rows[: a.limit]

    print(f"Kalshi scan at {now.isoformat(timespec='seconds')}: {len(series)} series, {seen} open markets in the window, "
          f"{len(rows)} kept; dropped: " + ", ".join(f"{k} {v}" for k, v in dropped.items() if v))
    for k, v in source.items():
        print(f"  series source: {k}: {v}")
    if failed:
        print(f"  NOT SCANNED (rate limit or error): {', '.join(failed[:20])}{' ...' if len(failed) > 20 else ''}")
    print(f"{'ticker':34} {'bid':>4} {'ask':>4} {'mid%':>5} {'BE yes':>6} {'BE no':>6} {'askQ':>6} {'OI $':>9} {'hrs':>6}  condition / flags")
    for r in rows:
        print(f"{r['ticker']:34} {r['yes_bid']*100:3.0f}c {r['yes_ask']*100:3.0f}c {r['mid']*100:5.1f} "
              f"{r['buy_yes']['breakeven_p']*100:5.1f}% {r['buy_no']['breakeven_p']*100:5.1f}% {r['yes_ask_size'] or 0:6.0f} "
              f"{r['open_interest_usd']:9,.0f} {r['hours_to_close']:6.1f}  {r['condition']}"
              + (f"  [{'; '.join(r['flags'])}]" if r["flags"] else ""))
    if a.json:
        with open(a.json, "w") as fh:
            json.dump({"scanned_at": now.isoformat(), "filters": vars(a), "series_source": source, "not_scanned": failed,
                       "markets": rows}, fh, indent=2)


if __name__ == "__main__":
    main()
