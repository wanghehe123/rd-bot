#!/usr/bin/env python3
"""Frozen, offline statistics for the RD-Bot coding-ablation benchmark."""

from __future__ import annotations

import argparse
import json
import math
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable


PASS = "PASS"
INFRA_ERROR = "INFRA_ERROR"
FORMAL_REPLICATE = 0
SLICES = ("FRESH_PRIMARY", "PUBLIC_ANCHOR", "FULL")
ARMS = ("A", "B", "C", "D")
_Z_95 = 1.959963984540054
_CHI_SQUARE_95_1DF = 3.841458820694124


class CodingScoreError(ValueError):
    """Raised when frozen Trial facts do not form a scoreable benchmark export."""


def _value(trial: dict[str, Any], *names: str, default: Any = None) -> Any:
    for name in names:
        if name in trial:
            return trial[name]
    return default


def _arm(trial: dict[str, Any]) -> str:
    arm = str(_value(trial, "arm", "benchmarkArm", default="")).upper()
    if arm not in ARMS:
        raise CodingScoreError("trial arm must be A, B, C, or D")
    return arm


def _case_id(trial: dict[str, Any]) -> str:
    case_id = str(_value(trial, "caseId", "case_id", default="")).strip()
    if not case_id:
        raise CodingScoreError("trial caseId is required")
    return case_id


def _slice(trial: dict[str, Any]) -> str:
    benchmark_slice = str(_value(trial, "slice", "benchmarkSlice", "benchmark_slice", default="FRESH_PRIMARY")).upper()
    if benchmark_slice not in {"FRESH_PRIMARY", "PUBLIC_ANCHOR"}:
        raise CodingScoreError("trial slice must be FRESH_PRIMARY or PUBLIC_ANCHOR")
    return benchmark_slice


def _replicate_no(trial: dict[str, Any]) -> int:
    try:
        return int(_value(trial, "replicateNo", "replicate_no", default=0))
    except (TypeError, ValueError) as error:
        raise CodingScoreError("replicateNo must be an integer") from error


def _verdict(trial: dict[str, Any]) -> str:
    verdict = str(_value(trial, "verdict", "finalVerdict", default="")).upper()
    if not verdict:
        raise CodingScoreError("trial verdict is required")
    return verdict


def _is_infra(trial: dict[str, Any]) -> bool:
    return _verdict(trial) == INFRA_ERROR


def _is_pass(trial: dict[str, Any]) -> bool:
    return _verdict(trial) == PASS


