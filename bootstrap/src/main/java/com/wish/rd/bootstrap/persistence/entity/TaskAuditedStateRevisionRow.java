package com.wish.rd.bootstrap.persistence.entity;

import java.time.OffsetDateTime;

/** Immutable revision snapshot of audited task state. */
public class TaskAuditedStateRevisionRow {
    public Long taskId;
    public Long stateVersion;
    public String stateHash;
    public String stateJson;
    public String auditRunId;
    public Long commandId;
    public OffsetDateTime createdAt;
}
