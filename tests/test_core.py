import copy
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from uuid import uuid4

from flamoris_pudding.core import Store
from flamoris_pudding.lifecycle import parse_time


class CoreTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = str(Path(self.directory.name) / "research.db")
        self.now = datetime(2026, 11, 8, 1, tzinfo=timezone.utc)
        self.store = self.new_store()
        self.addCleanup(lambda: self.store.close())
        self.state = None

    def new_store(self, **kwargs):
        return Store(self.path, clock=lambda: self.now, **kwargs)

    def create(self):
        r = self.store.call("pudding.sessions.create", {"request_id": str(uuid4())})
        self.assertTrue(r["ok"], r)
        self.state = r["session"]
        return r

    def command(self, name, **args):
        r = self.store.call(name, {"session_id": self.state["id"], "expected_revision": self.state["revision"],
                                   "request_id": str(uuid4()), **args})
        if r["ok"]:
            self.state = r["session"]
        return r

    def opened(self):
        self.create()
        self.assertTrue(self.command("pudding.lid.peel", amount_q=1000)["ok"])
        self.assertTrue(self.command("pudding.lid.dispose")["ok"])

    def scoop(self, body=8000, sauce=2000):
        return self.command("pudding.spoon.scoop", body_mg=body, sauce_mg=sauce)

    def snapshot(self):
        return self.store.call("pudding.sessions.get", {"session_id": self.state["id"]})

    def test_full_flow_mass_accounting_cleanup_and_restart(self):
        self.create()
        self.command("pudding.lid.peel", amount_q=250)
        self.assertEqual(self.state["lid"], "attached")
        self.command("pudding.lid.peel", amount_q=750)
        self.command("pudding.lid.dispose")
        self.scoop()
        r = self.command("pudding.spoon.taste")
        self.assertEqual(r["session"]["last_taste"]["features"]["sweetness_q"], 666)
        self.assertEqual(r["session"]["last_taste"]["features"]["bitterness_q"], 132)
        self.command("pudding.sessions.finish")
        self.assertEqual(self.state["session_stage"], "cleanup_required")
        self.command("pudding.sessions.cleanup")
        self.assertEqual(self.state["revision"], 7)
        self.assertEqual(self.state["session_stage"], "closed")
        self.assertEqual(sum(c["consumed_mg"] for c in self.state["components"].values()), 10000)
        self.assertEqual(sum(c["discarded_mg"] for c in self.state["components"].values()), 90000)
        self.store.close()
        self.store = self.new_store()
        self.assertEqual(self.snapshot()["session"], self.state)

    def test_last_bite_requires_cleanup_and_blocks_new_serving(self):
        self.opened()
        self.scoop(90000, 10000)
        self.command("pudding.spoon.taste")
        self.assertEqual(self.state["food_stage"], "depleted")
        for _ in range(3):
            r = self.store.call("pudding.sessions.create", {"request_id": str(uuid4())})
            self.assertEqual(r["error"], "UNRESOLVED_SESSION")
            self.assertEqual(r["policy"]["violations"], 1)
        self.command("pudding.sessions.cleanup")
        self.create()

    def test_lid_violation_counted_once_and_invalid_states_do_not_strike(self):
        self.create()
        self.assertEqual(self.scoop()["error"], "LID_NOT_OPEN")
        self.assertEqual(self.snapshot()["policy"]["violations"], 0)
        self.command("pudding.lid.peel", amount_q=1000)
        before = copy.deepcopy(self.state)
        for _ in range(3):
            r = self.scoop()
            self.assertEqual(r["error"], "LID_NOT_DISPOSED")
            self.assertEqual(r["food_effect"], "none")
            self.assertEqual(r["policy"]["violations"], 1)
        self.assertEqual(self.snapshot()["session"], before)
        self.command("pudding.lid.dispose")
        self.assertEqual(self.command("pudding.spoon.taste")["error"], "EMPTY_SPOON")
        self.assertEqual(self.snapshot()["policy"]["violations"], 1)

    def test_replay_survives_restart_and_payload_reuse_is_rejected(self):
        request = {"request_id": str(uuid4())}
        first = self.store.call("pudding.sessions.create", request)
        self.state = first["session"]
        self.command("pudding.lid.peel", amount_q=1000)
        self.store.close()
        self.store = self.new_store()
        self.assertEqual(self.store.call("pudding.sessions.create", request), first)
        changed = {**request, "preset_id": "custard-sweeter-v0"}
        self.assertEqual(self.store.call("pudding.sessions.create", changed)["error"], "REQUEST_ID_REUSED")

    def test_stale_revision_and_spoofed_identity_do_not_mutate(self):
        self.opened()
        old = copy.deepcopy(self.state)
        r = self.store.call("pudding.spoon.scoop", {
            "request_id": str(uuid4()), "session_id": old["id"], "expected_revision": 0,
            "body_mg": 10, "sauce_mg": 0})
        self.assertEqual(r["error"], "REVISION_CONFLICT")
        for extra in ({"agent_id": "victim"}, {"now": "2100-01-01"}, {"lab": True}):
            r = self.store.call("pudding.spoon.taste", {"request_id": str(uuid4()),
                "session_id": old["id"], "expected_revision": old["revision"], **extra})
            self.assertEqual(r["error"], "INVALID_ARGUMENT")
        self.assertEqual(self.snapshot()["session"], old)
        self.assertEqual(self.snapshot()["policy"]["violations"], 0)

    def test_another_configured_principal_cannot_read_or_change_serving(self):
        self.create()
        other = self.new_store(agent_id="other-synthetic-agent")
        self.addCleanup(other.close)
        self.assertEqual(other.call("pudding.sessions.get", {"session_id": self.state["id"]})["error"], "NOT_FOUND")
        r = other.call("pudding.lid.peel", {"session_id": self.state["id"],
            "request_id": str(uuid4()), "expected_revision": 0, "amount_q": 1000})
        self.assertEqual(r["error"], "NOT_FOUND")
        self.assertEqual(self.snapshot()["session"]["revision"], 0)

    def test_database_failure_rolls_back_food_event_and_replay_fence(self):
        self.opened()
        args = {"request_id": str(uuid4()), "session_id": self.state["id"],
                "expected_revision": self.state["revision"], "body_mg": 100, "sauce_mg": 0}
        before = self.snapshot()
        self.store.db.execute("CREATE TRIGGER fail_event BEFORE INSERT ON events BEGIN SELECT RAISE(ABORT,'fixture'); END")
        with self.assertRaises(sqlite3.IntegrityError):
            self.store.call("pudding.spoon.scoop", args)
        self.assertEqual(self.snapshot(), before)
        self.assertEqual(self.store.db.execute("SELECT count(*) FROM requests WHERE request_id=?", (args["request_id"],)).fetchone()[0], 0)
        self.store.db.execute("DROP TRIGGER fail_event")
        self.assertTrue(self.store.call("pudding.spoon.scoop", args)["ok"])

    def test_concurrent_same_revision_has_one_winner(self):
        self.opened()
        args = {"session_id": self.state["id"], "expected_revision": self.state["revision"],
                "body_mg": 1000, "sauce_mg": 0}
        def attempt(_):
            with_store = self.new_store()
            try:
                return with_store.call("pudding.spoon.scoop", {**args, "request_id": str(uuid4())})
            finally:
                with_store.close()
        with ThreadPoolExecutor(max_workers=2) as executor:
            result = list(executor.map(attempt, range(2)))
        self.assertEqual(sum(r["ok"] for r in result), 1)
        self.assertEqual([r["error"] for r in result if not r["ok"]], ["REVISION_CONFLICT"])
        self.assertEqual(self.snapshot()["session"]["components"]["body"]["spoon_mg"], 1000)

    def test_expired_bites_lock_before_third_consumption_and_allow_cleanup(self):
        self.store.close()
        self.store = self.new_store(fixture_purchased_at=self.now)
        self.opened()
        self.now += timedelta(days=15)
        for count in (1, 2):
            self.scoop(1000, 0)
            r = self.command("pudding.spoon.taste")
            self.assertTrue(r["ok"])
            self.assertEqual(r["policy"]["violations"], count)
        self.scoop(1000, 0)
        r = self.command("pudding.spoon.taste")
        self.assertEqual(r["error"], "LOCKED")
        self.assertEqual(r["policy"]["violations"], 3)
        self.assertEqual(self.snapshot()["session"]["bites"], 2)
        self.assertEqual(self.command("pudding.spoon.taste")["policy"]["violations"], 3)
        self.assertTrue(self.command("pudding.sessions.finish")["ok"])
        self.assertTrue(self.command("pudding.sessions.cleanup")["ok"])

    def test_hard_expiry_read_guard_sweep_replay_and_spoon_conservation(self):
        self.store.close()
        self.store = self.new_store(fixture_purchased_at=self.now)
        self.opened()
        self.scoop()
        self.now = parse_time(self.state["deadlines"]["dispose_after_at"])
        before = self.store.db.execute("SELECT total_changes()").fetchone()[0]
        self.assertEqual(self.snapshot()["session"]["effective_age"]["status"], "disposal_due")
        self.assertEqual(self.store.db.execute("SELECT total_changes()").fetchone()[0], before)
        self.assertEqual(self.command("pudding.spoon.taste")["error"], "DISPOSAL_DUE")
        self.now += timedelta(days=1)
        result = self.store.sweep_expired(limit=1)
        self.assertEqual(len(result), 1)
        self.assertEqual(self.store.sweep_expired(limit=1), [])
        state = result[0]["session"]
        self.assertEqual(state["disposal"]["effective_at"], state["deadlines"]["dispose_after_at"])
        self.assertNotEqual(state["disposal"]["processed_at"], state["disposal"]["effective_at"])
        self.assertEqual(sum(c["discarded_mg"] for c in state["components"].values()), 100000)
        self.assertEqual(state["session_stage"], "cleanup_required")
        self.assertEqual(self.snapshot()["policy"]["violations"], 0)
        self.assertEqual(self.store.db.execute("SELECT count(*) FROM events WHERE operation='fixture.monthly_disposal'").fetchone()[0], 1)

    def test_invalid_mass_bool_unknown_preset_and_rule_configuration(self):
        self.opened()
        for value in (-1, True, 0.5, 100001):
            self.assertEqual(self.scoop(value, 0)["error"], "INVALID_ARGUMENT")
        self.assertEqual(self.scoop(90001, 0)["error"], "INSUFFICIENT_MASS")
        self.assertEqual(self.store.call("pudding.sessions.create", {"request_id": str(uuid4()), "preset_id": "unknown"})["error"], "INVALID_ARGUMENT")
        with self.assertRaises(ValueError):
            self.new_store(lockout_threshold=4)
        self.assertEqual(self.snapshot()["policy"]["violations"], 0)


if __name__ == "__main__":
    unittest.main()
