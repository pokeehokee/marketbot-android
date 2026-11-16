#!/usr/bin/env python3
"""The payload rules book_collector.py depends on, frozen.

Run: python3 -m unittest discover -s research/commodity -p 'test_*.py' -v

Every rule here was verified against live Kalshi on 2026-11-16 and every one of them is a way the
collector could record something plausible and wrong. A dataset that is quietly wrong is worse than
no dataset, because the analysis built on it looks finished.
"""
from __future__ import annotations

import os
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import book_collector as bc  # noqa: E402


class TestEmptyBookIsNeverAPrice(unittest.TestCase):
    """Kalshi reports a side with nothing resting as 0.0000 (bid) / 1.0000 (ask).

    It is the same shape a CLOSED market quotes. Reading either as a transactable price is the bug
    that made weatherbot's first stop-loss replay report a 97% trigger rate at -72c/contract.
    """

    def test_the_sentinels_become_none(self):
        self.assertIsNone(bc.price("0.0000"), "a 0.0000 bid means nothing is resting")
        self.assertIsNone(bc.price("1.0000"), "a 1.0000 ask means nothing is resting")
        self.assertIsNone(bc.price("0"))
        self.assertIsNone(bc.price("1"))

    def test_real_prices_survive(self):
        self.assertEqual(bc.price("0.0100"), 0.01)
        self.assertEqual(bc.price("0.9900"), 0.99)
        self.assertEqual(bc.price("0.4050"), 0.405)

    def test_garbage_is_none_not_zero(self):
        for bad in (None, "", "abc", [], {}):
            self.assertIsNone(bc.price(bad))
            self.assertIsNone(bc.num(bad))

    def test_zero_size_is_absence_not_a_quantity(self):
        self.assertIsNone(bc.size("0.00"), "0 resting contracts is absence, not a size")
        self.assertEqual(bc.size("537.00"), 537.0)
        self.assertEqual(bc.size("0.01"), 0.01, "fractional contracts exist")

    def test_a_market_with_no_yes_bids_stores_null_both_ways(self):
        # The live shape of KXGOLDD-26NOV1617-T4087: no YES bids at all.
        m = {"yes_bid_dollars": "0.0000", "yes_bid_size_fp": "0.00",
             "yes_ask_dollars": "0.0100", "yes_ask_size_fp": "537.00",
             "no_ask_dollars": "1.0000"}
        top = bc.top_from_market(m)
        self.assertIsNone(top["yes_bid"])
        self.assertIsNone(top["yes_bid_size"])
        self.assertEqual(top["yes_ask"], 0.01)
        self.assertEqual(top["yes_ask_size"], 537.0)


class TestTheYesAskIsOneMinusTheBestNoBid(unittest.TestCase):
    """The orderbook carries ONLY BIDS. There is no ask side to read.

    Verified live twice: on KXGOLDD-26NOV1617-T4087 the ladder's best NO bid was 0.99/537 and the
    market object independently read yes_ask 0.0100 / size 537.00; on KXWTI-26NOV1614-T88.49 it was
    0.59/204 against yes_ask 0.41 / size 204.
    """

    def test_best_level_is_the_highest_not_the_last(self):
        # Ascending is what the server sends, but relying on its ordering is a silent dependency.
        self.assertEqual(bc.best_level([["0.01", "5"], ["0.59", "204"]]), (0.59, 204.0))
        self.assertEqual(bc.best_level([["0.59", "204"], ["0.01", "5"]]), (0.59, 204.0),
                         "a reordered payload must give the same best level")

    def test_the_gold_market_derivation(self):
        best_no = bc.best_level([["0.0100", "15740.00"], ["0.9900", "537.00"]])
        self.assertIsNotNone(best_no)
        self.assertAlmostEqual(1 - best_no[0], 0.01, places=6)
        self.assertEqual(best_no[1], 537.0, "the queue at the ask is that NO level's size")

    def test_an_empty_side_is_none_whether_absent_or_an_empty_list(self):
        # The docs say an absent side is absent. Live, it comes back as [] -- a case they omit.
        self.assertIsNone(bc.best_level([]), "an EMPTY LIST means nothing is resting")
        self.assertIsNone(bc.best_level(None), "an absent side means nothing is resting")

    def test_zero_quantity_levels_are_ignored(self):
        self.assertEqual(bc.best_level([["0.90", "0"], ["0.50", "10"]]), (0.5, 10.0))

    def test_malformed_levels_do_not_crash_or_fabricate(self):
        self.assertIsNone(bc.best_level([[], ["x"], None]))


