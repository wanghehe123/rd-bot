#!/usr/bin/env python3
"""Bounded localhost HTTP regression tests for verify_demo.py.

The fixture mirrors the DTO fields and HTTP status mapping of the real admin
controllers.  It never calls a deployed or external service.
"""
from __future__ import annotations

import json
import os
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


SCRIPT = Path(__file__).with_name("verify_demo.py").resolve()
TASK_ID = "task-current"
OTHER_TASK_ID = "task-other"
CODING_STAGE_ID = "stage-coding-current"
OTHER_CODING_STAGE_ID = "stage-coding-other"
PREVIEW_STAGE_ID = "stage-reviewer"
DECISION_HASH = "decision-qa"
CODING_DECISION_HASH = "decision-coding"


class _StubHandler(BaseHTTPRequestHandler):
    mode = "all"
    negative_status = 400
    manager_requests: list[str] = []

    def do_GET(self) -> None:  # noqa: N802 - stdlib handler API
        parsed = urlparse(self.path)
        status, body = self._route(parsed.path, parse_qs(parsed.query))
        raw = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, *_args: object) -> None:
        return

    @classmethod
    def _route(cls, path: str, query: dict[str, list[str]]) -> tuple[int, object]:
        if path.startswith(f"/admin/rd-tasks/{TASK_ID}/manager-decisions/"):
            cls.manager_requests.append(path)
            decision_hash = path.rsplit("/", 1)[-1]
            if decision_hash == DECISION_HASH:
                return 200, {
                    "taskId": TASK_ID,
                    "roundNo": 1,
                    "sourceCommandId": "source-qa",
                    "decisionHash": DECISION_HASH,
                    "route": "EXECUTE",
                    "executorRoute": "QA_AGENT",
                    "targetRecordIds": [],
                    "boundedContract": "",
                    "rationale": "host-verify succeeded; continue QA",
                    "stateVersion": 5,
                    "stateHash": "sha256:state-qa",
                }
            if decision_hash == CODING_DECISION_HASH:
                return 200, {
                    "taskId": TASK_ID,
                    "roundNo": 2,
                    "sourceCommandId": "source-coding",
                    "decisionHash": CODING_DECISION_HASH,
                    "route": "EXECUTE",
                    "executorRoute": "CODING_AGENT",
                    "targetRecordIds": ["AC-003"],
                    "boundedContract": "只修复已审计缺口：AC-003",
                    "rationale": "pending blocking acceptance [AC-003]",
                    "stateVersion": 6,
                    "stateHash": "sha256:state-coding",
                }
            return 404, {"status": 404, "error": "manager decision not found"}
        if path == "/admin/rd-tasks":
            if "pageSize" in query and cls.mode == "discovery-error":
                return 503, {"status": 503, "error": "stub discovery failure"}
            records = [{"taskId": TASK_ID, "taskType": "REQUIREMENT", "status": "COMPLETED"}]
            if cls.mode == "page-misses-task" and "pageSize" not in query:
                records = [{"taskId": OTHER_TASK_ID, "taskType": "REQUIREMENT", "status": "FAILED"}]
            if cls.mode not in {"single", "discovery-error"}:
                if not any(record["taskId"] == OTHER_TASK_ID for record in records):
                    records.append({"taskId": OTHER_TASK_ID, "taskType": "REQUIREMENT", "status": "FAILED"})
            return 200, {"records": records, "page": 1, "pageSize": 100, "total": len(records), "pages": 1}

        if path == f"/admin/rd-tasks/{TASK_ID}":
            return 200, {"taskId": TASK_ID, "taskType": "REQUIREMENT", "status": "COMPLETED"}

        if path == f"/admin/rd-tasks/{TASK_ID}/execution-overview":
            stages = [{"stageRunId": CODING_STAGE_ID, "taskId": TASK_ID, "status": "SUCCEEDED", "resultArtifactId": "artifact-coding"}]
            if cls.mode not in {"single", "discovery-error"}:
                stages.insert(0, {"stageRunId": PREVIEW_STAGE_ID, "taskId": TASK_ID, "status": "FAILED", "resultArtifactId": ""})
            return 200, {"taskId": TASK_ID, "stageRuns": stages}

        if path == f"/admin/rd-tasks/{TASK_ID}/role-prompts":
            return 200, {"taskId": TASK_ID, "stagePrompts": []}

        if path == f"/admin/rd-tasks/{TASK_ID}/coding-mea":
            stage_id = query.get("codingStageRunId", [None])[0]
            if stage_id == "not-a-real-stage":
                return 404, {"status": 404, "error": "stage run does not belong to task"}
            if stage_id == OTHER_CODING_STAGE_ID and cls.mode == "wrongidentity":
                return 200, {"schemaVersion": 1, "taskId": TASK_ID, "codingStageRunId": OTHER_CODING_STAGE_ID, "decisions": []}
            if stage_id not in {None, CODING_STAGE_ID}:
                status = cls.negative_status if cls.mode == "wrong-negative" else 404
                return status, {"status": status, "error": "stage run does not belong to task"}
            if cls.mode in {"single", "discovery-error"}:
                decisions = []
            elif cls.mode == "qa-only":
                decisions = [{
                    "decisionHash": DECISION_HASH,
                    "route": "EXECUTE",
                    "executorRoute": "QA_AGENT",
                    "targetRecordIds": [],
                    "roundNo": 1,
                    "sourceCommandId": "source-qa",
                    "stateVersion": 5,
                    "stateHash": "sha256:state-qa",
                }]
            else:
                decisions = [
                    {
                        "decisionHash": DECISION_HASH,
                        "route": "EXECUTE",
                        "executorRoute": "QA_AGENT",
                        "targetRecordIds": [],
                        "roundNo": 1,
                        "sourceCommandId": "source-qa",
                        "stateVersion": 5,
                        "stateHash": "sha256:state-qa",
                    },
                    {
                        "decisionHash": CODING_DECISION_HASH,
                        "route": "EXECUTE",
                        "executorRoute": "CODING_AGENT",
                        "targetRecordIds": ["AC-003"],
                        "roundNo": 2,
                        "sourceCommandId": "source-coding",
                        "stateVersion": 6,
                        "stateHash": "sha256:state-coding",
                    },
                ]
            response_task_id = OTHER_TASK_ID if cls.mode == "wrong-positive-identity" else TASK_ID
            return 200, {
                "schemaVersion": 1,
                "taskId": response_task_id,
                "codingStageRunId": CODING_STAGE_ID,
                "decisions": decisions,
                "codingStages": [{"stageRunId": CODING_STAGE_ID, "status": "SUCCEEDED", "resultArtifactId": "artifact-coding"}],
                "qaStages": [],
            }

        if path == f"/admin/rd-tasks/{OTHER_TASK_ID}/coding-mea":
            stage_id = query.get("codingStageRunId", [None])[0]
            if stage_id == CODING_STAGE_ID and cls.mode == "wrongidentity":
                return 200, {"schemaVersion": 1, "taskId": OTHER_TASK_ID, "codingStageRunId": CODING_STAGE_ID, "decisions": []}
            return 200, {"schemaVersion": 1, "taskId": OTHER_TASK_ID, "codingStageRunId": OTHER_CODING_STAGE_ID, "decisions": []}

        if path.startswith(f"/admin/rd-tasks/{TASK_ID}/stage-runs/") and path.endswith("/result/content"):
            stage_id = path.split("/")[-2]
            return 404, {"status": 404, "error": "FULL_RESULT_NOT_PERSISTED_OR_NOT_BOUND"}

        if path.startswith(f"/admin/rd-tasks/{TASK_ID}/stage-runs/") and path.endswith("/result"):
            stage_id = path.split("/")[-2]
            if stage_id == "not-a-real-stage":
                return 404, {"status": 404, "error": "stage run does not belong to task"}
            if stage_id == OTHER_CODING_STAGE_ID:
                if cls.mode == "wrongidentity":
                    return 200, {"taskId": TASK_ID, "stageRunId": OTHER_CODING_STAGE_ID, "source": "FINALIZATION_RESULT", "available": True}
                status = cls.negative_status if cls.mode == "wrong-negative" else 404
                return status, {"status": status, "error": "stage run does not belong to task"}
            if stage_id == CODING_STAGE_ID:
                body = {"taskId": TASK_ID, "stageRunId": CODING_STAGE_ID, "source": "FINALIZATION_RESULT", "available": True}
                if cls.mode == "wrong-status":
                    return 404, body
                return 200, body
            if stage_id == PREVIEW_STAGE_ID:
                return 200, {
                    "taskId": TASK_ID,
                    "stageRunId": PREVIEW_STAGE_ID,
                    "source": "ARTIFACT_PREVIEW",
                    "available": False,
                    "unavailableReason": "FULL_RESULT_NOT_PERSISTED_OR_NOT_BOUND",
                }
            return 404, {"status": 404, "error": "stage run does not belong to task"}

        return 404, {"status": 404, "error": "Not Found"}


