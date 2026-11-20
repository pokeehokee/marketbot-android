#!/usr/bin/env python3
"""Record Kalshi order books forward, at the resolution the lead question needs.

WHY THIS EXISTS
---------------
`commodity_leads.py` tried to answer "does quoting closer to close pay more?" from Kalshi's
1-minute candle history and could not: the history is sparse, so every nominal lead resolves to
"the last quote at or before the mark" (median candle age at the 60m mark: 90 minutes), and the
15m and 5m leads resolve to the IDENTICAL candle on 100% of markets. Zero lookahead still held,
so nothing was biased forward -- the curve below 60m simply is not measurable that way.

It is measurable on books that really existed. This collector writes those books down.

WHAT IT IS NOT
--------------
Read-only. It calls exactly two kinds of endpoint -- the per-series market listing and the
orderbook reads -- and `assert_read_only()` refuses any URL that is not one of them, so an order
endpoint cannot be reached from here even by mistake. It needs no credentials (both are public),
writes to its OWN database, and opens nothing belonging to the live book. It places, amends and
cancels nothing.

TWO PAYLOAD RULES IT DEPENDS ON, BOTH VERIFIED LIVE (2026-11-16)
----------------------------------------------------------------
1. The orderbook carries ONLY BIDS: `orderbook_fp.yes_dollars` are YES bids and `no_dollars` are
   NO bids, each `[price_dollars, count_fp]` as STRINGS, ascending by price. So the best bid of a
   side is its LAST level, **the YES ask is `1 - best NO bid`**, and the size resting at that ask
   is that NO level's size. Checked against the market object on KXGOLDD-26NOV1617-T4087: the
   ladder's best NO bid 0.99/537 gave 1-0.99 = 0.01 and 537, and the object independently read
   `yes_ask_dollars = '0.0100'`, `yes_ask_size_fp = '537.00'`.
2. **An empty side is not a price.** Kalshi reports a side with no resting orders as
   `yes_bid_dollars = '0.0000'` / `yes_ask_dollars = '1.0000'` -- the same shape a closed market
   quotes -- and in the ladder as an EMPTY LIST (`yes_dollars: []`), not as a missing key, which is
   a case the docs do not spell out. Every such reading is stored as NULL. weatherbot's stop-loss
   replay once reported a 97%-trigger rule at -72c/contract by reading that 1.00 as a buy-back
   price; 5.0% of its cached candles carried it.

CADENCE
-------
Commodity closes cluster into about two times a day (measured 2026-11-16: 19:30Z with 30 markets
and 22:00Z with 270), so the collector is idle most of the day and busy for two windows. Inside a
window it snapshots every `--interval` seconds; outside one it sleeps until the next window opens.
Top of book for EVERY open market comes from the 12 per-series listings -- 12 calls a cycle however
many markets there are -- and that alone is what pricing the cheap side needs (ask, bid, both
sizes). Full ladders cost one call per market, so they are fetched through the batch endpoint, only
for in-window markets in the cheap band, capped by `--depth-max`.

USAGE
-----
    python3 book_collector.py                      # run forever, window-aware
    python3 book_collector.py --once               # one cycle, for a smoke test
    python3 book_collector.py --cycles 5 --interval 30
    python3 book_collector.py --series KXBTCD --window 60     # any other book
    python3 book_collector.py --stats              # what has been collected so far

It is meant to run under systemd or pm2 on a machine that stays up; see DEPLOY.md. Each cycle is
independent and every insert is idempotent on (ticker, snapshot_ts), so a restart costs nothing and
a double-run writes nothing twice.
"""
from __future__ import annotations

import argparse
import datetime
import json
import csv
import gzip
import os
import sqlite3
import sys
import time
import urllib.error
import urllib.request

BASE = "https://api.elections.kalshi.com/trade-api/v2"
HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DB = os.path.join(HERE, "commodity_book.db")

