package com.wish.rd.rag.retrieval.impl;

import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.project.memory.ProjectMemorySearchPort;
import com.wish.rd.rag.project.memory.model.ProjectMemorySearchRequest;

import java.util.List;

/** Independent retrieval channel for project memory; it never creates legacy workflow experiences. */
public final class ProjectMemoryRetrievalChannel {
    private final ProjectMemorySearchPort searchPort;

    public ProjectMemoryRetrievalChannel(ProjectMemorySearchPort searchPort) {
        this.searchPort = searchPort;
    }

    public List<RoleContextEvidence> retrieve(String projectId, String role, String query, int limit,
                                              double minimumQuality, long nowEpochMillis) {
        return searchPort.search(new ProjectMemorySearchRequest(projectId, role, query, limit, minimumQuality, nowEpochMillis))
                .hits().stream().map(hit -> new RoleContextEvidence(
                        "project-memory:" + hit.memoryId() + ":" + hit.revisionVersion(),
                        "PROJECT_MEMORY",
                        "rd-memory://projects/" + hit.projectId() + "/memories/" + hit.memoryId() + "/revisions/" + hit.revisionVersion(),
                        "Project memory " + hit.memoryId(), hit.contentHash(), hit.summary(), nowEpochMillis,
                        "UNTRUSTED_PROJECT_MEMORY lexical relevance", hit.quality(), "", false
                )).toList();
    }
}
