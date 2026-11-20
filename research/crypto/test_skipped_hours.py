"""Freeze the rules the skipped-hours measurement depends on.

stdlib unittest, no network, no credentials. The zero-lookahead candle rule, the
empty-book sentinel and the read-only guard are already frozen in
test_crypto_tail.py -- this file covers what is NEW here: the resting-bid entry
price, the selector replay, event-hour clustering and the control gate.

    python3 -m unittest discover -s research/crypto -p 'test_*.py'
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import skipped_hours as sh


class EntryIsARestingBid(unittest.TestCase):
    """The ladder is POST-ONLY: it rests a bid and never lifts an ask.

    A YES bid is `yes_bid`. A NO bid is `1 - yes_ask`, because the orderbook
    carries only bids. Reading the ask instead is the mistake that made
    deep_itm_screen.py reject KXBTCD itself on its first run -- the live book
    quotes bid 0.95 / ask 0.99, so an ask filter finds nothing.
    """

    def test_no_side_entry_is_one_minus_the_yes_ask(self):
        self.assertAlmostEqual(round(1.0 - 0.06, 4), 0.94)
        self.assertAlmostEqual(round(1.0 - 0.10, 4), 0.90)

    def test_the_band_is_the_shipped_one(self):
        self.assertEqual((sh.BAND_LO, sh.BAND_HI), (0.90, 0.96))
        self.assertEqual(sh.MAX_OPEN, 8)

    def test_the_lead_sits_inside_the_fill_window(self):
        # The recovered desktop run filled at 342-2999 s to close, 100% inside
        # [ttc_min 300, ttc_max 3000]. A lead outside that measures an instant
        # the ladder never trades at.
        self.assertTrue(300 <= sh.LEAD_S <= 3000, sh.LEAD_S)

    def test_the_skip_list_is_the_shipped_one(self):
        self.assertEqual(sh.SKIPPED, (3, 10, 21, 23))


class DeepItmIsRelativeToSpot(unittest.TestCase):
    """Deep in the money means the strike is ALREADY passed: a YES strike below
    spot, a NO strike above it. Getting the sides backwards would price the
    cheap tail instead -- the inverse trade, measured at -0.83c."""

    def test_side_follows_the_strike_against_spot(self):
        spot = 64_000.0
        self.assertEqual("yes" if 63_000.0 < spot else "no", "yes")
        self.assertEqual("yes" if 65_000.0 < spot else "no", "no")

    def test_a_loss_is_the_result_disagreeing_with_the_side(self):
        def lost(result, side):
            return 0 if ((result == "yes") == (side == "yes")) else 1
        self.assertEqual(lost("yes", "yes"), 0)
        self.assertEqual(lost("no", "yes"), 1)
        self.assertEqual(lost("no", "no"), 0)
        self.assertEqual(lost("yes", "no"), 1)


class ClusterByEventHour(unittest.TestCase):
    """Every strike of one BTC hour settles off ONE price move -- 67 of 67
    losing hours had all their losers on one side of spot. So the hour is the
    independent unit; treating strikes as independent draws is what produced
    two Poisson-binomial p-values this repo had to withdraw."""

    def test_the_bootstrap_resamples_hours_not_rows(self):
        # Two hours, one all-losing and one all-winning: resampling HOURS must
        # be able to produce 0.0 and a high ratio. Resampling rows could not.
        groups = {
            "h1": [{"entry": 0.94, "lost": 1} for _ in range(8)],
            "h2": [{"entry": 0.94, "lost": 0} for _ in range(8)],
            "h3": [{"entry": 0.94, "lost": 0} for _ in range(8)],
        }
        lo, hi = sh.boot(groups, sh.ratio_of, n=800, seed=1)
        self.assertIsNotNone(lo)
        self.assertLess(lo, 0.1, "an all-winning draw must be reachable")
        self.assertGreater(hi, 1.0, "an all-losing draw must be reachable")

    def test_too_few_hours_returns_no_interval_rather_than_a_fake_one(self):
        self.assertEqual(sh.boot({"h1": [{"entry": 0.94, "lost": 0}]}, sh.ratio_of), (None, None))


class RatioAndEv(unittest.TestCase):
    """Implied losses come from the price PAID: resting at 0.94 implies a 6%
    loss rate. And a resting fill pays no fee -- 130 of 130 measured at 0.00."""

    def test_implied_is_one_minus_entry_per_contract(self):
        rows = [{"entry": 0.94, "lost": 0} for _ in range(100)]
        self.assertAlmostEqual(sum(1 - r["entry"] for r in rows), 6.0, places=6)
        self.assertEqual(sh.ratio_of(rows), 0.0)

    def test_a_fair_book_reads_one(self):
        rows = [{"entry": 0.90, "lost": 1 if i < 10 else 0} for i in range(100)]
        self.assertAlmostEqual(sh.ratio_of(rows), 1.0, places=6)

    def test_ev_is_the_premium_kept_less_the_losses_at_full_price(self):
        # 10 winners at 0.94 keep 6c each; one loser at 0.94 costs 94c.
        self.assertAlmostEqual(sh.ev_of([{"entry": 0.94, "lost": 0}]), 6.0, places=6)
        self.assertAlmostEqual(sh.ev_of([{"entry": 0.94, "lost": 1}]), -94.0, places=6)
        self.assertIsNone(sh.ev_of([]))

    def test_a_book_with_no_premium_has_no_ratio_rather_than_a_divide_by_zero(self):
        self.assertIsNone(sh.ratio_of([{"entry": 1.0, "lost": 0}]))


class TheControlCanWithholdTheVerdict(unittest.TestCase):
    """A screen that cannot reproduce its known-good case is measuring its own
    thresholds. screen_kalshi_pools.py once reported zero qualifying pools
    INCLUDING the one the account trades, because its bar rejected weather
    itself. The traded hours are this script's known-good case."""

    def test_the_account_ratio_is_the_measured_one(self):
        self.assertAlmostEqual(sh.ACCOUNT_RATIO, 0.72, places=2)

    def test_the_gate_band_contains_the_account_figure_and_excludes_nonsense(self):
        lo, hi = 0.45, 1.05
        self.assertTrue(lo <= sh.ACCOUNT_RATIO <= hi)
        for bad in (0.0, 0.2, 1.4, 3.0):
            self.assertFalse(lo <= bad <= hi, f"{bad} should fail the control")


