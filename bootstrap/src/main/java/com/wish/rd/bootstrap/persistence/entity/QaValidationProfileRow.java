package com.wish.rd.bootstrap.persistence.entity;

import java.time.OffsetDateTime;

/** Row for {@code rd_qa_validation_profiles}. */
public class QaValidationProfileRow {
    public String scopeType;
    public Long scopeId;
    public String mode;
    public String baseUrl;
    public String startCommand;
    public String healthPath;
    public String allowedHostsJson;
    public String regressionCommandsJson;
    public String buildCommandsJson;
    public String staticCommandsJson;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
