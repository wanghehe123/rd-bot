"""Fail-closed, loopback-only RD-Bot Admin API client for the prototype skill."""

from __future__ import annotations

import hashlib
import ipaddress
import json
import os
import re
import socket
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
MAX_REQUEST_BYTES = 256 * 1024
_NUMERIC_ID_RE = re.compile(r"^[0-9]{1,64}$")
_RUN_ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,120}$")
_RESOURCE_ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,120}$")
_REPO_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._/-]{0,199}$")
_GITHUB_OWNER_RE = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")
_GITHUB_REPOSITORY_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,99}$")
_PROJECT_KEY_RE = re.compile(r"^[a-z0-9][a-z0-9-]{1,99}$")
_CREDENTIAL_MARKER_RE = re.compile(r"(?:\bBearer\s+|\b(?:sk|key|token)[-_][A-Za-z0-9_-]{12,})", re.IGNORECASE)


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


def _require_resource_id(value: str, label: str) -> str:
    if not isinstance(value, str) or not _RESOURCE_ID_RE.fullmatch(value):
        raise ClientPolicyError(f"{label} contains unsafe characters")
    return value


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_args: Any, **_kwargs: Any) -> urllib.request.Request:
        raise ApiTransportError("redirect rejected", ambiguous=False)


class SafeRdBotClient:
    """Small typed client with an exact route allowlist and double live-write opt-in."""

    _GET_ROUTES = (
        re.compile(r"^/knowledge-base$"),
        re.compile(r"^/knowledge-base/[A-Za-z0-9._-]{1,120}$"),
        re.compile(r"^/knowledge-base/[A-Za-z0-9._-]{1,120}/docs$"),
        re.compile(r"^/admin/projects$"),
        re.compile(r"^/admin/projects/[0-9]{1,64}$"),
        re.compile(r"^/admin/rd-tasks$"),
        re.compile(r"^/admin/rd-tasks/[0-9]{1,64}$"),
        re.compile(r"^/admin/rd-tasks/[0-9]{1,64}/timeline$"),
        re.compile(r"^/admin/rd-tasks/[0-9]{1,64}/execution-overview$"),
        re.compile(r"^/admin/rd-tasks/[0-9]{1,64}/stage-runs/[A-Za-z0-9._-]{1,120}/execution-trace$"),
        re.compile(r"^/admin/rd-tasks/[0-9]{1,64}/retry-preview$"),
        re.compile(r"^/admin/rd-tasks/[0-9]{1,64}/retry-history$"),
        re.compile(r"^/admin/evaluations/runs$"),
        re.compile(r"^/admin/evaluations/runs/[A-Za-z0-9._-]{1,120}$"),
        re.compile(r"^/admin/evaluations/runs/[A-Za-z0-9._-]{1,120}/timeline$"),
        re.compile(r"^/admin/evaluations/runs/[A-Za-z0-9._-]{1,120}/artifacts$"),
    )
    _POST_ROUTES = (
        re.compile(r"^/knowledge-base$"),
        re.compile(r"^/knowledge-base/[A-Za-z0-9._-]{1,120}/docs/write$"),
        re.compile(r"^/admin/projects$"),
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
        if mode not in {"dry-run", "live-test", "live-provision"}:
            raise ClientPolicyError("mode must be dry-run, live-test, or live-provision")
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
        try:
            return ipaddress.ip_address(hostname).is_loopback
        except ValueError:
            try:
                infos = socket.getaddrinfo(hostname, None, type=socket.SOCK_STREAM)
            except OSError:
                return False
            addresses = {str(info[4][0]) for info in infos if info and info[4]}
            if not addresses:
                return False
            try:
                return all(ipaddress.ip_address(address).is_loopback for address in addresses)
            except ValueError:
                return False

    def _ensure_live_write(self, *, provisioning: bool = False) -> None:
        if self.mode == "live-test":
            if provisioning:
                raise ClientPolicyError("project provisioning requires live-provision mode and --live-provision")
            if not self.live_flag:
                raise ClientPolicyError("POST requires live-test mode and --live-test")
            if self.environ.get("RD_BOT_AUTOPILOT_LIVE_TEST") != "1":
                raise ClientPolicyError("RD_BOT_AUTOPILOT_LIVE_TEST=1 is required")
            return
        if self.mode == "live-provision":
            if not self.live_flag:
                raise ClientPolicyError("POST requires live-provision mode and --live-provision")
            if self.environ.get("RD_BOT_AUTOPILOT_LIVE_PROVISION") != "1":
                raise ClientPolicyError("RD_BOT_AUTOPILOT_LIVE_PROVISION=1 is required")
            return
        raise ClientPolicyError("POST requires live-test or live-provision mode")

    def preflight_write(self) -> None:
        self._ensure_live_write()

    @staticmethod
    def _is_provisioning_route(path: str) -> bool:
        return (
            path == "/knowledge-base"
            or path == "/admin/projects"
            or bool(re.fullmatch(r"/knowledge-base/[A-Za-z0-9._-]{1,120}/docs/write", path))
        )

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
            self._ensure_live_write(provisioning=self._is_provisioning_route(path))
            if payload is None or not isinstance(payload, Mapping):
                raise ClientPolicyError("POST payload must be a JSON object")
            self._validate_route_payload(path, payload)
            body = _canonical_json(payload)
            if len(body) > MAX_REQUEST_BYTES:
                raise ClientPolicyError("request body is too large")
            query_payload: Any = payload
        else:
            if payload is not None:
                raise ClientPolicyError("GET requests cannot contain a body")
            self._validate_query(path, params)
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

    @classmethod
    def _validate_route_payload(cls, path: str, payload: Mapping[str, Any]) -> None:
        if path == "/knowledge-base":
            cls._validate_knowledge_base(payload)
        elif re.fullmatch(r"/knowledge-base/[A-Za-z0-9._-]{1,120}/docs/write", path):
            cls._validate_knowledge_document(payload)
        elif path == "/admin/projects":
            cls._validate_project(payload)
        elif path == "/admin/rd-tasks/requirements":
            cls._validate_requirement(payload)
        elif path.endswith("/submit"):
            if payload:
                raise ClientPolicyError("submit payload must be empty")
        elif path.endswith("/retry"):
            cls._validate_retry(payload)
        elif path.endswith("/evaluations"):
            cls._validate_evaluation(payload)

    @staticmethod
    def _validate_query(path: str, params: Mapping[str, Any] | None) -> None:
        if not params:
            return
        if re.fullmatch(r"/knowledge-base/[A-Za-z0-9._-]{1,120}/docs", path):
            allowed = {"current", "size", "status", "keyword"}
        else:
            allowed = {
                "/knowledge-base": {"name", "current", "size"},
                "/admin/projects": {"keyword", "page", "pageSize"},
                "/admin/rd-tasks": {"projectId", "taskType", "keyword", "page", "pageSize"},
                "/admin/evaluations/runs": {"keyword", "datasetKind", "status", "page", "pageSize"},
            }.get(path, set())
        if set(params) - allowed:
            raise ClientPolicyError(f"query parameters for {path} are not allowlisted")

    def get_knowledge_base(self, knowledge_base_id: str) -> Any:
        knowledge_base_id = _require_resource_id(knowledge_base_id, "knowledge_base_id")
        return self.request("GET", f"/knowledge-base/{knowledge_base_id}")

    def list_knowledge_bases(self, name: str | None = None, page: int = 1, page_size: int = 100) -> Any:
        self._validate_page(page, page_size)
        params: dict[str, Any] = {"current": page, "size": page_size}
        if name:
            params["name"] = self._bounded_text(name, "knowledge base name", 120)
        return self.request("GET", "/knowledge-base", params=params)

    def list_knowledge_documents(
        self,
        knowledge_base_id: str,
        keyword: str | None = None,
        page: int = 1,
        page_size: int = 100,
    ) -> Any:
        knowledge_base_id = _require_resource_id(knowledge_base_id, "knowledge_base_id")
        self._validate_page(page, page_size)
        params: dict[str, Any] = {"current": page, "size": page_size}
        if keyword:
            params["keyword"] = self._bounded_text(keyword, "knowledge document keyword", 200)
        return self.request("GET", f"/knowledge-base/{knowledge_base_id}/docs", params=params)

    def create_knowledge_base(self, payload: Mapping[str, Any]) -> Any:
        self._validate_knowledge_base(payload)
        return self.request("POST", "/knowledge-base", payload)

    def preflight_knowledge_base(self, payload: Mapping[str, Any]) -> None:
        self._ensure_live_write(provisioning=True)
        self._validate_knowledge_base(payload)

    def write_knowledge_document(self, knowledge_base_id: str, payload: Mapping[str, Any]) -> Any:
        knowledge_base_id = _require_resource_id(knowledge_base_id, "knowledge_base_id")
        self._validate_knowledge_document(payload)
        return self.request("POST", f"/knowledge-base/{knowledge_base_id}/docs/write", payload)

    def preflight_knowledge_document(self, knowledge_base_id: str, payload: Mapping[str, Any]) -> None:
        self._ensure_live_write(provisioning=True)
        _require_resource_id(knowledge_base_id, "knowledge_base_id")
        self._validate_knowledge_document(payload)

    def create_project(self, payload: Mapping[str, Any]) -> Any:
        self._validate_project(payload)
        return self.request("POST", "/admin/projects", payload)

    def preflight_project(self, payload: Mapping[str, Any]) -> None:
        self._ensure_live_write(provisioning=True)
        self._validate_project(payload)

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
        if not isinstance(stage_run_id, str) or not _RUN_ID_RE.fullmatch(stage_run_id):
            raise ClientPolicyError("stage_run_id contains unsafe characters")
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

    def validate_requirement(self, payload: Mapping[str, Any]) -> None:
        """Validate a requirement locally without checking live-write permission."""

        self._validate_requirement(payload)

    def preflight_requirement(self, payload: Mapping[str, Any]) -> None:
        """Validate live-write permission and payload before an intent is persisted."""

        self._ensure_live_write()
        self._validate_requirement(payload)

    def submit_task(self, task_id: str) -> Any:
        task_id = _require_id(task_id, "task_id")
        return self.request("POST", f"/admin/rd-tasks/{task_id}/submit", {})

    def preflight_submit(self, task_id: str) -> None:
        self._ensure_live_write()
        _require_id(task_id, "task_id")

    def retry_task(self, task_id: str, payload: Mapping[str, Any]) -> Any:
        task_id = _require_id(task_id, "task_id")
        if not isinstance(payload, Mapping) or len(payload) > 20:
            raise ClientPolicyError("retry payload must be a bounded JSON object")
        return self.request("POST", f"/admin/rd-tasks/{task_id}/retry", payload)

    def preflight_retry(self, task_id: str, payload: Mapping[str, Any]) -> None:
        self._ensure_live_write()
        task_id = _require_id(task_id, "task_id")
        if not isinstance(payload, Mapping) or len(payload) > 20:
            raise ClientPolicyError("retry payload must be a bounded JSON object")
        self._validate_retry(payload)

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

    def preflight_evaluation(self, task_id: str, payload: Mapping[str, Any]) -> None:
        self._ensure_live_write()
        _require_id(task_id, "task_id")
        if not isinstance(payload, Mapping):
            raise ClientPolicyError("evaluation payload must be a JSON object")
        self._validate_evaluation(payload)

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

    @staticmethod
    def _reject_credentials(value: str, label: str) -> None:
        if _CREDENTIAL_MARKER_RE.search(value):
            raise ClientPolicyError(f"{label} must not contain credentials")

    @staticmethod
    def _require_autopilot_marker(value: str, kind: str, label: str) -> None:
        pattern = re.compile(
            rf"\[autopilot:[a-z0-9][a-z0-9-]{{1,80}}:{re.escape(kind)}:[0-9a-f]{{12}}\]"
        )
        if not pattern.search(value):
            raise ClientPolicyError(f"{label} must contain the expected autopilot marker")

    @classmethod
    def _validate_knowledge_base(cls, payload: Mapping[str, Any]) -> None:
        expected = {"name", "description"}
        unknown = set(payload) - expected
        if unknown:
            raise ClientPolicyError("knowledge base payload contains unknown fields")
        if set(payload) != expected:
            raise ClientPolicyError("knowledge base payload schema is incomplete")
        name = cls._bounded_text(payload.get("name"), "knowledge base name", 120)
        description = cls._bounded_text(payload.get("description"), "knowledge base description", 500)
        cls._reject_credentials(name, "knowledge base name")
        cls._reject_credentials(description, "knowledge base description")
        cls._require_autopilot_marker(description, "knowledge", "knowledge base description")

    @classmethod
    def _validate_knowledge_document(cls, payload: Mapping[str, Any]) -> None:
        if "sourceUri" in payload:
            raise ClientPolicyError("knowledge document sourceUri is not allowed")
        expected = {
            "sourceName", "knowledgeType", "mimeType", "content", "chunkingMode", "chunkSize", "overlapSize",
        }
        unknown = set(payload) - expected
        if unknown:
            raise ClientPolicyError("knowledge document payload contains unknown fields")
        if set(payload) != expected:
            raise ClientPolicyError("knowledge document payload schema is incomplete")
        expected_type = {
            "project-charter.md": ("PROJECT_CHARTER", "source-1"),
            "delivery-brief.md": ("DELIVERY_BRIEF", "source-2"),
        }.get(payload.get("sourceName"))
        if expected_type is None:
            raise ClientPolicyError("knowledge document sourceName is not allowed")
        if payload.get("knowledgeType") != expected_type[0]:
            raise ClientPolicyError("knowledge document knowledgeType does not match sourceName")
        if payload.get("mimeType") != "text/markdown":
            raise ClientPolicyError("knowledge document mimeType must be text/markdown")
        if payload.get("chunkingMode") != "STRUCTURE_AWARE":
            raise ClientPolicyError("knowledge document chunkingMode must be STRUCTURE_AWARE")
        if payload.get("chunkSize") != 512 or payload.get("overlapSize") != 64:
            raise ClientPolicyError("knowledge document chunk settings must be 512 and 64")
        content = cls._bounded_text(payload.get("content"), "knowledge document content", 20_000)
        cls._reject_credentials(content, "knowledge document content")
        cls._require_autopilot_marker(content, expected_type[1], "knowledge document content")

    @classmethod
    def _validate_project(cls, payload: Mapping[str, Any]) -> None:
        expected = {
            "projectKey", "name", "description", "repositoryUrl", "repoOwner", "repoName",
            "defaultBranch", "enabled", "knowledgeBaseId",
        }
        unknown = set(payload) - expected
        if unknown:
            raise ClientPolicyError("project payload contains unknown fields")
        if set(payload) != expected:
            raise ClientPolicyError("project payload schema is incomplete")
        project_key = cls._bounded_text(payload.get("projectKey"), "projectKey", 100)
        if not _PROJECT_KEY_RE.fullmatch(project_key) or not project_key.startswith("autopilot-"):
            raise ClientPolicyError("projectKey must be an autopilot-safe key")
        name = cls._bounded_text(payload.get("name"), "project name", 120)
        description = cls._bounded_text(payload.get("description"), "project description", 2_000)
        cls._reject_credentials(name, "project name")
        cls._reject_credentials(description, "project description")
        cls._require_autopilot_marker(description, "project", "project description")
        owner = cls._bounded_text(payload.get("repoOwner"), "repoOwner", 39)
        repository = cls._bounded_text(payload.get("repoName"), "repoName", 100)
        if not _GITHUB_OWNER_RE.fullmatch(owner) or not _GITHUB_REPOSITORY_RE.fullmatch(repository):
            raise ClientPolicyError("project repository identity contains unsafe characters")
        repository_url = cls._bounded_text(payload.get("repositoryUrl"), "repositoryUrl", 2_000)
        cls._validate_safe_uri(repository_url, "repositoryUrl")
        parsed = urllib.parse.urlsplit(repository_url)
        try:
            has_explicit_port = parsed.port is not None
        except ValueError as exc:
            raise ClientPolicyError("repositoryUrl is invalid") from exc
        if (
            parsed.scheme != "https"
            or parsed.hostname != "github.com"
            or has_explicit_port
            or parsed.query
            or parsed.fragment
            or parsed.path != f"/{owner}/{repository}.git"
        ):
            raise ClientPolicyError("repositoryUrl must be the exact GitHub repository URL")
        if payload.get("defaultBranch") != "main":
            raise ClientPolicyError("defaultBranch must be main")
        if payload.get("enabled") is not True:
            raise ClientPolicyError("project enabled must be true")
        _require_resource_id(payload.get("knowledgeBaseId"), "knowledgeBaseId")

    @classmethod
    def _validate_requirement(cls, payload: Mapping[str, Any]) -> None:
        allowed = {
            "title", "priority", "projectId", "repositoryUrl", "repoOwner", "repoName", "baseBranch",
            "expectedResult", "acceptanceCriteria", "materials", "autoExecute", "tokenBudgetOverride",
        }
        if set(payload) - allowed:
            raise ClientPolicyError("requirement payload contains unknown fields")
        title = cls._bounded_text(payload.get("title"), "title", MAX_TITLE_CHARS)
        priority = payload.get("priority", "P2")
        if priority not in {"P0", "P1", "P2", "P3"}:
            raise ClientPolicyError("priority must be P0, P1, P2, or P3")
        if not _NUMERIC_ID_RE.fullmatch(str(payload.get("projectId", ""))):
            raise ClientPolicyError("projectId must be a numeric ID")
        for key, maximum in (("repositoryUrl", 2_000), ("repoOwner", 200), ("repoName", 200), ("baseBranch", 200)):
            if key in payload and (not isinstance(payload[key], str) or len(payload[key]) > maximum):
                raise ClientPolicyError(f"{key} must be a bounded string")
            if key == "repositoryUrl" and key in payload and payload[key]:
                cls._validate_safe_uri(payload[key], key)
            if key != "repositoryUrl" and key in payload and payload[key] and not _REPO_NAME_RE.fullmatch(payload[key]):
                raise ClientPolicyError(f"{key} contains unsafe characters")
        cls._bounded_text(payload.get("expectedResult"), "expectedResult", 20_000)
        criteria = payload.get("acceptanceCriteria")
        if not isinstance(criteria, list) or not 2 <= len(criteria) <= 6:
            raise ClientPolicyError("acceptanceCriteria must contain 2 to 6 items")
        for criterion in criteria:
            cls._bounded_text(criterion, "acceptanceCriteria item", 1_000)
        if payload.get("autoExecute") is not False:
            raise ClientPolicyError("autoExecute must be false")
        if "tokenBudgetOverride" in payload:
            budget = payload["tokenBudgetOverride"]
            if isinstance(budget, bool) or not isinstance(budget, int) or not 0 <= budget <= 200_000:
                raise ClientPolicyError("tokenBudgetOverride must be an integer between 0 and 200000")
        materials = payload.get("materials", [])
        if not isinstance(materials, list) or not materials or len(materials) > 20:
            raise ClientPolicyError("materials must be a non-empty list of at most 20 items")
        total = 0
        usable = False
        for material in materials:
            if not isinstance(material, Mapping):
                raise ClientPolicyError("each material must be an object")
            allowed_material_keys = {
                "materialType", "sourceType", "type", "title", "name", "sourceUri", "content",
                "mimeType", "revisionId", "recoveryStageRunId",
            }
            if set(material) - allowed_material_keys:
                raise ClientPolicyError("material contains unknown fields")
            for key, maximum in (("materialType", 80), ("sourceType", 80), ("title", 500), ("sourceUri", 2_000), ("mimeType", 200), ("revisionId", 200), ("recoveryStageRunId", 120)):
                if key in material and (not isinstance(material[key], str) or len(material[key]) > maximum):
                    raise ClientPolicyError(f"material {key} is unbounded")
            for key in ("type", "name"):
                if key in material and (not isinstance(material[key], str) or len(material[key]) > 500):
                    raise ClientPolicyError(f"material {key} is unbounded")
            content = material.get("content", "")
            if not isinstance(content, str) or len(content) > MAX_MATERIAL_CHARS:
                raise ClientPolicyError("material content is too large")
            source_uri = material.get("sourceUri", "")
            if isinstance(source_uri, str) and source_uri:
                cls._validate_safe_uri(source_uri, "material sourceUri")
                raise ClientPolicyError("autopilot material sourceUri is not allowed")
            source_type = material.get("sourceType")
            if source_type is not None and source_type != "MANUAL_TEXT":
                raise ClientPolicyError("autopilot material sourceType must be MANUAL_TEXT")
            if (content.strip() or (isinstance(source_uri, str) and source_uri.strip())):
                usable = True
            total += len(content)
        if total > MAX_MATERIAL_TOTAL_CHARS:
            raise ClientPolicyError("material content is too large")
        if not usable:
            raise ClientPolicyError("at least one material content or sourceUri is required")
        try:
            if len(_canonical_json(payload)) > MAX_REQUEST_BYTES:
                raise ClientPolicyError("request body is too large")
        except (TypeError, ValueError) as exc:
            raise ClientPolicyError("requirement payload is not JSON serializable") from exc
        # Keep the local variable intentional: validation above must run even for a blank title.
        _ = title

    @staticmethod
    def _validate_safe_uri(value: str, label: str) -> None:
        try:
            parsed = urllib.parse.urlsplit(value)
        except ValueError as exc:
            raise ClientPolicyError(f"{label} is invalid") from exc
        if parsed.username is not None or parsed.password is not None or _CREDENTIAL_MARKER_RE.search(value):
            raise ClientPolicyError(f"{label} must not contain credentials")

    @classmethod
    def _validate_retry(cls, payload: Mapping[str, Any]) -> None:
        required = {
            "expectedFailedStageRunId",
            "expectedFailedRetrievalRunId",
            "expectedFailedAiReviewRunId",
            "expectedSourceTaskVersion",
            "operatorNote",
            "evidenceMaterialIds",
        }
        if set(payload) != required:
            raise ClientPolicyError("retry payload schema is incomplete")
        for key in ("expectedFailedStageRunId", "expectedFailedRetrievalRunId", "expectedFailedAiReviewRunId"):
            value = payload.get(key)
            if not isinstance(value, str) or len(value) > 120 or not re.fullmatch(r"[A-Za-z0-9._-]*", value):
                raise ClientPolicyError(f"{key} contains unsafe characters")
        version = payload.get("expectedSourceTaskVersion")
        if isinstance(version, bool) or not isinstance(version, int) or version <= 0:
            raise ClientPolicyError("expectedSourceTaskVersion must be positive")
        cls._bounded_text(payload.get("operatorNote"), "operatorNote", 500)
        evidence = payload.get("evidenceMaterialIds")
        if not isinstance(evidence, list) or len(evidence) > 50 or any(
            not isinstance(item, str) or len(item) > 120 or not re.fullmatch(r"[A-Za-z0-9._-]{1,120}", item)
            for item in evidence
        ):
            raise ClientPolicyError("evidenceMaterialIds must be a bounded list")

    @staticmethod
    def _validate_evaluation(payload: Mapping[str, Any]) -> None:
        allowed = {"judgeProvider", "judgeLimit", "timeoutSeconds", "baselineRunId"}
        if set(payload) != allowed:
            raise ClientPolicyError("evaluation payload schema is incomplete")
        if payload.get("judgeProvider") != "NONE":
            raise ClientPolicyError("judgeProvider must be NONE")
        if payload.get("judgeLimit") != 0:
            raise ClientPolicyError("judgeLimit must be 0")
        timeout = payload.get("timeoutSeconds")
        if isinstance(timeout, bool) or not isinstance(timeout, int) or not 1 <= timeout <= 90:
            raise ClientPolicyError("timeoutSeconds must be between 1 and 90")
        if payload.get("baselineRunId", "") not in {"", None}:
            raise ClientPolicyError("baselineRunId must be empty")
