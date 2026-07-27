"""Fail-closed, loopback-only RD-Bot Admin API client for the prototype skill."""

from __future__ import annotations

import hashlib
import ipaddress
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from typing import Any, Callable, Mapping

from .run_artifacts import redact


MAX_RESPONSE_BYTES = 2 * 1024 * 1024
MAX_TITLE_CHARS = 120
MAX_MATERIAL_CHARS = 10_000
MAX_MATERIAL_TOTAL_CHARS = 50_000
_NUMERIC_ID_RE = re.compile(r"^[0-9]{1,64}$")
_RUN_ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,120}$")


class ClientPolicyError(RuntimeError):
    """Raised before the network when a request violates the runtime policy."""


class ApiTransportError(RuntimeError):
    """Raised for transport failures; POST failures are marked ambiguous."""

    def __init__(self, message: str, *, ambiguous: bool = False) -> None:
        super().__init__(message)
        self.ambiguous = ambiguous


class ApiResponseError(RuntimeError):
    """Raised for bounded, malformed, or unsuccessful API responses."""


def _canonical_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _request_hash(value: Any) -> str:
    return hashlib.sha256(_canonical_json(value)).hexdigest()


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _require_id(value: str, label: str) -> str:
    if not isinstance(value, str) or not _NUMERIC_ID_RE.fullmatch(value):
        raise ClientPolicyError(f"{label} must be a numeric ID")
    return value


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_args: Any, **_kwargs: Any) -> urllib.request.Request:
        raise ApiTransportError("redirect rejected", ambiguous=False)


