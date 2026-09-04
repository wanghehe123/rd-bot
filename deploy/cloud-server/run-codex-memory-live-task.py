#!/usr/bin/env python3
"""Provision codex 运行测试 project and submit one live requirement on the cloud server."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

BASE = os.environ.get("RD_BOT_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
RUNTIME_TOKEN = os.environ.get("RD_AGENT_RUNTIME_MUTATION_TOKEN", "local-agent-runtime")
PROJECT_NAME = "codex运行测试"
PROJECT_KEY = "codex-run-test-waimai"
REPO_URL = "https://github.com/wanghehe123/rd-bot-waimai-acceptance-20260624-141045.git"
REPO_OWNER = "wanghehe123"
REPO_NAME = "rd-bot-waimai-acceptance-20260624-141045"
KB_NAME = "waimai"
WAIMAI_SOURCE = Path(os.environ.get("WAIMAI_SOURCE_DIR", "/tmp/waimai-seed"))
ARTIFACT_DIR = Path(os.environ.get("RUN_ARTIFACT_DIR", "/tmp/codex-memory-run"))


def req(method: str, path: str, body=None, headers=None, timeout=120):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    h = {"Accept": "application/json"}
    if body is not None:
        h["Content-Type"] = "application/json"
    if headers:
        h.update(headers)
    request = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as resp:
            raw = resp.read()
            return resp.status, json.loads(raw.decode()) if raw else {}
    except urllib.error.HTTPError as err:
        detail = err.read().decode("utf-8", errors="replace")[:2000]
        raise SystemExit(f"{method} {path} -> HTTP {err.code}: {detail}") from err


def ensure_project_and_kb():
    status, page = req("GET", "/admin/projects?" + urllib.parse.urlencode({"page": 1, "pageSize": 50}))
    project = next((p for p in page.get("records", []) if p.get("name") == PROJECT_NAME or p.get("projectKey") == PROJECT_KEY), None)
    if project is None:
        _, kb = _ensure_kb()
        _, project = req("POST", "/admin/projects", {
            "projectKey": PROJECT_KEY,
            "name": PROJECT_NAME,
            "description": "外卖商城 GitHub 验收项目 — project memory live run",
            "repositoryUrl": REPO_URL,
            "repoOwner": REPO_OWNER,
            "repoName": REPO_NAME,
            "defaultBranch": "main",
            "knowledgeBaseId": kb["id"],
            "enabled": True,
        })
        print(f"created project {project['projectId']} ({PROJECT_NAME})")
    else:
        print(f"reusing project {project['projectId']} ({project.get('name')})")
    return project


def _ensure_kb():
    _, bases = req("GET", "/knowledge-base?" + urllib.parse.urlencode({"current": 1, "size": 50, "name": KB_NAME}))
    kb = next((item for item in bases.get("records", []) if item.get("name") == KB_NAME), None)
    if kb is None:
        return req("POST", "/knowledge-base", {
            "name": KB_NAME,
            "description": "外卖项目知识库",
        })
    return 200, kb


def seed_knowledge(project):
    env = os.environ.copy()
    env.update({
        "RD_BOT_BASE_URL": BASE,
        "WAIMAI_KB_NAME": KB_NAME,
        "WAIMAI_PROJECT_KEY": PROJECT_KEY,
        "WAIMAI_REPOSITORY_URL": REPO_URL,
        "WAIMAI_REPOSITORY_REF": "main",
        "WAIMAI_SOURCE_DIR": str(WAIMAI_SOURCE),
        "WAIMAI_MIN_DOCUMENT_COUNT": "10",
    })
    script = Path(__file__).resolve().parents[2] / "scripts/owner/seed-waimai-repository-knowledge.sh"
    subprocess.run(["bash", str(script)], check=True, env=env)
    _, page = req("GET", "/admin/projects?" + urllib.parse.urlencode({"keyword": PROJECT_KEY, "page": 1, "pageSize": 10}))
    return next(p for p in page["records"] if p["projectKey"] == PROJECT_KEY)


def psql_json(query: str, *args: str) -> list[dict]:
    sql = query
    for arg in args:
        sql = sql.replace("%s", str(arg), 1)
    proc = subprocess.run(
        [
            "docker", "exec", "rd-bot-postgres", "psql", "-U", "postgres", "-d", "rdbot",
            "-t", "-A", "-F", "\t", "-c", sql,
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    lines = [line for line in proc.stdout.splitlines() if line.strip()]
    return [{"raw": line} for line in lines]


def set_memory_mode(project_id: str):
    subprocess.run(
        [
            "docker", "exec", "rd-bot-postgres", "psql", "-U", "postgres", "-d", "rdbot", "-c",
            f"""
            INSERT INTO rd_project_memory_modes (project_id, capture_mode, read_mode)
            VALUES ({project_id}, 'ACTIVE', 'SHADOW')
            ON CONFLICT (project_id) DO UPDATE
            SET capture_mode = EXCLUDED.capture_mode, read_mode = EXCLUDED.read_mode, updated_at = now();
            """,
        ],
        check=True,
    )
    print(f"memory mode ACTIVE/SHADOW for project {project_id}")


def ensure_model_provider():
    body = {
        "displayName": "OpenCode Go",
        "protocol": "ANTHROPIC_MESSAGES",
        "baseUrl": "https://opencode.ai/zen/go",
        "modelId": os.environ.get("RD_AI_CHAT_DEFAULT_MODEL", "qwen3.8-flash"),
        "credentialEnvironmentVariable": "OPENCODE_API_KEY",
        "authHeader": False,
        "enabled": True,
        "version": 1,
    }
    req(
        "PUT",
        "/admin/model-provider-profiles/opencode-go",
        body,
        headers={"X-RD-Agent-Runtime-Token": RUNTIME_TOKEN},
    )
    if os.environ.get("OPENCODE_API_KEY"):
        req(
            "PUT",
            "/admin/model-provider-profiles/opencode-go/credential",
            {"apiKey": os.environ["OPENCODE_API_KEY"]},
            headers={"X-RD-Agent-Runtime-Token": RUNTIME_TOKEN},
        )


def ensure_agent_strategy(project_id: str):
    _, bundle = req("GET", f"/admin/projects/{project_id}/agent-strategies")
    strategies = bundle.get("strategies") or []
    existing = next((s for s in strategies if s.get("strategyId") == "pi-default"), None)
    model = os.environ.get("RD_AI_CHAT_DEFAULT_MODEL", "qwen3.8-flash")
    roles = ["REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT", "QA_AGENT"]
    slots = []
    existing_slots = {
        slot.get("role"): slot
        for slot in ((existing or {}).get("roles") or [])
    }
    for role in roles:
        previous = existing_slots.get(role) or {}
        tool_policy = previous.get("toolPolicyId") or (
            "default-qa" if role == "QA_AGENT" else "legacy-host-bound"
        )
        slots.append({
            "role": role,
            "runtimeType": "PI",
            "providerProfileId": "opencode-go",
            "modelOverride": model,
            "extensionSetId": previous.get("extensionSetId") or "",
            "extensionSetVersion": previous.get("extensionSetVersion") or 0,
            "toolPolicyId": tool_policy,
            "toolPolicyVersion": previous.get("toolPolicyVersion") or 1,
            "imageMode": previous.get("imageMode") or "LOCAL_DEFAULT",
            "image": previous.get("image") or "",
            "dockerfileName": previous.get("dockerfileName") or "",
            "dockerfileSha256": previous.get("dockerfileSha256") or "",
            "dockerfileArtifactUri": previous.get("dockerfileArtifactUri") or "",
            "dockerfileText": "",
        })
    body = {
        "strategyId": "pi-default",
        "projectId": project_id,
        "name": (existing or {}).get("name") or "Pi default",
        "enabled": True,
        "version": ((existing or {}).get("version") or 1),
        "roles": slots,
    }
    already_opencode = existing is not None and all(
        slot.get("providerProfileId") == "opencode-go"
        and slot.get("modelOverride") == model
        for slot in (existing.get("roles") or [])
    )
    if existing is None:
        req(
            "POST",
            f"/admin/projects/{project_id}/agent-strategies",
            body,
            headers={"X-RD-Agent-Runtime-Token": RUNTIME_TOKEN},
        )
    elif not already_opencode:
        req(
            "PUT",
            f"/admin/projects/{project_id}/agent-strategies/pi-default",
            body,
            headers={"X-RD-Agent-Runtime-Token": RUNTIME_TOKEN},
        )
    req(
        "PUT",
        f"/admin/projects/{project_id}/agent-strategies/pi-default/default",
        {},
        headers={"X-RD-Agent-Runtime-Token": RUNTIME_TOKEN},
    )
    print("bound Pi agent strategy pi-default with opencode-go / " + model)


def submit_requirement(project_id: str) -> str:
    body = {
        "title": "顾客首页展示项目记忆验收标记",
        "projectId": project_id,
        "priority": "P1",
        "repositoryUrl": REPO_URL,
        "repoOwner": REPO_OWNER,
        "repoName": REPO_NAME,
        "baseBranch": "main",
        "expectedResult": "在顾客端首页可见一行静态验收标记文本，且不破坏现有下单流程。",
        "acceptanceCriteria": [
            "client 生产构建通过",
            "顾客首页渲染包含 MEMORY-LIVE-RUN 标记",
            "server 健康检查仍可用",
        ],
        "materials": [{
            "materialType": "REQUIREMENT",
            "sourceType": "MANUAL_TEXT",
            "title": "需求说明",
            "content": "为 project memory live run 在 customer home 增加可见但不干扰功能的 MEMORY-LIVE-RUN 标记。",
            "mimeType": "text/plain",
        }],
        "autoExecute": False,
        "tokenBudgetOverride": 0,
    }
    _, task = req("POST", "/admin/rd-tasks/requirements", body)
    task_id = task["taskId"]
    req("POST", f"/admin/rd-tasks/{task_id}/submit", {})
    print(f"submitted task {task_id} status={task.get('status')}")
    return task_id


def poll_task(task_id: str, timeout_sec: int = 7200):
    terminal = {"COMPLETED", "FAILED", "CANCELLED", "DELETED", "MERGED"}
    start = time.time()
    while time.time() - start < timeout_sec:
        _, task = req("GET", f"/admin/rd-tasks/{task_id}")
        status = task.get("status")
        print(f"poll task {task_id} status={status} paused={task.get('paused')}")
        if status in terminal:
            return task
        time.sleep(60)
    raise SystemExit(f"task {task_id} did not finish within {timeout_sec}s")


def export_memory(project_id: str, task_id: str):
    ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)
    tables = [
        ("modes", f"SELECT row_to_json(t) FROM rd_project_memory_modes t WHERE project_id={project_id}"),
        ("memories", f"SELECT row_to_json(t) FROM rd_project_memories t WHERE project_id={project_id} ORDER BY id"),
        ("revisions", f"""
            SELECT row_to_json(r) FROM rd_project_memory_revisions r
            JOIN rd_project_memories m ON m.id = r.memory_id
            WHERE m.project_id={project_id} ORDER BY r.id
        """),
        ("sources", f"SELECT row_to_json(t) FROM rd_project_memory_sources t WHERE project_id={project_id} ORDER BY id"),
        ("operations", f"SELECT row_to_json(t) FROM rd_project_memory_operations t WHERE project_id={project_id} ORDER BY id"),
    ]
    export = {"projectId": project_id, "taskId": task_id, "exportedAtEpochMs": int(time.time() * 1000)}
    for key, sql in tables:
        proc = subprocess.run(
            ["docker", "exec", "rd-bot-postgres", "psql", "-U", "postgres", "-d", "rdbot", "-t", "-A", "-c", sql],
            check=True,
            capture_output=True,
            text=True,
        )
        rows = []
        for line in proc.stdout.splitlines():
            line = line.strip()
            if line:
                rows.append(json.loads(line))
        export[key] = rows
    out = ARTIFACT_DIR / f"project-memory-export-{project_id}-{task_id}.json"
    out.write_text(json.dumps(export, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    print(f"exported memory artifact: {out}")
    return out


def main():
    if not WAIMAI_SOURCE.is_dir():
        raise SystemExit(f"missing WAIMAI_SOURCE_DIR checkout: {WAIMAI_SOURCE}")
    project = ensure_project_and_kb()
    project = seed_knowledge(project)
    project_id = str(project["projectId"])
    ensure_model_provider()
    ensure_agent_strategy(project_id)
    set_memory_mode(project_id)
    task_id = submit_requirement(project_id)
    ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)
    (ARTIFACT_DIR / "task-id.txt").write_text(task_id, encoding="utf-8")
    (ARTIFACT_DIR / "project-id.txt").write_text(project_id, encoding="utf-8")
    final = poll_task(task_id)
    export_memory(project_id, task_id)
    (ARTIFACT_DIR / "final-task.json").write_text(json.dumps(final, ensure_ascii=False, indent=2), encoding="utf-8")
    print("DONE", final.get("status"))


if __name__ == "__main__":
    main()
