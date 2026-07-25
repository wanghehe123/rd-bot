#!/usr/bin/env python3
"""Shared helpers for the local SWE-bench Lite pilot scripts.

The manifest intentionally contains only task metadata that an agent may see.
Reference patches, evaluator test payloads, and hint fields never enter these
helpers or any generated RD-Bot knowledge document.
"""

from __future__ import annotations

import hashlib
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_RUN_ROOT = REPO_ROOT / "qa-runs" / "swebench-lite-10"
DEFAULT_MANIFEST_PATH = DEFAULT_RUN_ROOT / "manifest.jsonl"
DEFAULT_RD_BOT_BASE_URL = "http://127.0.0.1:18080"
DEFAULT_CHECKOUT_ROOT = Path("/Volumes/WishDisk/codes/swe")

SAFE_MANIFEST_FIELDS = frozenset(
    {
        "repo",
        "instance_id",
        "base_commit",
        "problem_statement",
        "environment_setup_commit",
    }
)
FORBIDDEN_MANIFEST_FIELDS = frozenset(
    {
        "patch",
        "test_patch",
        "fail_to_pass",
        "pass_to_pass",
        "hints_text",
    }
)
TASK_CONTEXT_SOURCE_NAME = "swebench-lite-task-context.md"
REPOSITORY_DOCUMENT_PREFIX = "swebench-lite-repository-"
AGENT_IDS = ("claude-code", "rd-bot")


class ApiError(RuntimeError):
    """An RD-Bot admin API request could not be completed."""


