#!/usr/bin/env python3
"""Record RD-Bot evaluation runs from fixtures or live RAG HTTP."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

if __package__ is None or __package__ == "":
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts.evaluation import rd_eval_lib as lib


def main() -> int:
    parser = argparse.ArgumentParser(description="Record RD-Bot eval records as JSONL.")
    parser.add_argument("--dataset", required=True, help="Path to RdEvalSample JSONL data.")
    parser.add_argument("--source", choices=["fixture", "rag-http"], default="fixture")
    parser.add_argument("--run-id", default="", help="Stable run id. Defaults to rd-eval-<timestamp>.")
    parser.add_argument("--environment-id", default="local")
    parser.add_argument("--output-root", default=str(lib.DEFAULT_OUTPUT_ROOT))
    parser.add_argument("--limit", type=int, default=0, help="Limit sample count. 0 means all.")
    parser.add_argument("--base-url", default="http://127.0.0.1:18080", help="RD-Bot base URL for rag-http mode.")
    parser.add_argument("--rag-log", default="logs/rag-retrieval.jsonl", help="RAG retrieval JSONL log path for rag-http mode.")
    parser.add_argument("--timeout-seconds", type=float, default=30.0)
    parser.add_argument("--overwrite", action="store_true", help="Overwrite an existing run JSONL.")
    args = parser.parse_args()

    dataset_path = lib.resolve_repo_path(args.dataset)
    samples = lib.load_dataset(dataset_path)
    if args.limit > 0:
        samples = samples[: args.limit]
    run_id = args.run_id or lib.generate_run_id()
    root = lib.output_root(args.output_root)

    if args.source == "fixture":
        records = lib.records_from_fixtures(samples, run_id, args.environment_id, str(dataset_path))
    else:
        records = lib.records_from_rag_http(
            samples=samples,
            run_id=run_id,
            environment_id=args.environment_id,
            dataset_path=str(dataset_path),
            base_url=args.base_url,
            rag_log_path=lib.resolve_repo_path(args.rag_log) if args.rag_log else None,
            timeout_seconds=args.timeout_seconds,
        )

    path = lib.run_path(root, run_id)
    lib.write_jsonl(path, records, overwrite=args.overwrite)
    print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
