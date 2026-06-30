package com.wish.rd.engine.requirement;

import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.RdRequirementTask;
import com.wish.rd.rag.runtime.RdTask;
import com.wish.rd.rag.runtime.TaskMaterial;
import com.wish.rd.rag.runtime.TaskMaterialStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 需求交付编排引擎。
 *
 * <p>负责把已创建的 REQUIREMENT 任务转换成执行器输入，推进任务状态，并记录 PR 结果。
 */
@Service
public class RequirementDeliveryEngine {

    private final RagStreamTaskRegistry taskRegistry;
    private final TaskMaterialStore materialStore;
    private final RequirementExecutorPort executor;
    private final RequirementContextBuilder contextBuilder;
    private final RequirementPlanGenerator planGenerator;
    private final RuleBasedRequirementPolicyGate policyGate;

    @Autowired
    public RequirementDeliveryEngine(
            RagStreamTaskRegistry taskRegistry,
            TaskMaterialStore materialStore,
            ObjectProvider<RequirementExecutorPort> executorProvider
    ) {
        this(taskRegistry, materialStore, executorProvider.getIfAvailable(RequirementExecutorPort::unavailable));
    }

    public RequirementDeliveryEngine(
            RagStreamTaskRegistry taskRegistry,
            TaskMaterialStore materialStore,
            RequirementExecutorPort executor
    ) {
        this(
                taskRegistry,
                materialStore,
                executor,
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate()
        );
    }

    public RequirementDeliveryEngine(
            RagStreamTaskRegistry taskRegistry,
            TaskMaterialStore materialStore,
            RequirementExecutorPort executor,
            RequirementContextBuilder contextBuilder,
            RequirementPlanGenerator planGenerator,
            RuleBasedRequirementPolicyGate policyGate
    ) {
        this.taskRegistry = taskRegistry == null ? RagStreamTaskRegistry.inMemory() : taskRegistry;
        this.materialStore = materialStore;
        this.executor = executor == null ? RequirementExecutorPort.unavailable() : executor;
        this.contextBuilder = contextBuilder == null ? new RequirementContextBuilder() : contextBuilder;
        this.planGenerator = planGenerator == null ? new RequirementPlanGenerator() : planGenerator;
        this.policyGate = policyGate == null ? new RuleBasedRequirementPolicyGate() : policyGate;
    }

    /**
     * 提交需求任务并同步执行。
     *
     * @param taskId 任务 ID
     * @return 编排结果
     */
    public RequirementDeliveryResult submit(String taskId) {
        RdTask task = taskRegistry.getTask(taskId);
        if (!(task instanceof RdRequirementTask requirementTask)) {
            throw new IllegalArgumentException("task is not a requirement task: " + taskId);
        }
        List<TaskMaterial> materials = materialStore.listByTask(requirementTask.taskId());
        if (materials.isEmpty()) {
            throw new IllegalStateException("requirement materials must not be empty: " + taskId);
        }
        if (requirementTask.status().name().equals("CREATED")) {
            requirementTask = taskRegistry.markRequirementMaterialCollecting(requirementTask.taskId(), "收集需求材料");
        }
        if (requirementTask.status().name().equals("MATERIAL_COLLECTING")) {
            requirementTask = taskRegistry.markRequirementMaterialReady(requirementTask.taskId(), "需求材料就绪");
        }
        RequirementContextPackage context = contextBuilder.build(requirementTask, materials);
        if (requirementTask.status().name().equals("MATERIAL_READY")) {
            requirementTask = taskRegistry.markRequirementContextBuilding(requirementTask.taskId(), "构建需求上下文");
        }
        if (requirementTask.status().name().equals("CONTEXT_BUILDING")) {
            requirementTask = taskRegistry.markRequirementContextReady(requirementTask.taskId(), context.toJson());
        }
        RequirementPlan plan = planGenerator.generate(requirementTask, context);
        if (requirementTask.status().name().equals("CONTEXT_READY")) {
            requirementTask = taskRegistry.markRequirementPlanGenerating(requirementTask.taskId(), "生成需求实现计划");
        }
        if (requirementTask.status().name().equals("PLAN_GENERATING")) {
            requirementTask = taskRegistry.markRequirementPlanGenerated(requirementTask.taskId(), plan.toJson());
        }
        RequirementPolicyDecision policyDecision = policyGate.decide(requirementTask, context, plan, materials);
        if (requirementTask.status().name().equals("PLAN_GENERATED")) {
            requirementTask = taskRegistry.markRequirementWaitingPolicy(requirementTask.taskId(), policyDecision.toJson());
        }
        if (!policyDecision.allowed()) {
            if (policyDecision.waitingApproval()) {
                RdRequirementTask waitingApproval = taskRegistry.markRequirementWaitingApproval(
                        requirementTask.taskId(),
                        policyDecision.toJson()
                );
                return new RequirementDeliveryResult(
                        waitingApproval.taskId(),
                        waitingApproval.status(),
                        "",
                        waitingApproval.executionResultJson(),
                        waitingApproval.errorMessage()
                );
            }
            String resultJson = policyBlockedResult(policyDecision);
            RdRequirementTask failed = taskRegistry.markRequirementFailedNeedsHuman(
                    requirementTask.taskId(),
                    policyDecision.reason(),
                    resultJson
            );
            return new RequirementDeliveryResult(
                    failed.taskId(),
                    failed.status(),
                    "",
                    failed.executionResultJson(),
                    failed.errorMessage()
            );
        }
        String prompt = buildPrompt(requirementTask, materials, context, plan, policyDecision);
        requirementTask = taskRegistry.markRequirementExecuting(requirementTask.taskId(), prompt);
        RequirementExecutionResult executionResult = normalizeResult(
                requirementTask.taskId(),
                executor.execute(new RequirementExecutionRequest(requirementTask.taskId(), requirementTask, materials, prompt))
        );
        if (!executionResult.success() || executionResult.pullRequestUrl().isBlank()) {
            String reason = executionResult.errorMessage().isBlank()
                    ? "需求执行失败: 未生成 PR"
                    : executionResult.errorMessage();
            RdRequirementTask rejected = taskRegistry.markRequirementRejected(
                    requirementTask.taskId(),
                    reason,
                    executionResult.resultJson()
            );
            return new RequirementDeliveryResult(
                    rejected.taskId(),
                    rejected.status(),
                    "",
                    rejected.executionResultJson(),
                    rejected.errorMessage()
            );
        }
        RdRequirementTask committed = taskRegistry.markRequirementCommitted(
                requirementTask.taskId(),
                executionResult.pullRequestUrl(),
                executionResult.resultJson()
        );
        return new RequirementDeliveryResult(
                committed.taskId(),
                committed.status(),
                committed.pullRequestUrl(),
                committed.executionResultJson(),
                committed.errorMessage()
        );
    }

