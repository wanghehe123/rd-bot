package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.persistence.impl.PostgresRequirementStageCommandStore;
import com.wish.rd.bootstrap.persistence.impl.PostgresRequirementStageFinalizationAdapter;
import com.wish.rd.engine.requirement.job.RequirementStageFinalizationPort;
import com.wish.rd.engine.requirement.job.model.CommandDisposition;
import com.wish.rd.engine.requirement.job.model.ContinuationSpec;
import com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.engine.requirement.job.model.RequirementStageFinalization;
import com.wish.rd.engine.requirement.job.model.RequirementTaskMutation;
import com.wish.rd.engine.requirement.publication.RequirementOperationId;
import com.wish.rd.engine.requirement.publication.RequirementPublicationLedger;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationPrepareCommand;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationStatus;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * External PostgreSQL crash-window smoke for atomic requirement-stage finalization.
 *
 * <p>Run with {@code -Drd.integration.stage-finalization.enabled=true}. The test injects a
 * final marker-write failure after the publication receipt, task mutation, timeline append,
 * current command completion, and continuation insert would otherwise have run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "rd.knowledge.store=postgres",
        "rd.storage.mode=memory",
        "rd.distributed-lock.mode=local",
        "rd.executor.docker.circuit-breaker.state-store=memory"
})
@EnabledIfSystemProperty(named = "rd.integration.stage-finalization.enabled", matches = "true")
@Sql(
        scripts = "/sql/postgres/p1_multi_agent_orchestration.sql",
        config = @SqlConfig(separator = ";\n\n"),
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS
)
class PostgresRequirementStageFinalizationRealSmokeTest {

    private static final String LEASE_OWNER = "stage-finalization-smoke-worker";
    private static final String PR_URL = "https://github.com/acme/waimai/pull/104";

    @Autowired
    private PostgresRequirementStageFinalizationAdapter finalizationAdapter;

    @Autowired
    private PostgresRequirementStageCommandStore stageCommandStore;

