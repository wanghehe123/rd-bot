package com.wish.rd.bootstrap.controller.admin.review;

import com.wish.rd.engine.requirement.review.impl.InMemoryAiReviewRunStore;
import com.wish.rd.engine.requirement.review.model.AiReviewArtifact;
import com.wish.rd.engine.requirement.review.model.AiReviewRun;
import com.wish.rd.engine.requirement.review.model.AiReviewRunStatus;
import com.wish.rd.engine.requirement.review.AiDeliveryReviewEngine;
import com.wish.rd.engine.retry.TaskRetryEngine;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.retry.model.TaskRetryPoint;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class AiReviewControllerTest {

    @Test
    void startsAnOnDemandReviewForAnExistingRequirementTask() throws Exception {
        InMemoryAiReviewRunStore store = new InMemoryAiReviewRunStore();
        TaskRetryEngine retryEngine = mock(TaskRetryEngine.class);
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        AiDeliveryReviewEngine reviewEngine = mock(AiDeliveryReviewEngine.class);
        RdRequirementTask task = new RdRequirementTask(
                "task-manual", "REQUIREMENT", "ADMIN", "source", "", "P1", RdTaskStatus.VALIDATING,
                "订单筛选", "project", "waimai", "外卖", "https://github.com/acme/waimai",
                "acme", "waimai", "main", "feature", "筛选正确", "[]", "prompt",
                "{\"deliveryReview\":{\"approved\":true}}", "", "", 10L, 20L, false);
        AiReviewRun completed = AiReviewRun.created("run-manual", task.taskId(), 1, "", "model-a", 100L)
                .withStatus(AiReviewRunStatus.PACKAGING, "", "", 101L)
                .withStatus(AiReviewRunStatus.REVIEWING, "", "", 102L)
                .withStatus(AiReviewRunStatus.VALIDATING, "", "", 103L);
        when(registry.getTask(task.taskId())).thenReturn(task);
        when(reviewEngine.isEnabled()).thenReturn(true);
        when(reviewEngine.review(task, "{\"approved\":true}", "USER")).thenReturn(completed);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AiReviewController(store, () -> "unused", retryEngine, registry, reviewEngine)).build();

        mvc.perform(post("/admin/rd-tasks/task-manual/ai-reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-manual"));

        verify(reviewEngine).review(task, "{\"approved\":true}", "USER");
    }

    @Test
    void exposesRunsTimelineAndRedactedContentArtifacts() throws Exception {
        InMemoryAiReviewRunStore store = new InMemoryAiReviewRunStore();
        AiReviewRun run = store.create(AiReviewRun.created("run-1", "task-1", 1, "", "MiniMax-M3", 100L));
        store.transition(run.runId(), AiReviewRunStatus.CREATED, AiReviewRunStatus.PACKAGING,
                "AUTO", "package all evidence", "", "", 110L);
        store.appendArtifact(new AiReviewArtifact("artifact-1", run.runId(), "INPUT_MANIFEST",
                "ai-review://input/manifest", "RAG plan and all role results", "sha256:1", "{}", true, 115L));
        AtomicInteger ids = new AtomicInteger();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AiReviewController(store, () -> "run-" + (ids.incrementAndGet() + 1))).build();

        mvc.perform(get("/admin/rd-tasks/task-1/ai-reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].modelName").value("MiniMax-M3"));
        mvc.perform(get("/admin/ai-reviews/run-1/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].toStatus").value("PACKAGING"));
        mvc.perform(get("/admin/ai-reviews/run-1/artifacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].contentPreview").value("RAG plan and all role results"))
                .andExpect(jsonPath("$[0].redacted").value(true));
    }

    @Test
    void retriesOnlyTerminalRunsAndCancelsActiveRuns() throws Exception {
        InMemoryAiReviewRunStore store = new InMemoryAiReviewRunStore();
        AiReviewRun failed = store.create(AiReviewRun.created("run-1", "task-1", 1, "", "model-a", 100L));
        store.transition(failed.runId(), AiReviewRunStatus.CREATED, AiReviewRunStatus.PACKAGING,
                "AUTO", "package", "", "", 110L);
        store.transition(failed.runId(), AiReviewRunStatus.PACKAGING, AiReviewRunStatus.FAILED_RETRYABLE,
                "AUTO", "provider failed", "HTTP", "failed", 120L);
        AiReviewRun active = store.create(AiReviewRun.created("run-active", "task-2", 1, "", "model-a", 100L));
        TaskRetryEngine retryEngine = mock(TaskRetryEngine.class);
        TaskRetryPoint point = new TaskRetryPoint("task-1", TaskFailurePhase.AI_REVIEW,
                null, "", "", "run-1", "provider failed", 20L);
        TaskRetryCheckpoint checkpoint = new TaskRetryCheckpoint(
                "checkpoint-1", "task-1", TaskFailurePhase.AI_REVIEW, null,
                "", "", "run-1", 2, "task-1:20:AI_REVIEW:",
                RdTaskStatus.FAILED_RETRYABLE, 20L, "", List.of(), TaskRetryCheckpointStatus.DISPATCHED,
                "provider failed", "", 130L, 140L);
        when(retryEngine.retry("task-1", "USER")).thenReturn(checkpoint);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AiReviewController(store, () -> "unused", retryEngine)).build();

        mvc.perform(post("/admin/ai-reviews/run-1/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpointId").value("checkpoint-1"))
                .andExpect(jsonPath("$.status").value("DISPATCHED"))
                .andExpect(jsonPath("$.failedAiReviewRunId").value("run-1"));
        mvc.perform(post("/admin/ai-reviews/run-active/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/admin/ai-reviews/missing"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/admin/ai-reviews/run-1/cancel"))
                .andExpect(status().isConflict());
    }

    @Test
    void mapsMalformedRunIdsTo400InsteadOfLeakingA500() throws Exception {
        com.wish.rd.engine.requirement.review.AiReviewRunStore store =
                mock(com.wish.rd.engine.requirement.review.AiReviewRunStore.class);
        when(store.find("not-a-number")).thenThrow(new NumberFormatException("invalid run id"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AiReviewController(store, () -> "unused")).build();

        mvc.perform(get("/admin/ai-reviews/not-a-number"))
                .andExpect(status().isBadRequest());
    }
}
