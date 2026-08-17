package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.requirement.verify.impl.InMemoryHostVerificationStore;
import com.wish.rd.engine.requirement.verify.model.HostVerificationArtifact;
import com.wish.rd.engine.requirement.verify.model.HostVerificationRun;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStep;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStepName;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStepStatus;
import com.wish.rd.rag.ingestion.impl.InMemoryObjectStorageService;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Frozen admin HTTP contract for host BUILD/STATIC verification.
 *
 * <p>Standalone controller + in-memory store; no Spring Boot context.
 */
class HostVerificationControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> LIST_FIELDS = Set.of("taskId", "runs");
    private static final Set<String> RUN_FIELDS = Set.of(
            "runId",
            "codingStageRunId",
            "parentRunId",
            "attemptNo",
            "status",
            "docsOnly",
            "failureCategory",
            "errorMessage",
            "remediationCount",
            "createdAtEpochMillis",
            "startedAtEpochMillis",
            "finishedAtEpochMillis",
            "steps",
            "artifacts"
    );
    private static final Set<String> STEP_FIELDS = Set.of(
            "step", "status", "commands", "exitCode", "durationMillis", "logArtifactId", "errorMessage");
    private static final Set<String> ARTIFACT_FIELDS = Set.of(
            "artifactId",
            "type",
            "name",
            "relativePath",
            "contentType",
            "sizeBytes",
            "sha256",
            "previewable",
            "contentUrl"
    );

    private RagStreamTaskRegistry registry;
    private InMemoryHostVerificationStore store;
    private InMemoryObjectStorageService objectStorage;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registry = RagStreamTaskRegistry.inMemory();
        store = new InMemoryHostVerificationStore();
        objectStorage = new InMemoryObjectStorageService();
        mockMvc = MockMvcBuilders.standaloneSetup(
                new HostVerificationController(registry, store, objectStorage)).build();
    }

    @Test
    void shouldListEmptyRunsForExistingTask() throws Exception {
        RdRequirementTask task = createTask("空验证任务");

        MvcResult result = mockMvc.perform(get("/admin/rd-tasks/{taskId}/host-verifications", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(task.taskId()))
                .andExpect(jsonPath("$.runs").isEmpty())
                .andReturn();

        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals(LIST_FIELDS, fieldNames(body));
    }

    @Test
    void shouldListFailedBuildAndSkippedStatic() throws Exception {
        RdRequirementTask task = createTask("失败验证任务");
        seedFailedBuild(task.taskId(), "2", "secret-build-log");

        MvcResult result = mockMvc.perform(get("/admin/rd-tasks/{taskId}/host-verifications", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(task.taskId()))
                .andExpect(jsonPath("$.runs.length()").value(1))
                .andExpect(jsonPath("$.runs[0].runId").value("2"))
                .andExpect(jsonPath("$.runs[0].codingStageRunId").value("3"))
                .andExpect(jsonPath("$.runs[0].parentRunId").value(""))
                .andExpect(jsonPath("$.runs[0].attemptNo").value(1))
                .andExpect(jsonPath("$.runs[0].status").value("FAILED_NEEDS_HUMAN"))
                .andExpect(jsonPath("$.runs[0].docsOnly").value(false))
                .andExpect(jsonPath("$.runs[0].failureCategory").value("PRODUCT_DEFECT"))
                .andExpect(jsonPath("$.runs[0].errorMessage").value("npm run build exited 1"))
                .andExpect(jsonPath("$.runs[0].remediationCount").value(0))
                .andExpect(jsonPath("$.runs[0].steps.length()").value(2))
                .andExpect(jsonPath("$.runs[0].steps[0].step").value("BUILD"))
                .andExpect(jsonPath("$.runs[0].steps[0].status").value("FAILED"))
                .andExpect(jsonPath("$.runs[0].steps[0].commands[0]").value("npm ci"))
                .andExpect(jsonPath("$.runs[0].steps[0].exitCode").value(1))
                .andExpect(jsonPath("$.runs[0].steps[0].logArtifactId").value("9"))
                .andExpect(jsonPath("$.runs[0].steps[1].step").value("STATIC"))
                .andExpect(jsonPath("$.runs[0].steps[1].status").value("SKIPPED"))
                .andExpect(jsonPath("$.runs[0].steps[1].exitCode").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.runs[0].artifacts[0].artifactId").value("9"))
                .andExpect(jsonPath("$.runs[0].artifacts[0].type").value("VERIFY_BUILD_LOG"))
                .andExpect(jsonPath("$.runs[0].artifacts[0].name").value("build.log"))
                .andExpect(jsonPath("$.runs[0].artifacts[0].previewable").value(true))
                .andExpect(jsonPath("$.runs[0].artifacts[0].contentUrl")
                        .value("/admin/rd-tasks/" + task.taskId() + "/host-verifications/2/evidence/9/content"))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        assertFalse(json.contains("s3://"), json);
        assertFalse(json.contains("objectUri"), json);
        JsonNode body = OBJECT_MAPPER.readTree(json);
        assertEquals(LIST_FIELDS, fieldNames(body));
        JsonNode run = body.get("runs").get(0);
        assertEquals(RUN_FIELDS, fieldNames(run));
        assertEquals(STEP_FIELDS, fieldNames(run.get("steps").get(0)));
        assertEquals(STEP_FIELDS, fieldNames(run.get("steps").get(1)));
        assertTrue(run.get("steps").get(1).get("exitCode").isNull());
        assertEquals(ARTIFACT_FIELDS, fieldNames(run.get("artifacts").get(0)));
    }

    @Test
    void shouldReturnRunDetail() throws Exception {
        RdRequirementTask task = createTask("详情验证任务");
        seedFailedBuild(task.taskId(), "2", "detail-log");

        MvcResult result = mockMvc.perform(get(
                        "/admin/rd-tasks/{taskId}/host-verifications/{runId}", task.taskId(), "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("2"))
                .andExpect(jsonPath("$.status").value("FAILED_NEEDS_HUMAN"))
                .andExpect(jsonPath("$.steps[1].status").value("SKIPPED"))
                .andReturn();

        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals(RUN_FIELDS, fieldNames(body));
        assertFalse(body.has("taskId"));
    }

    @Test
    void shouldStreamOwnedEvidenceContent() throws Exception {
        RdRequirementTask task = createTask("证据任务");
        seedFailedBuild(task.taskId(), "2", "owned-log-bytes");

        mockMvc.perform(get(
                        "/admin/rd-tasks/{taskId}/host-verifications/{runId}/evidence/{artifactId}/content",
                        task.taskId(),
                        "2",
                        "9"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(header().string("X-Content-SHA256", "abc123secret"))
                .andExpect(content().string("owned-log-bytes"));
    }

    @Test
    void shouldReturn404WhenEvidenceTaskIdDoesNotMatch() throws Exception {
        RdRequirementTask owner = createTask("属主任务");
        RdRequirementTask other = createTask("其它任务");
        seedFailedBuild(owner.taskId(), "2", "must-not-leak");

        MvcResult result = mockMvc.perform(get(
                        "/admin/rd-tasks/{taskId}/host-verifications/{runId}/evidence/{artifactId}/content",
                        other.taskId(),
                        "2",
                        "9"))
                .andExpect(status().isNotFound())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("must-not-leak"), body);
    }

    @Test
    void shouldReturn404ForUnknownRun() throws Exception {
        RdRequirementTask task = createTask("未知轮次");

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/host-verifications/{runId}", task.taskId(), "404"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(
                        "/admin/rd-tasks/{taskId}/host-verifications/{runId}/evidence/{artifactId}/content",
                        task.taskId(),
                        "404",
                        "9"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturn404ForUnknownTask() throws Exception {
        mockMvc.perform(get("/admin/rd-tasks/{taskId}/host-verifications", "9999999999999999"))
                .andExpect(status().isNotFound());
    }

    private RdRequirementTask createTask(String title) {
        return registry.createRequirementTask(new CreateRequirementTaskCommand(
                title,
                "P1",
                "https://github.com/acme/web.git",
                "",
                "",
                "main",
                "页面可用",
                List.of("真实浏览器通过"),
                false
        ));
    }

    private void seedFailedBuild(String taskId, String runId, String logText) {
        byte[] bytes = logText.getBytes(StandardCharsets.UTF_8);
        String objectUri = objectStorage.upload(
                "rd-qa-evidence",
                new ByteArrayInputStream(bytes),
                bytes.length,
                "build.log",
                "text/plain"
        ).url();
        store.create(new HostVerificationRun(
                runId,
                taskId,
                "3",
                "",
                1,
                HostVerificationStatus.FAILED_NEEDS_HUMAN,
                false,
                "PRODUCT_DEFECT",
                "npm run build exited 1",
                0,
                1_000L,
                1_100L,
                1_200L
        ));
        store.saveStep(new HostVerificationStep(
                runId,
                HostVerificationStepName.BUILD,
                HostVerificationStepStatus.FAILED,
                List.of("npm ci", "npm test", "npm run build"),
                1,
                12_000L,
                "9",
                "Type error"
        ));
        store.saveStep(new HostVerificationStep(
                runId,
                HostVerificationStepName.STATIC,
                HostVerificationStepStatus.SKIPPED,
                List.of(),
                null,
                0L,
                "",
                "not executed because BUILD failed"
        ));
        store.appendArtifact(new HostVerificationArtifact(
                "9",
                taskId,
                runId,
                "VERIFY_BUILD_LOG",
                "verify-evidence/build/build.log",
                objectUri,
                "text/plain",
                bytes.length,
                "abc123secret",
                1_200L
        ));
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        Iterator<String> iterator = node.fieldNames();
        while (iterator.hasNext()) {
            names.add(iterator.next());
        }
        return names;
    }
}
