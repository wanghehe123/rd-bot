package com.wish.rd.bootstrap.controller.admin.trace;

import com.wish.rd.engine.admin.trace.ExecutionTraceQueryService;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExecutionTraceControllerTest {

    private RagStreamTaskRegistry registry;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator());
        mockMvc = MockMvcBuilders.standaloneSetup(new ExecutionTraceController(
                new ExecutionTraceQueryService(registry, null))).build();
    }

    @Test
    void shouldReturnOnlyTheSelectedProjectTracePage() throws Exception {
        registry.createRequirementTask(requirement("project-a", "项目 A 需求"));
        registry.createRequirementTask(requirement("project-b", "项目 B 需求"));

        mockMvc.perform(get("/admin/execution-traces")
                        .param("projectId", "project-a")
                        .param("taskType", "REQUIREMENT")
                        .param("page", "1")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.records[0].projectId", is("project-a")))
                .andExpect(jsonPath("$.records[0].taskType", is("REQUIREMENT")))
                .andExpect(jsonPath("$.records[0].progressTotal", is(24)));
    }

    private static CreateRequirementTaskCommand requirement(String projectId, String title) {
        return new CreateRequirementTaskCommand(
                title,
                "P1",
                "ADMIN",
                "",
                "",
                projectId,
                projectId + "-key",
                projectId + "-name",
                "https://github.com/example/repo.git",
                "example",
                "repo",
                "main",
                "完成交付",
                List.of("接口通过"),
                List.of(),
                false);
    }
}
