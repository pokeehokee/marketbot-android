---
name: kalshi-api
description: The Kalshi Trade API v2 as the official docs define it - endpoints, auth, fixed-point fields, paging, rate limits, exchange shards, the live/historical split, market lifecycle, settlement and fees. Use when reading or writing any code that calls Kalshi, when a Kalshi response is missing data you expected, when grading or reconciling positions, fills, orders or settlements, or when checking whether a field or endpoint still exists.
---

# Kalshi Trade API v2

Reviewed in full on 2026-11-11 against `https://docs.kalshi.com/openapi.yaml` (116 paths, 138
operations) and all 287 pages of `https://docs.kalshi.com/llms-full.txt`, including the changelog.

**The specs are the source of truth, not this file and not the SDKs** (the SDK page says so itself).
Re-check against them before acting on anything here:

```bash
curl -s https://docs.kalshi.com/openapi.yaml -o openapi.yaml       # REST
curl -s https://docs.kalshi.com/asyncapi.yaml -o asyncapi.yaml     # WebSocket
curl -s https://docs.kalshi.com/llms-full.txt -o docs.txt          # every guide page
curl -s https://api.elections.kalshi.com/trade-api/v2/historical/cutoff   # no auth
curl -s https://api.elections.kalshi.com/trade-api/v2/account/endpoint_costs   # no auth
```

## The trap that matters most: live data is not all the data

Kalshi splits exchange data into **live** and **historical** tiers, and the live endpoint stops
returning a record once it moves. This silently breaks any code that reads one endpoint and treats
"not found" as "nothing happened". Partitioned: markets, candlesticks, trades, orders, positions.
Events and series are never partitioned.

**Reading a settled position's result and P&L takes up to three calls, in this order:**

| # | Call | Returns |
| - | - | - |
| 1 | `GET /portfolio/settlements?ticker=X` | settlement records **still in the live set** only |
| 2 | `GET /portfolio/positions?ticker=X&settlement_status=settled` | settled, not yet archived: `realized_pnl_dollars`, `fees_paid_dollars` |
| 3 | `GET /historical/positions?ticker=X` | archived: position, realised P&L, fee data |

Quoting the Historical Data page: *"`GET /portfolio/settlements` reads only settled positions still
in the live data set. After archival, `GET /historical/positions` provides position, realized P&L,
and fee data, but there is currently no historical endpoint with the same settlement-record fields."*

So **a missing settlement is not evidence of a missing position.** Fall through all three before
concluding anything, and never grade from local arithmetic because step 1 came back empty.

- `GET /portfolio/positions` **defaults to `settlement_status=unsettled`** (added 2026-10-01).
  Settled positions are invisible unless you pass `settled` or `all`. This default has bitten us.
- Archival is **event-driven, not purely time-based**: a position moves only "after its event
  completes the live-to-historical handoff". `market_positions_last_updated_ts` on
  `GET /historical/cutoff` is a backfill horizon, not a visibility boundary. Short events (hourly
  crypto, for instance) can archive quickly; a long event can hold a settled position in the live set.
- The two reads are not one snapshot. Page live first, then historical, dedupe by
  (subaccount, ticker); an overlap can persist until a handoff retry.
- Positions endpoints: `/portfolio/positions` defaults to **all** subaccounts;
  `/historical/positions` defaults to **subaccount 0**. Pass `subaccount` explicitly for each.

Cutoff fields, each with its own window: `market_settled_ts` (markets + candlesticks),
`trades_created_ts` (trades and **user fills**), `orders_updated_ts` (canceled/executed orders;
resting orders always live), `market_positions_last_updated_ts` (as above).

## Market lifecycle: `determined` is not `finalized`

`initialized → active → (inactive) → closed → determined → finalized`, plus `disputed`/`amended`.

- **`result` is set at `determined`, before any payout.** A `settlement_timer_seconds` window then
  runs during which the result can be disputed. Treating `determined` as "settled" means expecting
  a settlement record that does not exist yet.
- Only `finalized` means positions have been paid out. It is terminal.
- `status=` filter values differ from the status strings: `unopened`, `open`, `paused`, `closed`,
  `settled` (= `finalized`).
- `inactive → active` reactivation **cancels every resting order**.
- `close_time` can move (`can_close_early`, event `close_date_updated`), so a close time cached at
  order placement can be wrong. Re-read it rather than trusting the stored copy.
- Time fields: `open_time`, `close_time`, `expected_expiration_time`, `latest_expiration_time`.
  `expiration_time` is the deprecated legacy field.

## Auth

