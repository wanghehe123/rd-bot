from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.iteration_state import ManifestStore, RunStatus, create_manifest
from scripts.rd_bot_client import ApiTransportError
from scripts.workflow import AutopilotWorkflow


PROJECT_ID = "7480000000000000000"
TASK_ID = "7480000000000000001"


def plan() -> dict[str, object]:
    return {
        "title": "Add a bounded status summary",
        "goal": "Give operators a compact status summary.",
        "nonGoals": ["No deployment"],
        "expectedResult": "The admin status summary is visible and tested.",
        "acceptanceCriteria": ["The endpoint returns 200", "The focused test passes"],
        "materials": [{"type": "TEXT", "name": "goal", "content": "bounded"}],
        "whyNow": "The test project needs one small iteration.",
    }


def detail(
    *,
    status: str = "CREATED",
    title: str | None = None,
    project_id: str = PROJECT_ID,
    task_id: str = TASK_ID,
) -> dict[str, object]:
    return {
        "taskId": task_id,
        "taskType": "REQUIREMENT",
        "title": title or "[autopilot:autopilot-test:1] Add a bounded status summary",
        "projectId": project_id,
        "expectedResult": "The admin status summary is visible and tested.",
        "acceptanceCriteria": ["The endpoint returns 200", "The focused test passes"],
        "tokenBudgetOverride": 0,
        "status": status,
    }


class SpyClient:
    def __init__(self, workflow: AutopilotWorkflow | None = None) -> None:
        self.workflow = workflow
        self.create_calls: list[dict[str, object]] = []
        self.submit_calls: list[str] = []
        self.tasks: list[dict[str, object]] = []
        self.detail = detail()
        self.submit_error: Exception | None = None
        self.create_error: Exception | None = None
        self.timeline: object = {"events": []}

    def create_requirement(self, payload: dict[str, object]) -> dict[str, object]:
        if self.workflow:
            on_disk = self.workflow.store.load()
            assert on_disk["status"] == RunStatus.DISPATCH_INTENT.value
        self.create_calls.append(payload)
        if self.create_error:
            raise self.create_error
        return {"taskId": TASK_ID, "status": "CREATED"}

    def list_tasks(self, project_id: str, keyword: str | None, page: int = 1, page_size: int = 100) -> dict[str, object]:
        return {"data": self.tasks}

    def get_task(self, task_id: str) -> dict[str, object]:
        return self.detail

    def submit_task(self, task_id: str) -> dict[str, object]:
        if self.workflow:
            on_disk = self.workflow.store.load()
            assert on_disk["status"] == RunStatus.SUBMIT_INTENT.value
        self.submit_calls.append(task_id)
        if self.submit_error:
            raise self.submit_error
        return {"taskId": task_id, "status": "EXECUTING"}

    def get_timeline(self, task_id: str) -> object:
        return self.timeline


class WorkflowDispatchTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.run_dir = Path(self.temp.name) / "run"
        self.store = ManifestStore(self.run_dir)
        self.store.initialize(create_manifest("autopilot-test", "live-test", PROJECT_ID, "status summary", ["test passes"]))
        self.store.transition(RunStatus.PLANNING, "charter ready")
        self.store.freeze_plan(1, plan())
        self.client = SpyClient()
        self.workflow = AutopilotWorkflow(self.store, self.client, sleep=lambda _seconds: None)
        self.client.workflow = self.workflow

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_dispatch_intent_is_on_disk_before_create_and_submit_once(self) -> None:
        result = self.workflow.dispatch(1)
        self.assertEqual(self.client.create_calls[0]["autoExecute"], False)
        self.assertEqual(self.client.submit_calls, [TASK_ID])
        self.assertEqual(result["status"], RunStatus.OBSERVING.value)

    def test_ambiguous_create_reconciles_exactly_one_without_resend(self) -> None:
        self.client.create_error = ApiTransportError("connection reset", ambiguous=True)
        self.client.tasks = [detail()]
        result = self.workflow.dispatch(1)
        self.assertEqual(len(self.client.create_calls), 1)
        self.assertEqual(self.client.submit_calls, [TASK_ID])
        self.assertEqual(result["iterations"][0]["reconciled"], True)

    def test_reconciliation_rejects_detail_mismatch(self) -> None:
        self.client.create_error = ApiTransportError("connection reset", ambiguous=True)
        self.client.tasks = [detail()]
        self.client.detail = detail(project_id="7480000000000000002")
        result = self.workflow.dispatch(1)
        self.assertEqual(result["status"], RunStatus.WAITING_HUMAN.value)
        self.assertEqual(self.client.submit_calls, [])
        self.assertEqual(result["pendingApproval"]["type"], "AMBIGUOUS_TASK_CREATION")

    def test_zero_or_multiple_reconciliation_matches_wait_for_human(self) -> None:
        self.client.create_error = ApiTransportError("connection reset", ambiguous=True)
        self.client.tasks = [detail(), detail(task_id="7480000000000000002")]
        result = self.workflow.dispatch(1)
        self.assertEqual(result["status"], RunStatus.WAITING_HUMAN.value)
        self.assertEqual(self.client.create_calls.__len__(), 1)
        self.assertEqual(self.client.submit_calls, [])

    def test_ambiguous_submit_continues_when_task_advanced(self) -> None:
        self.client.submit_error = ApiTransportError("connection reset", ambiguous=True)
        self.client.detail = detail(status="EXECUTING")
        result = self.workflow.dispatch(1)
        self.assertEqual(result["status"], RunStatus.OBSERVING.value)
        self.assertEqual(self.client.submit_calls, [TASK_ID])

    def test_ambiguous_submit_created_state_waits_without_resubmit(self) -> None:
        self.client.submit_error = ApiTransportError("connection reset", ambiguous=True)
        self.client.detail = detail(status="CREATED")
        result = self.workflow.dispatch(1)
        self.assertEqual(result["status"], RunStatus.WAITING_HUMAN.value)
        self.assertEqual(self.client.submit_calls, [TASK_ID])
        self.assertEqual(result["pendingApproval"]["type"], "AMBIGUOUS_TASK_SUBMISSION")

    def test_dry_run_never_calls_create(self) -> None:
        dry_store = ManifestStore(Path(self.temp.name) / "dry")
        dry_store.initialize(create_manifest("autopilot-dry", "dry-run", PROJECT_ID, "status summary", ["test passes"]))
        dry_store.transition(RunStatus.PLANNING, "charter ready")
        dry_store.freeze_plan(1, plan())
        client = SpyClient()
        result = AutopilotWorkflow(dry_store, client).dispatch(1)
        self.assertEqual(result["status"], RunStatus.DRY_RUN_COMPLETED.value)
        self.assertEqual(client.create_calls, [])


if __name__ == "__main__":
    unittest.main()
