package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStrategySqlPolicyTest {

    @Test
    void shouldProvideProjectAgentStrategyAggregateTables() throws Exception {
        Path sql = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres/p14_agent_strategy_profiles.sql");
        String content = Files.readString(sql);

        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_agent_strategy_profiles"));
        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_agent_strategy_role_slots"));
        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_project_agent_strategy_bindings"));
        assertTrue(content.contains("PRIMARY KEY (project_id, strategy_id)"));
        assertTrue(content.contains("PRIMARY KEY (project_id, strategy_id, role)"));
        assertTrue(content.contains("uk_rd_agent_strategy_profiles_project_name"));
        assertTrue(content.contains("strategy_id  VARCHAR(64) NOT NULL"));
        assertTrue(content.contains("LOCAL_DEFAULT"));
        assertTrue(content.contains("dockerfile_text"));
        assertTrue(content.contains("REFERENCES rd_agent_strategy_profiles(project_id, strategy_id)"));
    }
}
