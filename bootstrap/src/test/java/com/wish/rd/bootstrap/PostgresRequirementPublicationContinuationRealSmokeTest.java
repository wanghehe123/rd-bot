package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.executor.impl.RequirementPublicationStageContinuationAdapter;
import com.wish.rd.bootstrap.persistence.impl.PostgresRequirementPublicationCommitAdapter;
import com.wish.rd.bootstrap.persistence.impl.PostgresRequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.publication.RequirementOperationId;
import com.wish.rd.engine.requirement.publication.RequirementPublicationCommitPort;
import com.wish.rd.engine.requirement.publication.RequirementPublicationLedger;
import com.wish.rd.engine.requirement.publication.RequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.model.RequirementPublication;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationContinuation;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationPrepareCommand;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationStatus;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * External PostgreSQL smoke for the publication commit transaction and continuation identity.
 *
 * <p>Run against the task-local PostgreSQL instance with
 * {@code -Drd.integration.publication-continuation.enabled=true}. It stays disabled in normal
 * unit-test runs because it requires a migrated local database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "rd.integration.publication-continuation.enabled", matches = "true")
class PostgresRequirementPublicationContinuationRealSmokeTest {

    @Autowired
    private RagStreamTaskRegistry taskRegistry;

    @Autowired
    private RequirementPublicationLedger publicationLedger;

    @Autowired
    private RequirementPublicationStore publicationStore;

    @Autowired
    private PostgresRequirementPublicationCommitAdapter commitAdapter;

    @Autowired
    private RequirementPublicationStageContinuationAdapter continuationAdapter;

