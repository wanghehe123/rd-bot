package com.wish.rd.rag.project.budget.model;

/** Immutable project default token budget. Zero denotes an unlimited task budget. */
public record RdProjectTokenBudget(
        String projectId,
        long defaultTokenBudget,
        long createTimeEpochMillis,
        long updateTimeEpochMillis
) {
    public RdProjectTokenBudget {
        projectId = projectId == null ? "" : projectId.strip();
        if (projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        if (defaultTokenBudget < 0L) {
            throw new IllegalArgumentException("defaultTokenBudget must not be negative");
        }
        if (createTimeEpochMillis < 0L || updateTimeEpochMillis < 0L) {
            throw new IllegalArgumentException("timestamps must not be negative");
        }
    }
}
