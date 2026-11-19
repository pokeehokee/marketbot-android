"""Screen every frequently-settling Kalshi series for the DEEP-ITM shape that
the one profitable live book exploits: buy 0.90-0.96 on an almost-decided
contract, in a book with two-sided quotes and a tight spread.

Every previous screen in this repo looked for the SELL-cheap-tail shape. This
is its mirror. The control is KXBTCD: if it does not pass, the thresholds are
wrong, not the market (screen_kalshi_pools.py's first run rejected weather,
its own known-good case, and reported zero pools)."""
import sys, time, json, collections, urllib.request, urllib.error

BASE = "https://api.elections.kalshi.com/trade-api/v2"
ALLOWED = ("/series", "/markets")


def assert_read_only(path):
    if not any(path.startswith(a) for a in ALLOWED):
        raise RuntimeError(f"refusing a non-whitelisted path: {path}")
    for bad in ("/portfolio", "order"):
        if bad in path:
            raise RuntimeError(f"refusing a write-shaped path: {path}")


def get(path, _sleep=time.sleep):
    """Unauthenticated GET, retried on 429 (Kalshi sends no Retry-After)."""
    assert_read_only(path)
    last = None
    for nap in (0, 1, 3, 8, 20):
        if nap:
            _sleep(nap)
        try:
            r = urllib.request.urlopen(
                urllib.request.Request(BASE + path, headers={"User-Agent": "research"}), timeout=30)
            return json.load(r), None
        except urllib.error.HTTPError as e:
            last = f"HTTP {e.code}"
            if e.code != 429:
                return None, last
        except Exception as e:
            last = f"{type(e).__name__}: {e}"
    return None, last
BAND_LO, BAND_HI = 0.90, 0.96
# The ladder is POST-ONLY: it RESTS A BID in the band, it never lifts the ask.
# So the screen condition is "the best bid sits in the band", not "the ask does".
# Filtering on the ask rejected KXBTCD itself (bid 0.95 / ask 0.99) and returned
# zero pools -- the control is the only reason that was caught.
MAX_SPREAD = 0.12

def px(m):
    def f(k):
        try: return float(m.get(k))
        except: return None
    return f("yes_bid_dollars"), f("yes_ask_dollars")

cats=("Crypto","Financials","Economics","Sports","Politics","Elections",
      "Climate and Weather","Entertainment","Companies","Science and Technology","Health","World")
series=[]
for c in cats:
    cur=None
    for _ in range(12):
        d,_=get(f"/series?category={c}&limit=200"+(f"&cursor={cur}" if cur else ""))
        if not d: break
        ss=d.get("series") or []
        for x in ss:
            if x.get("frequency") in ("hourly","fifteen_min","daily"):
                series.append((x["ticker"], c, x.get("frequency")))
        cur=d.get("cursor")
        if not cur or not ss: break
print(f"screening {len(series)} frequent series", flush=True)
rows=[]
for i,(t,c,f) in enumerate(series):
    d,e=get(f"/markets?series_ticker={t}&status=open&limit=200")
    time.sleep(0.05)
    ms=(d or {}).get("markets") or []
    inband=[]
    for m in ms:
        b,a=px(m)
        if b is None or a is None: continue
        if b<=0 or a>=1: continue            # empty-book sentinel, never a price
        if BAND_LO<=b<=BAND_HI and (a-b)<=MAX_SPREAD:
            inband.append((m["ticker"],b,a))
    if inband:
        rows.append((t,c,f,len(ms),len(inband),inband))
    if i%60==0: print(f"  ...{i}/{len(series)}", flush=True)
rows.sort(key=lambda r:-r[4])
print(f"\nseries with ANY two-sided deep-ITM market (BID {BAND_LO}-{BAND_HI}, spread<={MAX_SPREAD}): {len(rows)}\n")
print(f"{'series':<22} {'cat':<22} {'freq':<12} {'open':>5} {'in-band':>8}")
for t,c,f,n,k,ex in rows[:30]:
    print(f"{t:<22} {c:<22} {f:<12} {n:>5} {k:>8}")
ctrl=[r for r in rows if r[0]=="KXBTCD"]
print(f"\nCONTROL KXBTCD: {'PASSES' if ctrl else 'FAILS -- thresholds are wrong, not the market'}")
if ctrl: print(f"   {ctrl[0][4]} in-band markets, e.g. {ctrl[0][5][:3]}")
