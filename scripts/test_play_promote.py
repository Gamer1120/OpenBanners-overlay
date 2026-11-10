"""Unit tests for the production promotion decision logic. Run: python3 -m unittest discover -s scripts -v"""

import datetime as dt
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from play_promote import AMS, build_notes, cutoff_for, decide, in_window, next_window_start  # noqa: E402

UTC = dt.timezone.utc


def ams(*args):
    return dt.datetime(*args, tzinfo=AMS)


def utc(*args):
    return dt.datetime(*args, tzinfo=UTC)


class WindowAndCutoff(unittest.TestCase):
    def test_cron_hours_winter(self):
        # CET (UTC+1): 22:15 UTC = 23:15 local (spare), 23:15..04:15 UTC = 00:15..05:15 local (act)
        self.assertFalse(in_window(utc(2026, 11, 10, 22, 15)))
        for h in (23, 0, 1, 2, 3, 4):
            day = 10 if h == 23 else 11
            self.assertTrue(in_window(utc(2026, 11, day, h, 15)), h)

    def test_cron_hours_summer(self):
        # CEST (UTC+2): 22:15..03:15 UTC = 00:15..05:15 local (act), 04:15 UTC = 06:15 (spare)
        for h in (22, 23, 0, 1, 2, 3):
            day = 9 if h >= 22 else 10
            self.assertTrue(in_window(utc(2026, 7, day, h, 15)), h)
        self.assertFalse(in_window(utc(2026, 7, 10, 4, 15)))

    def test_daytime_not_in_window(self):
        self.assertFalse(in_window(ams(2026, 11, 10, 16, 10)))
        self.assertFalse(in_window(ams(2026, 11, 10, 6, 0)))
        self.assertTrue(in_window(ams(2026, 11, 10, 5, 59)))
        self.assertTrue(in_window(ams(2026, 11, 10, 0, 0)))

    def test_cutoff_is_local_midnight(self):
        self.assertEqual(cutoff_for(utc(2026, 11, 10, 23, 15)), utc(2026, 11, 10, 23, 0))  # 00:15 CET on 11th
        self.assertEqual(cutoff_for(utc(2026, 7, 9, 22, 15)), utc(2026, 7, 9, 22, 0))     # 00:15 CEST on 10th

    def test_cutoff_spring_forward(self):
        # 2027-03-28: clocks go 02:00 CET -> 03:00 CEST. Midnight is still CET (UTC+1).
        self.assertEqual(cutoff_for(utc(2027, 3, 28, 1, 30)), utc(2027, 3, 27, 23, 0))  # 03:30 CEST
        self.assertTrue(in_window(utc(2027, 3, 28, 1, 30)))
        self.assertTrue(in_window(utc(2027, 3, 28, 3, 15)))   # 05:15 CEST
        self.assertFalse(in_window(utc(2027, 3, 28, 4, 15)))  # 06:15 CEST

    def test_cutoff_fall_back(self):
        # 2026-10-25: clocks go 03:00 CEST -> 02:00 CET. Midnight is still CEST (UTC+2).
        self.assertEqual(cutoff_for(utc(2026, 10, 25, 1, 30)), utc(2026, 10, 24, 22, 0))  # first 02:30 (CEST)
        self.assertEqual(cutoff_for(utc(2026, 10, 25, 4, 15)), utc(2026, 10, 24, 22, 0))  # 05:15 CET
        self.assertTrue(in_window(utc(2026, 10, 24, 22, 15)))  # 00:15 CEST
        self.assertTrue(in_window(utc(2026, 10, 25, 4, 15)))   # 05:15 CET
        self.assertFalse(in_window(utc(2026, 10, 25, 5, 15)))  # 06:15 CET

    def test_all_window_runs_of_one_night_share_the_cutoff(self):
        for night, runs in ((dt.date(2026, 10, 25), [utc(2026, 10, 24, 22, 15), utc(2026, 10, 25, 4, 15)]),
                            (dt.date(2027, 3, 28), [utc(2027, 3, 27, 23, 15), utc(2027, 3, 28, 3, 15)])):
            cutoffs = {cutoff_for(t) for t in runs}
            self.assertEqual(len(cutoffs), 1)
            self.assertEqual(cutoffs.pop().astimezone(AMS).date(), night)

    def test_next_window_start(self):
        self.assertEqual(next_window_start(ams(2026, 11, 10, 16, 10)), ams(2026, 11, 11, 0, 0))
        t = ams(2026, 11, 11, 2, 0)
        self.assertEqual(next_window_start(t), t)


