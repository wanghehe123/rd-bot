package com.wish.rd.engine.requirement.policy.impl;

import com.wish.rd.engine.requirement.policy.RequirementPolicyRunStore;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRunState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Single-runtime implementation of the immutable policy-ledger persistence contract. */
public final class InMemoryRequirementPolicyRunStore implements RequirementPolicyRunStore {
    private final Map<String, RequirementPolicyRun> byId = new LinkedHashMap<>();

    @Override
    public synchronized RequirementPolicyRun createOrGet(RequirementPolicyRun run) {
        require(run);
        RequirementPolicyRun sameId = byId.get(run.id());
        if (sameId != null) {
            requireExact(sameId, run, "policy run id");
            return sameId;
        }
        for (RequirementPolicyRun existing : byId.values()) {
            if (sameGeneration(existing, run)) {
                requireExact(existing, run, "policy generation");
                return existing;
            }
        }
        if (run.state() != RequirementPolicyRunState.PLAN_READY) {
            throw new IllegalStateException("new policy generation must start at PLAN_READY");
        }
        requireUnique(run, null);
        byId.put(run.id(), run);
        return run;
    }

    @Override
    public synchronized Optional<RequirementPolicyRun> findById(String policyRunId) {
        return Optional.ofNullable(byId.get(safe(policyRunId)));
    }

    @Override
    public synchronized Optional<RequirementPolicyRun> findActiveByTask(String taskId) {
        return byId.values().stream()
                .filter(run -> run.taskId().equals(safe(taskId)))
                .filter(run -> run.state().isActive())
                .findFirst();
    }

    @Override
    public synchronized RequirementPolicyRun compareAndSet(
            RequirementPolicyRun next, RequirementPolicyRunState expectedState, long expectedLedgerVersion
    ) {
        require(next);
        RequirementPolicyRun current = byId.get(next.id());
        if (current == null || current.state() != expectedState || current.ledgerVersion() != expectedLedgerVersion) {
            throw new IllegalStateException("policy ledger compare-and-set failed: " + next.id());
        }
        requireImmutableTransition(current, next);
        requireAllowed(current.state(), next.state());
        if (next.ledgerVersion() != current.ledgerVersion() + 1L) {
            throw new IllegalStateException("policy ledger version must advance by one");
        }
        requireUnique(next, current.id());
        byId.put(next.id(), next);
        return next;
    }

    @Override
    public synchronized RequirementPolicyRun supersedeForPolicyRetry(
            com.wish.rd.engine.requirement.policy.model.RequirementPolicyRetryContext context,
            long expectedLedgerVersion,
            long nowEpochMillis
    ) {
        if (context == null || context.isEmpty()) {
            throw new IllegalArgumentException("policy retry context must be complete");
        }
        RequirementPolicyRun current = byId.get(context.sourcePolicyRunId());
        if (current == null || current.ledgerVersion() != expectedLedgerVersion
                || !current.planDigest().equals(context.sourcePlanDigest()) || !current.state().canBeSuperseded()) {
            throw new IllegalStateException("policy retry source cannot be superseded: " + context.sourcePolicyRunId());
        }
        RequirementPolicyRun superseded = current.superseded(nowEpochMillis);
        requireUnique(superseded, current.id());
        byId.put(superseded.id(), superseded);
        return superseded;
    }

    private void requireUnique(RequirementPolicyRun candidate, String excludedId) {
        if (candidate.state().isActive() && byId.values().stream().anyMatch(run -> !run.id().equals(excludedId)
                && run.taskId().equals(candidate.taskId()) && run.state().isActive())) {
            throw new IllegalStateException("task already has an active policy generation: " + candidate.taskId());
        }
        if (!candidate.approvalRequestId().isBlank() && byId.values().stream().anyMatch(run -> !run.id().equals(excludedId)
                && run.taskId().equals(candidate.taskId()) && run.approvalRequestId().equals(candidate.approvalRequestId()))) {
            throw new IllegalStateException("approval request is already bound to task: " + candidate.approvalRequestId());
        }
        if (!candidate.consumedByCommandId().isBlank() && byId.values().stream().anyMatch(run -> !run.id().equals(excludedId)
                && run.consumedByCommandId().equals(candidate.consumedByCommandId()))) {
            throw new IllegalStateException("stage command already consumed a policy run: " + candidate.consumedByCommandId());
        }
        if (!candidate.approvalResumeCommandId().isBlank() && byId.values().stream().anyMatch(run -> !run.id().equals(excludedId)
                && run.approvalResumeCommandId().equals(candidate.approvalResumeCommandId()))) {
            throw new IllegalStateException("approval resume command is already bound to a policy run: "
                    + candidate.approvalResumeCommandId());
        }
    }

