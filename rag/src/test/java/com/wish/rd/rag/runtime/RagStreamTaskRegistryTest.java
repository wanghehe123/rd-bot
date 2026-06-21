package com.wish.rd.rag.runtime;

import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagStreamTaskRegistryTest {

    @Test
    void shouldPersistAndReloadBugFixTaskStateMachine() {
        InMemoryRdTaskStore store = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(store, generatorWithMovingClock());

        RdBugFixTask created = registry.createBugFixTask(ticket(), "P0");
        RdBugFixTask searching = registry.markSearching(created.taskId(), "RAG 检索中");
        RdBugFixTask executing = registry.markExecuting(created.taskId(), "请修复 OrderService.create");
        RdBugFixTask committed = registry.markCommitted(
                created.taskId(),
                "https://github.example/rd/pr/100",
                "{\"taskId\":\"" + created.taskId() + "\"}"
        );

        RagStreamTaskRegistry reloaded = new RagStreamTaskRegistry(store, generatorWithMovingClock());
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
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(new InMemoryRdTaskStore(), generatorWithMovingClock());
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
    void shouldRejectIllegalStateTransition() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(new InMemoryRdTaskStore(), generatorWithMovingClock());
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
