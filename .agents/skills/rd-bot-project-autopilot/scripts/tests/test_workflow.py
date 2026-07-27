from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from scripts.iteration_state import ManifestStore, RunStatus, create_manifest
from scripts.rd_bot_client import ApiTransportError
from scripts.workflow import AutopilotWorkflow, WorkflowError


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
        "priority": "P2",
        "title": title or "[autopilot:autopilot-test:1] Add a bounded status summary",
        "projectId": project_id,
        "repositoryUrl": "https://github.com/example/project",
        "repoOwner": "example",
        "repoName": "project",
        "baseBranch": "main",
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
        self.execution_overview: object = {
            "stageRuns": [
                {"stageRunId": "old", "role": "CODING", "attemptNo": 1, "status": "SUCCEEDED"},
                {"stageRunId": "new", "role": "CODING", "attemptNo": 2, "status": "SUCCEEDED"},
            ]
        }
        self.execution_trace: object = {"events": [{"sequence": 1, "type": "MODEL_OUTPUT"}]}
        self.retry_preview: object = {
            "taskId": TASK_ID,
            "sourceTaskVersion": 4,
            "failurePhase": "AGENT_ROLE",
            "failedStageRunId": "stage-1",
            "failedRetrievalRunId": "",
            "failedAiReviewRunId": "",
        }
        self.retry_error: Exception | None = None
        self.retry_history: object = []
        self.evaluation_create_error: Exception | None = None
        self.evaluation_create_calls: list[dict[str, object]] = []
        self.evaluation_responses: list[dict[str, object]] = [
            {"runId": "eval-1", "status": "SUCCEEDED", "overallPassed": True, "sampleCount": 1,
             "passedSampleCount": 1, "failedSampleCount": 0, "metricsJson": "{}"}
        ]
        self.evaluation_list: object = []
        self.project: object = {
            "data": {
                "projectId": PROJECT_ID,
                "repositoryUrl": "https://github.com/example/project",
                "repoOwner": "example",
                "repoName": "project",
                "defaultBranch": "main",
                "enabled": True,
            }
        }

    def create_requirement(self, payload: dict[str, object]) -> dict[str, object]:
        if self.workflow:
            on_disk = self.workflow.store.load()
            assert on_disk["status"] == RunStatus.DISPATCH_INTENT.value
        self.create_calls.append(payload)
        if self.create_error:
            raise self.create_error
        return {"taskId": TASK_ID, "status": "CREATED"}

    def preflight_write(self) -> None:
        return None

    def preflight_requirement(self, _payload: dict[str, object]) -> None:
        return None

    def preflight_submit(self, _task_id: str) -> None:
        return None

    def preflight_retry(self, _task_id: str, _payload: dict[str, object]) -> None:
        return None

    def preflight_evaluation(self, _task_id: str, _payload: dict[str, object]) -> None:
        return None

    def list_tasks(self, project_id: str, keyword: str | None, page: int = 1, page_size: int = 100) -> dict[str, object]:
        return {"data": self.tasks}

    def get_task(self, task_id: str) -> dict[str, object]:
        return self.detail

    def get_project(self, project_id: str) -> dict[str, object]:
        return self.project  # type: ignore[return-value]

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

    def get_execution_overview(self, task_id: str) -> object:
        return self.execution_overview

    def get_execution_trace(self, task_id: str, stage_run_id: str) -> object:
        return self.execution_trace

    def get_retry_preview(self, task_id: str) -> object:
        return self.retry_preview

    def retry_task(self, task_id: str, payload: dict[str, object]) -> object:
        if self.retry_error:
            raise self.retry_error
        return {"taskId": task_id, "status": "STARTED"}

    def get_retry_history(self, task_id: str) -> object:
        return self.retry_history

    def create_task_evaluation(self, task_id: str, payload: dict[str, object]) -> object:
        self.evaluation_create_calls.append(payload)
        if self.evaluation_create_error:
            raise self.evaluation_create_error
        return {"runId": "eval-1", "status": "CREATED"}

    def list_evaluations(self, keyword: str | None = None, page: int = 1, page_size: int = 100) -> object:
        return self.evaluation_list

    def get_evaluation(self, run_id: str) -> object:
        response = self.evaluation_responses[0]
        if len(self.evaluation_responses) > 1:
            self.evaluation_responses.pop(0)
        return response


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

    def test_real_detail_acceptance_criteria_json_string_is_verified(self) -> None:
        self.client.detail = detail()
        self.client.detail["acceptanceCriteriaJson"] = json.dumps(self.client.detail.pop("acceptanceCriteria"), ensure_ascii=False)
        result = self.workflow.dispatch(1)
        self.assertEqual(result["status"], RunStatus.OBSERVING.value)
        self.assertEqual(self.client.submit_calls, [TASK_ID])

    def test_project_url_only_snapshot_is_valid_backend_identity(self) -> None:
        self.client.project = {"data": {
            "projectId": PROJECT_ID,
            "repositoryUrl": "https://github.com/example/project",
            "defaultBranch": "main",
            "enabled": True,
        }}
        result = self.workflow.dispatch(1)
        self.assertEqual(result["status"], RunStatus.OBSERVING.value)

    def test_project_snapshot_identity_mismatch_blocks_before_create(self) -> None:
        self.client.project = {"data": {
            "projectId": "7480000000000000002",
            "repositoryUrl": "https://github.com/example/project",
            "defaultBranch": "main",
            "enabled": True,
        }}
        with self.assertRaisesRegex(WorkflowError, "snapshot identity"):
            self.workflow.dispatch(1)
        self.assertEqual(self.client.create_calls, [])
        self.assertEqual(self.store.load()["status"], RunStatus.READY.value)

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


