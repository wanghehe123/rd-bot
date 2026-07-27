"""Pure, bounded Provision Plan construction for RD-Bot project incubation."""

from __future__ import annotations

import copy
import hashlib
import json
import re
from typing import Any, Mapping, Sequence

from .run_artifacts import redact


PLAN_SCHEMA_VERSION = "rd-bot-autopilot-provision-plan/v1"
MAX_IDEA_CHARS = 4_000
MAX_CRITERIA = 6
MAX_DOCUMENT_CHARS = 20_000
MAX_REPOSITORY_CHARS = 100
MAX_OWNER_CHARS = 39
DEFAULT_LIMITS = {
    "maxIterations": 2,
    "maxActiveTasks": 1,
    "maxRetriesPerTask": 1,
    "maxElapsedMinutes": 120,
    "maxTokenBudget": 200_000,
}
_RUN_ID_RE = re.compile(r"^autopilot-[a-z0-9-]{1,80}$")
_OWNER_RE = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")
_REPOSITORY_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,99}$")
_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
_CREDENTIAL_RE = re.compile(
    r"\b(?:ghp|github_pat|sk|key|token)[_-][A-Za-z0-9_-]{12,}\b",
    re.IGNORECASE,
)


class ProvisionPlanError(ValueError):
    """Raised when an idea cannot become a safe, reproducible Provision Plan."""


def canonical_json(value: Mapping[str, Any]) -> str:
    """Return the stable serialization used for plan and document fingerprints."""

    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def plan_digest(plan: Mapping[str, Any]) -> str:
    return _sha256(canonical_json(plan))


def build_provision_plan(
    run_id: str,
    idea: str,
    success_criteria: Sequence[str],
    github_owner: str,
    *,
    repo_name: str | None = None,
    visibility: str = "private",
) -> dict[str, Any]:
    """Build a no-I/O plan for one new private personal project.

    The caller must obtain and verify ``github_owner`` from the narrow GitHub
    client. This module does not call the network or start a subprocess.
    """

    run_id = _require_run_id(run_id)
    clean_idea = _require_text(idea, "idea", MAX_IDEA_CHARS)
    _reject_credentials(clean_idea)
    clean_criteria = _require_criteria(success_criteria)
    owner = _require_owner(github_owner)
    if visibility != "private":
        raise ProvisionPlanError("repository visibility must be private")

    suffix = run_id.removeprefix("autopilot-")
    repository_name = _resolve_repository_name(repo_name, clean_idea, suffix)
    seed = _sha256(canonical_json({"runId": run_id, "owner": owner, "repository": repository_name, "idea": clean_idea}))
    repository_marker = _marker(run_id, "github", seed)
    knowledge_marker = _marker(run_id, "knowledge", seed)
    project_marker = _marker(run_id, "project", seed)
    display_name = _display_name(clean_idea)
    repository_url = f"https://github.com/{owner}/{repository_name}.git"

    plan: dict[str, Any] = {
        "schemaVersion": PLAN_SCHEMA_VERSION,
        "runId": run_id,
        "idea": clean_idea,
        "successCriteria": clean_criteria,
        "limits": dict(DEFAULT_LIMITS),
        "repository": {
            "owner": owner,
            "name": repository_name,
            "visibility": "private",
            "autoInit": True,
            "description": f"{repository_marker} RD-Bot generated project for {display_name}",
            "marker": repository_marker,
        },
        "knowledgeBase": {
            "name": _bounded(f"{display_name} project knowledge", 120),
            "description": f"{knowledge_marker} Generated project knowledge for {display_name}",
            "marker": knowledge_marker,
        },
        "project": {
            "projectKey": _bounded(f"autopilot-{suffix}", 100).lower(),
            "name": display_name,
            "description": f"{project_marker} {clean_idea}",
            "repositoryUrl": repository_url,
            "repoOwner": owner,
            "repoName": repository_name,
            "defaultBranch": "main",
            "enabled": True,
            "marker": project_marker,
        },
        "iterationPlan": {
            "title": _bounded(f"Build the initial {display_name}", 110),
            "goal": clean_idea,
            "nonGoals": ["Do not merge, deploy, or add external credentials."],
            "expectedResult": _bounded(
                f"A working first vertical slice for {display_name} with the stated acceptance criteria.",
                1_500,
            ),
            "acceptanceCriteria": clean_criteria,
            "whyNow": "Establish the smallest testable project slice from the approved idea.",
            "priority": "P2",
            "tokenBudgetOverride": 0,
        },
    }
    plan["documents"] = _render_documents(plan, seed)
    return validate_provision_plan(plan)


