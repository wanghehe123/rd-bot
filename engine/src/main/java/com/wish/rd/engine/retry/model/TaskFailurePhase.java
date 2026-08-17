package com.wish.rd.engine.retry.model;

/** Structured business checkpoint from which a failed task can resume. */
public enum TaskFailurePhase {
    MATERIAL,
    CONTEXT,
    PLAN,
    POLICY,
    RAG,
    AGENT_ROLE,
    HOST_VERIFY,
    DETERMINISTIC_REVIEW,
    AI_REVIEW,
    PR_PUBLICATION
}
