#!/usr/bin/env bash
set -euo pipefail

if [ "${1:-}" = "--help" ]; then
  printf '%s\n' "Usage: WAIMAI_SOURCE_DIR=/path/to/repository $0"
  printf '%s\n' "Imports the selected waimai repository source files and binds WAIMAI_PROJECT_KEY to WAIMAI_KB_NAME."
  exit 0
fi

: "${RD_BOT_BASE_URL:=http://127.0.0.1:18080}"
: "${WAIMAI_KB_NAME:=waimai}"
: "${WAIMAI_PROJECT_KEY:=codex-waimai-20260705-1613}"
: "${WAIMAI_REPOSITORY_URL:=https://github.com/example-owner/example-repo.git}"
: "${WAIMAI_REPOSITORY_REF:=main}"
: "${WAIMAI_MIN_DOCUMENT_COUNT:=30}"

export RD_BOT_BASE_URL
export WAIMAI_KB_NAME
export WAIMAI_PROJECT_KEY
export WAIMAI_REPOSITORY_URL
export WAIMAI_REPOSITORY_REF
export WAIMAI_MIN_DOCUMENT_COUNT

python3 - <<'PY'
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

base_url = os.environ["RD_BOT_BASE_URL"].rstrip("/")
kb_name = os.environ["WAIMAI_KB_NAME"]
project_key = os.environ["WAIMAI_PROJECT_KEY"]
repository_url = os.environ["WAIMAI_REPOSITORY_URL"]
repository_ref = os.environ["WAIMAI_REPOSITORY_REF"]
minimum_documents = int(os.environ["WAIMAI_MIN_DOCUMENT_COUNT"])
configured_source = os.environ.get("WAIMAI_SOURCE_DIR", "").strip()
tmp_dir = Path(tempfile.mkdtemp(prefix="rd-bot-waimai-repository-seed-"))


def request(method, path, payload=None):
    body = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    target = base_url + path
    try:
        with urllib.request.urlopen(
            urllib.request.Request(target, data=body, headers=headers, method=method),
            timeout=45,
        ) as response:
            raw = response.read()
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")[:1000]
        raise SystemExit(f"{method} {path} failed: HTTP {error.code}: {detail}") from error
    except urllib.error.URLError as error:
        raise SystemExit(f"{method} {path} failed: {error.reason}") from error
    return json.loads(raw.decode("utf-8")) if raw else None


def source_documents(source_dir):
    selected = [
        ("architecture", "README.md"),
        ("code-snippet", "package.json"),
        ("runbook", "start.sh"),
        ("frontend", "client/package.json"),
        ("frontend", "client/vite.config.ts"),
        ("frontend", "client/src/main.tsx"),
        ("frontend", "client/src/App.tsx"),
        ("frontend", "client/src/api.ts"),
        ("frontend", "client/src/index.css"),
        ("frontend", "client/src/context/AuthContext.tsx"),
        ("frontend", "client/src/layouts/AdminLayout.tsx"),
        ("frontend", "client/src/layouts/CustomerLayout.tsx"),
        ("frontend", "client/src/layouts/MerchantLayout.tsx"),
        ("frontend", "client/src/layouts/RiderLayout.tsx"),
        ("code-snippet", "server/package.json"),
        ("database", "server/config/schema.sql"),
        ("database", "server/src/database.ts"),
        ("code-snippet", "server/src/index.ts"),
        ("code-snippet", "server/src/seed.ts"),
        ("security", "server/src/middleware/auth.ts"),
        ("api", "server/src/routes/auth.ts"),
        ("api", "server/src/routes/orders.ts"),
        ("api", "server/src/routes/merchants.ts"),
        ("api", "server/src/routes/riders.ts"),
        ("api", "server/src/routes/coupons.ts"),
        ("api", "server/src/routes/admin.ts"),
    ]
    selected.extend(
        ("frontend", path.relative_to(source_dir).as_posix())
        for path in sorted((source_dir / "client/src/pages").glob("**/*.tsx"))
    )
    return sorted(
        {(kind, relative_path) for kind, relative_path in selected if (source_dir / relative_path).is_file()},
        key=lambda item: item[1],
    )


def redact(source):
    patterns = [
        r"(?i)(\b(?:api[_-]?key|access[_-]?token|auth[_-]?token|secret|password)\s*[:=]\s*[\"']?)([^\s\"',;]+)",
        r"(?i)(\bbearer\s+)([a-z0-9._-]{12,})",
    ]
    for pattern in patterns:
        source = re.sub(pattern, r"\1[REDACTED]", source)
    return source


