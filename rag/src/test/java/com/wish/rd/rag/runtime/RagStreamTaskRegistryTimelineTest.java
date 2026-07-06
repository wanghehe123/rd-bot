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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdTaskEventTrigger;
import com.wish.rd.rag.runtime.model.RdTaskPage;
import com.wish.rd.rag.runtime.model.RdTaskQuery;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.RdTaskStatusEvent;

/**
 * {@link RagStreamTaskRegistry} 状态事件时间线与管理能力单测。
 *
 * <p>覆盖 FR-1 全链路追踪（事件写入、序、耗时）与 FR-2 暂停 / 恢复、修改、删除、查询。
 */
class RagStreamTaskRegistryTimelineTest {

    @Test
    void shouldRecordTimelineForStateMachineProgress() {
        RagStreamTaskRegistry registry = newRegistry();
        RdBugFixTask created = registry.createBugFixTask(ticket(), "P1");

        registry.markSearching(created.taskId(), "RAG 检索中");
        registry.markExecuting(created.taskId(), "执行修复");
        registry.markCommitted(created.taskId(), "https://github.example/pr/1", "{\"ok\":true}");
        registry.markMerged(created.taskId());

        List<RdTaskStatusEvent> timeline = registry.timeline(created.taskId());
        assertEquals(5, timeline.size());
        // 状态序：CREATED → SEARCHING → EXECUTING → COMMITTED → MERGED
        assertEquals(RdTaskStatus.CREATED.name(), timeline.get(0).status());
        assertEquals(RdTaskStatus.SEARCHING.name(), timeline.get(1).status());
        assertEquals(RdTaskStatus.EXECUTING.name(), timeline.get(2).status());
        assertEquals(RdTaskStatus.COMMITTED.name(), timeline.get(3).status());
        assertEquals(RdTaskStatus.MERGED.name(), timeline.get(4).status());
        // 触发源：create 走 SYSTEM（编排），mark* 走 SYSTEM
        assertEquals(RdTaskEventTrigger.SYSTEM.name(), timeline.get(0).trigger());
        // 进入时刻升序
        for (int i = 1; i < timeline.size(); i++) {
            assertTrue(timeline.get(i).enteredAtEpochMillis() >= timeline.get(i - 1).enteredAtEpochMillis(),
                    "timeline must be non-decreasing by enteredAt");
            assertTrue(timeline.get(i).durationMillis() >= 0L, "duration must be non-negative");
        }
    }

    @Test
    void shouldKeepRejectedAndExecutingEventsAcrossMultipleRounds() {
        RagStreamTaskRegistry registry = newRegistry();
        RdBugFixTask created = registry.createBugFixTask(ticket(), "P2");
        registry.markSearching(created.taskId(), "");
        registry.markExecuting(created.taskId(), "");
        registry.markRejected(created.taskId(), "RD 评审不通过");
        registry.markExecuting(created.taskId(), "按评审意见补单测");
        registry.markCommitted(created.taskId(), "https://github.example/pr/2", "{}");
        registry.markMerged(created.taskId());

        List<RdTaskStatusEvent> timeline = registry.timeline(created.taskId());
        // CREATED, SEARCHING, EXECUTING, REJECTED, EXECUTING, COMMITTED, MERGED
        assertEquals(7, timeline.size());
        assertEquals(RdTaskStatus.REJECTED.name(), timeline.get(3).status());
        assertEquals(RdTaskStatus.EXECUTING.name(), timeline.get(4).status());
        // 打回的 REJECTED 事件应携带 errorMessage 作为 message
        assertEquals("RD 评审不通过", timeline.get(3).message());
    }

    @Test
    void shouldPauseAndResumeWithEventsAndFlag() {
        RagStreamTaskRegistry registry = newRegistry();
        RdBugFixTask created = registry.createBugFixTask(ticket(), "P1");
        registry.markSearching(created.taskId(), "");

        RdBugFixTask paused = registry.pause(created.taskId(), "临时干预");
        assertTrue(paused.paused());
        RdBugFixTask resumed = registry.resume(created.taskId(), "恢复运行");
        assertFalse(resumed.paused());

        List<RdTaskStatusEvent> timeline = registry.timeline(created.taskId());
        List<String> statuses = timeline.stream().map(RdTaskStatusEvent::status).toList();
        assertTrue(statuses.contains(RdTaskStatusEvent.ACTION_PAUSED));
        assertTrue(statuses.contains(RdTaskStatusEvent.ACTION_RESUMED));
        assertEquals(RdTaskEventTrigger.API.name(),
                timeline.stream().filter(e -> e.status().equals(RdTaskStatusEvent.ACTION_PAUSED))
                        .findFirst().orElseThrow().trigger());
    }

