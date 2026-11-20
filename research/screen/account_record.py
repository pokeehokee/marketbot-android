"""What each of the account's books has actually earned, graded from FILLS.

    python3 research/screen/account_record.py              # every book
    python3 research/screen/account_record.py commodity    # one book, per market

WHY NOT FROM SETTLEMENTS ALONE
------------------------------
The first version of this script derived P&L as `revenue - cost - fee` from
`/portfolio/settlements`, and that is WRONG for any position closed before
expiry. The settlement row books both legs of a round trip as costs:

    KXBTC15M-26NOV190930-30
      yes_count_fp 16.39   yes_total_cost_dollars 2.798860   <- the buys
      no_count_fp  16.39   no_total_cost_dollars  8.686600   <- the SELLS
      revenue 0                                              <- nothing held at expiry

Read as `revenue - cost - fee` that is **-$11.90**. From the fills it is bought
$2.80, sold $8.69, fees $0.42, i.e. **+$5.47** -- an error of $17.37 on one
market, in the wrong direction, and it landed on a day the operator asked about.
This is the trap the source repo already records: `/portfolio/fills` reports
`side` as the outcome ACQUIRED, not the trade direction, so a sell shows up as
acquiring the other side.

Only 2 of 3,310 settlements are round trips, so the blast radius is small -- but
"small and in exactly the place someone is looking" is how a wrong number gets
believed.

AND WHY IT IS NOT GRADED FROM FILLS EITHER
-----------------------------------------
The obvious repair -- "grade everything from fills" -- was written, run, and
produced **+$11,027 on the BTC ladder at +179c per contract**, on an account
holding about $100 and against a theoretical maximum of 100c. It treated
`action: "sell"` as closing a position. It is not:

    ('buy',  'yes', 'bid')  1281 ladder fills
    ('sell', 'no',  'ask')  1207 ladder fills     <- ALSO position-OPENING

On Kalshi, buying NO at 0.04 and selling YES at 0.96 are the same primitive, and
the exchange records the NO-side purchase as a SELL. So 1,207 openings were read
as disposals. This is the third time in one day the same documented trap bit:
"`side` is the outcome ACQUIRED, not the trade direction."

So the exchange's own settlement arithmetic stands, because it is the exchange's
accounting rather than a reconstruction -- which is what this repo's rule
("never grade P&L from local size and price") has always meant. Fills are used
for exactly one thing: the markets where the position was genuinely flat at
expiry, which the settlement row cannot express.

A GENUINE ROUND TRIP, AND HOW IT IS DETECTED
--------------------------------------------
`yes_count_fp > 0 AND no_count_fp > 0` on the settlement row: contracts acquired
on both sides of one market, i.e. closed out before expiry. That is **2 of
3,310** settlements, both manual 15-minute crypto trades; no bot market has it,
because within a market each strategy expresses one side only. For those two the
P&L is net cash from the fills, since nothing settled.

Fees are counted ONCE. A settlement's `fee_cost` is the same charge as its
fills' fees, not an extra one -- measured here they agree on **3,248 of 3,248**
markets that have both, to within half a cent.

Read-only; needs the credentials described in kalshi_auth.py.
"""
from __future__ import annotations

import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kalshi_auth as k

CENTS = 100.0

# A bot's P&L of this size on a ~$100 account is impossible, and the absence of
# any such check is what let +$11,027 print. Any total beyond this is reported as
# a failure rather than a result.
SANITY_ABS_TOTAL = 2_000.0


def side_price(f: dict) -> float:
    """The price of THIS fill on the side it transacted. `outcome_side` names the
    outcome acquired, so a NO fill is priced off `no_price_dollars`."""
    side = f.get("outcome_side") or f.get("side")
    return k.num(f, "yes_price_dollars" if side == "yes" else "no_price_dollars")


def settlement_pnl(r: dict) -> float:
    """The exchange's own figure: what it paid, less what the position cost,
    less the fee. Correct wherever the position was still on at expiry."""
    cost = k.num(r, "yes_total_cost_dollars") + k.num(r, "no_total_cost_dollars")
    return k.num(r, "revenue") / CENTS - cost - k.num(r, "fee_cost")


def is_round_trip(r: dict) -> bool:
    """Contracts acquired on BOTH sides of one market: closed before expiry.
    2 of 3,310 settlements, both manual; no bot market does this."""
    return k.num(r, "yes_count_fp") > 1e-9 and k.num(r, "no_count_fp") > 1e-9


def round_trip_pnl(fills: list[dict]) -> float:
    """Net cash, for a position that settled flat. Cash moves OUT on a buy and IN
    on a sell whatever side it names, and nothing is paid at expiry."""
    cash = 0.0
    for f in fills:
        n, px = k.num(f, "count_fp"), side_price(f)
        cash += (n * px) if f.get("action") == "sell" else -(n * px)
    return cash - sum(k.num(f, "fee_cost") for f in fills)


