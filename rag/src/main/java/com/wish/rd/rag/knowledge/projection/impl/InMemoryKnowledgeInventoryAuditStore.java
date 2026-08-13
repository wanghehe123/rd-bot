package com.wish.rd.rag.knowledge.projection.impl;

import com.wish.rd.rag.knowledge.model.KnowledgeBase;
import com.wish.rd.rag.knowledge.model.KnowledgeBaseLifecycle;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.KnowledgeInventoryAuditStore;
import com.wish.rd.rag.knowledge.projection.model.DuplicateIdentityGroup;
import com.wish.rd.rag.knowledge.projection.model.DuplicateIdentityMember;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.InventoryCategory;
import com.wish.rd.rag.knowledge.projection.model.InventoryCategoryCounts;
import com.wish.rd.rag.knowledge.projection.model.InventoryDriftEntry;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.store.KnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 内存存量审计：与 Postgres 实现同一套 D5 判定。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
public final class InMemoryKnowledgeInventoryAuditStore implements KnowledgeInventoryAuditStore {

    private static final String PROVIDER = KnowledgeExternalIndexBinding.OPENVIKING;

    private final KnowledgeDocumentStore documentStore;
    private final KnowledgeBaseStore baseStore;
    private final KnowledgeExternalIndexBindingStore bindingStore;
    private final KnowledgeExternalIndexOutboxStore outboxStore;

    public InMemoryKnowledgeInventoryAuditStore(
            KnowledgeDocumentStore documentStore,
            KnowledgeBaseStore baseStore,
            KnowledgeExternalIndexBindingStore bindingStore,
            KnowledgeExternalIndexOutboxStore outboxStore
    ) {
        this.documentStore = Objects.requireNonNull(documentStore, "documentStore must not be null");
        this.baseStore = Objects.requireNonNull(baseStore, "baseStore must not be null");
        this.bindingStore = Objects.requireNonNull(bindingStore, "bindingStore must not be null");
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore must not be null");
    }

    @Override
    public InventoryCategoryCounts countByCategory(String knowledgeBaseId) {
        EnumMap<InventoryCategory, Long> counts = new EnumMap<>(InventoryCategory.class);
        for (InventoryCategory category : InventoryCategory.values()) {
            counts.put(category, 0L);
        }
        Set<String> duplicateIdentities = duplicateIdentities(knowledgeBaseId);
        KnowledgeBase base = baseStore.findById(knowledgeBaseId).orElse(null);
        for (KnowledgeDocument document : documentsInBase(knowledgeBaseId)) {
            KnowledgeExternalIndexBinding binding = bindingStore
                    .findByProviderAndDocumentId(PROVIDER, document.id())
                    .orElse(null);
            boolean duplicate = !document.sourceIdentityKey().isBlank()
                    && duplicateIdentities.contains(document.sourceIdentityKey());
            InventoryCategory category = InventoryClassifier.classify(document, base, binding, duplicate);
            counts.merge(category, 1L, Long::sum);
        }
        return InventoryCategoryCounts.from(counts);
    }

    @Override
    public long countDocuments(String knowledgeBaseId) {
        return documentsInBase(knowledgeBaseId).size();
    }

    @Override
    public List<KnowledgeDocument> nextBackfillCandidates(String knowledgeBaseId, String afterDocumentId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        KnowledgeBase base = baseStore.findById(knowledgeBaseId).orElse(null);
        if (base == null || base.lifecycleStatus() != KnowledgeBaseLifecycle.ACTIVE) {
            return List.of();
        }
        Set<String> duplicateIdentities = duplicateIdentities(knowledgeBaseId);
        String after = afterDocumentId == null ? "" : afterDocumentId.strip();
        return documentsInBase(knowledgeBaseId).stream()
                .filter(KnowledgeDocument::visible)
                .filter(document -> !document.localOnlyOverride())
                .filter(document -> document.chunkCount() > 0 && !document.checksum().isBlank())
                .filter(document -> document.sourceIdentityKey().isBlank()
                        || !duplicateIdentities.contains(document.sourceIdentityKey()))
                .filter(document -> bindingStore.findByProviderAndDocumentId(PROVIDER, document.id()).isEmpty())
                .filter(document -> after.isBlank() || compareDocumentIds(document.id(), after) > 0)
                .sorted(Comparator.comparing(KnowledgeDocument::id, InMemoryKnowledgeInventoryAuditStore::compareDocumentIds))
                .limit(limit)
                .toList();
    }

