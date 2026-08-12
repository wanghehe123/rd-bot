package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.persistence.impl.PostgresRetrievalRoundAuditStore;
import com.wish.rd.engine.retrieval.iterative.RetrievalRoundAudit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

/** External PostgreSQL smoke for append-only iterative retrieval round audit persistence. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "rd.integration.retrieval-audit.enabled", matches = "true")
class PostgresRetrievalRoundAuditRealSmokeTest {

    @Autowired
    private PostgresRetrievalRoundAuditStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("rd.knowledge.store", () -> "postgres");
        registry.add("rd.storage.mode", () -> "memory");
        registry.add("spring.datasource.url", () -> System.getProperty(
                "rd.integration.retrieval-audit.url",
                "jdbc:postgresql://127.0.0.1:55432/rd_bot?client_encoding=UTF8"));
        registry.add("spring.datasource.username", () -> System.getProperty(
                "rd.integration.retrieval-audit.username", "rd_bot"));
        registry.add("spring.datasource.password", () -> System.getProperty(
                "rd.integration.retrieval-audit.password", "rd_bot"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
    }

    @Test
    void appendsAndReadsEveryRoundInStableOrder() {
        long suffix = Math.abs(System.nanoTime());
        long taskId = 8_500_000_000_000_000_000L + suffix % 1_000_000L;
        long runId = taskId + 1L;
        long now = System.currentTimeMillis();
        insertTask(taskId);
        insertRun(taskId, runId);
        try {
            store.append(new RetrievalRoundAudit(
                    String.valueOf(runId + 1L), String.valueOf(taskId), String.valueOf(runId), 1,
                    "first query", List.of("candidate-a"), List.of("candidate-a"), List.of("API"),
                    1, 120L, 10L, "", "project:smoke", now));
            store.append(new RetrievalRoundAudit(
                    String.valueOf(runId + 2L), String.valueOf(taskId), String.valueOf(runId), 2,
                    "second query", List.of("candidate-b"), List.of("candidate-b"), List.of(),
                    1, 240L, 20L, "EVIDENCE_GATE_SATISFIED", "project:smoke", now + 1L));

            List<RetrievalRoundAudit> rounds = store.listByRun(String.valueOf(runId));
            assertEquals(2, rounds.size());
            assertEquals(List.of(1, 2), rounds.stream().map(RetrievalRoundAudit::roundNo).toList());
            assertIterableEquals(List.of("candidate-a"), rounds.getFirst().selectedEvidenceIds());
            assertEquals("EVIDENCE_GATE_SATISFIED", rounds.getLast().stopReason());
            assertEquals(2, store.listByTask(String.valueOf(taskId)).size());
        } finally {
            jdbcTemplate.update("DELETE FROM rd_retrieval_round_audits WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM rd_rag_retrieval_runs WHERE id = ?", runId);
            jdbcTemplate.update("DELETE FROM rd_tasks WHERE id = ?", taskId);
        }
    }

    private void insertTask(long taskId) {
        jdbcTemplate.update("""
                INSERT INTO rd_tasks (id, task_type, ticket_id, priority, status, title)
                VALUES (?, 'REQUIREMENT', ?, 'P1', 'CREATED', 'retrieval audit smoke')
                """, taskId, "retrieval-audit-smoke-" + taskId);
    }

    private void insertRun(long taskId, long runId) {
        jdbcTemplate.update("""
                INSERT INTO rd_rag_retrieval_runs
                    (id, task_id, consumer_type, role, attempt_no, idempotency_key, status)
                VALUES (?, ?, 'REQUIREMENT_CONTEXT', 'CODING_AGENT', 1, ?, 'COMPLETED')
                """, runId, taskId, "retrieval-audit-smoke-" + runId);
    }
}
