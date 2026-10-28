# Adding a strategy

A strategy is one Kotlin class in `core/`. The app builds its card, settings screen, go-live
checklist, orders list, record-vs-evidence chart and help page from that class; nothing in
`app/` changes. Strategies are compiled in, so a new one ships with a new build (CI publishes
it as an update the app offers under Settings > About).

## 1. Implement `Strategy`

`core/src/main/kotlin/com/marketbot/core/strategy/Strategy.kt` defines the interface. The parts:

| Member | What it is for |
| --- | --- |
| `id`, `name`, `venue`, `summary` | Identity and the one-line card text. `id` is the storage key: never rename it. |
| `evidence` | How strong the case is (`EvidenceLevel`), a headline, details, the source, and an `Expectation` per contract when one was measured. Shown on the card and compared with the live record. |
| `help` | How it makes money, how it loses money, notes. Plain language. |
| `params` | Every setting: `Param.Num` / `Int_` / `Bool` / `Text` with range, unit and help. `Text` takes a `TextKind` (`HOURS`, `KINDS`, `ZONE`, `BANDS`) to get a picker instead of a text box. Defaults are measured values; say where they came from in the help. |
| `validate(config)` | Cross-checks between settings (e.g. the brake must cover one worst-case loss). Per-setting ranges are checked for you. |
| `effects(config)` | Sentences in money: worst single loss, how many the brake allows, what can be locked up. Shown before saving. |
| `runOnce(ctx)` | One pass. Read everything from the venue through `ctx.kalshi` / `ctx.polymarket`; write orders to `ctx.stores.ledger`; log with `ctx.info` / `warn` / `error` / `trade`. |
| `nextDelay(ctx)` | When the next pass is due. Under 5 minutes keeps the CPU awake; longer uses an OS alarm. |
| `liveRequirements(ctx)` | The go-live checklist. Hard items block arming; soft items need an explicit tick. |
| `preflight(ctx)` | Read-only (or one tiny test order) checks before live. |
| `stats(ctx)` | Figures for the Results section, each a `Sourced` value. |
| `cancelAllResting(ctx)` | Called by the kill switch: cancel only orders this strategy placed. |
| `onStop(ctx)`, `onLeaveLive` | What happens to resting orders when it stops or leaves live, and the sentence that says so. |

## 2. Rules every strategy follows

- **API first.** A value the venue provides is read from that field and wrapped in `Sourced` with
  its origin. Calculate only when no field exists, as `Sourced.derived(value, formula, reason)`.
  A missing value is never zero: throw `ValueUnavailable` and let the pass stop.
- **Live P&L comes from the exchange's settlement payload only.** Never grade a live position from
  local size and price; defer until the payload is readable. Paper P&L is `Source.PAPER_MODEL`.
- **Paper and live never mix.** Every ledger query passes a `Mode`.
- **Order creates are never retried.** Recover a lost response by client order id (Kalshi) or by
  matching open orders (Polymarket, `placeOrderRecovering`).
- **Every live order expires at the exchange** (`expirationEpochS` / good-till) so a dead phone
  cannot leave it resting forever, and Kalshi orders carry `cancel_order_on_pause`.
- **Messages are for people**: tickers through `Tickers.describe`, amounts through `money`,
  technical detail in the `values` map (shown behind a tap).

## 3. Register it

Add an instance to `StrategyRegistry.all`. The engine runs one pass of a strategy at a time
(a per-strategy lock), so a strategy may keep in-memory state between passes.

## 4. Test it

- **Port of existing code:** generate golden vectors from the original (see
  `core/src/test/resources/golden/golden.py` and `farm_golden.py`) and replay them in a test.
  The port is done when the outputs match.
- **Venue behaviour:** use `FakeKalshi` (a MockWebServer that records every request) or a
  MockWebServer for Polymarket; assert on the request bodies (post-only, expiry, shard, subaccount).
- **Lifecycle:** quote, fill, recycle, settle and brake over several passes, as in `LifecycleTest`.
- `./gradlew :core:test` runs in about a minute; `:app:testDebugUnitTest` renders the screens
  (`app/build/screens`) and compares the approved ones in `app/src/test/screenshots`.
