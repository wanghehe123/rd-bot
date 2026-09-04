package com.wish.rd.bootstrap.persistence.entity;

import java.time.OffsetDateTime;

/** PostgreSQL row for per-project capture/read policy. */
public class ProjectMemoryModeRow {
    public long projectId;
    public String captureMode;
    public String readMode;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
