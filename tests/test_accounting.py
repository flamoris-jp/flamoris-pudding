"""Synthetic accounting examples, not an approved tax/recognition policy."""

from dataclasses import FrozenInstanceError, replace
import json
import unittest

from flamoris_pudding.accounting import (
    TaxRate,
    calculate_inclusive_grouped_tax,
    expiry_candidate,
    select_tax_rate,
)


class ExpiryCandidateTests(unittest.TestCase):
    def candidate(self, **changes):
        arguments = {
            "asset_id": "asset-1",
            "purchase_id": "purchase-1",
            "amount_minor": 1100,
            "recognized_minor": 0,
            "refunded_minor": 0,
            "rights_expired_at": "2026-12-08T09:00:00+09:00",
            "processed_at": "2026-12-09T03:00:00+09:00",
            "obligations_cleared": True,
        }
        arguments.update(changes)
        return expiry_candidate(**arguments)

    def test_balanced_review_only_candidate_preserves_effective_date(self):
        candidate = self.candidate()
        self.assertEqual(candidate["amount_minor"], 1100)
        self.assertEqual(candidate["lines"], [
            {"side": "debit", "account": "契約負債", "amount_minor": 1100},
            {"side": "credit", "account": "期限切れ雑収入", "amount_minor": 1100},
        ])
        debit = sum(line["amount_minor"] for line in candidate["lines"]
                    if line["side"] == "debit")
        credit = sum(line["amount_minor"] for line in candidate["lines"]
                     if line["side"] == "credit")
        self.assertEqual(debit, credit)
        self.assertEqual(candidate["effective_at"], "2026-12-08T00:00:00Z")
        self.assertEqual(candidate["processed_at"], "2026-12-08T18:00:00Z")
        self.assertEqual(candidate["status"], "REVIEW_REQUIRED")
        self.assertEqual(candidate["tax_status"], "REVIEW_REQUIRED")
        self.assertIs(candidate["external_posting"], False)
        self.assertEqual(json.loads(json.dumps(candidate)), candidate)

    def test_uncleared_obligations_never_release_the_residual(self):
        self.assertIsNone(self.candidate(obligations_cleared=False))

    def test_full_recognition_full_refund_and_zero_purchase_have_no_release(self):
        for changes in ({"recognized_minor": 1100}, {"refunded_minor": 1100},
                        {"amount_minor": 0},
                        {"recognized_minor": 600, "refunded_minor": 500}):
            with self.subTest(changes=changes):
                self.assertIsNone(self.candidate(**changes))

    def test_partial_recognition_and_refund_release_only_the_residual(self):
        candidate = self.candidate(recognized_minor=400, refunded_minor=200)
        self.assertEqual(candidate["amount_minor"], 500)
        basis = candidate["basis"]
        self.assertEqual(basis["purchase_minor"], basis["recognized_minor"]
                         + basis["refunded_minor"] + candidate["amount_minor"])
        self.assertTrue(all(line["amount_minor"] == 500
                            for line in candidate["lines"]))

    def test_candidate_identity_survives_policy_changes_and_reprocessing(self):
        original = self.candidate()
        replay = self.candidate(policy_version="accounting-v2",
                                processed_at="2027-01-01T00:00:00Z")
        self.assertEqual(original["candidate_id"], replay["candidate_id"])
        self.assertNotEqual(original["policy_version"], replay["policy_version"])
        self.assertNotEqual(original["candidate_id"],
                            self.candidate(asset_id="asset-2")["candidate_id"])

    def test_expiry_at_exact_processing_boundary_is_accepted(self):
        candidate = self.candidate(processed_at="2026-12-08T00:00:00Z")
        self.assertEqual(candidate["effective_at"], candidate["processed_at"])
        with self.assertRaises(ValueError):
            self.candidate(processed_at="2026-12-07T23:59:59.999999Z")

    def test_invalid_money_and_excess_allocations_are_rejected(self):
        for field in ("amount_minor", "recognized_minor", "refunded_minor"):
            for value in (-1, True, False, 1.0, "1", None):
                with self.subTest(field=field, value=value):
                    with self.assertRaises(ValueError):
                        self.candidate(**{field: value})
        with self.assertRaises(ValueError):
            self.candidate(recognized_minor=1000, refunded_minor=101)

    def test_invalid_identity_flags_and_timestamps_are_rejected_even_for_noop(self):
        for field in ("asset_id", "purchase_id", "policy_version"):
            for value in ("", " ", " leading", None, True):
                with self.subTest(field=field, value=value):
                    with self.assertRaises(ValueError):
                        self.candidate(**{field: value})
        for value in (1, "true", None):
            with self.assertRaises(ValueError):
                self.candidate(obligations_cleared=value)
        for value in ("2026-12-08", "2026-12-08T00:00:00",
                      "2026-02-30T00:00:00Z", "2026-12-08T00:00:00+09:70",
                      "2026-12-08T24:00:00Z", "", None):
            for field in ("rights_expired_at", "processed_at"):
                with self.subTest(field=field, value=value):
                    with self.assertRaises(ValueError):
                        self.candidate(obligations_cleared=False, **{field: value})


