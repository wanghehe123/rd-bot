package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.wish.rd.engine.retry.TaskRetryCheckpointStore;
import com.wish.rd.engine.retry.TaskRetryEngine;
import com.wish.rd.engine.retry.TaskFailureRecoveryService;
import com.wish.rd.engine.retry.model.TaskFailureDiagnostic;
import com.wish.rd.engine.retry.model.TaskFailureIssue;
import com.wish.rd.engine.retry.model.TaskFailureRecoverySnapshot;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCommand;
import com.wish.rd.engine.retry.model.TaskRetryPoint;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

/** Operator API for previewing and starting a durable resume-from-failure attempt. */
@RestController
public class TaskRetryController {

    private final TaskRetryEngine retryEngine;
    private final TaskRetryCheckpointStore checkpointStore;
    private final TaskFailureRecoveryService failureRecoveryService;

    public TaskRetryController(
            TaskRetryEngine retryEngine,
            TaskRetryCheckpointStore checkpointStore,
            TaskFailureRecoveryService failureRecoveryService
    ) {
        this.retryEngine = retryEngine;
        this.checkpointStore = checkpointStore;
        this.failureRecoveryService = failureRecoveryService;
    }

    @GetMapping("/admin/rd-tasks/{taskId}/retry-preview")
    public TaskRetryPointView preview(@PathVariable("taskId") String taskId) {
        return TaskRetryPointView.from(retryEngine.preview(taskId));
    }

    @GetMapping("/admin/rd-tasks/{taskId}/retry-history")
    public List<TaskRetryCheckpointView> history(@PathVariable("taskId") String taskId) {
        return checkpointStore.listByTask(taskId).stream().map(TaskRetryCheckpointView::from).toList();
    }

    /**
     * Returns a bounded, structured failure diagnosis for the operator recovery workbench.
     *
     * @param taskId requirement task ID
     * @return retry point, structured issues, raw-result metadata, and retry history
     */
    @GetMapping("/admin/rd-tasks/{taskId}/failure-recovery")
    public TaskFailureRecoveryView failureRecovery(@PathVariable("taskId") String taskId) {
        return TaskFailureRecoveryView.from(failureRecoveryService.snapshot(taskId));
    }

