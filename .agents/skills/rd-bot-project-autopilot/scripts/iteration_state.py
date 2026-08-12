from __future__ import annotations

import copy
import hashlib
import json
import os
import re
import tempfile
import threading
from contextlib import contextmanager
from datetime import UTC, datetime
from enum import Enum
from functools import wraps
from pathlib import Path
from typing import Any, Callable, Iterator, Mapping, TypeVar

from .run_artifacts import redact
from .provisioning import ProvisionPlanError, plan_digest, validate_provision_plan


MAX_ITERATIONS = 2
MAX_ACTIVE_TASKS = 1
MAX_RETRIES_PER_TASK = 1
MAX_ELAPSED_MINUTES = 120
MAX_TOKEN_BUDGET = 200_000
RUN_ID_PATTERN = re.compile(r"^autopilot-[a-z0-9-]{1,80}$")
PROJECT_ID_PATTERN = re.compile(r"^[0-9]{1,64}$")
_TOP_LEVEL_KEYS = {
    "schemaVersion", "runId", "mode", "projectId", "idea", "successCriteria", "status",
    "limits", "usage", "iterations", "pendingApproval", "stopReason", "lastTransitionReason",
    "workspaceBefore", "workspaceAfter", "workspaceVerified", "requestLedger", "createdAt", "updatedAt",
}
_LEGACY_V1_TOP_LEVEL_KEYS = _TOP_LEVEL_KEYS - {"workspaceVerified"}
_V2_TOP_LEVEL_KEYS = _TOP_LEVEL_KEYS | {"provisioning"}
_PLAN_KEYS = {
    "title", "goal", "nonGoals", "expectedResult", "acceptanceCriteria", "materials", "whyNow",
    "priority", "repositoryUrl", "repoOwner", "repoName", "baseBranch", "tokenBudgetOverride",
}


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
    PLAN_READY = "PLAN_READY"
    LIVE_CONFIRMED = "LIVE_CONFIRMED"
    GH_INTENT = "GH_INTENT"
    GH_BOUND = "GH_BOUND"
    KB_INTENT = "KB_INTENT"
    KB_BOUND = "KB_BOUND"
    SOURCES_INTENT = "SOURCES_INTENT"
    SOURCES_BOUND = "SOURCES_BOUND"
    RD_PROJECT_INTENT = "RD_PROJECT_INTENT"
    RD_PROJECT_BOUND = "RD_PROJECT_BOUND"


ALLOWED_TRANSITIONS: dict[RunStatus, set[RunStatus]] = {
    RunStatus.DRAFT: {RunStatus.PLANNING, RunStatus.PLAN_READY, RunStatus.BOUNDED_STOP},
    RunStatus.PLANNING: {RunStatus.READY, RunStatus.BOUNDED_STOP, RunStatus.WAITING_HUMAN},
    RunStatus.READY: {RunStatus.DRY_RUN_COMPLETED, RunStatus.DISPATCH_INTENT, RunStatus.BOUNDED_STOP, RunStatus.WAITING_HUMAN},
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
        RunStatus.BOUNDED_STOP,
        RunStatus.WAITING_HUMAN,
    },
    RunStatus.PLAN_READY: {RunStatus.DRY_RUN_COMPLETED, RunStatus.LIVE_CONFIRMED, RunStatus.WAITING_HUMAN, RunStatus.BOUNDED_STOP},
    RunStatus.LIVE_CONFIRMED: {RunStatus.GH_INTENT, RunStatus.WAITING_HUMAN, RunStatus.BOUNDED_STOP},
    RunStatus.GH_INTENT: {RunStatus.GH_BOUND, RunStatus.WAITING_HUMAN, RunStatus.BOUNDED_STOP},
    RunStatus.GH_BOUND: {RunStatus.KB_INTENT, RunStatus.WAITING_HUMAN, RunStatus.BOUNDED_STOP},
    RunStatus.KB_INTENT: {RunStatus.KB_BOUND, RunStatus.WAITING_HUMAN, RunStatus.BOUNDED_STOP},
    RunStatus.KB_BOUND: {RunStatus.SOURCES_INTENT, RunStatus.WAITING_HUMAN, RunStatus.BOUNDED_STOP},
    RunStatus.SOURCES_INTENT: {RunStatus.SOURCES_BOUND, RunStatus.WAITING_HUMAN, RunStatus.BOUNDED_STOP},
    RunStatus.SOURCES_BOUND: {RunStatus.RD_PROJECT_INTENT, RunStatus.WAITING_HUMAN, RunStatus.BOUNDED_STOP},
    RunStatus.RD_PROJECT_INTENT: {RunStatus.RD_PROJECT_BOUND, RunStatus.WAITING_HUMAN, RunStatus.BOUNDED_STOP},
    RunStatus.RD_PROJECT_BOUND: {RunStatus.READY, RunStatus.WAITING_HUMAN, RunStatus.BOUNDED_STOP},
    RunStatus.WAITING_HUMAN: {
        RunStatus.PLANNING,
        RunStatus.READY,
        RunStatus.DISPATCH_INTENT,
        RunStatus.TASK_BOUND,
        RunStatus.SUBMIT_INTENT,
        RunStatus.OBSERVING,
        RunStatus.RETRY_INTENT,
        RunStatus.EVALUATION_INTENT,
        RunStatus.EVALUATING,
        RunStatus.LEARNING,
        RunStatus.PLAN_READY,
        RunStatus.LIVE_CONFIRMED,
        RunStatus.GH_INTENT,
        RunStatus.GH_BOUND,
        RunStatus.KB_INTENT,
        RunStatus.KB_BOUND,
        RunStatus.SOURCES_INTENT,
        RunStatus.SOURCES_BOUND,
        RunStatus.RD_PROJECT_INTENT,
        RunStatus.RD_PROJECT_BOUND,
        RunStatus.BOUNDED_STOP,
    },
}

_PROVISION_STATUS_SET = {
    RunStatus.PLAN_READY,
    RunStatus.LIVE_CONFIRMED,
    RunStatus.GH_INTENT,
    RunStatus.GH_BOUND,
    RunStatus.KB_INTENT,
    RunStatus.KB_BOUND,
    RunStatus.SOURCES_INTENT,
    RunStatus.SOURCES_BOUND,
    RunStatus.RD_PROJECT_INTENT,
    RunStatus.RD_PROJECT_BOUND,
}
_PROVISION_INTENT_STATES = {
    "github": (RunStatus.LIVE_CONFIRMED, RunStatus.GH_INTENT),
    "knowledgeBase": (RunStatus.GH_BOUND, RunStatus.KB_INTENT),
    "sources": (RunStatus.KB_BOUND, RunStatus.SOURCES_INTENT),
    "project": (RunStatus.SOURCES_BOUND, RunStatus.RD_PROJECT_INTENT),
}
_PROVISION_BOUND_STATES = {
    "github": (RunStatus.GH_INTENT, RunStatus.GH_BOUND),
    "knowledgeBase": (RunStatus.KB_INTENT, RunStatus.KB_BOUND),
    "sources": (RunStatus.SOURCES_INTENT, RunStatus.SOURCES_BOUND),
    "project": (RunStatus.RD_PROJECT_INTENT, RunStatus.RD_PROJECT_BOUND),
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


def _safe_text(value: Any, maximum: int = 2_000) -> str:
    if not isinstance(value, str):
        value = str(value or "")
    return str(redact(value.strip(), max_chars=maximum))


def _safe_material(material: Any) -> dict[str, Any]:
    if not isinstance(material, dict):
        return {"type": "TEXT", "name": "material", "content": _safe_text(material, 1_000)}
    content = material.get("content", "")
    content_text = content if isinstance(content, str) else str(content or "")
    safe = {
        "type": _safe_text(material.get("type") or material.get("materialType") or "TEXT", 80),
        "name": _safe_text(material.get("name") or material.get("title") or "material", 200),
        "content": _safe_text(content_text, 1_000),
        "contentSha256": hashlib.sha256(content_text.encode("utf-8")).hexdigest(),
        "contentChars": len(content_text),
    }
    for key in ("sourceType", "sourceUri", "mimeType", "revisionId"):
        if key in material and material[key] not in (None, ""):
            safe[key] = _safe_text(material[key], {"sourceType": 80, "sourceUri": 2_000, "mimeType": 200, "revisionId": 200}[key])
    return safe


def _safe_plan(plan: dict[str, Any]) -> dict[str, Any]:
    allowed: dict[str, Any] = {}
    for key in ("title", "goal", "expectedResult", "whyNow"):
        if key in plan:
            allowed[key] = _safe_text(plan[key])
    if isinstance(plan.get("nonGoals"), list):
        allowed["nonGoals"] = [_safe_text(item, 500) for item in plan["nonGoals"][:10]]
    criteria = plan.get("acceptanceCriteria")
    if isinstance(criteria, list):
        allowed["acceptanceCriteria"] = [_safe_text(item, 1_000) for item in criteria[:6]]
    materials = plan.get("materials", [])
    if isinstance(materials, list):
        allowed["materials"] = [_safe_material(item) for item in materials[:20]]
    for key in ("priority", "repositoryUrl", "repoOwner", "repoName", "baseBranch", "tokenBudgetOverride"):
        if key in plan:
            value = plan[key]
            maximum = {
                "priority": 2,
                "repositoryUrl": 2_000,
                "repoOwner": 200,
                "repoName": 200,
                "baseBranch": 200,
            }.get(key)
            allowed[key] = value if key == "tokenBudgetOverride" else _safe_text(value, maximum or 500)
    return allowed


def _safe_request(request: dict[str, Any]) -> dict[str, Any]:
    safe: dict[str, Any] = {}
    for key in ("title", "priority", "projectId", "repositoryUrl", "repoOwner", "repoName", "baseBranch", "expectedResult"):
        if key in request:
            maximum = {
                "title": 120,
                "priority": 2,
                "repositoryUrl": 2_000,
                "repoOwner": 200,
                "repoName": 200,
                "baseBranch": 200,
                "expectedResult": 20_000,
            }.get(key)
            safe[key] = request[key] if key == "projectId" else _safe_text(request[key], maximum or 500)
    criteria = request.get("acceptanceCriteria")
    if isinstance(criteria, list):
        safe["acceptanceCriteria"] = [_safe_text(item, 1_000) for item in criteria[:6]]
    materials = request.get("materials", [])
    if isinstance(materials, list):
        safe["materials"] = [_safe_request_material(item) for item in materials[:20]]
    safe["autoExecute"] = False
    budget = request.get("tokenBudgetOverride", 0)
    safe["tokenBudgetOverride"] = budget if isinstance(budget, int) and not isinstance(budget, bool) and budget >= 0 else 0
    return safe


def _safe_request_material(material: Any) -> dict[str, Any]:
    source = material if isinstance(material, dict) else {}
    content = source.get("content", "")
    return {
        "materialType": _safe_text(source.get("materialType") or source.get("type") or "REQUIREMENT_DOC", 80),
        "sourceType": _safe_text(source.get("sourceType") or "MANUAL_TEXT", 80),
        "title": _safe_text(source.get("title") or source.get("name") or "autopilot material", 500),
        "sourceUri": _safe_text(source.get("sourceUri") or "", 2_000),
        "content": _safe_text(content, 1_000),
        "mimeType": _safe_text(source.get("mimeType") or "text/plain", 200),
        "revisionId": _safe_text(source.get("revisionId") or "", 200),
        "recoveryStageRunId": _safe_text(source.get("recoveryStageRunId") or "", 120),
    }


def _safe_retry_preview(preview: dict[str, Any]) -> dict[str, Any]:
    safe: dict[str, Any] = {}
    task_id = str(preview.get("taskId", ""))
    if PROJECT_ID_PATTERN.fullmatch(task_id):
        safe["taskId"] = task_id
    version = _as_non_negative_int(preview.get("sourceTaskVersion"))
    if version:
        safe["sourceTaskVersion"] = version
    phase = str(preview.get("failurePhase", ""))
    if re.fullmatch(r"[A-Z_]{1,40}", phase):
        safe["failurePhase"] = phase
    for key in ("failedStageRunId", "failedRetrievalRunId", "failedAiReviewRunId"):
        value = str(preview.get(key, ""))
        safe[key] = value if re.fullmatch(r"[A-Za-z0-9._-]{0,120}", value) else ""
    return safe


def _elapsed_minutes(created_at: Any, now: datetime | None = None) -> int:
    if not isinstance(created_at, str) or not created_at:
        return 0
    try:
        start = datetime.fromisoformat(created_at.replace("Z", "+00:00"))
        current = now or datetime.now(UTC)
        return max(0, int((current - start).total_seconds() / 60.0 + 0.999999))
    except (TypeError, ValueError, OverflowError):
        return 0


def _as_non_negative_int(value: Any) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError, OverflowError):
        return 0
    return max(0, parsed)