    @Test
    void shouldUpdateEditableFieldsWithoutStatusChange() {
        RagStreamTaskRegistry registry = newRegistry();
        RdBugFixTask created = registry.createBugFixTask(ticket(), "P2");

        RdBugFixTask updated = registry.updateTask(created.taskId(), "新标题", "P0", "新工单标题");

        assertEquals("新标题", updated.title());
        assertEquals("P0", updated.priority());
        assertEquals("新工单标题", updated.ticketTitle());
        assertEquals(created.status(), updated.status());
    }

    @Test
    void shouldPageAndFilterTasks() {
        RagStreamTaskRegistry registry = newRegistry();
        RdBugFixTask a = registry.createBugFixTask(new TicketSnapshot("T-A", "支付下单 500", "", List.of(), Instant.now()), "P0");
        RdBugFixTask b = registry.createBugFixTask(new TicketSnapshot("T-B", "退款金额计算错误", "", List.of(), Instant.now()), "P1");
        registry.markSearching(b.taskId(), "");

        // 关键词过滤
        RdTaskPage paymentPage = registry.queryBugFixTasks(new RdTaskQuery(null, null, null, "支付", 1, 10));
        assertEquals(1, paymentPage.total());
        assertEquals(a.taskId(), paymentPage.records().get(0).taskId());

        // 状态过滤
        RdTaskPage searchingPage = registry.queryBugFixTasks(new RdTaskQuery(RdTaskStatus.SEARCHING.name(), null, null, null, 1, 10));
        assertEquals(1, searchingPage.total());
        assertEquals(b.taskId(), searchingPage.records().get(0).taskId());

        // 全量
        RdTaskPage all = registry.queryBugFixTasks(new RdTaskQuery(null, null, null, null, 1, 10));
        assertEquals(2, all.total());
    }

    @Test
    void shouldLogicallyDeleteTaskAndClearTimeline() {
        RagStreamTaskRegistry registry = newRegistry();
        RdBugFixTask created = registry.createBugFixTask(ticket(), "P2");
        registry.markSearching(created.taskId(), "");

        assertTrue(registry.deleteTask(created.taskId()));
        // 重复删除返回 false
        assertFalse(registry.deleteTask(created.taskId()));
        // 列表不再可见（DELETED 被过滤）
        RdTaskPage all = registry.queryBugFixTasks(new RdTaskQuery(null, null, null, null, 1, 10));
        assertEquals(0, all.total());
        // 时间线被清理
        assertTrue(registry.timeline(created.taskId()).isEmpty());
    }

    @Test
    void shouldRejectIllegalTransition() {
        RagStreamTaskRegistry registry = newRegistry();
        RdBugFixTask created = registry.createBugFixTask(ticket(), "P2");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> registry.markCommitted(created.taskId(), "https://github.example/pr/3", "{}"));
        assertTrue(exception.getMessage().contains("CREATED -> COMMITTED"));
    }

    @Test
    void shouldTolerateNullEventStoreForBackwardCompatibility() {
        // eventStore 为 null（旧兼容构造）时不应抛异常，时间线返回空
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(new InMemoryRdTaskStore(), generator());
        RdBugFixTask created = registry.createBugFixTask(ticket(), "P1");
        registry.markSearching(created.taskId(), "");
        assertTrue(registry.timeline(created.taskId()).isEmpty());
    }

    private RagStreamTaskRegistry newRegistry() {
        return new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
    }

    private TicketSnapshot ticket() {
        return new TicketSnapshot("FS-2002", "退款金额计算错误", "orders.amount 为空", List.of("refund"), Instant.now());
    }

    private SnowflakeIdGenerator generator() {
        AtomicLong now = new AtomicLong(1_780_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }
}
