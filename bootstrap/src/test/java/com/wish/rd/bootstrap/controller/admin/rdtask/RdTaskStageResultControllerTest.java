package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.requirement.query.CodingMeaNotFoundException;
import com.wish.rd.engine.requirement.query.StageResultQueryEngine;
import com.wish.rd.engine.requirement.query.StageResultReadPort;
import com.wish.rd.engine.requirement.query.StageResultSnapshot;
import com.wish.rd.engine.requirement.query.StageResultView;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RdTaskStageResultControllerTest {

    @Test
    void foreignStageIs404AndPreviewOnlyIs200() throws Exception {
        RagStreamTaskRegistry registry = RagStreamTaskRegistry.inMemory();
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "结果任务", "P1", "ADMIN", "", "", "project-owned", "owned-key", "Owned Project",
                "https://github.com/acme/web.git", "", "", "main", "页面可用",
                List.of("真实浏览器通过"), List.of(), false));
        AgentStageRun own = AgentStageRun.pending(
                "stage-own", task.taskId(), AgentRole.CODING_AGENT, 1, task.taskId() + ":C:1", 1L);
        StageResultReadPort port = (taskId, stageRunId) -> {
            if (!task.taskId().equals(taskId) || !"stage-own".equals(stageRunId)) {
                throw new CodingMeaNotFoundException("stage run not found");
            }
            return new StageResultSnapshot(own, "", "", "", "art-old", "{\"preview\":true}", true);
        };
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new RdTaskStageResultController(registry, port, new StageResultQueryEngine())
        ).build();

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/stage-runs/{stageRunId}/result",
                        task.taskId(), "stage-other"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/admin/rd-tasks/{taskId}/stage-runs/{stageRunId}/result",
                        task.taskId(), "stage-own"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value(StageResultView.SOURCE_PREVIEW))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.downloadPath").value(org.hamcrest.Matchers.nullValue()));
    }
}