class TestTheCommaBatchTrap(unittest.TestCase):
    """`tickers=A,B` is ACCEPTED and returns one fabricated empty book.

    Verified live: the response carried a single entry whose `ticker` was the literal string
    "KXGOLDD-...-T4087,KXGOLDD-...-T4077" with BOTH sides empty. Empty sides are exactly how a
    market with nothing resting looks, so this does not fail -- it invents empty books for every
    market asked about. The collector must therefore key off the ticker the response names and
    discard anything it did not ask for.
    """

    def _stub(self, payload):
        calls = []

        def fake_get(path, *a, **k):
            calls.append(path)
            return payload
        bc_get, bc.get = bc.get, fake_get
        self.addCleanup(lambda: setattr(bc, "get", bc_get))
        return calls

    def test_the_request_repeats_the_parameter_and_never_joins_with_commas(self):
        calls = self._stub({"orderbooks": []})
        bc.fetch_depth(["AAA", "BBB"])
        self.assertEqual(len(calls), 1)
        self.assertIn("tickers=AAA", calls[0])
        self.assertIn("tickers=BBB", calls[0])
        self.assertNotIn("AAA,BBB", calls[0], "the comma form silently fabricates empty books")

    def test_a_comma_joined_entry_is_discarded(self):
        self._stub({"orderbooks": [
            {"ticker": "AAA,BBB", "orderbook_fp": {"yes_dollars": [], "no_dollars": []}}]})
        got = bc.fetch_depth(["AAA", "BBB"])
        self.assertEqual(got, {}, "an entry naming a ticker we never asked for is not data")

    def test_entries_are_keyed_by_the_response_not_by_input_order(self):
        self._stub({"orderbooks": [
            {"ticker": "BBB", "orderbook_fp": {"no_dollars": [["0.70", "9"]]}},
            {"ticker": "AAA", "orderbook_fp": {"no_dollars": [["0.60", "4"]]}}]})
        got = bc.fetch_depth(["AAA", "BBB"])
        self.assertEqual(set(got), {"AAA", "BBB"})
        # Paired by position this would be inverted, which is the whole point.
        self.assertEqual(bc.best_level(got["AAA"][0]["orderbook_fp"]["no_dollars"]), (0.6, 4.0))
        self.assertEqual(bc.best_level(got["BBB"][0]["orderbook_fp"]["no_dollars"]), (0.7, 9.0))

    def test_each_ladder_carries_the_instant_its_response_arrived(self):
        self._stub({"orderbooks": [{"ticker": "AAA", "orderbook_fp": {"no_dollars": []}}]})
        got = bc.fetch_depth(["AAA"])
        self.assertIsInstance(got["AAA"][1], float)


class TestItCanOnlyRead(unittest.TestCase):
    """The whitelist is the safety property, so it is enforced on every call."""

    def test_order_and_portfolio_paths_are_refused(self):
        for path in ("/portfolio/orders", "/portfolio/balance", "/portfolio/fills",
                     "/portfolio/positions", "/markets/XYZ/orders",
                     "/markets/XYZ/orderbook/../../portfolio/orders",
                     "/exchange/status", "/portfolio/orders/batched"):
            with self.assertRaises(bc.ReadOnlyViolation, msg=f"{path} must be refused"):
                bc.assert_read_only(path)

    def test_the_three_read_paths_are_allowed(self):
        bc.assert_read_only("/markets?series_ticker=KXGOLDD&status=open&limit=200")
        bc.assert_read_only("/markets/KXGOLDD-26NOV1617-T4087/orderbook")
        bc.assert_read_only("/markets/orderbooks?tickers=AAA&tickers=BBB")

    def test_get_checks_before_it_opens_a_socket(self):
        # No network here: the guard must fire first, so this raises rather than hangs.
        with self.assertRaises(bc.ReadOnlyViolation):
            bc.get("/portfolio/orders")


class TestStorage(unittest.TestCase):
    def setUp(self):
        self.path = os.path.join(tempfile.mkdtemp(), "t.db")
        self.db = bc.open_db(self.path)

    def test_schema_applies_and_is_reentrant(self):
        bc.open_db(self.path)                       # a second open must not fail
        names = {r[0] for r in self.db.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertTrue({"book_top", "book_depth", "cycles"} <= names)

    def test_snapshots_are_idempotent_on_ticker_and_cycle(self):
        row = ("AAA", 1000, 1000.5, "KXGOLDD", 2000, 1000, "active", 2,
               None, None, 0.05, 10.0, 0.05, 1.0, 1.0, 1.0)
        sql = ("INSERT OR IGNORE INTO book_top (ticker,snapshot_ts,fetched_ts,series,close_ts,"
               "lead_s,status,exchange_index,yes_bid,yes_bid_size,yes_ask,yes_ask_size,last_price,"
               "volume,volume_24h,open_interest) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
        self.db.execute(sql, row)
        self.db.execute(sql, row)
        self.db.commit()
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM book_top").fetchone()[0], 1)

    def test_a_null_ask_is_storable_and_stays_null(self):
        """The sentinel must reach the database as NULL, not as 1.0 and not as 0."""
        self.db.execute(
            "INSERT INTO book_top (ticker,snapshot_ts,yes_ask) VALUES ('AAA',1,NULL)")
        self.db.commit()
        self.assertIsNone(self.db.execute(
            "SELECT yes_ask FROM book_top WHERE ticker='AAA'").fetchone()[0])


class TestWindowing(unittest.TestCase):
    def test_iso_parsing_and_bad_input(self):
        self.assertAlmostEqual(bc.iso_to_ts("1970-01-01T00:00:00Z"), 0.0)
        self.assertIsNone(bc.iso_to_ts(None))
        self.assertIsNone(bc.iso_to_ts(""))
        self.assertIsNone(bc.iso_to_ts("not a date"))

    def test_utc_is_timezone_aware(self):
        self.assertIsNotNone(bc.utc(0).tzinfo, "a naive UTC datetime is how timezone bugs start")


if __name__ == "__main__":
    unittest.main(verbosity=2)
