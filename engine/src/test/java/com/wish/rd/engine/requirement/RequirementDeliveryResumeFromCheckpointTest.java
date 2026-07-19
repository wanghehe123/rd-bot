package com.wish.rd.engine.requirement;

import com.wish.rd.engine.agent.AgentStagePlanner;
import com.wish.rd.engine.agent.AgentWorkflowAlertSinkPort;
import com.wish.rd.engine.agent.WorkflowExperienceStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.requirement.model.RequirementDeliveryResult;
import com.wish.rd.engine.requirement.model.RequirementDeliveryReviewResult;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublication;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementDeliveryResumeFromCheckpointTest {

    @Test
    void deterministicReviewCheckpointSkipsAllRoleExecutorsAndResumesAtValidation() {
        ResumeFixture fixture = resumeFixture(TaskFailurePhase.DETERMINISTIC_REVIEW, validDeliveryJson());
        AtomicInteger reviewerCalls = new AtomicInteger();
        RequirementDeliveryEngine engine = fixture.engine(new RequirementDeliveryReviewer() {
            @Override
            public RequirementDeliveryReviewResult review(String taskId, String deliveryResultJson) {
                reviewerCalls.incrementAndGet();
                assertTrue(deliveryResultJson.contains("multiAgentStages"));
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
    void aiReviewProviderRetrySkipsDeterministicReviewAndRolesThenPublishesOnOk() {
        ResumeFixture fixture = resumeFixture(TaskFailurePhase.AI_REVIEW,
                withDeliveryReview(validDeliveryJson()));
        RequirementDeliveryEngine engine = fixture.engine(new RequirementDeliveryReviewer() {
            @Override
            public RequirementDeliveryReviewResult review(String taskId, String deliveryResultJson) {
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

    private static RdRequirementTask recoveringTask() {
        return new RdRequirementTask(
                "task-1", "REQUIREMENT", "ADMIN", "source", "", "P1", RdTaskStatus.RECOVERING,
                "订单状态筛选", "project-1", "waimai", "外卖项目",
                "https://github.com/acme/waimai", "acme", "waimai", "main", "feature/status",
                "支持状态筛选", "[\"筛选正确\"]", "prompt",
                """
                        {"status":"SUCCESS","multiAgentStages":[{"role":"QA_AGENT","success":true}],
                         "deliveryReview":{"approved":true},
                         "pullRequestPublication":{"success":false,"errorMessage":"temporary"}}
                        """,
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
        return """
                {"multiAgentStatus":"SUCCESS","multiAgentStages":[
                  {"role":"REQUIREMENT_REVIEWER","success":true,"pullRequestUrl":"","resultJson":"{}"},
                  {"role":"SOLUTION_ARCHITECT","success":true,"pullRequestUrl":"","resultJson":"{}"},
                  {"role":"CODING_AGENT","success":true,"pullRequestUrl":"","resultJson":"{\\\"changedFiles\\\":[\\\"a.java\\\"]}"},
                  {"role":"QA_AGENT","success":true,"pullRequestUrl":"","resultJson":"{\\\"status\\\":\\\"PASSED\\\",\\\"acceptanceResults\\\":[{\\\"criteria\\\":\\\"筛选正确\\\",\\\"command\\\":\\\"mvn test\\\",\\\"status\\\":\\\"PASSED\\\",\\\"logArtifactId\\\":\\\"log-1\\\"}]}"}
                ]}
                """;
    }

    private static String withDeliveryReview(String value) {
        String normalized = value.strip();
        return normalized.substring(0, normalized.length() - 1)
                + ",\"deliveryReview\":{\"approved\":true}}";
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
            RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                    registry, materials, request -> {
                        executorCalls.incrementAndGet();
                        throw new AssertionError("role executor must not run during review-only retry");
                    },
                    new RequirementContextBuilder(), new RequirementPlanGenerator(),
                    new RuleBasedRequirementPolicyGate(), new AgentStagePlanner(ids::nextIdString),
                    new InMemoryAgentStageRunStore(), new InMemoryAgentStageArtifactStore(),
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
