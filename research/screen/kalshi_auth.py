"""Signed read-only GET against Kalshi, from environment credentials.

The measurement scripts beside this file need the ACCOUNT's own settlements,
which is the one thing in this repo's research that is not public. Credentials
come from the environment and are never written anywhere:

    export KALSHI_KEY_ID=...
    export KALSHI_PRIVATE_KEY="$(sed '1d;$d' kalshi.pem | tr -d '\\n')"   # PKCS#8 body, no armour

Read-only by construction: `get()` signs only GET, and `assert_read_only()`
refuses any path that is not one of the portfolio reads these scripts use. The
same guard shape as research/commodity/book_collector.py.
"""
from __future__ import annotations

import base64
import json
import os
import time
import urllib.error
import urllib.request

BASE = "https://api.elections.kalshi.com/trade-api/v2"
ALLOWED = ("/portfolio/settlements", "/portfolio/fills", "/portfolio/positions", "/portfolio/order_groups")
RETRY_SLEEPS = (1, 3, 8, 20)


def assert_read_only(path: str) -> None:
    """A research script must not be able to reach an order endpoint at all."""
    base = path.split("?")[0]
    if not any(base == a for a in ALLOWED):
        raise RuntimeError(f"refusing {path}: only {ALLOWED} are allowed here")


def _key():
    from cryptography.hazmat.primitives import serialization
    raw = os.environ["KALSHI_PRIVATE_KEY"].strip()
    if "BEGIN" not in raw:                       # the env var holds the bare PKCS#8 body
        raw = "-----BEGIN PRIVATE KEY-----\n" + raw + "\n-----END PRIVATE KEY-----\n"
    return serialization.load_pem_private_key(raw.encode(), password=None)


def get(path: str, _sleep=time.sleep):
    """GET `path`, signed. Returns (payload, error); 429 is retried."""
    assert_read_only(path)
    key, kid = _key(), os.environ["KALSHI_KEY_ID"]
    for i, nap in enumerate((*RETRY_SLEEPS, None)):
        ts = str(int(time.time() * 1000))
        sig = base64.b64encode(key.sign(f"{ts}GET/trade-api/v2{path.split('?')[0]}".encode())).decode()
        req = urllib.request.Request(BASE + path, headers={
            "KALSHI-ACCESS-KEY": kid, "KALSHI-ACCESS-SIGNATURE": sig,
            "KALSHI-ACCESS-TIMESTAMP": ts, "User-Agent": "research",
        })
        try:
            return json.load(urllib.request.urlopen(req, timeout=30)), None
        except urllib.error.HTTPError as e:
            # A rate-limited page yields an EMPTY result, which reads exactly like
            # "there is nothing here" -- so 429 is retried inside get(), never
            # at a call site.
            if e.code == 429 and nap is not None:
                _sleep(nap); continue
            return None, f"HTTP {e.code}"
        except Exception as e:
            if nap is not None:
                _sleep(nap); continue
            return None, str(e)[:160]
    return None, "exhausted"


def page(path: str, field: str, max_pages: int = 60):
    """Every page of a cursor-paginated read. /portfolio/settlements caps at 100
    per page, and reading it UNPAGINATED is the bug that once left weatherbot's
    settlements table holding 159 of 425 records."""
    out, cursor = [], None
    for _ in range(max_pages):
        sep = "&" if "?" in path else "?"
        d, e = get(f"{path}{sep}limit=200" + (f"&cursor={cursor}" if cursor else ""))
        if not d:
            break
        out += d.get(field) or []
        cursor = d.get("cursor")
        if not cursor:
            break
    return out


COMMODITY = ("KXBRENT", "KXWTI", "KXNATGAS", "KXGOLD", "KXSILVER", "KXCOPPER")


def book(ticker: str) -> str:
    """Which of the account's books a settled market belongs to."""
    if ticker.startswith("KXBTCD"):
        return "btc_ladder"
    if any(ticker.startswith(c) for c in COMMODITY):
        return "commodity"
    if "HIGH" in ticker or "LOW" in ticker:
        return "weather"
    return "other"


def num(row: dict, field: str) -> float:
    """Kalshi sends prices and sizes as fixed-point STRINGS; a missing one is 0.0 here."""
    try:
        return float(row.get(field))
    except (TypeError, ValueError):
        return 0.0
