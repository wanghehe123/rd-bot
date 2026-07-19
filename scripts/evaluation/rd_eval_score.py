#!/usr/bin/env python3
"""Score RD-Bot evaluation records."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

if __package__ is None or __package__ == "":
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts.evaluation import rd_eval_lib as lib


def main() -> int:
    parser = argparse.ArgumentParser(description="Score RD-Bot eval JSONL records.")
    parser.add_argument("--dataset", default="", help="Path to RdEvalSample JSONL data. Defaults to dataset_path in records.")
    parser.add_argument("--run-id", default="", help="Run id to score.")
    parser.add_argument("--latest", action="store_true", help="Score latest run under output root.")
    parser.add_argument("--output-root", default=str(lib.DEFAULT_OUTPUT_ROOT))
    parser.add_argument("--skip-judge", action="store_true", help="Do not call an AI judge provider.")
    parser.add_argument("--judge-provider", default="none", help="none, ragas, or openai-compatible.")
    parser.add_argument("--judge-limit", type=int, default=0, help="Maximum samples to judge. 0 means all.")
    parser.add_argument(
        "--strict-missing-records",
        action="store_true",
        help="Score all dataset rows and fail rows missing from a partial run.",
    )
    args = parser.parse_args()

    root = lib.output_root(args.output_root)
    run_id = args.run_id or (lib.latest_run_id(root) if args.latest else "")
    if not run_id:
        raise SystemExit("--run-id or --latest is required")
    records_path = lib.run_path(root, run_id)
    records = lib.load_jsonl(records_path)
    if args.dataset:
        dataset_path = lib.resolve_repo_path(args.dataset)
    elif records and records[0].get("dataset_path"):
        dataset_path = lib.resolve_repo_path(str(records[0]["dataset_path"]))
    else:
        raise SystemExit("--dataset is required when records do not contain dataset_path")
    samples = lib.load_dataset(dataset_path)
    samples = lib.scored_samples_for_records(
        samples,
        records,
        include_missing_records=args.strict_missing_records,
    )

    provider = lib.NoopJudgeProvider() if args.skip_judge else lib.judge_provider_from_name(args.judge_provider)
    score = lib.score_records(
        samples=samples,
        records=records,
        judge_provider=provider,
        judge_limit=args.judge_limit if args.judge_limit > 0 else None,
    )
    score["provenance"] = lib.score_provenance(dataset_path, records_path)
    path = lib.score_path(root, run_id)
    lib.write_score(path, score)
    print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
