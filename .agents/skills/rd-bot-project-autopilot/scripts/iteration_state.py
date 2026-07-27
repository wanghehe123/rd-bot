from __future__ import annotations

import copy
import hashlib
import json
import os
import re
import tempfile
from datetime import UTC, datetime
from enum import Enum
from pathlib import Path
from typing import Any


MAX_ITERATIONS = 2
MAX_ACTIVE_TASKS = 1
MAX_RETRIES_PER_TASK = 1
MAX_ELAPSED_MINUTES = 120
MAX_TOKEN_BUDGET = 200_000
RUN_ID_PATTERN = re.compile(r"^autopilot-[a-z0-9-]{1,80}$")
PROJECT_ID_PATTERN = re.compile(r"^[0-9]{1,64}$")


class ManifestError(ValueError):
    """The persisted run is invalid or a requested transition is unsafe."""


class RunStatus(str, Enum):
    DRAFT = "DRAFT"
    PLANNING = "PLANNING"
    READY = "READY"
    DRY_RUN_COMPLETED = "DRY_RUN_COMPLETED"
    DISPATCH_INTENT = "DISPATCH_INTENT"
    TASK_BOUND = "TASK_BOUND"
    SUBMIT_INTENT = "SUBMIT_INTENT"
    OBSERVING = "OBSERVING"
    RETRY_INTENT = "RETRY_INTENT"
    EVALUATION_INTENT = "EVALUATION_INTENT"
    EVALUATING = "EVALUATING"
    LEARNING = "LEARNING"
    WAITING_HUMAN = "WAITING_HUMAN"
    COMPLETED = "COMPLETED"
    BOUNDED_STOP = "BOUNDED_STOP"


ALLOWED_TRANSITIONS: dict[RunStatus, set[RunStatus]] = {
    RunStatus.DRAFT: {RunStatus.PLANNING},
    RunStatus.PLANNING: {RunStatus.READY, RunStatus.BOUNDED_STOP, RunStatus.WAITING_HUMAN},
    RunStatus.READY: {RunStatus.DRY_RUN_COMPLETED, RunStatus.DISPATCH_INTENT},
    RunStatus.DISPATCH_INTENT: {RunStatus.TASK_BOUND, RunStatus.WAITING_HUMAN},
    RunStatus.TASK_BOUND: {RunStatus.SUBMIT_INTENT},
    RunStatus.SUBMIT_INTENT: {RunStatus.OBSERVING, RunStatus.WAITING_HUMAN},
    RunStatus.OBSERVING: {
        RunStatus.RETRY_INTENT,
        RunStatus.EVALUATION_INTENT,
        RunStatus.WAITING_HUMAN,
        RunStatus.BOUNDED_STOP,
    },
    RunStatus.RETRY_INTENT: {RunStatus.OBSERVING, RunStatus.WAITING_HUMAN},
    RunStatus.EVALUATION_INTENT: {RunStatus.EVALUATING, RunStatus.WAITING_HUMAN},
    RunStatus.EVALUATING: {RunStatus.LEARNING, RunStatus.WAITING_HUMAN, RunStatus.BOUNDED_STOP},
    RunStatus.LEARNING: {
        RunStatus.PLANNING,
        RunStatus.COMPLETED,
        RunStatus.BOUNDED_STOP,
        RunStatus.WAITING_HUMAN,
    },
}

TERMINAL_TASK_STATES = {
    "COMPLETED",
    "MERGED",
    "REJECTED",
    "FAILED_RETRYABLE",
    "FAILED_NEEDS_HUMAN",
    "CANCELLED",
    "DEAD_LETTERED",
    "DELETED",
}


