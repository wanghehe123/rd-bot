package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import com.wish.rd.rag.knowledge.projection.model.ProjectionSettleBundle;

import java.util.Optional;

/**
 * 把 Outbox 收口与绑定观测提交在同一个本地事务里。
 *
 * <p>和 {@link KnowledgeMutationTransactionPort} 同一条规则：远端 HTTP 调用必须发生在
 * 本方法之前，事务内不得访问 OpenViking、模型或对象存储。
 */
public interface KnowledgeProjectionSettlePort {

    /**
     * 提交一次收口。Outbox CAS 失败时不得写绑定，并返回空。
     *
     * @param bundle 收口意图
     * @return CAS 成功时返回新的 Outbox 行
     */
    Optional<KnowledgeExternalIndexOperation> settle(ProjectionSettleBundle bundle);
}
