package com.wish.rd.engine.requirement.query;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageResultQueryEngineTest {

    private final StageResultQueryEngine engine = new StageResultQueryEngine();

    @Test
    void unpacksNestedQaAndDoesNotReturnCodingRoot() {
        String aggregate = """
                {"status":"NEEDS_HUMAN","stages":[{"role":"QA_AGENT","resultJson":"{\\"acceptanceResults\\":[],\\"status\\":\\"FAILED\\"}"}]}
                """;
        StageResultSnapshot snapshot = new StageResultSnapshot(
                AgentStageRun.pending("qa-1", "task-1", AgentRole.QA_AGENT, 2, "task-1:QA:2", 1L),
                "cmd-qa",
                "cmd-qa:1",
                aggregate,
                "art-qa",
                "",
                false
        );

        StageResultView view = engine.query(snapshot);

        assertEquals(StageResultView.SOURCE_FINALIZATION, view.source());
        assertTrue(view.available());
        assertTrue(view.content().contains("acceptanceResults"));
        assertFalse(view.content().contains("NEEDS_HUMAN"));
        assertEquals("/admin/rd-tasks/task-1/stage-runs/qa-1/result/content", view.downloadPath());
    }

    @Test
    void previewOnlyIsNotAFullDownload() {
        StageResultSnapshot snapshot = new StageResultSnapshot(
                AgentStageRun.pending("coding-1", "task-1", AgentRole.CODING_AGENT, 1, "task-1:C:1", 1L),
                "",
                "",
                "",
                "art-old",
                "{\"preview\":true}",
                true
        );

        StageResultView view = engine.query(snapshot);

        assertEquals(StageResultView.SOURCE_PREVIEW, view.source());
        assertFalse(view.available());
        assertEquals(StageResultView.FULL_RESULT_MISSING, view.unavailableReason());
        assertNull(view.downloadPath());
        assertTrue(view.truncated());
    }

    @Test
    void truncatesLongFinalizationForDefaultView() {
        String body = "{\"marker\":\"" + "x".repeat(21_000) + "\"}";
        StageResultSnapshot snapshot = new StageResultSnapshot(
                AgentStageRun.pending("coding-1", "task-1", AgentRole.CODING_AGENT, 1, "task-1:C:1", 1L),
                "cmd-c",
                "cmd-c:1",
                body,
                "art-c",
                "",
                false
        );

        StageResultView preview = engine.query(snapshot, false);
        StageResultView full = engine.query(snapshot, true);

        assertTrue(preview.truncated());
        assertEquals(StageResultView.PREVIEW_CHARS, preview.content().length());
        assertFalse(full.truncated());
        assertEquals(body, full.content());
    }
}
