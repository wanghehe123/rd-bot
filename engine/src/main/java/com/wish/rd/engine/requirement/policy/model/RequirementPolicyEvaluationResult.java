package com.wish.rd.engine.requirement.policy.model;

import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.FairRequirementDeliveryClaimPlanner;

/**
 * Atomic result of recording one policy decision and producing its durable apply command.
 *
 * <p>The result binds the completed evaluation command, the ledger decision, and the exact
 * post-CAS apply continuation so a later dispatcher cannot infer either from task history.
 */
public record RequirementPolicyEvaluationResult(
        RequirementPolicyRun policyRun,
        RequirementStageCommand completedEvaluationCommand,
        RequirementStageCommand policyApplyCommand,
        long taskVersion,
        long fencingToken,
        RequirementPolicyRetryContext retryContext
) {
    /** Enforces the decided ledger and exact evaluation/apply command identities. */
    public RequirementPolicyEvaluationResult {
        retryContext = java.util.Objects.requireNonNull(retryContext, "retryContext must not be null");
        if (policyRun == null || completedEvaluationCommand == null || policyApplyCommand == null) {
            throw new IllegalArgumentException("policyRun, completedEvaluationCommand, and policyApplyCommand must not be null");
        }
        if (taskVersion < 0L || fencingToken <= 0L) {
            throw new IllegalArgumentException("taskVersion must be nonnegative and fencingToken positive");
        }
        if (policyRun.state() != RequirementPolicyRunState.POLICY_DECIDED
                || policyRun.policyJson().isBlank()
                || policyRun.policyDigest().isBlank()
                || policyRun.policyAction().isBlank()
                || policyRun.boundTaskVersion() != taskVersion
                || policyRun.boundFencingToken() != fencingToken
                || taskVersion != Math.addExact(policyRun.sourceTaskVersion(), 1L)
                || fencingToken != Math.addExact(policyRun.sourceFencingToken(), 1L)
                || !policyRun.taskId().equals(completedEvaluationCommand.taskId())
                || !policyRun.taskId().equals(policyApplyCommand.taskId())
                || !policyRun.id().equals(completedEvaluationCommand.policyRunId())
                || !policyRun.id().equals(policyApplyCommand.policyRunId())
                || completedEvaluationCommand.status() != RequirementStageCommand.Status.SUCCEEDED
                || !"REQUIREMENT_DELIVERY".equals(completedEvaluationCommand.role())
                || !"POLICY_EVALUATE".equals(completedEvaluationCommand.stage())
                || completedEvaluationCommand.taskVersion() != policyRun.sourceTaskVersion()
                || completedEvaluationCommand.fencingToken() != policyRun.sourceFencingToken()
                || policyApplyCommand.status() != RequirementStageCommand.Status.PENDING
                || !"REQUIREMENT_DELIVERY".equals(policyApplyCommand.role())
                || !"POLICY_APPLY".equals(policyApplyCommand.stage())
                || policyApplyCommand.taskVersion() != taskVersion
                || policyApplyCommand.fencingToken() != fencingToken
                || policyApplyCommand.attemptNo() != 0
                || !policyApplyCommand.leaseOwner().isBlank()
                || policyApplyCommand.leaseUntilEpochMillis() != 0L
                || policyApplyCommand.deadlineEpochMillis() <= 0L
                || !policyApplyCommand.resourceRequirements().equals(
                FairRequirementDeliveryClaimPlanner.classifyStageRequirements(
                        policyApplyCommand.role(), policyApplyCommand.stage()))
                || !policyApplyCommand.resourceRequirements().contains(policyApplyCommand.resourceClass())
                || policyApplyCommand.providerId().isBlank()
                || policyApplyCommand.projectId().isBlank()) {
            throw new IllegalArgumentException("policy evaluation result identities must match the decided ledger generation");
        }
        retryContext.requireControlCommandIdentity(completedEvaluationCommand.retryCheckpointId(),
                completedEvaluationCommand.businessGeneration(), completedEvaluationCommand.targetRetryBindingId());
        retryContext.requireControlCommandIdentity(policyApplyCommand.retryCheckpointId(),
                policyApplyCommand.businessGeneration(), policyApplyCommand.targetRetryBindingId());
        retryContext.requireSourcePlanDigest(policyRun.planDigest());
    }

    /** Compatibility constructor for normal policy operations, with explicit empty retry context. */
    public RequirementPolicyEvaluationResult(
            RequirementPolicyRun policyRun, RequirementStageCommand completedEvaluationCommand,
            RequirementStageCommand policyApplyCommand, long taskVersion, long fencingToken
    ) {
        this(policyRun, completedEvaluationCommand, policyApplyCommand, taskVersion, fencingToken,
                RequirementPolicyRetryContext.empty());
    }
}
