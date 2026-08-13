package com.wish.rd.rag.knowledge.store;

import com.wish.rd.rag.knowledge.model.KnowledgeDocumentRevision;

import java.util.List;
import java.util.Optional;

/**
 * 知识文档不可变 revision 存储。
 */
public interface KnowledgeDocumentRevisionStore {

    KnowledgeDocumentRevision save(KnowledgeDocumentRevision revision);

    Optional<KnowledgeDocumentRevision> findByDocumentIdAndChecksum(String documentId, String checksum);

    List<KnowledgeDocumentRevision> listByDocumentId(String documentId);
}