def contracts(r: dict) -> float:
    """Exposure taken. On a round trip the two sides are the same contracts
    twice, so count one of them."""
    y, n = k.num(r, "yes_count_fp"), k.num(r, "no_count_fp")
    return max(y, n) if is_round_trip(r) else y + n


def implied_losses(r: dict) -> float:
    """What the price PAID implied. A round trip never faced settlement, so it
    implies nothing: folding it in would compare a scalp against a bias."""
    if is_round_trip(r):
        return 0.0
    n = contracts(r)
    if n <= 0:
        return 0.0
    cost = k.num(r, "yes_total_cost_dollars") + k.num(r, "no_total_cost_dollars")
    return n * (1.0 - cost / n)


def main(argv: list[str]) -> int:
    only = argv[1] if len(argv) > 1 else None
    rows = k.page("/portfolio/settlements", "settlements", max_pages=80)
    if not rows:
        print("nothing read -- check KALSHI_KEY_ID / KALSHI_PRIVATE_KEY")
        return 1

    rts = [r for r in rows if is_round_trip(r)]
    by_ticker: dict[str, list[dict]] = {}
    if rts:
        want = {r["ticker"] for r in rts}
        for f in k.page("/portfolio/fills", "fills", max_pages=80):
            if f.get("ticker") in want:
                by_ticker.setdefault(f["ticker"], []).append(f)

    def pnl(r: dict) -> float:
        if is_round_trip(r):
            fs = by_ticker.get(r["ticker"])
            if fs:
                return round_trip_pnl(fs)
            print(f"  WARNING {r['ticker']} closed before expiry and has no fills on record; "
                  f"its settlement arithmetic double-counts the exit and is NOT used")
            return 0.0
        return settlement_pnl(r)

    print(f"{len(rows)} settled markets, {len(rts)} of them closed before expiry\n")
    books = {}
    for r in rows:
        books.setdefault(k.book(r["ticker"]), []).append(r)

    print(f"{'book':12} {'mkts':>5} {'contracts':>10} {'P&L':>9} {'per ct':>8} "
          f"{'lost ct':>8} {'implied':>8} {'ratio':>6} {'fees':>7}  round trips")
    total = 0.0
    for name, ms in sorted(books.items()):
        ct = sum(contracts(r) for r in ms)
        p = sum(pnl(r) for r in ms)
        total += p
        held = [r for r in ms if not is_round_trip(r)]
        lost = sum(contracts(r) for r in held if settlement_pnl(r) < 0)
        imp = sum(implied_losses(r) for r in held)
        rt = [r for r in ms if is_round_trip(r)]
        if only and name != only:
            continue
        print(f"{name:12} {len(ms):5} {ct:10.0f} {p:+9.2f} {(100 * p / ct if ct else 0):+7.2f}c "
              f"{lost:8.0f} {imp:8.1f} {(lost / imp if imp > 0 else 0):6.2f} "
              f"{sum(k.num(r, 'fee_cost') for r in ms):7.2f}  {len(rt)} ({sum(pnl(r) for r in rt):+.2f})")
    print(f"{'TOTAL':12} {len(rows):5} {sum(contracts(r) for r in rows):10.0f} {total:+9.2f}")

    # The check whose absence let +$11,027 stand. Per-contract P&L cannot exceed
    # a dollar, and a book this size cannot have made thousands.
    print()
    bad = []
    if abs(total) > SANITY_ABS_TOTAL:
        bad.append(f"total {total:+.2f} exceeds ${SANITY_ABS_TOTAL:,.0f}; this account holds ~$100")
    for name, ms in books.items():
        ct = sum(contracts(r) for r in ms)
        if ct and abs(100 * sum(pnl(r) for r in ms) / ct) > 100.0:
            bad.append(f"{name} is {100 * sum(pnl(r) for r in ms) / ct:+.0f}c per contract; "
                       f"a binary contract cannot pay more than 100c")
    if bad:
        print("SANITY CHECK FAILED -- do not report these figures:")
        for b in bad:
            print("  " + b)
        return 1
    print(f"sanity: total {total:+.2f}, every book within +/-100c per contract.")

    if only:
        print(f"\n{only}, per market, oldest first:")
        for r in sorted(books.get(only, []), key=lambda r: r["settled_time"]):
            tag = "  ROUND TRIP (net cash from fills)" if is_round_trip(r) else ""
            print(f"  {r['settled_time'][:19]}  {r['ticker']:34} {r['market_result']:4} "
                  f"{contracts(r):8.2f} ct  {pnl(r):+8.2f}{tag}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
