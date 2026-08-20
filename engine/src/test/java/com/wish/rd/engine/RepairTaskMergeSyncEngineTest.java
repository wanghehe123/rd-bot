package com.wish.rd.engine;

import com.wish.rd.adapter.model.TicketSnapshot;
import com.wish.rd.engine.merge.model.PullRequestMergeStatus;
import com.wish.rd.engine.merge.PullRequestMergeStatusPort;
import com.wish.rd.engine.merge.RepairTaskMergeSyncEngine;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
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

        RdTask synced = engine.syncTask(task.taskId());

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

        RdTask synced = engine.syncTask(task.taskId());

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

        List<RdTask> synced = engine.syncAllCommitted();

        assertEquals(1, synced.size());
        assertEquals(RdTaskStatus.MERGED, registry.get(committed.taskId()).status());
        assertEquals(RdTaskStatus.CREATED, registry.get(created.taskId()).status());
        assertEquals(List.of("https://github.com/acme/order/pull/44"), statusPort.urls());
    }

    @Test
    void shouldContinueSyncingOtherTasksWhenOnePullRequestUrlIsInvalid() {
        RagStreamTaskRegistry registry = newRegistry();
        RdBugFixTask invalid = committedTask(
                registry,
                "FS-INVALID-URL",
                "https://github.example/rd-bot/pull/mock-7477247467243835392"
        );
        RdBugFixTask valid = committedTask(registry, "FS-VALID-URL", "https://github.com/acme/order/pull/46");
        PullRequestMergeStatusPort statusPort = pullRequestUrl -> {
            if (pullRequestUrl.contains("mock-")) {
                throw new IllegalArgumentException("pullRequestUrl must match /{owner}/{repo}/pull/{number}");
            }
            return new PullRequestMergeStatus(pullRequestUrl, "acme/order", "46", "closed", true);
        };
        RepairTaskMergeSyncEngine engine = new RepairTaskMergeSyncEngine(registry, statusPort);

        List<RdTask> synced = engine.syncAllCommitted();

        assertEquals(2, synced.size());
        assertEquals(RdTaskStatus.COMMITTED, registry.get(invalid.taskId()).status());
        assertEquals(RdTaskStatus.MERGED, registry.get(valid.taskId()).status());
    }

    @Test
    void shouldNotRequeryPullRequestStatusForAnAlreadyMergedTask() {
        RagStreamTaskRegistry registry = newRegistry();
        RdBugFixTask committed = committedTask(registry, "https://github.com/acme/order/pull/45");
        registry.markMerged(committed.taskId());
        RecordingPullRequestMergeStatusPort statusPort = new RecordingPullRequestMergeStatusPort(true);
        RepairTaskMergeSyncEngine engine = new RepairTaskMergeSyncEngine(registry, statusPort);

        List<RdTask> synced = engine.syncAllCommitted();

        assertEquals(1, synced.size());
        assertEquals(RdTaskStatus.MERGED, synced.getFirst().status());
        assertEquals(List.of(), statusPort.urls());
    }

    @Test
    void shouldMarkCompletedRequirementTaskAsMergedWhenPullRequestIsMerged() {
        RagStreamTaskRegistry registry = newRegistry();
        RdRequirementTask completed = completedRequirementTask(registry, "https://github.com/acme/order/pull/52");
        RecordingPullRequestMergeStatusPort statusPort = new RecordingPullRequestMergeStatusPort(true);
        RepairTaskMergeSyncEngine engine = new RepairTaskMergeSyncEngine(registry, statusPort);

        RdTask synced = engine.syncTask(completed.taskId());

        assertEquals(RdTaskStatus.MERGED, synced.status());
        assertEquals(RdTaskStatus.MERGED, registry.getTask(completed.taskId()).status());
        assertEquals(List.of("https://github.com/acme/order/pull/52"), statusPort.urls());
    }

    @Test
    void shouldSyncCompletedRequirementTasksWithPullRequestUrl() {
        RagStreamTaskRegistry registry = newRegistry();
        RdRequirementTask completed = completedRequirementTask(registry, "https://github.com/acme/order/pull/53");
        RecordingPullRequestMergeStatusPort statusPort = new RecordingPullRequestMergeStatusPort(true);
        RepairTaskMergeSyncEngine engine = new RepairTaskMergeSyncEngine(registry, statusPort);

        List<RdTask> synced = engine.syncAllCommitted();

        assertEquals(1, synced.size());
        assertEquals(RdTaskStatus.MERGED, registry.getTask(completed.taskId()).status());
        assertEquals(List.of("https://github.com/acme/order/pull/53"), statusPort.urls());
    }

    private static RdBugFixTask committedTask(RagStreamTaskRegistry registry, String pullRequestUrl) {
        return committedTask(registry, "FS-2001", pullRequestUrl);
    }

    private static RdBugFixTask committedTask(RagStreamTaskRegistry registry, String ticketId, String pullRequestUrl) {
        RdBugFixTask created = registry.createBugFixTask(ticket(ticketId), "P1");
        registry.markSearching(created.taskId(), "RAG 检索中");
        registry.markExecuting(created.taskId(), "执行修复");
        return registry.markCommitted(created.taskId(), pullRequestUrl, "{\"status\":\"SUCCESS\"}");
    }

    private static RdRequirementTask completedRequirementTask(RagStreamTaskRegistry registry, String pullRequestUrl) {
        RdRequirementTask created = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "优惠券系统",
                "P2",
                "https://github.com/acme/order.git",
                "acme",
                "order",
                "main",
                "完成优惠券核心链路",
                List.of("通过验收"),
                false
        ));
        registry.markRequirementMaterialCollecting(created.taskId(), "collecting");
        registry.markRequirementMaterialReady(created.taskId(), "ready");
        registry.markRequirementContextBuilding(created.taskId(), "context");
        registry.markRequirementContextReady(created.taskId(), "{}");
        registry.markRequirementPlanGenerating(created.taskId(), "planning");
        registry.markRequirementPlanGenerated(created.taskId(), "{}");
        registry.markRequirementWaitingPolicy(created.taskId(), "{}");
        registry.markRequirementExecuting(created.taskId(), "prompt");
        registry.markRequirementValidating(created.taskId(), "{}");
        registry.markRequirementPrCreating(created.taskId(), "{}");
        registry.markRequirementCommitted(created.taskId(), pullRequestUrl, "{\"status\":\"SUCCESS\"}");
        registry.markRequirementReporting(created.taskId(), "{\"status\":\"SUCCESS\"}");
        return registry.markRequirementCompleted(created.taskId(), pullRequestUrl, "{\"status\":\"SUCCESS\"}");
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
