package com.wish.rd.engine.requirement.review.model;

/** Append-only transition event for an AI review attempt. */
public record AiReviewEvent(
        String eventId,
        String runId,
        AiReviewRunStatus fromStatus,
        AiReviewRunStatus toStatus,
        String trigger,
        String message,
        String errorCategory,
        long occurredAtEpochMillis
) {
    public AiReviewEvent {
        eventId = requireText(eventId, "eventId");
        runId = requireText(runId, "runId");
        if (fromStatus == null || toStatus == null) {
            throw new IllegalArgumentException("event statuses must not be null");
        }
        trigger = trigger == null || trigger.isBlank() ? "SYSTEM" : trigger.strip();
        message = message == null ? "" : message.strip();
        errorCategory = errorCategory == null ? "" : errorCategory.strip();
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
