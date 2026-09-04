package com.wish.rd.bootstrap.persistence.entity;

import java.time.OffsetDateTime;

/** Finalized stage evidence eligible for project-memory operation reconciliation. */
public class ProjectMemoryReconciliationEvidenceRow {
    public Long commandId;
    public Long taskId;
    public String projectId;
    public String outcomePlanDigest;
    public OffsetDateTime finalizedAt;
}
