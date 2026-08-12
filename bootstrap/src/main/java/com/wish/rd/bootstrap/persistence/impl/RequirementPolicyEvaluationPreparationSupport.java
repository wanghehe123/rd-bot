package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRunState;

/** Shared immutable PLAN_READY ledger construction and replay validation for PostgreSQL producers. */
final class RequirementPolicyEvaluationPreparationSupport {

    private RequirementPolicyEvaluationPreparationSupport() {
    }

    static RequirementPolicyRun planReadyLedger(
            RequirementStageCommand command, String planJson, String planDigest, long nowEpochMillis
    ) {
        return new RequirementPolicyRun(
                command.policyRunId(), command.taskId(), command.taskVersion(), command.fencingToken(),
                planJson, planDigest, "", "", "", RequirementPolicyRunState.PLAN_READY,
                command.taskVersion(), command.fencingToken(), null, null,
                "", "", "", 0L, "", "", 0L, 0L, nowEpochMillis, nowEpochMillis);
    }

    static boolean samePreparedLedgerGeneration(RequirementPolicyRun actual, RequirementPolicyRun expected) {
        return actual.state() == RequirementPolicyRunState.PLAN_READY
                && actual.id().equals(expected.id())
                && actual.taskId().equals(expected.taskId())
                && actual.sourceTaskVersion() == expected.sourceTaskVersion()
                && actual.sourceFencingToken() == expected.sourceFencingToken()
                && actual.boundTaskVersion() == expected.boundTaskVersion()
                && actual.boundFencingToken() == expected.boundFencingToken()
                && actual.planDigest().equals(expected.planDigest())
                && RequirementPolicyRun.canonicalizeJson(actual.planJson()).equals(expected.planJson())
                && actual.policyJson().isBlank()
                && actual.policyDigest().isBlank()
                && actual.policyAction().isBlank()
                && actual.approvalRequestId().isBlank()
                && actual.approvalResumeCommandId().isBlank()
                && actual.consumedByCommandId().isBlank();
    }
}
