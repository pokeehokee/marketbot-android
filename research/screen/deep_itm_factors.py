"""Every known factor against the deep-ITM timeline, split-half validated.

    python3 research/screen/deep_itm_factors.py

WHAT IT IS FOR
--------------
"Set the parameters to the most profitable cell" is how this repo has twice
bought noise: 04-05 ET looked like the worst hour (-2.27c) and read +5.28c out
of sample; Q2 volatility looked like the best of four quartiles. So every factor
here is reported for the FULL history and for each half of the timeline, and a
factor whose best cell changes between halves is noise, not a parameter.

Measured 2026-11-21 on 2,282 settled positions / 11,903 contracts:

    entry price band          FLIPS   (H1 best 0.90-0.92, H2 best 0.92-0.94)
    strikes held that hour    FLIPS
    seconds to close at fill  FLIPS
    volatility quartile       FLIPS
    distance from spot        flat once measured correctly -- see below
    side (no > yes)           holds, but +1.77c vs +1.48c and yes falls to +0.91c in H2
    stacked vs single         HOLDS: +1.79c r0.70 against -0.67c r1.12
    day of week               Sunday -0.05c r1.01 in BOTH halves (1 of 7 cells)

Nothing here is a selector. The one actionable result is that STACKED positions
underperform, which is a defect to cap rather than an edge to trade.

THE LOOKAHEAD THAT NEARLY SHIPPED AS A FINDING
----------------------------------------------
The first run of this reported distance-from-spot at **-4.20c, ratio 1.63**,
with 87 of 98 losses in one bucket and consistent across halves. It was wrong.

**Coinbase candle keys are the bucket START; the close is at `key + granularity`.**
Taking `H[floor(entry/3600)]` as "spot at entry" therefore reads a price up to
3,600 s AFTER the position was opened -- a median of 1,085 s after, measured. A
position that went on to lose had price already drifting toward its strike, so
post-entry spot sits closer to the strike and lands in the "near" bucket. The
leak WAS the signal. With spot taken strictly before entry the same bucket reads
**+1.56c, ratio 0.78**, flat with every other bucket.

`close_at_or_before()` is the fix and the tests freeze it. Any price used as an
input must be timestamped against the decision, never against the hour the
decision fell in.
"""
import collections
import json
import math
import os
import re
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kalshi_auth as k

# Hourly BTC closes, keyed by bucket START. Filled by main(); importing this
# module must not touch the network -- a test that imports it should not spend a
# minute fetching candles, and a module whose import does I/O cannot be tested.
H: dict = {}


def fetch(gran: int = 3600, days: int = 80) -> dict:
    out, end = {}, int(time.time())
    t = end - days * 86400
    while t < end:
        hi = min(t + 300 * gran, end)
        try:
            r = urllib.request.Request(
                f"https://api.exchange.coinbase.com/products/BTC-USD/candles"
                f"?granularity={gran}&start={t}&end={hi}", headers={"User-Agent": "research"})
            for c in json.load(urllib.request.urlopen(r, timeout=30)):
                out[int(c[0])] = float(c[4])
        except Exception:
            pass
        t = hi
        time.sleep(0.3)
    return out


