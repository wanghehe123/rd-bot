package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.projection.model.KnowledgeDocumentMutationBundle;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeProjectionBackfillBundle;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeProjectionBackfillCommitResult;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeProjectionSupersedeBundle;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;

/**
 * 知识 mutation 的原子提交边界。实现禁止在事务内调用 OpenViking、模型、对象存储或执行器。
 */
public interface KnowledgeMutationTransactionPort {

    KnowledgeDocument commit(KnowledgeDocumentMutationBundle bundle);

    /**
     * 存量回填：窄 CAS 补身份与当前修订，幂等插入修订，绑定 {@code ON CONFLICT DO NOTHING}，再入队 UPSERT。
     * 不写分块、不删向量、不覆盖已有 {@code observed_*}。
     */
    KnowledgeProjectionBackfillCommitResult commitBackfill(KnowledgeProjectionBackfillBundle bundle);

    /**
     * 操作员确认的重复收敛。任一 loser 的行版本不匹配时抛 {@link InventorySupersedeConflictException}，整笔回滚。
     */
    void commitSupersede(KnowledgeProjectionSupersedeBundle bundle);
}
