package com.wish.rd.bootstrap.persistence.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeDocumentRevisionRow;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeDocumentRevisionMapper;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentRevision;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentRevisionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL 文档 revision Store。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresKnowledgeDocumentRevisionStore implements KnowledgeDocumentRevisionStore {

    private final KnowledgeDocumentRevisionMapper mapper;

    public PostgresKnowledgeDocumentRevisionStore(KnowledgeDocumentRevisionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public KnowledgeDocumentRevision save(KnowledgeDocumentRevision revision) {
        mapper.upsert(toRow(revision));
        return findByDocumentIdAndChecksum(revision.documentId(), revision.checksum()).orElse(revision);
    }

    @Override
    public Optional<KnowledgeDocumentRevision> findByDocumentIdAndChecksum(String documentId, String checksum) {
        return mapper.selectList(new QueryWrapper<KnowledgeDocumentRevisionRow>()
                        .eq("document_id", PostgresPersistenceSupport.parseId(documentId))
                        .eq("checksum", checksum))
                .stream()
                .findFirst()
                .map(this::toRevision);
    }

    @Override
    public List<KnowledgeDocumentRevision> listByDocumentId(String documentId) {
        return mapper.selectList(new QueryWrapper<KnowledgeDocumentRevisionRow>()
                        .eq("document_id", PostgresPersistenceSupport.parseId(documentId)))
                .stream()
                .sorted(Comparator.comparing(row -> row.syncVersion))
                .map(this::toRevision)
                .toList();
    }

    private KnowledgeDocumentRevisionRow toRow(KnowledgeDocumentRevision revision) {
        KnowledgeDocumentRevisionRow row = new KnowledgeDocumentRevisionRow();
        row.id = PostgresPersistenceSupport.parseId(revision.id());
        row.documentId = PostgresPersistenceSupport.parseId(revision.documentId());
        row.syncVersion = revision.syncVersion();
        row.sourceRevisionId = revision.sourceRevisionId();
        row.checksum = revision.checksum();
        row.canonicalMimeType = revision.canonicalMimeType();
        row.canonicalContent = revision.canonicalContent();
        row.parserName = revision.parserName();
        row.parserVersion = revision.parserVersion();
        row.createdAt = PostgresPersistenceSupport.toDateTime(revision.createdAtEpochMillis());
        return row;
    }

    private KnowledgeDocumentRevision toRevision(KnowledgeDocumentRevisionRow row) {
        return new KnowledgeDocumentRevision(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.documentId),
                row.syncVersion == null ? 1L : row.syncVersion,
                row.sourceRevisionId,
                row.checksum,
                row.canonicalMimeType,
                row.canonicalContent,
                row.parserName,
                row.parserVersion,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt)
        );
    }
}
