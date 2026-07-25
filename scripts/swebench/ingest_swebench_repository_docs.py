#!/usr/bin/env python3
"""Ingest a small set of exact-checkout documentation into each task KB.

Each task receives its own repository-document copies from the same commit as
its worktree. That avoids cross-instance retrieval and revision leakage while
keeping this pilot's ingestion corpus deliberately small.
"""

from __future__ import annotations

import argparse
import hashlib
import subprocess
from pathlib import Path
from typing import Any

import swebench_lib as lib


PREFERRED_DOCUMENTS = (
    "README.md",
    "README.rst",
    "README",
    "CONTRIBUTING.md",
    "CONTRIBUTING.rst",
    "CONTRIBUTING",
    "pyproject.toml",
    "setup.cfg",
    "tox.ini",
    "pytest.ini",
    "setup.py",
    "docs/index.rst",
    "docs/index.md",
    "docs/development/index.rst",
    "docs/development/index.md",
)
DOCUMENT_SUFFIXES = {".md", ".rst", ".txt"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=lib.DEFAULT_RD_BOT_BASE_URL)
    parser.add_argument("--manifest", type=Path, default=lib.DEFAULT_MANIFEST_PATH)
    parser.add_argument("--run-plan", type=Path, default=lib.DEFAULT_RUN_ROOT / "run-plan.json")
    parser.add_argument("--expected-count", type=int, default=10)
    parser.add_argument("--max-documents", type=int, default=6)
    parser.add_argument("--max-chars", type=int, default=50_000)
    parser.add_argument("--index-timeout-seconds", type=float, default=90.0)
    return parser.parse_args()


def git_value(checkout: Path, *args: str) -> str:
    completed = subprocess.run(
        ["git", "-C", str(checkout), *args],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"git command failed in {checkout}: {' '.join(args)}\n{completed.stderr.strip()}")
    return completed.stdout.strip()


def select_documents(checkout: Path, max_documents: int) -> list[Path]:
    if max_documents < 1:
        raise ValueError("--max-documents must be at least 1")
    selected: list[Path] = []
    seen: set[Path] = set()

    def add(path: Path) -> None:
        resolved = path.resolve()
        if resolved.is_file() and resolved not in seen and len(selected) < max_documents:
            seen.add(resolved)
            selected.append(resolved)

    for relative in PREFERRED_DOCUMENTS:
        add(checkout / relative)
    if len(selected) >= max_documents:
        return selected

    docs_dir = checkout / "docs"
    if docs_dir.is_dir():
        for candidate in sorted(docs_dir.rglob("*")):
            if candidate.is_file() and candidate.suffix.lower() in DOCUMENT_SUFFIXES:
                add(candidate)
                if len(selected) >= max_documents:
                    return selected
    for candidate in sorted(checkout.iterdir()):
        if candidate.is_file() and candidate.suffix.lower() in DOCUMENT_SUFFIXES:
            add(candidate)
            if len(selected) >= max_documents:
                return selected
    return selected


def content_for_document(
    task: dict[str, Any],
    checkout: Path,
    document_path: Path,
    max_chars: int,
) -> tuple[str, str, bool]:
    if max_chars < 1:
        raise ValueError("--max-chars must be at least 1")
    raw = document_path.read_text(encoding="utf-8", errors="replace").replace("\x00", "")
    truncated = len(raw) > max_chars
    body = raw[:max_chars]
    relative_path = document_path.relative_to(checkout).as_posix()
    content_hash = hashlib.sha256(raw.encode("utf-8")).hexdigest()
    content = "\n".join(
        [
            "# Repository Documentation",
            "",
            f"- Benchmark task: `{task['instance_id']}`",
            f"- Repository: `{task['repo']}`",
            f"- Base commit: `{task['base_commit']}`",
            f"- Relative path: `{relative_path}`",
            f"- Full-source SHA256: `{content_hash}`",
            f"- Truncated for the pilot corpus: `{str(truncated).lower()}`",
            "",
            "```text",
            body.rstrip(),
            "```",
            "",
        ]
    )
    return relative_path, content, truncated


