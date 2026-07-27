"""Bounded task dispatch and submit-once workflow for one manifest iteration."""

from __future__ import annotations

import hashlib
import json
import re
import time
from collections.abc import Callable, Mapping
from datetime import datetime
from functools import wraps
from typing import Any

from .iteration_state import ManifestError, ManifestStore, RunStatus
from .rd_bot_client import ApiTransportError, ClientPolicyError
from .run_artifacts import redact


PLAN_KEYS = {
    "title",
    "goal",
    "nonGoals",
    "expectedResult",
    "acceptanceCriteria",
    "materials",
    "whyNow",
}
_RECONCILE_SECONDS = 15.0
_POLL_INTERVAL_SECONDS = 1.0
_OBSERVATION_SECONDS = 120 * 60
_ACTIVE_TASK_STATES = {
    "CREATED",
    "MATERIAL_COLLECTING",
    "MATERIAL_READY",
    "CONTEXT_BUILDING",
    "CONTEXT_READY",
    "PLAN_GENERATING",
    "PLAN_GENERATED",
    "WAITING_POLICY",
    "SEARCHING",
    "EXECUTING",
    "VALIDATING",
    "PR_CREATING",
    "COMMITTED",
    "REPORTING",
    "RECOVERING",
}
_HUMAN_TASK_STATES = {"WAITING_APPROVAL", "FAILED_NEEDS_HUMAN", "REJECTED", "MERGED"}
_BOUNDED_TASK_STATES = {"CANCELLED", "DEAD_LETTERED", "DELETED"}
_RETRY_PHASES = {"RAG", "AGENT_ROLE", "DETERMINISTIC_REVIEW", "AI_REVIEW", "PR_PUBLICATION"}
_EVALUATION_ACTIVE_STATES = {"CREATED", "QUEUED", "RECORDING", "SCORING", "REPORTING", "DIFFING", "CANCEL_REQUESTED"}
_EVALUATION_TERMINAL_STATES = {"SUCCEEDED", "FAILED", "CANCELLED"}
_SAFE_RUN_ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,120}$")


class WorkflowError(RuntimeError):
    """Raised when a workflow response cannot be safely interpreted."""


def _locked_workflow(function: Callable[..., dict[str, Any]]) -> Callable[..., dict[str, Any]]:
    @wraps(function)
    def wrapper(self: "AutopilotWorkflow", *args: Any, **kwargs: Any) -> dict[str, Any]:
        with self.store.lock():
            return function(self, *args, **kwargs)

    return wrapper