_T = TypeVar("_T")


def _locked_mutation(function: Callable[..., _T]) -> Callable[..., _T]:
    @wraps(function)
    def wrapper(self: "ManifestStore", *args: Any, **kwargs: Any) -> _T:
        with self.lock():
            return function(self, *args, **kwargs)

    return wrapper


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
    clean_idea = _safe_text(idea, 4_000)
    clean_criteria = [_safe_text(item, 1_000) for item in success_criteria if str(item).strip()]
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
        "workspaceVerified": False,
        "requestLedger": [],
        "createdAt": now,
        "updatedAt": now,
    }


def create_provision_manifest(
    run_id: str,
    mode: str,
    idea: str,
    success_criteria: list[str],
) -> dict[str, Any]:
    """Create a v2 run before any GitHub or RD-Bot project exists."""

    if not RUN_ID_PATTERN.fullmatch(run_id):
        raise ManifestError("run_id must match autopilot-<lowercase-slug>")
    if mode not in {"dry-run", "live-provision"}:
        raise ManifestError("provision mode must be dry-run or live-provision")
    clean_idea = _safe_text(idea, 4_000)
    clean_criteria = [_safe_text(item, 1_000) for item in success_criteria if str(item).strip()]
    if not clean_idea:
        raise ManifestError("idea must not be empty")
    if not clean_criteria:
        raise ManifestError("success_criteria must contain one item")
    now = utc_now()
    return {
        "schemaVersion": "rd-bot-autopilot/v2",
        "runId": run_id,
        "mode": mode,
        "projectId": "",
        "idea": clean_idea,
        "successCriteria": clean_criteria[:6],
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
        "workspaceVerified": False,
        "requestLedger": [],
        "provisioning": {
            "plan": None,
            "planSha256": "",
            "confirmation": None,
            "intents": {},
            "resources": {
                "github": None,
                "knowledgeBase": None,
                "sources": [],
                "project": None,
            },
        },
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
    if not isinstance(manifest, dict):
        raise ManifestError("manifest root must be an object")
    schema_version = manifest.get("schemaVersion")
    if schema_version == "rd-bot-autopilot/v2":
        _validate_v2_manifest(manifest)
        return
    _validate_v1_manifest(manifest)


def _validate_v1_manifest(manifest: dict[str, Any]) -> None:
    if set(manifest) != _TOP_LEVEL_KEYS:
        raise ManifestError("manifest top-level fields are incomplete or unknown")
    if manifest.get("schemaVersion") != "rd-bot-autopilot/v1":
        raise ManifestError("unsupported manifest schemaVersion")
    if not RUN_ID_PATTERN.fullmatch(str(manifest.get("runId", ""))):
        raise ManifestError("invalid runId")
    if manifest.get("mode") not in {"dry-run", "live-test"}:
        raise ManifestError("invalid mode")
    if not PROJECT_ID_PATTERN.fullmatch(str(manifest.get("projectId", ""))):
        raise ManifestError("invalid projectId")
    if not isinstance(manifest.get("idea"), str) or not 1 <= len(manifest["idea"]) <= 4_000:
        raise ManifestError("idea is invalid")
    for key in ("createdAt", "updatedAt"):
        value = manifest.get(key)
        if not isinstance(value, str) or not 1 <= len(value) <= 64:
            raise ManifestError(f"{key} is invalid")
        try:
            datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise ManifestError(f"{key} is not an ISO timestamp") from exc
    criteria = manifest.get("successCriteria")
    if not isinstance(criteria, list) or not 1 <= len(criteria) <= 10 or any(
        not isinstance(item, str) or not item.strip() or len(item) > 1_000 for item in criteria
    ):
        raise ManifestError("successCriteria is invalid")
    try:
        status = RunStatus(str(manifest["status"]))
    except (KeyError, ValueError) as exc:
        raise ManifestError("unknown manifest status") from exc
    limits = manifest.get("limits")
    if not isinstance(limits, dict):
        raise ManifestError("limits must be an object")
    if isinstance(limits.get("maxIterations"), bool) or not isinstance(limits.get("maxIterations"), int) or not 1 <= limits["maxIterations"] <= MAX_ITERATIONS or limits.get("maxActiveTasks") != 1:
        raise ManifestError("hard iteration limits exceeded")
    if isinstance(limits.get("maxRetriesPerTask"), bool) or not isinstance(limits.get("maxRetriesPerTask"), int) or not 0 <= limits["maxRetriesPerTask"] <= MAX_RETRIES_PER_TASK:
        raise ManifestError("hard retry limit exceeded")
    if isinstance(limits.get("maxElapsedMinutes"), bool) or not isinstance(limits.get("maxElapsedMinutes"), int) or not 1 <= limits["maxElapsedMinutes"] <= MAX_ELAPSED_MINUTES:
        raise ManifestError("hard elapsed limit exceeded")
    if isinstance(limits.get("maxTokenBudget"), bool) or not isinstance(limits.get("maxTokenBudget"), int) or not 1 <= limits["maxTokenBudget"] <= MAX_TOKEN_BUDGET:
        raise ManifestError("hard token limit exceeded")
    usage = manifest.get("usage")
    if not isinstance(usage, dict):
        raise ManifestError("usage must be an object")
    for key in ("iterations", "tasksCreated", "retries", "elapsedMinutes", "observedTokens"):
        value = usage.get(key)
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise ManifestError(f"usage.{key} must be a non-negative integer")
    if usage["iterations"] > limits["maxIterations"] or usage["tasksCreated"] > limits["maxIterations"] or usage["retries"] > limits["maxRetriesPerTask"] * limits["maxIterations"]:
        raise ManifestError("usage exceeds hard iteration limits")
    if usage["elapsedMinutes"] > limits["maxElapsedMinutes"] or usage["observedTokens"] > limits["maxTokenBudget"]:
        raise ManifestError("usage exceeds hard budget")
    if "budgetExceeded" in usage and not isinstance(usage["budgetExceeded"], bool):
        raise ManifestError("usage.budgetExceeded must be boolean")
    for key in ("stopReason", "lastTransitionReason"):
        if not isinstance(manifest.get(key), str) or len(manifest[key]) > 1_000:
            raise ManifestError(f"{key} is invalid")
    ledger = manifest.get("requestLedger")
    if not isinstance(ledger, list) or len(ledger) > 500:
        raise ManifestError("requestLedger is unbounded")
    for entry in ledger:
        if not isinstance(entry, dict) or set(entry) != {"method", "path", "requestSha256", "status", "timestamp"}:
            raise ManifestError("requestLedger entry is invalid")
        if any(not isinstance(entry[key], str) or len(entry[key]) > 2_000 for key in ("method", "path", "requestSha256", "timestamp")):
            raise ManifestError("requestLedger entry is unbounded")
        if not isinstance(entry["status"], int) or isinstance(entry["status"], bool) or not 0 <= entry["status"] <= 599:
            raise ManifestError("requestLedger status is invalid")
    if not isinstance(manifest.get("workspaceVerified", False), bool):
        raise ManifestError("workspaceVerified must be boolean")
    for key in ("workspaceBefore", "workspaceAfter"):
        if manifest.get(key) is not None:
            _validate_fingerprint(manifest[key], key)
    if manifest.get("workspaceVerified") is True:
        if manifest.get("workspaceBefore") is None or manifest.get("workspaceAfter") is None or manifest["workspaceBefore"] != manifest["workspaceAfter"]:
            raise ManifestError("workspaceVerified requires equal before and after fingerprints")
    pending = manifest.get("pendingApproval")
    if pending is not None and (
        not isinstance(pending, dict) or len(json.dumps(pending, ensure_ascii=False)) > 20_000
    ):
        raise ManifestError("pendingApproval is unbounded")
    if isinstance(pending, dict):
        resume_status = pending.get("resumeStatus")
        if resume_status is not None:
            try:
                resume_target = RunStatus(str(resume_status))
            except ValueError as exc:
                raise ManifestError("pendingApproval resumeStatus is invalid") from exc
            if resume_target in {RunStatus.COMPLETED, RunStatus.BOUNDED_STOP, RunStatus.DRY_RUN_COMPLETED, RunStatus.WAITING_HUMAN} or resume_target not in ALLOWED_TRANSITIONS[RunStatus.WAITING_HUMAN]:
                raise ManifestError("pendingApproval resumeStatus is not resumable")
        resume_iteration = pending.get("resumeIteration")
        if resume_iteration is not None and (isinstance(resume_iteration, bool) or not isinstance(resume_iteration, int) or not 1 <= resume_iteration <= MAX_ITERATIONS):
            raise ManifestError("pendingApproval resumeIteration is invalid")
    iterations = manifest.get("iterations")
    if not isinstance(iterations, list) or len(iterations) > MAX_ITERATIONS:
        raise ManifestError("iterations exceed hard limit")
    task_ids: set[str] = set()
    required_fields_by_phase = {
        "DISPATCH_INTENT": ("request",),
        "TASK_BOUND": ("request", "taskId"),
        "SUBMIT_INTENT": ("request", "taskId"),
        "OBSERVING": ("request", "taskId"),
        "RETRY_INTENT": ("request", "taskId", "retryPreview", "retryOperatorNote"),
        "EVALUATION_INTENT": ("request", "taskId"),
        "EVALUATING": ("request", "taskId", "evaluationRunId"),
        "LEARNING": ("request", "taskId", "evaluationRunId", "evaluation"),
    }
    for item in iterations:
        if not isinstance(item, dict):
            raise ManifestError("iteration must be an object")
        phase = item.get("phase")
        if phase in required_fields_by_phase:
            for field in required_fields_by_phase[phase]:
                if item.get(field) in (None, ""):
                    raise ManifestError(f"iteration phase {phase} requires {field}")
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
        if request is not None:
            _validate_persisted_request(request, str(manifest.get("projectId", "")))
        plan = item.get("plan")
        if isinstance(plan, dict) and item.get("planHash") != request_fingerprint(plan):
            raise ManifestError("plan hash does not match plan")
        retries = item.get("retryCount", 0)
        if not isinstance(retries, int) or retries < 0 or retries > MAX_RETRIES_PER_TASK:
            raise ManifestError("invalid retryCount")
    if status == RunStatus.DRY_RUN_COMPLETED and manifest.get("mode") != "dry-run":
        raise ManifestError("live-test cannot be dry-run completed")
    if status == RunStatus.COMPLETED:
        if not iterations:
            raise ManifestError("completed run requires an iteration")
        latest = iterations[-1]
        evaluation = latest.get("evaluation") if isinstance(latest, dict) else None
        if latest.get("phase") != "LEARNING" or latest.get("evaluationRunId") in (None, "") or not isinstance(evaluation, dict) or evaluation.get("status") != "SUCCEEDED" or evaluation.get("overallPassed") is not True or manifest.get("workspaceVerified") is not True:
            raise ManifestError("completed run is missing evaluation or workspace verification")
    if _active_task_count(manifest) > MAX_ACTIVE_TASKS:
        raise ManifestError("more than one active task")


def _validate_v2_manifest(manifest: dict[str, Any]) -> None:
    if set(manifest) != _V2_TOP_LEVEL_KEYS:
        raise ManifestError("v2 manifest top-level fields are incomplete or unknown")
    if not RUN_ID_PATTERN.fullmatch(str(manifest.get("runId", ""))):
        raise ManifestError("invalid runId")
    if manifest.get("mode") not in {"dry-run", "live-provision"}:
        raise ManifestError("invalid provision mode")
    project_id = manifest.get("projectId")
    if not isinstance(project_id, str) or (project_id and not PROJECT_ID_PATTERN.fullmatch(project_id)):
        raise ManifestError("invalid provision projectId")
    if not isinstance(manifest.get("idea"), str) or not 1 <= len(manifest["idea"]) <= 4_000:
        raise ManifestError("idea is invalid")
    criteria = manifest.get("successCriteria")
    if not isinstance(criteria, list) or not 1 <= len(criteria) <= 6 or any(
        not isinstance(item, str) or not item.strip() or len(item) > 1_000 for item in criteria
    ):
        raise ManifestError("successCriteria is invalid")
    for key in ("createdAt", "updatedAt"):
        value = manifest.get(key)
        if not isinstance(value, str) or not 1 <= len(value) <= 64:
            raise ManifestError(f"{key} is invalid")
        try:
            datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise ManifestError(f"{key} is not an ISO timestamp") from exc
    try:
        status = RunStatus(str(manifest["status"]))
    except (KeyError, ValueError) as exc:
        raise ManifestError("unknown manifest status") from exc
    _validate_limits_and_usage(manifest)
    _validate_common_artifacts(manifest)

    provisioning = manifest.get("provisioning")
    if not isinstance(provisioning, dict) or set(provisioning) != {
        "plan", "planSha256", "confirmation", "intents", "resources",
    }:
        raise ManifestError("provisioning fields are incomplete or unknown")
    plan = provisioning.get("plan")
    plan_sha256 = provisioning.get("planSha256")
    if plan is None:
        if plan_sha256 != "":
            raise ManifestError("unfrozen provision plan cannot have a digest")
    else:
        try:
            clean_plan = validate_provision_plan(plan)
        except ProvisionPlanError as exc:
            raise ManifestError(f"invalid provision plan: {exc}") from exc
        if clean_plan != plan:
            raise ManifestError("provision plan is not canonical")
        if clean_plan.get("runId") != manifest["runId"] or clean_plan.get("idea") != manifest["idea"]:
            raise ManifestError("provision plan identity does not match manifest")
        if clean_plan.get("successCriteria") != criteria:
            raise ManifestError("provision plan criteria do not match manifest")
        if plan_sha256 != plan_digest(clean_plan) or not isinstance(plan_sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", plan_sha256):
            raise ManifestError("provision plan digest is invalid")
    confirmation = provisioning.get("confirmation")
    if confirmation is not None:
        if not isinstance(confirmation, dict) or set(confirmation) != {"planSha256", "confirmedAt"}:
            raise ManifestError("provision confirmation is invalid")
        if confirmation.get("planSha256") != plan_sha256:
            raise ManifestError("provision confirmation digest does not match plan")
        if not isinstance(confirmation.get("confirmedAt"), str):
            raise ManifestError("provision confirmation timestamp is invalid")
    intents = provisioning.get("intents")
    if not isinstance(intents, dict) or set(intents) - set(_PROVISION_INTENT_STATES):
        raise ManifestError("provision intents are invalid")
    for resource, intent in intents.items():
        if not isinstance(intent, dict) or set(intent) != {"request", "requestSha256", "createdAt"}:
            raise ManifestError("provision intent is invalid")
        request = intent.get("request")
        if not isinstance(request, (dict, list)) or len(json.dumps(request, ensure_ascii=False)) > 100_000:
            raise ManifestError("provision intent request is invalid")
        if intent.get("requestSha256") != request_fingerprint(request):
            raise ManifestError("provision intent fingerprint does not match request")
        if not isinstance(intent.get("createdAt"), str) or len(intent["createdAt"]) > 64:
            raise ManifestError("provision intent timestamp is invalid")
        if resource == "github" and isinstance(request, dict) and plan is not None and request != plan["repository"]:
            raise ManifestError("GitHub intent does not match the frozen plan")
        if resource == "knowledgeBase" and isinstance(request, dict) and plan is not None and request != plan["knowledgeBase"]:
            raise ManifestError("knowledge base intent does not match the frozen plan")
        if resource == "sources" and isinstance(request, list) and plan is not None and request != plan["documents"]:
            raise ManifestError("source intent does not match the frozen plan")
    resources = provisioning.get("resources")
    if not isinstance(resources, dict) or set(resources) != {"github", "knowledgeBase", "sources", "project"}:
        raise ManifestError("provision resources are invalid")
    _validate_v2_resources(resources, manifest["runId"])

    if status == RunStatus.DRAFT and plan is not None:
        raise ManifestError("DRAFT provision run cannot have a frozen plan")
    if status in _PROVISION_STATUS_SET | {RunStatus.READY, RunStatus.DRY_RUN_COMPLETED} and plan is None:
        raise ManifestError("provisioning state requires a frozen plan")
    if status in {
        RunStatus.LIVE_CONFIRMED, RunStatus.GH_INTENT, RunStatus.GH_BOUND, RunStatus.KB_INTENT,
        RunStatus.KB_BOUND, RunStatus.SOURCES_INTENT, RunStatus.SOURCES_BOUND,
        RunStatus.RD_PROJECT_INTENT, RunStatus.RD_PROJECT_BOUND,
    } and confirmation is None:
        raise ManifestError("live provisioning state requires exact plan confirmation")
    _validate_provision_status_resources(status, resources, project_id)
    _validate_v2_iterations(manifest, status)
    if status == RunStatus.DRY_RUN_COMPLETED and manifest.get("mode") != "dry-run":
        raise ManifestError("live provisioning cannot be dry-run completed")


def _validate_limits_and_usage(manifest: Mapping[str, Any]) -> None:
    limits = manifest.get("limits")
    if not isinstance(limits, dict):
        raise ManifestError("limits must be an object")
    if isinstance(limits.get("maxIterations"), bool) or not isinstance(limits.get("maxIterations"), int) or not 1 <= limits["maxIterations"] <= MAX_ITERATIONS or limits.get("maxActiveTasks") != 1:
        raise ManifestError("hard iteration limits exceeded")
    if isinstance(limits.get("maxRetriesPerTask"), bool) or not isinstance(limits.get("maxRetriesPerTask"), int) or not 0 <= limits["maxRetriesPerTask"] <= MAX_RETRIES_PER_TASK:
        raise ManifestError("hard retry limit exceeded")
    if isinstance(limits.get("maxElapsedMinutes"), bool) or not isinstance(limits.get("maxElapsedMinutes"), int) or not 1 <= limits["maxElapsedMinutes"] <= MAX_ELAPSED_MINUTES:
        raise ManifestError("hard elapsed limit exceeded")
    if isinstance(limits.get("maxTokenBudget"), bool) or not isinstance(limits.get("maxTokenBudget"), int) or not 1 <= limits["maxTokenBudget"] <= MAX_TOKEN_BUDGET:
        raise ManifestError("hard token limit exceeded")
    usage = manifest.get("usage")
    if not isinstance(usage, dict):
        raise ManifestError("usage must be an object")
    for key in ("iterations", "tasksCreated", "retries", "elapsedMinutes", "observedTokens"):
        value = usage.get(key)
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise ManifestError(f"usage.{key} must be a non-negative integer")
    if usage["iterations"] > limits["maxIterations"] or usage["tasksCreated"] > limits["maxIterations"] or usage["retries"] > limits["maxRetriesPerTask"] * limits["maxIterations"]:
        raise ManifestError("usage exceeds hard iteration limits")
    if usage["elapsedMinutes"] > limits["maxElapsedMinutes"] or usage["observedTokens"] > limits["maxTokenBudget"]:
        raise ManifestError("usage exceeds hard budget")
    if "budgetExceeded" in usage and not isinstance(usage["budgetExceeded"], bool):
        raise ManifestError("usage.budgetExceeded must be boolean")


def _validate_common_artifacts(manifest: Mapping[str, Any]) -> None:
    for key in ("stopReason", "lastTransitionReason"):
        if not isinstance(manifest.get(key), str) or len(manifest[key]) > 1_000:
            raise ManifestError(f"{key} is invalid")
    ledger = manifest.get("requestLedger")
    if not isinstance(ledger, list) or len(ledger) > 500:
        raise ManifestError("requestLedger is unbounded")
    for entry in ledger:
        if not isinstance(entry, dict) or set(entry) != {"method", "path", "requestSha256", "status", "timestamp"}:
            raise ManifestError("requestLedger entry is invalid")
        if any(not isinstance(entry[key], str) or len(entry[key]) > 2_000 for key in ("method", "path", "requestSha256", "timestamp")):
            raise ManifestError("requestLedger entry is unbounded")
        if not isinstance(entry["status"], int) or isinstance(entry["status"], bool) or not 0 <= entry["status"] <= 599:
            raise ManifestError("requestLedger status is invalid")
    if not isinstance(manifest.get("workspaceVerified"), bool):
        raise ManifestError("workspaceVerified must be boolean")
    for key in ("workspaceBefore", "workspaceAfter"):
        if manifest.get(key) is not None:
            _validate_fingerprint(manifest[key], key)
    if manifest.get("workspaceVerified") is True and manifest.get("workspaceBefore") != manifest.get("workspaceAfter"):
        raise ManifestError("workspaceVerified requires equal before and after fingerprints")
    pending = manifest.get("pendingApproval")
    if pending is not None and (not isinstance(pending, dict) or len(json.dumps(pending, ensure_ascii=False)) > 20_000):
        raise ManifestError("pendingApproval is unbounded")
    if isinstance(pending, dict) and "resumeStatus" in pending:
        try:
            target = RunStatus(str(pending["resumeStatus"]))
        except ValueError as exc:
            raise ManifestError("pendingApproval resumeStatus is invalid") from exc
        if target in {RunStatus.COMPLETED, RunStatus.BOUNDED_STOP, RunStatus.DRY_RUN_COMPLETED, RunStatus.WAITING_HUMAN} or target not in ALLOWED_TRANSITIONS[RunStatus.WAITING_HUMAN]:
            raise ManifestError("pendingApproval resumeStatus is not resumable")


def _validate_v2_resources(resources: Mapping[str, Any], run_id: str) -> None:
    github = resources.get("github")
    if github is not None:
        _validate_resource_mapping(github, {"owner", "name", "url", "defaultBranch", "marker"}, "github")
        _require_resource_text(github, "owner", 39)
        _require_resource_text(github, "name", 100)
        _require_resource_text(github, "url", 2_000)
        _require_resource_text(github, "defaultBranch", 120)
        _require_resource_marker(github, run_id, "github")
    knowledge = resources.get("knowledgeBase")
    if knowledge is not None:
        _validate_resource_mapping(knowledge, {"id", "name", "marker"}, "knowledge base")
        _require_resource_text(knowledge, "id", 120)
        _require_resource_text(knowledge, "name", 120)
        _require_resource_marker(knowledge, run_id, "knowledge")
    sources = resources.get("sources")
    if not isinstance(sources, list) or len(sources) > 2:
        raise ManifestError("source resources are invalid")
    for index, source in enumerate(sources, start=1):
        _validate_resource_mapping(source, {"id", "sourceName", "sha256", "marker"}, "source")
        _require_resource_text(source, "id", 120)
        _require_resource_text(source, "sourceName", 200)
        if not isinstance(source.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", source["sha256"]):
            raise ManifestError("source hash is invalid")
        _require_resource_marker(source, run_id, f"source-{index}")
    project = resources.get("project")
    if project is not None:
        _validate_resource_mapping(project, {"projectId", "projectKey", "name", "repositoryUrl", "repoOwner", "repoName", "defaultBranch", "knowledgeBaseId", "marker"}, "project")
        if not isinstance(project.get("projectId"), str) or not PROJECT_ID_PATTERN.fullmatch(project["projectId"]):
            raise ManifestError("project resource ID is invalid")
        for key, maximum in (("projectKey", 100), ("name", 120), ("repositoryUrl", 2_000), ("repoOwner", 39), ("repoName", 100), ("defaultBranch", 120), ("knowledgeBaseId", 120)):
            _require_resource_text(project, key, maximum)
        _require_resource_marker(project, run_id, "project")


def _validate_resource_mapping(value: Any, expected: set[str], label: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping) or set(value) != expected:
        raise ManifestError(f"{label} resource fields are invalid")
    return value


def _require_resource_text(value: Mapping[str, Any], key: str, maximum: int) -> str:
    candidate = value.get(key)
    if not isinstance(candidate, str) or not candidate.strip() or len(candidate) > maximum:
        raise ManifestError(f"resource {key} is invalid")
    return candidate


def _require_resource_marker(value: Mapping[str, Any], run_id: str, kind: str) -> None:
    marker = value.get("marker")
    if not isinstance(marker, str) or not re.fullmatch(rf"\[autopilot:{re.escape(run_id)}:{re.escape(kind)}:[0-9a-f]{{12}}\]", marker):
        raise ManifestError("resource marker is invalid")


def _validate_provision_status_resources(status: RunStatus, resources: Mapping[str, Any], project_id: str) -> None:
    required = {
        RunStatus.GH_BOUND: ("github",),
        RunStatus.KB_INTENT: ("github",),
        RunStatus.KB_BOUND: ("github", "knowledgeBase"),
        RunStatus.SOURCES_INTENT: ("github", "knowledgeBase"),
        RunStatus.SOURCES_BOUND: ("github", "knowledgeBase", "sources"),
        RunStatus.RD_PROJECT_INTENT: ("github", "knowledgeBase", "sources"),
        RunStatus.RD_PROJECT_BOUND: ("github", "knowledgeBase", "sources", "project"),
        RunStatus.READY: ("github", "knowledgeBase", "sources", "project"),
    }
    for key in required.get(status, ()):
        value = resources.get(key)
        if value in (None, []):
            raise ManifestError(f"provision status {status.value} requires {key}")
    if status in {RunStatus.RD_PROJECT_BOUND, RunStatus.READY}:
        project = resources.get("project")
        if not isinstance(project, Mapping) or project_id != project.get("projectId"):
            raise ManifestError("bound project must match manifest projectId")


def _validate_v2_iterations(manifest: Mapping[str, Any], status: RunStatus) -> None:
    iterations = manifest.get("iterations")
    if not isinstance(iterations, list) or len(iterations) > MAX_ITERATIONS:
        raise ManifestError("iterations exceed hard limit")
    if not iterations:
        return
    if not manifest.get("projectId"):
        raise ManifestError("iterations require a bound project")
    for item in iterations:
        if not isinstance(item, dict):
            raise ManifestError("iteration must be an object")
        request = item.get("request")
        if request is not None:
            if item.get("requestFingerprint") != request_fingerprint(request):
                raise ManifestError("request fingerprint does not match request")
            _validate_persisted_request(request, str(manifest["projectId"]))
    if _active_task_count(dict(manifest)) > MAX_ACTIVE_TASKS:
        raise ManifestError("more than one active task")


def _project_intent_matches_plan(
    request: Mapping[str, Any],
    plan: Mapping[str, Any],
    resources: Mapping[str, Any],
) -> bool:
    expected_keys = {
        "projectKey", "name", "description", "repositoryUrl", "repoOwner", "repoName",
        "defaultBranch", "enabled", "knowledgeBaseId",
    }
    if set(request) != expected_keys:
        return False
    project = plan.get("project")
    knowledge_base = resources.get("knowledgeBase")
    if not isinstance(project, Mapping) or not isinstance(knowledge_base, Mapping):
        return False
    for key in ("projectKey", "name", "description", "repositoryUrl", "repoOwner", "repoName", "defaultBranch", "enabled"):
        if request.get(key) != project.get(key):
            return False
    return request.get("knowledgeBaseId") == knowledge_base.get("id")


def _validate_persisted_request(request: Any, project_id: str) -> None:
    if not isinstance(request, dict):
        raise ManifestError("persisted request must be an object")
    allowed = {
        "title", "priority", "projectId", "repositoryUrl", "repoOwner", "repoName", "baseBranch",
        "expectedResult", "acceptanceCriteria", "materials", "autoExecute", "tokenBudgetOverride",
    }
    if set(request) - allowed:
        raise ManifestError("persisted request contains unknown fields")
    if not isinstance(request.get("title"), str) or not 1 <= len(request["title"]) <= 120:
        raise ManifestError("persisted request title is invalid")
    if request.get("priority") not in {"P0", "P1", "P2", "P3"}:
        raise ManifestError("persisted request priority is invalid")
    if str(request.get("projectId", "")) != project_id:
        raise ManifestError("persisted request projectId does not match manifest")
    if not isinstance(request.get("expectedResult"), str) or not 1 <= len(request["expectedResult"]) <= 20_000:
        raise ManifestError("persisted request expectedResult is invalid")
    for key, maximum in (("repositoryUrl", 2_000), ("repoOwner", 200), ("repoName", 200), ("baseBranch", 200)):
        if key in request and (not isinstance(request[key], str) or len(request[key]) > maximum):
            raise ManifestError(f"persisted request {key} is unbounded")
    criteria = request.get("acceptanceCriteria")
    if not isinstance(criteria, list) or not 2 <= len(criteria) <= 6 or any(not isinstance(item, str) or not item.strip() or len(item) > 1_000 for item in criteria):
        raise ManifestError("persisted request acceptanceCriteria is invalid")
    materials = request.get("materials")
    if not isinstance(materials, list) or not materials or len(materials) > 20:
        raise ManifestError("persisted request materials are invalid")
    for material in materials:
        if not isinstance(material, dict):
            raise ManifestError("persisted request material is invalid")
        material_allowed = {"materialType", "sourceType", "title", "sourceUri", "content", "mimeType", "revisionId", "recoveryStageRunId"}
        if set(material) - material_allowed:
            raise ManifestError("persisted request material contains unknown fields")
        for key, maximum in (("materialType", 80), ("sourceType", 80), ("title", 500), ("sourceUri", 2_000), ("mimeType", 200), ("revisionId", 200), ("recoveryStageRunId", 120)):
            if not isinstance(material.get(key), str) or len(material[key]) > maximum:
                raise ManifestError("persisted request material field is invalid")
        content = material.get("content", "")
        source_uri = material.get("sourceUri", "")
        if not isinstance(content, str) or len(content) > 1_000 or not isinstance(source_uri, str) or len(source_uri) > 2_000:
            raise ManifestError("persisted request material is unbounded")
        if not content.strip() and not source_uri.strip():
            raise ManifestError("persisted request has no usable material")
    if request.get("autoExecute") is not False:
        raise ManifestError("persisted request autoExecute must be false")
    budget = request.get("tokenBudgetOverride", 0)
    if isinstance(budget, bool) or not isinstance(budget, int) or not 0 <= budget <= MAX_TOKEN_BUDGET:
        raise ManifestError("persisted request token budget is invalid")


def _validate_fingerprint(value: Any, label: str) -> None:
    if not isinstance(value, dict):
        raise ManifestError(f"{label} must be an object")
    if set(value) != {"repoRoot", "sha256", "untrackedCount"}:
        raise ManifestError(f"{label} contains unknown fields")
    root = value.get("repoRoot")
    digest = value.get("sha256")
    count = value.get("untrackedCount")
    if not isinstance(root, str) or not root or len(root) > 1_000:
        raise ManifestError(f"{label}.repoRoot is invalid")
    if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ManifestError(f"{label}.sha256 is invalid")
    if isinstance(count, bool) or not isinstance(count, int) or count < 0:
        raise ManifestError(f"{label}.untrackedCount is invalid")


def _verify_workspace_fingerprint(value: dict[str, Any], label: str, *, exclude_path: Path | None = None) -> None:
    """Require a fingerprint to be produced by the read-only git capture helper."""

    _validate_fingerprint(value, label)
    try:
        from .workspace_guard import capture
    except ImportError:  # pragma: no cover - explicit script invocation imports as a package.
        from workspace_guard import capture  # type: ignore[no-redef]
    try:
        actual = capture(value["repoRoot"], exclude_paths=[exclude_path] if exclude_path is not None else None)
    except Exception as exc:
        raise ManifestError(f"{label} could not be captured from the repository") from exc
    if actual != value:
        raise ManifestError(f"{label} does not match the repository")


class ManifestStore:
    def __init__(self, run_dir: Path) -> None:
        self.run_dir = Path(run_dir).resolve()
        self.path = self.run_dir / "manifest.json"
        self.lock_path = self.run_dir / ".manifest.lock"
        self._lock_depth = threading.local()

    @contextmanager
    def lock(self) -> Iterator[None]:
        """Serialize a run across processes while retaining re-entrant calls."""

        self.run_dir.mkdir(parents=True, exist_ok=True)
        try:
            import fcntl
        except ImportError as exc:  # pragma: no cover - the supported runtime is POSIX.
            raise ManifestError("per-run locking requires a POSIX runtime") from exc
        depth = getattr(self._lock_depth, "value", 0)
        if depth:
            self._lock_depth.value = depth + 1
            try:
                yield
            finally:
                self._lock_depth.value -= 1
            return
        handle = self.lock_path.open("a+", encoding="utf-8")
        try:
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
            self._lock_depth.value = 1
            yield
        finally:
            self._lock_depth.value = 0
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
            finally:
                handle.close()

    @_locked_mutation
    def initialize(self, manifest: dict[str, Any]) -> None:
        if self.path.exists():
            raise ManifestError(f"manifest already exists: {self.path}")
        self.run_dir.mkdir(parents=True, exist_ok=True)
        candidate = copy.deepcopy(manifest)
        _validate_manifest(candidate)
        self._write(candidate)

    def load(self) -> dict[str, Any]:
        if getattr(self._lock_depth, "value", 0):
            return self._load_unlocked()
        with self.lock():
            return self._load_unlocked()

    def _load_unlocked(self) -> dict[str, Any]:
        try:
            manifest = json.loads(self.path.read_text(encoding="utf-8"))
        except FileNotFoundError as exc:
            raise ManifestError(f"manifest does not exist: {self.path}") from exc
        except json.JSONDecodeError as exc:
            raise ManifestError(f"manifest is not valid JSON: {self.path}") from exc
        if not isinstance(manifest, dict):
            raise ManifestError("manifest root must be an object")
        # Early v1 dry-run manifests predate the explicit verification flag.
        # Treat only that exact, known layout as unverified; unknown fields remain fatal.
        if set(manifest) == _LEGACY_V1_TOP_LEVEL_KEYS:
            manifest["workspaceVerified"] = False
        _validate_manifest(manifest)
        return manifest

    @_locked_mutation
    def transition(self, target: RunStatus, reason: str) -> dict[str, Any]:
        manifest = self.load()
        current = RunStatus(manifest["status"])
        if manifest.get("schemaVersion") == "rd-bot-autopilot/v2" and (
            target in _PROVISION_STATUS_SET or (current in _PROVISION_STATUS_SET and target != RunStatus.BOUNDED_STOP)
        ):
            raise ManifestError("provisioning transitions require the dedicated provisioning methods")
        if target == RunStatus.COMPLETED:
            raise ManifestError("COMPLETED requires record_decision with evaluation and workspace guards")
        if target not in ALLOWED_TRANSITIONS.get(current, set()):
            raise ManifestError(f"{current.value} -> {target.value} is not allowed: {reason}")
        manifest["status"] = target.value
        clean_reason = _safe_text(reason, 1000)
        manifest["lastTransitionReason"] = clean_reason
        if target in {RunStatus.WAITING_HUMAN, RunStatus.BOUNDED_STOP, RunStatus.COMPLETED}:
            manifest["stopReason"] = clean_reason
        self._write(manifest)
        return manifest

    @_locked_mutation
    def freeze_provision_plan(self, plan: Mapping[str, Any]) -> str:
        manifest = self.load()
        if manifest.get("schemaVersion") != "rd-bot-autopilot/v2":
            raise ManifestError("provision plan requires a v2 manifest")
        if RunStatus(manifest["status"]) != RunStatus.DRAFT:
            raise ManifestError("provision plan can only be frozen from DRAFT")
        try:
            clean_plan = validate_provision_plan(plan)
        except ProvisionPlanError as exc:
            raise ManifestError(f"invalid provision plan: {exc}") from exc
        if clean_plan["runId"] != manifest["runId"] or clean_plan["idea"] != manifest["idea"]:
            raise ManifestError("provision plan identity does not match manifest")
        if clean_plan["successCriteria"] != manifest["successCriteria"]:
            raise ManifestError("provision plan criteria do not match manifest")
        digest = plan_digest(clean_plan)
        manifest["provisioning"]["plan"] = clean_plan
        manifest["provisioning"]["planSha256"] = digest
        manifest["status"] = RunStatus.PLAN_READY.value
        manifest["lastTransitionReason"] = "canonical provision plan frozen"
        self._write(manifest)
        return digest

    @_locked_mutation
    def confirm_provision(self, digest: str) -> dict[str, Any]:
        manifest = self.load()
        if manifest.get("schemaVersion") != "rd-bot-autopilot/v2":
            raise ManifestError("provision confirmation requires a v2 manifest")
        if manifest.get("mode") != "live-provision":
            raise ManifestError("only live-provision runs may be confirmed")
        if RunStatus(manifest["status"]) != RunStatus.PLAN_READY:
            raise ManifestError("provision confirmation requires PLAN_READY")
        expected = manifest["provisioning"].get("planSha256")
        if not isinstance(digest, str) or digest != expected:
            raise ManifestError("provision confirmation digest does not match the frozen plan")
        manifest["provisioning"]["confirmation"] = {"planSha256": digest, "confirmedAt": utc_now()}
        manifest["status"] = RunStatus.LIVE_CONFIRMED.value
        manifest["lastTransitionReason"] = "exact provision plan digest confirmed"
        self._write(manifest)
        return manifest

    @_locked_mutation
    def record_provision_intent(self, resource: str, request: Mapping[str, Any] | list[dict[str, Any]]) -> dict[str, Any]:
        manifest = self.load()
        if manifest.get("schemaVersion") != "rd-bot-autopilot/v2":
            raise ManifestError("provision intent requires a v2 manifest")
        if resource not in _PROVISION_INTENT_STATES:
            raise ManifestError("unknown provision resource")
        required_status, target_status = _PROVISION_INTENT_STATES[resource]
        if RunStatus(manifest["status"]) != required_status:
            raise ManifestError(f"{RunStatus(manifest['status']).value} cannot record {resource} provision intent")
        if resource in manifest["provisioning"]["intents"]:
            raise ManifestError("provision intent already exists; resume or request human review")
        plan = manifest["provisioning"].get("plan")
        if not isinstance(plan, dict):
            raise ManifestError("provision intent requires a frozen plan")
        if resource == "github" and request != plan["repository"]:
            raise ManifestError("GitHub intent must exactly match the frozen plan")
        if resource == "knowledgeBase" and request != plan["knowledgeBase"]:
            raise ManifestError("knowledge base intent must exactly match the frozen plan")
        if resource == "sources" and request != plan["documents"]:
            raise ManifestError("source intent must exactly match the frozen plan")
        if resource == "project":
            if not isinstance(request, Mapping) or not _project_intent_matches_plan(request, plan, manifest["provisioning"]["resources"]):
                raise ManifestError("project intent must bind the frozen repository and knowledge base")
        safe_request = redact(copy.deepcopy(request), max_chars=20_000)
        if safe_request != request:
            raise ManifestError("provision intent contains redactable secret material")
        try:
            json.dumps(safe_request, ensure_ascii=False)
        except (TypeError, ValueError) as exc:
            raise ManifestError("provision intent is not JSON serializable") from exc
        manifest["provisioning"]["intents"][resource] = {
            "request": safe_request,
            "requestSha256": request_fingerprint(safe_request),
            "createdAt": utc_now(),
        }
        manifest["status"] = target_status.value
        manifest["lastTransitionReason"] = f"{resource} provision intent persisted before remote write"
        self._write(manifest)
        return copy.deepcopy(manifest["provisioning"]["intents"][resource])

    @_locked_mutation
    def bind_provision_resource(self, resource: str, resource_view: Mapping[str, Any] | list[dict[str, Any]]) -> dict[str, Any]:
        manifest = self.load()
        if manifest.get("schemaVersion") != "rd-bot-autopilot/v2":
            raise ManifestError("provision resource binding requires a v2 manifest")
        if resource not in _PROVISION_BOUND_STATES:
            raise ManifestError("unknown provision resource")
        required_status, target_status = _PROVISION_BOUND_STATES[resource]
        if RunStatus(manifest["status"]) != required_status:
            raise ManifestError(f"{RunStatus(manifest['status']).value} cannot bind {resource}")
        if resource not in manifest["provisioning"]["intents"]:
            raise ManifestError("provision resource cannot bind before its intent")
        safe_view = redact(copy.deepcopy(resource_view), max_chars=2_000)
        if safe_view != resource_view:
            raise ManifestError("provision resource contains redactable secret material")
        manifest["provisioning"]["resources"][resource] = safe_view
        if resource == "project":
            if not isinstance(safe_view, Mapping):
                raise ManifestError("project binding must be an object")
            manifest["projectId"] = str(safe_view.get("projectId", ""))
        manifest["status"] = target_status.value
        manifest["lastTransitionReason"] = f"{resource} provision resource bound after verification"
        self._write(manifest)
        return copy.deepcopy(manifest)

    @_locked_mutation
    def mark_provision_ready(self) -> dict[str, Any]:
        manifest = self.load()
        if manifest.get("schemaVersion") != "rd-bot-autopilot/v2" or RunStatus(manifest["status"]) != RunStatus.RD_PROJECT_BOUND:
            raise ManifestError("provision readiness requires RD_PROJECT_BOUND")
        manifest["status"] = RunStatus.READY.value
        manifest["lastTransitionReason"] = "repository, sources, and RD-Bot project are bound"
        self._write(manifest)
        return manifest

    @_locked_mutation
    def resume_provisioning(self) -> dict[str, Any]:
        manifest = self.load()
        if manifest.get("schemaVersion") != "rd-bot-autopilot/v2" or RunStatus(manifest["status"]) != RunStatus.WAITING_HUMAN:
            raise ManifestError("provision resume requires a v2 WAITING_HUMAN run")
        pending = manifest.get("pendingApproval")
        if not isinstance(pending, dict):
            raise ManifestError("WAITING_HUMAN has no safe provisioning resume target")
        try:
            target = RunStatus(str(pending.get("resumeStatus", "")))
        except ValueError as exc:
            raise ManifestError("WAITING_HUMAN has no safe provisioning resume target") from exc
        if target not in _PROVISION_STATUS_SET:
            raise ManifestError("WAITING_HUMAN resume target is not a provisioning state")
        manifest["status"] = target.value
        manifest["pendingApproval"] = None
        manifest["stopReason"] = ""
        manifest["lastTransitionReason"] = "human authorized reconciliation-only provisioning resume"
        self._write(manifest)
        return manifest

    @_locked_mutation
    def freeze_plan(self, iteration_no: int, plan: dict[str, Any]) -> dict[str, Any]:
        manifest = self.load()
        if not isinstance(plan, dict) or set(plan) - _PLAN_KEYS:
            raise ManifestError("plan contains unknown fields")
        try:
            if len(json.dumps(plan, ensure_ascii=False)) > 100_000:
                raise ManifestError("plan is too large")
        except (TypeError, ValueError) as exc:
            raise ManifestError("plan is not JSON serializable") from exc
        if _active_task_count(manifest) >= MAX_ACTIVE_TASKS:
            raise ManifestError("cannot plan another iteration while an active task exists")
        current = RunStatus(manifest["status"])
        provisioned_first_iteration = (
            manifest.get("schemaVersion") == "rd-bot-autopilot/v2"
            and current == RunStatus.READY
            and not manifest["iterations"]
        )
        if current not in {RunStatus.PLANNING, RunStatus.LEARNING} and not provisioned_first_iteration:
            raise ManifestError(f"cannot freeze plan from {current.value}")
        if iteration_no != len(manifest["iterations"]) + 1 or iteration_no > MAX_ITERATIONS:
            raise ManifestError("iteration number exceeds hard limit or is not sequential")
        title_value = plan.get("title", "")
        if not isinstance(title_value, str):
            raise ManifestError("plan title must be a string")
        title = title_value.strip()
        raw_criteria = plan.get("acceptanceCriteria", [])
        if not isinstance(raw_criteria, list) or any(not isinstance(item, str) for item in raw_criteria):
            raise ManifestError("plan acceptanceCriteria must be strings")
        criteria = [item.strip() for item in raw_criteria if item.strip()]
        if not title or len(title) > 4_000 or len(criteria) < 2 or len(criteria) > 6:
            raise ManifestError("plan needs a title and two to six acceptance criteria")
        for key, maximum in (("goal", 4_000), ("expectedResult", 20_000), ("whyNow", 4_000)):
            if key in plan and (not isinstance(plan[key], str) or len(plan[key]) > maximum):
                raise ManifestError(f"plan {key} is invalid")
        if "nonGoals" in plan and (not isinstance(plan["nonGoals"], list) or len(plan["nonGoals"]) > 10 or any(not isinstance(item, str) or len(item) > 500 for item in plan["nonGoals"])):
            raise ManifestError("plan nonGoals are invalid")
        materials = plan.get("materials", [])
        if not isinstance(materials, list) or len(materials) > 20:
            raise ManifestError("plan materials must contain at most 20 items")
        budget = plan.get("tokenBudgetOverride", 0)
        if isinstance(budget, bool) or not isinstance(budget, int) or not 0 <= budget <= MAX_TOKEN_BUDGET:
            raise ManifestError("plan tokenBudgetOverride must be between 0 and 200000")
        total_material_chars = 0
        for material in materials:
            if not isinstance(material, dict):
                raise ManifestError("each plan material must be an object")
            allowed_material_keys = {
                "type", "materialType", "name", "title", "content", "sourceType", "sourceUri",
                "mimeType", "revisionId", "recoveryStageRunId",
            }
            if set(material) - allowed_material_keys:
                raise ManifestError("plan material contains unknown fields")
            content = material.get("content", "")
            if not isinstance(content, str) or len(content) > 1_000:
                raise ManifestError("plan material content must be <= 1,000 characters")
            for key, maximum in (("sourceType", 80), ("sourceUri", 2_000), ("mimeType", 200), ("revisionId", 200), ("recoveryStageRunId", 120), ("title", 500), ("name", 500), ("type", 80), ("materialType", 80)):
                if key in material and (not isinstance(material[key], str) or len(material[key]) > maximum):
                    raise ManifestError(f"plan material {key} is unbounded")
            total_material_chars += len(content)
        if total_material_chars > 20_000:
            raise ManifestError("plan material content is too large")
        source_plan_fingerprint = request_fingerprint(plan)
        normalized = _safe_plan(plan)
        normalized_title = _safe_text(title, 4_000)
        normalized["title"] = normalized_title
        normalized["acceptanceCriteria"] = [_safe_text(item, 1_000) for item in criteria]
        marker = f"[autopilot:{manifest['runId']}:{iteration_no}]"
        if len(marker) + 1 + len(normalized_title) > 120:
            raise ManifestError("plan title plus autopilot marker exceeds 120 characters")
        item = {
            "iterationNo": iteration_no,
            "phase": "READY",
            "plan": normalized,
            "planHash": request_fingerprint(normalized),
            "sourcePlanFingerprint": source_plan_fingerprint,
            "marker": marker,
            "exactTitle": f"{marker} {normalized_title}",
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

    @_locked_mutation
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
            if not request["materials"]:
                request["materials"] = [{
                    "materialType": "REQUIREMENT_DOC",
                    "sourceType": "MANUAL_TEXT",
                    "title": "autopilot plan summary",
                    "sourceUri": "",
                    "content": request["expectedResult"],
                    "mimeType": "text/plain",
                    "revisionId": "",
                    "recoveryStageRunId": "",
                }]
        outbound_request = copy.deepcopy(request)
        source_request_fingerprint = request_fingerprint(outbound_request)
        request = _safe_request(request)
        request["autoExecute"] = False
        item["request"] = request
        item["requestFingerprint"] = request_fingerprint(request)
        item["sourceRequestFingerprint"] = source_request_fingerprint
        item["phase"] = "DISPATCH_INTENT"
        item["dispatchIntentAt"] = utc_now()
        manifest["status"] = RunStatus.DISPATCH_INTENT.value
        manifest["lastTransitionReason"] = "dispatch intent persisted before create POST"
        self._write(manifest)
        ephemeral = copy.deepcopy(item)
        ephemeral["request"] = outbound_request
        return ephemeral

    @_locked_mutation
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

    @_locked_mutation
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

    @_locked_mutation
    def mark_observing(self, iteration_no: int, reason: str) -> dict[str, Any]:
        manifest = self.load()
        current = RunStatus(manifest["status"])
        if current not in {RunStatus.SUBMIT_INTENT, RunStatus.RETRY_INTENT}:
            raise ManifestError(f"observing cannot start from {current.value}")
        item = _iteration(manifest, iteration_no)
        item["phase"] = "OBSERVING"
        item["observingAt"] = utc_now()
        manifest["status"] = RunStatus.OBSERVING.value
        manifest["lastTransitionReason"] = _safe_text(reason, 1000)
        self._write(manifest)
        return item

    @_locked_mutation
    def record_observation(self, iteration_no: int, observation: dict[str, Any]) -> dict[str, Any]:
        manifest = self.load()
        if RunStatus(manifest["status"]) != RunStatus.OBSERVING:
            raise ManifestError("observation requires OBSERVING status")
        if not isinstance(observation, dict):
            raise ManifestError("observation must be an object")
        for key in ("observedTokens", "elapsedMinutes"):
            if key in observation and (isinstance(observation[key], bool) or not isinstance(observation[key], int) or observation[key] < 0):
                raise ManifestError(f"observation {key} must be a non-negative integer")
        item = _iteration(manifest, iteration_no)
        bounded_observation = redact(copy.deepcopy(observation), max_chars=1_000)
        if not isinstance(bounded_observation, dict) or len(json.dumps(bounded_observation, ensure_ascii=False)) > 50_000:
            raise ManifestError("observation is unbounded")
        usage_tokens = max(0, _as_non_negative_int(bounded_observation.get("observedTokens", 0)))
        token_limit = manifest["limits"]["maxTokenBudget"]
        overview = bounded_observation.get("executionOverview")
        nested_budget = overview.get("tokenBudget") if isinstance(overview, dict) else None
        if isinstance(nested_budget, dict):
            if "overBudget" in nested_budget and not isinstance(nested_budget["overBudget"], bool):
                raise ManifestError("observation tokenBudget.overBudget must be boolean")
            for key in ("effectiveTokenBudget", "actualAccumulatedTokens", "finalActualTokens", "runningTokens", "estimatedTotalTokens"):
                if key in nested_budget and (isinstance(nested_budget[key], bool) or not isinstance(nested_budget[key], int) or nested_budget[key] < 0):
                    raise ManifestError(f"observation tokenBudget.{key} must be a non-negative integer")
        budget_over = bounded_observation.get("budgetOver") is True or (
            isinstance(nested_budget, dict) and nested_budget.get("overBudget") is True
        )
        token_exceeded = usage_tokens >= token_limit or budget_over
        bounded_observation["observedTokens"] = min(usage_tokens, token_limit)
        bounded_observation["budgetExceeded"] = token_exceeded
        elapsed_minutes = max(
            manifest["usage"].get("elapsedMinutes", 0),
            _as_non_negative_int(bounded_observation.get("elapsedMinutes", 0)),
            _elapsed_minutes(manifest.get("createdAt")),
        )
        elapsed_limit = manifest["limits"]["maxElapsedMinutes"]
        elapsed_exceeded = elapsed_minutes >= elapsed_limit
        bounded_observation["elapsedMinutes"] = min(elapsed_minutes, elapsed_limit)
        bounded_observation["elapsedExceeded"] = elapsed_exceeded
        item["observations"].append(bounded_observation)
        item["observations"] = item["observations"][-20:]
        if "taskStatus" in observation:
            item["taskStatus"] = _safe_text(observation["taskStatus"], 80)
        if "stageRuns" in observation:
            stages = observation["stageRuns"]
            if not isinstance(stages, list):
                raise ManifestError("stageRuns must be a list")
            item["stageRuns"] = redact(copy.deepcopy(stages[:100]), max_chars=500)
        item["updatedAt"] = utc_now()
        manifest["usage"]["observedTokens"] = max(manifest["usage"].get("observedTokens", 0), min(usage_tokens, token_limit))
        manifest["usage"]["elapsedMinutes"] = min(elapsed_minutes, elapsed_limit)
        if token_exceeded or elapsed_exceeded:
            manifest["usage"]["budgetExceeded"] = True
            manifest["status"] = RunStatus.BOUNDED_STOP.value
            manifest["stopReason"] = "autopilot token or elapsed-time budget exceeded"
            manifest["lastTransitionReason"] = manifest["stopReason"]
        self._write(manifest)
        return item

    @_locked_mutation
    def enforce_limits(self) -> dict[str, Any]:
        """Refresh persisted usage and stop before any further mutating action."""

        manifest = self.load()
        limits = manifest["limits"]
        usage = manifest["usage"]
        elapsed = max(usage.get("elapsedMinutes", 0), _elapsed_minutes(manifest.get("createdAt")))
        usage["elapsedMinutes"] = min(elapsed, limits["maxElapsedMinutes"])
        exceeded = usage.get("observedTokens", 0) >= limits["maxTokenBudget"] or elapsed >= limits["maxElapsedMinutes"]
        if exceeded:
            usage["budgetExceeded"] = True
            current = RunStatus(manifest["status"])
            if current not in {RunStatus.DRY_RUN_COMPLETED, RunStatus.COMPLETED, RunStatus.BOUNDED_STOP, RunStatus.WAITING_HUMAN}:
                manifest["status"] = RunStatus.BOUNDED_STOP.value
                manifest["stopReason"] = "autopilot token or elapsed-time budget exceeded"
                manifest["lastTransitionReason"] = manifest["stopReason"]
        self._write(manifest)
        return manifest

    @_locked_mutation
    def record_retry_intent(self, iteration_no: int, retry_preview: dict[str, Any]) -> dict[str, Any]:
        manifest = self.load()
        if RunStatus(manifest["status"]) != RunStatus.OBSERVING:
            raise ManifestError("retry intent requires OBSERVING status")
        item = _iteration(manifest, iteration_no)
        if item.get("retryCount", 0) >= MAX_RETRIES_PER_TASK:
            raise ManifestError("retry budget exhausted")
        item["retryCount"] += 1
        item["retryPreview"] = _safe_retry_preview(retry_preview)
        item["retryOperatorNote"] = f"[autopilot:{manifest['runId']}:{iteration_no}:retry:{item['retryCount']}]"
        item["phase"] = "RETRY_INTENT"
        item["retryIntentAt"] = utc_now()
        manifest["usage"]["retries"] += 1
        manifest["status"] = RunStatus.RETRY_INTENT.value
        manifest["lastTransitionReason"] = "retry intent persisted before retry POST"
        self._write(manifest)
        return item

    @_locked_mutation
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

    @_locked_mutation
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

    @_locked_mutation
    def record_evaluation(self, iteration_no: int, evaluation: dict[str, Any]) -> dict[str, Any]:
        manifest = self.load()
        if RunStatus(manifest["status"]) != RunStatus.EVALUATING:
            raise ManifestError("evaluation result requires EVALUATING status")
        if not isinstance(evaluation, dict) or evaluation.get("status") not in {"SUCCEEDED", "FAILED", "CANCELLED"}:
            raise ManifestError("evaluation status is invalid")
        if not isinstance(evaluation.get("overallPassed"), bool):
            raise ManifestError("evaluation overallPassed must be boolean")
        for key in ("sampleCount", "passedSampleCount", "failedSampleCount"):
            value = evaluation.get(key, 0)
            if isinstance(value, bool) or not isinstance(value, int) or value < 0 or value > 1_000_000:
                raise ManifestError(f"evaluation {key} is invalid")
        item = _iteration(manifest, iteration_no)
        item["evaluation"] = redact(copy.deepcopy(evaluation), max_chars=1_000)
        item["phase"] = "LEARNING"
        manifest["status"] = RunStatus.LEARNING.value
        manifest["lastTransitionReason"] = "evaluation reached terminal state"
        self._write(manifest)
        return item

    @_locked_mutation
    def set_waiting_human(self, reason: str, approval: dict[str, Any]) -> dict[str, Any]:
        manifest = self.load()
        current = RunStatus(manifest["status"])
        if current in {RunStatus.COMPLETED, RunStatus.BOUNDED_STOP, RunStatus.DRY_RUN_COMPLETED}:
            raise ManifestError(f"cannot wait for human from {current.value}")
        if RunStatus.WAITING_HUMAN not in ALLOWED_TRANSITIONS.get(current, set()):
            raise ManifestError(f"{current.value} -> WAITING_HUMAN is not allowed")
        if not isinstance(approval, dict):
            raise ManifestError("approval must be an object")
        pending = redact(copy.deepcopy(approval), max_chars=1_000)
        if not isinstance(pending, dict):
            raise ManifestError("approval must remain an object after redaction")
        pending["resumeStatus"] = current.value
        if manifest.get("iterations"):
            pending.setdefault("resumeIteration", manifest["iterations"][-1].get("iterationNo"))
        manifest["pendingApproval"] = pending
        manifest["status"] = RunStatus.WAITING_HUMAN.value
        clean_reason = _safe_text(reason, 1000)
        manifest["stopReason"] = clean_reason
        manifest["lastTransitionReason"] = clean_reason
        self._write(manifest)
        return manifest

    @_locked_mutation
    def record_decision(
        self,
        decision: str,
        reason: str,
        evidence_ids: list[str],
        *,
        next_goal: str | None = None,
    ) -> dict[str, Any]:
        """Persist a bounded manager decision after validating local evidence references."""

        manifest = self.load()
        if decision not in {"COMPLETE", "NEXT_ITERATION", "STOP", "WAITING_HUMAN", "RESUME"}:
            raise ManifestError("unknown decision")
        if not isinstance(evidence_ids, list) or len(evidence_ids) > 100 or any(
            not isinstance(item, str) or not 1 <= len(item) <= 200 for item in evidence_ids
        ):
            raise ManifestError("evidenceIds must be a bounded list of strings")
        known: set[str] = set()
        for item in manifest.get("iterations", []):
            for key in ("taskId", "evaluationRunId", "marker", "exactTitle"):
                if item.get(key):
                    known.add(str(item[key]))
            for stage in item.get("stageRuns", []):
                if isinstance(stage, dict) and stage.get("stageRunId"):
                    known.add(str(stage["stageRunId"]))
        unknown = sorted(set(evidence_ids) - known)
        if unknown:
            raise ManifestError(f"evidence id is not present in manifest: {unknown[0]}")
        clean_reason = _safe_text(reason, 1000)
        if not clean_reason:
            raise ManifestError("decision reason must not be empty")
        if not manifest.get("iterations"):
            raise ManifestError("decision requires at least one iteration")
        latest = manifest["iterations"][-1]
        latest["decision"] = {
            "decision": decision,
            "reason": clean_reason,
            "evidenceIds": list(evidence_ids),
            "nextGoal": _safe_text(next_goal, 4_000) if isinstance(next_goal, str) else "",
        }
        current = RunStatus(manifest["status"])
        if decision == "RESUME":
            if current != RunStatus.WAITING_HUMAN:
                raise ManifestError("RESUME requires WAITING_HUMAN status")
            pending = manifest.get("pendingApproval") or {}
            try:
                target = RunStatus(str(pending.get("resumeStatus", "")))
            except ValueError as exc:
                raise ManifestError("WAITING_HUMAN has no safe resume status") from exc
            if target in {RunStatus.COMPLETED, RunStatus.BOUNDED_STOP, RunStatus.DRY_RUN_COMPLETED, RunStatus.WAITING_HUMAN} or target not in ALLOWED_TRANSITIONS[RunStatus.WAITING_HUMAN]:
                raise ManifestError("WAITING_HUMAN resume target is not allowed")
            manifest["status"] = target.value
            manifest["pendingApproval"] = None
            manifest["stopReason"] = ""
        elif decision == "COMPLETE":
            evaluation = latest.get("evaluation") or {}
            if current != RunStatus.LEARNING or latest.get("evaluationRunId") is None:
                raise ManifestError("COMPLETE requires a terminal evaluation")
            if evaluation.get("status") != "SUCCEEDED" or evaluation.get("overallPassed") is not True:
                raise ManifestError("COMPLETE requires a successful evaluation")
            if manifest.get("workspaceVerified") is not True:
                raise ManifestError("COMPLETE requires a successful workspace verification")
            if not evidence_ids:
                raise ManifestError("COMPLETE requires evidenceIds")
            manifest["status"] = RunStatus.COMPLETED.value
            manifest["stopReason"] = clean_reason
        elif decision == "NEXT_ITERATION":
            if current != RunStatus.LEARNING:
                raise ManifestError("NEXT_ITERATION requires LEARNING status")
            if len(manifest["iterations"]) >= MAX_ITERATIONS:
                raise ManifestError("NEXT_ITERATION is unavailable after the final iteration")
            if not isinstance(next_goal, str) or not next_goal.strip():
                raise ManifestError("NEXT_ITERATION requires nextGoal")
            manifest["idea"] = _safe_text(next_goal, 4_000)
            manifest["status"] = RunStatus.PLANNING.value
        elif decision == "STOP":
            if RunStatus.BOUNDED_STOP not in ALLOWED_TRANSITIONS.get(current, set()):
                raise ManifestError(f"{current.value} -> BOUNDED_STOP is not allowed")
            manifest["status"] = RunStatus.BOUNDED_STOP.value
            manifest["stopReason"] = clean_reason
        else:
            if RunStatus.WAITING_HUMAN not in ALLOWED_TRANSITIONS.get(current, set()):
                raise ManifestError(f"{current.value} -> WAITING_HUMAN is not allowed")
            manifest["status"] = RunStatus.WAITING_HUMAN.value
            manifest["pendingApproval"] = {"type": "MANAGER_DECISION", "reason": clean_reason, "resumeStatus": current.value}
            manifest["stopReason"] = clean_reason
        manifest["lastTransitionReason"] = clean_reason
        self._write(manifest)
        return manifest

    @_locked_mutation
    def finish(self, status: RunStatus, reason: str) -> dict[str, Any]:
        manifest = self.load()
        if status not in {RunStatus.COMPLETED, RunStatus.BOUNDED_STOP, RunStatus.DRY_RUN_COMPLETED}:
            raise ManifestError("finish accepts only terminal run statuses")
        if status == RunStatus.COMPLETED:
            raise ManifestError("COMPLETED requires record_decision with evaluation and workspace guards")
        current = RunStatus(manifest["status"])
        if status not in ALLOWED_TRANSITIONS.get(current, set()):
            raise ManifestError(f"{current.value} -> {status.value} is not allowed")
        manifest["status"] = status.value
        clean_reason = _safe_text(reason, 1000)
        manifest["stopReason"] = clean_reason
        manifest["lastTransitionReason"] = clean_reason
        self._write(manifest)
        return manifest

    @_locked_mutation
    def set_workspace_before(self, fingerprint: dict[str, Any]) -> dict[str, Any]:
        manifest = self.load()
        _verify_workspace_fingerprint(fingerprint, "workspaceBefore", exclude_path=self.run_dir)
        manifest["workspaceBefore"] = copy.deepcopy(fingerprint)
        manifest["workspaceVerified"] = False
        self._write(manifest)
        return manifest

    @_locked_mutation
    def set_workspace_after(self, fingerprint: dict[str, Any]) -> dict[str, Any]:
        manifest = self.load()
        _verify_workspace_fingerprint(fingerprint, "workspaceAfter", exclude_path=self.run_dir)
        if manifest.get("workspaceBefore") is None:
            raise ManifestError("workspaceBefore must be captured first")
        manifest["workspaceAfter"] = copy.deepcopy(fingerprint)
        manifest["workspaceVerified"] = manifest.get("workspaceBefore") == fingerprint
        self._write(manifest)
        return manifest

    @_locked_mutation
    def append_request_ledger(self, entry: dict[str, Any]) -> dict[str, Any]:
        manifest = self.load()
        safe_entry = {
            "method": str(entry.get("method", "")),
            "path": str(entry.get("path", "")),
            "requestSha256": str(entry.get("requestSha256", "")),
            "status": int(entry.get("status", 0)),
            "timestamp": str(entry.get("timestamp", "")),
        }
        if not safe_entry["method"] or not safe_entry["path"] or not safe_entry["requestSha256"]:
            raise ManifestError("request ledger entry is incomplete")
        manifest.setdefault("requestLedger", []).append(safe_entry)
        manifest["requestLedger"] = manifest["requestLedger"][-500:]
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
