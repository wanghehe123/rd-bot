package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.engine.requirement.audit.AuditedStateMutation;
import com.wish.rd.engine.requirement.audit.AuditedTaskStatePolicyBootstrap;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateStore;
import com.wish.rd.rag.runtime.RdTaskStore;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies frozen audited-state writeback inside the caller's finalization transaction.
 *
 * <p>Must not be {@code final}: {@code @Transactional} uses CGLIB subclassing.
 */
@Component
@ConditionalOnBean(AuditedTaskStateStore.class)
public class AuditedStateFinalizationWriter {

    private final AuditedTaskStateStore store;
    private final RdTaskStore tasks;

    public AuditedStateFinalizationWriter(AuditedTaskStateStore store) {
        this(store, null);
    }

    @Autowired
    public AuditedStateFinalizationWriter(AuditedTaskStateStore store, RdTaskStore tasks) {
        this.store = java.util.Objects.requireNonNull(store, "auditedTaskStateStore must not be null");
        this.tasks = tasks;
    }

    /**
     * Inserts the audit run and CAS-appends the next revision. Same version and hash is
     * idempotent; a hash conflict raises and leaves the caller's marker untouched.
     *
     * <p>A missing head initialize-if-absent from the current task, then applies the mutation.
     * Initialization does not consume {@code UNIQUE(command_id)}.
     *
     * @param mutation frozen writeback, or {@code null} to no-op
     */
    @Transactional
    public void apply(AuditedStateMutation mutation) {
        if (mutation == null) {
            return;
        }
        String taskId = mutation.nextState().taskId();
        if (store.head(taskId).isEmpty() && mutation.expectedStateVersion() > 1L) {
            if (tasks == null) {
                throw new IllegalStateException(
                        "audited state head is missing and task store is unavailable: " + taskId);
            }
            RdRequirementTask task = tasks.findRequirementTask(taskId).orElseThrow(
                    () -> new IllegalStateException(
                            "requirement task is missing for audited initialize-if-absent: " + taskId));
            new AuditedTaskStatePolicyBootstrap(store).initializeIfAbsent(task);
        }
        store.appendRevision(mutation.expectedStateVersion(), mutation.nextState(), mutation.auditRun());
        if (mutation.completionBinding() != null) {
            store.bindCompletion(
                    mutation.completionBinding().taskId(),
                    mutation.completionBinding().auditRunId(),
                    mutation.completionBinding().stateVersion(),
                    mutation.completionBinding().stateHash());
        }
    }
}