class AutopilotWorkflow:
    def __init__(
        self,
        store: ManifestStore,
        client: Any,
        *,
        clock: Callable[[], float] | None = None,
        sleep: Callable[[float], None] | None = None,
        reconcile_seconds: float = _RECONCILE_SECONDS,
        poll_interval_seconds: float = _POLL_INTERVAL_SECONDS,
    ) -> None:
        self.store = store
        self.client = client
        self.clock = clock or time.monotonic
        self.sleep = sleep or time.sleep
        self.reconcile_seconds = max(0.0, reconcile_seconds)
        self.poll_interval_seconds = max(0.0, poll_interval_seconds)

    def freeze_plan(self, plan: Mapping[str, Any]) -> dict[str, Any]:
        missing = sorted(PLAN_KEYS - set(plan))
        if missing:
            raise WorkflowError(f"plan is missing required keys: {', '.join(missing)}")
        manifest = self.store.load()
        iteration_no = len(manifest.get("iterations", [])) + 1
        return self.store.freeze_plan(iteration_no, dict(plan))

    @_locked_workflow
    def dispatch(self, iteration_no: int) -> dict[str, Any]:
        manifest = self.store.enforce_limits()
        if RunStatus(manifest["status"]) == RunStatus.BOUNDED_STOP:
            return manifest
        if manifest["mode"] == "dry-run":
            if RunStatus(manifest["status"]) != RunStatus.READY:
                raise WorkflowError("dry-run dispatch requires READY status")
            item = _iteration(manifest, iteration_no)
            request = _dispatch_request(manifest, item, None, include_project_snapshot=False)
            _validate_requirement_payload(request, self.client)
            return self.store.finish(RunStatus.DRY_RUN_COMPLETED, "dry-run completed without mutating requests")
        if RunStatus(manifest["status"]) != RunStatus.READY:
            raise WorkflowError("live dispatch requires READY status")
        self._require_preflight("preflight_write")
        item = _iteration(manifest, iteration_no)
        request = _dispatch_request(manifest, item, self.client)
        self._require_preflight("preflight_requirement", request)
        item = self.store.record_dispatch_intent(iteration_no, request)
        request = item["request"]
        try:
            response = self.client.create_requirement(request)
        except ApiTransportError as exc:
            if not exc.ambiguous:
                return self._wait("TASK_CREATION_TRANSPORT_FAILED", str(exc))
            return self._reconcile_create(iteration_no)
        except ClientPolicyError:
            raise
        except Exception as exc:  # Fail closed for unknown provider/client failures.
            return self._wait("TASK_CREATION_FAILED", type(exc).__name__)

        task_id = _extract_task_id(response)
        if task_id is None:
            return self._wait("TASK_CREATION_RESPONSE_INVALID", "create response did not contain one numeric taskId")
        if not self._verify_task(task_id, request):
            return self._wait(
                "TASK_CREATION_DETAIL_MISMATCH",
                "created task detail did not match the exact request",
                task_id=task_id,
            )
        self.store.bind_task(iteration_no, task_id)
        return self._submit_bound(iteration_no)

    @_locked_workflow
    def resume(self, iteration_no: int) -> dict[str, Any]:
        """Continue a persisted intent or observation without creating another task."""

        manifest = self.store.enforce_limits()
        status = RunStatus(manifest["status"])
        if status == RunStatus.BOUNDED_STOP:
            return manifest
        item = _iteration(manifest, iteration_no)
        if status == RunStatus.DISPATCH_INTENT:
            return self._reconcile_create(iteration_no)
        if status == RunStatus.TASK_BOUND:
            return self._submit_bound(iteration_no)
        if status == RunStatus.SUBMIT_INTENT:
            task_id = str(item.get("taskId") or "")
            return self._reconcile_submit(iteration_no, task_id)
        if status == RunStatus.OBSERVING:
            return self.observe(iteration_no)
        if status == RunStatus.RETRY_INTENT:
            return self._reconcile_retry(
                iteration_no,
                str(item.get("taskId") or ""),
                _as_int((item.get("retryPreview") or {}).get("sourceTaskVersion")),
                str(item.get("retryOperatorNote") or ""),
            )
        if status == RunStatus.EVALUATION_INTENT:
            return self._reconcile_evaluation(iteration_no, str(item.get("taskId") or ""), item, None)
        if status == RunStatus.EVALUATING:
            run_id = str(item.get("evaluationRunId") or "")
            if not run_id:
                return self._wait("UNKNOWN_EVALUATION_STATE", "evaluation intent has no runId")
            return self._poll_evaluation(iteration_no, run_id, None)
        return manifest

    def _reconcile_create(self, iteration_no: int) -> dict[str, Any]:
        manifest = self.store.load()
        item = _iteration(manifest, iteration_no)
        request = item["request"]
        deadline = self.clock() + self.reconcile_seconds
        while True:
            try:
                listing = self.client.list_tasks(manifest["projectId"], item["marker"], page=1, page_size=100)
            except Exception as exc:
                return self._wait("AMBIGUOUS_TASK_CREATION", f"reconciliation read failed: {type(exc).__name__}")
            candidates: list[tuple[str, Mapping[str, Any]]] = []
            for record in _records(listing):
                task_id = _extract_task_id(record)
                if task_id is None or not _record_matches_marker(record, item, manifest["projectId"]):
                    continue
                try:
                    detail = _as_mapping(self.client.get_task(task_id))
                except Exception:
                    continue
                if _detail_matches_request(detail, request):
                    candidates.append((task_id, detail))
            unique = {task_id: detail for task_id, detail in candidates}
            if len(unique) == 1:
                task_id = next(iter(unique))
                self.store.bind_task(iteration_no, task_id, reconciled=True)
                return self._submit_bound(iteration_no)
            if len(unique) > 1 or self.clock() >= deadline:
                return self._wait(
                    "AMBIGUOUS_TASK_CREATION",
                    "reconciliation found zero or multiple exact task matches",
                    candidates=sorted(unique),
                )
            # A visible marker with a detail mismatch is already actionable
            # ambiguity; do not keep polling a known-bad candidate.
            visible_candidates = [
                _extract_task_id(record)
                for record in _records(listing)
                if _extract_task_id(record) is not None
                and _record_matches_marker(record, item, manifest["projectId"])
            ]
            if visible_candidates:
                return self._wait(
                    "AMBIGUOUS_TASK_CREATION",
                    "reconciliation candidate failed exact task-detail verification",
                    candidates=sorted(set(visible_candidates)),
                )
            remaining = max(0.0, deadline - self.clock())
            self.sleep(min(self.poll_interval_seconds, remaining))

    def _verify_task(self, task_id: str, request: Mapping[str, Any]) -> bool:
        try:
            detail = _as_mapping(self.client.get_task(task_id))
        except Exception:
            return False
        return _detail_matches_request(detail, request)

    def _submit_bound(self, iteration_no: int) -> dict[str, Any]:
        guarded = self.store.enforce_limits()
        if RunStatus(guarded["status"]) == RunStatus.BOUNDED_STOP:
            return guarded
        item_before_intent = _iteration(guarded, iteration_no)
        task_id_before_intent = str(item_before_intent.get("taskId") or "")
        self._require_preflight("preflight_submit", task_id_before_intent)
        item = self.store.record_submit_intent(iteration_no)
        task_id = str(item["taskId"])
        try:
            self.client.submit_task(task_id)
        except ApiTransportError as exc:
            if not exc.ambiguous:
                return self._wait("TASK_SUBMIT_TRANSPORT_FAILED", str(exc), task_id=task_id)
            return self._reconcile_submit(iteration_no, task_id)
        except ClientPolicyError:
            raise
        except Exception as exc:
            return self._wait("TASK_SUBMIT_FAILED", type(exc).__name__, task_id=task_id)
        self.store.mark_observing(iteration_no, "task submit accepted; observation may begin")
        return self.store.load()

    @_locked_workflow
    def observe(self, iteration_no: int, *, deadline_epoch: float | None = None) -> dict[str, Any]:
        """Poll one task using only known states and persist bounded evidence."""

        manifest = self.store.enforce_limits()
        if RunStatus(manifest["status"]) == RunStatus.BOUNDED_STOP:
            return manifest
        if RunStatus(manifest["status"]) != RunStatus.OBSERVING:
            raise WorkflowError("observation requires OBSERVING status")
        item = _iteration(manifest, iteration_no)
        task_id = str(item.get("taskId") or "")
        if not task_id:
            raise WorkflowError("observation requires a bound task")
        deadline = self.clock() + _OBSERVATION_SECONDS if deadline_epoch is None else deadline_epoch
        while True:
            if self.clock() >= deadline:
                return self.store.finish(RunStatus.BOUNDED_STOP, "task observation deadline exceeded")
            try:
                detail = _as_mapping(self.client.get_task(task_id))
                timeline = self.client.get_timeline(task_id)
                overview = _as_mapping(self.client.get_execution_overview(task_id))
            except Exception as exc:
                return self._wait("TASK_OBSERVATION_FAILED", f"observation read failed: {type(exc).__name__}", task_id=task_id)
            status = str(detail.get("status") or detail.get("taskStatus") or "")
            try:
                _validate_token_budget(overview.get("tokenBudget"))
            except WorkflowError as exc:
                return self._wait("INVALID_TOKEN_BUDGET", str(exc), task_id=task_id)
            stage_runs = _stage_runs(overview)
            latest_by_role = _latest_stage_by_role(stage_runs)
            trace_refs: list[str] = []
            unsafe_trace_ref = False
            for stage in latest_by_role.values():
                stage_run_id = str(stage.get("stageRunId") or stage.get("id") or "")
                if not stage_run_id:
                    continue
                if not _SAFE_RUN_ID_RE.fullmatch(stage_run_id):
                    unsafe_trace_ref = True
                    continue
                trace_refs.append(stage_run_id)
                try:
                    self.client.get_execution_trace(task_id, stage_run_id)
                except ClientPolicyError:
                    unsafe_trace_ref = True
                except Exception:
                    # The trace endpoint may be unavailable for an archived stage;
                    # retain the safe reference but do not fabricate its contents.
                    pass
            token_budget = _token_budget_snapshot(overview)
            observed_tokens = _observed_tokens(detail, overview)
            observation = {
                "taskStatus": status,
                "task": _task_snapshot(detail),
                "timeline": _timeline_snapshot(timeline),
                "executionOverview": {
                    "stageRunCount": len(stage_runs),
                    "elapsedMillis": _as_int(overview.get("elapsedMillis")),
                    "tokenBudget": token_budget,
                },
                "stageRuns": _stage_snapshots(stage_runs),
                "latestStageByRole": latest_by_role,
                "traceRefs": trace_refs[:50],
                "observedTokens": observed_tokens,
                "budgetOver": token_budget.get("overBudget") is True,
                "elapsedMinutes": max(0, int(_as_int(overview.get("elapsedMillis")) / 60_000)),
                "observedAt": self.clock(),
            }
            recorded = self.store.record_observation(iteration_no, redact(observation))
            current_manifest = self.store.load()
            if RunStatus(current_manifest["status"]) == RunStatus.BOUNDED_STOP:
                return current_manifest
            if unsafe_trace_ref:
                return self._wait("UNSAFE_STAGE_RUN_ID", "execution overview contained an unsafe stageRunId", task_id=task_id)
            if status in _HUMAN_TASK_STATES:
                return self._wait("TASK_REQUIRES_HUMAN", f"task entered human-gated status: {status}", task_id=task_id)
            if status in _BOUNDED_TASK_STATES:
                return self.store.finish(RunStatus.BOUNDED_STOP, f"task entered terminal stop status: {status}")
            if status not in _ACTIVE_TASK_STATES and status not in {"COMPLETED", "FAILED_RETRYABLE"}:
                return self._wait("UNKNOWN_TASK_STATUS", f"unknown task status: {status or '<empty>'}", task_id=task_id)
            if status in {"COMPLETED", "FAILED_RETRYABLE"}:
                return self.store.load()
            remaining = deadline - self.clock()
            if remaining <= 0:
                return self.store.finish(RunStatus.BOUNDED_STOP, "task observation deadline exceeded")
            self.sleep(min(self.poll_interval_seconds, remaining))

    @_locked_workflow
    def retry(self, iteration_no: int) -> dict[str, Any]:
        manifest = self.store.enforce_limits()
        if RunStatus(manifest["status"]) == RunStatus.BOUNDED_STOP:
            return manifest
        if RunStatus(manifest["status"]) != RunStatus.OBSERVING:
            raise WorkflowError("retry requires OBSERVING status")
        item = _iteration(manifest, iteration_no)
        task_id = str(item.get("taskId") or "")
        if item.get("taskStatus") != "FAILED_RETRYABLE":
            raise WorkflowError("retry requires FAILED_RETRYABLE task status")
        if item.get("retryCount", 0) >= 1:
            raise WorkflowError("retry budget exhausted")
        try:
            preview = _as_mapping(self.client.get_retry_preview(task_id))
            source_version = int(preview.get("sourceTaskVersion", 0))
            failure_phase = str(preview.get("failurePhase", ""))
        except (Exception, ValueError):
            return self._wait("INVALID_RETRY_PREVIEW", "retry preview could not be validated", task_id=task_id)
        if source_version <= 0 or failure_phase not in _RETRY_PHASES:
            return self._wait("INVALID_RETRY_PREVIEW", "retry preview is outside the bounded retry policy", task_id=task_id)
        guarded = self.store.enforce_limits()
        if RunStatus(guarded["status"]) == RunStatus.BOUNDED_STOP:
            return guarded
        retry_number = _as_int(_iteration(guarded, iteration_no).get("retryCount")) + 1
        operator_note = f"[autopilot:{guarded['runId']}:{iteration_no}:retry:{retry_number}]"
        payload = {
            "expectedFailedStageRunId": str(preview.get("failedStageRunId") or ""),
            "expectedFailedRetrievalRunId": str(preview.get("failedRetrievalRunId") or ""),
            "expectedFailedAiReviewRunId": str(preview.get("failedAiReviewRunId") or ""),
            "expectedSourceTaskVersion": source_version,
            "operatorNote": operator_note,
            "evidenceMaterialIds": [],
        }
        self._require_preflight("preflight_retry", task_id, payload)
        intent = self.store.record_retry_intent(iteration_no, dict(preview))
        try:
            self.client.retry_task(task_id, payload)
        except ApiTransportError as exc:
            if not exc.ambiguous:
                return self._wait("TASK_RETRY_TRANSPORT_FAILED", str(exc), task_id=task_id)
            return self._reconcile_retry(iteration_no, task_id, source_version, intent["retryOperatorNote"])
        except ClientPolicyError:
            raise
        except Exception as exc:
            return self._wait("TASK_RETRY_FAILED", type(exc).__name__, task_id=task_id)
        self.store.mark_observing(iteration_no, "retry accepted; observation may resume")
        return self.store.load()

    def _reconcile_retry(self, iteration_no: int, task_id: str, source_version: int, operator_note: str) -> dict[str, Any]:
        try:
            history = self.client.get_retry_history(task_id)
            matches = [
                item
                for item in _records(history)
                if str(item.get("taskId", "")) == task_id
                and _as_int(item.get("sourceTaskVersion")) == source_version
                and str(item.get("operatorNote", "")) == operator_note
                and _as_int(item.get("attemptNo")) == 1
            ]
        except Exception as exc:
            return self._wait("AMBIGUOUS_TASK_RETRY", f"retry reconciliation failed: {type(exc).__name__}", task_id=task_id)
        if len(matches) == 1:
            self.store.mark_observing(iteration_no, "ambiguous retry reconciled")
            return self.store.load()
        return self._wait("AMBIGUOUS_TASK_RETRY", "retry history has zero or multiple exact matches", task_id=task_id)

    @_locked_workflow
    def evaluate(self, iteration_no: int, *, deadline_epoch: float | None = None) -> dict[str, Any]:
        manifest = self.store.enforce_limits()
        if RunStatus(manifest["status"]) == RunStatus.BOUNDED_STOP:
            return manifest
        if RunStatus(manifest["status"]) != RunStatus.OBSERVING:
            raise WorkflowError("evaluation requires OBSERVING status")
        item = _iteration(manifest, iteration_no)
        task_id = str(item.get("taskId") or "")
        if item.get("taskStatus") != "COMPLETED":
            raise WorkflowError("evaluation requires COMPLETED task status")
        guarded = self.store.enforce_limits()
        if RunStatus(guarded["status"]) == RunStatus.BOUNDED_STOP:
            return guarded
        payload = {"judgeProvider": "NONE", "judgeLimit": 0, "timeoutSeconds": 90, "baselineRunId": ""}
        self._require_preflight("preflight_evaluation", task_id, payload)
        intent = self.store.record_evaluation_intent(iteration_no)
        try:
            response = self.client.create_task_evaluation(task_id, payload)
        except ApiTransportError as exc:
            if not exc.ambiguous:
                return self._wait("EVALUATION_CREATION_TRANSPORT_FAILED", str(exc), task_id=task_id)
            return self._reconcile_evaluation(iteration_no, task_id, intent, deadline_epoch)
        except ClientPolicyError:
            raise
        except Exception as exc:
            return self._wait("EVALUATION_CREATION_FAILED", type(exc).__name__, task_id=task_id)
        run_id = _extract_run_id(response)
        if run_id is None:
            return self._wait("EVALUATION_CREATION_RESPONSE_INVALID", "evaluation response did not contain one runId", task_id=task_id)
        self.store.mark_evaluating(iteration_no, run_id)
        return self._poll_evaluation(iteration_no, run_id, deadline_epoch)

    def _reconcile_evaluation(
        self,
        iteration_no: int,
        task_id: str,
        intent: Mapping[str, Any],
        deadline_epoch: float | None,
    ) -> dict[str, Any]:
        manifest = self.store.load()
        item = _iteration(manifest, iteration_no)
        try:
            listing = self.client.list_evaluations(keyword=item["marker"], page=1, page_size=100)
            matches = [
                record
                for record in _records(listing)
                if _evaluation_matches(record, task_id, item["marker"], intent.get("evaluationIntentAt", ""))
            ]
        except Exception as exc:
            return self._wait("AMBIGUOUS_EVALUATION_CREATION", f"evaluation reconciliation failed: {type(exc).__name__}", task_id=task_id)
        if len(matches) != 1:
            return self._wait("AMBIGUOUS_EVALUATION_CREATION", "evaluation list has zero or multiple exact matches", task_id=task_id)
        run_id = _extract_run_id(matches[0])
        if run_id is None:
            return self._wait("AMBIGUOUS_EVALUATION_CREATION", "evaluation candidate has no safe runId", task_id=task_id)
        self.store.mark_evaluating(iteration_no, run_id)
        return self._poll_evaluation(iteration_no, run_id, deadline_epoch)

    def _poll_evaluation(self, iteration_no: int, run_id: str, deadline_epoch: float | None) -> dict[str, Any]:
        deadline = self.clock() + 90 if deadline_epoch is None else deadline_epoch
        while True:
            guarded = self.store.enforce_limits()
            if RunStatus(guarded["status"]) == RunStatus.BOUNDED_STOP:
                return guarded
            if self.clock() >= deadline:
                return self.store.finish(RunStatus.BOUNDED_STOP, "evaluation polling deadline exceeded")
            try:
                evaluation = _as_mapping(self.client.get_evaluation(run_id))
            except Exception as exc:
                return self._wait("EVALUATION_OBSERVATION_FAILED", f"evaluation read failed: {type(exc).__name__}", run_id=run_id)
            status = str(evaluation.get("status", ""))
            if status in _EVALUATION_TERMINAL_STATES:
                if not isinstance(evaluation.get("overallPassed"), bool):
                    return self._wait("INVALID_EVALUATION_RESULT", "overallPassed must be a boolean", run_id=run_id)
                for key in ("sampleCount", "passedSampleCount", "failedSampleCount"):
                    value = evaluation.get(key, 0)
                    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                        return self._wait("INVALID_EVALUATION_RESULT", f"{key} must be a non-negative integer", run_id=run_id)
                timeline = None
                artifacts = None
                get_timeline = getattr(self.client, "get_evaluation_timeline", None)
                get_artifacts = getattr(self.client, "get_evaluation_artifacts", None)
                try:
                    if callable(get_timeline):
                        timeline = get_timeline(run_id)
                    if callable(get_artifacts):
                        artifacts = get_artifacts(run_id)
                except Exception:
                    timeline = None
                    artifacts = None
                result = _evaluation_snapshot(evaluation, timeline=timeline, artifacts=artifacts)
                self.store.record_evaluation(iteration_no, result)
                return self.store.load()
            if status not in _EVALUATION_ACTIVE_STATES:
                return self._wait("UNKNOWN_EVALUATION_STATUS", f"unknown evaluation status: {status or '<empty>'}", run_id=run_id)
            remaining = deadline - self.clock()
            if remaining <= 0:
                return self.store.finish(RunStatus.BOUNDED_STOP, "evaluation polling deadline exceeded")
            self.sleep(min(self.poll_interval_seconds, remaining))

    def _reconcile_submit(self, iteration_no: int, task_id: str) -> dict[str, Any]:
        try:
            detail = _as_mapping(self.client.get_task(task_id))
            timeline = self.client.get_timeline(task_id)
        except Exception as exc:
            return self._wait("AMBIGUOUS_TASK_SUBMISSION", f"submission reconciliation failed: {type(exc).__name__}", task_id=task_id)
        status = str(detail.get("status") or detail.get("taskStatus") or "")
        if status != "CREATED" or _timeline_started(timeline):
            self.store.mark_observing(iteration_no, "ambiguous submit reconciled as started")
            return self.store.load()
        return self._wait(
            "AMBIGUOUS_TASK_SUBMISSION",
            "task remains CREATED after ambiguous submit; manual confirmation required",
            task_id=task_id,
        )

    def _wait(self, approval_type: str, reason: str, **details: Any) -> dict[str, Any]:
        approval = {"type": approval_type, **{key: value for key, value in details.items() if value is not None}}
        try:
            return self.store.set_waiting_human(reason[:1000], approval)
        except ManifestError as exc:
            raise WorkflowError(f"cannot transition to WAITING_HUMAN: {exc}") from exc

    def _require_preflight(self, method_name: str, *args: Any) -> None:
        hook = getattr(self.client, method_name, None)
        if not callable(hook):
            raise ClientPolicyError(f"live client is missing required preflight: {method_name}")
        hook(*args)


