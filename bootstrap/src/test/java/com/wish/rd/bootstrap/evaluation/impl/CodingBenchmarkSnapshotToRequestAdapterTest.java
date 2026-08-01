package com.wish.rd.bootstrap.evaluation.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.evaluation.EvaluationProperties;
import com.wish.rd.engine.evaluation.CodingBenchmarkCaseRuntime;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkArm;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingBenchmarkSnapshotToRequestAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteRelayBackedPiRequestIntoTheTrialInputDirectory() throws Exception {
        EvaluationProperties properties = new EvaluationProperties();
        properties.setCodingBenchmarkPrepRoot(tempDir);
        properties.getCodingBenchmark().getModelProvider().setName("deepseek");
        properties.getCodingBenchmark().getModelProvider().setModel("deepseek-v4-flash");
        properties.getCodingBenchmark().getModelProvider().setProtocol("openai-chat-completions");

        Path prepRoot = tempDir;
        Path repo = Files.createDirectories(prepRoot.resolve("prepared/case-01"));
        Path agentCache = Files.createDirectories(prepRoot.resolve("cache-agent-case-01"));
        Path verifierCache = Files.createDirectories(prepRoot.resolve("cache-verifier-case-01"));
        Path patch = prepRoot.resolve("assets/case-01/runtime-withheld.patch");
        Files.createDirectories(patch.getParent());
        Files.writeString(patch, "diff --git a/a b/a\n");

        CodingBenchmarkCaseRuntime runtime = CodingBenchmarkCaseRuntime.of(
                prepRoot,
                "case-01",
                "registry.example/agent@sha256:" + "a".repeat(64),
                "registry.example/oracle@sha256:" + "b".repeat(64),
                repo,
                agentCache,
                repo,
                verifierCache,
                null,
                patch,
                "",
                List.of("node", "/work/pi-agent/rd-pi-bridge.mjs"),
                List.of("python3", "/opt/rd-pi-bridge/rd_eval_oracle.py"),
                30 * 60_000L,
                5 * 60_000L
        );

        CodingBenchmarkSnapshotToRequestAdapter adapter = new CodingBenchmarkSnapshotToRequestAdapter(
                tempDir.resolve("agent-output"),
                properties,
                (Supplier<String>) () -> "relay-token-123",
                new ObjectMapper()
        );
        CodingBenchmarkTrial trial = CodingBenchmarkTrial.queued(
                "trial-1", "campaign-1", "case-01", CodingBenchmarkArm.A, 0, 100L
        );
        CodingBenchmarkSnapshotToRequestAdapter.TrialWorkspace workspace =
                new CodingBenchmarkSnapshotToRequestAdapter.TrialWorkspace(
                        repo,
                        tempDir.resolve("verifier"),
                        agentCache,
                        verifierCache,
                        tempDir.resolve("agent-output"),
                        tempDir.resolve("trial-root"));

        adapter.adapt(trial, workspace, runtime);

        Path requestPath = workspace.trialRoot().resolve("input/request.json");
        assertTrue(Files.isRegularFile(requestPath));
        JsonNode request = new ObjectMapper().readTree(Files.readString(requestPath));
        assertEquals("deepseek", request.path("provider").asText());
        assertEquals("deepseek-v4-flash", request.path("model").asText());
        assertEquals("openai-completions", request.path("api").asText());
        assertEquals("http://rd-eval-relay-case-01-a:8765", request.path("baseUrl").asText());
        assertEquals(true, request.path("authHeader").asBoolean());
        assertEquals("RD_EVAL_MODEL_RELAY_TOKEN", request.path("credentialEnvironmentVariable").asText());
        assertEquals("/work/input", request.path("inputPath").asText());
        assertEquals("CODING_AGENT", request.path("role").asText());
        assertEquals("/work/output/candidate.patch", request.path("patchArtifactPath").asText());
        assertTrue(request.path("prompt").asText().contains("/work/output/candidate.patch"));
        assertTrue(request.path("prompt").asText().contains("/work/input/problem.md"));
        assertEquals(80, request.path("maxAgentTurns").asInt());
    }

    @Test
    void shouldSelectCodingAgentRoleForMultiRoleArms() throws Exception {
        EvaluationProperties properties = new EvaluationProperties();
        properties.setCodingBenchmarkPrepRoot(tempDir);
        Path prepRoot = tempDir;
        Path repo = Files.createDirectories(prepRoot.resolve("prepared/case-01"));
        Path agentCache = Files.createDirectories(prepRoot.resolve("cache-agent-case-01"));
        Path verifierCache = Files.createDirectories(prepRoot.resolve("cache-verifier-case-01"));
        Path patch = prepRoot.resolve("assets/case-01/runtime-withheld.patch");
        Files.createDirectories(patch.getParent());
        Files.writeString(patch, "diff --git a/a b/a\n");
        Files.writeString(prepRoot.resolve("trusted-dataset.jsonl"), """
                {"instance_id":"case-01","title":"Fix case","body":"Body text","resolved_issues":"Issue 1"}
                """);

        CodingBenchmarkCaseRuntime runtime = CodingBenchmarkCaseRuntime.of(
                prepRoot,
                "case-01",
                "registry.example/agent@sha256:" + "a".repeat(64),
                "registry.example/oracle@sha256:" + "b".repeat(64),
                repo,
                agentCache,
                repo,
                verifierCache,
                null,
                patch,
                "",
                List.of("node", "/work/pi-agent/rd-pi-bridge.mjs"),
                List.of("python3", "/opt/rd-pi-bridge/rd_eval_oracle.py"),
                30 * 60_000L,
                5 * 60_000L
        );
        CodingBenchmarkSnapshotToRequestAdapter adapter = new CodingBenchmarkSnapshotToRequestAdapter(
                tempDir.resolve("agent-output-b"),
                properties,
                (Supplier<String>) () -> "relay-token-123",
                new ObjectMapper()
        );
        CodingBenchmarkTrial trial = CodingBenchmarkTrial.queued(
                "trial-b", "campaign-1", "case-01", CodingBenchmarkArm.B, 1, 100L
        );
        Path trialRoot = tempDir.resolve("trial-root-b");
        Files.createDirectories(trialRoot);
        CodingBenchmarkSnapshotToRequestAdapter.TrialWorkspace workspace =
                new CodingBenchmarkSnapshotToRequestAdapter.TrialWorkspace(
                        repo,
                        tempDir.resolve("verifier-b"),
                        agentCache,
                        verifierCache,
                        tempDir.resolve("agent-output-b"),
                        trialRoot);

        adapter.adapt(trial, workspace, runtime);

        JsonNode request = new ObjectMapper().readTree(Files.readString(trialRoot.resolve("input/request.json")));
        assertEquals("CODING_AGENT", request.path("role").asText());
        String problem = Files.readString(trialRoot.resolve("input/problem.md"));
        assertTrue(problem.contains("Fix case"));
        assertTrue(problem.contains("Body text"));
    }

    @Test
    void shouldMaterialisePrivateVerifierCopySoOracleCannotMutateSharedPrepared() throws Exception {
        EvaluationProperties properties = new EvaluationProperties();
        properties.setCodingBenchmarkPrepRoot(tempDir);
        Path prepRoot = tempDir;
        Path sharedRepo = Files.createDirectories(prepRoot.resolve("prepared/case-shared"));
        Files.writeString(sharedRepo.resolve("Shared.java"), "class Shared {}\n");
        Path agentCache = Files.createDirectories(prepRoot.resolve("cache-agent"));
        Path verifierCache = Files.createDirectories(prepRoot.resolve("cache-verifier"));
        Path patch = prepRoot.resolve("assets/case-shared/runtime-withheld.patch");
        Files.createDirectories(patch.getParent());
        Files.writeString(patch, "diff --git a/a b/a\n");

        CodingBenchmarkCaseRuntime runtime = CodingBenchmarkCaseRuntime.of(
                prepRoot,
                "case-shared",
                "registry.example/agent@sha256:" + "a".repeat(64),
                "registry.example/oracle@sha256:" + "b".repeat(64),
                sharedRepo,
                agentCache,
                sharedRepo,
                verifierCache,
                null,
                patch,
                "",
                List.of("node", "/work/pi-agent/rd-pi-bridge.mjs"),
                List.of("python3", "/opt/rd-pi-bridge/rd_eval_oracle.py"),
                30 * 60_000L,
                5 * 60_000L
        );
        CodingBenchmarkSnapshotToRequestAdapter adapter = new CodingBenchmarkSnapshotToRequestAdapter(
                tempDir.resolve("out-root"),
                properties,
                (Supplier<String>) () -> "relay-token",
                new ObjectMapper()
        );
        CodingBenchmarkTrial trial = CodingBenchmarkTrial.queued(
                "trial-v", "campaign-1", "case-shared", CodingBenchmarkArm.A, 0, 100L
        );

        CodingBenchmarkSnapshotToRequestAdapter.TrialWorkspace workspace =
                adapter.materialiseTrialWorkspace(trial, runtime);
        var request = adapter.adapt(trial, workspace, runtime);

        assertTrue(Files.isRegularFile(workspace.verifierDir().resolve("Shared.java")));
        assertTrue(Files.isSameFile(request.verifierRepository(), workspace.verifierDir()));
        assertFalse(Files.isSameFile(request.verifierRepository(), sharedRepo));
        Files.writeString(workspace.verifierDir().resolve("OracleOnly.java"), "class OracleOnly {}\n");
        assertTrue(Files.notExists(sharedRepo.resolve("OracleOnly.java")));
    }

    @Test
    void shouldMaterialiseSymlinkedCacheAsRealDirectory() throws Exception {
        EvaluationProperties properties = new EvaluationProperties();
        properties.setCodingBenchmarkPrepRoot(tempDir);
        Path prepRoot = tempDir;
        Path sharedRepo = Files.createDirectories(prepRoot.resolve("prepared/rd-bot--demo"));
        Files.writeString(sharedRepo.resolve("App.java"), "class App {}\n");
        Path realCache = Files.createDirectories(prepRoot.resolve("cache-m2-rdbot"));
        Files.writeString(realCache.resolve("warm.log"), "warm\n");
        Files.createDirectories(realCache.resolve("m2"));
        Path symlinkCache = prepRoot.resolve("cache-rd-bot--demo");
        Files.createSymbolicLink(symlinkCache, Path.of("cache-m2-rdbot"));
        Path verifierCache = Files.createDirectories(prepRoot.resolve("cache-verifier"));
        Path patch = prepRoot.resolve("assets/rd-bot--demo/runtime-withheld.patch");
        Files.createDirectories(patch.getParent());
        Files.writeString(patch, "diff --git a/a b/a\n");

        CodingBenchmarkCaseRuntime runtime = CodingBenchmarkCaseRuntime.of(
                prepRoot,
                "rd-bot--demo",
                "registry.example/agent@sha256:" + "a".repeat(64),
                "registry.example/oracle@sha256:" + "b".repeat(64),
                sharedRepo,
                symlinkCache,
                sharedRepo,
                verifierCache,
                null,
                patch,
                "",
                List.of("node", "/work/pi-agent/rd-pi-bridge.mjs"),
                List.of("python3", "/opt/rd-pi-bridge/rd_eval_oracle.py"),
                30 * 60_000L,
                5 * 60_000L
        );
        CodingBenchmarkSnapshotToRequestAdapter adapter = new CodingBenchmarkSnapshotToRequestAdapter(
                tempDir.resolve("out-symlink"),
                properties,
                (Supplier<String>) () -> "relay-token",
                new ObjectMapper()
        );
        CodingBenchmarkTrial trial = CodingBenchmarkTrial.queued(
                "trial-symlink", "campaign-symlink", "rd-bot--demo", CodingBenchmarkArm.A, 0, 100L
        );

        CodingBenchmarkSnapshotToRequestAdapter.TrialWorkspace workspace =
                adapter.materialiseTrialWorkspace(trial, runtime);

        assertFalse(Files.isSymbolicLink(workspace.cacheDir()));
        assertTrue(Files.isRegularFile(workspace.cacheDir().resolve("warm.log")));
        assertTrue(Files.isDirectory(workspace.cacheDir().resolve("m2")));
    }
}

