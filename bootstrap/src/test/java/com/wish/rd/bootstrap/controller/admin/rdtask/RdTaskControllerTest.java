package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.bootstrap.executor.impl.QaEvidenceRetentionService;
import com.wish.rd.engine.bugfix.RdBotFixEngine;
import com.wish.rd.engine.bugfix.model.RdBotFixCommand;
import com.wish.rd.engine.bugfix.model.RdBotFixResult;
import com.wish.rd.engine.requirement.RequirementDeliveryEngine;
import com.wish.rd.engine.requirement.model.RequirementExecutionResult;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublication;
import com.wish.rd.engine.ticket.RdTaskRestartEngine;
import com.wish.rd.engine.ticket.model.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.model.RepairTicketMessage;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.impl.InMemoryTaskMaterialStore;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.ingestion.ObjectStorageService;
import com.wish.rd.rag.ingestion.impl.InMemoryObjectStorageService;
import com.wish.rd.rag.ingestion.model.StoredIngestionFile;
import com.wish.rd.rag.project.model.RdProject;
import com.wish.rd.rag.project.model.RdProjectCommand;
import com.wish.rd.rag.project.RdProjectService;
import com.wish.rd.rag.project.RdProjectStore;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.is;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link RdTaskController} 管理接口单测。
 *
 * <p>用内存 store 构造 registry + standalone MockMvc，覆盖列表 / 详情 / 新建 / 修改 /
 * 暂停 / 恢复 / 删除 / 时间线，以及非法流转 → 409、不存在 → 404、参数缺失 → 400。
 */
class RdTaskControllerTest {