def _iteration(manifest: Mapping[str, Any], iteration_no: int) -> Mapping[str, Any]:
    for item in manifest.get("iterations", []):
        if item.get("iterationNo") == iteration_no:
            return item
    raise WorkflowError(f"iteration {iteration_no} does not exist")


def _as_mapping(value: Any) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise WorkflowError("API response is not an object")
    data = value.get("data")
    if isinstance(data, Mapping):
        return data
    return value


def _records(value: Any) -> list[Mapping[str, Any]]:
    if isinstance(value, list):
        return [item for item in value if isinstance(item, Mapping)]
    if not isinstance(value, Mapping):
        return []
    data = value.get("data", value)
    if isinstance(data, Mapping):
        for key in ("records", "items", "content", "list"):
            if isinstance(data.get(key), list):
                return [item for item in data[key] if isinstance(item, Mapping)]
    if isinstance(data, list):
        return [item for item in data if isinstance(item, Mapping)]
    return []


def _extract_task_id(value: Any) -> str | None:
    if not isinstance(value, Mapping):
        return None
    direct: list[str] = []

    def visit(node: Any, *, allow_id: bool = False) -> None:
        if not isinstance(node, Mapping):
            return
        for key, item in node.items():
            if key == "taskId" or (allow_id and key == "id"):
                if isinstance(item, (str, int)) and str(item).isdigit():
                    direct.append(str(item))
            elif key == "data" or key in {"task", "result"}:
                visit(item, allow_id=True)

    visit(value, allow_id=True)
    unique = sorted(set(direct))
    return unique[0] if len(unique) == 1 else None


