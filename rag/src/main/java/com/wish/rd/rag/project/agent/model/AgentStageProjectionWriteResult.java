package com.wish.rd.rag.project.agent.model;

public record AgentStageProjectionWriteResult(
        AgentStageProjectionWriteStatus status,
        AgentStageStateProjection projection,
        String reason
) {
    public AgentStageProjectionWriteResult {
        if (status == null) throw new IllegalArgumentException("status must not be null");
        reason = reason == null ? "" : reason;
    }
}
