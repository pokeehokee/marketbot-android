"""Score buying the >=20x tail of KXBTCD.

Headline is the RATIO: realised wins / implied wins (the sum of the prices paid).
  ratio > 1  the tail is UNDERPRICED -> a buyer has an edge
  ratio < 1  the tail is OVERPRICED  -> the seller has it (what the app already does)

Every interval is bootstrapped CLUSTERED BY DAY, not by event hour. Hour
clustering is the obvious choice -- each hour settles off its own price move --
and it is WRONG here, because what generates a tail win is a day-level
volatility regime that persists across the hours. Measured on 2,343 quotes:
hour clustering gave a ratio CI of [0.17, 0.98] and an EV CI of [-1.46c,
-0.04c], both excluding fair; day clustering gives [0.15, 1.10] and [-1.50c,
+0.17c], both INCLUDING it. 14 of 23 wins fell on two days and one day paid 8x
its implied rate, so the effective sample is the day count (23), not the quote
count. Any Poisson-binomial p-value over these quotes assumes independence the
data does not have and will read several orders of magnitude too significant.

The rare event here is a WIN, so the sample is sized on expected wins, not on
quotes -- the inverse of the repo's "count the LOSSES, not the fills" rule.
"""
import sqlite3, math, random, sys, os

DB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "crypto_tail.db")
random.seed(7)


def fee_per_contract(p, stake=1.0):
    """Kalshi rounds the fee UP per order, so it depends on order size.
    A $1 stake buys floor(1/p) contracts; spread that order's fee over them."""
    c = max(1, int(stake / p))
    f = math.ceil(0.07 * c * p * (1 - p) * 100) / 100.0
    return f / c


def load(lead=1800, where="1=1", by="day"):
    c = sqlite3.connect(DB)
    key = ("strftime('%Y-%m-%d', close_ts, 'unixepoch')" if by == "day" else "event_ticker")
    rows = c.execute(f"SELECT {key}, side, ask, won, dist, strike, spot, close_ts "
                     f"FROM quote WHERE lead_s={lead} AND ({where})").fetchall()
    out = {}
    for g, side, ask, won, dist, strike, spot, cts in rows:
        out.setdefault(g, []).append((side, ask, won, dist, cts))
    return out


def stats(by, taker=True):
    n = wins = 0
    implied = ev = 0.0
    for ev_t, qs in by.items():
        for side, ask, won, dist, cts in qs:
            n += 1
            wins += won
            implied += ask
            ev += won - ask - (fee_per_contract(ask) if taker else 0.0)
    return n, wins, implied, ev


def boot(by, taker=True, B=4000):
    keys = list(by)
    out = []
    for _ in range(B):
        s = [by[random.choice(keys)] for _ in keys]
        n = 0
        tot = 0.0
        for qs in s:
            for side, ask, won, dist, cts in qs:
                n += 1
                tot += won - ask - (fee_per_contract(ask) if taker else 0.0)
        if n:
            out.append(tot / n)
    out.sort()
    return out[int(0.025 * len(out))], out[int(0.975 * len(out))]


def line(name, by, taker=True):
    n, wins, implied, ev = stats(by, taker)
    if n == 0:
        print(f"{name:<34} n=0")
        return
    ratio = wins / implied if implied else float("nan")
    per = 100 * ev / n
    lo, hi = boot(by, taker) if len(by) > 2 else (float("nan"), float("nan"))
    print(f"{name:<34} days={len(by):>4} n={n:>5} wins={wins:>4} implied={implied:>7.1f} "
          f"ratio={ratio:>5.2f}  EV={per:>+6.2f}c  95%CI=[{100*lo:>+6.2f},{100*hi:>+6.2f}]")


if __name__ == "__main__":
    lead = int(sys.argv[1]) if len(sys.argv) > 1 else 1800
    by = load(lead)
    if not by:
        print("no sample yet")
        sys.exit()
    print(f"=== KXBTCD, buying the <=0.05 tail, {lead//60}-minute lead, taker fee charged\n")
    line("ALL", by)
    line("ALL, no fee (resting bid)", by, taker=False)
    print()
    for a, b in ((0.01, 0.015), (0.015, 0.025), (0.025, 0.035), (0.035, 0.045), (0.045, 0.055)):
        line(f"  ask {a:.3f}-{b:.3f}", load(lead, f"ask>={a} AND ask<{b}"))
    print()
    line("  side=yes (strike above spot)", load(lead, "side='yes'"))
    line("  side=no  (strike below spot)", load(lead, "side='no'"))
    print()
    cs = sorted({q[4] for qs in by.values() for q in qs})
    mid = cs[len(cs) // 2]
    line("  earlier half", load(lead, f"close_ts<{mid}"))
    line("  later half", load(lead, f"close_ts>={mid}"))
    print("\nratio > 1 = underpriced (buyer's edge); < 1 = overpriced (seller's edge).")
    print("A $1 stake at ask p buys floor(1/p) contracts; the fee is charged per order and spread over them.")
