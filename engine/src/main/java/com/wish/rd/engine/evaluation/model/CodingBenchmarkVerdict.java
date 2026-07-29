package com.wish.rd.engine.evaluation.model;

/** Oracle-owned result classification for one coding benchmark trial. */
public enum CodingBenchmarkVerdict {
    PENDING,
    PASS,
    TEST_FAIL,
    BUILD_FAIL,
    NO_PATCH,
    TIMEOUT,
    REVIEW_BLOCKED,
    PROTOCOL_ERROR,
    DEPENDENCY_POLICY_VIOLATION,
    INFRA_ERROR
}
