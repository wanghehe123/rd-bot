package com.wish.rd.bootstrap.controller.admin.evaluation;

import com.wish.rd.engine.evaluation.taskrun.TaskRunEvaluationSnapshotCollector;
import com.wish.rd.engine.evaluation.EvaluationCatalogPort;
import com.wish.rd.engine.evaluation.EvaluationOutputReaderPort;
import com.wish.rd.engine.evaluation.EvaluationRunEngine;
import com.wish.rd.engine.evaluation.model.EvaluationArtifact;
import com.wish.rd.engine.evaluation.model.EvaluationCapabilities;
import com.wish.rd.engine.evaluation.model.EvaluationDatasetKind;
import com.wish.rd.engine.evaluation.model.EvaluationJudgeProvider;
import com.wish.rd.engine.evaluation.model.EvaluationRun;
import com.wish.rd.engine.evaluation.model.EvaluationRunConfig;
import com.wish.rd.engine.evaluation.model.EvaluationRunEvent;
import com.wish.rd.engine.evaluation.model.EvaluationRunPage;
import com.wish.rd.engine.evaluation.model.EvaluationRunQuery;
import com.wish.rd.engine.evaluation.model.EvaluationRunStatus;
import com.wish.rd.engine.evaluation.model.EvaluationSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/** Management API for configuring, running, inspecting, cancelling, and retrying local evaluations. */
@RestController
public class EvaluationController {
    private final EvaluationRunEngine engine;
    private final EvaluationCatalogPort catalog;
    private final EvaluationOutputReaderPort outputReader;
    private final TaskRunEvaluationSnapshotCollector taskRunCollector;

    public EvaluationController(
            EvaluationRunEngine engine,
            EvaluationCatalogPort catalog,
            EvaluationOutputReaderPort outputReader
    ) {
        this(engine, catalog, outputReader, (TaskRunEvaluationSnapshotCollector) null);
    }

    @Autowired
    public EvaluationController(
            EvaluationRunEngine engine,
            EvaluationCatalogPort catalog,
            EvaluationOutputReaderPort outputReader,
            ObjectProvider<TaskRunEvaluationSnapshotCollector> taskRunCollectorProvider
    ) {
        this(engine, catalog, outputReader, taskRunCollectorProvider.getIfAvailable());
    }

    public EvaluationController(
            EvaluationRunEngine engine,
            EvaluationCatalogPort catalog,
            EvaluationOutputReaderPort outputReader,
            TaskRunEvaluationSnapshotCollector taskRunCollector
    ) {
        this.engine = engine;
        this.catalog = catalog;
        this.outputReader = outputReader;
        this.taskRunCollector = taskRunCollector;
    }

    /** @return safe datasets, sources, Judge choices, limits, and local defaults */
    @GetMapping("/admin/evaluations/capabilities")
    public EvaluationCapabilities capabilities() {
        return catalog.capabilities();
    }

