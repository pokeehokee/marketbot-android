# CLAUDE.md

Android app (Kotlin, Compose) that runs the Kalshi/Polymarket strategies from `pokeehokee/weatherbot`
and `pokeehokee/polymarket` on the phone.

## Layout

- `core/`: pure Kotlin/JVM. API clients (`kalshi/`, `polymarket/`, `btc/`), signers (`net/Signers.kt`),
  provenance (`provenance/Sourced.kt`), strategy framework (`strategy/`), the four strategies (`strategies/`), and the engine (`engine/Engine.kt`).
- `app/`: Room stores implementing the core store interfaces, a Keystore-encrypted credential vault, `EngineService` (foreground service), and the Compose UI.

## Rules

- **API first.** Every value that a venue's API provides is read from that field and tagged with its origin. Calculate a value
  only when no field exists, and tag it `DERIVED` with the formula and the reason. Missing values are never zero.
- **Never grade live P&L locally.** Kalshi: `/portfolio/settlements` (`settlementPnl`) only; defer until readable.
- **Paper and live never mix.** Every ledger query filters by mode.
- **Order creates are never retried.** Recover a lost response by `client_order_id`.
- **Kalshi orders carry the market's `exchange_index`, and cancels send `market_ticker`.**
- **Strategy ports are golden-tested** against the original Python (`core/src/test/resources/golden`, regenerate with
  `golden.py`). If you change a port, the golden test must still pass or the divergence must be documented.
- Defaults are measured values from the source repos' CLAUDE.md; change them only with a new measurement.
- **Exact venue words live in `core/contract/Vocabulary.kt`.** Any enum value, field name, slug or ticker shape the code
  branches on is defined there (or pointed at from there), and the daily API wording check (`./gradlew :core:contractTest`,
  `.github/workflows/api-contract.yml`, and once a day in the app) compares live answers and Kalshi's OpenAPI spec against it.
  A refusal the app recognises only by its words must fail safe when the words change.

## Claude sync

Settings > Claude sync links the app to a private GitHub repository (`core/sync`). The app writes
`snapshot/latest.json` (every strategy's mode, settings and record) and `exports/orders-latest.csv`
once a day or on **Sync now**. It reads `proposals/pending.json`:

```json
{"proposals": [{"id": "2026-11-05-a", "strategy": "kalshi_weather_longshot@b",
  "set": {"max_mid_high": "0.15"}, "mode": "PAPER", "reason": "why"}]}
```

It writes each outcome to `proposals/results.json`. Nothing applies without the user's approval: a tap
for a setting change, the screen lock for a mode change. `ProposalPolicy` refuses, whatever the file
says:
- anything on a live strategy;
- any switch to live;
- raising a lot, loss limit or collateral share;
- values outside a setting's range;
- unknown settings.

A proposal may instead carry a one-off **research bet** for the `research_bets` strategy (paper only):

```json
{"proposals": [{"id": "bet-2026-11-09-chi", "strategy": "research_bets", "reason": "why",
  "bet": {"ticker": "KXHIGHCHI-26NOV10-T65", "side": "yes", "maxPrice": 0.47, "stakeUsd": 2.40,
          "probability": 0.54, "thesis": "...", "against": "...", "source": "..."}}]}
```

The app takes only the exact ticker and side named, buys at the ask once it is at or under `maxPrice` (taker fee), holds to
settlement, and writes every bet's price, outcome, `predictionCorrect`, Brier and paper P&L to `bets/results.json`. A bet
must fit the strategy's paper wallet ($30 default); approving the first one turns the strategy on in paper (screen lock).

Snapshots and exports never carry keys.

## Commands

```bash
./gradlew :core:test                 # fast, pure JVM
./gradlew :app:testDebugUnitTest     # Robolectric; writes screenshots to app/build/screens
./gradlew :app:assembleDebug
```

In this sandbox Maven Central rate-limits, so `~/.gradle/init.d/mirror.gradle` and the `robolectricRepo` property point at
Google's Maven Central mirror. CI needs neither.
