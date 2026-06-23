package com.wish.rd.engine;

import com.wish.rd.adapter.TicketSnapshot;
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

class RepairTaskMergeSyncEngineTest {

    @Test
    void shouldMarkCommittedTaskAsMergedWhenPullRequestIsMerged() {
        RagStreamTaskRegistry registry = newRegistry();
        RdBugFixTask task = committedTask(registry, "https://github.com/acme/order/pull/42");
        RecordingPullRequestMergeStatusPort statusPort = new RecordingPullRequestMergeStatusPort(true);
        RepairTaskMergeSyncEngine engine = new RepairTaskMergeSyncEngine(registry, statusPort);

        RdBugFixTask synced = engine.syncTask(task.taskId());

        assertEquals(RdTaskStatus.MERGED, synced.status());
        assertEquals(RdTaskStatus.MERGED, registry.get(task.taskId()).status());
        assertEquals(List.of("https://github.com/acme/order/pull/42"), statusPort.urls());
    }

    @Test
    void shouldKeepCommittedTaskWhenPullRequestIsStillOpen() {
        RagStreamTaskRegistry registry = newRegistry();
        RdBugFixTask task = committedTask(registry, "https://github.com/acme/order/pull/43");
        RecordingPullRequestMergeStatusPort statusPort = new RecordingPullRequestMergeStatusPort(false);
        RepairTaskMergeSyncEngine engine = new RepairTaskMergeSyncEngine(registry, statusPort);

        RdBugFixTask synced = engine.syncTask(task.taskId());

        assertEquals(RdTaskStatus.COMMITTED, synced.status());
        assertEquals(RdTaskStatus.COMMITTED, registry.get(task.taskId()).status());
    }

    @Test
    void shouldSyncAllCommittedTasksOnly() {
        RagStreamTaskRegistry registry = newRegistry();
        RdBugFixTask committed = committedTask(registry, "https://github.com/acme/order/pull/44");
        RdBugFixTask created = registry.createBugFixTask(ticket("FS-2002"), "P2");
        RecordingPullRequestMergeStatusPort statusPort = new RecordingPullRequestMergeStatusPort(true);
        RepairTaskMergeSyncEngine engine = new RepairTaskMergeSyncEngine(registry, statusPort);

        List<RdBugFixTask> synced = engine.syncAllCommitted();

        assertEquals(1, synced.size());
        assertEquals(RdTaskStatus.MERGED, registry.get(committed.taskId()).status());
        assertEquals(RdTaskStatus.CREATED, registry.get(created.taskId()).status());
        assertEquals(List.of("https://github.com/acme/order/pull/44"), statusPort.urls());
    }

    private static RdBugFixTask committedTask(RagStreamTaskRegistry registry, String pullRequestUrl) {
        RdBugFixTask created = registry.createBugFixTask(ticket("FS-2001"), "P1");
        registry.markSearching(created.taskId(), "RAG 检索中");
        registry.markExecuting(created.taskId(), "执行修复");
        return registry.markCommitted(created.taskId(), pullRequestUrl, "{\"status\":\"SUCCESS\"}");
    }

    private static RagStreamTaskRegistry newRegistry() {
        AtomicLong now = new AtomicLong(1_780_000_000_000L);
        return new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new SnowflakeIdGenerator(1, 1, now::getAndIncrement)
        );
    }

    private static TicketSnapshot ticket(String ticketId) {
        return new TicketSnapshot(
                ticketId,
                "外卖下单接口返回 500",
                "createOrder 字段不匹配",
                List.of("waimai"),
                Instant.parse("2026-06-23T00:00:00Z")
        );
    }

    private static final class RecordingPullRequestMergeStatusPort implements PullRequestMergeStatusPort {

        private final boolean merged;
        private final java.util.ArrayList<String> urls = new java.util.ArrayList<>();

        private RecordingPullRequestMergeStatusPort(boolean merged) {
            this.merged = merged;
        }

        @Override
        public PullRequestMergeStatus findByUrl(String pullRequestUrl) {
            urls.add(pullRequestUrl);
            return new PullRequestMergeStatus(pullRequestUrl, "acme/order", "42", merged ? "closed" : "open", merged);
        }

        private List<String> urls() {
            return List.copyOf(urls);
        }
    }
}
