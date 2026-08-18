package com.wish.rd.engine.requirement;

import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.requirement.model.RequirementDeliveryResult;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.impl.InMemoryTaskMaterialStore;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementExecutionProfileFailureTest {

    @Test
    void shouldFailBeforeRunningWhenProfileResolutionIsInvalid() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator()
        );
        InMemoryTaskMaterialStore materials = new InMemoryTaskMaterialStore();
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "需求", "P1", "https://github.com/acme/repo.git", "acme", "repo", "main",
                "完成需求", List.of("测试通过"), false
        ));
        materials.save(new TaskMaterial(
                "material-1", task.taskId(), TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT, "需求", "", "text/plain", "hash",
                "需求正文", "", "", "", "{}", 1L, 1L
        ));
        AgentStageRunStore stageRuns = new InMemoryAgentStageRunStore();
        AtomicInteger executorCalls = new AtomicInteger();
        AtomicInteger stageIds = new AtomicInteger();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materials,
                request -> {
                    executorCalls.incrementAndGet();
                    throw new AssertionError("executor must not be called");
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new com.wish.rd.engine.agent.AgentStagePlanner(() -> "stage-" + stageIds.incrementAndGet()),
                stageRuns,
                new com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore(),
                new com.wish.rd.rag.context.RoleContextBuilder(),
                new com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore(),
                com.wish.rd.engine.agent.AgentWorkflowAlertSinkPort.noop(),
                com.wish.rd.engine.agent.WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                RequirementPullRequestPublisherPort.unavailable()
        );
        engine.setExecutionProfileResolver((resolvedTask, role, stageRunId, attemptNo) -> {
            throw new IllegalStateException("AGENT_RUNTIME_PROFILE_INVALID: disabled profile");
        });

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(com.wish.rd.rag.runtime.model.RdTaskStatus.FAILED_NEEDS_HUMAN, result.status());
        assertTrue(result.errorMessage().contains("AGENT_RUNTIME_PROFILE_INVALID: disabled profile"));
        assertEquals(0, executorCalls.get());
        AgentStageRun reviewer = stageRuns.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.REQUIREMENT_REVIEWER)
                .findFirst()
                .orElseThrow();
        assertEquals(AgentStageStatus.FAILED_NEEDS_HUMAN, reviewer.status());
        assertEquals("AGENT_RUNTIME_PROFILE_INVALID", reviewer.errorCategory());
    }
}
