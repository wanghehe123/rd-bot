package com.wish.rd.engine.requirement.policy;

import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRetryContext;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRunState;

import java.util.Optional;

/** Authoritative persistence port for immutable requirement-policy ledger generations. */
public interface RequirementPolicyRunStore {

    /** Creates the supplied generation, or returns the exact prior immutable generation. */
    RequirementPolicyRun createOrGet(RequirementPolicyRun run);

    /** Finds one policy-ledger generation by its identifier. */
    Optional<RequirementPolicyRun> findById(String policyRunId);

    /** Finds the single active generation for a task, if it has one. */
    Optional<RequirementPolicyRun> findActiveByTask(String taskId);

    /** Advances one generation only when its state and ledger version still match. */
    RequirementPolicyRun compareAndSet(
            RequirementPolicyRun next, RequirementPolicyRunState expectedState, long expectedLedgerVersion);

    /**
     * Narrow, context-authorized CAS for terminalizing an active policy source before a POLICY
     * retry. Generic ledger CAS must never expose {@code SUPERSEDED} as a caller-selectable state.
     */
    RequirementPolicyRun supersedeForPolicyRetry(
            RequirementPolicyRetryContext context, long expectedLedgerVersion, long nowEpochMillis);
}
