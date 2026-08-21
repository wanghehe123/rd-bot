package com.wish.rd.bootstrap.executor.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wish.rd.exec.repair.runtime.AgentExecutionEventStore;
import com.wish.rd.exec.repair.runtime.impl.InMemoryAgentExecutionEventStore;
import com.wish.rd.rag.project.agent.AgentStageStateProjectionStore;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentStageStateProjectionStore;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.project.agent.model.AgentStateV2Codec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class ProjectionAwareAgentExecutionEventStoreTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String HASH_A = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void revalidatesCanonicalStateAndInjectionBeforeProjecting() throws Exception {
        InMemoryAgentExecutionProfileSnapshotStore snapshots = snapshots();
        InMemoryAgentStageStateProjectionStore projections = new InMemoryAgentStageStateProjectionStore();
        List<String> warnings = new ArrayList<>();
        ProjectionAwareAgentExecutionEventStore store = new ProjectionAwareAgentExecutionEventStore(
                new InMemoryAgentExecutionEventStore(), snapshots, projections, warnings::add
        );
        JsonNode state = state("task-1", "stage-1", "safe fact");
        String stateHash = AgentStateV2Codec.hash(state);

        store.onEvent("container-1", stateEvent(state, stateHash, 1L));
        String stateJson = AgentStateV2Codec.canonicalize(state);
        String block = "<rd-agent-state protocol=\"rd-agent-state/v2\" state-sequence=\"0\" "
                + "injection-sequence=\"1\" state-hash=\"" + stateHash + "\">\n"
                + stateJson + "\n</rd-agent-state>";
        String blockHash = "sha256:" + AgentExecutionProfileSnapshot.sha256(block);
        String idempotencyKey = "sha256:" + AgentExecutionProfileSnapshot.sha256(
                "stage-1:1:" + blockHash
        );
        store.onEvent("container-1", injectionEvent(
                block, stateHash, blockHash, idempotencyKey, 2L
        ));

        var projected = projections.findByStageRunId("stage-1").orElseThrow();
        assertEquals(0L, projected.stateSequence());
        assertEquals(stateHash, projected.stateHash());
        assertEquals(1L, projected.injectionSequence());
        assertEquals(blockHash, projected.injectedBlockHash());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void rejectsHashIdentityAndSecretPayloadsWithoutReplacingProjection() throws Exception {
        InMemoryAgentStageStateProjectionStore projections = new InMemoryAgentStageStateProjectionStore();
        List<String> warnings = new ArrayList<>();
        ProjectionAwareAgentExecutionEventStore store = new ProjectionAwareAgentExecutionEventStore(
                new InMemoryAgentExecutionEventStore(), snapshots(), projections, warnings::add
        );
        JsonNode safe = state("task-1", "stage-1", "safe fact");
        store.onEvent("container-1", stateEvent(safe, AgentStateV2Codec.hash(safe), 1L));

        JsonNode wrongIdentity = state("wrong-task", "stage-1", "safe fact");
        store.onEvent("container-1", stateEvent(wrongIdentity, AgentStateV2Codec.hash(wrongIdentity), 2L));
        store.onEvent("container-1", stateEvent(safe, HASH_A, 3L));
        JsonNode secret = state("task-1", "stage-1", "api_key=super-secret-value");
        store.onEvent("container-1", stateEvent(secret, AgentStateV2Codec.hash(secret), 4L));

        assertEquals(0L, projections.findByStageRunId("stage-1").orElseThrow().stateSequence());
        assertEquals(3, warnings.size());
        assertTrue(warnings.stream().anyMatch(message -> message.contains("identity")));
        assertTrue(warnings.stream().anyMatch(message -> message.contains("hash")));
        assertTrue(warnings.stream().anyMatch(message -> message.contains("sensitive")));
    }

    @Test
    void projectionFailureOnlyWarnsAndKeepsTheNormalizedEventObservable() throws Exception {
        AgentStageStateProjectionStore failingProjection = mock(AgentStageStateProjectionStore.class);
        doThrow(new IllegalStateException("database unavailable")).when(failingProjection).projectState(any());
        AgentExecutionEventStore delegate = new InMemoryAgentExecutionEventStore();
        List<String> warnings = new ArrayList<>();
        ProjectionAwareAgentExecutionEventStore store = new ProjectionAwareAgentExecutionEventStore(
                delegate, snapshots(), failingProjection, warnings::add
        );
        JsonNode state = state("task-1", "stage-1", "safe fact");

        assertDoesNotThrow(() -> store.onEvent(
                "container-1", stateEvent(state, AgentStateV2Codec.hash(state), 1L)
        ));

        assertFalse(delegate.snapshot("task-1", "stage-1", 0L, 10).events().isEmpty());
        assertTrue(warnings.stream().anyMatch(message -> message.contains("database unavailable")));
    }

    @Test
    void runtimeStoppedFinalizesTheCapabilityGatedProjectionWithoutChangingOutcome() throws Exception {
        InMemoryAgentStageStateProjectionStore projections = new InMemoryAgentStageStateProjectionStore();
        AgentExecutionEventStore delegate = new InMemoryAgentExecutionEventStore();
        List<String> warnings = new ArrayList<>();
        ProjectionAwareAgentExecutionEventStore store = new ProjectionAwareAgentExecutionEventStore(
                delegate, snapshots(), projections, warnings::add
        );
        JsonNode state = state("task-1", "stage-1", "safe fact");
        store.onEvent("container-1", stateEvent(state, AgentStateV2Codec.hash(state), 1L));

        store.onEvent("container-1", baseEvent("RUNTIME_STOPPED", 2L));

        var projected = projections.findByStageRunId("stage-1").orElseThrow();
        assertTrue(projected.finalized());
        assertEquals(1_700_000_000_000L, projected.finalizedAtEpochMillis());
        assertEquals(2, delegate.snapshot("task-1", "stage-1", 0L, 10).events().size());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    private static InMemoryAgentExecutionProfileSnapshotStore snapshots() throws Exception {
        InMemoryAgentExecutionProfileSnapshotStore store = new InMemoryAgentExecutionProfileSnapshotStore();
        String json = """
                {"capabilities":["PI_AGENT_STATE_V2"],"runtimeType":"PI"}
                """.strip();
        store.saveIfAbsent(new AgentExecutionProfileSnapshot(
                "snapshot-1", "stage-1", "task-1", "CODING_AGENT", 1,
                AgentRuntimeType.PI, json, AgentExecutionProfileSnapshot.sha256(json), 1L
        ));
        return store;
    }

    private static JsonNode state(String taskId, String stageRunId, String fact) throws Exception {
        ObjectNode state = OBJECT_MAPPER.createObjectNode();
        state.put("protocol", "rd-agent-state/v2");
        state.put("sequence", 0L);
        state.put("taskId", taskId);
        state.put("stageRunId", stageRunId);
        state.put("role", "CODING_AGENT");
        state.put("attemptNo", 1);
        state.put("runtimeType", "PI");
        state.put("profileSnapshotId", "snapshot-1");
        state.putObject("budget").put("availability", "UNKNOWN");
        state.putArray("todos");
        state.putArray("facts").addObject().put("statement", fact);
        return state;
    }

    private static ObjectNode stateEvent(JsonNode state, String hash, long sourceSequence) {
        ObjectNode event = baseEvent("STATE_SNAPSHOT_UPDATED", sourceSequence);
        ObjectNode payload = event.putObject("payload");
        payload.put("stateSequence", state.path("sequence").asLong());
        payload.put("stateHash", hash);
        payload.set("snapshot", state);
        payload.put("projectedAt", Instant.ofEpochMilli(1_700_000_000_000L).toString());
        payload.put("idempotencyKey", "sha256:" + AgentExecutionProfileSnapshot.sha256(
                state.path("stageRunId").asText() + ":" + state.path("sequence").asLong() + ":" + hash
        ));
        return event;
    }

    private static ObjectNode injectionEvent(
            String block,
            String stateHash,
            String blockHash,
            String idempotencyKey,
            long sourceSequence
    ) {
        ObjectNode event = baseEvent("STATE_CONTEXT_INJECTED", sourceSequence);
        ObjectNode payload = event.putObject("payload");
        payload.put("injectionSequence", 1L);
        payload.put("stateSequence", 0L);
        payload.put("stateHash", stateHash);
        payload.put("promptHash", HASH_A);
        payload.put("blockHash", blockHash);
        payload.put("injectedBlock", block);
        payload.put("injectedAt", Instant.ofEpochMilli(1_700_000_000_100L).toString());
        payload.put("idempotencyKey", idempotencyKey);
        payload.put("bytes", block.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        return event;
    }

    private static ObjectNode baseEvent(String type, long sourceSequence) {
        ObjectNode event = OBJECT_MAPPER.createObjectNode();
        event.put("protocol", "rd-agent-event/v1");
        event.put("eventType", type);
        event.put("sourceSequence", sourceSequence);
        event.put("taskId", "task-1");
        event.put("stageRunId", "stage-1");
        event.put("role", "CODING_AGENT");
        event.put("runtimeType", "PI");
        event.put("snapshotId", "snapshot-1");
        event.put("occurredAt", Instant.ofEpochMilli(1_700_000_000_000L).toString());
        return event;
    }
}