def _record_matches_marker(record: Mapping[str, Any], item: Mapping[str, Any], project_id: str) -> bool:
    return (
        str(record.get("title", "")) == str(item["exactTitle"])
        and str(record.get("projectId", "")) == str(project_id)
        and str(record.get("taskType", "")) == "REQUIREMENT"
    )


def _dispatch_request(
    manifest: Mapping[str, Any],
    item: Mapping[str, Any],
    client: Any,
    *,
    include_project_snapshot: bool = True,
) -> dict[str, Any]:
    plan = item.get("plan") if isinstance(item.get("plan"), Mapping) else {}
    plan_repo = {key: plan.get(key) for key in ("repositoryUrl", "repoOwner", "repoName", "baseBranch") if plan.get(key)}
    request: dict[str, Any] = {
        "title": item.get("exactTitle", ""),
        "priority": str(plan.get("priority", "P2")).upper(),
        "projectId": manifest.get("projectId", ""),
        "expectedResult": plan.get("expectedResult") or plan.get("goal") or item.get("exactTitle", ""),
        "acceptanceCriteria": plan.get("acceptanceCriteria", []),
        "materials": [_material_payload(item) for item in plan.get("materials", []) if isinstance(item, Mapping)],
        "autoExecute": False,
        "tokenBudgetOverride": plan.get("tokenBudgetOverride", 0),
    }
    if not include_project_snapshot:
        request.update(plan_repo)
        if not request["materials"]:
            request["materials"] = [{
                "materialType": "REQUIREMENT_DOC",
                "sourceType": "MANUAL_TEXT",
                "title": "autopilot plan summary",
                "sourceUri": "",
                "content": str(request["expectedResult"])[:1_000],
                "mimeType": "text/plain",
                "revisionId": "",
                "recoveryStageRunId": "",
            }]
        return request
    get_project = getattr(client, "get_project", None)
    if not callable(get_project):
        raise WorkflowError("live client is missing project snapshot support")
    try:
        project = _as_mapping(get_project(str(manifest["projectId"])))
    except Exception as exc:
        raise WorkflowError(f"project snapshot failed before dispatch: {type(exc).__name__}") from exc
    if str(project.get("projectId", "")) != str(manifest["projectId"]):
        raise WorkflowError("project snapshot identity does not match the selected project")
    if project.get("enabled") is not True:
        raise WorkflowError("selected project snapshot is not enabled")
    authoritative = {
        key: project.get(key)
        for key in ("repositoryUrl", "repoOwner", "repoName")
        if project.get(key)
    }
    if project.get("baseBranch"):
        authoritative["baseBranch"] = project["baseBranch"]
    elif project.get("defaultBranch"):
        authoritative["baseBranch"] = project["defaultBranch"]
    for key, value in plan_repo.items():
        if key not in authoritative or str(value) != str(authoritative[key]):
            raise WorkflowError(f"plan {key} does not match the selected project snapshot")
    base_branch = authoritative.get("baseBranch")
    has_repository_url = isinstance(authoritative.get("repositoryUrl"), str) and bool(authoritative["repositoryUrl"].strip())
    has_repository_pair = all(
        isinstance(authoritative.get(key), str) and bool(authoritative[key].strip())
        for key in ("repoOwner", "repoName")
    )
    if not isinstance(base_branch, str) or not base_branch.strip() or not (has_repository_url or has_repository_pair):
        raise WorkflowError("selected project snapshot is missing repository identity or base branch")
    request.update(authoritative)
    if not request["materials"]:
        request["materials"] = [{
            "materialType": "REQUIREMENT_DOC",
            "sourceType": "MANUAL_TEXT",
            "title": "autopilot plan summary",
            "sourceUri": "",
            "content": str(request["expectedResult"])[:1_000],
            "mimeType": "text/plain",
            "revisionId": "",
            "recoveryStageRunId": "",
        }]
    return request


