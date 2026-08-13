package com.wish.rd.rag.knowledge.projection.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 一轮对账的汇总。counts 方便调度与健康检查，findings 是本轮 upsert 后的行。
 *
 * @param counts   按种类计数
 * @param findings 本轮涉及到的发现
 */
public record ReconcileReport(
        Map<ReconcileFindingType, Integer> counts,
        List<ReconcileFinding> findings
) {

    public ReconcileReport {
        counts = counts == null ? Map.of() : Map.copyOf(counts);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static ReconcileReport of(List<ReconcileFinding> findings) {
        List<ReconcileFinding> snapshot = findings == null ? List.of() : List.copyOf(findings);
        EnumMap<ReconcileFindingType, Integer> counts = new EnumMap<>(ReconcileFindingType.class);
        for (ReconcileFinding finding : snapshot) {
            counts.merge(finding.findingType(), 1, Integer::sum);
        }
        return new ReconcileReport(counts, snapshot);
    }
}