    /** @return one server-filtered evaluation history page ordered newest first */
    @GetMapping("/admin/evaluations/runs")
    public EvaluationRunPage list(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "datasetKind", required = false) String datasetKind,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "gateStatus", required = false) String gateStatus,
            @RequestParam(name = "judgeStatus", required = false) String judgeStatus,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "10") int pageSize
    ) {
        EvaluationDatasetKind parsedDatasetKind = enumValue(datasetKind, EvaluationDatasetKind.class, "datasetKind");
        EvaluationCapabilities capabilities = catalog.capabilities();
        List<String> selectedDatasetIds = parsedDatasetKind == null || parsedDatasetKind == EvaluationDatasetKind.TASK_RUN
                ? List.of()
                : capabilities.datasets().stream()
                .filter(dataset -> dataset.kind() == parsedDatasetKind)
                .map(dataset -> dataset.id())
                .toList();
        List<String> nonSmokeDatasetIds = capabilities.datasets().stream()
                .filter(dataset -> dataset.kind() == EvaluationDatasetKind.QUALITY_BENCHMARK)
                .map(dataset -> dataset.id())
                .toList();
        return engine.query(new EvaluationRunQuery(
                keyword,
                parsedDatasetKind == EvaluationDatasetKind.TASK_RUN ? EvaluationSource.TASK_RUN : null,
                selectedDatasetIds,
                parsedDatasetKind != null && parsedDatasetKind != EvaluationDatasetKind.TASK_RUN,
                enumValue(status, EvaluationRunStatus.class, "status"),
                gateStatus,
                judgeStatus,
                nonSmokeDatasetIds,
                page,
                pageSize
        ));
    }

    /** Creates an asynchronous local evaluation from structured Web form values. */
    @PostMapping("/admin/evaluations/runs")
    public ResponseEntity<EvaluationRun> create(@RequestBody CreateEvaluationRequest request) {
        EvaluationRunConfig config = request.toConfig();
        config.validate();
        if (config.source() == EvaluationSource.TASK_RUN) {
            if (taskRunCollector == null) {
                throw new IllegalStateException("task-run evaluation collector is unavailable");
            }
            taskRunCollector.target(config.taskId());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(engine.start(config));
    }

    /** Creates a single-sample evaluation from one persisted RD task execution. */
    @PostMapping("/admin/rd-tasks/{taskId}/evaluations")
    public ResponseEntity<EvaluationRun> createForTask(
            @PathVariable("taskId") String taskId,
            @RequestBody(required = false) TaskRunEvaluationRequest request
    ) {
        if (taskId == null || !taskId.matches("[0-9]{1,64}")) {
            throw new IllegalArgumentException("task id must be numeric");
        }
        if (taskRunCollector == null) {
            throw new IllegalStateException("task-run evaluation collector is unavailable");
        }
        TaskRunEvaluationSnapshotCollector.TaskRunEvaluationTarget target = taskRunCollector.target(taskId);
        TaskRunEvaluationRequest safeRequest = request == null
                ? new TaskRunEvaluationRequest(EvaluationJudgeProvider.NONE, 0, 90, "")
                : request;
        String title = target.title().isBlank() ? target.taskId() : target.title();
        String name = "执行评测 · " + title;
        if (name.length() > 120) {
            name = name.substring(0, 120);
        }
        EvaluationRunConfig config = new EvaluationRunConfig(
                name,
                "task-run.generated.jsonl",
                EvaluationSource.TASK_RUN,
                "task-run-web",
                0,
                "",
                "",
                safeRequest.timeoutSeconds() == null ? 90 : safeRequest.timeoutSeconds(),
                safeRequest.judgeProvider(),
                safeRequest.judgeLimit() == null ? 0 : safeRequest.judgeLimit(),
                false,
                safe(safeRequest.baselineRunId()),
                target.taskId()
        );
        config.validate();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(engine.start(config));
    }

    /** Returns one evaluation attempt snapshot. */
    @GetMapping("/admin/evaluations/runs/{runId}")
    public EvaluationRun detail(@PathVariable("runId") String runId) {
        return engine.find(runId);
    }

    /** Returns the append-only state timeline for a run. */
    @GetMapping("/admin/evaluations/runs/{runId}/timeline")
    public List<EvaluationRunEvent> timeline(@PathVariable("runId") String runId) {
        return engine.timeline(runId);
    }

    /** Returns redacted artifact metadata for a run. */
    @GetMapping("/admin/evaluations/runs/{runId}/artifacts")
    public List<EvaluationArtifact> artifacts(@PathVariable("runId") String runId) {
        return engine.artifacts(runId);
    }

    /** Returns a bounded tail of the local evaluation process log. */
    @GetMapping("/admin/evaluations/runs/{runId}/logs")
    public EvaluationContentView logs(
            @PathVariable("runId") String runId,
            @RequestParam(name = "maxChars", defaultValue = "100000") int maxChars
    ) {
        engine.find(runId);
        return contentView(runId, "LOG", outputReader.readLog(runId, maxChars), maxChars);
    }

    /** Returns bounded text for one allowlisted artifact type. */
    @GetMapping("/admin/evaluations/runs/{runId}/artifacts/{artifactType}/content")
    public EvaluationContentView artifactContent(
            @PathVariable("runId") String runId,
            @PathVariable("artifactType") String artifactType,
            @RequestParam(name = "maxChars", defaultValue = "200000") int maxChars
    ) {
        engine.find(runId);
        return contentView(runId, artifactType, outputReader.readArtifact(runId, artifactType, maxChars), maxChars);
    }

    /** Cancels an active local evaluation process. */
    @PostMapping("/admin/evaluations/runs/{runId}/cancel")
    public EvaluationRun cancel(@PathVariable("runId") String runId) {
        return engine.cancel(runId);
    }

    /** Creates a new parent-linked attempt from a terminal run. */
    @PostMapping("/admin/evaluations/runs/{runId}/retry")
    public ResponseEntity<EvaluationRun> retry(@PathVariable("runId") String runId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(engine.retry(runId));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorView> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorView("NOT_FOUND", safe(exception.getMessage())));
    }

    @ExceptionHandler({IllegalArgumentException.class, NumberFormatException.class})
    public ResponseEntity<ErrorView> badRequest(RuntimeException exception) {
        return ResponseEntity.badRequest().body(new ErrorView("INVALID_EVALUATION_REQUEST", safe(exception.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorView> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorView("EVALUATION_STATE_CONFLICT", safe(exception.getMessage())));
    }

    /** Web request DTO; no executable or filesystem root is client-controlled. */
    public record CreateEvaluationRequest(
            String name,
            String datasetId,
            EvaluationSource source,
            String environmentId,
            Integer sampleLimit,
            String baseUrl,
            String ragLogPath,
            Integer timeoutSeconds,
            EvaluationJudgeProvider judgeProvider,
            Integer judgeLimit,
            Boolean strictMissingRecords,
            String baselineRunId,
            String taskId
    ) {
        EvaluationRunConfig toConfig() {
            return new EvaluationRunConfig(
                    name,
                    datasetId,
                    source,
                    environmentId,
                    value(sampleLimit),
                    baseUrl,
                    ragLogPath,
                    timeoutSeconds == null ? 90 : timeoutSeconds,
                    judgeProvider,
                    value(judgeLimit),
                    Boolean.TRUE.equals(strictMissingRecords),
                    baselineRunId,
                    taskId
            );
        }

        private static int value(Integer value) {
            return value == null ? 0 : value;
        }
    }

    /** Optional settings for a server-collected task execution evaluation. */
    public record TaskRunEvaluationRequest(
            EvaluationJudgeProvider judgeProvider,
            Integer judgeLimit,
            Integer timeoutSeconds,
            String baselineRunId
    ) {
    }

    private EvaluationContentView contentView(String runId, String artifactType, String outputContent, int maxChars) {
        String normalizedType = safe(artifactType).toUpperCase(Locale.ROOT);
        if (outputContent != null && !outputContent.isBlank()) {
            return new EvaluationContentView(normalizedType, outputContent, "OUTPUT_FILE");
        }
        String preview = engine.artifacts(runId).stream()
                .filter(artifact -> normalizedType.equalsIgnoreCase(artifact.artifactType()))
                .map(EvaluationArtifact::contentPreview)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
        if (!preview.isBlank()) {
            return new EvaluationContentView(normalizedType, bounded(preview, maxChars), "PERSISTED_PREVIEW");
        }
        return new EvaluationContentView(normalizedType, "", "UNAVAILABLE");
    }

    /** Bounded textual log or report payload. */
    public record EvaluationContentView(String artifactType, String content, String source) {
        public EvaluationContentView(String artifactType, String content) {
            this(artifactType, content, content == null || content.isBlank() ? "UNAVAILABLE" : "OUTPUT_FILE");
        }
    }

    /** Sanitized management API error. */
    public record ErrorView(String code, String message) {
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.length() <= 1_000 ? normalized : normalized.substring(0, 1_000);
    }

    private static String bounded(String value, int requestedMaxChars) {
        int maxChars = Math.max(100, Math.min(1_000_000, requestedMaxChars));
        String normalized = value == null ? "" : value;
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "...";
    }

    private static <T extends Enum<T>> T enumValue(String value, Class<T> enumType, String fieldName) {
        String normalized = safe(value).toUpperCase(Locale.ROOT);
        if (normalized.isBlank() || "ALL".equals(normalized)) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported " + fieldName + ": " + normalized);
        }
    }
}
