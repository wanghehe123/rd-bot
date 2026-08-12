#!/usr/bin/env python3
"""Dependency-free deterministic ranking and evaluation metrics."""

from math import log2, ceil
from statistics import mean, stdev


def _top(values, k):
    return list(values or [])[: max(0, int(k))]


def recall_at_k(retrieved, relevant, k):
    relevant_set = {item for item in (relevant or []) if item}
    if not relevant_set:
        return None
    return len(set(_top(retrieved, k)) & relevant_set) / len(relevant_set)


def reciprocal_rank(retrieved, relevant):
    relevant_set = {item for item in (relevant or []) if item}
    if not relevant_set:
        return None
    for index, item in enumerate(retrieved or [], start=1):
        if item in relevant_set:
            return 1.0 / index
    return 0.0


def ndcg(retrieved, relevant, k):
    relevant_set = {item for item in (relevant or []) if item}
    if not relevant_set:
        return None
    gains = [1.0 if item in relevant_set else 0.0 for item in _top(retrieved, k)]
    dcg = sum(gain / log2(index + 2) for index, gain in enumerate(gains))
    ideal = [1.0] * min(len(relevant_set), max(0, int(k)))
    idcg = sum(gain / log2(index + 2) for index, gain in enumerate(ideal))
    return 0.0 if idcg == 0.0 else dcg / idcg


def evidence_coverage(selected, required):
    required_set = {item for item in (required or []) if item}
    if not required_set:
        return None
    return len(set(item for item in (selected or []) if item) & required_set) / len(required_set)


def refusal_correctness(predicted_refusal, expected_refusal):
    return bool(predicted_refusal) == bool(expected_refusal)


def _nearest_rank(values, quantile):
    ordered = sorted(float(value) for value in values)
    if not ordered:
        return None
    index = max(1, min(len(ordered), ceil(float(quantile) * len(ordered)))) - 1
    return ordered[index]


def aggregate_latency(latencies_ms):
    values = [float(value) for value in (latencies_ms or [])]
    if not values:
        return {"count": 0, "p50Ms": None, "p95Ms": None}
    return {
        "count": len(values),
        "p50Ms": _nearest_rank(values, 0.50),
        "p95Ms": _nearest_rank(values, 0.95),
    }


def mean_and_variation(values):
    numbers = [float(value) for value in (values or [])]
    if not numbers:
        return {"count": 0, "mean": None, "stdev": None}
    return {
        "count": len(numbers),
        "mean": mean(numbers),
        "stdev": stdev(numbers) if len(numbers) > 1 else 0.0,
    }
