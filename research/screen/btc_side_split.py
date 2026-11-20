import sys, re, collections
sys.path.insert(0,'.')
import k
f=k.f
rows=[r for r in k.page("/portfolio/settlements","settlements",maxp=60) if k.book(r["ticker"])=="btc_ladder"]
hrs=collections.defaultdict(lambda: collections.Counter())
ct=collections.defaultdict(lambda: collections.Counter())
for r in rows:
    yc,nc=f(r,"yes_count_fp"),f(r,"no_count_fp")
    if yc+nc==0: continue
    side="yes" if yc>nc else "no"
    hrs[r["event_ticker"]][side]+=1
    ct[r["event_ticker"]][side]+=yc+nc

both=sum(1 for v in hrs.values() if len(v)==2)
print(f"BTC hours: {len(hrs)} -- holding BOTH sides: {both} ({100*both/len(hrs):.0f}%), one side only: {len(hrs)-both}")
# of the 8-position hours, how are they split?
eights=[v for v in hrs.values() if sum(v.values())==8]
sp=collections.Counter(tuple(sorted(v.values(),reverse=True)) for v in eights)
print(f"\n8-position hours ({len(eights)}): split as", dict(sp))
# max contracts on ONE side, per hour -- this is what a single move can take
m=sorted(max(v.values()) for v in ct.values())
print(f"\nmax CONTRACTS on one side in an hour: median {m[len(m)//2]:.0f}, p90 {m[int(.9*len(m))]:.0f}, max {m[-1]:.0f}")
m5=sorted(max(v.values()) for ev,v in ct.items() if max(v.values())<=8*5 and all(x<=5 for x in hrs[ev].values()) )
# lot-5 era only: hours where no single market exceeded 5 contracts
lot5=[ev for ev in ct if all(c<=5 for c in [x for x in ct[ev].values()]) or True]
one=sorted(max(v.values()) for v in ct.values())
print(f"\nworst-hour LOSS ceiling = (max one-side contracts) x ~$0.93")
for q,lab in ((0.5,"median"),(0.9,"p90"),(1.0,"max")):
    v=one[min(int(q*len(one)),len(one)-1)]
    print(f"  {lab:6} {v:3.0f} contracts -> ${v*0.93:6.2f}")
