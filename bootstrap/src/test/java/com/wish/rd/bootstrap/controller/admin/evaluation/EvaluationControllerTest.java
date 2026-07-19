package com.wish.rd.bootstrap.controller.admin.evaluation;

import com.wish.rd.engine.evaluation.EvaluationCatalogPort;
import com.wish.rd.engine.evaluation.EvaluationExecutionPort;
import com.wish.rd.engine.evaluation.EvaluationOutputReaderPort;
import com.wish.rd.engine.evaluation.EvaluationRunEngine;
import com.wish.rd.engine.evaluation.impl.InMemoryEvaluationRunStore;
import com.wish.rd.engine.evaluation.model.EvaluationCapabilities;
import com.wish.rd.engine.evaluation.model.EvaluationDataset;
import com.wish.rd.engine.evaluation.model.EvaluationArtifact;
import com.wish.rd.engine.evaluation.model.EvaluationExecutionResult;
import com.wish.rd.engine.evaluation.model.EvaluationJudgeProvider;
import com.wish.rd.engine.evaluation.model.EvaluationRun;
import com.wish.rd.engine.evaluation.model.EvaluationRunConfig;
import com.wish.rd.engine.evaluation.model.EvaluationRunStatus;
import com.wish.rd.engine.evaluation.model.EvaluationSource;
import com.wish.rd.bootstrap.evaluation.EvaluationExecutionConfiguration;
import com.wish.rd.bootstrap.evaluation.EvaluationProperties;
import com.wish.rd.bootstrap.evaluation.impl.LocalPythonEvaluationExecutor;
import com.wish.rd.engine.evaluation.taskrun.TaskRunEvaluationSnapshotCollector;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.Assertions;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvaluationControllerTest {

    @Test
    void shouldRegisterFallbackStoreSchedulerEngineAndController() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(SnowflakeIdGenerator.class, SnowflakeIdGenerator::defaultGenerator)
                .withUserConfiguration(
                        EvaluationProperties.class,
                        LocalPythonEvaluationExecutor.class,
                        EvaluationExecutionConfiguration.class,
                        EvaluationRunEngine.class,
                        EvaluationController.class)
                .run(context -> {
                    Assertions.assertThat(context).hasSingleBean(EvaluationRunEngine.class);
                    Assertions.assertThat(context).hasSingleBean(EvaluationController.class);
                    Assertions.assertThat(context).hasSingleBean(com.wish.rd.engine.evaluation.EvaluationRunStore.class);
                    Assertions.assertThat(context).hasSingleBean(com.wish.rd.engine.evaluation.EvaluationTaskSchedulerPort.class);
                });
    }

    @Test
    void shouldExposeCapabilitiesAndCreateListDetailControlRun() throws Exception {
        InMemoryEvaluationRunStore store = new InMemoryEvaluationRunStore();
        List<Runnable> queuedTasks = new ArrayList<>();
        AtomicInteger ids = new AtomicInteger(100);
        EvaluationExecutionPort executionPort = new NoopEvaluationExecutor();
        EvaluationRunEngine engine = new EvaluationRunEngine(
                store, executionPort, queuedTasks::add, () -> String.valueOf(ids.incrementAndGet()), () -> 1_000L);
        EvaluationCatalogPort catalog = () -> new EvaluationCapabilities(
                true,
                List.of(EvaluationSource.FIXTURE, EvaluationSource.RAG_HTTP),
                List.of(EvaluationJudgeProvider.NONE, EvaluationJudgeProvider.RAGAS,
                        EvaluationJudgeProvider.OPENAI_COMPATIBLE),
                List.of(new EvaluationDataset("rd_eval_smoke.jsonl", 6, 4096L)),
                "http://127.0.0.1:18080",
                10_000,
                3_600
        );
        EvaluationOutputReaderPort output = new EvaluationOutputReaderPort() {
            @Override
            public String readLog(String runId, int maxChars) {
                return "[RECORDING] fixture\n[SCORING] metrics";
            }

            @Override
            public String readArtifact(String runId, String artifactType, int maxChars) {
                return "# RD Eval Report";
            }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new EvaluationController(engine, catalog, output)).build();

        mvc.perform(get("/admin/evaluations/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasets[0].id").value("rd_eval_smoke.jsonl"))
                .andExpect(jsonPath("$.sources[1]").value("RAG_HTTP"));

        String body = """
                {
                  "name":"Web smoke",
                  "datasetId":"rd_eval_smoke.jsonl",
                  "source":"FIXTURE",
                  "environmentId":"web",
                  "sampleLimit":0,
                  "baseUrl":"http://127.0.0.1:18080",
                  "ragLogPath":"rag-retrieval.jsonl",
                  "timeoutSeconds":30,
                  "judgeProvider":"NONE",
                  "judgeLimit":0,
                  "strictMissingRecords":false,
                  "baselineRunId":""
                }
                """;
        mvc.perform(post("/admin/evaluations/runs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value("101"))
                .andExpect(jsonPath("$.status").value("QUEUED"));
        mvc.perform(get("/admin/evaluations/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].name").value("Web smoke"))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/admin/evaluations/runs/101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.datasetId").value("rd_eval_smoke.jsonl"));
        mvc.perform(get("/admin/evaluations/runs/101/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].toStatus").value("QUEUED"));
        mvc.perform(get("/admin/evaluations/runs/101/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("SCORING")));
        mvc.perform(get("/admin/evaluations/runs/101/artifacts/REPORT/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("RD Eval Report")));
        mvc.perform(post("/admin/evaluations/runs/101/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/admin/evaluations/runs/101/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value("102"))
                .andExpect(jsonPath("$.attemptNo").value(2))
                .andExpect(jsonPath("$.parentRunId").value("101"));
    }

    @Test
    void shouldPageEvaluationHistoryOnTheBackend() throws Exception {
        InMemoryEvaluationRunStore store = new InMemoryEvaluationRunStore();
        AtomicInteger ids = new AtomicInteger(700);
        AtomicLong clock = new AtomicLong(10_000L);
        EvaluationRunEngine engine = new EvaluationRunEngine(
                store, new NoopEvaluationExecutor(), task -> { },
                () -> String.valueOf(ids.incrementAndGet()), clock::incrementAndGet);
        EvaluationCatalogPort catalog = () -> new EvaluationCapabilities(
                true,
                List.of(EvaluationSource.FIXTURE),
                List.of(EvaluationJudgeProvider.NONE),
                List.of(new EvaluationDataset("rd_eval_smoke.jsonl", 6, 4096L)),
                "http://127.0.0.1:18080",
                10_000,
                3_600
        );
        EvaluationOutputReaderPort output = new EvaluationOutputReaderPort() {
            public String readLog(String runId, int maxChars) { return ""; }
            public String readArtifact(String runId, String type, int maxChars) { return ""; }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new EvaluationController(engine, catalog, output)).build();

        for (String name : List.of("最早的评测", "中间的评测", "最新的评测")) {
            engine.start(new EvaluationRunConfig(
                    name, "rd_eval_smoke.jsonl", EvaluationSource.FIXTURE, "web", 0,
                    "", "", 30, EvaluationJudgeProvider.NONE, 0, false, "", ""));
        }

        mvc.perform(get("/admin/evaluations/runs")
                        .param("page", "2")
                        .param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].runId").value("702"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.pageSize").value(1))
                .andExpect(jsonPath("$.pages").value(3));
    }

    @Test
    void shouldMapInvalidConfigurationUnknownRunAndIllegalTransition() throws Exception {
        InMemoryEvaluationRunStore store = new InMemoryEvaluationRunStore();
        AtomicInteger ids = new AtomicInteger(200);
        EvaluationRunEngine engine = new EvaluationRunEngine(
                store, new NoopEvaluationExecutor(), task -> { },
                () -> String.valueOf(ids.incrementAndGet()), () -> 2_000L);
        EvaluationCatalogPort catalog = () -> new EvaluationCapabilities(
                true, List.of(EvaluationSource.FIXTURE), List.of(EvaluationJudgeProvider.NONE),
                List.of(), "http://127.0.0.1:18080", 10_000, 3_600);
        EvaluationOutputReaderPort output = new EvaluationOutputReaderPort() {
            public String readLog(String runId, int maxChars) { return ""; }
            public String readArtifact(String runId, String type, int maxChars) { return ""; }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new EvaluationController(engine, catalog, output)).build();

        mvc.perform(get("/admin/evaluations/runs/999"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/admin/evaluations/runs").param("page", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/evaluations/runs").param("pageSize", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/evaluations/runs").param("datasetKind", "UNTRUSTED"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/evaluations/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"unsafe","datasetId":"../secret.jsonl","source":"RAG_HTTP",
                                "baseUrl":"https://example.com","ragLogPath":"../secret.log","timeoutSeconds":30,
                                "judgeProvider":"NONE"}
                                """))
                .andExpect(status().isBadRequest());

        String valid = """
                {"name":"valid","datasetId":"rd_eval_smoke.jsonl","source":"FIXTURE",
                "baseUrl":"http://127.0.0.1:18080","ragLogPath":"rag-retrieval.jsonl","timeoutSeconds":30,
                "judgeProvider":"NONE"}
                """;
        mvc.perform(post("/admin/evaluations/runs").contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isAccepted());
        mvc.perform(post("/admin/evaluations/runs/201/retry"))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldCreateTaskRunEvaluationFromTrustedTaskTarget() throws Exception {
        InMemoryEvaluationRunStore store = new InMemoryEvaluationRunStore();
        AtomicInteger ids = new AtomicInteger(300);
        EvaluationRunEngine engine = new EvaluationRunEngine(
                store, new NoopEvaluationExecutor(), task -> { },
                () -> String.valueOf(ids.incrementAndGet()), () -> 3_000L);
        EvaluationCatalogPort catalog = () -> new EvaluationCapabilities(
                true, List.of(EvaluationSource.TASK_RUN), List.of(EvaluationJudgeProvider.NONE),
                List.of(), "http://127.0.0.1:18080", 10_000, 3_600);
        EvaluationOutputReaderPort output = new EvaluationOutputReaderPort() {
            public String readLog(String runId, int maxChars) { return ""; }
            public String readArtifact(String runId, String type, int maxChars) { return ""; }
        };
        TaskRunEvaluationSnapshotCollector collector = mock(TaskRunEvaluationSnapshotCollector.class);
        when(collector.target("7480495920010891264")).thenReturn(
                new TaskRunEvaluationSnapshotCollector.TaskRunEvaluationTarget(
                        "7480495920010891264", "REQUIREMENT", "开发管理后台商品管理功能", "MERGED"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new EvaluationController(engine, catalog, output, collector)).build();

        mvc.perform(post("/admin/rd-tasks/7480495920010891264/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"judgeProvider\":\"NONE\",\"timeoutSeconds\":90}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value("301"))
                .andExpect(jsonPath("$.config.source").value("TASK_RUN"))
                .andExpect(jsonPath("$.config.taskId").value("7480495920010891264"))
                .andExpect(jsonPath("$.config.datasetId").value("task-run.generated.jsonl"));
        mvc.perform(post("/admin/rd-tasks/not-a-number/evaluations")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnPersistedArtifactPreviewWhenTheOriginalOutputRootIsNoLongerAvailable() throws Exception {
        InMemoryEvaluationRunStore store = new InMemoryEvaluationRunStore();
        EvaluationRunEngine engine = new EvaluationRunEngine(
                store, new NoopEvaluationExecutor(), task -> { }, () -> "501", () -> 5_000L);
        EvaluationRun run = engine.start(new EvaluationRunConfig(
                "isolated output", "rd_eval_smoke.jsonl", EvaluationSource.FIXTURE, "local", 0,
                "", "", 30, EvaluationJudgeProvider.NONE, 0, false, "", ""));
        store.appendArtifacts(run.runId(), List.of(
                new EvaluationArtifact("artifact-log", "LOG", "web-runs/501/execution.log",
                        "[RECORDING] persisted preview", "hash-log", 42L, 5_000L),
                new EvaluationArtifact("artifact-records", "RECORDS", "runs/501.jsonl",
                        "{\"sample_id\":\"TASK-501\"}", "hash-records", 42L, 5_000L)));
        EvaluationCatalogPort catalog = () -> new EvaluationCapabilities(
                true, List.of(EvaluationSource.FIXTURE), List.of(EvaluationJudgeProvider.NONE),
                List.of(), "http://127.0.0.1:18080", 10_000, 3_600);
        EvaluationOutputReaderPort unavailableOutput = new EvaluationOutputReaderPort() {
            public String readLog(String runId, int maxChars) { return ""; }
            public String readArtifact(String runId, String type, int maxChars) { return ""; }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new EvaluationController(engine, catalog, unavailableOutput)).build();

        mvc.perform(get("/admin/evaluations/runs/501/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("persisted preview")))
                .andExpect(jsonPath("$.source").value("PERSISTED_PREVIEW"));
        mvc.perform(get("/admin/evaluations/runs/501/artifacts/RECORDS/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("TASK-501")))
                .andExpect(jsonPath("$.source").value("PERSISTED_PREVIEW"));
    }

    private static final class NoopEvaluationExecutor implements EvaluationExecutionPort {
        @Override
        public EvaluationExecutionResult execute(
                com.wish.rd.engine.evaluation.model.EvaluationRun run,
                com.wish.rd.engine.evaluation.EvaluationProgressListener listener
        ) {
            listener.phase(EvaluationRunStatus.SCORING, "score");
            listener.phase(EvaluationRunStatus.REPORTING, "report");
            return new EvaluationExecutionResult(0, 0, 0, true, "[]", List.of());
        }

        @Override
        public void cancel(String runId) {
        }
    }
}
