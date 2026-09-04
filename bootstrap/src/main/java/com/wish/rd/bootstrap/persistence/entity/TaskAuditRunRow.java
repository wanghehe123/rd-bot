package com.wish.rd.bootstrap.persistence.entity;

import java.time.OffsetDateTime;

/** One Host deterministic audit run. */
public class TaskAuditRunRow {
    public String auditRunId;
    public Long taskId;
    public String subjectStageRunId;
    public String subjectRole;
    public Long commandId;
    public String completion;
    public String integrity;
    public String contractAudit;
    public String reportJson;
    public String reportHash;
    public OffsetDateTime createdAt;
}
