#!/usr/bin/env python3
"""Run a deterministic single-pass/iterative comparison over a frozen JSONL dataset.

The default mode records every case as pending reproduction when no external retrieval
results are supplied. It never fabricates Recall/MRR/NDCG values.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shlex
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from metrics import (  # noqa: E402
    aggregate_latency,
    evidence_coverage,
    mean_and_variation,
    ndcg,
    recall_at_k,
    reciprocal_rank,
    refusal_correctness,
)


ROOT = Path(__file__).resolve().parents[1]


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical_hash(value) -> str:
    return sha256_bytes(json.dumps(value, sort_keys=True, separators=(",", ":")).encode())


def command_output(*command, fallback="") -> str:
    try:
        return subprocess.check_output(command, cwd=ROOT.parents[1], text=True, stderr=subprocess.DEVNULL).strip()
    except (OSError, subprocess.CalledProcessError):
        return fallback


def dirty_tree_hash(repo_root: Path, excluded_paths: set[str] | None = None) -> str:
    """Hash tracked diff plus bytes of every untracked, non-ignored file.

    The output run is excluded because its metadata necessarily contains this hash.
    """
    excluded = {path.replace("\\", "/").strip("/") for path in (excluded_paths or set())}
    try:
        tracked = subprocess.check_output(
            ("git", "diff", "--no-ext-diff", "--binary", "HEAD", "--"),
            cwd=repo_root, stderr=subprocess.DEVNULL
        )
        untracked = subprocess.check_output(
            ("git", "ls-files", "--others", "--exclude-standard", "-z"),
            cwd=repo_root, stderr=subprocess.DEVNULL
        ).split(b"\0")
    except (OSError, subprocess.CalledProcessError):
        return "unknown"
    digest = hashlib.sha256()
    digest.update(b"tracked-diff\0")
    digest.update(tracked)
    for raw_path in sorted(path for path in untracked if path):
        relative = raw_path.decode("utf-8", errors="strict").replace("\\", "/")
        if any(relative == excluded_path or relative.startswith(excluded_path + "/")
               for excluded_path in excluded):
            continue
        candidate = repo_root / relative
        if not candidate.is_file():
            continue
        digest.update(b"untracked\0")
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(candidate.read_bytes())
    return digest.hexdigest()


def load_cases(path: Path) -> list[dict]:
    cases = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            cases.append(json.loads(line))
    if not cases:
        raise ValueError(f"dataset is empty: {path}")
    return cases


def load_results(path: Path | None) -> dict[str, dict]:
    if path is None:
        return {}
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            row = json.loads(line)
            result[row["caseId"]] = row
    return result


def observations(cases: list[dict], result_by_case: dict[str, dict], mode: str) -> list[dict]:
    output = []
    for case in cases:
        case_id = case["caseId"]
        supplied = result_by_case.get(case_id, {})
        row = {
            "caseId": case_id,
            "questionId": case.get("questionId", ""),
            "mode": mode,
            "status": supplied.get("status", "pending-reproduction"),
            "query": supplied.get("query", case.get("notes", "")),
            "retrievedEvidenceIds": supplied.get("retrievedEvidenceIds", []),
            "relevantEvidenceIds": supplied.get("relevantEvidenceIds"),
            "selectedEvidenceIds": supplied.get("selectedEvidenceIds", []),
            "requiredEvidenceIds": supplied.get("requiredEvidenceIds"),
            "predictedRefusal": supplied.get("predictedRefusal"),
            "expectedRefusal": supplied.get("expectedRefusal"),
            "latencyMs": supplied.get("latencyMs"),
        }
        output.append(row)
    return output


def aggregate(rows: list[dict], top_k: int) -> dict:
    recalls, ranks, ndcgs, coverage, refusals, latencies = [], [], [], [], [], []
    for row in rows:
        relevant = row.get("relevantEvidenceIds")
        if relevant is not None:
            recall = recall_at_k(row.get("retrievedEvidenceIds", []), relevant, top_k)
            rank = reciprocal_rank(row.get("retrievedEvidenceIds", []), relevant)
            score = ndcg(row.get("retrievedEvidenceIds", []), relevant, top_k)
            if recall is not None:
                recalls.append(recall)
            if rank is not None:
                ranks.append(rank)
            if score is not None:
                ndcgs.append(score)
        required = row.get("requiredEvidenceIds")
        if required is not None:
            score = evidence_coverage(row.get("selectedEvidenceIds", []), required)
            if score is not None:
                coverage.append(score)
        if row.get("predictedRefusal") is not None and row.get("expectedRefusal") is not None:
            refusals.append(refusal_correctness(row["predictedRefusal"], row["expectedRefusal"]))
        if row.get("latencyMs") is not None:
            latencies.append(row["latencyMs"])
    scored = bool(recalls or ranks or ndcgs or coverage or refusals or latencies)
    return {
        "status": "verified" if scored else "pending-reproduction",
        "sampleCount": len(rows),
        "scoredCount": max(len(recalls), len(ranks), len(ndcgs), len(coverage), len(refusals)),
        "recallAtK": mean_and_variation(recalls),
        "mrr": mean_and_variation(ranks),
        "ndcg": mean_and_variation(ndcgs),
        "evidenceCoverage": mean_and_variation(coverage),
        "refusalCorrectness": {
            "count": len(refusals),
            "accuracy": (sum(refusals) / len(refusals)) if refusals else None,
        },
        "latency": aggregate_latency(latencies),
        "unsupportedMetrics": [] if scored else ["Recall@K", "MRR", "NDCG", "evidenceCoverage", "latency"],
    }


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def run(args) -> Path:
    dataset = Path(args.dataset).resolve()
    cases = load_cases(dataset)
    run_dir = ROOT / "raw" / args.run_id
    report_path = ROOT / "reports" / f"{args.run_id}.html"
    if run_dir.exists() or report_path.exists():
        raise FileExistsError(f"immutable run already exists: {args.run_id}")
    repo_root = ROOT.parents[1]
    run_relative = run_dir.relative_to(repo_root).as_posix()
    report_relative = report_path.relative_to(repo_root).as_posix()
    runner_command = ["python3", "benchmarks/interview-claims/runners/run-evaluation.py"]
    for key, value in vars(args).items():
        if key == "provenance_command" or value is None:
            continue
        if isinstance(value, list):
            runner_command.extend(
                argument for item in value for argument in (f"--{key.replace('_', '-')}={item}",)
            )
        elif key != "run_id":
            runner_command.append(f"--{key.replace('_', '-')}={value}")
    runner_command.append(f"--run-id={args.run_id}")
    run_dir.mkdir(parents=True)

    single = observations(cases, load_results(Path(args.single_results).resolve()) if args.single_results else {}, "single-pass")
    iterative = observations(cases, load_results(Path(args.iterative_results).resolve()) if args.iterative_results else {}, "iterative")
    (run_dir / "single-pass.jsonl").write_text("".join(json.dumps(row, ensure_ascii=True, sort_keys=True) + "\n" for row in single), encoding="utf-8")
    (run_dir / "iterative.jsonl").write_text("".join(json.dumps(row, ensure_ascii=True, sort_keys=True) + "\n" for row in iterative), encoding="utf-8")

    dataset_bytes = dataset.read_bytes()
    metadata = {
        "runId": args.run_id,
        "status": "pending-reproduction",
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "datasetVersion": args.dataset_version,
        "datasetPath": str(dataset.relative_to(ROOT.parents[1])) if dataset.is_relative_to(ROOT.parents[1]) else str(dataset),
        "datasetSha256": sha256_bytes(dataset_bytes),
        "gitCommit": command_output("git", "rev-parse", "HEAD", fallback="unknown"),
        "dirtyTreeHash": dirty_tree_hash(repo_root, {run_relative, report_relative}),
        "configHash": canonical_hash(vars(args)),
        "model": args.model,
        "temperature": args.temperature,
        "budget": args.budget,
        "runCount": 1,
        "sampleCount": len(cases),
        "commands": list(args.provenance_command or []) + [shlex.join(runner_command)],
        "toolVersions": {"python": sys.version.split()[0]},
    }
    metrics = {
        "runId": args.run_id,
        "datasetVersion": args.dataset_version,
        "topK": args.top_k,
        "singlePass": aggregate(single, args.top_k),
        "iterative": aggregate(iterative, args.top_k),
    }
    statuses = {metrics[mode].get("status") for mode in ("singlePass", "iterative")}
    metadata["status"] = "verified" if statuses == {"verified"} else "pending-reproduction"
    write_json(run_dir / "metadata.json", metadata)
    write_json(run_dir / "metrics.json", metrics)
    (run_dir / "commands.txt").write_text(
        "\n".join(metadata["commands"])
        + "\n", encoding="utf-8"
    )
    render_report(run_dir, report_path, metadata, metrics, single, iterative)
    return run_dir


def render_report(run_dir: Path, report_path: Path, metadata: dict, metrics: dict,
                  single: list[dict], iterative: list[dict]) -> None:
    def esc(value):
        import html
        return html.escape(str(value))

    rows = []
    for row in single + iterative:
        rows.append("<tr>" + "".join(f"<td>{esc(row.get(key, ''))}</td>" for key in ("caseId", "mode", "status")) + "</tr>")
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(
        "<!doctype html><meta charset='utf-8'><title>Interview claims run " + esc(metadata["runId"]) + "</title>"
        "<h1>Interview claims run " + esc(metadata["runId"]) + "</h1>"
        "<p>Status: <strong>" + esc(metadata["status"]) + "</strong>; sample count: " + esc(metadata["sampleCount"]) + "</p>"
        "<pre>" + esc(json.dumps(metrics, ensure_ascii=True, indent=2, sort_keys=True)) + "</pre>"
        "<table><thead><tr><th>Case</th><th>Mode</th><th>Status</th></tr></thead><tbody>"
        + "".join(rows) + "</tbody></table>\n", encoding="utf-8"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--dataset", default=str(ROOT / "datasets" / "v0" / "cases.jsonl"))
    parser.add_argument("--dataset-version", default="v0")
    parser.add_argument("--single-results")
    parser.add_argument("--iterative-results")
    parser.add_argument("--provenance-command", action="append")
    parser.add_argument("--model", default="unspecified")
    parser.add_argument("--temperature", type=float)
    parser.add_argument("--budget", type=int)
    parser.add_argument("--top-k", type=int, default=5)
    args = parser.parse_args()
    print(run(args))


if __name__ == "__main__":
    main()
