package com.wish.rd.rag.project.alert.model;

/** Auditable delivery result for one project alert recipient. */
public record RdAlertDelivery(
        String deliveryId,
        String taskId,
        String projectId,
        RdProjectAlertEventType alertType,
        RdAlertRecipientType recipientType,
        String recipientId,
        RdAlertDeliveryStatus status,
        String providerMessageId,
        String failureCode,
        String failureMessage,
        String idempotencyKey,
        long createTimeEpochMillis
) {
    public RdAlertDelivery {
        deliveryId = requireText(deliveryId, "deliveryId");
        taskId = requireText(taskId, "taskId");
        projectId = requireText(projectId, "projectId");
        alertType = java.util.Objects.requireNonNull(alertType, "alertType must not be null");
        recipientType = java.util.Objects.requireNonNull(recipientType, "recipientType must not be null");
        recipientId = requireText(recipientId, "recipientId");
        status = java.util.Objects.requireNonNull(status, "status must not be null");
        providerMessageId = normalize(providerMessageId);
        failureCode = normalize(failureCode);
        failureMessage = normalize(failureMessage);
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        if (createTimeEpochMillis < 0) {
            throw new IllegalArgumentException("createTimeEpochMillis must not be negative");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
