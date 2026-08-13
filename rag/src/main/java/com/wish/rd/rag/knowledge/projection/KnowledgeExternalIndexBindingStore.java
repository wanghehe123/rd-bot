package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;

import java.util.List;
import java.util.Optional;

/**
 * 外部索引 desired/observed 绑定。
 */
public interface KnowledgeExternalIndexBindingStore {

    KnowledgeExternalIndexBinding save(KnowledgeExternalIndexBinding binding);

    /**
     * 以 CAS 写入观测结果。只更新 observed/projection/remote/last_* 列，
     * 绝不改 {@code desired_*}：desired 由本地 mutation 事务拥有，
     * Worker/Poller 覆盖它会把刚提交的 v2 意图悄悄退回 v1。
     *
     * @param binding            携带新观测值的绑定
     * @param expectedRowVersion 期望的当前 rowVersion
     * @return CAS 成功时返回新绑定；版本不符或行不存在时为空
     */
    Optional<KnowledgeExternalIndexBinding> saveObservationIfVersionMatches(
            KnowledgeExternalIndexBinding binding,
            long expectedRowVersion
    );

    Optional<KnowledgeExternalIndexBinding> findByProviderAndDocumentId(String provider, String documentId);

    List<KnowledgeExternalIndexBinding> listAll();

    void delete(String provider, String documentId);
}