class VerifyDemoHttpTest(unittest.TestCase):
    def setUp(self) -> None:
        self.artifacts = Path(tempfile.mkdtemp(prefix="mea-demo-verify-", dir="/tmp"))

    def run_script(self, mode: str, name: str, negative_status: int = 400) -> tuple[subprocess.CompletedProcess[str], dict, Path]:
        _StubHandler.mode = mode
        _StubHandler.negative_status = negative_status
        _StubHandler.manager_requests = []
        server = ThreadingHTTPServer(("127.0.0.1", 0), _StubHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        output = self.artifacts / name
        output.mkdir()
        try:
            process = subprocess.run(
                ["python3", str(SCRIPT), "--task-id", TASK_ID, "--output", str(output)],
                env={**os.environ, "RD_BOT_BASE_URL": f"http://127.0.0.1:{server.server_port}"},
                capture_output=True,
                text=True,
                check=False,
            )
        finally:
            server.shutdown()
            thread.join(timeout=2)
            server.server_close()
        (output / "run.log").write_text(process.stdout + process.stderr, encoding="utf-8")
        summary = json.loads((output / "verify-demo-summary.json").read_text(encoding="utf-8"))
        return process, summary, output

    def test_dual_task_all_four_real_negative_checks_pass(self) -> None:
        process, summary, _ = self.run_script("all", "dual-task")
        self.assertEqual(process.returncode, 0, process.stdout)
        self.assertEqual(summary["result"], "PASS")
        self.assertEqual(summary["unverifiedCount"], 0)
        self.assertEqual(len(_StubHandler.manager_requests), 2)
        self.assertIn("cross-task coding-mea with real stageRunId rejected (404)", process.stdout)
        self.assertIn("real stage without full result: /result/content is explicit 404", process.stdout)

    def test_task_list_page_can_omit_selected_task(self) -> None:
        process, summary, _ = self.run_script("page-misses-task", "page-misses-task")
        self.assertEqual(process.returncode, 0, process.stdout)
        self.assertEqual(summary["result"], "PASS")

    def test_single_task_records_explicit_skips(self) -> None:
        process, summary, _ = self.run_script("single", "single-task")
        self.assertEqual(process.returncode, 0, process.stdout)
        self.assertEqual(summary["result"], "PASS")
        self.assertEqual(summary["unverifiedCount"], 6)
        self.assertEqual(process.stdout.count("[SKIP]"), 6)

    def test_qa_only_decision_skips_bounded_coding_contract(self) -> None:
        process, summary, _ = self.run_script("qa-only", "qa-only")
        self.assertEqual(process.returncode, 0, process.stdout)
        self.assertEqual(summary["result"], "PASS")
        self.assertTrue(any("bounded Coding EXECUTE" in entry["item"] for entry in summary["unverified"]))
        self.assertEqual(len(_StubHandler.manager_requests), 1)

    def test_wrong_identity_fails_real_cross_task_checks(self) -> None:
        process, summary, _ = self.run_script("wrongidentity", "wrong-identity")
        self.assertNotEqual(process.returncode, 0)
        self.assertEqual(summary["result"], "FAIL")
        self.assertIn("cross-task coding-mea with real stageRunId rejected (404)", process.stdout)

    def test_positive_payload_identity_mismatch_fails(self) -> None:
        process, summary, _ = self.run_script("wrong-positive-identity", "wrong-positive-identity")
        self.assertNotEqual(process.returncode, 0)
        self.assertEqual(summary["result"], "FAIL")

    def test_valid_stage_result_with_http_404_cannot_pass(self) -> None:
        process, summary, _ = self.run_script("wrong-status", "wrong-positive-status")
        self.assertNotEqual(process.returncode, 0)
        self.assertEqual(summary["result"], "FAIL")

    def test_wrong_negative_statuses_cannot_pass(self) -> None:
        for status in (400, 401, 403):
            with self.subTest(status=status):
                process, summary, _ = self.run_script("wrong-negative", f"wrong-negative-{status}", status)
                self.assertNotEqual(process.returncode, 0)
                self.assertEqual(summary["result"], "FAIL")

    def test_discovery_http_failure_is_not_reported_as_zero_tasks(self) -> None:
        process, summary, _ = self.run_script("discovery-error", "discovery-error")
        self.assertEqual(process.returncode, 0, process.stdout)
        self.assertTrue(any("任务列表接口不可用" in entry["reason"] for entry in summary["unverified"]))
        self.assertFalse(any("只能看到 0 个 REQUIREMENT 任务" in entry["reason"] for entry in summary["unverified"]))


if __name__ == "__main__":
    unittest.main(verbosity=2)
