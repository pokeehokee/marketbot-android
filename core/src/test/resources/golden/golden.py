"""Run the ORIGINAL Python implementations over many inputs and write their
outputs as JSON, so the Kotlin port is tested against the code that actually
traded rather than against a re-reading of it."""
import ast, datetime as dt, json, math, random, sys, types
from pathlib import Path

S = Path(__file__).parent
sys.path[:0] = [str(S / 'src/wb/src'), str(S / 'src/pm')]
OUT = Path(sys.argv[1])
OUT.mkdir(parents=True, exist_ok=True)
rnd = random.Random(20261027)


def extract(path, names, extra_globals=None):
    """Exec only the named top-level defs/assigns of a module (avoids its heavy imports)."""
    tree = ast.parse(Path(path).read_text())
    body = [n for n in tree.body if (isinstance(n, (ast.FunctionDef, ast.ClassDef)) and n.name in names)
            or (isinstance(n, ast.Assign) and any(getattr(t, 'id', None) in names for t in n.targets))]
    mod = ast.Module(body=body, type_ignores=[])
    g = {'math': math, '__name__': 'extracted'}
    g.update(extra_globals or {})
    exec(compile(mod, str(path), 'exec'), g)
    return g


def dump(name, obj):
    (OUT / f'{name}.json').write_text(json.dumps(obj, indent=1, sort_keys=True))
    print(name, len(obj) if hasattr(obj, '__len__') else '')


# ---------------------------------------------------------------- longshot model
from weatherbot.models import longshot as L
from weatherbot.scanners import longshot_runner as R

fee = []
for cents in range(0, 101):
    for count in (1, 2, 3, 5, 7, 10, 13, 20, 50, 100):
        fee.append({'price': cents / 100, 'count': count, 'fee': L.kalshi_fee(cents / 100, count)})
dump('kalshi_fee', fee)

tickers = ['KXHIGHNY-26OCT28-T67', 'KXLOWTNYC-26OCT28-B50.5', 'KXHIGHTDAL-26OCT28-B80.5',
           'KXLOWTCHI-26OCT28-T40', 'KXHIGHCHI-26OCT28-B60.5', 'KXFOO-26OCT28-T1']
ev = []
for _ in range(3000):
    t = rnd.choice(tickers)
    bid = round(rnd.choice([rnd.uniform(0, 0.4), rnd.randint(0, 40) / 100]), 2)
    ask = round(bid + rnd.choice([0, 0.01, 0.02, 0.03, 0.05, 0.06, 0.07, 0.1, rnd.uniform(-0.02, 0.2)]), 2)
    ask = min(max(ask, 0.0), 1.0)
    om = rnd.choice([0, 5, 119, 120, 121])
    mc = rnd.choice([1, 5, 10])
    q = L.evaluate(t, bid, ask, open_markets=om, max_contracts=mc)
    ev.append({'ticker': t, 'bid': bid, 'ask': ask, 'open_markets': om, 'max_contracts': mc,
               'quote': None if q is None else {'price': q.price, 'contracts': q.contracts,
                                                 'edge': q.expected_edge}})
dump('longshot_evaluate', ev)

lots = []
for _ in range(500):
    asks = [round(rnd.uniform(0.02, 0.25), 2) for _ in range(rnd.randint(0, 120))]
    budget = round(rnd.uniform(-10, 1500), 2)
    lots.append({'budget': budget, 'asks': asks, 'lot': L.lot_for_budget(budget, asks)})
dump('longshot_lot_for_budget', lots)

sizes = []
for _ in range(500):
    p = round(rnd.uniform(0, 1), 2); b = round(rnd.uniform(0, 30), 2); cap = rnd.choice([1, 5, 10])
    sizes.append({'price': p, 'budget': b, 'cap': cap, 'size': L.size_for_budget(p, b, cap)})
dump('longshot_size_for_budget', sizes)

budgets = []
for _ in range(300):
    cash = round(rnd.uniform(0, 800), 2); cl = round(rnd.uniform(0, 500), 2); cb = round(rnd.uniform(0, cl), 2)
    pv = rnd.choice([None, round(rnd.uniform(0, 400), 2)])
    budgets.append({'cash': cash, 'committed_live': cl, 'committed_book': cb, 'portfolio_value': pv,
                    'budget': R.collateral_budget(cash, cl, cb, portfolio_value=pv)})
dump('longshot_collateral_budget', budgets)

