#!/usr/bin/env python3
"""Compare two RD-Bot evaluation score files."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

if __package__ is None or __package__ == "":
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts.evaluation import rd_eval_lib as lib


def main() -> int:
    parser = argparse.ArgumentParser(description="Diff two RD-Bot eval _scores.json files.")
    parser.add_argument("--baseline", required=True)
    parser.add_argument("--candidate", required=True)
    parser.add_argument("--output", default="", help="Optional diff.md output path.")
    parser.add_argument("--fail-on-regression", action="store_true")
    args = parser.parse_args()

    diff = lib.diff_scores(lib.load_score(args.baseline), lib.load_score(args.candidate))
    rendered = lib.render_diff_markdown(diff)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered, encoding="utf-8")
        print(output)
    else:
        print(rendered)
    if args.fail_on_regression and diff["regressions"]:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
