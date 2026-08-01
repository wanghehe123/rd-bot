package com.wish.rd.rag.project.agent.model;

import java.util.Objects;

/** Harness-authoritative TODO item. */
public record AgentTodoItem(
        String todoId,
        String title,
        AgentTodoStatus status,
        String evidenceArtifactId,
        String evidenceToolCallId,
        String blockerReason
) {

    public static final int MAX_TITLE_LENGTH = 256;

    public AgentTodoItem {
        todoId = requireText(todoId, "todoId");
        title = requireBoundedTitle(title);
        Objects.requireNonNull(status, "status must not be null");
        evidenceArtifactId = normalizeOptional(evidenceArtifactId);
        evidenceToolCallId = normalizeOptional(evidenceToolCallId);
        blockerReason = normalizeOptional(blockerReason);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String requireBoundedTitle(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (normalized.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("title exceeds max length " + MAX_TITLE_LENGTH);
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isBlank() ? null : normalized;
    }
}
