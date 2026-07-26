package com.wish.rd.bootstrap.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.executor.impl.EngineRequirementExecutorAdapter;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStatus;
import com.wish.rd.exec.repair.runtime.AgentRuntimeRouter;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.engine.requirement.model.RequirementExecutionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineRequirementAgentRuntimeAdapterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldRouteAnEnabledRequirementAttemptThroughItsSnapshotRuntime() {
        AtomicInteger legacyCalls = new AtomicInteger();
        AtomicInteger piCalls = new AtomicInteger();
        InMemoryAgentExecutionProfileSnapshotStore snapshots = new InMemoryAgentExecutionProfileSnapshotStore();
        AgentExecutionProfileSnapshot snapshot = snapshot(AgentRuntimeType.PI, "snapshot-pi");
        snapshots.saveIfAbsent(snapshot);
        AgentRuntimeRouter router = new AgentRuntimeRouter(Map.of(
                AgentRuntimeType.PI, request -> {
                    piCalls.incrementAndGet();
                    assertEquals("snapshot-pi", request.command().policyJson().get("executionProfileSnapshotId"));
                    return result("pi");
                }
        ));
        RepairExecutorPort legacy = command -> {
            legacyCalls.incrementAndGet();
            return result("legacy");
        };

        EngineRequirementExecutorAdapter adapter = new EngineRequirementExecutorAdapter(
                legacy,
                new EngineRequirementExecutorAdapter.AgentRuntimeConfiguration(true, router, snapshots)
        );

        var execution = adapter.execute(request("snapshot-pi"));

        assertTrue(execution.success());
        assertEquals("pi", execution.summary());
        assertEquals(1, piCalls.get());
        assertEquals(0, legacyCalls.get());
    }

    @Test
    void shouldRejectARequirementAttemptWithoutAnImmutableSnapshotBeforeCallingLegacyExecutor() {
        AtomicInteger legacyCalls = new AtomicInteger();
        RepairExecutorPort legacy = command -> {
            legacyCalls.incrementAndGet();
            return result("legacy");
        };
        AgentRuntimeRouter router = new AgentRuntimeRouter(Map.of(
                AgentRuntimeType.PI, request -> result("pi")
        ));
        EngineRequirementExecutorAdapter adapter = new EngineRequirementExecutorAdapter(
                legacy,
                new EngineRequirementExecutorAdapter.AgentRuntimeConfiguration(
                        true, router, new InMemoryAgentExecutionProfileSnapshotStore())
        );

        assertThrows(IllegalStateException.class, () -> adapter.execute(request("missing")));
        assertEquals(0, legacyCalls.get());
    }

    @Test
    void shouldNotExposeRestrictedPiArtifactsInTheNormalStageArtifactPreview() throws Exception {
        RepairExecutorPort legacy = command -> new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "completed",
                "",
                List.of(new RepairArtifact(
                        RepairArtifactType.PI_SESSION,
                        "private/session/session.jsonl",
                        "s3://rd-pi-session/session.jsonl",
                        "restricted session",
                        Map.of("restricted", "true")
                )),
                Map.of("status", "SUCCESS", "summary", "completed"),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                ""
        );

        JsonNode result = OBJECT_MAPPER.readTree(new EngineRequirementExecutorAdapter(legacy)
                .execute(request(""))
                .resultJson());

        assertTrue(result.path("stageArtifacts").isArray());
        assertTrue(result.path("stageArtifacts").isEmpty());
    }

    private RequirementExecutionRequest request(String snapshotId) {
        CreateRequirementTaskCommand command = new CreateRequirementTaskCommand(
                "需求交付", "P1", "https://github.com/acme/order.git", "", "", "main",
                "完成需求", List.of("测试通过"), false
        );
        RdRequirementTask task = RdRequirementTask.created("task-1001", command, 1000L);
        return new RequirementExecutionRequest(
                "task-1001", task, List.of(), "implement", com.wish.rd.engine.agent.model.AgentRole.CODING_AGENT,
                "{}", false, "[]", "stage-pi", snapshotId
        );
    }

    private AgentExecutionProfileSnapshot snapshot(AgentRuntimeType runtimeType, String id) {
        String json = "{\"runtimeType\":\"" + runtimeType.name() + "\"}";
        return new AgentExecutionProfileSnapshot(
                id, "stage-pi", "task-1001", "CODING_AGENT", 1, runtimeType, json,
                AgentExecutionProfileSnapshot.sha256(json), 1L
        );
    }

    private static RepairExecutionResult result(String summary) {
        return new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS, summary, "", List.of(),
                Map.of("status", "SUCCESS", "summary", summary), Map.of(), Map.of(), Map.of(), Map.of(), ""
        );
    }
}
