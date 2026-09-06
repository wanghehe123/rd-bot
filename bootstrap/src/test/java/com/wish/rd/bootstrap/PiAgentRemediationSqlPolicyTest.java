package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PiAgentRemediationSqlPolicyTest {

    @Test
    void shouldProvideDurableBoundedRemediationLedgerAndExclusiveCommandIdentity() throws Exception {
        Path sqlPath = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres/p18_pi_agent_state_and_remediation.sql");
        String sql = Files.readString(sqlPath);

        assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS capabilities_json"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rd_agent_remediation_rounds"));
        assertTrue(sql.contains("kind IN ('QA_PRODUCT_FIX', 'QA_PROTOCOL_RETRY')"));
        assertTrue(sql.contains("status IN ('PLANNED', 'CLAIMED', 'DISPATCHED', 'COMPLETED', 'FAILED_NEEDS_HUMAN')"));
        assertTrue(sql.contains("request_json") && sql.contains("request_hash"));
        assertTrue(sql.contains("octet_length(request_json::text) BETWEEN 2 AND 65536"));
        assertTrue(sql.contains("row_version") && sql.contains("lease_owner") && sql.contains("lease_until"));
        assertTrue(sql.contains("source_stage_run_id") && sql.contains("source_command_id"));
        assertTrue(sql.contains("target_coding_stage_run_id") && sql.contains("target_qa_stage_run_id"));
        assertTrue(sql.contains("first_command_id"));
        assertTrue(sql.contains("coding_profile_snapshot_id") && sql.contains("qa_profile_snapshot_id"));
        assertTrue(sql.contains("uk_rd_agent_remediation_rounds_task_kind_no"));
        assertTrue(sql.contains("(task_id, kind, remediation_no)"));
        assertTrue(sql.contains("uk_rd_agent_remediation_rounds_source_kind"));
        assertTrue(sql.contains("(source_stage_run_id, kind)"));

        assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS remediation_round_id"));
        assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS remediation_kind"));
        assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS remediation_no"));
        assertTrue(sql.contains("fk_rd_requirement_stage_command_remediation_round"));
        assertTrue(sql.contains("ck_rd_requirement_stage_command_generation_v2"));
        assertTrue(sql.contains("retry_checkpoint_id IS NULL AND remediation_round_id IS NULL"));
        assertTrue(sql.contains("retry_checkpoint_id IS NOT NULL AND remediation_round_id IS NULL"));
        assertTrue(sql.contains("retry_checkpoint_id IS NULL AND remediation_round_id IS NOT NULL"));
        assertTrue(sql.contains("uk_rd_requirement_stage_commands_remediation_identity"));
    }

    @Test
    void p20AcceptsHostVerifyFixWithoutChangingProductFixBudget() throws Exception {
        Path sqlPath = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres/p20_task_audited_state.sql");
        String sql = Files.readString(sqlPath);
        assertTrue(sql.contains("HOST_VERIFY_FIX"));
        assertTrue(sql.contains("kind IN ('QA_PRODUCT_FIX', 'QA_PROTOCOL_RETRY', 'HOST_VERIFY_FIX')"));
        assertTrue(sql.contains("(kind = 'QA_PRODUCT_FIX' AND remediation_no BETWEEN 1 AND 2)"));
        assertTrue(sql.contains("(kind = 'HOST_VERIFY_FIX' AND remediation_no BETWEEN 1 AND 2)"));
        assertTrue(sql.contains("ck_rd_agent_remediation_rounds_kind"));
        assertTrue(sql.contains("ck_rd_agent_remediation_rounds_number"));
        assertTrue(sql.contains("ck_rd_agent_remediation_rounds_targets"));
    }

    @Test
    void p21AllowsHostVerifyFixOnStageCommandGenerationConstraint() throws Exception {
        Path sqlPath = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres/p21_host_verify_fix_command_generation.sql");
        String sql = Files.readString(sqlPath);
        assertTrue(sql.contains("ck_rd_requirement_stage_command_generation_v2"));
        assertTrue(sql.contains("HOST_VERIFY_FIX"));
        assertTrue(sql.contains(
                "remediation_kind IN ('QA_PRODUCT_FIX', 'QA_PROTOCOL_RETRY', 'HOST_VERIFY_FIX')"));
    }

    @Test
    void p22AddsManagerDecisionsAndManagerGapFixWithoutWeakeningHostVerifyFixBudget() throws Exception {
        Path sqlPath = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres/p22_task_manager_decisions.sql");
        String sql = Files.readString(sqlPath);
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rd_task_manager_decisions"));
        assertTrue(sql.contains("uk_rd_task_manager_decisions_task_source"));
        assertTrue(sql.contains("UNIQUE (task_id, source_command_id)"));
        assertTrue(sql.contains("MANAGER_GAP_FIX"));
        assertTrue(sql.contains(
                "kind IN ('QA_PRODUCT_FIX', 'QA_PROTOCOL_RETRY', 'HOST_VERIFY_FIX', 'MANAGER_GAP_FIX')"));
        assertTrue(sql.contains("(kind = 'QA_PRODUCT_FIX' AND remediation_no BETWEEN 1 AND 2)"));
        assertTrue(sql.contains("(kind = 'HOST_VERIFY_FIX' AND remediation_no BETWEEN 1 AND 2)"));
        assertTrue(sql.contains("(kind = 'MANAGER_GAP_FIX' AND remediation_no BETWEEN 1 AND 2)"));
        assertTrue(sql.contains("uk_rd_agent_remediation_rounds_coding_source"));
        assertTrue(sql.contains(
                "remediation_kind IN ('QA_PRODUCT_FIX', 'QA_PROTOCOL_RETRY', 'HOST_VERIFY_FIX', 'MANAGER_GAP_FIX')"));
    }
}
