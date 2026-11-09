import unittest

from flamoris_pudding.sensory import evaluate_bite, get_preset, preference_fit, presets


class SensoryTests(unittest.TestCase):
    def test_mixed_bite_and_pure_component_references(self):
        preset = get_preset("custard-v0")
        mix = evaluate_bite(preset, {"body": 8000, "sauce": 2000})
        self.assertEqual((mix["features"]["sweetness_q"],
                          mix["features"]["bitterness_q"]), (666, 132))
        for masses, expected in (({"body": 10000}, (700, 0)),
                                 ({"sauce": 2000}, (600, 800))):
            features = evaluate_bite(preset, masses)["features"]
            self.assertEqual((features["sweetness_q"], features["bitterness_q"]), expected)
        self.assertEqual(mix["features"]["aroma_milk_q"], 560)
        self.assertEqual(mix["features"]["aroma_roasted_sugar_q"], 160)
        self.assertIsNone(mix["features"]["sourness_q"])

    def test_linear_reference_and_exact_half_up_final_rounding(self):
        preset = get_preset("custard-v0")
        features = evaluate_bite(preset, {"body": 8000, "sauce": 2000},
                                 "blend-linear-v0")["features"]
        self.assertEqual((features["sweetness_q"], features["bitterness_q"]), (680, 160))
        preset["components"]["body"]["features"]["hardness_q"] = 1
        preset["components"]["sauce"]["features"]["hardness_q"] = 0
        self.assertEqual(evaluate_bite(preset, {"body": 1, "sauce": 1})
                         ["features"]["hardness_q"], 1)

    def test_variants_change_one_feature_only_and_presets_are_independent(self):
        all_presets = presets()
        base = all_presets.pop("custard-v0")
        for name, variant in all_presets.items():
            if not name.startswith("custard-"):
                continue
            differences = [(component, key)
                           for component in base["components"]
                           for key in base["components"][component]["features"]
                           if base["components"][component]["features"][key]
                           != variant["components"][component]["features"][key]]
            self.assertEqual(len(differences), 1)
            result = evaluate_bite(variant, {"body": 8000, "sauce": 2000}, "blend-linear-v0")
            original = evaluate_bite(base, {"body": 8000, "sauce": 2000}, "blend-linear-v0")
            key = differences[0][1]
            self.assertGreater(result["features"][key], original["features"][key])
            self.assertEqual({k: v for k, v in result["features"].items() if k != key},
                             {k: v for k, v in original["features"].items() if k != key})
        base["components"]["body"]["features"]["sweetness_q"] = 0
        self.assertEqual(get_preset("custard-v0")["components"]["body"]
                         ["features"]["sweetness_q"], 700)

    def test_unknowns_propagate_only_from_active_components(self):
        preset = get_preset("custard-v0")
        preset["components"]["sauce"]["features"]["sweetness_q"] = None
        pure = evaluate_bite(preset, {"body": 5, "sauce": 0})["features"]
        self.assertEqual(pure["sweetness_q"], 700)
        mixed = evaluate_bite(preset, {"body": 5, "sauce": 5})["features"]
        self.assertIsNone(mixed["sweetness_q"])
        self.assertIsNone(mixed["bitterness_q"])
        self.assertEqual(mixed["hardness_q"], 225)

    def test_preference_fit_and_weighted_unknown_coverage(self):
        result = preference_fit({"sweetness_q": 666, "bitterness_q": 132},
                                {"sweetness_q": 816, "bitterness_q": 282},
                                {"sweetness_q": 1, "bitterness_q": 1})
        self.assertEqual(result["fit_q"], 850)
        partial = preference_fit({"sweetness_q": 666, "bitterness_q": None},
                                 {"sweetness_q": 816, "bitterness_q": 282},
                                 {"sweetness_q": 1, "bitterness_q": 3})
        self.assertEqual((partial["fit_q"], partial["partial_fit_q"],
                          partial["coverage_q"]), (None, 850, 250))
        ignored = preference_fit({"sweetness_q": 666},
                                 {"sweetness_q": 816, "bitterness_q": 282},
                                 {"sweetness_q": 1, "bitterness_q": 0})
        self.assertEqual(ignored["fit_q"], 850)
        empty = preference_fit({}, {"sweetness_q": 500}, {"sweetness_q": 1})
        self.assertEqual((empty["fit_q"], empty["partial_fit_q"], empty["coverage_q"]),
                         (None, None, 0))

    def test_invalid_inputs_are_rejected(self):
        for masses in ({}, {"body": 0}, {"body": -1}, {"body": True},
                       {"body": 1.5}, {"body": 1_000_001},
                       {"body": 90001}, {"milk": 1}, {"body": None}):
            with self.subTest(masses=masses), self.assertRaises(ValueError):
                evaluate_bite(get_preset("custard-v0"), masses)
        for name in ("missing", [], None):
            with self.assertRaises(ValueError):
                get_preset(name)
        for value in (-1, 1001, True, float("nan")):
            preset = get_preset("custard-v0")
            preset["components"]["body"]["features"]["sweetness_q"] = value
            with self.assertRaises(ValueError):
                evaluate_bite(preset, {"body": 1})
        for weights in ({"sweetness_q": 0}, {"sweetness_q": -1},
                        {"sweetness_q": True}, {}, {"sweetness_q": 1_000_001}):
            with self.assertRaises(ValueError):
                preference_fit({"sweetness_q": 500}, {"sweetness_q": 500}, weights)


if __name__ == "__main__":
    unittest.main()
