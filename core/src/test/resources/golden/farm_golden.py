"""Drive pmfarm's PaperBroker (the original) through random sequences of placed
orders, book snapshots and public trade prints, and record what it filled and
what each order's queue looked like after every step. The Kotlin
PaperFarmBroker replays the same steps and must agree.

    python3 farm_golden.py /path/to/polymarket out_dir
"""
import json, random, sys
from pathlib import Path

sys.path.insert(0, sys.argv[1])
from pmfarm import broker as B, clock          # noqa: E402
from pmfarm.gateway import Book                # noqa: E402
from pmfarm.rewards import Level               # noqa: E402

t = [1_000_000.0]
clock.time = lambda: t[0]
rnd = random.Random(20261028)
SLUG = "m1"
scenarios = []
for sc in range(60):
    br = B.PaperBroker(Path("/nonexistent"), starting_cash=500.0, persist=False)
    fed = rnd.random() < 0.6
    ids, steps = [], []
    for step in range(30):
        t[0] += 1.0
        r = rnd.random()
        if r < 0.25 or not ids:
            side = rnd.choice(["bid", "ask"])
            price = round(rnd.choice([0.40, 0.41, 0.42, 0.43]) if side == "bid" else rnd.choice([0.45, 0.46, 0.47, 0.48]), 2)
            qty = float(rnd.randint(5, 40)); ahead = float(rnd.randint(0, 60))
            intent = "BUY_LONG" if side == "bid" else "BUY_SHORT"
            oid = br.place(SLUG, side, price, qty, intent); br.set_queue(oid, ahead)
            ids.append(oid)
            steps.append({"op": "place", "side": side, "price": price, "qty": qty, "intent": intent, "ahead": ahead, "ts": t[0]})
        elif r < 0.6 or not fed:
            bids = sorted({round(rnd.choice([0.38, 0.39, 0.40, 0.41, 0.42, 0.43, 0.44, 0.45, 0.46]), 2) for _ in range(rnd.randint(0, 4))}, reverse=True)
            asks = sorted({round(rnd.choice([0.42, 0.43, 0.44, 0.45, 0.46, 0.47, 0.48, 0.49]), 2) for _ in range(rnd.randint(0, 4))})
            asks = [a for a in asks if not bids or a > bids[0]]
            bl = [[p, float(rnd.randint(1, 80))] for p in bids]; al = [[p, float(rnd.randint(1, 80))] for p in asks]
            fills = br.on_book(Book(SLUG, [Level(p, q) for p, q in bl], [Level(p, q) for p, q in al], "MARKET_STATE_OPEN"))
            steps.append({"op": "book", "bids": bl, "asks": al, "fills": [[ids.index(f["id"]), f["qty"], f["why"]] for f in fills]})
        else:
            px = round(rnd.choice([0.39, 0.40, 0.41, 0.42, 0.43, 0.45, 0.46, 0.47, 0.48, 0.49]), 2)
            q = float(rnd.randint(1, 50)); taker = rnd.choice(["ORDER_SIDE_BUY", "ORDER_SIDE_SELL"])
            fills = br.on_trade({"marketSlug": SLUG, "price": {"value": str(px)}, "quantity": {"value": str(q)}, "taker": {"side": taker}})
            steps.append({"op": "trade", "price": px, "qty": q, "taker": taker, "fills": [[ids.index(f["id"]), f["qty"], f["why"]] for f in fills]})
        steps[-1]["orders"] = {str(ids.index(i)): [o["qty"], o["ahead"]] for i, o in br.s.orders.items()}
        steps[-1]["cash"] = br.s.cash
        steps[-1]["position"] = br.s.positions.get(SLUG, {}).get("qty", 0.0)
    scenarios.append({"fed": fed, "steps": steps})
Path(sys.argv[2], "pm_paper_queue.json").write_text(json.dumps(scenarios, indent=0))
print("pm_paper_queue", len(scenarios))