def _validate_requirement_payload(request: Mapping[str, Any], client: Any) -> None:
    validate = getattr(client, "validate_requirement", None)
    if callable(validate):
        validate(request)
        return
    from .rd_bot_client import SafeRdBotClient

    SafeRdBotClient._validate_requirement(request)


def _material_payload(material: Mapping[str, Any]) -> dict[str, Any]:
    material_type = str(material.get("materialType") or material.get("type") or "REQUIREMENT_DOC").upper()
    if material_type not in {"REQUIREMENT_DOC", "ACCEPTANCE_CRITERIA", "DESIGN_DOC", "REFERENCE_DOC", "SCREENSHOT", "REFERENCE_IMAGE"}:
        material_type = "REQUIREMENT_DOC"
    source_type = str(material.get("sourceType") or "MANUAL_TEXT").upper()
    if source_type != "MANUAL_TEXT":
        raise WorkflowError("autopilot materials must use MANUAL_TEXT")
    content = str(material.get("content") or "")
    source_uri = str(material.get("sourceUri") or "")
    if source_uri or not content.strip():
        raise WorkflowError("autopilot materials must contain manual text and no sourceUri")
    return {
        "materialType": material_type,
        "sourceType": source_type,
        "title": str(material.get("title") or material.get("name") or "autopilot material")[:500],
        "sourceUri": "",
        "content": content[:10_000],
        "mimeType": str(material.get("mimeType") or "text/plain")[:200],
        "revisionId": str(material.get("revisionId") or "")[:200],
        "recoveryStageRunId": str(material.get("recoveryStageRunId") or "")[:120],
    }


