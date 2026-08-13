package com.wish.rd.bootstrap.persistence.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeExternalIndexBindingRow;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeExternalIndexBindingMapper;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeObservedState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresKnowledgeExternalIndexBindingStore implements KnowledgeExternalIndexBindingStore {

    private final KnowledgeExternalIndexBindingMapper mapper;

    public PostgresKnowledgeExternalIndexBindingStore(KnowledgeExternalIndexBindingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public KnowledgeExternalIndexBinding save(KnowledgeExternalIndexBinding binding) {
        mapper.upsert(toRow(binding));
        return binding;
    }

    @Override
    public Optional<KnowledgeExternalIndexBinding> saveObservationIfVersionMatches(
            KnowledgeExternalIndexBinding binding,
            long expectedRowVersion
    ) {
        KnowledgeExternalIndexBindingRow row = mapper.updateObservationIfVersionMatches(
                binding.provider(),
                PostgresPersistenceSupport.parseId(binding.documentId()),
                expectedRowVersion,
                binding.observedState().name(),
                binding.observedVersion(),
                binding.observedChecksum(),
                binding.projectionStatus().name(),
                PostgresPersistenceSupport.parseOptionalId(binding.activeOperationId()),
                binding.remoteTaskId(),
                binding.semanticConfigFingerprint(),
                PostgresPersistenceSupport.nullableDateTime(binding.lastSubmittedAtEpochMillis()),
                PostgresPersistenceSupport.nullableDateTime(binding.lastVerifiedAtEpochMillis()),
                binding.lastErrorCode(),
                binding.lastErrorMessage(),
                PostgresPersistenceSupport.toDateTime(binding.updatedAtEpochMillis())
        );
        return Optional.ofNullable(row).map(this::toBinding);
    }

    @Override
    public Optional<KnowledgeExternalIndexBinding> findByProviderAndDocumentId(String provider, String documentId) {
        return mapper.selectList(new QueryWrapper<KnowledgeExternalIndexBindingRow>()
                        .eq("provider", provider)
                        .eq("document_id", PostgresPersistenceSupport.parseId(documentId)))
                .stream()
                .findFirst()
                .map(this::toBinding);
    }

    @Override
    public List<KnowledgeExternalIndexBinding> findByKnowledgeBase(
            String provider,
            String knowledgeBaseId,
            ExternalKnowledgeProjectionStatus projectionStatus,
            int offset,
            int limit
    ) {
        QueryWrapper<KnowledgeExternalIndexBindingRow> query = new QueryWrapper<KnowledgeExternalIndexBindingRow>()
                .eq("provider", normalizeProvider(provider))
                .eq("knowledge_base_id", PostgresPersistenceSupport.parseId(knowledgeBaseId));
        if (projectionStatus != null) {
            query.eq("projection_status", projectionStatus.name());
        }
        query.orderByDesc("updated_at")
                .orderByDesc("document_id")
                .last("LIMIT " + Math.max(0, limit) + " OFFSET " + Math.max(0, offset));
        return mapper.selectList(query).stream().map(this::toBinding).toList();
    }

    @Override
    public Map<ExternalKnowledgeProjectionStatus, Long> countByProjectionStatus(
            String provider,
            String knowledgeBaseId
    ) {
        EnumMap<ExternalKnowledgeProjectionStatus, Long> counts =
                new EnumMap<>(ExternalKnowledgeProjectionStatus.class);
        for (KnowledgeExternalIndexBindingRow row : mapper.selectList(new QueryWrapper<KnowledgeExternalIndexBindingRow>()
                .eq("provider", normalizeProvider(provider))
                .eq("knowledge_base_id", PostgresPersistenceSupport.parseId(knowledgeBaseId))
                .select("projection_status"))) {
            if (row.projectionStatus == null || row.projectionStatus.isBlank()) {
                continue;
            }
            counts.merge(ExternalKnowledgeProjectionStatus.valueOf(row.projectionStatus), 1L, Long::sum);
        }
        return Map.copyOf(counts);
    }

    @Override
    public List<KnowledgeExternalIndexBinding> listAll() {
        return mapper.selectList(null).stream().map(this::toBinding).toList();
    }

    @Override
    public void delete(String provider, String documentId) {
        mapper.delete(new QueryWrapper<KnowledgeExternalIndexBindingRow>()
                .eq("provider", provider)
                .eq("document_id", PostgresPersistenceSupport.parseId(documentId)));
    }

    private KnowledgeExternalIndexBindingRow toRow(KnowledgeExternalIndexBinding binding) {
        KnowledgeExternalIndexBindingRow row = new KnowledgeExternalIndexBindingRow();
        row.provider = binding.provider();
        row.documentId = PostgresPersistenceSupport.parseId(binding.documentId());
        row.knowledgeBaseId = PostgresPersistenceSupport.parseId(binding.knowledgeBaseId());
        row.remoteUri = binding.remoteUri();
        row.ownershipMarker = binding.ownershipMarker();
        row.desiredState = binding.desiredState().name();
        row.desiredVersion = binding.desiredVersion();
        row.desiredChecksum = binding.desiredChecksum();
        row.observedState = binding.observedState().name();
        row.observedVersion = binding.observedVersion();
        row.observedChecksum = binding.observedChecksum();
        row.projectionStatus = binding.projectionStatus().name();
        row.activeOperationId = PostgresPersistenceSupport.parseOptionalId(binding.activeOperationId());
        row.remoteTaskId = binding.remoteTaskId();
        row.semanticConfigFingerprint = binding.semanticConfigFingerprint();
        row.lastSubmittedAt = PostgresPersistenceSupport.nullableDateTime(binding.lastSubmittedAtEpochMillis());
        row.lastVerifiedAt = PostgresPersistenceSupport.nullableDateTime(binding.lastVerifiedAtEpochMillis());
        row.lastErrorCode = binding.lastErrorCode();
        row.lastErrorMessage = binding.lastErrorMessage();
        row.rowVersion = binding.rowVersion();
        row.createdAt = PostgresPersistenceSupport.toDateTime(binding.createdAtEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(binding.updatedAtEpochMillis());
        return row;
    }

    private KnowledgeExternalIndexBinding toBinding(KnowledgeExternalIndexBindingRow row) {
        return new KnowledgeExternalIndexBinding(
                row.provider,
                PostgresPersistenceSupport.idString(row.documentId),
                PostgresPersistenceSupport.idString(row.knowledgeBaseId),
                row.remoteUri,
                row.ownershipMarker,
                ExternalKnowledgeDesiredState.valueOf(row.desiredState),
                row.desiredVersion == null ? 1L : row.desiredVersion,
                row.desiredChecksum,
                ExternalKnowledgeObservedState.valueOf(row.observedState),
                row.observedVersion == null ? 0L : row.observedVersion,
                row.observedChecksum,
                ExternalKnowledgeProjectionStatus.valueOf(row.projectionStatus),
                PostgresPersistenceSupport.idString(row.activeOperationId),
                row.remoteTaskId,
                row.semanticConfigFingerprint,
                PostgresPersistenceSupport.toEpochMillis(row.lastSubmittedAt),
                PostgresPersistenceSupport.toEpochMillis(row.lastVerifiedAt),
                row.lastErrorCode,
                row.lastErrorMessage,
                row.rowVersion == null ? 0L : row.rowVersion,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }

    private static String normalizeProvider(String provider) {
        return provider == null || provider.isBlank() ? "OPENVIKING" : provider.strip().toUpperCase();
    }
}
