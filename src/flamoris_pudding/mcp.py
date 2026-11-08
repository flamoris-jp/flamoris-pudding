"""Narrow local stdio MCP adapter (2025-11-25), not a general MCP SDK.

The process launcher fixes the synthetic principal in Store. No caller-supplied
identity, clock, purchase or accounting administration is exposed here.
"""

from __future__ import annotations

import copy
import json
import math
from typing import BinaryIO, TextIO
from uuid import UUID

PROTOCOL_VERSION = "2025-11-25"
MAX_LINE_BYTES = 65_536
MAX_REQUESTS = 10_000


def _integer(minimum: int, maximum: int) -> dict:
    return {"type": "integer", "minimum": minimum, "maximum": maximum}


_UUID = {"type": "string", "format": "uuid", "maxLength": 36}
_COMMON = {
    "session_id": _UUID,
    "request_id": _UUID,
    "expected_revision": _integer(0, 2**31 - 1),
}


def _tool(name: str, description: str, properties: dict, required: list,
          *, read_only: bool = False, destructive: bool = False) -> dict:
    return {
        "name": name,
        "description": description,
        "inputSchema": {
            "type": "object", "properties": properties, "required": required,
            "additionalProperties": False,
        },
        "annotations": {
            "readOnlyHint": read_only, "destructiveHint": destructive,
            "idempotentHint": read_only, "openWorldHint": False,
        },
    }


TOOLS = [
    _tool("pudding.presets.list", "List authored synthetic research presets.",
          {}, [], read_only=True),
    _tool("pudding.sessions.create", "Create one local research serving.",
          {"request_id": _UUID,
           "preset_id": {"type": "string", "maxLength": 64,
                         "default": "custard-v0"}}, ["request_id"]),
    _tool("pudding.sessions.get", "Read a serving without advancing time or food.",
          {"session_id": _UUID}, ["session_id"], read_only=True),
    _tool("pudding.lid.peel", "Peel part of the lid; 1000 is fully open.",
          {**_COMMON, "amount_q": _integer(1, 1000)},
          [*_COMMON, "amount_q"]),
    _tool("pudding.lid.dispose", "Move the detached lid into the virtual bin.",
          _COMMON, list(_COMMON)),
    _tool("pudding.spoon.scoop", "Move exact component masses onto one spoon.",
          {**_COMMON, "body_mg": _integer(0, 100_000),
           "sauce_mg": _integer(0, 100_000)},
          [*_COMMON, "body_mg", "sauce_mg"]),
    _tool("pudding.spoon.taste", "Consume the loaded spoon exactly once.",
          _COMMON, list(_COMMON)),
    _tool("pudding.sessions.finish", "End research eating and discard leftovers.",
          _COMMON, list(_COMMON), destructive=True),
    _tool("pudding.sessions.cleanup", "Clean the empty serving and close it.",
          _COMMON, list(_COMMON)),
]
_BY_NAME = {tool["name"]: tool for tool in TOOLS}


class _RpcError(Exception):
    def __init__(self, code: int, message: str):
        self.code, self.message = code, message


def _error(request_id, code: int, message: str) -> dict:
    return {"jsonrpc": "2.0", "id": request_id,
            "error": {"code": code, "message": message}}


def _pairs(pairs: list) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate key")
        result[key] = value
    return result


def _constant(_: str):
    raise ValueError("nonfinite JSON number")


def _bounded_shape(value) -> None:
    pending = [(value, 0)]
    visited = 0
    while pending:
        item, depth = pending.pop()
        visited += 1
        if depth > 64 or visited > 10_000:
            raise ValueError("JSON structure limit")
        if isinstance(item, float) and not math.isfinite(item):
            raise ValueError("nonfinite JSON number")
        children = item.values() if isinstance(item, dict) else item if isinstance(item, list) else ()
        pending.extend((child, depth + 1) for child in children)


def _object(value, allowed: set, required: set = frozenset()) -> dict:
    if not isinstance(value, dict) or set(value) - allowed or required - set(value):
        raise _RpcError(-32602, "Invalid parameters")
    return value


def _validate_arguments(name: str, arguments) -> dict:
    schema = _BY_NAME[name]["inputSchema"]
    arguments = _object(arguments, set(schema["properties"]),
                        set(schema["required"]))
    for key, value in arguments.items():
        definition = schema["properties"][key]
        if definition["type"] == "integer":
            if (type(value) is not int or value < definition["minimum"]
                    or value > definition["maximum"]):
                raise _RpcError(-32602, "Invalid tool arguments")
        elif not isinstance(value, str) or not value or len(value) > definition["maxLength"]:
            raise _RpcError(-32602, "Invalid tool arguments")
        elif definition.get("format") == "uuid":
            try:
                if str(UUID(value)) != value:
                    raise ValueError("noncanonical UUID")
            except ValueError:
                raise _RpcError(-32602, "Invalid tool arguments") from None
    return arguments


