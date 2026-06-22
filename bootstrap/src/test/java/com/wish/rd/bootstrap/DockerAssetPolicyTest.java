package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerAssetPolicyTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> REQUIRED_RESULT_FIELDS = Set.of(
            "status",
            "summary",
            "changedFiles",
            "testCommands",
            "testStatus",
            "riskLevel",
            "needHumanAction"
    );

    @Test
    void dockerImageAssetsShouldExist() {
        assertTrue(Files.isRegularFile(asset("Dockerfile")));
        assertTrue(Files.isRegularFile(asset("rd-claude-entrypoint.sh")));
        assertTrue(Files.isRegularFile(asset("result.schema.json")));
    }

    @Test
    void dockerfileShouldUseNonRootUserAndPinnedConfigurableClaudeVersion() throws IOException {
        String dockerfile = readAsset("Dockerfile");

        assertFalse(dockerfile.startsWith("# syntax=docker/dockerfile:"));
        assertTrue(dockerfile.contains("ARG NODE_BASE_IMAGE=node:22-bookworm-slim"));
        assertTrue(dockerfile.contains("FROM ${NODE_BASE_IMAGE}"));
        assertTrue(dockerfile.contains("apt-get install"));
        assertTrue(dockerfile.contains("git"));
        assertTrue(dockerfile.contains("openssh-client"));
        assertTrue(dockerfile.contains("ca-certificates"));
        assertTrue(dockerfile.contains("jq"));
        assertTrue(dockerfile.contains("sudo"));
        assertTrue(dockerfile.contains("/etc/sudoers.d/rdbot-toolchain"));
        assertTrue(dockerfile.contains("rdbot ALL=(root) NOPASSWD: /usr/bin/apt-get, /usr/bin/apt"));
        assertTrue(dockerfile.contains("chmod 0440 /etc/sudoers.d/rdbot-toolchain"));
        assertTrue(dockerfile.contains("ARG CLAUDE_CODE_VERSION"));
        assertTrue(dockerfile.contains("@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}"));
        assertTrue(dockerfile.contains("latest"));
        assertTrue(dockerfile.contains("grep -Eq"));
        assertTrue(dockerfile.contains("'^[0-9]+\\.[0-9]+\\.[0-9]+$'"));
        assertTrue(dockerfile.contains("/home/rdbot/.claude.json"));
        assertTrue(dockerfile.contains("\"bypassPermissionsModeAccepted\":true"));
        assertTrue(dockerfile.contains("USER rdbot"));
        assertFalse(dockerfile.contains("@latest"));
        assertFalse(containsCredentialCopy(dockerfile));
    }

    @Test
    void entrypointShouldUseWorkOutputOnlyForGeneratedArtifacts() throws IOException {
        String entrypoint = readAsset("rd-claude-entrypoint.sh");

        assertTrue(entrypoint.startsWith("#!/usr/bin/env bash"));
        assertTrue(entrypoint.contains("set -euo pipefail"));
        assertTrue(entrypoint.contains("PROMPT_FILE=\"/work/input/prompt.md\""));
        assertTrue(entrypoint.contains("SCHEMA_FILE=\"/work/input/result.schema.json\""));
        assertTrue(entrypoint.contains("OUTPUT_DIR=\"/work/output\""));
        assertTrue(entrypoint.contains("RESULT_FILE=\"${OUTPUT_DIR}/result.json\""));
        assertTrue(entrypoint.contains("PATCH_FILE=\"${OUTPUT_DIR}/patch.diff\""));
        assertTrue(entrypoint.contains("TEST_LOG_FILE=\"${OUTPUT_DIR}/test.log\""));
        assertTrue(entrypoint.contains("mkdir -p \"$OUTPUT_DIR\""));
        assertTrue(entrypoint.contains("tee \"$CLAUDE_EVENTS_FILE\""));
        assertTrue(entrypoint.contains("\"$SCHEMA_FILE\""));
        assertTrue(entrypoint.contains("\"$RESULT_FILE\""));
        assertTrue(entrypoint.contains("git diff --binary"));
        assertTrue(entrypoint.contains("generated_patch=\"$(mktemp)\""));
        assertTrue(entrypoint.contains("if [[ -s \"$generated_patch\" ]]"));
        assertTrue(entrypoint.contains("if [[ ! -s \"$PATCH_FILE\" ]] && git rev-parse --verify HEAD~1"));
        assertFalse(entrypoint.contains("git diff --binary > \"$PATCH_FILE\""));
        assertTrue(entrypoint.contains("docker-meta.json"));
        assertTrue(entrypoint.contains("PIPESTATUS[0]"));
        assertTrue(entrypoint.contains("RD_CLAUDE_AUTH_TOKEN_ENV"));
        assertTrue(entrypoint.contains("export ANTHROPIC_AUTH_TOKEN"));
        assertFalse(entrypoint.contains("ANTHROPIC_API_KEY="));
        assertFalse(entrypoint.contains(">/work/input"));
        assertFalse(entrypoint.contains(">/work/repo"));
    }

    @Test
    void resultSchemaShouldMatchRepairExecutionProtocol() throws IOException {
        JsonNode schema = OBJECT_MAPPER.readTree(readAsset("result.schema.json"));

        assertEquals("object", schema.path("type").asText());
        Set<String> required = OBJECT_MAPPER.convertValue(
                schema.path("required"),
                OBJECT_MAPPER.getTypeFactory().constructCollectionType(Set.class, String.class)
        );
        assertEquals(REQUIRED_RESULT_FIELDS, required);
        assertEquals("string", schema.path("properties").path("summary").path("type").asText());
        assertEquals("array", schema.path("properties").path("changedFiles").path("type").asText());
        assertEquals("array", schema.path("properties").path("testCommands").path("type").asText());
        assertEquals("string", schema.path("properties").path("prBody").path("type").asText());
        assertEquals("boolean", schema.path("properties").path("needHumanAction").path("type").asText());
        assertEquals("\\S", schema.path("properties").path("summary").path("pattern").asText());
        assertEquals("\\S", schema.path("properties").path("changedFiles").path("items").path("pattern").asText());
        assertEquals("\\S", schema.path("properties").path("testCommands").path("items").path("pattern").asText());
        assertTrue(schema.toString().contains("\"prBody\":{\"type\":\"string\",\"pattern\":\"\\\\S\"}"));
        assertTrue(schema.toString().contains("\"not\":{\"properties\":{\"status\":{\"enum\":[\"NEED_INFO\",\"FAILED\"]}"));
        assertTrue(schema.toString().contains("\"SUCCESS\""));
        assertTrue(schema.toString().contains("\"FAILED\""));
        assertTrue(schema.toString().contains("\"NEED_INFO\""));
        assertTrue(schema.toString().contains("\"UNSAFE\""));
        assertTrue(schema.toString().contains("\"SKIPPED\""));
        assertTrue(schema.toString().contains("\"minItems\":1"));
        assertTrue(schema.toString().contains("\"PASSED\""));
        assertTrue(schema.toString().contains("\"SKIPPED\""));
        assertTrue(schema.toString().contains("\"LOW\""));
        assertTrue(schema.toString().contains("\"HIGH\""));
    }

    private static boolean containsCredentialCopy(String dockerfile) {
        Pattern credentialPattern = Pattern.compile(
                "(?i)\\bCOPY\\b.*(\\.ssh|id_rsa|id_ed25519|\\.aws|\\.config/gcloud|credentials|token|secret)"
        );
        return credentialPattern.matcher(dockerfile).find();
    }

    private static String readAsset(String fileName) throws IOException {
        return Files.readString(asset(fileName), StandardCharsets.UTF_8);
    }

    private static Path asset(String fileName) {
        return moduleRoot().resolve("src/main/resources/executor/claude").resolve(fileName);
    }

    private static Path moduleRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (current.getFileName() != null && "bootstrap".equals(current.getFileName().toString())) {
            return current;
        }
        return current.resolve("bootstrap");
    }
}
