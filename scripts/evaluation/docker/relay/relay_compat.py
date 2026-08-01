"""Pure helpers for relay request/response compatibility shims."""

from __future__ import annotations

import json


def remap_developer_to_system_role(body: bytes) -> bytes:
    """
    If body is a JSON object with a ``messages`` array, replace any message
    ``role`` of ``developer`` with ``system``.  Other fields are unchanged.
    Non-JSON or malformed bodies are returned as-is.
    """
    if not body:
        return body
    try:
        payload = json.loads(body)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return body
    if not isinstance(payload, dict):
        return body
    messages = payload.get("messages")
    if not isinstance(messages, list):
        return body
    changed = False
    for message in messages:
        if isinstance(message, dict) and message.get("role") == "developer":
            message["role"] = "system"
            changed = True
    if not changed:
        return body
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
