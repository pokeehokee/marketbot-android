"""Freeze the guard that keeps the credentialed research scripts read-only.

stdlib unittest, no network, no credentials. Run with:
    python3 -m unittest discover -s research/screen -p 'test_*.py'

These scripts are the only ones in research/ that sign with the account's key,
so the whitelist is the whole of their safety. research/crypto and
research/commodity both freeze their equivalents; this one shipped without a
test, which is the same "the control exists but nothing checks it" shape the
branch it came in on is about.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import kalshi_auth as k


class ReadOnlyGuard(unittest.TestCase):
    def test_order_and_write_paths_are_refused(self):
        for bad in ("/portfolio/orders", "/portfolio/orders/abc", "/portfolio/balance",
                    "/portfolio/intra_exchange_instance_transfer", "/markets/orders",
                    "/portfolio/order_groups/create"):
            with self.assertRaises(RuntimeError, msg=f"{bad} was allowed"):
                k.assert_read_only(bad)

    def test_the_three_reads_it_needs_are_allowed(self):
        for ok in ("/portfolio/settlements", "/portfolio/settlements?limit=200",
                   "/portfolio/fills", "/portfolio/positions?settlement_status=settled"):
            k.assert_read_only(ok)

    def test_a_prefix_match_is_not_enough(self):
        """`/portfolio/settlements/cancel` starts with an allowed path and must
        still be refused: the check is on the path, not on a prefix of it."""
        for bad in ("/portfolio/settlements/cancel", "/portfolio/positionsX",
                    "/portfolio/fills/../orders"):
            with self.assertRaises(RuntimeError, msg=f"{bad} was allowed"):
                k.assert_read_only(bad)

    def test_get_refuses_before_it_signs_or_sends(self):
        """The guard must run before a key is loaded and before any request is
        made, so a bad path cannot even produce a signature.

        NEVER assert on os.environ here. The first version of this test did
        (`assertNotIn("KALSHI_PRIVATE_KEY", os.environ, ...)`) and unittest put
        the whole environment in the failure message -- the live trading key
        among it. A test that can print a credential is a credential leak with
        extra steps, and in CI it would land in a public log. Prove the guard by
        showing nothing was sent instead.
        """
        sent = []
        real = k.urllib.request.urlopen
        k.urllib.request.urlopen = lambda *a, **kw: sent.append(a) or (_ for _ in ()).throw(AssertionError("sent!"))
        try:
            with self.assertRaises(RuntimeError):
                k.get("/portfolio/orders")
        finally:
            k.urllib.request.urlopen = real
        self.assertEqual(sent, [], "a refused path must not reach the network")


class RateLimitBackoff(unittest.TestCase):
    def _fake_creds(self):
        """A stand-in key id and signer, restored afterwards. Never read or print
        the real ones -- see the note in test_get_refuses_before_it_signs_or_sends."""
        had = "KALSHI_KEY_ID" in os.environ
        prev = os.environ.get("KALSHI_KEY_ID")
        real_key = k._key
        os.environ["KALSHI_KEY_ID"] = "test"
        k._key = lambda: _FakeKey()

        def restore():
            k._key = real_key
            if had:
                os.environ["KALSHI_KEY_ID"] = prev
            else:
                os.environ.pop("KALSHI_KEY_ID", None)
        self.addCleanup(restore)

    """A rate-limited page yields an EMPTY list, which reads exactly like "this
    account has nothing" -- the reason weatherbot's settlements table once held
    159 of 425 records. 429 is retried inside get(), never at a call site."""

    def test_429_is_retried_then_succeeds(self):
        calls = {"n": 0}
        real = k.urllib.request.urlopen
        self._fake_creds()

        def fake(req, timeout=0):
            calls["n"] += 1
            if calls["n"] < 3:
                raise k.urllib.error.HTTPError(req.full_url, 429, "Too Many", {}, None)
            import io
            return io.BytesIO(b'{"settlements": []}')

        k.urllib.request.urlopen = fake
        try:
            d, e = k.get("/portfolio/settlements", _sleep=lambda s: None)
        finally:
            k.urllib.request.urlopen = real
        self.assertIsNone(e)
        self.assertEqual(calls["n"], 3)

    def test_a_non_429_error_is_not_retried(self):
        calls = {"n": 0}
        real = k.urllib.request.urlopen
        self._fake_creds()

        def fake(req, timeout=0):
            calls["n"] += 1
            raise k.urllib.error.HTTPError(req.full_url, 401, "nope", {}, None)

        k.urllib.request.urlopen = fake
        try:
            d, e = k.get("/portfolio/settlements", _sleep=lambda s: None)
        finally:
            k.urllib.request.urlopen = real
        self.assertEqual(e, "HTTP 401")
        self.assertEqual(calls["n"], 1, "a 401 must not be retried four times")


class BookRouting(unittest.TestCase):
    """Every per-book figure in CLAUDE.md is cut by this function, so a ticker
    landing in the wrong book silently moves money between the tables."""

    def test_each_traded_series_lands_in_its_own_book(self):
        self.assertEqual(k.book("KXBTCD-26NOV2012-T90000"), "btc_ladder")
        for t in ("KXGOLDD-26NOV1617-T3857", "KXWTI-26NOV1614-T87.49",
                  "KXBRENTD-26NOV1717-T91", "KXNATGASD-26NOV1817-T3.805",
                  "KXSILVERD-26NOV1717-T47", "KXCOPPERD-26NOV1717-T6.10"):
            self.assertEqual(k.book(t), "commodity", t)
        self.assertEqual(k.book("KXHIGHNY-26AUG11-T90"), "weather")
        self.assertEqual(k.book("KXLOWTCHI-26SEP11-T57"), "weather")
        self.assertEqual(k.book("KXFOMCDISSENTCOUNT-26DEC-T2"), "other")

    def test_the_ladder_is_not_mistaken_for_weather(self):
        """KXBTCD carries no HIGH/LOW, but order matters if one is ever added."""
        self.assertEqual(k.book("KXBTCD-26NOV2012-T90000"), "btc_ladder")


class FixedPointParsing(unittest.TestCase):
    """Kalshi sends prices and sizes as fixed-point STRINGS; a missing field is
    None, not 0, and must not read as a price."""

    def test_strings_parse_and_missing_fields_are_zero(self):
        self.assertEqual(k.num({"yes_count_fp": "5.00"}, "yes_count_fp"), 5.0)
        self.assertEqual(k.num({"a": None}, "a"), 0.0)
        self.assertEqual(k.num({}, "missing"), 0.0)
        self.assertEqual(k.num({"a": "not a number"}, "a"), 0.0)


class _FakeKey:
    def sign(self, data):
        return b"x" * 16


if __name__ == "__main__":
    unittest.main(verbosity=2)
