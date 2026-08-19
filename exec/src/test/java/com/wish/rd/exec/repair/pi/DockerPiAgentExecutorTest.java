package com.wish.rd.exec.repair.pi;

import com.wish.rd.exec.repair.pi.impl.DockerPiAgentExecutor;
import com.wish.rd.exec.repair.pi.impl.InMemoryPiCredentialLeaseIssuer;
import com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wish.rd.exec.repair.docker.ContainerOutputListener;
import com.wish.rd.exec.repair.docker.RepairWorkspaceFactory;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.docker.StreamingContainerRunnerPort;
import com.wish.rd.exec.repair.docker.model.ContainerRunRequest;
import com.wish.rd.exec.repair.docker.model.ContainerRunResult;
import com.wish.rd.exec.repair.docker.model.RepairWorkspace;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.model.RepairInputAttachment;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.qa.QaExecutionMetadataKeys;
import com.wish.rd.exec.repair.result.QaEvidenceBundleValidator;
import com.wish.rd.exec.repair.result.StructuredResultValidator;
import com.wish.rd.exec.repair.result.model.AgentRoleResultValidation;
import com.wish.rd.exec.repair.runtime.AgentExecutionEventSink;
import com.wish.rd.exec.repair.security.model.ExecutionAllowlistPolicy;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentStateV2Codec;
import com.wish.rd.rag.project.agent.model.AgentRuntimeCapability;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerPiAgentExecutorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String RESULT_SCHEMA = "{\"type\":\"object\"}";

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldHostValidateCanonicalProtocolFailureReceiptAndExposeItForRouting() throws Exception {
        String taskId = "task-protocol-receipt";
        String stageRunId = "stage-" + taskId;
        ProtocolFailureReceiptRunner runner = new ProtocolFailureReceiptRunner(taskId, stageRunId, false);
        DockerPiAgentExecutor executor = executor(runner, AgentExecutionEventSink.noop(), ignored -> "secret");

        AgentExecutionProfileSnapshot receiptSnapshot = qaRemediationSnapshot(
                "snapshot-protocol-receipt", stageRunId, taskId
        );
        assertTrue(receiptSnapshot.hasCapability(AgentRuntimeCapability.PI_QA_REMEDIATION_V2));
        RepairExecutionResult result = executor.execute(new AgentRuntimeExecutionRequest(
                receiptSnapshot,
                command(taskId, "QA_AGENT")
        ));

        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, result.status());
        assertEquals(
                "RESULT_MISSING_AFTER_RECOVERY",
                result.dockerMetadataJson().get("piProtocolFailureReceiptKind"),
                () -> "artifacts=" + result.artifacts().stream().map(artifact -> artifact.type() + ":" + artifact.name()).toList()
        );
        assertEquals(
                "PiProtocolFailureReceipt/v1",
                result.dockerMetadataJson().get("piProtocolFailureReceiptProtocol")
        );
        assertTrue(result.dockerMetadataJson().get("piProtocolFailureReceiptHash").startsWith("sha256:"));
        assertTrue(result.artifacts().stream().anyMatch(artifact ->
                artifact.type() == RepairArtifactType.PI_PROTOCOL_FAILURE_RECEIPT));
    }

    @Test
    void shouldRejectReceiptWhoseIdentityDoesNotMatchTrustedEvents() throws Exception {
        String taskId = "task-protocol-mismatch";
        String stageRunId = "stage-" + taskId;
        DockerPiAgentExecutor executor = executor(
                new ProtocolFailureReceiptRunner(taskId, stageRunId, true),
                AgentExecutionEventSink.noop(),
                ignored -> "secret"
        );

        RepairExecutionResult result = executor.execute(new AgentRuntimeExecutionRequest(
                qaRemediationSnapshot("snapshot-protocol-mismatch", stageRunId, taskId),
                command(taskId, "QA_AGENT")
        ));

        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, result.status());
        assertTrue(result.errorMessage().contains("identity mismatch"), result.errorMessage());
        assertFalse(result.dockerMetadataJson().containsKey("piProtocolFailureReceiptKind"));
    }

    @Test
    void shouldWriteVerifiedInitialStateForCapabilityGatedPiOnly() throws Exception {
        String stateJson = AgentStateV2Codec.canonicalize(OBJECT_MAPPER.readTree("""
                {
                  "protocol":"rd-agent-state/v2",
                  "sequence":0,
                  "taskId":"task-1",
                  "stageRunId":"stage-1",
                  "role":"CODING_AGENT",
                  "attemptNo":1,
                  "runtimeType":"PI",
                  "profileSnapshotId":"snapshot-state",
                  "budget":{"availability":"UNKNOWN"}
                }
                """));
        String stateHash = AgentStateV2Codec.hash(OBJECT_MAPPER.readTree(stateJson));
        RepairJobCommand stateCommand = commandWithInitialState(
                "task-1", "CODING_AGENT", stateJson, stateHash
        );
        DockerPiAgentExecutor enabledExecutor = executor(
                new StateV2ArtifactRunner(""), AgentExecutionEventSink.noop(), ignored -> "secret"
        );

        RepairExecutionResult enabled = enabledExecutor.execute(new AgentRuntimeExecutionRequest(
                stateV2Snapshot("snapshot-state", "stage-1", "task-1"), stateCommand
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, enabled.status(), enabled.errorMessage());
        JsonNode enabledRequest = OBJECT_MAPPER.readTree(Files.readString(
                temporaryDirectory.resolve("workspaces/task-1/input/request.json")
        ));
        assertEquals("rd-agent-state/v2", enabledRequest.path("initialAgentStateProtocol").asText());
        assertEquals(stateJson, enabledRequest.path("initialAgentStateJson").asText());
        assertEquals(stateHash, enabledRequest.path("initialAgentStateHash").asText());

        DockerPiAgentExecutor legacyExecutor = executor(
                new CapturingRunner(), AgentExecutionEventSink.noop(), ignored -> "secret"
        );
        RepairExecutionResult legacy = legacyExecutor.execute(new AgentRuntimeExecutionRequest(
                snapshot("snapshot-legacy-state", "stage-1", "task-1", AgentRuntimeType.PI, ""),
                commandWithInitialState("task-1", "CODING_AGENT", stateJson, stateHash)
        ));
        assertEquals(RepairExecutionStatus.SUCCESS, legacy.status(), legacy.errorMessage());
        JsonNode legacyRequest = OBJECT_MAPPER.readTree(Files.readString(
                temporaryDirectory.resolve("workspaces/task-1/input/request.json")
        ));
        assertTrue(legacyRequest.path("initialAgentStateProtocol").isMissingNode());
        assertTrue(legacyRequest.path("initialAgentStateJson").isMissingNode());
        assertTrue(legacyRequest.path("initialAgentStateHash").isMissingNode());
        assertFalse(legacy.artifacts().stream()
                .anyMatch(artifact -> artifact.type() == RepairArtifactType.AGENT_EFFECTIVE_CONTEXT));
    }

    @Test
    void shouldCollectVerifiedV2StateAndEffectiveContextArtifacts() throws Exception {
        String stateJson = canonicalInitialState("task-1", "stage-1", "snapshot-state-artifacts");
        String stateHash = AgentStateV2Codec.hash(OBJECT_MAPPER.readTree(stateJson));
        DockerPiAgentExecutor executor = executor(
                new StateV2ArtifactRunner(""), AgentExecutionEventSink.noop(), ignored -> "secret"
        );

        RepairExecutionResult result = executor.execute(new AgentRuntimeExecutionRequest(
                stateV2Snapshot("snapshot-state-artifacts", "stage-1", "task-1"),
                commandWithInitialState("task-1", "CODING_AGENT", stateJson, stateHash)
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status(), result.errorMessage());
        assertTrue(result.artifacts().stream().anyMatch(artifact ->
                artifact.type() == RepairArtifactType.AGENT_STATE_SNAPSHOT
                        && "agent-state-latest.json".equals(artifact.name())));
        assertTrue(result.artifacts().stream().anyMatch(artifact ->
                artifact.type() == RepairArtifactType.AGENT_EFFECTIVE_CONTEXT
                        && "agent-effective-context-latest.json".equals(artifact.name())));
    }

    @Test
    void shouldRejectV2StateArtifactIdentityMismatch() throws Exception {
        String stateJson = canonicalInitialState("task-1", "stage-1", "snapshot-state-identity");
        String stateHash = AgentStateV2Codec.hash(OBJECT_MAPPER.readTree(stateJson));
        DockerPiAgentExecutor executor = executor(
                new StateV2ArtifactRunner("STATE_IDENTITY"), AgentExecutionEventSink.noop(), ignored -> "secret"
        );

        RepairExecutionResult result = executor.execute(new AgentRuntimeExecutionRequest(
                stateV2Snapshot("snapshot-state-identity", "stage-1", "task-1"),
                commandWithInitialState("task-1", "CODING_AGENT", stateJson, stateHash)
        ));

        assertEquals(RepairExecutionStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("identity"), result.errorMessage());
    }

    @Test
    void shouldRejectV2EffectiveContextBlockHashMismatch() throws Exception {
        String stateJson = canonicalInitialState("task-1", "stage-1", "snapshot-state-hash");
        String stateHash = AgentStateV2Codec.hash(OBJECT_MAPPER.readTree(stateJson));
        DockerPiAgentExecutor executor = executor(
                new StateV2ArtifactRunner("BLOCK_HASH"), AgentExecutionEventSink.noop(), ignored -> "secret"
        );

        RepairExecutionResult result = executor.execute(new AgentRuntimeExecutionRequest(
                stateV2Snapshot("snapshot-state-hash", "stage-1", "task-1"),
                commandWithInitialState("task-1", "CODING_AGENT", stateJson, stateHash)
        ));

        assertEquals(RepairExecutionStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("block hash"), result.errorMessage());
    }

    @Test
    void shouldStreamPiEventsBuildFrozenRequestAndValidateTheJavaResult() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        List<String> eventTypes = new ArrayList<>();
        DockerPiAgentExecutor executor = executor(
                runner,
                (containerName, event) -> eventTypes.add(event.path("eventType").asText()),
                ignored -> "test-provider-secret"
        );

        RepairExecutionResult result = executor.execute(new com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest(
                snapshot("snapshot-1", "stage-1", "task-1", AgentRuntimeType.PI, ""),
                command("task-1", "CODING_AGENT")
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        assertEquals("Pi produced a valid patch", result.summary());
        assertEquals(List.of("RUNTIME_READY", "AGENT_STARTED", "RESULT_SUBMITTED", "AGENT_SETTLED"), eventTypes);
        assertEquals("rd-bot/pi-agent:test", runner.request.image());
        assertEquals(List.of("node", "/opt/rd-pi-bridge/src/rd-pi-bridge.mjs"), runner.request.command());
        assertEquals("/work/input:ro", runner.request.mounts().get(
                temporaryDirectory.resolve("workspaces/task-1/input").toString()
        ));
        assertEquals("/work/repo", runner.request.mounts().get(
                temporaryDirectory.resolve("workspaces/task-1/repo").toString()
        ));
        assertEquals("/work/repo", runner.request.workingDirectory());
        assertEquals("provider-1", OBJECT_MAPPER.readTree(
                Files.readString(temporaryDirectory.resolve("workspaces/task-1/input/request.json"))
        ).path("provider").asText());
        assertEquals("gpt-test", OBJECT_MAPPER.readTree(
                Files.readString(temporaryDirectory.resolve("workspaces/task-1/input/request.json"))
        ).path("model").asText());
        assertEquals("/work/cache/npm", runner.request.env().get("npm_config_cache"));
        assertEquals("/work/cache/pip", runner.request.env().get("PIP_CACHE_DIR"));
        assertEquals("/work/cache/yarn", runner.request.env().get("YARN_CACHE_FOLDER"));
        assertEquals("16777216", runner.request.env().get("RD_PI_MAX_RAW_EVENT_BYTES"));
        assertEquals("900000", runner.request.env().get("RD_PI_BASH_COMMAND_TIMEOUT_MILLIS"));
        assertTrue(runner.request.securityPolicy().enabled());
        assertTrue(runner.request.securityPolicy().readOnlyRootfs());
        assertTrue(runner.request.securityPolicy().capDropAll());
        assertTrue(runner.request.securityPolicy().noNewPrivileges());
        assertEquals("8g", runner.request.securityPolicy().memoryLimit());
        assertEquals("4", runner.request.securityPolicy().cpuLimit());
        assertEquals(512, runner.request.securityPolicy().pidsLimit());
        assertEquals("1000:1000", runner.request.securityPolicy().runAsUser());
        assertTrue(runner.request.securityPolicy().tmpfsMounts().containsKey("/work/pi-agent"));
        assertFalse(runner.request.initEnabled());
        assertFalse(result.dockerMetadataJson().containsValue("test-provider-secret"));
        assertEquals("snapshot-1", result.dockerMetadataJson().get("executionProfileSnapshotId"));
    }

    @Test
    void shouldPersistProviderAttemptsWithMeasuredPiTokenUsage() throws Exception {
        CapturingRunner runner = new CapturingRunner() {
            @Override
            public ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) throws IOException {
                Files.createDirectories(request.outputDirectory());
                Files.writeString(request.outputDirectory().resolve("result.json"), """
                        {
                          "status": "SUCCESS",
                          "summary": "Pi produced a valid patch",
                          "prBody": "Implementation details",
                          "changedFiles": ["src/App.java"],
                          "testCommands": ["./mvnw test"],
                          "testStatus": "PASSED",
                          "riskLevel": "LOW",
                          "needHumanAction": false
                        }
                        """, StandardCharsets.UTF_8);
                Files.writeString(request.outputDirectory().resolve("patch.diff"), "diff\n");
                Files.writeString(request.outputDirectory().resolve("test.log"), "ok\n");
                String events = """
                        {"protocol":"rd-agent-event/v1","eventType":"ASSISTANT_TEXT_COMPLETED","sourceSequence":1,"stageRunId":"stage-1","taskId":"task-1","role":"CODING_AGENT","payload":{"usage":{"input":10,"output":11,"cacheRead":12,"cacheWrite":13}}}
                        {"protocol":"rd-agent-event/v1","eventType":"AGENT_SETTLED","sourceSequence":2,"stageRunId":"stage-1","taskId":"task-1","role":"CODING_AGENT"}
                        """;
                Files.writeString(request.outputDirectory().resolve("agent-events.jsonl"), events, StandardCharsets.UTF_8);
                Files.writeString(request.outputDirectory().resolve("runtime-context-manifest.json"), """
                        {"schemaVersion":1,"mode":"LEGACY_OBSERVE_ONLY","observedFiles":[]}
                        """, StandardCharsets.UTF_8);
                listener.onStdout(events);
                return new ContainerRunResult(
                        0,
                        12L,
                        "",
                        "",
                        request.outputDirectory().resolve("result.json"),
                        request.outputDirectory().resolve("patch.diff"),
                        request.outputDirectory().resolve("test.log"),
                        null,
                        null,
                        Map.of("containerName", request.containerName())
                );
            }
        };
        DockerPiAgentExecutor executor = executor(runner, AgentExecutionEventSink.noop(), ignored -> "secret");

        RepairExecutionResult result = executor.execute(new AgentRuntimeExecutionRequest(
                snapshot("snapshot-usage", "stage-1", "task-1", AgentRuntimeType.PI, ""),
                command("task-1", "CODING_AGENT")
        ));

        JsonNode attempts = OBJECT_MAPPER.readTree(result.dockerMetadataJson().get("providerAttemptsJson"));
        assertEquals(1, attempts.size());
        assertTrue(attempts.get(0).path("tokenUsageAvailable").asBoolean());
        assertEquals(46L, attempts.get(0).path("totalTokens").asLong());
        assertEquals("rd-runtime-measurement/v1", attempts.get(0).path("measurementSchemaVersion").asText());
        assertFalse(attempts.get(0).path("firstTokenAvailable").asBoolean());
        assertTrue(result.artifacts().stream()
                .anyMatch(artifact -> artifact.type() == RepairArtifactType.RUNTIME_MEASUREMENT));
        assertTrue(result.artifacts().stream()
                .anyMatch(artifact -> "runtime-context-manifest.json".equals(artifact.name())));
    }

    @Test
    void malformedAgentEventsDoNotFailTheRoleExecution() throws Exception {
        CapturingRunner runner = new CapturingRunner() {
            @Override
            public ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) throws IOException {
                ContainerRunResult result = super.run(request, listener);
                Path events = request.outputDirectory().resolve("agent-events.jsonl");
                Files.writeString(events, "{not-json}\n" + Files.readString(events, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
                return result;
            }
        };
        DockerPiAgentExecutor executor = executor(runner, AgentExecutionEventSink.noop(), ignored -> "secret");

        RepairExecutionResult result = executor.execute(new AgentRuntimeExecutionRequest(
                snapshot("snapshot-usage-bad", "stage-1", "task-1", AgentRuntimeType.PI, ""),
                command("task-1", "CODING_AGENT")
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        JsonNode attempts = OBJECT_MAPPER.readTree(result.dockerMetadataJson().get("providerAttemptsJson"));
        assertFalse(attempts.get(0).path("firstTokenAvailable").asBoolean());
        assertTrue(result.artifacts().stream()
                .anyMatch(artifact -> artifact.type() == RepairArtifactType.RUNTIME_MEASUREMENT));
    }

    @Test
    void shouldWriteV2RequestAndMaterializeInputManifest() throws Exception {
        CapturingRunner runner = new CapturingRunner() {
            @Override
            public ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) throws IOException {
                Files.writeString(request.outputDirectory().resolve("runtime-context-manifest.json"), """
                        {
                          "schemaVersion": 1,
                          "protocol": "rd-runtime-context-manifest/v1",
                          "taskId": "task-1",
                          "stageRunId": "stage-1",
                          "role": "CODING_AGENT",
                          "attemptNo": 1,
                          "mode": "ROOT_ONLY",
                          "runtime": "PI",
                          "provider": "provider-1",
                          "model": "gpt-test",
                          "executionProfileSnapshotId": "snapshot-v2",
                          "inputManifestHash": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                          "contextPolicyHash": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                          "generatedAt": "2026-08-01T00:00:00Z",
                          "observedFiles": [
                            {
                              "path": "AGENTS.md",
                              "contentHash": "sha256:1111111111111111111111111111111111111111111111111111111111111111",
                              "bytes": 12,
                              "loadOrder": 1,
                              "scope": "REPO",
                              "trustDecision": "LOADED",
                              "rejectReason": ""
                            }
                          ],
                          "totalFiles": 1,
                          "totalBytes": 12,
                          "effectiveContextHash": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                          "status": "ACCEPTED"
                        }
                        """, StandardCharsets.UTF_8);
                return super.run(request, listener);
            }
        };
        DockerPiAgentExecutor executor = executor(
                runner,
                AgentExecutionEventSink.noop(),
                ignored -> "secret",
                v2Configuration()
        );
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
        String manifestJson = "{\"schemaVersion\":1,\"taskId\":\"task-1\"}";
        RepairJobCommand command = v2Command(
                "task-1",
                "CODING_AGENT",
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                policyJson,
                manifestJson
        );
        // Align Host context identity with the frozen runtime-context-manifest under test.
        Map<String, String> aligned = new java.util.LinkedHashMap<>(command.contextJson());
        aligned.put("taskId", "task-1");
        aligned.put("stageRunId", "stage-1");
        aligned.put("contextPolicyHash", "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        command = new RepairJobCommand(
                command.repairRecordId(),
                command.taskId(),
                command.ticketId(),
                command.ticketTitle(),
                command.prompt(),
                command.repositoryUrl(),
                command.repoOwner(),
                command.repoName(),
                command.baseBranch(),
                command.workBranch(),
                aligned,
                command.policyJson(),
                command.attachments()
        );

        RepairExecutionResult result = executor.execute(new AgentRuntimeExecutionRequest(
                snapshot("snapshot-v2", "stage-1", "task-1", AgentRuntimeType.PI, ""),
                command
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status(), result.errorMessage());
        Path inputDirectory = temporaryDirectory.resolve("workspaces/task-1/input");
        JsonNode request = OBJECT_MAPPER.readTree(Files.readString(inputDirectory.resolve("request.json")));
        assertEquals("rd-pi-request/v2", request.path("protocol").asText());
        assertEquals("/work/input/role-execution-input-manifest.json", request.path("inputManifestPath").asText());
        assertEquals("ROOT_ONLY", request.path("contextPolicy").path("mode").asText());
        assertTrue(Files.isRegularFile(inputDirectory.resolve("role-execution-input-manifest.json")));
    }

    @Test
    void shouldRejectV2RequestWithoutFullContextPolicy() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        DockerPiAgentExecutor executor = executor(
                runner,
                AgentExecutionEventSink.noop(),
                ignored -> "secret",
                v2Configuration()
        );
        Map<String, String> context = new java.util.LinkedHashMap<>(command("task-1", "CODING_AGENT").contextJson());
        context.put("inputManifestHash", "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        RepairExecutionResult result = executor.execute(new AgentRuntimeExecutionRequest(
                snapshot("snapshot-v2-missing-policy", "stage-1", "task-1", AgentRuntimeType.PI, ""),
                new RepairJobCommand(
                        "repair-task-1",
                        "task-1",
                        "ticket-1",
                        "Implement Pi runtime",
                        "Implement the requested change",
                        "https://github.com/acme/repo.git",
                        "acme",
                        "repo",
                        "main",
                        "repair/task-1",
                        context,
                        Map.of("repositoryPublishRequired", "false"),
                        List.of()
                )
        ));

        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, result.status());
        assertEquals("PI_REQUEST_V2", result.rawResultJson().get("failureCategory"));
    }

    @Test
    void shouldKeepV1RequestOptionalContextPolicyHashOnly() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        DockerPiAgentExecutor executor = executor(runner, AgentExecutionEventSink.noop(), ignored -> "secret");
        Map<String, String> context = new java.util.LinkedHashMap<>(command("task-1", "CODING_AGENT").contextJson());
        context.put("contextPolicyHash", "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        RepairExecutionResult result = executor.execute(new AgentRuntimeExecutionRequest(
                snapshot("snapshot-v1", "stage-1", "task-1", AgentRuntimeType.PI, ""),
                new RepairJobCommand(
                        "repair-task-1",
                        "task-1",
                        "ticket-1",
                        "Implement Pi runtime",
                        "Implement the requested change",
                        "https://github.com/acme/repo.git",
                        "acme",
                        "repo",
                        "main",
                        "repair/task-1",
                        context,
                        Map.of("repositoryPublishRequired", "false"),
                        List.of()
                )
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        JsonNode request = OBJECT_MAPPER.readTree(Files.readString(
                temporaryDirectory.resolve("workspaces/task-1/input/request.json")
        ));
        assertEquals("rd-pi-request/v1", request.path("protocol").asText());
        assertEquals(
                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                request.path("contextPolicy").path("policyHash").asText()
        );
        assertTrue(request.path("contextPolicy").path("mode").isMissingNode());
    }

    @Test
    void shouldSerializeOnlyOpaqueHostAssertionContractsForQaRequests() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        DockerPiAgentExecutor executor = executor(runner, AgentExecutionEventSink.noop(), ignored -> "secret");
        RepairJobCommand base = command("task-host-contract", "QA_AGENT");
        Map<String, String> context = new java.util.LinkedHashMap<>(base.contextJson());
        context.put("hostAssertionContracts", hostAssertionContractsJson());
        RepairJobCommand command = new RepairJobCommand(
                base.repairRecordId(),
                base.taskId(),
                base.ticketId(),
                base.ticketTitle(),
                base.prompt(),
                base.repositoryUrl(),
                base.repoOwner(),
                base.repoName(),
                base.baseBranch(),
                base.workBranch(),
                context,
                base.policyJson(),
                base.attachments()
        );

        executor.execute(new AgentRuntimeExecutionRequest(
                snapshot("snapshot-host-contract", "stage-task-host-contract", "task-host-contract",
                        AgentRuntimeType.PI, "", "QA_AGENT"),
                command
        ));

        JsonNode request = OBJECT_MAPPER.readTree(Files.readString(
                temporaryDirectory.resolve("workspaces/task-host-contract/input/request.json")
        ));
        assertEquals(2, request.path("hostAssertionContracts").size());
        assertEquals("CURRENT", request.path("hostAssertionContracts").get(0).path("scope").asText());
        assertEquals("REGRESSION", request.path("hostAssertionContracts").get(1).path("scope").asText());
        assertEquals(1L, request.path("hostAssertionContracts").get(0).path("version").asLong());
        assertFalse(request.path("hostAssertionContracts").get(0).has("specs"));
    }

    @Test
    void shouldClearStalePiOutputBeforeStartingANewAttempt() throws Exception {
        Path staleRawEvents = temporaryDirectory.resolve(
                "workspaces/task-1/output/private/pi-raw-events.jsonl"
        );
        Files.createDirectories(staleRawEvents.getParent());
        Files.writeString(staleRawEvents, "old private runtime event\n", StandardCharsets.UTF_8);
        CapturingRunner runner = new CapturingRunner() {
            @Override
            public ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) throws IOException {
                assertFalse(Files.exists(request.outputDirectory().resolve("private/pi-raw-events.jsonl")));
                return super.run(request, listener);
            }
        };
        DockerPiAgentExecutor executor = executor(runner, AgentExecutionEventSink.noop(), ignored -> "secret");

        RepairExecutionResult result = executor.execute(new AgentRuntimeExecutionRequest(
                snapshot("snapshot-stale", "stage-1", "task-1", AgentRuntimeType.PI, ""),
                command("task-1", "CODING_AGENT")
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        assertFalse(Files.exists(staleRawEvents));
    }

    @Test
    void shouldSerializePiExecutionsThatShareOneWorkspace() throws Exception {
        CountDownLatch firstContainerFinishedWriting = new CountDownLatch(1);
        CountDownLatch releaseFirstExecution = new CountDownLatch(1);
        CountDownLatch secondWorkspaceMaterializationStarted = new CountDownLatch(1);
        CountDownLatch secondContainerStarted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger workspaceCreates = new AtomicInteger();
        RepairWorkspaceFactory workspaceFactory = new RepairWorkspaceFactory(
                temporaryDirectory.resolve("workspaces"),
                RESULT_SCHEMA
        ) {
            @Override
            public RepairWorkspace create(RepairJobCommand command) throws IOException {
                if (workspaceCreates.incrementAndGet() == 2) {
                    secondWorkspaceMaterializationStarted.countDown();
                }
                return super.create(command);
            }
        };
        CapturingRunner runner = new CapturingRunner() {
            @Override
            public ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) throws IOException {
                if (calls.incrementAndGet() == 1) {
                    ContainerRunResult result = super.run(request, listener);
                    firstContainerFinishedWriting.countDown();
                    try {
                        if (!releaseFirstExecution.await(2, TimeUnit.SECONDS)) {
                            throw new IOException("test timed out waiting to release the first Pi execution");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("test interrupted while waiting to release the first Pi execution", exception);
                    }
                    return result;
                }
                secondContainerStarted.countDown();
                return super.run(request, listener);
            }
        };
        DockerPiAgentExecutor executor = executor(
                workspaceFactory,
                runner,
                AgentExecutionEventSink.noop(),
                ignored -> "secret"
        );

        CompletableFuture<RepairExecutionResult> first = CompletableFuture.supplyAsync(() -> executor.execute(
                new AgentRuntimeExecutionRequest(
                        snapshotUnchecked("snapshot-first", "stage-1", "task-1"),
                        command("task-1", "CODING_AGENT", "first prompt")
                )
        ));
        try {
            assertTrue(firstContainerFinishedWriting.await(1, TimeUnit.SECONDS));
            CompletableFuture<RepairExecutionResult> second = CompletableFuture.supplyAsync(() -> executor.execute(
                    new AgentRuntimeExecutionRequest(
                            snapshotUnchecked("snapshot-second", "stage-1", "task-1"),
                            command("task-1", "CODING_AGENT", "second prompt")
                    )
            ));

            assertFalse(secondWorkspaceMaterializationStarted.await(200, TimeUnit.MILLISECONDS));
            assertFalse(secondContainerStarted.await(200, TimeUnit.MILLISECONDS));
            releaseFirstExecution.countDown();
            assertEquals(RepairExecutionStatus.SUCCESS, first.get(2, TimeUnit.SECONDS).status());
            assertEquals(RepairExecutionStatus.SUCCESS, second.get(2, TimeUnit.SECONDS).status());
        } finally {
            releaseFirstExecution.countDown();
        }
    }

    @Test
    void shouldRejectSnapshotRoleMismatchBeforeStartingPiContainer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CapturingRunner runner = new CapturingRunner() {
            @Override
            public ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) {
                calls.incrementAndGet();
                return null;
            }
        };
        DockerPiAgentExecutor executor = executor(runner, AgentExecutionEventSink.noop(), ignored -> "secret");

        // Snapshot frozen for CODING_AGENT but the command carries QA_AGENT: identity must match.
        RepairExecutionResult result = executor.execute(new com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest(
                snapshot("snapshot-qa", "stage-qa", "task-qa", AgentRuntimeType.PI, ""),
                command("task-qa", "QA_AGENT")
        ));

        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, result.status());
        assertEquals("PI_ROLE_NOT_ALLOWED", result.rawResultJson().get("failureCategory"));
        assertEquals(0, calls.get());
    }

    @Test
    void shouldRouteQaAgentSnapshotToTheQaImage() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        DockerPiAgentExecutor executor = executor(runner, AgentExecutionEventSink.noop(), ignored -> "secret");

        executor.execute(new com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest(
                snapshot("snapshot-qa2", "stage-qa2", "task-qa2", AgentRuntimeType.PI, "", "QA_AGENT"),
                command("task-qa2", "QA_AGENT")
        ));

        // The QA role must select the dedicated QA image, not the base Pi image.
        assertEquals("rd-bot/pi-agent-qa:local", runner.request.image());
        assertTrue(runner.request.securityPolicy().enabled());
        assertEquals(1024, runner.request.securityPolicy().pidsLimit());
        assertTrue(runner.request.initEnabled());
        assertEquals("1g", runner.request.sharedMemorySize());
        assertEquals("/work/repo", runner.request.mounts().get(
                temporaryDirectory.resolve("workspaces/task-qa2/repo").toString()
        ));
        assertEquals("true", runner.request.env().get("npm_config_offline"));
    }

    @Test
    void shouldProvisionNpmDependenciesOnBridgeBeforeIsolatedQaAgent() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        DockerPiAgentExecutor executor = executor(
                new RepairWorkspaceFactory(temporaryDirectory.resolve("workspaces"), RESULT_SCHEMA),
                runner,
                AgentExecutionEventSink.noop(),
                ignored -> "secret",
                defaultConfiguration(),
                npmMonorepo()
        );

        executor.execute(new AgentRuntimeExecutionRequest(
                snapshot("snapshot-qa-deps", "stage-qa-deps", "task-qa-deps", AgentRuntimeType.PI, "", "QA_AGENT"),
                command("task-qa-deps", "QA_AGENT")
        ));

        assertEquals(2, runner.requests.size(), runner.requests.toString());
        ContainerRunRequest provision = runner.requests.get(0);
        ContainerRunRequest agent = runner.requests.get(1);
        assertEquals("sh", provision.entrypoint());
        assertEquals("bridge", provision.networkMode());
        assertNull(provision.networkPlan());
        assertTrue(provision.command().stream().anyMatch(part -> part.contains("npm")), provision.command().toString());
        assertEquals("/work/repo", provision.mounts().get(
                temporaryDirectory.resolve("workspaces/task-qa-deps/repo").toString()
        ));
        assertEquals("development", provision.env().get("NODE_ENV"));
        assertNotNull(agent.networkPlan());
        assertEquals("true", agent.env().get("npm_config_offline"));
        assertTrue(agent.entrypoint() == null || agent.entrypoint().isBlank());
    }

    @Test
    void shouldFailQaAsEnvironmentWhenNpmProvisionExitsNonZero() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        runner.provisionExitCode = 1;
        DockerPiAgentExecutor executor = executor(
                new RepairWorkspaceFactory(temporaryDirectory.resolve("workspaces"), RESULT_SCHEMA),
                runner,
                AgentExecutionEventSink.noop(),
                ignored -> "secret",
                defaultConfiguration(),
                npmMonorepo()
        );

        RepairExecutionResult result = executor.execute(new AgentRuntimeExecutionRequest(
                snapshot("snapshot-qa-deps-fail", "stage-qa-deps-fail", "task-qa-deps-fail",
                        AgentRuntimeType.PI, "", "QA_AGENT"),
                command("task-qa-deps-fail", "QA_AGENT")
        ));

        assertEquals(1, runner.requests.size());
        assertEquals(RepairExecutionStatus.FAILED, result.status());
        assertEquals("ENVIRONMENT", result.rawResultJson().get("failureCategory"));
        assertTrue(result.errorMessage().toLowerCase(java.util.Locale.ROOT).contains("npm")
                || result.summary().toLowerCase(java.util.Locale.ROOT).contains("depend"), result.summary() + result.errorMessage());
    }

    @Test
    void shouldMountRepoReadOnlyForReviewAndArchitectRoles() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        DockerPiAgentExecutor executor = executor(runner, AgentExecutionEventSink.noop(), ignored -> "secret");

        executor.execute(new AgentRuntimeExecutionRequest(
                snapshot("snapshot-review", "stage-review", "task-review", AgentRuntimeType.PI, "", "REQUIREMENT_REVIEWER"),
                command("task-review", "REQUIREMENT_REVIEWER")
        ));
        assertEquals("/work/repo:ro", runner.request.mounts().get(
                temporaryDirectory.resolve("workspaces/task-review/repo").toString()
        ));
        assertEquals("/work/repo", runner.request.workingDirectory());

        executor.execute(new AgentRuntimeExecutionRequest(
                snapshot("snapshot-architect", "stage-architect", "task-architect", AgentRuntimeType.PI, "", "SOLUTION_ARCHITECT"),
                command("task-architect", "SOLUTION_ARCHITECT")
        ));
        assertEquals("/work/repo:ro", runner.request.mounts().get(
                temporaryDirectory.resolve("workspaces/task-architect/repo").toString()
        ));
    }

    @Test
    @Disabled("aspirational QA provider-attempt isolation; see pi-qa spec §4")
    void shouldMountQaRepoFromAnIndependentProviderAttemptWorkspace() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        DockerPiAgentExecutor executor = executor(runner, AgentExecutionEventSink.noop(), ignored -> "secret");

        executor.execute(new AgentRuntimeExecutionRequest(
                snapshot("snapshot-qa-ro", "stage-qa-ro", "task-qa-ro", AgentRuntimeType.PI, "", "QA_AGENT"),
                command("task-qa-ro", "QA_AGENT")
        ));
        String qaRepoMount = runner.request.mounts().entrySet().stream()
                .filter(entry -> "/work/repo:ro".equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
        assertTrue(qaRepoMount.contains("/provider-attempts/"), qaRepoMount);
        assertTrue(Files.isDirectory(Path.of(qaRepoMount)));
    }

    @Test
    @Disabled("aspirational QA provider-attempt isolation; see pi-qa spec §4")
    void shouldGiveQaAnIndependentCandidatePatchWorkspaceWhileRetainingTaskCache() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        DockerPiAgentExecutor executor = executor(runner, AgentExecutionEventSink.noop(), ignored -> "secret");

        Path taskRoot = temporaryDirectory.resolve("workspaces/task-qa-isolated");
        Path codingRepo = Files.createDirectories(taskRoot.resolve("repo"));
        Path codingOutput = Files.createDirectories(taskRoot.resolve("output"));
        Path taskCache = Files.createDirectories(taskRoot.resolve("cache"));
        Files.writeString(codingRepo.resolve("secret-coding-output.txt"), "coding-only\n");
        Files.writeString(codingOutput.resolve("secret-coding-output.txt"), "coding-only\n");
        Files.writeString(taskCache.resolve("package-cache-marker"), "retain\n");

        RepairJobCommand base = command("task-qa-isolated", "QA_AGENT");
        RepairJobCommand qaCommand = new RepairJobCommand(
                base.repairRecordId(),
                base.taskId(),
                base.ticketId(),
                base.ticketTitle(),
                base.prompt(),
                base.repositoryUrl(),
                base.repoOwner(),
                base.repoName(),
                base.baseBranch(),
                base.workBranch(),
                base.contextJson(),
                Map.of(
                        "repositoryPublishRequired", "false",
                        "applyCandidatePatch", "true",
                        "repositoryDeliveryMode", "LOCAL_ONLY"
                ),
                List.of(new RepairInputAttachment(
                        "candidate-patch.diff",
                        "text/x-diff",
                        "diff --git a/README.md b/README.md\n".getBytes(StandardCharsets.UTF_8)
                ))
        );

        RepairExecutionResult result = executor.execute(new AgentRuntimeExecutionRequest(
                snapshot("snapshot-qa-isolated", "stage-qa-isolated", "task-qa-isolated",
                        AgentRuntimeType.PI, "", "QA_AGENT"),
                qaCommand
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status(), result.errorMessage());
        Path qaInput = Path.of(runner.request.mounts().entrySet().stream()
                .filter(entry -> "/work/input:ro".equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow());
        Path qaRoot = qaInput.getParent();
        assertTrue(qaRoot.toString().contains("/provider-attempts/"), qaRoot.toString());
        assertNotNull(runner.request.mounts().get(taskCache.toString()), runner.request.mounts().toString());
        assertEquals("/work/cache", runner.request.mounts().get(taskCache.toString()));
        assertEquals("diff --git a/README.md b/README.md\n",
                Files.readString(qaInput.resolve("attachments/candidate-patch.diff")));
        assertFalse(Files.exists(qaRoot.resolve("repo/secret-coding-output.txt")));
        assertFalse(Files.exists(qaRoot.resolve("output/secret-coding-output.txt")));
        assertEquals("coding-only\n", Files.readString(codingRepo.resolve("secret-coding-output.txt")));
        assertEquals("coding-only\n", Files.readString(codingOutput.resolve("secret-coding-output.txt")));
        assertEquals("retain\n", Files.readString(taskCache.resolve("package-cache-marker")));
    }

    @Test
    void shouldFailClosedWhenCredentialRelayIsExplicitlyDisabled() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CapturingRunner runner = new CapturingRunner() {
            @Override
            public ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) {
                calls.incrementAndGet();
                return null;
            }
        };
        DockerPiAgentExecutor executor = executor(
                runner,
                AgentExecutionEventSink.noop(),
                ignored -> "secret",
                disabledCredentialRelayConfiguration()
        );

        RepairExecutionResult result = executor.execute(new AgentRuntimeExecutionRequest(
                snapshot("snapshot-relay-off", "stage-relay-off", "task-relay-off", AgentRuntimeType.PI, "",
                        "CODING_AGENT"),
                command("task-relay-off", "CODING_AGENT")
        ));

        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, result.status());
        assertEquals("PI_CREDENTIAL_RELAY_REQUIRED", result.rawResultJson().get("failureCategory"));
        assertEquals(0, calls.get());
        assertFalse(result.dockerMetadataJson().toString().contains("secret"));
    }

    @Test
    void shouldCreateATaskLocalRelayNetworkForReviewRolesWhenCredentialRelayEnabled() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        InMemoryPiCredentialLeaseIssuer issuer = new InMemoryPiCredentialLeaseIssuer();
        DockerPiAgentExecutor.Configuration relayOn = new DockerPiAgentExecutor.Configuration(
                "rd-bot/pi-agent:test",
                "rd-bot/pi-agent-qa:local",
                List.of("node", "/opt/rd-pi-bridge/src/rd-pi-bridge.mjs"),
                "bridge",
                true,
                false,
                60_000L,
                900_000L,
                16L * 1024L * 1024L,
                "v1",
                true
        );
        DockerPiAgentExecutor executor = new DockerPiAgentExecutor(
                new RepairWorkspaceFactory(temporaryDirectory.resolve("workspaces"), RESULT_SCHEMA),
                runner,
                new StructuredResultValidator(),
                relayOn,
                RepairWorkspaceRepositoryPort.noop(),
                ExecutionAllowlistPolicy.disabled(),
                PiResourceManifestMaterializerPort.emptyOnly(),
                PiSkillMaterializerPort.emptyOnly(),
                AgentExecutionEventSink.noop(),
                AgentPrivateArtifactPublisher.noop(),
                ignored -> "secret",
                issuer
        );

        executor.execute(new AgentRuntimeExecutionRequest(
                snapshot("snapshot-review-relay-net", "stage-review-relay-net", "task-review-relay-net",
                        AgentRuntimeType.PI, "", "REQUIREMENT_REVIEWER"),
                command("task-review-relay-net", "REQUIREMENT_REVIEWER")
        ));
        assertTrue(runner.request.networkMode().startsWith("rd-pi-network-"));
        assertNotNull(runner.request.networkPlan());
        assertEquals(runner.request.networkMode(), runner.request.networkPlan().internalNetworkName());
        assertEquals("rd-pi-relay", runner.request.networkPlan().sidecar().networkAlias());
        assertEquals("bridge", runner.request.networkPlan().sidecar().egressNetwork());
    }

    @Test
    void shouldRouteCodingThroughTheTaskLocalRelaySidecarWhenCredentialRelayEnabled() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        InMemoryPiCredentialLeaseIssuer issuer = new InMemoryPiCredentialLeaseIssuer();
        DockerPiAgentExecutor.Configuration relayOn = new DockerPiAgentExecutor.Configuration(
                "rd-bot/pi-agent:test",
                "rd-bot/pi-agent-qa:local",
                List.of("node", "/opt/rd-pi-bridge/src/rd-pi-bridge.mjs"),
                "bridge",
                true,
                false,
                60_000L,
                900_000L,
                16L * 1024L * 1024L,
                "v1",
                true
        );
        DockerPiAgentExecutor executor = new DockerPiAgentExecutor(
                new RepairWorkspaceFactory(temporaryDirectory.resolve("workspaces"), RESULT_SCHEMA),
                runner,
                new StructuredResultValidator(),
                relayOn,
                RepairWorkspaceRepositoryPort.noop(),
                ExecutionAllowlistPolicy.disabled(),
                PiResourceManifestMaterializerPort.emptyOnly(),
                PiSkillMaterializerPort.emptyOnly(),
                AgentExecutionEventSink.noop(),
                AgentPrivateArtifactPublisher.noop(),
                ignored -> "secret",
                issuer
        );

        executor.execute(new AgentRuntimeExecutionRequest(
                snapshot("snapshot-coding-relay-net", "stage-coding-relay-net", "task-coding-relay-net",
                        AgentRuntimeType.PI, "", "CODING_AGENT"),
                command("task-coding-relay-net", "CODING_AGENT")
        ));

        assertTrue(runner.request.networkMode().startsWith("rd-pi-network-"));
        assertNotNull(runner.request.networkPlan());
        assertEquals("rd-pi-relay", runner.request.networkPlan().sidecar().networkAlias());
        assertEquals("bridge", runner.request.networkPlan().sidecar().egressNetwork());
        assertFalse(runner.request.env().containsKey("PI_TEST_API_KEY"));
    }

    @Test
    void shouldProvisionQaProfileInputAndEnvironmentForQaExecutions() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        DockerPiAgentExecutor executor = executor(runner, AgentExecutionEventSink.noop(), ignored -> "secret");

        executor.execute(new com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest(
                snapshot("snapshot-qa3", "stage-qa3", "task-qa3", AgentRuntimeType.PI, "", "QA_AGENT"),
                command("task-qa3", "QA_AGENT")
        ));

        // QA parity with the Claude executor: profile lands in the read-only input
        // mount and the container receives the QA environment contract.
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("workspaces/task-qa3/input/qa-profile.json")));
        assertEquals("/work/input/qa-profile.json", runner.request.env().get("RD_QA_PROFILE_FILE"));
        assertTrue(runner.request.env().containsKey("RD_QA_ALLOWED_HOSTS"));
        assertEquals("/work/output/qa-work/playwright", runner.request.env().get("PLAYWRIGHT_MCP_OUTPUT_DIR"));
    }

    @Test
    void shouldPublishDeterminableQaCandidateChangedFilesInDockerMetadata() throws Exception {
        String taskId = "task-qa-docs-meta";
        String stageRunId = "stage-" + taskId;
        IdentityCapturingRunner runner = new IdentityCapturingRunner(stageRunId, taskId, "QA_AGENT");
        DockerPiAgentExecutor executor = executor(
                runner,
                AgentExecutionEventSink.noop(),
                ignored -> "secret",
                new DocsOnlyCandidateRepositoryPort()
        );

        RepairExecutionResult result = executor.execute(new AgentRuntimeExecutionRequest(
                snapshot("snapshot-qa-docs-meta", stageRunId, taskId, AgentRuntimeType.PI, "", "QA_AGENT"),
                command(taskId, "QA_AGENT")
        ));

        assertTrue(
                result.dockerMetadataJson().containsKey(QaExecutionMetadataKeys.CANDIDATE_CHANGED_FILES_JSON),
                () -> "QA candidate files must ride dockerMetadataJson; github="
                        + result.githubMetadataJson()
                        + " docker="
                        + result.dockerMetadataJson()
        );
        assertFalse(
                result.githubMetadataJson().containsKey(QaExecutionMetadataKeys.CANDIDATE_CHANGED_FILES_JSON),
                "repository/github metadata must not carry the host docs-only decision channel"
        );
        assertEquals(
                "DOCS_ONLY",
                result.dockerMetadataJson().get(QaExecutionMetadataKeys.DECISION_SOURCE)
        );

        List<String> fromDocker = QaExecutionMetadataKeys.candidateChangedFilesFrom(result.dockerMetadataJson());
        assertNotNull(fromDocker, "determinable docs-only change set must deserialize from docker metadata");
        assertTrue(fromDocker.contains("docs/rd-bot-full-run-smoke.md"), fromDocker.toString());

        AgentRoleResultValidation hostValidation = new QaEvidenceBundleValidator().validate(
                docsOnlyQaResultJson(),
                docsOnlyHostArtifacts(),
                List.of("docs marker present"),
                fromDocker
        );
        assertTrue(hostValidation.valid(), () -> String.join("; ", hostValidation.errors()));
        assertNull(
                QaExecutionMetadataKeys.candidateChangedFilesFrom(Map.of()),
                "missing docker key must stay fail-closed (undeterminable)"
        );
    }

    @Test
    void shouldNotCollectTransientQaWorkFilesAsDeliveryArtifacts() throws Exception {
        CapturingRunner runner = new CapturingRunner() {
            @Override
            public ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) throws IOException {
                ContainerRunResult result = super.run(request, listener);
                Path qaWork = request.outputDirectory().resolve("qa-work/node_modules/pkg");
                Files.createDirectories(qaWork);
                Files.writeString(qaWork.resolve("index.js"), "module.exports = {};\n", StandardCharsets.UTF_8);
                Path evidence = request.outputDirectory().resolve("qa-evidence/commands");
                Files.createDirectories(evidence);
                Files.writeString(evidence.resolve("current.log"), "ok\n", StandardCharsets.UTF_8);
                return result;
            }
        };
        DockerPiAgentExecutor executor = executor(runner, AgentExecutionEventSink.noop(), ignored -> "secret");

        RepairExecutionResult result = executor.execute(new com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest(
                snapshot("snapshot-qa-work", "stage-1", "task-1", AgentRuntimeType.PI, ""),
                command("task-1", "CODING_AGENT")
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status(), result.errorMessage());
        assertFalse(result.artifacts().stream()
                .anyMatch(artifact -> artifact.name().startsWith("qa-work/")));
        assertTrue(result.artifacts().stream()
                .anyMatch(artifact -> artifact.name().equals("qa-evidence/commands/current.log")));
    }

    @Test
    void shouldKeepIntegrityMetadataForBinaryQaEvidenceArtifacts() throws Exception {
        byte[] fakePng = new byte[]{(byte) 0x89, 'P', 'N', 'G', (byte) 0xFF, (byte) 0xFE, 0x00, 0x01};
        CapturingRunner runner = new CapturingRunner() {
            @Override
            public ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) throws IOException {
                ContainerRunResult result = super.run(request, listener);
                Path screenshots = request.outputDirectory().resolve("qa-evidence/screenshots");
                Files.createDirectories(screenshots);
                Files.write(screenshots.resolve("current-desktop.png"), fakePng);
                return result;
            }
        };
        DockerPiAgentExecutor executor = executor(runner, AgentExecutionEventSink.noop(), ignored -> "secret");

        RepairExecutionResult result = executor.execute(new com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest(
                snapshot("snapshot-bin", "stage-1", "task-1", AgentRuntimeType.PI, ""),
                command("task-1", "CODING_AGENT")
        ));

        // Binary evidence must keep bytes/sha256: reading a PNG as UTF-8 used to
        // throw and erase the whole metadata map, failing QA bundle validation.
        var screenshot = result.artifacts().stream()
                .filter(artifact -> artifact.name().equals("qa-evidence/screenshots/current-desktop.png"))
                .findFirst()
                .orElseThrow();
        assertEquals(String.valueOf(fakePng.length), screenshot.metadataJson().get("bytes"));
        assertEquals(64, screenshot.metadataJson().get("sha256").length());
        assertEquals("image/png", screenshot.metadataJson().get("contentType"));
        assertFalse(screenshot.metadataJson().containsKey("contentPreview"));
    }

    @Test
    void shouldFailClosedWhenProviderCredentialIsMissing() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CapturingRunner runner = new CapturingRunner() {
            @Override
            public ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) {
                calls.incrementAndGet();
                return null;
            }
        };
        DockerPiAgentExecutor executor = executor(runner, AgentExecutionEventSink.noop(), ignored -> "");

        RepairExecutionResult result = executor.execute(new com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest(
                snapshot("snapshot-credential", "stage-credential", "task-credential", AgentRuntimeType.PI, ""),
                command("task-credential", "CODING_AGENT")
        ));

        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, result.status());
        assertEquals("PI_PROVIDER_CONFIGURATION", result.rawResultJson().get("failureCategory"));
        assertEquals(0, calls.get());
    }

    @Test
    void shouldDefaultCredentialedExecutionsToTheRelayAndNeverInjectRawSecret() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        DockerPiAgentExecutor executor = executor(runner, AgentExecutionEventSink.noop(), ignored -> "secret-key");

        RepairExecutionResult result = executor.execute(new com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest(
                snapshot("snapshot-1", "stage-1", "task-1", AgentRuntimeType.PI, ""),
                command("task-1", "CODING_AGENT")
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status(), result.errorMessage());
        assertTrue(defaultConfiguration().credentialRelayEnabled());
        assertFalse(runner.request.env().containsKey("PI_TEST_API_KEY"));
        assertTrue(runner.request.env().get("RD_PI_CREDENTIAL_LEASE").startsWith("pcl_"));
        assertFalse(runner.request.env().values().stream().anyMatch(value -> value != null && value.contains("secret-key")));
    }

    @Test
    void shouldFailClosedWhenCredentialRelayEnabledButNotImplemented() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CapturingRunner runner = new CapturingRunner() {
            @Override
            public ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) {
                calls.incrementAndGet();
                return null;
            }
        };
        DockerPiAgentExecutor.Configuration relayOn = new DockerPiAgentExecutor.Configuration(
                "rd-bot/pi-agent:test",
                "rd-bot/pi-agent-qa:local",
                List.of("node", "/opt/rd-pi-bridge/src/rd-pi-bridge.mjs"),
                "bridge",
                true,
                false,
                60_000L,
                900_000L,
                16L * 1024L * 1024L,
                "v1",
                true
        );
        DockerPiAgentExecutor executor = executorWithoutLeaseIssuer(
                runner,
                AgentExecutionEventSink.noop(),
                ignored -> "secret",
                relayOn
        );

        RepairExecutionResult result = executor.execute(new com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest(
                snapshot("snapshot-relay", "stage-relay", "task-relay", AgentRuntimeType.PI, ""),
                command("task-relay", "CODING_AGENT")
        ));

        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, result.status());
        assertEquals("PI_PROVIDER_CONFIGURATION", result.rawResultJson().get("failureCategory"));
        assertTrue(result.errorMessage().contains("credential relay")
                || result.errorMessage().contains("PiCredentialLeaseIssuer"));
        assertEquals(0, calls.get());
    }

    @Test
    void shouldFailClosedBeforeContainerCreationWhenRunnerCannotHonorRelayNetworkPlan() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CapturingRunner runner = new CapturingRunner() {
            @Override
            public boolean supportsNetworkPlans() {
                return false;
            }

            @Override
            public ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) {
                calls.incrementAndGet();
                return null;
            }
        };
        DockerPiAgentExecutor executor = executor(
                runner,
                AgentExecutionEventSink.noop(),
                ignored -> "secret",
                defaultConfiguration()
        );

        RepairExecutionResult result = executor.execute(new AgentRuntimeExecutionRequest(
                snapshot("snapshot-network-capability", "stage-network-capability", "task-network-capability",
                        AgentRuntimeType.PI, ""),
                command("task-network-capability", "CODING_AGENT")
        ));

        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, result.status());
        assertEquals("PI_RELAY_NETWORK_CAPABILITY", result.rawResultJson().get("failureCategory"));
        assertEquals(0, calls.get());
    }

    @Test
    void shouldAllowExplicitNoCredentialLocalPathWithoutWritingCredentialEnvironment() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        DockerPiAgentExecutor executor = executor(
                runner,
                AgentExecutionEventSink.noop(),
                ignored -> "unused-secret",
                disabledCredentialRelayConfiguration()
        );

        RepairExecutionResult result = executor.execute(new AgentRuntimeExecutionRequest(
                snapshotWithoutCredential("snapshot-1", "stage-1", "task-1"),
                command("task-1", "CODING_AGENT")
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status(), result.errorMessage());
        assertFalse(runner.request.env().containsKey("PI_TEST_API_KEY"));
        assertFalse(runner.request.env().values().stream().anyMatch(value -> value != null && value.contains("unused-secret")));
        JsonNode requestJson = OBJECT_MAPPER.readTree(Files.readString(
                temporaryDirectory.resolve("workspaces/task-1/input/request.json"),
                StandardCharsets.UTF_8
        ));
        assertTrue(requestJson.path("credentialEnvironmentVariable").isMissingNode());
        assertFalse(requestJson.path("authHeader").asBoolean());
    }

    @Test
    void shouldInjectOpaqueLeaseInsteadOfRawSecretWhenRelayEnabled() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        InMemoryPiCredentialLeaseIssuer issuer = new InMemoryPiCredentialLeaseIssuer();
        DockerPiAgentExecutor.Configuration relayOn = new DockerPiAgentExecutor.Configuration(
                "rd-bot/pi-agent:test",
                "rd-bot/pi-agent-qa:local",
                List.of("node", "/opt/rd-pi-bridge/src/rd-pi-bridge.mjs"),
                "bridge",
                true,
                false,
                60_000L,
                900_000L,
                16L * 1024L * 1024L,
                "v1",
                true
        );
        DockerPiAgentExecutor executor = new DockerPiAgentExecutor(
                new RepairWorkspaceFactory(temporaryDirectory.resolve("workspaces"), RESULT_SCHEMA),
                runner,
                new StructuredResultValidator(),
                relayOn,
                RepairWorkspaceRepositoryPort.noop(),
                ExecutionAllowlistPolicy.disabled(),
                PiResourceManifestMaterializerPort.emptyOnly(),
                PiSkillMaterializerPort.emptyOnly(),
                AgentExecutionEventSink.noop(),
                AgentPrivateArtifactPublisher.noop(),
                ignored -> "super-secret-key",
                issuer
        );

        RepairExecutionResult result = executor.execute(new com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest(
                snapshot("snapshot-lease", "stage-lease", "task-lease", AgentRuntimeType.PI, ""),
                command("task-lease", "CODING_AGENT")
        ));

        Map<String, String> env = runner.request.env();
        assertEquals("true", env.get("RD_PI_CREDENTIAL_RELAY_ENABLED"));
        assertTrue(env.get("RD_PI_CREDENTIAL_LEASE").startsWith("pcl_"));
        assertFalse(env.containsKey("RD_PI_CREDENTIAL_RELAY_URL"));
        assertFalse(env.containsKey("RD_PI_CREDENTIAL_RELAY_TASK_ID"));
        assertFalse(env.containsKey("RD_PI_CREDENTIAL_RELAY_STAGE_RUN_ID"));
        assertFalse(env.containsKey("RD_PI_CREDENTIAL_RELAY_PROVIDER_ID"));
        assertFalse(env.containsKey("PI_TEST_API_KEY"));
        assertFalse(env.values().stream().anyMatch(value -> value != null && value.contains("super-secret")));
        assertNotNull(runner.request.networkPlan());
        Map<String, String> relayEnv = runner.request.networkPlan().sidecar().env();
        assertEquals("node", runner.request.networkPlan().sidecar().entrypoint());
        assertEquals(
                List.of("/opt/rd-pi-bridge/src/rd-pi-relay-sidecar.mjs"),
                runner.request.networkPlan().sidecar().command()
        );
        assertEquals(
                "http://host.docker.internal:18080/internal/pi/credential-relay/proxy",
                relayEnv.get("RD_PI_RELAY_HOST_URL")
        );
        assertEquals("task-lease", relayEnv.get("RD_PI_RELAY_TASK_ID"));
        assertEquals("stage-lease", relayEnv.get("RD_PI_RELAY_STAGE_RUN_ID"));
        assertEquals("provider-1", relayEnv.get("RD_PI_RELAY_PROVIDER_ID"));
        assertFalse(relayEnv.values().stream().anyMatch(value -> value != null && value.contains("super-secret")));
        assertTrue(issuer.authorize(
                env.get("RD_PI_CREDENTIAL_LEASE"),
                "task-lease",
                "stage-lease",
                "provider-1",
                "POST",
                "/chat/completions",
                2
        ).isPresent());
        String requestJson = Files.readString(
                temporaryDirectory.resolve("workspaces/task-lease/input/request.json"),
                StandardCharsets.UTF_8
        );
        assertTrue(requestJson.contains("http://rd-pi-relay:8787"));
        assertTrue(OBJECT_MAPPER.readTree(requestJson).path("authHeader").asBoolean());
        assertFalse(requestJson.contains("super-secret"));
        assertFalse(runner.request.command().toString().contains("super-secret"));
        assertFalse(result.dockerMetadataJson().toString().contains("super-secret"));
    }

    @Test
    void shouldAlignCredentialRelayTimeoutWithPiExecutionTimeout() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        InMemoryPiCredentialLeaseIssuer issuer = new InMemoryPiCredentialLeaseIssuer();
        long executionTimeoutMillis = 3_600_000L;
        DockerPiAgentExecutor.Configuration relayOn = new DockerPiAgentExecutor.Configuration(
                "rd-bot/pi-agent:test",
                "rd-bot/pi-agent-qa:local",
                List.of("node", "/opt/rd-pi-bridge/src/rd-pi-bridge.mjs"),
                "bridge",
                true,
                false,
                executionTimeoutMillis,
                900_000L,
                16L * 1024L * 1024L,
                "v1",
                true
        );
        DockerPiAgentExecutor executor = new DockerPiAgentExecutor(
                new RepairWorkspaceFactory(temporaryDirectory.resolve("workspaces"), RESULT_SCHEMA),
                runner,
                new StructuredResultValidator(),
                relayOn,
                RepairWorkspaceRepositoryPort.noop(),
                ExecutionAllowlistPolicy.disabled(),
                PiResourceManifestMaterializerPort.emptyOnly(),
                PiSkillMaterializerPort.emptyOnly(),
                AgentExecutionEventSink.noop(),
                AgentPrivateArtifactPublisher.noop(),
                ignored -> "super-secret-key",
                issuer
        );

        executor.execute(new AgentRuntimeExecutionRequest(
                snapshot("snapshot-relay-timeout", "stage-relay-timeout", "task-relay-timeout", AgentRuntimeType.PI, ""),
                command("task-relay-timeout", "CODING_AGENT")
        ));

        Map<String, String> relayEnv = runner.request.networkPlan().sidecar().env();
        assertEquals(String.valueOf(executionTimeoutMillis), relayEnv.get("RD_PI_RELAY_TIMEOUT_MILLIS"));
        PiCredentialLeaseIssuer.RelayGrant grant = issuer.authorize(
                runner.request.env().get("RD_PI_CREDENTIAL_LEASE"),
                "task-relay-timeout",
                "stage-relay-timeout",
                "provider-1",
                "POST",
                "/chat/completions",
                2
        ).orElseThrow();
        assertEquals(Duration.ofMillis(executionTimeoutMillis), grant.relayPolicy().requestTimeout());
    }

    @Test
    void shouldFailClosedWhenSelectedExtensionSetCannotBeMaterialized() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        DockerPiAgentExecutor executor = executor(runner, AgentExecutionEventSink.noop(), ignored -> "secret");

        RepairExecutionResult result = executor.execute(new com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest(
                snapshot("snapshot-extension", "stage-extension", "task-extension", AgentRuntimeType.PI, "set-v1"),
                command("task-extension", "CODING_AGENT")
        ));

        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, result.status());
        assertEquals("PI_RESOURCE_CONFIGURATION", result.rawResultJson().get("failureCategory"));
        assertTrue(runner.request == null);
    }

    @Test
    void shouldClassifyMalformedLiveEventAsProtocolFailure() throws Exception {
        CapturingRunner runner = new CapturingRunner() {
            @Override
            public ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) {
                listener.onStdout("not-json\n");
                return null;
            }
        };
        DockerPiAgentExecutor executor = executor(runner, AgentExecutionEventSink.noop(), ignored -> "secret");

        RepairExecutionResult result = executor.execute(new com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest(
                snapshot("snapshot-event", "stage-event", "task-event", AgentRuntimeType.PI, ""),
                command("task-event", "CODING_AGENT")
        ));

        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, result.status());
        assertEquals("PI_EVENT_PROTOCOL", result.rawResultJson().get("failureCategory"));
    }

    private DockerPiAgentExecutor executor(
            StreamingContainerRunnerPort runner,
            AgentExecutionEventSink eventSink,
            com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor.AuthEnvironmentResolver authResolver
    ) {
        return executor(
                new RepairWorkspaceFactory(temporaryDirectory.resolve("workspaces"), RESULT_SCHEMA),
                runner,
                eventSink,
                authResolver,
                defaultConfiguration(),
                RepairWorkspaceRepositoryPort.noop()
        );
    }

    private DockerPiAgentExecutor executor(
            StreamingContainerRunnerPort runner,
            AgentExecutionEventSink eventSink,
            com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor.AuthEnvironmentResolver authResolver,
            RepairWorkspaceRepositoryPort workspaceRepository
    ) {
        return executor(
                new RepairWorkspaceFactory(temporaryDirectory.resolve("workspaces"), RESULT_SCHEMA),
                runner,
                eventSink,
                authResolver,
                defaultConfiguration(),
                workspaceRepository
        );
    }

    private DockerPiAgentExecutor executor(
            StreamingContainerRunnerPort runner,
            AgentExecutionEventSink eventSink,
            com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor.AuthEnvironmentResolver authResolver,
            DockerPiAgentExecutor.Configuration configuration
    ) {
        return executor(
                new RepairWorkspaceFactory(temporaryDirectory.resolve("workspaces"), RESULT_SCHEMA),
                runner,
                eventSink,
                authResolver,
                configuration,
                RepairWorkspaceRepositoryPort.noop()
        );
    }

    private DockerPiAgentExecutor executor(
            RepairWorkspaceFactory workspaceFactory,
            StreamingContainerRunnerPort runner,
            AgentExecutionEventSink eventSink,
            com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor.AuthEnvironmentResolver authResolver
    ) {
        return executor(
                workspaceFactory,
                runner,
                eventSink,
                authResolver,
                defaultConfiguration(),
                RepairWorkspaceRepositoryPort.noop()
        );
    }

    private DockerPiAgentExecutor executor(
            RepairWorkspaceFactory workspaceFactory,
            StreamingContainerRunnerPort runner,
            AgentExecutionEventSink eventSink,
            com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor.AuthEnvironmentResolver authResolver,
            DockerPiAgentExecutor.Configuration configuration
    ) {
        return executor(
                workspaceFactory,
                runner,
                eventSink,
                authResolver,
                configuration,
                RepairWorkspaceRepositoryPort.noop()
        );
    }

    private DockerPiAgentExecutor executor(
            RepairWorkspaceFactory workspaceFactory,
            StreamingContainerRunnerPort runner,
            AgentExecutionEventSink eventSink,
            com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor.AuthEnvironmentResolver authResolver,
            DockerPiAgentExecutor.Configuration configuration,
            RepairWorkspaceRepositoryPort workspaceRepository
    ) {
        return new DockerPiAgentExecutor(
                workspaceFactory,
                runner,
                new StructuredResultValidator(),
                configuration,
                workspaceRepository,
                ExecutionAllowlistPolicy.disabled(),
                PiResourceManifestMaterializerPort.emptyOnly(),
                PiSkillMaterializerPort.emptyOnly(),
                eventSink,
                com.wish.rd.exec.repair.pi.AgentPrivateArtifactPublisher.noop(),
                authResolver,
                configuration.credentialRelayEnabled() ? new InMemoryPiCredentialLeaseIssuer() : null
        );
    }

    private DockerPiAgentExecutor executorWithoutLeaseIssuer(
            StreamingContainerRunnerPort runner,
            AgentExecutionEventSink eventSink,
            com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor.AuthEnvironmentResolver authResolver,
            DockerPiAgentExecutor.Configuration configuration
    ) {
        return new DockerPiAgentExecutor(
                new RepairWorkspaceFactory(temporaryDirectory.resolve("workspaces"), RESULT_SCHEMA),
                runner,
                new StructuredResultValidator(),
                configuration,
                RepairWorkspaceRepositoryPort.noop(),
                ExecutionAllowlistPolicy.disabled(),
                PiResourceManifestMaterializerPort.emptyOnly(),
                eventSink,
                authResolver
        );
    }

    private static DockerPiAgentExecutor.Configuration defaultConfiguration() {
        return new DockerPiAgentExecutor.Configuration(
                "rd-bot/pi-agent:test",
                "rd-bot/pi-agent-qa:local",
                List.of("node", "/opt/rd-pi-bridge/src/rd-pi-bridge.mjs"),
                "bridge",
                true,
                false,
                60_000L,
                900_000L,
                16L * 1024L * 1024L,
                "v1",
                true
        );
    }

    private static DockerPiAgentExecutor.Configuration disabledCredentialRelayConfiguration() {
        return new DockerPiAgentExecutor.Configuration(
                "rd-bot/pi-agent:test",
                "rd-bot/pi-agent-qa:local",
                List.of("node", "/opt/rd-pi-bridge/src/rd-pi-bridge.mjs"),
                "bridge",
                true,
                false,
                60_000L,
                900_000L,
                16L * 1024L * 1024L,
                "v1",
                false
        );
    }

    private static DockerPiAgentExecutor.Configuration v2Configuration() {
        return new DockerPiAgentExecutor.Configuration(
                "rd-bot/pi-agent:test",
                "rd-bot/pi-agent-qa:local",
                List.of("node", "/opt/rd-pi-bridge/src/rd-pi-bridge.mjs"),
                "bridge",
                true,
                false,
                60_000L,
                900_000L,
                16L * 1024L * 1024L,
                "v2"
        );
    }

    private static RepairJobCommand v2Command(
            String taskId,
            String role,
            String inputManifestHash,
            String contextPolicyJson,
            String inputManifestJson
    ) {
        Map<String, String> context = new java.util.LinkedHashMap<>(command(taskId, role).contextJson());
        context.put("inputManifestHash", inputManifestHash);
        context.put("contextPolicyJson", contextPolicyJson);
        context.put("inputManifestJson", inputManifestJson);
        return new RepairJobCommand(
                "repair-" + taskId,
                taskId,
                "ticket-1",
                "Implement Pi runtime",
                "Implement the requested change",
                "https://github.com/acme/repo.git",
                "acme",
                "repo",
                "main",
                "repair/" + taskId,
                context,
                Map.of("repositoryPublishRequired", "false"),
                List.of()
        );
    }

    private static RepairJobCommand commandWithInitialState(
            String taskId,
            String role,
            String stateJson,
            String stateHash
    ) {
        RepairJobCommand base = command(taskId, role);
        Map<String, String> context = new java.util.LinkedHashMap<>(base.contextJson());
        context.put("initialAgentStateProtocol", "rd-agent-state/v2");
        context.put("initialAgentStateJson", stateJson);
        context.put("initialAgentStateHash", stateHash);
        return new RepairJobCommand(
                base.repairRecordId(), base.taskId(), base.ticketId(), base.ticketTitle(), base.prompt(),
                base.repositoryUrl(), base.repoOwner(), base.repoName(), base.baseBranch(), base.workBranch(),
                context, base.policyJson(), base.attachments()
        );
    }

    private static String canonicalInitialState(String taskId, String stageRunId, String snapshotId)
            throws Exception {
        return AgentStateV2Codec.canonicalize(OBJECT_MAPPER.readTree("""
                {
                  "protocol":"rd-agent-state/v2",
                  "sequence":0,
                  "taskId":"%s",
                  "stageRunId":"%s",
                  "role":"CODING_AGENT",
                  "attemptNo":1,
                  "runtimeType":"PI",
                  "profileSnapshotId":"%s",
                  "budget":{"availability":"UNKNOWN"},
                  "todos":[],
                  "facts":[]
                }
                """.formatted(taskId, stageRunId, snapshotId)));
    }

    private static AgentExecutionProfileSnapshot stateV2Snapshot(
            String snapshotId,
            String stageRunId,
            String taskId
    ) throws IOException {
        AgentExecutionProfileSnapshot base = snapshot(
                snapshotId, stageRunId, taskId, AgentRuntimeType.PI, "", "CODING_AGENT"
        );
        ObjectNode json = (ObjectNode) OBJECT_MAPPER.readTree(base.snapshotJson());
        json.putArray("capabilities").add(AgentRuntimeCapability.PI_AGENT_STATE_V2.name());
        json.put("dynamicStateEnabled", true);
        json.put("agentStateSchemaVersion", "rd-agent-state/v2");
        String snapshotJson = OBJECT_MAPPER.writeValueAsString(json);
        return new AgentExecutionProfileSnapshot(
                snapshotId, stageRunId, taskId, "CODING_AGENT", 1, AgentRuntimeType.PI,
                snapshotJson, AgentExecutionProfileSnapshot.sha256(snapshotJson), 1L
        );
    }

    private static AgentExecutionProfileSnapshot qaRemediationSnapshot(
            String snapshotId,
            String stageRunId,
            String taskId
    ) throws IOException {
        AgentExecutionProfileSnapshot base = snapshot(
                snapshotId, stageRunId, taskId, AgentRuntimeType.PI, "", "QA_AGENT"
        );
        ObjectNode json = (ObjectNode) OBJECT_MAPPER.readTree(base.snapshotJson());
        json.putArray("capabilities").add(AgentRuntimeCapability.PI_QA_REMEDIATION_V2.name());
        String snapshotJson = OBJECT_MAPPER.writeValueAsString(json);
        return new AgentExecutionProfileSnapshot(
                snapshotId, stageRunId, taskId, "QA_AGENT", 1, AgentRuntimeType.PI,
                snapshotJson, AgentExecutionProfileSnapshot.sha256(snapshotJson), 1L
        );
    }

    private static RepairJobCommand command(String taskId, String role) {
        return command(taskId, role, "Implement the requested change");
    }

    private static RepairJobCommand command(String taskId, String role, String prompt) {
        return new RepairJobCommand(
                "repair-" + taskId,
                taskId,
                "ticket-1",
                "Implement Pi runtime",
                prompt,
                "https://github.com/acme/repo.git",
                "acme",
                "repo",
                "main",
                "repair/" + taskId,
                Map.of("agentRole", role, "stageRunId", "stage-" + taskId),
                Map.of("repositoryPublishRequired", "false"),
                List.of()
        );
    }

    private static String hostAssertionContractsJson() {
        return """
                [
                  {
                    "scope": "CURRENT",
                    "contentHash": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "version": 1
                  },
                  {
                    "scope": "REGRESSION",
                    "contentHash": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    "version": 1
                  }
                ]
                """;
    }

    private static AgentExecutionProfileSnapshot snapshot(
            String snapshotId,
            String stageRunId,
            String taskId,
            AgentRuntimeType runtimeType,
            String extensionSetId
    ) throws IOException {
        return snapshot(snapshotId, stageRunId, taskId, runtimeType, extensionSetId, "CODING_AGENT");
    }

    private static AgentExecutionProfileSnapshot snapshot(
            String snapshotId,
            String stageRunId,
            String taskId,
            AgentRuntimeType runtimeType,
            String extensionSetId,
            String role
    ) throws IOException {
        String json = OBJECT_MAPPER.writeValueAsString(Map.ofEntries(
                Map.entry("snapshotVersion", 1),
                Map.entry("stageRunId", stageRunId),
                Map.entry("taskId", taskId),
                Map.entry("role", role),
                Map.entry("attemptNo", 1),
                Map.entry("runtimeType", runtimeType.name()),
                Map.entry("profileId", "profile-1"),
                Map.entry("profileVersion", 1),
                Map.entry("providerProfileId", "provider-1"),
                Map.entry("modelOverride", ""),
                Map.entry("providerProtocol", "OPENAI_CHAT_COMPLETIONS"),
                Map.entry("providerBaseUrl", "https://api.example.test/v1"),
                Map.entry("providerModelId", "gpt-test"),
                Map.entry("credentialEnvironmentVariable", "PI_TEST_API_KEY"),
                Map.entry("extensionSetId", extensionSetId),
                Map.entry("toolPolicyId", "coding-default"),
                Map.entry("toolPolicyVersion", 1),
                Map.entry("toolPolicy", Map.of(
                        "hostAllow", List.of("read", "bash", "edit", "write", "rd_submit_result"),
                        "allow", List.of("read", "bash", "edit", "write", "rd_submit_result"),
                        "deny", List.of(),
                        "effectiveAllow", List.of("bash", "edit", "read", "rd_submit_result", "write"),
                        "enabled", true
                ))
        ));
        return new AgentExecutionProfileSnapshot(
                snapshotId,
                stageRunId,
                taskId,
                role,
                1,
                runtimeType,
                json,
                AgentExecutionProfileSnapshot.sha256(json),
                1L
        );
    }

    private static AgentExecutionProfileSnapshot snapshotWithoutCredential(
            String snapshotId,
            String stageRunId,
            String taskId
    ) throws IOException {
        AgentExecutionProfileSnapshot base = snapshot(
                snapshotId,
                stageRunId,
                taskId,
                AgentRuntimeType.PI,
                "",
                "CODING_AGENT"
        );
        ObjectNode json = (ObjectNode) OBJECT_MAPPER.readTree(base.snapshotJson());
        json.remove("credentialEnvironmentVariable");
        String snapshotJson = OBJECT_MAPPER.writeValueAsString(json);
        return new AgentExecutionProfileSnapshot(
                base.snapshotId(),
                base.stageRunId(),
                base.taskId(),
                base.role(),
                base.attemptNo(),
                base.runtimeType(),
                snapshotJson,
                AgentExecutionProfileSnapshot.sha256(snapshotJson),
                base.resolvedAtEpochMillis()
        );
    }

    private static AgentExecutionProfileSnapshot snapshotUnchecked(
            String snapshotId,
            String stageRunId,
            String taskId
    ) {
        try {
            return snapshot(snapshotId, stageRunId, taskId, AgentRuntimeType.PI, "");
        } catch (IOException exception) {
            throw new AssertionError("failed to build Pi test snapshot", exception);
        }
    }

    private static String docsOnlyQaResultJson() {
        return """
                {
                  "status": "PASSED",
                  "summary": "docs-only candidate verified without browser regression",
                  "failureCategory": "NONE",
                  "retryRecommendation": "NONE",
                  "browserValidation": {
                    "required": false,
                    "performed": false,
                    "decisionSource": "DOCS_ONLY",
                    "baseUrl": "",
                    "browser": "chromium",
                    "viewports": []
                  },
                  "acceptanceResults": [
                    {
                      "criteria": "docs marker present",
                      "scope": "CURRENT",
                      "command": "rg -n rd-bot-full-run README.md docs/rd-bot-full-run-smoke.md",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 120,
                      "logArtifactId": "qa-evidence/commands/current.log",
                      "evidenceArtifactIds": ["qa-evidence/commands/current.log"]
                    },
                    {
                      "criteria": "no runtime files changed",
                      "scope": "REGRESSION",
                      "command": "git diff --cached --name-only",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 80,
                      "logArtifactId": "qa-evidence/commands/regression.log",
                      "evidenceArtifactIds": ["qa-evidence/commands/regression.log"]
                    }
                  ],
                  "evidenceManifestArtifactId": "qa-evidence/manifest.json"
                }
                """;
    }

    private static List<RepairArtifact> docsOnlyHostArtifacts() {
        List<RepairArtifact> evidence = List.of(
                hostArtifact(RepairArtifactType.QA_COMMAND_LOG, "qa-evidence/commands/current.log", "current log"),
                hostArtifact(RepairArtifactType.QA_COMMAND_LOG, "qa-evidence/commands/regression.log", "regression log")
        );
        String entries = evidence.stream()
                .map(artifact -> """
                        {"path":"%s","bytes":%s,"sha256":"%s"}
                        """.formatted(
                        artifact.name(),
                        artifact.metadataJson().get("bytes"),
                        artifact.metadataJson().get("sha256")
                ).strip())
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        List<RepairArtifact> artifacts = new ArrayList<>(evidence);
        artifacts.add(0, hostArtifact(
                RepairArtifactType.QA_EVIDENCE_MANIFEST,
                "qa-evidence/manifest.json",
                "{\"version\":1,\"artifacts\":[" + entries + "]}"
        ));
        return List.copyOf(artifacts);
    }

    private static RepairArtifact hostArtifact(RepairArtifactType type, String name, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new RepairArtifact(
                type,
                name,
                "file:///tmp/output/" + name,
                "QA evidence",
                Map.of(
                        "bytes", String.valueOf(bytes.length),
                        "sha256", sha256Hex(bytes),
                        "contentType", "application/octet-stream",
                        "contentPreview", body
                )
        );
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static RepairWorkspaceRepositoryPort npmMonorepo() {
        return new RepairWorkspaceRepositoryPort() {
            @Override
            public RepositoryOperationResult prepare(RepairJobCommand command, RepairWorkspace workspace)
                    throws IOException {
                Path repo = workspace.repoDirectory();
                Files.writeString(repo.resolve("package.json"), """
                        {"name":"root","scripts":{"install:all":"true"}}
                        """, StandardCharsets.UTF_8);
                Files.createDirectories(repo.resolve("server"));
                Files.writeString(repo.resolve("server").resolve("package.json"), """
                        {"name":"server","dependencies":{"express":"4.0.0"}}
                        """, StandardCharsets.UTF_8);
                Files.createDirectories(repo.resolve("client"));
                Files.writeString(repo.resolve("client").resolve("package.json"), """
                        {"name":"client","devDependencies":{"vite":"5.0.0"}}
                        """, StandardCharsets.UTF_8);
                Files.writeString(repo.resolve("start.sh"), "#!/bin/sh\nnpm install\n", StandardCharsets.UTF_8);
                return RepositoryOperationResult.empty();
            }

            @Override
            public RepositoryOperationResult publish(RepairJobCommand command, RepairWorkspace workspace) {
                return RepositoryOperationResult.empty();
            }
        };
    }

    private static final class RecordingRunner extends CapturingRunner {
        private final List<ContainerRunRequest> requests = new ArrayList<>();
        private int provisionExitCode;

        @Override
        public ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) throws IOException {
            requests.add(request);
            if (request.entrypoint() != null && !request.entrypoint().isBlank()) {
                this.request = request;
                if (provisionExitCode != 0) {
                    return new ContainerRunResult(
                            provisionExitCode,
                            5L,
                            "",
                            "npm install failed: ENOTCACHED",
                            request.outputDirectory().resolve("result.json"),
                            null,
                            null,
                            null,
                            null,
                            Map.of("containerName", request.containerName())
                    );
                }
            }
            return super.run(request, listener);
        }
    }

    private static final class DocsOnlyCandidateRepositoryPort implements RepairWorkspaceRepositoryPort {

        @Override
        public RepositoryOperationResult prepare(RepairJobCommand command, RepairWorkspace workspace) throws IOException {
            Path repo = workspace.repoDirectory();
            Files.createDirectories(repo);
            runGit(repo, "init");
            runGit(repo, "config", "user.email", "qa-meta@example.test");
            runGit(repo, "config", "user.name", "qa-meta");
            // Baseline looks like a runnable Next.js app so the auto profile requires
            // browser QA; the staged docs-only patch then downgrades to DOCS_ONLY.
            Files.writeString(repo.resolve("README.md"), "baseline\n", StandardCharsets.UTF_8);
            Files.writeString(repo.resolve("package.json"), """
                    {
                      "name": "qa-docs-meta",
                      "scripts": {
                        "dev": "next dev",
                        "build": "next build",
                        "start": "next start"
                      },
                      "dependencies": {
                        "next": "15.0.0",
                        "react": "19.0.0",
                        "react-dom": "19.0.0"
                      }
                    }
                    """, StandardCharsets.UTF_8);
            runGit(repo, "add", "README.md", "package.json");
            runGit(repo, "commit", "-m", "baseline");
            Path docs = Files.createDirectories(repo.resolve("docs"));
            Files.writeString(
                    docs.resolve("rd-bot-full-run-smoke.md"),
                    "rd-bot-full-run smoke marker\n",
                    StandardCharsets.UTF_8
            );
            runGit(repo, "add", "docs/rd-bot-full-run-smoke.md");
            return new RepositoryOperationResult(Map.of("candidatePatchApplied", "true"));
        }

        @Override
        public RepositoryOperationResult publish(RepairJobCommand command, RepairWorkspace workspace) {
            return RepositoryOperationResult.empty();
        }

        private static void runGit(Path repo, String... args) throws IOException {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.add("-C");
            command.add(repo.toAbsolutePath().normalize().toString());
            command.addAll(List.of(args));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output;
            try (InputStream stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("git timed out: " + command);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("git interrupted: " + command, exception);
            }
            if (process.exitValue() != 0) {
                throw new IOException("git failed (" + process.exitValue() + "): " + command + "\n" + output);
            }
        }
    }

    private static class CapturingRunner implements StreamingContainerRunnerPort {

        protected ContainerRunRequest request;

        @Override
        public boolean supportsNetworkPlans() {
            return true;
        }

        @Override
        public ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) throws IOException {
            this.request = request;
            Files.createDirectories(request.outputDirectory());
            Files.writeString(request.outputDirectory().resolve("result.json"), """
                    {
                      "status": "SUCCESS",
                      "summary": "Pi produced a valid patch",
                      "prBody": "Implementation details",
                      "changedFiles": ["src/App.java"],
                      "testCommands": ["./mvnw test"],
                      "testStatus": "PASSED",
                      "riskLevel": "LOW",
                      "needHumanAction": false
                    }
                    """, StandardCharsets.UTF_8);
            Files.writeString(request.outputDirectory().resolve("patch.diff"), "diff --git a/src/App.java b/src/App.java\n");
            Files.writeString(request.outputDirectory().resolve("test.log"), "./mvnw test\nBUILD SUCCESS\n");
            String events = """
                    {"protocol":"rd-agent-event/v1","eventType":"RUNTIME_READY","sourceSequence":1,"stageRunId":"stage-1","taskId":"task-1","role":"CODING_AGENT"}
                    {"protocol":"rd-agent-event/v1","eventType":"AGENT_STARTED","sourceSequence":2,"stageRunId":"stage-1","taskId":"task-1","role":"CODING_AGENT"}
                    {"protocol":"rd-agent-event/v1","eventType":"RESULT_SUBMITTED","sourceSequence":3,"stageRunId":"stage-1","taskId":"task-1","role":"CODING_AGENT"}
                    {"protocol":"rd-agent-event/v1","eventType":"AGENT_SETTLED","sourceSequence":4,"stageRunId":"stage-1","taskId":"task-1","role":"CODING_AGENT"}
                    """;
            Files.writeString(request.outputDirectory().resolve("agent-events.jsonl"), events, StandardCharsets.UTF_8);
            listener.onStdout(events.substring(0, 83));
            listener.onStdout(events.substring(83));
            return new ContainerRunResult(
                    0,
                    12L,
                    "",
                    "",
                    request.outputDirectory().resolve("result.json"),
                    request.outputDirectory().resolve("patch.diff"),
                    request.outputDirectory().resolve("test.log"),
                    null,
                    null,
                    Map.of("containerName", request.containerName())
            );
        }
    }

    private static final class ProtocolFailureReceiptRunner implements StreamingContainerRunnerPort {

        private final String taskId;
        private final String stageRunId;
        private final boolean mismatchedIdentity;

        private ProtocolFailureReceiptRunner(String taskId, String stageRunId, boolean mismatchedIdentity) {
            this.taskId = taskId;
            this.stageRunId = stageRunId;
            this.mismatchedIdentity = mismatchedIdentity;
        }

        @Override
        public boolean supportsNetworkPlans() {
            return true;
        }

        @Override
        public ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) throws IOException {
            Files.createDirectories(request.outputDirectory());
            Path resultPath = request.outputDirectory().resolve("result.json");
            Files.writeString(resultPath, "{\"status\":\"FAILED\",\"failureCategory\":\"PI_BRIDGE_PROTOCOL\"}\n");
            String events = """
                    {"protocol":"rd-agent-event/v1","eventType":"RUNTIME_READY","sourceSequence":1,"stageRunId":"%s","taskId":"%s","role":"QA_AGENT"}
                    {"protocol":"rd-agent-event/v1","eventType":"AGENT_SETTLED","sourceSequence":2,"stageRunId":"%s","taskId":"%s","role":"QA_AGENT"}
                    {"protocol":"rd-agent-event/v1","eventType":"PROTOCOL_ERROR","sourceSequence":3,"stageRunId":"%s","taskId":"%s","role":"QA_AGENT","payload":{"category":"PI_RESULT_RECOVERY"}}
                    {"protocol":"rd-agent-event/v1","eventType":"RESULT_SUBMITTED","sourceSequence":4,"stageRunId":"%s","taskId":"%s","role":"QA_AGENT","payload":{"source":"BRIDGE_SYNTHETIC"}}
                    """.formatted(
                    stageRunId, taskId,
                    stageRunId, taskId,
                    stageRunId, taskId,
                    stageRunId, taskId
            );
            Files.writeString(request.outputDirectory().resolve("agent-events.jsonl"), events);
            Files.writeString(request.outputDirectory().resolve("runtime-meta.json"), "{}\n");
            ObjectNode receipt = OBJECT_MAPPER.createObjectNode();
            receipt.put("protocol", "PiProtocolFailureReceipt/v1");
            receipt.put("kind", "RESULT_MISSING_AFTER_RECOVERY");
            receipt.putArray("missingFacts").add("AGENT_RESULT_SUBMITTED");
            receipt.put("resultSubmitted", true);
            receipt.put("resultSubmissionSource", "BRIDGE_SYNTHETIC");
            receipt.put("roleSchemaAccepted", false);
            receipt.put("acceptedResultDigest", "");
            receipt.put("agentSettled", true);
            receipt.put("eventStreamTrusted", true);
            receipt.put("containerTerminated", true);
            receipt.put("recoveryApplicable", true);
            receipt.put("recoveryIssued", true);
            receipt.put("recoveryExhausted", true);
            receipt.put("lastRejectionKind", "NONE");
            receipt.put("lastRejectionDigest", "");
            receipt.putArray("diagnosticArtifactIds").add("agent-events.jsonl").add("runtime-meta.json");
            receipt.put("taskId", mismatchedIdentity ? "wrong-task" : taskId);
            receipt.put("stageRunId", stageRunId);
            receipt.put("role", "QA_AGENT");
            receipt.put("attemptNo", 1);
            receipt.put("generatedAt", "2026-08-18T00:00:00Z");
            Files.writeString(
                    request.outputDirectory().resolve("pi-protocol-failure-receipt.json"),
                    AgentStateV2Codec.canonicalize(receipt)
            );
            listener.onStdout(events);
            return new ContainerRunResult(
                    0, 12L, "", "", resultPath, null, null, null, null,
                    Map.of("containerName", request.containerName())
            );
        }
    }

    private static final class StateV2ArtifactRunner extends CapturingRunner {

        private final String corruption;

        private StateV2ArtifactRunner(String corruption) {
            this.corruption = corruption == null ? "" : corruption;
        }

        @Override
        public ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) throws IOException {
            ContainerRunResult result = super.run(request, listener);
            Path inputDirectory = request.mounts().entrySet().stream()
                    .filter(entry -> "/work/input:ro".equals(entry.getValue()) || "/work/input".equals(entry.getValue()))
                    .map(entry -> Path.of(entry.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new IOException("missing /work/input mount"));
            JsonNode requestJson = OBJECT_MAPPER.readTree(Files.readString(
                    inputDirectory.resolve("request.json"), StandardCharsets.UTF_8
            ));
            ObjectNode state = (ObjectNode) OBJECT_MAPPER.readTree(requestJson.path("initialAgentStateJson").asText());
            if ("STATE_IDENTITY".equals(corruption)) {
                state.put("taskId", "wrong-task");
            }
            String stateJson = AgentStateV2Codec.canonicalize(state);
            String stateHash = AgentStateV2Codec.hash(state);
            Files.writeString(
                    request.outputDirectory().resolve("agent-state-latest.json"),
                    stateJson,
                    StandardCharsets.UTF_8
            );

            long injectionSequence = 1L;
            long stateSequence = state.path("sequence").asLong();
            String promptHash = "sha256:" + AgentExecutionProfileSnapshot.sha256(requestJson.path("prompt").asText());
            String block = "<rd-agent-state protocol=\"rd-agent-state/v2\" state-sequence=\""
                    + stateSequence + "\" injection-sequence=\"" + injectionSequence
                    + "\" state-hash=\"" + stateHash + "\">\n" + stateJson + "\n</rd-agent-state>";
            String blockHash = "sha256:" + AgentExecutionProfileSnapshot.sha256(block);
            if ("BLOCK_HASH".equals(corruption)) {
                blockHash = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
            }
            String idempotencyKey = "sha256:" + AgentExecutionProfileSnapshot.sha256(
                    requestJson.path("stageRunId").asText() + ":" + injectionSequence + ":" + blockHash
            );
            ObjectNode effective = OBJECT_MAPPER.createObjectNode();
            effective.put("protocol", "rd-agent-effective-context/v1");
            effective.put("taskId", requestJson.path("taskId").asText());
            effective.put("stageRunId", requestJson.path("stageRunId").asText());
            effective.put("role", requestJson.path("role").asText());
            effective.put("attemptNo", requestJson.path("attemptNo").asInt());
            effective.put("injectionSequence", injectionSequence);
            effective.put("stateSequence", stateSequence);
            effective.put("stateHash", stateHash);
            effective.put("promptHash", promptHash);
            effective.put("blockHash", blockHash);
            effective.put("injectedBlock", block);
            effective.put("injectedAt", "2026-08-18T00:00:00Z");
            effective.put("idempotencyKey", idempotencyKey);
            effective.put("bytes", block.getBytes(StandardCharsets.UTF_8).length);
            effective.putArray("compositionOrder").add("PROMPT_SNAPSHOT").add("AGENT_STATE_BLOCK");
            Files.writeString(
                    request.outputDirectory().resolve("agent-effective-context-latest.json"),
                    OBJECT_MAPPER.writeValueAsString(effective),
                    StandardCharsets.UTF_8
            );
            return result;
        }
    }

    private static final class IdentityCapturingRunner extends CapturingRunner {

        private final String stageRunId;
        private final String taskId;
        private final String role;

        private IdentityCapturingRunner(String stageRunId, String taskId, String role) {
            this.stageRunId = stageRunId;
            this.taskId = taskId;
            this.role = role;
        }

        @Override
        public ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) throws IOException {
            this.request = request;
            Files.createDirectories(request.outputDirectory());
            Files.writeString(
                    request.outputDirectory().resolve("result.json"),
                    docsOnlyQaResultJson(),
                    StandardCharsets.UTF_8
            );
            String events = """
                    {"protocol":"rd-agent-event/v1","eventType":"RUNTIME_READY","sourceSequence":1,"stageRunId":"%s","taskId":"%s","role":"%s"}
                    {"protocol":"rd-agent-event/v1","eventType":"AGENT_STARTED","sourceSequence":2,"stageRunId":"%s","taskId":"%s","role":"%s"}
                    {"protocol":"rd-agent-event/v1","eventType":"RESULT_SUBMITTED","sourceSequence":3,"stageRunId":"%s","taskId":"%s","role":"%s"}
                    {"protocol":"rd-agent-event/v1","eventType":"AGENT_SETTLED","sourceSequence":4,"stageRunId":"%s","taskId":"%s","role":"%s"}
                    """.formatted(
                    stageRunId, taskId, role,
                    stageRunId, taskId, role,
                    stageRunId, taskId, role,
                    stageRunId, taskId, role
            );
            Files.writeString(request.outputDirectory().resolve("agent-events.jsonl"), events, StandardCharsets.UTF_8);
            listener.onStdout(events);
            return new ContainerRunResult(
                    0,
                    12L,
                    "",
                    "",
                    request.outputDirectory().resolve("result.json"),
                    null,
                    null,
                    null,
                    null,
                    Map.of("containerName", request.containerName())
            );
        }
    }
}
