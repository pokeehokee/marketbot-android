"""Freeze the P&L arithmetic, after it was wrong twice in one day.

    python3 -m unittest discover -s research/screen -p 'test_*.py'

Both errors were the same documented trap -- "`side` is the outcome ACQUIRED,
not the trade direction":

1. `revenue - cost - fee` from the settlement row booked a round trip's EXIT
   proceeds as a second purchase: -$11.90 for a trade that made +$5.47.
2. The repair, grading everything from fills, treated `action: "sell"` as a
   close. The BTC ladder OPENS 1,207 of its positions that way (buying NO at
   0.04 is selling YES at 0.96), so it printed +$11,027 at +179c/contract on an
   account holding ~$100.

The second is why the sanity check exists, and why these tests assert on the
shape of a ladder fill rather than only on arithmetic.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import account_record as A


def st(yc, ycost, nc, ncost, revenue_cents, fee=0.0):
    return {"yes_count_fp": str(yc), "yes_total_cost_dollars": str(ycost),
            "no_count_fp": str(nc), "no_total_cost_dollars": str(ncost),
            "revenue": revenue_cents, "fee_cost": str(fee), "ticker": "T", "market_result": "yes"}


def fill(action, side, yes_px, no_px, count, fee=0.0):
    return {"action": action, "outcome_side": side, "count_fp": str(count),
            "yes_price_dollars": str(yes_px), "no_price_dollars": str(no_px), "fee_cost": str(fee)}


class RoundTripDetection(unittest.TestCase):
    """Contracts acquired on BOTH sides of one market means it was closed before
    expiry. 2 of 3,310 settlements; no bot market does it, because a strategy
    expresses one side per market."""

    def test_both_sides_is_a_round_trip(self):
        self.assertTrue(A.is_round_trip(st(16.39, 2.7989, 16.39, 8.6866, 0)))

    def test_one_side_only_is_not(self):
        self.assertFalse(A.is_round_trip(st(7, 6.72, 0, 0, 700)))
        self.assertFalse(A.is_round_trip(st(0, 0, 7, 0.28, 0)))

    def test_a_zero_count_side_is_not_a_round_trip(self):
        self.assertFalse(A.is_round_trip(st(5, 4.8, 0.0, 0.0, 500)))


class TheRealTrade(unittest.TestCase):
    """The exact market that exposed the bug: KXBTC15M-26NOV190930-30."""

    ROW = st(16.39, 2.798860, 16.39, 8.686600, 0, 0.418740)
    FILLS = [
        fill("buy",  "yes", 0.47, 0.53,  2.05, 0.0358),
        fill("sell", "no",  0.54, 0.46,  2.05, 0.0357),
        fill("buy",  "yes", 0.39, 0.61,  2.45, 0.0408),
        fill("buy",  "yes", 0.074, 0.926, 11.89, 0.05704),
        fill("sell", "no",  0.46, 0.54, 14.34, 0.2494),
    ]

    def test_the_settlement_arithmetic_gets_it_badly_wrong(self):
        self.assertAlmostEqual(A.settlement_pnl(self.ROW), -11.90, places=2)

    def test_the_fills_give_the_real_answer(self):
        self.assertAlmostEqual(A.round_trip_pnl(self.FILLS), 5.47, places=2)

    def test_the_two_disagree_by_the_exit_counted_twice(self):
        gap = A.round_trip_pnl(self.FILLS) - A.settlement_pnl(self.ROW)
        self.assertAlmostEqual(gap, 17.37, places=2)

    def test_a_round_trip_counts_its_contracts_once(self):
        # 16.39 bought and 16.39 sold is 16.39 of exposure, not 32.78.
        self.assertAlmostEqual(A.contracts(self.ROW), 16.39, places=2)

    def test_a_round_trip_implies_no_loss(self):
        """It never faced settlement, so folding it into the ratio would compare
        a scalp against a pricing bias."""
        self.assertEqual(A.implied_losses(self.ROW), 0.0)


class SellIsNotAlwaysAClose(unittest.TestCase):
    """The ladder's own fill shape. Buying NO at 0.04 and selling YES at 0.96 are
    the same primitive, and the exchange records the NO purchase as a SELL:

        ('buy',  'yes', 'bid')  1281
        ('sell', 'no',  'ask')  1207   <- position-OPENING

    So no code may infer 'closed' from action alone. Only both sides appearing on
    the settlement row establishes a round trip."""

    def test_a_ladder_market_is_never_read_as_a_round_trip(self):
        # One market, one side expressed, however the fills name it.
        self.assertFalse(A.is_round_trip(st(7, 6.72, 0, 0, 700)))

    def test_a_no_fill_is_priced_off_the_no_side(self):
        f = fill("sell", "no", 0.96, 0.04, 7)
        self.assertAlmostEqual(A.side_price(f), 0.04)
        self.assertAlmostEqual(A.side_price(fill("buy", "yes", 0.96, 0.04, 7)), 0.96)


class CashDirection(unittest.TestCase):
    def test_a_buy_is_cash_out_and_a_sell_is_cash_in(self):
        self.assertAlmostEqual(A.round_trip_pnl([fill("buy", "yes", 0.50, 0.50, 10)]), -5.0)
        self.assertAlmostEqual(A.round_trip_pnl([fill("sell", "no", 0.50, 0.50, 10)]), +5.0)

    def test_fees_are_subtracted_once(self):
        self.assertAlmostEqual(
            A.round_trip_pnl([fill("buy", "yes", 0.50, 0.50, 10, fee=0.25)]), -5.25)


class TheSanityCheck(unittest.TestCase):
    """The check whose absence let +$11,027 print. A binary contract cannot pay
    more than 100c, and this account holds about $100."""

    def test_the_total_ceiling_is_set_and_would_have_caught_it(self):
        self.assertTrue(0 < A.SANITY_ABS_TOTAL < 11_027)

    def test_it_leaves_room_for_the_real_figure(self):
        # Measured +$212.69 across every book on 2026-11-20.
        self.assertGreater(A.SANITY_ABS_TOTAL, 213.0)


class SettlementArithmetic(unittest.TestCase):
    def test_a_winner_pays_the_premium(self):
        # 7 contracts bought for $6.72, paid $7.00.
        self.assertAlmostEqual(A.settlement_pnl(st(7, 6.72, 0, 0, 700)), 0.28, places=4)

    def test_a_loser_costs_what_it_cost(self):
        self.assertAlmostEqual(A.settlement_pnl(st(7, 6.72, 0, 0, 0)), -6.72, places=4)

    def test_revenue_is_cents(self):
        self.assertEqual(A.CENTS, 100.0)

    def test_implied_is_one_minus_the_average_price_paid(self):
        # 100 contracts at 0.94 implies 6 losses.
        self.assertAlmostEqual(A.implied_losses(st(100, 94.0, 0, 0, 10000)), 6.0, places=4)


if __name__ == "__main__":
    unittest.main(verbosity=2)