def _detail_matches_request(detail: Mapping[str, Any], request: Mapping[str, Any]) -> bool:
    if str(detail.get("title", "")) != str(request.get("title", "")):
        return False
    if str(detail.get("projectId", "")) != str(request.get("projectId", "")):
        return False
    if str(detail.get("taskType", "")) != "REQUIREMENT":
        return False
    for key in ("priority", "repositoryUrl", "repoOwner", "repoName", "baseBranch"):
        expected = request.get(key)
        if expected not in (None, ""):
            if key not in detail or str(detail.get(key, "")) != str(expected):
                return False
    if str(detail.get("expectedResult", "")) != str(request.get("expectedResult", "")):
        return False
    actual_criteria = detail.get("acceptanceCriteria")
    if not isinstance(actual_criteria, list) and detail.get("acceptanceCriteriaJson") is not None:
        actual_criteria = detail.get("acceptanceCriteriaJson")
    if isinstance(actual_criteria, str):
        try:
            actual_criteria = json.loads(actual_criteria)
        except json.JSONDecodeError:
            return False
    expected_criteria = request.get("acceptanceCriteria")
    if not isinstance(actual_criteria, list) or not isinstance(expected_criteria, list) or any(not isinstance(item, str) for item in actual_criteria) or any(not isinstance(item, str) for item in expected_criteria) or actual_criteria != expected_criteria:
        return False
    actual_budget = detail.get("tokenBudgetOverride", detail.get("tokenBudget", 0))
    expected_budget = request.get("tokenBudgetOverride", 0)
    if isinstance(actual_budget, bool) or not isinstance(actual_budget, int) or isinstance(expected_budget, bool) or not isinstance(expected_budget, int):
        return False
    if actual_budget != expected_budget:
        return False
    actual_auto_execute = detail.get("autoExecute")
    if actual_auto_execute is not None and actual_auto_execute is not False:
        return False
    if "materials" in detail and not _materials_match(detail.get("materials"), request.get("materials", [])):
        return False
    if "materialCount" in detail and _as_int(detail.get("materialCount")) != len(request.get("materials", [])):
        return False
    return True