class SafeRdBotClient:
    """Small typed client with an exact route allowlist and double live-write opt-in."""

    _GET_ROUTES = (
        re.compile(r"^/admin/projects$"),
        re.compile(r"^/admin/projects/[0-9]{1,64}$"),
        re.compile(r"^/admin/rd-tasks$"),
        re.compile(r"^/admin/rd-tasks/[0-9]{1,64}$"),
        re.compile(r"^/admin/rd-tasks/[0-9]{1,64}/timeline$"),
        re.compile(r"^/admin/rd-tasks/[0-9]{1,64}/execution-overview$"),
        re.compile(r"^/admin/rd-tasks/[0-9]{1,64}/stage-runs/[0-9]{1,64}/execution-trace$"),
        re.compile(r"^/admin/rd-tasks/[0-9]{1,64}/retry-preview$"),
        re.compile(r"^/admin/rd-tasks/[0-9]{1,64}/retry-history$"),
        re.compile(r"^/admin/evaluations/runs$"),
        re.compile(r"^/admin/evaluations/runs/[A-Za-z0-9._-]{1,120}$"),
        re.compile(r"^/admin/evaluations/runs/[A-Za-z0-9._-]{1,120}/timeline$"),
        re.compile(r"^/admin/evaluations/runs/[A-Za-z0-9._-]{1,120}/artifacts$"),
    )
    _POST_ROUTES = (
        re.compile(r"^/admin/rd-tasks/requirements$"),
        re.compile(r"^/admin/rd-tasks/[0-9]{1,64}/submit$"),
        re.compile(r"^/admin/rd-tasks/[0-9]{1,64}/retry$"),
        re.compile(r"^/admin/rd-tasks/[0-9]{1,64}/evaluations$"),
    )

    def __init__(
        self,
        base_url: str,
        *,
        mode: str,
        live_flag: bool,
        environ: Mapping[str, str] | None = None,
        request_ledger: Callable[[dict[str, Any]], None] | None = None,
        timeout_seconds: float = 20.0,
    ) -> None:
        try:
            parsed = urllib.parse.urlsplit(base_url)
            hostname = parsed.hostname
        except ValueError as exc:
            raise ClientPolicyError("base URL is invalid") from exc
        if parsed.scheme not in {"http", "https"}:
            raise ClientPolicyError("base URL must use HTTP or HTTPS")
        if not hostname or parsed.username is not None or parsed.password is not None:
            raise ClientPolicyError("base URL must not contain user-info")
        if parsed.path not in {"", "/"} or parsed.query or parsed.fragment:
            raise ClientPolicyError("base URL must not contain a path, query, or fragment")
        if not self._is_loopback(hostname):
            raise ClientPolicyError("base URL must point to a loopback host")
        if mode not in {"dry-run", "live-test"}:
            raise ClientPolicyError("mode must be dry-run or live-test")
        if timeout_seconds <= 0:
            raise ClientPolicyError("timeout_seconds must be positive")
        self.base_url = base_url.rstrip("/")
        self.mode = mode
        self.live_flag = live_flag
        self.environ = dict(os.environ if environ is None else environ)
        self.request_ledger = request_ledger
        self.timeout_seconds = timeout_seconds
        self._opener = urllib.request.build_opener(_NoRedirectHandler())

    @staticmethod
    def _is_loopback(hostname: str) -> bool:
        if hostname.lower() == "localhost":
            return True
        try:
            return ipaddress.ip_address(hostname).is_loopback
        except ValueError:
            return False

    def _ensure_live_write(self) -> None:
        if self.mode != "live-test" or not self.live_flag:
            raise ClientPolicyError("POST requires live-test mode and --live-test")
        if self.environ.get("RD_BOT_AUTOPILOT_LIVE_TEST") != "1":
            raise ClientPolicyError("RD_BOT_AUTOPILOT_LIVE_TEST=1 is required")

    @classmethod
    def _allowlisted(cls, method: str, path: str) -> bool:
        routes = cls._GET_ROUTES if method == "GET" else cls._POST_ROUTES if method == "POST" else ()
        return any(route.fullmatch(path) for route in routes)

    def request(
        self,
        method: str,
        path: str,
        payload: Mapping[str, Any] | None = None,
        *,
        params: Mapping[str, Any] | None = None,
    ) -> Any:
        method = method.upper()
        if not path.startswith("/") or "?" in path or "#" in path:
            raise ClientPolicyError("request path is not allowlisted")
        if not self._allowlisted(method, path):
            raise ClientPolicyError(f"path {path} is not allowlisted")
        if method == "POST":
            self._ensure_live_write()
            if payload is None or not isinstance(payload, Mapping):
                raise ClientPolicyError("POST payload must be a JSON object")
            body = _canonical_json(payload)
            query_payload: Any = payload
        else:
            if payload is not None:
                raise ClientPolicyError("GET requests cannot contain a body")
            body = None
            query_payload = dict(params or {})
        query = urllib.parse.urlencode(query_payload, doseq=True) if method == "GET" and params else ""
        url = f"{self.base_url}{path}{'?' + query if query else ''}"
        request = urllib.request.Request(
            url,
            data=body,
            method=method,
            headers={"Accept": "application/json", **({"Content-Type": "application/json"} if body else {})},
        )
        try:
            response = self._opener.open(request, timeout=self.timeout_seconds)
            status = int(response.status)
            response_body = self._read_bounded(response)
            content_type = response.headers.get("Content-Type", "")
        except ApiTransportError:
            raise
        except urllib.error.HTTPError as exc:
            if exc.code in {301, 302, 303, 307, 308}:
                raise ApiTransportError("redirect rejected", ambiguous=False) from exc
            try:
                error_body = exc.read(MAX_RESPONSE_BYTES + 1)
            except OSError:
                error_body = b""
            if len(error_body) > MAX_RESPONSE_BYTES:
                preview = "<response too large>"
            else:
                preview = redact(error_body.decode("utf-8", "replace"), max_chars=1000)
            raise ApiResponseError(f"HTTP {exc.code}: {preview}") from exc
        except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as exc:
            raise ApiTransportError("transport failure", ambiguous=method == "POST") from exc
        if len(response_body) > MAX_RESPONSE_BYTES:
            raise ApiResponseError("response too large")
        if status < 200 or status >= 300:
            preview = redact(response_body.decode("utf-8", "replace"), max_chars=1000)
            raise ApiResponseError(f"HTTP {status}: {preview}")
        if "json" not in content_type.lower():
            raise ApiResponseError("response is not JSON")
        try:
            parsed = json.loads(response_body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ApiResponseError("response is not valid JSON") from exc
        if self.request_ledger:
            self.request_ledger(
                {
                    "method": method,
                    "path": path,
                    "requestSha256": _request_hash(query_payload),
                    "status": status,
                    "timestamp": _utc_now(),
                }
            )
        return parsed

    @staticmethod
    def _read_bounded(response: Any) -> bytes:
        content_length = response.headers.get("Content-Length")
        if content_length:
            try:
                if int(content_length) > MAX_RESPONSE_BYTES:
                    return response.read(MAX_RESPONSE_BYTES + 1)
            except ValueError:
                pass
        return response.read(MAX_RESPONSE_BYTES + 1)

    def get_project(self, project_id: str) -> Any:
        project_id = _require_id(project_id, "project_id")
        return self.request("GET", f"/admin/projects/{project_id}")

    def list_projects(self, keyword: str | None = None, page: int = 1, page_size: int = 100) -> Any:
        self._validate_page(page, page_size)
        params: dict[str, Any] = {"page": page, "pageSize": page_size}
        if keyword:
            params["keyword"] = self._bounded_text(keyword, "keyword", 200)
        return self.request("GET", "/admin/projects", params=params)

    def list_tasks(self, project_id: str, keyword: str | None, page: int = 1, page_size: int = 100) -> Any:
        project_id = _require_id(project_id, "project_id")
        self._validate_page(page, page_size)
        params: dict[str, Any] = {"projectId": project_id, "taskType": "REQUIREMENT", "page": page, "pageSize": page_size}
        if keyword:
            params["keyword"] = self._bounded_text(keyword, "keyword", 200)
        return self.request("GET", "/admin/rd-tasks", params=params)

    def get_task(self, task_id: str) -> Any:
        task_id = _require_id(task_id, "task_id")
        return self.request("GET", f"/admin/rd-tasks/{task_id}")

    def get_timeline(self, task_id: str) -> Any:
        task_id = _require_id(task_id, "task_id")
        return self.request("GET", f"/admin/rd-tasks/{task_id}/timeline")

    def get_execution_overview(self, task_id: str) -> Any:
        task_id = _require_id(task_id, "task_id")
        return self.request("GET", f"/admin/rd-tasks/{task_id}/execution-overview")

    def get_execution_trace(self, task_id: str, stage_run_id: str) -> Any:
        task_id = _require_id(task_id, "task_id")
        stage_run_id = _require_id(stage_run_id, "stage_run_id")
        return self.request("GET", f"/admin/rd-tasks/{task_id}/stage-runs/{stage_run_id}/execution-trace")

    def get_retry_preview(self, task_id: str) -> Any:
        task_id = _require_id(task_id, "task_id")
        return self.request("GET", f"/admin/rd-tasks/{task_id}/retry-preview")

    def get_retry_history(self, task_id: str) -> Any:
        task_id = _require_id(task_id, "task_id")
        return self.request("GET", f"/admin/rd-tasks/{task_id}/retry-history")

    def create_requirement(self, payload: Mapping[str, Any]) -> Any:
        self._validate_requirement(payload)
        return self.request("POST", "/admin/rd-tasks/requirements", payload)

    def submit_task(self, task_id: str) -> Any:
        task_id = _require_id(task_id, "task_id")
        return self.request("POST", f"/admin/rd-tasks/{task_id}/submit", {})

    def retry_task(self, task_id: str, payload: Mapping[str, Any]) -> Any:
        task_id = _require_id(task_id, "task_id")
        if not isinstance(payload, Mapping) or len(payload) > 20:
            raise ClientPolicyError("retry payload must be a bounded JSON object")
        return self.request("POST", f"/admin/rd-tasks/{task_id}/retry", payload)

    def list_evaluations(self, keyword: str | None = None, page: int = 1, page_size: int = 100) -> Any:
        self._validate_page(page, page_size)
        params: dict[str, Any] = {"page": page, "pageSize": page_size}
        if keyword:
            params["keyword"] = self._bounded_text(keyword, "keyword", 200)
        return self.request("GET", "/admin/evaluations/runs", params=params)

    def create_task_evaluation(self, task_id: str, payload: Mapping[str, Any]) -> Any:
        task_id = _require_id(task_id, "task_id")
        if not isinstance(payload, Mapping):
            raise ClientPolicyError("evaluation payload must be a JSON object")
        self._validate_evaluation(payload)
        return self.request("POST", f"/admin/rd-tasks/{task_id}/evaluations", payload)

    def get_evaluation(self, run_id: str) -> Any:
        if not isinstance(run_id, str) or not _RUN_ID_RE.fullmatch(run_id):
            raise ClientPolicyError("run_id contains unsafe characters")
        return self.request("GET", f"/admin/evaluations/runs/{run_id}")

    def get_evaluation_timeline(self, run_id: str) -> Any:
        if not isinstance(run_id, str) or not _RUN_ID_RE.fullmatch(run_id):
            raise ClientPolicyError("run_id contains unsafe characters")
        return self.request("GET", f"/admin/evaluations/runs/{run_id}/timeline")

    def get_evaluation_artifacts(self, run_id: str) -> Any:
        if not isinstance(run_id, str) or not _RUN_ID_RE.fullmatch(run_id):
            raise ClientPolicyError("run_id contains unsafe characters")
        return self.request("GET", f"/admin/evaluations/runs/{run_id}/artifacts")

    @staticmethod
    def _bounded_text(value: Any, label: str, maximum: int) -> str:
        if not isinstance(value, str) or not value.strip() or len(value) > maximum:
            raise ClientPolicyError(f"{label} must be non-empty and <= {maximum} characters")
        return value.strip()

    @staticmethod
    def _validate_page(page: int, page_size: int) -> None:
        if isinstance(page, bool) or not isinstance(page, int) or page < 1:
            raise ClientPolicyError("page must be a positive integer")
        if isinstance(page_size, bool) or not isinstance(page_size, int) or not 1 <= page_size <= 100:
            raise ClientPolicyError("page_size must be between 1 and 100")

    @classmethod
    def _validate_requirement(cls, payload: Mapping[str, Any]) -> None:
        title = cls._bounded_text(payload.get("title"), "title", MAX_TITLE_CHARS)
        if not _NUMERIC_ID_RE.fullmatch(str(payload.get("projectId", ""))):
            raise ClientPolicyError("projectId must be a numeric ID")
        cls._bounded_text(payload.get("expectedResult"), "expectedResult", 20_000)
        criteria = payload.get("acceptanceCriteria")
        if not isinstance(criteria, list) or not 2 <= len(criteria) <= 6:
            raise ClientPolicyError("acceptanceCriteria must contain 2 to 6 items")
        for criterion in criteria:
            cls._bounded_text(criterion, "acceptanceCriteria item", 1_000)
        if payload.get("autoExecute") is not False:
            raise ClientPolicyError("autoExecute must be false")
        budget = payload.get("tokenBudgetOverride")
        if budget is not None and (isinstance(budget, bool) or not isinstance(budget, int) or budget < 0):
            raise ClientPolicyError("tokenBudgetOverride must be a non-negative integer")
        materials = payload.get("materials", [])
        if not isinstance(materials, list) or len(materials) > 20:
            raise ClientPolicyError("materials must be a list of at most 20 items")
        total = 0
        for material in materials:
            if not isinstance(material, Mapping):
                raise ClientPolicyError("each material must be an object")
            content = material.get("content", "")
            if not isinstance(content, str) or len(content) > MAX_MATERIAL_CHARS:
                raise ClientPolicyError("material content is too large")
            total += len(content)
        if total > MAX_MATERIAL_TOTAL_CHARS:
            raise ClientPolicyError("material content is too large")
        # Keep the local variable intentional: validation above must run even for a blank title.
        _ = title

    @staticmethod
    def _validate_evaluation(payload: Mapping[str, Any]) -> None:
        if payload.get("judgeProvider") != "NONE":
            raise ClientPolicyError("judgeProvider must be NONE")
        if payload.get("judgeLimit") != 0:
            raise ClientPolicyError("judgeLimit must be 0")
        timeout = payload.get("timeoutSeconds")
        if isinstance(timeout, bool) or not isinstance(timeout, int) or not 1 <= timeout <= 90:
            raise ClientPolicyError("timeoutSeconds must be between 1 and 90")
        if payload.get("baselineRunId", "") not in {"", None}:
            raise ClientPolicyError("baselineRunId must be empty")
