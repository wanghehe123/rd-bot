package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.ProjectMemorySearchRow;
import com.wish.rd.bootstrap.persistence.mapper.ProjectMemoryMapper;
import com.wish.rd.rag.project.memory.ProjectMemorySearchPort;
import com.wish.rd.rag.project.memory.ProjectMemorySearchResult;
import com.wish.rd.rag.project.memory.model.ProjectMemorySearchHit;
import com.wish.rd.rag.project.memory.model.ProjectMemorySearchRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** PostgreSQL-backed bounded lexical search for ACTIVE project-memory heads. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresProjectMemorySearchPort implements ProjectMemorySearchPort {
    private final ProjectMemoryMapper memoryMapper;

    public PostgresProjectMemorySearchPort(ProjectMemoryMapper memoryMapper) {
        this.memoryMapper = memoryMapper;
    }

    @Override
    public ProjectMemorySearchResult search(ProjectMemorySearchRequest request) {
        long projectId = PostgresPersistenceSupport.parseId(request.projectId());
        OffsetDateTime now = OffsetDateTime.ofInstant(
                Instant.ofEpochMilli(request.nowEpochMillis()), ZoneOffset.UTC);
        int examined = memoryMapper.countExaminedActiveHeads(
                projectId,
                request.role(),
                now,
                request.minimumQuality(),
                request.minimumQuality());
        if (request.candidateLimit() == 0 || request.query().isBlank()) {
            return new ProjectMemorySearchResult(List.of(), examined);
        }
        List<ProjectMemorySearchRow> rows = memoryMapper.searchActiveHeadsLexical(
                projectId,
                request.role(),
                now,
                request.minimumQuality(),
                request.minimumQuality(),
                request.query().toLowerCase(),
                request.candidateLimit());
        return new ProjectMemorySearchResult(rows.stream().map(this::toHit).toList(), examined);
    }

    private ProjectMemorySearchHit toHit(ProjectMemorySearchRow row) {
        double quality = row.evidenceQuality == null ? 0d : row.evidenceQuality;
        return new ProjectMemorySearchHit(
                row.memoryId.toString(),
                row.revisionVersion == null ? 0L : row.revisionVersion,
                row.projectId.toString(),
                row.scopeRole == null ? "" : row.scopeRole,
                true,
                true,
                true,
                quality,
                row.summary == null ? "" : row.summary,
                row.contentHash == null ? "" : row.contentHash);
    }
}