# The 12 traded commodity range series, same list as commodity_sample.py.
SERIES = ("KXBRENTD KXBRENTW KXWTI KXWTIW KXNATGASD KXNATGASW "
          "KXGOLDD KXGOLDW KXSILVERD KXSILVERW KXCOPPERD KXCOPPERW").split()

# Minutes before close within which a market is worth snapshotting. 120 covers the shipped
# 40-90 minute window with margin on both sides, which is the point: the curve BELOW 60m is the
# unmeasured part.
WINDOW_MIN = 120.0
# Grace after close: the book goes empty but the last pre-close state is worth having.
GRACE_MIN = 2.0
# Ladders only for these asks. Wider than the strategy's 0.02-0.25 so the band question stays open.
DEPTH_ASK_LO, DEPTH_ASK_HI = 0.01, 0.45
DEPTH_MAX = 40              # markets per cycle to pull ladders for
DEPTH_CHUNK = 20            # tickers per batch orderbook call
INTERVAL_S = 60             # inside a window
IDLE_POLL_S = 900           # re-list this often while waiting for the next window

# ---------------------------------------------------------------------------- http


class ReadOnlyViolation(RuntimeError):
    """Raised if anything ever asks this collector to call a non-read endpoint."""


def assert_read_only(path: str) -> None:
    """The whitelist IS the safety property, so it is enforced on every call, not documented.

    Only the market listing and the orderbook reads are permitted. Any order, portfolio or
    position path raises instead of being requested.
    """
    p = path.split("?", 1)[0].rstrip("/")
    ok = (p == "/markets"
          or p == "/markets/orderbooks"
          or (p.startswith("/markets/") and p.endswith("/orderbook")))
    if not ok:
        raise ReadOnlyViolation(
            f"book_collector may only read /markets and orderbooks; refused {path!r}")


