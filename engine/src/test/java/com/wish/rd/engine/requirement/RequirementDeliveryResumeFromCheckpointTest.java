package com.wish.rd.engine.requirement;

import com.wish.rd.engine.agent.AgentStagePlanner;
import com.wish.rd.engine.agent.AgentWorkflowAlertSinkPort;
import com.wish.rd.engine.agent.WorkflowExperienceStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.requirement.model.RequirementBranchPublication;
import com.wish.rd.engine.requirement.model.RequirementDeliveryResult;
import com.wish.rd.engine.requirement.model.RequirementDeliveryReviewResult;
import com.wish.rd.engine.requirement.model.RequirementDeliveryPublicationView;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublication;
import com.wish.rd.engine.requirement.publication.RequirementOperationId;
import com.wish.rd.engine.requirement.publication.RequirementPublicationLedger;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort.BranchHeadQuery;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort.MatchedOpenPullRequest;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort.ReconcileQuery;
import com.wish.rd.engine.requirement.publication.impl.InMemoryRequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.model.RequirementPublication;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationPrepareCommand;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationStatus;
import com.wish.rd.engine.requirement.review.AiDeliveryReviewEngine;
import com.wish.rd.engine.requirement.review.AiReviewModelPort;
import com.wish.rd.engine.requirement.review.AiReviewResultValidator;
import com.wish.rd.engine.requirement.review.impl.InMemoryAiReviewRunStore;
import com.wish.rd.engine.requirement.review.model.AiReviewPackage;
import com.wish.rd.engine.requirement.review.model.AiReviewPart;
import com.wish.rd.engine.requirement.review.model.AiReviewSource;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryCheckpointStore;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.retry.model.TaskRetryPoint;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.context.RoleContextBuilder;
import com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.impl.InMemoryTaskMaterialStore;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementDeliveryResumeFromCheckpointTest {

    @Test
    void deterministicReviewCheckpointSkipsAllRoleExecutorsAndResumesAtValidation() {
        ResumeFixture fixture = resumeFixture(TaskFailurePhase.DETERMINISTIC_REVIEW, validDeliveryJson());
        AtomicInteger reviewerCalls = new AtomicInteger();
        RequirementDeliveryEngine engine = fixture.engine(new RequirementDeliveryReviewer() {
            @Override
            public RequirementDeliveryReviewResult review(
                    String taskId,
                    RequirementDeliveryPublicationView publicationView
            ) {
                reviewerCalls.incrementAndGet();
                assertEquals(4, publicationView.successfulRoles().size());
                return RequirementDeliveryReviewResult.approved(taskId);
            }
        });

        RequirementDeliveryResult result = engine.submit(fixture.task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        assertEquals(1, reviewerCalls.get());
        assertEquals(0, fixture.executorCalls.get());
        assertEquals(1, fixture.publisherCalls.get());
    }

    @Test
    void deterministicReviewCheckpointRebuildsFromStageArtifactsWhenPersistedEvidenceIsTruncated() {
        ResumeFixture fixture = resumeFixture(
                TaskFailurePhase.DETERMINISTIC_REVIEW, truncatedDeliveryJson());
        InMemoryAgentStageRunStore stageRuns = new InMemoryAgentStageRunStore();
        InMemoryAgentStageArtifactStore artifacts = new InMemoryAgentStageArtifactStore();
        seedSucceededStages(fixture.task().taskId(), stageRuns, artifacts);
        AtomicInteger reviewerCalls = new AtomicInteger();
        RequirementDeliveryEngine engine = fixture.engine(stageRuns, artifacts,
                new RequirementDeliveryReviewer() {
                    @Override
                    public RequirementDeliveryReviewResult review(
                            String taskId,
                            RequirementDeliveryPublicationView publicationView
                    ) {
                        reviewerCalls.incrementAndGet();
                        assertEquals(List.of("src/App.java"), publicationView.coding().changedFiles());
                        return RequirementDeliveryReviewResult.approved(taskId);
                    }
                });

        RequirementDeliveryResult result = engine.submit(fixture.task().taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        assertEquals(1, reviewerCalls.get());
        assertEquals(0, fixture.executorCalls().get());
    }

    @Test
    void aiReviewProviderRetrySkipsDeterministicReviewAndRolesThenPublishesOnOk() {
        ResumeFixture fixture = resumeFixture(TaskFailurePhase.AI_REVIEW,
                withDeliveryReview("task-resume", validDeliveryJson()));
        RequirementDeliveryEngine engine = fixture.engine(new RequirementDeliveryReviewer() {
            @Override
            public RequirementDeliveryReviewResult review(
                    String taskId,
                    RequirementDeliveryPublicationView publicationView
            ) {
                throw new AssertionError("deterministic review must not rerun for AI provider retry");
            }
        });
        AtomicInteger modelCalls = new AtomicInteger();
        AiReviewSource source = new AiReviewSource("source-1", "QA", "QA_AGENT", "RESULT_JSON",
                "sha256:source", "qa passed", "{}", 100L);
        AiReviewPackage reviewPackage = new AiReviewPackage(fixture.task.taskId(), List.of(source),
                List.of(new AiReviewPart(1, List.of(source.sourceId()), "qa passed", 9)),
                9, 0, "sha256:package");
        AiDeliveryReviewEngine aiReview = new AiDeliveryReviewEngine(
                new InMemoryAiReviewRunStore(), (task, review) -> reviewPackage,
                request -> {
                    modelCalls.incrementAndGet();
                    return AiReviewModelPort.ModelResponse.available("model-a", """
                            {"decision":"OK","score":95,"summary":"complete","retryFromRole":"",
                             "dimensions":[{"name":"qa","score":95,"reason":"passed","sourceIds":["source-1"]}],
                             "findings":[]}
                            """);
                },
                new AiReviewResultValidator(), new AtomicIdSupplier("ai-"), () -> 2_000L, "model-a");
        engine.setAiDeliveryReviewEngine(aiReview);

        RequirementDeliveryResult result = engine.submit(fixture.task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        assertEquals(1, modelCalls.get());
        assertEquals(0, fixture.executorCalls.get());
        assertEquals(1, fixture.publisherCalls.get());
    }

    @Test
    void aiReviewRetryFailsClosedBeforePublisherWithoutApprovedDeterministicReview() {
        for (String resultJson : List.of(
                validDeliveryJson(),
                withReviewObject(validDeliveryJson(), "{}"),
                withReviewObject(validDeliveryJson(),
                        "{\"reviewer\":\"DELIVERY_REVIEWER\",\"approved\":false,\"reason\":\"rejected\"}"))) {
            ResumeFixture fixture = resumeFixture(TaskFailurePhase.AI_REVIEW, resultJson);
            RequirementDeliveryEngine engine = fixture.engine(new RequirementDeliveryReviewer() {
                @Override
                public RequirementDeliveryReviewResult review(
                        String taskId,
                        RequirementDeliveryPublicationView publicationView
                ) {
                    throw new AssertionError("deterministic review must not rerun for AI provider retry");
                }
            });

            RequirementDeliveryResult result = engine.submit(fixture.task.taskId());

            assertEquals(RdTaskStatus.REJECTED, result.status(), result.errorMessage());
            assertEquals(0, fixture.publisherCalls.get());
            assertTrue(result.errorMessage().contains("delivery review failed"), result.errorMessage());
        }
    }

    @Test
    void prPublicationCheckpointRetriesOnlyPublicationWithoutRebuildingOrExecutingRoles() {
        AtomicLong now = new AtomicLong(1_784_000_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryTaskMaterialStore materials = new InMemoryTaskMaterialStore();
        RdRequirementTask task = recoveringTask();
        taskStore.saveRequirementTask(task);
        materials.save(material(task.taskId()));
        AtomicInteger executorCalls = new AtomicInteger();
        AtomicInteger publisherCalls = new AtomicInteger();
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, materials,
                request -> {
                    executorCalls.incrementAndGet();
                    throw new AssertionError("role executor must not run during PR-only retry");
                },
                new RequirementContextBuilder(), new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(), new AgentStagePlanner(ids::nextIdString), stages,
                new InMemoryAgentStageArtifactStore(), new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(), AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(), new RequirementDeliveryReviewer(), command -> {
                    publisherCalls.incrementAndGet();
                    assertTrue(command.deliveryResultJson().contains("multiAgentStages"));
                    return RequirementPullRequestPublication.success(
                            task.taskId(), "https://github.com/acme/waimai/pull/42", "42", "{}");
                });
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        TaskRetryPoint point = new TaskRetryPoint(task.taskId(), TaskFailurePhase.PR_PUBLICATION,
                null, "", "", "", "GitHub temporarily unavailable", task.updateTimeEpochMillis());
        TaskRetryCheckpoint checkpoint = checkpoints.createOrGet(TaskRetryCheckpoint.created(
                "checkpoint-1", point, 1, "task-1:1:PR_PUBLICATION:", RdTaskStatus.REJECTED, 100L
        )).checkpoint();
        checkpoints.transition(checkpoint.checkpointId(), TaskRetryCheckpointStatus.CREATED,
                TaskRetryCheckpointStatus.DISPATCHED, "", 110L);
        engine.setTaskRetryCheckpointStore(checkpoints);

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        assertEquals("https://github.com/acme/waimai/pull/42", result.pullRequestUrl());
        assertEquals(0, executorCalls.get());
        assertEquals(1, publisherCalls.get());
        assertTrue(stages.listByTask(task.taskId()).isEmpty(), "PR retry must not create role attempts");
        assertEquals(1, occurrences(result.resultJson(), "\"pullRequestPublication\""),
                "repeated PR retry must replace the old publication snapshot instead of duplicating JSON keys");
    }

    @Test
    void prPublicationCheckpointRejectsInvalidViewBeforeAnyRemoteWrite() {
        ResumeFixture fixture = resumeFixture(TaskFailurePhase.PR_PUBLICATION, validDeliveryJson());
        RequirementDeliveryEngine engine = fixture.engine(new RequirementDeliveryReviewer());
        AtomicInteger branchCalls = new AtomicInteger();
        engine.setBranchPublisher(command -> {
            branchCalls.incrementAndGet();
            return RequirementBranchPublication.success(command.taskId(), "unexpected", "{}");
        });

        RequirementDeliveryResult result = engine.submit(fixture.task().taskId());

        assertEquals(0, branchCalls.get(), "invalid publication view must block before branch push");
        assertEquals(0, fixture.publisherCalls().get(), "invalid publication view must block before PR create");
        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, result.status());
        assertTrue(result.errorMessage().contains("approved delivery review"), result.errorMessage());
    }

    @Test
    void checkpointsRejectFailedAggregateAndLoopbackOnlyEvidenceBeforePublisher() {
        String failedAggregate = validDeliveryJson().replace(
                "\"multiAgentStatus\":\"SUCCESS\"", "\"multiAgentStatus\":\"FAILED\"");
        ResumeFixture failedFixture = resumeFixture(
                TaskFailurePhase.PR_PUBLICATION,
                withReviewObject(failedAggregate,
                        "{\"taskId\":\"task-resume\",\"reviewer\":\"DELIVERY_REVIEWER\","
                                + "\"approved\":true,\"factsHash\":\"sha256:stale\"}"));

        RequirementDeliveryResult failedResult = failedFixture.engine(new RequirementDeliveryReviewer())
                .submit(failedFixture.task().taskId());

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, failedResult.status());
        assertEquals(0, failedFixture.publisherCalls().get());
        assertTrue(failedResult.errorMessage().contains("multiAgentStatus"), failedResult.errorMessage());

        String loopbackOnly = validDeliveryJson()
                .replace("qa/manifest.json", "http://2130706433/manifest.json")
                .replace("qa/current.log", "http://[::ffff:127.0.0.1]/current.log")
                .replace("qa/regression.log", "http://localhost/regression.log");
        ResumeFixture evidenceFixture = resumeFixture(TaskFailurePhase.DETERMINISTIC_REVIEW, loopbackOnly);

        RequirementDeliveryResult evidenceResult = evidenceFixture.engine(new RequirementDeliveryReviewer())
                .submit(evidenceFixture.task().taskId());

        assertEquals(RdTaskStatus.REJECTED, evidenceResult.status());
        assertEquals(0, evidenceFixture.publisherCalls().get());
        assertTrue(evidenceResult.errorMessage().contains("persistent"), evidenceResult.errorMessage());
    }

    @Test
    void resumePullRequestPublicationFinalizesExistingPreparedPublicationAfterBranchConfirmation() {
        AtomicLong now = new AtomicLong(1_784_000_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryTaskMaterialStore materials = new InMemoryTaskMaterialStore();
        String patchSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        RdRequirementTask task = recoveringTaskWithCandidatePatch(patchSha);
        taskStore.saveRequirementTask(task);
        materials.save(material(task.taskId()));
        String workBranch = "requirement/" + task.taskId();
        String operationId = RequirementOperationId.of(task.taskId(), task.baseBranch(), workBranch, patchSha);
        InMemoryRequirementPublicationStore publicationStore = new InMemoryRequirementPublicationStore();
        RequirementPublicationLedger ledger = new RequirementPublicationLedger(publicationStore);
        RequirementPublication seeded = ledger.prepare(new RequirementPublicationPrepareCommand(
                operationId, task.taskId(), "stage-seed", task.baseBranch(), workBranch, patchSha));
        AtomicInteger publisherCalls = new AtomicInteger();
        AtomicInteger branchCalls = new AtomicInteger();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, materials,
                request -> {
                    throw new AssertionError("role executor must not run during PR-only retry");
                },
                new RequirementContextBuilder(), new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(), new AgentStagePlanner(ids::nextIdString),
                new InMemoryAgentStageRunStore(), new InMemoryAgentStageArtifactStore(), new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(), AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(), new RequirementDeliveryReviewer(), command -> {
                    publisherCalls.incrementAndGet();
                    return RequirementPullRequestPublication.success(
                            task.taskId(), "https://github.com/acme/waimai/pull/42", "42", "{}");
                });
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        TaskRetryPoint point = new TaskRetryPoint(task.taskId(), TaskFailurePhase.PR_PUBLICATION,
                null, "", "", "", "GitHub temporarily unavailable", task.updateTimeEpochMillis());
        TaskRetryCheckpoint checkpoint = checkpoints.createOrGet(TaskRetryCheckpoint.created(
                "checkpoint-1", point, 1, "task-1:1:PR_PUBLICATION:", RdTaskStatus.REJECTED, 100L
        )).checkpoint();
        checkpoints.transition(checkpoint.checkpointId(), TaskRetryCheckpointStatus.CREATED,
                TaskRetryCheckpointStatus.DISPATCHED, "", 110L);
        engine.setTaskRetryCheckpointStore(checkpoints);
        engine.setPublicationLedger(ledger);
        engine.setBranchPublisher(command -> {
            branchCalls.incrementAndGet();
            return RequirementBranchPublication.success(command.taskId(), "resume-branch-sha", "{}");
        });

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        assertEquals(1, branchCalls.get());
        assertEquals(1, publisherCalls.get());
        RequirementPublication after = publicationStore.findByOperationId(operationId).orElseThrow();
        assertEquals(seeded.id(), after.id());
        assertEquals(4, after.version());
        assertEquals(RequirementPublicationStatus.COMMITTED, after.status());
    }

    @Test
    void resumePullRequestPublicationReusesConfirmedPullRequestWithoutReplay() {
        AtomicLong now = new AtomicLong(1_784_000_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryTaskMaterialStore materials = new InMemoryTaskMaterialStore();
        String patchSha = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
        RdRequirementTask task = recoveringTaskWithCandidatePatch(patchSha);
        taskStore.saveRequirementTask(task);
        materials.save(material(task.taskId()));
        String workBranch = "requirement/" + task.taskId();
        String operationId = RequirementOperationId.of(task.taskId(), task.baseBranch(), workBranch, patchSha);
        InMemoryRequirementPublicationStore publicationStore = new InMemoryRequirementPublicationStore();
        RequirementPublicationLedger ledger = new RequirementPublicationLedger(publicationStore);
        ledger.prepare(new RequirementPublicationPrepareCommand(
                operationId, task.taskId(), "stage-seed", task.baseBranch(), workBranch, patchSha));
        ledger.markBranchConfirmed(operationId, "deadbeef");
        ledger.markPullRequestConfirmed(operationId, "https://github.com/acme/waimai/pull/77", 77);
        AtomicInteger publisherCalls = new AtomicInteger();
        AtomicInteger branchCalls = new AtomicInteger();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, materials,
                request -> {
                    throw new AssertionError("role executor must not run during PR-only retry");
                },
                new RequirementContextBuilder(), new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(), new AgentStagePlanner(ids::nextIdString),
                new InMemoryAgentStageRunStore(), new InMemoryAgentStageArtifactStore(), new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(), AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(), new RequirementDeliveryReviewer(), command -> {
                    publisherCalls.incrementAndGet();
                    throw new AssertionError("PR publisher must not rerun when ledger already confirmed PR");
                });
        engine.setBranchPublisher(command -> {
            branchCalls.incrementAndGet();
            throw new AssertionError("branch push must not rerun when ledger already confirmed PR");
        });
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        TaskRetryPoint point = new TaskRetryPoint(task.taskId(), TaskFailurePhase.PR_PUBLICATION,
                null, "", "", "", "process crashed after PR create", task.updateTimeEpochMillis());
        TaskRetryCheckpoint checkpoint = checkpoints.createOrGet(TaskRetryCheckpoint.created(
                "checkpoint-reuse", point, 1, "task-1:1:PR_PUBLICATION:", RdTaskStatus.REJECTED, 100L
        )).checkpoint();
        checkpoints.transition(checkpoint.checkpointId(), TaskRetryCheckpointStatus.CREATED,
                TaskRetryCheckpointStatus.DISPATCHED, "", 110L);
        engine.setTaskRetryCheckpointStore(checkpoints);
        engine.setPublicationLedger(ledger);

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        assertEquals("https://github.com/acme/waimai/pull/77", result.pullRequestUrl());
        assertEquals(0, publisherCalls.get());
        assertEquals(0, branchCalls.get());
        assertEquals(RequirementPublicationStatus.COMMITTED,
                publicationStore.findByOperationId(operationId).orElseThrow().status());
        assertTrue(result.resultJson().contains("reusedFromPublicationLedger"));
    }

    @Test
    void resumePullRequestPublicationSkipsBranchPushWhenBranchAlreadyConfirmed() {
        AtomicLong now = new AtomicLong(1_784_000_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryTaskMaterialStore materials = new InMemoryTaskMaterialStore();
        String patchSha = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
        RdRequirementTask task = recoveringTaskWithCandidatePatch(patchSha);
        taskStore.saveRequirementTask(task);
        materials.save(material(task.taskId()));
        String workBranch = "requirement/" + task.taskId();
        String operationId = RequirementOperationId.of(task.taskId(), task.baseBranch(), workBranch, patchSha);
        InMemoryRequirementPublicationStore publicationStore = new InMemoryRequirementPublicationStore();
        RequirementPublicationLedger ledger = new RequirementPublicationLedger(publicationStore);
        ledger.prepare(new RequirementPublicationPrepareCommand(
                operationId, task.taskId(), "stage-seed", task.baseBranch(), workBranch, patchSha));
        ledger.markBranchConfirmed(operationId, "branch-sha-1");
        AtomicInteger publisherCalls = new AtomicInteger();
        AtomicInteger branchCalls = new AtomicInteger();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, materials,
                request -> {
                    throw new AssertionError("role executor must not run during PR-only retry");
                },
                new RequirementContextBuilder(), new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(), new AgentStagePlanner(ids::nextIdString),
                new InMemoryAgentStageRunStore(), new InMemoryAgentStageArtifactStore(), new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(), AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(), new RequirementDeliveryReviewer(), command -> {
                    publisherCalls.incrementAndGet();
                    return RequirementPullRequestPublication.success(
                            task.taskId(), "https://github.com/acme/waimai/pull/88", "88", "{}");
                });
        engine.setBranchPublisher(command -> {
            branchCalls.incrementAndGet();
            throw new AssertionError("branch push must be skipped when BRANCH_CONFIRMED");
        });
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        TaskRetryPoint point = new TaskRetryPoint(task.taskId(), TaskFailurePhase.PR_PUBLICATION,
                null, "", "", "", "crash after branch push", task.updateTimeEpochMillis());
        TaskRetryCheckpoint checkpoint = checkpoints.createOrGet(TaskRetryCheckpoint.created(
                "checkpoint-branch", point, 1, "task-1:1:PR_PUBLICATION:", RdTaskStatus.REJECTED, 100L
        )).checkpoint();
        checkpoints.transition(checkpoint.checkpointId(), TaskRetryCheckpointStatus.CREATED,
                TaskRetryCheckpointStatus.DISPATCHED, "", 110L);
        engine.setTaskRetryCheckpointStore(checkpoints);
        engine.setPublicationLedger(ledger);

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        assertEquals(0, branchCalls.get());
        assertEquals(1, publisherCalls.get());
        assertEquals("https://github.com/acme/waimai/pull/88", result.pullRequestUrl());
        assertEquals(RequirementPublicationStatus.COMMITTED,
                publicationStore.findByOperationId(operationId).orElseThrow().status());
    }

    @Test
    void resumePullRequestPublicationQueriesPreparedRemoteBranchBeforeReapplyingCandidate() {
        AtomicLong now = new AtomicLong(1_784_000_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryTaskMaterialStore materials = new InMemoryTaskMaterialStore();
        String patchSha = "1212121212121212121212121212121212121212121212121212121212121212";
        RdRequirementTask task = recoveringTaskWithCandidatePatch(patchSha);
        taskStore.saveRequirementTask(task);
        materials.save(material(task.taskId()));
        String workBranch = "requirement/" + task.taskId();
        String operationId = RequirementOperationId.of(task.taskId(), task.baseBranch(), workBranch, patchSha);
        InMemoryRequirementPublicationStore publicationStore = new InMemoryRequirementPublicationStore();
        RequirementPublicationLedger ledger = new RequirementPublicationLedger(publicationStore);
        ledger.prepare(new RequirementPublicationPrepareCommand(
                operationId, task.taskId(), "stage-seed", task.baseBranch(), workBranch, patchSha));
        AtomicInteger publisherCalls = new AtomicInteger();
        AtomicInteger branchCalls = new AtomicInteger();
        AtomicInteger branchHeadCalls = new AtomicInteger();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, materials,
                request -> {
                    throw new AssertionError("role executor must not run during PR-only retry");
                },
                new RequirementContextBuilder(), new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(), new AgentStagePlanner(ids::nextIdString),
                new InMemoryAgentStageRunStore(), new InMemoryAgentStageArtifactStore(), new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(), AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(), new RequirementDeliveryReviewer(), command -> {
                    publisherCalls.incrementAndGet();
                    return RequirementPullRequestPublication.success(
                            task.taskId(), "https://github.com/acme/waimai/pull/121", "121", "{}");
                });
        engine.setBranchPublisher(command -> {
            branchCalls.incrementAndGet();
            throw new AssertionError("branch push must not replay after prepared remote branch reconcile");
        });
        engine.setPublicationReconciler(new RequirementPublicationReconcilePort() {
            @Override
            public Optional<MatchedOpenPullRequest> findMatchingOpenPullRequest(ReconcileQuery query) {
                return Optional.empty();
            }

            @Override
            public RequirementPublicationReconcilePort.RemoteBranchHead resolveRemoteBranchHead(
                    BranchHeadQuery query
            ) {
                branchHeadCalls.incrementAndGet();
                assertEquals(workBranch, query.workBranch());
                return new RequirementPublicationReconcilePort.RemoteBranchHead.Present(
                        "remote-head-121", operationId, patchSha);
            }
        });
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        TaskRetryPoint point = new TaskRetryPoint(task.taskId(), TaskFailurePhase.PR_PUBLICATION,
                null, "", "", "", "crash before publication confirmation", task.updateTimeEpochMillis());
        TaskRetryCheckpoint checkpoint = checkpoints.createOrGet(TaskRetryCheckpoint.created(
                "checkpoint-prepared-branch", point, 1, "task-1:1:PR_PUBLICATION:",
                RdTaskStatus.REJECTED, 100L)).checkpoint();
        checkpoints.transition(checkpoint.checkpointId(), TaskRetryCheckpointStatus.CREATED,
                TaskRetryCheckpointStatus.DISPATCHED, "", 110L);
        engine.setTaskRetryCheckpointStore(checkpoints);
        engine.setPublicationLedger(ledger);

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        assertEquals(1, branchHeadCalls.get());
        assertEquals(0, branchCalls.get());
        assertEquals(1, publisherCalls.get());
        assertEquals("https://github.com/acme/waimai/pull/121", result.pullRequestUrl());
        assertEquals(RequirementPublicationStatus.COMMITTED,
                publicationStore.findByOperationId(operationId).orElseThrow().status());
    }

    @Test
    void resumePullRequestPublicationBlocksWhenWaitingForRemoteReconcile() {
        AtomicLong now = new AtomicLong(1_784_000_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryTaskMaterialStore materials = new InMemoryTaskMaterialStore();
        String patchSha = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
        RdRequirementTask task = recoveringTaskWithCandidatePatch(patchSha);
        taskStore.saveRequirementTask(task);
        materials.save(material(task.taskId()));
        String workBranch = "requirement/" + task.taskId();
        String operationId = RequirementOperationId.of(task.taskId(), task.baseBranch(), workBranch, patchSha);
        InMemoryRequirementPublicationStore publicationStore = new InMemoryRequirementPublicationStore();
        RequirementPublicationLedger ledger = new RequirementPublicationLedger(publicationStore);
        ledger.prepare(new RequirementPublicationPrepareCommand(
                operationId, task.taskId(), "stage-seed", task.baseBranch(), workBranch, patchSha));
        ledger.markUnknownRemoteResult(operationId, "GitHub POST timed out");
        AtomicInteger publisherCalls = new AtomicInteger();
        AtomicInteger branchCalls = new AtomicInteger();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, materials,
                request -> {
                    throw new AssertionError("role executor must not run during PR-only retry");
                },
                new RequirementContextBuilder(), new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(), new AgentStagePlanner(ids::nextIdString),
                new InMemoryAgentStageRunStore(), new InMemoryAgentStageArtifactStore(), new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(), AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(), new RequirementDeliveryReviewer(), command -> {
                    publisherCalls.incrementAndGet();
                    throw new AssertionError("PR must not replay while WAIT_RECONCILE");
                });
        engine.setBranchPublisher(command -> {
            branchCalls.incrementAndGet();
            throw new AssertionError("branch push must not replay while WAIT_RECONCILE");
        });
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        TaskRetryPoint point = new TaskRetryPoint(task.taskId(), TaskFailurePhase.PR_PUBLICATION,
                null, "", "", "", "GitHub POST timed out", task.updateTimeEpochMillis());
        TaskRetryCheckpoint checkpoint = checkpoints.createOrGet(TaskRetryCheckpoint.created(
                "checkpoint-wait", point, 1, "task-1:1:PR_PUBLICATION:", RdTaskStatus.REJECTED, 100L
        )).checkpoint();
        checkpoints.transition(checkpoint.checkpointId(), TaskRetryCheckpointStatus.CREATED,
                TaskRetryCheckpointStatus.DISPATCHED, "", 110L);
        engine.setTaskRetryCheckpointStore(checkpoints);
        engine.setPublicationLedger(ledger);

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.REJECTED, result.status());
        assertTrue(result.errorMessage().contains("waiting for remote reconciliation"));
        assertEquals(0, branchCalls.get());
        assertEquals(0, publisherCalls.get());
        assertEquals(RequirementPublicationStatus.UNKNOWN_REMOTE_RESULT,
                publicationStore.findByOperationId(operationId).orElseThrow().status());
    }

    @Test
    void resumePullRequestPublicationReconcilesUnknownWhenOpenPrMatches() {
        AtomicLong now = new AtomicLong(1_784_000_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryTaskMaterialStore materials = new InMemoryTaskMaterialStore();
        String patchSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        RdRequirementTask task = recoveringTaskWithCandidatePatch(patchSha);
        taskStore.saveRequirementTask(task);
        materials.save(material(task.taskId()));
        String workBranch = "requirement/" + task.taskId();
        String operationId = RequirementOperationId.of(task.taskId(), task.baseBranch(), workBranch, patchSha);
        InMemoryRequirementPublicationStore publicationStore = new InMemoryRequirementPublicationStore();
        RequirementPublicationLedger ledger = new RequirementPublicationLedger(publicationStore);
        ledger.prepare(new RequirementPublicationPrepareCommand(
                operationId, task.taskId(), "stage-seed", task.baseBranch(), workBranch, patchSha));
        ledger.markBranchConfirmed(operationId, "deadbeef");
        ledger.markUnknownRemoteResult(operationId, "GitHub POST timed out");
        AtomicInteger publisherCalls = new AtomicInteger();
        AtomicInteger branchCalls = new AtomicInteger();
        AtomicInteger reconcileCalls = new AtomicInteger();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, materials,
                request -> {
                    throw new AssertionError("role executor must not run during PR-only retry");
                },
                new RequirementContextBuilder(), new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(), new AgentStagePlanner(ids::nextIdString),
                new InMemoryAgentStageRunStore(), new InMemoryAgentStageArtifactStore(), new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(), AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(), new RequirementDeliveryReviewer(), command -> {
                    publisherCalls.incrementAndGet();
                    throw new AssertionError("PR must not replay after reconcile reuse");
                });
        engine.setBranchPublisher(command -> {
            branchCalls.incrementAndGet();
            throw new AssertionError("branch push must not replay after reconcile");
        });
        engine.setPublicationReconciler(new RequirementPublicationReconcilePort() {
            @Override
            public Optional<MatchedOpenPullRequest> findMatchingOpenPullRequest(ReconcileQuery query) {
                reconcileCalls.incrementAndGet();
                assertEquals(task.taskId(), query.taskId());
                assertEquals(operationId, query.operationId());
                return Optional.of(new MatchedOpenPullRequest(
                        "https://github.com/acme/waimai/pull/91", 91));
            }
        });
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        TaskRetryPoint point = new TaskRetryPoint(task.taskId(), TaskFailurePhase.PR_PUBLICATION,
                null, "", "", "", "GitHub POST timed out", task.updateTimeEpochMillis());
        TaskRetryCheckpoint checkpoint = checkpoints.createOrGet(TaskRetryCheckpoint.created(
                "checkpoint-reconcile", point, 1, "task-1:1:PR_PUBLICATION:", RdTaskStatus.REJECTED, 100L
        )).checkpoint();
        checkpoints.transition(checkpoint.checkpointId(), TaskRetryCheckpointStatus.CREATED,
                TaskRetryCheckpointStatus.DISPATCHED, "", 110L);
        engine.setTaskRetryCheckpointStore(checkpoints);
        engine.setPublicationLedger(ledger);

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        assertEquals("https://github.com/acme/waimai/pull/91", result.pullRequestUrl());
        assertEquals(1, reconcileCalls.get());
        assertEquals(0, branchCalls.get());
        assertEquals(0, publisherCalls.get());
        assertEquals(RequirementPublicationStatus.COMMITTED,
                publicationStore.findByOperationId(operationId).orElseThrow().status());
        assertTrue(result.resultJson().contains("reusedFromPublicationLedger"));
    }

    @Test
    void resumePullRequestPublicationReconcilesUnknownBranchThenCreatesPullRequest() {
        AtomicLong now = new AtomicLong(1_784_000_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryTaskMaterialStore materials = new InMemoryTaskMaterialStore();
        String patchSha = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
        RdRequirementTask task = recoveringTaskWithCandidatePatch(patchSha);
        taskStore.saveRequirementTask(task);
        materials.save(material(task.taskId()));
        String workBranch = "requirement/" + task.taskId();
        String operationId = RequirementOperationId.of(task.taskId(), task.baseBranch(), workBranch, patchSha);
        InMemoryRequirementPublicationStore publicationStore = new InMemoryRequirementPublicationStore();
        RequirementPublicationLedger ledger = new RequirementPublicationLedger(publicationStore);
        ledger.prepare(new RequirementPublicationPrepareCommand(
                operationId, task.taskId(), "stage-seed", task.baseBranch(), workBranch, patchSha));
        ledger.markUnknownRemoteResult(operationId, "GitHub push timed out");
        AtomicInteger publisherCalls = new AtomicInteger();
        AtomicInteger branchCalls = new AtomicInteger();
        AtomicInteger branchHeadCalls = new AtomicInteger();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, materials,
                request -> {
                    throw new AssertionError("role executor must not run during PR-only retry");
                },
                new RequirementContextBuilder(), new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(), new AgentStagePlanner(ids::nextIdString),
                new InMemoryAgentStageRunStore(), new InMemoryAgentStageArtifactStore(), new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(), AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(), new RequirementDeliveryReviewer(), command -> {
                    publisherCalls.incrementAndGet();
                    return RequirementPullRequestPublication.success(
                            task.taskId(), "https://github.com/acme/waimai/pull/92", "92", "{}");
                });
        engine.setBranchPublisher(command -> {
            branchCalls.incrementAndGet();
            throw new AssertionError("branch push must not replay after branch-head reconcile");
        });
        engine.setPublicationReconciler(new RequirementPublicationReconcilePort() {
            @Override
            public Optional<MatchedOpenPullRequest> findMatchingOpenPullRequest(ReconcileQuery query) {
                return Optional.empty();
            }

            @Override
            public RequirementPublicationReconcilePort.RemoteBranchHead resolveRemoteBranchHead(
                    BranchHeadQuery query
            ) {
                branchHeadCalls.incrementAndGet();
                assertEquals(workBranch, query.workBranch());
                return new RequirementPublicationReconcilePort.RemoteBranchHead.Present(
                        "cafebabe", operationId, patchSha);
            }
        });
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        TaskRetryPoint point = new TaskRetryPoint(task.taskId(), TaskFailurePhase.PR_PUBLICATION,
                null, "", "", "", "GitHub push timed out", task.updateTimeEpochMillis());
        TaskRetryCheckpoint checkpoint = checkpoints.createOrGet(TaskRetryCheckpoint.created(
                "checkpoint-branch-reconcile", point, 1, "task-1:1:PR_PUBLICATION:", RdTaskStatus.REJECTED, 100L
        )).checkpoint();
        checkpoints.transition(checkpoint.checkpointId(), TaskRetryCheckpointStatus.CREATED,
                TaskRetryCheckpointStatus.DISPATCHED, "", 110L);
        engine.setTaskRetryCheckpointStore(checkpoints);
        engine.setPublicationLedger(ledger);

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        assertEquals("https://github.com/acme/waimai/pull/92", result.pullRequestUrl());
        assertEquals(1, branchHeadCalls.get());
        assertEquals(0, branchCalls.get());
        assertEquals(1, publisherCalls.get());
        assertEquals(RequirementPublicationStatus.COMMITTED,
                publicationStore.findByOperationId(operationId).orElseThrow().status());
    }

    @Test
    void resumePullRequestPublicationRetriesPushAfterUnknownResetToPrepared() {
        AtomicLong now = new AtomicLong(1_784_000_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryTaskMaterialStore materials = new InMemoryTaskMaterialStore();
        String patchSha = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
        RdRequirementTask task = recoveringTaskWithCandidatePatch(patchSha);
        taskStore.saveRequirementTask(task);
        materials.save(material(task.taskId()));
        String workBranch = "requirement/" + task.taskId();
        String operationId = RequirementOperationId.of(task.taskId(), task.baseBranch(), workBranch, patchSha);
        InMemoryRequirementPublicationStore publicationStore = new InMemoryRequirementPublicationStore();
        RequirementPublicationLedger ledger = new RequirementPublicationLedger(publicationStore);
        ledger.prepare(new RequirementPublicationPrepareCommand(
                operationId, task.taskId(), "stage-seed", task.baseBranch(), workBranch, patchSha));
        ledger.markUnknownRemoteResult(operationId, "GitHub push timed out");
        AtomicInteger publisherCalls = new AtomicInteger();
        AtomicInteger branchCalls = new AtomicInteger();
        AtomicInteger branchResolveCalls = new AtomicInteger();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, materials,
                request -> {
                    throw new AssertionError("role executor must not run during PR-only retry");
                },
                new RequirementContextBuilder(), new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(), new AgentStagePlanner(ids::nextIdString),
                new InMemoryAgentStageRunStore(), new InMemoryAgentStageArtifactStore(), new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(), AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(), new RequirementDeliveryReviewer(), command -> {
                    publisherCalls.incrementAndGet();
                    return RequirementPullRequestPublication.success(
                            task.taskId(), "https://github.com/acme/waimai/pull/93", "93", "{}");
                });
        engine.setBranchPublisher(command -> {
            branchCalls.incrementAndGet();
            return RequirementBranchPublication.success(
                    task.taskId(), "deadbeef", "{}");
        });
        engine.setPublicationReconciler(new RequirementPublicationReconcilePort() {
            @Override
            public Optional<MatchedOpenPullRequest> findMatchingOpenPullRequest(ReconcileQuery query) {
                return Optional.empty();
            }

            @Override
            public RequirementPublicationReconcilePort.RemoteBranchHead resolveRemoteBranchHead(
                    BranchHeadQuery query
            ) {
                branchResolveCalls.incrementAndGet();
                return new RequirementPublicationReconcilePort.RemoteBranchHead.Absent();
            }
        });
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        TaskRetryPoint point = new TaskRetryPoint(task.taskId(), TaskFailurePhase.PR_PUBLICATION,
                null, "", "", "", "GitHub push timed out", task.updateTimeEpochMillis());
        TaskRetryCheckpoint checkpoint = checkpoints.createOrGet(TaskRetryCheckpoint.created(
                "checkpoint-reset-prepared", point, 1, "task-1:1:PR_PUBLICATION:", RdTaskStatus.REJECTED, 100L
        )).checkpoint();
        checkpoints.transition(checkpoint.checkpointId(), TaskRetryCheckpointStatus.CREATED,
                TaskRetryCheckpointStatus.DISPATCHED, "", 110L);
        engine.setTaskRetryCheckpointStore(checkpoints);
        engine.setPublicationLedger(ledger);

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        assertEquals("https://github.com/acme/waimai/pull/93", result.pullRequestUrl());
        assertEquals(1, branchResolveCalls.get());
        assertEquals(1, branchCalls.get());
        assertEquals(1, publisherCalls.get());
        assertEquals(RequirementPublicationStatus.COMMITTED,
                publicationStore.findByOperationId(operationId).orElseThrow().status());
    }

    private static RdRequirementTask recoveringTask() {
        return new RdRequirementTask(
                "task-1", "REQUIREMENT", "ADMIN", "source", "", "P1", RdTaskStatus.RECOVERING,
                "订单状态筛选", "project-1", "waimai", "外卖项目",
                "https://github.com/acme/waimai", "acme", "waimai", "main", "feature/status",
                "支持状态筛选", "[\"筛选正确\"]", "prompt",
                withPullRequestPublication(withDeliveryReview("task-1", validDeliveryJson())),
                "", "GitHub temporarily unavailable", 10L, 20L, false);
    }

    private static RdRequirementTask recoveringTaskWithCandidatePatch(String patchSha) {
        return new RdRequirementTask(
                "task-1", "REQUIREMENT", "ADMIN", "source", "", "P1", RdTaskStatus.RECOVERING,
                "订单状态筛选", "project-1", "waimai", "外卖项目",
                "https://github.com/acme/waimai", "acme", "waimai", "main", "feature/status",
                "支持状态筛选", "[\"筛选正确\"]", "prompt",
                withPullRequestPublication(withDeliveryReview("task-1", validDeliveryJson(patchSha))),
                "", "GitHub temporarily unavailable", 10L, 20L, false);
    }

    private static TaskMaterial material(String taskId) {
        return new TaskMaterial("material-1", taskId, TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT, "需求", "", "text/plain", "sha256:1",
                "订单状态筛选需求", "", "", "", "{}", 10L, 10L);
    }

    private ResumeFixture resumeFixture(TaskFailurePhase phase, String resultJson) {
        AtomicLong now = new AtomicLong(1_784_100_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryTaskMaterialStore materials = new InMemoryTaskMaterialStore();
        RdRequirementTask task = new RdRequirementTask(
                "task-resume", "REQUIREMENT", "ADMIN", "source", "", "P1", RdTaskStatus.RECOVERING,
                "订单状态筛选", "project-1", "waimai", "外卖项目",
                "https://github.com/acme/waimai", "acme", "waimai", "main", "feature/status",
                "支持状态筛选", "[\"筛选正确\"]", "prompt", resultJson,
                "", "retry", 10L, 20L, false);
        taskStore.saveRequirementTask(task);
        materials.save(material(task.taskId()));
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        TaskRetryPoint point = new TaskRetryPoint(task.taskId(), phase, null, "", "", "",
                "retry", task.updateTimeEpochMillis());
        TaskRetryCheckpoint checkpoint = checkpoints.createOrGet(TaskRetryCheckpoint.created(
                "checkpoint-" + phase, point, 1, "task-resume:20:" + phase + ":",
                RdTaskStatus.FAILED_RETRYABLE, 100L)).checkpoint();
        checkpoints.transition(checkpoint.checkpointId(), TaskRetryCheckpointStatus.CREATED,
                TaskRetryCheckpointStatus.DISPATCHED, "", 110L);
        return new ResumeFixture(registry, materials, task, checkpoints,
                new AtomicInteger(), new AtomicInteger(), ids);
    }

    private static String validDeliveryJson() {
        return validDeliveryJson("a".repeat(64));
    }

    private static String validDeliveryJson(String patchSha) {
        return """
                {"status":"SUCCESS","multiAgentStatus":"SUCCESS",
                 "changedFiles":["src/App.java"],"testCommands":["./mvnw test"],"testStatus":"PASSED","riskLevel":"LOW",
                 "multiAgentStages":[
                  {"role":"REQUIREMENT_REVIEWER","success":true,"pullRequestUrl":"","resultJson":{}},
                  {"role":"SOLUTION_ARCHITECT","success":true,"pullRequestUrl":"","resultJson":{}},
                  {"role":"CODING_AGENT","success":true,"pullRequestUrl":"",
                   "candidatePatch":{"sourceRole":"CODING_AGENT","targetRole":"QA_AGENT","artifactName":"patch.diff",
                     "artifactUri":"s3://rd-role-handoffs/private-candidate.patch","sha256":"%s","bytes":321},
                   "resultJson":{"summary":"implemented","prBody":"implemented","changedFiles":["src/App.java"],
                     "testCommands":["./mvnw test"],"testStatus":"PASSED","riskLevel":"LOW"}},
                  {"role":"QA_AGENT","success":true,"pullRequestUrl":"","resultJson":{
                    "status":"PASSED","summary":"current and regression passed","failureCategory":"NONE","retryRecommendation":"NONE",
                    "browserValidation":{"required":false,"performed":false,"decisionSource":"NOT_APPLICABLE","baseUrl":"","browser":"chromium","viewports":[]},
                    "acceptanceResults":[
                      {"criteria":"筛选正确","scope":"CURRENT","command":"./mvnw test","status":"PASSED","exitCode":0,"durationMillis":1,"logArtifactId":"qa/current.log","evidenceArtifactIds":["qa/current.log"]},
                      {"criteria":"回归正确","scope":"REGRESSION","command":"./mvnw test","status":"PASSED","exitCode":0,"durationMillis":1,"logArtifactId":"qa/regression.log","evidenceArtifactIds":["qa/regression.log"]}
                    ],"evidenceManifestArtifactId":"qa/manifest.json"}}
                ]}
                """.formatted(patchSha);
    }

    /** 每个阶段的 resultJson 都是被字符截断后不再合法的 RESULT_JSON 预览。 */
    private static String truncatedDeliveryJson() {
        return """
                {"multiAgentStatus":"SUCCESS","multiAgentStages":[
                  {"role":"REQUIREMENT_REVIEWER","success":true,"pullRequestUrl":"","resultJson":"%s"},
                  {"role":"SOLUTION_ARCHITECT","success":true,"pullRequestUrl":"","resultJson":"%s"},
                  {"role":"CODING_AGENT","success":true,"pullRequestUrl":"","resultJson":"%s"},
                  {"role":"QA_AGENT","success":true,"pullRequestUrl":"","resultJson":"%s"}
                ]}
                """.formatted(
                escaped(truncatedRolePreview()),
                escaped(truncatedRolePreview()),
                escaped(truncatedRolePreview()),
                escaped(truncatedRolePreview())
        );
    }

    private static String truncatedRolePreview() {
        return "{\"status\":\"SUCCESS\",\"prBody\":\"## Summary\",\"largeRaw\":\"xxxxxxxx";
    }

    private static String escaped(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void seedSucceededStages(
            String taskId,
            InMemoryAgentStageRunStore stageRuns,
            InMemoryAgentStageArtifactStore artifacts
    ) {
        int index = 0;
        for (AgentRole role : AgentRole.requirementDeliveryOrder()) {
            index++;
            String stageRunId = "stage-" + index;
            String artifactId = "artifact-" + index;
            String resultJson = switch (role) {
                case CODING_AGENT -> """
                        {"summary":"implemented","prBody":"implemented","changedFiles":["src/App.java"],
                         "testCommands":["./mvnw test"],"testStatus":"PASSED","riskLevel":"LOW"}
                        """;
                case QA_AGENT -> """
                        {"status":"PASSED","summary":"passed","failureCategory":"NONE","retryRecommendation":"NONE",
                         "browserValidation":{"required":false,"performed":false,"decisionSource":"NOT_APPLICABLE","baseUrl":"","browser":"chromium","viewports":[]},
                         "acceptanceResults":[
                           {"criteria":"current","scope":"CURRENT","command":"./mvnw test","status":"PASSED","exitCode":0,"durationMillis":1,"logArtifactId":"qa/current.log","evidenceArtifactIds":["qa/current.log"]},
                           {"criteria":"regression","scope":"REGRESSION","command":"./mvnw test","status":"PASSED","exitCode":0,"durationMillis":1,"logArtifactId":"qa/regression.log","evidenceArtifactIds":["qa/regression.log"]}],
                         "evidenceManifestArtifactId":"qa/manifest.json"}
                        """;
                default -> "{}";
            };
            artifacts.save(new AgentStageArtifact(
                    artifactId, stageRunId, taskId, role, "RESULT_JSON",
                    "rd-agent-stage://" + taskId + "/" + stageRunId + "/result",
                    role.name() + " result json", resultJson, "sha256:" + artifactId,
                    "{}", 100L + index));
            stageRuns.save(new AgentStageRun(
                    stageRunId, taskId, role, AgentStageStatus.SUCCEEDED, 1,
                    taskId + ":" + role.name() + ":1", "", "", artifactId, "", "[]", "{}", "", "",
                    100L + index, 200L + index, 100L + index, 200L + index));
        }
    }

    private static String withDeliveryReview(String taskId, String value) {
        String factsHash = new RequirementDeliveryPublicationViewAssembler()
                .assemble(value)
                .publicationFactsHash();
        return withReviewObject(value,
                "{\"taskId\":\"" + taskId + "\",\"reviewer\":\"DELIVERY_REVIEWER\"," +
                        "\"approved\":true,\"factsHash\":\"" + factsHash + "\"}");
    }

    private static String withReviewObject(String value, String reviewJson) {
        String normalized = value.strip();
        return normalized.substring(0, normalized.length() - 1)
                + ",\"deliveryReview\":" + reviewJson + "}";
    }

    private static String withPullRequestPublication(String value) {
        String normalized = value.strip();
        return normalized.substring(0, normalized.length() - 1)
                + ",\"pullRequestPublication\":{\"success\":false,\"errorMessage\":\"temporary\"}}";
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while (value != null && (offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private record ResumeFixture(
            RagStreamTaskRegistry registry,
            InMemoryTaskMaterialStore materials,
            RdRequirementTask task,
            InMemoryTaskRetryCheckpointStore checkpoints,
            AtomicInteger executorCalls,
            AtomicInteger publisherCalls,
            SnowflakeIdGenerator ids
    ) {
        RequirementDeliveryEngine engine(RequirementDeliveryReviewer reviewer) {
            return engine(new InMemoryAgentStageRunStore(), new InMemoryAgentStageArtifactStore(), reviewer);
        }

        RequirementDeliveryEngine engine(
                InMemoryAgentStageRunStore stageRuns,
                InMemoryAgentStageArtifactStore artifacts,
                RequirementDeliveryReviewer reviewer
        ) {
            RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                    registry, materials, request -> {
                        executorCalls.incrementAndGet();
                        throw new AssertionError("role executor must not run during review-only retry");
                    },
                    new RequirementContextBuilder(), new RequirementPlanGenerator(),
                    new RuleBasedRequirementPolicyGate(), new AgentStagePlanner(ids::nextIdString),
                    stageRuns, artifacts,
                    new RoleContextBuilder(), new InMemoryRoleContextPackageStore(),
                    AgentWorkflowAlertSinkPort.noop(), WorkflowExperienceStore.noop(), reviewer,
                    command -> {
                        publisherCalls.incrementAndGet();
                        return RequirementPullRequestPublication.success(
                                task.taskId(), "https://github.com/acme/waimai/pull/43", "43", "{}");
                    });
            engine.setTaskRetryCheckpointStore(checkpoints);
            return engine;
        }
    }

    private static final class AtomicIdSupplier implements java.util.function.Supplier<String> {
        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger();

        private AtomicIdSupplier(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public String get() {
            return prefix + sequence.incrementAndGet();
        }
    }
}
