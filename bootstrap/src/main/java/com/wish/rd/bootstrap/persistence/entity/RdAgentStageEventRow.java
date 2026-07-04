package com.wish.rd.bootstrap.persistence.entity;

import java.time.OffsetDateTime;

/**
 * Agent 阶段状态事件持久化行。
 *
 * <p>对应 {@code rd_agent_stage_events}，用于审计和恢复分析。
 */
public class RdAgentStageEventRow {

    public Long id;
    public Long stageRunId;
    public Long taskId;
    public String role;
    public String status;
    public String message;
    public String metadataJson;
    public OffsetDateTime enteredAt;
    public Long durationMs;
    public String trigger;
}