    private MockMvc mockMvc;
    private RagStreamTaskRegistry registry;
    private InMemoryAgentStageRunStore stageRunStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        stageRunStore = new InMemoryAgentStageRunStore();
        RdTaskController controller = new RdTaskController(registry);
        controller.setAgentStageRunStore(stageRunStore);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldCreateListAndGetTask() throws Exception {
        String taskId = createTask("FS-3001", "支付下单 500", "P0");

        mockMvc.perform(get("/admin/rd-tasks").param("keyword", "支付"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records", hasSize(1)))
                .andExpect(jsonPath("$.records[0].taskId", is(taskId)))
                .andExpect(jsonPath("$.records[0].status", is("CREATED")))
                .andExpect(jsonPath("$.total", is(1)));

        mockMvc.perform(get("/admin/rd-tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId", is(taskId)))
                .andExpect(jsonPath("$.title", is("支付下单 500")))
                .andExpect(jsonPath("$.paused", is(false)));
    }

    @Test
    void shouldUpdateTaskEditableFields() throws Exception {
        String taskId = createTask("FS-3002", "旧标题", "P2");
        mockMvc.perform(put("/admin/rd-tasks/{taskId}", taskId)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "新标题",
                                "priority", "P0",
                                "ticketTitle", "新工单标题"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("新标题")))
                .andExpect(jsonPath("$.priority", is("P0")))
                .andExpect(jsonPath("$.ticketTitle", is("新工单标题")))
                .andExpect(jsonPath("$.status", is("CREATED")));
    }

    @Test
    void shouldPauseAndResumeTask() throws Exception {
        String taskId = createTask("FS-3003", "任务X", "P1");
        mockMvc.perform(post("/admin/rd-tasks/{taskId}/pause", taskId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"临时干预\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused", is(true)));

        mockMvc.perform(post("/admin/rd-tasks/{taskId}/resume", taskId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"恢复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused", is(false)));

        // 时间线含 PAUSED / RESUMED
        mockMvc.perform(get("/admin/rd-tasks/{taskId}/timeline", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[1].status", is("PAUSED")))
                .andExpect(jsonPath("$[1].trigger", is("API")))
                .andExpect(jsonPath("$[2].status", is("RESUMED")));
    }

    @Test
    void shouldResumeAndPublishRestartMessageWhenRestartEngineAvailable() throws Exception {
        AtomicReference<RepairTicketMessage> published = new AtomicReference<>();
        RdTaskRestartEngine restartEngine = new RdTaskRestartEngine(
                registry,
                message -> {
                    published.set(message);
                    return RepairQueuePublishResult.success("msg-1", "topic", message.tag());
                }
        );
        mockMvc = MockMvcBuilders.standaloneSetup(new RdTaskController(registry, restartEngine)).build();
        String taskId = createTask("FS-3010", "待重启任务", "P0");
        registry.markSearching(taskId, "RAG 检索中");
        registry.markExecuting(taskId, "prompt");
        registry.pause(taskId, "人工暂停");

        mockMvc.perform(post("/admin/rd-tasks/{taskId}/resume", taskId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"恢复并重启\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId", is(taskId)))
                .andExpect(jsonPath("$.paused", is(false)))
                .andExpect(jsonPath("$.status", is("REJECTED")));

        assertEquals("FS-3010", published.get().ticketId());
        assertEquals("P0", published.get().priority());
    }

    @Test
    void shouldReturnTimelineInOrder() throws Exception {
        String taskId = createTask("FS-3004", "任务Y", "P1");
        registry.markSearching(taskId, "");
        registry.markExecuting(taskId, "");
        registry.markCommitted(taskId, "https://github.example/pr/4", "{}");
        registry.markMerged(taskId);

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/timeline", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].status", is("CREATED")))
                .andExpect(jsonPath("$[4].status", is("MERGED")))
                .andExpect(jsonPath("$[0].durationMillis", is(0)));
    }

    @Test
    void shouldLogicallyDeleteTask() throws Exception {
        String taskId = createTask("FS-3005", "任务Z", "P2");
        mockMvc.perform(delete("/admin/rd-tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted", is(true)));
        // 删除后列表不可见
        mockMvc.perform(get("/admin/rd-tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(0)));
    }

    @Test
    void shouldDeletePrivateQaEvidenceWhenTaskIsDeleted() throws Exception {
        String taskId = createTask("FS-3005-QA", "带 QA 证据的任务", "P2");
        InMemoryObjectStorageService objectStorage = new InMemoryObjectStorageService();
        byte[] content = "screenshot".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String uri = objectStorage.upload(
                "rd-qa-evidence",
                new ByteArrayInputStream(content),
                content.length,
                "current.png",
                "image/png"
        ).url();
        InMemoryAgentStageArtifactStore artifactStore = new InMemoryAgentStageArtifactStore();
        artifactStore.save(new AgentStageArtifact(
                "artifact-qa-1",
                "stage-qa-1",
                taskId,
                AgentRole.QA_AGENT,
                "QA_SCREENSHOT",
                uri,
                "current screenshot",
                "",
                "sha256:test",
                "{}",
                1L
        ));
        RdTaskController controller = new RdTaskController(registry);
        controller.setQaEvidenceRetentionService(new QaEvidenceRetentionService(artifactStore, objectStorage));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(delete("/admin/rd-tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted", is(true)));

        assertEquals(0, artifactStore.listByTask(taskId).size());
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> objectStorage.openStream(uri)
        );
    }

    @Test
    void shouldReturn404WhenTaskMissing() throws Exception {
        mockMvc.perform(get("/admin/rd-tasks/{taskId}", "9999999999999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturn400WhenCreateWithoutTitle() throws Exception {
        mockMvc.perform(post("/admin/rd-tasks")
                        .contentType(APPLICATION_JSON)
                        .content("{\"ticketId\":\"FS-X\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldPageResults() throws Exception {
        for (int i = 0; i < 3; i++) {
            createTask("FS-30" + i, "任务-" + i, "P1");
        }
        // pageSize=2 → 第一页 2 条，total 3
        mockMvc.perform(get("/admin/rd-tasks").param("page", "1").param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records", hasSize(2)))
                .andExpect(jsonPath("$.total", is(3)))
                .andExpect(jsonPath("$.page", is(1)))
                .andExpect(jsonPath("$.pageSize", is(2)));
    }

    @Test
    void shouldFilterByStatus() throws Exception {
        String created = createTask("FS-3007", "已创建任务", "P1");
        String searching = createTask("FS-3008", "检索中任务", "P1");
        registry.markSearching(searching, "");

        mockMvc.perform(get("/admin/rd-tasks").param("status", "SEARCHING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records", hasSize(1)))
                .andExpect(jsonPath("$.records[0].taskId", is(searching)));
    }

    @Test
    void shouldForwardProjectIdToCanonicalTaskQuery() throws Exception {
        RdBugFixTask projectOne = registry.createTaskManually(
                "FS-P1", "项目一任务", "项目一任务", "P1", "", "project-1", "p1", "项目一",
                "https://example.test/p1.git", "example", "p1", "main");
        registry.createTaskManually(
                "FS-P2", "项目二任务", "项目二任务", "P1", "", "project-2", "p2", "项目二",
                "https://example.test/p2.git", "example", "p2", "main");

        mockMvc.perform(get("/admin/rd-tasks").param("projectId", "project-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.records[0].taskId", is(projectOne.taskId())));
    }

    @Test
    void shouldCreateRequirementTaskAndListMaterials() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "title", "增加订单催单功能",
                "priority", "P1",
                "repositoryUrl", "https://github.com/example/waimai.git",
                "baseBranch", "main",
                "expectedResult", "用户可在订单详情页催单，商家端收到提醒",
                "acceptanceCriteria", List.of("前端构建通过", "新增接口测试通过"),
                "materials", List.of(Map.of(
                        "sourceType", "MANUAL_TEXT",
                        "title", "需求正文",
                        "content", "用户可以在订单详情页点击催单。"
                )),
                "autoExecute", false
        ));

        String response = mockMvc.perform(post("/admin/rd-tasks/requirements")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskType", is("REQUIREMENT")))
                .andExpect(jsonPath("$.title", is("增加订单催单功能")))
                .andExpect(jsonPath("$.repositoryUrl", is("https://github.com/example/waimai.git")))
                .andExpect(jsonPath("$.baseBranch", is("main")))
                .andExpect(jsonPath("$.expectedResult", is("用户可在订单详情页催单，商家端收到提醒")))
                .andReturn().getResponse().getContentAsString();
        String taskId = com.jayway.jsonpath.JsonPath.read(response, "$.taskId");

        mockMvc.perform(get("/admin/rd-tasks").param("taskType", "REQUIREMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records", hasSize(1)))
                .andExpect(jsonPath("$.records[0].taskId", is(taskId)))
                .andExpect(jsonPath("$.records[0].taskType", is("REQUIREMENT")));

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/materials", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].taskId", is(taskId)))
                .andExpect(jsonPath("$[0].sourceType", is("MANUAL_TEXT")))
                .andExpect(jsonPath("$[0].contentPreview", is("用户可以在订单详情页点击催单。")));
    }

    @Test
    void shouldCreateRequirementTaskFromSelectedProject() throws Exception {
        RdProjectService projectService = projectService();
        RdProject project = projectService.create(projectCommand());
        mockMvc = MockMvcBuilders.standaloneSetup(new RdTaskController(registry, projectService)).build();
        String body = objectMapper.writeValueAsString(Map.of(
                "title", "增加订单催单功能",
                "priority", "P1",
                "projectId", project.projectId(),
                "expectedResult", "用户可在订单详情页催单，商家端收到提醒",
                "acceptanceCriteria", List.of("前端构建通过"),
                "materials", List.of(Map.of(
                        "sourceType", "MANUAL_TEXT",
                        "title", "需求正文",
                        "content", "用户可以在订单详情页点击催单。"
                )),
                "autoExecute", false
        ));

        mockMvc.perform(post("/admin/rd-tasks/requirements")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId", is(project.projectId())))
                .andExpect(jsonPath("$.projectKey", is("waimai")))
                .andExpect(jsonPath("$.projectName", is("外卖系统")))
                .andExpect(jsonPath("$.repositoryUrl", is("https://github.com/example/waimai.git")))
                .andExpect(jsonPath("$.repoOwner", is("example")))
                .andExpect(jsonPath("$.repoName", is("waimai")))
                .andExpect(jsonPath("$.baseBranch", is("main")));
    }

    @Test
    void shouldCreateBugFixTaskFromSelectedProject() throws Exception {
        RdProjectService projectService = projectService();
        RdProject project = projectService.create(projectCommand());
        mockMvc = MockMvcBuilders.standaloneSetup(new RdTaskController(registry, projectService)).build();
        String body = objectMapper.writeValueAsString(Map.of(
                "title", "支付回调状态修复",
                "ticketId", "FS-3006",
                "ticketTitle", "支付成功后订单仍待支付",
                "priority", "P1",
                "projectId", project.projectId()
        ));

        mockMvc.perform(post("/admin/rd-tasks")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskType", is("BUG_FIX")))
                .andExpect(jsonPath("$.projectId", is(project.projectId())))
                .andExpect(jsonPath("$.projectKey", is("waimai")))
                .andExpect(jsonPath("$.projectName", is("外卖系统")))
                .andExpect(jsonPath("$.repositoryUrl", is("https://github.com/example/waimai.git")))
                .andExpect(jsonPath("$.baseBranch", is("main")));
    }

    @Test
    void shouldGenerateTicketIdWhenBugFixTaskCreatedWithoutExternalTicket() throws Exception {
        RdProjectService projectService = projectService();
        RdProject project = projectService.create(projectCommand());
        mockMvc = MockMvcBuilders.standaloneSetup(new RdTaskController(registry, projectService)).build();
        String body = objectMapper.writeValueAsString(Map.of(
                "title", "RocketMQ 修复任务无法重启",
                "ticketTitle", "No route info of topic RD_BOT_REPAIR_TICKET",
                "priority", "P1",
                "projectId", project.projectId(),
                "promptSnapshot", "现象：管理台恢复任务时报 RocketMQ topic 无路由。"
        ));

        mockMvc.perform(post("/admin/rd-tasks")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskType", is("BUG_FIX")))
                .andExpect(jsonPath("$.ticketId", startsWith("ticket-")))
                .andExpect(jsonPath("$.ticketTitle", is("No route info of topic RD_BOT_REPAIR_TICKET")));
    }

    @Test
    void shouldSubmitBugFixTaskThroughFixEngine() throws Exception {
        String promptSnapshot = "现象：localhost:5174 白屏；控制台报 useAuth 返回 null。";
        String taskId = createTask("ticket-admin-bugfix", "外卖配送管理系统启动后白屏", "P1", promptSnapshot);
        AtomicReference<RdBotFixCommand> submitted = new AtomicReference<>();
        RdBotFixEngine fixEngine = new RdBotFixEngine(null, null, registry, null, null) {
            @Override
            public RdBotFixResult runBugFix(RdBotFixCommand command) {
                submitted.set(command);
                registry.markSearching(taskId, "管理台提交 Bug 修复任务");
                return null;
            }
        };
        mockMvc = MockMvcBuilders.standaloneSetup(controllerWithBugFixEngine(fixEngine)).build();

        mockMvc.perform(post("/admin/rd-tasks/{taskId}/submit", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskType", is("BUG_FIX")))
                .andExpect(jsonPath("$.status", is("SEARCHING")));

        RdBotFixCommand command = submitted.get();
        assertNotNull(command);
        assertEquals("ticket-admin-bugfix", command.ticket().ticketId());
        assertEquals("外卖配送管理系统启动后白屏", command.ticket().title());
        assertTrue(command.ticket().description().contains(promptSnapshot));
        assertEquals(List.of(promptSnapshot), command.logs());
        assertEquals("P1", command.priority());
    }

    @Test
    void shouldAutoExecuteBugFixTaskWhenRequested() throws Exception {
        AtomicReference<RdBotFixCommand> submitted = new AtomicReference<>();
        RdBotFixEngine fixEngine = new RdBotFixEngine(null, null, registry, null, null) {
            @Override
            public RdBotFixResult runBugFix(RdBotFixCommand command) {
                submitted.set(command);
                RdBugFixTask task = registry.listBugFixTasks().stream()
                        .filter(candidate -> candidate.ticketId().equals(command.ticket().ticketId()))
                        .findFirst()
                        .orElseThrow();
                registry.markSearching(task.taskId(), "管理台自动提交 Bug 修复任务");
                return null;
            }
        };
        mockMvc = MockMvcBuilders.standaloneSetup(controllerWithBugFixEngine(fixEngine)).build();
        String body = objectMapper.writeValueAsString(Map.of(
                "title", "外卖配送管理系统启动后白屏",
                "ticketId", "ticket-auto-bugfix",
                "ticketTitle", "App.tsx 读取 useAuth 返回 null",
                "priority", "P1",
                "promptSnapshot", "现象：#root 为空，控制台 React 运行时错误。",
                "autoExecute", true
        ));

        mockMvc.perform(post("/admin/rd-tasks")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskType", is("BUG_FIX")))
                .andExpect(jsonPath("$.status", is("SEARCHING")));

        assertNotNull(submitted.get());
        assertEquals("ticket-auto-bugfix", submitted.get().ticket().ticketId());
    }

    @Test
    void shouldRejectTaskWhenSelectedProjectMissing() throws Exception {
        mockMvc = MockMvcBuilders.standaloneSetup(new RdTaskController(registry, projectService())).build();
        String body = objectMapper.writeValueAsString(Map.of(
                "title", "支付回调状态修复",
                "ticketId", "FS-3006",
                "priority", "P1",
                "projectId", "9999999999999999"
        ));

        mockMvc.perform(post("/admin/rd-tasks")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("project not found")));
    }

    @Test
    void shouldRejectRequirementTaskWhenRequiredFieldsMissing() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "title", "增加订单催单功能",
                "priority", "P1",
                "baseBranch", "main",
                "materials", List.of(Map.of(
                        "sourceType", "MANUAL_TEXT",
                        "title", "需求正文",
                        "content", "用户可以在订单详情页点击催单。"
                )),
                "autoExecute", false
        ));

        mockMvc.perform(post("/admin/rd-tasks/requirements")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("repositoryUrl")));

        mockMvc.perform(get("/admin/rd-tasks").param("taskType", "REQUIREMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(0)));
    }

    @Test
    void shouldAppendTextMaterialAndPreviewIt() throws Exception {
        String taskId = createRequirementTask(false);
        saveStageRun(taskId, "stage-failed-1");
        String body = objectMapper.writeValueAsString(Map.of(
                "title", "补充验收细节",
                "materialType", "ACCEPTANCE_CRITERIA",
                "content", "催单按钮在已完成订单不可见。",
                "mimeType", "text/plain",
                "recoveryStageRunId", "stage-failed-1"
        ));

        String response = mockMvc.perform(post("/admin/rd-tasks/{taskId}/materials/text", taskId)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId", is(taskId)))
                .andExpect(jsonPath("$.sourceType", is("MANUAL_TEXT")))
                .andExpect(jsonPath("$.materialType", is("ACCEPTANCE_CRITERIA")))
                .andExpect(jsonPath("$.contentHash", startsWith("sha256:")))
                .andExpect(jsonPath("$.contentPreview", is("催单按钮在已完成订单不可见。")))
                .andExpect(jsonPath("$.metadataJson", org.hamcrest.Matchers.containsString("stage-failed-1")))
                .andReturn().getResponse().getContentAsString();
        String materialId = com.jayway.jsonpath.JsonPath.read(response, "$.materialId");

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/materials/{materialId}/preview", taskId, materialId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materialId", is(materialId)))
                .andExpect(jsonPath("$.contentPreview", is("催单按钮在已完成订单不可见。")));
    }

    @Test
    void shouldUploadLocalRequirementMaterial() throws Exception {
        String taskId = createRequirementTask(false);
        saveStageRun(taskId, "stage-failed-1");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "requirement.md",
                "text/markdown",
                "# 催单需求\n用户可在待接单时催单。".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/admin/rd-tasks/{taskId}/materials/upload", taskId)
                        .file(file)
                        .param("title", "本地需求文档")
                        .param("materialType", "REQUIREMENT_DOC")
                        .param("recoveryStageRunId", "stage-failed-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId", is(taskId)))
                .andExpect(jsonPath("$.sourceType", is("LOCAL_UPLOAD")))
                .andExpect(jsonPath("$.title", is("本地需求文档")))
                .andExpect(jsonPath("$.sourceUri", is("local-upload://requirement.md")))
                .andExpect(jsonPath("$.mimeType", is("text/markdown")))
                .andExpect(jsonPath("$.metadataJson", org.hamcrest.Matchers.containsString("stage-failed-1")))
                .andExpect(jsonPath("$.contentPreview").value(org.hamcrest.Matchers.containsString("用户可在待接单时催单")));
    }

    @Test
    void shouldRejectTextRecoveryMaterialWhenStageRunDoesNotExist() throws Exception {
        String taskId = createRequirementTask(false);
        String body = objectMapper.writeValueAsString(Map.of(
                "title", "不存在阶段的补充材料",
                "content", "这条材料不能绑定到不存在的阶段。",
                "recoveryStageRunId", "stage-missing"
        ));

        mockMvc.perform(post("/admin/rd-tasks/{taskId}/materials/text", taskId)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("stage-missing")));

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/materials", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void shouldRejectUploadedRecoveryMaterialWhenStageBelongsToAnotherTask() throws Exception {
        String taskId = createRequirementTask(false);
        String otherTaskId = createRequirementTask(false);
        saveStageRun(otherTaskId, "stage-other-task");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "requirement.md",
                "text/markdown",
                "# 跨任务材料".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/admin/rd-tasks/{taskId}/materials/upload", taskId)
                        .file(file)
                        .param("recoveryStageRunId", "stage-other-task"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("stage-other-task")));

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/materials", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void shouldUploadAndReadBinaryScreenshotForBugFixTask() throws Exception {
        String taskId = createTask("FS-IMAGE-1", "页面错位", "P1");
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
        MockMultipartFile file = new MockMultipartFile("file", "broken.png", "image/png", png);

        String response = mockMvc.perform(multipart("/admin/rd-tasks/{taskId}/materials/upload", taskId)
                        .file(file)
                        .param("materialType", "SCREENSHOT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId", is(taskId)))
                .andExpect(jsonPath("$.materialType", is("SCREENSHOT")))
                .andExpect(jsonPath("$.mimeType", is("image/png")))
                .andExpect(jsonPath("$.artifactUri", startsWith("s3://rd-task-materials/")))
                .andExpect(jsonPath("$.contentHash", startsWith("sha256:")))
                .andExpect(jsonPath("$.contentPreview").value(org.hamcrest.Matchers.containsString("broken.png")))
                .andReturn().getResponse().getContentAsString();
        String materialId = com.jayway.jsonpath.JsonPath.read(response, "$.materialId");

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/materials/{materialId}/content", taskId, materialId))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("image/png"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(png));
    }

    @Test
    void shouldRejectDownloadedObjectWhenContentHashDoesNotMatch() throws Exception {
        String taskId = createTask("FS-IMAGE-HASH-1", "截图损坏", "P1");
        RdTaskController controller = new RdTaskController(registry);
        controller.setObjectStorageService(new CorruptingObjectStorageService());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};

        String response = mockMvc.perform(multipart("/admin/rd-tasks/{taskId}/materials/upload", taskId)
                        .file(new MockMultipartFile("file", "broken.png", "image/png", png))
                        .param("materialType", "SCREENSHOT"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String materialId = com.jayway.jsonpath.JsonPath.read(response, "$.materialId");

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/materials/{materialId}/content", taskId, materialId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("content hash mismatch")));
    }

    @Test
    void shouldRegisterFeishuRequirementMaterialWithoutMockingContent() throws Exception {
        String taskId = createRequirementTask(false);
        String feishuUrl = "https://my.feishu.cn/docx/ABCdEfGhIjKl";
        String body = objectMapper.writeValueAsString(Map.of(
                "title", "飞书 PRD",
                "sourceUri", feishuUrl,
                "revisionId", "rev-20260629"
        ));

        mockMvc.perform(post("/admin/rd-tasks/{taskId}/materials/feishu", taskId)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId", is(taskId)))
                .andExpect(jsonPath("$.sourceType", is("FEISHU_DOC")))
                .andExpect(jsonPath("$.sourceUri", is(feishuUrl)))
                .andExpect(jsonPath("$.revisionId", is("rev-20260629")))
                .andExpect(jsonPath("$.contentPreview", is("Feishu 文档来源: " + feishuUrl)));
    }

    @Test
    void shouldExposeRequirementExecutionEvidenceAfterAutoExecute() throws Exception {
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RequirementDeliveryEngine deliveryEngine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    if (request.role() == AgentRole.REQUIREMENT_REVIEWER) {
                        return RequirementExecutionResult.success(request.taskId(), "需求评审通过", "", requirementReviewBudgetJson());
                    }
                    if (request.role() == AgentRole.QA_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(),
                                "QA 验收通过",
                                "",
                                """
                                        {
                                          "status": "PASSED",
                                          "summary": "QA 验收通过",
                                          "failureCategory": "NONE",
                                          "retryRecommendation": "NONE",
                                          "browserValidation": {
                                            "required": false,
                                            "performed": false,
                                            "decisionSource": "NOT_APPLICABLE",
                                            "baseUrl": "",
                                            "browser": "chromium",
                                            "viewports": []
                                          },
                                          "acceptanceResults": [
                                            {"criteria":"前端构建通过","scope":"CURRENT","command":"npm run build","status":"PASSED","exitCode":0,"durationMillis":100,"logArtifactId":"qa-evidence/commands/current-build.log","evidenceArtifactIds":["qa-evidence/commands/current-build.log"]},
                                            {"criteria":"新增接口测试通过","scope":"CURRENT","command":"npm test","status":"PASSED","exitCode":0,"durationMillis":120,"logArtifactId":"qa-evidence/commands/current-api.log","evidenceArtifactIds":["qa-evidence/commands/current-api.log"]},
                                            {"criteria":"既有功能回归","scope":"REGRESSION","command":"npm test","status":"PASSED","exitCode":0,"durationMillis":140,"logArtifactId":"qa-evidence/commands/regression.log","evidenceArtifactIds":["qa-evidence/commands/regression.log"]}
                                          ],
                                          "evidenceManifestArtifactId": "qa-evidence/manifest.json"
                                        }
                                        """
                        );
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(),
                            "已完成催单入口和接口联调",
                            "",
                            """
                                    {
                                      "status": "SUCCESS",
                                      "summary": "已完成催单入口和接口联调",
                                      "prBody": "## 改动介绍\\n- 新增订单详情催单按钮\\n- 新增催单 API 调用",
                                      "changedFiles": ["client/src/pages/OrderDetail.tsx", "server/src/routes/reminders.ts"],
                                      "testCommands": ["npm run build", "npm test"],
                                      "testStatus": "PASSED",
                                      "riskLevel": "LOW"
                                    }
                                    """
                    );
                },
                command -> RequirementPullRequestPublication.success(
                        command.taskId(),
                        "https://github.com/example/waimai/pull/42",
                        "42",
                        "{\"provider\":\"controller-test\"}"
                )
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controllerWithRequirementEngine(materialStore, deliveryEngine))
                .build();
        String body = objectMapper.writeValueAsString(Map.of(
                "title", "增加订单催单功能",
                "priority", "P1",
                "repositoryUrl", "https://github.com/example/waimai.git",
                "baseBranch", "main",
                "expectedResult", "用户可在订单详情页催单",
                "acceptanceCriteria", List.of("前端构建通过", "新增接口测试通过"),
                "materials", List.of(Map.of(
                        "sourceType", "MANUAL_TEXT",
                        "title", "需求正文",
                        "content", "用户可以在订单详情页点击催单。"
                )),
                "autoExecute", true
        ));

        String response = mockMvc.perform(post("/admin/rd-tasks/requirements")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.pullRequestUrl", is("https://github.com/example/waimai/pull/42")))
                .andExpect(jsonPath("$.executionEvidence.summary", is("已完成催单入口和接口联调")))
                .andExpect(jsonPath("$.executionEvidence.prBody").value(org.hamcrest.Matchers.containsString("新增订单详情催单按钮")))
                .andExpect(jsonPath("$.executionEvidence.changedFiles[0]", is("client/src/pages/OrderDetail.tsx")))
                .andExpect(jsonPath("$.executionEvidence.testCommands[0]", is("npm run build")))
                .andExpect(jsonPath("$.executionEvidence.testStatus", is("PASSED")))
                .andExpect(jsonPath("$.executionEvidence.riskLevel", is("LOW")))
                .andReturn().getResponse().getContentAsString();
        String taskId = com.jayway.jsonpath.JsonPath.read(response, "$.taskId");
        assertEquals(List.of(
                "CREATED",
                "MATERIAL_COLLECTING",
                "MATERIAL_READY",
                "CONTEXT_BUILDING",
                "CONTEXT_READY",
                "PLAN_GENERATING",
                "PLAN_GENERATED",
                "WAITING_POLICY",
                "EXECUTING",
                "VALIDATING",
                "PR_CREATING",
                "COMMITTED",
                "REPORTING",
                "COMPLETED"
        ), registry.timeline(taskId).stream().map(event -> event.status()).toList());
    }

    @Test
    void shouldApproveWaitingRequirementTaskAndContinueExecution() throws Exception {
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RequirementDeliveryEngine deliveryEngine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    if (request.role() == AgentRole.REQUIREMENT_REVIEWER) {
                        return RequirementExecutionResult.success(request.taskId(), "需求评审通过", "", requirementReviewBudgetJson());
                    }
                    if (request.role() == AgentRole.QA_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(),
                                "QA 验收通过",
                                "",
                                """
                                        {
                                          "status": "PASSED",
                                          "summary": "QA 验收通过",
                                          "failureCategory": "NONE",
                                          "retryRecommendation": "NONE",
                                          "browserValidation": {
                                            "required": false,
                                            "performed": false,
                                            "decisionSource": "NOT_APPLICABLE",
                                            "baseUrl": "",
                                            "browser": "chromium",
                                            "viewports": []
                                          },
                                          "acceptanceResults": [
                                            {"criteria":"前端构建通过","scope":"CURRENT","command":"npm run build","status":"PASSED","exitCode":0,"durationMillis":100,"logArtifactId":"qa-evidence/commands/current-build.log","evidenceArtifactIds":["qa-evidence/commands/current-build.log"]},
                                            {"criteria":"既有功能回归","scope":"REGRESSION","command":"npm test","status":"PASSED","exitCode":0,"durationMillis":120,"logArtifactId":"qa-evidence/commands/regression.log","evidenceArtifactIds":["qa-evidence/commands/regression.log"]}
                                          ],
                                          "evidenceManifestArtifactId": "qa-evidence/manifest.json"
                                        }
                                        """
                        );
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(),
                            "已完成商品管理功能",
                            "",
                            """
                                    {
                                      "status": "SUCCESS",
                                      "summary": "已完成商品管理功能",
                                      "prBody": "## 改动介绍\\n- 新增商品管理页面",
                                      "changedFiles": ["client/src/pages/admin/Products.tsx"],
                                      "testCommands": ["npm run build"],
                                      "testStatus": "PASSED",
                                      "riskLevel": "LOW"
                                    }
                                    """
                    );
                },
                command -> RequirementPullRequestPublication.success(
                        command.taskId(),
                        "https://github.com/example/waimai/pull/77",
                        "77",
                        "{\"provider\":\"controller-test\"}"
                )
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controllerWithRequirementEngine(materialStore, deliveryEngine))
                .build();
        String body = objectMapper.writeValueAsString(Map.of(
                "title", "开发管理后台商品管理功能",
                "priority", "P2",
                "repositoryUrl", "https://github.com/example/waimai.git",
                "baseBranch", "main",
                "expectedResult", "管理员可管理商品与支付渠道",
                "acceptanceCriteria", List.of("前端构建通过"),
                "materials", List.of(Map.of(
                        "sourceType", "MANUAL_TEXT",
                        "title", "需求正文",
                        "content", "保持现有登录入口可用，新增商品管理页面。"
                )),
                "autoExecute", true
        ));
        String response = mockMvc.perform(post("/admin/rd-tasks/requirements")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("WAITING_APPROVAL")))
                .andReturn().getResponse().getContentAsString();
        String taskId = com.jayway.jsonpath.JsonPath.read(response, "$.taskId");

        mockMvc.perform(post("/admin/rd-tasks/{taskId}/approve", taskId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"人工确认商品管理需求可以进入沙箱执行\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.pullRequestUrl", is("https://github.com/example/waimai/pull/77")))
                .andExpect(jsonPath("$.executionEvidence.summary", is("已完成商品管理功能")));

        assertEquals(List.of(
                "CREATED",
                "MATERIAL_COLLECTING",
                "MATERIAL_READY",
                "CONTEXT_BUILDING",
                "CONTEXT_READY",
                "PLAN_GENERATING",
                "PLAN_GENERATED",
                "WAITING_POLICY",
                "WAITING_APPROVAL",
                "APPROVED",
                "EXECUTING",
                "VALIDATING",
                "PR_CREATING",
                "COMMITTED",
                "REPORTING",
                "COMPLETED"
        ), registry.timeline(taskId).stream().map(event -> event.status()).toList());
    }

    @Test
    void shouldNotBypassApprovalWhenWaitingRequirementIsSubmittedDirectly() throws Exception {
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        AtomicReference<AgentRole> executedRole = new AtomicReference<>();
        RequirementDeliveryEngine deliveryEngine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    executedRole.set(request.role());
                    return RequirementExecutionResult.success(
                            request.taskId(),
                            "不应执行",
                            "",
                            "{\"status\":\"SUCCESS\"}"
                    );
                },
                command -> RequirementPullRequestPublication.failure(command.taskId(), "不应发布 PR")
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controllerWithRequirementEngine(materialStore, deliveryEngine))
                .build();
        String body = objectMapper.writeValueAsString(Map.of(
                "title", "开发管理后台商品管理功能",
                "priority", "P2",
                "repositoryUrl", "https://github.com/example/waimai.git",
                "baseBranch", "main",
                "expectedResult", "管理员可管理商品与支付渠道",
                "acceptanceCriteria", List.of("前端构建通过"),
                "materials", List.of(Map.of(
                        "sourceType", "MANUAL_TEXT",
                        "title", "需求正文",
                        "content", "保持现有登录入口可用，新增商品管理页面。"
                )),
                "autoExecute", true
        ));
        String response = mockMvc.perform(post("/admin/rd-tasks/requirements")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("WAITING_APPROVAL")))
                .andReturn().getResponse().getContentAsString();
        String taskId = com.jayway.jsonpath.JsonPath.read(response, "$.taskId");

        mockMvc.perform(post("/admin/rd-tasks/{taskId}/submit", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("WAITING_APPROVAL")));

        assertNull(executedRole.get());
        assertEquals(List.of(
                "CREATED",
                "MATERIAL_COLLECTING",
                "MATERIAL_READY",
                "CONTEXT_BUILDING",
                "CONTEXT_READY",
                "PLAN_GENERATING",
                "PLAN_GENERATED",
                "WAITING_POLICY",
                "WAITING_APPROVAL"
        ), registry.timeline(taskId).stream().map(event -> event.status()).toList());
    }

    @Test
    void shouldCancelLogicalTaskEvenWhenExternalStopReportsNoProcess() throws Exception {
        String taskId = createTask("FS-3009", "待停止任务", "P1");

        mockMvc.perform(post("/admin/rd-tasks/{taskId}/stop", taskId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"停止\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopped", is(false)))
                .andExpect(jsonPath("$.task.status", is("CANCELLED")));

        mockMvc.perform(get("/admin/rd-tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")));
    }

    private String createTask(String ticketId, String title, String priority) throws Exception {
        return createTask(ticketId, title, priority, "");
    }

    private String createTask(String ticketId, String title, String priority, String promptSnapshot) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "ticketId", ticketId,
                "title", title,
                "priority", priority,
                "promptSnapshot", promptSnapshot));
        String response = mockMvc.perform(post("/admin/rd-tasks")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").exists())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(response, "$.taskId");
    }

    private String createRequirementTask(boolean autoExecute) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "title", "增加订单催单功能",
                "priority", "P1",
                "repositoryUrl", "https://github.com/example/waimai.git",
                "baseBranch", "main",
                "expectedResult", "用户可在订单详情页催单",
                "acceptanceCriteria", List.of("前端构建通过"),
                "materials", List.of(Map.of(
                        "sourceType", "MANUAL_TEXT",
                        "title", "需求正文",
                        "content", "用户可以在订单详情页点击催单。"
                )),
                "autoExecute", autoExecute
        ));
        String response = mockMvc.perform(post("/admin/rd-tasks/requirements")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").exists())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(response, "$.taskId");
    }

    private void saveStageRun(String taskId, String stageRunId) {
        stageRunStore.save(AgentStageRun.pending(
                stageRunId,
                taskId,
                AgentRole.REQUIREMENT_REVIEWER,
                1,
                taskId + ":REQUIREMENT_REVIEWER:1",
                100L
        ));
    }

    private static String requirementReviewBudgetJson() {
        return """
                {
                  "decision":"APPROVED",
                  "feasibility":"CAN_DO",
                  "missingInformation":[],
                  "risks":[],
                  "acceptanceCoverage":["前端构建通过"],
                  "budgetEstimate":{
                    "initialTokens":80000,
                    "retryReserveTokens":20000,
                    "estimatedTotalTokens":100000,
                    "confidence":"LOW",
                    "basis":"无历史样本时由模型判断完整四角色交付范围",
                    "historicalSamples":[]
                  }
                }
                """;
    }

    private SnowflakeIdGenerator generator() {
        AtomicLong now = new AtomicLong(1_781_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }

    private RdProjectService projectService() {
        return new RdProjectService(generator(), new FakeRdProjectStore());
    }

    private RdProjectCommand projectCommand() {
        return new RdProjectCommand(
                "waimai",
                "外卖系统",
                "外卖订单验收仓库",
                "https://github.com/example/waimai.git",
                "",
                "",
                "main",
                true
        );
    }

    private RdTaskController controllerWithRequirementEngine(
            InMemoryTaskMaterialStore materialStore,
            RequirementDeliveryEngine deliveryEngine
    ) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("materialStore", materialStore);
        beans.addBean("idGenerator", generator());
        beans.addBean("requirementDeliveryEngine", deliveryEngine);
        return new RdTaskController(
                registry,
                beans.getBeanProvider(com.wish.rd.exec.repair.execution.RepairExecutionControlPort.class),
                beans.getBeanProvider(com.wish.rd.engine.audit.RepairAuditSinkPort.class),
                beans.getBeanProvider(RdTaskRestartEngine.class),
                beans.getBeanProvider(com.wish.rd.rag.runtime.TaskMaterialStore.class),
                beans.getBeanProvider(SnowflakeIdGenerator.class),
                beans.getBeanProvider(RequirementDeliveryEngine.class),
                beans.getBeanProvider(com.wish.rd.bootstrap.threading.RequirementDeliveryDispatchService.class),
                beans.getBeanProvider(RdBotFixEngine.class),
                beans.getBeanProvider(com.wish.rd.bootstrap.threading.BugFixExecutionDispatchService.class),
                beans.getBeanProvider(RdProjectService.class)
        );
    }

    private RdTaskController controllerWithBugFixEngine(RdBotFixEngine fixEngine) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("idGenerator", generator());
        beans.addBean("bugFixEngine", fixEngine);
        return new RdTaskController(
                registry,
                beans.getBeanProvider(com.wish.rd.exec.repair.execution.RepairExecutionControlPort.class),
                beans.getBeanProvider(com.wish.rd.engine.audit.RepairAuditSinkPort.class),
                beans.getBeanProvider(RdTaskRestartEngine.class),
                beans.getBeanProvider(com.wish.rd.rag.runtime.TaskMaterialStore.class),
                beans.getBeanProvider(SnowflakeIdGenerator.class),
                beans.getBeanProvider(RequirementDeliveryEngine.class),
                beans.getBeanProvider(com.wish.rd.bootstrap.threading.RequirementDeliveryDispatchService.class),
                beans.getBeanProvider(RdBotFixEngine.class),
                beans.getBeanProvider(com.wish.rd.bootstrap.threading.BugFixExecutionDispatchService.class),
                beans.getBeanProvider(RdProjectService.class)
        );
    }

    private static final class FakeRdProjectStore implements RdProjectStore {

        private final LinkedHashMap<String, RdProject> projects = new LinkedHashMap<>();

        @Override
        public RdProject save(RdProject project) {
            projects.put(project.projectId(), project);
            return project;
        }

        @Override
        public Optional<RdProject> findById(String projectId) {
            return Optional.ofNullable(projects.get(projectId));
        }

        @Override
        public Optional<RdProject> findByKey(String projectKey) {
            return projects.values().stream()
                    .filter(project -> project.projectKey().equals(projectKey))
                    .findFirst();
        }

        @Override
        public List<RdProject> list() {
            return List.copyOf(projects.values());
        }
    }

    private static final class CorruptingObjectStorageService implements ObjectStorageService {

        private final InMemoryObjectStorageService delegate = new InMemoryObjectStorageService();

        @Override
        public StoredIngestionFile upload(
                String bucketName,
                InputStream content,
                long size,
                String originalFilename,
                String contentType
        ) {
            return delegate.upload(bucketName, content, size, originalFilename, contentType);
        }

        @Override
        public InputStream openStream(String url) {
            try {
                byte[] bytes = delegate.openStream(url).readAllBytes();
                bytes[bytes.length - 1] ^= 1;
                return new ByteArrayInputStream(bytes);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