    private static void requireImmutableTransition(RequirementPolicyRun current, RequirementPolicyRun next) {
        if (!sameGeneration(current, next) || !current.planJson().equals(next.planJson())
                || !current.planDigest().equals(next.planDigest())) {
            throw new IllegalStateException("policy plan snapshot is immutable");
        }
        if (!current.policyJson().isBlank() && (!current.policyJson().equals(next.policyJson())
                || !current.policyDigest().equals(next.policyDigest())
                || !current.policyAction().equals(next.policyAction()))) {
            throw new IllegalStateException("once-set policy snapshot is immutable");
        }
        if (current.approvalExpectedTaskVersion() != null
                && (!current.approvalExpectedTaskVersion().equals(next.approvalExpectedTaskVersion())
                || !current.approvalExpectedFencingToken().equals(next.approvalExpectedFencingToken()))) {
            throw new IllegalStateException("approval expected task concurrency is immutable");
        }
        if (!current.approvalResumeCommandId().isBlank()
                && !current.approvalResumeCommandId().equals(next.approvalResumeCommandId())) {
            throw new IllegalStateException("approval resume command identity is immutable");
        }
        if ((!current.approvalRequestId().isBlank() && !current.approvalRequestId().equals(next.approvalRequestId()))
                || (!current.approvedBy().isBlank() && !current.approvedBy().equals(next.approvedBy()))
                || (!current.note().isBlank() && !current.note().equals(next.note()))
                || (current.approvedAtEpochMillis() > 0L && current.approvedAtEpochMillis() != next.approvedAtEpochMillis())
                || (!current.consumedByCommandId().isBlank() && !current.consumedByCommandId().equals(next.consumedByCommandId()))
                || (current.consumedAtEpochMillis() > 0L && current.consumedAtEpochMillis() != next.consumedAtEpochMillis())
                || current.createdAtEpochMillis() != next.createdAtEpochMillis()) {
            throw new IllegalStateException("once-set policy audit metadata is immutable");
        }
    }

    private static void requireAllowed(RequirementPolicyRunState from, RequirementPolicyRunState to) {
        boolean allowed = switch (from) {
            case PLAN_READY -> to == RequirementPolicyRunState.POLICY_DECIDED;
            case POLICY_DECIDED -> to == RequirementPolicyRunState.WAITING_APPROVAL || to == RequirementPolicyRunState.DENIED
                    || to == RequirementPolicyRunState.APPLIED;
            case WAITING_APPROVAL -> to == RequirementPolicyRunState.APPROVED || to == RequirementPolicyRunState.DENIED;
            case APPROVED -> to == RequirementPolicyRunState.APPLIED;
            case APPLIED, DENIED, SUPERSEDED -> false;
        };
        if (!allowed) {
            throw new IllegalStateException("invalid policy ledger transition: " + from + " -> " + to);
        }
    }

    private static boolean sameGeneration(RequirementPolicyRun left, RequirementPolicyRun right) {
        return left.taskId().equals(right.taskId()) && left.sourceTaskVersion() == right.sourceTaskVersion()
                && left.sourceFencingToken() == right.sourceFencingToken();
    }

    private static void requireExact(RequirementPolicyRun expected, RequirementPolicyRun actual, String identity) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException("conflicting " + identity + " insert");
        }
    }

    private static void require(RequirementPolicyRun run) {
        if (run == null) {
            throw new IllegalArgumentException("policy run must not be null");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
