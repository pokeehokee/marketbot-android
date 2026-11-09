#!/usr/bin/env python3
"""Daily scan for Kalshi series that did not exist yesterday, scored for the longshot trade.

Reads every series (public API), compares with the list saved by the previous run, and for each new
series looks at its open markets: how many have a cheap side priced 2-25c, how deep the queue is at
the cheap side's price, and how wide the spread is. A series with several cheap markets, a short
queue and a tight spread is where a resting longshot order could fill: a candidate pool to measure
before anything else. Writes a Markdown report and the updated list.

    python tools/new_series_scan.py --state known_series.json --report report.md
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import time
import urllib.parse
import urllib.request

API = 'https://api.elections.kalshi.com/trade-api/v2'


def get(path, q=None, tries=4):
    url = API + path + ('?' + urllib.parse.urlencode(q) if q else '')
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'marketbot-new-series-scan'}), timeout=60) as r:
                return json.load(r)
        except Exception as e:  # noqa: BLE001
            if i == tries - 1:
                raise
            time.sleep(2 ** i)


def num(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def score(series):
    ms = get('/markets', {'series_ticker': series, 'status': 'open', 'limit': 200}).get('markets', [])
    cheap, queues, spreads = 0, [], []
    for m in ms:
        bid, ask = num(m.get('yes_bid_dollars')), num(m.get('yes_ask_dollars'))
        if bid is None or ask is None or bid <= 0 or ask >= 1 or ask <= bid:
            continue
        if 0.02 <= ask <= 0.25:
            cheap += 1; spreads.append(ask - bid); q = num(m.get('yes_ask_size_fp'))
            if q is not None:
                queues.append(q)
        elif 0.02 <= 1 - bid <= 0.25:
            cheap += 1; spreads.append(ask - bid); q = num(m.get('yes_bid_size_fp'))
            if q is not None:
                queues.append(q)
    mq = statistics.median(queues) if queues else None
    ms_ = statistics.median(spreads) if spreads else None
    candidate = cheap >= 3 and mq is not None and mq <= 60 and ms_ is not None and ms_ <= 0.05
    return dict(open=len(ms), cheap=cheap, queue=mq, spread=ms_, candidate=candidate)


def main(a):
    series = get('/series').get('series', [])
    now = {s['ticker']: dict(category=s.get('category'), frequency=s.get('frequency'), title=s.get('title')) for s in series if s.get('ticker')}
    known = json.load(open(a.state)) if os.path.exists(a.state) else None
    lines = []
    if known is None:
        lines.append(f'First run: {len(now)} series recorded. New ones are reported from tomorrow.')
        new = []
    else:
        new = sorted(set(now) - set(known))
        lines.append(f'{len(now)} series listed; {len(new)} new since the last run.')
    rows = []
    for t in new[:a.max_score]:
        try:
            rows.append((t, now[t], score(t)))
        except Exception as e:  # noqa: BLE001
            rows.append((t, now[t], dict(error=str(e))))
        time.sleep(0.2)
    if rows:
        cands = [r for r in rows if r[2].get('candidate')]
        lines += ['', f'**{len(cands)} look like candidate pools** (3+ cheap markets, median queue at the cheap price 60 or less, median spread 5c or less). '
                  'A candidate is something to measure on settled history before any paper test, never a strategy by itself.', '',
                  '| candidate | series | category | frequency | open | cheap 2-25c | median queue | median spread | title |',
                  '| --- | --- | --- | --- | --- | --- | --- | --- | --- |']
        for t, meta, sc in sorted(rows, key=lambda r: (not r[2].get('candidate'), -(r[2].get('cheap') or 0))):
            if 'error' in sc:
                lines.append(f"| | {t} | {meta['category']} | {meta['frequency']} | error: {sc['error'][:60]} | | | | |"); continue
            q = '-' if sc['queue'] is None else f"{sc['queue']:.0f}"; sp = '-' if sc['spread'] is None else f"{sc['spread'] * 100:.0f}c"
            lines.append(f"| {'YES' if sc['candidate'] else ''} | {t} | {meta['category']} | {meta['frequency']} | {sc['open']} | {sc['cheap']} | {q} | {sp} | {(meta['title'] or '')[:60]} |")
        if len(new) > a.max_score:
            lines.append(f'\n{len(new) - a.max_score} more new series not scored today (limit {a.max_score}).')
    open(a.report, 'w').write('\n'.join(lines) + '\n')
    json.dump(sorted(now), open(a.state, 'w'))
    print('\n'.join(lines[:5]))
    with open(os.environ.get('GITHUB_OUTPUT', os.devnull), 'a') as f:
        f.write(f"new={len(new)}\ncandidates={sum(1 for r in rows if r[2].get('candidate'))}\n")


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--state', required=True)
    p.add_argument('--report', required=True)
    p.add_argument('--max-score', type=int, default=80)
    main(p.parse_args())
