package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.persistence.impl.PostgresRdTaskStatePersistence;
import com.wish.rd.bootstrap.persistence.impl.PostgresRdTaskStore;
import com.wish.rd.bootstrap.persistence.impl.PostgresRdTaskStatusEventStore;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskMapper;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskEventTrigger;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.RdTaskStatusEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "rd.knowledge.store=postgres",
        "rd.storage.mode=memory",
        "rd.distributed-lock.mode=local",
        "rd.executor.docker.circuit-breaker.state-store=memory"
})
@EnabledIfSystemProperty(named = "rd.integration.task-state-atomic.enabled", matches = "true")
class PostgresRdTaskStateAtomicRealSmokeTest {

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
    private PostgresRdTaskStore taskStore;

    @Autowired
    private RdTaskMapper taskMapper;

    @Autowired
    private PostgresRdTaskStatusEventStore eventStore;

    @Test
    void shouldCasTaskAndRollbackDuplicateEventThenRejectStaleWorker() {
        String taskId = String.valueOf(7_900_000_000_000_000_000L + System.currentTimeMillis() % 1_000_000L);
        String eventId = String.valueOf(Long.parseLong(taskId) + 1L);
        long now = System.currentTimeMillis();
        RdBugFixTask created = RdBugFixTask.created(taskId, "ATOMIC-REAL", "事务回滚", "P1", now);
        RdTaskStatusEvent createdEvent = event(eventId, taskId, RdTaskStatus.CREATED, now);
        try {
            RdBugFixTask persisted = (RdBugFixTask) persistence.saveWithEvent(created, createdEvent);
            assertEquals(0L, persisted.version());
            assertTrue(persisted.fencingToken() > 0L);

            RdBugFixTask searching = persisted.withState(
                    RdTaskStatus.SEARCHING, "", "", "", "", "", "", now + 1L);
            assertThrows(DataIntegrityViolationException.class, () -> persistence.saveWithEventCas(
                    searching,
                    event(eventId, taskId, RdTaskStatus.SEARCHING, now + 1L),
                    persisted.version(), persisted.fencingToken(), persisted.status()));

            RdBugFixTask afterRollback = taskStore.findBugFixTask(taskId).orElseThrow();
            assertEquals(RdTaskStatus.CREATED, afterRollback.status());
            assertEquals(persisted.version(), afterRollback.version());
            assertEquals(persisted.fencingToken(), afterRollback.fencingToken());
            assertEquals(1, eventStore.listByTask(taskId).size());

            RdBugFixTask advanced = (RdBugFixTask) persistence.saveWithEventCas(
                    searching,
                    event(String.valueOf(Long.parseLong(eventId) + 1L), taskId,
                            RdTaskStatus.SEARCHING, now + 2L),
                    persisted.version(), persisted.fencingToken(), persisted.status());
            assertEquals(RdTaskStatus.SEARCHING, advanced.status());
            assertEquals(persisted.version() + 1L, advanced.version());
            assertEquals(persisted.fencingToken() + 1L, advanced.fencingToken());
            assertEquals(2, eventStore.listByTask(taskId).size());

            RdBugFixTask staleNext = persisted.withState(
                    RdTaskStatus.VALIDATING, "", "", "", "", "", "", now + 3L);
            assertThrows(IllegalStateException.class, () -> persistence.saveWithEventCas(
                    staleNext,
                    event(String.valueOf(Long.parseLong(eventId) + 2L), taskId,
                            RdTaskStatus.VALIDATING, now + 3L),
                    persisted.version(), persisted.fencingToken(), persisted.status()));

            RdBugFixTask afterStale = taskStore.findBugFixTask(taskId).orElseThrow();
            assertEquals(RdTaskStatus.SEARCHING, afterStale.status());
            assertEquals(advanced.version(), afterStale.version());
            assertEquals(advanced.fencingToken(), afterStale.fencingToken());
            assertEquals(2, eventStore.listByTask(taskId).size());
        } finally {
            eventStore.deleteByTask(taskId);
            taskMapper.deleteById(Long.parseLong(taskId));
        }
    }

