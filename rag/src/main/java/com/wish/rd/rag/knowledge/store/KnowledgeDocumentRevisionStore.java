package com.wish.rd.rag.knowledge.store;

import com.wish.rd.rag.knowledge.model.KnowledgeDocumentRevision;

import java.util.List;
import java.util.Optional;

/**
 * 知识文档不可变 revision 存储。
 */
public interface KnowledgeDocumentRevisionStore {

    KnowledgeDocumentRevision save(KnowledgeDocumentRevision revision);

    /**
     * 按 ID 读取冻结 revision。投影 Worker 用它取待上传的规范正文，
     * 而不是读可能已经变化的 {@code knowledge_documents.raw_content}。
     *
     * @param id revision ID
     * @return 存在时返回 revision
     */
    Optional<KnowledgeDocumentRevision> findById(String id);

    Optional<KnowledgeDocumentRevision> findByDocumentIdAndChecksum(String documentId, String checksum);

    List<KnowledgeDocumentRevision> listByDocumentId(String documentId);

    List<KnowledgeDocumentRevision> listAll();

    void delete(String id);
}
