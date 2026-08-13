package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.KnowledgeDocumentMutationEngine;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.projection.model.InventoryBackfillBatchReport;
import com.wish.rd.rag.knowledge.projection.model.InventoryBackfillOutcome;
import com.wish.rd.rag.knowledge.projection.model.InventoryBackfillSettings;
import com.wish.rd.rag.knowledge.projection.model.InventoryBackfillStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 存量回填批处理。只调用聚合根，不直接写 store。
 */
public final class KnowledgeProjectionBackfillEngine {

    public static final String IN_FLIGHT_CAP_REASON = "in-flight operations reached the configured cap";

    private final KnowledgeInventoryAuditStore auditStore;
    private final KnowledgeDocumentMutationEngine mutations;
    private final InventoryBackfillSettings settings;

    public KnowledgeProjectionBackfillEngine(
            KnowledgeInventoryAuditStore auditStore,
            KnowledgeDocumentMutationEngine mutations,
            InventoryBackfillSettings settings
    ) {
        this.auditStore = Objects.requireNonNull(auditStore, "auditStore must not be null");
        this.mutations = Objects.requireNonNull(mutations, "mutations must not be null");
        this.settings = settings == null ? InventoryBackfillSettings.defaults() : settings;
    }

    public InventoryBackfillBatchReport backfillBatch(String knowledgeBaseId, int limit, long nowEpochMillis) {
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        int requested = limit <= 0 ? settings.batchSize() : Math.min(limit, settings.batchSize());
        long inFlight = auditStore.countInFlightOperations(knowledgeBaseId);
        if (inFlight >= settings.maxInFlight()) {
            return new InventoryBackfillBatchReport(
                    knowledgeBaseId, 0, 0, 0, 0, 0, IN_FLIGHT_CAP_REASON, List.of());
        }
        int remainingCapacity = (int) Math.min(requested, settings.maxInFlight() - inFlight);
        List<KnowledgeDocument> candidates = auditStore.nextBackfillCandidates(
                knowledgeBaseId, "", remainingCapacity);
        ArrayList<InventoryBackfillOutcome> outcomes = new ArrayList<>();
        int applied = 0;
        int alreadyBound = 0;
        int notEligible = 0;
        int concurrent = 0;
        for (KnowledgeDocument candidate : candidates) {
            InventoryBackfillOutcome outcome = mutations.backfillProjection(candidate.id(), nowEpochMillis);
            outcomes.add(outcome);
            switch (outcome.status()) {
                case APPLIED -> applied++;
                case SKIPPED_ALREADY_BOUND -> alreadyBound++;
                case SKIPPED_NOT_ELIGIBLE -> notEligible++;
                case SKIPPED_CONCURRENT_MODIFICATION -> concurrent++;
            }
        }
        return new InventoryBackfillBatchReport(
                knowledgeBaseId,
                outcomes.size(),
                applied,
                alreadyBound,
                notEligible,
                concurrent,
                "",
                List.copyOf(outcomes)
        );
    }
}
