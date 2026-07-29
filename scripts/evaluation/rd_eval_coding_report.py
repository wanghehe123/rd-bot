#!/usr/bin/env python3
"""Render a reviewable Markdown report from frozen coding benchmark statistics."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def render_report(score: dict[str, Any]) -> str:
    lines = ["# Coding benchmark ablation report", "", "## Formal matrix", "", "| Case | A | B | C | D |", "| --- | --- | --- | --- | --- |"]
    for case_id, row in score.get("matrix", {}).items():
        lines.append(f"| {case_id} | {row.get('A', 'MISSING')} | {row.get('B', 'MISSING')} | {row.get('C', 'MISSING')} | {row.get('D', 'MISSING')} |")
    lines.extend(["", "## Paired contrasts", "", "| Slice | Contrast | paired N | net gain | missing | validity | exact McNemar p |", "| --- | --- | ---: | ---: | ---: | --- | ---: |"])
    for benchmark_slice, contrasts in score.get("contrasts", {}).items():
        for name, contrast in contrasts.items():
            lines.append(
                f"| {benchmark_slice} | {name} | {contrast.get('pairedN')} | {contrast.get('netGain')} | "
                f"{contrast.get('missingPairs')} | {contrast.get('validity')} | {contrast.get('exactMcNemarP')} |"
            )
    cost = score.get("cost", {}).get("D-A", {})
    lines.extend([
        "",
        "## Cost and stability",
        "",
        f"- D/A token ratio: {cost.get('tokenRatio')}",
        f"- D/A agent-time ratio: {cost.get('timeRatio')}",
        f"- Formal GoldFileHit@5 (post-hoc only): {score.get('rag', {}).get('formalGoldFileHitAt5')}",
        f"- Sentinel D-A direction reversals: {score.get('sentinel', {}).get('directionReversals')}",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Render coding benchmark statistics as Markdown.")
    parser.add_argument("--score", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    score = json.loads(Path(args.score).read_text(encoding="utf-8"))
    Path(args.output).write_text(render_report(score), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
