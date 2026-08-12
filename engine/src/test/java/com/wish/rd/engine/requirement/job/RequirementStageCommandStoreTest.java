package com.wish.rd.engine.requirement.job;

import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.model.FairScheduleLimits;
import com.wish.rd.engine.scheduling.model.RequirementDeliverySchedulingPolicy;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementStageCommandStoreTest {

    @Test
    void authorizedRoleCommandPreservesItsPolicyRunAcrossLeaseLifecycle() {
        RequirementStageCommand pending = RequirementStageCommand.pending(
                "authorized-role", "authorized-task", 7L, 9L,
                "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 0, 3, 60_000L,
                ScheduleResourceClass.PROVIDER, Set.of(ScheduleResourceClass.PROVIDER),
                "project-a", "provider-a", "P1", "policy-run-1", 1L);

        assertEquals("policy-run-1", pending.policyRunId());
        assertEquals("policy-run-1", pending.claimed("worker", 100L, 2L).policyRunId());
        assertEquals("policy-run-1", pending.claimed("worker", 100L, 2L)
                .failed("retry", 3L).policyRunId());
    }

    @Test
    void findsCommandByDurableId() {
        InMemoryRequirementStageCommandStore store = new InMemoryRequirementStageCommandStore();
        store.enqueue(command("lookup", "P1", 100L, 3));

        assertEquals("lookup", store.findById("lookup").orElseThrow().commandId());
        assertTrue(store.findById("missing").isEmpty());
    }

    @Test
    void rejectsLegacyZeroFenceBeforeAnyInMemoryStateWrite() {
        InMemoryRequirementStageCommandStore store = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand legacyRead = legacyZeroFenceCommand("legacy-zero");

        assertEquals(0L, legacyRead.fencingToken());
        assertThrows(IllegalArgumentException.class, () -> store.enqueue(legacyRead));
        assertTrue(store.findById(legacyRead.commandId()).isEmpty());
        assertTrue(store.listByStatus(RequirementStageCommand.Status.PENDING).isEmpty());
    }

    @Test
    void rejectsConflictingExistingTaskRoleStageIdentityInsteadOfSilentlyReusingIt() {
        InMemoryRequirementStageCommandStore store = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand existing = RequirementStageCommand.pending(
                "existing", "shared-task", 4L, 9L, "CODING_AGENT", "EXECUTE", 0, 3,
                60_000L, ScheduleResourceClass.PROVIDER, "project-a", "provider-a", "P1", 1L);
        RequirementStageCommand conflicting = RequirementStageCommand.pending(
                "conflicting", "shared-task", 5L, 10L, "CODING_AGENT", "EXECUTE", 0, 3,
                60_000L, ScheduleResourceClass.PROVIDER, "project-a", "provider-a", "P1", 2L);
        store.enqueue(existing);

        assertThrows(IllegalStateException.class, () -> store.enqueue(conflicting));

        assertEquals(existing, store.find("shared-task", "CODING_AGENT", "EXECUTE").orElseThrow());
        assertTrue(store.findById("conflicting").isEmpty());
    }

    @Test
    void keepsTerminalNormalCommandAndNewRetryGenerationAsSeparateIdentities() {
        InMemoryRequirementStageCommandStore store = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand normal = RequirementStageCommand.pending(
                "normal", "shared-task", 4L, 9L, "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 0, 1,
                60_000L, ScheduleResourceClass.PROVIDER, "project-a", "provider-a", "P1", 1L);
        RequirementStageCommand retry = RequirementStageCommand.pending(
                "retry", "shared-task", 5L, 10L, "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 0, 3,
                60_000L, ScheduleResourceClass.PROVIDER, Set.of(ScheduleResourceClass.PROVIDER),
                "project-a", "provider-a", "P1", "policy-1", "101", 101L, "binding-1", 2L);
        store.enqueue(normal);
        RequirementStageCommand claimedNormal = store.claim("normal", "worker", 10L, 20L).orElseThrow();
        store.complete(claimedNormal, "worker", 11L);
        store.enqueue(retry);

        assertEquals("normal", store.find("shared-task", "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT")
                .orElseThrow().commandId());
        assertEquals("retry", store.find("shared-task", "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", "101")
                .orElseThrow().commandId());
        RequirementStageCommand claimedRetry = store.claim("retry", "worker", 20L, 20L).orElseThrow();
        assertEquals(1, claimedRetry.attemptNo(), "technical reclaim alone increments the lease attempt");
        assertEquals(101L, claimedRetry.businessGeneration());
        assertEquals("binding-1", claimedRetry.targetRetryBindingId());

        assertThrows(IllegalStateException.class, () -> store.enqueue(retryIdentity(
                "retry-conflict", "shared-task", "ROLE_EXECUTION:CODING_AGENT", "101", 101L, "binding-2")));
        store.enqueue(retryIdentity(
                "retry-next-generation", "shared-task", "ROLE_EXECUTION:CODING_AGENT", "102", 102L, "binding-2"));
        assertEquals("retry-next-generation", store.find(
                "shared-task", "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", "102").orElseThrow().commandId());
    }

    @Test
    void rejectsInvalidNormalAndRetryCommandIdentitiesBeforePersistence() {
        assertThrows(IllegalArgumentException.class,
                () -> retryIdentity("normal-generation", "task-1", "EXECUTE", "", 1L, ""));
        assertThrows(IllegalArgumentException.class,
                () -> retryIdentity("normal-target", "task-1", "EXECUTE", "", 0L, "binding-1"));
        assertThrows(IllegalArgumentException.class,
                () -> retryIdentity("retry-zero", "task-1", "EXECUTE", "101", 0L, ""));
        assertThrows(IllegalArgumentException.class,
                () -> retryIdentity("retry-mismatch", "task-1", "EXECUTE", "101", 102L, ""));
        assertThrows(IllegalArgumentException.class,
                () -> retryIdentity("role-missing-target", "task-1", "ROLE_EXECUTION:CODING_AGENT", "101", 101L, ""));
        assertThrows(IllegalArgumentException.class,
                () -> retryIdentity("ai-missing-target", "task-1", "AI_REVIEW", "101", 101L, ""));
        assertThrows(IllegalArgumentException.class,
                () -> retryIdentity("infrastructure-target", "task-1", "PLAN_GENERATED", "101", 101L, "binding-1"));
    }

    @Test
    void continuationPreflightKeepsNormalAndCheckpointBoundGenerationsSeparate() {
        InMemoryRequirementStageCommandStore store = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand normalSource = command("normal-source", "P1", 1L, 3);
        store.enqueue(normalSource);
        RequirementStageCommand runningNormal = store.claim("normal-source", "worker", 10L, 10L).orElseThrow();
        RequirementStageCommand normalContinuation = RequirementStageCommand.pending(
                "normal-continuation", "task-normal-source", 4L, 9L, "CODING_AGENT",
                "ROLE_EXECUTION:CODING_AGENT", 0, 3, 60_000L, ScheduleResourceClass.PROVIDER,
                "project-a", "provider-a", "P1", 11L);
        store.completeAndEnqueue(runningNormal, "worker", 11L, normalContinuation);

        RequirementStageCommand retrySource = retryIdentity(
                "retry-source", "task-normal-source", "PLAN_GENERATED", "101", 101L, "");
        store.enqueue(retrySource);
        RequirementStageCommand runningRetry = store.claim("retry-source", "worker", 12L, 10L).orElseThrow();
        RequirementStageCommand retryContinuation = retryIdentity(
                "retry-continuation", "task-normal-source", "ROLE_EXECUTION:CODING_AGENT", "101", 101L, "binding-1");

        store.preflightCompletionAndEnqueue(runningRetry, "worker", 13L, retryContinuation);
        store.completeAndEnqueue(runningRetry, "worker", 13L, retryContinuation);

        assertEquals("normal-continuation", store.find(
                "task-normal-source", "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT").orElseThrow().commandId());
        assertEquals("retry-continuation", store.find(
                "task-normal-source", "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", "101").orElseThrow().commandId());
    }

    @Test
    void claimsByPriorityAndAgingAndOnlyOneWorkerWins() throws Exception {
        InMemoryRequirementStageCommandStore store = new InMemoryRequirementStageCommandStore();
        store.enqueue(command("low", "P2", 100L, 3));
        store.enqueue(command("high", "P0", 100L, 3));
        store.enqueue(command("aged", "P2", 0L, 2));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<RequirementStageCommand>> first = pool.submit(() -> {
                start.await();
                return store.claimBatch("worker-a", 1_000L, 10_000L, 1);
            });
            Future<List<RequirementStageCommand>> second = pool.submit(() -> {
                start.await();
                return store.claimBatch("worker-b", 1_000L, 10_000L, 1);
            });
            start.countDown();
            List<RequirementStageCommand> left = first.get();
            List<RequirementStageCommand> right = second.get();

            assertEquals(2, left.size() + right.size());
            assertEquals(2, store.listByStatus(RequirementStageCommand.Status.RUNNING).size());
            assertTrue(List.of(left, right).stream().flatMap(List::stream)
                    .anyMatch(command -> command.commandId().equals("high")));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void heartbeatAndRetryRespectLeaseOwnerAndDeadLetterAtAttemptLimit() {
        InMemoryRequirementStageCommandStore store = new InMemoryRequirementStageCommandStore();
        store.enqueue(command("retry", "P1", 100L, 2));
        RequirementStageCommand claimed = store.claimBatch("worker", 100L, 10L, 1).getFirst();

        assertThrows(IllegalStateException.class,
                () -> store.heartbeat(claimed.commandId(), "other", 101L, 10L));
        RequirementStageCommand failed = store.fail(claimed.commandId(), "worker", "provider 429", 102L);
        assertEquals(RequirementStageCommand.Status.FAILED_RETRYABLE, failed.status());

        RequirementStageCommand retried = store.claimBatch("worker", 103L, 10L, 1).getFirst();
        RequirementStageCommand dead = store.fail(retried.commandId(), "worker", "provider 500", 104L);
        assertEquals(RequirementStageCommand.Status.DEAD_LETTERED, dead.status());
    }

    @Test
    void expiredLeaseIsReclaimedWithNextAttempt() {
        InMemoryRequirementStageCommandStore store = new InMemoryRequirementStageCommandStore();
        store.enqueue(command("expired", "P1", 100L, 3));
        RequirementStageCommand first = store.claimBatch("worker-a", 100L, 10L, 1).getFirst();
        assertEquals(1, first.attemptNo());

        RequirementStageCommand recovered = store.claimBatch("worker-b", 111L, 10L, 1).getFirst();
        assertEquals(2, recovered.attemptNo());
        assertEquals("worker-b", recovered.leaseOwner());
    }

    @Test
    void rejectsAnExpiredLeaseBeforeTheSameOwnerCanCompleteIt() {
        InMemoryRequirementStageCommandStore store = new InMemoryRequirementStageCommandStore();
        store.enqueue(command("expired-complete", "P1", 100L, 3));
        RequirementStageCommand claimed = store.claimBatch("worker", 100L, 10L, 1).getFirst();

        assertThrows(IllegalStateException.class,
                () -> store.complete(claimed.commandId(), "worker", 111L));
    }

    @Test
    void rejectsTheStaleAttemptWhenTheSameOwnerReclaimsItsLease() {
        InMemoryRequirementStageCommandStore store = new InMemoryRequirementStageCommandStore();
        store.enqueue(command("same-owner-reclaim", "P1", 100L, 3));
        RequirementStageCommand first = store.claimBatch("worker", 100L, 10L, 1).getFirst();
        RequirementStageCommand recovered = store.claimBatch("worker", 111L, 10L, 1).getFirst();

        assertEquals(first.attemptNo() + 1, recovered.attemptNo());
        assertThrows(IllegalStateException.class,
                () -> store.complete(first.commandId(), first.attemptNo(), "worker", 112L));
    }

    @Test
    void selectedBatchClaimIsAtomicAndDeadlineCommandsAreDeadLettered() {
        InMemoryRequirementStageCommandStore store = new InMemoryRequirementStageCommandStore();
        store.enqueue(command("selected", "P1", 100L, 3));
        store.enqueue(command("not-selected", "P1", 100L, 3));

        List<RequirementStageCommand> claimed = store.claimBatchById(
                List.of("selected"), "worker", 200L, 10L, 10);

        assertEquals(List.of("selected"), claimed.stream()
                .map(RequirementStageCommand::commandId).toList());
        assertEquals(1, store.listByStatus(RequirementStageCommand.Status.RUNNING).size());

        RequirementStageCommand deadline = RequirementStageCommand.pending(
                "deadline", "task-deadline", 1L, 1L, "CODING_AGENT", "EXECUTE", 0, 3,
                250L, ScheduleResourceClass.PROVIDER, "project-a", "", "P1", 100L);
        store.enqueue(deadline);
        List<RequirementStageCommand> dead = store.deadLetterExpired(250L, 10);
        assertEquals(1, dead.size());
        assertEquals(RequirementStageCommand.Status.DEAD_LETTERED, dead.getFirst().status());
        assertTrue(store.claimBatch("worker", 250L, 10L, 10).stream()
                .noneMatch(command -> command.commandId().equals("deadline")));
    }

    @Test
    void deadlineCleanupPreservesCheckpointBoundRetryIdentity() {
        InMemoryRequirementStageCommandStore store = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand retry = RequirementStageCommand.pending(
                "retry-deadline", "task-retry-deadline", 4L, 9L,
                "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 0, 3,
                250L, ScheduleResourceClass.PROVIDER, Set.of(ScheduleResourceClass.PROVIDER),
                "project-a", "provider-a", "P1", "policy-1", "101", 101L, "binding-1", 100L);
        store.enqueue(retry);

        RequirementStageCommand dead = store.deadLetterExpired(250L, 1).getFirst();

        assertEquals(RequirementStageCommand.Status.DEAD_LETTERED, dead.status());
        assertEquals("101", dead.retryCheckpointId());
        assertEquals(101L, dead.businessGeneration());
        assertEquals("binding-1", dead.targetRetryBindingId());
        assertEquals(dead, store.find("task-retry-deadline", "CODING_AGENT",
                "ROLE_EXECUTION:CODING_AGENT", "101").orElseThrow());
    }

    @Test
    void deadlineCleanupDeadLettersAnExpiredRunningCommandEvenWithAnActiveLease() {
        InMemoryRequirementStageCommandStore store = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand command = RequirementStageCommand.pending(
                "running-deadline", "task-running-deadline", 1L, 1L,
                "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 0, 3,
                250L, ScheduleResourceClass.PROVIDER, "project-a", "provider-a", "P1", 100L);
        store.enqueue(command);
        store.claim(command.commandId(), "worker", 100L, 60_000L).orElseThrow();

        List<RequirementStageCommand> dead = store.deadLetterExpired(250L, 1);

        assertEquals(1, dead.size());
        assertEquals(RequirementStageCommand.Status.DEAD_LETTERED, dead.getFirst().status());
    }

    @Test
    void fairBatchClaimAtomicallyRejectsAnyCommandThatWouldExceedACombinedQuota() {
        InMemoryRequirementStageCommandStore store = new InMemoryRequirementStageCommandStore();
        long now = 1_000L;
        RequirementStageCommand running = RequirementStageCommand.pending(
                "running", "task-running", 1L, 1L, "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT",
                0, 3, now + 60_000L, ScheduleResourceClass.PROVIDER,
                Set.of(ScheduleResourceClass.PROVIDER, ScheduleResourceClass.DOCKER),
                "project-a", "provider-a", "P1", now - 100L);
        RequirementStageCommand sameProvider = RequirementStageCommand.pending(
                "same-provider", "task-same-provider", 1L, 1L,
                "SOLUTION_ARCHITECT", "ROLE_EXECUTION:SOLUTION_ARCHITECT", 0, 3,
                now + 60_000L, ScheduleResourceClass.PROVIDER,
                Set.of(ScheduleResourceClass.PROVIDER), "project-b", "provider-a", "P1", now);
        RequirementStageCommand dockerSaturated = RequirementStageCommand.pending(
                "docker-saturated", "task-docker-saturated", 1L, 1L,
                "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 0, 3,
                now + 60_000L, ScheduleResourceClass.PROVIDER,
                Set.of(ScheduleResourceClass.PROVIDER, ScheduleResourceClass.DOCKER),
                "project-c", "provider-b", "P1", now + 1L);
        RequirementStageCommand available = RequirementStageCommand.pending(
                "available", "task-available", 1L, 1L,
                "SOLUTION_ARCHITECT", "ROLE_EXECUTION:SOLUTION_ARCHITECT", 0, 3,
                now + 60_000L, ScheduleResourceClass.PROVIDER,
                Set.of(ScheduleResourceClass.PROVIDER), "project-d", "provider-b", "P1", now + 2L);
        store.enqueue(running);
        store.claim("running", "worker-a", now, 60_000L).orElseThrow();
        store.enqueue(sameProvider);
        store.enqueue(dockerSaturated);
        store.enqueue(available);
        RequirementDeliverySchedulingPolicy policy = new RequirementDeliverySchedulingPolicy(
                new FairScheduleLimits(4, 1, 1, 2, 1, 8, 60_000L),
                Map.of(),
                60_000L,
                "provider-default"
        );

        List<RequirementStageCommand> claimed = store.claimFairBatch(
                List.of("same-provider", "docker-saturated", "available"),
                "worker-b",
                now + 10L,
                60_000L,
                policy
        );

        assertEquals(List.of("available"), claimed.stream()
                .map(RequirementStageCommand::commandId)
                .toList());
    }

    @Test
    void recoveryWindowIncludesEachProjectHeadBeforeMoreWorkFromOneProject() {
        InMemoryRequirementStageCommandStore store = new InMemoryRequirementStageCommandStore();
        long now = 1_000L;
        for (int index = 0; index < 64; index++) {
            store.enqueue(RequirementStageCommand.pending(
                    "busy-" + index, "busy-task-" + index, 1L, 1L,
                    "SOLUTION_ARCHITECT", "ROLE_EXECUTION:SOLUTION_ARCHITECT", 0, 3,
                    now + 60_000L, ScheduleResourceClass.PROVIDER, "project-busy", "provider-busy",
                    "P0", now - 100L));
        }
        store.enqueue(RequirementStageCommand.pending(
                "waiting-project", "waiting-task", 1L, 1L,
                "SOLUTION_ARCHITECT", "ROLE_EXECUTION:SOLUTION_ARCHITECT", 0, 3,
                now + 60_000L, ScheduleResourceClass.PROVIDER, "project-waiting", "provider-waiting",
                "P1", now - 100L));

        List<RequirementStageCommand> candidates = store.recoverable(now, 64);

        assertTrue(candidates.stream()
                .anyMatch(command -> command.projectId().equals("project-waiting")));
    }

    @Test
    void recoveryWindowRotatesProjectHeadsBeyondItsBoundedSize() {
        InMemoryRequirementStageCommandStore store = new InMemoryRequirementStageCommandStore();
        long now = 1_000L;
        for (int index = 0; index < 65; index++) {
            String suffix = String.format("%03d", index);
            store.enqueue(RequirementStageCommand.pending(
                    "project-command-" + suffix, "project-task-" + suffix, 1L, 1L,
                    "SOLUTION_ARCHITECT", "ROLE_EXECUTION:SOLUTION_ARCHITECT", 0, 3,
                    now + 180_000L, ScheduleResourceClass.PROVIDER,
                    "project-" + suffix, "provider-" + suffix, "P1", now - 100L));
        }

        List<RequirementStageCommand> firstWindow = store.recoverable(now, 64);
        List<RequirementStageCommand> nextWindow = store.recoverable(now + 60_000L, 64);

        assertEquals(64, firstWindow.size());
        assertTrue(nextWindow.stream().anyMatch(command -> command.projectId().equals("project-064")),
                "a project outside one bounded window must become visible on the next aging interval");
    }

    private static RequirementStageCommand command(String id, String priority, long createdAt, int maxAttempts) {
        return RequirementStageCommand.pending(
                id, "task-" + id, 4L, 9L, "CODING_AGENT", "EXECUTE", 0, maxAttempts,
                60_000L, ScheduleResourceClass.PROVIDER, "project-a", "provider-a", priority, createdAt);
    }

    private static RequirementStageCommand legacyZeroFenceCommand(String commandId) {
        return new RequirementStageCommand(
                commandId, "task-" + commandId, 0L, 0L, "CODING_AGENT", "EXECUTE", 0, 3,
                0L, ScheduleResourceClass.GENERIC, Set.of(ScheduleResourceClass.GENERIC), "project-a",
                "", 2, RequirementStageCommand.Status.PENDING, "", 0L, 1L, "", 1L, 1L);
    }

    private static RequirementStageCommand retryIdentity(
            String commandId,
            String taskId,
            String stage,
            String checkpointId,
            long businessGeneration,
            String targetBindingId
    ) {
        return new RequirementStageCommand(
                commandId, taskId, 4L, 9L, "CODING_AGENT", stage, 0, 3,
                60_000L, ScheduleResourceClass.PROVIDER, Set.of(ScheduleResourceClass.PROVIDER),
                "project-a", "provider-a", 1, RequirementStageCommand.Status.PENDING, "", 0L,
                0L, "", 1L, 1L, "policy-1", checkpointId, businessGeneration, targetBindingId);
    }
}
