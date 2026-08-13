package com.wish.rd.bootstrap.openviking.impl;

import com.wish.rd.rag.knowledge.projection.ExternalKnowledgeIndexPort;
import com.wish.rd.rag.knowledge.projection.model.ExternalIndexFailureClass;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeSubmission;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeTaskSnapshot;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeTaskState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeUpsertCommand;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeVerification;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeVersionMarker;

/**
 * 未配置外部索引时的端口。永远 {@code ready() == false}，Worker/Poller 因此完全不动作。
 *
 * <p>存在的意义是让"没接外部索引"成为一个明确的运行态，而不是一个缺失的 Bean：
 * Outbox 行会安静地留在 PENDING，等真正接上以后再投影，不会被消耗预算或推进死信。
 */
public final class DisabledExternalKnowledgeIndexPort implements ExternalKnowledgeIndexPort {

    private static final String REASON = "external index projection is disabled";

    @Override
    public boolean ready() {
        return false;
    }

    @Override
    public ExternalKnowledgeSubmission submitUpsert(ExternalKnowledgeUpsertCommand command) {
        return ExternalKnowledgeSubmission.failed(
                ExternalIndexFailureClass.CONFIGURATION_BLOCKED, "PROJECTION_DISABLED", REASON);
    }

    @Override
    public ExternalKnowledgeTaskSnapshot inspectTask(String remoteTaskId) {
        return new ExternalKnowledgeTaskSnapshot(remoteTaskId, ExternalKnowledgeTaskState.UNKNOWN, "",
                0L, 0L, ExternalIndexFailureClass.CONFIGURATION_BLOCKED, "PROJECTION_DISABLED", REASON);
    }

    @Override
    public ExternalKnowledgeVerification verifyResource(ExternalKnowledgeVersionMarker marker) {
        return ExternalKnowledgeVerification.unavailable(
                ExternalIndexFailureClass.CONFIGURATION_BLOCKED, "PROJECTION_DISABLED", REASON);
    }
}
