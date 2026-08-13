package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.projection.model.DuplicateIdentityGroup;
import com.wish.rd.rag.knowledge.projection.model.InventoryCategoryCounts;
import com.wish.rd.rag.knowledge.projection.model.InventoryDriftEntry;

import java.util.List;

/**
 * 存量投影审计只读端口。分类必须穷尽且互斥，漂移单独成表。
 */
public interface KnowledgeInventoryAuditStore {

    InventoryCategoryCounts countByCategory(String knowledgeBaseId);

    long countDocuments(String knowledgeBaseId);

    /**
     * 键集分页取出符合条件且尚无绑定的待回填文档。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param afterDocumentId 上一页最后一篇文档 ID，空表示从头
     * @param limit           最多返回条数
     * @return 按文档 ID 升序的候选
     */
    List<KnowledgeDocument> nextBackfillCandidates(String knowledgeBaseId, String afterDocumentId, int limit);

    List<DuplicateIdentityGroup> listDuplicateGroups(String knowledgeBaseId, int limit);

    List<InventoryDriftEntry> listLocalOrphanDrift(String knowledgeBaseId, int limit);

    /**
     * 该知识库未收敛的外部索引操作数量（非 SUCCEEDED/SUPERSEDED/DEAD_LETTER）。
     */
    long countInFlightOperations(String knowledgeBaseId);
}
