# Competitive research, 2026-11-08

Apps that do part of what Marketbot does, on any platform, what they do well, and what almost none
of them do. Sources are app-store listings, vendor pages and comparison blogs (several are
affiliate-driven); feature claims were not verified hands-on.

## Who was looked at

| app | platform | what it is |
| --- | --- | --- |
| Prediction Market Tracker | iOS | read-only Kalshi + Polymarket ledger: one P&L, price alerts, tax CSV, local-first |
| Polymarket Dashboard, Polytracker | iOS | Polymarket watchlist with probability-shift alerts |
| Predytics, Kalshinomics, Polyanalytics, Alphascope | web | cross-venue odds, arbitrage scanners, whale feeds, topic dashboards |
| Kalshi / Polymarket own apps | iOS, Android, web | trading, positions, P&L per venue |
| 3Commas, Cryptohopper, Pionex | web + mobile | crypto trading bots: DCA/grid bots, paper trading, backtests, start/stop from the phone |
| Composer | web + mobile | no-code automated strategies ("symphonies"), unlimited backtests, benchmark chart, Sharpe and drawdown, AI that builds strategies from a prompt |
| Pikkit (and Stat Sniper) | iOS, Android | sports bet tracker: auto-sync, ROI by sport/market/book, closing-line value (paid) |

## What they do well that we should do too

| idea | seen in | Marketbot today | proposal |
| --- | --- | --- | --- |
| One P&L across venues | Prediction Market Tracker | yes (Home, Periods) | keep |
| Tax-time export | Prediction Market Tracker | CSV export, not per tax year | a "tax year" export: realised gains per venue per year, live only |
| Price alerts on a watchlist | Polymarket Dashboard, PM Tracker | none | star a market from Orders; notify on a move of X cents or a cross of a price |
| Benchmark on the performance chart | Composer | record vs evidence band | add a "cash" and "the backtest's expectation" line to the strategy chart |
| Sharpe, max drawdown, annualised return per strategy | Composer | drawdown on Home only | a stats strip per strategy |
| Breakdown by sport/market/book | Pikkit | per band (adverse selection), per method | per city, per series, per lead, per weekday: where a strategy makes and loses money |
| Closing-line value | Pikkit | markout exists, named "markout" | rename and surface as CLV per strategy: did the price move our way after we traded? |
| Backtest, then paper for 1-2 weeks, then live | Cryptohopper guidance | paper first, graduation gates (this PR) | done |
| Start/stop bots from the phone, notifications | all bot apps | yes | keep |
| Local-first, keys stay on the device | PM Tracker | yes (Keystore vault) | say it on the setup screen |

## What most or all of them do not have (where Marketbot can stand out)

1. **Every number says where it came from.** API field, derived formula, paper model or config. No tracker or
   bot app shows provenance; most mix exchange and estimated figures silently.
2. **An honest badge that follows the app's own record.** Bot marketplaces show backtest returns; none
   compare the live/paper record against what the backtest promised, with an interval (this PR).
3. **Graduation gates.** Paper to live only after time, fills, enough losses and a loss ratio that says the
   edge is real (this PR). Bot apps let a user flip to live at any moment.
4. **"Is this luck?"** Losses against what prices implied, with an exact p-value, on every strategy. Trackers show
   win rate, which a strategy selling insurance can keep at 95% while losing money.
5. **Adverse-selection meter.** How much more often orders fill on markets that go against them. No retail tool
   measures fill selection.
6. **AI proposals behind a policy and a fingerprint.** Composer lets AI build strategies; here Claude can only
   propose paper-safe changes, the phone refuses anything riskier, and a person approves each one (Claude sync).
7. **Pre-registered paper variants.** Two settings side by side on the same markets, compared on the same
   record (variants table, this PR); the next step is declaring a winner only when the difference is significant.
8. **Cross-venue books recorded at one moment** (this PR): the data no arbitrage scanner keeps, so a spread can be
   tested on history instead of eyeballed.
9. **Kill criteria that act.** A live strategy whose loss ratio crosses 1.0 over 60 days is disarmed, not just charted.

## Sources

- [Prediction Market Tracker (App Store)](https://apps.apple.com/app/id6759204134), [Polymarket Dashboard](https://apps.apple.com/app/id6762546600), [Polytracker](https://apps.apple.com/mx/app/polytracker/id6747049271)
- [Kalshinomics](https://www.bonus.com/prediction-markets/tools/kalshinomics/), [Predytics](https://hunted.space/product/predytics), [Polyanalytics](https://www.producthunt.com/p/polyanalytics), [Alphascope guide](https://www.alphascope.app/blog/free-prediction-market-tools)
- [3Commas vs Pionex](https://tradingtools-directory.pages.dev/compare/3commas-vs-pionex/), [3Commas vs Cryptohopper](https://tradingtools-directory.pages.dev/compare/3commas-vs-cryptohopper/), [3Commas review](https://makerstack.co/reviews/3commas-review/), [G2 reviews](https://www.g2.com/products/3commas)
- [Composer review (Benzinga)](https://benzinga.com/money/composer-review), [Composer help: creating strategies](https://help.composer.trade/article/216-creating-and-editing-algorithmic-trading-strategies-with-composer), [Trade with AI](https://help.composer.trade/article/254-what-is-trade-with-ai)
- [Stat Sniper vs Pikkit](https://statsniper.com/statsniper-vs-pikkit/)
