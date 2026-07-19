package com.wish.rd.bootstrap.evaluation.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.evaluation.EvaluationProperties;
import com.wish.rd.engine.evaluation.model.EvaluationExecutionResult;
import com.wish.rd.engine.evaluation.model.EvaluationDatasetKind;
import com.wish.rd.engine.evaluation.model.EvaluationJudgeProvider;
import com.wish.rd.engine.evaluation.model.EvaluationRun;
import com.wish.rd.engine.evaluation.model.EvaluationRunConfig;
import com.wish.rd.engine.evaluation.model.EvaluationRunStatus;
import com.wish.rd.engine.evaluation.model.EvaluationSource;
import com.wish.rd.engine.evaluation.taskrun.TaskRunEvaluationSnapshotCollector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalPythonEvaluationExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldClassifyScorerSmokeAndExposeDatasetProvenance() throws Exception {
        LocalPythonEvaluationExecutor executor = new LocalPythonEvaluationExecutor(
                properties(repositoryRoot(), tempDir.resolve("catalog-output")), new ObjectMapper());

        var smoke = executor.capabilities().datasets().stream()
                .filter(dataset -> dataset.id().equals("rd_eval_smoke.jsonl"))
                .findFirst()
                .orElseThrow();

        assertEquals(EvaluationDatasetKind.SCORER_SMOKE, smoke.kind());
        assertEquals("unversioned", smoke.version());
        assertTrue(smoke.sha256().startsWith("sha256:"));
    }

    @Test
    void shouldClassifyVersionedQualityBenchmarkAndUseSeparateFixtureRecords() throws Exception {
        LocalPythonEvaluationExecutor executor = new LocalPythonEvaluationExecutor(
                properties(repositoryRoot(), tempDir.resolve("quality-catalog-output")), new ObjectMapper());

        var quality = executor.capabilities().datasets().stream()
                .filter(dataset -> dataset.id().equals("rd_eval_quality_v1.jsonl"))
                .findFirst()
                .orElseThrow();
        EvaluationRunConfig qualityConfig = new EvaluationRunConfig(
                "quality baseline", quality.id(), EvaluationSource.FIXTURE, "local", 0,
                "http://127.0.0.1:18080", "rag-retrieval.jsonl", 30,
                EvaluationJudgeProvider.NONE, 0, false, "");
        List<String> command = executor.recordCommand(
                EvaluationRun.created("quality-v1", qualityConfig, 1, "", 1L));

        assertEquals(EvaluationDatasetKind.QUALITY_BENCHMARK, quality.kind());
        assertEquals("v1", quality.version());
        assertEquals(48, quality.sampleCount());
        assertTrue(command.contains("--fixture-records"));
        assertTrue(command.stream().anyMatch(value -> value.endsWith(
                "scripts/evaluation/fixtures/records/rd_eval_quality_v1.records.jsonl")));
    }

    @Test
    void shouldRunExistingFixturePipelineAndExposeSafeArtifacts() throws Exception {
        Path repositoryRoot = repositoryRoot();
        EvaluationProperties properties = properties(repositoryRoot, tempDir.resolve("output"));
        LocalPythonEvaluationExecutor executor = new LocalPythonEvaluationExecutor(properties, new ObjectMapper());
        List<EvaluationRunStatus> phases = new ArrayList<>();
        EvaluationRun run = EvaluationRun.created("web-fixture-1", config(EvaluationSource.FIXTURE), 1, "", 10L)
                .withStatus(EvaluationRunStatus.QUEUED, "queued", "", "", 11L)
                .withStatus(EvaluationRunStatus.RECORDING, "record", "", "", 12L);

        EvaluationExecutionResult result = executor.execute(run, (phase, message) -> phases.add(phase));

        assertEquals(6, result.sampleCount());
        assertEquals(6, result.passedSampleCount());
        assertEquals(0, result.failedSampleCount());
        assertTrue(result.overallPassed());
        assertTrue(result.metricsJson().contains("evidence_hit@5"));
        var metricsPayload = new ObjectMapper().readTree(result.metricsJson());
        assertTrue(metricsPayload.isObject());
        assertTrue(metricsPayload.path("metrics").isArray());
        assertTrue(metricsPayload.path("summary").isObject());
        assertEquals(List.of(EvaluationRunStatus.SCORING, EvaluationRunStatus.REPORTING), phases);
        assertTrue(result.artifacts().stream().anyMatch(artifact -> artifact.artifactType().equals("SCORES")));
        assertTrue(result.artifacts().stream().anyMatch(artifact -> artifact.artifactType().equals("REPORT")));
        assertTrue(result.artifacts().stream().allMatch(artifact -> !Path.of(artifact.artifactUri()).isAbsolute()));

        String log = executor.readLog(run.runId(), 20_000);
        assertTrue(log.contains("RECORDING"));
        assertTrue(log.contains("SCORING"));
        assertTrue(log.contains("REPORTING"));
        assertFalse(log.contains("--api-key"));
        assertFalse(log.contains(repositoryRoot.toString()));
        assertFalse(log.contains(properties.resolvedOutputRoot().toString()));
        assertTrue(log.contains("$REPO"));
        assertTrue(log.contains("$OUTPUT"));
        assertTrue(executor.readArtifact(run.runId(), "REPORT", 20_000).contains("RD Eval Report"));
    }

    @Test
    void shouldBuildAllowlistedArgumentArrayWithoutShellInterpolation() throws Exception {
        Path repositoryRoot = repositoryRoot();
        EvaluationProperties properties = properties(repositoryRoot, tempDir.resolve("output"));
        LocalPythonEvaluationExecutor executor = new LocalPythonEvaluationExecutor(properties, new ObjectMapper());
        EvaluationRun run = EvaluationRun.created("web-http-1", config(EvaluationSource.RAG_HTTP), 1, "", 10L);

        List<String> command = executor.recordCommand(run);

        assertEquals("python3", command.getFirst());
        assertTrue(command.stream().anyMatch(value -> value.endsWith("scripts/evaluation/rd_eval_run.py")));
        assertTrue(command.contains("--source"));
        assertTrue(command.contains("rag-http"));
        assertTrue(command.contains("http://127.0.0.1:18080"));
        assertFalse(command.contains("sh"));
        assertFalse(command.contains("-c"));
    }

    @Test
    void shouldRejectDatasetAndLogPathOutsideConfiguredRoots() throws Exception {
        Path repositoryRoot = repositoryRoot();
        EvaluationProperties properties = properties(repositoryRoot, tempDir.resolve("output"));
        LocalPythonEvaluationExecutor executor = new LocalPythonEvaluationExecutor(properties, new ObjectMapper());
        EvaluationRunConfig missingDataset = new EvaluationRunConfig(
                "missing", "not-present.jsonl", EvaluationSource.FIXTURE, "local", 0,
                "http://127.0.0.1:18080", "logs/rag-retrieval.jsonl", 30,
                EvaluationJudgeProvider.NONE, 0, false, "");
        EvaluationRunConfig traversal = new EvaluationRunConfig(
                "traversal", "rd_eval_smoke.jsonl", EvaluationSource.RAG_HTTP, "local", 0,
                "http://127.0.0.1:18080", "../outside.log", 30,
                EvaluationJudgeProvider.NONE, 0, false, "");

        assertThrows(IllegalArgumentException.class,
                () -> executor.recordCommand(EvaluationRun.created("missing", missingDataset, 1, "", 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> executor.recordCommand(EvaluationRun.created("traversal", traversal, 1, "", 1L)));
        assertThrows(IllegalArgumentException.class, () -> executor.readArtifact("../other", "REPORT", 100));
    }

    @Test
    void shouldDiscoverRepositoryRootWhenSpringBootRunsFromBootstrapModule() throws Exception {
        Path root = repositoryRoot();
        EvaluationProperties properties = new EvaluationProperties();

        properties.setRepositoryRoot(root.resolve("bootstrap"));

        assertEquals(root, properties.getRepositoryRoot());
    }

    @Test
    void shouldRecordTaskRunSnapshotWithoutInvokingTheHttpOrFixtureRecorder() throws Exception {
        Path repositoryRoot = repositoryRoot();
        EvaluationProperties properties = properties(repositoryRoot, tempDir.resolve("task-output"));
        TaskRunEvaluationSnapshotCollector collector = mock(TaskRunEvaluationSnapshotCollector.class);
        LocalPythonEvaluationExecutor executor = new LocalPythonEvaluationExecutor(
                properties, new ObjectMapper(), collector);
        EvaluationRunConfig taskConfig = new EvaluationRunConfig(
                "task run", "task-run.generated.jsonl", EvaluationSource.TASK_RUN, "local-task", 0,
                "", "", 30, EvaluationJudgeProvider.NONE, 0, false, "", "7480495920010891264");
        EvaluationRun run = EvaluationRun.created("web-task-1", taskConfig, 1, "", 10L)
                .withStatus(EvaluationRunStatus.QUEUED, "queued", "", "", 11L)
                .withStatus(EvaluationRunStatus.RECORDING, "record", "", "", 12L);
        Map<String, Object> dataset = Map.of(
                "sample_id", "TASK-7480495920010891264",
                "suite", "task-run",
                "task_run_gold", Map.of("expectedRoles", List.of(), "expectedRetrievalConsumers", List.of()));
        Map<String, Object> record = Map.of(
                "run_id", run.runId(),
                "sample_id", "TASK-7480495920010891264",
                "task_id", taskConfig.taskId(),
                "status", "RECORDED",
                "suite", "task-run",
                "stages", Map.of(),
                "task_run", Map.of(
                        "taskStatus", "MERGED",
                        "timeline", List.of(Map.of("status", "MERGED")),
                        "testEvidenceCount", 1,
                        "pullRequestUrl", "https://github.com/example/repo/pull/1",
                        "baseBranch", "main",
                        "workBranch", "rd/task-run-evaluation",
                        "commitSha", "0123456789abcdef0123456789abcdef01234567"));
        when(collector.collect(run)).thenReturn(
                new TaskRunEvaluationSnapshotCollector.TaskRunEvaluationPayload(dataset, record));

        EvaluationExecutionResult result = executor.execute(run, (phase, message) -> { });

        assertEquals(1, result.sampleCount());
        assertTrue(result.overallPassed());
        assertTrue(executor.readArtifact(run.runId(), "RECORDS", 20_000).contains(taskConfig.taskId()));
        assertTrue(executor.readArtifact(run.runId(), "DATASET", 20_000).contains("task_run_gold"));
        String log = executor.readLog(run.runId(), 20_000);
        assertTrue(log.contains("TASK_RUN_SNAPSHOT"));
        assertFalse(log.contains("rd_eval_run.py"));
    }

    @Test
    void shouldTreatIncompleteJudgeSummaryAsNotPassed() throws Exception {
        LocalPythonEvaluationExecutor executor = new LocalPythonEvaluationExecutor(
                properties(repositoryRoot(), tempDir.resolve("judge-output")), new ObjectMapper());
        var score = new ObjectMapper().readTree("""
                {
                  "metrics": [{"name":"answer_relevancy","status":"PASS","value":1.0}],
                  "failures": [],
                  "summary": {
                    "overallStatus": "INCOMPLETE",
                    "overallPassed": false,
                    "gateStatus": "INCOMPLETE",
                    "judgeStatus": "FAILED"
                  }
                }
                """);

        assertFalse(executor.overallPassed(score, true, 0));
    }

    private static EvaluationProperties properties(Path repositoryRoot, Path outputRoot) {
        EvaluationProperties properties = new EvaluationProperties();
        properties.setRepositoryRoot(repositoryRoot);
        properties.setOutputRoot(outputRoot);
        properties.setDatasetRoot(Path.of("scripts/evaluation/datasets"));
        properties.setRagLogRoot(Path.of("logs"));
        properties.setPythonExecutable("python3");
        return properties;
    }

    private static EvaluationRunConfig config(EvaluationSource source) {
        return new EvaluationRunConfig(
                "Web fixture",
                "rd_eval_smoke.jsonl",
                source,
                "web-test",
                0,
                "http://127.0.0.1:18080",
                "rag-retrieval.jsonl",
                30,
                EvaluationJudgeProvider.NONE,
                0,
                false,
                ""
        );
    }

    private static Path repositoryRoot() throws Exception {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (int index = 0; index < 4 && current != null; index++) {
            if (Files.isRegularFile(current.resolve("scripts/evaluation/rd_eval_run.py"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("RD-Bot repository root not found from test working directory");
    }
}