    @Autowired
    private RequirementPublicationLedger publicationLedger;

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
    void requireStageFinalizationSchema() {
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name = 'rd_requirement_stage_finalizations'
                """, Integer.class),
                "apply p1_multi_agent_orchestration.sql to the opt-in PostgreSQL database before this smoke");
    }

    @Test
    void lateFinalizationMarkerFailureRollsBackPublicationReceiptAndAllStageWrites() {
        Fixture fixture = new Fixture();
        String triggerName = "tr_stage_finalization_" + fixture.taskId;
        String functionName = "fn_stage_finalization_" + fixture.taskId;
        try {
            fixture.prepare();
            jdbcTemplate.execute("""
                    CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      IF NEW.command_id = %d AND NEW.state = 'FINALIZED' THEN
                        RAISE EXCEPTION 'stage finalization smoke injected late failure';
                      END IF;
                      RETURN NEW;
                    END;
                    $$
                    """.formatted(functionName, fixture.commandId));
            jdbcTemplate.execute("""
                    CREATE TRIGGER %s BEFORE UPDATE ON rd_requirement_stage_finalizations
                    FOR EACH ROW EXECUTE FUNCTION %s()
                    """.formatted(triggerName, functionName));

            assertThrows(RuntimeException.class, fixture::finalizeStage);

            assertTask(fixture.taskId, RdTaskStatus.PR_CREATING, 5L, 7L);
            assertEquals(0, count("SELECT COUNT(*) FROM rd_task_status_events WHERE task_id = ?", fixture.taskId));
            assertEquals("RUNNING", text("SELECT status FROM rd_requirement_stage_commands WHERE id = ?", fixture.commandId));
            assertEquals(LEASE_OWNER,
                    text("SELECT lease_owner FROM rd_requirement_stage_commands WHERE id = ?", fixture.commandId));
            assertEquals(0, count("""
                    SELECT COUNT(*) FROM rd_requirement_stage_commands
                     WHERE task_id = ? AND role = 'REPORTER' AND stage = 'ROLE_EXECUTION:REPORTER'
                    """, fixture.taskId));
            assertEquals("OUTCOME_RECORDED", text("""
                    SELECT state FROM rd_requirement_stage_finalizations
                     WHERE command_id = ? AND attempt_no = 1
                    """, fixture.commandId));
            assertEquals("PR_CONFIRMED", text("""
                    SELECT status FROM rd_requirement_publications WHERE operation_id = ?
                    """, fixture.operationId));
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName
                    + " ON rd_requirement_stage_finalizations");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + functionName + "()");
            fixture.cleanup();
        }
    }

    private void assertTask(long taskId, RdTaskStatus expectedStatus, long expectedVersion, long expectedFence) {
        var row = jdbcTemplate.queryForMap("""
                SELECT status, version, fencing_token FROM rd_tasks WHERE id = ?
                """, taskId);
        assertEquals(expectedStatus.name(), row.get("status"));
        assertEquals(expectedVersion, ((Number) row.get("version")).longValue());
        assertEquals(expectedFence, ((Number) row.get("fencing_token")).longValue());
    }

    private int count(String sql, long taskId) {
        return jdbcTemplate.queryForObject(sql, Integer.class, taskId);
    }

    private String text(String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, String.class, argument);
    }

    private final class Fixture {
        private final long taskId = 8_600_000_000_000_000_000L + Math.floorMod(System.nanoTime(), 1_000_000L);
        private final long commandId = taskId + 1L;
        private final long continuationCommandId = taskId + 2L;
        private final long now = System.currentTimeMillis();
        private final String operationId = RequirementOperationId.of(
                String.valueOf(taskId), "main", "requirement/" + taskId,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        private RequirementStageCommand claimedCommand;
        private RequirementStageFinalization recordedMarker;
        private RequirementStageExecutionPlan plan;
        private RequirementStageCommand continuation;

        private void prepare() {
            insertTask();
            publicationLedger.prepare(new RequirementPublicationPrepareCommand(
                    operationId, String.valueOf(taskId), "stage-finalization-smoke", "main",
                    "requirement/" + taskId,
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
            publicationLedger.markBranchConfirmed(operationId, "deadbeef");
            publicationLedger.markPullRequestConfirmed(operationId, PR_URL, 104);

            RequirementStageCommand command = RequirementStageCommand.pending(
                    String.valueOf(commandId), String.valueOf(taskId), 5L, 7L,
                    "PUBLISHER", "ROLE_EXECUTION:PUBLISHER", 0, 3, now + 60_000L,
                    ScheduleResourceClass.PROVIDER, Set.of(ScheduleResourceClass.PROVIDER),
                    "project-stage-finalization", "provider-stage-finalization", "P1", now);
            stageCommandStore.enqueue(command);
            claimedCommand = stageCommandStore.claim(command.commandId(), LEASE_OWNER, now + 1L, 30_000L)
                    .orElseThrow();

            continuation = RequirementStageCommand.pending(
                    String.valueOf(continuationCommandId), String.valueOf(taskId), 6L, 8L,
                    "REPORTER", "ROLE_EXECUTION:REPORTER", 0, 3, now + 60_000L,
                    ScheduleResourceClass.PROVIDER, Set.of(ScheduleResourceClass.PROVIDER),
                    "project-stage-finalization", "provider-stage-finalization", "P1", now + 2L);
            plan = new RequirementStageExecutionPlan(
                    RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                    String.valueOf(taskId), 5L, 7L, RdTaskStatus.PR_CREATING,
                    List.of(RequirementTaskMutation.statusTransition(
                            RdTaskStatus.PR_CREATING, RdTaskStatus.COMMITTED, "",
                            "{\"status\":\"SUCCESS\"}", PR_URL, "", "publication finalized")),
                    CommandDisposition.SUCCEEDED,
                    new ContinuationSpec(continuation.role(), continuation.stage()),
                    new ExternalEffectReceipt(ExternalEffectReceipt.Kind.PUBLICATION, operationId,
                            "PR_CONFIRMED", """
                                    {"operationId":"%s","taskId":"%d","pullRequestUrl":"%s","pullRequestNumber":104}
                                    """.formatted(operationId, taskId, PR_URL)));
            RequirementStageFinalization prepared = finalizationAdapter.prepare(
                    claimedCommand, LEASE_OWNER, RdTaskStatus.PR_CREATING, now + 2L);
            recordedMarker = finalizationAdapter.recordOutcome(
                    prepared, claimedCommand, LEASE_OWNER, plan, now + 3L);
        }

        private void finalizeStage() {
            finalizationAdapter.finalize(new RequirementStageFinalizationPort.FinalizationCommand(
                    recordedMarker, claimedCommand, LEASE_OWNER, plan, continuation, null,
                    RequirementStageFinalizationPort.JobDisposition.NONE,
                    RequirementStageFinalizationPort.TaskMutationDisposition.APPLY,
                    now + 4L));
        }

        private void insertTask() {
            jdbcTemplate.update("""
                    INSERT INTO rd_tasks (id, task_type, ticket_id, priority, status, title,
                                          project_id, version, fencing_token)
                    VALUES (?, 'REQUIREMENT', ?, 'P1', 'PR_CREATING', 'stage finalization smoke',
                            'project-stage-finalization', 5, 7)
                    """, taskId, "stage-finalization-" + taskId);
        }

        private void cleanup() {
            jdbcTemplate.update("DELETE FROM rd_requirement_stage_finalizations WHERE task_id = ?", taskId);
            jdbcTemplate.update("DELETE FROM rd_requirement_stage_commands WHERE task_id = ?", taskId);
            jdbcTemplate.update("DELETE FROM rd_requirement_publications WHERE task_id = ?", taskId);
            jdbcTemplate.update("DELETE FROM rd_task_status_events WHERE task_id = ?", taskId);
            jdbcTemplate.update("DELETE FROM rd_tasks WHERE id = ?", taskId);
        }
    }
}
