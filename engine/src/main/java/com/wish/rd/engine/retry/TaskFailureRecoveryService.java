package com.wish.rd.engine.retry;

import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.requirement.review.AiReviewRunStore;
import com.wish.rd.engine.retry.model.TaskFailureDiagnostic;
import com.wish.rd.engine.retry.model.TaskFailureRecoverySnapshot;
import com.wish.rd.engine.retry.model.TaskRetryPoint;
import com.wish.rd.rag.retrieval.run.RetrievalRunStore;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Builds a safe, read-only recovery view from durable task, stage, artifact, and retry records. */
@Service
public final class TaskFailureRecoveryService {

    private static final int MAX_RAW_RESULT_PREVIEW = 20_000;
    private final TaskRetryTaskPort taskPort;
    private final AgentStageRunStore stageRunStore;
    private final AgentStageArtifactStore artifactStore;
    private final RetrievalRunStore retrievalRunStore;
    private final AiReviewRunStore aiReviewRunStore;
    private final TaskRetryCheckpointStore checkpointStore;
    private final TaskRetryPointResolver resolver;
    private final TaskFailureDiagnosticParser parser;

    /**
     * Creates the recovery query use case with production store ports.
     *
     * @param taskPort requirement task state port
     * @param stageRunStore agent stage store
     * @param artifactStore agent artifact store
     * @param retrievalRunStore retrieval run store
     * @param aiReviewRunStore AI review run store
     * @param checkpointStore retry checkpoint store
     */
    @Autowired
    public TaskFailureRecoveryService(
            TaskRetryTaskPort taskPort,
            AgentStageRunStore stageRunStore,
            AgentStageArtifactStore artifactStore,
            RetrievalRunStore retrievalRunStore,
            AiReviewRunStore aiReviewRunStore,
            TaskRetryCheckpointStore checkpointStore
    ) {
        this(
                taskPort,
                stageRunStore,
                artifactStore,
                retrievalRunStore,
                aiReviewRunStore,
                checkpointStore,
                new TaskRetryPointResolver(),
                new TaskFailureDiagnosticParser()
        );
    }

    /**
     * Creates the recovery query use case with explicit collaborators for deterministic tests.
     *
     * @param taskPort requirement task state port
     * @param stageRunStore agent stage store
     * @param artifactStore agent artifact store
     * @param retrievalRunStore retrieval run store
     * @param aiReviewRunStore AI review run store
     * @param checkpointStore retry checkpoint store
     * @param resolver retry-point resolver
     * @param parser structured failure parser
     */
    public TaskFailureRecoveryService(
            TaskRetryTaskPort taskPort,
            AgentStageRunStore stageRunStore,
            AgentStageArtifactStore artifactStore,
            RetrievalRunStore retrievalRunStore,
            AiReviewRunStore aiReviewRunStore,
            TaskRetryCheckpointStore checkpointStore,
            TaskRetryPointResolver resolver,
            TaskFailureDiagnosticParser parser
    ) {
        this.taskPort = Objects.requireNonNull(taskPort, "taskPort must not be null");
        this.stageRunStore = Objects.requireNonNull(stageRunStore, "stageRunStore must not be null");
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore must not be null");
        this.retrievalRunStore = Objects.requireNonNull(retrievalRunStore, "retrievalRunStore must not be null");
        this.aiReviewRunStore = Objects.requireNonNull(aiReviewRunStore, "aiReviewRunStore must not be null");
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore must not be null");
        this.resolver = resolver == null ? new TaskRetryPointResolver() : resolver;
        this.parser = parser == null ? new TaskFailureDiagnosticParser() : parser;
    }

    /**
     * Reads a recovery snapshot without mutating task, checkpoint, or stage state.
     *
     * @param taskId requirement task ID
     * @return failure diagnostic and retry history
     */
    public TaskFailureRecoverySnapshot snapshot(String taskId) {
        RdRequirementTask task = taskPort.getRequirementTask(taskId);
        List<AgentStageRun> stages = stageRunStore.listByTask(taskId);
        TaskRetryPoint point = resolver.resolve(
                task,
                stages,
                retrievalRunStore.listByTask(taskId),
                aiReviewRunStore.listByTask(taskId)
        );
        AgentStageRun failedStage = findStage(stages, point.failedStageRunId());
        AgentStageArtifact resultArtifact = findResultArtifact(task.taskId(), failedStage);
        String resultJson = resultArtifact == null
                ? (failedStage == null ? "" : failedStage.reviewResultJson())
                : resultArtifact.contentPreview();
        TaskFailureDiagnostic diagnostic = parser.parse(
                point.failurePhase(),
                point.retryFromRole(),
                failedStage == null ? "" : failedStage.errorCategory(),
                firstNonBlank(failedStage == null ? "" : failedStage.errorMessage(), point.reason()),
                resultJson
        );
        return new TaskFailureRecoverySnapshot(
                task.taskId(),
                task.status(),
                point,
                failedStage == null ? "" : failedStage.status().name(),
                failedStage == null ? 0 : failedStage.attemptNo(),
                failedStage == null ? "" : failedStage.providerName(),
                failedStage == null ? "" : failedStage.errorCategory(),
                firstNonBlank(failedStage == null ? "" : failedStage.errorMessage(), point.reason()),
                diagnostic,
                resultArtifact == null ? "" : resultArtifact.artifactId(),
                resultArtifact == null ? "" : resultArtifact.contentHash(),
                bounded(resultJson),
                checkpointStore.listByTask(task.taskId())
        );
    }

    private static AgentStageRun findStage(List<AgentStageRun> stages, String stageRunId) {
        if (stageRunId == null || stageRunId.isBlank()) {
            return null;
        }
        return (stages == null ? List.<AgentStageRun>of() : stages).stream()
                .filter(stage -> stageRunId.equals(stage.stageRunId()))
                .findFirst().orElse(null);
    }

    private AgentStageArtifact findResultArtifact(String taskId, AgentStageRun stage) {
        if (stage == null || stage.resultArtifactId().isBlank()) {
            return null;
        }
        return artifactStore.listByTask(taskId).stream()
                .filter(artifact -> stage.resultArtifactId().equals(artifact.artifactId()))
                .filter(artifact -> stage.stageRunId().equals(artifact.stageRunId()))
                .filter(artifact -> "RESULT_JSON".equals(artifact.artifactType()))
                .max(Comparator.comparingLong(AgentStageArtifact::createdAtEpochMillis)
                        .thenComparing(AgentStageArtifact::artifactId))
                .orElse(null);
    }

    private static String firstNonBlank(String first, String second) {
        String normalized = first == null ? "" : first.strip();
        return normalized.isBlank() ? (second == null ? "" : second.strip()) : normalized;
    }

    private static String bounded(String value) {
        String normalized = value == null ? "" : value.strip();
        return normalized.length() <= MAX_RAW_RESULT_PREVIEW
                ? normalized
                : normalized.substring(0, MAX_RAW_RESULT_PREVIEW);
    }
}
