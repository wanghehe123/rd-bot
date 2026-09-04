package com.wish.rd.engine.requirement.audit;

/**
 * Result of one deterministic audit: the run plus the next sealed state.
 *
 * @param auditRun audit run
 * @param nextState next audited state
 */
public record AuditMutation(AuditRun auditRun, AuditedTaskState nextState) {
    public AuditMutation {
        if (auditRun == null) {
            throw new IllegalArgumentException("auditRun must not be null");
        }
        if (nextState == null) {
            throw new IllegalArgumentException("nextState must not be null");
        }
    }

    /**
     * Freezes this mutation for a schema-v3 execution plan.
     *
     * @return plan writeback whose expected version matches {@code nextState}
     */
    public AuditedStateMutation toPlanMutation() {
        return new AuditedStateMutation(auditRun, nextState, nextState.stateVersion());
    }
}
