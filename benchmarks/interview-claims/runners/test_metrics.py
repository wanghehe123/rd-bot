#!/usr/bin/env python3
"""Unit tests for deterministic interview-claim metrics."""

import unittest

from metrics import (
    aggregate_latency,
    evidence_coverage,
    mean_and_variation,
    ndcg,
    recall_at_k,
    reciprocal_rank,
    refusal_correctness,
)


class MetricsTest(unittest.TestCase):
    def test_rank_metrics_are_deterministic(self):
        retrieved = ["a", "b", "c"]
        relevant = {"b", "c"}
        self.assertEqual(0.5, recall_at_k(retrieved, relevant, 2))
        self.assertEqual(0.5, reciprocal_rank(retrieved, relevant))
        self.assertAlmostEqual(0.386853, ndcg(retrieved, relevant, 2), places=5)

    def test_evidence_refusal_latency_and_variation(self):
        self.assertEqual(2 / 3, evidence_coverage(["a", "b"], ["a", "b", "c"]))
        self.assertTrue(refusal_correctness(True, True))
        self.assertFalse(refusal_correctness(False, True))
        self.assertEqual({"count": 3, "p50Ms": 20.0, "p95Ms": 30.0},
                         aggregate_latency([10, 20, 30]))
        self.assertEqual({"count": 3, "mean": 2.0, "stdev": 1.0},
                         mean_and_variation([1, 2, 3]))


if __name__ == "__main__":
    unittest.main()
