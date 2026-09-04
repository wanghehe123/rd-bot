package com.wish.rd.rag.project.memory;

import com.wish.rd.rag.project.memory.model.ProjectMemorySearchHit;
import java.util.List;

/** Candidate results plus the examined-row audit count. */
public record ProjectMemorySearchResult(List<ProjectMemorySearchHit> hits, int examinedRowCount) {
    public ProjectMemorySearchResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
        examinedRowCount = Math.max(0, examinedRowCount);
    }
}
