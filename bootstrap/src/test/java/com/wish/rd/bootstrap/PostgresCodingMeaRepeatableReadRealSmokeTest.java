package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.persistence.impl.PostgresCodingMeaReadAdapter;
import com.wish.rd.bootstrap.persistence.impl.PostgresRequirementStageCommandStore;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.query.CodingMeaNotFoundException;
import com.wish.rd.engine.requirement.query.CodingMeaSnapshot;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real PostgreSQL smoke for Coding MEA {@code REPEATABLE_READ} snapshots, ownership, and GET purity.
 *
 * <p>Run against throwaway Postgres on {@code 127.0.0.1:55432} (preferred) or any migrated instance:
 * {@code -Drd.integration.coding-mea.enabled=true}. Disabled in normal unit-test runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "rd.knowledge.store=postgres",
        "rd.storage.mode=memory",
        "rd.distributed-lock.mode=local",
        "rd.executor.docker.circuit-breaker.state-store=memory"
})
@ContextConfiguration(initializers = PostgresClasspathSchemaInitializer.class)
@EnabledIfSystemProperty(named = "rd.integration.coding-mea.enabled", matches = "true")
class PostgresCodingMeaRepeatableReadRealSmokeTest {

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty(
                "rd.integration.coding-mea.url",
                "jdbc:postgresql://127.0.0.1:55432/rdbot_acceptance?client_encoding=UTF8"));
        registry.add("spring.datasource.username", () -> System.getProperty(
                "rd.integration.coding-mea.username", "postgres"));
        registry.add("spring.datasource.password", () -> System.getProperty(
                "rd.integration.coding-mea.password", "postgres"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
    }

    @Autowired
    private RagStreamTaskRegistry taskRegistry;

    @Autowired
    private AgentStageRunStore stageRunStore;

    @Autowired
    private PostgresRequirementStageCommandStore commandStore;

    @Autowired
    private PostgresCodingMeaReadAdapter readAdapter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void repeatableReadHoldsStableCommandIdsAcrossConcurrentEnqueue() throws Exception {
        RdRequirementTask task = createTask("crm-rr-owner");
        long base = Long.parseLong(task.taskId());
        String stageId = String.valueOf(base + 10L);
        long cmdA = base + 11L;
        long cmdB = base + 12L;
        long cmdC = base + 13L;
        try {
            stageRunStore.save(AgentStageRun.pending(
                    stageId, task.taskId(), AgentRole.CODING_AGENT, 1, task.taskId() + ":C:1", 1L));
            commandStore.enqueue(pending(cmdA, task.taskId(), "MATERIAL_READY", 1_000L));
            commandStore.enqueue(pending(cmdB, task.taskId(), "CONTEXT_BUILDING", 2_000L));

            TransactionTemplate rr = new TransactionTemplate(transactionManager);
            rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            rr.setReadOnly(true);

            CountDownLatch firstReadDone = new CountDownLatch(1);
            CountDownLatch writerDone = new CountDownLatch(1);
            AtomicReference<Set<String>> firstIds = new AtomicReference<>();
            AtomicReference<Set<String>> secondIds = new AtomicReference<>();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> reader = pool.submit(() -> rr.executeWithoutResult(status -> {
                    CodingMeaSnapshot first = readAdapter.readSnapshot(task.taskId(), stageId, 50, "");
                    firstIds.set(Set.copyOf(first.knownCommandIds()));
                    firstReadDone.countDown();
                    try {
                        assertTrue(writerDone.await(20, TimeUnit.SECONDS), "writer did not finish");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    CodingMeaSnapshot second = readAdapter.readSnapshot(task.taskId(), stageId, 50, "");
                    secondIds.set(Set.copyOf(second.knownCommandIds()));
                }));

                Future<?> writer = pool.submit(() -> {
                    assertTrue(firstReadDone.await(20, TimeUnit.SECONDS), "reader never opened snapshot");
                    commandStore.enqueue(pending(cmdC, task.taskId(), "POLICY_APPLY", 3_000L));
                    writerDone.countDown();
                    return null;
                });

                reader.get(30, TimeUnit.SECONDS);
                writer.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            assertEquals(Set.of(String.valueOf(cmdA), String.valueOf(cmdB)), firstIds.get());
            assertEquals(firstIds.get(), secondIds.get(),
                    "REPEATABLE_READ snapshot must not observe a concurrent enqueue");

            CodingMeaSnapshot after = readAdapter.readSnapshot(task.taskId(), stageId, 50, "");
            assertEquals(Set.of(String.valueOf(cmdA), String.valueOf(cmdB), String.valueOf(cmdC)),
                    Set.copyOf(after.knownCommandIds()));
        } finally {
            cleanup(base, List.of(cmdA, cmdB, cmdC), stageId);
        }
    }

    @Test
    void foreignCodingStageIsRefusedAndReadDoesNotEnqueue() {
        RdRequirementTask owner = createTask("crm-rr-a");
        RdRequirementTask other = createTask("crm-rr-b");
        long ownerId = Long.parseLong(owner.taskId());
        long otherId = Long.parseLong(other.taskId());
        String foreignStage = String.valueOf(otherId + 31L);
        String ownStage = String.valueOf(ownerId + 31L);
        long cmdId = ownerId + 21L;
        try {
            stageRunStore.save(AgentStageRun.pending(
                    foreignStage, other.taskId(), AgentRole.CODING_AGENT, 1, other.taskId() + ":C:1", 1L));
            stageRunStore.save(AgentStageRun.pending(
                    ownStage, owner.taskId(), AgentRole.CODING_AGENT, 1,
                    owner.taskId() + ":C:1", 1L));
            commandStore.enqueue(pending(cmdId, owner.taskId(), "MATERIAL_READY", 5_000L));

            assertThrows(CodingMeaNotFoundException.class,
                    () -> readAdapter.readSnapshot(owner.taskId(), foreignStage, 50, ""));

            int before = commandStore.listIdsByTask(owner.taskId()).size();
            CodingMeaSnapshot snapshot = readAdapter.readSnapshot(owner.taskId(), "", 50, "");
            assertEquals(before, commandStore.listIdsByTask(owner.taskId()).size());
            assertEquals(Set.of(String.valueOf(cmdId)), Set.copyOf(snapshot.knownCommandIds()));
            assertTrue(snapshot.knownCommandIds().stream().noneMatch(id -> id.equals(String.valueOf(otherId))));
        } finally {
            cleanup(ownerId, List.of(cmdId), ownStage);
            cleanup(otherId, List.of(), foreignStage);
        }
    }

    private RdRequirementTask createTask(String titleMarker) {
        return taskRegistry.createRequirementTask(new CreateRequirementTaskCommand(
                titleMarker, "P1", "ADMIN", "", "", "project-crm-rr", "CRM-RR", "CRM RR Project",
                "https://github.com/acme/web.git", "", "", "main", "页面可用",
                List.of("真实浏览器通过"), List.of(), false));
    }

    private static RequirementStageCommand pending(long commandId, String taskId, String stage, long createdAt) {
        return RequirementStageCommand.pending(
                String.valueOf(commandId), taskId, 0L, 1L,
                "REQUIREMENT_DELIVERY", stage, 0, 3, createdAt + 60_000L,
                ScheduleResourceClass.GENERIC, "project-crm-rr", "provider-crm-rr", "P1", createdAt);
    }

    private void cleanup(long taskId, List<Long> commandIds, String stageId) {
        for (long commandId : commandIds) {
            jdbcTemplate.update("DELETE FROM rd_requirement_stage_commands WHERE id = ?", commandId);
        }
        if (stageId != null && !stageId.isBlank()) {
            jdbcTemplate.update("DELETE FROM rd_agent_stage_runs WHERE id = ?", Long.parseLong(stageId));
        }
        jdbcTemplate.update("DELETE FROM rd_tasks WHERE id = ?", taskId);
    }
}
