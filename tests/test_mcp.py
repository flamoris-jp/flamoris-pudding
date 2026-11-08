import io
import json
import unittest
from uuid import uuid4

from flamoris_pudding.mcp import MAX_LINE_BYTES, PROTOCOL_VERSION, run


class FakeStore:
    def __init__(self):
        self.calls = []

    def call(self, name, arguments):
        self.calls.append((name, arguments))
        return {"ok": True, "observation": "仮想プリン"}


def request(request_id, method, params=None):
    result = {"jsonrpc": "2.0", "id": request_id, "method": method}
    if params is not None:
        result["params"] = params
    return result


def handshake():
    return [request("init", "initialize", {
        "protocolVersion": PROTOCOL_VERSION, "capabilities": {},
        "clientInfo": {"name": "unittest", "version": "1"}}),
        {"jsonrpc": "2.0", "method": "notifications/initialized"}]


def transcript(messages, store=None):
    store = store or FakeStore()
    data = b"".join(json.dumps(m).encode() + b"\n" for m in messages)
    output = io.StringIO()
    run(store, io.BytesIO(data), output)
    return [json.loads(line) for line in output.getvalue().splitlines()], store


class McpTests(unittest.TestCase):
    def test_handshake_list_call_and_utf8(self):
        rid = str(uuid4())
        output, store = transcript(handshake() + [
            request(2, "tools/list"),
            request(3, "tools/call", {"name": "pudding.sessions.create",
                                      "arguments": {"request_id": rid}})])
        self.assertEqual(output[0]["result"]["protocolVersion"], PROTOCOL_VERSION)
        tools = output[1]["result"]["tools"]
        self.assertEqual(len(tools), 9)
        self.assertTrue(all(t["inputSchema"]["additionalProperties"] is False for t in tools))
        self.assertEqual(store.calls, [("pudding.sessions.create", {"request_id": rid})])
        result = output[2]["result"]
        self.assertFalse(result["isError"])
        self.assertEqual(json.loads(result["content"][0]["text"]), result["structuredContent"])

    def test_init_is_required_and_initialized_notification_gates_tools(self):
        output, store = transcript([request(1, "tools/list"), *handshake()[:1],
                                    request(2, "tools/list")])
        self.assertEqual([output[i]["error"]["code"] for i in (0, 2)], [-32000, -32000])
        self.assertFalse(store.calls)

    def test_ping_works_during_initialization(self):
        output, _ = transcript([request(0, "ping")])
        self.assertEqual(output, [{"jsonrpc": "2.0", "id": 0, "result": {}}])

    def test_version_negotiation_advertises_only_supported_revision(self):
        messages = handshake()
        messages[0]["params"]["protocolVersion"] = "unknown-future-version"
        output, _ = transcript(messages)
        self.assertEqual(output[0]["result"]["protocolVersion"], PROTOCOL_VERSION)

    def test_notifications_do_not_respond_or_invoke_domain(self):
        output, store = transcript(handshake() + [
            {"jsonrpc": "2.0", "method": "tools/call", "params": {
                "name": "pudding.sessions.create", "arguments": {"request_id": str(uuid4())}}},
            {"jsonrpc": "2.0", "method": "unknown"}])
        self.assertEqual(len(output), 1)
        self.assertFalse(store.calls)

    def test_invalid_envelopes_and_duplicate_rpc_id(self):
        output, store = transcript(handshake() + [
            request(None, "tools/list"), request(True, "tools/list"),
            request(1.5, "tools/list"), request(2, "tools/list"),
            request(2, "tools/list"), [], {"jsonrpc": "1.0", "id": 5, "method": "ping"}])
        for i in (1, 2, 3, 5, 6, 7):
            self.assertEqual(output[i]["error"]["code"], -32600)
        self.assertEqual(output[4]["id"], 2)
        self.assertFalse(store.calls)

    def test_argument_validation_does_not_reach_store(self):
        common = {"session_id": str(uuid4()), "request_id": str(uuid4()), "expected_revision": 0}
        cases = [
            ("pudding.lid.peel", {**common, "amount_q": -1}),
            ("pudding.lid.peel", {**common, "amount_q": True}),
            ("pudding.lid.peel", {**common, "amount_q": 1001}),
            ("pudding.spoon.taste", {**common, "agent_id": "victim"}),
            ("pudding.spoon.taste", {**common, "expected_revision": True}),
            ("pudding.sessions.get", {"session_id": "bad"}),
            ("pudding.sessions.create", {}),
            ("pudding.presets.list", {"clock": "fake"}),
            ("missing", {}),
        ]
        output, store = transcript(handshake() + [request(i, "tools/call", {
            "name": name, "arguments": args}) for i, (name, args) in enumerate(cases)])
        self.assertTrue(all(r["error"]["code"] == -32602 for r in output[1:]))
        self.assertFalse(store.calls)

    def test_unknown_method_and_invalid_list_cursor(self):
        output, _ = transcript(handshake() + [request(2, "unknown"),
            request(3, "tools/list", {"cursor": "fake"})])
        self.assertEqual(output[1]["error"]["code"], -32601)
        self.assertEqual(output[2]["error"]["code"], -32602)

    def test_domain_failure_is_a_tool_error(self):
        class Denied(FakeStore):
            def call(self, name, arguments):
                return {"ok": False, "code": "LOCKED", "food_effect": "none"}
        output, _ = transcript(handshake() + [request(2, "tools/call", {
            "name": "pudding.presets.list"})], Denied())
        self.assertTrue(output[1]["result"]["isError"])
        self.assertEqual(output[1]["result"]["structuredContent"]["code"], "LOCKED")

    def test_unexpected_exception_does_not_leak(self):
        class Broken(FakeStore):
            def call(self, name, arguments):
                raise RuntimeError("secret sql token")
        output, _ = transcript(handshake() + [request(2, "tools/call", {
            "name": "pudding.presets.list"})], Broken())
        self.assertEqual(output[1]["error"], {"code": -32603, "message": "Internal error"})
        self.assertNotIn("secret", json.dumps(output))

    def test_duplicate_keys_nonfinite_invalid_utf8_and_deep_json(self):
        for data in (b'{"jsonrpc":"2.0","id":1,"id":2,"method":"ping"}\n',
                     b'{"jsonrpc":"2.0","id":NaN,"method":"ping"}\n',
                     b'{"jsonrpc":"2.0","id":1,"method":"ping","params":{"x":1e10000}}\n',
                     b'\xff\n', b'[' * 2000 + b']' * 2000 + b'\n'):
            with self.subTest(data=data[:40]):
                output = io.StringIO()
                run(FakeStore(), io.BytesIO(data), output)
                self.assertEqual(json.loads(output.getvalue())["error"]["code"], -32700)

    def test_oversize_line_closes_connection_with_bounded_read(self):
        output = io.StringIO()
        run(FakeStore(), io.BytesIO(b"x" * (MAX_LINE_BYTES + 1) + b"\n"), output)
        lines = output.getvalue().splitlines()
        self.assertEqual(len(lines), 1)
        self.assertEqual(json.loads(lines[0])["error"]["code"], -32600)


if __name__ == "__main__":
    unittest.main()
