package com.wish.rd.engine.requirement;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.AgentStagePlanner;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.agent.model.AgentWorkflowAlert;
import com.wish.rd.engine.agent.AgentWorkflowAlertSinkPort;
import com.wish.rd.engine.agent.model.AgentWorkflowAlertType;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.recovery.InterruptedStageRecoveryService;
import com.wish.rd.engine.agent.recovery.model.RecoveredWorkspaceExecution;
import com.wish.rd.engine.agent.model.WorkflowExperienceEntry;
import com.wish.rd.engine.agent.WorkflowExperienceStore;
import com.wish.rd.engine.agent.model.WorkflowExperienceType;
import com.wish.rd.engine.retrieval.DeepRetrievalOrchestrator;
import com.wish.rd.engine.retrieval.RequirementKnowledgeSearchPort;
import com.wish.rd.engine.retrieval.model.RetrievalScope;
import com.wish.rd.engine.retrieval.model.SearchResult;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore;
import com.wish.rd.rag.context.RoleContextBuilder;
import com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle;
import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.impl.InMemoryTaskMaterialStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.engine.requirement.model.RequirementDeliveryResult;
import com.wish.rd.engine.requirement.model.RequirementDeliveryReviewResult;
import com.wish.rd.engine.requirement.model.RequirementExecutionRequest;
import com.wish.rd.engine.requirement.model.RequirementExecutionResult;
import com.wish.rd.engine.requirement.model.RequirementExecutionProfileResolution;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublication;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublishCommand;
import com.wish.rd.engine.requirement.model.RequirementBranchPublication;
import com.wish.rd.engine.requirement.model.RequirementBranchPublishCommand;

class RequirementDeliveryEngineTest {