class _Session:
    def __init__(self, store):
        self.store = store
        self.initializing = False
        self.ready = False
        self.seen_ids = set()

    def dispatch(self, message):
        if not isinstance(message, dict):
            return _error(None, -32600, "Invalid request; batches are unsupported")
        request_id = message.get("id")
        has_id = "id" in message
        if (message.get("jsonrpc") != "2.0"
                or not isinstance(message.get("method"), str)
                or not message["method"]
                or set(message) - {"jsonrpc", "id", "method", "params"}
                or (has_id and (type(request_id) not in (str, int)
                                or (isinstance(request_id, str) and len(request_id) > 256)))):
            return _error(None, -32600, "Invalid request")
        params = message.get("params", {})
        method = message["method"]
        if not has_id:
            # Unknown notifications never invoke domain operations and never reply.
            if (method == "notifications/initialized" and self.initializing
                    and isinstance(params, dict)
                    and not set(params) - {"_meta"}):
                self.ready = True
            return None
        if request_id in self.seen_ids:
            return _error(request_id, -32600, "Request ID already used in this connection")
        self.seen_ids.add(request_id)
        try:
            if method == "ping":
                _object(params, {"_meta"})
                result = {}
            elif method == "initialize":
                if self.initializing:
                    raise _RpcError(-32600, "Already initialized")
                _object(params, {"protocolVersion", "capabilities", "clientInfo", "_meta"},
                        {"protocolVersion", "capabilities", "clientInfo"})
                info = params["clientInfo"]
                if (not isinstance(params["protocolVersion"], str)
                        or not params["protocolVersion"]
                        or not isinstance(params["capabilities"], dict)
                        or not isinstance(info, dict)
                        or any(not isinstance(info.get(k), str) or not info[k]
                               or len(info[k]) > 256 for k in ("name", "version"))):
                    raise _RpcError(-32602, "Invalid initialization parameters")
                self.initializing = True
                result = {
                    "protocolVersion": PROTOCOL_VERSION,
                    "capabilities": {"tools": {"listChanged": False}},
                    "serverInfo": {"name": "flamoris-pudding", "version": "0.1.0"},
                    "instructions": "Local synthetic research only. No premium authentication or payments.",
                }
            elif not self.ready:
                raise _RpcError(-32000, "Initialize and send notifications/initialized first")
            elif method == "tools/list":
                _object(params, {"cursor", "_meta"})
                if "cursor" in params:
                    raise _RpcError(-32602, "Invalid cursor; this list has one page")
                result = {"tools": copy.deepcopy(TOOLS)}
            elif method == "tools/call":
                _object(params, {"name", "arguments", "_meta"}, {"name"})
                name = params["name"]
                if not isinstance(name, str) or name not in _BY_NAME:
                    raise _RpcError(-32602, "Unknown tool")
                arguments = _validate_arguments(name, params.get("arguments", {}))
                domain = self.store.call(name, arguments)
                if not isinstance(domain, dict) or type(domain.get("ok")) is not bool:
                    raise RuntimeError("invalid domain response")
                result = {
                    "content": [{"type": "text", "text": json.dumps(
                        domain, ensure_ascii=False, separators=(",", ":"), allow_nan=False)}],
                    "structuredContent": domain,
                    "isError": not domain["ok"],
                }
            else:
                raise _RpcError(-32601, "Method not found")
            return {"jsonrpc": "2.0", "id": request_id, "result": result}
        except _RpcError as exc:
            return _error(request_id, exc.code, exc.message)
        except Exception:
            # Domain/DB errors must not disclose paths, SQL, tokens or tracebacks.
            return _error(request_id, -32603, "Internal error")


def run(store, reader: BinaryIO, writer: TextIO) -> None:
    """Run until stdin EOF; oversize input or 10,000 requests closes the session.

    A binary reader guarantees that MAX_LINE_BYTES bounds UTF-8 bytes. stdout is
    exclusively newline-delimited JSON-RPC. Inputs are not logged.
    """
    session = _Session(store)
    while True:
        line = reader.readline(MAX_LINE_BYTES + 1)
        if not line:
            return
        if len(line) > MAX_LINE_BYTES:
            response = _error(None, -32600, "Message exceeds 65536 bytes; closing session")
            writer.write(json.dumps(response, separators=(",", ":")) + "\n")
            writer.flush()
            return
        try:
            message = json.loads(line.decode("utf-8"), object_pairs_hook=_pairs,
                                 parse_constant=_constant)
            _bounded_shape(message)
            response = session.dispatch(message)
        except (ValueError, UnicodeDecodeError, RecursionError):
            response = _error(None, -32700, "Parse error")
        if response is not None:
            writer.write(json.dumps(response, ensure_ascii=False,
                                    separators=(",", ":"), allow_nan=False) + "\n")
            writer.flush()
        if len(session.seen_ids) >= MAX_REQUESTS:
            return
