package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.persistence.impl.PostgresProjectMemoryOperationStore;
import com.wish.rd.bootstrap.persistence.impl.PostgresProjectMemoryStore;
import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperation;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperationStatus;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevision;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevisionStatus;
import com.wish.rd.rag.project.memory.model.ProjectMemorySource;
import com.wish.rd.rag.project.memory.model.ProjectMemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real PostgreSQL smoke for project agent memory: task delete isolation, single head CAS, operation fencing.
 *
 * <p>Run with {@code -Drd.integration.stage-finalization.enabled=true} against throwaway PostgreSQL on
 * {@code 127.0.0.1:55432}. {@link PostgresClasspathSchemaInitializer} applies {@code p19} before refresh.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "rd.knowledge.store=postgres",
        "rd.storage.mode=memory",
        "rd.distributed-lock.mode=local",
        "rd.executor.docker.circuit-breaker.state-store=memory"
})
@ContextConfiguration(initializers = PostgresClasspathSchemaInitializer.class)
@EnabledIfSystemProperty(named = "rd.integration.stage-finalization.enabled", matches = "true")
class ProjectAgentMemoryPostgresRealSmokeTest {

    private static final String PROJECT_ID = "99001";
    private static final String CONTENT_HASH = "a".repeat(64);
    private static final String SOURCE_HASH = "b".repeat(64);
    private static final String WORKER = "project-memory-smoke-worker";

    @Autowired
    private PostgresProjectMemoryStore memoryStore;

