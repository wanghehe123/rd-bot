package com.wish.rd.engine;

import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.engine.ticket.RdTaskRestartEngine;
import com.wish.rd.engine.ticket.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.RepairTicketMessage;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.RdBugFixTask;
import com.wish.rd.rag.runtime.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdTaskRestartEngineTest {

    @Test
    void shouldResumePausedExecutingTaskAndPublishSameTicketForRetry() {
        RagStreamTaskRegistry registry = registry();
        RdBugFixTask created = registry.createBugFixTask(ticket(), "P0");
        registry.markSearching(created.taskId(), "RAG 检索中");
        registry.markExecuting(created.taskId(), "prompt");
        registry.pause(created.taskId(), "人工暂停");
        AtomicReference<RepairTicketMessage> published = new AtomicReference<>();
        RdTaskRestartEngine restartEngine = new RdTaskRestartEngine(
                registry,
                message -> {
                    published.set(message);
                    return RepairQueuePublishResult.success("msg-1", "topic", message.tag());
                }
        );

        RdBugFixTask restarted = restartEngine.resumeAndRestart(created.taskId(), "管理台恢复并重启");

        assertEquals(created.taskId(), restarted.taskId());
        assertFalse(restarted.paused());
        assertEquals(RdTaskStatus.REJECTED, restarted.status());
        assertTrue(restarted.errorMessage().contains("管理台恢复并重启"));
        assertNotNull(published.get());
        assertEquals("FI-RESTART-1", published.get().ticketId());
        assertEquals("P0", published.get().priority());
        assertEquals("admin", published.get().source());
        assertEquals("admin.rd-task.resume", published.get().eventType());
        assertEquals(1, registry.listBugFixTasks().size());
    }

    private RagStreamTaskRegistry registry() {
        return new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator()
        );
    }

    private TicketSnapshot ticket() {
        return new TicketSnapshot(
                "FI-RESTART-1",
                "外卖后端在 Node ESM 环境下启动失败",
                "ReferenceError: __dirname is not defined",
                List.of("waimai"),
                Instant.now(),
                "P0",
                "",
                "",
                "feishu",
                "",
                java.util.Map.of(),
                Instant.now(),
                Instant.EPOCH
        );
    }

    private SnowflakeIdGenerator generator() {
        AtomicLong now = new AtomicLong(1_782_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }
}
