"""Purchase deadlines and authored virtual aging; no wall-clock or food-safety I/O.

Production code supplies its trusted clock to these pure functions. All durations
are computed as exact integer microseconds; quantization happens only at output.
"""

from __future__ import annotations

from calendar import monthrange
from datetime import datetime, timedelta, timezone
from fractions import Fraction
from zoneinfo import ZoneInfo

RULES_VERSION = "lifecycle-v0"
AGING_VERSION = "aging-authored-v0"
PURCHASE_TIMEZONE = "Asia/Tokyo"


def _aware(value: datetime) -> datetime:
    if not isinstance(value, datetime) or value.tzinfo is None:
        raise ValueError("an aware datetime is required")
    try:
        if value.utcoffset() is None:
            raise ValueError("an aware datetime is required")
        return value.astimezone(timezone.utc)
    except (OverflowError, TypeError) as exc:
        raise ValueError("datetime is outside the supported range") from exc


def utc_text(value: datetime) -> str:
    """Canonical UTC ISO-8601 with fixed microsecond precision, ending in Z."""
    return _aware(value).isoformat(timespec="microseconds").replace("+00:00", "Z")


def parse_time(value: str) -> datetime:
    """Read an ISO-8601 instant; timezone-free dates/times are never inferred."""
    if not isinstance(value, str) or not value or len(value) > 64:
        raise ValueError("an ISO-8601 timestamp with timezone is required")
    try:
        return _aware(datetime.fromisoformat(value.replace("Z", "+00:00")))
    except (ValueError, OverflowError, TypeError) as exc:
        raise ValueError("an ISO-8601 timestamp with timezone is required") from exc


def deadlines(purchased_at: datetime) -> dict:
    """14 absolute days and one calendar month in JST, clamping month ends."""
    purchase = _aware(purchased_at)
    try:
        local = purchase.astimezone(ZoneInfo(PURCHASE_TIMEZONE))
        year = local.year + (local.month == 12)
        month = 1 if local.month == 12 else local.month + 1
        day = min(local.day, monthrange(year, month)[1])
        hard = local.replace(year=year, month=month, day=day)
        best = purchase + timedelta(days=14)
        return {
            "purchased_at": utc_text(purchase),
            "best_before_at": utc_text(best),
            "dispose_after_at": utc_text(hard),
            "timezone": PURCHASE_TIMEZONE,
            "rules_version": RULES_VERSION,
        }
    except (ValueError, OverflowError) as exc:
        raise ValueError("purchase deadlines are outside the supported range") from exc


def _microseconds(value: timedelta) -> int:
    return ((value.days * 86400 + value.seconds) * 1_000_000
            + value.microseconds)


def _q(value: Fraction) -> int:
    # Values are nonnegative. This is exact ROUND_HALF_UP to q1000.
    scaled = value * 1000
    return (2 * scaled.numerator + scaled.denominator) // (2 * scaled.denominator)


def age(deadline_record: dict, now: datetime) -> dict:
    """Return current virtual quality without changing any persisted state.

    ``rotten_egg_like_q`` is an authored narrative parameter, not a measurement
    or prediction about actual food. An expired status can have quality_q 1000
    immediately after its boundary because the displayed q1000 is quantized.
    """
    if not isinstance(deadline_record, dict):
        raise ValueError("a deadline record is required")
    try:
        best = parse_time(deadline_record["best_before_at"])
        hard = parse_time(deadline_record["dispose_after_at"])
    except KeyError as exc:
        raise ValueError("both deadline timestamps are required") from exc
    if hard <= best:
        raise ValueError("mandatory disposal must be later than best-before")
    instant = _aware(now)
    if instant < best:
        status = "fresh"
        u = Fraction(0)
    elif instant >= hard:
        status = "disposal_due"
        u = Fraction(1)
    else:
        status = "expired_pending_disposal"
        u = Fraction(_microseconds(instant - best), _microseconds(hard - best))
    rotten = max(Fraction(0), min(Fraction(1), (u - Fraction(3, 4)) * 4))
    return {
        "status": status,
        "quality_q": _q(1 - u),
        "rotten_egg_like_q": _q(rotten),
        "model_version": AGING_VERSION,
        "basis": "authored",
    }
