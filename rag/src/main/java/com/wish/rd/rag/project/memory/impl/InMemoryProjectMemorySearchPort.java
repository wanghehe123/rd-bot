package com.wish.rd.rag.project.memory.impl;

import com.wish.rd.rag.project.memory.ProjectMemorySearchPort;
import com.wish.rd.rag.project.memory.ProjectMemorySearchResult;
import com.wish.rd.rag.project.memory.model.ProjectMemorySearchHit;
import com.wish.rd.rag.project.memory.model.ProjectMemorySearchRequest;

import java.util.Comparator;
import java.util.List;

/** In-memory contract implementation. Production implementations must enforce these conditions in SQL. */
public final class InMemoryProjectMemorySearchPort implements ProjectMemorySearchPort {
    private final List<ProjectMemorySearchHit> corpus;

    public InMemoryProjectMemorySearchPort(List<ProjectMemorySearchHit> corpus) {
        this.corpus = corpus == null ? List.of() : List.copyOf(corpus);
    }

    @Override
    public ProjectMemorySearchResult search(ProjectMemorySearchRequest request) {
        return new ProjectMemorySearchResult(corpus.stream()
                .filter(hit -> hit.projectId().equals(request.projectId()))
                .filter(hit -> hit.role().isEmpty() || hit.role().equals(request.role()))
                .filter(hit -> hit.activeHead() && hit.valid() && hit.redacted() && hit.quality() >= request.minimumQuality())
                .filter(hit -> hit.summary().toLowerCase(java.util.Locale.ROOT)
                        .contains(request.query().toLowerCase(java.util.Locale.ROOT)))
                .sorted(Comparator.comparingDouble(ProjectMemorySearchHit::quality).reversed()
                        .thenComparing(ProjectMemorySearchHit::memoryId))
                .limit(request.candidateLimit()).toList(), corpus.size());
    }
}
