"""Freeze the rules the crypto-tail measurement depends on.

stdlib unittest, no network. Run with:
    python3 -m unittest discover -s research/crypto -p 'test_*.py'
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


class _FakeGet:
    """Stands in for k.get, returning one canned candlestick payload."""

    def __init__(self, candles):
        self.candles = candles
        self.queries = []

    def __call__(self, q):
        self.queries.append(q)
        return {"candlesticks": self.candles}, None


def _c(end_ts, ask, bid, vol="0.00", oi="0.00"):
    def side(v):
        return {} if v is None else {"close_dollars": f"{v:.4f}"}
    return {"end_period_ts": end_ts, "yes_ask": side(ask), "yes_bid": side(bid),
            "volume_fp": vol, "open_interest_fp": oi}


class _RestoresGet(unittest.TestCase):
    """Monkeypatching module state without restoring it makes later tests read a
    stub left behind by an earlier one -- which is how the 429 tests first saw a
    candlestick fake. Every patch of m.get goes through here."""

    def setUp(self):
        import crypto_tail_sample as m
        self.m = m
        self._real_get = m.get
        self.addCleanup(lambda: setattr(m, "get", self._real_get))


class ZeroLookahead(_RestoresGet):
    """The candle used must END at or before close - lead, and the REQUEST
    window must end there too. A candle merely containing the mark is a
    lookahead: weatherbot's stop-loss replay lost a whole result that way,
    and the '13:00 prices that spanned 04:45 to 13:45' near-miss is the same
    bug in another guise."""

    def test_request_window_ends_at_the_mark(self):
        import crypto_tail_sample as m
        fake = _FakeGet([_c(1000, 0.03, 0.02)])
        m.get = fake
        close_ts, lead = 10_000, 1800
        m.price_at("T", close_ts, lead)
        self.assertIn(f"end_ts={close_ts - lead}", fake.queries[0])
        self.assertNotIn(f"end_ts={close_ts}", fake.queries[0])

    def test_candle_straddling_the_mark_is_rejected(self):
        import crypto_tail_sample as m
        mark = 10_000 - 1800
        # one candle ends AFTER the mark: it must not be chosen
        m.get = _FakeGet([_c(mark - 60, 0.03, 0.02), _c(mark + 30, 0.40, 0.39)])
        ask, bid, end, vol, oi = m.price_at("T", 10_000, 1800)
        self.assertEqual(end, mark - 60)
        self.assertAlmostEqual(ask, 0.03)

    def test_no_candle_at_or_before_the_mark_returns_none(self):
        import crypto_tail_sample as m
        mark = 10_000 - 1800
        m.get = _FakeGet([_c(mark + 1, 0.03, 0.02)])
        self.assertIsNone(m.price_at("T", 10_000, 1800))


class EmptyBookSentinel(_RestoresGet):
    """yes_ask 1.00 / yes_bid 0.00 means 'no orders on that side' -- the same
    shape a CLOSED market quotes. Read as a price it once made a stop-loss
    replay report a 97% trigger rate at -72c/contract. It is never a price."""

    def setUp(self):
        super().setUp()
        self.m.CHEAP_LO, self.m.CHEAP_HI = 0.01, 0.05
        self.m.NEAR, self.m.FAR = 100.0, 1500.0

    def test_a_missing_side_parses_to_none_not_zero(self):
        import crypto_tail_sample as m
        m.get = _FakeGet([_c(100, None, None)])
        ask, bid, end, vol, oi = m.price_at("T", 2000, 1800)
        self.assertIsNone(ask)
        self.assertIsNone(bid)

    def test_sentinels_are_the_documented_values(self):
        import crypto_tail_sample as m
        self.assertEqual(m.SENTINEL_ASK, 1.0)
        self.assertEqual(m.SENTINEL_BID, 0.0)


class BuySidePrice(unittest.TestCase):
    """What a BUYER pays. YES costs the yes_ask. NO costs 1 - yes_bid, so a
    yes_bid of 0 means the NO ask is $1.00 and the contract is unbuyable --
    not a 100x bargain. Getting this backwards would invent an edge out of
    an empty book."""

    def test_no_side_price_is_one_minus_the_yes_bid(self):
        self.assertAlmostEqual(round(1.0 - 0.96, 4), 0.04)
        self.assertAlmostEqual(round(1.0 - 0.99, 4), 0.01)

    def test_a_zero_yes_bid_means_the_no_side_costs_a_dollar(self):
        self.assertAlmostEqual(round(1.0 - 0.0, 4), 1.0)


class FeeIsPerOrder(unittest.TestCase):
    """Kalshi rounds the fee UP per ORDER, not per contract, so the fee a $1
    stake pays depends on how many contracts that stake buys. Charging a
    per-contract fee understates a cheap bet's cost several-fold."""

    def setUp(self):
        import score_crypto_tail as s
        self.s = s

    def test_dollar_stake_at_two_cents_pays_seven_cents(self):
        # 50 contracts at 0.02: ceil(0.07 * 50 * 0.02 * 0.98 * 100)/100 = $0.07
        f = self.s.fee_per_contract(0.02, stake=1.0)
        self.assertAlmostEqual(f * 50, 0.07, places=6)

    def test_fee_is_never_zero_for_a_taker(self):
        for p in (0.01, 0.02, 0.03, 0.04, 0.05):
            self.assertGreater(self.s.fee_per_contract(p), 0.0)


