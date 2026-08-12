package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.executor.impl.RequirementPublicationReconcileScheduler;
import com.wish.rd.bootstrap.executor.impl.RequirementPublicationStageContinuationAdapter;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageCommandStore;
import com.wish.rd.engine.requirement.publication.RequirementOperationId;
import com.wish.rd.engine.requirement.publication.RequirementPublicationLedger;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort.BranchHeadQuery;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort.MatchedOpenPullRequest;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort.ReconcileQuery;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconciliationService;
import com.wish.rd.engine.requirement.publication.impl.InMemoryRequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationContinuation;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationPrepareCommand;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementPublicationReconcileSchedulerTest {

    @Test
    void shouldFailClosedWhenContinuationPortIsMissing() {
        AtomicLong ids = new AtomicLong(1L);
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), new SnowflakeIdGenerator(1, 1, ids::getAndIncrement));
        InMemoryRequirementPublicationStore publicationStore = new InMemoryRequirementPublicationStore();
        RequirementPublicationReconciliationService service = new RequirementPublicationReconciliationService(
                new RequirementPublicationLedger(publicationStore), query -> Optional.empty());

        assertThrows(NullPointerException.class, () -> new RequirementPublicationReconcileScheduler(
                publicationStore, service, registry, null, null, System::currentTimeMillis));
    }

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
                store, service, registry, null, continuation -> { }, clock::get);

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
                store, service, registry, null, continuation -> { }, clock::get);

        scheduler.reconcileDuePublications();

        long deferred = store.findByOperationId(operationId).orElseThrow().nextReconcileAtEpochMillis();
        assertEquals(RequirementPublicationStatus.UNKNOWN_REMOTE_RESULT,
                store.findByOperationId(operationId).orElseThrow().status());
        assertTrue(deferred > firstDue);
        assertEquals(RdTaskStatus.CREATED, registry.getRequirementTask(task.taskId()).status());
    }

    @Test
    void shouldPersistOperationKeyedContinuationWithCurrentTaskFenceAfterPrReconcile() {
        AtomicLong ids = new AtomicLong(1L);
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), new SnowflakeIdGenerator(1, 1, ids::getAndIncrement));
        RdRequirementTask task = rejectedPublicationTask(registry);
        String workBranch = "requirement/" + task.taskId();
        String patchSha = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
        String operationId = RequirementOperationId.of(task.taskId(), "main", workBranch, patchSha);
        InMemoryRequirementPublicationStore publicationStore = new InMemoryRequirementPublicationStore();
        RequirementPublicationLedger ledger = new RequirementPublicationLedger(publicationStore);
        ledger.prepare(new RequirementPublicationPrepareCommand(
                operationId, task.taskId(), "stage-1", "main", workBranch, patchSha));
        ledger.markBranchConfirmed(operationId, "deadbeef");
        ledger.markUnknownRemoteResult(operationId, "GitHub POST timed out");
        long firstDue = publicationStore.findByOperationId(operationId).orElseThrow().nextReconcileAtEpochMillis();
        RequirementPublicationReconciliationService service = new RequirementPublicationReconciliationService(
                ledger,
                query -> Optional.of(new MatchedOpenPullRequest(
                        "https://github.com/acme/waimai/pull/102", 102))
        );
        InMemoryRequirementStageCommandStore commandStore = new InMemoryRequirementStageCommandStore();
        RequirementPublicationStageContinuationAdapter continuation =
                new RequirementPublicationStageContinuationAdapter(
                        commandStore, new SnowflakeIdGenerator(1, 2, ids::getAndIncrement), 3);
        RequirementPublicationReconcileScheduler scheduler = new RequirementPublicationReconcileScheduler(
                publicationStore, service, registry, null, continuation, () -> firstDue);

        scheduler.reconcileDuePublications();

        assertEquals(RequirementPublicationStatus.PR_CONFIRMED,
                publicationStore.findByOperationId(operationId).orElseThrow().status());
        var command = commandStore.find(task.taskId(), "REQUIREMENT_DELIVERY",
                RequirementPublicationContinuation.stageFor(operationId)).orElseThrow();
        assertEquals(task.version(), command.taskVersion());
        assertEquals(task.fencingToken(), command.fencingToken());
        assertEquals(task.projectId(), command.projectId());
        assertEquals(task.priority(), "P" + command.priorityRank());
        assertEquals("REQUIREMENT_DELIVERY", command.role());
    }

    @Test
    void twoSchedulerInstancesEnqueueOnlyOneOperationKeyedContinuation() throws Exception {
        AtomicLong ids = new AtomicLong(1L);
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), new SnowflakeIdGenerator(1, 1, ids::getAndIncrement));
        RdRequirementTask task = rejectedPublicationTask(registry);
        String workBranch = "requirement/" + task.taskId();
        String patchSha = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
        String operationId = RequirementOperationId.of(task.taskId(), "main", workBranch, patchSha);
        InMemoryRequirementPublicationStore publicationStore = new InMemoryRequirementPublicationStore();
        RequirementPublicationLedger ledger = new RequirementPublicationLedger(publicationStore);
        ledger.prepare(new RequirementPublicationPrepareCommand(
                operationId, task.taskId(), "stage-1", "main", workBranch, patchSha));
        ledger.markBranchConfirmed(operationId, "deadbeef");
        ledger.markUnknownRemoteResult(operationId, "GitHub POST timed out");
        long firstDue = publicationStore.findByOperationId(operationId).orElseThrow().nextReconcileAtEpochMillis();
        CyclicBarrier remoteCheckBarrier = new CyclicBarrier(2);
        RequirementPublicationReconciliationService service = new RequirementPublicationReconciliationService(
                ledger,
                query -> {
                    try {
                        remoteCheckBarrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception exception) {
                        throw new IllegalStateException("scheduler test barrier failed", exception);
                    }
                    return Optional.of(new MatchedOpenPullRequest(
                            "https://github.com/acme/waimai/pull/103", 103));
                }
        );
        InMemoryRequirementStageCommandStore commandStore = new InMemoryRequirementStageCommandStore();
        RequirementPublicationStageContinuationAdapter continuation =
                new RequirementPublicationStageContinuationAdapter(
                        commandStore, new SnowflakeIdGenerator(1, 2, ids::getAndIncrement), 3);
        RequirementPublicationReconcileScheduler first = new RequirementPublicationReconcileScheduler(
                publicationStore, service, registry, null, continuation, () -> firstDue);
        RequirementPublicationReconcileScheduler second = new RequirementPublicationReconcileScheduler(
                publicationStore, service, registry, null, continuation, () -> firstDue);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var firstRun = pool.submit(first::reconcileDuePublications);
            var secondRun = pool.submit(second::reconcileDuePublications);
            firstRun.get(10, TimeUnit.SECONDS);
            secondRun.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        String stage = RequirementPublicationContinuation.stageFor(operationId);
        assertEquals(1L, commandStore.listByStatus(
                com.wish.rd.engine.requirement.job.model.RequirementStageCommand.Status.PENDING).stream()
                .filter(command -> command.taskId().equals(task.taskId()))
                .filter(command -> command.role().equals("REQUIREMENT_DELIVERY"))
                .filter(command -> command.stage().equals(stage))
                .count());
        var command = commandStore.find(task.taskId(), "REQUIREMENT_DELIVERY", stage).orElseThrow();
        assertEquals(task.version(), command.taskVersion());
        assertEquals(task.fencingToken(), command.fencingToken());
    }

    private static RdRequirementTask rejectedPublicationTask(RagStreamTaskRegistry registry) {
        RdRequirementTask task = registry.createRequirementTask(createCommand());
        for (RdTaskStatus status : List.of(
                RdTaskStatus.MATERIAL_COLLECTING,
                RdTaskStatus.MATERIAL_READY,
                RdTaskStatus.CONTEXT_BUILDING,
                RdTaskStatus.CONTEXT_READY,
                RdTaskStatus.PLAN_GENERATING,
                RdTaskStatus.PLAN_GENERATED,
                RdTaskStatus.WAITING_POLICY,
                RdTaskStatus.EXECUTING,
                RdTaskStatus.VALIDATING,
                RdTaskStatus.PR_CREATING)) {
            task = registry.transitionRequirementFenced(task, status, "", "{}", "", "");
        }
        return registry.markRequirementRejected(task.taskId(), "publication timed out", task.executionResultJson());
    }

    private static CreateRequirementTaskCommand createCommand() {
        return new CreateRequirementTaskCommand(
                "订单筛选",
                "P1",
                "ADMIN",
                "",
                "",
                "project-waimai",
                "waimai",
                "外卖",
                "https://github.com/acme/waimai",
                "acme",
                "waimai",
                "main",
                "支持筛选",
                List.of("ok"),
                List.of(),
                false
        );
    }
}
