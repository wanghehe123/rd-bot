#!/usr/bin/env python3
"""Create one RD-Bot project and knowledge base for every selected SWE task.

This script does not call a model. It only creates task-scoped admin records,
uploads agent-safe task context, and writes the canonical prompt for each
instance. Repository documentation is ingested later from the exact checkout.
"""

from __future__ import annotations

import argparse
from pathlib import Path
from typing import Any

import swebench_lib as lib


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=lib.DEFAULT_RD_BOT_BASE_URL)
    parser.add_argument("--manifest", type=Path, default=lib.DEFAULT_MANIFEST_PATH)
    parser.add_argument("--output-dir", type=Path, default=lib.DEFAULT_RUN_ROOT)
    parser.add_argument("--checkout-root", type=Path, default=lib.DEFAULT_CHECKOUT_ROOT)
    parser.add_argument("--expected-count", type=int, default=10)
    parser.add_argument("--index-timeout-seconds", type=float, default=60.0)
    return parser.parse_args()


def get_or_create_knowledge_base(
    client: lib.RdBotApi,
    row: dict[str, Any],
) -> tuple[dict[str, Any], bool]:
    name = lib.knowledge_base_name(row)
    knowledge_base = lib.find_exact(client.list_knowledge_bases(name), "name", name)
    if knowledge_base is None:
        knowledge_base = client.request(
            "POST",
            "/knowledge-base",
            {
                "name": name,
                "description": (
                    f"Task-scoped SWE-bench Lite context and exact-checkout repository "
                    f"documents for {row['instance_id']}."
                ),
            },
        )
        return dict(knowledge_base), True
    if not knowledge_base.get("enabled", False):
        raise RuntimeError(f"knowledge base exists but is disabled: {name}")
    return knowledge_base, False


def get_or_create_project(
    client: lib.RdBotApi,
    row: dict[str, Any],
    knowledge_base_id: str,
) -> tuple[dict[str, Any], bool]:
    key = lib.project_key(row)
    project = lib.find_exact(client.list_projects(key), "projectKey", key)
    payload = lib.project_payload(row, knowledge_base_id)
    if project is None:
        return dict(client.request("POST", "/admin/projects", payload)), True
    return (
        dict(client.request("PUT", f"/admin/projects/{project['projectId']}", payload)),
        False,
    )


def get_or_write_task_context(
    client: lib.RdBotApi,
    knowledge_base_id: str,
    row: dict[str, Any],
) -> tuple[dict[str, Any], bool]:
    existing = lib.find_exact(
        client.list_documents(knowledge_base_id),
        "sourceName",
        lib.TASK_CONTEXT_SOURCE_NAME,
    )
    if existing is not None:
        return existing, False
    document = client.request(
        "POST",
        f"/knowledge-base/{knowledge_base_id}/docs/write",
        {
            "sourceName": lib.TASK_CONTEXT_SOURCE_NAME,
            "knowledgeType": "benchmark_task",
            "mimeType": "text/markdown",
            "content": lib.task_context_document(row),
            "chunkingMode": "STRUCTURE_AWARE",
            "chunkSize": 900,
            "overlapSize": 80,
        },
    )
    return dict(document), True


def main() -> int:
    args = parse_args()
    manifest_path = args.manifest.resolve()
    output_dir = args.output_dir.resolve()
    checkout_root = args.checkout_root.resolve()
    rows = lib.load_manifest(manifest_path, args.expected_count)
    client = lib.RdBotApi(args.base_url)
    client.assert_ready()

    plan_path = output_dir / "run-plan.json"
    previous_documents: dict[str, list[dict[str, Any]]] = {}
    if plan_path.is_file():
        previous_plan = lib.read_json(plan_path)
        for previous_task in previous_plan.get("tasks", []):
            instance_id = previous_task.get("instance_id")
            documents = previous_task.get("repository_documents")
            if isinstance(instance_id, str) and isinstance(documents, list):
                previous_documents[instance_id] = documents

    prepared_tasks: list[dict[str, Any]] = []
    created_projects = 0
    created_knowledge_bases = 0
    created_task_documents = 0

    for row in rows:
        knowledge_base, created_kb = get_or_create_knowledge_base(client, row)
        project, created_project = get_or_create_project(client, row, str(knowledge_base["id"]))
        task_document, created_document = get_or_write_task_context(client, str(knowledge_base["id"]), row)
        indexed = lib.wait_for_indexed_documents(
            client,
            str(knowledge_base["id"]),
            {lib.TASK_CONTEXT_SOURCE_NAME},
            args.index_timeout_seconds,
        )[lib.TASK_CONTEXT_SOURCE_NAME]

        agent_workspaces: dict[str, str] = {}
        prompt_paths: dict[str, dict[str, str]] = {}
        for agent_id in lib.AGENT_IDS:
            agent_key = lib.agent_plan_key(agent_id)
            checkout = lib.agent_checkout_path(checkout_root, row, agent_id)
            prompt = lib.task_prompt(row, checkout)
            prompt_path = lib.agent_prompt_path(output_dir, row["instance_id"], agent_id)
            prompt_path.parent.mkdir(parents=True, exist_ok=True)
            prompt_path.write_text(prompt, encoding="utf-8")
            agent_workspaces[agent_key] = str(checkout)
            prompt_paths[agent_key] = {
                "path": lib.relative_to_repo(prompt_path),
                "sha256": lib.sha256_text(prompt),
            }

        created_projects += int(created_project)
        created_knowledge_bases += int(created_kb)
        created_task_documents += int(created_document)
        prepared_tasks.append(
            {
                "instance_id": row["instance_id"],
                "repo": row["repo"],
                "base_commit": row["base_commit"],
                "environment_setup_commit": row["environment_setup_commit"],
                "checkout_path": agent_workspaces["claude_code"],
                "agent_workspaces": agent_workspaces,
                "prompt_paths": prompt_paths,
                "project": {
                    "project_id": project["projectId"],
                    "project_key": project["projectKey"],
                    "name": project["name"],
                    "knowledge_base_id": project.get("knowledgeBaseId"),
                },
                "knowledge_base": {
                    "id": knowledge_base["id"],
                    "name": knowledge_base["name"],
                },
                "task_context_document": {
                    "id": indexed["id"],
                    "source_name": indexed["sourceName"],
                    "status": indexed["status"],
                    "checksum": indexed.get("checksum", ""),
                },
                "repository_documents": previous_documents.get(row["instance_id"], []),
            }
        )
        print(
            f"Prepared {row['instance_id']}: project={project['projectId']} "
            f"knowledge_base={knowledge_base['id']}"
        )

    plan = {
        "suite": "SWE-bench Lite",
        "split": "test",
        "created_at": lib.utc_now(),
        "manifest_path": lib.relative_to_repo(manifest_path),
        "manifest_sha256": lib.sha256_file(manifest_path),
        "safe_manifest_fields": sorted(lib.SAFE_MANIFEST_FIELDS),
        "checkout_root": str(checkout_root),
        "task_count": len(prepared_tasks),
        "tasks": prepared_tasks,
    }
    lib.write_json(plan_path, plan)
    print(
        "RD-Bot setup complete: "
        f"projects created={created_projects}, knowledge bases created={created_knowledge_bases}, "
        f"task documents created={created_task_documents}"
    )
    print(f"Run plan: {plan_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
