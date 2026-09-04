package com.wish.rd.engine.requirement.audit;

/**
 * Frozen audited-state writeback carried by a v3 {@code RequirementStageExecutionPlan}.
 *
 * @param auditRun audit run to persist
 * @param nextState sealed next head
 * @param expectedStateVersion must equal {@code nextState.stateVersion()}
 * @param completionBinding optional completion binding written in the same finalize transaction
 */
public record AuditedStateMutation(
        AuditRun auditRun,
        AuditedTaskState nextState,
        long expectedStateVersion,
        CompletionBinding completionBinding
) {
    public AuditedStateMutation {
        if (auditRun == null) {
            throw new IllegalArgumentException("auditRun must not be null");
        }
        if (nextState == null) {
            throw new IllegalArgumentException("nextState must not be null");
        }
        if (expectedStateVersion != nextState.stateVersion()) {
            throw new IllegalArgumentException("expectedStateVersion must match nextState.stateVersion");
        }
        if (!auditRun.taskId().equals(nextState.taskId())) {
            throw new IllegalArgumentException("audit run taskId must match next state taskId");
        }
        if (completionBinding != null) {
            if (!completionBinding.taskId().equals(nextState.taskId())) {
                throw new IllegalArgumentException("completion binding taskId must match next state taskId");
            }
            if (!completionBinding.auditRunId().equals(auditRun.auditRunId())) {
                throw new IllegalArgumentException("completion binding auditRunId must match audit run");
            }
            if (completionBinding.stateVersion() != nextState.stateVersion()) {
                throw new IllegalArgumentException("completion binding stateVersion must match next state");
            }
            if (!completionBinding.stateHash().equals(nextState.stateHash())) {
                throw new IllegalArgumentException("completion binding stateHash must match next state");
            }
        }
    }

    /**
     * Writeback without a completion binding.
     *
     * @param auditRun audit run
     * @param nextState next head
     * @param expectedStateVersion expected version
     */
    public AuditedStateMutation(AuditRun auditRun, AuditedTaskState nextState, long expectedStateVersion) {
        this(auditRun, nextState, expectedStateVersion, null);
    }

    /**
     * Returns a copy that also binds task completion to this revision.
     *
     * @param binding completion binding
     * @return mutation carrying {@code binding}
     */
    public AuditedStateMutation withCompletionBinding(CompletionBinding binding) {
        return new AuditedStateMutation(auditRun, nextState, expectedStateVersion, binding);
    }
}