class Decide(unittest.TestCase):
    cutoff = ams(2026, 11, 11, 0, 0)

    def tags(self, **kw):
        base = {57: ams(2026, 11, 10, 7, 5), 58: ams(2026, 11, 10, 15, 55)}
        base.update({int(k[1:]): v for k, v in kw.items()})
        return base

    def test_tonight_v58(self):
        c, skipped, eligible = decide(57, 58, self.tags(), {56, 57, 58}, set(), self.cutoff)
        self.assertEqual(c, 58)
        self.assertEqual(eligible, [58])

    def test_commit_after_cutoff_waits(self):
        c, skipped, _ = decide(57, 58, self.tags(), {57, 58}, set(), ams(2026, 11, 10, 0, 0))
        self.assertIsNone(c)
        self.assertIn("cutoff", skipped[58])

    def test_commit_exactly_at_cutoff_waits(self):
        c, _, _ = decide(57, 58, self.tags(v58=self.cutoff), {57, 58}, set(), self.cutoff)
        self.assertIsNone(c)

    def test_hold(self):
        c, skipped, _ = decide(57, 58, self.tags(), {57, 58}, {58}, self.cutoff)
        self.assertIsNone(c)
        self.assertIn("hold-v58", skipped[58])

    def test_hold_falls_back_to_older_version(self):
        tags = self.tags(v59=ams(2026, 11, 10, 17, 0))
        c, _, eligible = decide(57, 59, tags, {57, 58, 59}, {59}, self.cutoff)
        self.assertEqual(c, 58)

    def test_idempotent(self):
        self.assertIsNone(decide(58, 58, self.tags(), {57, 58}, set(), self.cutoff)[0])
        self.assertIsNone(decide(59, 58, self.tags(), {57, 58}, set(), self.cutoff)[0])

    def test_not_on_beta_yet(self):
        c, skipped, _ = decide(57, 57, self.tags(), {57, 58}, set(), self.cutoff)
        self.assertIsNone(c)
        self.assertIn("beta", skipped[58])
        c, _, _ = decide(57, 0, self.tags(), {57, 58}, set(), self.cutoff)
        self.assertIsNone(c)

    def test_missing_bundle(self):
        c, skipped, _ = decide(57, 58, self.tags(), {57}, set(), self.cutoff)
        self.assertIsNone(c)
        self.assertIn("bundle", skipped[58])

    def test_picks_max_of_older_bundles(self):
        tags = self.tags(v59=ams(2026, 11, 10, 18, 0), v60=ams(2026, 11, 11, 1, 0))
        c, _, eligible = decide(57, 60, tags, {57, 58, 59, 60}, set(), self.cutoff)
        self.assertEqual((c, eligible), (59, [58, 59]))

    def test_promote_now_ignores_cutoff_but_not_hold(self):
        tags = self.tags(v59=ams(2026, 11, 11, 1, 0))
        c, _, _ = decide(57, 59, tags, {57, 58, 59}, set(), self.cutoff, ignore_cutoff=True)
        self.assertEqual(c, 59)
        c, _, _ = decide(57, 59, tags, {57, 58, 59}, {59}, self.cutoff, ignore_cutoff=True)
        self.assertEqual(c, 58)

    def test_explicit_version_bypasses_hold(self):
        c, _, _ = decide(57, 58, self.tags(), {57, 58}, {58}, self.cutoff, ignore_cutoff=True, explicit=58)
        self.assertEqual(c, 58)

    def test_explicit_version_needs_bundle_and_newer_than_prod(self):
        self.assertIsNone(decide(57, 58, self.tags(), {57}, set(), self.cutoff, explicit=58)[0])
        self.assertIsNone(decide(58, 58, self.tags(), {58}, set(), self.cutoff, explicit=58)[0])


class Notes(unittest.TestCase):
    def test_newest_first_and_range(self):
        logs = {56: "a\n", 57: "b\n", 58: "c\n", 59: "d\n"}
        self.assertEqual(build_notes(logs, 56, 58), "c\n\nb")

    def test_skip_held(self):
        logs = {58: "First open beta release.\n", 59: "New feature.\n"}
        self.assertEqual(build_notes(logs, 57, 59, skip={58}), "New feature.")

    def test_exactly_limit(self):
        logs = {58: "x" * 250, 59: "y" * 248}
        self.assertEqual(len(build_notes(logs, 57, 59)), 500)

    def test_drops_oldest_whole_when_little_room(self):
        logs = {57: "o" * 100, 58: "y" * 470}
        self.assertEqual(build_notes(logs, 56, 58), "y" * 470)

    def test_truncates_oldest_with_ellipsis(self):
        logs = {57: "o" * 300, 58: "y" * 300}
        notes = build_notes(logs, 56, 58)
        self.assertEqual(len(notes), 500)
        self.assertTrue(notes.startswith("y" * 300 + "\n\n"))
        self.assertTrue(notes.endswith("…"))

    def test_single_too_long(self):
        notes = build_notes({58: "z" * 800}, 57, 58)
        self.assertEqual(len(notes), 500)
        self.assertTrue(notes.endswith("…"))

    def test_counts_characters_not_bytes(self):
        notes = build_notes({58: "é" * 500}, 57, 58)
        self.assertEqual(notes, "é" * 500)


if __name__ == "__main__":
    unittest.main()
