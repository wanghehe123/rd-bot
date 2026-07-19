package com.wish.rd.exec.repair.docker.impl;

import com.wish.rd.exec.repair.docker.model.ContainerRunRequest;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerExecutionRegistryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldExposeLogicalTaskAndLiveTokenUsageFromMountedEvents() throws Exception {
        Path outputDirectory = temporaryDirectory.resolve("output");
        Files.createDirectories(outputDirectory);
        Files.writeString(outputDirectory.resolve("claude-events.jsonl"), """
                {"type":"assistant","message":{"id":"message-1","usage":{"input_tokens":7,"output_tokens":8}}}
                """);
        DockerExecutionRegistry registry = DockerExecutionRegistry.noop();
        RepairJobCommand command = new RepairJobCommand(
                "execution-task", "execution-task", "", "title", "prompt", "", "", "", "main", "",
                Map.of("workflowTaskId", "workflow-task", "stageRunId", "stage-1"), Map.of()
        );
        ContainerRunRequest request = new ContainerRunRequest(
                "container-1", "image", List.of("claude"), Map.of(), Map.of(), "/work", "none", true, false,
                outputDirectory
        );

        registry.register(command, "claude", request);
        DockerExecutionRegistry.RunningExecution snapshot = registry.runningExecutions().getFirst();

        assertEquals("workflow-task", snapshot.taskId());
        assertEquals("execution-task", snapshot.executionTaskId());
        assertEquals("stage-1", snapshot.stageRunId());
        assertEquals(15L, snapshot.tokenUsage().totalTokens());
        assertTrue(snapshot.tokenUsage().available());
    }
}
