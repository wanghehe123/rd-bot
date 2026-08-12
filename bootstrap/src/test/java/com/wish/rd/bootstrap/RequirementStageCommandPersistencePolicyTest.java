package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the PostgreSQL stage-command table and SKIP LOCKED claim adapter are present. */
class RequirementStageCommandPersistencePolicyTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();

    @Test
    void shouldProvidePersistentStageCommandSchemaAndMapper() throws Exception {
        String sql = Files.readString(PROJECT_ROOT.resolve(
                "bootstrap/src/main/resources/sql/postgres/p1_multi_agent_orchestration.sql"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rd_requirement_stage_commands"));
        assertTrue(sql.contains("task_version"));
        assertTrue(sql.contains("fencing_token"));
        assertTrue(sql.contains("next_visible_at"));
        assertTrue(sql.contains("idx_rd_requirement_stage_commands_claim"));
        assertTrue(sql.contains("uk_rd_requirement_stage_commands_identity"));
        assertTrue(sql.contains("approval_resume_command_id"));
        assertTrue(sql.contains("REFERENCES rd_requirement_stage_commands(id) ON DELETE RESTRICT"));
        assertTrue(sql.contains("uk_rd_requirement_policy_runs_approval_resume_command"));

        String mapper = Files.readString(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RequirementStageCommandMapper.java"));
        assertTrue(mapper.contains("FOR UPDATE SKIP LOCKED"));
        assertTrue(mapper.contains("attempt_no < max_attempts"));
        assertTrue(mapper.contains("EXTRACT(EPOCH FROM (#{now} - created_at))"));

        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RequirementStageCommandRow.java")));
        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RequirementStageCommandMapper.java")));
        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRequirementStageCommandStore.java")));
    }
}
