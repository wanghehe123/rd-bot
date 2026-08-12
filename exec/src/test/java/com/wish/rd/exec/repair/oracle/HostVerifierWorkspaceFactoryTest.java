package com.wish.rd.exec.repair.oracle;

import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.oracle.model.HostVerifierWorkspace;
import com.wish.rd.exec.repair.oracle.model.HostVerifierWorkspaceRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HostVerifierWorkspaceFactoryTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void shouldCreateNeutralWorkspaceForHostScope() throws Exception {
        HostVerifierWorkspaceFactory factory = (command, request) -> new HostVerifierWorkspace(
                workspaceRoot,
                "http://127.0.0.1:18080",
                Map.of("origin", "host")
        );

        HostVerifierWorkspace workspace = factory.create(
                command(),
                new HostVerifierWorkspaceRequest("task-1", "stage-1", "CURRENT")
        );

        assertEquals(workspaceRoot.toAbsolutePath().normalize(), workspace.workspaceRoot());
        assertEquals("http://127.0.0.1:18080", workspace.baseUrl());
        assertEquals("host", workspace.attributes().get("origin"));
    }

    private static RepairJobCommand command() {
        return new RepairJobCommand(
                "task-1-qa",
                "task-1-qa",
                "",
                "QA",
                "verify",
                "https://example.invalid/org/repo.git",
                "org",
                "repo",
                "main",
                "host-verify",
                Map.of("workflowTaskId", "task-1", "stageRunId", "stage-1"),
                Map.of("repositoryDeliveryMode", "LOCAL_ONLY"),
                List.of()
        );
    }
}
