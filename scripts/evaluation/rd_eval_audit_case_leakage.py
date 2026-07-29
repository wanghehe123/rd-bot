#!/usr/bin/env python3
"""Detect a case whose answer is already written down inside its own base repository.

``rd_eval_stage_benchmark_repository.py`` proves an Agent cannot reach commits after the
base, but a repository can describe unimplemented behavior in its own committed docs. When
a case is authored from the repository that also documents its architecture, the fix may
already be spelled out in a spec the Agent is free to read.

The signal used here is narrow enough to be actionable: a symbol the Gold patch introduces
that a base document already names while no base production file defines it. Such a
document describes behavior that does not exist yet, which is the answer. Deciding whether
a given sentence gives the fix away is not mechanical, so this audit surfaces the finding
and fails closed instead of silently approving the case.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any, Sequence


class LeakageAuditError(RuntimeError):
    """Raised when a case's base repository cannot be audited for self-documented answers."""


_MINIMUM_SYMBOL_LENGTH = 12

# Only a symbol the Gold patch *declares* can be an unimplemented API that a base document
# gives away. A symbol the patch merely calls is usually library vocabulary, which appears
# in base tests and docs for reasons that have nothing to do with this case.
_NEW_FILE_MARKER = re.compile(r"^--- /dev/null\s*$")
_TARGET_PATH = re.compile(r"^\+\+\+ b/(.+?)\s*$")
_TYPE_DECLARATION = re.compile(r"\b(?:class|interface|enum|record|trait|struct|type)\s+([A-Za-z_]\w*)")
_BINDING_DECLARATION = re.compile(r"\b(?:function|const|let|var|def|fun)\s+([A-Za-z_]\w*)")
_JAVA_MEMBER_DECLARATION = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:(?:public|protected|private|static|final|abstract|default|synchronized|native)\s+)*"
    r"[\w<>\[\],.?]+\s+([A-Za-z_]\w*)\s*\([^;]*\)\s*(?:throws [\w,.\s]+)?\{"
)
_JAVA_FIELD_DECLARATION = re.compile(
    r"^\s*(?:(?:public|protected|private|static|final)\s+)+[\w<>\[\],.?]+\s+([A-Za-z_]\w*)\s*="
)
# A table, column, environment or configuration key is a declared resource rather than a
# call into a library, so snake_case and SCREAMING_SNAKE names count on their own.
_RESOURCE_NAME = re.compile(r"\b([a-z][a-z0-9]*(?:_[a-z0-9]+){2,}|[A-Z][A-Z0-9]*(?:_[A-Z0-9]+){2,})\b")
_DECLARED_FILE_SUFFIXES: tuple[str, ...] = (".java", ".kt", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".py")

_DOCUMENT_SUFFIXES: tuple[str, ...] = (".md", ".mdx", ".rst", ".txt", ".adoc")
_TEST_PATH_PATTERNS: tuple[str, ...] = (
    "src/test/",
    "src/it/",
    "/test/",
    "/tests/",
    "/__tests__/",
    ".test.",
    ".spec.",
)

# Language and framework vocabulary long enough to pass the length floor but far too common
# to indicate that a document is describing an unimplemented change.
_STOPLIST: frozenset[str] = frozenset({
    "IllegalArgumentException",
    "IllegalStateException",
    "NoSuchElementException",
    "UnsupportedOperationException",
    "RuntimeException",
    "StandardCharsets",
    "CompletableFuture",
    "ConcurrentHashMap",
    "LinkedHashMap",
    "LinkedHashSet",
    "RequiredArgsConstructor",
    "RestController",
    "Configuration",
    "Transactional",
    "Deprecated",
    "Override",
    "SuppressWarnings",
    "FunctionalInterface",
    "implementation",
    "documentation",
    "configuration",
    "instanceof",
    "synchronized",
    "constructor",
    "interface",
    "protected",
})