def close_at_or_before(t: int):
    """Newest hourly close at or before `t`, as (price, close_time).

    Coinbase keys a candle by the bucket's START and the close is at
    `key + granularity`, so the bucket CONTAINING `t` has not closed yet. Using
    it reads a price up to an hour into the future: that is the lookahead that
    produced a spurious -4.20c finding, with the spot landing a median of 1,085
    seconds AFTER the entry it was supposed to precede.
    """
    b = (t // 3600) * 3600 - 3600
    for i in range(0, 6):
        if (b - 3600 * i) in H:
            return H[b - 3600 * i], b - 3600 * i + 3600
    return None, None


def sigma_1s(before: int):
    """Per-sqrt-second vol from up to 12 hourly returns, all closing <= `before`."""
    b = (before // 3600) * 3600 - 3600
    px = [H[b - 3600 * i] for i in range(0, 14) if (b - 3600 * i) in H]
    if len(px) < 8:
        return None
    r = [math.log(px[i] / px[i + 1]) for i in range(len(px) - 1)]
    m = sum(r) / len(r)
    return math.sqrt(sum((x - m) ** 2 for x in r) / (len(r) - 1)) / 60.0


def ts(s): return int(time.mktime(time.strptime(s[:19], "%Y-%m-%dT%H:%M:%S")))
def ct(r): return k.num(r, "yes_count_fp") + k.num(r, "no_count_fp")
def cost(r): return k.num(r, "yes_total_cost_dollars") + k.num(r, "no_total_cost_dollars")
def pnl(r): return k.num(r, "revenue") / 100.0 - cost(r) - k.num(r, "fee_cost")


def strike(t):
    m = re.search(r"-T(\d+(?:\.\d+)?)$", t)
    return float(m.group(1)) if m else None


def stat(g):
    if not g:
        return None
    n = sum(x["n"] for x in g)
    p = sum(x["pnl"] for x in g)
    imp = sum(x["n"] * (1 - x["px"]) for x in g)
    lost = sum(x["n"] for x in g if x["pnl"] < 0)
    return n, 100 * p / n, (lost / imp if imp > 0 else 0), sum(1 for x in g if x["pnl"] < 0)


def table(rows, mid, name, keyf, order):
    """One factor, full history and both halves. A best cell that changes
    between halves is noise -- the 04-05 ET and Q2-volatility failure mode."""
    print(f"--- {name}")
    print(f"{'level':14} {'ct':>7} {'per ct':>8} {'ratio':>6} {'L':>4} | {'H1':>17} | {'H2':>17}")
    g = collections.defaultdict(list)
    for x in rows:
        kk = keyf(x)
        if kk is not None:
            g[kk].append(x)
    best = {}
    for kk in order:
        s = stat(g.get(kk) or [])
        if not s:
            continue
        h1 = stat([x for x in g[kk] if x["date"] < mid])
        h2 = stat([x for x in g[kk] if x["date"] >= mid])
        for tag, h in (("H1", h1), ("H2", h2)):
            if h and h[0] >= 200 and h[1] > best.get(tag, (None, -9e9))[1]:
                best[tag] = (kk, h[1])
        f1 = f"{h1[1]:+7.2f}c r{h1[2]:5.2f}" if h1 else "-"
        f2 = f"{h2[1]:+7.2f}c r{h2[2]:5.2f}" if h2 else "-"
        print(f"{str(kk):14} {s[0]:7.0f} {s[1]:+7.2f}c {s[2]:6.2f} {s[3]:4} | {f1:>17} | {f2:>17}")
    b1, b2 = best.get("H1", (None,))[0], best.get("H2", (None,))[0]
    print(f"  best cell (>=200 ct/half): {'SAME' if b1 == b2 else f'FLIPS  H1={b1}  H2={b2}'}\n")


def build():
    sets = [r for r in k.page("/portfolio/settlements", "settlements", max_pages=80)
            if r["ticker"].startswith("KXBTCD-")]
    fills = [f for f in k.page("/portfolio/fills", "fills", max_pages=80)
             if (f.get("ticker") or "").startswith("KXBTCD-")]
    first = {}
    for f in fills:
        t, c = f["ticker"], ts(f["created_time"])
        if t not in first or c < first[t]:
            first[t] = c
    rows, lags = [], []
    for r in sets:
        n = ct(r)
        tk = r["ticker"]
        if n <= 0 or tk not in first:
            continue
        entry = first[tk]
        close = ts(r["settled_time"]) // 3600 * 3600
        ttc = close - entry
        if ttc <= 0:
            continue
        spot, spot_t = close_at_or_before(entry)
        s1, kk = sigma_1s(entry), strike(tk)
        if not (spot and s1 and kk):
            continue
        lags.append(entry - spot_t)
        side = "yes" if k.num(r, "yes_count_fp") > k.num(r, "no_count_fp") else "no"
        zr = math.log(spot / kk) / (s1 * math.sqrt(ttc))
        rows.append(dict(date=r["settled_time"][:10], n=n, px=cost(r) / n, pnl=pnl(r),
                         z=(zr if side == "yes" else -zr), side=side,
                         dist=abs(math.log(spot / kk)) * 100, ttc=ttc,
                         stacked=1 if n > (7.5 if r["settled_time"][:10] >= "2026-11-18" else 5.5) else 0))
    return rows, lags


def main() -> int:
    global H
    H = fetch()
    rows, lags = build()
    if not rows:
        print("nothing reconstructable -- check credentials and the candle fetch")
        return 1
    lags.sort()
    # The one line that would have caught the lookahead. It must never go negative.
    print(f"spot lag behind entry: median {lags[len(lags)//2]}s, min {lags[0]}s, max {lags[-1]}s "
          f"(must all be >= 0)")
    if lags[0] < 0:
        print("LOOKAHEAD: a spot was taken AFTER its entry; refusing to report factors")
        return 1
    dates = sorted({x["date"] for x in rows})
    mid = dates[len(dates) // 2]
    print(f"{len(rows)} positions, {sum(x['n'] for x in rows):.0f} ct, split at {mid}\n")
    table(rows, mid, "distance from spot (%)",
          lambda x: "<0.3" if x["dist"] < 0.3 else "0.3-0.5" if x["dist"] < 0.5
          else "0.5-0.8" if x["dist"] < 0.8 else "0.8+", ["<0.3", "0.3-0.5", "0.5-0.8", "0.8+"])
    table(rows, mid, "z = ln(spot/strike)/(sigma*sqrt(ttc)), signed to our side",
          lambda x: "z<1" if x["z"] < 1 else "1-2" if x["z"] < 2 else "2-4" if x["z"] < 4 else "4+",
          ["z<1", "1-2", "2-4", "4+"])
    table(rows, mid, "entry price band",
          lambda x: "0.90-0.92" if x["px"] < 0.92 else "0.92-0.94" if x["px"] < 0.94
          else "0.94-0.955" if x["px"] < 0.955 else "0.955+",
          ["0.90-0.92", "0.92-0.94", "0.94-0.955", "0.955+"])
    table(rows, mid, "side", lambda x: x["side"], ["yes", "no"])
    table(rows, mid, "stacked position", lambda x: "stacked" if x["stacked"] else "single",
          ["single", "stacked"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
