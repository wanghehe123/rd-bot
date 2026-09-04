package com.wish.rd.rag.project.memory;

import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemorySearchPort;
import com.wish.rd.rag.project.memory.model.ProjectMemorySearchHit;
import com.wish.rd.rag.project.memory.model.ProjectMemorySearchRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectMemorySearchPortTest {
    @Test
    void filtersBeforeRankingByExactProjectRoleActiveValidityAndQualityWithBoundedAudit() {
        InMemoryProjectMemorySearchPort port = new InMemoryProjectMemorySearchPort(List.of(
                hit("1", "101", "CODING_AGENT", true, true, 0.9, "build compile"),
                hit("2", "102", "CODING_AGENT", true, true, 0.9, "build compile"),
                hit("3", "101", "QA_AGENT", true, true, 0.9, "build compile"),
                hit("4", "101", "CODING_AGENT", false, true, 0.9, "build compile"),
                hit("5", "101", "CODING_AGENT", true, false, 0.9, "build compile"),
                hit("6", "101", "CODING_AGENT", true, true, 0.1, "build compile")
        ));

        ProjectMemorySearchResult result = port.search(new ProjectMemorySearchRequest(
                "101", "CODING_AGENT", "build", 2, 0.5, 1L
        ));

        assertEquals(List.of("1"), result.hits().stream().map(ProjectMemorySearchHit::memoryId).toList());
        assertEquals(6, result.examinedRowCount());
    }

    private static ProjectMemorySearchHit hit(String id, String project, String role, boolean active, boolean valid,
                                              double quality, String summary) {
        return new ProjectMemorySearchHit(id, 1L, project, role, active, valid, true, quality, summary, "h" + id);
    }
}
