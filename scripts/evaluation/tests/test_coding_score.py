from __future__ import annotations

import unittest

from scripts.evaluation.rd_eval_coding_score import compare_arms, score_trials


def trial(
    case_id: str,
    arm: str,
    verdict: str,
    *,
    benchmark_slice: str = "FRESH_PRIMARY",
    replicate_no: int = 0,
    gold_file_hit_at_5: bool | None = None,
    tokens: int = 100,
    agent_seconds: int = 10,
) -> dict[str, object]:
    record: dict[str, object] = {
        "caseId": case_id,
        "arm": arm,
        "verdict": verdict,
        "slice": benchmark_slice,
        "replicateNo": replicate_no,
        "tokens": tokens,
        "agentExecutionSeconds": agent_seconds,
    }
    if gold_file_hit_at_5 is not None:
        record["rag"] = {"goldFileHitAt5": gold_file_hit_at_5}
    return record


class CodingScoreTest(unittest.TestCase):
    def test_formal_gold_file_hit_is_reported_but_never_a_readiness_failure(self) -> None:
        trials = [
            trial("fresh-01", "A", "TEST_FAIL"),
            trial("fresh-01", "B", "TEST_FAIL"),
            trial("fresh-01", "C", "TEST_FAIL", gold_file_hit_at_5=False),
            trial("fresh-01", "D", "PASS", gold_file_hit_at_5=False),
        ]

        report = score_trials(trials)

        self.assertTrue(report["readiness"]["passed"])
        self.assertEqual(0.0, report["rag"]["formalGoldFileHitAt5"])

    def test_missing_pair_bounds_mark_contrast_inconclusive(self) -> None:
        trials = [
            trial("fresh-01", "A", "INFRA_ERROR"),
            trial("fresh-01", "D", "PASS"),
        ]

        contrast = compare_arms(trials, "D", "A")

        self.assertEqual("INCONCLUSIVE", contrast["validity"])
        self.assertEqual(1, contrast["missingPairs"])
        self.assertEqual([-1, 1], contrast["netGainBounds"])

    def test_fresh_primary_contrast_uses_paired_wins_mcnemar_and_cost_ratios(self) -> None:
        trials = [
            trial("fresh-01", "A", "TEST_FAIL", tokens=100, agent_seconds=10),
            trial("fresh-01", "D", "PASS", tokens=250, agent_seconds=20),
            trial("fresh-02", "A", "PASS", tokens=100, agent_seconds=10),
            trial("fresh-02", "D", "TEST_FAIL", tokens=250, agent_seconds=20),
            trial("fresh-03", "A", "TEST_FAIL", tokens=100, agent_seconds=10),
            trial("fresh-03", "D", "PASS", tokens=250, agent_seconds=20),
        ]

        report = score_trials(trials)
        contrast = report["contrasts"]["FRESH_PRIMARY"]["D-A"]

        self.assertEqual(3, contrast["pairedN"])
        self.assertEqual(2, contrast["wins"])
        self.assertEqual(1, contrast["losses"])
        self.assertEqual(1, contrast["netGain"])
        self.assertIn("pairedNewcombe95", contrast)
        self.assertIn("exactMcNemarP", contrast)
        self.assertEqual(2.5, report["cost"]["D-A"]["tokenRatio"])
        self.assertEqual(2.0, report["cost"]["D-A"]["timeRatio"])

    def test_sentinel_repeat_is_reported_without_replacing_formal_result(self) -> None:
        trials = [
            trial("fresh-01", "A", "TEST_FAIL"),
            trial("fresh-01", "D", "PASS"),
            trial("fresh-01", "A", "PASS", replicate_no=1),
            trial("fresh-01", "D", "TEST_FAIL", replicate_no=1),
        ]

        report = score_trials(trials)

        self.assertEqual(1, report["sentinel"]["directionReversals"])
        self.assertEqual("PASS", report["matrix"]["fresh-01"]["D"])


if __name__ == "__main__":
    unittest.main()
