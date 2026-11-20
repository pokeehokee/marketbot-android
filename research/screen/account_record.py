"""What each of the account's books has actually earned, from the exchange.

The authoritative answer to "how is this book doing" is `/portfolio/settlements`
and nothing else. This repo has twice reported a book's record from a local
table and been wrong: the Claude-sync snapshot and the raw order journal gave
+$0.84/51 and -$0.91/15 for the same commodity book, which really stands at
-$0.66 over 57 settlements -- the snapshot's "51 settled" is the WIN count.

    python3 research/screen/account_record.py              # every book
    python3 research/screen/account_record.py commodity    # one book, per market

Read-only; needs the credentials described in kalshi_auth.py.
"""
from __future__ import annotations

import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kalshi_auth as k


def pnl(row: dict) -> float:
    """What the exchange paid, minus what it cost. `revenue` is in CENTS.

    Never derive this from a local size and price: the local row is what the
    app INTENDED, not what filled.
    """
    cost = k.num(row, "yes_total_cost_dollars") + k.num(row, "no_total_cost_dollars")
    return k.num(row, "revenue") / 100.0 - cost - k.num(row, "fee_cost")


def implied_losses(row: dict) -> float:
    """P(loss) the price paid implied: buying at p loses (1-p) of the time."""
    n = k.num(row, "yes_count_fp") + k.num(row, "no_count_fp")
    if n == 0:
        return 0.0
    cost = k.num(row, "yes_total_cost_dollars") + k.num(row, "no_total_cost_dollars")
    return n * (1.0 - cost / n)


def main(argv: list[str]) -> int:
    only = argv[1] if len(argv) > 1 else None
    rows = k.page("/portfolio/settlements", "settlements", max_pages=60)
    if not rows:
        print("no settlements read -- check KALSHI_KEY_ID / KALSHI_PRIVATE_KEY", flush=True)
        return 1
    print(f"{len(rows)} settlements\n")
    books = collections.defaultdict(list)
    for r in rows:
        books[k.book(r["ticker"])].append(r)

    print(f"{'book':12} {'settled':>8} {'contracts':>10} {'P&L':>9} {'per ct':>8} {'lost ct':>7} {'lost mkt':>8} {'implied':>8} {'ratio':>6} {'fees':>7}")
    for name, rs in sorted(books.items()):
        if only and name != only:
            continue
        ct = sum(k.num(r, "yes_count_fp") + k.num(r, "no_count_fp") for r in rs)
        tot = sum(pnl(r) for r in rs)
        imp = sum(implied_losses(r) for r in rs)
        # Losing CONTRACTS, not losing markets: `implied` is a sum over contracts,
        # so counting rows against it compares two different units and flatters
        # any book whose lot is above 1. On the BTC ladder at lot 5 that read
        # ratio 0.14 where the truth is 0.69.
        lost = sum(k.num(r, "yes_count_fp") + k.num(r, "no_count_fp") for r in rs if pnl(r) < 0)
        lost_markets = sum(1 for r in rs if pnl(r) < 0)
        fees = sum(k.num(r, "fee_cost") for r in rs)
        per = f"{100 * tot / ct:+.2f}c" if ct else "-"
        ratio = f"{lost / imp:.2f}" if imp > 0 else "-"
        print(f"{name:12} {len(rs):8} {ct:10.0f} {tot:+9.2f} {per:>8} {lost:7.0f} {lost_markets:8} {imp:8.1f} {ratio:>6} {fees:7.2f}")

    if only:
        print(f"\n{only}, per market, oldest first:")
        for r in sorted(books[only], key=lambda r: r["settled_time"]):
            print(f"  {r['settled_time'][:19]}  {r['ticker']:34} {r['market_result']:4} {pnl(r):+7.2f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
