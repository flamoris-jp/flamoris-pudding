"""Local synthetic-agent sessions; the SQLite transaction is the state authority.

The configured principal is trusted process configuration, NOT remote authentication.
Do not put this research harness behind a shared/public HTTP or MCP gateway.
"""
from __future__ import annotations

from contextlib import contextmanager
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import json
import sqlite3
from typing import Callable
from uuid import UUID, uuid4

from .lifecycle import age, deadlines, utc_text
from .sensory import evaluate_bite, get_preset, presets

UTC = timezone.utc
RULES_VERSION = "research-rules-v0"
COMMON = {"session_id", "request_id", "expected_revision"}
FIELDS = {
    "pudding.presets.list": set(),
    "pudding.sessions.get": {"session_id"},
    "pudding.sessions.create": {"request_id", "preset_id"},
    "pudding.lid.peel": COMMON | {"amount_q"},
    "pudding.lid.dispose": COMMON,
    "pudding.spoon.scoop": COMMON | {"body_mg", "sauce_mg"},
    "pudding.spoon.taste": COMMON,
    "pudding.sessions.finish": COMMON,
    "pudding.sessions.cleanup": COMMON,
}


def encode(value: object) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
                      allow_nan=False)


def integer(value: object, name: str, low: int, high: int) -> int:
    if type(value) is not int or not low <= value <= high:
        raise ValueError(f"{name} must be an integer in [{low}, {high}]")
    return value


def identifier(value: object, name: str) -> str:
    if not isinstance(value, str) or len(value) != 36:
        raise ValueError(f"{name} must be a canonical UUID")
    if str(UUID(value)) != value:
        raise ValueError(f"{name} must be a canonical UUID")
    return value


class Denied(Exception):
    def __init__(self, code: str):
        self.code = code


