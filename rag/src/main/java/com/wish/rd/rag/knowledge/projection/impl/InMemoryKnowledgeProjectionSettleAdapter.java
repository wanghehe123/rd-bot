package com.wish.rd.rag.knowledge.projection.impl;

import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.KnowledgeProjectionSettlePort;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import com.wish.rd.rag.knowledge.projection.model.ProjectionSettleBundle;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 内存收口适配器。语义与 PostgreSQL 版一致：Outbox CAS 不通过就不碰绑定。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
public final class InMemoryKnowledgeProjectionSettleAdapter implements KnowledgeProjectionSettlePort {

    private final KnowledgeExternalIndexOutboxStore outboxStore;
    private final KnowledgeExternalIndexBindingStore bindingStore;

    public InMemoryKnowledgeProjectionSettleAdapter(
            KnowledgeExternalIndexOutboxStore outboxStore,
            KnowledgeExternalIndexBindingStore bindingStore
    ) {
        this.outboxStore = outboxStore;
        this.bindingStore = bindingStore;
    }

    @Override
    public synchronized Optional<KnowledgeExternalIndexOperation> settle(ProjectionSettleBundle bundle) {
        Optional<KnowledgeExternalIndexOperation> settled = outboxStore.settle(
                bundle.eventId(),
                bundle.expectedStatus(),
                bundle.leaseOwner(),
                bundle.expectedRowVersion(),
                bundle.command(),
                bundle.nowEpochMillis()
        );
        if (settled.isEmpty() || bundle.observation() == null) {
            return settled;
        }
        ProjectionObservationWriter.write(bindingStore, bundle.observation());
        return settled;
    }
}