class RdBotApi:
    """Small standard-library HTTP client for the local RD-Bot admin APIs."""

    def __init__(self, base_url: str, timeout_seconds: float = 45.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds

    def request(self, method: str, path: str, payload: Any | None = None) -> Any:
        body = None
        headers = {"Accept": "application/json"}
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(
            self.base_url + path,
            data=body,
            headers=headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                raw = response.read()
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")[:2_000]
            raise ApiError(f"{method} {path} failed with HTTP {error.code}: {detail}") from error
        except urllib.error.URLError as error:
            raise ApiError(f"{method} {path} failed: {error.reason}") from error
        if not raw:
            return None
        try:
            return json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError as error:
            preview = raw.decode("utf-8", errors="replace")[:500]
            raise ApiError(f"{method} {path} returned invalid JSON: {preview}") from error

    def assert_ready(self) -> None:
        self.request("GET", "/rag/settings")

    def list_knowledge_bases(self, name: str | None = None) -> list[dict[str, Any]]:
        params: dict[str, Any] = {"current": 1, "size": 100}
        if name:
            params["name"] = name
        response = self.request("GET", "/knowledge-base?" + urllib.parse.urlencode(params))
        return list(response.get("records", []))

    def list_projects(self, keyword: str | None = None) -> list[dict[str, Any]]:
        params: dict[str, Any] = {"page": 1, "pageSize": 100}
        if keyword:
            params["keyword"] = keyword
        response = self.request("GET", "/admin/projects?" + urllib.parse.urlencode(params))
        return list(response.get("records", []))

    def get_project(self, project_id: str) -> dict[str, Any]:
        return dict(self.request("GET", f"/admin/projects/{urllib.parse.quote(project_id, safe='')}"))

    def get_knowledge_base(self, knowledge_base_id: str) -> dict[str, Any]:
        return dict(
            self.request("GET", f"/knowledge-base/{urllib.parse.quote(knowledge_base_id, safe='')}")
        )

    def list_documents(self, knowledge_base_id: str) -> list[dict[str, Any]]:
        records: list[dict[str, Any]] = []
        current = 1
        while True:
            params = {"current": current, "size": 100}
            response = self.request(
                "GET",
                f"/knowledge-base/{urllib.parse.quote(knowledge_base_id, safe='')}/docs?"
                + urllib.parse.urlencode(params),
            )
            records.extend(response.get("records", []))
            pages = int(response.get("pages", 0) or 0)
            if pages <= current:
                return records
            current += 1


def utc_now() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def normalize_newlines(value: str) -> str:
    """Use one on-disk newline form so prompt checksums are reproducible."""
    return value.replace("\r\n", "\n").replace("\r", "\n")


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise ValueError(f"required file does not exist: {path}") from error
    except json.JSONDecodeError as error:
        raise ValueError(f"invalid JSON in {path}: {error}") from error


def load_manifest(path: Path, expected_count: int = 10) -> list[dict[str, str]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except FileNotFoundError as error:
        raise ValueError(f"manifest does not exist: {path}") from error

    rows: list[dict[str, str]] = []
    for line_number, raw_line in enumerate(lines, start=1):
        if not raw_line.strip():
            continue
        try:
            row = json.loads(raw_line)
        except json.JSONDecodeError as error:
            raise ValueError(f"invalid JSONL at {path}:{line_number}: {error}") from error
        if not isinstance(row, dict):
            raise ValueError(f"manifest row {line_number} is not a JSON object")
        rows.append({str(key): value for key, value in row.items()})
    validate_manifest(rows, expected_count)
    return rows


def validate_manifest(rows: list[dict[str, Any]], expected_count: int) -> None:
    if len(rows) != expected_count:
        raise ValueError(f"expected {expected_count} manifest rows, found {len(rows)}")

    seen_instance_ids: set[str] = set()
    for index, row in enumerate(rows, start=1):
        fields = frozenset(row.keys())
        leaked = sorted(field for field in fields if field.lower() in FORBIDDEN_MANIFEST_FIELDS)
        if leaked:
            raise ValueError(f"manifest row {index} includes forbidden evaluator fields: {', '.join(leaked)}")
        missing = sorted(SAFE_MANIFEST_FIELDS - fields)
        unexpected = sorted(fields - SAFE_MANIFEST_FIELDS)
        if missing or unexpected:
            details = []
            if missing:
                details.append("missing " + ", ".join(missing))
            if unexpected:
                details.append("unexpected " + ", ".join(unexpected))
            raise ValueError(f"manifest row {index} does not match the safe schema: {'; '.join(details)}")

        instance_id = require_text(row, "instance_id", index)
        if instance_id in seen_instance_ids:
            raise ValueError(f"manifest contains a duplicate instance_id: {instance_id}")
        seen_instance_ids.add(instance_id)
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", instance_id):
            raise ValueError(f"manifest row {index} has an unsafe instance_id: {instance_id}")
        repo = require_text(row, "repo", index)
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
            raise ValueError(f"manifest row {index} has an invalid repository: {repo}")
        for field in ("base_commit", "environment_setup_commit"):
            value = require_text(row, field, index)
            if not re.fullmatch(r"[0-9a-f]{40}", value):
                raise ValueError(f"manifest row {index} has an invalid {field}: {value}")
        require_text(row, "problem_statement", index)


def require_text(row: dict[str, Any], field: str, row_index: int | None = None) -> str:
    value = row.get(field)
    if not isinstance(value, str) or not value.strip():
        label = f"manifest row {row_index}" if row_index is not None else "row"
        raise ValueError(f"{label} has no non-empty {field}")
    return value.strip()


def repo_slug(repo: str) -> str:
    return repo.replace("/", "__")


def repository_url(row: dict[str, Any]) -> str:
    return f"https://github.com/{row['repo']}.git"


def knowledge_base_name(row: dict[str, Any]) -> str:
    return f"SWE-bench Lite - {row['instance_id']}"


def project_key(row: dict[str, Any]) -> str:
    return f"swebench-lite-{row['instance_id']}"


def project_name(row: dict[str, Any]) -> str:
    return f"SWE-bench Lite {row['instance_id']}"


def project_payload(row: dict[str, Any], knowledge_base_id: str) -> dict[str, Any]:
    owner, name = row["repo"].split("/", maxsplit=1)
    return {
        "projectKey": project_key(row),
        "name": project_name(row),
        "description": (
            f"SWE-bench Lite test task {row['instance_id']}; "
            f"exact base commit {row['base_commit']}."
        ),
        "repositoryUrl": repository_url(row),
        "repoOwner": owner,
        "repoName": name,
        # RD-Bot's project schema calls this field defaultBranch. For a SWE-bench
        # project it must be the immutable benchmark revision, not a moving branch.
        "defaultBranch": row["base_commit"],
        "enabled": True,
        "knowledgeBaseId": knowledge_base_id,
    }


def task_context_document(row: dict[str, Any]) -> str:
    return "\n".join(
        [
            "# SWE-bench Lite Evaluation Context",
            "",
            f"- Instance ID: `{row['instance_id']}`",
            f"- Repository: `{row['repo']}`",
            f"- Exact base commit: `{row['base_commit']}`",
            "- Dataset split: `test`",
            "",
            "## Observed behavior and task statement",
            "",
            normalize_newlines(row["problem_statement"]).rstrip(),
            "",
            "## Evaluation objective",
            "",
            "Fix the reported behavior in the exact repository checkout while preserving existing behavior.",
            "Use only the task statement, the exact checkout, and documents from this knowledge base.",
            "",
            "## Benchmark boundaries",
            "",
            "Do not rely on evaluator-only references, held-out checks, or reference answers.",
            "Do not publish commits, branches, pull requests, or remote changes from this task.",
            "",
        ]
    )


def task_prompt(row: dict[str, Any], checkout_path: Path) -> str:
    return "\n".join(
        [
            f"# SWE-bench Lite repair task: {row['instance_id']}",
            "",
            "Work as a software engineer on one isolated repository checkout.",
            "",
            "## Workspace",
            "",
            f"- Repository: `{row['repo']}`",
            f"- Exact base commit: `{row['base_commit']}`",
            f"- Working directory: `{checkout_path}`",
            "",
            "Do not switch commits. Do not commit, push, open a pull request, or access benchmark reference",
            "answers or held-out checks. Work only in the stated checkout and use ordinary repository tooling.",
            "",
            "## Reported behavior",
            "",
            normalize_newlines(row["problem_statement"]).rstrip(),
            "",
            "## Goal",
            "",
            "Implement the smallest correct production fix for the reported behavior. Preserve unrelated behavior.",
            "Investigate the existing code and tests before changing files. Run focused tests when practical.",
            "",
            "## Completion report",
            "",
            "When finished, state the changed files, why the change fixes the issue, and every test command run",
            "with its result. Leave the working tree with the implementation diff available for collection.",
            "",
        ]
    )


def checkout_path(root: Path, row: dict[str, Any]) -> Path:
    return root / "worktrees" / row["instance_id"]


def agent_checkout_path(root: Path, row: dict[str, Any], agent_id: str) -> Path:
    if agent_id not in AGENT_IDS:
        raise ValueError(f"unknown benchmark agent: {agent_id}")
    if agent_id == "claude-code":
        return checkout_path(root, row)
    return root / "worktrees" / agent_id / row["instance_id"]


def agent_prompt_path(output_root: Path, instance_id: str, agent_id: str) -> Path:
    if agent_id not in AGENT_IDS:
        raise ValueError(f"unknown benchmark agent: {agent_id}")
    return output_root / "prompts" / agent_id / f"{instance_id}.md"


def agent_plan_key(agent_id: str) -> str:
    if agent_id not in AGENT_IDS:
        raise ValueError(f"unknown benchmark agent: {agent_id}")
    return agent_id.replace("-", "_")


def repository_clone_path(root: Path, row: dict[str, Any]) -> Path:
    return root / "repositories" / repo_slug(row["repo"])


def repository_document_source_name(relative_path: str) -> str:
    normalized = relative_path.replace("\\", "/").strip("/")
    safe = re.sub(r"[^A-Za-z0-9._-]+", "-", normalized.replace("/", "--"))
    return REPOSITORY_DOCUMENT_PREFIX + safe


def find_exact(items: list[dict[str, Any]], field: str, expected: str) -> dict[str, Any] | None:
    matches = [item for item in items if item.get(field) == expected]
    if len(matches) > 1:
        raise ValueError(f"more than one item has {field}={expected!r}")
    return matches[0] if matches else None


def wait_for_indexed_documents(
    client: RdBotApi,
    knowledge_base_id: str,
    source_names: set[str],
    timeout_seconds: float,
) -> dict[str, dict[str, Any]]:
    deadline = time.monotonic() + timeout_seconds
    while True:
        documents = {
            str(item.get("sourceName")): item
            for item in client.list_documents(knowledge_base_id)
            if item.get("sourceName") in source_names
        }
        missing = source_names - documents.keys()
        not_indexed = {
            source_name: document.get("status")
            for source_name, document in documents.items()
            if document.get("status") != "INDEXED"
        }
        if not missing and not not_indexed:
            return documents
        if time.monotonic() >= deadline:
            details = []
            if missing:
                details.append("missing=" + ", ".join(sorted(missing)))
            if not_indexed:
                details.append(
                    "not indexed="
                    + ", ".join(f"{name}:{status}" for name, status in sorted(not_indexed.items()))
                )
            raise RuntimeError(
                f"knowledge base {knowledge_base_id} did not finish indexing within {timeout_seconds}s: "
                + "; ".join(details)
            )
        time.sleep(0.5)


def relative_to_repo(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(REPO_ROOT))
    except ValueError:
        return str(path.resolve())
