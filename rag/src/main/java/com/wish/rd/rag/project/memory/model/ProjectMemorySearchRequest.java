package com.wish.rd.rag.project.memory.model;

/** Bounded exact-project candidate query; production adapters must apply these predicates in SQL. */
public record ProjectMemorySearchRequest(String projectId, String role, String query, int candidateLimit,
                                         double minimumQuality, long nowEpochMillis) {
    public ProjectMemorySearchRequest {
        projectId = projectId == null ? "" : projectId.strip();
        role = role == null ? "" : role.strip();
        query = query == null ? "" : query.strip();
        if (!projectId.matches("[1-9][0-9]*")) throw new IllegalArgumentException("projectId must be numeric");
        candidateLimit = Math.max(0, candidateLimit);
        minimumQuality = Math.max(0d, Math.min(1d, minimumQuality));
    }
}
