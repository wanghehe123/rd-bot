package com.wish.rd.bootstrap.persistence.entity;

import java.time.OffsetDateTime;

/** Head row for one task's audited state. */
public class TaskAuditedStateHeadRow {
    public Long taskId;
    public Long stateVersion;
    public String stateHash;
    public String lastAuditRunId;
    public OffsetDateTime updatedAt;
}