    /**
     * Creates a durable checkpoint and resumes from the current failed stage without replaying upstream success.
     *
     * @param taskId requirement task ID
     * @param request optional operator note, selected task evidence, and stale-failure guards
     * @return durable recovery checkpoint
     */
    @PostMapping("/admin/rd-tasks/{taskId}/retry")
    public TaskRetryCheckpointView retry(
            @PathVariable("taskId") String taskId,
            @RequestBody(required = false) TaskRetryRequest request
    ) {
        TaskRetryCheckpoint checkpoint = request == null
                ? retryEngine.retry(taskId, "USER")
                : retryEngine.retry(taskId, "USER", request.toCommand());
        return TaskRetryCheckpointView.from(checkpoint);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorView> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorView(exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorView> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorView(exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorView> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorView(exception.getMessage()));
    }

    public record TaskRetryPointView(
            String taskId, String failurePhase, String retryFromRole, String failedStageRunId,
            String failedRetrievalRunId, String failedAiReviewRunId, String reason, long sourceTaskVersion
    ) {
        static TaskRetryPointView from(TaskRetryPoint point) {
            return new TaskRetryPointView(point.taskId(), point.failurePhase().name(),
                    point.retryFromRole() == null ? "" : point.retryFromRole().name(), point.failedStageRunId(),
                    point.failedRetrievalRunId(), point.failedAiReviewRunId(), point.reason(), point.sourceTaskVersion());
        }
    }

    public record TaskRetryCheckpointView(
            String checkpointId, String taskId, String failurePhase, String retryFromRole,
            String failedStageRunId, String failedRetrievalRunId, String failedAiReviewRunId,
            long sourceTaskVersion, int attemptNo, String operatorNote, List<String> evidenceMaterialIds,
            String status, String reason, String errorMessage,
            long createdAtEpochMillis, long updatedAtEpochMillis
    ) {
        static TaskRetryCheckpointView from(TaskRetryCheckpoint checkpoint) {
            return new TaskRetryCheckpointView(checkpoint.checkpointId(), checkpoint.taskId(),
                    checkpoint.failurePhase().name(),
                    checkpoint.retryFromRole() == null ? "" : checkpoint.retryFromRole().name(),
                    checkpoint.failedStageRunId(), checkpoint.failedRetrievalRunId(),
                    checkpoint.failedAiReviewRunId(), checkpoint.sourceTaskVersion(), checkpoint.attemptNo(),
                    checkpoint.operatorNote(),
                    checkpoint.evidenceMaterialIds(), checkpoint.status().name(),
                    checkpoint.reason(), checkpoint.errorMessage(), checkpoint.createdAtEpochMillis(),
                    checkpoint.updatedAtEpochMillis());
        }
    }

    /** Request payload for a durable resume-from-failure attempt. */
    public record TaskRetryRequest(
            String expectedFailedStageRunId,
            String expectedFailedRetrievalRunId,
            String expectedFailedAiReviewRunId,
            long expectedSourceTaskVersion,
            String operatorNote,
            List<String> evidenceMaterialIds
    ) {
        public TaskRetryRequest {
            if (expectedSourceTaskVersion <= 0L) {
                throw new IllegalArgumentException("expectedSourceTaskVersion must be positive");
            }
            evidenceMaterialIds = List.copyOf(evidenceMaterialIds == null ? List.of() : evidenceMaterialIds);
        }

        TaskRetryCommand toCommand() {
            return new TaskRetryCommand(
                    expectedFailedStageRunId,
                    expectedFailedRetrievalRunId,
                    expectedFailedAiReviewRunId,
                    expectedSourceTaskVersion,
                    operatorNote,
                    evidenceMaterialIds
            );
        }
    }

    /** Stable read view for the operator failure-recovery workbench. */
    public record TaskFailureRecoveryView(
            String taskId,
            String sourceTaskStatus,
            TaskRetryPointView retryPoint,
            String failedStageStatus,
            int failedAttemptNo,
            String providerName,
            String errorCategory,
            String errorMessage,
            TaskFailureDiagnosticView diagnostic,
            String rawResultArtifactId,
            String rawResultContentHash,
            String rawResultPreview,
            List<TaskRetryCheckpointView> history
    ) {
        static TaskFailureRecoveryView from(TaskFailureRecoverySnapshot snapshot) {
            return new TaskFailureRecoveryView(
                    snapshot.taskId(), snapshot.sourceTaskStatus().name(),
                    TaskRetryPointView.from(snapshot.retryPoint()), snapshot.failedStageStatus(),
                    snapshot.failedAttemptNo(), snapshot.providerName(), snapshot.errorCategory(),
                    snapshot.errorMessage(), TaskFailureDiagnosticView.from(snapshot.diagnostic()),
                    snapshot.rawResultArtifactId(), snapshot.rawResultContentHash(), snapshot.rawResultPreview(),
                    snapshot.history().stream().map(TaskRetryCheckpointView::from).toList()
            );
        }
    }

    /** Stable diagnosis view independent of a raw role-result JSON shape. */
    public record TaskFailureDiagnosticView(
            String category,
            String title,
            String summary,
            String suggestedAction,
            boolean requiresSupplement,
            List<TaskFailureIssueView> issues,
            List<TaskFailureIssueView> risks,
            List<TaskFailureIssueView> acceptanceGaps
    ) {
        static TaskFailureDiagnosticView from(TaskFailureDiagnostic diagnostic) {
            return new TaskFailureDiagnosticView(
                    diagnostic.category(), diagnostic.title(), diagnostic.summary(), diagnostic.suggestedAction(),
                    diagnostic.requiresSupplement(),
                    diagnostic.issues().stream().map(TaskFailureIssueView::from).toList(),
                    diagnostic.risks().stream().map(TaskFailureIssueView::from).toList(),
                    diagnostic.acceptanceGaps().stream().map(TaskFailureIssueView::from).toList()
            );
        }
    }

    /** One concise issue, risk, or acceptance coverage gap in a recovery diagnosis. */
    public record TaskFailureIssueView(
            String kind,
            String severity,
            String title,
            String detail,
            String sourceField
    ) {
        static TaskFailureIssueView from(TaskFailureIssue issue) {
            return new TaskFailureIssueView(
                    issue.kind(), issue.severity(), issue.title(), issue.detail(), issue.sourceField());
        }
    }

    public record ErrorView(String message) {
    }
}
