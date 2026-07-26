package com.wish.rd.bootstrap.persistence.entity;

import java.time.OffsetDateTime;

/** Row for the immutable stage execution profile snapshot. */
public class AgentExecutionProfileSnapshotRow {
    public String snapshotId;
    public Long stageRunId;
    public Long taskId;
    public String role;
    public Integer attemptNo;
    public String runtimeType;
    public String snapshotJson;
    public String snapshotHash;
    public OffsetDateTime resolvedAt;
}