def source_payload(source_dir, revision, knowledge_type, relative_path):
    source = redact((source_dir / relative_path).read_text(encoding="utf-8"))
    source_name = "waimai-repo-" + relative_path.replace("/", "-") + ".md"
    content = "\n".join([
        "# Waimai Repository Source",
        "",
        "- Repository: example-owner/example-repo",
        f"- Revision: {revision}",
        f"- Path: {relative_path}",
        "",
        "~~~text",
        source.rstrip(),
        "~~~",
        "",
    ])
    return {
        "sourceName": source_name,
        "knowledgeType": knowledge_type,
        "mimeType": "text/markdown",
        "content": content,
        "chunkingMode": "STRUCTURE_AWARE",
        "chunkSize": 360,
        "overlapSize": 48,
    }


try:
    source_dir = Path(configured_source).expanduser().resolve() if configured_source else tmp_dir / "source"
    if not configured_source:
        subprocess.run(
            ["git", "clone", "--depth", "1", "--branch", repository_ref, repository_url, str(source_dir)],
            check=True,
            stdout=subprocess.DEVNULL,
        )
    if not (source_dir / ".git").is_dir():
        raise SystemExit(f"WAIMAI_SOURCE_DIR must be a checked-out repository: {source_dir}")
    revision = subprocess.run(
        ["git", "-C", str(source_dir), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()

    print(f"Checking RD-Bot at {base_url}...")
    request("GET", "/rag/settings")

    bases = request(
        "GET",
        "/knowledge-base?" + urllib.parse.urlencode({"current": 1, "size": 100, "name": kb_name}),
    )["records"]
    knowledge_base = next((item for item in bases if item.get("name") == kb_name), None)
    if knowledge_base is None:
        knowledge_base = request(
            "POST",
            "/knowledge-base",
            {"name": kb_name, "description": "外卖项目的仓库源码、接口、数据模型与页面实现知识库"},
        )
        print(f"Created knowledge base: {kb_name} ({knowledge_base['id']})")
    if not knowledge_base.get("enabled", False):
        raise SystemExit("waimai knowledge base is disabled; enable it before importing")
    knowledge_base_id = knowledge_base["id"]

    documents = source_documents(source_dir)
    if len(documents) < minimum_documents:
        raise SystemExit(
            f"source manifest only has {len(documents)} documents; expected at least {minimum_documents}"
        )

    before = request("GET", f"/knowledge-base/{knowledge_base_id}/docs?current=1&size=500")["records"]
    for document in before:
        if document.get("sourceName", "").startswith("waimai-repo-"):
            request("DELETE", f"/knowledge-base/docs/{document['id']}")
    for knowledge_type, relative_path in documents:
        request(
            "POST",
            f"/knowledge-base/{knowledge_base_id}/docs/write",
            source_payload(source_dir, revision, knowledge_type, relative_path),
        )
        print(f"Seeded: {relative_path}")

    projects = request(
        "GET",
        "/admin/projects?" + urllib.parse.urlencode(
            {"keyword": project_key, "page": 1, "pageSize": 100}
        ),
    )["records"]
    project = next((item for item in projects if item.get("projectKey") == project_key), None)
    if project is None:
        raise SystemExit(f"project not found: {project_key}")
    updated_project = request(
        "PUT",
        f"/admin/projects/{project['projectId']}",
        {
            "projectKey": project["projectKey"],
            "name": project["name"],
            "description": project.get("description", ""),
            "repositoryUrl": project["repositoryUrl"],
            "repoOwner": project.get("repoOwner", ""),
            "repoName": project.get("repoName", ""),
            "defaultBranch": project["defaultBranch"],
            "knowledgeBaseId": knowledge_base_id,
            "enabled": project["enabled"],
        },
    )

    after = request("GET", f"/knowledge-base/{knowledge_base_id}/docs?current=1&size=500")["records"]
    imported = [item for item in after if item.get("sourceName", "").startswith("waimai-repo-")]
    if len(imported) != len(documents):
        raise SystemExit(f"expected {len(documents)} repository documents, found {len(imported)}")
    if not all(item.get("status") == "INDEXED" for item in imported):
        raise SystemExit("not every repository document reached INDEXED status")
    if updated_project.get("knowledgeBaseId") != knowledge_base_id:
        raise SystemExit("project knowledge base binding was not persisted")

    print(f"Repository corpus verified: {len(imported)} indexed documents")
    print("Project knowledge base binding verified")
    print(f"Imported revision: {revision}")
finally:
    shutil.rmtree(tmp_dir, ignore_errors=True)
PY
