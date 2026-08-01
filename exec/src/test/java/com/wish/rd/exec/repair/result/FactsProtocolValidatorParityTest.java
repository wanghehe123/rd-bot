package com.wish.rd.exec.repair.result;

import com.wish.rd.rag.project.agent.model.FactFreshnessEvaluator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactsProtocolValidatorParityTest {

    private final StructuredResultValidator validator = new StructuredResultValidator();
    private final AgentRoleResultValidator roleValidator = new AgentRoleResultValidator();
    private final FactFreshnessEvaluator.FreshnessContext context = new FactFreshnessEvaluator.FreshnessContext(
            "abc123",
            "ws-1",
            Instant.parse("2026-08-01T09:00:00Z"),
            FactFreshnessEvaluator.DEFAULT_MAX_TTL
    );

    @Test
    void factsV1SharedFixturePassesHostValidation() throws IOException {
        String json = readFixture("facts/facts-v1-valid-result.json");
        assertTrue(validator.validate(json, "FACTS_V1", context).valid());
        assertTrue(roleValidator.validate("CODING_AGENT", json, "FACTS_V1", context).valid());
    }

    @Test
    void factsV1MissingFactsFailsHostValidation() {
        String json = """
                {
                  "status": "SUCCESS",
                  "summary": "summary",
                  "changedFiles": ["src/App.java"],
                  "testCommands": ["npm test"],
                  "testStatus": "PASSED",
                  "riskLevel": "LOW",
                  "prBody": "body",
                  "needHumanAction": false
                }
                """;
        var validation = validator.validate(json, "FACTS_V1", context);
        assertFalse(validation.valid());
        assertTrue(validation.errors().contains("facts must be present when contextProtocolVersion is FACTS_V1"));
    }

    @Test
    void legacyEnvironmentNotesOnlyFixturePassesLegacyMode() {
        String json = """
                {
                  "status": "SUCCESS",
                  "summary": "summary",
                  "changedFiles": ["src/App.java"],
                  "testCommands": ["npm test"],
                  "testStatus": "PASSED",
                  "riskLevel": "LOW",
                  "prBody": "body",
                  "needHumanAction": false,
                  "environmentNotes": ["npm install requires --include=dev in this workspace"]
                }
                """;
        assertTrue(validator.validate(json).valid());
    }

    @Test
    void factsV1RejectsMismatchedEnvironmentNotes() {
        String json = """
                {
                  "status": "SUCCESS",
                  "summary": "summary",
                  "changedFiles": ["src/App.java"],
                  "testCommands": ["npm test"],
                  "testStatus": "PASSED",
                  "riskLevel": "LOW",
                  "prBody": "body",
                  "needHumanAction": false,
                  "facts": [{
                    "factId": "fact-1",
                    "kind": "OBSERVED",
                    "statement": "npm install requires --include=dev in this workspace",
                    "sourceArtifactId": "artifact-tool-1",
                    "sourceStageRunId": "stage-1",
                    "observedAt": "2026-08-01T08:00:00Z",
                    "repoRevision": "abc123",
                    "freshnessPolicy": "SAME_REVISION"
                  }],
                  "environmentNotes": ["different note"]
                }
                """;
        var validation = validator.validate(json, "FACTS_V1", context);
        assertFalse(validation.valid());
        assertTrue(validation.errors().contains("environmentNotes must match fresh OBSERVED facts derivation"));
    }

    private static String readFixture(String resourcePath) throws IOException {
        Path path = Path.of("src/test/resources").resolve(resourcePath);
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