orders = []
for _ in range(200):
    cands = []
    for i in range(rnd.randint(0, 30)):
        kind = rnd.choice(['KXHIGHNY', 'KXLOWTNYC', 'KXHIGHTDAL', 'KXLOWTCHI'])
        bid = round(rnd.uniform(0.01, 0.2), 2)
        ask = round(bid + rnd.choice([0.01, 0.02, 0.03]), 2)
        cands.append([f'{kind}-26OCT28-B{i}', bid, ask])
    ranks = {c[0]: round(rnd.uniform(0, 0.2), 3) for c in cands if rnd.random() < 0.5}
    orders.append({'candidates': cands, 'ranks': ranks,
                   'ordered': [c[0] for c in R.order_candidates([tuple(c) for c in cands], ranks)]})
dump('longshot_order_candidates', orders)

books = []
for payload in [
    {'yes_bid_dollars': '0.0300', 'yes_ask_dollars': '0.0400'},
    {'yes_bid': 3, 'yes_ask': 4},
    {'yes_bid_dollars': '0.0300', 'no_bid_dollars': '0.9500'},
    {'yes_bid_dollars': '0.0300'},
    {'yes_bid_dollars': 'abc', 'yes_ask_dollars': '0.04'},
    {'yes_bid_dollars': '0.0000', 'yes_ask_dollars': '1.0000'},
    {},
]:
    b, a = R._book(payload)
    books.append({'payload': payload, 'bid': b, 'ask': a})
dump('longshot_book', books)

now = dt.datetime(2026, 10, 27, 20, 0, tzinfo=dt.timezone.utc)
leads = []
for close in ['2026-10-28T16:00:00Z', '2026-10-28T15:59:00Z', '2026-10-28T05:00:00Z', '', None, 'garbage',
              '2026-10-28T16:00:00+00:00']:
    m = {} if close is None else {'close_time': close}
    leads.append({'market': m, 'lead': R.lead_hours(m, now=now), 'ok': R.has_min_lead(m, now=now)})
dump('longshot_lead', {'now': now.isoformat(), 'cases': leads})

# resolver pieces (extracted; the module drags in the whole scanner)
G = extract(S / 'src/wb/src/weatherbot/scanners/longshot_resolver.py',
            {'fill_state', '_fp', '_FILL_KEYS', '_REMAINING_KEYS', 'pnl_for'}, {'kalshi_fee': L.kalshi_fee})
fills = []
for order in [
    {'fill_count_fp': '5.00', 'status': 'executed'}, {'fill_count_fp': '0.00', 'status': 'resting'},
    {'fill_count': 3}, {'remaining_count_fp': '2.00', 'initial_count_fp': '5.00'},
    {'remaining_count': 5, 'count': 5}, {'status': 'executed'}, {'status': 'canceled'},
    {'status': 'resting'}, {}, {'fill_count_fp': 'x', 'remaining_count_fp': '1', 'initial_count_fp': '4'},
]:
    f, n = G['fill_state'](order)
    fills.append({'order': order, 'filled': f, 'n': n})
dump('kalshi_fill_state', fills)

pnls = []
for _ in range(300):
    p = round(rnd.uniform(0.02, 0.3), 2); c = rnd.randint(1, 20); r = rnd.choice(['yes', 'no'])
    pnls.append({'price': p, 'contracts': c, 'result': r, 'pnl': G['pnl_for'](p, c, r)})
dump('longshot_pnl_for', pnls)

P = extract(S / 'src/wb/src/weatherbot/execution/resolve_kalshi_trades.py', {'_pnl_from_payload'}, {'retry_on_db_lock': lambda *a, **k: (lambda f: f)})
settle = []
for s in [
    {'revenue': 500, 'yes_total_cost_dollars': '0.00', 'no_total_cost_dollars': '4.75', 'fee_cost': '0.000000',
     'no_count_fp': '5.00', 'yes_count_fp': '0.00', 'market_result': 'no'},
    {'revenue': 0, 'no_total_cost_dollars': '4.75', 'fee_cost': '0', 'no_count_fp': '5.00', 'market_result': 'yes'},
    {'revenue': 0, 'yes_total_cost_dollars': '4.40', 'no_total_cost_dollars': '19.60', 'fee_cost': '0.16',
     'no_count_fp': '20.00', 'yes_count_fp': '20.00', 'market_result': 'no'},
    {'revenue': 1000, 'yes_total_cost': 0, 'no_total_cost': 950, 'fee_cost': '0', 'no_count': 10},
    {'revenue': '300', 'no_total_cost_dollars': '2.91', 'fee_cost': '0.01', 'no_count_fp': '3.00'},
]:
    settle.append({'payload': s, 'pnl': P['_pnl_from_payload'](s)})
