package com.wish.rd.engine.requirement.audit;

import com.wish.rd.rag.runtime.model.RdRequirementTask;

/**
 * Writes {@code state_version=1} when a task first becomes {@code EXECUTING} via policy allow
 * or approval resume.
 */
public final class AuditedTaskStatePolicyBootstrap {

    private final AuditedTaskStateStore store;
    private final AuditedTaskStateInitializer initializer;

    public AuditedTaskStatePolicyBootstrap(AuditedTaskStateStore store) {
        this.store = store;
        this.initializer = new AuditedTaskStateInitializer(new AuditedTaskStateCodec());
    }

    /**
     * Initializes audited state when the store is present. A missing store fails closed because
     * later HOST_VERIFY/QA writeback has no head to CAS against.
     *
     * @param task task entering {@code EXECUTING}
     */
    public void initializeIfAbsent(RdRequirementTask task) {
        if (store == null) {
            throw new IllegalStateException("audited task state store is unavailable: "
                    + (task == null ? "" : task.taskId()));
        }
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        store.initializeIfAbsent(initializer.initialize(task));
    }
}
