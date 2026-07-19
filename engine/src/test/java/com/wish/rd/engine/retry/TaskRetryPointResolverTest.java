package com.wish.rd.engine.retry;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.requirement.review.model.AiReviewDecision;
import com.wish.rd.engine.requirement.review.model.AiReviewResult;
import com.wish.rd.engine.requirement.review.model.AiReviewRun;
import com.wish.rd.engine.requirement.review.model.AiReviewRunStatus;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskRetryPointResolverTest {

    private final TaskRetryPointResolver resolver = new TaskRetryPointResolver();

    @Test
    void resolvesEarliestFailedLatestRoleAttempt() {
        AgentStageRun reviewer = stage("reviewer-1", AgentRole.REQUIREMENT_REVIEWER, 1,
                AgentStageStatus.SUCCEEDED, 100L);
        AgentStageRun architect = stage("architect-1", AgentRole.SOLUTION_ARCHITECT, 1,
                AgentStageStatus.SUCCEEDED, 110L);
        AgentStageRun coding = stage("coding-1", AgentRole.CODING_AGENT, 1,
                AgentStageStatus.FAILED_NEEDS_HUMAN, 120L);
        AgentStageRun qa = stage("qa-1", AgentRole.QA_AGENT, 1, AgentStageStatus.PENDING, 90L);

        var point = resolver.resolve(task(RdTaskStatus.FAILED_NEEDS_HUMAN, "{}"),
                List.of(reviewer, architect, coding, qa), List.of(), List.of());

        assertEquals(TaskFailurePhase.AGENT_ROLE, point.failurePhase());
        assertEquals(AgentRole.CODING_AGENT, point.retryFromRole());
        assertEquals("coding-1", point.failedStageRunId());
    }

    @Test
    void resolvesAiGateRecommendationWhenItIsLatestFailure() {
        AiReviewRun review = AiReviewRun.created("ai-1", "task-1", 1, "", "model-a", 100L)
                .withPackageHash("sha256:package", 105L)
                .withStatus(AiReviewRunStatus.PACKAGING, "", "", 106L)
                .withStatus(AiReviewRunStatus.REVIEWING, "", "", 107L)
                .withStatus(AiReviewRunStatus.VALIDATING, "", "", 108L)
                .withResult(new AiReviewResult(AiReviewDecision.NOT_OK, 40, "code incomplete",
                                AgentRole.CODING_AGENT, List.of(), List.of(), "{}"),
                        AiReviewRunStatus.SUCCEEDED_NOT_OK, 200L);

        var point = resolver.resolve(task(RdTaskStatus.FAILED_NEEDS_HUMAN, "{}"),
                List.of(stage("coding-1", AgentRole.CODING_AGENT, 1, AgentStageStatus.SUCCEEDED, 150L)),
                List.of(), List.of(review));

        assertEquals(TaskFailurePhase.AI_REVIEW, point.failurePhase());
        assertEquals(AgentRole.CODING_AGENT, point.retryFromRole());
        assertEquals("ai-1", point.failedAiReviewRunId());
    }

    @Test
    void resolvesPrPublicationFromStructuredResult() {
        var point = resolver.resolve(task(RdTaskStatus.REJECTED, """
                {"pullRequestPublication":{"success":false,"errorMessage":"GitHub unavailable"}}
                """), List.of(), List.of(), List.of());

        assertEquals(TaskFailurePhase.PR_PUBLICATION, point.failurePhase());
    }

    @Test
    void resolvesEarlyPipelineFailureFromStructuredPhaseInsteadOfRestartingBlindly() {
        for (TaskFailurePhase phase : List.of(
                TaskFailurePhase.MATERIAL, TaskFailurePhase.CONTEXT,
                TaskFailurePhase.PLAN, TaskFailurePhase.POLICY,
                TaskFailurePhase.DETERMINISTIC_REVIEW, TaskFailurePhase.PR_PUBLICATION)) {
            var point = resolver.resolve(task(RdTaskStatus.FAILED_RETRYABLE,
                    "{\"failurePhase\":\"" + phase.name() + "\",\"errorMessage\":\"boom\"}"),
                    List.of(), List.of(), List.of());
            assertEquals(phase, point.failurePhase());
        }
    }

    @Test
    void rejectsCompletedTaskAndAmbiguousFailure() {
        assertThrows(IllegalStateException.class, () -> resolver.resolve(
                task(RdTaskStatus.COMPLETED, "{}"), List.of(), List.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> resolver.resolve(
                task(RdTaskStatus.REJECTED, "{}"), List.of(), List.of(), List.of()));
    }

    private AgentStageRun stage(
            String id,
            AgentRole role,
            int attempt,
            AgentStageStatus status,
            long updatedAt
    ) {
        return AgentStageRun.pending(id, "task-1", role, attempt,
                        "task-1:" + role + ":" + attempt, 10L)
                .withStatus(status, status.name().startsWith("FAILED") ? "TEST" : "",
                        status.name().startsWith("FAILED") ? "failed" : "", updatedAt);
    }

    private RdRequirementTask task(RdTaskStatus status, String resultJson) {
        return new RdRequirementTask(
                "task-1", "REQUIREMENT", "ADMIN", "source", "", "P1", status,
                "订单状态筛选", "project-1", "waimai", "外卖项目", "https://github.example/waimai",
                "owner", "repo", "main", "feature/status", "支持状态筛选", "[\"筛选正确\"]",
                "prompt", resultJson, "", "failed", 10L, 300L, false);
    }
}
