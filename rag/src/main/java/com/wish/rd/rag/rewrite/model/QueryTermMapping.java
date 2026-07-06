package com.wish.rd.rag.rewrite.model;

import java.util.Objects;

public record QueryTermMapping(
        String sourceTerm,
        String targetTerm,
        int priority,
        boolean enabled
) {

    public QueryTermMapping {
        Objects.requireNonNull(sourceTerm, "sourceTerm must not be null");
        Objects.requireNonNull(targetTerm, "targetTerm must not be null");
    }
}
