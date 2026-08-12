package com.wish.rd.engine.requirement.policy.impl;

import com.wish.rd.engine.requirement.policy.RequirementPolicyRunStore;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRunState;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRetryContext;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Single-runtime contract for immutable policy-run generations and CAS transitions. */
class InMemoryRequirementPolicyRunStoreTest {

    @Test
    void createsOneGenerationIdempotentlyAndRejectsConflictingGeneration() {
        RequirementPolicyRunStore store = new InMemoryRequirementPolicyRunStore();
        RequirementPolicyRun first = plan("1", "2", "{\"plan\":true}");

        assertEquals(first, store.createOrGet(first));
        assertEquals(first, store.createOrGet(first));
        assertThrows(IllegalStateException.class, () -> store.createOrGet(plan("9", "2", "{\"plan\":false}")));
    }

    @Test
    void compareAndSetRejectsStaleOrMutatedPlanAndPreservesOnceSetPolicy() {
        RequirementPolicyRunStore store = new InMemoryRequirementPolicyRunStore();
        RequirementPolicyRun plan = store.createOrGet(plan("1", "2", "{\"plan\":true}"));
        RequirementPolicyRun decided = policy(plan, "{\"action\":\"WAITING_APPROVAL\"}", 1L);

        assertEquals(decided, store.compareAndSet(decided, RequirementPolicyRunState.PLAN_READY, 0L));
        assertThrows(IllegalStateException.class, () -> store.compareAndSet(decided, RequirementPolicyRunState.PLAN_READY, 0L));
        assertThrows(IllegalStateException.class, () -> store.compareAndSet(
                policy(plan("1", "2", "{\"plan\":false}"), "{\"action\":\"WAITING_APPROVAL\"}", 2L),
                RequirementPolicyRunState.POLICY_DECIDED, 1L));
    }

    @Test
    void supersedesOnlyActiveGenerationsAndAllowsTheirTaskToReceiveANewGeneration() {
        RequirementPolicyRunStore store = new InMemoryRequirementPolicyRunStore();
        RequirementPolicyRun plan = store.createOrGet(plan("1", "2", "{\"plan\":true}"));
        RequirementPolicyRetryContext context = new RequirementPolicyRetryContext(
                "101", 101L, "binding-1", plan.id(), plan.planDigest(), TaskRetryCheckpointStatus.DISPATCHED);

        RequirementPolicyRun superseded = store.supersedeForPolicyRetry(context, plan.ledgerVersion(), 2L);
        assertEquals(java.util.Optional.empty(), store.findActiveByTask("2"));
        RequirementPolicyRun replacement = new RequirementPolicyRun(
                "2", "2", 1L, 2L, "{\"plan\":false}",
                RequirementPolicyRun.canonicalJsonDigest("{\"plan\":false}"), "", "", "",
                RequirementPolicyRunState.PLAN_READY, 1L, 2L, null, null,
                "", "", "", 0L, "", "", 0L, 0L, 3L, 3L);
        assertEquals(replacement, store.createOrGet(replacement));
        assertThrows(IllegalStateException.class, () -> store.compareAndSet(
                superseded, RequirementPolicyRunState.SUPERSEDED, superseded.ledgerVersion()));
    }

    private static RequirementPolicyRun plan(String id, String taskId, String plan) {
        return new RequirementPolicyRun(id, taskId, 0L, 1L, plan, RequirementPolicyRun.canonicalJsonDigest(plan),
                "", "", "", RequirementPolicyRunState.PLAN_READY, 0L, 1L, null, null,
                "", "", "", 0L, "", "", 0L, 0L, 1L, 1L);
    }

    private static RequirementPolicyRun policy(RequirementPolicyRun run, String policy, long version) {
        return new RequirementPolicyRun(run.id(), run.taskId(), run.sourceTaskVersion(), run.sourceFencingToken(),
                run.planJson(), run.planDigest(), policy, RequirementPolicyRun.canonicalJsonDigest(policy),
                "WAITING_APPROVAL", RequirementPolicyRunState.POLICY_DECIDED,
                Math.addExact(run.sourceTaskVersion(), 1L), Math.addExact(run.sourceFencingToken(), 1L),
                null, null, "", "", "", 0L, "", "", 0L, version,
                run.createdAtEpochMillis(), 2L);
    }
}
