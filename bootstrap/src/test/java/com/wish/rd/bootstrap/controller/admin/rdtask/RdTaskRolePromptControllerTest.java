package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.rag.context.RoleContextPackageStore;
import com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.context.model.RoleContextPackage;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RdTaskRolePromptControllerTest {

    private MockMvc mockMvc;
    private RagStreamTaskRegistry registry;
    private AgentStageRunStore stageRunStore;
    private AgentStageArtifactStore artifactStore;
    private RoleContextPackageStore contextPackageStore;

    @BeforeEach
    void setUp() {
        registry = RagStreamTaskRegistry.inMemory();
        stageRunStore = new InMemoryAgentStageRunStore();
        artifactStore = new InMemoryAgentStageArtifactStore();
        contextPackageStore = new InMemoryRoleContextPackageStore();
        mockMvc = MockMvcBuilders.standaloneSetup(new RdTaskRolePromptController(
                registry,
                stageRunStore,
                artifactStore,
                contextPackageStore
        )).build();
    }

    @Test
    void shouldReturnActualRolePromptAndItsBoundRetrievalEvidence() throws Exception {
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "角色 Prompt 审计", "P2", "https://github.com/acme/repo.git", "acme", "repo", "main",
                "查看每个角色的真实 Prompt", List.of("角色与证据可追溯"), false
        ));
        contextPackageStore.save(new RoleContextPackage(
                "context-review-1001",
                task.taskId(),
                "REQUIREMENT_REVIEWER",
                2,
                List.of(new RoleContextEvidence(
                        "evidence-1001",
                        "TASK_MATERIAL",
                        "rd-task-material://requirement-1001",
                        "需求正文",
                        "sha256:evidence",
                        "包含验收范围与边界条件",
                        1_783_000_000_000L,
                        "需求评审必须使用任务原始材料",
                        0.91D,
                        "REQUIREMENT",
                        true
                )),
                List.of("角色与证据可追溯"),
                List.of("不得泄露密钥"),
                18_000,
                1_248,
                List.of("evidence-omitted-1"),
                "retrieval-1001",
                1_783_000_000_000L
        ));
        stageRunStore.save(new AgentStageRun(
                "stage-review-1001",
                task.taskId(),
                AgentRole.REQUIREMENT_REVIEWER,
                AgentStageStatus.SUCCEEDED,
                1,
                task.taskId() + ":REQUIREMENT_REVIEWER:1",
                "context-review-1001",
                "prompt-review-1001",
                "",
                "long-cat",
                "[]",
                "{}",
                "",
                "",
                1_783_000_000_000L,
                1_783_000_000_100L,
                1_783_000_000_010L,
                1_783_000_000_100L
        ));
        stageRunStore.save(AgentStageRun.pending(
                "stage-coding-1001",
                task.taskId(),
                AgentRole.CODING_AGENT,
                1,
                task.taskId() + ":CODING_AGENT:1",
                1_783_000_000_120L
        ));
        artifactStore.save(new AgentStageArtifact(
                "prompt-review-1001",
                "stage-review-1001",
                task.taskId(),
                AgentRole.REQUIREMENT_REVIEWER,
                "PROMPT_SNAPSHOT",
                "rd-agent-stage://stage-review-1001/prompt",
                "REQUIREMENT_REVIEWER prompt snapshot",
                "# 当前职责\n\n评审需求是否可执行。",
                "sha256:prompt",
                "{\"contentLength\":42}",
                1_783_000_000_010L
        ));

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/role-prompts", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId", is(task.taskId())))
                .andExpect(jsonPath("$.stagePrompts", hasSize(2)))
                .andExpect(jsonPath("$.stagePrompts[0].role", is("REQUIREMENT_REVIEWER")))
                .andExpect(jsonPath("$.stagePrompts[0].attemptNo", is(1)))
                .andExpect(jsonPath("$.stagePrompts[0].prompt.available", is(true)))
                .andExpect(jsonPath("$.stagePrompts[0].prompt.contentPreview", containsString("当前职责")))
                .andExpect(jsonPath("$.stagePrompts[0].prompt.contentLength", is(42)))
                .andExpect(jsonPath("$.stagePrompts[0].context.available", is(true)))
                .andExpect(jsonPath("$.stagePrompts[0].context.retrievalRunId", is("retrieval-1001")))
                .andExpect(jsonPath("$.stagePrompts[0].context.evidence[0].selectionReason", is("需求评审必须使用任务原始材料")))
                .andExpect(jsonPath("$.stagePrompts[1].role", is("CODING_AGENT")))
                .andExpect(jsonPath("$.stagePrompts[1].prompt.available", is(false)))
                .andExpect(jsonPath("$.stagePrompts[1].prompt.unavailableReason", is("尚未生成实际角色 Prompt")));
    }

    @Test
    void shouldNotExposePromptArtifactBoundToAnotherStage() throws Exception {
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "Prompt 绑定隔离", "P2", "https://github.com/acme/repo.git", "acme", "repo", "main",
                "避免跨阶段 Prompt 泄露", List.of("阶段产物严格绑定"), false
        ));
        stageRunStore.save(new AgentStageRun(
                "stage-review-bound-1001",
                task.taskId(),
                AgentRole.REQUIREMENT_REVIEWER,
                AgentStageStatus.SUCCEEDED,
                1,
                task.taskId() + ":REQUIREMENT_REVIEWER:1",
                "",
                "prompt-wrong-stage-1001",
                "",
                "long-cat",
                "[]",
                "{}",
                "",
                "",
                1L,
                2L,
                1L,
                2L
        ));
        artifactStore.save(new AgentStageArtifact(
                "prompt-wrong-stage-1001",
                "other-stage-1001",
                task.taskId(),
                AgentRole.REQUIREMENT_REVIEWER,
                "PROMPT_SNAPSHOT",
                "rd-agent-stage://other-stage-1001/prompt",
                "foreign prompt",
                "# 不应被读取",
                "sha256:foreign",
                "{}",
                1L
        ));

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/role-prompts", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stagePrompts[0].prompt.available", is(false)))
                .andExpect(jsonPath("$.stagePrompts[0].prompt.unavailableReason", is("实际角色 Prompt 产物不可用")));
    }

    @Test
    void shouldReturn404ForUnknownTask() throws Exception {
        mockMvc.perform(get("/admin/rd-tasks/{taskId}/role-prompts", "task-missing"))
                .andExpect(status().isNotFound());
    }
}
