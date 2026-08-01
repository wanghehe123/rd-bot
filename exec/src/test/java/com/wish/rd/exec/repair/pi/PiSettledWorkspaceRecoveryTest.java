package com.wish.rd.exec.repair.pi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiSettledWorkspaceRecoveryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldRecoverSettledArchitectWorkspaceWithoutRequiringContainerReplay() throws Exception {
        Path output = Files.createDirectories(
                temporaryDirectory.resolve("task-1-solution_architect/output"));
        Files.writeString(output.resolve("result.json"), """
                {
                  "status": "SUCCESS",
                  "summary": "Recovered architect plan",
                  "affectedFiles": ["README.md"],
                  "implementationSteps": ["Update README"],
                  "acceptanceMapping": [{"criteria": "marker present", "validation": "grep marker"}],
                  "testPlan": [{"criteria": "marker present", "command": "grep marker README.md"}]
                }
                """, StandardCharsets.UTF_8);
        Files.createDirectories(output.resolve("handoff"));
        Files.writeString(output.resolve("handoff/next.md"), "# handoff\n", StandardCharsets.UTF_8);
        Files.writeString(output.resolve("agent-events.jsonl"), """
                {"protocol":"rd-agent-event/v1","eventType":"RESULT_SUBMITTED","sourceSequence":1,"stageRunId":"stage-1","taskId":"task-1","role":"SOLUTION_ARCHITECT"}
                {"protocol":"rd-agent-event/v1","eventType":"AGENT_SETTLED","sourceSequence":2,"stageRunId":"stage-1","taskId":"task-1","role":"SOLUTION_ARCHITECT"}
                """, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("runtime-meta.json"), """
                {"provider":"opencode-go","model":"deepseek","snapshotId":"snapshot-1","settled":true,"resultAccepted":true}
                """, StandardCharsets.UTF_8);

        PiSettledWorkspaceRecovery recovery = new PiSettledWorkspaceRecovery(temporaryDirectory);

        PiSettledWorkspaceRecovery.RecoveredWorkspaceSnapshot snapshot = recovery
                .tryRecover("task-1", "stage-1", "SOLUTION_ARCHITECT")
                .orElseThrow();

        assertTrue(snapshot.success());
        assertTrue(snapshot.resultJson().contains("\"status\":\"SUCCESS\""));
        assertTrue(snapshot.resultJson().contains("stageArtifacts"));
        assertEquals("opencode-go", snapshot.providerName());
    }

    @Test
    void shouldRecoverCodingWorkspaceUnderBareTaskId() throws Exception {
        Path output = Files.createDirectories(temporaryDirectory.resolve("task-1/output"));
        Files.writeString(output.resolve("result.json"), """
                {
                  "status": "SUCCESS",
                  "summary": "Recovered coding patch",
                  "changedFiles": ["README.md"],
                  "commandsRun": ["npm test"],
                  "testResults": [{"name":"unit","passed":true}],
                  "residualRisks": []
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("patch.diff"), "diff --git a/README.md b/README.md\n", StandardCharsets.UTF_8);
        Files.writeString(output.resolve("test.log"), "ok\n", StandardCharsets.UTF_8);
        Files.writeString(output.resolve("agent-events.jsonl"), """
                {"protocol":"rd-agent-event/v1","eventType":"RESULT_SUBMITTED","sourceSequence":1,"stageRunId":"stage-2","taskId":"task-1","role":"CODING_AGENT"}
                {"protocol":"rd-agent-event/v1","eventType":"AGENT_SETTLED","sourceSequence":2,"stageRunId":"stage-2","taskId":"task-1","role":"CODING_AGENT"}
                """, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("runtime-meta.json"), """
                {"provider":"opencode-go","settled":true,"resultAccepted":true}
                """, StandardCharsets.UTF_8);

        PiSettledWorkspaceRecovery recovery = new PiSettledWorkspaceRecovery(temporaryDirectory);

        assertTrue(recovery.tryRecover("task-1", "stage-2", "CODING_AGENT").isPresent());
    }

    @Test
    void shouldReturnEmptyWhenLifecycleIsIncomplete() throws Exception {
        Path output = Files.createDirectories(
                temporaryDirectory.resolve("task-1-solution_architect/output"));
        Files.writeString(output.resolve("result.json"), "{\"status\":\"SUCCESS\",\"summary\":\"orphan result\"}",
                StandardCharsets.UTF_8);
        Files.writeString(output.resolve("agent-events.jsonl"), """
                {"protocol":"rd-agent-event/v1","eventType":"RESULT_SUBMITTED","sourceSequence":1,"stageRunId":"stage-1","taskId":"task-1","role":"SOLUTION_ARCHITECT"}
                """, StandardCharsets.UTF_8);

        PiSettledWorkspaceRecovery recovery = new PiSettledWorkspaceRecovery(temporaryDirectory);

        assertFalse(recovery.tryRecover("task-1", "stage-1", "SOLUTION_ARCHITECT").isPresent());
    }
}
