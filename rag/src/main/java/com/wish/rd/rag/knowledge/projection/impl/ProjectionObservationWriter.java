package com.wish.rd.rag.knowledge.projection.impl;

import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;

import java.util.Optional;

/**
 * 观测写入的共享重试逻辑，内存与 PostgreSQL 适配器共用一套语义。
 *
 * <p>CAS 失败只可能是本地 mutation 事务并发推进了 desired。观测的 SET 子句不含
 * {@code desired_*}，所以重新读取版本号后重试是安全的：既不回退更高版本的意图，
 * 也不丢掉刚拿到的远端观测。
 */
public final class ProjectionObservationWriter {

    private static final int MAX_ATTEMPTS = 3;

    private ProjectionObservationWriter() {
    }

    /**
     * 读取当前版本后 CAS 写入观测，失败时有界重试。
     *
     * @param bindingStore 绑定 Store
     * @param observation  观测值
     * @return 写入后的绑定；绑定不存在时为空
     */
    public static Optional<KnowledgeExternalIndexBinding> write(
            KnowledgeExternalIndexBindingStore bindingStore,
            KnowledgeExternalIndexBinding observation
    ) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Optional<KnowledgeExternalIndexBinding> current = bindingStore.findByProviderAndDocumentId(
                    observation.provider(), observation.documentId());
            if (current.isEmpty()) {
                return Optional.empty();
            }
            Optional<KnowledgeExternalIndexBinding> written = bindingStore.saveObservationIfVersionMatches(
                    observation, current.orElseThrow().rowVersion());
            if (written.isPresent()) {
                return written;
            }
        }
        throw new IllegalStateException(
                "binding observation lost the CAS race repeatedly: " + observation.documentId());
    }
}
