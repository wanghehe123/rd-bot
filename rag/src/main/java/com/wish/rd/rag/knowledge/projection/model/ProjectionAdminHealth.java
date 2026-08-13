package com.wish.rd.rag.knowledge.projection.model;

import java.util.Map;

/**
 * 投影健康卡：ready、运行参数、语义指纹与对账发现计数。
 *
 * @param ready                        远端是否可接收写入
 * @param workerBatchSize              Worker 批量
 * @param workerLeaseMillis            提交侧租约
 * @param unknownOutcomeTimeoutMillis  发送后墙钟截止
 * @param semanticConfigFingerprint    账本上见到的指纹，没有时为空
 * @param openFindingCount             未关闭的对账发现
 * @param findingCounts                按 finding_type 计数
 */
public record ProjectionAdminHealth(
        boolean ready,
        int workerBatchSize,
        long workerLeaseMillis,
        long unknownOutcomeTimeoutMillis,
        String semanticConfigFingerprint,
        long openFindingCount,
        Map<String, Long> findingCounts
) {

    public ProjectionAdminHealth {
        semanticConfigFingerprint = semanticConfigFingerprint == null ? "" : semanticConfigFingerprint;
        findingCounts = findingCounts == null ? Map.of() : Map.copyOf(findingCounts);
        openFindingCount = Math.max(0L, openFindingCount);
    }
}
