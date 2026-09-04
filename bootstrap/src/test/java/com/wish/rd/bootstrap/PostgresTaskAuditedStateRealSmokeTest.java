package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.persistence.impl.PostgresAuditedTaskStateStore;
import com.wish.rd.bootstrap.persistence.impl.PostgresRdTaskStatePersistence;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskMapper;
import com.wish.rd.engine.requirement.audit.AuditCompletion;
import com.wish.rd.engine.requirement.audit.AuditIntegrity;
import com.wish.rd.engine.requirement.audit.AuditRun;
import com.wish.rd.engine.requirement.audit.AuditedContractRef;
import com.wish.rd.engine.requirement.audit.AuditedRecord;
import com.wish.rd.engine.requirement.audit.AuditedRecordKind;
import com.wish.rd.engine.requirement.audit.AuditedRecordStatus;
import com.wish.rd.engine.requirement.audit.AuditedTaskState;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateCodec;
import com.wish.rd.engine.requirement.audit.ContractAuditVerdict;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskEventTrigger;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.RdTaskStatusEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "rd.knowledge.store=postgres",
        "rd.storage.mode=memory",
        "rd.distributed-lock.mode=local",
        "rd.executor.docker.circuit-breaker.state-store=memory"
})
@EnabledIfSystemProperty(named = "rd.integration.task-audited-state.enabled", matches = "true")
class PostgresTaskAuditedStateRealSmokeTest {

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty(
                "rd.integration.task-state-atomic.url",
                "jdbc:postgresql://127.0.0.1:55432/rdbot_acceptance?client_encoding=UTF8"));
        registry.add("spring.datasource.username", () -> System.getProperty(
                "rd.integration.task-state-atomic.username", "postgres"));
        registry.add("spring.datasource.password", () -> System.getProperty(
                "rd.integration.task-state-atomic.password", "postgres"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
    }

    @Autowired
    private PostgresRdTaskStatePersistence persistence;

    @Autowired
    private RdTaskMapper taskMapper;

    @Autowired
    private PostgresAuditedTaskStateStore auditedStore;

    @Test
    void initializesWritebackConflictsAndBindsCompletion() throws Exception {
        long taskIdValue = 7_920_000_000_000_000_000L + Math.abs(System.nanoTime()) % 1_000_000L;
        String taskId = String.valueOf(taskIdValue);
        String eventId = String.valueOf(taskIdValue + 1L);
        long now = System.currentTimeMillis();
        RdRequirementTask created = RdRequirementTask.created(
                taskId,
                new CreateRequirementTaskCommand(
                        "审计状态 smoke", "P1", "ADMIN", "smoke-audit", "https://example.test/req/audit",
                        "project-smoke", "PROJ-SMOKE", "Smoke Project",
                        "https://github.com/example/repo.git", "owner", "repo", "main",
                        "真实交付结果", List.of("真实验收标准"), List.of(), false, 64L),
                now);
        try {
            persistence.saveWithEvent(created, event(eventId, taskId, RdTaskStatus.CREATED, now));
            AuditedTaskState first = sealed(taskId, 1L, "AC-001");
            AuditRun firstRun = auditRun(String.valueOf(taskIdValue + 2L), first);
            auditedStore.appendRevision(1L, first, firstRun);
            assertEquals(first.stateHash(), auditedStore.head(taskId).orElseThrow().stateHash());

            AuditedTaskState secondA = sealed(taskId, 2L, "AC-001");
            AuditedTaskState secondB = sealed(taskId, 2L, "AC-002");
            AuditRun runA = auditRun(String.valueOf(taskIdValue + 3L), secondA);
            AuditRun runB = auditRun(String.valueOf(taskIdValue + 4L), secondB);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger conflicts = new AtomicInteger();
            Future<?> one = pool.submit(() -> race(ready, () -> auditedStore.appendRevision(2L, secondA, runA),
                    successes, conflicts));
            Future<?> two = pool.submit(() -> race(ready, () -> auditedStore.appendRevision(2L, secondB, runB),
                    successes, conflicts));
            one.get(10, TimeUnit.SECONDS);
            two.get(10, TimeUnit.SECONDS);
            pool.shutdownNow();
            assertEquals(1, successes.get());
            assertTrue(conflicts.get() >= 1);
            AuditedTaskState head = auditedStore.head(taskId).orElseThrow();
            assertEquals(2L, head.stateVersion());
            String boundRunId = auditedStore.listAuditRuns(taskId).getLast().auditRunId();
            auditedStore.bindCompletion(taskId, boundRunId, head.stateVersion(), head.stateHash());
            assertEquals(head.stateHash(), auditedStore.completionBinding(taskId).orElseThrow().stateHash());
        } finally {
            taskMapper.deleteById(taskIdValue);
        }
    }

    private static void race(
            CountDownLatch ready,
            Runnable action,
            AtomicInteger successes,
            AtomicInteger conflicts
    ) {
        ready.countDown();
        try {
            ready.await(5, TimeUnit.SECONDS);
            action.run();
            successes.incrementAndGet();
        } catch (RuntimeException | InterruptedException failure) {
            conflicts.incrementAndGet();
            if (failure instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }

    private static AuditedTaskState sealed(String taskId, long version, String requirementId) {
        return new AuditedTaskStateCodec().seal(new AuditedTaskState(
                taskId,
                version,
                "",
                new AuditedContractRef("sha256:" + "c".repeat(64), 1L, 1L),
                List.of(new AuditedRecord(
                        requirementId,
                        AuditedRecordKind.REQUIREMENT,
                        true,
                        "criterion " + requirementId,
                        AuditedRecordStatus.PENDING,
                        List.of(),
                        "",
                        "")),
                "audit-head"));
    }

    private static AuditRun auditRun(String commandId, AuditedTaskState state) {
        return new AuditRun(
                "audit-" + commandId,
                state.taskId(),
                "stage-1",
                "HOST_VERIFY",
                commandId,
                AuditCompletion.INCOMPLETE,
                AuditIntegrity.CLEAN,
                ContractAuditVerdict.ALIGNED,
                List.of(),
                List.of(state.records().getFirst().id()),
                List.of(),
                List.of(),
                List.of(),
                1_700_000_000_000L
        );
    }

    private static RdTaskStatusEvent event(String eventId, String taskId, RdTaskStatus status, long now) {
        return new RdTaskStatusEvent(
                eventId, taskId, status.name(), "审计写回", "", now, 0L,
                RdTaskEventTrigger.SYSTEM.name());
    }
}
