"""Freeze the timestamp rule that a spurious finding turned on.

    python3 -m unittest discover -s research/screen -p 'test_*.py'

The factor study reported distance-from-spot at -4.20c with ratio 1.63 and 87 of
98 losses in one bucket, consistent across both halves of the timeline. It was a
lookahead: Coinbase candle keys are the bucket START, so `H[floor(entry/3600)]`
is a price up to 3,600 s AFTER entry. A losing position's price had already
drifted toward its strike, so the leak WAS the signal. Corrected, the same
bucket reads +1.56c.

This is the fourth lookahead recorded across these repos and every one was
caught by a person reading a timestamp, never by a test. So: a test.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


class CandleKeysAreBucketStarts(unittest.TestCase):
    """`H[b]` holds the close of the bucket that STARTS at b, i.e. the price at
    b + granularity. Verified live against Coinbase: bucket 1795003200 is
    2026-11-18T12:00:00Z and its close is the 13:00:00Z price."""

    GRAN = 3600

    def close_time_of(self, bucket_start):
        return bucket_start + self.GRAN

    def test_a_buckets_close_is_after_its_key(self):
        self.assertEqual(self.close_time_of(1795003200), 1795006800)

    def test_the_bucket_containing_an_instant_closes_after_it(self):
        """This is the trap. The bucket an entry falls INSIDE has not closed yet."""
        entry = 1795003200 + 1200          # 20 minutes into the bucket
        containing = (entry // self.GRAN) * self.GRAN
        self.assertGreater(self.close_time_of(containing), entry,
                           "the containing bucket's close is in the future at entry")

    def test_the_newest_usable_bucket_is_one_earlier(self):
        entry = 1795003200 + 1200
        usable = (entry // self.GRAN) * self.GRAN - self.GRAN
        self.assertLessEqual(self.close_time_of(usable), entry,
                             "the chosen bucket must have closed at or before entry")


class SpotMustPrecedeEntry(unittest.TestCase):
    def setUp(self):
        import deep_itm_factors as m
        self.m = m
        self._H = m.H
        self.addCleanup(lambda: setattr(m, "H", self._H))

    def test_close_at_or_before_never_returns_a_future_price(self):
        import deep_itm_factors as m
        base = 1795003200
        m.H = {base - 3600 * i: 60000.0 + i for i in range(0, 8)}
        for offset in (1, 600, 1200, 3599):
            entry = base + offset
            px, t = m.close_at_or_before(entry)
            self.assertIsNotNone(px)
            self.assertLessEqual(t, entry, f"returned a close at {t} for an entry at {entry}")

    def test_it_walks_back_over_a_gap(self):
        import deep_itm_factors as m
        base = 1795003200
        # the two most recent buckets are missing
        m.H = {base - 3600 * i: 60000.0 for i in range(3, 9)}
        px, t = m.close_at_or_before(base + 600)
        self.assertIsNotNone(px, "a gap must be walked back, not treated as no data")
        self.assertLessEqual(t, base + 600)

    def test_no_data_returns_none_rather_than_a_guess(self):
        import deep_itm_factors as m
        m.H = {}
        self.assertEqual(m.close_at_or_before(1795003200), (None, None))


class TheLagIsAsserted(unittest.TestCase):
    """The script prints the entry-minus-spot lag and it must never be negative.
    In the broken version it was a median of -1085 s, i.e. the spot came after
    the entry; that single line is what would have caught it."""

    def _src_lines(self):
        p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "deep_itm_factors.py")
        return open(p).read().splitlines()

    def test_the_script_refuses_to_report_on_a_negative_lag(self):
        """Not a prose match -- the behaviour. main() must compute the lag and
        bail rather than print factors built on a spot taken after its entry.

        Asserted line-by-line with a line number in the message: an assertIn
        over a whole file prints the whole file on failure, which is the same
        mistake test_kalshi_auth.py records for os.environ."""
        lines = self._src_lines()
        guard = [i for i, l in enumerate(lines, 1) if "lags[0] < 0" in l]
        self.assertTrue(guard, "main() must check the minimum lag")
        refuse = [i for i, l in enumerate(lines, 1) if "LOOKAHEAD" in l and "print" in l]
        self.assertTrue(refuse, "a negative lag must say so, not pass silently")
        after = [i for i, l in enumerate(lines, 1) if i > guard[0] and l.strip() == "return 1"]
        self.assertTrue(after, f"the guard at line {guard[0]} must return non-zero")

    def test_importing_the_module_does_no_network_io(self):
        """Importing it must not fetch candles. The first version ran the whole
        analysis at import, so `python -m unittest` spent a minute on the
        network -- and a module whose import does I/O cannot be unit-tested."""
        lines = self._src_lines()
        bad = [f"{i}: {l.strip()[:60]}" for i, l in enumerate(lines, 1)
               if not l.startswith((" ", "\t", "#", "'", '"'))
               and ("fetch(" in l or "k.page(" in l) and "def " not in l]
        self.assertEqual(bad, [], f"module-level network call at {bad}")


class SplitHalfIsTheMethod(unittest.TestCase):
    """A factor whose best cell changes between halves is noise. Two of this
    repo's rejected parameters (04-05 ET, Q2 volatility) look excellent on the
    full sample and flip on the split."""

    def test_the_script_splits_every_factor(self):
        src = open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "deep_itm_factors.py")).read()
        self.assertIn("mid", src)
        self.assertIn('x["date"]<mid', src.replace(" ", ""))
        self.assertIn('x["date"]>=mid', src.replace(" ", ""))


if __name__ == "__main__":
    unittest.main(verbosity=2)