def _formal_trials(trials: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    return [trial for trial in trials if _replicate_no(trial) == FORMAL_REPLICATE]


def _trials_for_slice(trials: Iterable[dict[str, Any]], benchmark_slice: str) -> list[dict[str, Any]]:
    if benchmark_slice == "FULL":
        return list(trials)
    return [trial for trial in trials if _slice(trial) == benchmark_slice]


def _by_case_arm(trials: Iterable[dict[str, Any]]) -> dict[tuple[str, str], dict[str, Any]]:
    mapped: dict[tuple[str, str], dict[str, Any]] = {}
    for trial in trials:
        key = (_case_id(trial), _arm(trial))
        if key in mapped:
            raise CodingScoreError(f"duplicate logical trial for case={key[0]} arm={key[1]}")
        mapped[key] = trial
    return mapped


def wilson_interval(successes: int, total: int) -> list[float | None]:
    """Two-sided 95% Wilson score interval, expressed as proportions."""

    if total <= 0:
        return [None, None]
    proportion = successes / total
    denominator = 1 + (_Z_95 ** 2) / total
    centre = (proportion + (_Z_95 ** 2) / (2 * total)) / denominator
    radius = _Z_95 * math.sqrt((proportion * (1 - proportion) / total) + (_Z_95 ** 2) / (4 * total * total)) / denominator
    return [round(max(0.0, centre - radius), 6), round(min(1.0, centre + radius), 6)]


def _log_likelihood(counts: tuple[int, int, int, int], probabilities: tuple[float, float, float, float]) -> float:
    total = 0.0
    for count, probability in zip(counts, probabilities, strict=True):
        if count == 0:
            continue
        if probability <= 0:
            return float("-inf")
        total += count * math.log(probability)
    return total


def _profile_log_likelihood(counts: tuple[int, int, int, int], difference: float) -> float:
    """Profile a paired 2x2 multinomial under p(experimental)-p(control)=difference.

    This score-inversion form keeps all four paired outcomes rather than treating
    the two arms as independent samples.  It is the paired Newcombe-style score
    interval used by the frozen analysis plan.
    """

    both_pass, experimental_only, control_only, neither_pass = counts
    epsilon = 1e-12
    lower = max(0.0, -difference) + epsilon
    upper = (1.0 - difference) / 2.0 - epsilon
    if lower > upper:
        return float("-inf")
    stable = both_pass + neither_pass

    def derivative(value: float) -> float:
        result = 0.0
        if experimental_only:
            result += experimental_only / (value + difference)
        if control_only:
            result += control_only / value
        if stable:
            result -= 2.0 * stable / (1.0 - 2.0 * value - difference)
        return result

    low_derivative = derivative(lower)
    high_derivative = derivative(upper)
    if low_derivative <= 0:
        control_only_probability = lower
    elif high_derivative >= 0:
        control_only_probability = upper
    else:
        left, right = lower, upper
        for _ in range(100):
            midpoint = (left + right) / 2.0
            if derivative(midpoint) > 0:
                left = midpoint
            else:
                right = midpoint
        control_only_probability = (left + right) / 2.0

    remaining = 1.0 - 2.0 * control_only_probability - difference
    if stable:
        both_probability = remaining * both_pass / stable
        neither_probability = remaining * neither_pass / stable
    else:
        both_probability = remaining / 2.0
        neither_probability = remaining / 2.0
    return _log_likelihood(
        counts,
        (both_probability, control_only_probability + difference, control_only_probability, neither_probability),
    )


def paired_newcombe_interval(wins: int, losses: int, ties_pass: int, ties_fail: int) -> list[float | None]:
    """95% paired score interval for the difference in resolved proportions."""

    total = wins + losses + ties_pass + ties_fail
    if total == 0:
        return [None, None]
    counts = (ties_pass, wins, losses, ties_fail)
    empirical = (wins - losses) / total
    unconstrained = _log_likelihood(counts, tuple(count / total for count in counts))

    def exceeds_cutoff(value: float) -> bool:
        constrained = _profile_log_likelihood(counts, value)
        return not math.isfinite(constrained) or 2.0 * (unconstrained - constrained) > _CHI_SQUARE_95_1DF

    epsilon = 1e-10
    lower_bound = -1.0
    if exceeds_cutoff(-1.0 + epsilon):
        left, right = -1.0 + epsilon, empirical
        for _ in range(80):
            midpoint = (left + right) / 2.0
            if exceeds_cutoff(midpoint):
                left = midpoint
            else:
                right = midpoint
        lower_bound = right
    upper_bound = 1.0
    if exceeds_cutoff(1.0 - epsilon):
        left, right = empirical, 1.0 - epsilon
        for _ in range(80):
            midpoint = (left + right) / 2.0
            if exceeds_cutoff(midpoint):
                right = midpoint
            else:
                left = midpoint
        upper_bound = left
    return [round(lower_bound, 6), round(upper_bound, 6)]


def exact_mcnemar_p_value(wins: int, losses: int) -> float:
    discordant = wins + losses
    if discordant == 0:
        return 1.0
    tail = sum(math.comb(discordant, index) for index in range(0, min(wins, losses) + 1)) / (2 ** discordant)
    return round(min(1.0, 2.0 * tail), 8)


def _trial_summary(trials: list[dict[str, Any]], arm: str) -> dict[str, Any]:
    selected = [trial for trial in trials if _arm(trial) == arm and not _is_infra(trial)]
    passed = sum(_is_pass(trial) for trial in selected)
    return {
        "passed": passed,
        "denominator": len(selected),
        "resolvedAt1": round(passed / len(selected), 6) if selected else None,
        "wilson95": wilson_interval(passed, len(selected)),
        "infraExcluded": sum(_arm(trial) == arm and _is_infra(trial) for trial in trials),
    }


def compare_arms(
    trials: Iterable[dict[str, Any]],
    experimental_arm: str,
    control_arm: str,
    benchmark_slice: str = "FULL",
) -> dict[str, Any]:
    """Score one paired contrast, treating any missing/infra pair as unknown."""

    experimental_arm = experimental_arm.upper()
    control_arm = control_arm.upper()
    if experimental_arm not in ARMS or control_arm not in ARMS or experimental_arm == control_arm:
        raise CodingScoreError("contrast must compare two different benchmark arms")
    formal = _formal_trials(trials)
    selected = _trials_for_slice(formal, benchmark_slice)
    mapped = _by_case_arm(selected)
    case_ids = sorted({case_id for case_id, arm in mapped if arm in {experimental_arm, control_arm}})
    wins = losses = ties_pass = ties_fail = missing = 0
    for case_id in case_ids:
        experimental = mapped.get((case_id, experimental_arm))
        control = mapped.get((case_id, control_arm))
        if experimental is None or control is None or _is_infra(experimental) or _is_infra(control):
            missing += 1
            continue
        experimental_pass = _is_pass(experimental)
        control_pass = _is_pass(control)
        if experimental_pass and not control_pass:
            wins += 1
        elif control_pass and not experimental_pass:
            losses += 1
        elif experimental_pass:
            ties_pass += 1
        else:
            ties_fail += 1
    paired_n = wins + losses + ties_pass + ties_fail
    net_gain = wins - losses
    return {
        "experimentalArm": experimental_arm,
        "controlArm": control_arm,
        "slice": benchmark_slice,
        "pairedN": paired_n,
        "missingPairs": missing,
        "wins": wins,
        "losses": losses,
        "tiesPass": ties_pass,
        "tiesFail": ties_fail,
        "netGain": net_gain,
        "netGainBounds": [net_gain - missing, net_gain + missing],
        "difference": round(net_gain / paired_n, 6) if paired_n else None,
        "pairedNewcombe95": paired_newcombe_interval(wins, losses, ties_pass, ties_fail),
        "exactMcNemarP": exact_mcnemar_p_value(wins, losses),
        "validity": "VALID" if paired_n and not missing else "INCONCLUSIVE",
    }


def holm_adjust(p_values: dict[str, float]) -> dict[str, float]:
    """Return familywise Holm-adjusted p-values while preserving contrast labels."""

    ordered = sorted(p_values.items(), key=lambda entry: entry[1])
    adjusted: dict[str, float] = {}
    running = 0.0
    count = len(ordered)
    for index, (name, p_value) in enumerate(ordered):
        running = max(running, min(1.0, (count - index) * p_value))
        adjusted[name] = round(running, 8)
    return adjusted


def _numeric(trial: dict[str, Any], *names: str) -> float | None:
    value = _value(trial, *names)
    if value is None:
        return None
    try:
        numeric = float(value)
    except (TypeError, ValueError):
        return None
    return numeric if numeric >= 0 else None


def _cost_ratios(formal_trials: list[dict[str, Any]]) -> dict[str, Any]:
    mapped = _by_case_arm(formal_trials)
    case_ids = sorted({case_id for case_id, arm in mapped if arm in {"A", "D"}})
    numerator_tokens = denominator_tokens = numerator_seconds = denominator_seconds = 0.0
    paired_n = 0
    for case_id in case_ids:
        baseline = mapped.get((case_id, "A"))
        full = mapped.get((case_id, "D"))
        if baseline is None or full is None or _is_infra(baseline) or _is_infra(full):
            continue
        baseline_tokens = _numeric(baseline, "tokens", "totalTokens", "modelTokens")
        full_tokens = _numeric(full, "tokens", "totalTokens", "modelTokens")
        baseline_seconds = _numeric(baseline, "agentExecutionSeconds", "agentSeconds")
        full_seconds = _numeric(full, "agentExecutionSeconds", "agentSeconds")
        if None in {baseline_tokens, full_tokens, baseline_seconds, full_seconds}:
            continue
        paired_n += 1
        denominator_tokens += baseline_tokens or 0.0
        numerator_tokens += full_tokens or 0.0
        denominator_seconds += baseline_seconds or 0.0
        numerator_seconds += full_seconds or 0.0
    return {
        "pairedN": paired_n,
        "tokenRatio": round(numerator_tokens / denominator_tokens, 6) if denominator_tokens else None,
        "timeRatio": round(numerator_seconds / denominator_seconds, 6) if denominator_seconds else None,
    }


def _formal_rag_metrics(formal_trials: list[dict[str, Any]]) -> dict[str, Any]:
    hits: list[bool] = []
    injected_tokens: list[float] = []
    cross_kb_hits = 0
    for trial in formal_trials:
        if _arm(trial) not in {"C", "D"}:
            continue
        rag = trial.get("rag")
        if not isinstance(rag, dict):
            continue
        hit = _value(rag, "goldFileHitAt5", "gold_file_hit_at_5")
        if isinstance(hit, bool):
            hits.append(hit)
        tokens = _numeric(rag, "injectedTokens", "injected_tokens")
        if tokens is not None:
            injected_tokens.append(tokens)
        raw_cross_hits = _numeric(rag, "crossKnowledgeBaseHits", "cross_knowledge_base_hits")
        cross_kb_hits += int(raw_cross_hits or 0)
    return {
        "formalGoldFileHitAt5": round(sum(hits) / len(hits), 6) if hits else None,
        "formalGoldFileHitSampleN": len(hits),
        "averageInjectedTokens": round(sum(injected_tokens) / len(injected_tokens), 6) if injected_tokens else None,
        "crossKnowledgeBaseHits": cross_kb_hits,
    }


def _sentinel_metrics(trials: list[dict[str, Any]]) -> dict[str, Any]:
    grouped: dict[tuple[str, str], dict[int, dict[str, Any]]] = defaultdict(dict)
    for trial in trials:
        replicate = _replicate_no(trial)
        if _arm(trial) in {"A", "D"} and replicate in {0, 1}:
            grouped[(_case_id(trial), _arm(trial))][replicate] = trial
    flips = {"A": 0, "D": 0}
    repeated = {"A": 0, "D": 0}
    for (_, arm), copies in grouped.items():
        if 0 in copies and 1 in copies and not _is_infra(copies[0]) and not _is_infra(copies[1]):
            repeated[arm] += 1
            flips[arm] += _is_pass(copies[0]) != _is_pass(copies[1])
    directions: dict[str, dict[int, int]] = defaultdict(dict)
    for (case_id, arm), copies in grouped.items():
        for replicate, trial in copies.items():
            if not _is_infra(trial):
                directions[case_id][replicate] = directions[case_id].get(replicate, 0) + (1 if arm == "D" and _is_pass(trial) else -1 if arm == "D" else -1 if _is_pass(trial) else 1)
    reversals = sum(0 in values and 1 in values and values[0] * values[1] < 0 for values in directions.values())
    return {
        "repeatN": repeated,
        "flipCounts": flips,
        "flipRates": {arm: round(flips[arm] / repeated[arm], 6) if repeated[arm] else None for arm in ("A", "D")},
        "directionReversals": reversals,
    }


def score_trials(trials: Iterable[dict[str, Any]], analysis_plan: dict[str, Any] | None = None) -> dict[str, Any]:
    """Produce an immutable report from frozen Trial JSON facts only."""

    records = [dict(trial) for trial in trials]
    formal = _formal_trials(records)
    # Ensure malformed duplicate logical rows fail before any headline metric is emitted.
    _by_case_arm(formal)
    matrix: dict[str, dict[str, str]] = defaultdict(dict)
    for trial in formal:
        matrix[_case_id(trial)][_arm(trial)] = _verdict(trial)
    contrasts: dict[str, dict[str, Any]] = {}
    arm_summaries: dict[str, dict[str, Any]] = {}
    for benchmark_slice in SLICES:
        sliced = _trials_for_slice(formal, benchmark_slice)
        arm_summaries[benchmark_slice] = {arm: _trial_summary(sliced, arm) for arm in ARMS}
        slice_contrasts = {
            "B-A": compare_arms(sliced, "B", "A", "FULL"),
            "C-B": compare_arms(sliced, "C", "B", "FULL"),
            "D-C": compare_arms(sliced, "D", "C", "FULL"),
            "D-A": compare_arms(sliced, "D", "A", "FULL"),
        }
        exploratory = holm_adjust({name: slice_contrasts[name]["exactMcNemarP"] for name in ("B-A", "C-B", "D-C")})
        for name, adjusted in exploratory.items():
            slice_contrasts[name]["holmAdjustedExactMcNemarP"] = adjusted
        contrasts[benchmark_slice] = slice_contrasts
    report = {
        "readiness": {
            "passed": True,
            "formalGoldFileHitIsPostHocOnly": True,
            "analysisPlanDigest": (analysis_plan or {}).get("analysisPlanDigest"),
        },
        "formalTrialCount": len(formal),
        "matrix": {case_id: matrix[case_id] for case_id in sorted(matrix)},
        "arms": arm_summaries,
        "contrasts": contrasts,
        "cost": {"D-A": _cost_ratios(formal)},
        "rag": _formal_rag_metrics(formal),
        "sentinel": _sentinel_metrics(records),
    }
    return report


def _load_jsonl(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        value = json.loads(line)
        if not isinstance(value, dict):
            raise CodingScoreError(f"trial record {line_number} must be an object")
        records.append(value)
    return records


def main() -> int:
    parser = argparse.ArgumentParser(description="Score frozen coding benchmark Trial records.")
    parser.add_argument("--trials", required=True, help="Frozen trials.jsonl")
    parser.add_argument("--analysis-plan", default="")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    plan = json.loads(Path(args.analysis_plan).read_text(encoding="utf-8")) if args.analysis_plan else None
    report = score_trials(_load_jsonl(Path(args.trials)), plan)
    Path(args.output).write_text(json.dumps(report, sort_keys=True, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (CodingScoreError, json.JSONDecodeError) as error:
        print(f"coding score error: {error}")
        raise SystemExit(2)
