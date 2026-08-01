package com.wish.rd.exec.repair.pi;

import com.wish.rd.exec.repair.pi.impl.DockerPiAgentExecutor;
import com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.docker.ContainerOutputListener;
import com.wish.rd.exec.repair.docker.RepairWorkspaceFactory;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.docker.StreamingContainerRunnerPort;
import com.wish.rd.exec.repair.docker.model.ContainerRunRequest;
import com.wish.rd.exec.repair.docker.model.ContainerRunResult;
import com.wish.rd.exec.repair.docker.model.RepairWorkspace;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.result.StructuredResultValidator;
import com.wish.rd.exec.repair.runtime.AgentExecutionEventSink;
import com.wish.rd.exec.repair.security.model.ExecutionAllowlistPolicy;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerPiAgentExecutorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String RESULT_SCHEMA = "{\"type\":\"object\"}";

    @TempDir
    Path temporaryDirectory;

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
        assertTrue(result.artifacts().stream()
                .anyMatch(artifact -> "runtime-context-manifest.json".equals(artifact.name())));
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
                defaultConfiguration()
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
                configuration
        );
    }

    private DockerPiAgentExecutor executor(
            RepairWorkspaceFactory workspaceFactory,
            StreamingContainerRunnerPort runner,
            AgentExecutionEventSink eventSink,
            com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor.AuthEnvironmentResolver authResolver
    ) {
        return executor(workspaceFactory, runner, eventSink, authResolver, defaultConfiguration());
    }

    private DockerPiAgentExecutor executor(
            RepairWorkspaceFactory workspaceFactory,
            StreamingContainerRunnerPort runner,
            AgentExecutionEventSink eventSink,
            com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor.AuthEnvironmentResolver authResolver,
            DockerPiAgentExecutor.Configuration configuration
    ) {
        return new DockerPiAgentExecutor(
                workspaceFactory,
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
                60_000L
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

    private static class CapturingRunner implements StreamingContainerRunnerPort {

        protected ContainerRunRequest request;

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
}