    @Override
    public List<DuplicateIdentityGroup> listDuplicateGroups(String knowledgeBaseId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        LinkedHashMap<String, List<KnowledgeDocument>> grouped = new LinkedHashMap<>();
        for (KnowledgeDocument document : documentsInBase(knowledgeBaseId)) {
            if (!document.visible() || document.sourceIdentityKey().isBlank()) {
                continue;
            }
            grouped.computeIfAbsent(document.sourceIdentityKey(), key -> new ArrayList<>()).add(document);
        }
        ArrayList<DuplicateIdentityGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<KnowledgeDocument>> entry : grouped.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            List<DuplicateIdentityMember> members = entry.getValue().stream()
                    .sorted(SURVIVOR_ORDER)
                    .map(document -> new DuplicateIdentityMember(
                            document.id(),
                            document.lastSyncedAtEpochMillis(),
                            document.createdAtEpochMillis(),
                            document.rowVersion()))
                    .toList();
            groups.add(new DuplicateIdentityGroup(
                    knowledgeBaseId,
                    entry.getKey(),
                    members.getFirst().documentId(),
                    members
            ));
            if (groups.size() >= limit) {
                break;
            }
        }
        return List.copyOf(groups);
    }

    @Override
    public List<InventoryDriftEntry> listLocalOrphanDrift(String knowledgeBaseId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return bindingStore.listAll().stream()
                .filter(binding -> binding.knowledgeBaseId().equals(knowledgeBaseId))
                .filter(binding -> binding.desiredState() == ExternalKnowledgeDesiredState.PRESENT)
                .filter(binding -> documentStore.findById(binding.documentId())
                        .map(document -> !document.visible())
                        .orElse(true))
                .sorted(Comparator.comparing(KnowledgeExternalIndexBinding::documentId,
                        InMemoryKnowledgeInventoryAuditStore::compareDocumentIds))
                .limit(limit)
                .map(binding -> new InventoryDriftEntry(
                        binding.knowledgeBaseId(),
                        binding.documentId(),
                        binding.remoteUri(),
                        binding.desiredState()))
                .toList();
    }

    @Override
    public long countInFlightOperations(String knowledgeBaseId) {
        return outboxStore.listAll().stream()
                .filter(operation -> operation.knowledgeBaseId().equals(knowledgeBaseId))
                .filter(operation -> !operation.status().terminal())
                .count();
    }

    private List<KnowledgeDocument> documentsInBase(String knowledgeBaseId) {
        return documentStore.listByKnowledgeBaseId(knowledgeBaseId);
    }

    private Set<String> duplicateIdentities(String knowledgeBaseId) {
        return documentsInBase(knowledgeBaseId).stream()
                .filter(KnowledgeDocument::visible)
                .filter(document -> !document.sourceIdentityKey().isBlank())
                .collect(Collectors.groupingBy(KnowledgeDocument::sourceIdentityKey, Collectors.counting()))
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue() > 1L)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    static int compareDocumentIds(String left, String right) {
        try {
            return Long.compare(Long.parseLong(left), Long.parseLong(right));
        } catch (NumberFormatException ignored) {
            return left.compareTo(right);
        }
    }

    private static final Comparator<KnowledgeDocument> SURVIVOR_ORDER =
            Comparator.comparingLong(KnowledgeDocument::lastSyncedAtEpochMillis).reversed()
                    .thenComparing(Comparator.comparingLong(KnowledgeDocument::createdAtEpochMillis).reversed())
                    .thenComparing(KnowledgeDocument::id, Comparator.reverseOrder());
}