dump('kalshi_settlement_pnl', settle)

# ---------------------------------------------------------------- deep ITM
from weatherbot.deep_itm import strategy as D, paper as DP, index as DI, runner as DR

fair = []
for _ in range(500):
    spot = rnd.uniform(60000, 120000); strike = spot * rnd.uniform(0.97, 1.03)
    sig = rnd.choice([None, 0.0, rnd.uniform(5e-6, 6e-5)]); ttc = rnd.choice([0, rnd.uniform(1, 4000)])
    p, z = D.fair_yes(spot, strike, sig, ttc)
    fair.append({'spot': spot, 'strike': strike, 'sigma': sig, 'ttc': ttc, 'p': p, 'z': z})
dump('deepitm_fair', fair)

btc = json.loads((S / 'probe/kalshi_markets_KXBTCD.json').read_text())['markets']
cand_cases = []
for _ in range(60):
    ms = []
    close = dt.datetime(2026, 10, 27, rnd.choice([14, 15, 21, 22]), 0, tzinfo=dt.timezone.utc)
    spot = rnd.uniform(70000, 80000)
    for m in rnd.sample(btc, min(25, len(btc))):
        m = dict(m)
        m['close_time'] = close.strftime('%Y-%m-%dT%H:%M:%SZ')
        m['floor_strike'] = round(spot + rnd.uniform(-1500, 1500), 2)
        yb = rnd.choice([0, 0.01, 0.9, 0.91, 0.93, 0.95, 0.96, 0.97, 1.0, rnd.uniform(0, 1)])
        nb = rnd.choice([0, 0.01, 0.9, 0.92, 0.94, 0.96, 0.98, rnd.uniform(0, 1)])
        m['yes_bid_dollars'] = f'{yb:.4f}'; m['no_bid_dollars'] = f'{nb:.4f}'
        m['yes_bid_size_fp'] = f'{rnd.randint(0, 500)}.00'; m['yes_ask_size_fp'] = f'{rnd.randint(0, 500)}.00'
        if rnd.random() < 0.1:
            m['strike_type'] = 'between'
        ms.append(m)
    now_c = close - dt.timedelta(seconds=rnd.choice([100, 300, 1200, 2999, 3000, 3100]))
    sig = rnd.uniform(1e-5, 4e-5)
    cs = D.candidates(ms, spot, sig, now_c)
    held = set(rnd.sample([m['ticker'] for m in ms], 2))
    sel = D.select(cs, held)
    cand_cases.append({'markets': ms, 'spot': spot, 'sigma': sig, 'now': now_c.isoformat(),
                       'candidates': [c.__dict__ for c in cs], 'held': sorted(held),
                       'selected': [c.__dict__ for c in sel]})
dump('deepitm_candidates', cand_cases)

ev_names = []
for _ in range(100):
    n = dt.datetime(2026, rnd.randint(1, 12), rnd.randint(1, 28), rnd.randint(0, 23), rnd.randint(0, 59),
                    tzinfo=dt.timezone.utc)
    e, c = DR.next_hour_event(n)
    ev_names.append({'now': n.isoformat(), 'event': e, 'close': c.isoformat()})
dump('deepitm_next_hour_event', ev_names)

tape_cases = []
for _ in range(200):
    placed = dt.datetime(2026, 10, 27, 20, 10, tzinfo=dt.timezone.utc)
    side = rnd.choice(['yes', 'no']); price = rnd.choice([0.92, 0.94, 0.95])
    q = {'placed_at': DP.iso(placed), 'cancelled_at': rnd.choice([None, DP.iso(placed + dt.timedelta(minutes=20))]),
         'side': side, 'price': price, 'count': 5, 'queue_ahead': float(rnd.randint(0, 30))}
    trades = []
    for _ in range(rnd.randint(0, 12)):
        t = placed + dt.timedelta(seconds=rnd.randint(-120, 2400))
        yp = rnd.choice([0.05, 0.06, 0.08, 0.92, 0.94, 0.95, 0.96])
        trades.append({'created_time': t.strftime('%Y-%m-%dT%H:%M:%S.%fZ'), 'taker_side': rnd.choice(['yes', 'no']),
                       'count_fp': f'{rnd.randint(1, 20)}.00', 'yes_price_dollars': f'{yp:.4f}',
                       'no_price_dollars': f'{1 - yp:.4f}'})
    f, touched, first = DP.fill_from_tape(q, trades)
    tape_cases.append({'quote': q, 'trades': trades, 'filled': f, 'touched': touched, 'first': first})
