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
}
