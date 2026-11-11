#!/usr/bin/env python3
"""
The record of every probability this skill states, and how it scored against the market.

Only logging the markets that became bets would grade the skill on the cases it felt sure about,
which is the one sample that cannot answer "is this analysis worth anything". So every candidate
that reaches a verdict is logged here, bet, watch and pass alike, with the price at that moment.
Once the market settles, both forecasts are scored on the same outcome.

    python3 estimates.py log --ticker KXCPI-26OCT-T0.2 --side yes --prob 0.48 --verdict pass \\
        --market-ask 0.44 --market-mid 0.435 --note "Cleveland Fed nowcast 0.24 vs 0.25 needed" \\
        [--prob-lo 0.45 --prob-hi 0.52] [--source "..."] [--id my-id]
    python3 estimates.py grade        # settle what has settled, then print the scoreboard
    python3 estimates.py list [--open] [--json]

The log is append-only JSON Lines. It defaults to <marketbot-sync>/research/estimates.jsonl, because the
record is worthless if it dies with the container -- commit and push it after logging or grading. Without
that checkout it falls back to ~/.cache/kalshi-prediction/estimates.jsonl and warns. Override with --file or
KALSHI_ESTIMATES. Grading only ever fills in the outcome fields of a row.

Brier score: (p - outcome)^2, lower is better, and the comparison that matters is mine against the
market's price at the same instant. A skill that cannot beat the mid over 30+ settled calls is not
adding information, whatever its P&L happens to look like.
"""
import argparse
import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

BASE = "https://api.elections.kalshi.com/trade-api/v2"
def _default_log():
    """
    The record has to outlive the machine the skill runs on, so it belongs in a git checkout, not a
    cache. The sync repository is the one place already kept in version control, so prefer it; fall
    back to the cache only when it is not here, and say so when that happens.
    """
    if os.environ.get("KALSHI_ESTIMATES"):
        return Path(os.environ["KALSHI_ESTIMATES"])
    for repo in (Path.home() / "marketbot-sync", Path.cwd().parent / "marketbot-sync"):
        if (repo / ".git").is_dir():
            return repo / "research" / "estimates.jsonl"
    return Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "kalshi-prediction" / "estimates.jsonl"


DEFAULT = _default_log()
VERDICTS = ("bet", "watch", "pass")


def market(ticker):
    url = f"{BASE}/markets/{urllib.parse.quote(ticker)}"
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "kalshi-prediction-skill"})
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r).get("market") or {}
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if e.code != 429 and e.code < 500:
                raise RuntimeError(f"GET {url}: HTTP {e.code}")
        except Exception:
            pass
        time.sleep(min(30, 3 * 2 ** attempt))
    raise RuntimeError(f"GET {url} kept failing")


def read(path):
    if not path.exists():
        return []
    out = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if line:
            try:
                out.append(json.loads(line))
            except ValueError:
                print(f"skipping unreadable line: {line[:80]}", file=sys.stderr)
    return out


