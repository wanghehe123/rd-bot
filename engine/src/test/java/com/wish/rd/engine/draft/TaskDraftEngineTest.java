package com.wish.rd.engine.draft;

import com.wish.rd.engine.draft.model.TaskDraftRequest;
import com.wish.rd.engine.draft.model.TaskDraftResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskDraftEngineTest {

    @Test
    void shouldValidateAndReturnStructuredBugDraft() {
        TaskDraftEngine engine = new TaskDraftEngine(request -> TaskDraftModelPort.ModelResponse.available("""
                {"actualBehavior":"注册返回 HTML","expectedBehavior":"注册返回 JSON",
                 "reproductionSteps":"1. 提交注册","affectedScope":"注册流程",
                 "acceptanceCriteria":["HTTP 200"],"missingFields":[],
                 "evidence":["用户摘要"],"confidence":0.82}
                """));

        TaskDraftResult result = engine.complete(new TaskDraftRequest(
                "BUG_FIX", "project-1", Map.of("summary", "Unexpected token"), List.of("broken.png image/png")));

        assertTrue(result.available());
        assertTrue(result.aiGenerated());
        assertEquals("注册返回 HTML", result.actualBehavior());
        assertEquals(List.of("HTTP 200"), result.acceptanceCriteria());
    }

    @Test
    void shouldRejectInvalidJsonWithoutInventingContent() {
        TaskDraftEngine engine = new TaskDraftEngine(request -> TaskDraftModelPort.ModelResponse.available("not-json"));
        TaskDraftResult result = engine.complete(new TaskDraftRequest("BUG_FIX", "", Map.of(), List.of()));
        assertFalse(result.available());
        assertFalse(result.aiGenerated());
        assertTrue(result.reason().contains("invalid"));
        assertEquals("", result.actualBehavior());
    }

    @Test
    void shouldRejectJsonObjectMissingRequiredStructure() {
        TaskDraftEngine engine = new TaskDraftEngine(request -> TaskDraftModelPort.ModelResponse.available("{}"));
        TaskDraftResult result = engine.complete(new TaskDraftRequest("BUG_FIX", "", Map.of(), List.of()));
        assertFalse(result.available());
        assertFalse(result.aiGenerated());
        assertTrue(result.reason().contains("invalid"));
    }

    @Test
    void shouldRejectConfidenceOutsideProtocolRange() {
        TaskDraftEngine engine = new TaskDraftEngine(request -> TaskDraftModelPort.ModelResponse.available("""
                {"actualBehavior":"注册返回 HTML","expectedBehavior":"注册返回 JSON",
                 "reproductionSteps":"1. 提交注册","affectedScope":"注册流程",
                 "acceptanceCriteria":["HTTP 200"],"missingFields":[],
                 "evidence":[],"confidence":2}
                """));

        TaskDraftResult result = engine.complete(new TaskDraftRequest("BUG_FIX", "", Map.of(), List.of()));

        assertFalse(result.available());
        assertEquals(0, result.confidence());
    }

    @Test
    void shouldRejectTrailingTokensAfterJsonObject() {
        TaskDraftEngine engine = new TaskDraftEngine(request -> TaskDraftModelPort.ModelResponse.available("""
                {"actualBehavior":"注册返回 HTML","expectedBehavior":"注册返回 JSON",
                 "reproductionSteps":"1. 提交注册","affectedScope":"注册流程",
                 "acceptanceCriteria":["HTTP 200"],"missingFields":[],
                 "evidence":[],"confidence":0.8} trailing
                """));

        TaskDraftResult result = engine.complete(new TaskDraftRequest("BUG_FIX", "", Map.of(), List.of()));

        assertFalse(result.available());
    }

    @Test
    void shouldExposeUnavailableModelWithoutFakeDraft() {
        TaskDraftEngine engine = new TaskDraftEngine(TaskDraftModelPort.unavailable("model credential is not configured"));
        TaskDraftResult result = engine.complete(new TaskDraftRequest("REQUIREMENT", "", Map.of(), List.of()));
        assertFalse(result.available());
        assertEquals("model credential is not configured", result.reason());
    }
}
