"""Experimental accounting candidates and exact, fixture-only tax arithmetic.

These pure helpers never post journals or establish an accounting/tax policy.
All supplied tax rates are unapproved experimental inputs. In particular, using
a rate here does not classify virtual pudding as food or digital content.
Amounts are nonnegative integer minor units; expiry candidates currently use a
single experimental JPY ledger. Callers own durable deduplication and approval.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import re
from typing import Iterable


EXPERIMENTAL_LEDGER_ID = "pudding-experimental-jpy"
_REVIEW_REQUIRED = "REVIEW_REQUIRED"
_TIMESTAMP = re.compile(
    r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?"
    r"(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)"
)


def _text(value: str, name: str) -> str:
    if not isinstance(value, str) or not value or value != value.strip():
        raise ValueError(f"{name} must be a nonempty string without outer whitespace")
    return value


def _minor(value: int, name: str) -> int:
    if type(value) is not int or value < 0:
        raise ValueError(f"{name} must be a nonnegative integer, not bool")
    return value


def _instant(value: str, name: str) -> datetime:
    """Parse an explicit-offset ISO timestamp, retaining microsecond precision."""
    if not isinstance(value, str) or not _TIMESTAMP.fullmatch(value):
        raise ValueError(f"{name} must be an ISO timestamp with seconds and timezone")
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(
            timezone.utc
        )
    except (ValueError, OverflowError) as exc:
        raise ValueError(f"{name} is not a valid timestamp") from exc


def _iso(instant: datetime) -> str:
    return instant.isoformat().replace("+00:00", "Z")


def expiry_candidate(
    asset_id: str,
    purchase_id: str,
    amount_minor: int,
    recognized_minor: int,
    refunded_minor: int,
    rights_expired_at: str,
    processed_at: str,
    obligations_cleared: bool,
    policy_version: str = "accounting-v0",
) -> dict | None:
    """Propose release of an expired right's remaining deferred gross amount.

    ``recognized_minor`` is gross consideration already allocated to fulfilled
    service, not net revenue excluding tax. Physical food disposal alone is not
    sufficient: a trusted caller must establish expiry and cleared obligations.
    This helper validates their supplied facts, not that authority or contract.

    The candidate remains subject to accounting and tax review even when
    obligations are cleared. Its stable identity excludes rule versions and
    processing time, so replay requires reconciliation rather than a new posting.
    All inputs are validated even when no candidate would be returned.
    """
    _text(asset_id, "asset_id")
    _text(purchase_id, "purchase_id")
    _text(policy_version, "policy_version")
    _minor(amount_minor, "amount_minor")
    _minor(recognized_minor, "recognized_minor")
    _minor(refunded_minor, "refunded_minor")
    if recognized_minor + refunded_minor > amount_minor:
        raise ValueError("recognized and refunded amounts exceed purchase amount")
    if type(obligations_cleared) is not bool:
        raise ValueError("obligations_cleared must be bool")
    effective = _instant(rights_expired_at, "rights_expired_at")
    processed = _instant(processed_at, "processed_at")
    if processed < effective:
        raise ValueError("processed_at cannot precede rights_expired_at")

    residual = amount_minor - recognized_minor - refunded_minor
    if not obligations_cleared or residual == 0:
        return None

    identity = json.dumps(
        [EXPERIMENTAL_LEDGER_ID, asset_id, "expiry_release"],
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    return {
        "candidate_id": "expiry-" + hashlib.sha256(identity).hexdigest(),
        "kind": "expiry_release",
        "ledger_id": EXPERIMENTAL_LEDGER_ID,
        "asset_id": asset_id,
        "purchase_id": purchase_id,
        "currency": "JPY",
        "amount_minor": residual,
        "basis": {
            "purchase_minor": amount_minor,
            "recognized_minor": recognized_minor,
            "refunded_minor": refunded_minor,
        },
        "policy_version": policy_version,
        "effective_at": _iso(effective),
        "processed_at": _iso(processed),
        "status": _REVIEW_REQUIRED,
        "tax_status": _REVIEW_REQUIRED,
        "external_posting": False,
        "lines": [
            {"side": "debit", "account": "契約負債", "amount_minor": residual},
            {"side": "credit", "account": "期限切れ雑収入", "amount_minor": residual},
        ],
    }


@dataclass(frozen=True)
class TaxRate:
    """Unapproved experimental rate, effective on [from, until).

    The rate is ``numerator / denominator``: a hypothetical 10% is 1/10,
    rather than the tax-inclusive fraction 1/11. No legal rate is built in.
    Timestamps must have seconds and an explicit offset; storage is UTC ISO.
    """

    rule_id: str
    version: str
    category: str
    jurisdiction: str
    numerator: int
    denominator: int
    effective_from: str
    effective_until: str | None = None

    def __post_init__(self) -> None:
        for name in ("rule_id", "version", "category", "jurisdiction"):
            _text(getattr(self, name), name)
        _minor(self.numerator, "numerator")
        _minor(self.denominator, "denominator")
        if self.denominator == 0:
            raise ValueError("denominator must be positive")
        start = _instant(self.effective_from, "effective_from")
        end = None
        if self.effective_until is not None:
            end = _instant(self.effective_until, "effective_until")
            if end <= start:
                raise ValueError("effective_until must be after effective_from")
        object.__setattr__(self, "effective_from", _iso(start))
        if end is not None:
            object.__setattr__(self, "effective_until", _iso(end))

    def to_dict(self) -> dict:
        """Return a JSON-safe snapshot; never an approved tax classification."""
        return {
            "rule_id": self.rule_id,
            "version": self.version,
            "category": self.category,
            "jurisdiction": self.jurisdiction,
            "numerator": self.numerator,
            "denominator": self.denominator,
            "effective_from": self.effective_from,
            "effective_until": self.effective_until,
            "status": _REVIEW_REQUIRED,
        }


def select_tax_rate(
    rates: Iterable[TaxRate], *, category: str, jurisdiction: str, at: str
) -> TaxRate:
    """Select one fixture rate at a tax point; reject gaps and overlaps.

    Overlaps anywhere in the requested category/jurisdiction are rejected.
    A gap is rejected when no rate covers ``at``. Other categories and regions
    are independent schedules. This selection does not approve the chosen rate.
    """
    _text(category, "category")
    _text(jurisdiction, "jurisdiction")
    instant = _instant(at, "at")
    relevant = []
    for rate in rates:
        if not isinstance(rate, TaxRate):
            raise ValueError("rates must contain only TaxRate values")
        if rate.category == category and rate.jurisdiction == jurisdiction:
            relevant.append(rate)
    relevant.sort(key=lambda rate: _instant(rate.effective_from, "effective_from"))
    for previous, following in zip(relevant, relevant[1:]):
        if previous.effective_until is None or _instant(
            previous.effective_until, "effective_until"
        ) > _instant(following.effective_from, "effective_from"):
            raise ValueError("tax rate effective periods overlap")
    for rate in relevant:
        if _instant(rate.effective_from, "effective_from") <= instant and (
            rate.effective_until is None
            or instant < _instant(rate.effective_until, "effective_until")
        ):
            return rate
    raise ValueError("no tax rate covers the requested tax point")


def calculate_inclusive_grouped_tax(
    amounts_minor: Iterable[int], rate: TaxRate
) -> dict:
    """Sum one invoice/rate group's gross lines, then floor its tax once.

    tax = floor(gross * numerator / (denominator + numerator)). Call separately
    for distinct rate groups; do not sum per-line rounded taxes. This helper
    neither selects a tax point nor allocates group tax back to individual lines.
    An empty group is invalid; zero-value lines are permitted. Results are
    experimental calculations, with no posting or tax-approval authority.
    """
    if not isinstance(rate, TaxRate):
        raise ValueError("rate must be TaxRate")
    gross = 0
    line_count = 0
    for amount in amounts_minor:
        gross += _minor(amount, "amounts_minor item")
        line_count += 1
    if line_count == 0:
        raise ValueError("amounts_minor must contain at least one line")
    tax = gross * rate.numerator // (rate.denominator + rate.numerator)
    return {
        "gross_minor": gross,
        "net_minor": gross - tax,
        "tax_minor": tax,
        "line_count": line_count,
        "rounding": "floor",
        "tax_rate": rate.to_dict(),
        "status": _REVIEW_REQUIRED,
        "tax_status": _REVIEW_REQUIRED,
        "external_posting": False,
    }
