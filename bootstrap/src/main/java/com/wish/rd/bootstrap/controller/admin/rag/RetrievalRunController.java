package com.wish.rd.bootstrap.controller.admin.rag;

import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.retrieval.run.RetrievalRunStore;
import com.wish.rd.rag.retrieval.run.model.RetrievalRun;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunEvent;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunStatus;
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

/** Read and control surface for the independent RAG retrieval attempt lifecycle. */
@RestController
public class RetrievalRunController {

    private final RetrievalRunStore store;
    private final Supplier<String> idSupplier;

    @Autowired
    public RetrievalRunController(
            ObjectProvider<RetrievalRunStore> storeProvider,
            ObjectProvider<SnowflakeIdGenerator> idGeneratorProvider
    ) {
        this(storeProvider.getIfAvailable(), idGeneratorProvider.getIfAvailable(SnowflakeIdGenerator::defaultGenerator)::nextIdString);
    }

    public RetrievalRunController(RetrievalRunStore store, Supplier<String> idSupplier) {
        if (store == null) {
            throw new IllegalArgumentException("retrieval run store must not be null");
        }
        this.store = store;
        this.idSupplier = idSupplier == null ? SnowflakeIdGenerator.defaultGenerator()::nextIdString : idSupplier;
    }

    @GetMapping("/admin/rd-tasks/{taskId}/retrieval-runs")
    public List<RetrievalRunView> listByTask(@PathVariable("taskId") String taskId) {
        return store.listByTask(taskId).stream().map(RetrievalRunView::from).toList();
    }

    @GetMapping("/admin/rag-retrieval-runs/{runId}")
    public RetrievalRunView detail(@PathVariable("runId") String runId) {
        return store.find(runId).map(RetrievalRunView::from)
                .orElseThrow(() -> new NoSuchElementException("retrieval run not found: " + runId));
    }

    @GetMapping("/admin/rag-retrieval-runs/{runId}/timeline")
    public List<RetrievalRunEventView> timeline(@PathVariable("runId") String runId) {
        detail(runId);
        return store.listEvents(runId).stream().map(RetrievalRunEventView::from).toList();
    }

    @PostMapping("/admin/rag-retrieval-runs/{runId}/retry")
    public RetrievalRunView retry(@PathVariable("runId") String runId) {
        return RetrievalRunView.from(store.retry(runId, idSupplier.get(), System.currentTimeMillis()));
    }

    @PostMapping("/admin/rag-retrieval-runs/{runId}/cancel")
    public RetrievalRunView cancel(@PathVariable("runId") String runId) {
        RetrievalRun current = store.find(runId)
                .orElseThrow(() -> new NoSuchElementException("retrieval run not found: " + runId));
        if (current.status().isTerminal()) {
            throw new IllegalStateException("retrieval run is already terminal: " + runId);
        }
        return RetrievalRunView.from(store.transition(
                runId, current.version(), RetrievalRunStatus.CANCELLED, current.currentIteration(),
                current.qualityDecision(), "cancelled by operator", "", "", "MANUAL", System.currentTimeMillis()
        ));
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
        return ResponseEntity.badRequest().body(new ErrorView(exception.getMessage()));
    }

    public record RetrievalRunView(
            String runId, String taskId, String consumerType, String role, String stageRunId, int attemptNo,
            String parentRunId, String status, List<String> knowledgeBaseIds, String queryHash, String queryPreview,
            int currentIteration, int maxIterations, int contextBudgetChars, int candidateCount,
            int selectedEvidenceCount, String qualityDecision, String stopReason, String errorCategory,
            String errorMessage, long version, long createdAtEpochMillis, long updatedAtEpochMillis
    ) {
        static RetrievalRunView from(RetrievalRun run) {
            return new RetrievalRunView(
                    run.runId(), run.taskId(), run.consumerType().name(), run.role(), run.stageRunId(), run.attemptNo(),
                    run.parentRunId(), run.status().name(), run.knowledgeBaseIds(), run.queryHash(), run.queryPreview(),
                    run.currentIteration(), run.maxIterations(), run.contextBudgetChars(), run.candidateCount(),
                    run.selectedEvidenceCount(), run.qualityDecision() == null ? "" : run.qualityDecision().name(),
                    run.stopReason(), run.errorCategory(), run.errorMessage(), run.version(),
                    run.createdAtEpochMillis(), run.updatedAtEpochMillis()
            );
        }
    }

    public record RetrievalRunEventView(
            String eventId, String runId, String fromStatus, String toStatus, String trigger, String message,
            String errorCategory, long occurredAtEpochMillis
    ) {
        static RetrievalRunEventView from(RetrievalRunEvent event) {
            return new RetrievalRunEventView(
                    event.eventId(), event.runId(), event.fromStatus().name(), event.toStatus().name(), event.trigger(),
                    event.message(), event.errorCategory(), event.occurredAtEpochMillis()
            );
        }
    }

    public record ErrorView(String message) {
    }
}
