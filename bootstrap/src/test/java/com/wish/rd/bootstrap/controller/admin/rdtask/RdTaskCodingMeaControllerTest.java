package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.wish.rd.engine.requirement.manager.impl.InMemoryManagerDecisionStore;
import com.wish.rd.engine.requirement.query.CodingMeaNotFoundException;
import com.wish.rd.engine.requirement.query.CodingMeaQueryEngine;
import com.wish.rd.engine.requirement.query.CodingMeaReadPort;
import com.wish.rd.engine.requirement.query.CodingMeaSnapshot;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RdTaskCodingMeaControllerTest {

    private RagStreamTaskRegistry registry;
    private MockMvc mockMvc;
    private RdRequirementTask task;

    @BeforeEach
    void setUp() {
        registry = RagStreamTaskRegistry.inMemory();
        task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "读取任务", "P1", "ADMIN", "", "", "project-owned", "owned-key", "Owned Project",
                "https://github.com/acme/web.git", "", "", "main", "页面可用",
                List.of("真实浏览器通过"), List.of(), false));
        CodingMeaReadPort port = (taskId, codingStageRunId, limit, cursor) -> {
            if (!task.taskId().equals(taskId)) {
                throw new CodingMeaNotFoundException("task not found");
            }
            return new CodingMeaSnapshot(
                    1L, task.taskId(), task.version(), task.status().name(), task.paused(),
                    "", true, null, Map.of(), List.of(), List.of(), Set.of(), false, "",
                    List.of(), List.of(), List.of(), List.of(), List.of());
        };
        mockMvc = MockMvcBuilders.standaloneSetup(
                new RdTaskCodingMeaController(
                        registry, port, new CodingMeaQueryEngine(), new InMemoryManagerDecisionStore())
        ).build();
    }

    @Test
    void missingCodingStageReturns200AvailableFalse() throws Exception {
        mockMvc.perform(get("/admin/rd-tasks/{taskId}/coding-mea", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.unavailableReason").value("NO_CODING_STAGE"))
                .andExpect(jsonPath("$.schemaVersion").value(1));
    }

    @Test
    void unknownTaskIs404() throws Exception {
        mockMvc.perform(get("/admin/rd-tasks/{taskId}/coding-mea", "9999999999999999"))
                .andExpect(status().isNotFound());
    }
}
