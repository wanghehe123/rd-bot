package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.persistence.impl.PostgresRequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.FairRequirementDeliveryClaimPlanner;
import com.wish.rd.engine.scheduling.model.FairScheduleLimits;
import com.wish.rd.engine.scheduling.model.RequirementDeliverySchedulingPolicy;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * External PostgreSQL smoke for stage-command lease exclusion, fair quota admission, and attempt bounds.
 *
 * <p>Run against the task-local PostgreSQL instance with
 * {@code -Drd.integration.stage-command.enabled=true}; it is intentionally disabled in normal
 * unit-test runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "rd.integration.stage-command.enabled", matches = "true")
class PostgresRequirementStageCommandRealSmokeTest {

    @Autowired
    private PostgresRequirementStageCommandStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("rd.knowledge.store", () -> "postgres");
        registry.add("rd.storage.mode", () -> "memory");
        registry.add("spring.datasource.url", () -> System.getProperty(
                "rd.integration.stage-command.url",
                "jdbc:postgresql://127.0.0.1:55432/rd_bot?client_encoding=UTF8"));
        registry.add("spring.datasource.username", () -> System.getProperty(
                "rd.integration.stage-command.username", "rd_bot"));
        registry.add("spring.datasource.password", () -> System.getProperty(
                "rd.integration.stage-command.password", "rd_bot"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
    }

    @Test
    void exactlyOneConcurrentClaimAndExhaustedCommandIsNeverReclaimed() throws Exception {
        long suffix = Math.abs(System.nanoTime());
        long taskId = 8_400_000_000_000_000_000L + suffix % 1_000_000L;
        long commandId = taskId + 1L;
        long now = System.currentTimeMillis();
        insertTask(taskId);
        try {
            RequirementStageCommand command = RequirementStageCommand.pending(
                    String.valueOf(commandId), String.valueOf(taskId), 0L, 1L,
                    "CODING_AGENT", "docker-execute", 0, 1, now + 60_000L,
                    ScheduleResourceClass.DOCKER, "project-smoke", "provider-smoke", "P1", now);
            store.enqueue(command);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Callable<List<RequirementStageCommand>>> claimers = List.of(
                        () -> store.claimBatch("smoke-a", now + 1L, 10_000L, 1),
                        () -> store.claimBatch("smoke-b", now + 1L, 10_000L, 1));
                List<RequirementStageCommand> claimed = pool.invokeAll(claimers).stream()
                        .map(future -> {
                            try {
                                return future.get();
                            } catch (Exception exception) {
                                throw new AssertionError(exception);
                            }
                        })
                        .flatMap(List::stream)
                        .toList();
                assertEquals(1, claimed.size(), "SKIP LOCKED must lease one row to one claimant");

                RequirementStageCommand winner = claimed.getFirst();
                RequirementStageCommand dead = store.fail(
                        winner.commandId(), winner.leaseOwner(), "attempt budget exhausted", now + 2L);
                assertEquals(RequirementStageCommand.Status.DEAD_LETTERED, dead.status());
                assertTrue(store.claimBatch("smoke-c", now + 3L, 10_000L, 1).isEmpty(),
                        "a dead-lettered/exhausted command must not be reclaimed");
            } finally {
                pool.shutdownNow();
            }
        } finally {
            jdbcTemplate.update("DELETE FROM rd_requirement_stage_commands WHERE id = ?", commandId);
            jdbcTemplate.update("DELETE FROM rd_tasks WHERE id = ?", taskId);
        }
    }

