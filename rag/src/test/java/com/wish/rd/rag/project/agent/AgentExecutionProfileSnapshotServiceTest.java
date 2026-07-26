package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentExecutionProfileSnapshotServiceTest {

    @Test
    void reusesTheExistingSnapshotForTheSameStage() {
        InMemoryAgentExecutionProfileSnapshotStore store = new InMemoryAgentExecutionProfileSnapshotStore();
        AgentExecutionProfileSnapshotService service = new AgentExecutionProfileSnapshotService(store);
        String json = "{\"runtimeType\":\"PI\",\"model\":\"m1\"}";
        AgentExecutionProfileSnapshot snapshot = snapshot("snapshot-1", json);

        AgentExecutionProfileSnapshot first = service.resolveOrSave(snapshot);
        AgentExecutionProfileSnapshot second = service.resolveOrSave(
                snapshot("snapshot-2", json)
        );

        assertEquals(first, second);
    }

    @Test
    void rejectsAChangedSnapshotForAnAlreadyResolvedStage() {
        InMemoryAgentExecutionProfileSnapshotStore store = new InMemoryAgentExecutionProfileSnapshotStore();
        AgentExecutionProfileSnapshotService service = new AgentExecutionProfileSnapshotService(store);
        service.resolveOrSave(snapshot("snapshot-1", "{\"runtimeType\":\"PI\"}"));

        assertThrows(
                IllegalStateException.class,
                () -> service.resolveOrSave(snapshot("snapshot-2", "{\"runtimeType\":\"CLAUDE_CODE\"}"))
        );
    }

    @Test
    void rejectsSnapshotWhenItsIntegrityHashDoesNotMatch() {
        AgentExecutionProfileSnapshot invalid = new AgentExecutionProfileSnapshot(
                "snapshot-1",
                "stage-1",
                "task-1",
                "CODING_AGENT",
                1,
                AgentRuntimeType.PI,
                "{\"runtimeType\":\"PI\"}",
                "0000000000000000000000000000000000000000000000000000000000000000",
                1L
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentExecutionProfileSnapshotService(
                        new InMemoryAgentExecutionProfileSnapshotStore()
                ).resolveOrSave(invalid)
        );
    }

    @Test
    void shouldReadAnImmutableSnapshotBySnapshotId() {
        InMemoryAgentExecutionProfileSnapshotStore store = new InMemoryAgentExecutionProfileSnapshotStore();
        AgentExecutionProfileSnapshotService service = new AgentExecutionProfileSnapshotService(store);
        AgentExecutionProfileSnapshot snapshot = snapshot("snapshot-1", "{}");

        service.resolveOrSave(snapshot);

        assertEquals(snapshot, store.findBySnapshotId("snapshot-1").orElseThrow());
    }

    private static AgentExecutionProfileSnapshot snapshot(String id, String json) {
        return new AgentExecutionProfileSnapshot(
                id,
                "stage-1",
                "task-1",
                "CODING_AGENT",
                1,
                AgentRuntimeType.PI,
                json,
                AgentExecutionProfileSnapshot.sha256(json),
                1L
        );
    }
}