dump('deepitm_fill_from_tape', tape_cases)

pp = []
for side in ('yes', 'no'):
    for result in ('yes', 'no', None):
        for filled in (0, 3, 5):
            pp.append({'side': side, 'price': 0.94, 'filled': filled, 'result': result,
                       'pnl': DP.pnl(side, 0.94, filled, result)})
dump('deepitm_pnl', pp)

candles = []
base = 1793134000
for case in range(40):
    rows = []; px = 75000.0
    for i in range(rnd.randint(10, 25)):
        if rnd.random() < 0.05:
            continue
        px *= math.exp(rnd.gauss(0, 0.0008))
        rows.append([base + 60 * i, px * 0.999, px * 1.001, px, px, 1.0])
    rnd.shuffle(rows)
    candles.append({'candles': rows, 'sigma': DI.candle_sigma(rows)})
dump('btc_candle_sigma', candles)

rs = []
for case in range(40):
    samples = []; t = 1000.0; px = 75000.0
    for i in range(rnd.randint(100, 1000)):
        t += rnd.choice([1, 1, 1, 2, 30]); px *= math.exp(rnd.gauss(0, 2e-5))
        samples.append([t, px])
    rs.append({'samples': samples, 'sigma1': DI.realised_sigma([tuple(s) for s in samples]),
               'sigma10': DI.realised_sigma([tuple(s) for s in samples], step_s=10)})
dump('btc_realised_sigma', rs)

mi = []
for case in range(60):
    q = {v: (100.0 - rnd.uniform(0, 8), rnd.uniform(74000, 76000)) for v in rnd.sample(['a', 'b', 'c', 'd'], rnd.randint(0, 4))}
    mi.append({'quotes': {k: list(v) for k, v in q.items()}, 'now': 100.0, 'index': DI.median_index(q, 100.0)})
dump('btc_median_index', mi)

# ---------------------------------------------------------------- pmfarm
from pmfarm import rewards as RW, scanner as SC, risk as RK, broker as BR, config as CF
from pmfarm.gateway import MarketMeta

ws = []
for _ in range(300):
    n = rnd.randint(0, 6); best = round(rnd.uniform(0.05, 0.9), 2)
    lv = [RW.Level(round(best - i * 0.01 * rnd.randint(1, 3), 2), float(rnd.randint(1, 500))) for i in range(n)]
    lv.sort(key=lambda l: l.price, reverse=True)
    tgt = float(rnd.choice([1, 50, 200, 1000])); disc = rnd.choice([0.3, 0.5, 0.8])
    r = RW.walk_side(lv, 0.01, tgt, disc)
    ws.append({'levels': [[l.price, l.qty] for l in lv], 'target': tgt, 'discount': disc,
               'best': r.best, 'score': r.score, 'depth': r.depth, 'adjusted': r.adjusted_price})
dump('pm_walk_side', ws)

eq = []
for _ in range(400):
    mid = round(rnd.uniform(0.1, 0.9), 2)
    bids = sorted([RW.Level(round(mid - 0.01 * k, 2), float(rnd.randint(1, 300))) for k in range(1, rnd.randint(1, 5))],
                  key=lambda l: -l.price)
    asks = sorted([RW.Level(round(mid + 0.01 * k, 2), float(rnd.randint(1, 300))) for k in range(1, rnd.randint(1, 5))],
                  key=lambda l: l.price)
    side = rnd.choice(['bid', 'ask'])
    price = (bids[0].price if bids else mid) if side == 'bid' else (asks[0].price if asks else mid)
    qty = float(rnd.randint(1, 400)); tgt = float(rnd.choice([1, 50, 200, 1000]))
    ms_ = rnd.choice([None, 0.02, 0.05])
    e = RW.estimate_quote(bids, asks, side, price, qty, tick=0.01, target=tgt, discount=0.5,
                          max_spread=ms_, side_rate=12.5)
    eq.append({'bids': [[l.price, l.qty] for l in bids], 'asks': [[l.price, l.qty] for l in asks],
               'side': side, 'price': price, 'qty': qty, 'target': tgt, 'max_spread': ms_,
               'pays': e.pays, 'share': e.share, 'usd_per_day': e.usd_per_day})
