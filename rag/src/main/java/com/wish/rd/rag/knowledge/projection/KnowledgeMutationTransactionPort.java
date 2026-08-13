package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.projection.model.KnowledgeDocumentMutationBundle;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;

/**
 * 知识 mutation 的原子提交边界。实现禁止在事务内调用 OpenViking、模型、对象存储或执行器。
 */
public interface KnowledgeMutationTransactionPort {

    KnowledgeDocument commit(KnowledgeDocumentMutationBundle bundle);
}
