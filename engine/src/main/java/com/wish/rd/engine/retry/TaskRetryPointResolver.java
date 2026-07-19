package com.wish.rd.engine.retry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.requirement.review.model.AiReviewRun;
import com.wish.rd.engine.requirement.review.model.AiReviewRunStatus;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryPoint;
import com.wish.rd.rag.retrieval.run.model.RetrievalRun;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunStatus;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Resolves the latest structured failure checkpoint without parsing free-form error messages. */
public final class TaskRetryPointResolver {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Map<AgentRole, Integer> ROLE_ORDER = roleOrder();

    /**
     * Resolves the unique retry point for a failed requirement task.
     *
     * @param task failed requirement task
     * @param stageRuns all role attempts for the task
     * @param retrievalRuns all RetrievalRun attempts for the task
     * @param aiReviewRuns all AI review attempts for the task
     * @return structured retry point
     * @throws IllegalStateException when the task is not retryable or the point is ambiguous
     */
    public TaskRetryPoint resolve(
            RdRequirementTask task,
            List<AgentStageRun> stageRuns,
            List<RetrievalRun> retrievalRuns,
            List<AiReviewRun> aiReviewRuns
    ) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        requireRetryableStatus(task.status());
        List<AgentStageRun> safeStages = stageRuns == null ? List.of() : stageRuns;
        List<RetrievalRun> safeRetrievals = retrievalRuns == null ? List.of() : retrievalRuns;
        List<AiReviewRun> safeReviews = aiReviewRuns == null ? List.of() : aiReviewRuns;

        AgentStageRun failedStage = earliestLatestFailedStage(safeStages);
        AiReviewRun failedReview = latestFailedReview(safeReviews);
        RetrievalRun failedRetrieval = latestFailedRetrieval(safeRetrievals);

        long stageTime = failedStage == null ? -1L : failedStage.updateTimeEpochMillis();
        long reviewTime = failedReview == null ? -1L : failedReview.updatedAtEpochMillis();
        long retrievalTime = failedRetrieval == null ? -1L : failedRetrieval.updatedAtEpochMillis();

        if (failedReview != null && reviewTime >= stageTime && reviewTime >= retrievalTime) {
            return new TaskRetryPoint(task.taskId(), TaskFailurePhase.AI_REVIEW,
                    parseRole(failedReview.retryFromRole()), "", "", failedReview.runId(),
                    firstNonBlank(failedReview.summary(), failedReview.errorMessage()), task.updateTimeEpochMillis());
        }
        if (failedStage != null && stageTime >= retrievalTime) {
            return new TaskRetryPoint(task.taskId(), TaskFailurePhase.AGENT_ROLE, failedStage.role(),
                    failedStage.stageRunId(), "", "",
                    firstNonBlank(failedStage.errorMessage(), failedStage.errorCategory()), task.updateTimeEpochMillis());
        }
        if (failedRetrieval != null) {
            return new TaskRetryPoint(task.taskId(), TaskFailurePhase.RAG,
                    parseRole(failedRetrieval.role()), "", failedRetrieval.runId(), "",
                    firstNonBlank(failedRetrieval.errorMessage(), failedRetrieval.stopReason()), task.updateTimeEpochMillis());
        }