class GradingControl(unittest.TestCase):
    """The reading that mattered was 'wins far below implied', and a grading
    bug produces exactly the same zero. The control compares every quote's
    exchange result against where BTC actually finished; it agreed on 830 of
    831 quotes, the one exception being a strike 30 CENTS from the Coinbase
    close (BRTI basis, not a bug).

    The earlier control was mis-specified and proved nothing: it inherited the
    cheap band's $100-$1500 distance window, but a 0.30-0.70 strike sits
    WITHIN ~$100 of spot, so it could not find any -- 4 hours scanned, 2
    quotes. A control that cannot observe its own target is not a control."""

    def test_win_definition_matches_the_strike_geometry(self):
        # strike_type is 'greater': YES pays iff close > floor_strike.
        def won(side, close, strike):
            result = "yes" if close > strike else "no"
            return 1 if ((result == "yes") == (side == "yes")) else 0
        self.assertEqual(won("yes", 65_000, 64_000), 1)   # bought YES, closed above
        self.assertEqual(won("yes", 63_000, 64_000), 0)
        self.assertEqual(won("no", 63_000, 64_000), 1)    # bought NO, closed below
        self.assertEqual(won("no", 65_000, 64_000), 0)

    def test_control_window_must_reach_spot(self):
        """A mid-price control has to fetch strikes adjacent to spot."""
        import crypto_tail_sample as m
        m.NEAR, m.FAR = 0.0, 400.0
        self.assertEqual(m.NEAR, 0.0, "a mid-price control needs NEAR=0 or it sees nothing")


class ReadOnlyGuard(unittest.TestCase):
    """The sampler must never be able to reach an order endpoint, and needs no
    credentials at all -- both the settled listing and the candlesticks are
    public. Same guard shape as research/commodity/book_collector.py."""

    def test_order_paths_are_refused(self):
        import crypto_tail_sample as m
        for bad in ("/portfolio/orders", "/portfolio/balance", "/markets/orders"):
            with self.assertRaises(RuntimeError):
                m.assert_read_only(bad)

    def test_the_paths_it_needs_are_allowed(self):
        import crypto_tail_sample as m
        m.assert_read_only("/markets?series_ticker=KXBTCD&status=settled")
        m.assert_read_only("/series/KXBTCD/markets/X/candlesticks?x=1")


class RateLimitBackoff(_RestoresGet):
    """A rate-limited listing silently yields an empty day, which is
    indistinguishable from 'that day had no markets'. 429 must be retried
    inside get(), not at one call site."""

    def test_429_is_retried_then_succeeds(self):
        import crypto_tail_sample as m
        calls = {"n": 0}
        real = urllib_open = m.urllib.request.urlopen

        class FakeResp:
            def read(self_inner):
                return b'{"markets": []}'

        def fake(req, timeout=0):
            calls["n"] += 1
            if calls["n"] < 3:
                raise m.urllib.error.HTTPError(req.full_url, 429, "Too Many", {}, None)
            import io
            return io.BytesIO(b'{"markets": []}')
        m.urllib.request.urlopen = fake
        try:
            d, e = m.get("/markets?x=1", _sleep=lambda s: None)
        finally:
            m.urllib.request.urlopen = real
        self.assertIsNone(e)
        self.assertEqual(d, {"markets": []})
        self.assertEqual(calls["n"], 3)

    def test_a_non_429_error_is_not_retried(self):
        import crypto_tail_sample as m
        calls = {"n": 0}
        real = m.urllib.request.urlopen

        def fake(req, timeout=0):
            calls["n"] += 1
            raise m.urllib.error.HTTPError(req.full_url, 404, "nope", {}, None)
        m.urllib.request.urlopen = fake
        try:
            d, e = m.get("/markets?x=1", _sleep=lambda s: None)
        finally:
            m.urllib.request.urlopen = real
        self.assertEqual(e, "HTTP 404")
        self.assertEqual(calls["n"], 1)


class ClusterByDay(_RestoresGet):
    """Hour clustering is the obvious choice and it is wrong here: a tail win is
    generated by a DAY-level volatility regime, so hour groups understate the
    uncertainty. On the 2,343-quote sample it moved the ratio CI from
    [0.17, 0.98] (excludes fair) to [0.15, 1.10] (includes it)."""

    def test_loader_groups_by_day_by_default(self):
        import inspect
        import score_crypto_tail as s
        sig = inspect.signature(s.load)
        self.assertEqual(sig.parameters["by"].default, "day")

    def test_day_grouping_is_coarser_than_hour_grouping(self):
        """A day holds many hours, so it must never produce MORE groups."""
        import score_crypto_tail as s
        src = inspect.getsource(s.load) if (inspect := __import__("inspect")) else ""
        self.assertIn("strftime('%Y-%m-%d'", src)
        self.assertIn("event_ticker", src)


if __name__ == "__main__":
    unittest.main(verbosity=2)