def validate_provision_plan(plan: Mapping[str, Any]) -> dict[str, Any]:
    """Fail closed unless a plan has exactly the approved v1 structure."""

    if not isinstance(plan, Mapping):
        raise ProvisionPlanError("provision plan must be an object")
    expected = {
        "schemaVersion", "runId", "idea", "successCriteria", "limits", "repository",
        "knowledgeBase", "documents", "project", "iterationPlan",
    }
    if set(plan) != expected:
        raise ProvisionPlanError("provision plan fields are incomplete or unknown")
    if plan.get("schemaVersion") != PLAN_SCHEMA_VERSION:
        raise ProvisionPlanError("unsupported provision plan schema version")
    run_id = _require_run_id(plan.get("runId"))
    idea = _require_text(plan.get("idea"), "idea", MAX_IDEA_CHARS)
    _reject_credentials(idea)
    criteria = _require_criteria(plan.get("successCriteria"))
    _validate_limits(plan.get("limits"))

    repository = _require_mapping(plan.get("repository"), "repository")
    if set(repository) != {"owner", "name", "visibility", "autoInit", "description", "marker"}:
        raise ProvisionPlanError("repository fields are incomplete or unknown")
    owner = _require_owner(repository.get("owner"))
    name = _require_repository_name(repository.get("name"))
    suffix = run_id.removeprefix("autopilot-")
    if not name.endswith(f"-{suffix}"):
        raise ProvisionPlanError("repository name must include the run suffix")
    if repository.get("visibility") != "private" or repository.get("autoInit") is not True:
        raise ProvisionPlanError("repository must be private and auto initialized")
    _require_marker(repository.get("marker"), run_id, "github")
    _require_text(repository.get("description"), "repository description", 500)

    knowledge = _require_mapping(plan.get("knowledgeBase"), "knowledgeBase")
    if set(knowledge) != {"name", "description", "marker"}:
        raise ProvisionPlanError("knowledge base fields are incomplete or unknown")
    _require_text(knowledge.get("name"), "knowledge base name", 120)
    _require_text(knowledge.get("description"), "knowledge base description", 500)
    _require_marker(knowledge.get("marker"), run_id, "knowledge")

    project = _require_mapping(plan.get("project"), "project")
    if set(project) != {
        "projectKey", "name", "description", "repositoryUrl", "repoOwner", "repoName",
        "defaultBranch", "enabled", "marker",
    }:
        raise ProvisionPlanError("project fields are incomplete or unknown")
    _require_text(project.get("projectKey"), "project key", 100)
    _require_text(project.get("name"), "project name", 120)
    _require_text(project.get("description"), "project description", 2_000)
    if project.get("repositoryUrl") != f"https://github.com/{owner}/{name}.git":
        raise ProvisionPlanError("project repository URL must match the private GitHub repository")
    if project.get("repoOwner") != owner or project.get("repoName") != name:
        raise ProvisionPlanError("project repository identity must match the private GitHub repository")
    if project.get("defaultBranch") != "main" or project.get("enabled") is not True:
        raise ProvisionPlanError("project must use main and be enabled")
    _require_marker(project.get("marker"), run_id, "project")

    documents = plan.get("documents")
    if not isinstance(documents, list) or len(documents) != 2:
        raise ProvisionPlanError("provision plan must contain exactly two generated documents")
    expected_names = ["project-charter.md", "delivery-brief.md"]
    for index, document in enumerate(documents, start=1):
        _validate_document(document, run_id, index, expected_names[index - 1])

    iteration = _require_mapping(plan.get("iterationPlan"), "iterationPlan")
    if set(iteration) != {
        "title", "goal", "nonGoals", "expectedResult", "acceptanceCriteria", "whyNow", "priority", "tokenBudgetOverride",
    }:
        raise ProvisionPlanError("iteration plan fields are incomplete or unknown")
    _require_text(iteration.get("title"), "iteration title", 110)
    _require_text(iteration.get("goal"), "iteration goal", MAX_IDEA_CHARS)
    _require_text(iteration.get("expectedResult"), "expected result", 1_500)
    _require_text(iteration.get("whyNow"), "why now", 1_000)
    if iteration.get("acceptanceCriteria") != criteria:
        raise ProvisionPlanError("iteration criteria must exactly match success criteria")
    if iteration.get("priority") not in {"P0", "P1", "P2", "P3"}:
        raise ProvisionPlanError("iteration priority is invalid")
    budget = iteration.get("tokenBudgetOverride")
    if isinstance(budget, bool) or not isinstance(budget, int) or not 0 <= budget <= DEFAULT_LIMITS["maxTokenBudget"]:
        raise ProvisionPlanError("iteration token budget is invalid")
    non_goals = iteration.get("nonGoals")
    if not isinstance(non_goals, list) or not 1 <= len(non_goals) <= 10:
        raise ProvisionPlanError("iteration non-goals are invalid")
    for value in non_goals:
        _require_text(value, "iteration non-goal", 500)
    return copy.deepcopy(dict(plan))


