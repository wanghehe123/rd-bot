package com.wish.rd.engine.requirement.verify;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.requirement.model.AgentWorkflowPlan;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostVerificationPortTest {

    @Test
    void unavailableRejectsVerifyWithClearMessage() {
        UnsupportedOperationException exception = assertThrows(
                UnsupportedOperationException.class,
                () -> HostVerificationPort.unavailable().verify(
                        RdRequirementTask.created(
                                "task-1",
                                new CreateRequirementTaskCommand(
                                        "title",
                                        "P2",
                                        "https://example.com/repo.git",
                                        "acme",
                                        "repo",
                                        "main",
                                        "ok",
                                        List.of(),
                                        false
                                ),
                                1L
                        ),
                        AgentStageRun.pending("stage-1", "task-1", AgentRole.CODING_AGENT, 1, "idem-1", 1L),
                        AgentWorkflowPlan.production(),
                        0
                )
        );
        assertTrue(exception.getMessage().toLowerCase().contains("unavailable"), exception.getMessage());
    }

    @Test
    void noopReturnsSucceededWithoutThrowing() {
        RdRequirementTask task = RdRequirementTask.created(
                "task-1",
                new CreateRequirementTaskCommand(
                        "title",
                        "P2",
                        "https://example.com/repo.git",
                        "acme",
                        "repo",
                        "main",
                        "ok",
                        List.of(),
                        false
                ),
                1L
        );
        AgentStageRun coding = AgentStageRun.pending(
                "stage-1", "task-1", AgentRole.CODING_AGENT, 1, "idem-1", 1L);
        var run = HostVerificationPort.noop().verify(task, coding, AgentWorkflowPlan.production(), 0);
        assertEquals(HostVerificationStatus.SUCCEEDED, run.status());
        assertEquals(coding.stageRunId(), run.codingStageRunId());
    }
}
