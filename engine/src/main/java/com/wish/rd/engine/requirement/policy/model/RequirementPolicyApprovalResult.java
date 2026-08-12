package com.wish.rd.engine.requirement.policy.model;

import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;

/**
 * Durable result of approving a waiting policy run and enqueuing its exact resume command.
 *
 * <p>Callers receive the post-CAS task concurrency pair only from this transaction result, never
 * by inferring it from the old approval request.
 */
public record RequirementPolicyApprovalResult(
        RequirementPolicyRun policyRun,
        RequirementStageCommand approvalResumeCommand,
        long taskVersion,
        long fencingToken,
        RequirementPolicyRetryContext retryContext
) {
    /** Ensures the returned command and task concurrency pair remain inseparable. */
    public RequirementPolicyApprovalResult {
        retryContext = java.util.Objects.requireNonNull(retryContext, "retryContext must not be null");
        if (policyRun == null || approvalResumeCommand == null) {
            throw new IllegalArgumentException("policyRun and approvalResumeCommand must not be null");
        }
        if (taskVersion < 0L || fencingToken <= 0L) {
            throw new IllegalArgumentException("taskVersion must be nonnegative and fencingToken positive");
        }
        if (!policyRun.taskId().equals(approvalResumeCommand.taskId())
                || !policyRun.id().equals(approvalResumeCommand.policyRunId())
                || !policyRun.approvalResumeCommandId().equals(approvalResumeCommand.commandId())
                || !"WAITING_APPROVAL".equals(policyRun.policyAction())
                || !"REQUIREMENT_DELIVERY".equals(approvalResumeCommand.role())
                || !"APPROVAL_RESUME".equals(approvalResumeCommand.stage())
                || approvalResumeCommand.taskVersion() != taskVersion
                || approvalResumeCommand.fencingToken() != fencingToken
                || policyRun.approvalExpectedTaskVersion() == null
                || policyRun.approvalExpectedFencingToken() == null
                || taskVersion != Math.addExact(policyRun.approvalExpectedTaskVersion(), 1L)
                || fencingToken != Math.addExact(policyRun.approvalExpectedFencingToken(), 1L)) {
            throw new IllegalArgumentException("approval result must bind the ledger and exact resume command identity");
        }
        boolean pendingApproval = policyRun.state() == RequirementPolicyRunState.APPROVED
                && policyRun.boundTaskVersion() == taskVersion
                && policyRun.boundFencingToken() == fencingToken
                && (approvalResumeCommand.status() == RequirementStageCommand.Status.PENDING
                || approvalResumeCommand.status() == RequirementStageCommand.Status.RUNNING
                || approvalResumeCommand.status() == RequirementStageCommand.Status.FAILED_RETRYABLE);
        boolean consumedApproval = policyRun.state() == RequirementPolicyRunState.APPLIED
                && policyRun.boundTaskVersion() == Math.addExact(taskVersion, 1L)
                && policyRun.boundFencingToken() == Math.addExact(fencingToken, 1L)
                && approvalResumeCommand.status() == RequirementStageCommand.Status.SUCCEEDED;
        if (!pendingApproval && !consumedApproval) {
            throw new IllegalArgumentException("approval result ledger state and resume status are inconsistent");
        }
        retryContext.requireControlCommandIdentity(approvalResumeCommand.retryCheckpointId(),
                approvalResumeCommand.businessGeneration(), approvalResumeCommand.targetRetryBindingId());
        retryContext.requireSourcePlanDigest(policyRun.planDigest());
    }

    /** Compatibility constructor for normal policy operations, with explicit empty retry context. */
    public RequirementPolicyApprovalResult(
            RequirementPolicyRun policyRun, RequirementStageCommand approvalResumeCommand,
            long taskVersion, long fencingToken
    ) {
        this(policyRun, approvalResumeCommand, taskVersion, fencingToken, RequirementPolicyRetryContext.empty());
    }
}