def _render_documents(plan: Mapping[str, Any], seed: str) -> list[dict[str, Any]]:
    run_id = str(plan["runId"])
    project = _require_mapping(plan["project"], "project")
    iteration = _require_mapping(plan["iterationPlan"], "iterationPlan")
    charter_marker = _marker(run_id, "source-1", seed)
    brief_marker = _marker(run_id, "source-2", seed)
    charter = "\n".join([
        f"# {project['name']} Project Charter",
        "",
        charter_marker,
        "",
        "## Goal",
        str(plan["idea"]),
        "",
        "## Success Criteria",
        *[f"- {criterion}" for criterion in plan["successCriteria"]],
        "",
        "## Boundaries",
        "- No merge, deploy, credential changes, or external-source ingestion.",
    ])
    brief = "\n".join([
        f"# {project['name']} Delivery Brief",
        "",
        brief_marker,
        "",
        "## First Requirement",
        str(iteration["goal"]),
        "",
        "## Expected Result",
        str(iteration["expectedResult"]),
        "",
        "## Acceptance Criteria",
        *[f"- {criterion}" for criterion in iteration["acceptanceCriteria"]],
    ])
    return [
        _document("project-charter.md", "PROJECT_CHARTER", charter_marker, charter),
        _document("delivery-brief.md", "DELIVERY_BRIEF", brief_marker, brief),
    ]


def _document(source_name: str, knowledge_type: str, marker: str, content: str) -> dict[str, Any]:
    clean_content = _require_text(content, "document content", MAX_DOCUMENT_CHARS)
    _reject_credentials(clean_content)
    return {
        "sourceName": source_name,
        "knowledgeType": knowledge_type,
        "mimeType": "text/markdown",
        "content": str(redact(clean_content, max_chars=MAX_DOCUMENT_CHARS)),
        "chunkingMode": "STRUCTURE_AWARE",
        "chunkSize": 512,
        "overlapSize": 64,
        "source": "autopilot_generated",
        "marker": marker,
        "sha256": _sha256(clean_content),
    }


