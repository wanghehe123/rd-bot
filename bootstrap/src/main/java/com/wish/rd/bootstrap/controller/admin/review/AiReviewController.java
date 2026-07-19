package com.wish.rd.bootstrap.controller.admin.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.requirement.review.AiDeliveryReviewEngine;
import com.wish.rd.engine.requirement.review.AiReviewRunStore;
import com.wish.rd.engine.requirement.review.model.AiReviewArtifact;
import com.wish.rd.engine.requirement.review.model.AiReviewEvent;
import com.wish.rd.engine.requirement.review.model.AiReviewRun;
import com.wish.rd.engine.requirement.review.model.AiReviewRunStatus;
import com.wish.rd.engine.retry.TaskRetryEngine;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/** Preview-only management API for AI delivery-review state, process, and evidence. */
@RestController
public class AiReviewController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AiReviewRunStore store;
    private final TaskRetryEngine taskRetryEngine;
    private final RagStreamTaskRegistry taskRegistry;
    private final AiDeliveryReviewEngine aiDeliveryReviewEngine;

    @Autowired
    public AiReviewController(
            AiReviewRunStore store,
            ObjectProvider<TaskRetryEngine> retryEngineProvider,
            RagStreamTaskRegistry taskRegistry,
            ObjectProvider<AiDeliveryReviewEngine> reviewEngineProvider
    ) {
        this(store, () -> "", retryEngineProvider.getIfAvailable(), taskRegistry,
                reviewEngineProvider.getIfAvailable());
    }

    public AiReviewController(AiReviewRunStore store, Supplier<String> idSupplier) {
        this(store, idSupplier, null, null, null);
    }

    public AiReviewController(AiReviewRunStore store, Supplier<String> idSupplier, TaskRetryEngine taskRetryEngine) {
        this(store, idSupplier, taskRetryEngine, null, null);
    }

    public AiReviewController(
            AiReviewRunStore store,
            Supplier<String> idSupplier,
            TaskRetryEngine taskRetryEngine,
            RagStreamTaskRegistry taskRegistry,
            AiDeliveryReviewEngine aiDeliveryReviewEngine
    ) {
        this.store = store;
        this.taskRetryEngine = taskRetryEngine;
        this.taskRegistry = taskRegistry;
        this.aiDeliveryReviewEngine = aiDeliveryReviewEngine;
    }

    @GetMapping("/admin/rd-tasks/{taskId}/ai-reviews")
    public List<AiReviewRunView> list(@PathVariable("taskId") String taskId) {
        return store.listByTask(taskId).stream().map(AiReviewRunView::from).toList();
    }

    @PostMapping("/admin/rd-tasks/{taskId}/ai-reviews")
    public AiReviewRunView review(@PathVariable("taskId") String taskId) {
        if (taskRegistry == null || aiDeliveryReviewEngine == null || !aiDeliveryReviewEngine.isEnabled()) {
            throw new IllegalStateException("AI delivery review is disabled or unavailable");
        }
        RdTask task = taskRegistry.getTask(taskId);
        if (!(task instanceof RdRequirementTask requirementTask)) {
            throw new IllegalArgumentException("task is not a requirement task: " + taskId);
        }
        return AiReviewRunView.from(aiDeliveryReviewEngine.review(
                requirementTask, deliveryReviewJson(requirementTask.executionResultJson()), "USER"));
    }

    @GetMapping("/admin/ai-reviews/{runId}")
    public AiReviewRunView detail(@PathVariable("runId") String runId) {
        return AiReviewRunView.from(require(runId));
    }

    @GetMapping("/admin/ai-reviews/{runId}/timeline")
    public List<AiReviewEventView> timeline(@PathVariable("runId") String runId) {
        require(runId);
        return store.listEvents(runId).stream().map(AiReviewEventView::from).toList();
    }

    @GetMapping("/admin/ai-reviews/{runId}/artifacts")
    public List<AiReviewArtifactView> artifacts(@PathVariable("runId") String runId) {
        require(runId);
        return store.listArtifacts(runId).stream().map(AiReviewArtifactView::from).toList();
    }

    @PostMapping("/admin/ai-reviews/{runId}/retry")
    public AiReviewRetryView retry(@PathVariable("runId") String runId) {
        AiReviewRun current = require(runId);
        if (!current.status().isTerminal()) {
            throw new IllegalStateException("only terminal AI review run can be retried: " + runId);
        }
        if (taskRetryEngine == null) {
            throw new IllegalStateException("task retry engine is unavailable");
        }
        return AiReviewRetryView.from(taskRetryEngine.retry(current.taskId(), "USER"));
    }

    @PostMapping("/admin/ai-reviews/{runId}/cancel")
    public AiReviewRunView cancel(@PathVariable("runId") String runId) {
        AiReviewRun current = require(runId);
        if (current.status().isTerminal()) {
            throw new IllegalStateException("AI review run is already terminal: " + runId);
        }
        return AiReviewRunView.from(store.transition(runId, current.status(), AiReviewRunStatus.CANCELLED,
                "USER", "cancelled by operator", "", "", System.currentTimeMillis()));
    }

    private AiReviewRun require(String runId) {
        return store.find(runId).orElseThrow(() -> new NoSuchElementException("AI review run not found: " + runId));
    }

    private String deliveryReviewJson(String resultJson) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(resultJson == null ? "{}" : resultJson);
            JsonNode review = root == null ? null : root.path("deliveryReview");
            return review == null || review.isMissingNode() || review.isNull() ? "{}" : review.toString();
        } catch (Exception exception) {
            return "{}";
        }
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

    public record AiReviewRunView(
            String runId, String taskId, int attemptNo, String parentRunId, String status, String modelName,
            String packageHash, String decision, int score, String retryFromRole, String summary,
            String errorCategory, String errorMessage, long version, long createdAtEpochMillis, long updatedAtEpochMillis
    ) {
        static AiReviewRunView from(AiReviewRun run) {
            return new AiReviewRunView(run.runId(), run.taskId(), run.attemptNo(), run.parentRunId(),
                    run.status().name(), run.modelName(), run.packageHash(),
                    run.decision() == null ? "" : run.decision().name(), run.score(), run.retryFromRole(),
                    run.summary(), run.errorCategory(), run.errorMessage(), run.version(),
                    run.createdAtEpochMillis(), run.updatedAtEpochMillis());
        }
    }

    public record AiReviewEventView(
            String eventId, String runId, String fromStatus, String toStatus, String trigger,
            String message, String errorCategory, long occurredAtEpochMillis
    ) {
        static AiReviewEventView from(AiReviewEvent event) {
            return new AiReviewEventView(event.eventId(), event.runId(), event.fromStatus().name(),
                    event.toStatus().name(), event.trigger(), event.message(), event.errorCategory(),
                    event.occurredAtEpochMillis());
        }
    }

    public record AiReviewArtifactView(
            String artifactId, String runId, String artifactType, String artifactUri,
            String contentPreview, String contentHash, String metadataJson, boolean redacted,
            long createdAtEpochMillis
    ) {
        static AiReviewArtifactView from(AiReviewArtifact artifact) {
            return new AiReviewArtifactView(artifact.artifactId(), artifact.runId(), artifact.artifactType(),
                    artifact.artifactUri(), artifact.contentPreview(), artifact.contentHash(),
                    artifact.metadataJson(), artifact.redacted(), artifact.createdAtEpochMillis());
        }
    }

    public record AiReviewRetryView(
            String checkpointId, String taskId, int attemptNo, String status,
            String failurePhase, String retryFromRole, String failedAiReviewRunId, String reason
    ) {
        static AiReviewRetryView from(TaskRetryCheckpoint checkpoint) {
            return new AiReviewRetryView(
                    checkpoint.checkpointId(), checkpoint.taskId(), checkpoint.attemptNo(),
                    checkpoint.status().name(), checkpoint.failurePhase().name(),
                    checkpoint.retryFromRole() == null ? "" : checkpoint.retryFromRole().name(),
                    checkpoint.failedAiReviewRunId(), checkpoint.reason());
        }
    }

    public record ErrorView(String message) {
    }
}
