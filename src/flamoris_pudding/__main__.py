"""Local research CLI; no HTTP listener or production deployment."""
import argparse
import json
import sqlite3
import sys
from uuid import uuid4

from .accounting import expiry_candidate
from .core import Store
from .mcp import run
from .sensory import preference_fit


def demo(store: Store) -> dict:
    result = store.call("pudding.sessions.create", {"request_id": str(uuid4())})
    if not result["ok"]:
        raise RuntimeError("demo requires a synthetic principal with no unresolved serving")
    state = result["session"]
    steps = []
    for operation, args in (
        ("pudding.lid.peel", {"amount_q": 250}),
        ("pudding.lid.peel", {"amount_q": 750}),
        ("pudding.lid.dispose", {}),
        ("pudding.spoon.scoop", {"body_mg": 8000, "sauce_mg": 2000}),
        ("pudding.spoon.taste", {}),
        ("pudding.sessions.finish", {}),
        ("pudding.sessions.cleanup", {}),
    ):
        result = store.call(operation, {"request_id": str(uuid4()), "session_id": state["id"],
                                        "expected_revision": state["revision"], **args})
        if not result["ok"]:
            raise RuntimeError("demo operation failed")
        state = result["session"]
        steps.append({"operation": operation, "revision": state["revision"],
                      "food_stage": state["food_stage"], "session_stage": state["session_stage"]})
    fit = preference_fit(state["last_taste"]["features"],
                         {"sweetness_q": 816, "bitterness_q": 282},
                         {"sweetness_q": 1, "bitterness_q": 1})
    # Independent accounting fixture: this is NOT derived from the food disposal.
    candidate = expiry_candidate("synthetic-expired-asset", "synthetic-purchase", 1100, 0, 0,
                                  "2026-12-08T01:00:00Z", "2026-12-09T01:00:00Z", True)
    return {"fixture_only": True, "steps": steps, "session": state, "preference": fit,
            "independent_accounting_fixture": candidate,
            "subjective_experience_claim": False, "external_posting": False}


def main() -> int:
    parser = argparse.ArgumentParser(description="Virtual pudding synthetic research harness")
    parser.add_argument("command", choices=("demo", "mcp"))
    parser.add_argument("--db", default="pudding-research.sqlite3", help="local SQLite research database")
    parser.add_argument("--synthetic-agent", default="synthetic-agent",
                        help="trusted local fixture principal; not an authentication credential")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", newline="\n")
    store = None
    try:
        store = Store(args.db, agent_id=args.synthetic_agent)
        if args.command == "mcp":
            run(store, sys.stdin.buffer, sys.stdout)
        else:
            print(json.dumps(demo(store), ensure_ascii=False, indent=2, allow_nan=False))
        return 0
    except (OSError, ValueError, RuntimeError, sqlite3.Error):
        print("Research harness failed; check database access and unresolved serving state.", file=sys.stderr)
        return 1
    finally:
        if store is not None:
            store.close()


if __name__ == "__main__":
    raise SystemExit(main())
