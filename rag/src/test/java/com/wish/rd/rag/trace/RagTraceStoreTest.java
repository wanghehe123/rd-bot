package com.wish.rd.rag.trace;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagTraceStoreTest {

    @Test
    void recordsRunAndOrderedNodesForQuery() {
        RagTraceStore store = new RagTraceStore();
        store.startRun(new RagTraceRunStart(
                "trace-1",
                "prompt-flow",
                "POST /test/rag/prompt-flow",
                "conversation-1",
                "ticket-1",
                "user-1",
                Map.of("question", "支付系统下单接口 500")
        ));
        store.recordNode(new RagTraceNodeRecord(
                "trace-1",
                "ingestion",
                null,
                0,
                "INGESTION",
                "document ingestion",
                "RagPromptFlowTestEngine",
                "run",
                "SUCCESS",
                null,
                12,
                Map.of("chunkCount", "1")
        ));
        store.recordNode(new RagTraceNodeRecord(
                "trace-1",
                "prompt",
                "ingestion",
                1,
                "PROMPT",
                "prompt plan",
                "RepairPromptService",
                "build",
                "SUCCESS",
                null,
                3,
                Map.of("scene", "REPAIR_MIXED")
        ));
        store.finishRun("trace-1", "SUCCESS", null, 20);

        RagTraceDetail detail = store.detail("trace-1");

        assertEquals("trace-1", detail.run().traceId());
        assertEquals("SUCCESS", detail.run().status());
        assertEquals("支付系统下单接口 500", detail.run().question());
        assertEquals(20, detail.run().durationMs());
        assertEquals(2, detail.nodes().size());
        assertEquals("ingestion", detail.nodes().get(0).nodeId());
        assertEquals("prompt", detail.nodes().get(1).nodeId());
        assertTrue(store.listRuns().stream().anyMatch(run -> run.traceId().equals("trace-1")));
    }
}
