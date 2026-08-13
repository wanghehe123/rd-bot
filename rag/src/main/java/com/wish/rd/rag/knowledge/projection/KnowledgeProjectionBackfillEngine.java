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

    /**
     * 以有界批量回填一个知识库。未收敛操作达到上限时本批不入队并给出原因。
     * 单篇失败记为 {@link InventoryBackfillStatus#FAILED} 并继续处理其余候选。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param limit           本批上限，非正数时用配置默认
     * @param nowEpochMillis  当前时间
     * @return 逐类计数与逐篇结果
     */
    public InventoryBackfillBatchReport backfillBatch(String knowledgeBaseId, int limit, long nowEpochMillis) {
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        int requested = limit <= 0 ? settings.batchSize() : Math.min(limit, settings.batchSize());
        long inFlight = auditStore.countInFlightOperations(knowledgeBaseId);
        if (inFlight >= settings.maxInFlight()) {
            return new InventoryBackfillBatchReport(
                    knowledgeBaseId, 0, 0, 0, 0, 0, 0, IN_FLIGHT_CAP_REASON, List.of());
        }
        int remainingCapacity = (int) Math.min(requested, settings.maxInFlight() - inFlight);
        List<KnowledgeDocument> candidates = auditStore.nextBackfillCandidates(
                knowledgeBaseId, "", remainingCapacity);
        ArrayList<InventoryBackfillOutcome> outcomes = new ArrayList<>();
        int applied = 0;
        int alreadyBound = 0;
        int notEligible = 0;
        int concurrent = 0;
        int failed = 0;
        for (KnowledgeDocument candidate : candidates) {
            InventoryBackfillOutcome outcome;
            try {
                outcome = mutations.backfillProjection(candidate.id(), nowEpochMillis);
            } catch (RuntimeException exception) {
                // 单篇失败必须记名 FAILED 并继续，禁止整批 500，也禁止静默跳过。
                outcome = InventoryBackfillOutcome.failed(candidate.id(), sanitizeReason(exception));
            }
            outcomes.add(outcome);
            switch (outcome.status()) {
                case APPLIED -> applied++;
                case SKIPPED_ALREADY_BOUND -> alreadyBound++;
                case SKIPPED_NOT_ELIGIBLE -> notEligible++;
                case SKIPPED_CONCURRENT_MODIFICATION -> concurrent++;
                case FAILED -> failed++;
            }
        }
        return new InventoryBackfillBatchReport(
                knowledgeBaseId,
                outcomes.size(),
                applied,
                alreadyBound,
                notEligible,
                concurrent,
                failed,
                "",
                List.copyOf(outcomes)
        );
    }

    /**
     * 去掉堆栈与密钥，只保留可落报告的原因文本。
     *
     * @param error 单篇回填抛出的异常
     * @return 已脱敏且有界的原因
     */
    static String sanitizeReason(Throwable error) {
        String raw = error == null ? "" : error.getMessage();
        if (raw == null || raw.isBlank()) {
            raw = "backfill failed";
        }
        String redacted = raw
                .replaceAll("(?i)(\"?user_key\"?\\s*[:=]\\s*\"?)[^\\s\",}]+", "$1REDACTED")
                .replaceAll("(?i)(\"?admin_key\"?\\s*[:=]\\s*\"?)[^\\s\",}]+", "$1REDACTED")
                .replaceAll("(?i)(\"?api[_-]?key\"?\\s*[:=]\\s*\"?)[^\\s\",}]+", "$1REDACTED")
                .replaceAll("(?i)(X-API-Key\\s*[:=]\\s*)\\S+", "$1REDACTED");
        return redacted.length() <= 500 ? redacted : redacted.substring(0, 500);
    }
}
