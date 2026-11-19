"""Method #30: Kalshi daily S&P 500 range brackets vs an options-implied distribution.

Design, and the lookahead discipline it turns on:

  * `KXINX` opens 03:30Z and closes 21:00Z the SAME day, so there is no
    prior-close instant at which the market is listed. The pricing mark is
    therefore 13:30Z on the settlement day -- inside the market's life, and
    still BEFORE the 14:30Z US open.
  * At that mark the inputs are the PREVIOUS session's SPX close and VIX1D
    close, both published at 21:00Z the day before. Using the SAME day's
    VIX1D would be lookahead: it is computed from that day's option prices
    and published after the close.
  * The Kalshi price is the last candle ENDING at or before the mark, with the
    request window ending there too. An ask of 1.0000 / bid of 0.0000 is the
    empty-book sentinel and is never a price.
  * Every CI is bootstrapped CLUSTERED BY EVENT-DAY: all ~20 brackets of one
    day settle off a single closing print and are not independent draws.

Model: S_T = S_0 * exp(-0.5*s^2*t + s*sqrt(t)*Z), s = VIX1D/100, t = 1/252.
S_0 is the previous session's close, so the overnight gap is inside the move
VIX1D is quoting, which is what makes t one trading day rather than a partial.
"""
import os, sys, json, time, math, calendar, sqlite3, statistics, random
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import urllib.request, urllib.error

BASE = "https://api.elections.kalshi.com/trade-api/v2"
ALLOWED = ("/markets", "/historical/markets", "/series/")


def assert_read_only(path):
    if not any(path.startswith(a) for a in ALLOWED):
        raise RuntimeError(f"refusing a non-whitelisted path: {path}")
    if "/portfolio" in path or "order" in path:
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

HERE = os.path.dirname(os.path.abspath(__file__))
DB = os.path.join(HERE, "inxvol.db")
LEAD_S = 27000           # 7.5h before the 21:00Z close = 13:30Z, pre-open
TAU = 1.0 / 252.0
random.seed(5)


def utc(s):
    return calendar.timegm(time.strptime(s[:19] + "Z", "%Y-%m-%dT%H:%M:%SZ"))


def ndist(x):
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))


def db():
    c = sqlite3.connect(DB)
    c.executescript("""
    CREATE TABLE IF NOT EXISTS bracket(
      ticker TEXT PRIMARY KEY, event TEXT, day TEXT, close_ts INT,
      floor REAL, cap REAL, kbid REAL, kask REAL, candle_end INT,
      s0 REAL, vix1d REAL, model_p REAL, won INT);
    CREATE TABLE IF NOT EXISTS done(event TEXT PRIMARY KEY, n INT);
    """)
    return c


def prior_session(day, spx, vix):
    """The last trading day strictly before `day` with BOTH closes published."""
    ks = sorted(k for k in spx if k < day and k in vix)
    return ks[-1] if ks else None


def model_p(floor, cap, s0, sigma):
    """P(S_T in [floor, cap)) under the implied lognormal."""
    sd = sigma * math.sqrt(TAU)
    if sd <= 0:
        return None
    def cdf(x):
        if x is None:
            return None
        if x <= 0:
            return 0.0
        return ndist((math.log(x / s0) + 0.5 * sd * sd) / sd)
    lo = cdf(floor) if floor is not None else 0.0
    hi = cdf(cap) if cap is not None else 1.0
    if lo is None or hi is None:
        return None
    return max(0.0, min(1.0, hi - lo))


def price_at(ticker, close_ts):
    mark = close_ts - LEAD_S
    q = (f"/series/KXINX/markets/{ticker}/candlesticks"
         f"?start_ts={mark - 50400}&end_ts={mark}&period_interval=1")
    d, e = get(q)
    if not d:
        return None
    cs = [x for x in (d.get("candlesticks") or []) if x.get("end_period_ts", 1 << 62) <= mark]
    if not cs:
        return None
    k = max(cs, key=lambda x: x["end_period_ts"])

    def f(side):
        v = (k.get(side) or {}).get("close_dollars")
        try:
            return float(v)
        except (TypeError, ValueError):
            return None
    a, b = f("yes_ask"), f("yes_bid")
    # An ask of exactly 1.0000 is the empty-book sentinel and is never a price.
    # A bid of 0.0000 is NOT a sentinel here: overnight (13:30Z, pre-open) the
    # KXINX brackets are genuinely ONE-SIDED -- a real ask with nothing behind
    # it. Rejecting those discarded every bracket on 29 straight days. It does
    # mean only the BUY leg is executable, so the scorer trades the ask alone.
    if a is None or a >= 1.0:
        return None
    return (b if b is not None else 0.0), a, k["end_period_ts"]


