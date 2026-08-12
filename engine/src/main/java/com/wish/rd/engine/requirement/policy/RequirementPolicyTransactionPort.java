package com.wish.rd.engine.requirement.policy;

import com.wish.rd.engine.requirement.policy.model.ApproveRequirementPolicyCommand;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyApprovalResult;
import com.wish.rd.engine.requirement.policy.model.RecordRequirementPolicyDecisionCommand;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationProposal;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationPreparationResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyApplyResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyResumeResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRetryContext;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;

/**
 * Host transaction boundary for policy-ledger approval and durable resume production.
 *
 * <p>Its PostgreSQL implementation locks the policy ledger before the task and never exposes an
 * approval without the matching durable {@code APPROVAL_RESUME} command.
 */
public interface RequirementPolicyTransactionPort {

    /**
     * CAS-terminalizes an eligible active source generation before an authorized POLICY retry
     * creates its replacement generation. DENIED remains an immutable source and is never
     * rewritten; APPLIED is not eligible for POLICY reevaluation.
     *
     * @param context complete checkpoint-bound policy retry lineage
     * @param expectedLedgerVersion source ledger version guarded by the caller
     * @param nowEpochMillis audit clock
     * @return the exact terminal SUPERSEDED source generation
     */
    default RequirementPolicyRun supersedeForPolicyRetry(
            RequirementPolicyRetryContext context, long expectedLedgerVersion, long nowEpochMillis
    ) {
        throw new UnsupportedOperationException("policy retry supersession is not configured");
    }

    /**
     * Atomically persists the frozen {@code PLAN_READY} ledger generation and its exact pending
     * {@code POLICY_EVALUATE} command before either is visible to a dispatcher.
     *
     * <p>The proposal is computed read-only from the fenced {@code PLAN_GENERATED} task. The
     * transaction persists only its canonical frozen plan; the later leased control command
     * records the policy decision against this pre-existing generation.
     */
    default RequirementPolicyEvaluationPreparationResult prepareEvaluation(
            RequirementPolicyEvaluationProposal proposal,
            RequirementStageCommand policyEvaluateCommand,
            long nowEpochMillis
    ) {
        return prepareEvaluation(proposal, policyEvaluateCommand, RequirementPolicyRetryContext.empty(), nowEpochMillis);
    }

    /** Context-aware preparation overload for one checkpoint-bound policy generation. */
    default RequirementPolicyEvaluationPreparationResult prepareEvaluation(
            RequirementPolicyEvaluationProposal proposal,
            RequirementStageCommand policyEvaluateCommand,
            RequirementPolicyRetryContext retryContext,
            long nowEpochMillis
    ) {
        throw new UnsupportedOperationException("policy-evaluation preparation is not configured");
    }

    /**
     * Persists a pre-evaluated canonical policy decision and produces its exact apply command.
     *
     * <p>The default keeps approval-only fixtures source compatible until the dispatcher is wired
     * to run the dedicated control command.
     */
    default RequirementPolicyEvaluationResult recordEvaluation(
            RecordRequirementPolicyDecisionCommand decision,
            RequirementStageCommand command,
            String leaseOwner,
            long nowEpochMillis
    ) {
        return recordEvaluation(decision, command, RequirementPolicyRetryContext.empty(), leaseOwner, nowEpochMillis);
    }

    /** Context-aware evaluation consumption overload for one checkpoint-bound policy generation. */
    default RequirementPolicyEvaluationResult recordEvaluation(
            RecordRequirementPolicyDecisionCommand decision,
            RequirementStageCommand command,
            RequirementPolicyRetryContext retryContext,
            String leaseOwner,
            long nowEpochMillis
    ) {
        throw new UnsupportedOperationException("policy-evaluation recording is not configured");
    }

    /**
     * Approves one waiting policy generation or returns its exact prior idempotent result.
     *
     * @param command digest- and concurrency-bound approval request
     * @param actor host-controlled service actor recorded for the approval audit trail
     * @param nowEpochMillis Host clock for the ledger and task timeline
     * @return authoritative ledger, resume command, and post-CAS task concurrency pair
     */
    RequirementPolicyApprovalResult approve(
            ApproveRequirementPolicyCommand command, String actor, long nowEpochMillis);

    /** Context-aware approval overload for one checkpoint-bound policy generation. */
    default RequirementPolicyApprovalResult approve(
            ApproveRequirementPolicyCommand command,
            RequirementPolicyRetryContext retryContext,
            String actor,
            long nowEpochMillis
    ) {
        throw new UnsupportedOperationException("policy approval is not configured");
    }

    /**
     * Consumes one leased approval-resume command and durably starts its first delivery role.
     *
     * <p>The default preserves the approval producer's single-abstract-method compatibility for
     * existing controller fixtures until a durable consumer is wired by the dispatcher.
     *
     * @param command exact leased {@code APPROVAL_RESUME} command
     * @param leaseOwner current command lease owner
     * @param nowEpochMillis Host clock for task, command, and ledger writes
     * @return applied ledger, completed resume command, continuation, and post-CAS task pair
     */
    default RequirementPolicyResumeResult consumeApproval(
            RequirementStageCommand command, String leaseOwner, long nowEpochMillis
    ) {
        return consumeApproval(command, RequirementPolicyRetryContext.empty(), leaseOwner, nowEpochMillis);
    }

    /** Context-aware approval-resume consumption overload. */
    default RequirementPolicyResumeResult consumeApproval(
            RequirementStageCommand command,
            RequirementPolicyRetryContext retryContext,
            String leaseOwner,
            long nowEpochMillis
    ) {
        throw new UnsupportedOperationException("approval-resume consumption is not configured");
    }

    /**
     * Consumes one leased policy-apply command without invoking a policy gate under row locks.
     *
     * @param command exact leased {@code POLICY_APPLY} command
     * @param leaseOwner current command lease owner
     * @param nowEpochMillis host clock for task, command, and ledger writes
     * @return authoritative disposition and, only when allowed, its first role continuation
     */
    default RequirementPolicyApplyResult consumePolicyApply(
            RequirementStageCommand command, String leaseOwner, long nowEpochMillis
    ) {
        return consumePolicyApply(command, RequirementPolicyRetryContext.empty(), leaseOwner, nowEpochMillis);
    }

    /** Context-aware policy-apply consumption overload. */
    default RequirementPolicyApplyResult consumePolicyApply(
            RequirementStageCommand command,
            RequirementPolicyRetryContext retryContext,
            String leaseOwner,
            long nowEpochMillis
    ) {
        throw new UnsupportedOperationException("policy-apply consumption is not configured");
    }
}
