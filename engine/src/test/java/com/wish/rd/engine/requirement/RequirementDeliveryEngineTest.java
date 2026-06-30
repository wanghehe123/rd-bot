package com.wish.rd.engine.requirement;

import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.InMemoryTaskMaterialStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.RdRequirementTask;
import com.wish.rd.rag.runtime.RdTaskStatus;
import com.wish.rd.rag.runtime.TaskMaterial;
import com.wish.rd.rag.runtime.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.TaskMaterialType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementDeliveryEngineTest {

    @Test
    void shouldExecuteRequirementTaskAndCommitPullRequest() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "增加订单催单功能",
                "P1",
                "https://github.com/example/waimai.git",
                "example",
                "waimai",
                "main",
                "订单详情页可以催单",
                List.of("前端构建通过"),
                false
        ));
        materialStore.save(new TaskMaterial(
                "7820000000001",
                task.taskId(),
                TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT,
                "需求正文",
                "",
                "text/markdown",
                "sha256:test",
                "用户可以在订单详情页点击催单。",
                "",
                "",
                "",
                "{}",
                1L,
                1L
        ));
        AtomicReference<RequirementExecutionRequest> captured = new AtomicReference<>();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    captured.set(request);
                    return RequirementExecutionResult.success(
                            request.taskId(),
                            "实现完成",
                            "https://github.com/example/waimai/pull/12",
                            "{\"status\":\"SUCCESS\"}"
                    );
                }
        );

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMMITTED, result.status());
        assertEquals("https://github.com/example/waimai/pull/12", result.pullRequestUrl());
        assertEquals(RdTaskStatus.COMMITTED, registry.getTask(task.taskId()).status());
        assertTrue(captured.get().prompt().contains("用户可以在订单详情页点击催单。"));
        assertEquals(RdTaskStatus.MATERIAL_READY.name(), registry.timeline(task.taskId()).get(2).status());
        assertEquals(RdTaskStatus.CONTEXT_BUILDING.name(), registry.timeline(task.taskId()).get(3).status());
        assertEquals(RdTaskStatus.CONTEXT_READY.name(), registry.timeline(task.taskId()).get(4).status());
        assertEquals(RdTaskStatus.PLAN_GENERATING.name(), registry.timeline(task.taskId()).get(5).status());
        assertEquals(RdTaskStatus.PLAN_GENERATED.name(), registry.timeline(task.taskId()).get(6).status());
        assertEquals(RdTaskStatus.WAITING_POLICY.name(), registry.timeline(task.taskId()).get(7).status());
        assertEquals(RdTaskStatus.EXECUTING.name(), registry.timeline(task.taskId()).get(8).status());
        assertTrue(captured.get().prompt().contains("# 需求摘要"));
        assertTrue(captured.get().prompt().contains("# 实现计划"));
        assertTrue(captured.get().prompt().contains("# 策略决策"));
        assertTrue(captured.get().prompt().contains("policyAction: ALLOWED"));
    }

    @Test
    void shouldStopBeforeExecutorWhenAcceptanceCriteriaMissing() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "增加订单催单功能",
                "P1",
                "https://github.com/example/waimai.git",
                "example",
                "waimai",
                "main",
                "订单详情页可以催单",
                List.of(),
                false
        ));
        materialStore.save(new TaskMaterial(
                "7820000000002",
                task.taskId(),
                TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT,
                "需求正文",
                "",
                "text/markdown",
                "sha256:test",
                "用户可以在订单详情页点击催单。",
                "",
                "",
                "",
                "{}",
                1L,
                1L
        ));
        AtomicInteger executorCalls = new AtomicInteger();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    executorCalls.incrementAndGet();
                    return RequirementExecutionResult.success(
                            request.taskId(),
                            "不应执行",
                            "https://github.com/example/waimai/pull/12",
                            "{\"status\":\"SUCCESS\"}"
                    );
                }
        );

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, result.status());
        assertEquals(0, executorCalls.get());
        assertTrue(result.errorMessage().contains("验收标准"));
        assertTrue(result.resultJson().contains("\"status\":\"NEED_INFO\""));
        assertEquals(RdTaskStatus.WAITING_POLICY.name(), registry.timeline(task.taskId()).get(7).status());
        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN.name(), registry.timeline(task.taskId()).get(8).status());
    }

    private SnowflakeIdGenerator generator() {
        AtomicLong now = new AtomicLong(1_783_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }
}
