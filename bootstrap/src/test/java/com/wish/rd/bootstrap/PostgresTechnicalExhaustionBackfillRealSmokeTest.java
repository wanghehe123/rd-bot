package com.wish.rd.bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Opt-in real-PostgreSQL smoke for the technical-exhaustion provenance backfill.
 *
 * <p>Uses direct JDBC (no full Spring Boot context) so a fresh {@code rdbot_acceptance}
 * database can be migrated and asserted without seeding unrelated ingestion tables first.
 */
@EnabledIfSystemProperty(named = "rd.integration.retry-schema.enabled", matches = "true")
class PostgresTechnicalExhaustionBackfillRealSmokeTest {

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateFreshSchema() {
        DataSource dataSource = dataSource();
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        // Match PostgresMigrationOrderPolicyTest / schema smoke: statements end with ";\\n\\n".
        populator.setSeparator(";\n\n");
        populator.setContinueOnError(false);
        populator.addScript(new ClassPathResource("sql/postgres/p0_knowledge_productionization.sql"));
        populator.addScript(new ClassPathResource("sql/postgres/p1_multi_agent_orchestration.sql"));
        populator.addScript(new ClassPathResource("sql/postgres/p2_rag_retrieval_state.sql"));
        populator.addScript(new ClassPathResource("sql/postgres/p3_task_retry_ai_review.sql"));
        // Replay once to prove the migration (including backfill) stays idempotent.
        populator.addScript(new ClassPathResource("sql/postgres/p1_multi_agent_orchestration.sql"));
        populator.addScript(new ClassPathResource("sql/postgres/p3_task_retry_ai_review.sql"));
        populator.execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void technicalExhaustionBackfillIsIdempotentRepairsStrandedShapeAndSkipsAmbiguity() {
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_class c
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE c.relkind = 'S'
                   AND c.relname = 'rd_task_failure_provenance_backfill_id_seq'
                """, Integer.class));

        long base = 7_820_000_000_000_000_000L + Math.abs(System.nanoTime() % 1_000_000L) * 20L;
        long strandedTask = base;
        long originalCommand = base + 1;
        long retryCommand = base + 2;
        long checkpoint = base + 3;
        long binding = base + 4;
        long strandedAttempt = base + 5;
        long originalStage = base + 6;
        long policy = base + 7;
        long ambiguousTask = base + 8;
        long ambiguousCheckpointA = base + 9;
        long ambiguousCheckpointB = base + 10;
        long ambiguousBindingA = base + 11;
        long ambiguousBindingB = base + 12;
        long ambiguousAttemptA = base + 13;
        long ambiguousAttemptB = base + 14;
        long ambiguousCommandA = base + 15;
        long ambiguousCommandB = base + 16;
        String planDigest = "sha256:" + "d".repeat(64);
        seedStranded(strandedTask, originalCommand, retryCommand, checkpoint, binding,
                strandedAttempt, originalStage, policy, planDigest);
        seedAmbiguous(ambiguousTask, ambiguousCheckpointA, ambiguousCheckpointB,
                ambiguousBindingA, ambiguousBindingB, ambiguousAttemptA, ambiguousAttemptB,
                ambiguousCommandA, ambiguousCommandB, planDigest);

        rerunBackfill();
        rerunBackfill();

        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM rd_task_failure_provenance
                 WHERE task_id = ? AND failed_task_version = 13 AND failed_task_fencing_token = 14
                   AND failure_kind = 'TECHNICAL_EXHAUSTION_BACKFILL'
                """, Integer.class, strandedTask));
        assertEquals("AGENT_ROLE", jdbc.queryForObject("""
                SELECT failure_phase FROM rd_task_failure_provenance
                 WHERE task_id = ? AND failed_task_version = 13 AND failed_task_fencing_token = 14
                """, String.class, strandedTask));
        assertEquals(retryCommand, jdbc.queryForObject("""
                SELECT failed_stage_command_id FROM rd_task_failure_provenance
                 WHERE task_id = ? AND failed_task_version = 13 AND failed_task_fencing_token = 14
                """, Long.class, strandedTask));
        assertEquals(strandedAttempt, jdbc.queryForObject("""
                SELECT failed_stage_run_id FROM rd_task_failure_provenance
                 WHERE task_id = ? AND failed_task_version = 13 AND failed_task_fencing_token = 14
                """, Long.class, strandedTask));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM rd_agent_stage_runs WHERE id = ?", String.class, strandedAttempt));
        assertEquals(policy, jdbc.queryForObject(
                "SELECT policy_run_id FROM rd_requirement_stage_commands WHERE id = ?",
                Long.class, retryCommand));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM rd_requirement_stage_finalizations
                 WHERE command_id = ? AND attempt_no = 3 AND state = 'PREPARED'
                """, Integer.class, retryCommand));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM rd_task_failure_provenance
                 WHERE task_id = ? AND failure_kind = 'TECHNICAL_EXHAUSTION_BACKFILL'
                """, Integer.class, ambiguousTask));
        assertEquals(13L, jdbc.queryForObject(
                "SELECT version FROM rd_tasks WHERE id = ?", Long.class, strandedTask));
        assertEquals(14L, jdbc.queryForObject(
                "SELECT fencing_token FROM rd_tasks WHERE id = ?", Long.class, strandedTask));
    }

    private static void seedStranded(
            long taskId, long originalCommand, long retryCommand, long checkpointId, long bindingId,
            long strandedAttemptId, long originalStageId, long policyId, String planDigest
    ) {
        jdbc.update("""
                INSERT INTO rd_tasks(id, task_type, status, version, fencing_token)
                VALUES (?, 'REQUIREMENT', 'FAILED_RETRYABLE', 13, 14)
                """, taskId);
        jdbc.update("""
                INSERT INTO rd_requirement_policy_runs(id,task_id,source_task_version,source_fence,plan_json,plan_digest,
                policy_json,policy_digest,policy_action,state,bound_task_version,bound_fence,ledger_version)
                VALUES (?,?,11,12,'{}',?,'{}',?,'ALLOWED','APPLIED',11,12,0)
                """, policyId, taskId, planDigest, "sha256:" + "e".repeat(64));
        jdbc.update("""
                INSERT INTO rd_agent_stage_runs(id, task_id, role, status, attempt_no, idempotency_key)
                VALUES (?, ?, 'CODING_AGENT', 'FAILED_NEEDS_HUMAN', 1, ?)
                """, originalStageId, taskId, "stranded-original-" + originalStageId);
        jdbc.update("""
                INSERT INTO rd_agent_stage_runs(id, task_id, role, status, attempt_no, idempotency_key)
                VALUES (?, ?, 'CODING_AGENT', 'PENDING', 2, ?)
                """, strandedAttemptId, taskId, "stranded-pending-" + strandedAttemptId);
        jdbc.update("""
                INSERT INTO rd_requirement_stage_commands(id,task_id,task_version,fencing_token,role,stage,
                attempt_no,max_attempts,status) VALUES (?,?,11,12,'CODING_AGENT','ROLE_EXECUTION:CODING_AGENT',1,3,'DEAD_LETTERED')
                """, originalCommand, taskId);
        jdbc.update("""
                INSERT INTO rd_task_retry_checkpoints(id,task_id,failure_phase,retry_from_role,failed_stage_run_id,
                attempt_no,idempotency_key,source_task_status,source_task_version,source_fencing_token,
                failed_stage_command_id,failed_stage,source_policy_run_id,source_plan_digest,
                business_generation,status)
                VALUES (?,?,'AGENT_ROLE','CODING_AGENT',?,1,?,'FAILED_NEEDS_HUMAN',11,12,?,?,?,?,?,'FAILED_RETRYABLE')
                """, checkpointId, taskId, originalStageId, "stranded-cp-" + checkpointId,
                originalCommand, "ROLE_EXECUTION:CODING_AGENT", policyId, planDigest, checkpointId);
        jdbc.update("""
                INSERT INTO rd_task_retry_attempt_bindings(id,checkpoint_id,binding_kind,role,stage_run_id,attempt_no,ordinal)
                VALUES (?,?,'AGENT_STAGE','CODING_AGENT',?,2,0)
                """, bindingId, checkpointId, strandedAttemptId);
        jdbc.update("""
                INSERT INTO rd_requirement_stage_commands(id,task_id,task_version,fencing_token,role,stage,
                retry_checkpoint_id,business_generation,target_retry_binding_id,attempt_no,max_attempts,status)
                VALUES (?,?,12,13,'CODING_AGENT','ROLE_EXECUTION:CODING_AGENT',?,?,?,3,3,'DEAD_LETTERED')
                """, retryCommand, taskId, checkpointId, checkpointId, bindingId);
        jdbc.update("""
                UPDATE rd_task_retry_checkpoints
                   SET dispatch_task_version = 12, dispatch_fencing_token = 13, dispatch_command_id = ?
                 WHERE id = ?
                """, retryCommand, checkpointId);
        jdbc.update("""
                INSERT INTO rd_task_failure_provenance(id,task_id,failed_stage_command_id,failed_command_attempt_no,
                failed_stage,failure_phase,outcome_status,failed_task_version,failed_task_fencing_token,
                failed_stage_run_id,source_policy_run_id,source_plan_digest,failure_kind)
                VALUES (?,?,?,1,'ROLE_EXECUTION:CODING_AGENT','AGENT_ROLE','FAILED_NEEDS_HUMAN',11,12,?,?,?,'ORIGINAL')
                """, originalCommand + 50, taskId, originalCommand, originalStageId, policyId, planDigest);
    }

    private static void seedAmbiguous(
            long taskId, long checkpointA, long checkpointB, long bindingA, long bindingB,
            long attemptA, long attemptB, long commandA, long commandB, String planDigest
    ) {
        jdbc.update("""
                INSERT INTO rd_tasks(id, task_type, status, version, fencing_token)
                VALUES (?, 'REQUIREMENT', 'FAILED_RETRYABLE', 5, 6)
                """, taskId);
        jdbc.update("""
                INSERT INTO rd_agent_stage_runs(id, task_id, role, status, attempt_no, idempotency_key)
                VALUES (?, ?, 'CODING_AGENT', 'PENDING', 1, ?)
                """, attemptA, taskId, "ambiguous-pending-a-" + attemptA);
        jdbc.update("""
                INSERT INTO rd_agent_stage_runs(id, task_id, role, status, attempt_no, idempotency_key)
                VALUES (?, ?, 'QA_AGENT', 'PENDING', 1, ?)
                """, attemptB, taskId, "ambiguous-pending-b-" + attemptB);
        jdbc.update("""
                INSERT INTO rd_requirement_stage_commands(id,task_id,task_version,fencing_token,role,stage,
                attempt_no,max_attempts,status) VALUES (?,?,3,4,'CODING_AGENT','ROLE_EXECUTION:CODING_AGENT',1,3,'DEAD_LETTERED')
                """, commandA, taskId);
        jdbc.update("""
                INSERT INTO rd_requirement_stage_commands(id,task_id,task_version,fencing_token,role,stage,
                attempt_no,max_attempts,status) VALUES (?,?,3,4,'QA_AGENT','ROLE_EXECUTION:QA_AGENT',1,3,'DEAD_LETTERED')
                """, commandB, taskId);
        jdbc.update("""
                INSERT INTO rd_task_retry_checkpoints(id,task_id,failure_phase,retry_from_role,failed_stage_run_id,
                attempt_no,idempotency_key,source_task_status,source_task_version,source_fencing_token,
                failed_stage_command_id,failed_stage,source_plan_digest,business_generation,status)
                VALUES (?,?,'AGENT_ROLE','CODING_AGENT',?,1,?,'FAILED_RETRYABLE',4,5,?,?,?,?,'FAILED_RETRYABLE')
                """, checkpointA, taskId, attemptA, "ambiguous-cp-a-" + checkpointA,
                commandA, "ROLE_EXECUTION:CODING_AGENT", planDigest, checkpointA);
        jdbc.update("""
                INSERT INTO rd_task_retry_checkpoints(id,task_id,failure_phase,retry_from_role,failed_stage_run_id,
                attempt_no,idempotency_key,source_task_status,source_task_version,source_fencing_token,
                failed_stage_command_id,failed_stage,source_plan_digest,business_generation,status)
                VALUES (?,?,'AGENT_ROLE','QA_AGENT',?,2,?,'FAILED_RETRYABLE',4,5,?,?,?,?,'FAILED_RETRYABLE')
                """, checkpointB, taskId, attemptB, "ambiguous-cp-b-" + checkpointB,
                commandB, "ROLE_EXECUTION:QA_AGENT", planDigest, checkpointB);
        jdbc.update("""
                INSERT INTO rd_task_retry_attempt_bindings(id,checkpoint_id,binding_kind,role,stage_run_id,attempt_no,ordinal)
                VALUES (?,?,'AGENT_STAGE','CODING_AGENT',?,1,0)
                """, bindingA, checkpointA, attemptA);
        jdbc.update("""
                INSERT INTO rd_task_retry_attempt_bindings(id,checkpoint_id,binding_kind,role,stage_run_id,attempt_no,ordinal)
                VALUES (?,?,'AGENT_STAGE','QA_AGENT',?,1,0)
                """, bindingB, checkpointB, attemptB);
        jdbc.update("""
                UPDATE rd_requirement_stage_commands
                   SET retry_checkpoint_id = ?, business_generation = ?, target_retry_binding_id = ?
                 WHERE id = ?
                """, checkpointA, checkpointA, bindingA, commandA);
        jdbc.update("""
                UPDATE rd_requirement_stage_commands
                   SET retry_checkpoint_id = ?, business_generation = ?, target_retry_binding_id = ?
                 WHERE id = ?
                """, checkpointB, checkpointB, bindingB, commandB);
    }

    private static void rerunBackfill() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setSeparator(";\n\n");
        populator.addScript(new ClassPathResource("sql/postgres/p3_task_retry_ai_review.sql"));
        populator.execute(dataSource());
    }

    private static DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(System.getProperty("rd.integration.retry-schema.url",
                "jdbc:postgresql://127.0.0.1:55432/rdbot_acceptance?client_encoding=UTF8"));
        dataSource.setUsername(System.getProperty("rd.integration.retry-schema.username", "postgres"));
        dataSource.setPassword(System.getProperty("rd.integration.retry-schema.password", "postgres"));
        return dataSource;
    }
}
