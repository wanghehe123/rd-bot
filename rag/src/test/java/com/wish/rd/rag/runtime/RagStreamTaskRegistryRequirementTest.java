package com.wish.rd.rag.runtime;

import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private SnowflakeIdGenerator generator() {
        AtomicLong now = new AtomicLong(1_782_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }
}
