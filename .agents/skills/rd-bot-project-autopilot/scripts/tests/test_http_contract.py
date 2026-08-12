from __future__ import annotations

import unittest

from scripts.rd_bot_client import ApiResponseError, ApiTransportError, SafeRdBotClient
from scripts.tests.fake_rd_bot import FakeRdBotServer


class HttpContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.server = FakeRdBotServer()
        self.server.__enter__()
        self.ledger: list[dict[str, object]] = []
        self.client = SafeRdBotClient(
            self.server.base_url,
            mode="live-test",
            live_flag=True,
            environ={"RD_BOT_AUTOPILOT_LIVE_TEST": "1"},
            request_ledger=self.ledger.append,
        )

    def tearDown(self) -> None:
        self.server.__exit__(None, None, None)

    def test_malformed_json_fails_closed(self) -> None:
        self.server.queue_response(200, b"not-json", {"Content-Type": "application/json"})
        with self.assertRaisesRegex(ApiResponseError, "valid JSON"):
            self.client.get_project("7480000000000000000")

    def test_non_json_response_fails_closed(self) -> None:
        self.server.queue_response(200, b"<html>no</html>", {"Content-Type": "text/html"})
        with self.assertRaisesRegex(ApiResponseError, "not JSON"):
            self.client.get_project("7480000000000000000")

    def test_http_conflict_and_server_error_are_not_retried(self) -> None:
        self.server.queue_json({"error": "conflict"}, status=409)
        with self.assertRaisesRegex(ApiResponseError, "HTTP 409"):
            self.client.get_project("7480000000000000000")
        self.server.queue_json({"error": "server"}, status=500)
        with self.assertRaisesRegex(ApiResponseError, "HTTP 500"):
            self.client.get_project("7480000000000000000")
        self.assertEqual(len(self.server.requests), 2)
        self.assertEqual([entry["status"] for entry in self.ledger], [409, 500])
        self.assertEqual(
            [set(entry) for entry in self.ledger],
            [
                {"method", "path", "requestSha256", "status", "timestamp"},
                {"method", "path", "requestSha256", "status", "timestamp"},
            ],
        )

    def test_post_connection_failure_is_marked_ambiguous(self) -> None:
        closed = SafeRdBotClient(
            "http://127.0.0.1:9",
            mode="live-test",
            live_flag=True,
            environ={"RD_BOT_AUTOPILOT_LIVE_TEST": "1"},
            timeout_seconds=0.1,
        )
        with self.assertRaises(ApiTransportError) as context:
            closed.submit_task("7480000000000000001")
        self.assertTrue(context.exception.ambiguous)

    def test_ledger_contains_hash_only_and_accepts_non_numeric_evaluation_id(self) -> None:
        self.server.queue_json({"runId": "eval-2026.07.27_1", "status": "CREATED"})
        result = self.client.get_evaluation("eval-2026.07.27_1")
        self.assertEqual(result["runId"], "eval-2026.07.27_1")
        self.assertEqual(set(self.ledger[0]), {"method", "path", "requestSha256", "status", "timestamp"})
        self.assertEqual(self.ledger[0]["path"], "/admin/evaluations/runs/eval-2026.07.27_1")


if __name__ == "__main__":
    unittest.main()
