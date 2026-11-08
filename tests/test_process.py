"""Exercise the real stdio process, rather than only a mock domain store."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from uuid import uuid4


ROOT = Path(__file__).resolve().parents[1]


class ProcessTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = str(Path(self.directory.name) / "process.sqlite3")
        self.environment = dict(os.environ, PYTHONPATH=str(ROOT / "src"))

    def test_demo_completes_and_persists_closed_session(self):
        result = subprocess.run([sys.executable, "-m", "flamoris_pudding", "demo", "--db", self.path],
                                cwd=ROOT, env=self.environment, capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        data = json.loads(result.stdout)
        self.assertEqual(data["session"]["session_stage"], "closed")
        self.assertEqual(data["preference"]["fit_q"], 850)
        candidate = data["independent_accounting_fixture"]
        self.assertEqual(candidate["lines"][1]["account"], "期限切れ雑収入")
        self.assertEqual(candidate["status"], "REVIEW_REQUIRED")
        self.assertFalse(candidate["external_posting"])
        self.assertEqual(result.stderr, "")

    def test_mcp_real_process_handshake_full_flow_and_restart_replay(self):
        request_id = str(uuid4())
        create = {"name": "pudding.sessions.create", "arguments": {"request_id": request_id}}
        created = None
        for restart in (False, True):
            with subprocess.Popen([sys.executable, "-m", "flamoris_pudding", "mcp", "--db", self.path],
                                  cwd=ROOT, env=self.environment, stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                  text=True, encoding="utf-8") as process:
                def send(value, reply=True):
                    process.stdin.write(json.dumps(value) + "\n")
                    process.stdin.flush()
                    return json.loads(process.stdout.readline()) if reply else None
                def request(rpc_id, method, params):
                    return send({"jsonrpc": "2.0", "id": rpc_id, "method": method, "params": params})
                initialize = request(1, "initialize", {"protocolVersion": "2025-11-25", "capabilities": {},
                    "clientInfo": {"name": "synthetic-test", "version": "1"}})
                self.assertEqual(initialize["result"]["protocolVersion"], "2025-11-25")
                send({"jsonrpc": "2.0", "method": "notifications/initialized"}, reply=False)
                tools = request(2, "tools/list", {})
                self.assertEqual(len(tools["result"]["tools"]), 9)
                result = request(3, "tools/call", create)["result"]["structuredContent"]
                self.assertTrue(result["ok"])
                if restart:
                    self.assertEqual(result, created)
                else:
                    created = result
                    state = result["session"]
                    for index, (name, extra) in enumerate((
                        ("pudding.lid.peel", {"amount_q": 1000}),
                        ("pudding.lid.dispose", {}),
                        ("pudding.spoon.scoop", {"body_mg": 8000, "sauce_mg": 2000}),
                        ("pudding.spoon.taste", {}),
                        ("pudding.sessions.finish", {}),
                        ("pudding.sessions.cleanup", {}),
                    ), 4):
                        response = request(index, "tools/call", {"name": name, "arguments": {
                            "session_id": state["id"], "expected_revision": state["revision"],
                            "request_id": str(uuid4()), **extra}})
                        self.assertFalse(response["result"]["isError"], response)
                        state = response["result"]["structuredContent"]["session"]
                    self.assertEqual(state["session_stage"], "closed")
                process.stdin.close()
                process.wait(timeout=5)
                self.assertEqual(process.stdout.read(), "")
                self.assertEqual(process.stderr.read(), "")
                self.assertEqual(process.returncode, 0)


if __name__ == "__main__":
    unittest.main()
