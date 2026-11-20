"""Do hours 3 and 10 ET deserve to be skipped on the BTC deep-ITM ladder?

`skip_hours_et: 3,10,21,23` drops four close-hours. The setting's own
description says "session opens, where losses ran 3-7x higher", and CLAUDE.md
records that the claim "is supported by nothing in this repo" and that unskipping
03 and 10 is worth about 8% of throughput -- "a measurement to run, not a change
to make". This is that measurement.

**It cannot be run on the account's own record.** Hours 03 and 10 are traded
exactly zero times, so there is no fill history for them; the question is about
the MARKET in those hours. So: price every settled KXBTCD hour's deep-ITM
strikes from public candles at the ladder's own entry conditions, with zero
lookahead, grade at settlement, and compare the skipped hours with the traded
ones.

WHAT IT CAN AND CANNOT SETTLE
-----------------------------
This is a POPULATION measurement, not a fill measurement, and the repo is
emphatic that the two differ: on the weather book, adverse selection took a 0.73
population ratio to 0.96 on the quotes that actually got lifted. So a skipped
hour looking like a traded one says the SKIP HAS NO BASIS. It does not say the
hour would be profitable to trade, and nothing here justifies turning one on
without forward paper evidence.

THE CONTROL IS THE WHOLE DESIGN
-------------------------------
The traded hours must come out near the account's own realised ratio (0.72 over
11,623 contracts, from /portfolio/settlements). If they do not, the method is
measuring its own thresholds rather than the market -- which is exactly how
`screen_kalshi_pools.py` once reported zero pools including the one the account
trades. `main()` refuses to print a verdict when the control is out of range.

    python3 research/crypto/skipped_hours.py --days 30     # build + score
    python3 research/crypto/skipped_hours.py --score        # score the cache

Read-only and unauthenticated: it reuses crypto_tail_sample's `assert_read_only`
and its zero-lookahead `price_at`.
"""
from __future__ import annotations

import argparse
import math
import os
import random
import sqlite3
import sys
import time
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import crypto_tail_sample as cts

ET = ZoneInfo("America/New_York")
DB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "skipped_hours.db")

# The ladder's own entry conditions (DeepItmRule / the recovered desktop run).
BAND_LO, BAND_HI = 0.90, 0.96
MAX_OPEN = 8                      # distinct strikes per event-hour, one side each
LEAD_S = 1800                     # inside the fill window: ttc_min 300, ttc_max 3000
SKIPPED = (3, 10, 21, 23)         # skip_hours_et
ACCOUNT_RATIO = 0.72              # the control: /portfolio/settlements, 11,623 contracts

SCHEMA = """
CREATE TABLE IF NOT EXISTS pick (
    ticker TEXT PRIMARY KEY, event_ticker TEXT, close_ts INTEGER, et_hour INTEGER,
    side TEXT, entry REAL, strike REAL, spot REAL, candle_end INTEGER, lost INTEGER
);
CREATE TABLE IF NOT EXISTS hour_done (event_ticker TEXT PRIMARY KEY, picks INTEGER);
-- crypto_tail_sample's spot cache lives here too: its fetch_spot/spot_at take a
-- connection, so this database must carry the table they read.
CREATE TABLE IF NOT EXISTS spot (minute_ts INT PRIMARY KEY, close REAL);
"""


def db():
    c = sqlite3.connect(DB)
    c.executescript(SCHEMA)
    c.commit()
    return c


def et_hour(close_ts: int) -> int:
    return datetime.fromtimestamp(close_ts, timezone.utc).astimezone(ET).hour


def pick_hour(c, markets) -> int:
    """Replay the ladder's selector on one event-hour and store what it would hold.

    Deep in the money on either side, at the price the ladder would REST at:
    a YES bid is `yes_bid`; a NO bid is `1 - yes_ask`, because the orderbook
    carries only bids. Reading the ask instead is the mistake that made
    `deep_itm_screen.py` reject KXBTCD itself on its first run -- the ladder is
    post-only and never lifts an ask.
    """
    ev = markets[0]["event_ticker"]
    if c.execute("SELECT 1 FROM hour_done WHERE event_ticker=?", (ev,)).fetchone():
        return 0
    close_ts = cts.utc(markets[0]["close_time"])
    spot = cts.spot_at(c, close_ts - LEAD_S)
    if spot is None:
        return 0

    cands = []
    for m in markets:
        k = m.get("floor_strike")
        if not k:
            continue
        # Deep ITM means the strike is already passed: YES below spot, NO above.
        side = "yes" if k < spot else "no"
        p = cts.price_at(m["ticker"], close_ts, LEAD_S)
        time.sleep(0.10)
        if p is None:
            continue
        ask, bid, end, _vol, _oi = p
        if ask is None or bid is None:
            continue
        if side == "yes":
            if bid <= cts.SENTINEL_BID:      # nothing resting: no queue to join
                continue
            entry = bid
        else:
            if ask >= cts.SENTINEL_ASK:      # empty side; 1-1.00 is not a price
                continue
            entry = round(1.0 - ask, 4)
        if not (BAND_LO - 1e-9 <= entry <= BAND_HI + 1e-9):
            continue
        lost = 0 if ((m.get("result") == "yes") == (side == "yes")) else 1
        cands.append((m["ticker"], ev, close_ts, et_hour(close_ts), side,
                      entry, k, spot, end, lost))

    # slot_order: cheapest, one side per ticker, up to MAX_OPEN distinct strikes.
    cands.sort(key=lambda r: (r[5], r[0]))
    seen, keep = set(), []
    for r in cands:
        if r[0] in seen:
            continue
        seen.add(r[0])
        keep.append(r)
        if len(keep) >= MAX_OPEN:
            break

    c.executemany("INSERT OR REPLACE INTO pick VALUES(?,?,?,?,?,?,?,?,?,?)", keep)
    c.execute("INSERT OR REPLACE INTO hour_done VALUES(?,?)", (ev, len(keep)))
    c.commit()
    return len(keep)


