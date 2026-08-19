package com.wish.rd.rag.project.agent.model;

public enum AgentStageProjectionWriteStatus {
    APPLIED,
    IDEMPOTENT,
    REJECTED_STALE,
    REJECTED_CONFLICT,
    REJECTED_IDENTITY,
    REJECTED_FINALIZED
}
