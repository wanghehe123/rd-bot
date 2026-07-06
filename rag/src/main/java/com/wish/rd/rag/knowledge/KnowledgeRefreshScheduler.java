package com.wish.rd.rag.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.wish.rd.rag.knowledge.model.FeishuDocImportCommand;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeRefreshMetric;

/**
 * 知识刷新调度器：扫描到期文档并按来源触发刷新。
 *
 * <p>P0 提供可测试的同步入口，生产层可用 Spring {@code @Scheduled} 定时调用。
 */
public final class KnowledgeRefreshScheduler {

    private final KnowledgeWorkspace workspace;
    private final FeishuDocKnowledgeImporter feishuImporter;
    private final KnowledgeRefreshMetricSink metricSink;

    public KnowledgeRefreshScheduler(KnowledgeWorkspace workspace, FeishuDocKnowledgeImporter feishuImporter) {
        this(workspace, feishuImporter, KnowledgeRefreshMetricSink.noop());
    }

    public KnowledgeRefreshScheduler(
            KnowledgeWorkspace workspace,
            FeishuDocKnowledgeImporter feishuImporter,
            KnowledgeRefreshMetricSink metricSink
    ) {
        this.workspace = workspace;
        this.feishuImporter = feishuImporter;
        this.metricSink = metricSink == null ? KnowledgeRefreshMetricSink.noop() : metricSink;
    }

    /**
     * 刷新当前到期文档。
     *
     * @param nowEpochMillis 当前时间
     * @param limit          单批上限
     * @return 触发刷新后的文档列表
     */
    public List<KnowledgeDocument> refreshDue(long nowEpochMillis, int limit) {
        List<KnowledgeDocument> refreshed = new ArrayList<>();
        for (KnowledgeDocument document : workspace.dueRefreshDocuments(nowEpochMillis, limit)) {
            refreshed.add(refreshOne(document));
        }
        return List.copyOf(refreshed);
    }

    private KnowledgeDocument refreshOne(KnowledgeDocument document) {
        long startedAt = System.currentTimeMillis();
        try {
            KnowledgeDocument refreshed = document;
            if ("FEISHU".equals(document.sourceType())) {
                refreshed = feishuImporter.importDocument(new FeishuDocImportCommand(
                        document.knowledgeBaseId(),
                        document.sourceUrl().isBlank() ? document.sourceToken() : document.sourceUrl(),
                        document.knowledgeType(),
                        512,
                        64
                ));
            }
            publishMetric(document, refreshed, true, "", startedAt);
            return refreshed;
        } catch (RuntimeException exception) {
            publishMetric(document, document, false, exception.getMessage(), startedAt);
            return document;
        }
    }

    private void publishMetric(
            KnowledgeDocument before,
            KnowledgeDocument after,
            boolean success,
            String errorMessage,
            long startedAtEpochMillis
    ) {
        metricSink.publish(new KnowledgeRefreshMetric(
                before.id(),
                before.knowledgeBaseId(),
                before.sourceType(),
                before.sourceName(),
                success,
                before.chunkCount(),
                after.chunkCount(),
                System.currentTimeMillis() - startedAtEpochMillis,
                errorMessage,
                Map.of("revisionId", after.revisionId()),
                System.currentTimeMillis()
        ));
    }
}
