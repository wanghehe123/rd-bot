package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.KnowledgeProjectionSettlePort;
import com.wish.rd.rag.knowledge.projection.impl.ProjectionObservationWriter;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import com.wish.rd.rag.knowledge.projection.model.ProjectionSettleBundle;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Outbox 收口与绑定观测的 PostgreSQL 单事务适配器。远端调用必须发生在进入本方法之前。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresKnowledgeProjectionSettleAdapter implements KnowledgeProjectionSettlePort {

    private final KnowledgeExternalIndexOutboxStore outboxStore;
    private final KnowledgeExternalIndexBindingStore bindingStore;

    public PostgresKnowledgeProjectionSettleAdapter(
            KnowledgeExternalIndexOutboxStore outboxStore,
            KnowledgeExternalIndexBindingStore bindingStore
    ) {
        this.outboxStore = outboxStore;
        this.bindingStore = bindingStore;
    }

    @Override
    @Transactional
    public Optional<KnowledgeExternalIndexOperation> settle(ProjectionSettleBundle bundle) {
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