def _validate_document(value: Any, run_id: str, index: int, expected_name: str) -> None:
    document = _require_mapping(value, "document")
    expected = {
        "sourceName", "knowledgeType", "mimeType", "content", "chunkingMode", "chunkSize",
        "overlapSize", "source", "marker", "sha256",
    }
    if set(document) != expected:
        raise ProvisionPlanError("document fields are incomplete or unknown")
    if document.get("sourceName") != expected_name:
        raise ProvisionPlanError("document name is invalid")
    _require_text(document.get("knowledgeType"), "document knowledge type", 80)
    if document.get("mimeType") != "text/markdown" or document.get("chunkingMode") != "STRUCTURE_AWARE":
        raise ProvisionPlanError("document type or chunking mode is invalid")
    if document.get("chunkSize") != 512 or document.get("overlapSize") != 64:
        raise ProvisionPlanError("document chunk parameters are invalid")
    if document.get("source") != "autopilot_generated":
        raise ProvisionPlanError("document source is invalid")
    content = _require_text(document.get("content"), "document content", MAX_DOCUMENT_CHARS)
    _reject_credentials(content)
    if document.get("sha256") != _sha256(content) or not _SHA256_RE.fullmatch(str(document.get("sha256"))):
        raise ProvisionPlanError("document hash is invalid")
    _require_marker(document.get("marker"), run_id, f"source-{index}")


def _validate_limits(value: Any) -> None:
    if value != DEFAULT_LIMITS:
        raise ProvisionPlanError("provision plan limits must equal the hard defaults")


def _require_run_id(value: Any) -> str:
    if not isinstance(value, str) or not _RUN_ID_RE.fullmatch(value):
        raise ProvisionPlanError("run ID must match autopilot-<lowercase-slug>")
    return value


def _require_owner(value: Any) -> str:
    if not isinstance(value, str) or not _OWNER_RE.fullmatch(value):
        raise ProvisionPlanError("GitHub owner is invalid")
    return value


def _require_repository_name(value: Any) -> str:
    if not isinstance(value, str) or not _REPOSITORY_RE.fullmatch(value) or len(value) > MAX_REPOSITORY_CHARS:
        raise ProvisionPlanError("repository name is invalid")
    return value


def _resolve_repository_name(value: str | None, idea: str, suffix: str) -> str:
    if value is None:
        words = re.findall(r"[A-Za-z0-9]+", idea.lower())[:5]
        stem = "-".join(words) or "project"
        value = f"{stem}-{suffix}"
    name = _require_repository_name(value)
    if not name.endswith(f"-{suffix}"):
        raise ProvisionPlanError("repository name must include the run suffix")
    return name


def _require_criteria(value: Any) -> list[str]:
    if not isinstance(value, Sequence) or isinstance(value, (str, bytes)):
        raise ProvisionPlanError("success criteria must be a list")
    clean = [_require_text(item, "success criterion", 1_000) for item in value]
    if not 1 <= len(clean) <= MAX_CRITERIA:
        raise ProvisionPlanError("success criteria must contain 1 to 6 entries")
    return clean


def _require_marker(value: Any, run_id: str, kind: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(rf"\[autopilot:{re.escape(run_id)}:{re.escape(kind)}:[0-9a-f]{{12}}\]", value):
        raise ProvisionPlanError("provision marker is invalid")
    return value


def _marker(run_id: str, kind: str, seed: str) -> str:
    return f"[autopilot:{run_id}:{kind}:{seed[:12]}]"


def _require_mapping(value: Any, label: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ProvisionPlanError(f"{label} must be an object")
    return value


def _require_text(value: Any, label: str, maximum: int) -> str:
    if not isinstance(value, str) or not value.strip() or len(value.strip()) > maximum:
        raise ProvisionPlanError(f"{label} must be non-empty and <= {maximum} characters")
    return value.strip()


def _reject_credentials(value: str) -> None:
    if _CREDENTIAL_RE.search(value):
        raise ProvisionPlanError("credential-shaped content is not allowed")


def _display_name(idea: str) -> str:
    words = re.findall(r"[A-Za-z0-9]+", idea)
    if not words:
        return "RD-Bot Project"
    return _bounded(" ".join(word.capitalize() for word in words[:6]), 120)


def _bounded(value: str, maximum: int) -> str:
    return value[:maximum].strip()


def _sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()