def main() -> int:
    args = parse_args()
    rows = lib.load_manifest(args.manifest.resolve(), args.expected_count)
    row_by_instance = {row["instance_id"]: row for row in rows}
    plan_path = args.run_plan.resolve()
    plan = lib.read_json(plan_path)
    if plan.get("manifest_sha256") != lib.sha256_file(args.manifest.resolve()):
        raise RuntimeError("run plan does not match the current safe manifest")
    if plan.get("task_count") != args.expected_count:
        raise RuntimeError("run plan has the wrong task count")

    client = lib.RdBotApi(args.base_url)
    client.assert_ready()
    updated_tasks: list[dict[str, Any]] = []

    for task in plan["tasks"]:
        instance_id = task.get("instance_id")
        row = row_by_instance.get(instance_id)
        if row is None:
            raise RuntimeError(f"run plan has an unknown instance_id: {instance_id}")
        if task.get("base_commit") != row["base_commit"]:
            raise RuntimeError(f"run plan has a mismatched base commit for {instance_id}")
        checkout = Path(str(task["checkout_path"])).resolve()
        if not checkout.is_dir():
            raise RuntimeError(f"worktree does not exist for {instance_id}: {checkout}")
        actual_commit = git_value(checkout, "rev-parse", "HEAD")
        if actual_commit != row["base_commit"]:
            raise RuntimeError(
                f"worktree has the wrong commit for {instance_id}: expected {row['base_commit']}, got {actual_commit}"
            )
        if git_value(checkout, "status", "--porcelain", "--untracked-files=all"):
            raise RuntimeError(f"worktree must be clean before document ingestion: {checkout}")

        knowledge_base_id = str(task["knowledge_base"]["id"])
        documents_by_source = {
            str(document.get("sourceName")): document
            for document in client.list_documents(knowledge_base_id)
        }
        selected = select_documents(checkout, args.max_documents)
        if not selected:
            raise RuntimeError(f"no documentation files found for {instance_id} in {checkout}")

        source_names: set[str] = set()
        repository_documents: list[dict[str, Any]] = []
        for document_path in selected:
            relative_path, content, truncated = content_for_document(task, checkout, document_path, args.max_chars)
            source_name = lib.repository_document_source_name(relative_path)
            source_names.add(source_name)
            document = documents_by_source.get(source_name)
            if document is None:
                document = client.request(
                    "POST",
                    f"/knowledge-base/{knowledge_base_id}/docs/write",
                    {
                        "sourceName": source_name,
                        "knowledgeType": "repository_document",
                        "mimeType": "text/markdown",
                        "content": content,
                        "chunkingMode": "STRUCTURE_AWARE",
                        "chunkSize": 900,
                        "overlapSize": 80,
                    },
                )
            repository_documents.append(
                {
                    "id": document.get("id", ""),
                    "source_name": source_name,
                    "relative_path": relative_path,
                    "content_sha256": lib.sha256_text(content),
                    "truncated": truncated,
                    "status": document.get("status", ""),
                }
            )

        indexed = lib.wait_for_indexed_documents(
            client,
            knowledge_base_id,
            source_names,
            args.index_timeout_seconds,
        )
        for document in repository_documents:
            indexed_document = indexed[document["source_name"]]
            document["id"] = indexed_document["id"]
            document["status"] = indexed_document["status"]
            document["checksum"] = indexed_document.get("checksum", "")
        task["repository_documents"] = repository_documents
        updated_tasks.append(task)
        print(f"Indexed {len(repository_documents)} repository documents for {instance_id}")

    plan["repository_documents_ingested_at"] = lib.utc_now()
    plan["tasks"] = updated_tasks
    lib.write_json(plan_path, plan)
    print(f"Updated run plan: {plan_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