dump('pm_estimate_quote', eq)

progs = json.loads((S / 'probe/pm_incentives_big.json').read_text())['programs']
cfg = CF.Config()
apnow = dt.datetime(2026, 10, 27, 21, 0, tzinfo=dt.timezone.utc)
ap = []
for m in progs:
    c = SC.active_periods(m, apnow, cfg)
    ap.append({'market': m, 'candidate': None if c is None else {
        'slug': c.slug, 'event_key': c.event_key, 'side_rate': c.side_rate,
        'periods': [{'kind': p.kind, 'pool': p.pool, 'seconds': p.period_seconds, 'target': p.target,
                     'discount': p.discount, 'max_spread': p.max_spread} for p in c.periods]}})
dump('pm_active_periods', {'now': apnow.isoformat(), 'cases': ap})

pq = []
for _ in range(300):
    mid = round(rnd.uniform(0.05, 0.95), 2)
    bids = sorted([RW.Level(round(mid - 0.01 * k, 2), float(rnd.randint(1, 300))) for k in range(1, rnd.randint(2, 6))],
                  key=lambda l: -l.price)
    asks = sorted([RW.Level(round(mid + 0.01 * k, 2), float(rnd.randint(1, 300))) for k in range(1, rnd.randint(2, 6))],
                  key=lambda l: l.price)
    periods = [SC.Period('p', 'daily', rnd.choice([5.0, 50.0, 450.0]), 86400.0, None,
                         float(rnd.choice([1, 50, 200])), rnd.choice([0.3, 0.5]), rnd.choice([None, 0.05]))]
    cand = SC.Candidate('slug-x', 'fam:slug-x', None, periods)
    meta = MarketMeta('slug-x', 0.01, rnd.choice([0.01, 1.0]), False, True, None)
    budget = round(rnd.uniform(0.3, 25), 2)
    c2 = CF.Config(); c2.allow_short_quotes = rnd.random() < 0.3
    plan = SC.plan_quote(cand, meta, bids, asks, c2, budget)
    pq.append({'bids': [[l.price, l.qty] for l in bids], 'asks': [[l.price, l.qty] for l in asks],
               'periods': [p.__dict__ | {'end': None} for p in periods], 'min_qty': meta.min_qty, 'budget': budget,
               'allow_short': c2.allow_short_quotes,
               'plan': None if plan is None else {'side': plan.side, 'price': plan.price, 'qty': plan.qty,
                                                   'cost': plan.cost, 'usd_per_day': plan.usd_per_day,
                                                   'share': plan.share}})
dump('pm_plan_quote', pq)

ek = [{'slug': s, 'key': SC.event_key(s)} for s in [m['marketSlug'] for m in progs][:200] + [
    'astatc-nfl-pit-cle-2026-10-01-ptd-6pt5', 'cpc-btc-hitprice-low-yr-12-31-2026-75k', 'x']]
dump('pm_event_key', ek)

fees = [{'theta': th, 'qty': q, 'price': p, 'fee': RW.trade_fee(th, q, p)}
        for th in (RW.TAKER_THETA, RW.MAKER_THETA) for q in (1, 10, 37.5) for p in (0.02, 0.5, 0.93)]
dump('pm_trade_fee', fees)

risk = []
for _ in range(200):
    c3 = CF.Config(); c3.start_equity = rnd.choice([0.0, 10.0])
    hw = rnd.uniform(0, 50); eqv = rnd.uniform(0, 50); bp = rnd.uniform(0, eqv); free = rnd.uniform(0, eqv)
    rm = RK.RiskManager.__new__(RK.RiskManager); rm.cfg = c3; rm.path = None; rm.state = RK.RiskState(high_water=hw)
    bal = BR.Balance(buying_power=bp, equity=eqv, free_cash=free)
    risk.append({'start_equity': c3.start_equity, 'high_water': hw, 'equity': eqv, 'free_cash': free,
                 'floor': rm.floor(), 'entry_budget': rm.entry_budget(bal)})
dump('pm_risk', risk)

it = [{'side': s, 'pos': p, 'exit': e, 'intent': BR.intent_for(s, p, e)}
      for s in ('bid', 'ask') for p in (-3.0, 0.0, 4.0) for e in (False, True)]
dump('pm_intent', it)
print('done')