class TaxFixtureTests(unittest.TestCase):
    def setUp(self):
        # These rates and dates are invented test inputs, not legal tax rules.
        self.old = TaxRate("fixture-tax", "v1", "synthetic-digital", "JP-test",
                           1, 10, "2026-01-01T00:00:00+09:00",
                           "2027-01-01T00:00:00+09:00")
        self.future = TaxRate("fixture-tax", "v2", "synthetic-digital", "JP-test",
                              3, 25, "2027-01-01T00:00:00+09:00")

    def select(self, at, rates=None):
        return select_tax_rate([self.old, self.future] if rates is None else rates,
                               category="synthetic-digital", jurisdiction="JP-test",
                               at=at)

    def test_half_open_effective_periods_across_offset_boundary(self):
        self.assertEqual(self.select("2026-12-31T14:59:59.999999Z"), self.old)
        self.assertEqual(self.select("2026-12-31T15:00:00Z"), self.future)
        self.assertEqual(self.select("2025-12-31T15:00:00Z"), self.old)
        with self.assertRaises(ValueError):
            self.select("2025-12-31T14:59:59.999999Z")

    def test_gap_and_overlap_are_rejected(self):
        with self.assertRaises(ValueError):
            self.select("2027-01-01T00:00:00+09:00", [self.old])
        overlapping = replace(self.future,
                              effective_from="2026-12-01T00:00:00+09:00")
        with self.assertRaises(ValueError):
            self.select("2026-12-10T00:00:00+09:00", [self.old, overlapping])
        # Even a currently unambiguous tax point cannot hide a bad schedule.
        with self.assertRaises(ValueError):
            self.select("2026-02-01T00:00:00+09:00", [self.old, overlapping])
        with self.assertRaises(ValueError):
            self.select("2027-02-01T00:00:00+09:00", [self.future, self.future])

    def test_categories_and_regions_are_independent(self):
        other_category = replace(self.old, category="different")
        other_region = replace(self.old, jurisdiction="different")
        self.assertEqual(self.select("2026-02-01T00:00:00+09:00",
                                     [other_category, self.old, other_region]), self.old)
        with self.assertRaises(ValueError):
            self.select("2026-02-01T00:00:00+09:00", [other_category, other_region])

    def test_invoice_group_rounds_once_instead_of_per_line(self):
        result = calculate_inclusive_grouped_tax([5, 6], self.old)
        self.assertEqual(result["gross_minor"], 11)
        self.assertEqual(result["tax_minor"], 1)
        self.assertEqual(result["net_minor"], 10)
        per_line = sum(calculate_inclusive_grouped_tax([amount], self.old)["tax_minor"]
                       for amount in (5, 6))
        self.assertEqual(per_line, 0)
        self.assertEqual(result["rounding"], "floor")
        self.assertEqual(result["tax_status"], "REVIEW_REQUIRED")
        self.assertIs(result["external_posting"], False)
        self.assertEqual(json.loads(json.dumps(result)), result)

    def test_exact_rational_arithmetic_large_values_and_zero_rate(self):
        result = calculate_inclusive_grouped_tax([1100], self.old)
        self.assertEqual((result["net_minor"], result["tax_minor"]), (1000, 100))
        future = calculate_inclusive_grouped_tax([1120], self.future)
        self.assertEqual((future["net_minor"], future["tax_minor"]), (1000, 120))
        gross = 10**30 + 7
        large = calculate_inclusive_grouped_tax([gross], self.old)
        self.assertEqual(large["tax_minor"], gross // 11)
        self.assertEqual(large["net_minor"] + large["tax_minor"], gross)
        zero = calculate_inclusive_grouped_tax([0, 1100], replace(self.old, numerator=0))
        self.assertEqual((zero["net_minor"], zero["tax_minor"]), (1100, 0))
        self.assertEqual(zero["tax_status"], "REVIEW_REQUIRED")

    def test_original_snapshot_remains_frozen_when_future_rate_is_added(self):
        original = calculate_inclusive_grouped_tax([1100], self.old)
        frozen = json.dumps(original, sort_keys=True)
        self.select("2027-02-01T00:00:00+09:00")
        self.assertEqual(json.dumps(original, sort_keys=True), frozen)
        with self.assertRaises(FrozenInstanceError):
            self.old.numerator = 99
        snapshot = self.old.to_dict()
        snapshot["numerator"] = 99
        self.assertEqual(self.old.numerator, 1)

    def test_invalid_rate_periods_and_numeric_values_are_rejected(self):
        for changes in ({"numerator": True}, {"numerator": -1},
                        {"denominator": False}, {"denominator": 0},
                        {"denominator": 1.0}, {"effective_from": "2026-01-01"},
                        {"effective_until": "2026-01-01T00:00:00+09:00"},
                        {"effective_until": "2025-01-01T00:00:00+09:00"},
                        {"category": ""}):
            with self.subTest(changes=changes):
                with self.assertRaises(ValueError):
                    replace(self.old, **changes)
        for amounts in ([], [-1], [True], [1.2], ["1100"]):
            with self.subTest(amounts=amounts):
                with self.assertRaises(ValueError):
                    calculate_inclusive_grouped_tax(amounts, self.old)
        with self.assertRaises(ValueError):
            self.select("2026-02-01T00:00:00+09:00", [self.old, None])


if __name__ == "__main__":
    unittest.main()
