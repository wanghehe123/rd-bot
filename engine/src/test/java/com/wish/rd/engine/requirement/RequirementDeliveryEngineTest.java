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
import com.wish.rd.engine.agent.model.WorkflowExperienceEntry;
import com.wish.rd.engine.agent.WorkflowExperienceStore;
import com.wish.rd.engine.agent.model.WorkflowExperienceType;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore;
import com.wish.rd.rag.context.RoleContextBuilder;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.engine.requirement.model.RequirementDeliveryResult;
import com.wish.rd.engine.requirement.model.RequirementDeliveryReviewResult;
import com.wish.rd.engine.requirement.model.RequirementExecutionRequest;
import com.wish.rd.engine.requirement.model.RequirementExecutionResult;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublication;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublishCommand;

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
        assertTrue(captured.getFirst().prompt().contains("# 上游阶段结果"));
        assertTrue(captured.getFirst().prompt().contains("# 需求摘要"));
        assertTrue(captured.getFirst().prompt().contains("# 实现计划"));
        assertTrue(captured.getFirst().prompt().contains("# 策略决策"));
        assertTrue(captured.getFirst().prompt().contains("policyAction: ALLOWED"));
        assertTrue(captured.get(3).prompt().contains("\"acceptanceResults\""));
        assertTrue(captured.get(3).prompt().contains("真实执行命令"));
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
                                              "contentPreview": "diff --git a/src/main/java/com/example/OrderController.java b/src/main/java/com/example/OrderController.java"
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
                new InMemoryRoleContextPackageStore(),
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
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                new RecordingRequirementPullRequestPublisher()
        );

        RequirementDeliveryResult first = engine.submit(task.taskId());
        RequirementDeliveryResult second = engine.submit(task.taskId());

        assertEquals(RdTaskStatus.REJECTED, first.status());
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
        if (role == AgentRole.QA_AGENT) {
            return """
                    {
                      "status": "PASSED",
                      "summary": "QA 验收通过",
                      "acceptanceResults": [
                        {
                          "criteria": "前端构建通过",
                          "command": "./mvnw test",
                          "status": "PASSED",
                          "logArtifactId": "artifact-qa-log"
                        }
                      ],
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

    private String successfulRoleResultWithoutProviderFallback(AgentRole role) {
        if (role == AgentRole.QA_AGENT) {
            return """
                    {
                      "status": "PASSED",
                      "summary": "QA 验收通过",
                      "acceptanceResults": [
                        {
                          "criteria": "前端构建通过",
                          "command": "./mvnw test",
                          "status": "PASSED",
                          "logArtifactId": "artifact-qa-log"
                        }
                      ],
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
