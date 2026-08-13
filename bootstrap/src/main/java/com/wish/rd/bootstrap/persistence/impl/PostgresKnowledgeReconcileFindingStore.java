package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeReconcileFindingRow;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeReconcileFindingMapper;
import com.wish.rd.rag.knowledge.projection.KnowledgeReconcileFindingStore;
import com.wish.rd.rag.knowledge.projection.model.ReconcileFinding;
import com.wish.rd.rag.knowledge.projection.model.ReconcileFindingStatus;
import com.wish.rd.rag.knowledge.projection.model.ReconcileFindingType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PostgreSQL 对账发现账本。身份匹配含 {@code document_id IS NULL}，
 * 因为 UNIQUE 约束吸收不了孤儿行的空文档 ID。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresKnowledgeReconcileFindingStore implements KnowledgeReconcileFindingStore {

    private final KnowledgeReconcileFindingMapper mapper;

    public PostgresKnowledgeReconcileFindingStore(KnowledgeReconcileFindingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ReconcileFinding upsert(ReconcileFinding finding) {
        Long documentId = PostgresPersistenceSupport.parseOptionalId(finding.documentId());
        KnowledgeReconcileFindingRow existing = mapper.findByIdentity(
                finding.provider(),
                PostgresPersistenceSupport.parseId(finding.knowledgeBaseId()),
                finding.findingType().name(),
                finding.remoteUri(),
                documentId
        );
        if (existing != null) {
            KnowledgeReconcileFindingRow updated = mapper.updateLastSeen(
                    existing.id,
                    finding.detail(),
                    finding.status().name(),
                    PostgresPersistenceSupport.toDateTime(finding.lastSeenAtEpochMillis())
            );
            return toFinding(updated == null ? existing : updated);
        }
        mapper.insertFinding(toRow(finding));
        return finding;
    }

    @Override
    public List<ReconcileFinding> listByKnowledgeBase(String provider, String knowledgeBaseId) {
        String normalized = provider == null || provider.isBlank() ? "OPENVIKING" : provider.strip().toUpperCase();
        return mapper.listByKnowledgeBase(
                        normalized, PostgresPersistenceSupport.parseId(knowledgeBaseId))
                .stream()
                .map(this::toFinding)
                .toList();
    }

    private static KnowledgeReconcileFindingRow toRow(ReconcileFinding finding) {
        KnowledgeReconcileFindingRow row = new KnowledgeReconcileFindingRow();
        row.id = PostgresPersistenceSupport.parseId(finding.id());
        row.provider = finding.provider();
        row.knowledgeBaseId = PostgresPersistenceSupport.parseId(finding.knowledgeBaseId());
        row.findingType = finding.findingType().name();
        row.remoteUri = finding.remoteUri();
        row.documentId = PostgresPersistenceSupport.parseOptionalId(finding.documentId());
        row.detail = finding.detail();
        row.status = finding.status().name();
        row.firstSeenAt = PostgresPersistenceSupport.toDateTime(finding.firstSeenAtEpochMillis());
        row.lastSeenAt = PostgresPersistenceSupport.toDateTime(finding.lastSeenAtEpochMillis());
        return row;
    }

    private ReconcileFinding toFinding(KnowledgeReconcileFindingRow row) {
        return new ReconcileFinding(
                PostgresPersistenceSupport.idString(row.id),
                row.provider,
                PostgresPersistenceSupport.idString(row.knowledgeBaseId),
                ReconcileFindingType.valueOf(row.findingType),
                row.remoteUri,
                PostgresPersistenceSupport.idString(row.documentId),
                row.detail,
                ReconcileFindingStatus.valueOf(row.status),
                PostgresPersistenceSupport.toEpochMillis(row.firstSeenAt),
                PostgresPersistenceSupport.toEpochMillis(row.lastSeenAt)
        );
    }
}
