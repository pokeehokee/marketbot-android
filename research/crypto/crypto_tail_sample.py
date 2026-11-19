"""Is the cheap tail of Kalshi's hourly BTC ladder underpriced to a BUYER?

The >20x question: buy a contract at <=0.05 and it pays 1.00. Does it win more
often than its price implies, or less?

Rules this obeys, all from the repos' CLAUDE.md:
  * ZERO LOOKAHEAD. The candle used is the last whose period ENDS at or before
    close - lead, and the request window itself ends there.
  * The empty-book sentinel is never a price. yes_ask 1.00 / yes_bid 0.00 means
    "no orders on that side", the same shape a closed market quotes -> NULL.
  * Strikes are selected from SPOT AT THE LEAD (Coinbase 1m), never from the
    outcome. No selection on anything the decision moment could not see.
  * All strikes of one hour settle off ONE price move, so every CI clusters by
    event hour, not by strike.
  * Both tails: cheap YES on strikes above spot, cheap NO on strikes below.
"""
import os, sys, json, time, calendar, sqlite3, urllib.request, urllib.error

BASE = "https://api.elections.kalshi.com/trade-api/v2"

# Read-only by construction, the same guard book_collector.py uses: this script
# never needs credentials (the settled-market listing and the candlesticks are
# both public) and must never be able to reach an order endpoint.
ALLOWED = ("/markets", "/series/")


def assert_read_only(path):
    if not any(path.startswith(a) for a in ALLOWED):
        raise RuntimeError(f"refusing a non-whitelisted path: {path}")
    for bad in ("/portfolio", "/orders", "order"):
        if bad in path:
            raise RuntimeError(f"refusing a write-shaped path: {path}")


# Kalshi sends no Retry-After with a 429, so back off on a fixed ladder. This
# lives in get() rather than at one call site because a rate-limited LISTING
# silently yields an empty day, which looks exactly like "that day had no
# markets" -- the same class of failure as a swallowed exception freezing a
# table. Every caller gets the retry.
RETRY_SLEEPS = (1, 3, 8, 20)


def get(path, _sleep=time.sleep):
    """Unauthenticated GET, retried on 429. Returns (payload, error)."""
    assert_read_only(path)
    last = None
    for i, nap in enumerate((0,) + RETRY_SLEEPS):
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

DB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "crypto_tail.db")
SERIES = "KXBTCD"
CHEAP_LO, CHEAP_HI = 0.01, 0.05          # >=20x
# Distance-from-spot window to fetch. The cheap band sits ~$260 (0.05) to
# ~$840 (0.01) from spot at a 30-minute lead, so $100-$1500 covers it with
# few wasted calls. A control run on MID prices must widen NEAR to 0,
# because a 0.30-0.70 strike sits within ~$100 of spot and would otherwise
# be excluded by this window -- which is how the first control found 2
# quotes in 4 hours and proved nothing.
NEAR, FAR = 100.0, 1500.0
SENTINEL_ASK, SENTINEL_BID = 1.0, 0.0


def utc(s):
    return calendar.timegm(time.strptime(s[:19] + "Z", "%Y-%m-%dT%H:%M:%SZ"))


def db():
    c = sqlite3.connect(DB)
    c.executescript("""
    CREATE TABLE IF NOT EXISTS quote(
      ticker TEXT, event_ticker TEXT, close_ts INT, lead_s INT,
      side TEXT,              -- 'yes' or 'no': which side we would BUY
      ask REAL,               -- what a buyer pays, from the candle
      strike REAL, spot REAL, dist REAL,
      candle_end_ts INT, volume REAL, open_interest REAL,
      won INT,                -- 1 if the side we bought settled in the money
      PRIMARY KEY(ticker, lead_s, side));
    CREATE TABLE IF NOT EXISTS hour_done(event_ticker TEXT, lead_s INT,
      n_strikes INT, PRIMARY KEY(event_ticker, lead_s));
    CREATE TABLE IF NOT EXISTS spot(minute_ts INT PRIMARY KEY, close REAL);
    """)
    return c