def get(path: str, tries: int = 6, timeout: int = 45):
    """GET with exponential backoff. A 429 carries no Retry-After and no cooldown, so back off."""
    assert_read_only(path)
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(BASE + path, headers={"User-Agent": "research"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            last = f"HTTP {e.code}"
            if e.code in (429, 500, 502, 503, 504):
                time.sleep(min(60, 3 * 2 ** i))
                continue
            raise
        except Exception as e:                                  # noqa: BLE001 - network is flaky
            last = type(e).__name__
            time.sleep(2 * (i + 1))
    raise RuntimeError(f"{path}: giving up after {tries} tries ({last})")


# ---------------------------------------------------------------- payload parsing


def num(x):
    """A fixed-point string to float, or None. Never raises, never returns a guess."""
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def price(x):
    """A price, with the empty-book sentinel mapped to None.

    Kalshi reports a side with no resting orders as 0.0000 (bid) or 1.0000 (ask) -- the same shape
    a closed market quotes. Neither is a price anyone can transact at, and storing either as one is
    the bug that produced a -72c/contract phantom stop-loss in weatherbot.
    """
    v = num(x)
    if v is None or v <= 0.0 or v >= 1.0:
        return None
    return v


def size(x):
    """A resting size. 0 means nothing is resting, which is absence, not a quantity."""
    v = num(x)
    if v is None or v <= 0.0:
        return None
    return v


def iso_to_ts(s):
    if not s:
        return None
    try:
        return datetime.datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def utc(ts_s):
    """Timezone-aware UTC; utcfromtimestamp is deprecated in 3.12+."""
    return datetime.datetime.fromtimestamp(ts_s, datetime.timezone.utc)


def best_level(levels):
    """Best (highest) bid of one ladder side as (price, size), or None.

    Levels come ascending, so the best is the last -- but this takes the max rather than [-1],
    because relying on the server's ordering is a silent dependency. An EMPTY LIST means the side
    has nothing resting, which is the live shape the docs omit.
    """
    if not levels:
        return None
    best = None
    for lv in levels:
        if not lv or len(lv) < 2:
            continue
        p, c = num(lv[0]), num(lv[1])
        if p is None or c is None or c <= 0:
            continue
        if best is None or p > best[0]:
            best = (p, c)
    return best


def top_from_market(m: dict) -> dict:
    """Top of book straight off the market object, with sentinels nulled.

    `yes_ask_dollars`/`yes_ask_size_fp` are the exchange's own view of the ask we would rest at and
    the size already queued there, and they agree with the ladder derivation (verified live).
    """
    return {
        "yes_bid": price(m.get("yes_bid_dollars")),
        "yes_bid_size": size(m.get("yes_bid_size_fp")),
        "yes_ask": price(m.get("yes_ask_dollars")),
        "yes_ask_size": size(m.get("yes_ask_size_fp")),
        "last_price": price(m.get("last_price_dollars")),
        "volume": num(m.get("volume_fp")),
        "volume_24h": num(m.get("volume_24h_fp")),
        "open_interest": num(m.get("open_interest_fp")),
    }


# ----------------------------------------------------------------------- storage

SCHEMA = """
CREATE TABLE IF NOT EXISTS book_top (
    ticker TEXT NOT NULL,
    snapshot_ts INTEGER NOT NULL,       -- the CYCLE's id, for joining; not when this row was read
    fetched_ts REAL,                    -- when THIS row's HTTP response actually arrived
    series TEXT,
    close_ts INTEGER,                   -- market close, unix seconds
    lead_s INTEGER,                     -- close_ts - snapshot_ts; negative after close
    status TEXT,
    exchange_index INTEGER,
    yes_bid REAL, yes_bid_size REAL,    -- NULL means nothing resting, never 0.00/1.00
    yes_ask REAL, yes_ask_size REAL,
    last_price REAL,
    volume REAL, volume_24h REAL, open_interest REAL,
    PRIMARY KEY (ticker, snapshot_ts)
);
CREATE INDEX IF NOT EXISTS ix_top_lead  ON book_top(ticker, lead_s);
CREATE INDEX IF NOT EXISTS ix_top_close ON book_top(close_ts);

-- Depth is a SEPARATE HTTP call from the listing that produced book_top, so it lands a second or
-- more later and the book can move in between: observed live on KXWTI-26NOV1614-T88.49, where the
-- listing reported 167 contracts at the best YES bid and the ladder read 15 a moment later. Both
-- readings are correct for their own instant. `fetched_ts` is what makes that measurable instead
-- of a hidden assumption -- the same mistake as "13:00 prices" that actually spanned nine hours.
CREATE TABLE IF NOT EXISTS book_depth (
    ticker TEXT NOT NULL,
    snapshot_ts INTEGER NOT NULL,       -- the cycle's id, matching book_top
    fetched_ts REAL,                    -- when THIS ladder's response arrived
    side TEXT NOT NULL,                 -- 'yes_bid' or 'no_bid'; the raw sides, undistorted
    level INTEGER NOT NULL,             -- 0 = best of that side
    price REAL NOT NULL,
    qty REAL NOT NULL,
    PRIMARY KEY (ticker, snapshot_ts, side, level)
);

CREATE TABLE IF NOT EXISTS cycles (
    cycle_ts INTEGER PRIMARY KEY,
    duration_s REAL,
    series_ok INTEGER, series_failed INTEGER,
    markets_seen INTEGER, in_window INTEGER,
    top_written INTEGER,
    depth_markets INTEGER, depth_rows INTEGER, depth_failed INTEGER,
    note TEXT
);
"""


def open_db(path: str) -> sqlite3.Connection:
    db = sqlite3.connect(path, timeout=30)
    db.execute("PRAGMA journal_mode=WAL")
    db.executescript(SCHEMA)
    db.commit()
    return db


# ------------------------------------------------------------------- collection


def list_series(series: str) -> list[dict]:
    """Every open market of one series, following the cursor."""
    out, cursor = [], None
    while True:
        path = f"/markets?series_ticker={series}&status=open&limit=200"
        if cursor:
            path += f"&cursor={cursor}"
        d = get(path)
        out += d.get("markets") or []
        cursor = d.get("cursor")
        if not cursor:
            return out


def fetch_depth(tickers: list[str]) -> dict[str, tuple[dict, float]]:
    """Ladders for several markets, keyed by the ticker the RESPONSE names.

    The parameter must be repeated -- `tickers=A&tickers=B` -- and this is not cosmetic. Verified
    live 2026-11-16: the comma form `tickers=A,B` is accepted and returns **one** entry whose
    `ticker` is the literal string "A,B" with both sides EMPTY. That is indistinguishable from a
    market with nothing resting, so a comma call does not fail, it silently fabricates empty books
    for every market asked about. Hence two rules here:

      * never pair a response entry with an input by position, only by the ticker it names;
      * refuse any entry whose ticker was not asked for (a comma-joined label never will be).

    Checked against the per-market endpoint on the same instant: both gave 21 NO levels.
    """
    got: dict[str, tuple[dict, float]] = {}
    for i in range(0, len(tickers), DEPTH_CHUNK):
        chunk = tickers[i:i + DEPTH_CHUNK]
        want = set(chunk)
        d = get("/markets/orderbooks?" + "&".join(f"tickers={t}" for t in chunk))
        at = time.time()
        for b in d.get("orderbooks") or []:
            t = b.get("ticker")
            if t in want:
                got[t] = (b, at)
    return got


def cycle(db: sqlite3.Connection, series_list: list[str], window_min: float,
          depth_max: int, verbose: bool = True) -> dict:
    """One snapshot pass. Never raises: a failing series or market is skipped and counted."""
    t0 = time.time()
    now = int(t0)
    series_ok = series_failed = 0
    rows, in_window, depth_candidates = [], 0, []

    for s in series_list:
        try:
            markets = list_series(s)
            at = time.time()          # when THIS listing landed, not when the cycle began
            series_ok += 1
        except Exception as e:                                  # noqa: BLE001
            series_failed += 1
            if verbose:
                print(f"  ! series {s}: {type(e).__name__}: {e}", flush=True)
            continue
        for m in markets:
            t = m.get("ticker")
            if not t:
                continue
            close_ts = iso_to_ts(m.get("close_time"))
            lead_s = int(close_ts - now) if close_ts is not None else None
            # Snapshot only what is near its close. Everything else is hours of flat book.
            if lead_s is None or not (-GRACE_MIN * 60 <= lead_s <= window_min * 60):
                continue
            in_window += 1
            top = top_from_market(m)
            rows.append((t, now, round(at, 3), s, int(close_ts), lead_s, m.get("status"),
                         m.get("exchange_index"), top["yes_bid"], top["yes_bid_size"],
                         top["yes_ask"], top["yes_ask_size"], top["last_price"],
                         top["volume"], top["volume_24h"], top["open_interest"]))
            a = top["yes_ask"]
            if a is not None and DEPTH_ASK_LO <= a <= DEPTH_ASK_HI:
                depth_candidates.append((lead_s, t))

    db.executemany(
        "INSERT OR IGNORE INTO book_top (ticker,snapshot_ts,fetched_ts,series,close_ts,lead_s,"
        "status,exchange_index,yes_bid,yes_bid_size,yes_ask,yes_ask_size,last_price,volume,"
        "volume_24h,open_interest) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows)

    # Ladders: nearest to close first, since that is the part of the curve we cannot measure.
    depth_candidates.sort()
    want = [t for _, t in depth_candidates[:depth_max]]
    depth_rows = depth_failed = 0
    if want:
        try:
            books = fetch_depth(want)
        except Exception as e:                                  # noqa: BLE001
            books, depth_failed = {}, len(want)
            if verbose:
                print(f"  ! depth: {type(e).__name__}: {e}", flush=True)
        drows = []
        for t, (b, at_d) in books.items():
            ob = b.get("orderbook_fp") or {}
            for side in ("yes_dollars", "no_dollars"):
                levels = ob.get(side) or []          # [] and absent both mean nothing resting
                parsed = []
                for lv in levels:
                    if not lv or len(lv) < 2:
                        continue
                    p, c = num(lv[0]), num(lv[1])
                    if p is None or c is None or c <= 0:
                        continue
                    parsed.append((p, c))
                parsed.sort(key=lambda x: -x[0])      # level 0 = best bid of that side
                tag = "yes_bid" if side == "yes_dollars" else "no_bid"
                for i, (p, c) in enumerate(parsed):
                    drows.append((t, now, round(at_d, 3), tag, i, p, c))
        db.executemany("INSERT OR IGNORE INTO book_depth (ticker,snapshot_ts,fetched_ts,side,"
                       "level,price,qty) VALUES (?,?,?,?,?,?,?)", drows)
        depth_rows = len(drows)

    dur = time.time() - t0
    db.execute("INSERT OR REPLACE INTO cycles (cycle_ts,duration_s,series_ok,series_failed,"
               "markets_seen,in_window,top_written,depth_markets,depth_rows,depth_failed,note) "
               "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
               (now, round(dur, 2), series_ok, series_failed, in_window, in_window, len(rows),
                len(want), depth_rows, depth_failed, None))
    db.commit()
    if verbose:
        print(f"  cycle {utc(now):%H:%M:%S}Z  "
              f"series {series_ok}/{series_ok + series_failed}  in-window {in_window}  "
              f"top {len(rows)}  depth {len(want)} mkts/{depth_rows} rows  {dur:.1f}s", flush=True)
    return {"in_window": in_window, "top": len(rows), "depth_rows": depth_rows,
            "series_failed": series_failed, "duration_s": dur}


def next_window_start(series_list: list[str], window_min: float) -> float | None:
    """Seconds until the next market comes within the window, or None if nothing is scheduled."""
    now = time.time()
    soonest = None
    for s in series_list:
        try:
            markets = list_series(s)
        except Exception:                                       # noqa: BLE001
            continue
        for m in markets:
            ct = iso_to_ts(m.get("close_time"))
            if ct is None:
                continue
            start = ct - window_min * 60
            if start > now and (soonest is None or start < soonest):
                soonest = start
    return None if soonest is None else soonest - now


def show_stats(db: sqlite3.Connection) -> None:
    q = db.execute("SELECT COUNT(*), COUNT(DISTINCT ticker), MIN(snapshot_ts), MAX(snapshot_ts) "
                   "FROM book_top").fetchone()
    n, mkts, lo, hi = q
    print(f"book_top:   {n:>8} snapshots over {mkts} markets")
    if n:
        print(f"            {utc(lo):%Y-%m-%d %H:%M}Z .. {utc(hi):%Y-%m-%d %H:%M}Z")
        print("  by lead bucket (minutes before close), snapshots with a usable ask:")
        for lo_m, hi_m in ((0, 5), (5, 15), (15, 30), (30, 60), (60, 90), (90, 120)):
            r = db.execute("SELECT COUNT(*), COUNT(yes_ask) FROM book_top "
                           "WHERE lead_s >= ? AND lead_s < ?", (lo_m * 60, hi_m * 60)).fetchone()
            print(f"    {lo_m:>3}-{hi_m:<3} min: {r[0]:>7} snapshots, {r[1]:>7} with an ask")
        r = db.execute("SELECT COUNT(*) FROM book_top WHERE yes_ask IS NULL").fetchone()[0]
        print(f"  empty-ask snapshots (sentinel stored as NULL, never 1.00): {r}")
    d = db.execute("SELECT COUNT(*), COUNT(DISTINCT ticker) FROM book_depth").fetchone()
    print(f"book_depth: {d[0]:>8} levels over {d[1]} markets")
    c = db.execute("SELECT COUNT(*), SUM(series_failed), SUM(depth_failed), AVG(duration_s) "
                   "FROM cycles").fetchone()
    print(f"cycles:     {c[0]:>8} runs, {c[1] or 0} series failures, {c[2] or 0} depth failures, "
          f"avg {c[3] or 0:.1f}s")


def export_csv(db: sqlite3.Connection, out_dir: str) -> int:
    """Dump every table to `<out_dir>/<table>-<UTC stamp>.csv.gz`.

    The collector's host is an ephemeral CI runner, so `commodity_book.db` does
    not outlive the job; these files do. Gzipped CSV rather than the database
    because the rows are append-only and compress about 6x, and because a text
    format can be scored by anything.

    NULL stays EMPTY, never 0 and never 1.00: a side with nothing resting is the
    sentinel this whole collector exists to record correctly, and writing it as a
    price is the bug that once made a stop-loss replay report a 97% trigger rate.
    """
    os.makedirs(out_dir, exist_ok=True)
    stamp = time.strftime("%Y%m%dT%H%M", time.gmtime())
    rows = 0
    for table in ("book_top", "book_depth", "cycles"):
        cur = db.execute(f"SELECT * FROM {table}")
        cols = [d[0] for d in cur.description]
        path = os.path.join(out_dir, f"{table}-{stamp}.csv.gz")
        n = 0
        with gzip.open(path, "wt", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh)
            w.writerow(cols)
            for r in cur:
                w.writerow(["" if v is None else v for v in r])
                n += 1
        rows += n
        print(f"  {path}  {n} rows", flush=True)
    return rows


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=DEFAULT_DB)
    ap.add_argument("--series", default=",".join(SERIES),
                    help="comma-separated series tickers (default: the 12 commodity ranges)")
    ap.add_argument("--window", type=float, default=WINDOW_MIN,
                    help="snapshot markets within this many minutes of close (default 120)")
    ap.add_argument("--interval", type=int, default=INTERVAL_S,
                    help="seconds between cycles inside a window (default 60)")
    ap.add_argument("--depth-max", type=int, default=DEPTH_MAX,
                    help="markets per cycle to pull full ladders for (default 40; 0 disables)")
    ap.add_argument("--once", action="store_true", help="one cycle and exit")
    ap.add_argument("--cycles", type=int, default=0, help="run this many cycles then exit")
    ap.add_argument("--stats", action="store_true", help="print what has been collected and exit")
    ap.add_argument("--export", metavar="DIR",
                    help="write every table to gzipped CSV in DIR and exit. The collector runs on an\nephemeral CI runner, so the database dies with the job: the CSVs are what survives.")
    a = ap.parse_args()

    db = open_db(a.db)
    if a.export:
        print(f"exporting {a.db} -> {a.export}", flush=True)
        print(f"{export_csv(db, a.export)} rows exported", flush=True)
        return 0
    if a.stats:
        show_stats(db)
        return 0

    series_list = [s.strip() for s in a.series.split(",") if s.strip()]
    print(f"book_collector: {len(series_list)} series, window {a.window:.0f} min, "
          f"interval {a.interval}s, depth<={a.depth_max}  -> {a.db}", flush=True)

    if a.once:
        cycle(db, series_list, a.window, a.depth_max)
        return 0

    done = 0
    while True:
        try:
            r = cycle(db, series_list, a.window, a.depth_max)
        except Exception as e:                                  # noqa: BLE001
            # A cycle must never take the collector down; the next one runs.
            print(f"  ! cycle failed: {type(e).__name__}: {e}", flush=True)
            r = {"in_window": 0}
        done += 1
        if a.cycles and done >= a.cycles:
            return 0
        if r.get("in_window", 0) > 0:
            time.sleep(a.interval)
            continue
        # Nothing near close. Sleep until the next window rather than poll a flat book.
        try:
            wait = next_window_start(series_list, a.window)
        except Exception:                                       # noqa: BLE001
            wait = None
        if wait is None:
            nap = IDLE_POLL_S
            print(f"  idle: no close scheduled; re-checking in {nap // 60} min", flush=True)
        else:
            nap = max(30.0, min(wait, IDLE_POLL_S))
            print(f"  idle: next window opens in {wait / 60:.0f} min; "
                  f"sleeping {nap / 60:.0f} min", flush=True)
        time.sleep(nap)


if __name__ == "__main__":
    sys.exit(main())
