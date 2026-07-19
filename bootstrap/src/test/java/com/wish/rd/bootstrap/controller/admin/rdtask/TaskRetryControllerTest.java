package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retry.TaskRetryCheckpointStore;
import com.wish.rd.engine.retry.TaskRetryEngine;
import com.wish.rd.engine.retry.TaskFailureRecoveryService;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskFailureDiagnostic;
import com.wish.rd.engine.retry.model.TaskFailureRecoverySnapshot;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.retry.model.TaskRetryCommand;
import com.wish.rd.engine.retry.model.TaskRetryPoint;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskRetryControllerTest {

    @Test
    void exposesPreviewHistoryAndRetryAction() throws Exception {
        TaskRetryEngine engine = mock(TaskRetryEngine.class);
        TaskRetryCheckpointStore store = mock(TaskRetryCheckpointStore.class);
        TaskFailureRecoveryService recoveryService = mock(TaskFailureRecoveryService.class);
        TaskRetryPoint point = new TaskRetryPoint("task-1", TaskFailurePhase.AGENT_ROLE,
                AgentRole.CODING_AGENT, "stage-3", "", "", "coding failed", 18L);
        TaskRetryCheckpoint checkpoint = checkpoint(point, "账号使用 user1", List.of("material-1"));
        when(engine.preview("task-1")).thenReturn(point);
        when(engine.retry("task-1", "USER")).thenReturn(checkpoint);
        when(store.listByTask("task-1")).thenReturn(List.of(checkpoint));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TaskRetryController(engine, store, recoveryService)).build();

        mvc.perform(get("/admin/rd-tasks/task-1/retry-preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failurePhase").value("AGENT_ROLE"))
                .andExpect(jsonPath("$.retryFromRole").value("CODING_AGENT"))
                .andExpect(jsonPath("$.reason").value("coding failed"));
        mvc.perform(post("/admin/rd-tasks/task-1/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpointId").value("checkpoint-1"))
                .andExpect(jsonPath("$.status").value("DISPATCHED"));
        mvc.perform(post("/admin/rd-tasks/task-1/retry")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/rd-tasks/task-1/retry-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attemptNo").value(1))
                .andExpect(jsonPath("$[0].sourceTaskVersion").value(18));
    }

    @Test
    void exposesStructuredFailureRecoveryAndBindsRetryCommandEvidence() throws Exception {
        TaskRetryEngine engine = mock(TaskRetryEngine.class);
        TaskRetryCheckpointStore store = mock(TaskRetryCheckpointStore.class);
        TaskFailureRecoveryService recoveryService = mock(TaskFailureRecoveryService.class);
        TaskRetryPoint point = new TaskRetryPoint("task-1", TaskFailurePhase.AGENT_ROLE,
                AgentRole.REQUIREMENT_REVIEWER, "stage-review-1", "", "", "need account", 18L);
        TaskRetryCheckpoint checkpoint = checkpoint(point, "账号使用 user1", List.of("material-1"));
        TaskFailureDiagnostic diagnostic = new TaskFailureDiagnostic(
                "REQUIREMENT_REVIEW_NEEDS_HUMAN", "需求信息不足", "确认账号",
                "补充证据后重试", true,
                List.of(new com.wish.rd.engine.retry.model.TaskFailureIssue(
                        "MISSING_INFORMATION", "HIGH", "需要补充的信息", "确认顾客测试账号", "missingInformation")),
                List.of(), List.of()
        );
        TaskFailureRecoverySnapshot snapshot = new TaskFailureRecoverySnapshot(
                "task-1", RdTaskStatus.FAILED_NEEDS_HUMAN, point, "FAILED_NEEDS_HUMAN", 1,
                "long-cat", "REQUIREMENT_REVIEW_NEEDS_HUMAN", "need account", diagnostic,
                "result-1", "sha256:result", "{\"decision\":\"NEED_INFO\"}", List.of(checkpoint)
        );
        when(recoveryService.snapshot("task-1")).thenReturn(snapshot);
        when(engine.retry(org.mockito.ArgumentMatchers.eq("task-1"), org.mockito.ArgumentMatchers.eq("USER"), any()))
                .thenReturn(checkpoint);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TaskRetryController(engine, store, recoveryService)).build();

        mvc.perform(get("/admin/rd-tasks/task-1/failure-recovery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnostic.title").value("需求信息不足"))
                .andExpect(jsonPath("$.diagnostic.issues[0].detail").value("确认顾客测试账号"))
                .andExpect(jsonPath("$.rawResultArtifactId").value("result-1"));
        mvc.perform(post("/admin/rd-tasks/task-1/retry")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"expectedFailedStageRunId\":\"stage-review-1\","
                                + "\"expectedFailedRetrievalRunId\":\"retrieval-review-1\","
                                + "\"expectedFailedAiReviewRunId\":\"review-run-1\","
                                + "\"expectedSourceTaskVersion\":18,"
                                + "\"operatorNote\":\"账号使用 user1\","
                                + "\"evidenceMaterialIds\":[\"material-1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operatorNote").value("账号使用 user1"))
                .andExpect(jsonPath("$.evidenceMaterialIds[0]").value("material-1"));

        ArgumentCaptor<TaskRetryCommand> command = ArgumentCaptor.forClass(TaskRetryCommand.class);
        verify(engine).retry(eq("task-1"), eq("USER"), command.capture());
        assertEquals("stage-review-1", command.getValue().expectedFailedStageRunId());
        assertEquals("retrieval-review-1", command.getValue().expectedFailedRetrievalRunId());
        assertEquals("review-run-1", command.getValue().expectedFailedAiReviewRunId());
        assertEquals(18L, command.getValue().expectedSourceTaskVersion());
    }

    @Test
    void mapsUnknownOrIllegalRetryTo404And409() throws Exception {
        TaskRetryEngine engine = mock(TaskRetryEngine.class);
        TaskRetryCheckpointStore store = mock(TaskRetryCheckpointStore.class);
        TaskFailureRecoveryService recoveryService = mock(TaskFailureRecoveryService.class);
        when(engine.preview("missing")).thenThrow(new java.util.NoSuchElementException("task not found: missing"));
        when(engine.preview("running")).thenThrow(new IllegalStateException("task status is not retryable: EXECUTING"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TaskRetryController(engine, store, recoveryService)).build();

        mvc.perform(get("/admin/rd-tasks/missing/retry-preview"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/admin/rd-tasks/running/retry-preview"))
                .andExpect(status().isConflict());
        when(engine.preview("invalid")).thenThrow(new NumberFormatException("invalid task id"));
        mvc.perform(get("/admin/rd-tasks/invalid/retry-preview"))
                .andExpect(status().isBadRequest());
    }

    private static TaskRetryCheckpoint checkpoint(TaskRetryPoint point) {
        return checkpoint(point, "", List.of());
    }

    private static TaskRetryCheckpoint checkpoint(
            TaskRetryPoint point,
            String operatorNote,
            List<String> evidenceMaterialIds
    ) {
        return new TaskRetryCheckpoint("checkpoint-1", point.taskId(), point.failurePhase(), point.retryFromRole(),
                point.failedStageRunId(), "", "", 1, "task-1:18:AGENT_ROLE:CODING_AGENT",
                RdTaskStatus.FAILED_NEEDS_HUMAN, 18L, operatorNote, evidenceMaterialIds,
                TaskRetryCheckpointStatus.DISPATCHED,
                point.reason(), "", 100L, 120L);
    }
}
