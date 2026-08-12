package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in PostgreSQL smoke for idempotent retry schema migration and same-checkpoint FKs. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "rd.knowledge.store=postgres", "rd.storage.mode=memory", "rd.distributed-lock.mode=local",
        "rd.executor.docker.circuit-breaker.state-store=memory"
})
@EnabledIfSystemProperty(named = "rd.integration.retry-schema.enabled", matches = "true")
@Sql(scripts = {"/sql/postgres/p0_knowledge_productionization.sql", "/sql/postgres/p1_multi_agent_orchestration.sql",
        "/sql/postgres/p2_rag_retrieval_state.sql", "/sql/postgres/p3_task_retry_ai_review.sql",
        "/sql/postgres/p1_multi_agent_orchestration.sql", "/sql/postgres/p3_task_retry_ai_review.sql"},
        config = @SqlConfig(separator = ";\n\n"), executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class PostgresRetrySchemaConstraintRealSmokeTest {

    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("rd.integration.retry-schema.url",
                "jdbc:postgresql://127.0.0.1:55432/rdbot_acceptance?client_encoding=UTF8"));
        registry.add("spring.datasource.username", () -> System.getProperty("rd.integration.retry-schema.username", "postgres"));
        registry.add("spring.datasource.password", () -> System.getProperty("rd.integration.retry-schema.password", "postgres"));
    }

    @Test
    void migrationIsRepeatableAndRejectsCrossCheckpointCommandBinding() {
        assertEquals(1, jdbc.queryForObject("SELECT (to_regclass('rd_task_retry_attempt_bindings') IS NOT NULL)::int", Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_constraint c JOIN pg_class r ON r.oid = c.conrelid
                 WHERE r.relname = 'rd_requirement_stage_commands'
                   AND c.conname = 'fk_rd_requirement_stage_command_binding_checkpoint' AND c.convalidated
                """, Integer.class));
        String activeIndex = jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes WHERE schemaname=current_schema()
                  AND indexname='uk_rd_task_retry_checkpoint_one_active'
                """, String.class);
        assertTrue(activeIndex.contains("WHERE") && activeIndex.contains("WAITING_APPROVAL"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_indexes WHERE schemaname = current_schema()
                  AND tablename = 'rd_task_retry_checkpoints'
                  AND indexname = 'uk_rd_task_retry_checkpoint_one_active'
                """, Integer.class));

        long base = 7_800_000_000_000_000_000L + Math.abs(System.nanoTime() % 1_000_000L) * 10L;
        long task = base, stage = base + 1, firstCheckpoint = base + 2, secondCheckpoint = base + 3;
        long binding = base + 4, command = base + 5, retrieval = base + 6;
        long retrievalBinding = base + 7, wrongRetrieval = base + 8, wrongBinding = base + 9;
        long stageTwo = base + 10, parentTwo = base + 11, crossParentRetrieval = base + 12;
        long crossParentChild = base + 13, orphanRetrieval = base + 14, policy = base + 15;
        try {
            jdbc.update("INSERT INTO rd_tasks(id, task_type, status) VALUES (?, 'REQUIREMENT', 'FAILED_NEEDS_HUMAN')", task);
            jdbc.update("""
                    INSERT INTO rd_agent_stage_runs(id, task_id, role, status, attempt_no, idempotency_key)
                    VALUES (?, ?, 'CODING_AGENT', 'PENDING', 1, ?)
                    """, stage, task, "retry-schema-" + stage);
            jdbc.update("""
                    INSERT INTO rd_task_retry_checkpoints(id,task_id,failure_phase,attempt_no,idempotency_key,
                    source_task_status,source_task_version,status) VALUES (?,?,'AGENT_ROLE',1,?,'FAILED_NEEDS_HUMAN',1,'CREATED')
                    """,
                    firstCheckpoint, task, "retry-schema-cp-" + firstCheckpoint);
            jdbc.update("""
                    INSERT INTO rd_task_retry_checkpoints(id,task_id,failure_phase,attempt_no,idempotency_key,
                    source_task_status,source_task_version,status) VALUES (?,?,'AGENT_ROLE',2,?,'FAILED_NEEDS_HUMAN',1,'SUCCEEDED')
                    """,
                    secondCheckpoint, task, "retry-schema-cp-" + secondCheckpoint);
            assertThrows(DataAccessException.class, () -> jdbc.update("""
                    INSERT INTO rd_task_retry_checkpoints(id,task_id,failure_phase,attempt_no,idempotency_key,
                    source_task_status,source_task_version,status) VALUES (?,?, 'AGENT_ROLE',3,?,'FAILED_NEEDS_HUMAN',1,'CREATED')
                    """, base + 16, task, "retry-schema-active-" + base));
            assertThrows(DataAccessException.class, () -> jdbc.update("""
                    INSERT INTO rd_task_retry_checkpoints(id,task_id,failure_phase,attempt_no,idempotency_key,
                    source_task_status,source_task_version,source_fencing_token,business_generation,status)
                    VALUES (?,?, 'AGENT_ROLE',3,?,'FAILED_NEEDS_HUMAN',1,1,?,'SUCCEEDED')
                    """, base + 17, task, "retry-schema-invalid-" + base, base + 17));
            jdbc.update("""
                    INSERT INTO rd_task_retry_attempt_bindings(id,checkpoint_id,binding_kind,role,stage_run_id,attempt_no,ordinal)
                    VALUES (?,?,'AGENT_STAGE','CODING_AGENT',?,1,0)
                    """, binding, firstCheckpoint, stage);
            jdbc.update("""
                    INSERT INTO rd_requirement_stage_commands(id,task_id,task_version,fencing_token,role,stage,
                    attempt_no,max_attempts,status) VALUES (?,?,1,1,'CODING_AGENT','ROLE_EXECUTION:CODING_AGENT',0,3,'PENDING')
                    """, command, task);
            jdbc.update("""
                    INSERT INTO rd_requirement_stage_commands(id,task_id,task_version,fencing_token,role,stage,
                    retry_checkpoint_id,business_generation,target_retry_binding_id,attempt_no,max_attempts,status)
                    VALUES (?,?,1,1,'CODING_AGENT','ROLE_EXECUTION:CODING_AGENT',?,?,?,0,3,'PENDING')
                    """, command + 1, task, firstCheckpoint, firstCheckpoint, binding);
            jdbc.update("""
                    INSERT INTO rd_requirement_policy_runs(id,task_id,source_task_version,source_fence,plan_json,plan_digest,
                    policy_json,policy_digest,policy_action,state,bound_task_version,bound_fence,
                    approval_expected_task_version,approval_expected_fence,approval_request_id,approved_by,approved_at,
                    approval_resume_command_id,ledger_version)
                    VALUES (?,?,99,99,'{}',?,'{}',?,'ALLOWED','SUPERSEDED',99,99,99,99,?,'operator',now(),?,0)
                    """, policy, task, "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                    "approved-" + policy, command);
            assertEquals(command, jdbc.queryForObject("""
                    SELECT id FROM rd_requirement_stage_commands WHERE task_id=? AND role='CODING_AGENT'
                      AND stage='ROLE_EXECUTION:CODING_AGENT' AND retry_checkpoint_id IS NULL
                    """, Long.class, task));
            assertThrows(DataAccessException.class, () -> jdbc.update("""
                    INSERT INTO rd_requirement_stage_commands(id,task_id,task_version,fencing_token,role,stage,
                    retry_checkpoint_id,business_generation,target_retry_binding_id,attempt_no,max_attempts,status)
                    VALUES (?,?,1,1,'CODING_AGENT','ROLE_EXECUTION:CODING_AGENT',?,?,?,0,3,'PENDING')
                    """,
                    command + 2, task, secondCheckpoint, secondCheckpoint, binding));
            jdbc.update("""
                    INSERT INTO rd_rag_retrieval_runs(id,task_id,consumer_type,role,stage_run_id,attempt_no,idempotency_key,status)
                    VALUES (?,?,'ROLE','CODING_AGENT',?,1,?,'PENDING')
                    """, retrieval, task, stage, "retry-schema-retrieval-" + retrieval);
            jdbc.update("""
                    INSERT INTO rd_task_retry_attempt_bindings(id,checkpoint_id,binding_kind,role,retrieval_run_id,parent_binding_id,attempt_no,ordinal)
                    VALUES (?,?,'RETRIEVAL','CODING_AGENT',?,?,1,0)
                    """, retrievalBinding, firstCheckpoint, retrieval, binding);
            jdbc.update("""
                    INSERT INTO rd_rag_retrieval_runs(id,task_id,consumer_type,role,stage_run_id,attempt_no,idempotency_key,status)
                    VALUES (?,?,'ROLE','CODING_AGENT',?,1,?,'PENDING')
                    """, wrongRetrieval, task, stage, "retry-schema-retrieval-" + wrongRetrieval);
            assertThrows(DataAccessException.class, () -> jdbc.update("""
                    INSERT INTO rd_task_retry_attempt_bindings(id,checkpoint_id,binding_kind,role,retrieval_run_id,parent_binding_id,attempt_no,ordinal)
                    VALUES (?,?,'RETRIEVAL','QA_AGENT',?,?,1,1)
                    """, wrongBinding, firstCheckpoint, wrongRetrieval, binding));
            jdbc.update("""
                    INSERT INTO rd_agent_stage_runs(id, task_id, role, status, attempt_no, idempotency_key)
                    VALUES (?, ?, 'CODING_AGENT', 'PENDING', 2, ?)
                    """, stageTwo, task, "retry-schema-" + stageTwo);
            jdbc.update("""
                    INSERT INTO rd_task_retry_attempt_bindings(id,checkpoint_id,binding_kind,role,stage_run_id,attempt_no,ordinal)
                    VALUES (?,?,'AGENT_STAGE','CODING_AGENT',?,2,0)
                    """, parentTwo, secondCheckpoint, stageTwo);
            jdbc.update("""
                    INSERT INTO rd_rag_retrieval_runs(id,task_id,consumer_type,role,stage_run_id,attempt_no,idempotency_key,status)
                    VALUES (?,?,'ROLE','CODING_AGENT',?,2,?,'PENDING')
                    """, crossParentRetrieval, task, stageTwo, "retry-schema-retrieval-" + crossParentRetrieval);
            assertThrows(DataAccessException.class, () -> jdbc.update("""
                    INSERT INTO rd_task_retry_attempt_bindings(id,checkpoint_id,binding_kind,role,retrieval_run_id,parent_binding_id,attempt_no,ordinal)
                    VALUES (?,?,'RETRIEVAL','CODING_AGENT',?,?,2,2)
                    """, crossParentChild, firstCheckpoint, crossParentRetrieval, parentTwo));
            jdbc.update("""
                    INSERT INTO rd_rag_retrieval_runs(id,task_id,consumer_type,role,stage_run_id,attempt_no,idempotency_key,status)
                    VALUES (?,?,'ROLE','CODING_AGENT',?,1,?,'PENDING')
                    """, orphanRetrieval, task, stage, "retry-schema-retrieval-" + orphanRetrieval);
            assertThrows(DataAccessException.class, () -> jdbc.update("""
                    INSERT INTO rd_task_retry_attempt_bindings(id,checkpoint_id,binding_kind,role,retrieval_run_id,attempt_no,ordinal)
                    VALUES (?,?,'RETRIEVAL','CODING_AGENT',?,1,3)
                    """, base + 18, firstCheckpoint, orphanRetrieval));
        } finally {
            jdbc.update("DELETE FROM rd_requirement_policy_runs WHERE id = ?", policy);
            jdbc.update("DELETE FROM rd_task_retry_attempt_bindings WHERE id IN (?, ?)", retrievalBinding, wrongBinding);
            jdbc.update("DELETE FROM rd_task_retry_attempt_bindings WHERE id IN (?, ?)", parentTwo, crossParentChild);
            jdbc.update("DELETE FROM rd_rag_retrieval_runs WHERE id IN (?, ?, ?, ?)", retrieval, wrongRetrieval, crossParentRetrieval, orphanRetrieval);
            jdbc.update("DELETE FROM rd_requirement_stage_commands WHERE id IN (?, ?, ?)", command, command + 1, command + 2);
            jdbc.update("DELETE FROM rd_agent_stage_runs WHERE id = ?", stageTwo);
            jdbc.update("DELETE FROM rd_task_retry_attempt_bindings WHERE id = ?", binding);
            jdbc.update("DELETE FROM rd_task_retry_checkpoints WHERE id IN (?, ?)", firstCheckpoint, secondCheckpoint);
            jdbc.update("DELETE FROM rd_agent_stage_runs WHERE id = ?", stage);
            jdbc.update("DELETE FROM rd_tasks WHERE id = ?", task);
        }
    }
}