def utc_now() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def request_fingerprint(value: Any) -> str:
    canonical = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def create_manifest(
    run_id: str,
    mode: str,
    project_id: str,
    idea: str,
    success_criteria: list[str],
) -> dict[str, Any]:
    if not RUN_ID_PATTERN.fullmatch(run_id):
        raise ManifestError("run_id must match autopilot-<lowercase-slug>")
    if mode not in {"dry-run", "live-test"}:
        raise ManifestError("mode must be dry-run or live-test")
    if not PROJECT_ID_PATTERN.fullmatch(str(project_id)):
        raise ManifestError("project_id must be numeric")
    clean_idea = str(idea).strip()
    clean_criteria = [str(item).strip() for item in success_criteria if str(item).strip()]
    if not clean_idea:
        raise ManifestError("idea must not be empty")
    if not clean_criteria:
        raise ManifestError("success_criteria must contain one item")
    now = utc_now()
    return {
        "schemaVersion": "rd-bot-autopilot/v1",
        "runId": run_id,
        "mode": mode,
        "projectId": str(project_id),
        "idea": clean_idea,
        "successCriteria": clean_criteria[:10],
        "status": RunStatus.DRAFT.value,
        "limits": {
            "maxIterations": MAX_ITERATIONS,
            "maxActiveTasks": MAX_ACTIVE_TASKS,
            "maxRetriesPerTask": MAX_RETRIES_PER_TASK,
            "maxElapsedMinutes": MAX_ELAPSED_MINUTES,
            "maxTokenBudget": MAX_TOKEN_BUDGET,
        },
        "usage": {
            "iterations": 0,
            "tasksCreated": 0,
            "retries": 0,
            "elapsedMinutes": 0,
            "observedTokens": 0,
        },
        "iterations": [],
        "pendingApproval": None,
        "stopReason": "",
        "lastTransitionReason": "created",
        "workspaceBefore": None,
        "workspaceAfter": None,
        "requestLedger": [],
        "createdAt": now,
        "updatedAt": now,
    }


def _active_task_count(manifest: dict[str, Any]) -> int:
    count = 0
    for iteration in manifest.get("iterations", []):
        if iteration.get("taskId") and iteration.get("taskStatus") not in TERMINAL_TASK_STATES:
            count += 1
    return count


def _iteration(manifest: dict[str, Any], iteration_no: int) -> dict[str, Any]:
    for item in manifest.get("iterations", []):
        if item.get("iterationNo") == iteration_no:
            return item
    raise ManifestError(f"iteration {iteration_no} does not exist")


def _validate_manifest(manifest: dict[str, Any]) -> None:
    if manifest.get("schemaVersion") != "rd-bot-autopilot/v1":
        raise ManifestError("unsupported manifest schemaVersion")
    if not RUN_ID_PATTERN.fullmatch(str(manifest.get("runId", ""))):
        raise ManifestError("invalid runId")
    if manifest.get("mode") not in {"dry-run", "live-test"}:
        raise ManifestError("invalid mode")
    if not PROJECT_ID_PATTERN.fullmatch(str(manifest.get("projectId", ""))):
        raise ManifestError("invalid projectId")
    try:
        status = RunStatus(str(manifest["status"]))
    except (KeyError, ValueError) as exc:
        raise ManifestError("unknown manifest status") from exc
    limits = manifest.get("limits")
    if not isinstance(limits, dict):
        raise ManifestError("limits must be an object")
    if limits.get("maxIterations", 0) > MAX_ITERATIONS or limits.get("maxActiveTasks") != 1:
        raise ManifestError("hard iteration limits exceeded")
    if limits.get("maxRetriesPerTask", 0) > MAX_RETRIES_PER_TASK:
        raise ManifestError("hard retry limit exceeded")
    if limits.get("maxElapsedMinutes", 0) > MAX_ELAPSED_MINUTES:
        raise ManifestError("hard elapsed limit exceeded")
    if limits.get("maxTokenBudget", 0) > MAX_TOKEN_BUDGET:
        raise ManifestError("hard token limit exceeded")
    iterations = manifest.get("iterations")
    if not isinstance(iterations, list) or len(iterations) > MAX_ITERATIONS:
        raise ManifestError("iterations exceed hard limit")
    task_ids: set[str] = set()
    for item in iterations:
        if not isinstance(item, dict):
            raise ManifestError("iteration must be an object")
        task_id = item.get("taskId")
        if task_id:
            if not PROJECT_ID_PATTERN.fullmatch(str(task_id)):
                raise ManifestError("taskId must be numeric")
            if task_id in task_ids:
                raise ManifestError("duplicate taskId")
            task_ids.add(task_id)
        request = item.get("request")
        fingerprint = item.get("requestFingerprint", "")
        if request is not None and fingerprint != request_fingerprint(request):
            raise ManifestError("request fingerprint does not match request")
        retries = item.get("retryCount", 0)
        if not isinstance(retries, int) or retries < 0 or retries > MAX_RETRIES_PER_TASK:
            raise ManifestError("invalid retryCount")
    if status == RunStatus.DRY_RUN_COMPLETED and manifest.get("mode") != "dry-run":
        raise ManifestError("live-test cannot be dry-run completed")
    if _active_task_count(manifest) > MAX_ACTIVE_TASKS:
        raise ManifestError("more than one active task")


