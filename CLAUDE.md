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

## Commands

```bash
./gradlew :core:test                 # fast, pure JVM
./gradlew :app:testDebugUnitTest     # Robolectric; writes screenshots to app/build/screens
./gradlew :app:assembleDebug
```

In this sandbox Maven Central rate-limits, so `~/.gradle/init.d/mirror.gradle` and the `robolectricRepo` property point at
Google's Maven Central mirror. CI needs neither.
