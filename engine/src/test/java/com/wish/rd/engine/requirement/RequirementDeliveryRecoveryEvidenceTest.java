package com.wish.rd.engine.requirement;

import com.wish.rd.engine.agent.AgentStagePlanner;
import com.wish.rd.engine.agent.AgentWorkflowAlertSinkPort;
import com.wish.rd.engine.agent.WorkflowExperienceStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.requirement.model.RequirementExecutionRequest;
import com.wish.rd.engine.requirement.model.RequirementExecutionResult;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryCheckpointStore;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.retry.model.TaskRetryCommand;
import com.wish.rd.engine.retry.model.TaskRetryPoint;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.context.RoleContextBuilder;
import com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.impl.InMemoryTaskMaterialStore;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementDeliveryRecoveryEvidenceTest {

    @Test
    void putsCheckpointEvidenceOnlyInNewFailedAndDownstreamRolePrompts() {
        AtomicLong now = new AtomicLong(1_785_000_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        RdRequirementTask task = recoveringTask();
        taskStore.saveRequirementTask(task);
        InMemoryTaskMaterialStore materials = new InMemoryTaskMaterialStore();
        materials.save(material("material-base", task.taskId(), "需求正文", "订单允许催单。"));
        materials.save(material("material-recovery", task.taskId(), "账号澄清", "顾客账号使用 user1。"));

        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        InMemoryAgentStageArtifactStore artifacts = new InMemoryAgentStageArtifactStore();
        AgentStageRun reviewer = completedStage("reviewer-1", task.taskId(), AgentRole.REQUIREMENT_REVIEWER, 1,
                "prompt-reviewer-1", "result-reviewer-1");
        AgentStageRun oldArchitecture = AgentStageRun.pending(
                        "architecture-1", task.taskId(), AgentRole.SOLUTION_ARCHITECT, 1,
                        task.taskId() + ":SOLUTION_ARCHITECT:1", 20L)
                .withStatus(AgentStageStatus.FAILED_NEEDS_HUMAN, "NEED_INFO", "缺少账号信息", 30L);
        stages.save(reviewer);
        stages.save(oldArchitecture);
        stages.save(AgentStageRun.pending("architecture-2", task.taskId(), AgentRole.SOLUTION_ARCHITECT, 2,
                task.taskId() + ":SOLUTION_ARCHITECT:2", 40L));
        stages.save(AgentStageRun.pending("coding-1", task.taskId(), AgentRole.CODING_AGENT, 1,
                task.taskId() + ":CODING_AGENT:1", 40L));
        stages.save(AgentStageRun.pending("qa-1", task.taskId(), AgentRole.QA_AGENT, 1,
                task.taskId() + ":QA_AGENT:1", 40L));
        artifacts.save(new AgentStageArtifact(
                "prompt-reviewer-1", "reviewer-1", task.taskId(), AgentRole.REQUIREMENT_REVIEWER,
                "PROMPT_SNAPSHOT", "", "旧评审 Prompt", "旧评审上下文", "sha256:old-prompt", "{}", 20L));
        artifacts.save(new AgentStageArtifact(
                "result-reviewer-1", "reviewer-1", task.taskId(), AgentRole.REQUIREMENT_REVIEWER,
                "RESULT_JSON", "", "旧评审结果", "{\"decision\":\"APPROVED\"}", "sha256:old-result", "{}", 20L));

        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        TaskRetryPoint point = new TaskRetryPoint(task.taskId(), TaskFailurePhase.AGENT_ROLE,
                AgentRole.SOLUTION_ARCHITECT, "architecture-1", "", "", "缺少账号信息", 20L);
        TaskRetryCheckpoint checkpoint = checkpoints.createOrGet(TaskRetryCheckpoint.created(
                "checkpoint-1", point, 1, task.taskId() + ":20:AGENT_ROLE:SOLUTION_ARCHITECT",
                RdTaskStatus.FAILED_NEEDS_HUMAN,
                new TaskRetryCommand("architecture-1", "顾客账号使用 user1", List.of("material-recovery")),
                50L
        )).checkpoint();
        checkpoints.transition(checkpoint.checkpointId(), TaskRetryCheckpointStatus.CREATED,
                TaskRetryCheckpointStatus.DISPATCHED, "", 60L);

        List<RequirementExecutionRequest> requests = new CopyOnWriteArrayList<>();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materials,
                request -> {
                    requests.add(request);
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.failure(task.taskId(), "stop after coding prompt", "{\"status\":\"FAILED\"}");
                    }
                    return RequirementExecutionResult.success(task.taskId(), "architecture ready", "", "{\"status\":\"SUCCESS\"}");
                },
                new RequirementContextBuilder(), new RequirementPlanGenerator(), new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(ids::nextIdString), stages, artifacts, new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(), AgentWorkflowAlertSinkPort.noop(), WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(), command -> { throw new AssertionError("PR must not be published"); }
        );
        engine.setTaskRetryCheckpointStore(checkpoints);

        engine.submit(task.taskId());

        assertEquals(List.of(AgentRole.SOLUTION_ARCHITECT, AgentRole.CODING_AGENT),
                requests.stream().map(RequirementExecutionRequest::role).toList());
        assertTrue(requests.stream().allMatch(request -> request.prompt().contains("# 本次失败恢复补充")));
        assertTrue(requests.stream().allMatch(request -> request.prompt().contains("顾客账号使用 user1")));
        assertTrue(requests.stream().allMatch(request -> request.prompt().contains("账号澄清")));
        assertFalse(artifacts.listByTask(task.taskId()).stream()
                .filter(artifact -> artifact.artifactId().equals("prompt-reviewer-1"))
                .findFirst().orElseThrow().contentPreview().contains("# 本次失败恢复补充"));
    }

    private static RdRequirementTask recoveringTask() {
        return new RdRequirementTask(
                "task-1", "REQUIREMENT", "ADMIN", "source", "", "P1", RdTaskStatus.RECOVERING,
                "订单催单", "project-1", "waimai", "外卖项目", "https://github.example/waimai",
                "owner", "repo", "main", "feature/reminder", "实现催单", "[\"可催单\"]",
                "prompt", "{}", "", "缺少账号信息", 10L, 20L, false
        );
    }

    private static TaskMaterial material(String id, String taskId, String title, String content) {
        return new TaskMaterial(id, taskId, TaskMaterialType.REQUIREMENT_DOC, TaskMaterialSourceType.MANUAL_TEXT,
                title, "", "text/plain", "sha256:" + id, content, "", "", "", "{}", 10L, 10L);
    }

    private static AgentStageRun completedStage(
            String id,
            String taskId,
            AgentRole role,
            int attemptNo,
            String promptArtifactId,
            String resultArtifactId
    ) {
        return AgentStageRun.pending(id, taskId, role, attemptNo, taskId + ":" + role + ":" + attemptNo, 10L)
                .withPromptArtifactId(promptArtifactId, 20L)
                .withResultArtifactId(resultArtifactId, 20L)
                .withStatus(AgentStageStatus.SUCCEEDED, "", "", 20L);
    }
}