Headers on every signed request: `KALSHI-ACCESS-KEY` (key id), `KALSHI-ACCESS-TIMESTAMP` (ms),
`KALSHI-ACCESS-SIGNATURE` = base64(sign(`timestamp_ms + METHOD + path`)).

- **Sign the path without the query string.** `/trade-api/v2/portfolio/orders?limit=5` signs
  `/trade-api/v2/portfolio/orders`.
- Ed25519 (recommended, signs the text directly) or RSA 2048 (RSA-PSS, SHA-256, MGF1-SHA256, salt
  length = digest length). The PEM header does not identify the type: PKCS#8 RSA also starts
  `BEGIN PRIVATE KEY`, so determine it from the parsed key.
- API keys may be restricted to one subaccount; requests omitting `subaccount` act on that one.

## Fixed point

- `*_dollars` are dollar strings, up to 4 decimals in requests, up to 6 in responses.
- `*_fp` are contract counts, 2 decimals, granularity 0.01 — **fractional contracts exist**.
- The `_fp` suffix alone does not say whether a field is a count or an amount; read the field's doc.
- Integer-cent fields cannot carry sub-cent prices. On sub-cent markets read the `_dollars` fields.
  Bare `yes_bid`-style integer fields can come back null; the `_dollars` ones are authoritative.
- `price_ranges` (`{start, end, step}` bands) is the source of truth for valid prices. Snap to the
  band's `step`. Never hardcode a tick, and never key logic off `price_level_structure`'s label.

## Orderbook

`GET /markets/{ticker}/orderbook` returns `orderbook_fp` with **only bids**: `yes_dollars` (YES bids)
and `no_dollars` (NO bids), each `[price_dollars, count_fp]`. So **the YES ask is `1 − best NO bid`**,
and the size at that ask is that NO level's size. An absent side is absent, never a price. Top of
book also appears on the market object as `yes_bid_size_fp` / `yes_ask_size_fp` (use these for depth;
`liquidity_dollars` was removed 2026-10-01).

WebSocket `orderbook_delta`/`orderbook_snapshot` are the exception to "direction does not change the
price": no-side levels default to **no-leg pricing**. Pass `use_yes_price: true` to get one scale;
that default will flip in a future release.

## Order direction: migrate off `action`/`side`

`outcome_side` (`yes`|`no`) and `book_side` (`bid`|`ask`) are canonical, and **`bid ≡ yes`,
`ask ≡ no`** always. Direction does not change the price: an order at `p` with `outcome_side=no`
matches one at the same `p` with `outcome_side=yes`.

| legacy `action` | legacy `side` | `outcome_side` | `book_side` |
| --- | --- | --- | --- |
| buy | yes | yes | bid |
| sell | no | yes | bid |
| buy | no | no | ask |
| sell | yes | no | ask |

Deprecated and **already past their stated removal dates** (May 2026): `action`, `side` (Order,
Fill), `is_yes` (Order WS), `purchased_side` (Fill WS), `taker_side` (Trade REST + WS → use
`taker_outcome_side`/`taker_book_side`). Read the new fields, keep the legacy ones only as a
fallback, and alarm when a response carries legacy fields only.

## Exchange shards

Categories map to matching engines. **Crypto and Commodities are shard 2**, Exotics/Combos shard 1,
some Sports shard 3, everything else shard 0. `exchange_index` on the market object is authoritative;
ticker shape says nothing.

- **Collateral checks run inside the matching engine, so the shard must be funded.** API orders are
  **not** funded automatically (the app and website auto-transfer; the API does not). An unfunded
  shard rejects orders with `insufficient_shard_balance`, whose `details` name the fix:
  `POST /portfolio/intra_exchange_instance_transfer`.
- `GET /portfolio/balance` without `exchange_index` covers **all** shards, so a healthy total can
  hide an empty shard. `balance_breakdown` gives per-shard balances and ignores the query params.
- Routing: `exchange_index >= 0` targets that shard; `-1` auto-routes and requires `market_ticker`;
  omitted with `market_ticker` auto-routes; otherwise shard 0. **An order id alone cannot identify a
  shard**, so cancels send `market_ticker`.
- Order groups do not work across shards; subaccount balances are per shard.
- Optional auto-rebalancing: `POST /portfolio/target_balance_allocation`.

## Rate limits

Tokens per request, default 10; `GET /account/endpoint_costs` is the authoritative non-default list
and needs no auth. `GET /account/limits` gives your tier and budgets.

- Two budgets: **Read** (GETs) and **Write** (orders, amends, cancels, order groups, RFQ quote flow).
  REST and FIX share them.
- `GET /portfolio/balance` has **its own bucket**: 1/s on Basic and Advanced, 5/s above, no burst.
  It does not touch the Read budget. Balance changes only on fills, settlements and transfers —
  react to those instead of polling.
