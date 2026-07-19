package com.wish.rd.rag.project.budget;

import com.wish.rd.rag.project.budget.model.RdProjectTokenBudget;

import java.util.Objects;
import java.util.function.Consumer;

/** Project-level default token-budget use case. Zero means no hard token limit. */
public final class RdProjectTokenBudgetService {

    private final RdProjectTokenBudgetStore store;
    private final Consumer<String> projectValidator;

    public RdProjectTokenBudgetService(RdProjectTokenBudgetStore store) {
        this(store, ignored -> { });
    }

    public RdProjectTokenBudgetService(RdProjectTokenBudgetStore store, Consumer<String> projectValidator) {
        this.store = Objects.requireNonNull(store, "token budget store must not be null");
        this.projectValidator = projectValidator == null ? ignored -> { } : projectValidator;
    }

    public RdProjectTokenBudget get(String projectId) {
        String safeProjectId = requireProjectId(projectId);
        projectValidator.accept(safeProjectId);
        return store.findByProjectId(safeProjectId)
                .orElseGet(() -> new RdProjectTokenBudget(safeProjectId, 0L, 0L, 0L));
    }

    public RdProjectTokenBudget update(String projectId, long defaultTokenBudget) {
        String safeProjectId = requireProjectId(projectId);
        if (defaultTokenBudget < 0L) {
            throw new IllegalArgumentException("defaultTokenBudget must not be negative");
        }
        RdProjectTokenBudget existing = get(safeProjectId);
        long now = System.currentTimeMillis();
        return store.save(new RdProjectTokenBudget(
                safeProjectId,
                defaultTokenBudget,
                existing.createTimeEpochMillis() > 0L ? existing.createTimeEpochMillis() : now,
                now
        ));
    }

    private static String requireProjectId(String projectId) {
        String normalized = projectId == null ? "" : projectId.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        return normalized;
    }
}
