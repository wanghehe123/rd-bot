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
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdTaskPage;
import com.wish.rd.rag.runtime.model.RdTaskQuery;
import com.wish.rd.rag.runtime.model.RdTaskStatus;

class RagStreamTaskRegistryTest {

    @Test
    void shouldFilterTaskPageByProjectId() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generatorWithMovingClock());
        RdBugFixTask projectOne = registry.createTaskManually(
                "ticket-1", "项目一问题", "项目一问题", "P1", "", "project-1", "p1", "项目一",
                "https://example.test/p1.git", "example", "p1", "main");
        registry.createTaskManually(
                "ticket-2", "项目二问题", "项目二问题", "P1", "", "project-2", "p2", "项目二",
                "https://example.test/p2.git", "example", "p2", "main");

        RdTaskPage page = registry.queryTasks(new RdTaskQuery(
                "BUG_FIX", null, null, "project-1", null, null, 1, 20));

        assertEquals(1, page.total());
        assertEquals(projectOne.taskId(), page.records().getFirst().taskId());
    }

    @Test
    void shouldPersistAndReloadBugFixTaskStateMachine() {
        InMemoryRdTaskStore store = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(store, new InMemoryRdTaskStatusEventStore(), generatorWithMovingClock());

        RdBugFixTask created = registry.createBugFixTask(ticket(), "P0");
        RdBugFixTask searching = registry.markSearching(created.taskId(), "RAG 检索中");
        RdBugFixTask executing = registry.markExecuting(created.taskId(), "请修复 OrderService.create");
        RdBugFixTask committed = registry.markCommitted(
                created.taskId(),
                "https://github.example/rd/pr/100",
                "{\"taskId\":\"" + created.taskId() + "\"}"
        );

        RagStreamTaskRegistry reloaded = new RagStreamTaskRegistry(store, new InMemoryRdTaskStatusEventStore(), generatorWithMovingClock());
        RdBugFixTask found = reloaded.get(created.taskId());

        assertEquals(RdTaskStatus.CREATED, created.status());
        assertEquals(RdTaskStatus.SEARCHING, searching.status());
        assertEquals(RdTaskStatus.EXECUTING, executing.status());
        assertEquals(RdTaskStatus.COMMITTED, committed.status());
        assertEquals("FS-1001", found.ticketId());
        assertEquals("P0", found.priority());
        assertEquals("https://github.example/rd/pr/100", found.pullRequestUrl());
        assertEquals(RdTaskStatus.COMMITTED, found.status());
    }

    @Test
    void shouldAllowRejectedTaskToReturnToExecutingForRdReviewChanges() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generatorWithMovingClock());
        RdBugFixTask created = registry.createBugFixTask(ticket(), "P1");

        registry.markSearching(created.taskId(), "RAG 检索中");
        registry.markExecuting(created.taskId(), "初版修复 Prompt");
        registry.markCommitted(created.taskId(), "https://github.example/rd/pr/101", "{\"ok\":true}");
        RdBugFixTask rejected = registry.markRejected(created.taskId(), "RD 评审不通过，需要补充单测");
        RdBugFixTask executingAgain = registry.markExecuting(created.taskId(), "按 RD 评审意见补充单测");

        assertEquals(RdTaskStatus.REJECTED, rejected.status());
        assertEquals(RdTaskStatus.EXECUTING, executingAgain.status());
        assertEquals("RD 评审不通过，需要补充单测", rejected.errorMessage());
    }

    @Test
    void shouldKeepExecutionResultJsonWhenRejectingFailedExecution() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generatorWithMovingClock());
        RdBugFixTask created = registry.createBugFixTask(ticket(), "P1");
        String executionResultJson = "{\"status\":\"FAILED\",\"errorMessage\":\"git clone failed\"}";

        registry.markSearching(created.taskId(), "RAG 检索中");
        registry.markExecuting(created.taskId(), "请修复 OrderService.create");
        RdBugFixTask rejected = registry.markRejected(
                created.taskId(),
                "修复执行失败: status=FAILED, reason=git clone failed",
                executionResultJson
        );

        assertEquals(RdTaskStatus.REJECTED, rejected.status());
        assertEquals(executionResultJson, rejected.executionResultJson());
        assertEquals("修复执行失败: status=FAILED, reason=git clone failed", rejected.errorMessage());
    }

    @Test
    void shouldAllowSearchingTaskToBecomeRetryableAfterRagFailure() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generatorWithMovingClock());
        RdBugFixTask created = registry.createBugFixTask(ticket(), "P1");

        registry.markSearching(created.taskId(), "RAG 检索中");
        RdBugFixTask failed = registry.markFailedRetryable(created.taskId(), "RAG unavailable");
        RdBugFixTask searchingAgain = registry.markSearching(created.taskId(), "RAG 重试中");

        assertEquals(RdTaskStatus.FAILED_RETRYABLE, failed.status());
        assertEquals("RAG unavailable", failed.errorMessage());
        assertEquals(RdTaskStatus.SEARCHING, searchingAgain.status());
    }

    @Test
    void shouldRejectIllegalStateTransition() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generatorWithMovingClock());
        RdBugFixTask created = registry.createBugFixTask(ticket(), "P2");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> registry.markCommitted(created.taskId(), "https://github.example/rd/pr/102", "{}")
        );

        assertEquals("illegal task status transition: CREATED -> COMMITTED", exception.getMessage());
    }

    private TicketSnapshot ticket() {
        return new TicketSnapshot(
                "FS-1001",
                "支付系统下单接口 500",
                "金额为空时 OrderService.create 写入订单失败",
                List.of("payment", "orders.amount"),
                Instant.parse("2026-06-21T00:00:00Z")
        );
    }

    private SnowflakeIdGenerator generatorWithMovingClock() {
        AtomicLong now = new AtomicLong(1_780_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }
}
