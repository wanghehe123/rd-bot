package com.wish.rd.engine.requirement.audit;

import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.rag.runtime.model.RdTaskStatus;

/**
 * Rejects a {@code COMPLETED} plan that does not bind an audit run when audited state is in use.
 */
public final class AuditedCompletionBindingGuard {

    private AuditedCompletionBindingGuard() {
    }

    /**
     * Requires a completion binding when the plan writes {@code COMPLETED} and writeback is enabled.
     *
     * @param plan frozen plan
     * @param writebackEnabled whether an audited-state store/writer is configured
     */
    public static void requireBindingIfCompleted(RequirementStageExecutionPlan plan, boolean writebackEnabled) {
        if (plan == null || plan.postStatus() != RdTaskStatus.COMPLETED || !writebackEnabled) {
            return;
        }
        AuditedStateMutation mutation = plan.auditedStateMutation();
        if (mutation == null || mutation.completionBinding() == null) {
            throw new IllegalStateException("COMPLETED plan requires completion binding: " + plan.taskId());
        }
    }
}
