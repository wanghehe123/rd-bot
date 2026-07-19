package com.wish.rd.engine.requirement;

import com.wish.rd.engine.agent.AgentStagePlanner;
import com.wish.rd.engine.agent.AgentWorkflowAlertSinkPort;
import com.wish.rd.engine.agent.WorkflowExperienceStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.model.RequirementExecutionResult;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublication;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublishCommand;
import com.wish.rd.engine.requirement.review.AiDeliveryReviewEngine;
import com.wish.rd.engine.requirement.review.AiReviewModelPort;
import com.wish.rd.engine.requirement.review.AiReviewResultValidator;
import com.wish.rd.engine.requirement.review.impl.InMemoryAiReviewRunStore;
import com.wish.rd.engine.requirement.review.model.AiReviewPackage;
import com.wish.rd.engine.requirement.review.model.AiReviewPart;
import com.wish.rd.engine.requirement.review.model.AiReviewSource;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.context.RoleContextBuilder;
import com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.impl.InMemoryTaskMaterialStore;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequirementDeliveryAiReviewGateTest {

    @Test
    void aiNotOkBlocksPullRequestAndMarksTaskNeedsHuman() {
        Fixture fixture = fixture(request -> AiReviewModelPort.ModelResponse.available("model-a", """
                {"decision":"NOT_OK","score":45,"summary":"code does not satisfy requirement",
                 "retryFromRole":"CODING_AGENT","dimensions":[],
                 "findings":[{"severity":"HIGH","title":"missing behavior","detail":"state is not updated",
                 "sourceIds":["source-1"],"suggestion":"fix coding result"}]}
                """));

        var result = fixture.engine.submit(fixture.taskId);

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, result.status());
        assertEquals(0, fixture.publisher.calls);
    }

    @Test
    void aiProviderFailureMarksTaskRetryableWithoutPublishing() {
        Fixture fixture = fixture(request -> AiReviewModelPort.ModelResponse.unavailable(
                "model-a", "PROVIDER_TIMEOUT", "timeout"));

        var result = fixture.engine.submit(fixture.taskId);

        assertEquals(RdTaskStatus.FAILED_RETRYABLE, result.status(), result.errorMessage());
        assertEquals(0, fixture.publisher.calls);
    }

    @Test
    void aiOkAllowsPullRequestPublication() {
        Fixture fixture = fixture(request -> AiReviewModelPort.ModelResponse.available("model-a", """
                {"decision":"OK","score":95,"summary":"delivery is complete","retryFromRole":"",
                 "dimensions":[{"name":"qa_acceptance","score":96,"reason":"passed","sourceIds":["source-1"]}],
                 "findings":[]}
                """));

        var result = fixture.engine.submit(fixture.taskId);

        assertEquals(RdTaskStatus.COMPLETED, result.status(), result.errorMessage());
        assertEquals(1, fixture.publisher.calls);
    }

    private Fixture fixture(AiReviewModelPort modelPort) {
        AtomicLong time = new AtomicLong(1_783_000_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, time::getAndIncrement);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        var task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "增加订单催单功能", "P1", "https://github.com/example/waimai.git",
                "example", "waimai", "main", "订单详情页可以催单",
                List.of("前端构建通过"), false));
        materialStore.save(new TaskMaterial(
                ids.nextIdString(), task.taskId(), TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT, "需求正文", "", "text/markdown",
                "sha256:material", "用户可以在订单详情页点击催单。", "", "", "", "{}", 1L, 1L));
        InMemoryAgentStageRunStore stageStore = new InMemoryAgentStageRunStore();
        InMemoryAgentStageArtifactStore artifactStore = new InMemoryAgentStageArtifactStore();
        InMemoryRoleContextPackageStore contextStore = new InMemoryRoleContextPackageStore();
        RecordingPublisher publisher = new RecordingPublisher();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, materialStore, request -> roleResult(request.taskId(), request.role()),
                new RequirementContextBuilder(), new RequirementPlanGenerator(), new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(ids::nextIdString), stageStore, artifactStore,
                new RoleContextBuilder(), contextStore, AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(), new RequirementDeliveryReviewer(), publisher);

        AtomicInteger reviewIds = new AtomicInteger();
        AiReviewSource source = new AiReviewSource("source-1", "QA", AgentRole.QA_AGENT.name(),
                "RESULT_JSON", "sha256:source", "qa passed", "{}", 1L);
        AiReviewPackage reviewPackage = new AiReviewPackage(task.taskId(), List.of(source),
                List.of(new AiReviewPart(1, List.of("source-1"), "qa passed", 9)),
                9, 0, "sha256:package");
        AiDeliveryReviewEngine aiEngine = new AiDeliveryReviewEngine(
                new InMemoryAiReviewRunStore(), (ignoredTask, ignoredReview) -> reviewPackage,
                modelPort, new AiReviewResultValidator(),
                () -> "ai-id-" + reviewIds.incrementAndGet(),
                () -> 2_000L + reviewIds.get(), "model-a");
        engine.setAiDeliveryReviewEngine(aiEngine);
        return new Fixture(engine, task.taskId(), publisher);
    }

    private RequirementExecutionResult roleResult(String taskId, AgentRole role) {
        if (role == AgentRole.REQUIREMENT_REVIEWER) {
            return RequirementExecutionResult.success(taskId, "review complete", "", """
                    {
                      "decision":"APPROVED",
                      "feasibility":"CAN_DO",
                      "missingInformation":[],
                      "risks":[],
                      "acceptanceCoverage":["前端构建通过"],
                      "budgetEstimate":{
                        "initialTokens":80000,
                        "retryReserveTokens":20000,
                        "estimatedTotalTokens":100000,
                        "confidence":"LOW",
                        "basis":"无历史样本时由模型估算完整四角色交付",
                        "historicalSamples":[]
                      }
                    }
                    """);
        }
        if (role == AgentRole.CODING_AGENT) {
            return RequirementExecutionResult.success(taskId, "coding complete", "", """
                    {"status":"SUCCESS","changedFiles":"OrderService.java","testSummary":"tests passed",
                     "prBody":"implemented requirement"}
                    """);
        }
        if (role == AgentRole.QA_AGENT) {
            return RequirementExecutionResult.success(taskId, "qa passed", "", """
                    {
                      "status":"PASSED",
                      "summary":"当前需求与回归验收通过",
                      "failureCategory":"NONE",
                      "retryRecommendation":"NONE",
                      "browserValidation":{"required":false,"performed":false,
                        "decisionSource":"NOT_APPLICABLE","baseUrl":"","browser":"chromium","viewports":[]},
                      "acceptanceResults":[
                        {"criteria":"前端构建通过","scope":"CURRENT","command":"npm run build",
                         "status":"PASSED","exitCode":0,"durationMillis":100,
                         "logArtifactId":"qa-evidence/commands/current.log",
                         "evidenceArtifactIds":["qa-evidence/commands/current.log"]},
                        {"criteria":"既有功能回归","scope":"REGRESSION","command":"npm test",
                         "status":"PASSED","exitCode":0,"durationMillis":100,
                         "logArtifactId":"qa-evidence/commands/regression.log",
                         "evidenceArtifactIds":["qa-evidence/commands/regression.log"]}
                      ],
                      "evidenceManifestArtifactId":"qa-evidence/manifest.json"
                    }
                    """);
        }
        return RequirementExecutionResult.success(taskId, role.name() + " complete", "",
                "{\"status\":\"SUCCESS\"}");
    }

    private static final class RecordingPublisher implements RequirementPullRequestPublisherPort {
        private int calls;

        @Override
        public RequirementPullRequestPublication publish(RequirementPullRequestPublishCommand command) {
            calls++;
            return RequirementPullRequestPublication.success(command.taskId(),
                    "https://github.com/example/waimai/pull/12", "12", "{}");
        }
    }

    private record Fixture(RequirementDeliveryEngine engine, String taskId, RecordingPublisher publisher) {
    }
}
