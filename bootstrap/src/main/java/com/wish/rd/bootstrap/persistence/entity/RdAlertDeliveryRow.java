package com.wish.rd.bootstrap.persistence.entity;

import java.time.OffsetDateTime;

/** Row for {@code rd_alert_deliveries}. */
public class RdAlertDeliveryRow {
    public Long id;
    public Long taskId;
    public Long projectId;
    public String alertType;
    public String recipientType;
    public String recipientId;
    public String status;
    public String providerMessageId;
    public String failureCode;
    public String failureMessage;
    public String idempotencyKey;
    public OffsetDateTime createdAt;
}
