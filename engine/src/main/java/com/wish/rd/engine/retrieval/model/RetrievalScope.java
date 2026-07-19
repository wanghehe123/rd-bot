package com.wish.rd.engine.retrieval.model;

import java.util.List;

/** Immutable project boundary resolved before a requirement retrieval attempt starts. */
public record RetrievalScope(
        List<String> knowledgeBaseIds,
        String repositoryFingerprint,
        boolean projectScopeRequired,
        String missingReason
) {
    public RetrievalScope {
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : knowledgeBaseIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
        repositoryFingerprint = safe(repositoryFingerprint);
        missingReason = safe(missingReason);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
