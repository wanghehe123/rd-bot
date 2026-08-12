package com.wish.rd.engine.requirement.policy.model;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.FairRequirementDeliveryClaimPlanner;

/**
 * Authoritative result of consuming one approved policy-resume command exactly once.
 *
 * <p>The result keeps the consumed resume command, first delivery command, and post-CAS task
 * concurrency pair together so callers cannot combine a continuation with another generation.
 */
public record RequirementPolicyResumeResult(
        RequirementPolicyRun policyRun,
        RequirementStageCommand completedResumeCommand,
        RequirementStageCommand nextCommand,
        long taskVersion,
        long fencingToken,
        RequirementPolicyRetryContext retryContext
) {
    /** Enforces the immutable ledger, resume, continuation, and post-CAS task identities. */
    public RequirementPolicyResumeResult {
        retryContext = java.util.Objects.requireNonNull(retryContext, "retryContext must not be null");
        if (policyRun == null || completedResumeCommand == null || nextCommand == null) {
            throw new IllegalArgumentException("policyRun, completedResumeCommand, and nextCommand must not be null");
        }
        if (taskVersion < 0L || fencingToken <= 0L) {
            throw new IllegalArgumentException("taskVersion must be nonnegative and fencingToken positive");
        }
        if (policyRun.state() != RequirementPolicyRunState.APPLIED
                || !"WAITING_APPROVAL".equals(policyRun.policyAction())
                || !policyRun.taskId().equals(completedResumeCommand.taskId())
                || !policyRun.taskId().equals(nextCommand.taskId())
                || !policyRun.id().equals(completedResumeCommand.policyRunId())
                || !policyRun.id().equals(nextCommand.policyRunId())
                || !policyRun.approvalResumeCommandId().equals(completedResumeCommand.commandId())
                || !policyRun.consumedByCommandId().equals(completedResumeCommand.commandId())
                || completedResumeCommand.status() != RequirementStageCommand.Status.SUCCEEDED
                || !"REQUIREMENT_DELIVERY".equals(completedResumeCommand.role())
                || !"APPROVAL_RESUME".equals(completedResumeCommand.stage())
                || policyRun.approvalExpectedTaskVersion() == null
                || policyRun.approvalExpectedFencingToken() == null
                || completedResumeCommand.taskVersion() != Math.addExact(policyRun.approvalExpectedTaskVersion(), 1L)
                || completedResumeCommand.fencingToken() != Math.addExact(policyRun.approvalExpectedFencingToken(), 1L)
                || nextCommand.taskVersion() != taskVersion
                || nextCommand.fencingToken() != fencingToken
                || policyRun.boundTaskVersion() != taskVersion
                || policyRun.boundFencingToken() != fencingToken
                || nextCommand.status() != RequirementStageCommand.Status.PENDING
                || nextCommand.attemptNo() != 0
                || !nextCommand.leaseOwner().isBlank()
                || nextCommand.leaseUntilEpochMillis() != 0L
                || nextCommand.deadlineEpochMillis() <= 0L
                || !AgentRole.requirementDeliveryOrder().getFirst().name().equals(nextCommand.role())
                || !("ROLE_EXECUTION:" + AgentRole.requirementDeliveryOrder().getFirst().name()).equals(nextCommand.stage())
                || !nextCommand.resourceRequirements().equals(
                FairRequirementDeliveryClaimPlanner.classifyStageRequirements(nextCommand.role(), nextCommand.stage()))
                || !nextCommand.resourceRequirements().contains(nextCommand.resourceClass())
                || nextCommand.projectId().isBlank()
                || nextCommand.providerId().isBlank()) {
            throw new IllegalArgumentException("policy resume result identities must match the applied ledger generation");
        }
        retryContext.requireControlCommandIdentity(completedResumeCommand.retryCheckpointId(),
                completedResumeCommand.businessGeneration(), completedResumeCommand.targetRetryBindingId());
        retryContext.requireContinuationIdentity(nextCommand.retryCheckpointId(),
                nextCommand.businessGeneration(), nextCommand.targetRetryBindingId());
        retryContext.requireSourcePlanDigest(policyRun.planDigest());
    }

    /** Compatibility constructor for normal policy operations, with explicit empty retry context. */
    public RequirementPolicyResumeResult(
            RequirementPolicyRun policyRun, RequirementStageCommand completedResumeCommand,
            RequirementStageCommand nextCommand, long taskVersion, long fencingToken
    ) {
        this(policyRun, completedResumeCommand, nextCommand, taskVersion, fencingToken,
                RequirementPolicyRetryContext.empty());
    }
}