def fetch_spot(c, lo, hi):
    """Coinbase 1m closes into the cache, 300 minutes per call."""
    have = {r[0] for r in c.execute("SELECT minute_ts FROM spot WHERE minute_ts BETWEEN ? AND ?", (lo, hi))}
    need = [t for t in range(lo // 60 * 60, hi + 60, 60) if t not in have]
    if not need:
        return
    t = min(need)
    while t <= hi:
        u = (f"https://api.exchange.coinbase.com/products/BTC-USD/candles"
             f"?granularity=60&start={t}&end={min(t + 300 * 60, hi + 60)}")
        try:
            r = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": "research"}), timeout=25)
            rows = json.load(r)
            c.executemany("INSERT OR IGNORE INTO spot VALUES(?,?)", [(int(x[0]), float(x[4])) for x in rows])
            c.commit()
        except Exception as e:
            print("  spot fetch failed", t, repr(e)[:90], flush=True)
        t += 300 * 60
        time.sleep(0.35)


def spot_at(c, ts):
    """Last 1m close at or before ts. Never interpolates forward."""
    r = c.execute("SELECT close FROM spot WHERE minute_ts<=? ORDER BY minute_ts DESC LIMIT 1", (ts,)).fetchone()
    return r[0] if r else None


def price_at(ticker, close_ts, lead_s):
    """The last candle ENDING at or before close-lead. Returns (ask,bid,end,vol,oi) or None."""
    mark = close_ts - lead_s
    q = (f"/series/{SERIES}/markets/{ticker}/candlesticks"
         f"?start_ts={mark - 5400}&end_ts={mark}&period_interval=1")
    for attempt in range(3):
        d, e = get(q)
        if d is not None:
            break
        if e and "429" in str(e):
            time.sleep(2 + attempt * 3)
            continue
        return None
    else:
        return None
    cs = [x for x in (d.get("candlesticks") or []) if x.get("end_period_ts", 1 << 62) <= mark]
    if not cs:
        return None
    k = max(cs, key=lambda x: x["end_period_ts"])

    def f(side, field):
        v = (k.get(side) or {}).get(field)
        try:
            return float(v)
        except (TypeError, ValueError):
            return None
    ask, bid = f("yes_ask", "close_dollars"), f("yes_bid", "close_dollars")
    return ask, bid, k["end_period_ts"], f_num(k.get("volume_fp")), f_num(k.get("open_interest_fp"))


def f_num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return 0.0


def sample_hour(c, markets, lead_s, max_per_side=7):
    ev = markets[0]["event_ticker"]
    close_ts = utc(markets[0]["close_time"])
    if c.execute("SELECT 1 FROM hour_done WHERE event_ticker=? AND lead_s=?", (ev, lead_s)).fetchone():
        return 0
    sp = spot_at(c, close_ts - lead_s)
    if sp is None:
        return 0
    # Only fetch where the cheap band plausibly lives, measured on the smoke
    # test: 0.05 sits ~$260 from spot and 0.01 ~$840. A generous $100-$1500
    # window keeps the call count down without selecting on anything the lead
    # moment could not see.
    above = sorted([m for m in markets if m.get("floor_strike")
                    and NEAR <= m["floor_strike"] - sp <= FAR], key=lambda m: m["floor_strike"])
    below = sorted([m for m in markets if m.get("floor_strike")
                    and NEAR <= sp - m["floor_strike"] <= FAR], key=lambda m: -m["floor_strike"])
    rows, n = [], 0
    for ms, side in ((above, "yes"), (below, "no")):
        taken = 0
        for m in ms:
            if taken >= max_per_side:
                break
            p = price_at(m["ticker"], close_ts, lead_s)
            time.sleep(0.12)
            if p is None:
                continue
            ask, bid, end, vol, oi = p
            if ask is None or bid is None:
                continue
            # a buyer of YES pays yes_ask; a buyer of NO pays 1 - yes_bid
            if side == "yes":
                if ask >= SENTINEL_ASK:      # no YES offer at all
                    continue
                buy = ask
            else:
                if bid <= SENTINEL_BID:      # no YES bid => NO ask is $1, unbuyable
                    continue
                buy = round(1.0 - bid, 4)
            if not (CHEAP_LO - 1e-9 <= buy <= CHEAP_HI + 1e-9):
                continue
            won = 1 if ((m.get("result") == "yes") == (side == "yes")) else 0
            rows.append((m["ticker"], ev, close_ts, lead_s, side, buy,
                         m["floor_strike"], sp, m["floor_strike"] - sp, end, vol, oi, won))
            taken += 1
            n += 1
    c.executemany("INSERT OR REPLACE INTO quote VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", rows)
    c.execute("INSERT OR REPLACE INTO hour_done VALUES(?,?,?)", (ev, lead_s, n))
    c.commit()
    return n


def hours_in(lo, hi):
    """Settled markets grouped by event hour, paged over a time window."""
    out, cur = {}, None
    while True:
        q = (f"/markets?series_ticker={SERIES}&status=settled"
             f"&min_close_ts={lo}&max_close_ts={hi}&limit=200" + (f"&cursor={cur}" if cur else ""))
        d, e = get(q)
        if not d:
            print("  listing failed", str(e)[:90], flush=True)
            break
        for m in d.get("markets") or []:
            if m.get("result") in ("yes", "no"):
                out.setdefault(m["event_ticker"], []).append(m)
        cur = d.get("cursor")
        if not cur:
            break
        time.sleep(0.12)
    return out


if __name__ == "__main__":
    days = int(sys.argv[1]) if len(sys.argv) > 1 else 10
    lead_s = int(sys.argv[2]) if len(sys.argv) > 2 else 1800
    every = int(sys.argv[3]) if len(sys.argv) > 3 else 1
    now = int(time.time())
    c = db()
    done = 0
    for d_back in range(1, days + 1):
        hi = now - (d_back - 1) * 86400
        lo = now - d_back * 86400
        hrs = hours_in(lo, hi)
        keys = sorted(hrs)[::every]
        fetch_spot(c, lo - 7200, hi)
        for ev in keys:
            n = sample_hour(c, hrs[ev], lead_s)
            done += n
        tot = c.execute("SELECT COUNT(*) FROM quote WHERE lead_s=?", (lead_s,)).fetchone()[0]
        wins = c.execute("SELECT SUM(won) FROM quote WHERE lead_s=?", (lead_s,)).fetchone()[0] or 0
        print(f"day -{d_back}: {len(keys)} hours sampled, +{done} quotes | total {tot}, wins {wins}", flush=True)
