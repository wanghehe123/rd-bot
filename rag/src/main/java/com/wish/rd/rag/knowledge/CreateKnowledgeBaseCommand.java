package com.wish.rd.rag.knowledge;

import java.util.Objects;

public record CreateKnowledgeBaseCommand(
        String name,
        String description
) {

    public CreateKnowledgeBaseCommand {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        description = description == null ? "" : description;
    }
}
