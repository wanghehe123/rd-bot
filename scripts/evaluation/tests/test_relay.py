from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from pathlib import Path


def _load_module(module_name: str, relative_path: str):
    module_path = Path(__file__).resolve().parents[1] / relative_path
    spec = importlib.util.spec_from_file_location(module_name, module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


relay_compat = _load_module("relay_compat", "docker/relay/relay_compat.py")
relay = _load_module("relay", "docker/relay/relay.py")


class RelayMessageCompatTest(unittest.TestCase):
    def test_remaps_developer_role_to_system(self) -> None:
        body = json.dumps(
            {
                "model": "deepseek-chat",
                "messages": [
                    {"role": "developer", "content": "You are helpful."},
                    {"role": "user", "content": "Hello"},
                ],
            }
        ).encode("utf-8")

        remapped = relay_compat.remap_developer_to_system_role(body)
        payload = json.loads(remapped)

        self.assertEqual("system", payload["messages"][0]["role"])
        self.assertEqual("You are helpful.", payload["messages"][0]["content"])
        self.assertEqual("user", payload["messages"][1]["role"])

    def test_leaves_non_chat_bodies_unchanged(self) -> None:
        body = b'{"model":"deepseek-chat","prompt":"hello"}'
        self.assertIs(body, relay_compat.remap_developer_to_system_role(body))

    def test_leaves_messages_without_developer_unchanged(self) -> None:
        body = json.dumps(
            {
                "messages": [
                    {"role": "system", "content": "You are helpful."},
                    {"role": "user", "content": "Hello"},
                ]
            }
        ).encode("utf-8")
        self.assertIs(body, relay_compat.remap_developer_to_system_role(body))


class RelayFallbackPolicyTest(unittest.TestCase):
    def test_fallback_on_quota_statuses(self) -> None:
        self.assertTrue(relay.should_fallback(429, b"{}"))
        self.assertTrue(relay.should_fallback(401, b"{}"))
        self.assertTrue(relay.should_fallback(402, b"{}"))
        self.assertTrue(relay.should_fallback(502, b'{"error":"upstream_error"}'))

    def test_fallback_on_quota_message_body(self) -> None:
        body = b'{"error":{"message":"You exceeded your current quota"}}'
        self.assertTrue(relay.should_fallback(400, body))

    def test_no_fallback_on_success_or_plain_bad_request(self) -> None:
        self.assertFalse(relay.should_fallback(200, b'{"ok":true}'))
        self.assertFalse(relay.should_fallback(400, b'{"error":"invalid_request"}'))

    def test_retry_primary_on_transient_statuses(self) -> None:
        self.assertTrue(relay.should_retry_primary(502, b"{}"))
        self.assertTrue(relay.should_retry_primary(429, b"{}"))
        self.assertFalse(relay.should_retry_primary(401, b"{}"))
        self.assertFalse(relay.should_retry_primary(200, b"{}"))

    def test_rewrite_request_model(self) -> None:
        body = json.dumps({"model": "primary-model", "messages": []}).encode("utf-8")
        rewritten = relay.rewrite_request_model(body, "fallback-model")
        self.assertEqual("fallback-model", json.loads(rewritten)["model"])


if __name__ == "__main__":
    unittest.main()