def build(days: int, max_hours: int) -> None:
    c = db()
    hi = int(time.time())
    lo = hi - days * 86400
    cts.fetch_spot(c, lo - 7200, hi)
    hours = cts.hours_in(lo, hi)
    print(f"{len(hours)} settled event-hours in the window", flush=True)
    # Walk hours in a FIXED order (by close time), never sampled by outcome, and
    # stop on a budget rather than selecting which hours get measured.
    todo = sorted(hours.items(), key=lambda kv: cts.utc(kv[1][0]["close_time"]))
    done = 0
    for ev, ms in todo:
        if done >= max_hours:
            print(f"  stopping at the {max_hours}-hour budget", flush=True)
            break
        n = pick_hour(c, ms)
        if n:
            done += 1
            if done % 20 == 0:
                print(f"  {done} hours, {c.execute('SELECT COUNT(*) FROM pick').fetchone()[0]} picks", flush=True)


def boot(groups, stat, n=4000, seed=7):
    """Bootstrap clustered by EVENT-HOUR: every strike of one hour settles off
    one price move, so hours are the independent unit, not strikes."""
    keys = list(groups)
    if len(keys) < 3:
        return None, None
    rng = random.Random(seed)
    out = []
    for _ in range(n):
        s = [groups[rng.choice(keys)] for _ in keys]
        v = stat([r for g in s for r in g])
        if v is not None:
            out.append(v)
    if not out:
        return None, None
    out.sort()
    return out[int(0.025 * len(out))], out[int(0.975 * len(out))]


def ratio_of(rows):
    implied = sum(1.0 - r["entry"] for r in rows)
    return (sum(r["lost"] for r in rows) / implied) if implied > 0 else None


def ev_of(rows):
    # Resting fills pay no fee on these series (130 of 130 measured at 0.00).
    if not rows:
        return None
    return 100.0 * sum((1.0 - r["entry"]) if not r["lost"] else -r["entry"] for r in rows) / len(rows)


def score() -> int:
    c = db()
    c.row_factory = sqlite3.Row
    rows = [dict(r) for r in c.execute("SELECT * FROM pick")]
    if not rows:
        print("no picks cached; run without --score first")
        return 1
    hours = {}
    for r in rows:
        hours.setdefault(r["event_ticker"], []).append(r)
    print(f"\n{len(rows)} picks over {len(hours)} event-hours, "
          f"{len({r['et_hour'] for r in rows})} distinct ET hours, lead {LEAD_S}s\n")

    def block(label, keep):
        sub = [r for r in rows if keep(r["et_hour"])]
        if not sub:
            print(f"{label:28} no picks")
            return None
        g = {}
        for r in sub:
            g.setdefault(r["event_ticker"], []).append(r)
        ra, e = ratio_of(sub), ev_of(sub)
        lo, hi = boot(g, ratio_of)
        ci = f"[{lo:.2f}, {hi:.2f}]" if lo is not None else "--"
        print(f"{label:28} {len(sub):6} picks {len(g):4} hours  lost {sum(r['lost'] for r in sub):4}"
              f" / {sum(1 - r['entry'] for r in sub):7.1f} implied  ratio {ra:.2f} {ci:>14}  {e:+.2f}c")
        return ra

    traded = block("traded hours (control)", lambda h: h not in SKIPPED)
    skipped = block("skipped hours (all four)", lambda h: h in SKIPPED)
    print()
    for h in SKIPPED:
        block(f"  hour {h:02d} ET", lambda x, h=h: x == h)
    print()
    print("per ET hour, every hour:")
    for h in sorted({r["et_hour"] for r in rows}):
        mark = "  SKIPPED" if h in SKIPPED else ""
        block(f"  {h:02d} ET{mark}", lambda x, h=h: x == h)

    # The control decides whether any of the above may be read at all.
    print()
    if traded is None:
        print("VERDICT WITHHELD: no traded-hour picks, so there is no control.")
        return 1
    if not (0.45 <= traded <= 1.05):
        print(f"VERDICT WITHHELD: the control reads {traded:.2f} against the account's "
              f"{ACCOUNT_RATIO:.2f} on 11,623 contracts. A method that cannot reproduce the "
              f"hours the ladder really trades is measuring its own thresholds, not the market.")
        return 1
    print(f"control OK: traded hours {traded:.2f} against the account's {ACCOUNT_RATIO:.2f}.")
    if skipped is None:
        print("The skipped hours carry NO in-band strikes at all -- the skip costs nothing.")
    else:
        print(f"The skipped hours' population reads {skipped:.2f} against {traded:.2f}.")
        print("POPULATION ONLY. Fills are adversely selected (weather: 0.73 -> 0.96), so this")
        print("can show the skip has no basis; it cannot show an hour is profitable. Any change")
        print("needs forward paper evidence, and the lot-7 step is mid-flight besides.")
    return 0


def main(argv) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--days", type=int, default=30)
    ap.add_argument("--max-hours", type=int, default=400)
    ap.add_argument("--score", action="store_true", help="score the cache and exit")
    a = ap.parse_args(argv[1:])
    if not a.score:
        build(a.days, a.max_hours)
    return score()


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
