package com.wish.rd.bootstrap.evaluation.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.evaluation.EvaluationProperties;
import com.wish.rd.engine.evaluation.EvaluationExecutionPort;
import com.wish.rd.engine.evaluation.EvaluationCatalogPort;
import com.wish.rd.engine.evaluation.CodingBenchmarkCatalogPort;
import com.wish.rd.engine.evaluation.EvaluationOutputReaderPort;
import com.wish.rd.engine.evaluation.EvaluationProgressListener;
import com.wish.rd.engine.evaluation.model.EvaluationArtifact;
import com.wish.rd.engine.evaluation.model.EvaluationCapabilities;
import com.wish.rd.engine.evaluation.model.EvaluationDataset;
import com.wish.rd.engine.evaluation.model.EvaluationDatasetKind;
import com.wish.rd.engine.evaluation.model.EvaluationExecutionResult;
import com.wish.rd.engine.evaluation.model.EvaluationJudgeProvider;
import com.wish.rd.engine.evaluation.model.EvaluationRun;
import com.wish.rd.engine.evaluation.model.EvaluationRunConfig;
import com.wish.rd.engine.evaluation.model.EvaluationRunStatus;
import com.wish.rd.engine.evaluation.model.EvaluationSource;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkSnapshot;
import com.wish.rd.engine.evaluation.taskrun.TaskRunEvaluationSnapshotCollector;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the repository-owned Python evaluation scripts without shell interpolation. */
@Component
public final class LocalPythonEvaluationExecutor implements EvaluationExecutionPort, EvaluationCatalogPort, EvaluationOutputReaderPort {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalPythonEvaluationExecutor.class);
    private static final Map<String, String> ARTIFACT_FILES = artifactFiles();
    private final EvaluationProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskRunEvaluationSnapshotCollector taskRunCollector;
    private final CodingBenchmarkCatalogPort codingBenchmarkCatalog;
    private final ConcurrentHashMap<String, Process> activeProcesses = new ConcurrentHashMap<>();
    private final Set<String> cancelledRunIds = ConcurrentHashMap.newKeySet();

    public LocalPythonEvaluationExecutor(EvaluationProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, (TaskRunEvaluationSnapshotCollector) null, null);
    }

    @Autowired
    public LocalPythonEvaluationExecutor(
            EvaluationProperties properties,
            ObjectMapper objectMapper,
            ObjectProvider<TaskRunEvaluationSnapshotCollector> taskRunCollectorProvider,
            ObjectProvider<CodingBenchmarkCatalogPort> codingBenchmarkCatalogProvider
    ) {
        this(properties, objectMapper, taskRunCollectorProvider.getIfAvailable(), codingBenchmarkCatalogProvider.getIfAvailable());
    }

    public LocalPythonEvaluationExecutor(
            EvaluationProperties properties,
            ObjectMapper objectMapper,
            TaskRunEvaluationSnapshotCollector taskRunCollector
    ) {
        this(properties, objectMapper, taskRunCollector, null);
    }

    public LocalPythonEvaluationExecutor(
            EvaluationProperties properties,
            ObjectMapper objectMapper,
            TaskRunEvaluationSnapshotCollector taskRunCollector,
            CodingBenchmarkCatalogPort codingBenchmarkCatalog
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.taskRunCollector = taskRunCollector;
        this.codingBenchmarkCatalog = codingBenchmarkCatalog;
    }

    /** Returns the safe server-owned choices used by the management form. */
    @Override
    public EvaluationCapabilities capabilities() {
        List<EvaluationDataset> datasets;
        try {
            Path root = properties.resolvedDatasetRoot();
            if (!Files.isDirectory(root)) {
                datasets = List.of();
            } else {
                try (var paths = Files.list(root)) {
                    datasets = paths
                            .filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}\\.jsonl"))
                            .sorted()
                            .map(this::datasetDescriptor)
                            .toList();
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("cannot inspect evaluation datasets", exception);
        }
        LOGGER.info("[EVALUATION] CATALOG repositoryRoot={} datasetRoot={} datasets={} enabled={}",
                properties.getRepositoryRoot(), properties.resolvedDatasetRoot(), datasets.size(), properties.isEnabled());
        List<CodingBenchmarkSnapshot> codingBenchmarkSnapshots = codingBenchmarkCatalog == null
                ? List.of() : codingBenchmarkCatalog.readySnapshots();
        return new EvaluationCapabilities(
                properties.isEnabled(),
                List.of(EvaluationSource.FIXTURE, EvaluationSource.RAG_HTTP, EvaluationSource.TASK_RUN),
                List.of(EvaluationJudgeProvider.NONE, EvaluationJudgeProvider.RAGAS, EvaluationJudgeProvider.OPENAI_COMPATIBLE),
                datasets,
                properties.getDefaultBaseUrl(),
                10_000,
                3_600,
                codingBenchmarkSnapshots
        );
    }

    /** Executes record, score, report, and optional baseline diff phases. */
    @Override
    public EvaluationExecutionResult execute(EvaluationRun run, EvaluationProgressListener listener) {
        requireSafeRunId(run.runId());
        run.config().validate();
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Web evaluation execution is disabled");
        }
        prepareOutputDirectories(run.runId());
        if (run.config().source() == EvaluationSource.TASK_RUN) {
            recordTaskRun(run);
        } else {
            runStep(run, EvaluationRunStatus.RECORDING, recordCommand(run));
        }
        listener.phase(EvaluationRunStatus.SCORING, "score evaluation records");
        runStep(run, EvaluationRunStatus.SCORING, scoreCommand(run));
        listener.phase(EvaluationRunStatus.REPORTING, "render evaluation reports");
        runStep(run, EvaluationRunStatus.REPORTING, reportCommand(run));
        if (!run.config().baselineRunId().isBlank()) {
            listener.phase(EvaluationRunStatus.DIFFING, "compare with baseline run " + run.config().baselineRunId());
            runStep(run, EvaluationRunStatus.DIFFING, diffCommand(run));
        }
        EvaluationExecutionResult result = parseResult(run.runId());
        cancelledRunIds.remove(run.runId());
        return result;
    }

    /** Cancels the currently active child process for the run, if present. */
    @Override
    public void cancel(String runId) {
        requireSafeRunId(runId);
        cancelledRunIds.add(runId);
        Process process = activeProcesses.get(runId);
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    /** Returns a bounded tail of the local execution log. */
    @Override
    public String readLog(String runId, int maxChars) {
        requireSafeRunId(runId);
        return sanitizeForLog(readTail(logPath(runId), boundedChars(maxChars)));
    }

    /** Returns bounded text for an allowlisted artifact type. */
    @Override
    public String readArtifact(String runId, String artifactType, int maxChars) {
        requireSafeRunId(runId);
        String normalizedType = safe(artifactType).toUpperCase(Locale.ROOT);
        Path path = artifactPath(runId, normalizedType);
        if (path == null) {
            throw new IllegalArgumentException("unsupported evaluation artifact type: " + artifactType);
        }
        return sanitizeForLog(readTail(path, boundedChars(maxChars)));
    }

    /** Builds the fixed argv for the record phase; package-visible for security tests. */
    List<String> recordCommand(EvaluationRun run) {
        EvaluationRunConfig config = run.config();
        config.validate();
        if (config.source() == EvaluationSource.TASK_RUN) {
            throw new IllegalArgumentException("task-run snapshots are recorded by the trusted store collector");
        }
        Path dataset = datasetPath(config.datasetId());
        List<String> command = new ArrayList<>();
        command.add(properties.getPythonExecutable());
        command.add(script("rd_eval_run.py").toString());
        add(command, "--dataset", dataset.toString());
        add(command, "--source", config.source().commandValue());
        add(command, "--run-id", run.runId());
        add(command, "--output-root", outputRoot().toString());
        add(command, "--timeout-seconds", String.valueOf(config.timeoutSeconds()));
        command.add("--overwrite");
        if (!config.environmentId().isBlank()) {
            add(command, "--environment-id", config.environmentId());
        }
        if (config.sampleLimit() > 0) {
            add(command, "--limit", String.valueOf(config.sampleLimit()));
        }
        if (config.source() == EvaluationSource.FIXTURE) {
            Path fixtureRecords = externalFixtureRecordsPath(config.datasetId());
            if (fixtureRecords != null) {
                add(command, "--fixture-records", fixtureRecords.toString());
            }
        }
        if (config.source() == EvaluationSource.RAG_HTTP) {
            add(command, "--base-url", config.baseUrl());
            add(command, "--rag-log", ragLogPath(config.ragLogPath()).toString());
        }
        return List.copyOf(command);
    }

    private List<String> scoreCommand(EvaluationRun run) {
        EvaluationRunConfig config = run.config();
        List<String> command = new ArrayList<>();
        command.add(scorePython(config.judgeProvider()));
        command.add(script("rd_eval_score.py").toString());
        add(command, "--dataset", evaluationDatasetPath(run).toString());
        add(command, "--run-id", run.runId());
        add(command, "--output-root", outputRoot().toString());
        if (config.judgeProvider() == EvaluationJudgeProvider.NONE) {
            command.add("--skip-judge");
        } else {
            add(command, "--judge-provider", config.judgeProvider().commandValue());
            if (config.judgeLimit() > 0) {
                add(command, "--judge-limit", String.valueOf(config.judgeLimit()));
            }
        }
        if (config.strictMissingRecords()) {
            command.add("--strict-missing-records");
        }
        return List.copyOf(command);
    }

    private List<String> reportCommand(EvaluationRun run) {
        List<String> command = new ArrayList<>();
        command.add(properties.getPythonExecutable());
        command.add(script("rd_eval_report.py").toString());
        add(command, "--run-id", run.runId());
        add(command, "--output-root", outputRoot().toString());
        return List.copyOf(command);
    }

    private void recordTaskRun(EvaluationRun run) {
        if (cancelledRunIds.contains(run.runId())) {
            throw new CancellationException("evaluation cancelled before task-run snapshot");
        }
        if (taskRunCollector == null) {
            throw new IllegalStateException("task-run evaluation collector is unavailable");
        }
        TaskRunEvaluationSnapshotCollector.TaskRunEvaluationPayload payload = taskRunCollector.collect(run);
        Path dataset = generatedDatasetPath(run.runId());
        Path records = recordsPath(run.runId());
        try {
            Files.writeString(dataset, objectMapper.writeValueAsString(payload.dataset()) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(records, objectMapper.writeValueAsString(payload.record()) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot write task-run evaluation snapshot", exception);
        }
        appendLog(run.runId(), "[" + Instant.now() + "] [RECORDING] TASK_RUN_SNAPSHOT taskId="
                + run.config().taskId() + " dataset=$OUTPUT/web-runs/" + run.runId()
                + "/task-run-dataset.jsonl records=$OUTPUT/runs/" + run.runId() + ".jsonl\n");
    }

    private List<String> diffCommand(EvaluationRun run) {
        Path baseline = scorePath(run.config().baselineRunId());
        if (!Files.isRegularFile(baseline)) {
            throw new IllegalArgumentException("baseline score artifact does not exist: " + run.config().baselineRunId());
        }
        List<String> command = new ArrayList<>();
        command.add(properties.getPythonExecutable());
        command.add(script("rd_eval_diff.py").toString());
        add(command, "--baseline", baseline.toString());
        add(command, "--candidate", scorePath(run.runId()).toString());
        add(command, "--output", reportDir(run.runId()).resolve("diff.md").toString());
        return List.copyOf(command);
    }

    private void runStep(EvaluationRun run, EvaluationRunStatus phase, List<String> command) {
        if (cancelledRunIds.contains(run.runId())) {
            throw new CancellationException("evaluation cancelled before " + phase);
        }
        appendLog(run.runId(), "\n[" + Instant.now() + "] [" + phase + "] " + sanitizeCommand(command) + "\n");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(properties.getRepositoryRoot().toFile());
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot start local evaluation process", exception);
        }
        activeProcesses.put(run.runId(), process);
        AtomicReference<RuntimeException> readerFailure = new AtomicReference<>();
        Thread reader = Thread.ofVirtual().name("rd-eval-log-" + run.runId()).start(() -> {
            try (BufferedReader buffered = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = buffered.readLine()) != null) {
                    appendLog(run.runId(), line + "\n");
                }
            } catch (IOException exception) {
                readerFailure.set(new IllegalStateException("cannot read local evaluation output", exception));
            }
        });
        try {
            boolean finished = process.waitFor(run.config().timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("evaluation phase timed out after " + run.config().timeoutSeconds() + " seconds: " + phase);
            }
            reader.join(5_000L);
            if (readerFailure.get() != null) {
                throw readerFailure.get();
            }
            if (cancelledRunIds.contains(run.runId())) {
                throw new CancellationException("evaluation cancelled during " + phase);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("evaluation phase exited with code " + process.exitValue() + ": " + phase);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new CancellationException("evaluation execution interrupted");
        } finally {
            activeProcesses.remove(run.runId(), process);
        }
    }

    private EvaluationExecutionResult parseResult(String runId) {
        Path score = scorePath(runId);
        try {
            JsonNode root = objectMapper.readTree(score.toFile());
            int sampleCount = root.path("sample_count").asInt(0);
            Set<String> failedSampleIds = new HashSet<>();
            JsonNode failures = root.path("failures");
            if (failures.isArray()) {
                failures.forEach(failure -> {
                    String id = failure.path("sampleId").asText(failure.path("sample_id").asText(""));
                    failedSampleIds.add(id.isBlank() ? "failure-" + failedSampleIds.size() : id);
                });
            }
            boolean metricsPassed = true;
            JsonNode metrics = root.path("metrics");
            if (metrics.isArray()) {
                for (JsonNode metric : metrics) {
                    if (Set.of("FAIL", "FAILED").contains(metric.path("status").asText())) {
                        metricsPassed = false;
                        break;
                    }
                }
            }
            int failed = Math.min(sampleCount, failedSampleIds.size());
            int passed = Math.max(0, sampleCount - failed);
            var metricsEnvelope = objectMapper.createObjectNode();
            metricsEnvelope.set("metrics", metrics.isArray() ? metrics : objectMapper.createArrayNode());
            JsonNode summary = root.path("summary");
            metricsEnvelope.set("summary", summary.isObject() ? summary : objectMapper.createObjectNode());
            String metricsJson = objectMapper.writeValueAsString(metricsEnvelope);
            return new EvaluationExecutionResult(sampleCount, passed, failed,
                    overallPassed(root, metricsPassed, failed), metricsJson, discoverArtifacts(runId));
        } catch (IOException exception) {
            throw new IllegalStateException("cannot parse evaluation score artifact", exception);
        }
    }

    boolean overallPassed(JsonNode score, boolean metricsPassed, int failedSampleCount) {
        JsonNode summary = score.path("summary");
        if (summary.isObject() && summary.has("overallPassed")) {
            return summary.path("overallPassed").asBoolean(false);
        }
        String gateStatus = summary.path("gateStatus").asText("").toUpperCase(Locale.ROOT);
        if (Set.of("FAILED", "NOT_PASSED", "INCOMPLETE").contains(gateStatus)) {
            return false;
        }
        return failedSampleCount == 0 && metricsPassed;
    }

    private EvaluationDataset datasetDescriptor(Path path) {
        try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            int sampleCount = Math.toIntExact(lines.filter(line -> !line.isBlank()).count());
            String id = path.getFileName().toString();
            EvaluationDatasetKind kind = id.toLowerCase(Locale.ROOT).contains("quality")
                    ? EvaluationDatasetKind.QUALITY_BENCHMARK
                    : EvaluationDatasetKind.SCORER_SMOKE;
            java.util.regex.Matcher versionMatcher = java.util.regex.Pattern
                    .compile("(?i)(?:^|[_-])(v[0-9]+)(?:[._-]|$)")
                    .matcher(id);
            String version = versionMatcher.find() ? versionMatcher.group(1).toLowerCase(Locale.ROOT) : "unversioned";
            return new EvaluationDataset(
                    id, sampleCount, Files.size(path), kind, version, "sha256:" + sha256(path)
            );
        } catch (IOException exception) {
            throw new IllegalStateException("cannot inspect evaluation dataset: " + path.getFileName(), exception);
        }
    }

    private List<EvaluationArtifact> discoverArtifacts(String runId) {
        List<EvaluationArtifact> artifacts = new ArrayList<>();
        for (Map.Entry<String, String> entry : ARTIFACT_FILES.entrySet()) {
            Path path = artifactPath(runId, entry.getKey());
            if (path == null || !Files.isRegularFile(path)) {
                continue;
            }
            try {
                artifacts.add(new EvaluationArtifact(
                        runId + "-" + entry.getKey().toLowerCase(Locale.ROOT),
                        entry.getKey(),
                        outputRoot().relativize(path).toString(),
                        sanitizeForLog(readTail(path, 2_000)),
                        sha256(path),
                        Files.size(path),
                        Files.getLastModifiedTime(path).toMillis()
                ));
            } catch (IOException exception) {
                throw new IllegalStateException("cannot inspect evaluation artifact: " + entry.getKey(), exception);
            }
        }
        return List.copyOf(artifacts);
    }

    private Path artifactPath(String runId, String artifactType) {
        String file = ARTIFACT_FILES.get(artifactType);
        if (file == null) {
            return null;
        }
        Path path = switch (artifactType) {
            case "LOG" -> logPath(runId);
            case "RECORDS" -> recordsPath(runId);
            case "DATASET" -> generatedDatasetPath(runId);
            default -> reportDir(runId).resolve(file);
        };
        return requireWithin(path, outputRoot(), "artifact");
    }

    private Path datasetPath(String datasetId) {
        Path root = properties.resolvedDatasetRoot();
        Path path = requireWithin(root.resolve(datasetId), root, "dataset");
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("evaluation dataset does not exist: " + datasetId);
        }
        return path;
    }

    private Path externalFixtureRecordsPath(String datasetId) {
        String safeDatasetId = safe(datasetId);
        if (!safeDatasetId.endsWith(".jsonl")) {
            return null;
        }
        String recordsName = safeDatasetId.substring(0, safeDatasetId.length() - ".jsonl".length())
                + ".records.jsonl";
        Path root = properties.getRepositoryRoot()
                .resolve("scripts/evaluation/fixtures/records")
                .toAbsolutePath()
                .normalize();
        Path path = requireWithin(root.resolve(recordsName), root, "fixture records");
        return Files.isRegularFile(path) ? path : null;
    }

    private Path evaluationDatasetPath(EvaluationRun run) {
        return run.config().source() == EvaluationSource.TASK_RUN
                ? generatedDatasetPath(run.runId())
                : datasetPath(run.config().datasetId());
    }

    private Path generatedDatasetPath(String runId) {
        requireSafeRunId(runId);
        return requireWithin(outputRoot().resolve("web-runs").resolve(runId).resolve("task-run-dataset.jsonl"),
                outputRoot(), "task-run dataset");
    }

    private Path recordsPath(String runId) {
        requireSafeRunId(runId);
        return requireWithin(outputRoot().resolve("runs").resolve(runId + ".jsonl"), outputRoot(), "records");
    }

    private Path ragLogPath(String relativePath) {
        Path root = properties.resolvedRagLogRoot();
        Path path = requireWithin(root.resolve(relativePath), root, "RAG log");
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("RAG retrieval log does not exist: " + relativePath);
        }
        return path;
    }

    private Path script(String scriptName) {
        Path scripts = properties.getRepositoryRoot().resolve("scripts/evaluation").toAbsolutePath().normalize();
        Path script = requireWithin(scripts.resolve(scriptName), scripts, "evaluation script");
        if (!Files.isRegularFile(script)) {
            throw new IllegalStateException("evaluation script does not exist: " + scriptName);
        }
        return script;
    }

    private Path outputRoot() {
        return properties.resolvedOutputRoot();
    }

    private Path reportDir(String runId) {
        requireSafeRunId(runId);
        return requireWithin(outputRoot().resolve("reports").resolve(runId), outputRoot(), "report directory");
    }

    private Path scorePath(String runId) {
        return reportDir(runId).resolve("_scores.json");
    }

    private Path logPath(String runId) {
        requireSafeRunId(runId);
        return requireWithin(outputRoot().resolve("web-runs").resolve(runId).resolve("execution.log"), outputRoot(), "execution log");
    }

    private void prepareOutputDirectories(String runId) {
        try {
            Files.createDirectories(outputRoot().resolve("runs"));
            Files.createDirectories(reportDir(runId));
            Files.createDirectories(logPath(runId).getParent());
        } catch (IOException exception) {
            throw new IllegalStateException("cannot create evaluation output directories", exception);
        }
    }

    private void appendLog(String runId, String text) {
        try {
            Path path = logPath(runId);
            Files.createDirectories(path.getParent());
            Files.writeString(path, sanitizeForLog(text), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot append evaluation execution log", exception);
        }
    }

    private String scorePython(EvaluationJudgeProvider provider) {
        if (provider == EvaluationJudgeProvider.RAGAS && !properties.getRagasPythonExecutable().isBlank()) {
            return properties.getRagasPythonExecutable();
        }
        return properties.getPythonExecutable();
    }

    private static Path requireWithin(Path candidate, Path root, String field) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException(field + " escapes its configured root");
        }
        return normalized;
    }

    private static void requireSafeRunId(String runId) {
        if (runId == null || !runId.matches("[A-Za-z0-9._-]{1,120}")) {
            throw new IllegalArgumentException("evaluation run id contains unsupported characters");
        }
    }

    private static int boundedChars(int maxChars) {
        return Math.max(100, Math.min(1_000_000, maxChars));
    }

    private static String readTail(Path path, int maxChars) {
        if (!Files.isRegularFile(path)) {
            return "";
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return content.length() <= maxChars ? content : content.substring(content.length() - maxChars);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read evaluation artifact", exception);
        }
    }

    private static String sanitizeCommand(List<String> command) {
        return String.join(" ", command).replaceAll("(?i)(api[_-]?key|token|secret)=\\S+", "$1=<redacted>");
    }

    private String sanitizeForLog(String text) {
        String sanitized = text == null ? "" : text;
        sanitized = sanitized.replace(outputRoot().toString(), "$OUTPUT");
        sanitized = sanitized.replace(properties.getRepositoryRoot().toString(), "$REPO");
        return sanitized.replaceAll("(?i)(api[_-]?key|token|secret)=\\S+", "$1=<redacted>");
    }

    private static void add(List<String> command, String flag, String value) {
        command.add(flag);
        command.add(value);
    }

    private static String sha256(Path path) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException("cannot hash evaluation artifact", exception);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static Map<String, String> artifactFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("LOG", "execution.log");
        files.put("DATASET", "task-run-dataset.jsonl");
        files.put("RECORDS", "records.jsonl");
        files.put("SCORES", "_scores.json");
        files.put("REPORT", "report.md");
        files.put("SAMPLES", "per_sample.csv");
        files.put("FAILURES", "failures.jsonl");
        files.put("DIFF", "diff.md");
        return Map.copyOf(files);
    }
}
