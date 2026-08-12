package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.persistence.impl.PostgresRdTaskStatusEventStore;
import com.wish.rd.bootstrap.persistence.impl.PostgresRequirementPolicyRunStore;
import com.wish.rd.bootstrap.persistence.impl.PostgresRequirementPolicyTransactionAdapter;
import com.wish.rd.bootstrap.persistence.impl.PostgresRequirementStageCommandStore;
import com.wish.rd.bootstrap.persistence.mapper.RequirementStageCommandMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskMapper;
import com.wish.rd.bootstrap.threading.RequirementStageCommandFactory;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.policy.model.ApproveRequirementPolicyCommand;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyApprovalResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyApplyDisposition;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyApplyResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyResumeResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRunState;
import com.wish.rd.engine.requirement.policy.model.RecordRequirementPolicyDecisionCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * External PostgreSQL smoke for the atomic requirement-policy approval and resume-consumption
 * transactions.
 *
 * <p>Run with {@code -Drd.integration.policy-transaction.enabled=true}. The test is opt-in
 * because it creates and removes its own rows in a real PostgreSQL database; it is not a mock
 * substitute for transaction rollback evidence.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "rd.knowledge.store=postgres",
        "rd.storage.mode=memory",
        "rd.distributed-lock.mode=local",
        "rd.repair.queue.mode=memory",
        "rd.executor.docker.circuit-breaker.state-store=memory"
})
@EnabledIfSystemProperty(named = "rd.integration.policy-transaction.enabled", matches = "true")
class PostgresRequirementPolicyTransactionRealSmokeTest {

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty(
                "rd.integration.policy-transaction.url",
                "jdbc:postgresql://127.0.0.1:55432/rdbot_acceptance?client_encoding=UTF8"));
        registry.add("spring.datasource.username", () -> System.getProperty(
                "rd.integration.policy-transaction.username", "postgres"));
        registry.add("spring.datasource.password", () -> System.getProperty(
                "rd.integration.policy-transaction.password", "postgres"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
    }

    @Autowired
    private PostgresRequirementPolicyTransactionAdapter transactionAdapter;

    @Autowired
    private PostgresRequirementPolicyRunStore policyRunStore;

    @Autowired
    private PostgresRequirementStageCommandStore stageCommandStore;

    @Autowired
    private RequirementStageCommandFactory commandFactory;

    @Autowired
    private PostgresRdTaskStatusEventStore eventStore;

    @Autowired
    private RequirementStageCommandMapper stageCommandMapper;

    @Autowired
    private RdTaskMapper taskMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void requirePolicyTransactionSchema() {
        assertEquals(4, jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'rd_requirement_policy_runs'
                   AND column_name IN ('approval_resume_command_id', 'consumed_by_command_id',
                                       'approval_expected_task_version', 'approval_expected_fence')
                """, Integer.class),
                "apply p1_multi_agent_orchestration.sql to the opt-in PostgreSQL database before this smoke");
    }

    @Test
    void recordEvaluationAtomicallyDecidesPolicyAndSchedulesItsApplyCommand() {
        EvaluationFixture fixture = evaluationFixture();
        try {
            RequirementPolicyEvaluationResult result = transactionAdapter.recordEvaluation(
                    fixture.decision(), fixture.claimedEvaluation, "policy-evaluate-smoke-worker", fixture.now + 10L);

            assertEquals(RequirementPolicyRunState.POLICY_DECIDED, result.policyRun().state());
            assertEquals(RequirementStageCommand.Status.SUCCEEDED, result.completedEvaluationCommand().status());
            assertEquals("POLICY_APPLY", result.policyApplyCommand().stage());
            assertEquals(6L, result.taskVersion());
            assertEquals(8L, result.fencingToken());
            assertTask(fixture.taskId, RdTaskStatus.WAITING_POLICY, 6L, 8L);
            assertEquals(1, countEvents(fixture.taskId, "WAITING_POLICY"));
            assertEquals(RequirementStageCommand.Status.SUCCEEDED,
                    stageCommandStore.findById(fixture.claimedEvaluation.commandId()).orElseThrow().status());
            assertEquals(1, countCommands(fixture.taskId, "REQUIREMENT_DELIVERY", "POLICY_APPLY"));
            RequirementPolicyRun stored = policyRunStore.findById(String.valueOf(fixture.policyRunId)).orElseThrow();
            assertEquals(RequirementPolicyRunState.POLICY_DECIDED, stored.state());
            assertEquals(6L, stored.boundTaskVersion());
            assertEquals(8L, stored.boundFencingToken());
        } finally {
            fixture.cleanup();
        }
    }

    @Test
    void recordEvaluationReplaysExactCanonicalDecisionAfterPostgresJsonbNormalizationWithoutWrites() {
        EvaluationFixture fixture = evaluationFixture();
        try {
            RequirementPolicyEvaluationResult committed = transactionAdapter.recordEvaluation(
                    fixture.decision(), fixture.claimedEvaluation, "policy-evaluate-smoke-worker", fixture.now + 20L);
            String jsonbText = jdbcTemplate.queryForObject(
                    "SELECT policy_json::text FROM rd_requirement_policy_runs WHERE id = ?",
                    String.class, fixture.policyRunId);
            assertTrue(jsonbText.contains(": "), "PostgreSQL jsonb output must be normalized before replay");
            int eventsBeforeReplay = countEvents(fixture.taskId, "WAITING_POLICY");
            int commandsBeforeReplay = totalCommands(fixture.taskId);
            long ledgerVersionBeforeReplay = policyRunStore.findById(String.valueOf(fixture.policyRunId))
                    .orElseThrow().ledgerVersion();

            RequirementPolicyEvaluationResult replay = transactionAdapter.recordEvaluation(
                    fixture.decision(), fixture.claimedEvaluation, "policy-evaluate-smoke-worker", fixture.now + 21L);

            assertEquals(committed.policyApplyCommand().commandId(), replay.policyApplyCommand().commandId());
            assertEquals(eventsBeforeReplay, countEvents(fixture.taskId, "WAITING_POLICY"));
            assertEquals(commandsBeforeReplay, totalCommands(fixture.taskId));
            assertEquals(ledgerVersionBeforeReplay, policyRunStore.findById(String.valueOf(fixture.policyRunId))
                    .orElseThrow().ledgerVersion());
            assertTask(fixture.taskId, RdTaskStatus.WAITING_POLICY, 6L, 8L);
        } finally {
            fixture.cleanup();
        }
    }

    @Test
    void failingPolicyApplyInsertRollsBackTheEntireRealEvaluationTransaction() {
        EvaluationFixture fixture = evaluationFixture();
        String triggerName = "tr_policy_evaluate_" + fixture.taskId;
        String functionName = "fn_policy_evaluate_" + fixture.taskId;
        try {
            jdbcTemplate.execute("""
                    CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      RAISE EXCEPTION 'policy evaluation smoke injected apply insert failure';
                    END;
                    $$
                    """.formatted(functionName));
            jdbcTemplate.execute("""
                    CREATE TRIGGER %s BEFORE INSERT ON rd_requirement_stage_commands
                    FOR EACH ROW WHEN (NEW.task_id = %d AND NEW.stage = 'POLICY_APPLY')
                    EXECUTE FUNCTION %s()
                    """.formatted(triggerName, fixture.taskId, functionName));

            assertThrows(RuntimeException.class, () -> transactionAdapter.recordEvaluation(
                    fixture.decision(), fixture.claimedEvaluation, "policy-evaluate-smoke-worker", fixture.now + 30L));

            assertTask(fixture.taskId, RdTaskStatus.PLAN_GENERATED, 5L, 7L);
            RequirementPolicyRun afterRollback = policyRunStore.findById(String.valueOf(fixture.policyRunId)).orElseThrow();
            assertEquals(RequirementPolicyRunState.PLAN_READY, afterRollback.state());
            assertEquals(0L, afterRollback.ledgerVersion());
            assertEquals(0, countEvents(fixture.taskId, "WAITING_POLICY"));
            assertEquals(0, countCommands(fixture.taskId, "REQUIREMENT_DELIVERY", "POLICY_APPLY"));
            assertEquals(RequirementStageCommand.Status.RUNNING,
                    stageCommandStore.findById(fixture.claimedEvaluation.commandId()).orElseThrow().status());
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName + " ON rd_requirement_stage_commands");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + functionName + "()");
            fixture.cleanup();
        }
    }

    @Test
    void consumeAllowedPolicyApplyAtomicallyStartsTheFirstRoleAndReplaysWithoutWrites() {
        PolicyApplyFixture fixture = policyApplyFixture("ALLOWED");
        try {
            RequirementPolicyApplyResult applied = transactionAdapter.consumePolicyApply(
                    fixture.claimedApply, "policy-apply-smoke-worker", fixture.now + 10L);

            String firstRole = AgentRole.requirementDeliveryOrder().getFirst().name();
            assertEquals(RequirementPolicyApplyDisposition.ALLOWED, applied.disposition());
            assertEquals(RequirementPolicyRunState.APPLIED, applied.policyRun().state());
            assertEquals(RequirementStageCommand.Status.SUCCEEDED, applied.completedApplyCommand().status());
            assertEquals(fixture.claimedApply.commandId(), applied.policyRun().consumedByCommandId());
            assertEquals(firstRole, applied.nextCommand().role());
            assertEquals("ROLE_EXECUTION:" + firstRole, applied.nextCommand().stage());
            assertEquals(String.valueOf(fixture.policyRunId), applied.nextCommand().policyRunId());
            assertTask(fixture.taskId, RdTaskStatus.EXECUTING, 7L, 9L);
            assertEquals(1, countEvents(fixture.taskId, "EXECUTING"));
            assertEquals(RequirementStageCommand.Status.SUCCEEDED,
                    stageCommandStore.findById(fixture.claimedApply.commandId()).orElseThrow().status());
            assertEquals(1, countCommands(fixture.taskId, firstRole, "ROLE_EXECUTION:" + firstRole));
            RequirementPolicyRun stored = policyRunStore.findById(String.valueOf(fixture.policyRunId)).orElseThrow();
            assertEquals(2L, stored.ledgerVersion());
            assertEquals(7L, stored.boundTaskVersion());
            assertEquals(9L, stored.boundFencingToken());

            int eventsBeforeReplay = countEvents(fixture.taskId, "EXECUTING");
            int commandsBeforeReplay = totalCommands(fixture.taskId);
            long ledgerVersionBeforeReplay = stored.ledgerVersion();
            RequirementPolicyApplyResult replay = transactionAdapter.consumePolicyApply(
                    fixture.claimedApply, "different-replay-owner", fixture.now + 11L);

            assertEquals(applied.nextCommand().commandId(), replay.nextCommand().commandId());
            assertEquals(eventsBeforeReplay, countEvents(fixture.taskId, "EXECUTING"));
            assertEquals(commandsBeforeReplay, totalCommands(fixture.taskId));
            assertEquals(ledgerVersionBeforeReplay, policyRunStore.findById(String.valueOf(fixture.policyRunId))
                    .orElseThrow().ledgerVersion());
        } finally {
            fixture.cleanup();
        }
    }

    @Test
    void consumeWaitingApprovalAndDeniedPolicyApplyLeaveNoFirstRoleContinuation() {
        for (PolicyApplyExpectation expectation : List.of(
                new PolicyApplyExpectation("WAITING_APPROVAL", RequirementPolicyApplyDisposition.WAITING_APPROVAL,
                        RequirementPolicyRunState.WAITING_APPROVAL, RdTaskStatus.WAITING_APPROVAL),
                new PolicyApplyExpectation("NEED_INFO", RequirementPolicyApplyDisposition.DENIED,
                        RequirementPolicyRunState.DENIED, RdTaskStatus.FAILED_NEEDS_HUMAN)
        )) {
            PolicyApplyFixture fixture = policyApplyFixture(expectation.action);
            try {
                RequirementPolicyApplyResult applied = transactionAdapter.consumePolicyApply(
                        fixture.claimedApply, "policy-apply-smoke-worker", fixture.now + 20L);

                String firstRole = AgentRole.requirementDeliveryOrder().getFirst().name();
                assertEquals(expectation.disposition, applied.disposition());
                assertEquals(expectation.state, applied.policyRun().state());
                assertEquals(RequirementStageCommand.Status.SUCCEEDED, applied.completedApplyCommand().status());
                assertNull(applied.nextCommand());
                assertTask(fixture.taskId, expectation.taskStatus, 7L, 9L);
                assertEquals(1, countEvents(fixture.taskId, expectation.taskStatus.name()));
                assertEquals(0, countCommands(fixture.taskId, firstRole, "ROLE_EXECUTION:" + firstRole));
                assertEquals(1, totalCommands(fixture.taskId));
                RequirementPolicyRun stored = policyRunStore.findById(String.valueOf(fixture.policyRunId)).orElseThrow();
                assertEquals(2L, stored.ledgerVersion());
                if (expectation.disposition == RequirementPolicyApplyDisposition.DENIED) {
                    assertEquals(fixture.claimedApply.commandId(), stored.consumedByCommandId());
                } else {
                    assertEquals("", stored.consumedByCommandId());
                }

                int eventsBeforeReplay = countEvents(fixture.taskId, expectation.taskStatus.name());
                int commandsBeforeReplay = totalCommands(fixture.taskId);
                RequirementPolicyApplyResult replay = transactionAdapter.consumePolicyApply(
                        fixture.claimedApply, "different-replay-owner", fixture.now + 21L);
                assertEquals(expectation.disposition, replay.disposition());
                assertNull(replay.nextCommand());
                assertEquals(eventsBeforeReplay, countEvents(fixture.taskId, expectation.taskStatus.name()));
                assertEquals(commandsBeforeReplay, totalCommands(fixture.taskId));
            } finally {
                fixture.cleanup();
            }
        }
    }

    @Test
    void failingFirstRoleInsertRollsBackTheEntirePolicyApplyTransaction() {
        PolicyApplyFixture fixture = policyApplyFixture("ALLOWED");
        String triggerName = "tr_policy_apply_" + fixture.taskId;
        String functionName = "fn_policy_apply_" + fixture.taskId;
        try {
            String firstRole = AgentRole.requirementDeliveryOrder().getFirst().name();
            jdbcTemplate.execute("""
                    CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      RAISE EXCEPTION 'policy apply smoke injected first-role insert failure';
                    END;
                    $$
                    """.formatted(functionName));
            jdbcTemplate.execute("""
                    CREATE TRIGGER %s BEFORE INSERT ON rd_requirement_stage_commands
                    FOR EACH ROW WHEN (NEW.task_id = %d AND NEW.role = '%s'
                                       AND NEW.stage = 'ROLE_EXECUTION:%s')
                    EXECUTE FUNCTION %s()
                    """.formatted(triggerName, fixture.taskId, firstRole, firstRole, functionName));

            assertThrows(RuntimeException.class, () -> transactionAdapter.consumePolicyApply(
                    fixture.claimedApply, "policy-apply-smoke-worker", fixture.now + 30L));

            assertTask(fixture.taskId, RdTaskStatus.WAITING_POLICY, 6L, 8L);
            assertEquals(0, countEvents(fixture.taskId, "EXECUTING"));
            assertEquals(0, countCommands(fixture.taskId, firstRole, "ROLE_EXECUTION:" + firstRole));
            assertEquals(1, totalCommands(fixture.taskId));
            assertEquals(RequirementStageCommand.Status.RUNNING,
                    stageCommandStore.findById(fixture.claimedApply.commandId()).orElseThrow().status());
            RequirementPolicyRun afterRollback = policyRunStore.findById(String.valueOf(fixture.policyRunId)).orElseThrow();
            assertEquals(RequirementPolicyRunState.POLICY_DECIDED, afterRollback.state());
            assertEquals(1L, afterRollback.ledgerVersion());
            assertEquals("", afterRollback.consumedByCommandId());
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName + " ON rd_requirement_stage_commands");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + functionName + "()");
            fixture.cleanup();
        }
    }

    @Test
    void concurrentDuplicatePolicyApplyConsumptionCommitsOneDurableContinuationAndReplaysIt() throws Exception {
        PolicyApplyFixture fixture = policyApplyFixture("ALLOWED");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<String> consume = () -> {
                ready.countDown();
                start.await();
                return transactionAdapter.consumePolicyApply(
                        fixture.claimedApply, "policy-apply-smoke-worker", fixture.now + 40L)
                        .nextCommand().commandId();
            };
            List<Future<String>> results = List.of(pool.submit(consume), pool.submit(consume));
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();

            String firstRole = AgentRole.requirementDeliveryOrder().getFirst().name();
            assertEquals(results.get(0).get(), results.get(1).get());
            assertTask(fixture.taskId, RdTaskStatus.EXECUTING, 7L, 9L);
            assertEquals(1, countEvents(fixture.taskId, "EXECUTING"));
            assertEquals(1, countCommands(fixture.taskId, firstRole, "ROLE_EXECUTION:" + firstRole));
            assertEquals(2, totalCommands(fixture.taskId));
        } finally {
            pool.shutdownNow();
            fixture.cleanup();
        }
    }

    @Test
    void approvalAndResumeConsumerCommitOneCoherentGenerationAndReplayExactly() {
        Fixture fixture = fixture();
        try {
            RequirementPolicyApprovalResult approved = transactionAdapter.approve(
                    fixture.approval("request-1", "approved by real smoke"), "ADMIN_API", fixture.now + 10L);

            assertEquals(RequirementPolicyRunState.APPROVED, approved.policyRun().state());
            assertEquals(8L, approved.taskVersion());
            assertEquals(10L, approved.fencingToken());
            assertEquals("APPROVAL_RESUME", approved.approvalResumeCommand().stage());
            assertTask(fixture.taskId, RdTaskStatus.WAITING_APPROVAL, 8L, 10L);
            assertEquals(1, countCommands(fixture.taskId, "REQUIREMENT_DELIVERY", "APPROVAL_RESUME"));
            assertEquals(1, countEvents(fixture.taskId, "APPROVED"));

            RequirementPolicyApprovalResult approvalReplay = transactionAdapter.approve(
                    fixture.approval("request-1", "approved by real smoke"), "ADMIN_API", fixture.now + 11L);
            assertEquals(approved.approvalResumeCommand().commandId(), approvalReplay.approvalResumeCommand().commandId());
            assertEquals(1, countCommands(fixture.taskId, "REQUIREMENT_DELIVERY", "APPROVAL_RESUME"));
            assertEquals(1, countEvents(fixture.taskId, "APPROVED"));

            RequirementStageCommand claimed = stageCommandStore.claim(
                    approved.approvalResumeCommand().commandId(), "policy-smoke-worker", fixture.now + 12L, 60_000L)
                    .orElseThrow();
            RequirementPolicyResumeResult resumed = transactionAdapter.consumeApproval(
                    claimed, "policy-smoke-worker", fixture.now + 13L);

            assertEquals(RequirementPolicyRunState.APPLIED, resumed.policyRun().state());
            assertEquals(RequirementStageCommand.Status.SUCCEEDED, resumed.completedResumeCommand().status());
            assertEquals(9L, resumed.taskVersion());
            assertEquals(11L, resumed.fencingToken());
            assertEquals("ROLE_EXECUTION:" + resumed.nextCommand().role(), resumed.nextCommand().stage());
            assertTask(fixture.taskId, RdTaskStatus.EXECUTING, 9L, 11L);
            assertEquals(1, countEvents(fixture.taskId, "EXECUTING"));
            assertEquals(1, countCommands(fixture.taskId, resumed.nextCommand().role(), resumed.nextCommand().stage()));

            RequirementPolicyResumeResult resumeReplay = transactionAdapter.consumeApproval(
                    claimed, "policy-smoke-worker", fixture.now + 14L);
            assertEquals(resumed.nextCommand().commandId(), resumeReplay.nextCommand().commandId());
            assertEquals(1, countEvents(fixture.taskId, "EXECUTING"));
            assertEquals(2, totalCommands(fixture.taskId));
        } finally {
            fixture.cleanup();
        }
    }

    @Test
    void concurrentConflictingApprovalHasOneDurableWinnerAndNoDuplicateResume() throws Exception {
        Fixture fixture = fixture();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<Boolean>> attempts = List.of(
                    concurrentApproval(fixture, "first" , ready, start),
                    concurrentApproval(fixture, "conflicts" , ready, start));
            List<Future<Boolean>> results = attempts.stream().map(pool::submit).toList();
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            long successes = 0L;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    successes++;
                }
            }

            assertEquals(1L, successes);
            assertEquals(1, countCommands(fixture.taskId, "REQUIREMENT_DELIVERY", "APPROVAL_RESUME"));
            assertEquals(1, countEvents(fixture.taskId, "APPROVED"));
            RequirementPolicyRun stored = policyRunStore.findById(String.valueOf(fixture.policyRunId)).orElseThrow();
            assertEquals(RequirementPolicyRunState.APPROVED, stored.state());
            assertTrue(stored.note().equals("first") || stored.note().equals("conflicts"));
            assertTask(fixture.taskId, RdTaskStatus.WAITING_APPROVAL, 8L, 10L);
        } finally {
            pool.shutdownNow();
            fixture.cleanup();
        }
    }

    @Test
    void failingRealDatabaseWriteRollsBackTaskEventLedgerAndResumeCommand() {
        Fixture fixture = fixture();
        String triggerName = "tr_policy_smoke_" + fixture.taskId;
        String functionName = "fn_policy_smoke_" + fixture.taskId;
        try {
            jdbcTemplate.execute("""
                    CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      RAISE EXCEPTION 'policy smoke injected stage-command failure';
                    END;
                    $$
                    """.formatted(functionName));
            jdbcTemplate.execute("""
                    CREATE TRIGGER %s BEFORE INSERT ON rd_requirement_stage_commands
                    FOR EACH ROW WHEN (NEW.task_id = %d) EXECUTE FUNCTION %s()
                    """.formatted(triggerName, fixture.taskId, functionName));

            assertThrows(RuntimeException.class, () -> transactionAdapter.approve(
                    fixture.approval("rollback-request", "must roll back"), "ADMIN_API", fixture.now + 20L));

            assertTask(fixture.taskId, RdTaskStatus.WAITING_APPROVAL, 7L, 9L);
            RequirementPolicyRun afterRollback = policyRunStore.findById(String.valueOf(fixture.policyRunId)).orElseThrow();
            assertEquals(RequirementPolicyRunState.WAITING_APPROVAL, afterRollback.state());
            assertEquals(2L, afterRollback.ledgerVersion());
            assertEquals(0, countEvents(fixture.taskId, "APPROVED"));
            assertEquals(0, countCommands(fixture.taskId, "REQUIREMENT_DELIVERY", "APPROVAL_RESUME"));
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName + " ON rd_requirement_stage_commands");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + functionName + "()");
            fixture.cleanup();
        }
    }

    @Test
    void failingContinuationInsertRollsBackTheEntireApprovalConsumerTransaction() {
        Fixture fixture = fixture();
        String triggerName = "tr_policy_consume_" + fixture.taskId;
        String functionName = "fn_policy_consume_" + fixture.taskId;
        try {
            RequirementPolicyApprovalResult approved = transactionAdapter.approve(
                    fixture.approval("consumer-rollback", "approved before rollback"),
                    "ADMIN_API", fixture.now + 40L);
            RequirementStageCommand claimed = stageCommandStore.claim(
                    approved.approvalResumeCommand().commandId(), "policy-smoke-worker",
                    fixture.now + 41L, 60_000L).orElseThrow();
            jdbcTemplate.execute("""
                    CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      RAISE EXCEPTION 'policy smoke injected continuation failure';
                    END;
                    $$
                    """.formatted(functionName));
            jdbcTemplate.execute("""
                    CREATE TRIGGER %s BEFORE INSERT ON rd_requirement_stage_commands
                    FOR EACH ROW WHEN (NEW.task_id = %d AND NEW.stage LIKE 'ROLE_EXECUTION:%%')
                    EXECUTE FUNCTION %s()
                    """.formatted(triggerName, fixture.taskId, functionName));

            assertThrows(RuntimeException.class, () -> transactionAdapter.consumeApproval(
                    claimed, "policy-smoke-worker", fixture.now + 42L));

            assertTask(fixture.taskId, RdTaskStatus.WAITING_APPROVAL, 8L, 10L);
            RequirementPolicyRun afterRollback = policyRunStore.findById(
                    String.valueOf(fixture.policyRunId)).orElseThrow();
            assertEquals(RequirementPolicyRunState.APPROVED, afterRollback.state());
            assertEquals(0, countEvents(fixture.taskId, "EXECUTING"));
            assertEquals(0, jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM rd_requirement_stage_commands
                     WHERE task_id = ? AND stage LIKE 'ROLE_EXECUTION:%'
                    """, Integer.class, fixture.taskId));
            assertEquals(RequirementStageCommand.Status.RUNNING,
                    stageCommandStore.findById(claimed.commandId()).orElseThrow().status());
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName + " ON rd_requirement_stage_commands");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + functionName + "()");
            fixture.cleanup();
        }
    }

    @Test
    void concurrentDuplicateApprovalConsumptionCommitsOnceAndReplaysTheSameContinuation() throws Exception {
        Fixture fixture = fixture();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            RequirementPolicyApprovalResult approved = transactionAdapter.approve(
                    fixture.approval("consumer-concurrent", "approved for concurrent consume"),
                    "ADMIN_API", fixture.now + 50L);
            RequirementStageCommand claimed = stageCommandStore.claim(
                    approved.approvalResumeCommand().commandId(), "policy-smoke-worker",
                    fixture.now + 51L, 60_000L).orElseThrow();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<String> consume = () -> {
                ready.countDown();
                start.await();
                return transactionAdapter.consumeApproval(
                        claimed, "policy-smoke-worker", fixture.now + 52L).nextCommand().commandId();
            };
            List<Future<String>> results = List.of(pool.submit(consume), pool.submit(consume));
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();

            assertEquals(results.get(0).get(), results.get(1).get());
            assertTask(fixture.taskId, RdTaskStatus.EXECUTING, 9L, 11L);
            assertEquals(1, countEvents(fixture.taskId, "EXECUTING"));
            assertEquals(2, totalCommands(fixture.taskId));
        } finally {
            pool.shutdownNow();
            fixture.cleanup();
        }
    }

    private Callable<Boolean> concurrentApproval(
            Fixture fixture, String note, CountDownLatch ready, CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                transactionAdapter.approve(fixture.approval("request-1", note), "ADMIN_API", fixture.now + 30L);
                return true;
            } catch (IllegalStateException expectedConflict) {
                return false;
            }
        };
    }

    private Fixture fixture() {
        long taskId = 8_750_000_000_000_000_000L + Math.abs(System.nanoTime()) % 1_000_000L;
        long policyRunId = taskId + 1L;
        long now = System.currentTimeMillis();
        String planJson = "{\"plan\":\"real-postgres-smoke\"}";
        String policyJson = "{\"action\":\"WAITING_APPROVAL\"}";
        jdbcTemplate.update("""
                INSERT INTO rd_tasks
                    (id, task_type, ticket_id, priority, status, title, project_id, version, fencing_token)
                VALUES (?, ?, ?, 'P1', 'WAITING_APPROVAL', 'policy transaction smoke', ?, 7, 9)
                """, taskId, RdRequirementTask.TASK_TYPE, "policy-smoke-" + taskId, String.valueOf(taskId));
        RequirementPolicyRun planReady = new RequirementPolicyRun(
                String.valueOf(policyRunId), String.valueOf(taskId), 5L, 7L,
                planJson, RequirementPolicyRun.canonicalJsonDigest(planJson),
                "", "", "", RequirementPolicyRunState.PLAN_READY, 5L, 7L,
                null, null, "", "", "", 0L, "", "", 0L, 0L, now, now);
        policyRunStore.createOrGet(planReady);
        RequirementPolicyRun decided = new RequirementPolicyRun(
                planReady.id(), planReady.taskId(), 5L, 7L, planJson, planReady.planDigest(),
                policyJson, RequirementPolicyRun.canonicalJsonDigest(policyJson), "WAITING_APPROVAL",
                RequirementPolicyRunState.POLICY_DECIDED, 6L, 8L,
                null, null, "", "", "", 0L, "", "", 0L, 1L, now, now);
        policyRunStore.compareAndSet(decided, RequirementPolicyRunState.PLAN_READY, 0L);
        RequirementPolicyRun waiting = new RequirementPolicyRun(
                decided.id(), decided.taskId(), 5L, 7L, planJson, decided.planDigest(),
                policyJson, decided.policyDigest(), "WAITING_APPROVAL",
                RequirementPolicyRunState.WAITING_APPROVAL, 7L, 9L,
                null, null, "", "", "", 0L, "", "", 0L, 2L, now, now);
        policyRunStore.compareAndSet(waiting, RequirementPolicyRunState.POLICY_DECIDED, 1L);
        return new Fixture(taskId, policyRunId, now, waiting.planDigest(), waiting.policyDigest());
    }

    private EvaluationFixture evaluationFixture() {
        long taskId = 8_760_000_000_000_000_000L + Math.abs(System.nanoTime()) % 1_000_000L;
        long policyRunId = taskId + 1L;
        long now = System.currentTimeMillis();
        String planJson = RequirementPolicyRun.canonicalizeJson("{\"plan\":\"evaluation-real-postgres-smoke\"}");
        jdbcTemplate.update("""
                INSERT INTO rd_tasks
                    (id, task_type, ticket_id, priority, status, title, project_id, version, fencing_token)
                VALUES (?, ?, ?, 'P1', 'PLAN_GENERATED', 'policy evaluation transaction smoke', ?, 5, 7)
                """, taskId, RdRequirementTask.TASK_TYPE, "policy-evaluation-smoke-" + taskId, String.valueOf(taskId));
        RequirementPolicyRun planReady = new RequirementPolicyRun(
                String.valueOf(policyRunId), String.valueOf(taskId), 5L, 7L,
                planJson, RequirementPolicyRun.canonicalJsonDigest(planJson),
                "", "", "", RequirementPolicyRunState.PLAN_READY, 5L, 7L,
                null, null, "", "", "", 0L, "", "", 0L, 0L, now, now);
        policyRunStore.createOrGet(planReady);
        RequirementStageCommand pendingEvaluation = commandFactory.createPendingCommand(
                String.valueOf(taskId), 5L, 7L, "REQUIREMENT_DELIVERY", "POLICY_EVALUATE",
                String.valueOf(taskId), "P1", "", String.valueOf(policyRunId), now);
        RequirementStageCommand persistedEvaluation = stageCommandStore.enqueue(pendingEvaluation);
        RequirementStageCommand claimedEvaluation = stageCommandStore.claim(
                persistedEvaluation.commandId(), "policy-evaluate-smoke-worker", now + 1L, 60_000L).orElseThrow();
        return new EvaluationFixture(taskId, policyRunId, now, planReady.planDigest(), claimedEvaluation);
    }

    private PolicyApplyFixture policyApplyFixture(String policyAction) {
        long taskId = 8_770_000_000_000_000_000L + Math.abs(System.nanoTime()) % 1_000_000L;
        long policyRunId = taskId + 1L;
        long now = System.currentTimeMillis();
        String planJson = RequirementPolicyRun.canonicalizeJson("{\"plan\":\"policy-apply-real-postgres-smoke\"}");
        String policyJson = RequirementPolicyRun.canonicalizeJson(
                "{\"policyAction\":\"" + policyAction + "\",\"reason\":\"real policy-apply smoke\"}");
        jdbcTemplate.update("""
                INSERT INTO rd_tasks
                    (id, task_type, ticket_id, priority, status, title, project_id, version, fencing_token)
                VALUES (?, ?, ?, 'P1', 'WAITING_POLICY', 'policy apply transaction smoke', ?, 6, 8)
                """, taskId, RdRequirementTask.TASK_TYPE, "policy-apply-smoke-" + taskId, String.valueOf(taskId));
        RequirementPolicyRun decided = new RequirementPolicyRun(
                String.valueOf(policyRunId), String.valueOf(taskId), 5L, 7L,
                planJson, RequirementPolicyRun.canonicalJsonDigest(planJson),
                policyJson, RequirementPolicyRun.canonicalJsonDigest(policyJson), policyAction,
                RequirementPolicyRunState.POLICY_DECIDED, 6L, 8L,
                null, null, "", "", "", 0L, "", "", 0L, 1L, now, now);
        policyRunStore.createOrGet(decided);
        RequirementStageCommand pendingApply = commandFactory.createPendingCommand(
                String.valueOf(taskId), 6L, 8L, "REQUIREMENT_DELIVERY", "POLICY_APPLY",
                String.valueOf(taskId), "P1", "", String.valueOf(policyRunId), now);
        RequirementStageCommand persistedApply = stageCommandStore.enqueue(pendingApply);
        RequirementStageCommand claimedApply = stageCommandStore.claim(
                persistedApply.commandId(), "policy-apply-smoke-worker", now + 1L, 60_000L).orElseThrow();
        return new PolicyApplyFixture(taskId, policyRunId, now, claimedApply);
    }

    private void assertTask(long taskId, RdTaskStatus status, long version, long fence) {
        assertEquals(status.name(), jdbcTemplate.queryForObject(
                "SELECT status FROM rd_tasks WHERE id = ?", String.class, taskId));
        assertEquals(version, jdbcTemplate.queryForObject(
                "SELECT version FROM rd_tasks WHERE id = ?", Long.class, taskId));
        assertEquals(fence, jdbcTemplate.queryForObject(
                "SELECT fencing_token FROM rd_tasks WHERE id = ?", Long.class, taskId));
    }

    private int countEvents(long taskId, String status) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rd_task_status_events WHERE task_id = ? AND status = ?",
                Integer.class, taskId, status);
    }

    private int countCommands(long taskId, String role, String stage) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM rd_requirement_stage_commands
                 WHERE task_id = ? AND role = ? AND stage = ?
                """, Integer.class, taskId, role, stage);
    }

    private int totalCommands(long taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rd_requirement_stage_commands WHERE task_id = ?", Integer.class, taskId);
    }

    private final class Fixture {
        private final long taskId;
        private final long policyRunId;
        private final long now;
        private final String planDigest;
        private final String policyDigest;

        private Fixture(long taskId, long policyRunId, long now, String planDigest, String policyDigest) {
            this.taskId = taskId;
            this.policyRunId = policyRunId;
            this.now = now;
            this.planDigest = planDigest;
            this.policyDigest = policyDigest;
        }

        private ApproveRequirementPolicyCommand approval(String requestId, String note) {
            return new ApproveRequirementPolicyCommand(
                    String.valueOf(taskId), String.valueOf(policyRunId), 7L, 9L,
                    planDigest, policyDigest, requestId, "APPROVED", note);
        }

        private void cleanup() {
            eventStore.deleteByTask(String.valueOf(taskId));
            jdbcTemplate.update("DELETE FROM rd_requirement_policy_runs WHERE id = ?", policyRunId);
            jdbcTemplate.update("DELETE FROM rd_requirement_stage_commands WHERE task_id = ?", taskId);
            taskMapper.deleteById(taskId);
        }
    }

    private final class EvaluationFixture {
        private final long taskId;
        private final long policyRunId;
        private final long now;
        private final String planDigest;
        private final RequirementStageCommand claimedEvaluation;

        private EvaluationFixture(
                long taskId, long policyRunId, long now, String planDigest, RequirementStageCommand claimedEvaluation
        ) {
            this.taskId = taskId;
            this.policyRunId = policyRunId;
            this.now = now;
            this.planDigest = planDigest;
            this.claimedEvaluation = claimedEvaluation;
        }

        private RecordRequirementPolicyDecisionCommand decision() {
            String policyJson = RequirementPolicyRun.canonicalizeJson(
                    "{\"reason\":\"real smoke\",\"policyAction\":\"ALLOWED\"}");
            return new RecordRequirementPolicyDecisionCommand(
                    String.valueOf(policyRunId), String.valueOf(taskId), 5L, 7L,
                    planDigest, policyJson, RequirementPolicyRun.canonicalJsonDigest(policyJson), "ALLOWED");
        }

        private void cleanup() {
            eventStore.deleteByTask(String.valueOf(taskId));
            jdbcTemplate.update("DELETE FROM rd_requirement_stage_commands WHERE task_id = ?", taskId);
            jdbcTemplate.update("DELETE FROM rd_requirement_policy_runs WHERE id = ?", policyRunId);
            taskMapper.deleteById(taskId);
        }
    }

    private record PolicyApplyExpectation(
            String action,
            RequirementPolicyApplyDisposition disposition,
            RequirementPolicyRunState state,
            RdTaskStatus taskStatus
    ) { }

    private final class PolicyApplyFixture {
        private final long taskId;
        private final long policyRunId;
        private final long now;
        private final RequirementStageCommand claimedApply;

        private PolicyApplyFixture(long taskId, long policyRunId, long now, RequirementStageCommand claimedApply) {
            this.taskId = taskId;
            this.policyRunId = policyRunId;
            this.now = now;
            this.claimedApply = claimedApply;
        }

        private void cleanup() {
            eventStore.deleteByTask(String.valueOf(taskId));
            jdbcTemplate.update("DELETE FROM rd_requirement_stage_commands WHERE task_id = ?", taskId);
            jdbcTemplate.update("DELETE FROM rd_requirement_policy_runs WHERE id = ?", policyRunId);
            taskMapper.deleteById(taskId);
        }
    }
}
