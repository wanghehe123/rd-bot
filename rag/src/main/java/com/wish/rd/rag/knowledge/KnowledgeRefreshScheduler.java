package com.wish.rd.rag.knowledge;

import java.util.List;

/**
 * 知识刷新调度器：扫描到期文档并按来源触发刷新。
 *
 * <p>P0 提供可测试的同步入口，生产层可用 Spring {@code @Scheduled} 定时调用。
 */
public final class KnowledgeRefreshScheduler {

    private final KnowledgeWorkspace workspace;
    private final FeishuDocKnowledgeImporter feishuImporter;

    public KnowledgeRefreshScheduler(KnowledgeWorkspace workspace, FeishuDocKnowledgeImporter feishuImporter) {
        this.workspace = workspace;
        this.feishuImporter = feishuImporter;
    }

    /**
     * 刷新当前到期文档。
     *
     * @param nowEpochMillis 当前时间
     * @param limit          单批上限
     * @return 触发刷新后的文档列表
     */
    public List<KnowledgeDocument> refreshDue(long nowEpochMillis, int limit) {
        return workspace.dueRefreshDocuments(nowEpochMillis, limit).stream()
                .map(this::refreshOne)
                .toList();
    }

    private KnowledgeDocument refreshOne(KnowledgeDocument document) {
        if ("FEISHU".equals(document.sourceType())) {
            return feishuImporter.importDocument(new FeishuDocImportCommand(
                    document.knowledgeBaseId(),
                    document.sourceUrl().isBlank() ? document.sourceToken() : document.sourceUrl(),
                    document.knowledgeType(),
                    512,
                    64
            ));
        }
        return document;
    }
}