    @Test
    void shouldCasRequirementMetadataWithoutTimelineEventThenRejectStaleWorker() {
        long taskIdValue = 7_901_000_000_000_000_000L + Math.abs(System.nanoTime()) % 1_000_000L;
        String taskId = String.valueOf(taskIdValue);
        String eventId = String.valueOf(taskIdValue + 1L);
        long now = System.currentTimeMillis();
        RdRequirementTask created = RdRequirementTask.created(
                taskId,
                new CreateRequirementTaskCommand(
                        "需求元数据 smoke", "P1", "ADMIN", "smoke-source", "https://example.test/req/smoke",
                        "project-smoke", "PROJ-SMOKE", "Smoke Project",
                        "https://github.com/example/repo.git", "owner", "repo", "main",
                        "真实交付结果", List.of("真实验收标准"), List.of(), false, 64L),
                now);
        try {
            RdRequirementTask persisted = (RdRequirementTask) persistence.saveWithEvent(
                    created, event(eventId, taskId, RdTaskStatus.CREATED, now));
            int eventCountBeforeEdit = eventStore.listByTask(taskId).size();

            RdRequirementTask edited = persisted.withEditedFields(
                    "编辑后的需求元数据", "P0", now + 1L);
            RdRequirementTask saved = (RdRequirementTask) persistence.saveActionWithEventCas(
                    edited,
                    null,
                    persisted.version(),
                    persisted.fencingToken(),
                    persisted.status());

            assertEquals("编辑后的需求元数据", saved.title());
            assertEquals("P0", saved.priority());
            assertEquals("project-smoke", saved.projectId());
            assertEquals("PROJ-SMOKE", saved.projectKey());
            assertEquals("Smoke Project", saved.projectName());
            assertEquals("真实交付结果", saved.expectedResult());
            assertEquals("[\"真实验收标准\"]", saved.acceptanceCriteriaJson());
            assertEquals(persisted.version() + 1L, saved.version());
            assertEquals(persisted.fencingToken() + 1L, saved.fencingToken());
            assertEquals(eventCountBeforeEdit, eventStore.listByTask(taskId).size());

            RdRequirementTask staleStage = persisted.withState(
                    RdTaskStatus.MATERIAL_COLLECTING,
                    "stale prompt",
                    "{\"stale\":true}",
                    "",
                    "",
                    now + 2L);
            assertThrows(IllegalStateException.class, () -> persistence.saveWithEventCas(
                    staleStage,
                    event(String.valueOf(taskIdValue + 2L), taskId,
                            RdTaskStatus.MATERIAL_COLLECTING, now + 2L),
                    persisted.version(),
                    persisted.fencingToken(),
                    persisted.status()));

            RdRequirementTask afterStale = taskStore.findRequirementTask(taskId).orElseThrow();
            assertEquals(RdTaskStatus.CREATED, afterStale.status());
            assertEquals(saved.title(), afterStale.title());
            assertEquals(saved.priority(), afterStale.priority());
            assertEquals(saved.projectId(), afterStale.projectId());
            assertEquals(saved.acceptanceCriteriaJson(), afterStale.acceptanceCriteriaJson());
            assertEquals(saved.version(), afterStale.version());
            assertEquals(saved.fencingToken(), afterStale.fencingToken());
            assertEquals(eventCountBeforeEdit, eventStore.listByTask(taskId).size());
        } finally {
            eventStore.deleteByTask(taskId);
            taskMapper.deleteById(taskIdValue);
        }
    }

    private RdTaskStatusEvent event(String eventId, String taskId, RdTaskStatus status, long now) {
        return new RdTaskStatusEvent(
                eventId, taskId, status.name(), "事务回滚", "", now, 0L,
                RdTaskEventTrigger.SYSTEM.name());
    }
}
