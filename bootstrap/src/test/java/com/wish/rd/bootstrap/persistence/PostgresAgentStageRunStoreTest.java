package com.wish.rd.bootstrap.persistence;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.wish.rd.bootstrap.persistence.entity.RdAgentStageEventRow;
import com.wish.rd.bootstrap.persistence.entity.RdAgentStageRunRow;
import com.wish.rd.bootstrap.persistence.mapper.RdAgentStageRunMapper;
import com.wish.rd.engine.agent.AgentRole;
import com.wish.rd.engine.agent.AgentStageRun;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.AgentStageStatus;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresAgentStageRunStoreTest {

    private final RdAgentStageRunMapper mapper = mock(RdAgentStageRunMapper.class);
    private final PostgresAgentStageRunStore store = new PostgresAgentStageRunStore(mapper, generator());

    @Test
    void shouldSaveListAndTransitionStageRuns() {
        AgentStageRun run = AgentStageRun.pending(
                "7478000000000000001",
                "7478000000000000000",
                AgentRole.REQUIREMENT_REVIEWER,
                1,
                "7478000000000000000:REQUIREMENT_REVIEWER:1",
                1_783_000_000_000L
        );
        when(mapper.selectList(any())).thenReturn(List.of(row(run.stageRunId(), run.taskId(), run.role().name(), run.status().name())));
        when(mapper.selectById(7478000000000000001L))
                .thenReturn(row(run.stageRunId(), run.taskId(), run.role().name(), run.status().name()));

        store.save(run);
        AgentStageRun transitioned = store.transition(
                run.stageRunId(),
                AgentStageStatus.CONTEXT_READY,
                "",
                "",
                1_783_000_000_010L
        );

        assertEquals(List.of(run), store.listByTask(run.taskId()));
        assertEquals(AgentStageStatus.CONTEXT_READY, transitioned.status());
        verify(mapper, times(2)).upsertStageRun(any(RdAgentStageRunRow.class));
    }

    @Test
    void shouldInsertStageEventWhenTransitioningStageRun() {
        AgentStageRun run = AgentStageRun.pending(
                "7478000000000000101",
                "7478000000000000000",
                AgentRole.REQUIREMENT_REVIEWER,
                1,
                "7478000000000000000:REQUIREMENT_REVIEWER:1",
                1_783_000_000_000L
        );
        when(mapper.selectById(7478000000000000101L))
                .thenReturn(row(run.stageRunId(), run.taskId(), run.role().name(), run.status().name()));

        store.transition(
                run.stageRunId(),
                AgentStageStatus.CONTEXT_READY,
                "",
                "",
                1_783_000_000_250L
        );

        ArgumentCaptor<RdAgentStageEventRow> eventCaptor = ArgumentCaptor.forClass(RdAgentStageEventRow.class);
        verify(mapper).insertStageEvent(eventCaptor.capture());
        RdAgentStageEventRow event = eventCaptor.getValue();
        assertEquals(7478000000000000101L, event.stageRunId);
        assertEquals(7478000000000000000L, event.taskId);
        assertEquals("REQUIREMENT_REVIEWER", event.role);
        assertEquals("CONTEXT_READY", event.status);
        assertEquals(250L, event.durationMs);
        assertEquals("SYSTEM", event.trigger);
        assertEquals("{\"sourceStatus\":\"PENDING\",\"targetStatus\":\"CONTEXT_READY\",\"errorCategory\":\"\"}",
                event.metadataJson);
    }

    @Test
    void shouldRejectDuplicateIdempotencyKeyWithDifferentStageId() {
        AgentStageRun run = AgentStageRun.pending(
                "7478000000000000001",
                "7478000000000000000",
                AgentRole.QA_AGENT,
                1,
                "7478000000000000000:QA_AGENT:1",
                1_783_000_000_000L
        );
        when(mapper.selectOne(any(Wrapper.class)))
                .thenReturn(row("7478000000000000002", run.taskId(), run.role().name(), run.status().name()));

        assertThrows(IllegalStateException.class, () -> store.save(run));
    }

    @Test
    void shouldWireStoreThroughSpringWhenUsingPostgresProfile() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getSystemProperties().put("rd.knowledge.store", "postgres");
            context.registerBean(RdAgentStageRunMapper.class, () -> mapper);
            context.register(TransactionProxyConfig.class);
            context.register(PostgresAgentStageRunStore.class);

            context.refresh();

            assertInstanceOf(PostgresAgentStageRunStore.class, context.getBean(AgentStageRunStore.class));
        }
    }

    private RdAgentStageRunRow row(String id, String taskId, String role, String status) {
        RdAgentStageRunRow row = new RdAgentStageRunRow();
        row.id = Long.parseLong(id);
        row.taskId = Long.parseLong(taskId);
        row.role = role;
        row.status = status;
        row.attemptNo = 1;
        row.idempotencyKey = taskId + ":" + role + ":1";
        row.providerName = "";
        row.providerAttemptsJson = "[]";
        row.contextPackageId = null;
        row.promptArtifactId = null;
        row.resultArtifactId = null;
        row.reviewResultJson = "{}";
        row.errorCategory = "";
        row.errorMessage = "";
        row.createdAt = PostgresPersistenceSupport.toDateTime(1_783_000_000_000L);
        row.updatedAt = row.createdAt;
        return row;
    }

    private SnowflakeIdGenerator generator() {
        AtomicLong now = new AtomicLong(1_783_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionProxyConfig {

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }
    }
}
