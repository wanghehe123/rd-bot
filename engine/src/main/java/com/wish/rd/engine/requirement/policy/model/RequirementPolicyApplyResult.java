package com.wish.rd.engine.requirement.policy.model;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.FairRequirementDeliveryClaimPlanner;

/**
 * Exact outcome of atomically consuming one leased {@code POLICY_APPLY} command.
 *
 * <p>Only {@link RequirementPolicyApplyDisposition#ALLOWED} carries a runnable continuation;
 * the other dispositions deliberately expose no command for a dispatcher to schedule.
 */
public record RequirementPolicyApplyResult(
        RequirementPolicyRun policyRun,
        RequirementStageCommand completedApplyCommand,
        RequirementPolicyApplyDisposition disposition,
        RequirementStageCommand nextCommand,
        long taskVersion,
        long fencingToken,
        RequirementPolicyRetryContext retryContext
) {
    /** Validates the ledger, completed control command, and disposition-specific continuation. */
    public RequirementPolicyApplyResult {
        retryContext = java.util.Objects.requireNonNull(retryContext, "retryContext must not be null");
        if (policyRun == null || completedApplyCommand == null || disposition == null) {
            throw new IllegalArgumentException("policyRun, completedApplyCommand, and disposition must not be null");
        }
        if (taskVersion < 0L || fencingToken <= 0L
                || !policyRun.taskId().equals(completedApplyCommand.taskId())
                || !policyRun.id().equals(completedApplyCommand.policyRunId())
                || completedApplyCommand.status() != RequirementStageCommand.Status.SUCCEEDED
                || !"REQUIREMENT_DELIVERY".equals(completedApplyCommand.role())
                || !"POLICY_APPLY".equals(completedApplyCommand.stage())
                || completedApplyCommand.taskVersion() != Math.addExact(policyRun.sourceTaskVersion(), 1L)
                || completedApplyCommand.fencingToken() != Math.addExact(policyRun.sourceFencingToken(), 1L)
                || policyRun.boundTaskVersion() != taskVersion
                || policyRun.boundFencingToken() != fencingToken
                || taskVersion != Math.addExact(policyRun.sourceTaskVersion(), 2L)
                || fencingToken != Math.addExact(policyRun.sourceFencingToken(), 2L)) {
            throw new IllegalArgumentException("policy-apply result identities must match the consumed ledger generation");
        }
        switch (disposition) {
            case ALLOWED -> requireAllowed(policyRun, completedApplyCommand, nextCommand);
            case WAITING_APPROVAL -> requireWaitingApproval(policyRun, nextCommand);
            case DENIED -> requireDenied(policyRun, completedApplyCommand, nextCommand);
        }
        retryContext.requireControlCommandIdentity(completedApplyCommand.retryCheckpointId(),
                completedApplyCommand.businessGeneration(), completedApplyCommand.targetRetryBindingId());
        if (nextCommand != null) {
            retryContext.requireContinuationIdentity(nextCommand.retryCheckpointId(),
                    nextCommand.businessGeneration(), nextCommand.targetRetryBindingId());
        }
        retryContext.requireSourcePlanDigest(policyRun.planDigest());
    }

    /** Compatibility constructor for normal policy operations, with explicit empty retry context. */
    public RequirementPolicyApplyResult(
            RequirementPolicyRun policyRun, RequirementStageCommand completedApplyCommand,
            RequirementPolicyApplyDisposition disposition, RequirementStageCommand nextCommand,
            long taskVersion, long fencingToken
    ) {
        this(policyRun, completedApplyCommand, disposition, nextCommand, taskVersion, fencingToken,
                RequirementPolicyRetryContext.empty());
    }

    private static void requireAllowed(
            RequirementPolicyRun policyRun, RequirementStageCommand completed, RequirementStageCommand next
    ) {
        String firstRole = AgentRole.requirementDeliveryOrder().getFirst().name();
        if (policyRun.state() != RequirementPolicyRunState.APPLIED
                || !"ALLOWED".equals(policyRun.policyAction())
                || !policyRun.consumedByCommandId().equals(completed.commandId())
                || next == null
                || !policyRun.taskId().equals(next.taskId())
                || !policyRun.id().equals(next.policyRunId())
                || next.status() != RequirementStageCommand.Status.PENDING
                || next.attemptNo() != 0
                || !next.leaseOwner().isBlank()
                || next.leaseUntilEpochMillis() != 0L
                || next.deadlineEpochMillis() <= 0L
                || !firstRole.equals(next.role())
                || !("ROLE_EXECUTION:" + firstRole).equals(next.stage())
                || next.taskVersion() != policyRun.boundTaskVersion()
                || next.fencingToken() != policyRun.boundFencingToken()
                || !next.resourceRequirements().equals(
                        FairRequirementDeliveryClaimPlanner.classifyStageRequirements(next.role(), next.stage()))
                || !next.resourceRequirements().contains(next.resourceClass())
                || next.projectId().isBlank()
                || next.providerId().isBlank()) {
            throw new IllegalArgumentException("allowed policy-apply result must carry its exact first continuation");
        }
    }

    private static void requireWaitingApproval(RequirementPolicyRun policyRun, RequirementStageCommand next) {
        if (policyRun.state() != RequirementPolicyRunState.WAITING_APPROVAL
                || !"WAITING_APPROVAL".equals(policyRun.policyAction()) || next != null) {
            throw new IllegalArgumentException("waiting policy-apply result must not carry a continuation");
        }
    }

    private static void requireDenied(
            RequirementPolicyRun policyRun, RequirementStageCommand completed, RequirementStageCommand next
    ) {
        if (policyRun.state() != RequirementPolicyRunState.DENIED
                || !("NEED_INFO".equals(policyRun.policyAction()) || "UNSAFE".equals(policyRun.policyAction()))
                || !policyRun.consumedByCommandId().equals(completed.commandId()) || next != null) {
            throw new IllegalArgumentException("denied policy-apply result must bind its consuming command and no continuation");
        }
    }
}