    @Autowired
    private PostgresProjectMemoryOperationStore operationStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty(
                "rd.integration.stage-finalization.url",
                "jdbc:postgresql://127.0.0.1:55432/rdbot_acceptance?client_encoding=UTF8"));
        registry.add("spring.datasource.username", () -> System.getProperty(
                "rd.integration.stage-finalization.username", "postgres"));
        registry.add("spring.datasource.password", () -> System.getProperty(
                "rd.integration.stage-finalization.password", "postgres"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
    }

    @BeforeEach
    void requireProjectMemorySchema() {
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name = 'rd_project_memories'
                """, Integer.class),
                "apply p19_project_agent_memory.sql to the opt-in PostgreSQL database before this smoke");
        ensureProject();
    }

    @Test
    void taskDeleteNullsSourceReferenceButPreservesSnapshot() {
        long suffix = Math.floorMod(System.nanoTime(), 1_000_000L);
        long taskId = 8_700_000_000_000_000_000L + suffix;
        long memoryId = 8_701_000_000_000_000_000L + suffix;
        long revisionId = 8_702_000_000_000_000_000L + suffix;
        long sourceId = 8_703_000_000_000_000_000L + suffix;
        String sourceUri = "rd-task://tasks/" + taskId + "/artifacts/smoke";
        try {
            insertTask(taskId);
            memoryStore.create(new ProjectMemory(
                    String.valueOf(memoryId), PROJECT_ID, "CODING_AGENT", ProjectMemoryType.PROCEDURAL,
                    "smoke-key-" + suffix, 1L));
            memoryStore.persistRevisionSourceAndAdvanceHead(
                    revision(memoryId, revisionId, 1L),
                    new ProjectMemorySource(
                            String.valueOf(sourceId), String.valueOf(revisionId), PROJECT_ID,
                            String.valueOf(taskId), "", "", sourceUri, SOURCE_HASH,
                            "main", "extractor-v1", "schema-v1", "redacted summary"),
                    1L);
            assertEquals(String.valueOf(taskId), jdbcTemplate.queryForObject("""
                    SELECT CAST(task_id AS TEXT) FROM rd_project_memory_sources WHERE id = ?
                    """, String.class, sourceId));

            jdbcTemplate.update("DELETE FROM rd_tasks WHERE id = ?", taskId);

            assertNull(jdbcTemplate.queryForObject(
                    "SELECT task_id FROM rd_project_memory_sources WHERE id = ?", Long.class, sourceId));
            assertEquals(sourceUri, jdbcTemplate.queryForObject(
                    "SELECT source_uri FROM rd_project_memory_sources WHERE id = ?", String.class, sourceId));
            assertEquals(SOURCE_HASH, jdbcTemplate.queryForObject(
                    "SELECT source_content_hash FROM rd_project_memory_sources WHERE id = ?", String.class, sourceId));
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM rd_project_memory_revisions WHERE id = ?", Integer.class, revisionId));
        } finally {
            cleanupMemoryChain(memoryId, revisionId, sourceId);
            cleanupMemory(memoryId);
        }
    }

    @Test
    void concurrentHeadAdvanceAllowsOnlyOneActiveHead() throws Exception {
        long suffix = Math.floorMod(System.nanoTime(), 1_000_000L);
        long memoryId = 8_710_000_000_000_000_000L + suffix;
        long revisionA = 8_711_000_000_000_000_000L + suffix;
        long revisionB = 8_712_000_000_000_000_000L + suffix;
        long sourceA = 8_713_000_000_000_000_000L + suffix;
        long sourceB = 8_714_000_000_000_000_000L + suffix;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            memoryStore.create(new ProjectMemory(
                    String.valueOf(memoryId), PROJECT_ID, "QA_AGENT", ProjectMemoryType.SEMANTIC,
                    "concurrent-head-" + suffix, 1L));
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger successes = new AtomicInteger();
            List<Future<?>> attempts = new ArrayList<>();
            attempts.add(executor.submit(() -> {
                ready.countDown();
                awaitGo(go);
                try {
                    memoryStore.persistRevisionSourceAndAdvanceHead(
                            revision(memoryId, revisionA, 1L),
                            source(memoryId, revisionA, sourceA, "uri-a"),
                            1L);
                    successes.incrementAndGet();
                    return null;
                } catch (RuntimeException failed) {
                    return failed;
                }
            }));
            attempts.add(executor.submit(() -> {
                ready.countDown();
                awaitGo(go);
                try {
                    memoryStore.persistRevisionSourceAndAdvanceHead(
                            revision(memoryId, revisionB, 1L),
                            source(memoryId, revisionB, sourceB, "uri-b"),
                            1L);
                    successes.incrementAndGet();
                    return null;
                } catch (RuntimeException failed) {
                    return failed;
                }
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            int conflicts = 0;
            for (Future<?> attempt : attempts) {
                Object outcome = attempt.get(20, TimeUnit.SECONDS);
                if (outcome instanceof RuntimeException) {
                    conflicts++;
                }
            }
            assertEquals(1, successes.get(), "exactly one concurrent head advance may succeed");
            assertEquals(1, conflicts, "the losing advance must fail closed on row-version conflict");
            assertEquals(1, jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM rd_project_memory_revisions
                     WHERE memory_id = ? AND status = 'ACTIVE'
                    """, Integer.class, memoryId));
            assertNotNull(jdbcTemplate.queryForObject(
                    "SELECT head_revision_id FROM rd_project_memories WHERE id = ?", Long.class, memoryId));
        } finally {
            executor.shutdownNow();
            cleanupMemoryChain(memoryId, revisionA, sourceA);
            cleanupMemoryChain(memoryId, revisionB, sourceB);
            cleanupMemory(memoryId);
        }
    }

    @Test
    void staleFencingTokenCannotSettleOperation() {
        long suffix = Math.floorMod(System.nanoTime(), 1_000_000L);
        long operationId = 8_720_000_000_000_000_000L + suffix;
        String sourceIdentity = "stage-finalization-smoke-" + suffix;
        try {
            ProjectMemoryOperation registered = operationStore.register(ProjectMemoryOperation.pending(
                    String.valueOf(operationId), PROJECT_ID, "STAGE", sourceIdentity,
                    CONTENT_HASH, "extractor-v1", "schema-v1"));
            var claim = operationStore.claimNext(WORKER, System.currentTimeMillis(), 30_000L).orElseThrow();
            assertEquals(registered.operationKey(), claim.operation().operationKey());
            long currentFence = claim.fencingToken();
            long currentRowVersion = claim.rowVersion();
            assertTrue(currentFence >= 1L);
            assertFalse(operationStore.settle(
                    claim.operation().operationId(), WORKER, currentFence - 1L, currentRowVersion,
                    ProjectMemoryOperationStatus.SUCCEEDED),
                    "stale fencing token must not settle a claimed operation");
            assertTrue(operationStore.settle(
                    claim.operation().operationId(), WORKER, currentFence, currentRowVersion,
                    ProjectMemoryOperationStatus.SUCCEEDED));
            assertEquals("SUCCEEDED", jdbcTemplate.queryForObject(
                    "SELECT status FROM rd_project_memory_operations WHERE id = ?", String.class, operationId));
        } finally {
            jdbcTemplate.update("DELETE FROM rd_project_memory_operations WHERE id = ?", operationId);
        }
    }

    private static void awaitGo(CountDownLatch go) {
        try {
            assertTrue(go.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private ProjectMemoryRevision revision(long memoryId, long revisionId, long version) {
        return new ProjectMemoryRevision(
                String.valueOf(revisionId), String.valueOf(memoryId), version,
                ProjectMemoryRevisionStatus.ACTIVE, "title", "summary", "{}", CONTENT_HASH,
                "schema-v1", "", System.currentTimeMillis(), 1L);
    }

    private ProjectMemorySource source(long memoryId, long revisionId, long sourceId, String uri) {
        return new ProjectMemorySource(
                String.valueOf(sourceId), String.valueOf(revisionId), PROJECT_ID,
                "", "", "", uri, SOURCE_HASH, "main", "extractor-v1", "schema-v1", "summary");
    }

    private void ensureProject() {
        jdbcTemplate.update("""
                INSERT INTO rd_projects (id, project_key, name, repository_url, repo_owner, repo_name)
                VALUES (99001, 'project-memory-smoke', 'Project Memory Smoke',
                        'https://github.com/acme/waimai', 'acme', 'waimai')
                ON CONFLICT (id) DO NOTHING
                """);
    }

    private void insertTask(long taskId) {
        jdbcTemplate.update("""
                INSERT INTO rd_tasks (id, task_type, ticket_id, priority, status, title,
                                      project_id, version, fencing_token)
                VALUES (?, 'REQUIREMENT', ?, 'P1', 'EXECUTING', 'memory source smoke',
                        'project-memory-smoke', 1, 1)
                """, taskId, "memory-smoke-" + taskId);
    }

    private void cleanupMemoryChain(long memoryId, long revisionId, long sourceId) {
        jdbcTemplate.update("DELETE FROM rd_project_memory_sources WHERE id = ?", sourceId);
        jdbcTemplate.update("""
                UPDATE rd_project_memories
                   SET head_revision_id = NULL, head_version = 0
                 WHERE id = ?
                """, memoryId);
        jdbcTemplate.update("DELETE FROM rd_project_memory_revisions WHERE id = ?", revisionId);
    }

    private void cleanupMemory(long memoryId) {
        jdbcTemplate.update("""
                UPDATE rd_project_memories
                   SET head_revision_id = NULL, head_version = 0
                 WHERE id = ?
                """, memoryId);
        jdbcTemplate.update("DELETE FROM rd_project_memory_sources WHERE revision_id IN "
                + "(SELECT id FROM rd_project_memory_revisions WHERE memory_id = ?)", memoryId);
        jdbcTemplate.update("DELETE FROM rd_project_memory_revisions WHERE memory_id = ?", memoryId);
        jdbcTemplate.update("DELETE FROM rd_project_memories WHERE id = ?", memoryId);
    }
}