    private RequirementExecutionResult normalizeResult(String taskId, RequirementExecutionResult result) {
        if (result != null) {
            return result;
        }
        return RequirementExecutionResult.failure(
                taskId,
                "requirement executor returned null",
                "{\"status\":\"FAILED\",\"errorMessage\":\"requirement executor returned null\"}"
        );
    }

    private String policyBlockedResult(RequirementPolicyDecision policyDecision) {
        String status = "UNSAFE".equals(policyDecision.action()) ? "UNSAFE" : "NEED_INFO";
        return """
                {"status":"%s","riskLevel":%s,"errorMessage":%s,"policyDecision":%s}
                """.formatted(
                status,
                RequirementContextPackage.json(policyDecision.riskLevel()),
                RequirementContextPackage.json(policyDecision.reason()),
                policyDecision.toJson()
        ).strip();
    }

    private String buildPrompt(
            RdRequirementTask task,
            List<TaskMaterial> materials,
            RequirementContextPackage context,
            RequirementPlan plan,
            RequirementPolicyDecision policyDecision
    ) {
        return """
                你是 RD-Bot 的需求交付执行器。请在受控仓库中完成需求编码、测试，并准备可审查 PR。

                # 任务
                - taskId: %s
                - title: %s
                - priority: %s

                # 仓库
                - repositoryUrl: %s
                - repoOwner: %s
                - repoName: %s
                - baseBranch: %s

                # 预期结果
                %s

                # 验收标准
                %s

                # 需求摘要
                %s

                # 实现计划
                %s

                # 策略决策
                - policyAction: %s
                - riskLevel: %s
                - reason: %s

                # 建议验证命令
                %s

                # 需求材料
                %s

                # 输出要求
                - 修改代码后运行必要的测试或构建命令。
                - 返回结构化 JSON，status 使用 SUCCESS/FAILED/NEED_INFO/UNSAFE。
                - 成功时提供 summary、changedFiles、testSummary 和 prBody。
                """.formatted(
                task.taskId(),
                task.title(),
                task.priority(),
                task.repositoryUrl(),
                task.repoOwner(),
                task.repoName(),
                task.baseBranch(),
                task.expectedResult(),
                task.acceptanceCriteriaJson(),
                context.requirementSummary(),
                plan.implementationSteps(),
                policyDecision.action(),
                policyDecision.riskLevel(),
                policyDecision.reason(),
                context.suggestedValidationCommands(),
                materialPrompt(materials)
        ).strip();
    }

    private String materialPrompt(List<TaskMaterial> materials) {
        return materials.stream()
                .map(material -> """
                        ## %s
                        - sourceType: %s
                        - sourceUri: %s
                        - contentHash: %s

                        %s
                        """.formatted(
                        material.title(),
                        material.sourceType().name(),
                        material.sourceUri(),
                        material.contentHash(),
                        material.contentPreview()
                ).strip())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }
}
