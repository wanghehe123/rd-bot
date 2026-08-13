package com.wish.rd.rag.knowledge.model;

import java.util.Objects;

public record KnowledgeBase(
        String id,
        String name,
        String description,
        boolean enabled,
        long createdAtEpochMillis,
        KnowledgeBaseLifecycle lifecycleStatus,
        long deletedAtEpochMillis,
        long purgeAfterEpochMillis,
        long syncVersion,
        long rowVersion
) {

    public KnowledgeBase {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        description = description == null ? "" : description;
        lifecycleStatus = lifecycleStatus == null ? KnowledgeBaseLifecycle.ACTIVE : lifecycleStatus;
        deletedAtEpochMillis = Math.max(0L, deletedAtEpochMillis);
        purgeAfterEpochMillis = Math.max(0L, purgeAfterEpochMillis);
        syncVersion = syncVersion <= 0L ? 1L : syncVersion;
        rowVersion = Math.max(0L, rowVersion);
    }

    public KnowledgeBase(
            String id,
            String name,
            String description,
            boolean enabled,
            long createdAtEpochMillis
    ) {
        this(
                id,
                name,
                description,
                enabled,
                createdAtEpochMillis,
                KnowledgeBaseLifecycle.ACTIVE,
                0L,
                0L,
                1L,
                0L
        );
    }

    public boolean visible() {
        return lifecycleStatus == KnowledgeBaseLifecycle.ACTIVE && deletedAtEpochMillis == 0L;
    }

    public KnowledgeBase withEnabled(boolean newEnabled) {
        return new KnowledgeBase(
                id,
                name,
                description,
                newEnabled,
                createdAtEpochMillis,
                lifecycleStatus,
                deletedAtEpochMillis,
                purgeAfterEpochMillis,
                syncVersion,
                rowVersion
        );
    }

    public KnowledgeBase withName(String newName) {
        String safeName = newName == null || newName.isBlank() ? name : newName.strip();
        return new KnowledgeBase(
                id,
                safeName,
                description,
                enabled,
                createdAtEpochMillis,
                lifecycleStatus,
                deletedAtEpochMillis,
                purgeAfterEpochMillis,
                syncVersion,
                rowVersion
        );
    }

    public KnowledgeBase withDeleting(long deletedAt, long purgeAfter) {
        return new KnowledgeBase(
                id,
                name,
                description,
                enabled,
                createdAtEpochMillis,
                KnowledgeBaseLifecycle.DELETING,
                deletedAt,
                purgeAfter,
                syncVersion + 1L,
                rowVersion + 1L
        );
    }
}