class WorkflowObservationTest(unittest.TestCase):
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
        self.workflow.dispatch(1)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_observation_keeps_attempt_history_and_selects_latest_role_stage(self) -> None:
        self.client.detail = detail(status="COMPLETED")
        result = self.workflow.observe(1)
        observation = result["iterations"][0]["observations"][-1]
        self.assertEqual(result["status"], RunStatus.OBSERVING.value)
        self.assertEqual(len(observation["stageRuns"]), 2)
        self.assertEqual(observation["latestStageByRole"]["CODING"]["stageRunId"], "new")
        self.assertEqual(observation["traceRefs"], ["new"])

    def test_human_and_terminal_statuses_stop_without_polling(self) -> None:
        for status, expected in (
            ("WAITING_APPROVAL", RunStatus.WAITING_HUMAN.value),
            ("FAILED_NEEDS_HUMAN", RunStatus.WAITING_HUMAN.value),
            ("REJECTED", RunStatus.WAITING_HUMAN.value),
            ("MERGED", RunStatus.WAITING_HUMAN.value),
            ("CANCELLED", RunStatus.BOUNDED_STOP.value),
            ("DEAD_LETTERED", RunStatus.BOUNDED_STOP.value),
            ("DELETED", RunStatus.BOUNDED_STOP.value),
        ):
            with self.subTest(status=status):
                isolated = self._new_observing()
                isolated.client.detail = detail(status=status)
                result = isolated.workflow.observe(1)
                self.assertEqual(result["status"], expected)

    def test_unknown_task_status_waits_for_human(self) -> None:
        self.client.detail = detail(status="ALIEN_STATE")
        result = self.workflow.observe(1)
        self.assertEqual(result["status"], RunStatus.WAITING_HUMAN.value)
        self.assertEqual(result["pendingApproval"]["type"], "UNKNOWN_TASK_STATUS")

    def test_deadline_stops_active_polling(self) -> None:
        now = [0.0]

        def sleep(seconds: float) -> None:
            now[0] += seconds

        isolated = self._new_observing(clock=lambda: now[0], sleep=sleep)
        isolated.client.detail = detail(status="EXECUTING")
        result = isolated.workflow.observe(1, deadline_epoch=0.5)
        self.assertEqual(result["status"], RunStatus.BOUNDED_STOP.value)

    def _new_observing(self, *, clock=None, sleep=None) -> "WorkflowObservationTest":
        other = object.__new__(WorkflowObservationTest)
        other.temp = tempfile.TemporaryDirectory()
        other.run_dir = Path(other.temp.name) / "run"
        other.store = ManifestStore(other.run_dir)
        other.store.initialize(create_manifest("autopilot-test", "live-test", PROJECT_ID, "status summary", ["test passes"]))
        other.store.transition(RunStatus.PLANNING, "charter ready")
        other.store.freeze_plan(1, plan())
        other.client = SpyClient()
        other.workflow = AutopilotWorkflow(other.store, other.client, clock=clock, sleep=sleep or (lambda _seconds: None))
        other.client.workflow = other.workflow
        other.workflow.dispatch(1)
        return other


class WorkflowRetryEvaluationTest(unittest.TestCase):
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
        self.workflow.dispatch(1)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_retry_requires_allowed_preview_and_persists_intent_before_post(self) -> None:
        self.client.detail = detail(status="FAILED_RETRYABLE")
        self.workflow.observe(1)
        result = self.workflow.retry(1)
        self.assertEqual(result["status"], RunStatus.OBSERVING.value)
        self.assertEqual(result["iterations"][0]["retryCount"], 1)

    def test_retry_ambiguous_history_zero_or_multiple_waits(self) -> None:
        self.client.detail = detail(status="FAILED_RETRYABLE")
        self.workflow.observe(1)
        self.client.retry_error = ApiTransportError("reset", ambiguous=True)
        self.client.retry_history = []
        result = self.workflow.retry(1)
        self.assertEqual(result["status"], RunStatus.WAITING_HUMAN.value)
        self.assertEqual(result["pendingApproval"]["type"], "AMBIGUOUS_TASK_RETRY")

    def test_completed_task_evaluation_is_intent_then_polled_to_learning(self) -> None:
        self.client.detail = detail(status="COMPLETED")
        self.workflow.observe(1)
        result = self.workflow.evaluate(1)
        self.assertEqual(result["status"], RunStatus.LEARNING.value)
        self.assertEqual(self.client.evaluation_create_calls[0], {
            "judgeProvider": "NONE", "judgeLimit": 0, "timeoutSeconds": 90, "baselineRunId": ""
        })
        self.assertEqual(result["iterations"][0]["evaluation"]["overallPassed"], True)



if __name__ == "__main__":
    unittest.main()
