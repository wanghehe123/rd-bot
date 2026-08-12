package com.wish.rd.engine.requirement.policy.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Contract tests for the immutable policy-run ledger model. */
class RequirementPolicyRunContractTest {

    @Test
    void canonicalPolicyDigestsUseRfc8785JcsBeforeSha256() throws Exception {
        Class<?> model = requireClass("com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun");
        Method digest = model.getMethod("canonicalJsonDigest", String.class);

        assertEquals(
                "sha256:43258cff783fe7036d8a43033f830adfc60ec037382473548ac742b888292777",
                invokeDigest(digest, "{\"b\":2,\"a\":1}")
        );
        assertEquals(
                "sha256:7c892d3452ad85ad65857a43e8dcac93b79475d2334fc3e85bac5c599142c158",
                invokeDigest(digest, "{\"numbers\":[333333333.33333329,1E30,4.50,2e-3,0.000000000000000000000000001]}")
        );
    }

    @Test
    void policyRunStatesExposeTheTerminalSupersessionLifecycle() throws Exception {
        Class<?> state = requireClass("com.wish.rd.engine.requirement.policy.model.RequirementPolicyRunState");

        assertEquals(
                java.util.Set.of("PLAN_READY", "POLICY_DECIDED", "WAITING_APPROVAL", "APPROVED", "APPLIED", "DENIED", "SUPERSEDED"),
                Arrays.stream(state.getEnumConstants()).map(Object::toString).collect(java.util.stream.Collectors.toSet())
        );
    }

    @Test
    void retryContextIsExplicitlyEmptyForNormalPolicyAndCompleteForCheckpointPolicyRetry() {
        assertTrue(RequirementPolicyRetryContext.empty().isEmpty());
        RequirementPolicyRetryContext retry = new RequirementPolicyRetryContext(
                "101", 101L, "binding-1", "policy-1", "sha256:" + "a".repeat(64),
                com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus.DISPATCHED);
        assertDoesNotThrow(() -> retry.requireControlCommandIdentity("101", 101L, ""));
        assertDoesNotThrow(() -> retry.requireContinuationIdentity("101", 101L, "binding-1"));
        assertThrows(IllegalArgumentException.class,
                () -> retry.requireControlCommandIdentity("102", 101L, ""));
        assertThrows(IllegalArgumentException.class,
                () -> retry.requireContinuationIdentity("101", 101L, "binding-other"));
        String plan = "{\"plan\":true}";
        String policy = "{\"policyAction\":\"ALLOWED\"}";
        assertTrue(new RequirementPolicyEvaluationProposal(
                "task-1", 1L, 1L, plan, RequirementPolicyRun.canonicalJsonDigest(plan), policy,
                RequirementPolicyRun.canonicalJsonDigest(policy), "ALLOWED").retryContext().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new RequirementPolicyRetryContext(
                "101", 0L, "binding-1", "policy-1", "sha256:" + "a".repeat(64),
                com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus.DISPATCHED));
    }

    @Test
    void supersessionPreservesOnlyEligibleActiveAuditSnapshots() {
        for (RequirementPolicyRun active : java.util.List.of(
                activePlan(), activeDecided(), activeWaitingApproval(), activeApproved())) {
            assertDoesNotThrow(() -> active.superseded(10L));
            assertEquals(RequirementPolicyRunState.SUPERSEDED, active.superseded(10L).state());
        }
        RequirementPolicyRun approved = activeApproved();
        RequirementPolicyRun supersededApproved = approved.superseded(10L);
        assertEquals(approved.approvalRequestId(), supersededApproved.approvalRequestId());
        assertEquals(approved.approvedBy(), supersededApproved.approvedBy());
        assertEquals(approved.approvalResumeCommandId(), supersededApproved.approvalResumeCommandId());
        assertEquals(approved.boundTaskVersion(), supersededApproved.boundTaskVersion());
        assertEquals(approved.boundFencingToken(), supersededApproved.boundFencingToken());
        assertThrows(IllegalStateException.class, () -> activeApplied().superseded(10L));
        assertThrows(IllegalStateException.class, () -> activeDenied().superseded(10L));
        assertThrows(IllegalStateException.class, () -> activePlan().superseded(10L).superseded(11L));
    }