class ReadOnly(unittest.TestCase):
    """It reuses crypto_tail_sample's guard, so an order path is unreachable."""

    def test_order_paths_are_refused(self):
        import crypto_tail_sample as cts
        for bad in ("/portfolio/orders", "/portfolio/balance"):
            with self.assertRaises(RuntimeError):
                cts.assert_read_only(bad)

    def test_it_opens_no_connection_of_its_own(self):
        """It must reach the network ONLY through crypto_tail_sample, whose guard
        is tested above -- so it cannot grow a call that bypasses the whitelist.

        Checked line by line, outside strings and comments, and reported as a
        line number rather than by dumping the file: a crude assertNotIn over the
        whole source both false-positives on the docstring (which cites
        /portfolio/settlements as the control's source) and prints everything it
        was given on failure. The os.environ lesson in research/screen applies to
        any assertion over a large value.
        """
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "skipped_hours.py")
        bad = []
        for i, line in enumerate(open(path), 1):
            code = line.split("#", 1)[0]
            if code.lstrip().startswith(('"', "'")):
                continue
            for tok in ("urlopen", "urllib.request", "http.client", "requests.", "socket."):
                if tok in code:
                    bad.append(f"{i}: {tok}")
        self.assertEqual(bad, [], f"opens its own connection at {bad}; go through cts.get")

    def test_the_only_network_helpers_it_uses_are_the_guarded_ones(self):
        import crypto_tail_sample as cts
        for name in ("get", "price_at", "hours_in", "fetch_spot"):
            self.assertTrue(hasattr(cts, name), f"cts.{name} is what this script relies on")


if __name__ == "__main__":
    unittest.main(verbosity=2)


class TheFetchWindowMustNotClipTheBand(unittest.TestCase):
    """~190 strikes an hour at one candle call each is minutes per hour, so only
    strikes within a distance of spot are priced. If that window clipped the
    0.90-0.96 band the sample would select on VOLATILITY -- a quiet hour puts
    the band near spot, a wild one far -- which is exactly the hour-of-day
    question being asked, so the bias would land on the answer."""

    def test_the_outer_edge_clears_the_observed_band_with_margin(self):
        # Calibrated on the first in-band picks: $192 to $435 from spot.
        self.assertGreaterEqual(sh.WINDOW_HI, 435 + sh.CLIP_MARGIN)
        self.assertGreaterEqual(sh.WINDOW_HI / 435.0, 3.0, "leave real margin over the observed band")

    def test_the_inner_edge_cannot_reach_a_deep_itm_price(self):
        """A strike within WINDOW_LO of spot is near the money, nowhere near
        0.90-0.96, so the inner edge cannot clip the band -- which is why only
        the outer edge is guarded. The observed band starts at $192."""
        self.assertLess(sh.WINDOW_LO, 192, "the inner edge must sit inside the nearest in-band strike")

    def test_the_window_is_a_distance_from_spot_at_the_lead_moment(self):
        """Not from the settlement price: that would be lookahead, and a window
        drawn around the outcome would pick the strikes that won."""
        src = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "skipped_hours.py")).read()
        self.assertIn("abs(k - spot)", src)
        self.assertNotIn("abs(k - close", src)

    def test_only_the_outer_edge_is_guarded(self):
        """An inner guard warned on every sample: the band reaches $192 and the
        margin is $200. A guard that always fires is one that gets ignored."""
        self.assertGreater(sh.CLIP_MARGIN, 0)
        src = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "skipped_hours.py")).read()
        self.assertIn("WINDOW_HI - CLIP_MARGIN", src)
        self.assertNotIn("WINDOW_LO + CLIP_MARGIN", src)
