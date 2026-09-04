package com.wish.rd.bootstrap.persistence.entity;

import java.time.OffsetDateTime;

/** Completion binding of a task to a specific audited head. */
public class TaskCompletionBindingRow {
    public Long taskId;
    public String auditRunId;
    public Long stateVersion;
    public String stateHash;
    public OffsetDateTime boundAt;
}
