package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiAgentOrchestrationSqlPolicyTest {

    @Test
    void shouldProvideProjectManagedPostgresTablesForMultiAgentOrchestration() throws Exception {
        Path sql = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres/p1_multi_agent_orchestration.sql");
        String content = Files.readString(sql);

        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_agent_stage_runs"));
        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_role_context_packages"));
        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_agent_stage_artifacts"));
        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_agent_skill_installations"));
        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_experience_entries"));
        assertTrue(content.contains("uk_rd_agent_stage_runs_idempotency"));
    }

    @Test
    void shouldProvideImmutableFencedRequirementPolicyLedger() throws Exception {
        Path sql = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres/p1_multi_agent_orchestration.sql");
        String content = Files.readString(sql);

        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_requirement_policy_runs"));
        assertTrue(content.contains("source_fence") && content.contains("bound_fence"));
        assertTrue(content.contains("approval_expected_task_version") && content.contains("approval_expected_fence"));
        assertTrue(content.contains("source_task_version >= 0"));
        assertTrue(content.contains("bound_task_version >= 0"));
        assertTrue(content.contains("approval_expected_task_version >= 0"));
        assertTrue(content.contains("approval_expected_fence > 0"));
        assertTrue(content.contains("ledger_version >= 0"));
        assertTrue(content.contains("'^sha256:[0-9a-f]{64}$'"));
        assertTrue(content.contains("uk_rd_requirement_policy_runs_generation"));
        assertTrue(content.contains("uk_rd_requirement_policy_runs_one_active"));
        assertTrue(content.contains("uk_rd_requirement_policy_runs_approval_request"));
        assertTrue(content.contains("uk_rd_requirement_policy_runs_consumed_command"));
        assertTrue(content.contains("ON rd_requirement_policy_runs (task_id, approval_request_id)"));
        assertTrue(content.contains("REFERENCES rd_tasks(id)") && content.contains("REFERENCES rd_requirement_stage_commands(id)"));
        assertTrue(content.contains("ck_rd_requirement_policy_run_approval_expected_pair"));
        assertTrue(content.contains("ck_rd_requirement_policy_run_approval_expected_state"));
        int approvalColumnAlter = content.indexOf("ADD COLUMN IF NOT EXISTS approval_resume_command_id");
        int approvalIndex = content.indexOf("CREATE UNIQUE INDEX IF NOT EXISTS uk_rd_requirement_policy_runs_approval_resume_command");
        assertTrue(approvalColumnAlter >= 0 && approvalIndex > approvalColumnAlter,
                "legacy replay must add approval_resume_command_id before indexing it");
        assertTrue(content.contains("ADD CONSTRAINT fk_rd_requirement_policy_run_approval_resume_command"));
        int policyTable = content.indexOf("CREATE TABLE IF NOT EXISTS rd_requirement_policy_runs");
        int stagePolicyColumn = content.indexOf("ADD COLUMN IF NOT EXISTS policy_run_id");
        int stagePolicyForeignKey = content.indexOf("ADD CONSTRAINT fk_rd_requirement_stage_command_policy_run");
        assertTrue(policyTable >= 0 && stagePolicyColumn > policyTable && stagePolicyForeignKey > stagePolicyColumn,
                "stage policy authorization must be added only after its referenced ledger table exists");
        assertTrue(content.contains("FOREIGN KEY (policy_run_id)")
                        && content.contains("REFERENCES rd_requirement_policy_runs(id) ON DELETE RESTRICT"),
                "stage commands must retain an immutable policy-ledger reference");
    }

    @Test
    void shouldProvideIdempotentScopedExperienceMigration() throws Exception {
        Path sql = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres/p6_evaluation_data_quality.sql");
        String content = Files.readString(sql);

        assertTrue(content.contains("ADD COLUMN IF NOT EXISTS project_id"));
        assertTrue(content.contains("ADD COLUMN IF NOT EXISTS repository_fingerprint"));
        assertTrue(content.contains("ADD COLUMN IF NOT EXISTS evidence_quality"));
        assertTrue(content.contains("ADD COLUMN IF NOT EXISTS applicable_roles_json"));
        assertTrue(content.contains("CREATE INDEX IF NOT EXISTS idx_rd_experience_entries_scope"));
    }

    @Test
    void shouldProvideVerifiedClaudeCodeRuntimeProfileMigration() throws Exception {
        Path sql = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres/p7_project_runtime_profiles.sql");
        String content = Files.readString(sql);

        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_project_runtime_profiles"));
        assertTrue(content.contains("PRIMARY KEY (project_id, role)"));
        assertTrue(content.contains("agent_type = 'CLAUDE_CODE'"));
        assertTrue(content.contains("validation_status = 'VERIFIED'"));
        assertTrue(content.contains("idx_rd_project_runtime_profiles_updated"));
    }

    @Test
    void shouldProvideAdditivePiAgentControlPlaneWithImmutableAndScopedGuards() throws Exception {
        Path sql = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres/p8_pi_agent_runtime.sql");
        String content = Files.readString(sql);

        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_model_provider_profiles"));
        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_agent_execution_profiles"));
        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_agent_execution_profile_snapshots"));
        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_agent_private_artifacts"));
        assertTrue(content.contains("idx_rd_agent_private_artifacts_expiry"));
        assertTrue(content.contains("artifact_uri LIKE 's3://%'") );
        assertTrue(content.contains("UNIQUE (project_id, role, name)"));
        assertTrue(content.contains("PRIMARY KEY (project_id, role)"));
        assertTrue(content.contains("PRIMARY KEY (task_id, role)"));
        assertTrue(content.contains("UNIQUE REFERENCES rd_agent_stage_runs(id)"));
        assertTrue(content.contains("status IN ('UPLOADED', 'VERIFYING', 'VERIFIED', 'REJECTED', 'REVOKED')"));
        assertTrue(content.contains("credential_environment_variable"));
        assertTrue(!content.toLowerCase().contains("api_key"));
        assertTrue(!content.toLowerCase().contains("secret_value"));
        assertTrue(content.contains("'default-qa-v2'"));
        assertTrue(content.contains("deny\":[\"edit\",\"write\"]"));
    }

    @Test
    void shouldProvideDefaultQaV2GateMigration() throws Exception {
        Path sql = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres/p9_default_qa_v2_gate.sql");
        String content = Files.readString(sql);

        assertTrue(content.contains("'default-qa-v2'"));
        assertTrue(content.contains("ON CONFLICT (policy_id, version) DO NOTHING"));
        assertTrue(content.contains("profile_id = 'pi-qa-nextjs-kbr'"));
        assertTrue(content.contains("project_id = 7487468535443230720"));
        assertTrue(content.contains("tool_policy_version < 2"));
        assertTrue(!content.contains("rd_agent_execution_profile_snapshots"));
    }

    @Test
    void shouldProvideSkillHubCatalogAndRoleBindingTables() throws Exception {
        Path sql = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres/p10_skill_hub.sql");
        String content = Files.readString(sql);

        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_skill_catalog"));
        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_skill_role_bindings"));
        assertTrue(content.contains("PRIMARY KEY (role, skill_id)"));
        assertTrue(content.contains("status IN ('ACTIVE', 'WAITING_APPROVAL', 'DISABLED', 'REJECTED')"));
    }
}
