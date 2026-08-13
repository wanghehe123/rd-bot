package com.wish.rd.rag.knowledge.projection.impl;

import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存投影绑定 Store。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
public final class InMemoryKnowledgeExternalIndexBindingStore implements KnowledgeExternalIndexBindingStore {

    private final LinkedHashMap<String, KnowledgeExternalIndexBinding> bindings = new LinkedHashMap<>();

    @Override
    public synchronized KnowledgeExternalIndexBinding save(KnowledgeExternalIndexBinding binding) {
        bindings.put(key(binding.provider(), binding.documentId()), binding);
        return binding;
    }

    @Override
    public synchronized Optional<KnowledgeExternalIndexBinding> saveObservationIfVersionMatches(
            KnowledgeExternalIndexBinding binding,
            long expectedRowVersion
    ) {
        String key = key(binding.provider(), binding.documentId());
        KnowledgeExternalIndexBinding current = bindings.get(key);
        if (current == null || current.rowVersion() != expectedRowVersion) {
            return Optional.empty();
        }
        KnowledgeExternalIndexBinding merged = new KnowledgeExternalIndexBinding(
                current.provider(),
                current.documentId(),
                current.knowledgeBaseId(),
                current.remoteUri(),
                current.ownershipMarker(),
                current.desiredState(),
                current.desiredVersion(),
                current.desiredChecksum(),
                binding.observedState(),
                binding.observedVersion(),
                binding.observedChecksum(),
                binding.projectionStatus(),
                binding.activeOperationId(),
                binding.remoteTaskId(),
                binding.semanticConfigFingerprint(),
                binding.lastSubmittedAtEpochMillis(),
                binding.lastVerifiedAtEpochMillis(),
                binding.lastErrorCode(),
                binding.lastErrorMessage(),
                current.rowVersion() + 1L,
                current.createdAtEpochMillis(),
                binding.updatedAtEpochMillis()
        );
        bindings.put(key, merged);
        return Optional.of(merged);
    }

    @Override
    public synchronized Optional<KnowledgeExternalIndexBinding> findByProviderAndDocumentId(
            String provider,
            String documentId
    ) {
        return Optional.ofNullable(bindings.get(key(provider, documentId)));
    }

    @Override
    public synchronized List<KnowledgeExternalIndexBinding> findByKnowledgeBase(
            String provider,
            String knowledgeBaseId,
            ExternalKnowledgeProjectionStatus projectionStatus,
            int offset,
            int limit
    ) {
        String normalized = provider == null || provider.isBlank() ? "OPENVIKING" : provider.strip().toUpperCase();
        return bindings.values().stream()
                .filter(binding -> binding.provider().equals(normalized)
                        && binding.knowledgeBaseId().equals(knowledgeBaseId)
                        && (projectionStatus == null || binding.projectionStatus() == projectionStatus))
                .sorted(ADMIN_ORDER)
                .skip(Math.max(0, offset))
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public synchronized Map<ExternalKnowledgeProjectionStatus, Long> countByProjectionStatus(
            String provider,
            String knowledgeBaseId
    ) {
        String normalized = provider == null || provider.isBlank() ? "OPENVIKING" : provider.strip().toUpperCase();
        EnumMap<ExternalKnowledgeProjectionStatus, Long> counts =
                new EnumMap<>(ExternalKnowledgeProjectionStatus.class);
        for (KnowledgeExternalIndexBinding binding : bindings.values()) {
            if (binding.provider().equals(normalized) && binding.knowledgeBaseId().equals(knowledgeBaseId)) {
                counts.merge(binding.projectionStatus(), 1L, Long::sum);
            }
        }
        return Map.copyOf(counts);
    }

    @Override
    public synchronized List<KnowledgeExternalIndexBinding> listAll() {
        return List.copyOf(bindings.values());
    }

    @Override
    public synchronized void delete(String provider, String documentId) {
        bindings.remove(key(provider, documentId));
    }

    private static String key(String provider, String documentId) {
        return provider + "\0" + documentId;
    }

    private static final Comparator<KnowledgeExternalIndexBinding> ADMIN_ORDER =
            Comparator.comparingLong(KnowledgeExternalIndexBinding::updatedAtEpochMillis)
                    .reversed()
                    .thenComparing(KnowledgeExternalIndexBinding::documentId, Comparator.reverseOrder());
}
