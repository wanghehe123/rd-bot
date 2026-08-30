package com.wish.rd.rag.project.memory;

import com.wish.rd.rag.project.memory.model.ProjectMemorySearchRequest;

/** PostgreSQL-backed retrieval boundary; implementations must filter before ranking. */
public interface ProjectMemorySearchPort {
    ProjectMemorySearchResult search(ProjectMemorySearchRequest request);
}
