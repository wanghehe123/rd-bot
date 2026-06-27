package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.rag.runtime.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.RdBugFixTask;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.http.MediaType.APPLICATION_JSON;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        RdTaskController controller = new RdTaskController(registry);
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

    private String createTask(String ticketId, String title, String priority) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "ticketId", ticketId,
                "title", title,
                "priority", priority));
        String response = mockMvc.perform(post("/admin/rd-tasks")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").exists())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(response, "$.taskId");
    }

    private SnowflakeIdGenerator generator() {
        AtomicLong now = new AtomicLong(1_781_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }
}
