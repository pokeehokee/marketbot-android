#!/usr/bin/env python3
"""Score the commodity cheap-tail trade across leads, PAIRED.

Unpaired lead curves lie here: a market only has a quotable book at a short lead if it was still
contested there, which selects on outcome. Every comparison below is restricted to markets priced
at BOTH leads, so the attrition drops out.

CIs are bootstrapped clustered by CLOSE-DATE: a commodity range settles off one price move, so a
day's strikes win or lose together and n markets are not n observations.
Resting fills pay no fee on these series, so the seller's price is the price.
"""
import json, random, collections, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
PATH = os.path.join(HERE, "commodity_leads.jsonl")
MAXSPREAD = 0.05
LO, HI = 0.02, 0.25
LEADS = ["60m", "30m", "15m", "5m"]

rows, seen = [], set()
for line in open(PATH):
    try: r = json.loads(line)
    except Exception: continue
    k = (r["series"], r["ticker"])
    if k in seen: continue
    seen.add(k); rows.append(r)


def pick(r, lead, lo=LO, hi=HI):
    """The trade the strategy would take at this lead, or None. Sells the cheap side at the
    resting price: YES cheap -> sell YES at the ask; NO cheap -> buy YES at the bid."""
    b = r.get(lead)
    if not b: return None
    bid, ask = b["bid"], b["ask"]
    if ask - bid > MAXSPREAD: return None
    if lo <= ask <= hi:      return ask, (r["result"] == "yes")
    if lo <= 1.0 - bid <= hi: return 1.0 - bid, (r["result"] == "no")
    return None


def score(s):
    if not s: return None
    n = len(s); lost = sum(x[2] for x in s); imp = sum(x[1] for x in s)
    ev = sum((x[1] - 1.0) if x[2] else x[1] for x in s) / n
    return n, lost, imp, (lost / imp if imp else float("nan")), ev * 100


def boot(vals_by_day, B=4000, seed=7):
    """vals_by_day: {date: [per-contract P&L in cents]}. Resample DAYS, not markets."""
    days = list(vals_by_day)
    if len(days) < 3: return None
    random.seed(seed); out = []
    for _ in range(B):
        flat = [v for d in (random.choice(days) for _ in days) for v in vals_by_day[d]]
        out.append(sum(flat) / len(flat))
    out.sort()
    return out[int(0.025 * B)], out[int(0.975 * B)]


def byday(s):
    d = collections.defaultdict(list)
    for day, p, lost, *_ in s:
        d[day].append((p - 1.0) * 100 if lost else p * 100)
    return d


def collect(lead, only=None):
    out = []
    for r in rows:
        if only is not None and (r["series"], r["ticker"]) not in only: continue
        p = pick(r, lead)
        if p: out.append((r["close"][:10], p[0], p[1]))
    return out


print(f"{len(rows)} markets in the file\n")
print("=== every market quotable at that lead (UNPAIRED - for coverage only) ===")
print(f"{'lead':>5}  {'n':>5} {'days':>5} {'lost/implied':>14} {'ratio':>6} {'EV/ct':>8}  95% CI")
for lead in LEADS:
    s = collect(lead)
    sc = score(s)
    if not sc: print(f"{lead:>5}  none"); continue
    n, lost, imp, ratio, ev = sc
    ci = boot(byday(s))
    days = len({x[0] for x in s})
    cis = f"[{ci[0]:+.2f}, {ci[1]:+.2f}]" if ci else "n/a"
    print(f"{lead:>5}  {n:5d} {days:5d} {lost:6d}/{imp:7.1f} {ratio:6.2f} {ev:+7.2f}c  {cis}")

print("\n=== PAIRED against 60m: the same markets, priced at both ===")
print(f"{'vs':>5}  {'n':>5} {'ratio@60m':>9} {'EV@60m':>8} {'ratio@near':>10} {'EV@near':>8} {'paired diff':>11}  95% CI")
base_keys = {(r["series"], r["ticker"]) for r in rows if pick(r, "60m")}
for lead in LEADS[1:]:
    both = {(r["series"], r["ticker"]) for r in rows
            if (r["series"], r["ticker"]) in base_keys and pick(r, lead)}
    if len(both) < 50: print(f"{lead:>5}  only {len(both)} paired markets"); continue
    a, b = collect("60m", both), collect(lead, both)
    sa, sb = score(a), score(b)
    # paired difference, clustered by day: per day, (near EV) - (60m EV) over the same markets
    pa, pb = byday(a), byday(b)
    diff_by_day = {d: [x - y for x, y in zip(pb[d], pa[d])] for d in pa if d in pb}
    allv = [v for vs in diff_by_day.values() for v in vs]
    diff = sum(allv) / len(allv)
    ci = boot(diff_by_day)
    cis = f"[{ci[0]:+.2f}, {ci[1]:+.2f}]" if ci else "n/a"
    print(f"{lead:>5}  {len(both):5d} {sa[3]:9.2f} {sa[4]:+7.2f}c {sb[3]:10.2f} {sb[4]:+7.2f}c {diff:+10.2f}c  {cis}")

print("\n=== yield per dollar of collateral per DAY ===")
print("Collateral locked is (1 - price) per contract, held from the quote to close. Commodity")
print("closes cluster into 2 windows a day (measured: median 2 distinct close-times per date),")
print("so the same dollar can be deployed at most twice -- shorter leads do NOT buy more turns.")
print(f"{'lead':>5}  {'EV/ct':>8} {'collateral':>11} {'per $ per window':>17} {'per $-day (x2)':>15}")
for lead in LEADS:
    s = collect(lead)
    if not s: continue
    ev = score(s)[4]
    coll = sum(1.0 - x[1] for x in s) / len(s) * 100
    per = ev / (coll / 100)
    print(f"{lead:>5}  {ev:+7.2f}c {coll:10.1f}c {per:+16.2f}c {per * 2:+14.2f}c")
