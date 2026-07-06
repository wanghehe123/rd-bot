package com.wish.rd.rag.retrieval.model;

import com.wish.rd.rag.intent.model.NodeScore;

import java.util.List;
import java.util.Optional;

/**
 * 检索请求，封装查询文本、主意图与 topK，并派生各类目标范围。
 *
 * @param query        检索查询文本（描述 + 日志合并后）
 * @param primaryIntent 主意图节点评分，为空表示未命中意图
 * @param topK         每个通道返回的最大证据块数，<=0 时取默认 10
 */
public record RetrievalRequest(
        String query,
        Optional<NodeScore> primaryIntent,
        int topK
) {

    /** 紧凑构造器：归一可空字段，校正 topK。 */
    public RetrievalRequest {
        query = query == null ? "" : query;
        primaryIntent = primaryIntent == null ? Optional.empty() : primaryIntent;
        topK = topK <= 0 ? 10 : topK;
    }

    /** 主意图关联的知识库 ID 列表（意图导向向量检索的范围）。 */
    public List<String> targetKnowledgeBaseIds() {
        return primaryIntent
                .map(score -> score.node().knowledgeBaseIds())
                .orElseGet(List::of);
    }

    /** 主意图关联的目标系统 ID（日志中心检索的范围）。 */
    public String targetSystemId() {
        return primaryIntent
                .map(score -> score.node().systemId())
                .orElse("");
    }

    /** 主意图关联的代码仓库 ID 列表（代码仓库检索的范围）。 */
    public List<String> targetCodeRepositoryIds() {
        return primaryIntent
                .map(score -> score.node().codeRepositoryIds())
                .orElseGet(List::of);
    }
}
