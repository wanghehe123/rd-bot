package com.wish.rd.rag.knowledge;

import java.util.Objects;

public record KnowledgeBase(
        String id,
        String name,
        String description,
        boolean enabled,
        long createdAtEpochMillis
) {

    public KnowledgeBase {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        description = description == null ? "" : description;
    }

    public KnowledgeBase withEnabled(boolean newEnabled) {
        return new KnowledgeBase(id, name, description, newEnabled, createdAtEpochMillis);
    }

    public KnowledgeBase withName(String newName) {
        String safeName = newName == null || newName.isBlank() ? name : newName.strip();
        return new KnowledgeBase(id, safeName, description, enabled, createdAtEpochMillis);
    }
}