    @Test
    void fairAdmissionSerializesConcurrentSchedulersAndRechecksCombinedQuotas() throws Exception {
        long suffix = Math.abs(System.nanoTime());
        long taskBase = 8_400_000_000_000_000_000L + suffix % 900_000L;
        long now = System.currentTimeMillis();
        long runningTaskId = taskBase;
        long sameProviderTaskId = taskBase + 10L;
        long dockerTaskId = taskBase + 20L;
        long availableTaskId = taskBase + 30L;
        List<Long> taskIds = List.of(runningTaskId, sameProviderTaskId, dockerTaskId, availableTaskId);
        List<Long> commandIds = List.of(taskBase + 1L, taskBase + 11L, taskBase + 21L, taskBase + 31L);
        for (long taskId : taskIds) {
            insertTask(taskId);
        }
        try {
            RequirementStageCommand running = pending(
                    commandIds.getFirst(), runningTaskId, "project-a", "provider-a",
                    Set.of(ScheduleResourceClass.PROVIDER, ScheduleResourceClass.DOCKER), now - 1_000L);
            RequirementStageCommand sameProvider = pending(
                    commandIds.get(1), sameProviderTaskId, "project-b", "provider-a",
                    Set.of(ScheduleResourceClass.PROVIDER), now - 900L);
            RequirementStageCommand dockerSaturated = pending(
                    commandIds.get(2), dockerTaskId, "project-c", "provider-b",
                    Set.of(ScheduleResourceClass.PROVIDER, ScheduleResourceClass.DOCKER), now - 800L);
            RequirementStageCommand available = pending(
                    commandIds.get(3), availableTaskId, "project-d", "provider-b",
                    Set.of(ScheduleResourceClass.PROVIDER), now - 700L);
            store.enqueue(running);
            store.enqueue(sameProvider);
            store.enqueue(dockerSaturated);
            store.enqueue(available);
            store.claim(running.commandId(), "active-worker", now, 60_000L).orElseThrow();
            RequirementDeliverySchedulingPolicy policy = new RequirementDeliverySchedulingPolicy(
                    new FairScheduleLimits(4, 1, 1, 2, 1, 8, 60_000L),
                    Map.of(),
                    60_000L,
                    "provider-default"
            );

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Callable<List<RequirementStageCommand>>> claimers = List.of(
                        () -> store.claimFairBatch(
                                List.of(sameProvider.commandId(), dockerSaturated.commandId(), available.commandId()),
                                "fair-worker-a", now + 1L, 60_000L, policy),
                        () -> store.claimFairBatch(
                                List.of(sameProvider.commandId(), dockerSaturated.commandId(), available.commandId()),
                                "fair-worker-b", now + 1L, 60_000L, policy));
                List<RequirementStageCommand> claimed = pool.invokeAll(claimers).stream()
                        .map(future -> {
                            try {
                                return future.get();
                            } catch (Exception exception) {
                                throw new AssertionError(exception);
                            }
                        })
                        .flatMap(List::stream)
                        .toList();

                assertEquals(List.of(available.commandId()), claimed.stream()
                        .map(RequirementStageCommand::commandId)
                        .toList());
            } finally {
                pool.shutdownNow();
            }
        } finally {
            for (long commandId : commandIds) {
                jdbcTemplate.update("DELETE FROM rd_requirement_stage_commands WHERE id = ?", commandId);
            }
            for (long taskId : taskIds) {
                jdbcTemplate.update("DELETE FROM rd_tasks WHERE id = ?", taskId);
            }
        }
    }

    @Test
    void recoveryWindowIncludesEachProjectHeadBeforeMoreWorkFromOneProject() {
        long suffix = Math.abs(System.nanoTime());
        long taskBase = 8_400_000_000_000_000_000L + suffix % 100_000L;
        long now = System.currentTimeMillis();
        List<Long> taskIds = new ArrayList<>();
        List<Long> commandIds = new ArrayList<>();
        try {
            for (int index = 0; index < 64; index++) {
                long taskId = taskBase + index * 10L;
                long commandId = taskId + 1L;
                taskIds.add(taskId);
                commandIds.add(commandId);
                insertTask(taskId);
                store.enqueue(RequirementStageCommand.pending(
                        String.valueOf(commandId), String.valueOf(taskId), 0L, 1L,
                        "SOLUTION_ARCHITECT", "ROLE_EXECUTION:SOLUTION_ARCHITECT", 0, 3,
                        now + 60_000L, ScheduleResourceClass.PROVIDER,
                        Set.of(ScheduleResourceClass.PROVIDER),
                        "project-busy", "provider-busy", "P0", now - 100L));
            }
            long waitingTaskId = taskBase + 700L;
            long waitingCommandId = waitingTaskId + 1L;
            taskIds.add(waitingTaskId);
            commandIds.add(waitingCommandId);
            insertTask(waitingTaskId);
            store.enqueue(RequirementStageCommand.pending(
                    String.valueOf(waitingCommandId), String.valueOf(waitingTaskId), 0L, 1L,
                    "SOLUTION_ARCHITECT", "ROLE_EXECUTION:SOLUTION_ARCHITECT", 0, 3,
                    now + 60_000L, ScheduleResourceClass.PROVIDER,
                    Set.of(ScheduleResourceClass.PROVIDER),
                    "project-waiting", "provider-waiting", "P1", now - 100L));

            List<RequirementStageCommand> candidates = store.recoverable(now, 64);

            assertEquals(64, candidates.size());
            assertTrue(candidates.stream()
                    .anyMatch(command -> command.commandId().equals(String.valueOf(waitingCommandId))),
                    "a bounded recovery window must expose a waiting project's head command");
        } finally {
            for (long commandId : commandIds) {
                jdbcTemplate.update("DELETE FROM rd_requirement_stage_commands WHERE id = ?", commandId);
            }
            for (long taskId : taskIds) {
                jdbcTemplate.update("DELETE FROM rd_tasks WHERE id = ?", taskId);
            }
        }
    }

    @Test
    void deadlineCleanupDeadLettersAnExpiredRunningCommand() {
        long suffix = Math.abs(System.nanoTime());
        long taskId = 8_400_000_000_000_000_000L + suffix % 1_000_000L;
        long commandId = taskId + 1L;
        long now = System.currentTimeMillis();
        insertTask(taskId);
        try {
            RequirementStageCommand command = RequirementStageCommand.pending(
                    String.valueOf(commandId), String.valueOf(taskId), 0L, 1L,
                    "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 0, 3, now + 100L,
                    ScheduleResourceClass.PROVIDER,
                    Set.of(ScheduleResourceClass.PROVIDER, ScheduleResourceClass.DOCKER),
                    "project-deadline", "provider-deadline", "P1", now);
            store.enqueue(command);
            store.claim(command.commandId(), "deadline-worker", now, 60_000L).orElseThrow();

            List<RequirementStageCommand> dead = store.deadLetterExpired(now + 101L, 1);

            assertEquals(1, dead.size());
            assertEquals(RequirementStageCommand.Status.DEAD_LETTERED, dead.getFirst().status());
        } finally {
            jdbcTemplate.update("DELETE FROM rd_requirement_stage_commands WHERE id = ?", commandId);
            jdbcTemplate.update("DELETE FROM rd_tasks WHERE id = ?", taskId);
        }
    }

    private RequirementStageCommand pending(
            long commandId,
            long taskId,
            String projectId,
            String providerId,
            Set<ScheduleResourceClass> resourceRequirements,
            long createdAtEpochMillis
    ) {
        return RequirementStageCommand.pending(
                String.valueOf(commandId), String.valueOf(taskId), 0L, 1L,
                "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 0, 3,
                createdAtEpochMillis + 60_000L, ScheduleResourceClass.PROVIDER, resourceRequirements,
                projectId, providerId, "P1", createdAtEpochMillis);
    }

    private void insertTask(long taskId) {
        jdbcTemplate.update("""
                INSERT INTO rd_tasks (id, task_type, ticket_id, priority, status, title)
                VALUES (?, 'REQUIREMENT', ?, 'P1', 'CREATED', 'stage smoke')
                """, taskId, "stage-smoke-" + taskId);
    }
}
