"""Deterministic, authored sensory representations, never physical measurements.

q1000 means an integer on this experiment's 0..1000 scale. None is unknown,
not zero. S01 motivates the ingredients; no numerical intensity is sourced
from that manufacturer. See docs/DESIGN.md sections 4.4, 5.2 and 6.3.
"""

from copy import deepcopy
from fractions import Fraction
import re

MAX_MASS_MG = 1_000_000
MAX_FEATURES = 64
_NAME = re.compile(r"[a-z][a-z0-9_]{0,63}\Z")
_MODELS = {"blend-linear-v0", "blend-interaction-v0"}


def _integer(value, maximum, label):
    if type(value) is not int or not 0 <= value <= maximum:
        raise ValueError(f"{label} must be an integer from 0 to {maximum}")
    return value


def _features(values):
    if not isinstance(values, dict) or len(values) > MAX_FEATURES:
        raise ValueError("features must be an object with at most 64 entries")
    for key, value in values.items():
        if not isinstance(key, str) or not _NAME.fullmatch(key):
            raise ValueError("invalid feature name")
        if value is not None:
            _integer(value, 1000, key)


def _round(value):
    """ROUND_HALF_UP for the nonnegative exact fractions in this module."""
    return (2 * value.numerator + value.denominator) // (2 * value.denominator)


def presets():
    """Return independent JSON-safe baseline and one-feature comparisons."""
    unknown = dict(sourness_q=None, saltiness_q=None, umami_q=None,
                   astringency_q=None)
    baseline = {
        "id": "custard-v0", "model_version": "custard-authored-v0",
        "basis": "authored", "source_ids": ["S01"],
        "temperature_c": 8, "physical_viscosity_mpa_s": None,
        "sugar_g_per_100g": None, "brix_percent": None,
        "components": {
            "body": {"mass_mg": 90000, "features": {
                **unknown, "sweetness_q": 700, "bitterness_q": 0,
                "hardness_q": 450, "smoothness_q": 850,
                "aroma_milk_q": 700, "aroma_egg_q": 500,
                "aroma_vanilla_q": 350, "aroma_roasted_sugar_q": 0,
                "aroma_cocoa_q": 0}},
            "sauce": {"mass_mg": 10000, "features": {
                **unknown, "sweetness_q": 600, "bitterness_q": 800,
                "hardness_q": 0, "smoothness_q": 1000,
                "aroma_milk_q": 0, "aroma_egg_q": 0,
                "aroma_vanilla_q": 0, "aroma_roasted_sugar_q": 800,
                "aroma_cocoa_q": 0}}}}
    result = {baseline["id"]: baseline}
    for name, component, feature, value in (
        ("custard-sweeter-v0", "body", "sweetness_q", 800),
        ("custard-firmer-v0", "body", "hardness_q", 600),
        ("custard-bitterer-v0", "sauce", "bitterness_q", 900),
    ):
        variant = deepcopy(baseline)
        variant["id"] = name
        variant["components"][component]["features"][feature] = value
        result[name] = variant
    return result


def get_preset(preset_id):
    if not isinstance(preset_id, str):
        raise ValueError("unknown preset")
    try:
        return presets()[preset_id]
    except KeyError:
        raise ValueError("unknown preset") from None


def evaluate_bite(preset, masses, model="blend-interaction-v0"):
    """Blend exactly, rounding each final feature only once.

    Only positive-mass components influence the result. Unknown intensities
    propagate through every calculation which depends on them. The interaction
    is an authored hypothesis with alpha=1/5, beta=2/5, not a sensory finding.
    """
    if not isinstance(model, str) or model not in _MODELS:
        raise ValueError("unknown blend model")
    if not isinstance(preset, dict) or preset.get("basis") != "authored":
        raise ValueError("an authored preset is required")
    components = preset.get("components")
    if (not isinstance(components, dict) or not components
            or not set(components) <= {"body", "sauce"}):
        raise ValueError("components must contain body and/or sauce")
    for component in components.values():
        if not isinstance(component, dict):
            raise ValueError("invalid component")
        _integer(component.get("mass_mg"), MAX_MASS_MG, "component mass_mg")
        _features(component.get("features"))
    if (not isinstance(masses, dict) or not masses
            or not set(masses) <= set(components)):
        raise ValueError("masses must reference known components")
    for name, mass in masses.items():
        _integer(mass, MAX_MASS_MG, "mass_mg")
        if mass > components[name]["mass_mg"]:
            raise ValueError("bite exceeds preset component mass")
    total = sum(masses.values())
    if not 0 < total <= MAX_MASS_MG:
        raise ValueError("total bite mass must be positive and bounded")
    active = {name: mass for name, mass in masses.items() if mass}
    keys = set().union(*(components[n]["features"] for n in active))
    if len(keys) > MAX_FEATURES:
        raise ValueError("too many blended features")
    values = {}
    for key in sorted(keys):
        source = [(components[n]["features"].get(key), mass)
                  for n, mass in active.items()]
        values[key] = (None if any(v is None for v, _ in source)
                       else sum(Fraction(v * mass, total) for v, mass in source))
    if model == "blend-interaction-v0" and len(active) > 1:
        r = Fraction(active.get("body", 0), total)
        g = 4 * r * (1 - r)
        sweet, bitter = values.get("sweetness_q"), values.get("bitterness_q")
        values["sweetness_q"] = (None if sweet is None or bitter is None
                                  else sweet * (1 - Fraction(1, 5) * g * bitter / 1000))
        values["bitterness_q"] = (None if sweet is None or bitter is None
                                  else bitter * (1 - Fraction(2, 5) * g * sweet / 1000))
    return {
        "model_version": model, "basis": "derived_from_authored",
        "preset_id": preset.get("id"), "components_mg": dict(masses),
        "features": {key: None if value is None else _round(value)
                     for key, value in values.items()},
        "assumptions": (["authored_component_intensities", "mass_weighted_average"]
                        + (["alpha_0.2_beta_0.4"]
                           if model == "blend-interaction-v0" else [])),
    }


def preference_fit(features, targets, weights):
    """Explicit weighted distance; missing active data makes total fit unknown."""
    _features(features)
    _features(targets)
    if (not targets or any(v is None for v in targets.values())
            or not isinstance(weights, dict) or set(weights) != set(targets)):
        raise ValueError("targets and weights must have the same nonempty keys")
    for weight in weights.values():
        _integer(weight, 1_000_000, "weight")
    total = sum(weights.values())
    if not total:
        raise ValueError("positive total weight is required")
    known = {key: w for key, w in weights.items()
             if w and features.get(key) is not None}
    known_weight = sum(known.values())
    partial = (None if not known_weight else _round(
        Fraction(1000) - Fraction(sum(w * abs(features[k] - targets[k])
                                      for k, w in known.items()), known_weight)))
    return {
        "model_version": "preference-fit-v0", "basis": "derived_from_authored",
        "fit_q": partial if known_weight == total else None,
        "partial_fit_q": partial,
        "coverage_q": _round(Fraction(1000 * known_weight, total)),
    }