- Per-shard Write budgets: an explicit `exchange_index >= 1` bills that shard's bucket and each
  shard carries the full tier budget; shard 0 and batch writes bill the unscoped bucket; auto-routed
  writes bill **every** shard's bucket.
- Buckets hold 1–3 seconds of budget depending on tier, so idle time can bank a burst.
- **Batching saves no tokens** — every item is billed, and the whole batch must fit at once.
- 429 returns `{"error": "too many requests"}` with **no `Retry-After` and no `X-RateLimit-*`
  headers**, and no cooldown. Back off exponentially.

## Paging

Cursor based: request without a cursor, follow the response's `cursor`, stop when it is absent or
empty. `limit` is 1–1000, default 100 (a few endpoints cap lower). The historical endpoints page the
same way. An unpaginated read of a paged endpoint is a silent truncation bug — weatherbot lost 266
settlements that way.

## Fees

The real model has three parts per fill, at `$0.000001` granularity:
**net fee = trade fee + rounding fee − rebate**, never below zero.

- Trade fee comes from the fee model, rounded up to `$0.000001`.
- A **rounding fee** restores the account's target balance precision (`$0.0001` direct,
  `$0.01` non-direct).
- A per-order **accumulator** carries rounding overpayment across all fills of that order, taker and
  maker alike, and rebates it in precision-sized increments.
- `fee_type` on the series is `quadratic`, `quadratic_with_maker_fees`,
  `quadratic_with_combo_maker_fees` or `flat`. A resting fill pays no fee on a plain `quadratic`
  series; a move to `quadratic_with_maker_fees` is a real change in economics — watch
  `GET /series/fee_changes` and `GET /events/fee_changes`.
- `ceil(0.07 × C × P × (1−P))` is a usable estimate for EV only. **Grade from the exchange's
  `fee_cost`**, never from the estimate.
- Settlement fees are zero for plain yes/no, non-zero only for sub-cent scalar settlement.

## Endpoints worth knowing that are easy to miss

| Endpoint | Why |
| --- | --- |
| `GET /historical/cutoff` | routes every query; no auth |
| `GET /portfolio/positions?settlement_status=settled\|all` | the settled-but-live window |
| `GET /historical/positions` | archived positions, with realised P&L |
| `GET /historical/fills` / `orders` / `markets` / `trades` | the other side of each cutoff |
| `GET /exchange/user_data_timestamp` | when balance/orders/fills/positions were last validated — the API lags the exchange |
| `GET /portfolio/summary/total_resting_order_value` | resting collateral, instead of recomputing it |
| `GET /portfolio/fills?ticker=A,B,...` | up to 100 tickers in one call (2026-10-08) |
| `GET /markets/orderbooks?tickers=` and `GET /portfolio/orders/queue_positions` | batch forms of the per-market calls |
| `GET /markets?min_settled_ts=&max_settled_ts=` | find settled markets by settlement time |
| `GET /incentive_programs`, `GET /incentive_schedules` | maker/volume rebate programmes, no auth |
| `GET /live_data/weather/{city}` and `/calibrations` | Kalshi's own weather index |
| `GET /exchange/schedule`, `GET /exchange/status` | shard-level trading state |

WebSocket beats polling for state: the `fill` and `user_orders` channels have dedicated low-latency
hosts (`wss://warp-ws.kalshi.com/trade-api/ws/v2`, those two channels only), and `market_positions`
pushes on trades **and settlements**. Recommended REST host is now
`https://external-api.kalshi.com/trade-api/v2`; `api.elections.kalshi.com` stays supported.

## Operational facts

- **Maintenance every Thursday 03:00–05:00 ET**: a trading pause (cancels allowed, no placing or
  amending) or, rarely, an exchange pause (nothing allowed). Resting orders stay unless the order
  set `cancel_order_on_pause`. Expect disconnects and reconnect after 05:00 ET.
- Order groups cancel every resting order in the group when the rolling 15-second filled-contract
  limit trips, when triggered manually, or when the limit is lowered below current volume. A delete
  also cancels them. Reset clears the triggered state.
- Settlement floors (`settlement_bounds_type: floor`, `settlement_floor_dollars`) are **not live
  yet**, but when they are, a raised floor cancels resting orders at or below it and changes
  available balance.
- Error bodies always carry `code`; branch on it. The `service` field was removed in August 2026.
- `GET /markets/{ticker}` and `GET /events/{event_ticker}` send `Cache-Control: max-age=5`, so a
  response can be up to 5 seconds stale. Use the `ticker` WebSocket channel for live prices.
- Demo environment prices and behaviour do not reflect production.
