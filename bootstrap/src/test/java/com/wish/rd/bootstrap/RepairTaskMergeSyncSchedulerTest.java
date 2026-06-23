package com.wish.rd.bootstrap;

import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.bootstrap.github.RepairTaskMergeSyncScheduler;
import com.wish.rd.engine.merge.PullRequestMergeStatus;
import com.wish.rd.engine.merge.PullRequestMergeStatusPort;
import com.wish.rd.engine.merge.RepairTaskMergeSyncEngine;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.RdBugFixTask;
import com.wish.rd.rag.runtime.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepairTaskMergeSyncSchedulerTest {

    @Test
    void shouldDelegateToMergeSyncEngine() {
        RagStreamTaskRegistry registry = registry();
        RdBugFixTask created = registry.createBugFixTask(ticket(), "P1");
        registry.markSearching(created.taskId(), "RAG");
        registry.markExecuting(created.taskId(), "Prompt");
        registry.markCommitted(created.taskId(), "https://github.com/acme/order/pull/42", "{}");
        RepairTaskMergeSyncEngine engine = new RepairTaskMergeSyncEngine(
                registry,
                pullRequestUrl -> new PullRequestMergeStatus(pullRequestUrl, "acme/order", "42", "closed", true)
        );
        RepairTaskMergeSyncScheduler scheduler = new RepairTaskMergeSyncScheduler(engine);

        scheduler.syncCommittedTasks();

        assertEquals(RdTaskStatus.MERGED, registry.get(created.taskId()).status());
    }

    private static RagStreamTaskRegistry registry() {
        AtomicLong now = new AtomicLong(1_780_000_000_000L);
        return new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new SnowflakeIdGenerator(1, 1, now::getAndIncrement)
        );
    }

    private static TicketSnapshot ticket() {
        return new TicketSnapshot(
                "FI-real-waimai-20260623",
                "外卖下单接口返回 500",
                "createOrder 字段不匹配",
                List.of("waimai"),
                Instant.parse("2026-06-23T00:00:00Z")
        );
    }
}