    @Test
    void canonicalPolicyDigestsRejectInvalidAndNonIJsonInputs() throws Exception {
        Class<?> model = requireClass("com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun");
        Method digest = model.getMethod("canonicalJsonDigest", String.class);

        assertThrows(IllegalArgumentException.class, () -> invokeDigest(digest, "{\"a\":1,\"a\":2}"));
        assertThrows(IllegalArgumentException.class, () -> invokeDigest(digest, "{\"a\":NaN}"));
        assertThrows(IllegalArgumentException.class, () -> invokeDigest(digest, "{\"a\":\\\"unterminated}"));
    }

    @Test
    void stateSpecificLedgerInvariantsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> run(RequirementPolicyRunState.PLAN_READY,
                "{\"action\":\"WAITING_APPROVAL\"}", "WAITING_APPROVAL", "request", "host", 1L, "", 0L));
        assertThrows(IllegalArgumentException.class, () -> run(RequirementPolicyRunState.WAITING_APPROVAL,
                "{\"action\":\"ALLOWED\"}", "ALLOWED", "", "", 0L, "", 0L));
        assertThrows(IllegalArgumentException.class, () -> run(RequirementPolicyRunState.APPROVED,
                "{\"action\":\"WAITING_APPROVAL\"}", "WAITING_APPROVAL", "", "host", 1L, "", 0L));
        assertThrows(IllegalArgumentException.class, () -> run(RequirementPolicyRunState.APPLIED,
                "{\"action\":\"WAITING_APPROVAL\"}", "WAITING_APPROVAL", "request", "host", 1L, "", 0L));
        String plan = "{\"plan\":true}";
        String allowed = "{\"action\":\"ALLOWED\"}";
        assertDoesNotThrow(() -> new RequirementPolicyRun(
                "1", "2", 0L, 1L, plan, RequirementPolicyRun.canonicalJsonDigest(plan),
                allowed, RequirementPolicyRun.canonicalJsonDigest(allowed), "ALLOWED",
                RequirementPolicyRunState.APPLIED, 2L, 3L,
                null, null, "", "", "", 0L, "", "command", 1L, 0L, 1L, 1L));
    }

    @Test
    void approvalStatesPreserveTheOriginalExpectedTaskConcurrencyPair() {
        assertEquals(
                java.util.List.of("approvalExpectedTaskVersion", "approvalExpectedFencingToken"),
                Arrays.stream(RequirementPolicyRun.class.getRecordComponents())
                        .map(component -> component.getName())
                        .filter(name -> name.startsWith("approvalExpected"))
                        .toList()
        );
    }

    @Test
    void approvalBackedStatesRequireWaitingActionAndTheExactDerivedConcurrencyPair() {
        assertThrows(IllegalArgumentException.class, () -> approvalBacked(
                RequirementPolicyRunState.APPROVED, "ALLOWED", 6L, 8L, "", 0L));
        assertThrows(IllegalArgumentException.class, () -> approvalBacked(
                RequirementPolicyRunState.APPROVED, "WAITING_APPROVAL", 7L, 8L, "", 0L));
        assertThrows(IllegalArgumentException.class, () -> approvalBacked(
                RequirementPolicyRunState.APPLIED, "ALLOWED", 7L, 9L, "901", 2L));
        assertThrows(IllegalArgumentException.class, () -> approvalBacked(
                RequirementPolicyRunState.APPLIED, "WAITING_APPROVAL", 7L, 10L, "901", 2L));
        assertDoesNotThrow(() -> approvalBacked(
                RequirementPolicyRunState.APPLIED, "WAITING_APPROVAL", 7L, 9L, "901", 2L));
    }

    private static RequirementPolicyRun run(
            RequirementPolicyRunState state, String policy, String action, String request, String actor,
            long approvedAt, String consumedCommand, long consumedAt
    ) {
        String plan = "{\"plan\":true}";
        return new RequirementPolicyRun(
                "1", "2", 3L, 5L, plan, RequirementPolicyRun.canonicalJsonDigest(plan), policy,
                policy.isBlank() ? "" : RequirementPolicyRun.canonicalJsonDigest(policy), action, state,
                0L, 1L, request.isBlank() ? null : 0L, request.isBlank() ? null : 1L,
                request, actor, "note", approvedAt, request.isBlank() ? "" : "901", consumedCommand, consumedAt, 0L, 1L, 1L);
    }

    private static RequirementPolicyRun approvalBacked(
            RequirementPolicyRunState state, String action, long boundVersion, long boundFence,
            String consumedCommand, long consumedAt
    ) {
        String plan = "{\"plan\":true}";
        String policy = "{\"action\":\"" + action + "\"}";
        return new RequirementPolicyRun(
                "1", "2", 3L, 5L, plan, RequirementPolicyRun.canonicalJsonDigest(plan), policy,
                RequirementPolicyRun.canonicalJsonDigest(policy), action, state,
                boundVersion, boundFence, 5L, 7L, "request", "host", "note", 1L,
                "901", consumedCommand, consumedAt, 1L, 1L, 1L);
    }

    private static RequirementPolicyRun activePlan() {
        String plan = "{\"plan\":true}";
        return new RequirementPolicyRun("1", "2", 3L, 5L, plan, RequirementPolicyRun.canonicalJsonDigest(plan),
                "", "", "", RequirementPolicyRunState.PLAN_READY, 3L, 5L, null, null,
                "", "", "", 0L, "", "", 0L, 0L, 1L, 1L);
    }

    private static RequirementPolicyRun activeDecided() {
        String policy = "{\"action\":\"ALLOWED\"}";
        RequirementPolicyRun plan = activePlan();
        return new RequirementPolicyRun("1", "2", 3L, 5L, plan.planJson(), plan.planDigest(),
                policy, RequirementPolicyRun.canonicalJsonDigest(policy), "ALLOWED",
                RequirementPolicyRunState.POLICY_DECIDED, 4L, 6L, null, null,
                "", "", "", 0L, "", "", 0L, 1L, 1L, 2L);
    }

    private static RequirementPolicyRun activeWaitingApproval() {
        String policy = "{\"action\":\"WAITING_APPROVAL\"}";
        RequirementPolicyRun plan = activePlan();
        return new RequirementPolicyRun("1", "2", 3L, 5L, plan.planJson(), plan.planDigest(),
                policy, RequirementPolicyRun.canonicalJsonDigest(policy), "WAITING_APPROVAL",
                RequirementPolicyRunState.WAITING_APPROVAL, 5L, 7L, null, null,
                "", "", "", 0L, "", "", 0L, 2L, 1L, 3L);
    }

    private static RequirementPolicyRun activeApproved() {
        RequirementPolicyRun waiting = activeWaitingApproval();
        return new RequirementPolicyRun("1", "2", 3L, 5L, waiting.planJson(), waiting.planDigest(),
                waiting.policyJson(), waiting.policyDigest(), waiting.policyAction(),
                RequirementPolicyRunState.APPROVED, 6L, 8L, 5L, 7L,
                "approval", "host", "approved", 1L, "resume", "", 0L, 3L, 1L, 4L);
    }

    private static RequirementPolicyRun activeApplied() {
        RequirementPolicyRun decided = activeDecided();
        return new RequirementPolicyRun("1", "2", 3L, 5L, decided.planJson(), decided.planDigest(),
                decided.policyJson(), decided.policyDigest(), decided.policyAction(),
                RequirementPolicyRunState.APPLIED, 5L, 7L, null, null,
                "", "", "", 0L, "", "apply", 1L, 2L, 1L, 3L);
    }

    private static RequirementPolicyRun activeDenied() {
        String policy = "{\"action\":\"UNSAFE\"}";
        RequirementPolicyRun plan = activePlan();
        return new RequirementPolicyRun("1", "2", 3L, 5L, plan.planJson(), plan.planDigest(),
                policy, RequirementPolicyRun.canonicalJsonDigest(policy), "UNSAFE",
                RequirementPolicyRunState.DENIED, 5L, 7L, null, null,
                "", "", "", 0L, "", "apply", 1L, 2L, 1L, 3L);
    }

    private static Class<?> requireClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException missing) {
            fail("missing policy-ledger model " + className, missing);
            throw new AssertionError("unreachable");
        }
    }

    private static String invokeDigest(Method digest, String json) throws Exception {
        try {
            return (String) digest.invoke(null, json);
        } catch (InvocationTargetException failed) {
            throw (Exception) failed.getCause();
        }
    }
}
