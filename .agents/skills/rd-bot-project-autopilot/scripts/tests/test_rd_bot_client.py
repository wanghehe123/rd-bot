from __future__ import annotations

import unittest

from scripts.rd_bot_client import (
    ApiResponseError,
    ApiTransportError,
    ClientPolicyError,
    SafeRdBotClient,
)
from scripts.tests.fake_rd_bot import FakeRdBotServer


def valid_request() -> dict[str, object]:
    return {
        "title": "Add a bounded status summary",
        "priority": "P2",
        "projectId": "7480000000000000000",
        "repositoryUrl": "https://github.com/example/project",
        "repoOwner": "example",
        "repoName": "project",
        "baseBranch": "main",
        "expectedResult": "The admin status summary is visible and tested.",
        "acceptanceCriteria": ["The endpoint returns 200", "The focused test passes"],
        "materials": [{"type": "TEXT", "name": "goal", "content": "bounded"}],
        "autoExecute": False,
        "tokenBudgetOverride": 1000,
    }


class SafeRdBotClientTest(unittest.TestCase):
    def setUp(self) -> None:
        self.server = FakeRdBotServer()
        self.server.__enter__()
        self.client = SafeRdBotClient(
            self.server.base_url,
            mode="live-test",
            live_flag=True,
            environ={"RD_BOT_AUTOPILOT_LIVE_TEST": "1"},
        )

    def tearDown(self) -> None:
        self.server.__exit__(None, None, None)

    def test_rejects_non_loopback_origin(self) -> None:
        with self.assertRaisesRegex(ClientPolicyError, "loopback"):
            SafeRdBotClient("https://example.com", mode="dry-run", live_flag=False)

    def test_dry_run_rejects_every_post(self) -> None:
        client = SafeRdBotClient(self.server.base_url, mode="dry-run", live_flag=False)
        with self.assertRaisesRegex(ClientPolicyError, "live-test"):
            client.create_requirement(valid_request())
        self.assertEqual(self.server.requests, [])

    def test_live_mode_requires_independent_environment_opt_in(self) -> None:
        client = SafeRdBotClient(self.server.base_url, mode="live-test", live_flag=True, environ={})
        with self.assertRaisesRegex(ClientPolicyError, "RD_BOT_AUTOPILOT_LIVE_TEST"):
            client.submit_task("7480000000000000001")
        self.assertEqual(self.server.requests, [])

    def test_rejects_redirect(self) -> None:
        self.server.queue_redirect()
        with self.assertRaisesRegex(ApiTransportError, "redirect"):
            self.client.get_project("7480000000000000000")

    def test_rejects_response_larger_than_two_mebibytes(self) -> None:
        self.server.queue_response(
            200,
            b'{"data":"' + (b"x" * (2 * 1024 * 1024)) + b'"}',
            {"Content-Type": "application/json"},
        )
        with self.assertRaisesRegex(ApiResponseError, "response too large"):
            self.client.get_project("7480000000000000000")

    def test_unknown_path_is_denied_before_network(self) -> None:
        with self.assertRaisesRegex(ClientPolicyError, "not allowlisted"):
            self.client.request("POST", "/admin/rd-tasks/1/approve", {})
        self.assertEqual(self.server.requests, [])

    def test_allowlisted_route_still_rejects_untyped_write_payload(self) -> None:
        with self.assertRaisesRegex(ClientPolicyError, "submit payload"):
            self.client.request("POST", "/admin/rd-tasks/7480000000000000001/submit", {"approve": True})
        with self.assertRaisesRegex(ClientPolicyError, "query parameters"):
            self.client.request("GET", "/admin/projects", params={"unsafe": "value"})
        self.assertEqual(self.server.requests, [])

    def test_create_uses_exact_payload_and_returns_json(self) -> None:
        self.server.queue_json({"taskId": "7480000000000000001", "status": "CREATED"})
        result = self.client.create_requirement(valid_request())
        self.assertEqual(result["taskId"], "7480000000000000001")
        self.assertEqual(self.server.requests[0]["path"], "/admin/rd-tasks/requirements")
        self.assertFalse(self.server.requests[0]["json"]["autoExecute"])

    def test_invalid_requirement_fails_before_network(self) -> None:
        payload = valid_request()
        payload["acceptanceCriteria"] = ["only one"]
        with self.assertRaisesRegex(ClientPolicyError, "acceptanceCriteria"):
            self.client.create_requirement(payload)
        self.assertEqual(self.server.requests, [])

    def test_requirement_rejects_non_manual_material_or_credential_source(self) -> None:
        local = valid_request()
        local["materials"] = [{"sourceType": "LOCAL_UPLOAD", "content": "bounded"}]
        with self.assertRaisesRegex(ClientPolicyError, "sourceType"):
            self.client.create_requirement(local)
        credential_uri = valid_request()
        credential_uri["materials"] = [{"sourceType": "MANUAL_TEXT", "sourceUri": "https://user:secret@example.invalid/doc", "content": "bounded"}]
        with self.assertRaisesRegex(ClientPolicyError, "sourceUri"):
            self.client.create_requirement(credential_uri)
        self.assertEqual(self.server.requests, [])

    def test_requirement_rejects_null_token_budget_before_network(self) -> None:
        payload = valid_request()
        payload["tokenBudgetOverride"] = None
        with self.assertRaisesRegex(ClientPolicyError, "tokenBudgetOverride"):
            self.client.create_requirement(payload)
        self.assertEqual(self.server.requests, [])

    def test_live_provision_allows_only_exact_project_and_knowledge_payloads(self) -> None:
        client = SafeRdBotClient(
            self.server.base_url,
            mode="live-provision",
            live_flag=True,
            environ={"RD_BOT_AUTOPILOT_LIVE_PROVISION": "1"},
        )
        knowledge_base = {
            "name": "Habit tracker project knowledge",
            "description": "[autopilot:autopilot-web-123:knowledge:123456abcdef] generated knowledge",
        }
        document = {
            "sourceName": "project-charter.md",
            "knowledgeType": "PROJECT_CHARTER",
            "mimeType": "text/markdown",
            "content": "# Charter\n\n[autopilot:autopilot-web-123:source-1:123456abcdef]",
            "chunkingMode": "STRUCTURE_AWARE",
            "chunkSize": 512,
            "overlapSize": 64,
        }
        project = {
            "projectKey": "autopilot-web-123",
            "name": "Habit Tracker",
            "description": "[autopilot:autopilot-web-123:project:123456abcdef] Build a habit tracker",
            "repositoryUrl": "https://github.com/alice/habit-tracker-web-123.git",
            "repoOwner": "alice",
            "repoName": "habit-tracker-web-123",
            "defaultBranch": "main",
            "enabled": True,
            "knowledgeBaseId": "kb-1",
        }
        self.server.queue_json({"id": "kb-1", "name": knowledge_base["name"]})
        self.server.queue_json({"id": "doc-1", "sourceName": document["sourceName"]})
        self.server.queue_json({"projectId": "7480000000000000000", **project})

        client.create_knowledge_base(knowledge_base)
        client.write_knowledge_document("kb-1", document)
        client.create_project(project)

        self.assertEqual(
            ["/knowledge-base", "/knowledge-base/kb-1/docs/write", "/admin/projects"],
            [request["path"] for request in self.server.requests],
        )

    def test_knowledge_list_uses_the_real_controller_query_contract(self) -> None:
        self.server.queue_json({"records": []})
        self.server.queue_json({"records": []})

        self.client.list_knowledge_bases("habit", page=2, page_size=50)
        self.client.list_knowledge_documents("kb-1", "brief", page=3, page_size=20)

        self.assertEqual("current=2&size=50&name=habit", self.server.requests[0]["query"])
        self.assertEqual("current=3&size=20&keyword=brief", self.server.requests[1]["query"])

    def test_provision_rejects_external_document_unknown_project_field_and_old_env_opt_in(self) -> None:
        with self.assertRaisesRegex(ClientPolicyError, "live-provision"):
            self.client.create_knowledge_base({
                "name": "Knowledge",
                "description": "[autopilot:autopilot-web-123:knowledge:123456abcdef]",
            })

        old_env = SafeRdBotClient(
            self.server.base_url,
            mode="live-provision",
            live_flag=True,
            environ={"RD_BOT_AUTOPILOT_LIVE_TEST": "1"},
        )
        with self.assertRaisesRegex(ClientPolicyError, "LIVE_PROVISION"):
            old_env.create_knowledge_base({"name": "Knowledge", "description": "[autopilot:autopilot-web-123:knowledge:123456abcdef]"})

        client = SafeRdBotClient(
            self.server.base_url,
            mode="live-provision",
            live_flag=True,
            environ={"RD_BOT_AUTOPILOT_LIVE_PROVISION": "1"},
        )
        with self.assertRaisesRegex(ClientPolicyError, "unknown"):
            client.create_project({"unexpected": True})
        with self.assertRaisesRegex(ClientPolicyError, "source"):
            client.write_knowledge_document("kb-1", {
                "sourceName": "project-charter.md",
                "knowledgeType": "PROJECT_CHARTER",
                "mimeType": "text/markdown",
                "content": "# Charter",
                "sourceUri": "https://example.com/secret.md",
                "chunkingMode": "STRUCTURE_AWARE",
                "chunkSize": 512,
                "overlapSize": 64,
            })
        self.assertEqual([], self.server.requests)


if __name__ == "__main__":
    unittest.main()
