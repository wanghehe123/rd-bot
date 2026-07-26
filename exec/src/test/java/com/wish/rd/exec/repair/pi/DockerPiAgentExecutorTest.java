package com.wish.rd.exec.repair.pi;

import com.wish.rd.exec.repair.pi.impl.DockerPiAgentExecutor;
import com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest;
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
        assertFalse(result.dockerMetadataJson().containsValue("test-provider-secret"));
        assertEquals("snapshot-1", result.dockerMetadataJson().get("executionProfileSnapshotId"));
    }

    @Test
    void shouldRejectQaSnapshotBeforeStartingPiContainer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CapturingRunner runner = new CapturingRunner() {
            @Override
            public ContainerRunResult run(ContainerRunRequest request, ContainerOutputListener listener) {
                calls.incrementAndGet();
                return null;
            }
        };
        DockerPiAgentExecutor executor = executor(runner, AgentExecutionEventSink.noop(), ignored -> "secret");

        RepairExecutionResult result = executor.execute(new com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest(
                snapshot("snapshot-qa", "stage-qa", "task-qa", AgentRuntimeType.PI, ""),
                command("task-qa", "QA_AGENT")
        ));

        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, result.status());
        assertEquals("PI_ROLE_NOT_ALLOWED", result.rawResultJson().get("failureCategory"));
        assertEquals(0, calls.get());
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
        return new DockerPiAgentExecutor(
                new RepairWorkspaceFactory(temporaryDirectory.resolve("workspaces"), RESULT_SCHEMA),
                runner,
                new StructuredResultValidator(),
                new DockerPiAgentExecutor.Configuration(
                        "rd-bot/pi-agent:test",
                        List.of("node", "/opt/rd-pi-bridge/src/rd-pi-bridge.mjs"),
                        "bridge",
                        true,
                        false,
                        60_000L
                ),
                RepairWorkspaceRepositoryPort.noop(),
                ExecutionAllowlistPolicy.disabled(),
                PiResourceManifestMaterializerPort.emptyOnly(),
                eventSink,
                authResolver
        );
    }

    private static RepairJobCommand command(String taskId, String role) {
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
        String json = OBJECT_MAPPER.writeValueAsString(Map.ofEntries(
                Map.entry("snapshotVersion", 1),
                Map.entry("stageRunId", stageRunId),
                Map.entry("taskId", taskId),
                Map.entry("role", "CODING_AGENT"),
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
                "CODING_AGENT",
                1,
                runtimeType,
                json,
                AgentExecutionProfileSnapshot.sha256(json),
                1L
        );
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
