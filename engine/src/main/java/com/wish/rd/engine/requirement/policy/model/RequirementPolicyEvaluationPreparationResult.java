package com.wish.rd.engine.requirement.policy.model;

import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;

/**
 * Atomic durable preparation of a frozen plan ledger and its policy-evaluation command.
 *
 * <p>The returned pair is the only valid route into {@code POLICY_EVALUATE}: the command's
 * immutable policy-run reference must already resolve to the exact {@link RequirementPolicyRun}
 * before the command can be claimed.
 */
public record RequirementPolicyEvaluationPreparationResult(
        RequirementPolicyRun policyRun,
        RequirementStageCommand policyEvaluateCommand,
        RequirementPolicyRetryContext retryContext
) {
    /** Rejects orphan, leased, or cross-generation preparation results at the port boundary. */
    public RequirementPolicyEvaluationPreparationResult {
        retryContext = java.util.Objects.requireNonNull(retryContext, "retryContext must not be null");
        if (policyRun == null || policyEvaluateCommand == null
                || policyRun.state() != RequirementPolicyRunState.PLAN_READY
                || !policyRun.id().equals(policyEvaluateCommand.policyRunId())
                || !policyRun.id().equals(policyEvaluateCommand.commandId())
                || !policyRun.taskId().equals(policyEvaluateCommand.taskId())
                || policyRun.sourceTaskVersion() != policyEvaluateCommand.taskVersion()
                || policyRun.sourceFencingToken() != policyEvaluateCommand.fencingToken()
                || policyRun.boundTaskVersion() != policyEvaluateCommand.taskVersion()
                || policyRun.boundFencingToken() != policyEvaluateCommand.fencingToken()
                || policyEvaluateCommand.status() != RequirementStageCommand.Status.PENDING
                || !policyEvaluateCommand.leaseOwner().isBlank()
                || policyEvaluateCommand.leaseUntilEpochMillis() != 0L
                || !"REQUIREMENT_DELIVERY".equals(policyEvaluateCommand.role())
                || !"POLICY_EVALUATE".equals(policyEvaluateCommand.stage())) {
            throw new IllegalArgumentException(
                    "policy preparation must bind one pending evaluate command to its PLAN_READY ledger");
        }
        retryContext.requireControlCommandIdentity(policyEvaluateCommand.retryCheckpointId(),
                policyEvaluateCommand.businessGeneration(), policyEvaluateCommand.targetRetryBindingId());
        retryContext.requireSourcePlanDigest(policyRun.planDigest());
    }

    /** Compatibility constructor for normal policy operations, with explicit empty retry context. */
    public RequirementPolicyEvaluationPreparationResult(
            RequirementPolicyRun policyRun, RequirementStageCommand policyEvaluateCommand
    ) {
        this(policyRun, policyEvaluateCommand, RequirementPolicyRetryContext.empty());
    }
}