def _git(repository: Path, arguments: Sequence[str]) -> str:
    completed = subprocess.run(
        ["git", "-C", str(repository), *arguments],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if completed.returncode != 0:
        raise LeakageAuditError(completed.stdout.strip() or "Git command failed")
    return completed.stdout


def gold_patch_declared_symbols(gold_patch: str, minimum_length: int = _MINIMUM_SYMBOL_LENGTH) -> list[str]:
    """Collect the distinctive APIs and resources that the Gold patch's added lines declare."""

    symbols: list[str] = []

    def remember(candidate: str) -> None:
        if len(candidate) >= minimum_length and candidate not in _STOPLIST and candidate not in symbols:
            symbols.append(candidate)

    previous_was_new_file = False
    for line in gold_patch.splitlines():
        target = _TARGET_PATH.match(line)
        if target:
            if previous_was_new_file and target.group(1).endswith(_DECLARED_FILE_SUFFIXES):
                remember(target.group(1).rsplit("/", 1)[-1].rsplit(".", 1)[0])
            previous_was_new_file = False
            continue
        previous_was_new_file = bool(_NEW_FILE_MARKER.match(line))
        if not line.startswith("+") or line.startswith("+++"):
            continue
        added = line[1:]
        for pattern in (_TYPE_DECLARATION, _BINDING_DECLARATION, _RESOURCE_NAME):
            for match in pattern.finditer(added):
                remember(match.group(1))
        for pattern in (_JAVA_MEMBER_DECLARATION, _JAVA_FIELD_DECLARATION):
            match = pattern.match(added)
            if match:
                remember(match.group(1))
    return symbols


def _is_document(path: str) -> bool:
    return path.lower().endswith(_DOCUMENT_SUFFIXES)


def _is_test(path: str) -> bool:
    return any(pattern in path for pattern in _TEST_PATH_PATTERNS)


def _tracked_paths(repository: Path) -> list[str]:
    return [line.strip() for line in _git(repository, ["ls-files"]).splitlines() if line.strip()]


def _paths_containing(repository: Path, symbol: str, candidates: Sequence[str]) -> list[str]:
    if not candidates:
        return []
    matched: list[str] = []
    for path in candidates:
        try:
            content = (repository / path).read_text(encoding="utf-8", errors="ignore")
        except (OSError, IsADirectoryError):
            continue
        if symbol in content:
            matched.append(path)
    return matched


def audit_case_leakage(prepared_repository: Path, gold_patch: Path) -> dict[str, Any]:
    """Report Gold-patch symbols that the base repository documents but does not implement."""

    repository = Path(prepared_repository).resolve()
    if not repository.is_dir() or repository.is_symlink():
        raise LeakageAuditError("prepared repository is unavailable")
    patch_path = Path(gold_patch).resolve()
    if not patch_path.is_file() or patch_path.is_symlink():
        raise LeakageAuditError("Gold patch is unavailable")
    symbols = gold_patch_declared_symbols(patch_path.read_text(encoding="utf-8", errors="ignore"))
    tracked = _tracked_paths(repository)
    documents = [path for path in tracked if _is_document(path)]
    tests = [path for path in tracked if not _is_document(path) and _is_test(path)]
    production = [path for path in tracked if not _is_document(path) and not _is_test(path)]

    documentation_leaks: list[dict[str, Any]] = []
    test_leaks: list[dict[str, Any]] = []
    for symbol in symbols:
        if _paths_containing(repository, symbol, production):
            continue
        document_hits = _paths_containing(repository, symbol, documents)
        if document_hits:
            documentation_leaks.append({"symbol": symbol, "paths": document_hits})
            continue
        test_hits = _paths_containing(repository, symbol, tests)
        if test_hits:
            test_leaks.append({"symbol": symbol, "paths": test_hits})
    if documentation_leaks or test_leaks:
        verdict = "LEAK"
    elif not symbols:
        # No distinctive declaration means this rule produced no signal at all. Reporting
        # that as CLEAN would turn an unanswered question into a clean bill of health.
        verdict = "UNAUDITABLE"
    else:
        verdict = "CLEAN"
    return {
        "verdict": verdict,
        "auditedSymbols": symbols,
        "documentationLeaks": documentation_leaks,
        "testLeaks": test_leaks,
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Audit whether a case's base repository already documents its unwritten fix."
    )
    parser.add_argument("--prepared-repository", required=True)
    parser.add_argument("--gold-patch", required=True)
    parser.add_argument("--output")
    args = parser.parse_args()
    report = audit_case_leakage(Path(args.prepared_repository), Path(args.gold_patch))
    serialized = json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2)
    if args.output:
        destination = Path(args.output).resolve()
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(serialized + "\n", encoding="utf-8")
    print(serialized)
    return {"LEAK": 1, "UNAUDITABLE": 3}.get(report["verdict"], 0)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except LeakageAuditError as error:
        print(f"case leakage audit error: {error}", file=sys.stderr)
        raise SystemExit(2)
