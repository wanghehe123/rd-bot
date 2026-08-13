package com.wish.rd.rag.retrieval.navigator.model;

import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;

import java.util.Objects;

/**
 * 通过 allowlist 的一条证据：保留原始命中 URI，并带上本地绑定与文档快照。
 *
 * @param hit     远端原始命中，URI 可能是文档根下的派生路径
 * @param binding 最长路径前缀解析到的本地绑定
 * @param document 判定时读到的文档快照
 */
public record AdmittedKnowledgeEvidence(
        ExternalNavigatorSearch.Hit hit,
        KnowledgeExternalIndexBinding binding,
        KnowledgeDocument document
) {

    public AdmittedKnowledgeEvidence {
        Objects.requireNonNull(hit, "hit must not be null");
        Objects.requireNonNull(binding, "binding must not be null");
        Objects.requireNonNull(document, "document must not be null");
    }
}
