package com.wish.rd.engine.retry.model;

/** Typed durable target of one checkpoint-bound retry attempt binding. */
public enum TaskRetryAttemptKind {
    AGENT_STAGE,
    RETRIEVAL,
    AI_REVIEW
}
