package com.wish.rd.rag.runtime;

import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;

import com.wish.rd.adapter.model.TicketSnapshot;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskPage;
import com.wish.rd.rag.runtime.model.RdTaskQuery;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.RdTaskType;

/**
 * {@link RagStreamTaskRegistry} 需求任务能力单测。
 *
 * <p>需求交付任务必须作为独立任务类型进入管理台查询，不能伪装成 BugFix 工单任务。
 */
class RagStreamTaskRegistryRequirementTest {

    @Test
    void shouldCreateRequirementTaskAndFilterByTaskType() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        registry.createBugFixTask(new TicketSnapshot(
                "FS-BUG-1",
                "支付下单 500",
                "",
                List.of(),
                Instant.now()), "P1");

        RdRequirementTask requirement = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "增加订单催单功能",
                "P1",
                "https://github.com/example/waimai.git",
                "example",
                "waimai",
                "main",
                "用户可在订单详情页催单，商家端收到提醒",
                List.of("前端构建通过", "新增接口测试通过"),
                false
        ));

        RdTaskPage page = registry.queryTasks(new RdTaskQuery(
                RdTaskType.REQUIREMENT.name(),
                null,
                null,
                null,
                "催单",
                1,
                10
        ));

        assertEquals(RdTaskType.REQUIREMENT.name(), requirement.taskType());
        assertEquals(RdTaskStatus.CREATED, requirement.status());
        assertEquals("https://github.com/example/waimai.git", requirement.repositoryUrl());
        assertEquals("main", requirement.baseBranch());
        assertEquals(1, page.total());
        assertEquals(requirement.taskId(), page.records().get(0).taskId());
        assertEquals(requirement.taskId(), registry.getTask(requirement.taskId()).taskId());
        assertEquals(RdTaskStatus.CREATED.name(), registry.timeline(requirement.taskId()).get(0).status());
    }

    @Test
    void shouldControlRequirementCancellationRecoveryAndDeadLetterIdempotently() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generator());

        RdRequirementTask cancellable = registry.createRequirementTask(command("取消中的需求"));
        registry.markRequirementMaterialCollecting(cancellable.taskId(), "收集材料");
        assertEquals(RdTaskStatus.CANCELLED, registry.cancelTask(cancellable.taskId(), "人工停止").status());
        assertSame(registry.getTask(cancellable.taskId()), registry.cancelTask(cancellable.taskId(), "重复停止"));

        RdRequirementTask recoverable = registry.createRequirementTask(command("恢复中的需求"));
        registry.markRequirementMaterialCollecting(recoverable.taskId(), "收集材料");
        registry.markRequirementMaterialReady(recoverable.taskId(), "材料就绪");
        registry.markRequirementFailedNeedsHuman(recoverable.taskId(), "{}", "上下文失败");
        assertEquals(RdTaskStatus.RECOVERING,
                registry.markRecovering(recoverable.taskId(), "人工恢复").status());

        RdRequirementTask deadLetter = registry.createRequirementTask(command("死信需求"));
        assertEquals(RdTaskStatus.DEAD_LETTERED,
                registry.markDeadLettered(deadLetter.taskId(), "超过派发上限").status());
    }

    private CreateRequirementTaskCommand command(String title) {
        return new CreateRequirementTaskCommand(
                title, "P1", "https://github.com/example/waimai.git", "example", "waimai", "main",
                "交付可验证", List.of("测试通过"), false);
    }

    private SnowflakeIdGenerator generator() {
        AtomicLong now = new AtomicLong(1_782_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }
}
