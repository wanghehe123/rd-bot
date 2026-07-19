package com.wish.rd.rag.retrieval.run.model;

/** Result of deterministic and optional model-backed evidence quality checks. */
public enum EvidenceQualityDecision {
    SUFFICIENT,
    RETRY_WITH_REFINED_PLAN,
    NEED_INPUT,
    DEGRADED_ACCEPTABLE,
    UNSAFE_OR_OUT_OF_SCOPE
}
