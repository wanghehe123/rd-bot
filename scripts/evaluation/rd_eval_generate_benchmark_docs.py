#!/usr/bin/env python3
"""Create immutable, case-scoped RAG documents from a benchmark base repository.

The generator is intentionally unable to consume a Gold patch or runtime-withheld
test asset.  Those assets may be named in a case manifest only so their paths and
contents can be excluded and checked after generation.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from pathlib import Path, PurePosixPath
from typing import Any, Iterable


class DocumentationError(ValueError):
    """Raised when a benchmark knowledge snapshot cannot be safely frozen."""


DOCUMENT_LAYOUT = (
    ("repository-overview.md", "Repository overview"),
    ("build-and-test-guide.md", "Offline build and test guide"),
    ("architecture-and-conventions.md", "Architecture and conventions"),
    ("symbol-and-test-index.md", "Symbol and public-test index"),
)
GENERATOR_VERSION = "rd-eval-docs-v1"
_EXCLUDED_DIRECTORY_NAMES = frozenset({
    ".git", ".gradle", ".idea", ".mvn", ".next", ".rd-eval", ".venv", "__pycache__",
    "build", "coverage", "dist", "gold", "node_modules", "oracle", "output", "target",
    "vendor", "withheld", "runtime_withheld",
})
_TEXT_SUFFIXES = frozenset({
    ".cfg", ".gradle", ".java", ".js", ".json", ".kt", ".kts", ".md", ".mjs", ".properties",
    ".py", ".rst", ".sh", ".toml", ".ts", ".tsx", ".txt", ".xml", ".yaml", ".yml",
})
_BUILD_FILE_NAMES = frozenset({
    "build.gradle", "build.gradle.kts", "gradle.properties", "mvnw", "package.json", "pnpm-lock.yaml",
    "pom.xml", "pyproject.toml", "requirements.txt", "settings.gradle", "settings.gradle.kts", "yarn.lock",
})
_MAX_SOURCE_BYTES = 256 * 1024
_EXCERPT_CHARS = 3_600


def _sha256_text(value: str) -> str:
    return "sha256:" + hashlib.sha256(value.encode("utf-8")).hexdigest()


def _canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def _safe_case_id(value: object) -> str:
    case_id = str(value or "").strip()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", case_id):
        raise DocumentationError("caseId must be a safe immutable identifier")
    return case_id


def _safe_relative_path(value: object) -> PurePosixPath:
    raw = str(value or "").strip().replace("\\", "/")
    path = PurePosixPath(raw)
    if not raw or path.is_absolute() or ".." in path.parts or path == PurePosixPath("."):
        raise DocumentationError("protected asset path must stay beneath the base repository")
    return path


def _protected_paths(case: dict[str, Any], repository_root: Path) -> tuple[Path, ...]:
    values: list[object] = []
    for field in ("goldPatchPaths", "withheldTestPaths", "protectedPaths"):
        raw_values = case.get(field, [])
        if raw_values is None:
            continue
        if not isinstance(raw_values, list):
            raise DocumentationError(f"{field} must be a list")
        values.extend(raw_values)
    protected: list[Path] = []
    for value in values:
        relative = _safe_relative_path(value)
        candidate = (repository_root / relative).resolve(strict=False)
        if not candidate.is_relative_to(repository_root):
            raise DocumentationError("protected asset path must stay beneath the base repository")
        protected.append(candidate)
    return tuple(protected)


def _is_protected(path: Path, protected_paths: Iterable[Path]) -> bool:
    return any(path == protected or protected in path.parents for protected in protected_paths)


def _is_text_source(path: Path) -> bool:
    return path.suffix.lower() in _TEXT_SUFFIXES or path.name in _BUILD_FILE_NAMES or path.name.startswith("README")


def _safe_source_files(repository_root: Path, protected_paths: tuple[Path, ...]) -> list[Path]:
    sources: list[Path] = []
    for path in repository_root.rglob("*"):
        if path.is_symlink() or not path.is_file():
            continue
        relative = path.relative_to(repository_root)
        if any(part.lower() in _EXCLUDED_DIRECTORY_NAMES for part in relative.parts[:-1]):
            continue
        if _is_protected(path.resolve(), protected_paths) or not _is_text_source(path):
            continue
        if path.stat().st_size <= _MAX_SOURCE_BYTES:
            sources.append(path)
    return sorted(sources, key=lambda candidate: candidate.relative_to(repository_root).as_posix())


def _read_excerpt(path: Path, repository_root: Path) -> str:
    try:
        content = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return ""
    if not content.strip():
        return ""
    relative = path.relative_to(repository_root).as_posix()
    excerpt = content[:_EXCERPT_CHARS].rstrip()
    if len(content) > len(excerpt):
        excerpt += "\n… [excerpt truncated by frozen document generator]"
    return f"### `{relative}`\n\n```text\n{excerpt}\n```\n"


def _select_files(sources: list[Path], repository_root: Path, category: str) -> list[Path]:
    def relative(path: Path) -> str:
        return path.relative_to(repository_root).as_posix().lower()

    if category == "overview":
        selected = [path for path in sources if path.name.lower().startswith("readme") or path.name in _BUILD_FILE_NAMES]
    elif category == "build":
        selected = [path for path in sources if path.name in _BUILD_FILE_NAMES or "test" in relative(path)]
    elif category == "architecture":
        selected = [path for path in sources if any(part in {"src", "app", "lib", "server", "backend"} for part in path.relative_to(repository_root).parts)]
    else:
        selected = [path for path in sources if "test" in relative(path) or path.suffix.lower() in {".java", ".py", ".ts", ".tsx", ".js"}]
    # A small repository may not have the conventional directory names above.
    return (selected or sources)[:16]


def _render_document(title: str, case_id: str, files: list[Path], repository_root: Path) -> str:
    body = [f"# {title}", "", f"Frozen case: `{case_id}`", "", "This document was generated only from the frozen base repository and public assets.", ""]
    excerpts = [_read_excerpt(path, repository_root) for path in files]
    body.extend(excerpt for excerpt in excerpts if excerpt)
    if len(body) == 6:
        body.append("No readable public source files matched this document category.\n")
    return "\n".join(body).rstrip() + "\n"


def _protected_fingerprints(paths: tuple[Path, ...]) -> tuple[str, ...]:
    fingerprints: list[str] = []
    for protected in paths:
        if protected.is_dir():
            candidates = (candidate for candidate in protected.rglob("*") if candidate.is_file() and not candidate.is_symlink())
        elif protected.is_file() and not protected.is_symlink():
            candidates = (protected,)
        else:
            candidates = ()
        for candidate in candidates:
            if candidate.stat().st_size > _MAX_SOURCE_BYTES:
                continue
            try:
                text = candidate.read_text(encoding="utf-8").strip()
            except UnicodeDecodeError:
                continue
            if len(text) >= 12:
                fingerprints.append(text)
    return tuple(fingerprints)


def _find_leakage(documents: dict[str, str], protected_paths: tuple[Path, ...]) -> list[dict[str, str]]:
    hits: list[dict[str, str]] = []
    fingerprints = _protected_fingerprints(protected_paths)
    for document_path, document in documents.items():
        for fingerprint in fingerprints:
            if fingerprint in document:
                hits.append({"document": document_path, "kind": "protected-content", "sha256": _sha256_text(fingerprint)})
    return hits


def _chunks(documents: dict[str, str], chunk_characters: int) -> list[dict[str, Any]]:
    if not 2_400 <= chunk_characters <= 4_000:
        raise DocumentationError("chunkCharacters must stay between 2400 and 4000 (about 600-1000 tokens)")
    chunks: list[dict[str, Any]] = []
    for document_path, text in documents.items():
        for index, start in enumerate(range(0, len(text), chunk_characters)):
            content = text[start:start + chunk_characters]
            chunks.append({
                "document": document_path,
                "index": index,
                "start": start,
                "end": start + len(content),
                "tokenEstimate": math.ceil(len(content) / 4),
                "sha256": _sha256_text(content),
            })
    return chunks


def generate_case_documents(case: dict[str, Any], output_root: Path, *, chunk_characters: int = 3_200) -> dict[str, Any]:
    """Write one immutable four-document knowledge snapshot for a benchmark case."""

    case_id = _safe_case_id(case.get("caseId"))
    repository_value = str(case.get("repositoryRoot", "")).strip()
    if not repository_value:
        raise DocumentationError("repositoryRoot is required")
    repository_root = Path(repository_value).resolve()
    if not repository_root.is_dir():
        raise DocumentationError("repositoryRoot must be an existing base repository")
    protected_paths = _protected_paths(case, repository_root)
    output_directory = Path(output_root).resolve() / case_id
    if output_directory.exists() and any(output_directory.iterdir()):
        raise DocumentationError("knowledge output already exists; frozen documents cannot be overwritten")
    output_directory.mkdir(parents=True, exist_ok=True)

    sources = _safe_source_files(repository_root, protected_paths)
    categories = ("overview", "build", "architecture", "index")
    rendered: dict[str, str] = {}
    for (filename, title), category in zip(DOCUMENT_LAYOUT, categories, strict=True):
        rendered[filename] = _render_document(title, case_id, _select_files(sources, repository_root, category), repository_root)
    leakage = _find_leakage(rendered, protected_paths)
    if leakage:
        raise DocumentationError("generated documentation contains protected Gold or withheld-test content")

    for filename, content in rendered.items():
        (output_directory / filename).write_text(content, encoding="utf-8")
    document_entries = [
        {"path": filename, "sha256": _sha256_text(content), "tokenEstimate": math.ceil(len(content) / 4)}
        for filename, content in rendered.items()
    ]
    chunks = _chunks(rendered, chunk_characters)
    manifest = {
        "caseId": case_id,
        "generatorVersion": GENERATOR_VERSION,
        "documents": document_entries,
        "chunks": chunks,
        "leakage": {"hits": leakage, "protectedAssetCount": len(protected_paths)},
        "sourceFileCount": len(sources),
        "chunkCharacters": chunk_characters,
    }
    manifest["knowledgeSnapshotDigest"] = _sha256_text(_canonical_json(manifest))
    (output_directory / "knowledge-manifest.json").write_text(_canonical_json(manifest) + "\n", encoding="utf-8")
    return {"caseId": case_id, "outputDirectory": str(output_directory), "manifest": manifest}


def generate_benchmark_documents(manifest_path: Path, output_root: Path) -> dict[str, Any]:
    """Generate all case snapshots, rejecting formal-case tuning overrides."""

    config = json.loads(Path(manifest_path).read_text(encoding="utf-8"))
    cases = config.get("cases")
    if not isinstance(cases, list) or not cases:
        raise DocumentationError("benchmark manifest must declare cases")
    frozen_options = config.get("documentGenerator", {})
    if not isinstance(frozen_options, dict):
        raise DocumentationError("documentGenerator must be an object")
    chunk_characters = int(frozen_options.get("chunkCharacters", 3_200))
    results: list[dict[str, Any]] = []
    for case in cases:
        if not isinstance(case, dict):
            raise DocumentationError("benchmark cases must be objects")
        if case.get("documentGenerator") and str(case.get("kind", "FORMAL")).upper() != "DEVELOPMENT_PROBE":
            raise DocumentationError("only DEVELOPMENT_PROBE cases may tune document generation")
        case_chunk_size = int(case.get("documentGenerator", {}).get("chunkCharacters", chunk_characters))
        results.append(generate_case_documents(case, output_root, chunk_characters=case_chunk_size))
    report = {
        "ready": True,
        "caseCount": len(results),
        "generatorVersion": GENERATOR_VERSION,
        "results": [{"caseId": result["caseId"], "knowledgeSnapshotDigest": result["manifest"]["knowledgeSnapshotDigest"]} for result in results],
    }
    report["snapshotDigest"] = _sha256_text(_canonical_json(report))
    destination = Path(output_root).resolve() / "knowledge-readiness-report.json"
    destination.write_text(_canonical_json(report) + "\n", encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate frozen RAG documents for coding benchmark cases.")
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--output-root", required=True)
    args = parser.parse_args()
    generate_benchmark_documents(Path(args.manifest), Path(args.output_root))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (DocumentationError, json.JSONDecodeError) as error:
        print(f"benchmark documentation error: {error}")
        raise SystemExit(2)
