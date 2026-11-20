import sys, re, json, time, collections, urllib.request
sys.path.insert(0,'.')
import k
f=k.f

# --- BTC hourly candles from Coinbase (public), Sep 15 -> Nov 21
def candles():
    out={}
    end=int(time.mktime(time.strptime("2026-11-21","%Y-%m-%d")))
    start=int(time.mktime(time.strptime("2026-09-15","%Y-%m-%d")))
    t=start
    while t<end:
        hi=min(t+300*3600,end)
        u=("https://api.exchange.coinbase.com/products/BTC-USD/candles"
           f"?granularity=3600&start={t}&end={hi}")
        try:
            r=urllib.request.Request(u,headers={"User-Agent":"research"})
            for c in json.load(urllib.request.urlopen(r,timeout=30)):
                out[int(c[0])]=float(c[4])   # [ts,low,high,open,close,vol]
        except Exception as e:
            print("  candle fetch failed:",str(e)[:80],flush=True)
        t=hi; time.sleep(0.4)
    return out
C=candles()
print(f"BTC hourly closes: {len(C)}")

def strike(t):
    m=re.search(r'-T(\d+(?:\.\d+)?)$',t); return float(m.group(1)) if m else None

rows=[r for r in k.page("/portfolio/settlements","settlements",maxp=60) if k.book(r["ticker"])=="btc_ladder"]
hrs=collections.defaultdict(list)
for r in rows:
    n=f(r,"yes_count_fp")+f(r,"no_count_fp")
    if n==0: continue
    cost=f(r,"yes_total_cost_dollars")+f(r,"no_total_cost_dollars")
    side="yes" if f(r,"yes_count_fp")>f(r,"no_count_fp") else "no"
    hrs[r["event_ticker"]].append((strike(r["ticker"]),side,f(r,"revenue")/100.0-cost))

# event ticker KXBTCD-26NOV1707 -> close hour in ET; use settled_time instead (UTC, == close)
closeof={}
for r in rows: closeof[r["event_ticker"]]=r["settled_time"]
def ts(s):
    return int(time.mktime(time.strptime(s[:19],"%Y-%m-%dT%H:%M:%S")))

need=0; swept=0; dists=[]; moves=[]
for ev,v in hrs.items():
    ct=ts(closeof[ev])//3600*3600        # top of the settling hour
    entry=C.get(ct-3600); close=C.get(ct)   # spot an hour before close; close of that hour
    if entry is None or close is None: continue
    need+=1
    # our book is one-sided per hour in 67/67 losing hours; take the dominant side
    for side in ("yes","no"):
        ss=[s for s,sd,_ in v if sd==side and s]
        if not ss: continue
        # YES deep-ITM = strike BELOW spot (pays if close > strike): adverse move is DOWN
        # NO  deep-ITM = strike ABOVE spot: adverse move is UP
        if side=="yes": d=entry-min(ss); adverse=entry-close
        else:           d=max(ss)-entry; adverse=close-entry
        if d<=0: continue
        dists.append(d); moves.append(adverse)
        if adverse>=d: swept+=1
        break

dists.sort()
print(f"\nhours with spot + a one-sided book: {need}")
print(f"distance entry-spot -> FURTHEST same-side strike: median ${dists[len(dists)//2]:.0f}, "
      f"p10 ${dists[int(.1*len(dists))]:.0f}, p90 ${dists[int(.9*len(dists))]:.0f}")
print(f"FULL one-side sweeps (adverse move >= that distance): {swept} of {len(dists)}  = 1 in {len(dists)/max(swept,1):.1f} hours")
am=sorted(abs(x) for x in moves)
print(f"realised 1h move: median ${am[len(am)//2]:.0f}, p90 ${am[int(.9*len(am))]:.0f}, max ${am[-1]:.0f}")
