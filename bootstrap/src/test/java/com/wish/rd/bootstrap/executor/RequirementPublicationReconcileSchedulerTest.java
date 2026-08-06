package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.executor.impl.RequirementPublicationReconcileScheduler;
import com.wish.rd.engine.requirement.publication.RequirementOperationId;
import com.wish.rd.engine.requirement.publication.RequirementPublicationLedger;
import com.wish.rd.engine.requirement.publication.RequirementPublicationPrepareCommand;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort.BranchHeadQuery;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort.MatchedOpenPullRequest;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort.ReconcileQuery;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconciliationService;
import com.wish.rd.engine.requirement.publication.impl.InMemoryRequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationStatus;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementPublicationReconcileSchedulerTest {

    @Test
    void shouldAdvanceUnknownPublicationWhenOpenPrAppears() {
        AtomicLong ids = new AtomicLong(1L);
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), new SnowflakeIdGenerator(1, 1, ids::getAndIncrement));
        RdRequirementTask task = registry.createRequirementTask(createCommand());
        String workBranch = "requirement/" + task.taskId();
        String patchSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String operationId = RequirementOperationId.of(task.taskId(), "main", workBranch, patchSha);
        InMemoryRequirementPublicationStore store = new InMemoryRequirementPublicationStore();
        RequirementPublicationLedger ledger = new RequirementPublicationLedger(store);
        ledger.prepare(new RequirementPublicationPrepareCommand(
                operationId, task.taskId(), "stage-1", "main", workBranch, patchSha));
        ledger.markBranchConfirmed(operationId, "deadbeef");
        ledger.markUnknownRemoteResult(operationId, "GitHub POST timed out");
        long firstDue = store.findByOperationId(operationId).orElseThrow().nextReconcileAtEpochMillis();
        AtomicInteger openPrCalls = new AtomicInteger();
        RequirementPublicationReconciliationService service = new RequirementPublicationReconciliationService(
                ledger,
                new RequirementPublicationReconcilePort() {
                    @Override
                    public Optional<MatchedOpenPullRequest> findMatchingOpenPullRequest(ReconcileQuery query) {
                        openPrCalls.incrementAndGet();
                        return Optional.of(new MatchedOpenPullRequest(
                                "https://github.com/acme/waimai/pull/101", 101));
                    }
                }
        );
        AtomicLong clock = new AtomicLong(firstDue);
        RequirementPublicationReconcileScheduler scheduler = new RequirementPublicationReconcileScheduler(
                store, service, registry, null, clock::get);

        scheduler.reconcileDuePublications();

        assertEquals(1, openPrCalls.get());
        assertEquals(RequirementPublicationStatus.PR_CONFIRMED,
                store.findByOperationId(operationId).orElseThrow().status());
    }

    @Test
    void shouldDeferWhenRemoteEvidenceStillMissing() {
        AtomicLong ids = new AtomicLong(1L);
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), new SnowflakeIdGenerator(1, 1, ids::getAndIncrement));
        RdRequirementTask task = registry.createRequirementTask(createCommand());
        String workBranch = "requirement/" + task.taskId();
        String patchSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String operationId = RequirementOperationId.of(task.taskId(), "main", workBranch, patchSha);
        InMemoryRequirementPublicationStore store = new InMemoryRequirementPublicationStore();
        RequirementPublicationLedger ledger = new RequirementPublicationLedger(store);
        ledger.prepare(new RequirementPublicationPrepareCommand(
                operationId, task.taskId(), "stage-1", "main", workBranch, patchSha));
        ledger.markUnknownRemoteResult(operationId, "GitHub push timed out");
        long firstDue = store.findByOperationId(operationId).orElseThrow().nextReconcileAtEpochMillis();
        RequirementPublicationReconciliationService service = new RequirementPublicationReconciliationService(
                ledger,
                new RequirementPublicationReconcilePort() {
                    @Override
                    public Optional<MatchedOpenPullRequest> findMatchingOpenPullRequest(ReconcileQuery query) {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<String> findRemoteBranchHead(BranchHeadQuery query) {
                        return Optional.empty();
                    }
                }
        );
        AtomicLong clock = new AtomicLong(firstDue);
        RequirementPublicationReconcileScheduler scheduler = new RequirementPublicationReconcileScheduler(
                store, service, registry, null, clock::get);

        scheduler.reconcileDuePublications();

        long deferred = store.findByOperationId(operationId).orElseThrow().nextReconcileAtEpochMillis();
        assertEquals(RequirementPublicationStatus.UNKNOWN_REMOTE_RESULT,
                store.findByOperationId(operationId).orElseThrow().status());
        assertTrue(deferred > firstDue);
        assertEquals(RdTaskStatus.CREATED, registry.getRequirementTask(task.taskId()).status());
    }

    private static CreateRequirementTaskCommand createCommand() {
        return new CreateRequirementTaskCommand(
                "订单筛选",
                "P1",
                "https://github.com/acme/waimai",
                "acme",
                "waimai",
                "main",
                "支持筛选",
                List.of("ok"),
                false
        );
    }
}