    @Autowired
    private PostgresRequirementStageCommandStore stageCommandStore;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("rd.knowledge.store", () -> "postgres");
        registry.add("rd.storage.mode", () -> "memory");
        registry.add("spring.datasource.url", () -> System.getProperty(
                "rd.integration.publication-continuation.url",
                "jdbc:postgresql://127.0.0.1:5432/rdbot?client_encoding=UTF8"));
        registry.add("spring.datasource.username", () -> System.getProperty(
                "rd.integration.publication-continuation.username", "postgres"));
        registry.add("spring.datasource.password", () -> System.getProperty(
                "rd.integration.publication-continuation.password", "postgres"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
    }

    @Test
    void publicationCommitRollsBackTaskEventWhenLedgerFinalizationFails() {
        long taskId = taskId();
        try {
            RdRequirementTask task = prCreatingTask(taskId);
            String operationId = operationId(task.taskId());
            prepareConfirmedPublication(publicationLedger, operationId, task.taskId());
            RequirementPublicationCommitPort.RequirementPublicationCommitCommand command = commitCommand(task, operationId);
            int eventsBefore = eventCount(task.taskId());

            RequirementPublicationLedger failingLedger = new RequirementPublicationLedger(
                    new FailOnCommittedPublicationStore(publicationStore));
            PostgresRequirementPublicationCommitAdapter failingAdapter =
                    new PostgresRequirementPublicationCommitAdapter(taskRegistry, failingLedger);

            assertThrows(IllegalStateException.class, () -> transactionTemplate.executeWithoutResult(
                    ignored -> failingAdapter.commit(command)));

            assertEquals(RdTaskStatus.PR_CREATING, taskRegistry.getRequirementTask(task.taskId()).status());
            assertEquals(RequirementPublicationStatus.PR_CONFIRMED,
                    publicationLedger.findByOperationId(operationId).orElseThrow().status());
            assertEquals(eventsBefore, eventCount(task.taskId()));
        } finally {
            cleanup(taskId);
        }
    }

    @Test
    void concurrentOperationKeyedContinuationsPersistOneFencedStageCommand() throws Exception {
        long taskId = taskId();
        String taskIdString = String.valueOf(taskId);
        String operationId = operationId(taskIdString);
        try {
            insertTask(taskId, 17L, 29L);
            RequirementPublicationContinuation continuation = new RequirementPublicationContinuation(
                    operationId, taskIdString, 17L, 29L, "project-smoke", "P1");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Callable<Void>> writers = List.of(
                        () -> enqueue(continuation),
                        () -> enqueue(continuation));
                for (var future : pool.invokeAll(writers)) {
                    future.get();
                }
            } finally {
                pool.shutdownNow();
            }

            String stage = continuation.stage();
            RequirementStageCommand command = stageCommandStore.find(
                    taskIdString, "REQUIREMENT_DELIVERY", stage).orElseThrow();
            assertEquals(17L, command.taskVersion());
            assertEquals(29L, command.fencingToken());
            assertEquals("project-smoke", command.projectId());
            assertEquals(1, command.priorityRank());
            assertEquals(1, jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                      FROM rd_requirement_stage_commands
                     WHERE task_id = ? AND role = 'REQUIREMENT_DELIVERY' AND stage = ?
                    """, Integer.class, taskId, stage));
        } finally {
            cleanup(taskId);
        }
    }

    private Void enqueue(RequirementPublicationContinuation continuation) {
        continuationAdapter.enqueue(continuation);
        return null;
    }

    private RdRequirementTask prCreatingTask(long taskId) {
        RdRequirementTask task = taskRegistry.createRequirementTask(new CreateRequirementTaskCommand(
                "publication transaction smoke",
                "P1",
                "ADMIN",
                "publication-smoke-" + taskId,
                "",
                "project-smoke",
                "smoke",
                "Publication smoke",
                "https://github.com/acme/waimai",
                "acme",
                "waimai",
                "main",
                "confirm atomic publication commit",
                List.of("publication smoke"),
                List.of(),
                false
        ));
        for (RdTaskStatus status : List.of(
                RdTaskStatus.MATERIAL_COLLECTING,
                RdTaskStatus.MATERIAL_READY,
                RdTaskStatus.CONTEXT_BUILDING,
                RdTaskStatus.CONTEXT_READY,
                RdTaskStatus.PLAN_GENERATING,
                RdTaskStatus.PLAN_GENERATED,
                RdTaskStatus.WAITING_POLICY,
                RdTaskStatus.EXECUTING,
                RdTaskStatus.VALIDATING,
                RdTaskStatus.PR_CREATING)) {
            task = taskRegistry.transitionRequirementFenced(task, status, "", "{}", "", "");
        }
        return task;
    }

    private void prepareConfirmedPublication(
            RequirementPublicationLedger ledger,
            String operationId,
            String taskId
    ) {
        ledger.prepare(new RequirementPublicationPrepareCommand(
                operationId,
                taskId,
                "stage-smoke",
                "main",
                "requirement/" + taskId,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        ));
        ledger.markBranchConfirmed(operationId, "deadbeef");
        ledger.markPullRequestConfirmed(operationId, "https://github.com/acme/waimai/pull/104", 104);
    }

    private RequirementPublicationCommitPort.RequirementPublicationCommitCommand commitCommand(
            RdRequirementTask task,
            String operationId
    ) {
        return new RequirementPublicationCommitPort.RequirementPublicationCommitCommand(
                task.taskId(),
                operationId,
                "https://github.com/acme/waimai/pull/104",
                "{\"status\":\"SUCCESS\"}",
                task.version(),
                task.status(),
                task.fencingToken()
        );
    }

    private void insertTask(long taskId, long version, long fencingToken) {
        jdbcTemplate.update("""
                INSERT INTO rd_tasks (id, task_type, ticket_id, priority, status, title,
                                      project_id, version, fencing_token)
                VALUES (?, 'REQUIREMENT', ?, 'P1', 'REJECTED', 'publication continuation smoke',
                        'project-smoke', ?, ?)
                """, taskId, "publication-continuation-" + taskId, version, fencingToken);
    }

    private int eventCount(String taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rd_task_status_events WHERE task_id = ?",
                Integer.class,
                Long.parseLong(taskId));
    }

    private void cleanup(long taskId) {
        jdbcTemplate.update("DELETE FROM rd_requirement_stage_commands WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM rd_requirement_publications WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM rd_task_status_events WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM rd_tasks WHERE id = ?", taskId);
    }

    private static long taskId() {
        return 8_500_000_000_000_000_000L + Math.floorMod(System.nanoTime(), 1_000_000L);
    }

    private static String operationId(String taskId) {
        String patchSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        return RequirementOperationId.of(taskId, "main", "requirement/" + taskId, patchSha);
    }

    private static final class FailOnCommittedPublicationStore implements RequirementPublicationStore {

        private final RequirementPublicationStore delegate;

        private FailOnCommittedPublicationStore(RequirementPublicationStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public RequirementPublication insertPrepared(RequirementPublication publication) {
            return delegate.insertPrepared(publication);
        }

        @Override
        public Optional<RequirementPublication> findByOperationId(String operationId) {
            return delegate.findByOperationId(operationId);
        }

        @Override
        public Optional<RequirementPublication> findLatestByTaskId(String taskId) {
            return delegate.findLatestByTaskId(taskId);
        }

        @Override
        public List<RequirementPublication> findDueForReconcile(long beforeEpochMillis, int limit) {
            return delegate.findDueForReconcile(beforeEpochMillis, limit);
        }

        @Override
        public RequirementPublication save(RequirementPublication publication) {
            if (publication.status() == RequirementPublicationStatus.COMMITTED) {
                throw new IllegalStateException("forced publication ledger finalization failure");
            }
            return delegate.save(publication);
        }
    }
}
