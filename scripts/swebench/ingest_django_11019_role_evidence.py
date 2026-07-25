#!/usr/bin/env python3
"""Ingest the role-specific, source-derived evidence needed by one SWE task."""

from __future__ import annotations

from pathlib import Path

import swebench_lib as lib


KNOWLEDGE_BASE_ID = "7485660426584330240"
EVIDENCE_ROOT = lib.DEFAULT_RUN_ROOT / "role-evidence" / "django__django-11019"
EVIDENCE_DOCUMENTS = (
    ("swebench-role-architecture.md", "architecture", EVIDENCE_ROOT / "architecture.md"),
    ("swebench-role-source-code.md", "source_code", EVIDENCE_ROOT / "source-code.md"),
    ("swebench-role-test-entry.md", "test_entry", EVIDENCE_ROOT / "test-entry.md"),
)


def main() -> int:
    client = lib.RdBotApi(lib.DEFAULT_RD_BOT_BASE_URL)
    client.assert_ready()
    existing = {
        document.get("sourceName"): document
        for document in client.list_documents(KNOWLEDGE_BASE_ID)
    }
    required_sources: set[str] = set()
    for source_name, knowledge_type, path in EVIDENCE_DOCUMENTS:
        required_sources.add(source_name)
        if source_name not in existing:
            client.request(
                "POST",
                f"/knowledge-base/{KNOWLEDGE_BASE_ID}/docs/write",
                {
                    "sourceName": source_name,
                    "knowledgeType": knowledge_type,
                    "mimeType": "text/markdown",
                    "content": path.read_text(encoding="utf-8"),
                    "chunkingMode": "STRUCTURE_AWARE",
                    "chunkSize": 900,
                    "overlapSize": 80,
                },
            )
            print(f"Created {source_name} ({knowledge_type})")
        else:
            print(f"Reused {source_name} ({knowledge_type})")

    indexed = lib.wait_for_indexed_documents(
        client, KNOWLEDGE_BASE_ID, required_sources, timeout_seconds=30.0
    )
    for source_name in sorted(indexed):
        document = indexed[source_name]
        print(f"Indexed {source_name}: id={document['id']} chunks={document['chunkCount']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
