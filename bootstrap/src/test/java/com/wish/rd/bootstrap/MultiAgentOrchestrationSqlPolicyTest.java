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
}
