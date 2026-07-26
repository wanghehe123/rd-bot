package com.wish.rd.exec.repair.runtime;

import com.wish.rd.exec.repair.runtime.model.AgentExecutionTraceSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutionEventParserTest {

    @Test
    void parsesOnlyTheExpectedStageAndBuildsAnArchiveSafeSnapshot() {
        String jsonl = """
                {"protocol":"rd-agent-event/v1","sourceSequence":1,"taskId":"task-1","stageRunId":"stage-1","eventType":"RUNTIME_READY"}
                {"protocol":"rd-agent-event/v1","sourceSequence":2,"taskId":"task-1","stageRunId":"stage-1","eventType":"AGENT_SETTLED"}
                {"protocol":"rd-agent-event/v1","sourceSequence":3,"taskId":"other","stageRunId":"stage-1","eventType":"PROTOCOL_ERROR"}
                """;

        AgentExecutionTraceSnapshot snapshot = AgentExecutionEventParser.parseJsonl(
                jsonl, "task-1", "stage-1", 1L, 10
        );

        assertEquals("ARCHIVED", snapshot.source());
        assertTrue(snapshot.available());
        assertTrue(snapshot.finalized());
        assertEquals(1, snapshot.events().size());
        assertEquals("AGENT_SETTLED", snapshot.events().getFirst().path("eventType").asText());
        assertEquals(2L, snapshot.nextSequence());
    }
}
