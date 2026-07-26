package com.wish.rd.exec.repair.runtime;

import com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRuntimeRouterTest {

    @Test
    void shouldDispatchOnlyToTheRuntimeSelectedByTheImmutableSnapshot() {
        AgentRuntimeRouter router = new AgentRuntimeRouter(Map.of(
                AgentRuntimeType.PI, result -> result("pi"),
                AgentRuntimeType.CLAUDE_CODE, result -> result("claude"),
                AgentRuntimeType.MODEL_ONLY, result -> result("model-only")
        ));

        assertEquals("pi", router.execute(request(AgentRuntimeType.PI)).summary());
        assertEquals("claude", router.execute(request(AgentRuntimeType.CLAUDE_CODE)).summary());
        assertEquals("model-only", router.execute(request(AgentRuntimeType.MODEL_ONLY)).summary());
    }

    @Test
    void shouldRejectAnUnregisteredRuntimeBeforeCallingAnExecutor() {
        AgentRuntimeRouter router = new AgentRuntimeRouter(Map.of(
                AgentRuntimeType.CLAUDE_CODE, result -> result("claude")
        ));

        assertThrows(
                UnsupportedAgentRuntimeException.class,
                () -> router.execute(request(AgentRuntimeType.PI))
        );
    }

    @Test
    void shouldRejectAHashedSnapshotThatWasTamperedWith() {
        AgentExecutionProfileSnapshot snapshot = request(AgentRuntimeType.PI).snapshot();
        AgentExecutionProfileSnapshot tampered = new AgentExecutionProfileSnapshot(
                snapshot.snapshotId(),
                snapshot.stageRunId(),
                snapshot.taskId(),
                snapshot.role(),
                snapshot.attemptNo(),
                snapshot.runtimeType(),
                "{\"runtimeType\":\"CLAUDE_CODE\"}",
                snapshot.snapshotHash(),
                snapshot.resolvedAtEpochMillis()
        );
        AgentRuntimeRouter router = new AgentRuntimeRouter(Map.of(
                AgentRuntimeType.PI, result -> result("pi")
        ));

        assertThrows(
                IllegalArgumentException.class,
                () -> router.execute(new AgentRuntimeExecutionRequest(tampered, command()))
        );
    }

    private AgentRuntimeExecutionRequest request(AgentRuntimeType runtimeType) {
        String json = "{\"runtimeType\":\"" + runtimeType.name() + "\"}";
        AgentExecutionProfileSnapshot snapshot = new AgentExecutionProfileSnapshot(
                "snapshot-1",
                "stage-1",
                "task-1",
                "CODING_AGENT",
                1,
                runtimeType,
                json,
                AgentExecutionProfileSnapshot.sha256(json),
                1L
        );
        return new AgentRuntimeExecutionRequest(snapshot, command());
    }

    private RepairJobCommand command() {
        return new RepairJobCommand(
                "repair-1", "task-1", "", "title", "prompt", "https://github.com/acme/repo.git",
                "acme", "repo", "main", "work/task-1", Map.of(), Map.of()
        );
    }

    private static RepairExecutionResult result(String summary) {
        return new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS, summary, "", List.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), ""
        );
    }
}