def _timeline_started(timeline: Any) -> bool:
    text = str(timeline).upper()
    return any(marker in text for marker in ("SUBMIT", "EXECUT", "SEARCH", "MATERIAL_COLLECTING"))


def _as_int(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def _stage_runs(overview: Mapping[str, Any]) -> list[Mapping[str, Any]]:
    raw = overview.get("stageRuns", overview.get("stages", []))
    if not isinstance(raw, list):
        return []
    return [item for item in raw if isinstance(item, Mapping)][:100]


def _stage_snapshots(stage_runs: list[Mapping[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "stageRunId": str(item.get("stageRunId") or item.get("id") or "")[:120],
            "role": str(item.get("role") or "")[:80],
            "status": str(item.get("status") or "")[:80],
            "attemptNo": _as_int(item.get("attemptNo")),
        }
        for item in stage_runs
    ]


def _latest_stage_by_role(stage_runs: list[Mapping[str, Any]]) -> dict[str, dict[str, Any]]:
    latest: dict[str, tuple[int, int, Mapping[str, Any]]] = {}
    for index, item in enumerate(stage_runs):
        role = str(item.get("role") or "UNKNOWN")[:80]
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", role):
            role = "UNKNOWN"
        key = (_as_int(item.get("attemptNo")), index)
        if role not in latest or key >= latest[role][:2]:
            latest[role] = (key[0], key[1], item)
    return {role: _stage_snapshots([item])[0] for role, (_attempt, _index, item) in latest.items()}


def _task_snapshot(detail: Mapping[str, Any]) -> dict[str, Any]:
    snapshot: dict[str, Any] = {}
    if "taskId" in detail:
        snapshot["taskId"] = str(detail["taskId"])[:64]
    if "projectId" in detail:
        snapshot["projectId"] = str(detail["projectId"])[:64]
    if "title" in detail:
        snapshot["title"] = str(detail["title"])[:120]
    for key in ("status", "taskStatus"):
        if key in detail:
            snapshot[key] = str(detail[key])[:80]
    for key in ("attemptNo", "version"):
        if key in detail:
            snapshot[key] = _as_int(detail[key])
    if "tokenUsage" in detail:
        snapshot["tokenUsage"] = redact(detail["tokenUsage"], max_chars=1_000)
    return snapshot


def _timeline_snapshot(timeline: Any) -> dict[str, Any]:
    events = timeline if isinstance(timeline, list) else timeline.get("events", []) if isinstance(timeline, Mapping) else []
    if not isinstance(events, list):
        events = []
    types = [str(event.get("type") or event.get("eventType") or "")[:80] for event in events if isinstance(event, Mapping)]
    return {"eventCount": min(len(events), 10_000), "lastTypes": [item for item in types[-20:] if item]}


def _token_budget_snapshot(overview: Mapping[str, Any]) -> dict[str, Any]:
    raw = overview.get("tokenBudget")
    if not isinstance(raw, Mapping):
        return {}
    fields = (
        "effectiveTokenBudget",
        "actualAccumulatedTokens",
        "finalActualTokens",
        "runningTokens",
        "estimatedTotalTokens",
        "actualAvailable",
        "overBudget",
    )
    return {key: raw[key] for key in fields if key in raw}


def _validate_token_budget(raw: Any) -> None:
    if raw is None:
        return
    if not isinstance(raw, Mapping):
        raise WorkflowError("tokenBudget must be an object")
    for key in ("effectiveTokenBudget", "actualAccumulatedTokens", "finalActualTokens", "runningTokens", "estimatedTotalTokens"):
        if key in raw and (isinstance(raw[key], bool) or not isinstance(raw[key], int) or raw[key] < 0):
            raise WorkflowError(f"tokenBudget.{key} must be a non-negative integer")
    if "overBudget" in raw and not isinstance(raw["overBudget"], bool):
        raise WorkflowError("tokenBudget.overBudget must be boolean")


def _observed_tokens(detail: Mapping[str, Any], overview: Mapping[str, Any] | None = None) -> int:
    if isinstance(overview, Mapping):
        token_budget = overview.get("tokenBudget")
        if isinstance(token_budget, Mapping):
            for key in ("actualAccumulatedTokens", "finalActualTokens", "runningTokens"):
                value = _as_int(token_budget.get(key))
                if value > 0:
                    return value
    for key in ("observedTokens", "tokenUsage", "totalTokens"):
        if key in detail:
            return max(0, _as_int(detail[key]))
    return 0


def _materials_match(actual: Any, expected: Any) -> bool:
    if not isinstance(actual, list) or not isinstance(expected, list):
        return False
    if len(actual) != len(expected):
        return False
    for left, right in zip(actual, expected):
        if not isinstance(left, Mapping) or not isinstance(right, Mapping):
            return False
        for key in ("type", "materialType", "name", "title", "sourceType", "sourceUri", "mimeType", "revisionId"):
            if key in left and key in right and str(left.get(key, "")) != str(right.get(key, "")):
                return False
        if "content" in left and "content" in right and str(left.get("content", "")) != str(right.get("content", "")):
            return False
        if "contentHash" in left and "contentSha256" in right and str(left["contentHash"]) != str(right["contentSha256"]):
            return False
    return True


def _extract_run_id(value: Any) -> str | None:
    found: list[str] = []

    def visit(node: Any, allow_id: bool = False) -> None:
        if not isinstance(node, Mapping):
            return
        for key, item in node.items():
            if key == "runId" or (allow_id and key == "id"):
                if isinstance(item, (str, int)) and _SAFE_RUN_ID_RE.fullmatch(str(item)):
                    found.append(str(item))
            elif key in {"data", "result", "evaluation", "run"}:
                visit(item, allow_id=True)

    visit(value, allow_id=True)
    unique = sorted(set(found))
    return unique[0] if len(unique) == 1 else None


def _evaluation_matches(record: Mapping[str, Any], task_id: str, marker: str, intent_at: str) -> bool:
    config = record.get("config") if isinstance(record.get("config"), Mapping) else {}
    source = str(config.get("source") or record.get("source") or "")
    configured_task = str(config.get("taskId") or record.get("taskId") or "")
    name = str(record.get("name") or "")
    created_at = str(record.get("createdAt") or record.get("createdAtEpochMillis") or "")
    return (
        source in {"TASK_RUN", "task-run"}
        and configured_task == task_id
        and marker in name
        and _created_after(created_at, intent_at)
    )


def _created_after(created_at: str, intent_at: str) -> bool:
    if not created_at:
        return False
    if not intent_at:
        return True
    try:
        created_ms = float(created_at)
        intent_ms = datetime.fromisoformat(intent_at.replace("Z", "+00:00")).timestamp() * 1000
        return created_ms >= intent_ms
    except (TypeError, ValueError, OverflowError):
        pass
    return created_at >= intent_at


def _evaluation_snapshot(
    evaluation: Mapping[str, Any],
    *,
    timeline: Any = None,
    artifacts: Any = None,
) -> dict[str, Any]:
    metrics = evaluation.get("metricsJson", evaluation.get("metrics", "{}"))
    if not isinstance(metrics, str):
        metrics = json.dumps(metrics, ensure_ascii=False, sort_keys=True)
    metrics_preview = redact(metrics, max_chars=1000)
    return {
        "status": str(evaluation.get("status", "")),
        "overallPassed": evaluation["overallPassed"],
        "sampleCount": _as_int(evaluation.get("sampleCount")),
        "passedSampleCount": _as_int(evaluation.get("passedSampleCount")),
        "failedSampleCount": _as_int(evaluation.get("failedSampleCount")),
        "metricsSha256": hashlib.sha256(metrics.encode("utf-8")).hexdigest(),
        "metricsPreview": metrics_preview,
        "timeline": _timeline_snapshot(timeline) if timeline is not None else "evaluation timeline available via runId",
        "artifactRefs": _artifact_refs(artifacts),
    }


def _artifact_refs(artifacts: Any) -> list[str]:
    values = artifacts if isinstance(artifacts, list) else artifacts.get("data", []) if isinstance(artifacts, Mapping) else []
    if not isinstance(values, list):
        return []
    refs: list[str] = []
    for item in values[:50]:
        if isinstance(item, Mapping):
            ref = item.get("artifactId") or item.get("id")
            if isinstance(ref, str) and re.fullmatch(r"[A-Za-z0-9._-]{1,200}", ref):
                refs.append(ref)
    return refs