        TaskFailurePhase structuredPhase = structuredTaskFailure(task.executionResultJson());
        if (structuredPhase != null) {
            return new TaskRetryPoint(task.taskId(), structuredPhase, null,
                    "", "", "", task.errorMessage(), task.updateTimeEpochMillis());
        }
        throw new IllegalStateException("RETRY_POINT_AMBIGUOUS: " + task.taskId());
    }

    private static AgentStageRun earliestLatestFailedStage(List<AgentStageRun> stageRuns) {
        Map<AgentRole, AgentStageRun> latest = new EnumMap<>(AgentRole.class);
        for (AgentStageRun stage : stageRuns) {
            if (stage == null) {
                continue;
            }
            latest.merge(stage.role(), stage, (left, right) -> stageRecency(left, right) >= 0 ? left : right);
        }
        return latest.values().stream()
                .filter(TaskRetryPointResolver::isFailedStage)
                .min(Comparator.comparingInt(stage -> ROLE_ORDER.getOrDefault(stage.role(), Integer.MAX_VALUE)))
                .orElse(null);
    }

    private static int stageRecency(AgentStageRun left, AgentStageRun right) {
        return Comparator.comparingInt(AgentStageRun::attemptNo)
                .thenComparingLong(AgentStageRun::createTimeEpochMillis)
                .thenComparing(AgentStageRun::stageRunId)
                .compare(left, right);
    }

    private static boolean isFailedStage(AgentStageRun stage) {
        return stage.status() == AgentStageStatus.FAILED_RETRYABLE
                || stage.status() == AgentStageStatus.FAILED_NEEDS_HUMAN;
    }

    private static AiReviewRun latestFailedReview(List<AiReviewRun> runs) {
        return runs.stream().filter(java.util.Objects::nonNull)
                .filter(run -> run.status() == AiReviewRunStatus.SUCCEEDED_NOT_OK
                        || run.status() == AiReviewRunStatus.SUCCEEDED_NEEDS_HUMAN
                        || run.status() == AiReviewRunStatus.FAILED_RETRYABLE)
                .max(Comparator.comparingInt(AiReviewRun::attemptNo)
                        .thenComparingLong(AiReviewRun::updatedAtEpochMillis)
                        .thenComparing(AiReviewRun::runId))
                .orElse(null);
    }

    private static RetrievalRun latestFailedRetrieval(List<RetrievalRun> runs) {
        return runs.stream().filter(java.util.Objects::nonNull)
                .filter(run -> run.status() == RetrievalRunStatus.WAITING_INPUT
                        || run.status() == RetrievalRunStatus.FAILED_RETRYABLE
                        || run.status() == RetrievalRunStatus.FAILED_NEEDS_HUMAN
                        || run.status() == RetrievalRunStatus.DEAD_LETTERED)
                .max(Comparator.comparingInt(RetrievalRun::attemptNo)
                        .thenComparingLong(RetrievalRun::updatedAtEpochMillis)
                        .thenComparing(RetrievalRun::runId))
                .orElse(null);
    }

    private static TaskFailurePhase structuredTaskFailure(String resultJson) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(resultJson == null ? "{}" : resultJson);
            if (root == null || !root.isObject()) {
                return null;
            }
            String explicitPhase = root.path("failurePhase").asText("").strip();
            if (!explicitPhase.isBlank()) {
                try {
                    return TaskFailurePhase.valueOf(explicitPhase);
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
            JsonNode publication = root.path("pullRequestPublication");
            if (publication.isObject() && publication.has("success") && !publication.path("success").asBoolean(true)) {
                return TaskFailurePhase.PR_PUBLICATION;
            }
            JsonNode review = root.path("deliveryReview");
            if (review.isObject() && review.has("approved") && !review.path("approved").asBoolean(true)) {
                return TaskFailurePhase.DETERMINISTIC_REVIEW;
            }
            if (root.has("approved") && !root.path("approved").asBoolean(true)) {
                return TaskFailurePhase.DETERMINISTIC_REVIEW;
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void requireRetryableStatus(RdTaskStatus status) {
        if (status != RdTaskStatus.REJECTED
                && status != RdTaskStatus.FAILED_RETRYABLE
                && status != RdTaskStatus.FAILED_NEEDS_HUMAN) {
            throw new IllegalStateException("task status is not retryable: " + status);
        }
    }

    private static AgentRole parseRole(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            return null;
        }
        try {
            AgentRole role = AgentRole.valueOf(normalized);
            return AgentRole.requirementDeliveryOrder().contains(role) ? role : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Map<AgentRole, Integer> roleOrder() {
        Map<AgentRole, Integer> order = new EnumMap<>(AgentRole.class);
        List<AgentRole> roles = AgentRole.requirementDeliveryOrder();
        for (int index = 0; index < roles.size(); index++) {
            order.put(roles.get(index), index);
        }
        return Map.copyOf(order);
    }

    private static String firstNonBlank(String first, String second) {
        String normalizedFirst = first == null ? "" : first.strip();
        return normalizedFirst.isBlank() ? (second == null ? "" : second.strip()) : normalizedFirst;
    }
}
