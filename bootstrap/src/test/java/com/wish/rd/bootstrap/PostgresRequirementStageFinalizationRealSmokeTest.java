package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.persistence.impl.PostgresRequirementStageCommandStore;
import com.wish.rd.bootstrap.persistence.impl.PostgresRequirementStageFinalizationAdapter;
import com.wish.rd.engine.requirement.job.RequirementStageFinalizationPort;
import com.wish.rd.engine.requirement.job.model.CommandDisposition;
import com.wish.rd.engine.requirement.job.model.ContinuationSpec;
import com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt;
import com.wish.rd.engine.requirement.job.model.PiQaRemediationIntent;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.engine.requirement.job.model.RequirementStageFinalization;
import com.wish.rd.engine.requirement.job.model.RequirementTaskMutation;
import com.wish.rd.engine.requirement.policy.CanonicalJsonSha256;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationKind;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.engine.requirement.publication.RequirementOperationId;
import com.wish.rd.engine.requirement.publication.RequirementPublicationLedger;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationPrepareCommand;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * External PostgreSQL crash-window and PI remediation smoke for stage finalization.
 *
 * <p>Run with {@code -Drd.integration.stage-finalization.enabled=true} against throwaway
 * PostgreSQL on {@code 127.0.0.1:55432}. {@link PostgresClasspathSchemaInitializer} applies
 * {@code p0}/{@code p1}/{@code p4}/{@code p8}/{@code p18}/{@code p19} before context refresh.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "rd.knowledge.store=postgres",
        "rd.storage.mode=memory",
        "rd.distributed-lock.mode=local",
        "rd.executor.docker.circuit-breaker.state-store=memory"
})
@ContextConfiguration(initializers = PostgresClasspathSchemaInitializer.class)
@EnabledIfSystemProperty(named = "rd.integration.stage-finalization.enabled", matches = "true")
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

    @Autowired
    private javax.sql.DataSource dataSource;

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

    @Test
    void adminCommitBeforeOutcomeLockMakesPreparedProfileVersionStale() throws Exception {
        String first = "profile-admin-first-a-" + Math.floorMod(System.nanoTime(), 1_000_000L);
        String second = "profile-admin-first-b-" + Math.floorMod(System.nanoTime(), 1_000_000L);
        try {
            insertProfile(first, "CODING_AGENT");
            insertProfile(second, "QA_AGENT");
            try (java.sql.Connection admin = dataSource.getConnection()) {
                admin.setAutoCommit(false);
                try (java.sql.PreparedStatement update = admin.prepareStatement(
                        "UPDATE rd_agent_execution_profiles SET version=2 WHERE profile_id=?")) {
                    update.setString(1, second);
                    assertEquals(1, update.executeUpdate());
                }
                admin.commit();
            }
            try (java.sql.Connection outcome = dataSource.getConnection()) {
                outcome.setAutoCommit(false);
                List<Long> versions = lockProfileVersions(outcome, List.of(second, first));
                assertEquals(List.of(1L, 2L), versions,
                        "recordOutcome must observe the committed Admin version and reject the stale intent");
                outcome.rollback();
            }
        } finally {
            deleteProfiles(first, second);
        }
    }

    @Test
    void outcomeLocksMultipleProfilesAscendingAndAdminWaitsWithoutDeadlock() throws Exception {
        String first = "profile-outcome-first-a-" + Math.floorMod(System.nanoTime(), 1_000_000L);
        String second = "profile-outcome-first-b-" + Math.floorMod(System.nanoTime(), 1_000_000L);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            insertProfile(first, "CODING_AGENT");
            insertProfile(second, "QA_AGENT");
            try (java.sql.Connection outcome = dataSource.getConnection()) {
                outcome.setAutoCommit(false);
                List<Long> frozenVersions = lockProfileVersions(outcome, List.of(second, first));
                assertEquals(List.of(1L, 1L), frozenVersions,
                        "immutable intent must retain the versions locked by recordOutcome");
                java.util.concurrent.CountDownLatch updateStarted = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Future<Integer> adminUpdate = executor.submit(() -> {
                    try (java.sql.Connection admin = dataSource.getConnection()) {
                        admin.setAutoCommit(false);
                        updateStarted.countDown();
                        try (java.sql.PreparedStatement update = admin.prepareStatement(
                                "UPDATE rd_agent_execution_profiles SET version=2 WHERE profile_id=?")) {
                            update.setString(1, second);
                            int count = update.executeUpdate();
                            admin.commit();
                            return count;
                        }
                    }
                });
                org.junit.jupiter.api.Assertions.assertTrue(
                        updateStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
                org.junit.jupiter.api.Assertions.assertFalse(adminUpdate.isDone(),
                        "Admin update must wait while outcome owns the ordered profile locks");
                outcome.commit();
                assertEquals(1, adminUpdate.get(5, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertEquals(2L, jdbcTemplate.queryForObject(
                    "SELECT version FROM rd_agent_execution_profiles WHERE profile_id=?",
                    Long.class, second));
        } finally {
            executor.shutdownNow();
            deleteProfiles(first, second);
        }
    }

    @Test
    void twoSourceRecordOutcomeAssignsDistinctRemediationNumbersUnderTaskLock() throws Exception {
        String coding = "a-coding-" + Math.floorMod(System.nanoTime(), 1_000_000L);
        String qa = "z-qa-" + Math.floorMod(System.nanoTime(), 1_000_000L);
        long taskId = 8_610_000_000_000_000_000L + Math.floorMod(System.nanoTime(), 1_000_000L);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            insertProfile(coding, "CODING_AGENT");
            insertProfile(qa, "QA_AGENT");
            insertExecutingTask(taskId);
            RemediationAttempt first = new RemediationAttempt(taskId, 0, coding, qa, "ROLE_EXECUTION:QA_AGENT");
            RemediationAttempt second = new RemediationAttempt(taskId, 10, coding, qa, "ROLE_EXECUTION:QA_AGENT:b");
            first.prepare();
            second.prepare();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<Integer> firstNo = executor.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return first.recordOutcome().piQaRemediationIntent().remediationNo();
            });
            Future<Integer> secondNo = executor.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return second.recordOutcome().piQaRemediationIntent().remediationNo();
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            assertEquals(Set.of(1, 2), Set.of(firstNo.get(20, TimeUnit.SECONDS), secondNo.get(20, TimeUnit.SECONDS)),
                    "two sources proposing remNo=1 must linearize to unique round numbers");
        } finally {
            executor.shutdownNow();
            cleanupRemediationTask(taskId);
            deleteProfiles(coding, qa);
        }
    }

    @Test
    void recordOutcomeReplayAndRestartFinalizeKeepSingleRoundAndWakeContinuation() {
        String coding = "a-coding-" + Math.floorMod(System.nanoTime(), 1_000_000L);
        String qa = "z-qa-" + Math.floorMod(System.nanoTime(), 1_000_000L);
        long taskId = 8_620_000_000_000_000_000L + Math.floorMod(System.nanoTime(), 1_000_000L);
        try {
            insertProfile(coding, "CODING_AGENT");
            insertProfile(qa, "QA_AGENT");
            insertExecutingTask(taskId);
            RemediationAttempt attempt = new RemediationAttempt(taskId, 0, coding, qa, "ROLE_EXECUTION:QA_AGENT");
            attempt.prepare();
            attempt.insertSourceStage();
            RequirementStageExecutionPlan frozen = attempt.recordOutcome();
            assertEquals(1, frozen.piQaRemediationIntent().remediationNo());
            assertThrows(IllegalStateException.class, attempt::recordOutcome,
                    "same-source recordOutcome must not mint a second remNo");

            RequirementStageExecutionPlan recovered = finalizationAdapter.decodeOutcomePlan(attempt.recorded);
            assertEquals(frozen.piQaRemediationIntent().remediationNo(),
                    recovered.piQaRemediationIntent().remediationNo());
            assertEquals(frozen.piQaRemediationIntent().requestHash(),
                    recovered.piQaRemediationIntent().requestHash());

            RequirementStageFinalizationPort.FinalizationResult result = attempt.finalizeRecorded(recovered);
            assertEquals(RequirementStageCommand.Status.SUCCEEDED, result.completedCommand().status());
            assertNotNull(result.nextCommand());
            assertEquals(String.valueOf(attempt.firstCommandId), result.nextCommand().commandId());
            assertEquals(RequirementStageCommand.Status.PENDING, result.nextCommand().status());
            assertEquals("CODING_AGENT", result.nextCommand().role());
            assertTask(taskId, RdTaskStatus.EXECUTING, 7L, 3L);
            assertEquals(1, count("""
                    SELECT COUNT(*) FROM rd_agent_remediation_rounds
                     WHERE task_id = ? AND kind = 'QA_PRODUCT_FIX' AND remediation_no = 1
                    """, taskId));
            assertEquals("DISPATCHED", text("""
                    SELECT status FROM rd_agent_remediation_rounds WHERE id = ?
                    """, attempt.roundId));
            assertEquals("PENDING", text("""
                    SELECT status FROM rd_requirement_stage_commands WHERE id = ?
                    """, attempt.firstCommandId));
            assertThrows(RuntimeException.class, () -> attempt.finalizeRecorded(recovered),
                    "restart must not create a second round after commit");
        } finally {
            cleanupRemediationTask(taskId);
            deleteProfiles(coding, qa);
        }
    }

    @Test
    void adminCommitBeforeRemediationRecordOutcomeRejectsStaleProfileClaim() {
        String coding = "a-coding-" + Math.floorMod(System.nanoTime(), 1_000_000L);
        String qa = "z-qa-" + Math.floorMod(System.nanoTime(), 1_000_000L);
        long taskId = 8_630_000_000_000_000_000L + Math.floorMod(System.nanoTime(), 1_000_000L);
        try {
            insertProfile(coding, "CODING_AGENT");
            insertProfile(qa, "QA_AGENT");
            insertExecutingTask(taskId);
            RemediationAttempt attempt = new RemediationAttempt(taskId, 0, coding, qa, "ROLE_EXECUTION:QA_AGENT");
            attempt.prepare();
            jdbcTemplate.update("UPDATE rd_agent_execution_profiles SET version=2 WHERE profile_id=?", qa);
            assertThrows(IllegalStateException.class, attempt::recordOutcome);
            assertEquals(0, count("""
                    SELECT COUNT(*) FROM rd_requirement_stage_finalizations
                     WHERE task_id = ? AND state = 'OUTCOME_RECORDED'
                    """, taskId));
        } finally {
            cleanupRemediationTask(taskId);
            deleteProfiles(coding, qa);
        }
    }

    private List<Long> lockProfileVersions(java.sql.Connection connection, List<String> profileIds)
            throws java.sql.SQLException {
        try (java.sql.PreparedStatement select = connection.prepareStatement("""
                SELECT profile_id, version
                  FROM rd_agent_execution_profiles
                 WHERE profile_id IN (?, ?)
                 ORDER BY profile_id ASC
                 FOR UPDATE
                """)) {
            select.setString(1, profileIds.get(0));
            select.setString(2, profileIds.get(1));
            List<Long> versions = new java.util.ArrayList<>();
            try (java.sql.ResultSet result = select.executeQuery()) {
                while (result.next()) versions.add(result.getLong("version"));
            }
            return List.copyOf(versions);
        }
    }

    private void insertProfile(String profileId, String role) {
        jdbcTemplate.update("""
                INSERT INTO rd_agent_execution_profiles (
                  profile_id, project_id, role, name, runtime_type, provider_profile_id,
                  tool_policy_id, enabled, version, capabilities_json
                ) VALUES (?, 99001, ?, ?, 'PI', 'provider-smoke', 'tool-smoke', TRUE, 1,
                          '["PI_QA_REMEDIATION_V2"]'::jsonb)
                """, profileId, role, profileId);
    }

    private void deleteProfiles(String... profileIds) {
        for (String profileId : profileIds) {
            jdbcTemplate.update("DELETE FROM rd_agent_execution_profiles WHERE profile_id=?", profileId);
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

    private void insertExecutingTask(long taskId) {
        jdbcTemplate.update("""
                INSERT INTO rd_tasks (id, task_type, ticket_id, priority, status, title,
                                      project_id, version, fencing_token)
                VALUES (?, 'REQUIREMENT', ?, 'P1', 'EXECUTING', 'pi remediation smoke',
                        'project-stage-finalization', 7, 3)
                """, taskId, "pi-remediation-" + taskId);
    }

    private void cleanupRemediationTask(long taskId) {
        jdbcTemplate.update("UPDATE rd_agent_remediation_rounds SET first_command_id = NULL WHERE task_id = ?",
                taskId);
        jdbcTemplate.update("""
                DELETE FROM rd_requirement_stage_commands
                 WHERE task_id = ? AND remediation_round_id IS NOT NULL
                """, taskId);
        jdbcTemplate.update("DELETE FROM rd_agent_remediation_rounds WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM rd_requirement_stage_commands WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM rd_agent_execution_profile_snapshots WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM rd_agent_stage_runs WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM rd_requirement_stage_finalizations WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM rd_tasks WHERE id = ?", taskId);
    }

    private final class RemediationAttempt {
        private final long taskId;
        private final long commandId;
        private final long sourceStageId;
        private final long codingStageId;
        private final long qaStageId;
        private final long firstCommandId;
        private final long roundId;
        private final long now = System.currentTimeMillis();
        private final String codingProfile;
        private final String qaProfile;
        private final String stage;
        private RequirementStageCommand claimed;
        private RequirementStageFinalization prepared;
        private RequirementStageFinalization recorded;
        private RequirementStageExecutionPlan proposed;

        private RemediationAttempt(
                long taskId, int idOffset, String codingProfile, String qaProfile, String stage
        ) {
            this.taskId = taskId;
            this.commandId = taskId + 1 + idOffset;
            this.sourceStageId = taskId + 2 + idOffset;
            this.codingStageId = taskId + 3 + idOffset;
            this.qaStageId = taskId + 4 + idOffset;
            this.firstCommandId = taskId + 5 + idOffset;
            this.roundId = taskId + 6 + idOffset;
            this.codingProfile = codingProfile;
            this.qaProfile = qaProfile;
            this.stage = stage;
        }

        private void prepare() {
            RequirementStageCommand command = RequirementStageCommand.pending(
                    String.valueOf(commandId), String.valueOf(taskId), 7L, 3L,
                    "QA_AGENT", stage, 0, 3, now + 60_000L,
                    ScheduleResourceClass.PROVIDER, Set.of(ScheduleResourceClass.PROVIDER),
                    "project-stage-finalization", "provider-stage-finalization", "P1", now);
            stageCommandStore.enqueue(command);
            claimed = stageCommandStore.claim(command.commandId(), LEASE_OWNER, now + 1L, 30_000L)
                    .orElseThrow();
            prepared = finalizationAdapter.prepare(claimed, LEASE_OWNER, RdTaskStatus.EXECUTING, now + 2L);
            proposed = new RequirementStageExecutionPlan(
                    RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                    String.valueOf(taskId), 7L, 3L, RdTaskStatus.EXECUTING, List.of(),
                    CommandDisposition.SUCCEEDED, ContinuationSpec.terminal(), ExternalEffectReceipt.none(),
                    intent());
        }

        private void insertSourceStage() {
            jdbcTemplate.update("""
                    INSERT INTO rd_agent_stage_runs (id, task_id, role, status, attempt_no, idempotency_key)
                    VALUES (?, ?, 'QA_AGENT', 'RUNNING', 1, ?)
                    """, sourceStageId, taskId, "source-qa-" + sourceStageId);
        }

        private RequirementStageExecutionPlan recordOutcome() {
            recorded = finalizationAdapter.recordOutcome(prepared, claimed, LEASE_OWNER, proposed, now + 3L);
            return finalizationAdapter.decodeOutcomePlan(recorded);
        }

        private RequirementStageFinalizationPort.FinalizationResult finalizeRecorded(
                RequirementStageExecutionPlan plan
        ) {
            return finalizationAdapter.finalize(new RequirementStageFinalizationPort.FinalizationCommand(
                    recorded, claimed, LEASE_OWNER, plan, null, null,
                    RequirementStageFinalizationPort.JobDisposition.NONE,
                    RequirementStageFinalizationPort.TaskMutationDisposition.APPLY,
                    now + 4L));
        }

        private PiQaRemediationIntent intent() {
            PiQaRemediationIntent.ExecutionProfileClaim source = claim(qaProfile, 1L);
            PiQaRemediationIntent.ExecutionProfileClaim codingClaim = claim(codingProfile, 1L);
            PiQaRemediationIntent.PreparedProfileSnapshot coding = preparedSnapshot(
                    "snapshot-" + codingStageId, String.valueOf(codingStageId), "CODING_AGENT", 2, codingClaim);
            PiQaRemediationIntent.PreparedProfileSnapshot qa = preparedSnapshot(
                    "snapshot-" + qaStageId, String.valueOf(qaStageId), "QA_AGENT", 2, source);
            String request = "{\"reason\":\"verified defect\",\"remediationNo\":1}";
            return new PiQaRemediationIntent(
                    PiQaRemediationIntent.PROTOCOL, String.valueOf(taskId), String.valueOf(sourceStageId),
                    String.valueOf(commandId), "sha256:" + "a".repeat(64), 7L, 3L,
                    AgentRemediationKind.QA_PRODUCT_FIX, 1, String.valueOf(roundId),
                    request, CanonicalJsonSha256.digest(request),
                    String.valueOf(codingStageId), 2, String.valueOf(qaStageId), 2,
                    String.valueOf(firstCommandId), source, coding, qa, "", "");
        }

        private PiQaRemediationIntent.ExecutionProfileClaim claim(String profileId, long version) {
            return new PiQaRemediationIntent.ExecutionProfileClaim(
                    profileId, version, "PI", List.of("PI_QA_REMEDIATION_V2"));
        }

        private PiQaRemediationIntent.PreparedProfileSnapshot preparedSnapshot(
                String snapshotId, String stageId, String role, int attempt,
                PiQaRemediationIntent.ExecutionProfileClaim profile
        ) {
            String json = "{\"attemptNo\":" + attempt
                    + ",\"capabilities\":[\"PI_QA_REMEDIATION_V2\"]"
                    + ",\"profileId\":\"" + profile.profileId() + "\""
                    + ",\"profileVersion\":" + profile.profileVersion()
                    + ",\"providerProfileId\":\"provider-smoke\""
                    + ",\"role\":\"" + role + "\""
                    + ",\"runtimeType\":\"PI\""
                    + ",\"stageRunId\":\"" + stageId + "\""
                    + ",\"taskId\":\"" + taskId + "\"}";
            String canonical = CanonicalJsonSha256.canonicalize(json);
            return new PiQaRemediationIntent.PreparedProfileSnapshot(
                    snapshotId, stageId, role, attempt, profile, canonical,
                    AgentExecutionProfileSnapshot.sha256(canonical));
        }
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
