package com.wish.rd.bootstrap.persistence.entity;

import java.time.OffsetDateTime;

/** PostgreSQL row for one durable PI QA remediation round. */
public class AgentRemediationRoundRow {
    public Long id;
    public Long taskId;
    public String kind;
    public Integer remediationNo;
    public Long sourceStageRunId;
    public Long sourceCommandId;
    public String sourceResultHash;
    public Long sourceTaskVersion;
    public Long sourceFencingToken;
    public Long targetCodingStageRunId;
    public Long targetQaStageRunId;
    public Long firstCommandId;
    public String codingProfileSnapshotId;
    public String qaProfileSnapshotId;
    public String requestJson;
    public String requestHash;
    public String status;
    public Long rowVersion;
    public String leaseOwner;
    public OffsetDateTime leaseUntil;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    public OffsetDateTime claimedAt;
    public OffsetDateTime dispatchedAt;
    public OffsetDateTime completedAt;
}