class ManifestStore:
    def __init__(self, run_dir: Path) -> None:
        self.run_dir = Path(run_dir).resolve()
        self.path = self.run_dir / "manifest.json"

    def initialize(self, manifest: dict[str, Any]) -> None:
        if self.path.exists():
            raise ManifestError(f"manifest already exists: {self.path}")
        self.run_dir.mkdir(parents=True, exist_ok=True)
        candidate = copy.deepcopy(manifest)
        _validate_manifest(candidate)
        self._write(candidate)

    def load(self) -> dict[str, Any]:
        try:
            manifest = json.loads(self.path.read_text(encoding="utf-8"))
        except FileNotFoundError as exc:
            raise ManifestError(f"manifest does not exist: {self.path}") from exc
        except json.JSONDecodeError as exc:
            raise ManifestError(f"manifest is not valid JSON: {self.path}") from exc
        if not isinstance(manifest, dict):
            raise ManifestError("manifest root must be an object")
        _validate_manifest(manifest)
        return manifest

    def transition(self, target: RunStatus, reason: str) -> dict[str, Any]:
        manifest = self.load()
        current = RunStatus(manifest["status"])
        if target not in ALLOWED_TRANSITIONS.get(current, set()):
            raise ManifestError(f"{current.value} -> {target.value} is not allowed: {reason}")
        manifest["status"] = target.value
        manifest["lastTransitionReason"] = str(reason).strip()[:1000]
        if target in {RunStatus.WAITING_HUMAN, RunStatus.BOUNDED_STOP, RunStatus.COMPLETED}:
            manifest["stopReason"] = str(reason).strip()[:1000]
        self._write(manifest)
        return manifest

    def freeze_plan(self, iteration_no: int, plan: dict[str, Any]) -> dict[str, Any]:
        manifest = self.load()
        if _active_task_count(manifest) >= MAX_ACTIVE_TASKS:
            raise ManifestError("cannot plan another iteration while an active task exists")
        current = RunStatus(manifest["status"])
        if current not in {RunStatus.PLANNING, RunStatus.LEARNING}:
            raise ManifestError(f"cannot freeze plan from {current.value}")
        if iteration_no != len(manifest["iterations"]) + 1 or iteration_no > MAX_ITERATIONS:
            raise ManifestError("iteration number exceeds hard limit or is not sequential")
        title = str(plan.get("title", "")).strip()
        criteria = [str(item).strip() for item in plan.get("acceptanceCriteria", []) if str(item).strip()]
        if not title or not criteria or len(criteria) > 6:
            raise ManifestError("plan needs a title and one to six acceptance criteria")
        normalized = copy.deepcopy(plan)
        normalized["title"] = title
        normalized["acceptanceCriteria"] = criteria
        marker = f"[autopilot:{manifest['runId']}:{iteration_no}]"
        item = {
            "iterationNo": iteration_no,
            "phase": "READY",
            "plan": normalized,
            "planHash": request_fingerprint(normalized),
            "marker": marker,
            "exactTitle": f"{marker} {title}",
            "request": None,
            "requestFingerprint": "",
            "taskId": None,
            "taskStatus": None,
            "submitted": False,
            "retryCount": 0,
            "stageRuns": [],
            "observations": [],
            "evaluationRunId": None,
            "evaluation": None,
            "decision": None,
            "createdAt": utc_now(),
            "updatedAt": utc_now(),
        }
        manifest["iterations"].append(item)
        manifest["usage"]["iterations"] = iteration_no
        manifest["status"] = RunStatus.READY.value
        manifest["lastTransitionReason"] = "plan frozen"
        self._write(manifest)
        return manifest

    def record_dispatch_intent(self, iteration_no: int, request: dict[str, Any] | None = None) -> dict[str, Any]:
        manifest = self.load()
        if RunStatus(manifest["status"]) != RunStatus.READY:
            raise ManifestError("dispatch intent requires READY status")
        item = _iteration(manifest, iteration_no)
        if request is None:
            plan = item["plan"]
            request = {
                "title": item["exactTitle"],
                "priority": "P2",
                "projectId": manifest["projectId"],
                "expectedResult": str(plan.get("expectedResult") or plan.get("goal") or item["exactTitle"]),
                "acceptanceCriteria": plan["acceptanceCriteria"],
                "materials": plan.get("materials", []),
                "autoExecute": False,
                "tokenBudgetOverride": 0,
            }
        request = copy.deepcopy(request)
        request["autoExecute"] = False
        item["request"] = request
        item["requestFingerprint"] = request_fingerprint(request)
        item["phase"] = "DISPATCH_INTENT"
        item["dispatchIntentAt"] = utc_now()
        manifest["status"] = RunStatus.DISPATCH_INTENT.value
        manifest["lastTransitionReason"] = "dispatch intent persisted before create POST"
        self._write(manifest)
        return item

    def bind_task(self, iteration_no: int, task_id: str, reconciled: bool = False) -> dict[str, Any]:
        manifest = self.load()
        if RunStatus(manifest["status"]) != RunStatus.DISPATCH_INTENT:
            raise ManifestError("task binding requires DISPATCH_INTENT status")
        if not PROJECT_ID_PATTERN.fullmatch(str(task_id)):
            raise ManifestError("task_id must be numeric")
        item = _iteration(manifest, iteration_no)
        item["taskId"] = str(task_id)
        item["phase"] = "TASK_BOUND"
        item["reconciled"] = bool(reconciled)
        item["boundAt"] = utc_now()
        manifest["usage"]["tasksCreated"] += 1 if not reconciled else 0
        manifest["status"] = RunStatus.TASK_BOUND.value
        manifest["lastTransitionReason"] = "task bound" if not reconciled else "task reconciled after ambiguous create"
        self._write(manifest)
        return item

    def record_submit_intent(self, iteration_no: int) -> dict[str, Any]:
        manifest = self.load()
        if RunStatus(manifest["status"]) != RunStatus.TASK_BOUND:
            raise ManifestError("submit intent requires TASK_BOUND status")
        item = _iteration(manifest, iteration_no)
        if not item.get("taskId"):
            raise ManifestError("submit intent requires taskId")
        item["phase"] = "SUBMIT_INTENT"
        item["submitIntentAt"] = utc_now()
        item["submitRequestFingerprint"] = request_fingerprint({"taskId": item["taskId"], "action": "submit"})
        manifest["status"] = RunStatus.SUBMIT_INTENT.value
        manifest["lastTransitionReason"] = "submit intent persisted before submit POST"
        self._write(manifest)
        return item

    def mark_observing(self, iteration_no: int, reason: str) -> dict[str, Any]:
        manifest = self.load()
        current = RunStatus(manifest["status"])
        if current not in {RunStatus.SUBMIT_INTENT, RunStatus.RETRY_INTENT}:
            raise ManifestError(f"observing cannot start from {current.value}")
        item = _iteration(manifest, iteration_no)
        item["phase"] = "OBSERVING"
        item["observingAt"] = utc_now()
        manifest["status"] = RunStatus.OBSERVING.value
        manifest["lastTransitionReason"] = reason[:1000]
        self._write(manifest)
        return item

    def record_observation(self, iteration_no: int, observation: dict[str, Any]) -> dict[str, Any]:
        manifest = self.load()
        if RunStatus(manifest["status"]) != RunStatus.OBSERVING:
            raise ManifestError("observation requires OBSERVING status")
        item = _iteration(manifest, iteration_no)
        item["observations"].append(copy.deepcopy(observation))
        item["observations"] = item["observations"][-20:]
        if "taskStatus" in observation:
            item["taskStatus"] = observation["taskStatus"]
        if "stageRuns" in observation:
            item["stageRuns"] = copy.deepcopy(observation["stageRuns"])
        item["updatedAt"] = utc_now()
        usage_tokens = int(observation.get("observedTokens", 0) or 0)
        manifest["usage"]["observedTokens"] = max(manifest["usage"].get("observedTokens", 0), usage_tokens)
        self._write(manifest)
        return item

    def record_retry_intent(self, iteration_no: int, retry_preview: dict[str, Any]) -> dict[str, Any]:
        manifest = self.load()
        if RunStatus(manifest["status"]) != RunStatus.OBSERVING:
            raise ManifestError("retry intent requires OBSERVING status")
        item = _iteration(manifest, iteration_no)
        if item.get("retryCount", 0) >= MAX_RETRIES_PER_TASK:
            raise ManifestError("retry budget exhausted")
        item["retryCount"] += 1
        item["retryPreview"] = copy.deepcopy(retry_preview)
        item["retryOperatorNote"] = f"[autopilot:{manifest['runId']}:{iteration_no}:retry:{item['retryCount']}]"
        item["phase"] = "RETRY_INTENT"
        item["retryIntentAt"] = utc_now()
        manifest["usage"]["retries"] += 1
        manifest["status"] = RunStatus.RETRY_INTENT.value
        manifest["lastTransitionReason"] = "retry intent persisted before retry POST"
        self._write(manifest)
        return item

    def record_evaluation_intent(self, iteration_no: int) -> dict[str, Any]:
        manifest = self.load()
        if RunStatus(manifest["status"]) != RunStatus.OBSERVING:
            raise ManifestError("evaluation intent requires OBSERVING status")
        item = _iteration(manifest, iteration_no)
        if item.get("taskStatus") != "COMPLETED":
            raise ManifestError("evaluation requires COMPLETED task")
        item["evaluationNameMarker"] = f"{item['exactTitle']} evaluation"
        item["evaluationIntentAt"] = utc_now()
        item["phase"] = "EVALUATION_INTENT"
        manifest["status"] = RunStatus.EVALUATION_INTENT.value
        manifest["lastTransitionReason"] = "evaluation intent persisted before evaluation POST"
        self._write(manifest)
        return item

    def mark_evaluating(self, iteration_no: int, run_id: str) -> dict[str, Any]:
        manifest = self.load()
        if RunStatus(manifest["status"]) != RunStatus.EVALUATION_INTENT:
            raise ManifestError("evaluating requires EVALUATION_INTENT status")
        item = _iteration(manifest, iteration_no)
        item["evaluationRunId"] = str(run_id)
        item["phase"] = "EVALUATING"
        manifest["status"] = RunStatus.EVALUATING.value
        manifest["lastTransitionReason"] = "evaluation run bound"
        self._write(manifest)
        return item

    def record_evaluation(self, iteration_no: int, evaluation: dict[str, Any]) -> dict[str, Any]:
        manifest = self.load()
        if RunStatus(manifest["status"]) != RunStatus.EVALUATING:
            raise ManifestError("evaluation result requires EVALUATING status")
        item = _iteration(manifest, iteration_no)
        item["evaluation"] = copy.deepcopy(evaluation)
        item["phase"] = "LEARNING"
        manifest["status"] = RunStatus.LEARNING.value
        manifest["lastTransitionReason"] = "evaluation reached terminal state"
        self._write(manifest)
        return item

    def set_waiting_human(self, reason: str, approval: dict[str, Any]) -> dict[str, Any]:
        manifest = self.load()
        current = RunStatus(manifest["status"])
        if current in {RunStatus.COMPLETED, RunStatus.BOUNDED_STOP, RunStatus.DRY_RUN_COMPLETED}:
            raise ManifestError(f"cannot wait for human from {current.value}")
        if RunStatus.WAITING_HUMAN not in ALLOWED_TRANSITIONS.get(current, set()):
            raise ManifestError(f"{current.value} -> WAITING_HUMAN is not allowed")
        manifest["pendingApproval"] = copy.deepcopy(approval)
        manifest["status"] = RunStatus.WAITING_HUMAN.value
        manifest["stopReason"] = reason[:1000]
        manifest["lastTransitionReason"] = reason[:1000]
        self._write(manifest)
        return manifest

    def finish(self, status: RunStatus, reason: str) -> dict[str, Any]:
        manifest = self.load()
        if status not in {RunStatus.COMPLETED, RunStatus.BOUNDED_STOP, RunStatus.DRY_RUN_COMPLETED}:
            raise ManifestError("finish accepts only terminal run statuses")
        current = RunStatus(manifest["status"])
        if status not in ALLOWED_TRANSITIONS.get(current, set()):
            raise ManifestError(f"{current.value} -> {status.value} is not allowed")
        manifest["status"] = status.value
        manifest["stopReason"] = reason[:1000]
        manifest["lastTransitionReason"] = reason[:1000]
        self._write(manifest)
        return manifest

    def set_workspace_before(self, fingerprint: dict[str, Any]) -> dict[str, Any]:
        manifest = self.load()
        manifest["workspaceBefore"] = copy.deepcopy(fingerprint)
        self._write(manifest)
        return manifest

    def set_workspace_after(self, fingerprint: dict[str, Any]) -> dict[str, Any]:
        manifest = self.load()
        manifest["workspaceAfter"] = copy.deepcopy(fingerprint)
        self._write(manifest)
        return manifest

    def _write(self, manifest: dict[str, Any]) -> None:
        _validate_manifest(manifest)
        self.run_dir.mkdir(parents=True, exist_ok=True)
        manifest["updatedAt"] = utc_now()
        fd, temp_name = tempfile.mkstemp(prefix="manifest.", suffix=".tmp", dir=self.run_dir)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(manifest, handle, ensure_ascii=False, indent=2)
                handle.write("\n")
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp_name, self.path)
        finally:
            if os.path.exists(temp_name):
                os.unlink(temp_name)
