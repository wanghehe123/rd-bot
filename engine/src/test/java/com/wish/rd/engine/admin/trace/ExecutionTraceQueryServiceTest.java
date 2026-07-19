package com.wish.rd.engine.admin.trace;

import com.wish.rd.engine.admin.trace.model.ExecutionTracePage;
import com.wish.rd.engine.admin.trace.model.ExecutionTraceQuery;

import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionTraceQueryServiceTest {

    private RagStreamTaskRegistry registry;
    private AgentStageRunStore stageRunStore;

    @BeforeEach
    void setUp() {
        registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator());
        stageRunStore = new InMemoryAgentStageRunStore();
    }

    @Test
    void shouldReturnLatestAttemptForTheSelectedProjectAndProvider() {
        RdRequirementTask projectTask = createRequirement("project-1", "交付项目一");
        registry.markRequirementMaterialCollecting(projectTask.taskId(), "收集材料");
        registry.markRequirementMaterialReady(projectTask.taskId(), "材料就绪");
        registry.markRequirementContextBuilding(projectTask.taskId(), "构建上下文");
        registry.markRequirementContextReady(projectTask.taskId(), "{}");
        registry.markRequirementPlanGenerating(projectTask.taskId(), "生成计划");
        registry.markRequirementPlanGenerated(projectTask.taskId(), "{}");
        registry.markRequirementWaitingPolicy(projectTask.taskId(), "{}");
        registry.markRequirementExecuting(projectTask.taskId(), "开始执行");
        stageRunStore.save(run(projectTask.taskId(), AgentRole.REQUIREMENT_REVIEWER,
                AgentStageStatus.FAILED_NEEDS_HUMAN, 1, "long-cat", "模型响应格式异常"));
        stageRunStore.save(run(projectTask.taskId(), AgentRole.REQUIREMENT_REVIEWER,
                AgentStageStatus.SUCCEEDED, 2, "long-cat", ""));
        stageRunStore.save(run(projectTask.taskId(), AgentRole.SOLUTION_ARCHITECT,
                AgentStageStatus.RUNNING, 1, "long-cat", ""));

        RdRequirementTask otherProjectTask = createRequirement("project-2", "交付项目二");
        stageRunStore.save(run(otherProjectTask.taskId(), AgentRole.REQUIREMENT_REVIEWER,
                AgentStageStatus.RUNNING, 1, "minimax", ""));

        ExecutionTraceQueryService service = new ExecutionTraceQueryService(registry, stageRunStore);

        ExecutionTracePage page = service.query(new ExecutionTraceQuery(
                "project-1", "REQUIREMENT", "", "SOLUTION_ARCHITECT", "long-cat", "", 1, 20));

        assertEquals(1L, page.total());
        assertEquals(projectTask.taskId(), page.records().getFirst().taskId());
        assertEquals("SOLUTION_ARCHITECT", page.records().getFirst().currentRole());
        assertEquals("RUNNING", page.records().getFirst().currentStageStatus());
        assertEquals("long-cat", page.records().getFirst().providerName());
        assertEquals(1, page.records().getFirst().retryCount());
    }

    private RdRequirementTask createRequirement(String projectId, String title) {
        return registry.createRequirementTask(new CreateRequirementTaskCommand(
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
                false));
    }

    private AgentStageRun run(
            String taskId,
            AgentRole role,
            AgentStageStatus status,
            int attemptNo,
            String provider,
            String errorMessage
    ) {
        long now = 1_783_700_000_000L + attemptNo;
        return new AgentStageRun(
                taskId + "-" + role.name() + "-" + attemptNo,
                taskId,
                role,
                status,
                attemptNo,
                taskId + ":" + role.name() + ":" + attemptNo,
                "",
                "",
                "",
                provider,
                "[]",
                "{}",
                errorMessage.isBlank() ? "" : "PROVIDER_RESPONSE_INVALID",
                errorMessage,
                now,
                now,
                now,
                status.isTerminal() ? now + 1 : 0L);
    }
}