def build(days=200):
    spx = json.load(open(os.path.join(HERE, "yf_GSPC.json")))
    vix = json.load(open(os.path.join(HERE, "yf_VIX1D.json")))
    c = db()
    seen = {}
    for path in ("/markets?series_ticker=KXINX&status=settled", "/historical/markets?series_ticker=KXINX"):
        cur = None
        for _ in range(14):
            u = path + ("&" if "?" in path else "?") + "limit=200" + (f"&cursor={cur}" if cur else "")
            d, e = get(u)
            if not d:
                break
            ms = d.get("markets") or []
            for m in ms:
                if m.get("result") in ("yes", "no") and m.get("close_time"):
                    seen.setdefault(m["event_ticker"], []).append(m)
            cur = d.get("cursor")
            if not cur or not ms:
                break
            time.sleep(0.05)
    print(f"event-days found: {len(seen)}", flush=True)
    for i, (ev, ms) in enumerate(sorted(seen.items())):
        if c.execute("SELECT 1 FROM done WHERE event=?", (ev,)).fetchone():
            continue
        close_ts = utc(ms[0]["close_time"])
        day = time.strftime("%Y-%m-%d", time.gmtime(close_ts))
        ps = prior_session(day, spx, vix)
        if not ps:
            c.execute("INSERT OR REPLACE INTO done VALUES(?,0)", (ev,)); c.commit(); continue
        s0, sigma = spx[ps], vix[ps] / 100.0
        rows = []
        for m in ms:
            p = price_at(m["ticker"], close_ts)
            time.sleep(0.05)
            if not p:
                continue
            kb, ka, end = p
            mp = model_p(m.get("floor_strike"), m.get("cap_strike"), s0, sigma)
            if mp is None:
                continue
            rows.append((m["ticker"], ev, day, close_ts, m.get("floor_strike"), m.get("cap_strike"),
                         kb, ka, end, s0, vix[ps], mp, 1 if m["result"] == "yes" else 0))
        c.executemany("INSERT OR REPLACE INTO bracket VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", rows)
        c.execute("INSERT OR REPLACE INTO done VALUES(?,?)", (ev, len(rows)))
        c.commit()
        if i % 10 == 0:
            n = c.execute("SELECT COUNT(*) FROM bracket").fetchone()[0]
            print(f"  {i}/{len(seen)} {ev} -> {len(rows)} brackets (total {n})", flush=True)


def fee(p):
    return math.ceil(0.07 * 1 * p * (1 - p) * 100) / 100.0


def score():
    c = db()
    rows = c.execute("SELECT day, kbid, kask, model_p, won FROM bracket").fetchall()
    by = {}
    for day, kb, ka, mp, won in rows:
        by.setdefault(day, []).append((kb, ka, mp, won))
    print(f"\n=== Method #30: KXINX brackets vs VIX1D-implied lognormal, {LEAD_S//3600}h lead, zero lookahead")
    print(f"brackets {len(rows)}, event-days {len(by)}\n")
    # Calibration first. The book is one-sided overnight, so the market's own
    # number is the ASK, not a mid -- an ask overstates P(YES) by the spread,
    # which is exactly the trap weatherbot records for scoring a market on its
    # bid. Both are reported; neither is a fair "market forecast".
    two = [(kb, ka, mp, won) for qs in by.values() for kb, ka, mp, won in qs if kb > 0]
    bm = bk = n = 0.0
    for kb, ka, mp, won in two:
        mid = 0.5 * (kb + ka)
        bm += (mp - won) ** 2; bk += (mid - won) ** 2; n += 1
    if n:
        print(f"two-sided brackets {int(n)}:  Brier model {bm/n:.4f}  market mid {bk/n:.4f}  "
              f"{'MODEL BETTER' if bm < bk else 'market better'}")
    else:
        print("no two-sided brackets at this lead: the overnight book is one-sided")
    allr = [(kb, ka, mp, won) for qs in by.values() for kb, ka, mp, won in qs]
    bm2 = sum((mp - won) ** 2 for _, _, mp, won in allr) / len(allr)
    bk2 = sum((ka - won) ** 2 for _, ka, _, won in allr) / len(allr)
    print(f"all {len(allr)} brackets:  Brier model {bm2:.4f}  market ASK {bk2:.4f} (ask overstates P)")
    print(f"\n{'gate':>6} {'trades':>7} {'per contract':>13} {'95% CI by day':>24}")
    for gate in (0.03, 0.05, 0.08, 0.12, 0.20):
        per_day = {}
        for day, qs in by.items():
            tot = 0.0; k = 0
            for kb, ka, mp, won in qs:
                # Buy at the ask only: overnight there is no bid to sell into.
                if mp > ka + gate:
                    tot += won - ka - fee(ka); k += 1
            if k:
                per_day[day] = (tot, k)
        if not per_day:
            print(f"{gate:>6.2f} {'0':>7}"); continue
        T = sum(v[0] for v in per_day.values()); K = sum(v[1] for v in per_day.values())
        ks = list(per_day)
        bs = []
        for _ in range(4000):
            s = [per_day[random.choice(ks)] for _ in ks]
            t = sum(x[0] for x in s); k = sum(x[1] for x in s)
            if k: bs.append(100 * t / k)
        bs.sort()
        lo, hi = bs[int(.025 * len(bs))], bs[int(.975 * len(bs))]
        print(f"{gate:>6.2f} {K:>7} {100*T/K:>+12.2f}c  [{lo:>+7.2f}, {hi:>+7.2f}]")


if __name__ == "__main__":
    if "--score" in sys.argv:
        score()
    else:
        build()
        score()
