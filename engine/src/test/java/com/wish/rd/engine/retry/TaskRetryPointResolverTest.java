package com.wish.rd.engine.retry;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.requirement.review.model.AiReviewDecision;
import com.wish.rd.engine.requirement.review.model.AiReviewResult;
import com.wish.rd.engine.requirement.review.model.AiReviewRun;
import com.wish.rd.engine.requirement.review.model.AiReviewRunStatus;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryFailureProvenance;
import com.wish.rd.rag.retrieval.run.model.EvidenceQualityDecision;
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import com.wish.rd.rag.retrieval.run.model.RetrievalRun;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunStatus;
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
    void resolvesTheExactDurableProvenanceInsteadOfRankingStageFailureTimes() {
        AgentStageRun staleButNewer = stage("coding-later", AgentRole.CODING_AGENT, 3,
                AgentStageStatus.FAILED_RETRYABLE, 9_999L);
        TaskRetryFailureProvenance provenance = provenance(
                "provenance-1", TaskFailurePhase.AGENT_ROLE, "command-17",
                "ROLE_EXECUTION:CODING_AGENT", RdTaskStatus.FAILED_NEEDS_HUMAN, 41L, 71L);

        var point = resolver.resolve(task(RdTaskStatus.FAILED_NEEDS_HUMAN, "{}", 41L, 71L), provenance,
                List.of(staleButNewer), List.of(), List.of());

        assertEquals("command-17", point.failedStageCommandId());
        assertEquals("ROLE_EXECUTION:CODING_AGENT", point.failedStage());
        assertEquals(41L, point.sourceTaskVersion());
        assertEquals(71L, point.sourceFencingToken());
    }

    @Test
    void rejectsCoarseOrConflictingProvenanceInsteadOfFallingBackToTaskUpdateTime() {
        TaskRetryFailureProvenance coarse = provenance(
                "provenance-1", TaskFailurePhase.AGENT_ROLE, "", "", RdTaskStatus.FAILED_NEEDS_HUMAN,
                41L, 71L);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(task(RdTaskStatus.FAILED_NEEDS_HUMAN, "{}", 41L, 71L), coarse,
                        List.of(), List.of(), List.of()));

        assertEquals("RETRY_POINT_AMBIGUOUS: task-1", failure.getMessage());
    }

    @Test
    void rejectsAuthoritativeProvenanceWithoutAPositiveFence() {
        TaskRetryFailureProvenance provenance = provenance(
                "provenance-1", TaskFailurePhase.AGENT_ROLE, "command-17",
                "ROLE_EXECUTION:CODING_AGENT", RdTaskStatus.FAILED_NEEDS_HUMAN, 41L, 0L);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(task(RdTaskStatus.FAILED_NEEDS_HUMAN, "{}", 41L, 0L), provenance,
                        List.of(stage("coding-later", AgentRole.CODING_AGENT, 3,
                                AgentStageStatus.FAILED_RETRYABLE, 9_999L)), List.of(), List.of()));

        assertEquals("RETRY_POINT_AMBIGUOUS: task-1", failure.getMessage());
    }

    @Test
    void rejectsExactAttemptThatIsNotInARetryableOrInterruptedState() {
        TaskRetryFailureProvenance provenance = provenance(
                "provenance-1", TaskFailurePhase.AGENT_ROLE, "command-17",
                "ROLE_EXECUTION:CODING_AGENT", RdTaskStatus.FAILED_NEEDS_HUMAN, 41L, 71L);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(task(RdTaskStatus.FAILED_NEEDS_HUMAN, "{}", 41L, 71L), provenance,
                        List.of(stage("coding-later", AgentRole.CODING_AGENT, 3,
                                AgentStageStatus.SUCCEEDED, 9_999L)), List.of(), List.of()));

        assertEquals("RETRY_POINT_AMBIGUOUS: task-1", failure.getMessage());
    }

    @Test
    void rejectsAgentRoleProvenanceWhoseCommandStageNamesAnotherRole() {
        TaskRetryFailureProvenance provenance = provenance(
                "provenance-1", TaskFailurePhase.AGENT_ROLE, "command-17",
                "ROLE_EXECUTION:QA_AGENT", RdTaskStatus.FAILED_NEEDS_HUMAN, 41L, 71L);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(task(RdTaskStatus.FAILED_NEEDS_HUMAN, "{}", 41L, 71L), provenance,
                        List.of(stage("coding-later", AgentRole.CODING_AGENT, 3,
                                AgentStageStatus.FAILED_RETRYABLE, 9_999L)), List.of(), List.of()));

        assertEquals("RETRY_POINT_AMBIGUOUS: task-1", failure.getMessage());
    }

    @Test
    void rejectsRagProvenanceWhoseCommandStageNamesAnotherRole() {
        TaskRetryFailureProvenance provenance = new TaskRetryFailureProvenance(
                "provenance-1", "task-1", "command-17", 2,
                "ROLE_EXECUTION:QA_AGENT", TaskFailurePhase.RAG,
                RdTaskStatus.FAILED_NEEDS_HUMAN, 41L, 71L,
                "", "retrieval-1", "", "policy-1", "sha256:" + "a".repeat(64), "",
                "TECHNICAL", 400L);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(task(RdTaskStatus.FAILED_NEEDS_HUMAN, "{}", 41L, 71L), provenance,
                        List.of(), List.of(retrieval("retrieval-1", AgentRole.CODING_AGENT)), List.of()));

        assertEquals("RETRY_POINT_AMBIGUOUS: task-1", failure.getMessage());
    }

    @Test
    void rejectsPhaseIncompatibleOptionalLedgerIdentities() {
        TaskRetryFailureProvenance earlyWithPolicy = provenance(
                "provenance-material", TaskFailurePhase.MATERIAL, "command-material",
                "MATERIAL_COLLECTING", RdTaskStatus.FAILED_RETRYABLE, 41L, 71L);
        TaskRetryFailureProvenance roleWithPublication = new TaskRetryFailureProvenance(
                "provenance-role", "task-1", "command-role", 2,
                "ROLE_EXECUTION:CODING_AGENT", TaskFailurePhase.AGENT_ROLE,
                RdTaskStatus.FAILED_NEEDS_HUMAN, 41L, 71L,
                "coding-later", "", "", "policy-1", "sha256:" + "a".repeat(64),
                "publication-1", "BUSINESS", 400L);

        assertThrows(IllegalStateException.class,
                () -> resolver.resolve(task(RdTaskStatus.FAILED_RETRYABLE, "{}", 41L, 71L), earlyWithPolicy,
                        List.of(), List.of(), List.of()));
        assertThrows(IllegalStateException.class,
                () -> resolver.resolve(task(RdTaskStatus.FAILED_NEEDS_HUMAN, "{}", 41L, 71L), roleWithPublication,
                        List.of(stage("coding-later", AgentRole.CODING_AGENT, 3,
                                AgentStageStatus.FAILED_RETRYABLE, 9_999L)), List.of(), List.of()));
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

    private RetrievalRun retrieval(String id, AgentRole role) {
        return new RetrievalRun(
                id, "task-1", RetrievalConsumerType.AGENT_ROLE, role.name(), "coding-later", 1, "",
                "task-1:" + role + ":1", RetrievalRunStatus.FAILED_RETRYABLE,
                List.of("kb-1"), "sha256:query", "redacted query", 0, 3, 18_000, 0, 0,
                EvidenceQualityDecision.NEED_INPUT, "", "PROVIDER", "timeout", "", 0L,
                0L, 100L, 100L);
    }

    private RdRequirementTask task(RdTaskStatus status, String resultJson) {
        return task(status, resultJson, 0L, 0L);
    }

    private RdRequirementTask task(RdTaskStatus status, String resultJson, long version, long fence) {
        return new RdRequirementTask(
                "task-1", "REQUIREMENT", "ADMIN", "source", "", "P1", status,
                "订单状态筛选", "project-1", "waimai", "外卖项目", "https://github.example/waimai",
                "owner", "repo", "main", "feature/status", "支持状态筛选", "[\"筛选正确\"]",
                "prompt", resultJson, "", "failed", 10L, 300L, false, 0L, version, fence);
    }

    private TaskRetryFailureProvenance provenance(
            String id,
            TaskFailurePhase phase,
            String commandId,
            String stage,
            RdTaskStatus outcomeStatus,
            long version,
            long fence
    ) {
        return new TaskRetryFailureProvenance(id, "task-1", commandId, 2, stage, phase, outcomeStatus,
                version, fence, phase == TaskFailurePhase.AGENT_ROLE ? "coding-later" : "", "", "",
                "policy-1", "sha256:" + "a".repeat(64), "", "BUSINESS", 400L);
    }
}