def write_all(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text("".join(json.dumps(r) + "\n" for r in rows))
    tmp.replace(path)


def resolve(a):
    path = Path(a.file) if a.file else DEFAULT
    if not a.file and ".cache" in path.parts:
        print(f"warning: no marketbot-sync checkout found, logging to {path}, which does not survive this machine.",
              file=sys.stderr)
    return path


def cmd_log(a):
    path = resolve(a)
    rows = read(path)
    now = datetime.now(timezone.utc)
    if not 0.0 <= a.prob <= 1.0:
        sys.exit("--prob must be between 0 and 1")
    if a.side not in ("yes", "no"):
        sys.exit("--side must be yes or no")
    m = market(a.ticker)
    if m is None or not m.get("ticker"):
        sys.exit(f"Kalshi has no market {a.ticker}: an estimate is never logged against a ticker that does not resolve")
    def f(x):
        try:
            return float(x)
        except (TypeError, ValueError):
            return None
    bid, ask = f(m.get("yes_bid_dollars")), f(m.get("yes_ask_dollars"))
    mid = a.market_mid if a.market_mid is not None else ((bid + ask) / 2 if bid is not None and ask is not None else None)
    paid = a.market_ask if a.market_ask is not None else (ask if a.side == "yes" else (1 - bid if bid is not None else None))
    # The market's probability FOR THE SIDE the estimate is about, so both forecasts score the same event.
    market_p = mid if a.side == "yes" else (1 - mid if mid is not None else None)
    row = {
        "id": a.id or f"{now:%Y%m%d-%H%M%S}-{a.ticker}",
        "at": now.isoformat(timespec="seconds"),
        "ticker": a.ticker, "side": a.side, "verdict": a.verdict,
        "prob": a.prob, "prob_lo": a.prob_lo, "prob_hi": a.prob_hi,
        "market_mid": mid, "market_p_side": market_p, "price_paid": paid,
        "close_time": m.get("close_time"), "condition": m.get("yes_sub_title") or m.get("title"),
        "note": a.note, "source": a.source,
        "result": None, "outcome": None, "brier": None, "market_brier": None, "settled_at": None,
    }
    if any(r["id"] == row["id"] for r in rows):
        sys.exit(f"an estimate with id {row['id']} is already logged")
    rows.append(row)
    write_all(path, rows)
    print(f"logged {row['id']}: {a.verdict.upper()} {a.side.upper()} {a.ticker} at p={a.prob:.2f} "
          f"against market {market_p if market_p is None else round(market_p, 3)} ({path})")


def cmd_grade(a):
    path = resolve(a)
    rows = read(path)
    if not rows:
        sys.exit(f"no estimates logged yet ({path})")
    now = datetime.now(timezone.utc)
    graded = 0
    for r in rows:
        if r.get("result") or not r.get("close_time"):
            continue
        if datetime.fromisoformat(r["close_time"].replace("Z", "+00:00")) > now:
            continue
        m = market(r["ticker"])
        res = (m or {}).get("result")
        if res not in ("yes", "no"):
            continue
        outcome = 1.0 if res == r["side"] else 0.0
        r["result"] = res
        r["outcome"] = outcome
        r["brier"] = round((r["prob"] - outcome) ** 2, 6)
        if r.get("market_p_side") is not None:
            r["market_brier"] = round((r["market_p_side"] - outcome) ** 2, 6)
        r["settled_at"] = now.isoformat(timespec="seconds")
        graded += 1
        time.sleep(0.3)
    if graded:
        write_all(path, rows)
    done = [r for r in rows if r.get("result")]
    both = [r for r in done if r.get("market_brier") is not None]
    print(f"{len(rows)} estimates logged, {len(done)} settled ({graded} newly graded), {len(rows) - len(done)} open.")
    if not done:
        return
    mine = sum(r["brier"] for r in done) / len(done)
    print(f"\nBrier (lower is better), all settled: mine {mine:.4f}", end="")
    if both:
        mkt = sum(r["market_brier"] for r in both) / len(both)
        me_on_both = sum(r["brier"] for r in both) / len(both)
        d = me_on_both - mkt
        se = math.sqrt(sum(((r["brier"] - r["market_brier"]) - d) ** 2 for r in both) / max(1, len(both) - 1) / len(both)) if len(both) > 1 else float("nan")
        print(f" | market {mkt:.4f} on the same {len(both)} | difference {d:+.4f}", end="")
        if len(both) > 1:
            print(f" (95% {d - 1.96 * se:+.4f} to {d + 1.96 * se:+.4f})")
            if len(both) < 30:
                print(f"  {len(both)} settled: too few to read. The bar is 30+, and the difference must exclude zero.")
            elif d + 1.96 * se < 0:
                print("  Beats the market, interval excluding zero.")
            elif d - 1.96 * se > 0:
                print("  LOSES to the market, interval excluding zero. Stop acting on these estimates.")
            else:
                print("  No measurable difference from the market price.")
        else:
            print()
    print(f"\n{'verdict':8} {'n':>3} {'hit rate':>9} {'my Brier':>9} {'mkt Brier':>9}")
    for v in VERDICTS:
        g = [r for r in done if r.get("verdict") == v]
        if not g:
            continue
        gb = [r for r in g if r.get("market_brier") is not None]
        print(f"{v:8} {len(g):3} {sum(r['outcome'] for r in g) / len(g):8.0%} {sum(r['brier'] for r in g) / len(g):9.4f} "
              f"{(sum(r['market_brier'] for r in gb) / len(gb)) if gb else float('nan'):9.4f}")
    print(f"\n{'ticker':34} {'side':4} {'verdict':7} {'mine':>5} {'mkt':>5} {'out':>4}")
    for r in sorted(done, key=lambda r: r["at"])[-25:]:
        print(f"{r['ticker']:34} {r['side']:4} {r['verdict']:7} {r['prob']:5.2f} "
              f"{(r.get('market_p_side') if r.get('market_p_side') is not None else float('nan')):5.2f} {r['outcome']:4.0f}")


def cmd_list(a):
    path = resolve(a)
    rows = read(path)
    if a.open:
        rows = [r for r in rows if not r.get("result")]
    if a.json:
        print(json.dumps(rows, indent=2)); return
    print(f"{'id':40} {'verdict':7} {'side':4} {'p':>5} {'mkt':>5} {'result':7} ticker")
    for r in rows:
        print(f"{r['id'][:40]:40} {r.get('verdict', ''):7} {r['side']:4} {r['prob']:5.2f} "
              f"{(r.get('market_p_side') if r.get('market_p_side') is not None else float('nan')):5.2f} "
              f"{r.get('result') or 'open':7} {r['ticker']}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--file", help=f"estimate log (default {DEFAULT})")
    sub = ap.add_subparsers(dest="cmd", required=True)
    lg = sub.add_parser("log", help="record one probability against the live price")
    lg.add_argument("--ticker", required=True)
    lg.add_argument("--side", required=True, choices=["yes", "no"])
    lg.add_argument("--prob", required=True, type=float, help="probability that SIDE wins, 0-1")
    lg.add_argument("--verdict", required=True, choices=list(VERDICTS))
    lg.add_argument("--prob-lo", type=float); lg.add_argument("--prob-hi", type=float)
    lg.add_argument("--market-ask", type=float, help="price paid for the side; read live when omitted")
    lg.add_argument("--market-mid", type=float, help="YES mid; read live when omitted")
    lg.add_argument("--note", default=""); lg.add_argument("--source", default=""); lg.add_argument("--id")
    lg.set_defaults(fn=cmd_log)
    gr = sub.add_parser("grade", help="settle what has settled and score it against the market")
    gr.set_defaults(fn=cmd_grade)
    ls = sub.add_parser("list", help="show the log")
    ls.add_argument("--open", action="store_true"); ls.add_argument("--json", action="store_true")
    ls.set_defaults(fn=cmd_list)
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
