package com.wish.rd.bootstrap.persistence.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Row for {@code rd_project_alert_configs}. */
public class RdProjectAlertConfigRow {
    public Long projectId;
    public Boolean enabled;
    public String recipientsJson;
    public String eventTypesJson;
    public BigDecimal budgetThresholdCny;
    public Integer failureThreshold;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
