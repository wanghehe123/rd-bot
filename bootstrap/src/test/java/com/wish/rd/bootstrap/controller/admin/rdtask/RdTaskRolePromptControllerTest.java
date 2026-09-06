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
import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentStageStateProjectionStore;
import com.wish.rd.rag.project.agent.model.AgentContextInjectionProjectionUpdate;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.project.agent.model.AgentStageStateIdentity;
import com.wish.rd.rag.project.agent.model.AgentStateProjectionUpdate;
import com.wish.rd.rag.project.agent.model.AgentStateV2Codec;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RdTaskRolePromptControllerTest {

    private MockMvc mockMvc;
    private RagStreamTaskRegistry registry;
    private AgentStageRunStore stageRunStore;
    private AgentStageArtifactStore artifactStore;
    private RoleContextPackageStore contextPackageStore;
    private InMemoryAgentExecutionProfileSnapshotStore profileSnapshotStore;
    private InMemoryAgentStageStateProjectionStore stateProjectionStore;

    @BeforeEach
    void setUp() {
        registry = RagStreamTaskRegistry.inMemory();
        stageRunStore = new InMemoryAgentStageRunStore();
        artifactStore = new InMemoryAgentStageArtifactStore();
        contextPackageStore = new InMemoryRoleContextPackageStore();
        profileSnapshotStore = new InMemoryAgentExecutionProfileSnapshotStore();
        stateProjectionStore = new InMemoryAgentStageStateProjectionStore();
        mockMvc = MockMvcBuilders.standaloneSetup(new RdTaskRolePromptController(
                registry,
                stageRunStore,
                artifactStore,
                contextPackageStore,
                profileSnapshotStore,
                stateProjectionStore,
                () -> 1_783_000_100_000L
        )).build();
    }

    @Test
    void rolePromptsReadTaskArtifactsOnceAcrossStages() throws Exception {
        CountingAgentStageArtifactStore counting = new CountingAgentStageArtifactStore(new InMemoryAgentStageArtifactStore());
        MockMvc isolated = MockMvcBuilders.standaloneSetup(new RdTaskRolePromptController(
                registry,
                stageRunStore,
                counting,
                contextPackageStore,
                profileSnapshotStore,
                stateProjectionStore,
                () -> 1_783_000_100_000L
        )).build();
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "role-prompts 产物只读一次", "P1", "https://github.com/acme/repo.git", "acme", "repo", "main",
                "多阶段不得反复 listByTask", List.of("一次拉取"), false
        ));
        savePromptStage(counting, task.taskId(), AgentRole.REQUIREMENT_REVIEWER, "stage-reviewer-once", "prompt-reviewer-once");
        savePromptStage(counting, task.taskId(), AgentRole.CODING_AGENT, "stage-coding-once", "prompt-coding-once");
        savePromptStage(counting, task.taskId(), AgentRole.QA_AGENT, "stage-qa-once", "prompt-qa-once");

        isolated.perform(get("/admin/rd-tasks/{taskId}/role-prompts", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stagePrompts", hasSize(3)));
        assertEquals(1, counting.listByTaskCalls.get());
    }

    @Test
    void shouldExposeExactLiveEffectiveContextAndLatestStateProvenance() throws Exception {
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "PI 有效上下文审计", "P1", "https://github.com/acme/repo.git", "acme", "repo", "main",
                "展示真正注入的状态", List.of("区分 latest 与 injected"), false
        ));
        AgentStageRun stage = stageRunStore.save(new AgentStageRun(
                "stage-pi-context-1001", task.taskId(), AgentRole.CODING_AGENT,
                AgentStageStatus.RUNNING, 1, task.taskId() + ":CODING_AGENT:1", "",
                "prompt-pi-context-1001", "", "pi", "[]", "{}", "", "",
                1_783_000_000_000L, 1_783_000_090_000L, 1_783_000_000_000L, 0L
        ));
        String prompt = "# Coding\n\nImplement the accepted scope.";
        String promptHash = sha256(prompt);
        artifactStore.save(new AgentStageArtifact(
                "prompt-pi-context-1001", stage.stageRunId(), task.taskId(), AgentRole.CODING_AGENT,
                "PROMPT_SNAPSHOT", "rd-agent-stage://stage-pi-context-1001/prompt", "coding prompt",
                prompt, promptHash, "{\"contentLength\":" + prompt.length() + "}",
                1_783_000_000_010L
        ));
        savePiV2Profile(stage);
        AgentStageStateIdentity identity = new AgentStageStateIdentity(
                task.taskId(), stage.stageRunId(), stage.role().name(), stage.attemptNo()
        );
        String injectedState = stateJson(task.taskId(), stage.stageRunId(), 10, "实现 API");
        String injectedStateHash = hashState(injectedState);
        String block = stateBlock(injectedState, injectedStateHash, 10, 3);
        stateProjectionStore.projectState(new AgentStateProjectionUpdate(
                identity, 10, injectedStateHash, injectedState, 1_783_000_080_000L
        ));
        stateProjectionStore.projectInjection(new AgentContextInjectionProjectionUpdate(
                identity, 3, 10, injectedStateHash, promptHash, sha256(block), block,
                sha256(stage.stageRunId() + ":3:" + sha256(block)), 1_783_000_081_000L
        ));
        String latestState = stateJson(task.taskId(), stage.stageRunId(), 12, "运行回归测试");
        stateProjectionStore.projectState(new AgentStateProjectionUpdate(
                identity, 12, hashState(latestState), latestState, 1_783_000_095_000L
        ));

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/role-prompts", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stagePrompts[0].runtimeType", is("PI")))
                .andExpect(jsonPath("$.stagePrompts[0].latestState.available", is(true)))
                .andExpect(jsonPath("$.stagePrompts[0].latestState.source", is("LIVE_PROJECTION")))
                .andExpect(jsonPath("$.stagePrompts[0].latestState.sequence", is(12)))
                .andExpect(jsonPath("$.stagePrompts[0].latestState.currentGoal", is("运行回归测试")))
                .andExpect(jsonPath("$.stagePrompts[0].latestState.stale", is(false)))
                .andExpect(jsonPath("$.stagePrompts[0].effectiveContext.available", is(true)))
                .andExpect(jsonPath("$.stagePrompts[0].effectiveContext.injectionSequence", is(3)))
                .andExpect(jsonPath("$.stagePrompts[0].effectiveContext.stateSequence", is(10)))
                .andExpect(jsonPath("$.stagePrompts[0].effectiveContext.injectedBlockHash", is(sha256(block))))
                .andExpect(jsonPath("$.stagePrompts[0].effectiveContext.latestStateNotInjected", is(true)))
                .andExpect(jsonPath("$.stagePrompts[0].effectiveContext.contentPreview", containsString(block)))
                .andExpect(jsonPath("$.stagePrompts[0].effectiveContext.contentPreview", containsString("Implement the accepted scope")));
    }

    @Test
    void shouldMarkOldActiveProjectionStaleButArchivedFinalArtifactFresh() throws Exception {
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "投影 freshness", "P2", "https://github.com/acme/repo.git", "acme", "repo", "main",
                "展示 freshness", List.of("active stale, archived fresh"), false
        ));
        AgentStageRun active = stageRunStore.save(new AgentStageRun(
                "stage-active-stale-1001", task.taskId(), AgentRole.CODING_AGENT,
                AgentStageStatus.RUNNING, 1, task.taskId() + ":CODING_AGENT:1", "", "", "",
                "pi", "[]", "{}", "", "", 1L, 1L, 1L, 0L
        ));
        savePiV2Profile(active);
        AgentStageStateIdentity activeIdentity = new AgentStageStateIdentity(
                task.taskId(), active.stageRunId(), active.role().name(), active.attemptNo()
        );
        String state = stateJson(task.taskId(), active.stageRunId(), 2, "等待构建");
        stateProjectionStore.projectState(new AgentStateProjectionUpdate(
                activeIdentity, 2, hashState(state), state, 1_783_000_000_000L
        ));

        AgentStageRun archived = stageRunStore.save(new AgentStageRun(
                "stage-archived-1001", task.taskId(), AgentRole.QA_AGENT,
                AgentStageStatus.SUCCEEDED, 1, task.taskId() + ":QA_AGENT:1", "", "prompt-archived-1001", "",
                "pi", "[]", "{}", "", "", 1L, 2L, 1L, 2L
        ));
        savePiV2Profile(archived);
        String archivedState = stateJson(task.taskId(), archived.stageRunId(), "QA_AGENT", 4, "完成验收");
        String archivedPrompt = "# QA\n\nRun acceptance.";
        String archivedPromptHash = sha256(archivedPrompt);
        artifactStore.save(new AgentStageArtifact(
                "prompt-archived-1001", archived.stageRunId(), task.taskId(), AgentRole.QA_AGENT,
                "PROMPT_SNAPSHOT", "rd://prompt", "prompt", archivedPrompt, archivedPromptHash,
                "{\"contentLength\":" + archivedPrompt.length() + "}", 1_782_000_000_000L
        ));
        artifactStore.save(new AgentStageArtifact(
                "state-archived-1001", archived.stageRunId(), task.taskId(), AgentRole.QA_AGENT,
                "AGENT_STATE_SNAPSHOT", "rd://state", "state", archivedState, hashState(archivedState),
                "{\"contentLength\":" + archivedState.length() + "}", 1_782_000_000_000L
        ));
        String archivedStateHash = hashState(archivedState);
        String archivedBlock = stateBlock(archivedState, archivedStateHash, 4, 1);
        String archivedBlockHash = sha256(archivedBlock);
        String archivedEffective = """
                {"protocol":"rd-agent-effective-context/v1","taskId":"%s","stageRunId":"%s","role":"QA_AGENT","attemptNo":1,"injectionSequence":1,"stateSequence":4,"stateHash":"%s","promptHash":"%s","blockHash":"%s","injectedBlock":%s,"injectedAt":"2026-07-01T00:00:00Z","idempotencyKey":"%s","bytes":%d,"compositionOrder":["PROMPT_SNAPSHOT","AGENT_STATE_BLOCK"]}
                """.formatted(
                task.taskId(), archived.stageRunId(), archivedStateHash, archivedPromptHash, archivedBlockHash,
                jsonString(archivedBlock), sha256(archived.stageRunId() + ":1:" + archivedBlockHash),
                archivedBlock.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        ).strip();
        artifactStore.save(new AgentStageArtifact(
                "effective-archived-1001", archived.stageRunId(), task.taskId(), AgentRole.QA_AGENT,
                "AGENT_EFFECTIVE_CONTEXT", "rd://effective", "effective", archivedEffective,
                sha256(archivedEffective), "{\"contentLength\":" + archivedEffective.length() + "}",
                1_782_000_000_100L
        ));

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/role-prompts", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stagePrompts[0].latestState.stale", is(true)))
                .andExpect(jsonPath("$.stagePrompts[0].latestState.staleReason", containsString("30000")))
                .andExpect(jsonPath("$.stagePrompts[1].latestState.source", is("ARCHIVED_ARTIFACT")))
                .andExpect(jsonPath("$.stagePrompts[1].latestState.finalized", is(true)))
                .andExpect(jsonPath("$.stagePrompts[1].latestState.stale", is(false)))
                .andExpect(jsonPath("$.stagePrompts[1].effectiveContext.source", is("ARCHIVED_ARTIFACT")))
                .andExpect(jsonPath("$.stagePrompts[1].effectiveContext.finalized", is(true)))
                .andExpect(jsonPath("$.stagePrompts[1].effectiveContext.stale", is(false)))
                .andExpect(jsonPath("$.stagePrompts[1].effectiveContext.injectedBlockHash", is(archivedBlockHash)));
    }

    @Test
    void shouldKeepLegacyAndStateOnlyAttemptsExplicitlyUnavailable() throws Exception {
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "Legacy 状态边界", "P2", "https://github.com/acme/repo.git", "acme", "repo", "main",
                "不得合成状态", List.of("legacy unavailable"), false
        ));
        AgentStageRun legacy = stageRunStore.save(AgentStageRun.pending(
                "stage-legacy-1001", task.taskId(), AgentRole.REQUIREMENT_REVIEWER, 1,
                task.taskId() + ":REQUIREMENT_REVIEWER:1", 1L
        ));
        String legacyProfile = "{\"capabilities\":[],\"runtimeType\":\"PI\"}";
        profileSnapshotStore.saveIfAbsent(new AgentExecutionProfileSnapshot(
                "profile-legacy", legacy.stageRunId(), task.taskId(), legacy.role().name(), 1,
                AgentRuntimeType.PI, legacyProfile, AgentExecutionProfileSnapshot.sha256(legacyProfile), 1L
        ));
        String prompt = "# 当前职责\n\n评审需求是否可执行。";
        artifactStore.save(new AgentStageArtifact(
                "prompt-legacy-1001",
                legacy.stageRunId(),
                task.taskId(),
                AgentRole.REQUIREMENT_REVIEWER,
                "PROMPT_SNAPSHOT",
                "rd-agent-stage://stage-legacy-1001/prompt",
                "REQUIREMENT_REVIEWER prompt snapshot",
                prompt,
                sha256(prompt),
                "{\"contentLength\":" + prompt.length() + "}",
                1L
        ));
        stageRunStore.save(legacy.withPromptArtifactId("prompt-legacy-1001", 1L));

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/role-prompts", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stagePrompts[0].prompt.available", is(true)))
                .andExpect(jsonPath("$.stagePrompts[0].prompt.contentPreview", containsString("当前职责")))
                .andExpect(jsonPath("$.stagePrompts[0].latestState.available", is(false)))
                .andExpect(jsonPath("$.stagePrompts[0].latestState.unavailableReason", containsString("PI_AGENT_STATE_V2")))
                .andExpect(jsonPath("$.stagePrompts[0].effectiveContext.available", is(false)));
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

    private void savePiV2Profile(AgentStageRun stage) {
        String profileJson = "{\"capabilities\":[\"PI_AGENT_STATE_V2\"],\"runtimeType\":\"PI\"}";
        profileSnapshotStore.saveIfAbsent(new AgentExecutionProfileSnapshot(
                "profile-" + stage.stageRunId(), stage.stageRunId(), stage.taskId(), stage.role().name(),
                stage.attemptNo(), AgentRuntimeType.PI, profileJson,
                AgentExecutionProfileSnapshot.sha256(profileJson), 1_783_000_000_000L
        ));
    }

    private static String stateJson(String taskId, String stageRunId, long sequence, String goal) {
        return stateJson(taskId, stageRunId, "CODING_AGENT", sequence, goal);
    }

    private static String stateJson(
            String taskId,
            String stageRunId,
            String role,
            long sequence,
            String goal
    ) {
        try {
            return AgentStateV2Codec.canonicalize(new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                    {
                      "protocol":"rd-agent-state/v2",
                      "sequence":%d,
                      "taskId":"%s",
                      "stageRunId":"%s",
                      "role":"%s",
                      "attemptNo":1,
                      "runtimeType":"PI",
                      "profileSnapshotId":"profile-%s",
                      "currentGoal":"%s",
                      "taskStartedAtEpochMillis":1783000000000,
                      "stageStartedAtEpochMillis":1783000000000,
                      "phase":"EXECUTING",
                      "budget":{"availability":"UNKNOWN","model":"","estimatorVersion":""},
                      "todos":[{"todoId":"todo-1","owner":"HOST","kind":"ACCEPTANCE","title":"完成验收","status":"IN_PROGRESS","required":true,"acceptanceCriteriaId":"ac-1","acceptanceContentHash":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","evidenceArtifactIds":[]}],
                      "generatedAtEpochMillis":1783000090000
                    }
                    """.formatted(sequence, taskId, stageRunId, role, stageRunId, goal)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String hashState(String stateJson) {
        try {
            return AgentStateV2Codec.hash(new com.fasterxml.jackson.databind.ObjectMapper().readTree(stateJson));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String stateBlock(
            String stateJson,
            String stateHash,
            long stateSequence,
            long injectionSequence
    ) {
        return "<rd-agent-state protocol=\"rd-agent-state/v2\" state-sequence=\"" + stateSequence
                + "\" injection-sequence=\"" + injectionSequence + "\" state-hash=\"" + stateHash
                + "\">\n" + stateJson + "\n</rd-agent-state>";
    }

    private static String sha256(String value) {
        return "sha256:" + AgentExecutionProfileSnapshot.sha256(value);
    }

    private static String jsonString(String value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void savePromptStage(
            AgentStageArtifactStore store,
            String taskId,
            AgentRole role,
            String stageRunId,
            String promptArtifactId
    ) {
        stageRunStore.save(new AgentStageRun(
                stageRunId, taskId, role, AgentStageStatus.SUCCEEDED, 1,
                taskId + ":" + role.name() + ":1", "", promptArtifactId, "", "pi", "[]", "{}", "", "",
                1_783_000_000_000L, 1_783_000_090_000L, 1_783_000_000_000L, 1_783_000_090_000L
        ));
        store.save(new AgentStageArtifact(
                promptArtifactId, stageRunId, taskId, role, "PROMPT_SNAPSHOT",
                "rd-agent-stage://" + stageRunId + "/prompt", role.name() + " prompt",
                "# " + role.name(), "sha256:" + promptArtifactId, "{}", 1_783_000_000_010L
        ));
    }

    private static final class CountingAgentStageArtifactStore implements AgentStageArtifactStore {
        private final AgentStageArtifactStore delegate;
        private final AtomicInteger listByTaskCalls = new AtomicInteger();

        private CountingAgentStageArtifactStore(AgentStageArtifactStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public AgentStageArtifact save(AgentStageArtifact artifact) {
            return delegate.save(artifact);
        }

        @Override
        public AgentStageArtifact saveImmutable(AgentStageArtifact artifact) {
            return delegate.saveImmutable(artifact);
        }

        @Override
        public List<AgentStageArtifact> listByTask(String taskId) {
            listByTaskCalls.incrementAndGet();
            return delegate.listByTask(taskId);
        }

        @Override
        public int deleteByTaskAndTypes(String taskId, Set<String> artifactTypes) {
            return delegate.deleteByTaskAndTypes(taskId, artifactTypes);
        }
    }
}
