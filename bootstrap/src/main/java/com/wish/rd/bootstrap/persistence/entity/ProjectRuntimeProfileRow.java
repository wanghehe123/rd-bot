package com.wish.rd.bootstrap.persistence.entity;

import java.time.OffsetDateTime;

/** Row for {@code rd_project_runtime_profiles}. */
public class ProjectRuntimeProfileRow {
    public Long projectId;
    public String role;
    public String agentType;
    public String image;
    public String dockerfileUri;
    public String dockerfileSha256;
    public String dockerfileName;
    public String validationStatus;
    public String validationSummary;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
