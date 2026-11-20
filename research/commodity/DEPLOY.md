# Running `book_collector.py` continuously

The collector is only worth anything if it keeps running: it records books forward, so a day it
misses is a day that can never be recovered. Kalshi serves 1-minute candles but not historical
order books, which is the whole reason this exists.

It needs **no credentials** — the market listing and the orderbook reads are public — so it can run
anywhere with outbound HTTPS. It writes only `commodity_book.db` beside itself.

## What it costs

| | |
| --- | --- |
| calls per cycle | 12 series listings + up to 2 batch orderbook calls |
| tokens per cycle | ~140 of the **Read** budget (10 per request; `GET /account/endpoint_costs` is authoritative) |
| wall time per cycle | ~2 s measured |
| cycles per day | ~240 (two 2-hour windows at 60 s), near-zero outside them |
| disk | ~300 top rows + ~300 depth rows per in-window cycle; order of 100 MB/month, SQLite WAL |

Commodity closes cluster into about **two times a day** (measured 2026-11-16: 19:30Z with 30
markets, 22:00Z with 270), so it sleeps through roughly 20 hours of every day and only polls when
something is approaching its close. That is deliberate — outside the window the book is flat and
polling it is waste.

## systemd user service (preferred)

This mirrors the pattern weatherbot settled on after its pm2 daemon died with an operator session
and took every job with it: a **user service with `Restart=always` and linger on**, so it survives a
logout and comes back after a reboot.

`~/.config/systemd/user/commodity-book.service`:

```ini
[Unit]
Description=Kalshi commodity order book collector (read-only)
After=network-online.target

[Service]
Type=simple
WorkingDirectory=%h/marketbot-android/research/commodity
ExecStart=/usr/bin/python3 %h/marketbot-android/research/commodity/book_collector.py
Restart=always
RestartSec=30
StandardOutput=append:%h/marketbot-android/research/commodity/book_collector.log
StandardError=inherit

[Install]
WantedBy=default.target
```

```bash
loginctl enable-linger "$USER"          # without this it stops when you log out
systemctl --user daemon-reload
systemctl --user enable --now commodity-book.service
systemctl --user status commodity-book.service
tail -f research/commodity/book_collector.log
```

## pm2 alternative

```bash
pm2 start book_collector.py --name commodity-book --interpreter python3
pm2 save          # or a restart brings back the old process list
```

Deliberately second choice: a pm2 daemon with no supervisor is exactly what failed in weatherbot on
2026-09-09, and it was only noticed 1.7 hours later, across a settlement burst.

## Checking it is alive

```bash
python3 book_collector.py --stats          # snapshots, lead-bucket coverage, failures
sqlite3 commodity_book.db "SELECT datetime(MAX(cycle_ts),'unixepoch') FROM cycles;"
```

`--stats` prints snapshot counts per lead bucket. The bucket that matters is **0–60 min**: that is
the region candles could not measure, and it should fill up at roughly 300 rows per minute of
window once a close approaches. If the 0–60 buckets stay empty while the 60–120 ones fill, the
collector is dying partway through each window.

A cycle that fails is logged and the next one runs; `cycles.series_failed` and `cycles.depth_failed`
accumulate, so a steady nonzero count there means something is wrong even while rows keep arriving.

## Before trusting the data

Give it **at least a week**, covering both daily close times, and only then score it. One window is
one price move, and a commodity range settles off one price move — the same clustering that makes
per-fill CIs meaningless on this instrument. Score with CIs clustered by close-date, as
`score_commodity.py` already does.

Two things the dataset will let you check that candles could not:

- the **lead curve below 60 minutes**, on books that really existed rather than on a quote whose
  median age at the 60m mark was 90 minutes;
- how the **queue at the ask** evolves through the window, which is the input to a real fill model
  (the current one rests on 707 fill-checked quotes and a queue-depth relationship the data says is
  *not* established).


## The host, settled 2026-11-20 — and why it is many short runs

This document assumed a systemd user service on a machine that stays up. No such
service was ever created, so nothing was collected at all until the collector
moved to GitHub Actions (`.github/workflows/book-collector.yml`; the repository
is public, so minutes are free).

The first CI design copied this document's shape -- one long daily run -- and it
**did not fire on its first day**: zero runs, while a manual dispatch of the same
file worked. GitHub's `schedule` is best-effort and can be delayed or dropped, so
one fire a day means one dropped fire costs the whole day, which is exactly what
this document says cannot be recovered.

It now fires **every 15 minutes from 17:00Z to 22:59Z**, each run collecting ~14
minutes and exiting, tiling the window that holds both close clusters (19:30Z
and 22:00Z) plus the 120 minutes of tracking ahead of each. A dropped fire costs
15 minutes. Each run uploads its own gzipped CSV, because the runner's database
dies with the job.

A systemd service on a real machine would still be better -- it would cover the
whole day and never drop a slot -- and these units remain the way to do that.
