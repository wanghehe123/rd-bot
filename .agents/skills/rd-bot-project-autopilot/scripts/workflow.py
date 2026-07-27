"""Bounded task dispatch and submit-once workflow for one manifest iteration."""

from __future__ import annotations

import time
from collections.abc import Callable, Mapping
from typing import Any

from .iteration_state import ManifestError, ManifestStore, RunStatus
from .rd_bot_client import ApiTransportError


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


class WorkflowError(RuntimeError):
    """Raised when a workflow response cannot be safely interpreted."""


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

    def dispatch(self, iteration_no: int) -> dict[str, Any]:
        manifest = self.store.load()
        if manifest["mode"] == "dry-run":
            if RunStatus(manifest["status"]) != RunStatus.READY:
                raise WorkflowError("dry-run dispatch requires READY status")
            return self.store.finish(RunStatus.DRY_RUN_COMPLETED, "dry-run completed without mutating requests")
        if RunStatus(manifest["status"]) != RunStatus.READY:
            raise WorkflowError("live dispatch requires READY status")
        item = self.store.record_dispatch_intent(iteration_no)
        request = item["request"]
        try:
            response = self.client.create_requirement(request)
        except ApiTransportError as exc:
            if not exc.ambiguous:
                return self._wait("TASK_CREATION_TRANSPORT_FAILED", str(exc))
            return self._reconcile_create(iteration_no)
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
        item = self.store.record_submit_intent(iteration_no)
        task_id = str(item["taskId"])
        try:
            self.client.submit_task(task_id)
        except ApiTransportError as exc:
            if not exc.ambiguous:
                return self._wait("TASK_SUBMIT_TRANSPORT_FAILED", str(exc), task_id=task_id)
            return self._reconcile_submit(iteration_no, task_id)
        except Exception as exc:
            return self._wait("TASK_SUBMIT_FAILED", type(exc).__name__, task_id=task_id)
        self.store.mark_observing(iteration_no, "task submit accepted; observation may begin")
        return self.store.load()

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


def _detail_matches_request(detail: Mapping[str, Any], request: Mapping[str, Any]) -> bool:
    if str(detail.get("title", "")) != str(request.get("title", "")):
        return False
    if str(detail.get("projectId", "")) != str(request.get("projectId", "")):
        return False
    if str(detail.get("taskType", "")) != "REQUIREMENT":
        return False
    if str(detail.get("expectedResult", "")) != str(request.get("expectedResult", "")):
        return False
    actual_criteria = detail.get("acceptanceCriteria")
    expected_criteria = request.get("acceptanceCriteria")
    if not isinstance(actual_criteria, list) or [str(item) for item in actual_criteria] != [str(item) for item in expected_criteria or []]:
        return False
    actual_budget = detail.get("tokenBudgetOverride", detail.get("tokenBudget", 0))
    expected_budget = request.get("tokenBudgetOverride", 0)
    try:
        if int(actual_budget or 0) != int(expected_budget or 0):
            return False
    except (TypeError, ValueError):
        return False
    return True


def _timeline_started(timeline: Any) -> bool:
    text = str(timeline).upper()
    return any(marker in text for marker in ("SUBMIT", "EXECUT", "SEARCH", "MATERIAL_COLLECTING"))
