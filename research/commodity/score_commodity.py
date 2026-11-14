#!/usr/bin/env python3
"""Score SELLING the cheap tail (rest at the ask) on settled commodity range markets.

A seller of YES at price p collects p and pays 1 if it settles YES.  EV/contract = p - P(yes).
"Implied" losses = sum of p over the sample; realised losses = count settling YES.
Resting fills pay no fee on quadratic series.
CIs bootstrapped clustered by CLOSE-DATE, because one day's strikes share one price move.
"""
import json, sys, random, collections, statistics

rows, seen = [], set()
for ln in open("commodity_sample.jsonl"):
    r = json.loads(ln)
    k = (r["series"], r["ticker"])
    if k in seen: continue
    seen.add(k); rows.append(r)

BANDS = [(0.02,0.05),(0.05,0.10),(0.10,0.15),(0.15,0.25)]
MAXSPREAD = 0.05

def sample(lead, lo=0.02, hi=0.25, series=None):
    out = []
    for r in rows:
        if series and r["series"] != series: continue
        b = r.get(lead)
        if not b: continue
        bid, ask = b["bid"], b["ask"]
        if ask - bid > MAXSPREAD: continue
        # the strategy sells the CHEAP tail: either sell YES when YES is cheap,
        # or buy YES when YES is dear (= selling the cheap NO).
        if lo <= ask <= hi:
            p, lost = ask, (r["result"] == "yes")
        elif lo <= 1.0 - bid <= hi:
            p, lost = 1.0 - bid, (r["result"] == "no")
        else:
            continue
        out.append((r["close"][:10], p, lost, r["series"]))
    return out

def score(s):
    if not s: return None
    n = len(s); lost = sum(x[2] for x in s); imp = sum(x[1] for x in s)
    ev = sum((x[1] - 1.0) if x[2] else x[1] for x in s) / n
    return n, lost, imp, (lost/imp if imp else float('nan')), ev*100

def boot(s, B=4000):
    byday = collections.defaultdict(list)
    for x in s: byday[x[0]].append(x)
    days = list(byday)
    if len(days) < 3: return None
    random.seed(7); evs = []
    for _ in range(B):
        pick = [byday[random.choice(days)] for _ in days]
        flat = [x for g in pick for x in g]
        evs.append(sum((x[1]-1.0) if x[2] else x[1] for x in flat)/len(flat)*100)
    evs.sort()
    return evs[int(.025*B)], evs[int(.975*B)]

for lead in ("60m","720m"):
    s = sample(lead)
    r = score(s)
    if not r: continue
    days = len(set(x[0] for x in s))
    ci = boot(s)
    print(f"\n=== lead {lead}  |  {r[0]} markets, {days} close-dates ===")
    print(f"  lost {r[1]} vs {r[2]:.1f} implied   ratio {r[3]:.2f}   EV {r[4]:+.2f}c"
          + (f"   95% CI [{ci[0]:+.2f}, {ci[1]:+.2f}]" if ci else ""))
    print(f"  {'band':<12}{'n':>6}{'lost':>6}{'imp':>8}{'ratio':>8}{'EV/ct':>9}  CI")
    for lo,hi in BANDS:
        sb = sample(lead, lo, hi); rb = score(sb)
        if not rb: continue
        cb = boot(sb)
        print(f"  {lo:.2f}-{hi:.2f}  {rb[0]:>6}{rb[1]:>6}{rb[2]:>8.1f}{rb[3]:>8.2f}{rb[4]:>8.2f}c  "
              + (f"[{cb[0]:+.2f}, {cb[1]:+.2f}]" if cb else "-"))
    print(f"  {'series':<12}{'n':>6}{'lost':>6}{'imp':>8}{'ratio':>8}{'EV/ct':>9}")
    for sr in sorted(set(x[3] for x in s)):
        ss = sample(lead, series=sr); rs = score(ss)
        if rs: print(f"  {sr:<12}{rs[0]:>6}{rs[1]:>6}{rs[2]:>8.1f}{rs[3]:>8.2f}{rs[4]:>8.2f}c")
