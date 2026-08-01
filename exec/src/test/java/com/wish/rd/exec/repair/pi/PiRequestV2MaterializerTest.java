package com.wish.rd.exec.repair.pi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.pi.impl.DockerPiAgentExecutor;
import com.wish.rd.rag.project.agent.model.RuntimeContextFileDecision;
import com.wish.rd.rag.project.agent.model.RuntimeContextFileExpectation;
import com.wish.rd.rag.project.agent.model.RuntimeContextManifest;
import com.wish.rd.rag.project.agent.model.RuntimeContextPolicy;
import com.wish.rd.rag.project.agent.model.RuntimeContextPolicyMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiRequestV2MaterializerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldNormalizeProtocolVersionAndDetectV2() {
        assertEquals("v1", PiRequestV2Materializer.normalizeProtocolVersion(null));
        assertEquals("v1", PiRequestV2Materializer.normalizeProtocolVersion(""));
        assertEquals("v1", PiRequestV2Materializer.normalizeProtocolVersion("V1"));
        assertEquals("v2", PiRequestV2Materializer.normalizeProtocolVersion("V2"));
        assertFalse(PiRequestV2Materializer.isV2("v1"));
        assertTrue(PiRequestV2Materializer.isV2("v2"));
        assertThrows(IllegalArgumentException.class, () -> PiRequestV2Materializer.normalizeProtocolVersion("v3"));
    }

    @Test
    void shouldRejectUnknownRequestProtocolVersionInExecutorConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerPiAgentExecutor.Configuration(
                        "rd-bot/pi-agent:test",
                        "rd-bot/pi-agent-qa:local",
                        List.of("node"),
                        "bridge",
                        true,
                        false,
                        60_000L,
                        900_000L,
                        16L * 1024L * 1024L,
                        "v3"
                )
        );
    }

    @Test
    void shouldMaterializeInputManifestOnlyWhenJsonPresent() throws Exception {
        Path inputDirectory = temporaryDirectory.resolve("input");
        Files.createDirectories(inputDirectory);

        PiRequestV2Materializer.materializeInputManifest(inputDirectory, "");
        assertFalse(Files.exists(inputDirectory.resolve(PiRequestV2Materializer.INPUT_MANIFEST_PATH_RELATIVE)));

        String manifestJson = "{\"schemaVersion\":1,\"taskId\":\"task-1\"}";
        PiRequestV2Materializer.materializeInputManifest(inputDirectory, manifestJson);
        Path manifestPath = inputDirectory.resolve(PiRequestV2Materializer.INPUT_MANIFEST_PATH_RELATIVE);
        assertTrue(Files.isRegularFile(manifestPath));
        assertEquals(manifestJson + "\n", Files.readString(manifestPath, StandardCharsets.UTF_8));
    }

    @Test
    void shouldParseFullContextPolicyForRequestEmbedding() throws Exception {
        String policyJson = """
                {
                  "protocol": "rd-runtime-context-policy/v1",
                  "policyHash": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "mode": "ROOT_ONLY",
                  "expectedFiles": [
                    {"path": "AGENTS.md", "contentHash": "sha256:1111111111111111111111111111111111111111111111111111111111111111"}
                  ]
                }
                """;

        Map<String, Object> policy = PiRequestV2Materializer.parseContextPolicy(policyJson);

        assertEquals("rd-runtime-context-policy/v1", policy.get("protocol"));
        assertEquals("sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", policy.get("policyHash"));
        assertEquals("ROOT_ONLY", policy.get("mode"));
        JsonNode expectedFiles = OBJECT_MAPPER.valueToTree(policy.get("expectedFiles"));
        assertEquals(1, expectedFiles.size());
        assertEquals("AGENTS.md", expectedFiles.get(0).path("path").asText());
    }

    @Test
    void shouldRejectMissingPolicyFieldsForV2() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PiRequestV2Materializer.parseContextPolicy("")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PiRequestV2Materializer.parseContextPolicy("{\"protocol\":\"rd-runtime-context-policy/v1\"}")
        );
    }

    @Test
    void shouldValidatePreflightFromPolicyAndManifestJson() {
        String policyJson = """
                {
                  "protocol": "rd-runtime-context-policy/v1",
                  "policyHash": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "mode": "ROOT_ONLY",
                  "expectedFiles": [
                    {"path": "AGENTS.md", "contentHash": "sha256:111"}
                  ]
                }
                """;
        String manifestJson = manifestJson(
                "ACCEPTED",
                List.of(new RuntimeContextFileDecision("AGENTS.md", "sha256:111", 12, 1, "REPO", "LOADED", ""))
        );

        RuntimeContextPreflightValidator.ValidationResult accepted =
                PiRequestV2Materializer.validatePreflight(policyJson, manifestJson);
        assertTrue(accepted.accepted());

        String rejectedManifestJson = manifestJson(
                "REJECTED",
                List.of(new RuntimeContextFileDecision("AGENTS.md", "sha256:111", 12, 1, "REPO", "LOADED", ""))
        );
        RuntimeContextPreflightValidator.ValidationResult rejected =
                PiRequestV2Materializer.validatePreflight(policyJson, rejectedManifestJson);
        assertFalse(rejected.accepted());
        assertTrue(rejected.violations().stream().anyMatch(violation -> violation.contains("ACCEPTED")));
    }

    private static String manifestJson(String status, List<RuntimeContextFileDecision> observed) {
        RuntimeContextManifest manifest = new RuntimeContextManifest(
                1,
                RuntimeContextManifest.PROTOCOL,
                "task-1",
                "stage-1",
                "CODING_AGENT",
                1,
                RuntimeContextPolicyMode.ROOT_ONLY,
                "PI",
                "anthropic",
                "claude",
                "snapshot-1",
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "2026-08-01T00:00:00Z",
                observed,
                observed.size(),
                observed.stream().mapToLong(RuntimeContextFileDecision::bytes).sum(),
                "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                status
        );
        return manifest.canonicalJson();
    }
}
