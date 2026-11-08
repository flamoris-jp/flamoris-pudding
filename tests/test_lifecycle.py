import unittest
from datetime import datetime, timedelta, timezone

from flamoris_pudding.lifecycle import age, deadlines, parse_time, utc_text


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.purchase = parse_time("2026-11-08T10:00:00+09:00")
        self.record = deadlines(self.purchase)

    def test_deadlines_are_purchase_based_and_canonical_utc(self):
        self.assertEqual(self.record, {
            "purchased_at": "2026-11-08T01:00:00.000000Z",
            "best_before_at": "2026-11-22T01:00:00.000000Z",
            "dispose_after_at": "2026-12-08T01:00:00.000000Z",
            "timezone": "Asia/Tokyo",
            "rules_version": "lifecycle-v0",
        })
        best = parse_time(self.record["best_before_at"])
        self.assertEqual(best - self.purchase, timedelta(hours=336))

    def test_calendar_month_clamps_nonleap_and_leap_february(self):
        cases = [
            ("2027-01-31T23:59:59.123456+09:00", "2027-02-28T14:59:59.123456Z"),
            ("2028-01-31T23:59:59.123456+09:00", "2028-02-29T14:59:59.123456Z"),
            ("2026-12-31T10:00:00+09:00", "2027-01-31T01:00:00.000000Z"),
            ("2026-03-31T00:00:00+09:00", "2026-04-29T15:00:00.000000Z"),
        ]
        for purchase, expected in cases:
            with self.subTest(purchase=purchase):
                self.assertEqual(deadlines(parse_time(purchase))["dispose_after_at"], expected)

    def test_calendar_day_is_jst_not_the_utc_day(self):
        # UTC January 30 is already January 31 in Japan.
        record = deadlines(parse_time("2027-01-30T15:00:00Z"))
        self.assertEqual(record["dispose_after_at"], "2027-02-27T15:00:00.000000Z")

    def test_exact_soft_and_hard_boundaries(self):
        best = parse_time(self.record["best_before_at"])
        hard = parse_time(self.record["dispose_after_at"])
        self.assertEqual(age(self.record, best - timedelta(microseconds=1))["status"], "fresh")
        at_best = age(self.record, best)
        self.assertEqual(at_best["status"], "expired_pending_disposal")
        self.assertEqual(at_best["quality_q"], 1000)
        self.assertEqual(at_best["rotten_egg_like_q"], 0)
        self.assertEqual(age(self.record, hard - timedelta(microseconds=1))["status"], "expired_pending_disposal")
        at_hard = age(self.record, hard)
        self.assertEqual(at_hard["status"], "disposal_due")
        self.assertEqual(at_hard["quality_q"], 0)
        self.assertEqual(at_hard["rotten_egg_like_q"], 1000)
        self.assertEqual(age(self.record, hard + timedelta(days=100)), at_hard)

    def test_authored_curve_and_final_rounding(self):
        best = parse_time(self.record["best_before_at"])
        duration = parse_time(self.record["dispose_after_at"]) - best
        onset = age(self.record, best + duration * 3 // 4)
        self.assertEqual((onset["quality_q"], onset["rotten_egg_like_q"]), (250, 0))
        late = age(self.record, best + duration * 7 // 8)
        self.assertEqual((late["quality_q"], late["rotten_egg_like_q"]), (125, 500))
        self.assertEqual(late["basis"], "authored")
        self.assertEqual(late["model_version"], "aging-authored-v0")
        # Half a q unit rounds up; no floating point or premature u rounding.
        rounding = age(self.record, best + duration * 1999 // 2000)
        self.assertEqual(rounding["quality_q"], 1)

    def test_invalid_timestamps_and_range_are_rejected(self):
        for value in [None, True, 1, "", "2026-11-08", "2026-11-08T12:00:00", "broken"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_time(value)
        for value in [None, True, datetime(2026, 11, 8)]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                deadlines(value)
        with self.assertRaises(ValueError):
            deadlines(datetime(9999, 12, 1, tzinfo=timezone.utc))
        for bad in [{}, {"best_before_at": "bad", "dispose_after_at": "bad"},
                    {"best_before_at": self.record["dispose_after_at"],
                     "dispose_after_at": self.record["best_before_at"]}]:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                age(bad, self.purchase)
        with self.assertRaises(ValueError):
            age(self.record, datetime(2026, 11, 8))

    def test_read_is_pure_and_offset_round_trip(self):
        original = dict(self.record)
        first = age(self.record, self.purchase)
        self.assertEqual(self.record, original)
        self.assertEqual(age(self.record, self.purchase), first)
        self.assertEqual(parse_time(utc_text(self.purchase)), self.purchase)


if __name__ == "__main__":
    unittest.main()
