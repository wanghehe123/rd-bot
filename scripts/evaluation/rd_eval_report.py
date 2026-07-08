#!/usr/bin/env python3
"""Render human-readable RD-Bot evaluation reports."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

if __package__ is None or __package__ == "":
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts.evaluation import rd_eval_lib as lib


def main() -> int:
    parser = argparse.ArgumentParser(description="Render RD-Bot eval Markdown/CSV/JSONL reports.")
    parser.add_argument("--run-id", default="", help="Run id to render.")
    parser.add_argument("--latest", action="store_true", help="Render latest run under output root.")
    parser.add_argument("--output-root", default=str(lib.DEFAULT_OUTPUT_ROOT))
    parser.add_argument("--scores", default="", help="Explicit _scores.json path.")
    args = parser.parse_args()

    root = lib.output_root(args.output_root)
    run_id = args.run_id or (lib.latest_run_id(root) if args.latest else "")
    if args.scores:
        score_path = lib.resolve_repo_path(args.scores)
    else:
        if not run_id:
            raise SystemExit("--run-id, --latest, or --scores is required")
        score_path = lib.score_path(root, run_id)
    score = lib.load_score(score_path)
    actual_run_id = run_id or str(score.get("run_id", "unknown-run"))
    report_dir = lib.report_dir(root, actual_run_id)
    score = lib.apply_manual_overrides_to_score(score, report_dir / "per_sample.csv")
    lib.write_report_files(report_dir, score)
    print(report_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