    @Test
    void shouldExecuteRequirementTaskAndCommitPullRequest() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "增加订单催单功能",
                "P1",
                "https://github.com/example/waimai.git",
                "example",
                "waimai",
                "main",
                "订单详情页可以催单",
                List.of("前端构建通过"),
                false
        ));
        materialStore.save(new TaskMaterial(
                "7820000000001",
                task.taskId(),
                TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT,
                "需求正文",
                "",
                "text/markdown",
                "sha256:test",
                "用户可以在订单详情页点击催单。",
                "",
                "",
                "",
                "{}",
                1L,
                1L
        ));
        List<RequirementExecutionRequest> captured = new CopyOnWriteArrayList<>();
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        InMemoryWorkflowExperienceStore experienceStore = new InMemoryWorkflowExperienceStore();
        RecordingRequirementPullRequestPublisher pullRequestPublisher = new RecordingRequirementPullRequestPublisher();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    captured.add(request);
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(),
                                "实现完成",
                                "",
                                codingResultJson(request.role())
                        );
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(),
                            request.role().name() + " 完成",
                            "",
                            roleResultJson(request.role())
                    );
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                experienceStore,
                new RequirementDeliveryReviewer(),
                pullRequestPublisher
        );
        engine.setExecutionProfileResolver((resolvedTask, role, stageRunId, attemptNo) ->
                RequirementExecutionProfileResolution.of("snapshot-" + stageRunId, "")
        );

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        assertEquals("https://github.com/example/waimai/pull/12", result.pullRequestUrl());
        assertTrue(result.resultJson().contains("\"deliveryReview\""));
        assertTrue(result.resultJson().contains("\"approved\":true"));
        assertTrue(result.resultJson().contains("\"reviewer\":\"DELIVERY_REVIEWER\""));
        assertEquals(RdTaskStatus.COMPLETED, registry.getTask(task.taskId()).status());
        assertEquals(List.of(
                AgentRole.REQUIREMENT_REVIEWER,
                AgentRole.SOLUTION_ARCHITECT,
                AgentRole.CODING_AGENT,
                AgentRole.QA_AGENT
        ), captured.stream().map(RequirementExecutionRequest::role).toList());
        assertEquals(List.of(false, false, false, false),
                captured.stream().map(RequirementExecutionRequest::pullRequestRequired).toList());
        assertEquals(4, captured.stream()
                .filter(request -> !request.executionProfileSnapshotId().isBlank())
                .count());
        assertEquals(task.taskId(), pullRequestPublisher.command().taskId());
        assertEquals("requirement/" + task.taskId(), pullRequestPublisher.command().workBranch());
        assertTrue(pullRequestPublisher.command().deliveryResultJson().contains("\"deliveryReview\""));
        assertTrue(captured.getFirst().prompt().contains("REQUIREMENT_REVIEWER"));
        assertTrue(captured.get(2).prompt().contains("用户可以在订单详情页点击催单。"));
        assertTrue(captured.get(3).upstreamResultJson().contains("CODING_AGENT"));
        assertEquals(List.of(
                RdTaskStatus.CREATED.name(),
                RdTaskStatus.MATERIAL_COLLECTING.name(),
                RdTaskStatus.MATERIAL_READY.name(),
                RdTaskStatus.CONTEXT_BUILDING.name(),
                RdTaskStatus.CONTEXT_READY.name(),
                RdTaskStatus.PLAN_GENERATING.name(),
                RdTaskStatus.PLAN_GENERATED.name(),
                RdTaskStatus.WAITING_POLICY.name(),
                RdTaskStatus.EXECUTING.name(),
                RdTaskStatus.VALIDATING.name(),
                RdTaskStatus.PR_CREATING.name(),
                RdTaskStatus.COMMITTED.name(),
                RdTaskStatus.REPORTING.name(),
                RdTaskStatus.COMPLETED.name()
        ), registry.timeline(task.taskId()).stream().map(event -> event.status()).toList());
        assertTrue(captured.getFirst().prompt().contains("# 角色上下文"));
        assertTrue(captured.getFirst().prompt().contains("# 上游交接摘要"));
        assertTrue(captured.getFirst().prompt().contains("# 需求摘要"));
        assertTrue(captured.getFirst().prompt().contains("# 实现计划"));
        assertTrue(captured.getFirst().prompt().contains("# 策略决策"));
        assertTrue(captured.getFirst().prompt().contains("policyAction: ALLOWED"));
        assertTrue(captured.get(2).prompt().contains("不得删除 node_modules、package-lock.json 或 /work/cache"));
        assertTrue(captured.get(2).prompt().contains("npm run build && npm run start"));
        assertTrue(captured.get(2).prompt().contains("HTTP 请求必须设置不超过 30 秒的请求超时"));
        assertTrue(captured.get(3).prompt().contains("\"acceptanceResults\""));
        assertTrue(captured.get(3).prompt().contains("真实执行命令"));
        assertTrue(captured.get(3).prompt().contains("\"failureCategory\""));
        assertTrue(captured.get(3).prompt().contains("\"retryRecommendation\""));
        assertTrue(captured.get(3).prompt().contains("\"browserValidation\""));
        assertTrue(captured.get(3).prompt().contains("\"scope\": \"CURRENT|REGRESSION\""));
        assertTrue(captured.get(3).prompt().contains("\"evidenceManifestArtifactId\""));
        assertTrue(captured.get(3).prompt().contains("/work/input/qa-profile.json"));
        assertTrue(captured.get(3).prompt().contains("不得修改 /work/repo 中的跟踪文件"));
        assertTrue(captured.getFirst().prompt().contains("\"decision\""));
        assertTrue(captured.get(1).prompt().contains("\"implementationSteps\""));
        assertEquals(List.of(
                AgentRole.REQUIREMENT_REVIEWER,
                AgentRole.SOLUTION_ARCHITECT,
                AgentRole.CODING_AGENT,
                AgentRole.QA_AGENT
        ), stageRunStore.listByTask(task.taskId()).stream().map(AgentStageRun::role).toList());
        assertTrue(stageRunStore.listByTask(task.taskId()).stream()
                .allMatch(stage -> stage.status().name().equals("SUCCEEDED")));
        assertTrue(stageRunStore.listByTask(task.taskId()).stream()
                .allMatch(stage -> !stage.promptArtifactId().isBlank()),
                "each successful role must bind a persisted prompt artifact");
        assertTrue(stageRunStore.listByTask(task.taskId()).stream()
                .allMatch(stage -> !stage.resultArtifactId().isBlank()),
                "each successful role must bind a persisted result artifact");
        AgentStageRun codingStage = stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.CODING_AGENT)
                .findFirst()
                .orElseThrow();
        assertEquals("claude-code-coding_agent", codingStage.providerName());
        assertTrue(codingStage.providerAttemptsJson().contains("\"provider\":\"deepseek\""));
        assertTrue(codingStage.providerAttemptsJson().contains("\"status\":\"FAILED_VALIDATION\""));
        assertTrue(codingStage.providerAttemptsJson().contains("\"provider\":\"claude-code-coding_agent\""));
        assertTrue(codingStage.providerAttemptsJson().contains("\"status\":\"SUCCESS\""));
        assertEquals(List.of(
                WorkflowExperienceType.REQUIREMENT_REVIEW,
                WorkflowExperienceType.TECHNICAL_DESIGN,
                WorkflowExperienceType.CODE_CHANGE,
                WorkflowExperienceType.QA_REPORT,
                WorkflowExperienceType.DELIVERY_REPORT
        ), experienceStore.listByTask(task.taskId()).stream().map(WorkflowExperienceEntry::experienceType).toList());
        assertTrue(experienceStore.listByTask(task.taskId()).stream()
                        .allMatch(entry -> !entry.sourceArtifactId().isBlank()),
                "successful workflow experiences must link back to source stage artifacts");
        assertTrue(experienceStore.listByTask(task.taskId()).stream()
                        .allMatch(entry -> entry.repositoryFingerprint().equals("example/waimai")
                                && entry.evidenceQuality() > 0.0d
                                && !entry.applicableRoles().isEmpty()),
                "new workflow experiences must carry repository scope, quality, and applicable roles");
    }

    @Test
    void shouldPushReviewedBranchBeforeCreatingPullRequest() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "branch-push-order-material",
                "分支推送顺序测试",
                "建 PR 前必须先推送工作分支",
                "验证复核通过后先推送工作分支再创建 PR。"
        );
        List<String> publishOrder = new CopyOnWriteArrayList<>();
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        OrderRecordingPullRequestPublisher pullRequestPublisher =
                new OrderRecordingPullRequestPublisher(publishOrder);
        RequirementDeliveryEngine engine =
                happyPathEngine(registry, materialStore, stageRunStore, pullRequestPublisher);
        RecordingRequirementBranchPublisher branchPublisher =
                new RecordingRequirementBranchPublisher(publishOrder, false, "");
        engine.setBranchPublisher(branchPublisher);

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        assertEquals(List.of("branch", "pull-request"), publishOrder,
                "工作分支必须在创建 PR 之前推送");
        assertEquals(task.taskId(), branchPublisher.command().taskId());
        assertEquals("requirement/" + task.taskId(), branchPublisher.command().workBranch());
        assertEquals("example", branchPublisher.command().repoOwner());
        assertEquals("waimai", branchPublisher.command().repoName());
        assertTrue(branchPublisher.command().deliveryResultJson().contains("\"deliveryReview\""));
        assertTrue(pullRequestPublisher.invoked());
    }

    @Test
    void shouldRejectRequirementWhenReviewedBranchPushFails() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "branch-push-failure-material",
                "分支推送失败测试",
                "分支推送失败必须拒绝交付",
                "验证工作分支推送失败时拒绝交付且不创建 PR。"
        );
        List<String> publishOrder = new CopyOnWriteArrayList<>();
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        OrderRecordingPullRequestPublisher pullRequestPublisher =
                new OrderRecordingPullRequestPublisher(publishOrder);
        RequirementDeliveryEngine engine =
                happyPathEngine(registry, materialStore, stageRunStore, pullRequestPublisher);
        engine.setBranchPublisher(new RecordingRequirementBranchPublisher(
                publishOrder, true, "push rejected: head not found"));

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.REJECTED, result.status());
        assertTrue(result.errorMessage().contains("pull request publication failed"));
        assertTrue(result.errorMessage().contains("push rejected: head not found"));
        assertFalse(pullRequestPublisher.invoked(), "分支推送失败后不得创建 PR");
        assertEquals(List.of("branch"), publishOrder);
        assertEquals(RdTaskStatus.REJECTED, registry.getTask(task.taskId()).status());
    }

    private RequirementDeliveryEngine happyPathEngine(
            RagStreamTaskRegistry registry,
            InMemoryTaskMaterialStore materialStore,
            AgentStageRunStore stageRunStore,
            RequirementPullRequestPublisherPort pullRequestPublisher
    ) {
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(), "实现完成", "", codingResultJson(request.role()));
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(), request.role().name() + " 完成", "", roleResultJson(request.role()));
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                pullRequestPublisher
        );
        engine.setExecutionProfileResolver((resolvedTask, role, stageRunId, attemptNo) ->
                RequirementExecutionProfileResolution.of("snapshot-" + stageRunId, ""));
        return engine;
    }

    @Test
    void shouldForwardOnlyCompactPrivateHandoffMetadataToTheNextRole() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "handoff-material",
                "紧凑交接测试",
                "下游应只读取交接文档",
                "验证角色间不再传播整段执行 JSON。"
        );
        List<RequirementExecutionRequest> captured = new CopyOnWriteArrayList<>();
        String reviewerResult = """
                {
                  "decision": "APPROVED",
                  "feasibility": "CAN_DO",
                  "missingInformation": [],
                  "risks": [],
                  "acceptanceCoverage": ["前端构建通过"],
                  "budgetEstimate": {
                    "initialTokens": 80000,
                    "retryReserveTokens": 20000,
                    "estimatedTotalTokens": 100000,
                    "confidence": "LOW",
                    "basis": "完整交付估算",
                    "historicalSamples": []
                  },
                  "largeRaw": "DO_NOT_FORWARD_RAW_ROLE_JSON",
                  "dockerMetadata": {"image": "rd-bot/claude-code:local", "containerId": "container-raw"},
                  "roleHandoff": {
                    "sourceRole": "REQUIREMENT_REVIEWER",
                    "targetRole": "SOLUTION_ARCHITECT",
                    "artifactName": "handoff/next.md",
                    "artifactUri": "s3://rd-role-handoffs/private-review.md",
                    "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "bytes": 321,
                    "summary": "需求评审已经批准，详细约束见交接文档"
                  }
                }
                """;
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    captured.add(request);
                    if (request.role() == AgentRole.REQUIREMENT_REVIEWER) {
                        return RequirementExecutionResult.success(request.taskId(), "评审通过", "", reviewerResult);
                    }
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.success(request.taskId(), "实现完成", "", codingResultJson(request.role()));
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(), request.role().name() + " 完成", "", roleResultJson(request.role()));
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                new InMemoryAgentStageRunStore(),
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                new RecordingRequirementPullRequestPublisher()
        );

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        RequirementExecutionRequest architect = captured.stream()
                .filter(request -> request.role() == AgentRole.SOLUTION_ARCHITECT)
                .findFirst()
                .orElseThrow();
        assertTrue(architect.upstreamResultJson().contains("\"handoff\""));
        assertFalse(architect.upstreamResultJson().contains("roleHandoff"));
        assertFalse(architect.upstreamResultJson().contains("dockerMetadata"));
        assertFalse(architect.upstreamResultJson().contains("DO_NOT_FORWARD_RAW_ROLE_JSON"));
        assertTrue(architect.prompt().contains("# 上游交接摘要"));
        assertTrue(architect.prompt().contains("/work/input/attachments/handoff-requirement_reviewer.md"));
        assertFalse(architect.prompt().contains("s3://rd-role-handoffs/"));
    }

    @Test
    void shouldForwardOnlyVerifiedCodingPatchMetadataToLocalQa() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "candidate-patch-handoff",
                "候选补丁交接测试",
                "QA 必须在独立工作区验证 Coding 产生的补丁",
                "禁止把完整执行 JSON 或 RustFS 地址交给下游模型。"
        );
        List<RequirementExecutionRequest> captured = new CopyOnWriteArrayList<>();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    captured.add(request);
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(), "实现完成", "", codingResultWithCandidatePatchJson());
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(), request.role().name() + " 完成", "", roleResultJson(request.role()));
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                new InMemoryAgentStageRunStore(),
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                new RecordingRequirementPullRequestPublisher()
        );

        assertEquals(RdTaskStatus.COMPLETED, engine.submit(task.taskId()).status());

        RequirementExecutionRequest qa = captured.stream()
                .filter(request -> request.role() == AgentRole.QA_AGENT)
                .findFirst()
                .orElseThrow();
        assertTrue(qa.upstreamResultJson().contains("\"candidatePatch\""));
        assertTrue(qa.upstreamResultJson().contains("\"artifactName\":\"patch.diff\""));
        assertFalse(qa.upstreamResultJson().contains("PRIVATE_PATCH_CONTENT_MUST_NOT_REACH_QA_PROMPT"));
        assertTrue(qa.prompt().contains("/work/input/attachments/candidate-patch.diff"));
        assertFalse(qa.prompt().contains("s3://rd-role-handoffs/"));
    }

    @Test
    void shouldTellRolesToPerformBoundedRepositoryDiscoveryWhenRoleEvidenceIsMissing() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "repository-discovery-material",
                "受限仓库发现提示",
                "在代码库内定位并修复问题",
                "没有命中角色特定证据时，编码代理应在当前仓库中自行定位文件和测试入口。"
        );
        List<RequirementExecutionRequest> captured = new CopyOnWriteArrayList<>();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    captured.add(request);
                    return RequirementExecutionResult.success(
                            request.taskId(), request.role().name() + " 完成", "",
                            request.role() == AgentRole.CODING_AGENT
                                    ? codingResultJson(request.role())
                                    : roleResultJson(request.role())
                    );
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                new InMemoryAgentStageRunStore(),
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                new RecordingRequirementPullRequestPublisher()
        );
        AtomicInteger retrievalIds = new AtomicInteger();
        engine.setDeepRetrievalOrchestrator(new DeepRetrievalOrchestrator(
                new RetrievalRunLifecycle(
                        new InMemoryRetrievalRunStore(),
                        () -> "retrieval-" + retrievalIds.incrementAndGet(),
                        () -> 100L
                ),
                new RequirementKnowledgeSearchPort() {
                    @Override
                    public RetrievalScope resolveScope(RdRequirementTask ignored) {
                        return new RetrievalScope(List.of("project-kb"), "example/waimai", true, "");
                    }

                    @Override
                    public SearchResult search(
                            RdRequirementTask ignored,
                            AgentRole role,
                            String query,
                            RetrievalScope scope,
                            int topK
                    ) {
                        return SearchResult.empty();
                    }
                }
        ));

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        for (AgentRole role : List.of(AgentRole.SOLUTION_ARCHITECT, AgentRole.CODING_AGENT, AgentRole.QA_AGENT)) {
            RequirementExecutionRequest request = captured.stream()
                    .filter(candidate -> candidate.role() == role)
                    .findFirst()
                    .orElseThrow();
            assertTrue(request.prompt().contains("# 受限仓库发现"), request.prompt());
            assertTrue(request.prompt().contains("在形成方案、修改代码或执行 QA 前完成受限仓库发现"), request.prompt());
            assertTrue(request.prompt().contains("最多 12 条只读命令"), request.prompt());
        }
    }

    @Test
    void shouldReusePersistedHandoffArtifactWhenResultPreviewTruncatesOnRecovery() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "reused-handoff-material",
                "截断结果恢复测试",
                "恢复执行时仍应传递受控交接文档",
                "验证超过审计预览上限的结果不会丢失下游交接。"
        );
        List<RequirementExecutionRequest> captured = new CopyOnWriteArrayList<>();
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        InMemoryAgentStageArtifactStore artifactStore = new InMemoryAgentStageArtifactStore();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    captured.add(request);
                    if (request.role() == AgentRole.REQUIREMENT_REVIEWER) {
                        return RequirementExecutionResult.success(
                                request.taskId(), "评审通过", "", largeReviewerResultWithHandoff());
                    }
                    if (request.role() == AgentRole.SOLUTION_ARCHITECT) {
                        return RequirementExecutionResult.failure(
                                request.taskId(), "让恢复链路停在方案设计阶段", "{\"status\":\"FAILED\"}");
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(), request.role().name() + " 完成", "", roleResultJson(request.role()));
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore,
                artifactStore,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                new RecordingRequirementPullRequestPublisher()
        );

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, engine.submit(task.taskId()).status());
        AgentStageArtifact reviewerResult = artifactStore.listByTask(task.taskId()).stream()
                .filter(artifact -> artifact.role() == AgentRole.REQUIREMENT_REVIEWER)
                .filter(artifact -> artifact.artifactType().equals("RESULT_JSON"))
                .findFirst()
                .orElseThrow();
        assertEquals(20_000, reviewerResult.contentPreview().length());
        assertFalse(reviewerResult.contentPreview().contains("\"roleHandoff\""));
        assertTrue(artifactStore.listByTask(task.taskId()).stream()
                .anyMatch(artifact -> artifact.role() == AgentRole.REQUIREMENT_REVIEWER
                        && artifact.artifactType().equals("HANDOFF_MARKDOWN")));

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, engine.submit(task.taskId()).status());

        List<RequirementExecutionRequest> architectRequests = captured.stream()
                .filter(request -> request.role() == AgentRole.SOLUTION_ARCHITECT)
                .toList();
        assertEquals(2, architectRequests.size());
        String recoveredUpstream = architectRequests.get(1).upstreamResultJson();
        assertTrue(recoveredUpstream.contains("\"handoff\""));
        assertTrue(recoveredUpstream.contains("s3://rd-role-handoffs/review-recovery.md"));
        assertTrue(recoveredUpstream.contains("\"targetRole\":\"SOLUTION_ARCHITECT\""));
    }

    @Test
    void shouldStopCreatingRoleAttemptsAfterMaxRoleAttempts() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "attempt-cap-material",
                "attempt 上限测试",
                "重试风暴必须被 attempt 硬上限阻断",
                "验证同一角色最多只能创建 3 次 attempt。"
        );
        List<RequirementExecutionRequest> captured = new CopyOnWriteArrayList<>();
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        RequirementDeliveryEngine engine = failingArchitectEngine(registry, materialStore, captured, stageRunStore);

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, engine.submit(task.taskId()).status());
        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, engine.submit(task.taskId()).status());
        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, engine.submit(task.taskId()).status());
        engine.submit(task.taskId());

        List<AgentStageRun> architectStages = stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.SOLUTION_ARCHITECT)
                .toList();
        assertEquals(3, architectStages.size());
        assertEquals(3, architectStages.stream().mapToInt(AgentStageRun::attemptNo).max().orElse(0));
        assertEquals(3, captured.stream()
                .filter(request -> request.role() == AgentRole.SOLUTION_ARCHITECT)
                .count());
    }

    @Test
    void shouldInjectPreviousFailureFeedbackIntoRetryPrompt() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "failure-feedback-material",
                "失败反馈回注测试",
                "重试 attempt 必须看到上一轮失败明细",
                "验证协议校验失败明细会回注重试 prompt。"
        );
        List<RequirementExecutionRequest> captured = new CopyOnWriteArrayList<>();
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        RequirementDeliveryEngine engine = failingArchitectEngine(registry, materialStore, captured, stageRunStore);

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, engine.submit(task.taskId()).status());
        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, engine.submit(task.taskId()).status());

        List<RequirementExecutionRequest> architectRequests = captured.stream()
                .filter(request -> request.role() == AgentRole.SOLUTION_ARCHITECT)
                .toList();
        assertEquals(2, architectRequests.size());
        assertFalse(architectRequests.get(0).prompt().contains("上一轮失败反馈"));
        assertTrue(architectRequests.get(1).prompt().contains("上一轮失败反馈"));
        assertTrue(architectRequests.get(1).prompt().contains("缺少必填字段: implementationSteps"));
    }

    private RequirementDeliveryEngine failingArchitectEngine(
            RagStreamTaskRegistry registry,
            InMemoryTaskMaterialStore materialStore,
            List<RequirementExecutionRequest> captured,
            AgentStageRunStore stageRunStore
    ) {
        return new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    captured.add(request);
                    if (request.role() == AgentRole.SOLUTION_ARCHITECT) {
                        return RequirementExecutionResult.failure(
                                request.taskId(), "缺少必填字段: implementationSteps", "{\"status\":\"FAILED\"}");
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(), request.role().name() + " 完成", "", roleResultJson(request.role()));
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore,
                new InMemoryAgentStageArtifactStore(),
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                new RecordingRequirementPullRequestPublisher()
        );
    }

    @Test
    void shouldReusePersistedCandidatePatchWhenQaRetriesAfterResultPreviewTruncates() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "reused-candidate-patch-material",
                "候选补丁恢复测试",
                "QA 重试时仍应收到已验证候选补丁",
                "验证编码结果审计预览截断后仍能从独立产物恢复候选补丁。"
        );
        List<RequirementExecutionRequest> captured = new CopyOnWriteArrayList<>();
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        InMemoryAgentStageArtifactStore artifactStore = new InMemoryAgentStageArtifactStore();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    captured.add(request);
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(), "编码完成", "", largeCodingResultWithCandidatePatch());
                    }
                    if (request.role() == AgentRole.QA_AGENT) {
                        return RequirementExecutionResult.failure(
                                request.taskId(),
                                "QA evidence protocol rejected",
                                """
                                        {
                                          "status": "FAILED",
                                          "failureCategory": "QA_INFRASTRUCTURE",
                                          "retryRecommendation": "HUMAN"
                                        }
                                        """);
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(), request.role().name() + " 完成", "", roleResultJson(request.role()));
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore,
                artifactStore,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                new RecordingRequirementPullRequestPublisher()
        );

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, engine.submit(task.taskId()).status());
        AgentStageArtifact codingResult = artifactStore.listByTask(task.taskId()).stream()
                .filter(artifact -> artifact.role() == AgentRole.CODING_AGENT)
                .filter(artifact -> artifact.artifactType().equals("RESULT_JSON"))
                .findFirst()
                .orElseThrow();
        assertEquals(20_000, codingResult.contentPreview().length());
        assertFalse(codingResult.contentPreview().contains("\"candidatePatch\""));
        assertTrue(artifactStore.listByTask(task.taskId()).stream()
                .anyMatch(artifact -> artifact.role() == AgentRole.CODING_AGENT
                        && artifact.artifactType().equals("PATCH_DIFF")));

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, engine.submit(task.taskId()).status());

        List<RequirementExecutionRequest> qaRequests = captured.stream()
                .filter(request -> request.role() == AgentRole.QA_AGENT)
                .toList();
        assertEquals(2, qaRequests.size());
        String recoveredUpstream = qaRequests.get(1).upstreamResultJson();
        assertTrue(recoveredUpstream.contains("\"candidatePatch\""), recoveredUpstream);
        assertTrue(recoveredUpstream.contains("s3://rd-role-handoffs/private-candidate.patch"));
        assertTrue(recoveredUpstream.contains("\"targetRole\":\"QA_AGENT\""));
    }

    @Test
    void shouldApproveDeliveryReviewFromReusedCodingEvidenceAfterResultPreviewTruncates() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "truncated-coding-evidence-material",
                "截断交付证据复核测试",
                "复用阶段仍应保留可复核的编码交付证据",
                "验证编码结果审计预览截断后交付复核仍能读到 prBody 与 changedFiles。"
        );
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        InMemoryAgentStageArtifactStore artifactStore = new InMemoryAgentStageArtifactStore();
        AtomicInteger qaAttempts = new AtomicInteger();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(), "编码完成", "", largeCodingResultWithDeliveryEvidence());
                    }
                    if (request.role() == AgentRole.QA_AGENT && qaAttempts.incrementAndGet() == 1) {
                        return RequirementExecutionResult.failure(
                                request.taskId(),
                                "QA evidence protocol rejected",
                                """
                                        {
                                          "status": "FAILED",
                                          "failureCategory": "QA_INFRASTRUCTURE",
                                          "retryRecommendation": "HUMAN"
                                        }
                                        """);
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(), request.role().name() + " 完成", "", roleResultJson(request.role()));
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore,
                artifactStore,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                new RecordingRequirementPullRequestPublisher()
        );

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, engine.submit(task.taskId()).status());
        AgentStageArtifact codingResult = artifactStore.listByTask(task.taskId()).stream()
                .filter(artifact -> artifact.role() == AgentRole.CODING_AGENT)
                .filter(artifact -> artifact.artifactType().equals("RESULT_JSON"))
                .findFirst()
                .orElseThrow();
        assertEquals(20_000, codingResult.contentPreview().length());

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status(), result.errorMessage());
        assertTrue(result.resultJson().contains("\"resultJsonTruncated\":true"), result.resultJson());
        assertTrue(result.resultJson().contains("\"approved\":true"), result.resultJson());
    }

    @Test
    void shouldRejectDeliveryReviewWhenTruncatedCodingPreviewLosesEvidence() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "truncated-coding-evidence-loss-material",
                "截断交付证据缺失测试",
                "证据被截断时交付复核必须拒绝",
                "验证抢救出的顶层字段不含交付证据时不会放行。"
        );
        AtomicInteger qaAttempts = new AtomicInteger();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(), "编码完成", "", largeCodingResultWithoutDeliveryEvidence());
                    }
                    if (request.role() == AgentRole.QA_AGENT && qaAttempts.incrementAndGet() == 1) {
                        return RequirementExecutionResult.failure(
                                request.taskId(),
                                "QA evidence protocol rejected",
                                """
                                        {
                                          "status": "FAILED",
                                          "failureCategory": "QA_INFRASTRUCTURE",
                                          "retryRecommendation": "HUMAN"
                                        }
                                        """);
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(), request.role().name() + " 完成", "", roleResultJson(request.role()));
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                new InMemoryAgentStageRunStore(),
                new InMemoryAgentStageArtifactStore(),
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                new RecordingRequirementPullRequestPublisher()
        );

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, engine.submit(task.taskId()).status());
        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.REJECTED, result.status());
        assertTrue(result.errorMessage().contains("CODING_AGENT delivery evidence is incomplete"),
                result.errorMessage());
    }

    @Test
    void shouldPauseForTokenBudgetThenResumeWithoutReplayingReviewer() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "预算审批需求", "P1", "ADMIN", "", "", "project-1", "project", "Project",
                "https://github.com/example/waimai.git", "example", "waimai", "main", "实现预算审批", List.of("前端构建通过"),
                List.of(), false, 100L
        ));
        materialStore.save(new TaskMaterial(
                "budget-material", task.taskId(), TaskMaterialType.REQUIREMENT_DOC, TaskMaterialSourceType.MANUAL_TEXT,
                "需求正文", "", "text/markdown", "sha256:budget", "实现预算审批", "", "", "", "{}", 1L, 1L
        ));
        List<RequirementExecutionRequest> captured = new CopyOnWriteArrayList<>();
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        RecordingRequirementPullRequestPublisher publisher = new RecordingRequirementPullRequestPublisher();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, materialStore,
                request -> {
                    captured.add(request);
                    if (request.role() == AgentRole.REQUIREMENT_REVIEWER) {
                        return RequirementExecutionResult.success(request.taskId(), "评审通过", "", """
                                {
                                  "decision":"APPROVED", "feasibility":"CAN_DO", "missingInformation":[], "risks":[],
                                  "acceptanceCoverage":["前端构建通过"],
                                  "budgetEstimate":{"initialTokens":80,"retryReserveTokens":21,"estimatedTotalTokens":101,
                                    "confidence":"LOW","basis":"没有历史样本，由模型判断完整四角色和一次重试","historicalSamples":[]}
                                }
                                """);
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(), request.role().name() + " 完成", "",
                            request.role() == AgentRole.CODING_AGENT ? codingResultJson(request.role()) : roleResultJson(request.role())
                    );
                },
                new RequirementContextBuilder(), new RequirementPlanGenerator(), new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()), stageRunStore, new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(), AgentWorkflowAlertSinkPort.noop(), WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(), publisher
        );

        RequirementDeliveryResult waiting = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.WAITING_APPROVAL, waiting.status());
        assertTrue(waiting.resultJson().contains("\"effectiveTokenBudget\":100"));
        assertEquals(List.of(AgentRole.REQUIREMENT_REVIEWER), captured.stream().map(RequirementExecutionRequest::role).toList());
        assertEquals(AgentStageStatus.SUCCEEDED, stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.REQUIREMENT_REVIEWER).findFirst().orElseThrow().status());

        registry.approveRequirementTask(task.taskId(), "预算确认");
        RequirementDeliveryResult completed = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, completed.status());
        assertEquals(1L, captured.stream().filter(request -> request.role() == AgentRole.REQUIREMENT_REVIEWER).count());
    }

    @Test
    void shouldBlockRequirementWhenBudgetEstimateIsInvalidWithoutHistory() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry, materialStore, "budget-invalid", "预算估算", "预算估算", "验证无历史预算估算"
        );
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        List<RequirementExecutionRequest> captured = new CopyOnWriteArrayList<>();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, materialStore,
                request -> {
                    captured.add(request);
                    return RequirementExecutionResult.success(request.taskId(), "评审通过", "", """
                            {
                              "decision":"APPROVED", "feasibility":"CAN_DO", "missingInformation":[], "risks":[],
                              "acceptanceCoverage":["前端构建通过"],
                              "budgetEstimate":{"initialTokens":80,"retryReserveTokens":20,"estimatedTotalTokens":100,
                                "confidence":"HIGH","basis":"无历史时错误声明高置信度","historicalSamples":[]}
                            }
                            """);
                },
                new RequirementContextBuilder(), new RequirementPlanGenerator(), new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()), stageRunStore, new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(), AgentWorkflowAlertSinkPort.noop(), WorkflowExperienceStore.noop()
        );

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, result.status());
        assertTrue(result.errorMessage().contains("confidence must be LOW without historical samples"));
        assertEquals(List.of(AgentRole.REQUIREMENT_REVIEWER), captured.stream().map(RequirementExecutionRequest::role).toList());
    }

    @Test
    void shouldPersistCodingExecutorArtifactsAsStageArtifacts() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "7820000000030",
                "增加订单催单功能",
                "订单详情页可以催单",
                "用户可以在订单详情页点击催单。"
        );
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        InMemoryRoleContextPackageStore contextStore = new InMemoryRoleContextPackageStore();
        InMemoryAgentStageArtifactStore artifactStore = new InMemoryAgentStageArtifactStore();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(),
                                "实现完成",
                                "",
                                """
                                        {
                                          "status": "SUCCESS",
                                          "summary": "实现完成",
                                          "changedFiles": "src/main/java/com/example/OrderController.java",
                                          "testCommands": "./mvnw test",
                                          "testStatus": "PASSED",
                                          "testsFailed": 0,
                                          "validationExitCode": 0,
                                          "prBody": "## Summary\\n- implement requirement",
                                          "dockerMetadata": {
                                            "image": "rd-bot/claude-code:local",
                                            "containerId": "container-123",
                                            "workspacePath": "/tmp/rd-bot/work",
                                            "provider": "claude-code-coding_agent",
                                            "providerAttemptsJson": "[{\\"provider\\":\\"claude-code-coding_agent\\",\\"status\\":\\"SUCCESS\\"}]"
                                          },
                                          "stageArtifacts": [
                                            {
                                              "type": "PATCH_DIFF",
                                              "uri": "rd-artifact://task/coding/patch.diff",
                                              "summary": "代码补丁",
                                              "contentPreview": "diff --git a/src/main/java/com/example/OrderController.java b/src/main/java/com/example/OrderController.java",
                                              "metadataJson": {
                                                "bytes": "128",
                                                "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                                                "contentType": "text/x-diff",
                                                "artifactName": "patch.diff"
                                              }
                                            },
                                            {
                                              "type": "TEST_LOG",
                                              "uri": "rd-artifact://task/coding/test.log",
                                              "summary": "测试日志",
                                              "contentPreview": "./mvnw test passed"
                                            },
                                            {
                                              "type": "DOCKER_METADATA",
                                              "uri": "rd-artifact://task/coding/docker-metadata.json",
                                              "summary": "Docker metadata",
                                              "contentPreview": {
                                                "image": "rd-bot/claude-code:local",
                                                "containerId": "container-123",
                                                "workspacePath": "/tmp/rd-bot/work",
                                                "exitCode": 0
                                              }
                                            }
                                          ]
                                        }
                                        """
                        );
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(),
                            request.role().name() + " 完成",
                            "",
                            successfulRoleResultWithoutProviderFallback(request.role())
                    );
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore,
                artifactStore,
                new RoleContextBuilder(),
                contextStore,
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                new RecordingRequirementPullRequestPublisher()
        );

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        List<AgentStageArtifact> codingArtifacts = artifactStore.listByTask(task.taskId()).stream()
                .filter(artifact -> artifact.role() == AgentRole.CODING_AGENT)
                .toList();
        assertTrue(codingArtifacts.stream().anyMatch(artifact -> artifact.artifactType().equals("RESULT_JSON")));
        assertTrue(codingArtifacts.stream().anyMatch(artifact -> artifact.artifactType().equals("PATCH_DIFF")));
        assertTrue(codingArtifacts.stream().anyMatch(artifact -> artifact.artifactType().equals("TEST_LOG")));
        AgentStageArtifact patchArtifact = codingArtifacts.stream()
                .filter(artifact -> artifact.artifactType().equals("PATCH_DIFF"))
                .findFirst()
                .orElseThrow();
        assertEquals("sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                patchArtifact.contentHash());
        AgentStageArtifact dockerMetadata = codingArtifacts.stream()
                .filter(artifact -> artifact.artifactType().equals("DOCKER_METADATA"))
                .findFirst()
                .orElseThrow();
        assertTrue(dockerMetadata.contentPreview().contains("\"containerId\":\"container-123\""));
    }

    @Test
    void shouldInjectReusableExperienceIntoLaterSimilarRequirementContext() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        List<RequirementExecutionRequest> captured = new CopyOnWriteArrayList<>();
        InMemoryWorkflowExperienceStore experienceStore = new InMemoryWorkflowExperienceStore();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    captured.add(request);
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(),
                                "实现订单催单功能",
                                "",
                                codingResultJson(request.role())
                        );
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(),
                            request.role().name() + " 完成",
                            "",
                            roleResultJson(request.role())
                    );
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                new InMemoryAgentStageRunStore(),
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                experienceStore,
                new RequirementDeliveryReviewer(),
                new RecordingRequirementPullRequestPublisher()
        );
        RdRequirementTask firstTask = createRequirementTask(
                registry,
                materialStore,
                "7820000000101",
                "增加订单催单功能",
                "订单详情页可以催单",
                "用户可以在订单详情页点击催单。"
        );

        engine.submit(firstTask.taskId());
        assertTrue(experienceStore.listByTask(firstTask.taskId()).stream()
                .anyMatch(WorkflowExperienceEntry::reusable));
        captured.clear();
        RdRequirementTask secondTask = createRequirementTask(
                registry,
                materialStore,
                "7820000000102",
                "优化订单催单功能",
                "订单催单失败时可以重试",
                "订单详情页催单失败时显示重试提示。"
        );

        engine.submit(secondTask.taskId());

        RequirementExecutionRequest reviewerRequest = captured.stream()
                .filter(request -> request.taskId().equals(secondTask.taskId()))
                .filter(request -> request.role() == AgentRole.REQUIREMENT_REVIEWER)
                .findFirst()
                .orElseThrow();
        assertTrue(
                reviewerRequest.roleContextJson().contains("历史经验 - 需求交付报告"),
                "later similar requirement context must include reusable workflow experience"
        );
    }

    @Test
    void shouldStopBeforeExecutorWhenAcceptanceCriteriaMissing() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "增加订单催单功能",
                "P1",
                "https://github.com/example/waimai.git",
                "example",
                "waimai",
                "main",
                "订单详情页可以催单",
                List.of(),
                false
        ));
        materialStore.save(new TaskMaterial(
                "7820000000002",
                task.taskId(),
                TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT,
                "需求正文",
                "",
                "text/markdown",
                "sha256:test",
                "用户可以在订单详情页点击催单。",
                "",
                "",
                "",
                "{}",
                1L,
                1L
        ));
        AtomicInteger executorCalls = new AtomicInteger();
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    executorCalls.incrementAndGet();
                    return RequirementExecutionResult.success(
                            request.taskId(),
                            "不应执行",
                            "https://github.com/example/waimai/pull/12",
                            "{\"status\":\"SUCCESS\"}"
                    );
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore
        );

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, result.status());
        assertEquals(0, executorCalls.get());
        assertTrue(result.errorMessage().contains("验收标准"));
        assertTrue(result.resultJson().contains("\"status\":\"NEED_INFO\""));
        assertEquals(RdTaskStatus.WAITING_POLICY.name(), registry.timeline(task.taskId()).get(7).status());
        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN.name(), registry.timeline(task.taskId()).get(8).status());
        assertEquals(4, stageRunStore.listByTask(task.taskId()).size());
    }

    @Test
    void shouldRejectRequirementWhenQaAgentFailsAfterCodingStage() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "增加订单催单功能",
                "P1",
                "https://github.com/example/waimai.git",
                "example",
                "waimai",
                "main",
                "订单详情页可以催单",
                List.of("前端构建通过"),
                false
        ));
        materialStore.save(new TaskMaterial(
                "7820000000003",
                task.taskId(),
                TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT,
                "需求正文",
                "",
                "text/markdown",
                "sha256:test",
                "用户可以在订单详情页点击催单。",
                "",
                "",
                "",
                "{}",
                1L,
                1L
        ));
        List<RequirementExecutionRequest> captured = new CopyOnWriteArrayList<>();
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        RecordingAgentWorkflowAlertSink alertSink = new RecordingAgentWorkflowAlertSink();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    captured.add(request);
                    if (request.role() == AgentRole.QA_AGENT) {
                        return RequirementExecutionResult.failure(
                                request.taskId(),
                                "QA 未通过真实验收",
                                "{\"status\":\"FAILED\",\"summary\":\"QA failed\"}"
                        );
                    }
                    if (request.role() == AgentRole.REQUIREMENT_REVIEWER) {
                        return RequirementExecutionResult.success(
                                request.taskId(), "需求评审完成", "", successfulRoleResultWithoutProviderFallback(request.role())
                        );
                    }
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(),
                                "实现完成",
                                "",
                                "{\"status\":\"SUCCESS\"}"
                        );
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(),
                            request.role().name() + " 完成",
                            "",
                            "{\"status\":\"SUCCESS\"}"
                    );
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                alertSink,
                WorkflowExperienceStore.noop()
        );

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, result.status());
        assertEquals("", result.pullRequestUrl());
        assertTrue(result.errorMessage().contains("QA 未通过真实验收"));
        assertTrue(result.resultJson().contains("\"status\":\"NEEDS_HUMAN\""));
        assertEquals(List.of(
                AgentRole.REQUIREMENT_REVIEWER,
                AgentRole.SOLUTION_ARCHITECT,
                AgentRole.CODING_AGENT,
                AgentRole.QA_AGENT
        ), captured.stream().map(RequirementExecutionRequest::role).toList());
        assertEquals(AgentStageStatus.FAILED_NEEDS_HUMAN,
                stageRunStore.listByTask(task.taskId()).stream()
                        .filter(stage -> stage.role() == AgentRole.QA_AGENT)
                        .findFirst()
                        .orElseThrow()
                        .status());
        assertEquals(2, alertSink.alerts().size());
        AgentWorkflowAlert alert = alertSink.alerts().getFirst();
        assertEquals(AgentWorkflowAlertType.QA_FAILED, alert.type());
        assertEquals(task.taskId(), alert.taskId());
        assertEquals("QA_AGENT", alert.metadata().get("role"));
        assertTrue(alert.message().contains("QA 未通过真实验收"));
        assertEquals(AgentWorkflowAlertType.TASK_BLOCKED, alertSink.alerts().get(1).type());
    }

    @Test
    void shouldAutomaticallyReturnProductDefectQaFailureToCodingOnce() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "7820000000099",
                "修复订单催单按钮",
                "订单详情页可以成功催单",
                "催单按钮点击后应发送真实请求并展示成功状态。"
        );
        List<RequirementExecutionRequest> captured = new CopyOnWriteArrayList<>();
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        RecordingAgentWorkflowAlertSink alertSink = new RecordingAgentWorkflowAlertSink();
        RecordingRequirementPullRequestPublisher pullRequestPublisher = new RecordingRequirementPullRequestPublisher();
        AtomicInteger qaAttempts = new AtomicInteger();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    captured.add(request);
                    if (request.role() == AgentRole.REQUIREMENT_REVIEWER) {
                        return RequirementExecutionResult.success(
                                request.taskId(), "需求评审完成", "", successfulRoleResultWithoutProviderFallback(request.role()));
                    }
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(), "实现完成", "", codingResultJson(request.role()));
                    }
                    if (request.role() == AgentRole.QA_AGENT && qaAttempts.incrementAndGet() == 1) {
                        return RequirementExecutionResult.failure(
                                request.taskId(), "真实浏览器点击后接口返回 500", qaFailedResultJson("PRODUCT_DEFECT", "CODING_AGENT"));
                    }
                    if (request.role() == AgentRole.QA_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(), "修复后真实验收通过", "", qaPassedResultJson());
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(), request.role().name() + " 完成", "", "{\"status\":\"SUCCESS\"}");
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                alertSink,
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                pullRequestPublisher
        );

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        assertEquals(List.of(
                AgentRole.REQUIREMENT_REVIEWER,
                AgentRole.SOLUTION_ARCHITECT,
                AgentRole.CODING_AGENT,
                AgentRole.QA_AGENT,
                AgentRole.CODING_AGENT,
                AgentRole.QA_AGENT
        ), captured.stream().map(RequirementExecutionRequest::role).toList());
        List<AgentStageRun> codingAttempts = stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.CODING_AGENT)
                .toList();
        List<AgentStageRun> qaStageAttempts = stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.QA_AGENT)
                .toList();
        assertEquals(List.of(1, 2), codingAttempts.stream().map(AgentStageRun::attemptNo).toList());
        assertEquals(List.of(1, 2), qaStageAttempts.stream().map(AgentStageRun::attemptNo).toList());
        assertEquals(AgentStageStatus.FAILED_NEEDS_HUMAN, qaStageAttempts.getFirst().status());
        assertEquals(AgentStageStatus.SUCCEEDED, qaStageAttempts.getLast().status());
        RequirementExecutionRequest remediationCoding = captured.get(4);
        assertTrue(remediationCoding.upstreamResultJson().contains("qaRemediation"));
        assertTrue(remediationCoding.upstreamResultJson().contains("PRODUCT_DEFECT"));
        assertTrue(remediationCoding.upstreamResultJson().contains("qa-current-screenshot"));
        assertTrue(alertSink.alerts().stream()
                .anyMatch(alert -> alert.type() == AgentWorkflowAlertType.QA_REMEDIATION_STARTED));
    }

    @Test
    void shouldNotReturnEnvironmentQaFailureToCoding() {
        QaRemediationScenario scenario = runQaRemediationScenario("ENVIRONMENT", "HUMAN", false);

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, scenario.result().status());
        assertEquals(List.of(
                AgentRole.REQUIREMENT_REVIEWER,
                AgentRole.SOLUTION_ARCHITECT,
                AgentRole.CODING_AGENT,
                AgentRole.QA_AGENT
        ), scenario.captured().stream().map(RequirementExecutionRequest::role).toList());
        assertEquals(1, scenario.stageRunStore().listByTask(scenario.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.CODING_AGENT)
                .count());
        assertFalse(scenario.alertSink().alerts().stream()
                .anyMatch(alert -> alert.type() == AgentWorkflowAlertType.QA_REMEDIATION_STARTED));
    }

    @Test
    void shouldBoundProductDefectQaRemediationToOneCodingRetry() {
        QaRemediationScenario scenario = runQaRemediationScenario("PRODUCT_DEFECT", "CODING_AGENT", false);

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, scenario.result().status());
        assertEquals(List.of(
                AgentRole.REQUIREMENT_REVIEWER,
                AgentRole.SOLUTION_ARCHITECT,
                AgentRole.CODING_AGENT,
                AgentRole.QA_AGENT,
                AgentRole.CODING_AGENT,
                AgentRole.QA_AGENT
        ), scenario.captured().stream().map(RequirementExecutionRequest::role).toList());
        assertEquals(List.of(1, 2), scenario.stageRunStore().listByTask(scenario.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.CODING_AGENT)
                .map(AgentStageRun::attemptNo)
                .toList());
        assertEquals(List.of(1, 2), scenario.stageRunStore().listByTask(scenario.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.QA_AGENT)
                .map(AgentStageRun::attemptNo)
                .toList());
        assertEquals(1, scenario.alertSink().alerts().stream()
                .filter(alert -> alert.type() == AgentWorkflowAlertType.QA_REMEDIATION_STARTED)
                .count());
    }

    @Test
    void shouldSkipPullRequestPublicationForPatchOnlyDelivery() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "SWE-bench django__django-11019",
                "P1",
                "https://github.com/example/swebench.git",
                "example",
                "swebench",
                "main",
                "按需求材料产出可应用补丁并通过回归测试",
                List.of("补丁可 git apply", "不提交、不推送、不创建 PR"),
                false
        ));
        materialStore.save(new TaskMaterial(
                "7820000000030",
                task.taskId(),
                TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT,
                "需求正文",
                "",
                "text/markdown",
                "sha256:swebench-patch-only",
                "补丁即交付，不允许提交或创建 PR。",
                "",
                "",
                "",
                "{}",
                1L,
                1L
        ));
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        RecordingRequirementPullRequestPublisher pullRequestPublisher = new RecordingRequirementPullRequestPublisher();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(), "实现完成", "", codingResultJson(request.role()));
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(), request.role().name() + " 完成", "", roleResultJson(request.role()));
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                pullRequestPublisher
        );

        RequirementDeliveryResult result = engine.submit(task.taskId());

        // 验收标准明确禁止创建 PR（SWE-bench 补丁即交付）：跳过发布器，直接完成
        assertEquals(RdTaskStatus.COMPLETED, result.status());
        assertEquals("", result.pullRequestUrl());
        assertEquals(null, pullRequestPublisher.command());
        assertEquals(RdTaskStatus.COMPLETED, registry.getTask(task.taskId()).status());
        assertTrue(result.resultJson().contains("\"deliveryReview\""));
    }

    @Test
    void shouldPropagateEnvironmentNotesAndApplyLightweightModeForPatchOnlyDelivery() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "SWE-bench django__django-11019",
                "P1",
                "https://github.com/example/swebench.git",
                "example",
                "swebench",
                "main",
                "按需求材料产出可应用补丁并通过回归测试",
                List.of("补丁可 git apply", "不提交、不推送、不创建 PR"),
                false
        ));
        materialStore.save(new TaskMaterial(
                "7820000000031",
                task.taskId(),
                TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT,
                "需求正文",
                "",
                "text/markdown",
                "sha256:swebench-env-notes",
                "补丁即交付，不允许提交或创建 PR。",
                "",
                "",
                "",
                "{}",
                1L,
                1L
        ));
        String reviewerEnvironmentNote = "python3.13 已移除 cgi 模块，测试需用 stub 注入 PYTHONPATH";
        List<RequirementExecutionRequest> captured = new CopyOnWriteArrayList<>();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    captured.add(request);
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(), "实现完成", "", codingResultJson(request.role()));
                    }
                    String resultJson = roleResultJson(request.role());
                    if (request.role() == AgentRole.REQUIREMENT_REVIEWER) {
                        // 评审角色实测发现环境坑，按输出协议记录 environmentNotes
                        resultJson = resultJson.replaceFirst("\\{",
                                "{\n  \"environmentNotes\": [\"" + reviewerEnvironmentNote + "\"],");
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(), request.role().name() + " 完成", "", resultJson);
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                new InMemoryAgentStageRunStore(),
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                new RecordingRequirementPullRequestPublisher()
        );

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        assertEquals(4, captured.size());
        // P1: 输出协议要求各角色记录 environmentNotes
        assertTrue(captured.getFirst().prompt().contains("\"environmentNotes\""),
                "reviewer output contract must include environmentNotes");
        // P1: 上游实测环境备忘必须传导到所有下游角色 prompt，避免重复探测
        for (int i = 1; i < 4; i++) {
            assertTrue(captured.get(i).prompt().contains("环境备忘"),
                    "downstream prompt #" + i + " must carry environment notes section");
            assertTrue(captured.get(i).prompt().contains(reviewerEnvironmentNote),
                    "downstream prompt #" + i + " must carry reviewer environment note");
        }
        // P2: 补丁即交付任务对 REVIEWER/ARCHITECT 启用轻量模式，消除重复复现
        assertTrue(captured.getFirst().prompt().contains("轻量交付模式"),
                "reviewer prompt must enable lightweight mode for patch-only delivery");
        assertTrue(captured.get(1).prompt().contains("轻量交付模式"),
                "architect prompt must enable lightweight mode for patch-only delivery");
        assertTrue(captured.get(1).prompt().contains("禁止重新复现"),
                "architect must reuse reviewer reproduction instead of redoing it");
    }

    @Test
    void shouldBlockAgentStageWhenExecutorReturnsPullRequestUrlBeforeDeliveryReview() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "7820000000020",
                "增加订单催单功能",
                "订单详情页可以催单",
                "用户可以在订单详情页点击催单。"
        );
        List<RequirementExecutionRequest> captured = new CopyOnWriteArrayList<>();
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        RecordingAgentWorkflowAlertSink alertSink = new RecordingAgentWorkflowAlertSink();
        RecordingRequirementPullRequestPublisher pullRequestPublisher = new RecordingRequirementPullRequestPublisher();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    captured.add(request);
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(),
                                "执行器不应提前创建 PR",
                                "https://github.com/example/waimai/pull/99",
                                codingResultJson(request.role())
                        );
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(),
                            request.role().name() + " 完成",
                            "",
                            roleResultJson(request.role())
                    );
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                alertSink,
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                pullRequestPublisher
        );

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, result.status());
        assertEquals("", result.pullRequestUrl());
        assertTrue(result.errorMessage().contains("agent stage returned pullRequestUrl before delivery review"));
        assertEquals(List.of(
                AgentRole.REQUIREMENT_REVIEWER,
                AgentRole.SOLUTION_ARCHITECT,
                AgentRole.CODING_AGENT
        ), captured.stream().map(RequirementExecutionRequest::role).toList());
        assertEquals(null, pullRequestPublisher.command());
        AgentStageRun codingStage = stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.CODING_AGENT)
                .findFirst()
                .orElseThrow();
        assertEquals(AgentStageStatus.FAILED_NEEDS_HUMAN, codingStage.status());
        assertTrue(stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.QA_AGENT)
                .allMatch(stage -> stage.status() == AgentStageStatus.PENDING));
        List<AgentWorkflowAlert> policyAlerts = alertSink.alerts().stream()
                .filter(alert -> alert.type() == AgentWorkflowAlertType.STAGE_FAILED_NEEDS_HUMAN)
                .toList();
        assertEquals(1, policyAlerts.size());
        AgentWorkflowAlert alert = policyAlerts.getFirst();
        assertEquals(AgentWorkflowAlertType.STAGE_FAILED_NEEDS_HUMAN, alert.type());
        assertEquals("CODING_AGENT", alert.metadata().get("role"));
    }

    @Test
    void shouldStopAfterRequirementReviewerWhenRequirementNeedsHumanInput() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "7820000000005",
                "增加订单催单功能",
                "订单详情页可以催单",
                "用户可以在订单详情页点击催单，但没有说明失败提示和验收命令。"
        );
        List<RequirementExecutionRequest> captured = new CopyOnWriteArrayList<>();
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        RecordingAgentWorkflowAlertSink alertSink = new RecordingAgentWorkflowAlertSink();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    captured.add(request);
                    if (request.role() == AgentRole.REQUIREMENT_REVIEWER) {
                        return RequirementExecutionResult.success(
                                request.taskId(),
                                "需求缺少权限和验收命令，需要人工补充",
                                "",
                                """
                                        {
                                          "decision": "NEED_INFO",
                                          "feasibility": "NEED_INFO",
                                          "missingInformation": ["权限边界", "真实验收命令"],
                                          "risks": ["缺少失败提示会导致 QA 无法验收"],
                                          "acceptanceCoverage": [
                                            {"criteria":"前端构建通过","covered":false,"reason":"缺少命令"}
                                          ]
                                        }
                                        """
                        );
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(),
                            request.role().name() + " 不应执行",
                            "",
                            roleResultJson(request.role())
                    );
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                alertSink,
                WorkflowExperienceStore.noop()
        );

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, result.status());
        assertTrue(result.errorMessage().contains("需求缺少权限和验收命令"));
        assertTrue(result.resultJson().contains("\"status\":\"NEEDS_HUMAN\""));
        assertEquals(List.of(AgentRole.REQUIREMENT_REVIEWER),
                captured.stream().map(RequirementExecutionRequest::role).toList());
        AgentStageRun reviewerStage = stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.REQUIREMENT_REVIEWER)
                .findFirst()
                .orElseThrow();
        assertEquals(AgentStageStatus.FAILED_NEEDS_HUMAN, reviewerStage.status());
        assertTrue(stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() != AgentRole.REQUIREMENT_REVIEWER)
                .allMatch(stage -> stage.status() == AgentStageStatus.PENDING));
        assertEquals(2, alertSink.alerts().size());
        AgentWorkflowAlert alert = alertSink.alerts().getFirst();
        assertEquals(AgentWorkflowAlertType.STAGE_FAILED_NEEDS_HUMAN, alert.type());
        assertEquals("REQUIREMENT_REVIEWER", alert.metadata().get("role"));
        assertEquals(AgentWorkflowAlertType.TASK_BLOCKED, alertSink.alerts().get(1).type());
    }

    @Test
    void shouldCreateNewAgentStageAttemptWhenRejectedRequirementIsSubmittedAgain() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "7820000000021",
                "外卖订单预计送达超时提示",
                "订单详情接口返回超时提示字段",
                "用户查询订单详情时可以看到是否超过预计送达时间。"
        );
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        InMemoryRoleContextPackageStore contextStore = new InMemoryRoleContextPackageStore();
        AtomicInteger executorCalls = new AtomicInteger();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    int callNo = executorCalls.incrementAndGet();
                    if (callNo == 1) {
                        return RequirementExecutionResult.failure(
                                request.taskId(),
                                "provider long-cat is missing required auth environment variable(s): LONGCAT_API_KEY",
                                "{\"status\":\"FAILED\",\"errorMessage\":\"missing LONGCAT_API_KEY\"}"
                        );
                    }
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(),
                                "实现完成",
                                "",
                                codingResultJson(request.role())
                        );
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(),
                            request.role().name() + " 完成",
                            "",
                            roleResultJson(request.role())
                    );
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore,
                new RoleContextBuilder(),
                contextStore,
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                new RecordingRequirementPullRequestPublisher()
        );

        RequirementDeliveryResult first = engine.submit(task.taskId());
        String firstContextId = stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.REQUIREMENT_REVIEWER)
                .findFirst().orElseThrow().contextPackageId();
        materialStore.save(new TaskMaterial(
                "7820000000022", task.taskId(), TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT, "补充验收说明", "", "text/plain",
                "sha256:acceptance", "已补充验收命令", "", "", "", "{}", 2L, 2L));
        RequirementDeliveryResult second = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, first.status());
        assertTrue(first.errorMessage().contains("LONGCAT_API_KEY"));
        assertEquals(RdTaskStatus.COMPLETED, second.status());
        assertEquals("https://github.com/example/waimai/pull/12", second.pullRequestUrl());
        assertEquals(5, executorCalls.get());
        assertEquals(2, registry.timeline(task.taskId()).stream()
                .filter(event -> event.status().equals(RdTaskStatus.EXECUTING.name()))
                .count());
        List<AgentStageRun> reviewerStages = stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.REQUIREMENT_REVIEWER)
                .toList();
        assertEquals(2, reviewerStages.size());
        assertEquals(List.of(1, 2), reviewerStages.stream().map(AgentStageRun::attemptNo).toList());
        assertEquals(List.of(AgentStageStatus.FAILED_NEEDS_HUMAN, AgentStageStatus.SUCCEEDED),
                reviewerStages.stream().map(AgentStageRun::status).toList());
        assertEquals(firstContextId, reviewerStages.getFirst().contextPackageId());
        assertEquals(2, contextStore.findById(reviewerStages.getLast().contextPackageId())
                .orElseThrow().packageVersion());
        assertEquals(5, stageRunStore.listByTask(task.taskId()).size());
    }

    @Test
    void shouldPublishProviderFallbackAlertWhenAgentMetadataContainsFailedAttemptBeforeSuccess() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "7820000000006",
                "增加订单催单功能",
                "订单详情页可以催单",
                "用户可以在订单详情页点击催单。"
        );
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        RecordingAgentWorkflowAlertSink alertSink = new RecordingAgentWorkflowAlertSink();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(),
                                "实现完成",
                                "",
                                """
                                        {
                                          "status": "SUCCESS",
                                          "summary": "实现完成",
                                          "changedFiles": "src/main/java/com/example/OrderController.java",
                                          "testSummary": "./mvnw test passed",
                                          "prBody": "## Summary\\n- implement requirement",
                                          "dockerMetadata": {
                                            "provider": "claude-code-coding_agent",
                                            "providerAttemptsJson": "[{\\"provider\\":\\"deepseek\\",\\"status\\":\\"FAILED_VALIDATION\\"},{\\"provider\\":\\"claude-code-coding_agent\\",\\"status\\":\\"SUCCESS\\"}]"
                                          }
                                        }
                                        """
                        );
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(),
                            request.role().name() + " 完成",
                            "",
                            successfulRoleResultWithoutProviderFallback(request.role())
                    );
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                alertSink,
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                new RecordingRequirementPullRequestPublisher()
        );

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        List<AgentWorkflowAlert> providerFallbackAlerts = alertSink.alerts().stream()
                .filter(alert -> alert.type() == AgentWorkflowAlertType.PROVIDER_FALLBACK)
                .toList();
        assertEquals(1, providerFallbackAlerts.size());
        AgentWorkflowAlert alert = providerFallbackAlerts.getFirst();
        assertEquals("CODING_AGENT", alert.metadata().get("role"));
        assertEquals("deepseek", alert.metadata().get("failedProvider"));
        assertEquals("claude-code-coding_agent", alert.metadata().get("activeProvider"));
        assertEquals("FAILED_VALIDATION", alert.metadata().get("failedStatus"));
    }

    @Test
    void shouldNotPublishProviderFallbackAlertForSameProviderRetry() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "7820000000016",
                "增加订单催单功能",
                "订单详情页可以催单",
                "用户可以在订单详情页点击催单。"
        );
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        RecordingAgentWorkflowAlertSink alertSink = new RecordingAgentWorkflowAlertSink();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(),
                                "实现完成",
                                "",
                                """
                                        {
                                          "status": "SUCCESS",
                                          "summary": "实现完成",
                                          "changedFiles": "src/main/java/com/example/OrderController.java",
                                          "testSummary": "./mvnw test passed",
                                          "prBody": "## Summary\\n- implement requirement",
                                          "dockerMetadata": {
                                            "provider": "deepseek",
                                            "providerAttemptsJson": "[{\\"provider\\":\\"deepseek\\",\\"status\\":\\"FAILED_VALIDATION\\"},{\\"provider\\":\\"deepseek\\",\\"status\\":\\"SUCCESS\\"}]"
                                          }
                                        }
                                        """
                        );
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(),
                            request.role().name() + " 完成",
                            "",
                            successfulRoleResultWithoutProviderFallback(request.role())
                    );
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                alertSink,
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                new RecordingRequirementPullRequestPublisher()
        );

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        assertTrue(alertSink.alerts().stream()
                .noneMatch(alert -> alert.type() == AgentWorkflowAlertType.PROVIDER_FALLBACK));
    }

    @Test
    void shouldRejectRequirementWhenDeliveryReviewerRejectsFinalResult() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "增加订单催单功能",
                "P1",
                "https://github.com/example/waimai.git",
                "example",
                "waimai",
                "main",
                "订单详情页可以催单",
                List.of("前端构建通过"),
                false
        ));
        materialStore.save(new TaskMaterial(
                "7820000000004",
                task.taskId(),
                TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT,
                "需求正文",
                "",
                "text/markdown",
                "sha256:test",
                "用户可以在订单详情页点击催单。",
                "",
                "",
                "",
                "{}",
                1L,
                1L
        ));
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        RecordingAgentWorkflowAlertSink alertSink = new RecordingAgentWorkflowAlertSink();
        InMemoryWorkflowExperienceStore experienceStore = new InMemoryWorkflowExperienceStore();
        RequirementDeliveryReviewer rejectingReviewer = new RequirementDeliveryReviewer() {

            @Override
            public RequirementDeliveryReviewResult review(
                    String taskId,
                    String deliveryResultJson
            ) {
                return RequirementDeliveryReviewResult.rejected(taskId, "missing delivery review artifact");
            }
        };
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(),
                                "实现完成",
                                "",
                                codingResultJson(request.role())
                        );
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(),
                            request.role().name() + " 完成",
                            "",
                            roleResultJson(request.role())
                    );
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                alertSink,
                experienceStore,
                rejectingReviewer
        );

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.REJECTED, result.status());
        assertEquals("", result.pullRequestUrl());
        assertTrue(result.errorMessage().contains("missing delivery review artifact"));
        assertTrue(result.resultJson().contains("\"approved\":false"));
        assertTrue(stageRunStore.listByTask(task.taskId()).stream()
                .allMatch(stage -> stage.status() == AgentStageStatus.SUCCEEDED));
        assertEquals(4, alertSink.alerts().stream()
                .filter(alert -> alert.type() == AgentWorkflowAlertType.PROVIDER_FALLBACK)
                .count());
        List<AgentWorkflowAlert> deliveryReviewAlerts = alertSink.alerts().stream()
                .filter(alert -> alert.type() == AgentWorkflowAlertType.DELIVERY_REVIEW_FAILED)
                .toList();
        assertEquals(1, deliveryReviewAlerts.size());
        AgentWorkflowAlert alert = deliveryReviewAlerts.getFirst();
        assertEquals(AgentWorkflowAlertType.DELIVERY_REVIEW_FAILED, alert.type());
        assertEquals("DELIVERY_REVIEWER", alert.metadata().get("role"));
        assertEquals("missing delivery review artifact", alert.metadata().get("reason"));
        WorkflowExperienceEntry failureReport = experienceStore.listByTask(task.taskId()).getLast();
        assertEquals(WorkflowExperienceType.DELIVERY_REPORT, failureReport.experienceType());
        assertTrue(failureReport.failure());
    }

    @Test
    void shouldMarkTaskFailedNeedsHumanWhenCodingAgentStageNeedsHuman() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "7820000000030",
                "增加订单催单功能",
                "订单详情页可以催单",
                "用户可以在订单详情页点击催单。"
        );
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        RecordingAgentWorkflowAlertSink alertSink = new RecordingAgentWorkflowAlertSink();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.failure(
                                request.taskId(),
                                "coding sandbox timeout",
                                "{\"status\":\"FAILED\",\"summary\":\"sandbox timeout\"}"
                        );
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(),
                            request.role().name() + " 完成",
                            "",
                            roleResultJson(request.role())
                    );
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                alertSink,
                WorkflowExperienceStore.noop()
        );

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, result.status());
        assertTrue(result.resultJson().contains("\"status\":\"NEEDS_HUMAN\""));
        assertTrue(result.errorMessage().contains("coding sandbox timeout"));
        AgentStageRun codingStage = stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.CODING_AGENT)
                .findFirst()
                .orElseThrow();
        assertEquals(AgentStageStatus.FAILED_NEEDS_HUMAN, codingStage.status());
        assertEquals(AgentWorkflowAlertType.TASK_BLOCKED, alertSink.alerts().getLast().type());
    }

    @Test
    void shouldEnterWaitingApprovalFromRecoveringWhenPolicyRequiresApproval() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "7820000000031",
                "增加订单催单功能",
                "支付成功后订单详情页可以催单",
                "用户可以在订单详情页点击催单。"
        );
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.failure(
                                request.taskId(),
                                "coding needs human",
                                "{\"status\":\"FAILED\"}"
                        );
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(),
                            request.role().name() + " 完成",
                            "",
                            roleResultJson(request.role())
                    );
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop()
        );

        // 任务主体含“支付”：首次提交被策略门禁拦入审批。
        RequirementDeliveryResult first = engine.submit(task.taskId());
        assertEquals(RdTaskStatus.WAITING_APPROVAL, first.status());

        // 人工审批后放行执行，编码阶段失败进入可恢复状态。
        registry.approveRequirementTask(task.taskId(), "支付风险已人工确认");
        RequirementDeliveryResult approved = engine.submit(task.taskId());
        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, approved.status());

        RequirementDeliveryResult second = assertDoesNotThrow(() -> engine.submit(task.taskId()));

        assertEquals(RdTaskStatus.WAITING_APPROVAL, second.status());
        assertTrue(registry.timeline(task.taskId()).stream()
                .anyMatch(event -> RdTaskStatus.RECOVERING.name().equals(event.status())));
        assertTrue(registry.timeline(task.taskId()).stream()
                .anyMatch(event -> RdTaskStatus.WAITING_APPROVAL.name().equals(event.status())));
    }

    @Test
    void shouldNotCreateNewAttemptForCancelledOrSkippedStagesOnRetry() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "7820000000033",
                "增加订单催单功能",
                "订单详情页可以催单",
                "用户可以在订单详情页点击催单，但没有说明失败提示和验收命令。"
        );
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        AtomicInteger executorCalls = new AtomicInteger();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    executorCalls.incrementAndGet();
                    if (request.role() == AgentRole.REQUIREMENT_REVIEWER && executorCalls.get() == 1) {
                        return RequirementExecutionResult.success(
                                request.taskId(),
                                "需求缺少权限和验收命令，需要人工补充",
                                "",
                                """
                                        {
                                          "decision": "NEED_INFO",
                                          "feasibility": "NEED_INFO",
                                          "missingInformation": ["权限边界", "真实验收命令"],
                                          "risks": ["缺少失败提示会导致 QA 无法验收"],
                                          "acceptanceCoverage": [
                                            {"criteria":"前端构建通过","covered":false,"reason":"缺少命令"}
                                          ]
                                        }
                                        """
                        );
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(),
                            request.role().name() + " 完成",
                            "",
                            roleResultJson(request.role())
                    );
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                new RecordingRequirementPullRequestPublisher()
        );

        RequirementDeliveryResult first = engine.submit(task.taskId());
        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, first.status());

        AgentStageRun architect = stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.SOLUTION_ARCHITECT)
                .findFirst()
                .orElseThrow();
        AgentStageRun coding = stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.CODING_AGENT)
                .findFirst()
                .orElseThrow();
        stageRunStore.transition(architect.stageRunId(), AgentStageStatus.SKIPPED, "", "", 3L);
        stageRunStore.transition(coding.stageRunId(), AgentStageStatus.CANCELLED, "", "", 4L);

        materialStore.save(new TaskMaterial(
                "7820000000034",
                task.taskId(),
                TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT,
                "补充验收说明",
                "",
                "text/plain",
                "sha256:acceptance-retry",
                "已补充验收命令 ./mvnw test",
                "",
                "",
                "",
                "{}",
                2L,
                2L
        ));
        engine.submit(task.taskId());

        assertEquals(2, stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.REQUIREMENT_REVIEWER)
                .count());
        assertEquals(1, stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.SOLUTION_ARCHITECT)
                .count());
        assertEquals(1, stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.CODING_AGENT)
                .count());
        assertEquals(AgentStageStatus.SKIPPED, stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.SOLUTION_ARCHITECT)
                .findFirst()
                .orElseThrow()
                .status());
        assertEquals(AgentStageStatus.CANCELLED, stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.CODING_AGENT)
                .findFirst()
                .orElseThrow()
                .status());
    }

    @Test
    void shouldRecoverSettledWorkspaceWithoutCreatingFreshAttempt() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "settled-workspace-recovery-material",
                "恢复已结算工作区",
                "中断后应补采集而不是新建 attempt",
                "模拟 Bridge 已 RESULT_SUBMITTED 且 AGENT_SETTLED 的恢复场景。"
        );
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        InMemoryAgentStageArtifactStore artifacts = new InMemoryAgentStageArtifactStore();
        stages.save(AgentStageRun.pending("reviewer-1", task.taskId(), AgentRole.REQUIREMENT_REVIEWER, 1,
                task.taskId() + ":REQUIREMENT_REVIEWER:1", 10L)
                .withStatus(AgentStageStatus.SUCCEEDED, "", "", 20L));
        stages.save(AgentStageRun.pending("architect-1", task.taskId(), AgentRole.SOLUTION_ARCHITECT, 1,
                task.taskId() + ":SOLUTION_ARCHITECT:1", 10L)
                .withStatus(AgentStageStatus.RUNNING, "", "", 40L));
        stages.save(AgentStageRun.pending("coding-1", task.taskId(), AgentRole.CODING_AGENT, 1,
                task.taskId() + ":CODING_AGENT:1", 10L));
        stages.save(AgentStageRun.pending("qa-1", task.taskId(), AgentRole.QA_AGENT, 1,
                task.taskId() + ":QA_AGENT:1", 10L));
        registry.markRequirementFailedRetryable(task.taskId(), "worker interrupted", "{\"failurePhase\":\"AGENT_ROLE\"}");

        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> RequirementExecutionResult.success(
                        request.taskId(), request.role().name() + " recovered", "",
                        request.role() == AgentRole.CODING_AGENT
                                ? codingResultJson(request.role())
                                : roleResultJson(request.role())
                ),
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stages,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                new RecordingRequirementPullRequestPublisher()
        );
        engine.setInterruptedStageRecoveryService(new InterruptedStageRecoveryService(
                stage -> java.util.Optional.of(new RecoveredWorkspaceExecution(
                        true,
                        roleResultJson(AgentRole.SOLUTION_ARCHITECT),
                        "",
                        "",
                        "pi-opencode",
                        "[]"
                )),
                stages,
                artifacts,
                generator()::nextIdString
        ));

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        List<AgentStageRun> architectAttempts = stages.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.SOLUTION_ARCHITECT)
                .toList();
        assertEquals(List.of(1), architectAttempts.stream().map(AgentStageRun::attemptNo).toList());
        assertEquals(AgentStageStatus.SUCCEEDED, architectAttempts.getFirst().status());
        assertFalse(architectAttempts.getFirst().resultArtifactId().isBlank());
        assertTrue(artifacts.listByTask(task.taskId()).stream()
                .anyMatch(artifact -> artifact.stageRunId().equals("architect-1")
                        && "RESULT_JSON".equals(artifact.artifactType())));
    }

    @Test
    void shouldReplaceInterruptedRunningStageWithFreshAttemptDuringRetryableResume() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                "interrupted-recovery-material",
                "恢复中断的编码阶段",
                "恢复时必须重新创建编码尝试",
                "模拟进程中断后的角色阶段恢复。"
        );
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        stages.save(AgentStageRun.pending("reviewer-1", task.taskId(), AgentRole.REQUIREMENT_REVIEWER, 1,
                task.taskId() + ":REQUIREMENT_REVIEWER:1", 10L)
                .withStatus(AgentStageStatus.SUCCEEDED, "", "", 20L));
        stages.save(AgentStageRun.pending("architect-1", task.taskId(), AgentRole.SOLUTION_ARCHITECT, 1,
                task.taskId() + ":SOLUTION_ARCHITECT:1", 10L)
                .withStatus(AgentStageStatus.SUCCEEDED, "", "", 20L));
        stages.save(AgentStageRun.pending("coding-1", task.taskId(), AgentRole.CODING_AGENT, 1,
                task.taskId() + ":CODING_AGENT:1", 10L)
                .withStatus(AgentStageStatus.FAILED_NEEDS_HUMAN, "AGENT_RESULT_REJECTED", "previous failure", 20L));
        stages.save(AgentStageRun.pending("coding-2", task.taskId(), AgentRole.CODING_AGENT, 2,
                task.taskId() + ":CODING_AGENT:2", 30L)
                .withStatus(AgentStageStatus.RUNNING, "", "", 40L));
        stages.save(AgentStageRun.pending("qa-1", task.taskId(), AgentRole.QA_AGENT, 1,
                task.taskId() + ":QA_AGENT:1", 10L));
        registry.markRequirementFailedRetryable(task.taskId(), "worker interrupted", "{\"failurePhase\":\"AGENT_ROLE\"}");

        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> RequirementExecutionResult.success(
                        request.taskId(), request.role().name() + " recovered", "",
                        request.role() == AgentRole.CODING_AGENT
                                ? codingResultJson(request.role())
                                : roleResultJson(request.role())
                ),
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stages,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                new RecordingRequirementPullRequestPublisher()
        );

        RequirementDeliveryResult result = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.COMPLETED, result.status());
        List<AgentStageRun> codingAttempts = stages.listByTask(task.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.CODING_AGENT)
                .toList();
        assertEquals(List.of(1, 2, 3), codingAttempts.stream().map(AgentStageRun::attemptNo).toList());
        assertEquals(AgentStageStatus.FAILED_RETRYABLE, codingAttempts.get(1).status());
        assertEquals("ORCHESTRATION_INTERRUPTED", codingAttempts.get(1).errorCategory());
        assertEquals(AgentStageStatus.SUCCEEDED, codingAttempts.get(2).status());
    }

    private SnowflakeIdGenerator generator() {
        AtomicLong now = new AtomicLong(1_783_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }

    private RdRequirementTask createRequirementTask(
            RagStreamTaskRegistry registry,
            InMemoryTaskMaterialStore materialStore,
            String materialId,
            String title,
            String expectedResult,
            String materialPreview
    ) {
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                title,
                "P1",
                "https://github.com/example/waimai.git",
                "example",
                "waimai",
                "main",
                expectedResult,
                List.of("前端构建通过"),
                false
        ));
        materialStore.save(new TaskMaterial(
                materialId,
                task.taskId(),
                TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT,
                "需求正文",
                "",
                "text/markdown",
                "sha256:" + materialId,
                materialPreview,
                "",
                "",
                "",
                "{}",
                1L,
                1L
        ));
        return task;
    }

    private String roleResultJson(AgentRole role) {
        String providerName = "claude-code-" + role.name().toLowerCase();
        if (role == AgentRole.REQUIREMENT_REVIEWER) {
            return """
                    {
                      "decision": "APPROVED",
                      "feasibility": "CAN_DO",
                      "missingInformation": [],
                      "risks": [],
                      "acceptanceCoverage": ["前端构建通过"],
                      "budgetEstimate": {
                        "initialTokens": 80000,
                        "retryReserveTokens": 20000,
                        "estimatedTotalTokens": 100000,
                        "confidence": "LOW",
                        "basis": "没有历史样本，基于四角色完整交付范围由模型判断",
                        "historicalSamples": []
                      },
                      "dockerMetadata": {
                        "provider": "%s",
                        "providerAttemptsJson": "[{\\"provider\\":\\"deepseek\\",\\"status\\":\\"FAILED_VALIDATION\\"},{\\"provider\\":\\"%s\\",\\"status\\":\\"SUCCESS\\"}]"
                      }
                    }
                    """.formatted(providerName, providerName);
        }
        if (role == AgentRole.QA_AGENT) {
            return """
                    {
                      "status": "PASSED",
                      "summary": "当前需求与回归验收通过",
                      "failureCategory": "NONE",
                      "retryRecommendation": "NONE",
                      "browserValidation": {
                        "required": false,
                        "performed": false,
                        "decisionSource": "NOT_APPLICABLE",
                        "baseUrl": "",
                        "browser": "chromium",
                        "viewports": []
                      },
                      "acceptanceResults": [
                        {
                          "criteria": "前端构建通过",
                          "scope": "CURRENT",
                          "command": "./mvnw test",
                          "status": "PASSED",
                          "exitCode": 0,
                          "durationMillis": 120,
                          "logArtifactId": "qa-current-log",
                          "evidenceArtifactIds": ["qa-current-log"]
                        },
                        {
                          "criteria": "既有功能回归通过",
                          "scope": "REGRESSION",
                          "command": "./mvnw test",
                          "status": "PASSED",
                          "exitCode": 0,
                          "durationMillis": 120,
                          "logArtifactId": "qa-regression-log",
                          "evidenceArtifactIds": ["qa-regression-log"]
                        }
                      ],
                      "evidenceManifestArtifactId": "qa-evidence-manifest",
                      "dockerMetadata": {
                        "provider": "%s",
                        "providerAttemptsJson": "[{\\"provider\\":\\"deepseek\\",\\"status\\":\\"FAILED_VALIDATION\\"},{\\"provider\\":\\"%s\\",\\"status\\":\\"SUCCESS\\"}]"
                      }
                    }
                    """.formatted(providerName, providerName);
        }
        return """
                {
                  "status": "SUCCESS",
                  "dockerMetadata": {
                    "provider": "%s",
                    "providerAttemptsJson": "[{\\"provider\\":\\"deepseek\\",\\"status\\":\\"FAILED_VALIDATION\\"},{\\"provider\\":\\"%s\\",\\"status\\":\\"SUCCESS\\"}]"
                  }
                }
                """.formatted(providerName, providerName);
    }

    private String largeReviewerResultWithHandoff() {
        String largeRaw = "x".repeat(20_500);
        return """
                {
                  "decision": "APPROVED",
                  "feasibility": "CAN_DO",
                  "missingInformation": [],
                  "risks": [],
                  "acceptanceCoverage": ["前端构建通过"],
                  "budgetEstimate": {
                    "initialTokens": 80,
                    "retryReserveTokens": 20,
                    "estimatedTotalTokens": 100,
                    "confidence": "LOW",
                    "basis": "恢复交接链路测试",
                    "historicalSamples": []
                  },
                  "largeRaw": "%s",
                  "stageArtifacts": [
                    {
                      "type": "HANDOFF_MARKDOWN",
                      "name": "handoff/next.md",
                      "uri": "s3://rd-role-handoffs/review-recovery.md",
                      "summary": "受控需求评审交接",
                      "contentPreview": "# Downstream Handoff",
                      "metadataJson": {
                        "artifactName": "handoff/next.md",
                        "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "bytes": "321"
                      }
                    }
                  ],
                  "roleHandoff": {
                    "sourceRole": "REQUIREMENT_REVIEWER",
                    "targetRole": "SOLUTION_ARCHITECT",
                    "artifactName": "handoff/next.md",
                    "artifactUri": "s3://rd-role-handoffs/review-recovery.md",
                    "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "bytes": 321,
                    "summary": "受控需求评审交接"
                  }
                }
                """.formatted(largeRaw);
    }

    private String largeCodingResultWithCandidatePatch() {
        String largeRaw = "x".repeat(20_500);
        return """
                {
                  "status": "SUCCESS",
                  "summary": "实现完成",
                  "largeRaw": "%s",
                  "stageArtifacts": [
                    {
                      "type": "PATCH_DIFF",
                      "name": "patch.diff",
                      "uri": "s3://rd-role-handoffs/private-candidate.patch",
                      "summary": "Private candidate patch",
                      "contentPreview": "PRIVATE_PATCH_CONTENT_MUST_NOT_REACH_QA_PROMPT",
                      "metadataJson": {
                        "candidatePatch": "true",
                        "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        "bytes": "321"
                      }
                    }
                  ]
                }
                """.formatted(largeRaw);
    }

    private String largeCodingResultWithDeliveryEvidence() {
        return """
                {
                  "status": "SUCCESS",
                  "summary": "实现完成",
                  "prBody": "## Summary\\n- implement requirement",
                  "changedFiles": "src/main/java/com/example/OrderController.java",
                  "largeRaw": "%s"
                }
                """.formatted("x".repeat(20_500));
    }

    private String largeCodingResultWithoutDeliveryEvidence() {
        return """
                {
                  "status": "SUCCESS",
                  "largeRaw": "%s",
                  "prBody": "## Summary\\n- implement requirement",
                  "changedFiles": "src/main/java/com/example/OrderController.java"
                }
                """.formatted("x".repeat(20_500));
    }

    private String codingResultJson(AgentRole role) {
        String providerName = "claude-code-" + role.name().toLowerCase();
        return """
                {
                  "status": "SUCCESS",
                  "summary": "实现完成",
                  "changedFiles": "src/main/java/com/example/OrderController.java",
                  "testSummary": "./mvnw test passed",
                  "prBody": "## Summary\\n- implement requirement",
                  "dockerMetadata": {
                    "provider": "%s",
                    "providerAttemptsJson": "[{\\"provider\\":\\"deepseek\\",\\"status\\":\\"FAILED_VALIDATION\\"},{\\"provider\\":\\"%s\\",\\"status\\":\\"SUCCESS\\"}]"
                  }
                }
                """.formatted(providerName, providerName);
    }

    private String codingResultWithCandidatePatchJson() {
        return """
                {
                  "status": "SUCCESS",
                  "summary": "实现完成",
                  "changedFiles": "src/main/java/com/example/OrderController.java",
                  "testSummary": "./mvnw test passed",
                  "prBody": "## Summary\\n- implement requirement",
                  "stageArtifacts": [
                    {
                      "type": "PATCH_DIFF",
                      "name": "patch.diff",
                      "uri": "s3://rd-role-handoffs/private-candidate.patch",
                      "summary": "Private candidate patch",
                      "contentPreview": "PRIVATE_PATCH_CONTENT_MUST_NOT_REACH_QA_PROMPT",
                      "metadataJson": {
                        "candidatePatch": "true",
                        "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        "bytes": "321"
                      }
                    }
                  ]
                }
                """;
    }

    private String qaPassedResultJson() {
        return """
                {
                  "status": "PASSED",
                  "summary": "当前需求与回归验收均通过",
                  "failureCategory": "NONE",
                  "retryRecommendation": "NONE",
                  "browserValidation": {
                    "required": true,
                    "performed": true,
                    "decisionSource": "AUTO_DETECTION",
                    "browser": "chromium",
                    "baseUrl": "http://127.0.0.1:5173",
                    "viewports": ["1440x900", "390x844"]
                  },
                  "evidenceManifestArtifactId": "qa-evidence-manifest",
                  "acceptanceResults": [
                    {
                      "criteria": "订单详情页可以成功催单",
                      "scope": "CURRENT",
                      "command": "playwright-cli screenshot --filename=/work/output/qa-evidence/current.png",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 1200,
                      "logArtifactId": "qa-current-log",
                      "evidenceArtifactIds": ["qa-current-screenshot", "qa-current-trace"]
                    },
                    {
                      "criteria": "已有订单详情能力保持正常",
                      "scope": "REGRESSION",
                      "command": "npm test -- --runInBand",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 900,
                      "logArtifactId": "qa-regression-log",
                      "evidenceArtifactIds": ["qa-regression-report"]
                    }
                  ]
                }
                """;
    }

    private String qaFailedResultJson(String failureCategory, String retryRecommendation) {
        return """
                {
                  "status": "FAILED",
                  "summary": "真实浏览器验证发现当前需求缺陷",
                  "failureCategory": "%s",
                  "retryRecommendation": "%s",
                  "browserValidation": {
                    "required": true,
                    "performed": true,
                    "decisionSource": "AUTO_DETECTION",
                    "browser": "chromium",
                    "baseUrl": "http://127.0.0.1:5173",
                    "viewports": ["1440x900", "390x844"]
                  },
                  "evidenceManifestArtifactId": "qa-evidence-manifest-failed",
                  "acceptanceResults": [
                    {
                      "criteria": "订单详情页可以成功催单",
                      "scope": "CURRENT",
                      "command": "playwright-cli screenshot --filename=/work/output/qa-evidence/current-failed.png",
                      "status": "FAILED",
                      "exitCode": 1,
                      "durationMillis": 1300,
                      "logArtifactId": "qa-current-failed-log",
                      "evidenceArtifactIds": ["qa-current-screenshot", "qa-current-trace", "qa-current-network"]
                    },
                    {
                      "criteria": "已有订单详情能力保持正常",
                      "scope": "REGRESSION",
                      "command": "npm test -- --runInBand",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 900,
                      "logArtifactId": "qa-regression-log",
                      "evidenceArtifactIds": ["qa-regression-report"]
                    }
                  ]
                }
                """.formatted(failureCategory, retryRecommendation);
    }

    private QaRemediationScenario runQaRemediationScenario(
            String failureCategory,
            String retryRecommendation,
            boolean secondQaPasses
    ) {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        String taskId = "7820000000100";
        RdRequirementTask task = createRequirementTask(
                registry,
                materialStore,
                taskId,
                "修复订单催单按钮",
                "订单详情页可以成功催单",
                "催单按钮点击后应发送真实请求并展示成功状态。"
        );
        List<RequirementExecutionRequest> captured = new CopyOnWriteArrayList<>();
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        RecordingAgentWorkflowAlertSink alertSink = new RecordingAgentWorkflowAlertSink();
        AtomicInteger qaAttempts = new AtomicInteger();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                request -> {
                    captured.add(request);
                    if (request.role() == AgentRole.REQUIREMENT_REVIEWER) {
                        return RequirementExecutionResult.success(
                                request.taskId(), "需求评审完成", "", successfulRoleResultWithoutProviderFallback(request.role()));
                    }
                    if (request.role() == AgentRole.CODING_AGENT) {
                        return RequirementExecutionResult.success(
                                request.taskId(), "实现完成", "", codingResultJson(request.role()));
                    }
                    if (request.role() == AgentRole.QA_AGENT) {
                        int attempt = qaAttempts.incrementAndGet();
                        if (attempt == 1 || !secondQaPasses) {
                            return RequirementExecutionResult.failure(
                                    request.taskId(),
                                    "QA 真实验证失败",
                                    qaFailedResultJson(failureCategory, retryRecommendation)
                            );
                        }
                        return RequirementExecutionResult.success(
                                request.taskId(), "修复后真实验收通过", "", qaPassedResultJson());
                    }
                    return RequirementExecutionResult.success(
                            request.taskId(), request.role().name() + " 完成", "", "{\"status\":\"SUCCESS\"}");
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(new AtomicStageIdSupplier()),
                stageRunStore,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                alertSink,
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                new RecordingRequirementPullRequestPublisher()
        );
        RequirementDeliveryResult result = engine.submit(task.taskId());
        return new QaRemediationScenario(
                task.taskId(), result, List.copyOf(captured), stageRunStore, alertSink);
    }

    private String successfulRoleResultWithoutProviderFallback(AgentRole role) {
        if (role == AgentRole.REQUIREMENT_REVIEWER) {
            return """
                    {
                      "decision": "APPROVED",
                      "feasibility": "CAN_DO",
                      "missingInformation": [],
                      "risks": [],
                      "acceptanceCoverage": ["前端构建通过"],
                      "budgetEstimate": {
                        "initialTokens": 80000,
                        "retryReserveTokens": 20000,
                        "estimatedTotalTokens": 100000,
                        "confidence": "LOW",
                        "basis": "没有历史样本，基于四角色完整交付范围由模型判断",
                        "historicalSamples": []
                      },
                      "dockerMetadata": {
                        "provider": "claude-code-requirement_reviewer",
                        "providerAttemptsJson": "[{\\"provider\\":\\"claude-code-requirement_reviewer\\",\\"status\\":\\"SUCCESS\\"}]"
                      }
                    }
                    """;
        }
        if (role == AgentRole.QA_AGENT) {
            return """
                    {
                      "status": "PASSED",
                      "summary": "当前需求与回归验收通过",
                      "failureCategory": "NONE",
                      "retryRecommendation": "NONE",
                      "browserValidation": {
                        "required": false,
                        "performed": false,
                        "decisionSource": "NOT_APPLICABLE",
                        "baseUrl": "",
                        "browser": "chromium",
                        "viewports": []
                      },
                      "acceptanceResults": [
                        {
                          "criteria": "前端构建通过",
                          "scope": "CURRENT",
                          "command": "./mvnw test",
                          "status": "PASSED",
                          "exitCode": 0,
                          "durationMillis": 120,
                          "logArtifactId": "qa-current-log",
                          "evidenceArtifactIds": ["qa-current-log"]
                        },
                        {
                          "criteria": "既有功能回归通过",
                          "scope": "REGRESSION",
                          "command": "./mvnw test",
                          "status": "PASSED",
                          "exitCode": 0,
                          "durationMillis": 120,
                          "logArtifactId": "qa-regression-log",
                          "evidenceArtifactIds": ["qa-regression-log"]
                        }
                      ],
                      "evidenceManifestArtifactId": "qa-evidence-manifest",
                      "dockerMetadata": {
                        "provider": "claude-code-qa_agent",
                        "providerAttemptsJson": "[{\\"provider\\":\\"claude-code-qa_agent\\",\\"status\\":\\"SUCCESS\\"}]"
                      }
                    }
                    """;
        }
        return """
                {
                  "status": "SUCCESS",
                  "dockerMetadata": {
                    "provider": "claude-code-%s",
                    "providerAttemptsJson": "[{\\"provider\\":\\"claude-code-%s\\",\\"status\\":\\"SUCCESS\\"}]"
                  }
                }
                """.formatted(role.name().toLowerCase(), role.name().toLowerCase());
    }

    private static final class AtomicStageIdSupplier implements java.util.function.Supplier<String> {

        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public String get() {
            return "stage-" + sequence.incrementAndGet();
        }
    }

    private static final class RecordingAgentWorkflowAlertSink implements AgentWorkflowAlertSinkPort {

        private final List<AgentWorkflowAlert> alerts = new ArrayList<>();

        @Override
        public void publish(AgentWorkflowAlert alert) {
            alerts.add(alert);
        }

        private List<AgentWorkflowAlert> alerts() {
            return List.copyOf(alerts);
        }
    }

    private static final class RecordingRequirementPullRequestPublisher implements RequirementPullRequestPublisherPort {

        private RequirementPullRequestPublishCommand command;

        @Override
        public RequirementPullRequestPublication publish(RequirementPullRequestPublishCommand command) {
            this.command = command;
            return RequirementPullRequestPublication.success(
                    command.taskId(),
                    "https://github.com/example/waimai/pull/12",
                    "12",
                    "{\"provider\":\"recording\"}"
            );
        }

        private RequirementPullRequestPublishCommand command() {
            return command;
        }
    }

    private static final class OrderRecordingPullRequestPublisher implements RequirementPullRequestPublisherPort {

        private final List<String> order;
        private boolean invoked;

        private OrderRecordingPullRequestPublisher(List<String> order) {
            this.order = order;
        }

        @Override
        public RequirementPullRequestPublication publish(RequirementPullRequestPublishCommand command) {
            invoked = true;
            if (order != null) {
                order.add("pull-request");
            }
            return RequirementPullRequestPublication.success(
                    command.taskId(),
                    "https://github.com/example/waimai/pull/99",
                    "99",
                    "{\"provider\":\"recording\"}"
            );
        }

        private boolean invoked() {
            return invoked;
        }
    }

    private static final class RecordingRequirementBranchPublisher implements RequirementBranchPublisherPort {

        private final List<String> order;
        private final boolean fail;
        private final String failureMessage;
        private RequirementBranchPublishCommand command;

        private RecordingRequirementBranchPublisher(List<String> order, boolean fail, String failureMessage) {
            this.order = order;
            this.fail = fail;
            this.failureMessage = failureMessage;
        }

        @Override
        public RequirementBranchPublication publishBranch(RequirementBranchPublishCommand command) {
            this.command = command;
            if (order != null) {
                order.add("branch");
            }
            if (fail) {
                return RequirementBranchPublication.failure(command.taskId(), failureMessage);
            }
            return RequirementBranchPublication.success(
                    command.taskId(), "abc123", "{\"provider\":\"recording-branch\"}");
        }

        private RequirementBranchPublishCommand command() {
            return command;
        }
    }

    private record QaRemediationScenario(
            String taskId,
            RequirementDeliveryResult result,
            List<RequirementExecutionRequest> captured,
            AgentStageRunStore stageRunStore,
            RecordingAgentWorkflowAlertSink alertSink
    ) {
    }

    private static final class InMemoryWorkflowExperienceStore implements WorkflowExperienceStore {

        private final List<WorkflowExperienceEntry> entries = new ArrayList<>();

        @Override
        public WorkflowExperienceEntry save(WorkflowExperienceEntry entry) {
            entries.add(entry);
            return entry;
        }

        @Override
        public List<WorkflowExperienceEntry> listByTask(String taskId) {
            return entries.stream()
                    .filter(entry -> entry.taskId().equals(taskId))
                    .toList();
        }

        @Override
        public List<WorkflowExperienceEntry> searchReusable(String query, String excludeTaskId, int limit) {
            return entries.stream()
                    .filter(WorkflowExperienceEntry::reusable)
                    .filter(entry -> !entry.failure())
                    .filter(entry -> entry.redacted())
                    .filter(entry -> !entry.taskId().equals(excludeTaskId))
                    .limit(Math.max(limit, 0))
                    .toList();
        }
    }
}