class Store:
    """One trusted synthetic principal per process; file access is privileged.

    clock / fixture_purchased_at are Python-only test injection points. Neither is
    exposed in tool arguments. Fixtures are never actual paid inventory.
    """

    def __init__(self, path: str, *, agent_id: str = "synthetic-agent",
                 clock: Callable[[], datetime] | None = None,
                 lockout_threshold: int = 3,
                 fixture_purchased_at: datetime | None = None):
        if not isinstance(agent_id, str) or not 1 <= len(agent_id) <= 80:
            raise ValueError("invalid configured synthetic principal")
        integer(lockout_threshold, "lockout_threshold", 1, 100)
        self.agent_id = agent_id
        self.clock = clock or (lambda: datetime.now(UTC))
        self.fixture_deadlines = (deadlines(fixture_purchased_at)
                                  if fixture_purchased_at is not None else None)
        self.db = sqlite3.connect(path, isolation_level=None, timeout=5)
        try:
            self._initialize(lockout_threshold)
        except BaseException:
            self.db.close()
            raise
        self.threshold = lockout_threshold

    def _initialize(self, lockout_threshold: int) -> None:
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS config(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS agents(
                id TEXT PRIMARY KEY, violations INTEGER NOT NULL DEFAULT 0,
                enforcement_revision INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS sessions(
                id TEXT PRIMARY KEY, agent_id TEXT NOT NULL REFERENCES agents(id),
                revision INTEGER NOT NULL, state TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS sessions_owner ON sessions(agent_id);
            CREATE INDEX IF NOT EXISTS sessions_stage ON sessions(
                agent_id, json_extract(state, '$.session_stage'));
            CREATE TABLE IF NOT EXISTS requests(
                agent_id TEXT NOT NULL, request_id TEXT NOT NULL, payload TEXT NOT NULL,
                response TEXT NOT NULL, PRIMARY KEY(agent_id, request_id));
            CREATE TABLE IF NOT EXISTS violations(
                agent_id TEXT NOT NULL, obligation TEXT NOT NULL, rule TEXT NOT NULL,
                occurred_at TEXT NOT NULL, PRIMARY KEY(agent_id, obligation));
            CREATE TABLE IF NOT EXISTS events(
                id INTEGER PRIMARY KEY, agent_id TEXT NOT NULL, session_id TEXT,
                request_id TEXT NOT NULL, operation TEXT NOT NULL,
                occurred_at TEXT NOT NULL, response TEXT NOT NULL,
                UNIQUE(agent_id, request_id));
        """)
        with self._transaction():
            self.db.execute("INSERT OR IGNORE INTO config VALUES('rules', ?)",
                            (encode({"version": RULES_VERSION, "threshold": lockout_threshold}),))
            config = json.loads(self.db.execute("SELECT value FROM config WHERE key='rules'").fetchone()[0])
            if config != {"version": RULES_VERSION, "threshold": lockout_threshold}:
                raise ValueError("database rules differ from process configuration")
            self.db.execute("INSERT OR IGNORE INTO agents(id) VALUES(?)", (self.agent_id,))

    def close(self) -> None:
        self.db.close()

    @contextmanager
    def _transaction(self):
        self.db.execute("BEGIN IMMEDIATE")
        try:
            yield
            self.db.execute("COMMIT")
        except BaseException:
            self.db.execute("ROLLBACK")
            raise

    def _policy(self) -> dict:
        row = self.db.execute("SELECT * FROM agents WHERE id=?", (self.agent_id,)).fetchone()
        count = row["violations"]
        return {"agent_id": self.agent_id, "violations": count,
                "status": "locked" if count >= self.threshold else ("blacklisted" if count else "clear"),
                "lockout_threshold": self.threshold, "rules_version": RULES_VERSION,
                "enforcement_revision": row["enforcement_revision"]}

    def _violate(self, obligation: str, rule: str, now: datetime) -> None:
        inserted = self.db.execute("INSERT OR IGNORE INTO violations VALUES(?,?,?,?)",
                                   (self.agent_id, obligation, rule, utc_text(now))).rowcount
        if inserted:
            self.db.execute("UPDATE agents SET violations=violations+1, "
                            "enforcement_revision=enforcement_revision+1 WHERE id=?", (self.agent_id,))

    def _load(self, session_id: str) -> dict:
        # One query deliberately hides existence of other principals' objects.
        row = self.db.execute("SELECT state FROM sessions WHERE id=? AND agent_id=?",
                              (session_id, self.agent_id)).fetchone()
        if row is None:
            raise Denied("NOT_FOUND")
        return json.loads(row[0])

    def _view(self, state: dict, now: datetime) -> dict:
        result = deepcopy(state)
        result["effective_age"] = (age(state["deadlines"], now) if state["deadlines"]
                                   else {"status": "not_applicable"})
        return result

    def _validate(self, name: str, args: dict) -> dict:
        if name not in FIELDS:
            raise Denied("UNKNOWN_TOOL")
        if type(args) is not dict or set(args) - FIELDS[name]:
            raise ValueError("unknown argument or invalid argument object")
        result = dict(args)
        for key in ("request_id", "session_id"):
            if key in FIELDS[name]:
                result[key] = identifier(args.get(key), key)
        if "expected_revision" in FIELDS[name]:
            integer(args.get("expected_revision"), "expected_revision", 0, 2**31-1)
        if name == "pudding.sessions.create":
            result.setdefault("preset_id", "custard-v0")
            get_preset(result["preset_id"])
        if name == "pudding.lid.peel":
            integer(args.get("amount_q"), "amount_q", 1, 1000)
        if name == "pudding.spoon.scoop":
            for key in ("body_mg", "sauce_mg"):
                integer(result.get(key), key, 0, 100000)
            if result["body_mg"] + result["sauce_mg"] == 0:
                raise ValueError("spoon mass must be positive")
        return result

    def call(self, name: str, arguments: dict) -> dict:
        """Validated local tool boundary; no identity or clock supplied by caller."""
        try:
            args = self._validate(name, arguments)
        except (ValueError, TypeError):
            return {"ok": False, "error": "INVALID_ARGUMENT", "food_effect": "none"}
        except Denied as error:
            return {"ok": False, "error": error.code, "food_effect": "none"}
        if name == "pudding.presets.list":
            return {"ok": True, "presets": presets()}
        if name == "pudding.sessions.get":
            # A consistent read snapshot; no clock / policy / expiry mutation.
            self.db.execute("BEGIN")
            try:
                now = self.clock()
                result = {"ok": True, "session": self._view(self._load(args["session_id"]), now),
                          "policy": self._policy()}
            except Denied as error:
                result = {"ok": False, "error": error.code, "food_effect": "none"}
            finally:
                self.db.execute("ROLLBACK")
            return result
        return self._write(name, args)

    def _write(self, name: str, args: dict) -> dict:
        payload = encode({"operation": name, "arguments": args})
        with self._transaction():
            replay = self.db.execute("SELECT payload,response FROM requests WHERE agent_id=? AND request_id=?",
                                     (self.agent_id, args["request_id"])).fetchone()
            if replay:
                if replay["payload"] != payload:
                    return {"ok": False, "error": "REQUEST_ID_REUSED", "food_effect": "none"}
                return json.loads(replay["response"])
            now = self.clock()
            # Validate injected clocks even for free fixtures.
            utc_text(now)
            before_policy = self._policy()["enforcement_revision"]
            state = None
            try:
                if name == "pudding.sessions.create":
                    state = self._create(args, now)
                else:
                    state = self._load(args["session_id"])
                    if state["revision"] != args["expected_revision"]:
                        raise Denied("REVISION_CONFLICT")
                    self._operate(name, args, state, now)
                    state["revision"] += 1
                self._invariant(state)
                self.db.execute("INSERT INTO sessions VALUES(?,?,?,?) "
                                "ON CONFLICT(id) DO UPDATE SET revision=excluded.revision,state=excluded.state",
                                (state["id"], self.agent_id, state["revision"], encode(state)))
                result = {"ok": True, "session": self._view(state, now), "food_effect": "applied"}
            except Denied as error:
                result = {"ok": False, "error": error.code, "food_effect": "none"}
            result["policy"] = self._policy()
            result["policy_effect"] = ("recorded" if result["policy"]["enforcement_revision"] != before_policy else "none")
            response = encode(result)
            self.db.execute("INSERT INTO requests VALUES(?,?,?,?)",
                            (self.agent_id, args["request_id"], payload, response))
            self.db.execute("INSERT INTO events(agent_id,session_id,request_id,operation,occurred_at,response) "
                            "VALUES(?,?,?,?,?,?)", (self.agent_id, state["id"] if state else args.get("session_id"),
                            args["request_id"], name, utc_text(now), response))
            return result

    def _create(self, args: dict, now: datetime) -> dict:
        if self._policy()["status"] == "locked":
            raise Denied("LOCKED")
        for row in self.db.execute("SELECT state FROM sessions WHERE agent_id=? "
                                   "AND json_extract(state,'$.session_stage') != 'closed' LIMIT 1", (self.agent_id,)):
            existing = json.loads(row[0])
            if existing["session_stage"] != "closed":
                if existing["session_stage"] == "cleanup_required":
                    self._violate("cleanup:" + existing["id"], "CLEANUP_NOT_DONE", now)
                raise Denied("UNRESOLVED_SESSION")
        preset = get_preset(args["preset_id"])
        components = {key: {"initial_mg": part["mass_mg"], "cup_mg": part["mass_mg"],
                            "spoon_mg": 0, "consumed_mg": 0, "discarded_mg": 0}
                      for key, part in preset["components"].items()}
        return {"id": str(uuid4()), "preset_id": args["preset_id"], "preset": preset,
                "revision": 0, "peel_q": 0, "lid": "attached", "food_stage": "sealed",
                "session_stage": "active", "cleanup": "not_due", "cleanup_due_at": None,
                "components": components, "bites": 0, "last_taste": None,
                "rules_version": RULES_VERSION, "deadlines": deepcopy(self.fixture_deadlines),
                "fixture_only": True}

    def _remaining(self, state: dict) -> int:
        return sum(c["cup_mg"] + c["spoon_mg"] for c in state["components"].values())

    def _cleanup_required(self, state: dict, now: datetime) -> None:
        state["session_stage"] = "cleanup_required"
        state["cleanup"] = "required"
        if state["cleanup_due_at"] is None:
            state["cleanup_due_at"] = utc_text(now + timedelta(minutes=10))

    def _operate(self, name: str, args: dict, state: dict, now: datetime) -> None:
        if state["session_stage"] == "closed":
            raise Denied("SESSION_CLOSED")
        eating = name in {"pudding.lid.peel", "pudding.spoon.scoop", "pudding.spoon.taste"}
        if eating:
            if self._policy()["status"] == "locked":
                raise Denied("LOCKED")
            if state["deadlines"] and age(state["deadlines"], now)["status"] == "disposal_due":
                raise Denied("DISPOSAL_DUE")
            if state["session_stage"] != "active":
                raise Denied("FOOD_FINISHED")
        if name == "pudding.lid.peel":
            if state["peel_q"] == 1000 or state["peel_q"] + args["amount_q"] > 1000:
                raise Denied("INVALID_PEEL")
            state["peel_q"] += args["amount_q"]
            if state["peel_q"] == 1000:
                state.update(lid="detached", food_stage="open")
            else:
                state["food_stage"] = "peeling"
        elif name == "pudding.lid.dispose":
            if state["lid"] != "detached":
                raise Denied("NO_DETACHED_LID")
            state["lid"] = "in_bin"
        elif name in {"pudding.spoon.scoop", "pudding.spoon.taste"}:
            if state["peel_q"] != 1000:
                raise Denied("LID_NOT_OPEN")
            # Only an otherwise executable food command can establish a rule
            # violation. An empty spoon or impossible mass is a state error.
            if name == "pudding.spoon.scoop":
                if any(c["spoon_mg"] for c in state["components"].values()):
                    raise Denied("SPOON_NOT_EMPTY")
                masses = {"body": args["body_mg"], "sauce": args["sauce_mg"]}
                for key, amount in masses.items():
                    if amount > state["components"].get(key, {}).get("cup_mg", 0):
                        raise Denied("INSUFFICIENT_MASS")
            else:
                masses = {key: c["spoon_mg"] for key, c in state["components"].items()}
                if not sum(masses.values()):
                    raise Denied("EMPTY_SPOON")
            if state["lid"] == "detached":
                self._violate("lid:" + state["id"], "LID_NOT_DISPOSED", now)
                raise Denied("LID_NOT_DISPOSED")
            if name == "pudding.spoon.scoop":
                for key, c in state["components"].items():
                    amount = masses.get(key, 0)
                    c["cup_mg"] -= amount
                    c["spoon_mg"] += amount
                state["food_stage"] = "spoon_loaded"
            else:
                quality = age(state["deadlines"], now) if state["deadlines"] else None
                if quality and quality["status"] == "expired_pending_disposal":
                    self._violate("expired:" + args["request_id"], "EXPIRED_USE", now)
                    if self._policy()["status"] == "locked":
                        raise Denied("LOCKED")
                taste = evaluate_bite(state["preset"], masses)
                taste.update(evaluated_at=utc_text(now), aging=quality)
                for c in state["components"].values():
                    c["consumed_mg"] += c["spoon_mg"]
                    c["spoon_mg"] = 0
                state["bites"] += 1
                state["last_taste"] = taste
                if self._remaining(state) == 0:
                    state["food_stage"] = "depleted"
                    self._cleanup_required(state, now)
                else:
                    state["food_stage"] = "open"
        elif name == "pudding.sessions.finish":
            if state["session_stage"] != "active":
                raise Denied("FOOD_FINISHED")
            # Research-only fixtures may discard fresh leftovers. Premium assets
            # will require a separate human authorization path, not this operation.
            remaining = self._remaining(state)
            for c in state["components"].values():
                c["discarded_mg"] += c["cup_mg"] + c["spoon_mg"]
                c["cup_mg"] = c["spoon_mg"] = 0
            if remaining:
                state["food_stage"] = "disposed"
            self._cleanup_required(state, now)
        elif name == "pudding.sessions.cleanup":
            if state["session_stage"] != "cleanup_required" or self._remaining(state):
                raise Denied("FOOD_REMAINS")
            if state["lid"] == "detached":
                raise Denied("LID_NOT_DISPOSED")
            state.update(lid="in_bin", cleanup="done", session_stage="closed")

    @staticmethod
    def _invariant(state: dict) -> None:
        for c in state["components"].values():
            if (not all(type(v) is int and v >= 0 for v in c.values()) or
                    c["initial_mg"] != sum(c[k] for k in ("cup_mg", "spoon_mg", "consumed_mg", "discarded_mg"))):
                raise RuntimeError("mass conservation failed")

    def sweep_expired(self, *, limit: int = 100) -> list[dict]:
        """Operator-invoked fixture scan, bounded and restart-safe; no scheduler.

        This disposes synthetic food only. It does not release financial liability
        or certify that an Agent cleaned its dishes.
        """
        integer(limit, "limit", 1, 1000)
        now = self.clock()
        results = []
        with self._transaction():
            rows = self.db.execute("SELECT id,state FROM sessions WHERE agent_id=? "
                                   "AND json_extract(state,'$.deadlines.dispose_after_at') <= ? "
                                   "AND json_extract(state,'$.session_stage') = 'active' "
                                   "ORDER BY id LIMIT ?", (self.agent_id, utc_text(now), limit)).fetchall()
            for row in rows:
                state = json.loads(row["state"])
                if not state["deadlines"] or not self._remaining(state):
                    continue
                if age(state["deadlines"], now)["status"] != "disposal_due":
                    continue
                for c in state["components"].values():
                    c["discarded_mg"] += c["cup_mg"] + c["spoon_mg"]
                    c["cup_mg"] = c["spoon_mg"] = 0
                state["food_stage"] = "disposed"
                self._cleanup_required(state, now)
                state["revision"] += 1
                state["disposal"] = {"reason": "monthly_expiry", "processed_at": utc_text(now),
                                     "effective_at": state["deadlines"]["dispose_after_at"]}
                self._invariant(state)
                self.db.execute("UPDATE sessions SET revision=?,state=? WHERE id=?",
                                (state["revision"], encode(state), state["id"]))
                request_id = "expiry:" + state["id"]
                result = {"ok": True, "session": self._view(state, now), "food_effect": "applied"}
                self.db.execute("INSERT INTO events(agent_id,session_id,request_id,operation,occurred_at,response) "
                                "VALUES(?,?,?,?,?,?)", (self.agent_id, state["id"], request_id,
                                "fixture.monthly_disposal", utc_text(now), encode(result)))
                results.append(result)
        return results
