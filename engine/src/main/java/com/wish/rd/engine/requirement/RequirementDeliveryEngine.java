package com.wish.rd.engine.requirement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.model.AgentRole;
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
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.context.model.RoleContextPackage;
import com.wish.rd.rag.context.RoleContextPackageStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.TaskMaterialStore;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.wish.rd.engine.requirement.model.RequirementContextPackage;
import com.wish.rd.engine.requirement.model.RequirementDeliveryResult;
import com.wish.rd.engine.requirement.model.RequirementDeliveryReviewResult;
import com.wish.rd.engine.requirement.model.RequirementExecutionRequest;
import com.wish.rd.engine.requirement.model.RequirementExecutionResult;
import com.wish.rd.engine.requirement.model.RequirementPlan;
import com.wish.rd.engine.requirement.model.RequirementPolicyDecision;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublication;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublishCommand;

/**
 * 需求交付编排引擎。
 *
 * <p>负责把已创建的 REQUIREMENT 任务转换成执行器输入，推进任务状态，并记录 PR 结果。
 */
@Service
public class RequirementDeliveryEngine {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Comparator<AgentStageRun> STAGE_RUN_RECENCY = Comparator
            .comparingInt(AgentStageRun::attemptNo)
            .thenComparingLong(AgentStageRun::createTimeEpochMillis)
            .thenComparing(AgentStageRun::stageRunId);

    private final RagStreamTaskRegistry taskRegistry;
    private final TaskMaterialStore materialStore;
    private final RequirementExecutorPort executor;
    private final RequirementContextBuilder contextBuilder;
    private final RequirementPlanGenerator planGenerator;
    private final RuleBasedRequirementPolicyGate policyGate;
    private final AgentStagePlanner stagePlanner;
    private final AgentStageRunStore stageRunStore;
    private final AgentStageArtifactStore artifactStore;
    private final RoleContextBuilder roleContextBuilder;
    private final RoleContextPackageStore roleContextPackageStore;
    private final AgentWorkflowAlertSinkPort alertSink;
    private final WorkflowExperienceStore experienceStore;
    private final RequirementDeliveryReviewer deliveryReviewer;
    private final RequirementPullRequestPublisherPort pullRequestPublisher;
    private final SnowflakeIdGenerator idGenerator;

    public RequirementDeliveryEngine(
            RagStreamTaskRegistry taskRegistry,
            TaskMaterialStore materialStore,
            ObjectProvider<RequirementExecutorPort> executorProvider
    ) {
        this(
                taskRegistry,
                materialStore,
                executorProvider,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                SnowflakeIdGenerator.defaultGenerator()
        );
    }

    public RequirementDeliveryEngine(
            RagStreamTaskRegistry taskRegistry,
            TaskMaterialStore materialStore,
            ObjectProvider<RequirementExecutorPort> executorProvider,
            ObjectProvider<AgentStageRunStore> stageRunStoreProvider,
            ObjectProvider<RoleContextPackageStore> roleContextPackageStoreProvider,
            SnowflakeIdGenerator idGenerator
    ) {
        this(
                taskRegistry,
                materialStore,
                executorProvider,
                stageRunStoreProvider,
                roleContextPackageStoreProvider,
                null,
                null,
                null,
                null,
                null,
                idGenerator
        );
    }

    @Autowired
    public RequirementDeliveryEngine(
            RagStreamTaskRegistry taskRegistry,
            TaskMaterialStore materialStore,
            ObjectProvider<RequirementExecutorPort> executorProvider,
            ObjectProvider<AgentStageRunStore> stageRunStoreProvider,
            ObjectProvider<RoleContextPackageStore> roleContextPackageStoreProvider,
            ObjectProvider<AgentStageArtifactStore> artifactStoreProvider,
            ObjectProvider<AgentWorkflowAlertSinkPort> alertSinkProvider,
            ObjectProvider<WorkflowExperienceStore> experienceStoreProvider,
            ObjectProvider<RequirementDeliveryReviewer> deliveryReviewerProvider,
            ObjectProvider<RequirementPullRequestPublisherPort> pullRequestPublisherProvider,
            SnowflakeIdGenerator idGenerator
    ) {
        this(
                taskRegistry,
                materialStore,
                executorProvider == null
                        ? RequirementExecutorPort.unavailable()
                        : executorProvider.getIfAvailable(RequirementExecutorPort::unavailable),
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(safeIdGenerator(idGenerator)::nextIdString),
                stageRunStoreProvider == null
                        ? new InMemoryAgentStageRunStore()
                        : stageRunStoreProvider.getIfAvailable(InMemoryAgentStageRunStore::new),
                artifactStoreProvider == null
                        ? new InMemoryAgentStageArtifactStore()
                        : artifactStoreProvider.getIfAvailable(InMemoryAgentStageArtifactStore::new),
                new RoleContextBuilder(),
                roleContextPackageStoreProvider == null
                        ? new InMemoryRoleContextPackageStore()
                        : roleContextPackageStoreProvider.getIfAvailable(InMemoryRoleContextPackageStore::new),
                alertSinkProvider == null
                        ? AgentWorkflowAlertSinkPort.noop()
                        : alertSinkProvider.getIfAvailable(AgentWorkflowAlertSinkPort::noop),
                experienceStoreProvider == null
                        ? WorkflowExperienceStore.noop()
                        : experienceStoreProvider.getIfAvailable(WorkflowExperienceStore::noop),
                deliveryReviewerProvider == null
                        ? new RequirementDeliveryReviewer()
                        : deliveryReviewerProvider.getIfAvailable(RequirementDeliveryReviewer::new),
                pullRequestPublisherProvider == null
                        ? RequirementPullRequestPublisherPort.unavailable()
                        : pullRequestPublisherProvider.getIfAvailable(RequirementPullRequestPublisherPort::unavailable),
                safeIdGenerator(idGenerator)
        );
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
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(SnowflakeIdGenerator.defaultGenerator()::nextIdString),
                new InMemoryAgentStageRunStore(),
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop()
        );
    }

    public RequirementDeliveryEngine(
            RagStreamTaskRegistry taskRegistry,
            TaskMaterialStore materialStore,
            RequirementExecutorPort executor,
            RequirementPullRequestPublisherPort pullRequestPublisher
    ) {
        this(
                taskRegistry,
                materialStore,
                executor,
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(SnowflakeIdGenerator.defaultGenerator()::nextIdString),
                new InMemoryAgentStageRunStore(),
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                pullRequestPublisher
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
        this(
                taskRegistry,
                materialStore,
                executor,
                contextBuilder,
                planGenerator,
                policyGate,
                new AgentStagePlanner(SnowflakeIdGenerator.defaultGenerator()::nextIdString),
                new InMemoryAgentStageRunStore(),
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop()
        );
    }

    public RequirementDeliveryEngine(
            RagStreamTaskRegistry taskRegistry,
            TaskMaterialStore materialStore,
            RequirementExecutorPort executor,
            RequirementContextBuilder contextBuilder,
            RequirementPlanGenerator planGenerator,
            RuleBasedRequirementPolicyGate policyGate,
            AgentStagePlanner stagePlanner,
            AgentStageRunStore stageRunStore
    ) {
        this(
                taskRegistry,
                materialStore,
                executor,
                contextBuilder,
                planGenerator,
                policyGate,
                stagePlanner,
                stageRunStore,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop()
        );
    }

    public RequirementDeliveryEngine(
            RagStreamTaskRegistry taskRegistry,
            TaskMaterialStore materialStore,
            RequirementExecutorPort executor,
            RequirementContextBuilder contextBuilder,
            RequirementPlanGenerator planGenerator,
            RuleBasedRequirementPolicyGate policyGate,
            AgentStagePlanner stagePlanner,
            AgentStageRunStore stageRunStore,
            RoleContextBuilder roleContextBuilder,
            RoleContextPackageStore roleContextPackageStore
    ) {
        this(
                taskRegistry,
                materialStore,
                executor,
                contextBuilder,
                planGenerator,
                policyGate,
                stagePlanner,
                stageRunStore,
                roleContextBuilder,
                roleContextPackageStore,
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop()
        );
    }

    public RequirementDeliveryEngine(
            RagStreamTaskRegistry taskRegistry,
            TaskMaterialStore materialStore,
            RequirementExecutorPort executor,
            RequirementContextBuilder contextBuilder,
            RequirementPlanGenerator planGenerator,
            RuleBasedRequirementPolicyGate policyGate,
            AgentStagePlanner stagePlanner,
            AgentStageRunStore stageRunStore,
            AgentStageArtifactStore artifactStore,
            RoleContextBuilder roleContextBuilder,
            RoleContextPackageStore roleContextPackageStore,
            AgentWorkflowAlertSinkPort alertSink,
            WorkflowExperienceStore experienceStore,
            RequirementDeliveryReviewer deliveryReviewer,
            RequirementPullRequestPublisherPort pullRequestPublisher
    ) {
        this(
                taskRegistry,
                materialStore,
                executor,
                contextBuilder,
                planGenerator,
                policyGate,
                stagePlanner,
                stageRunStore,
                artifactStore,
                roleContextBuilder,
                roleContextPackageStore,
                alertSink,
                experienceStore,
                deliveryReviewer,
                pullRequestPublisher,
                SnowflakeIdGenerator.defaultGenerator()
        );
    }

    public RequirementDeliveryEngine(
            RagStreamTaskRegistry taskRegistry,
            TaskMaterialStore materialStore,
            RequirementExecutorPort executor,
            RequirementContextBuilder contextBuilder,
            RequirementPlanGenerator planGenerator,
            RuleBasedRequirementPolicyGate policyGate,
            AgentStagePlanner stagePlanner,
            AgentStageRunStore stageRunStore,
            RoleContextBuilder roleContextBuilder,
            RoleContextPackageStore roleContextPackageStore,
            AgentWorkflowAlertSinkPort alertSink,
            WorkflowExperienceStore experienceStore
    ) {
        this(
                taskRegistry,
                materialStore,
                executor,
                contextBuilder,
                planGenerator,
                policyGate,
                stagePlanner,
                stageRunStore,
                roleContextBuilder,
                roleContextPackageStore,
                alertSink,
                experienceStore,
                new RequirementDeliveryReviewer(),
                RequirementPullRequestPublisherPort.unavailable(),
                SnowflakeIdGenerator.defaultGenerator()
        );
    }

    public RequirementDeliveryEngine(
            RagStreamTaskRegistry taskRegistry,
            TaskMaterialStore materialStore,
            RequirementExecutorPort executor,
            RequirementContextBuilder contextBuilder,
            RequirementPlanGenerator planGenerator,
            RuleBasedRequirementPolicyGate policyGate,
            AgentStagePlanner stagePlanner,
            AgentStageRunStore stageRunStore,
            RoleContextBuilder roleContextBuilder,
            RoleContextPackageStore roleContextPackageStore,
            AgentWorkflowAlertSinkPort alertSink,
            WorkflowExperienceStore experienceStore,
            RequirementDeliveryReviewer deliveryReviewer
    ) {
        this(
                taskRegistry,
                materialStore,
                executor,
                contextBuilder,
                planGenerator,
                policyGate,
                stagePlanner,
                stageRunStore,
                roleContextBuilder,
                roleContextPackageStore,
                alertSink,
                experienceStore,
                deliveryReviewer,
                RequirementPullRequestPublisherPort.unavailable(),
                SnowflakeIdGenerator.defaultGenerator()
        );
    }

    public RequirementDeliveryEngine(
            RagStreamTaskRegistry taskRegistry,
            TaskMaterialStore materialStore,
            RequirementExecutorPort executor,
            RequirementContextBuilder contextBuilder,
            RequirementPlanGenerator planGenerator,
            RuleBasedRequirementPolicyGate policyGate,
            AgentStagePlanner stagePlanner,
            AgentStageRunStore stageRunStore,
            RoleContextBuilder roleContextBuilder,
            RoleContextPackageStore roleContextPackageStore,
            AgentWorkflowAlertSinkPort alertSink,
            WorkflowExperienceStore experienceStore,
            RequirementDeliveryReviewer deliveryReviewer,
            RequirementPullRequestPublisherPort pullRequestPublisher
    ) {
        this(
                taskRegistry,
                materialStore,
                executor,
                contextBuilder,
                planGenerator,
                policyGate,
                stagePlanner,
                stageRunStore,
                roleContextBuilder,
                roleContextPackageStore,
                alertSink,
                experienceStore,
                deliveryReviewer,
                pullRequestPublisher,
                SnowflakeIdGenerator.defaultGenerator()
        );
    }

    private RequirementDeliveryEngine(
            RagStreamTaskRegistry taskRegistry,
            TaskMaterialStore materialStore,
            RequirementExecutorPort executor,
            RequirementContextBuilder contextBuilder,
            RequirementPlanGenerator planGenerator,
            RuleBasedRequirementPolicyGate policyGate,
            AgentStagePlanner stagePlanner,
            AgentStageRunStore stageRunStore,
            RoleContextBuilder roleContextBuilder,
            RoleContextPackageStore roleContextPackageStore,
            AgentWorkflowAlertSinkPort alertSink,
            WorkflowExperienceStore experienceStore,
            RequirementDeliveryReviewer deliveryReviewer,
            RequirementPullRequestPublisherPort pullRequestPublisher,
            SnowflakeIdGenerator idGenerator
    ) {
        this(
                taskRegistry,
                materialStore,
                executor,
                contextBuilder,
                planGenerator,
                policyGate,
                stagePlanner,
                stageRunStore,
                new InMemoryAgentStageArtifactStore(),
                roleContextBuilder,
                roleContextPackageStore,
                alertSink,
                experienceStore,
                deliveryReviewer,
                pullRequestPublisher,
                idGenerator
        );
    }

    private RequirementDeliveryEngine(
            RagStreamTaskRegistry taskRegistry,
            TaskMaterialStore materialStore,
            RequirementExecutorPort executor,
            RequirementContextBuilder contextBuilder,
            RequirementPlanGenerator planGenerator,
            RuleBasedRequirementPolicyGate policyGate,
            AgentStagePlanner stagePlanner,
            AgentStageRunStore stageRunStore,
            AgentStageArtifactStore artifactStore,
            RoleContextBuilder roleContextBuilder,
            RoleContextPackageStore roleContextPackageStore,
            AgentWorkflowAlertSinkPort alertSink,
            WorkflowExperienceStore experienceStore,
            RequirementDeliveryReviewer deliveryReviewer,
            RequirementPullRequestPublisherPort pullRequestPublisher,
            SnowflakeIdGenerator idGenerator
    ) {
        this.taskRegistry = taskRegistry == null ? RagStreamTaskRegistry.inMemory() : taskRegistry;
        this.materialStore = materialStore;
        this.executor = executor == null ? RequirementExecutorPort.unavailable() : executor;
        this.contextBuilder = contextBuilder == null ? new RequirementContextBuilder() : contextBuilder;
        this.planGenerator = planGenerator == null ? new RequirementPlanGenerator() : planGenerator;
        this.policyGate = policyGate == null ? new RuleBasedRequirementPolicyGate() : policyGate;
        this.stagePlanner = stagePlanner == null
                ? new AgentStagePlanner(SnowflakeIdGenerator.defaultGenerator()::nextIdString)
                : stagePlanner;
        this.stageRunStore = stageRunStore == null ? new InMemoryAgentStageRunStore() : stageRunStore;
        this.artifactStore = artifactStore == null ? new InMemoryAgentStageArtifactStore() : artifactStore;
        this.roleContextBuilder = roleContextBuilder == null ? new RoleContextBuilder() : roleContextBuilder;
        this.roleContextPackageStore = roleContextPackageStore == null
                ? new InMemoryRoleContextPackageStore()
                : roleContextPackageStore;
        this.alertSink = alertSink == null ? AgentWorkflowAlertSinkPort.noop() : alertSink;
        this.experienceStore = experienceStore == null ? WorkflowExperienceStore.noop() : experienceStore;
        this.deliveryReviewer = deliveryReviewer == null ? new RequirementDeliveryReviewer() : deliveryReviewer;
        this.pullRequestPublisher = pullRequestPublisher == null
                ? RequirementPullRequestPublisherPort.unavailable()
                : pullRequestPublisher;
        this.idGenerator = safeIdGenerator(idGenerator);
    }

    /**
     * 提交需求任务并同步执行。
     *
     * @param taskId 任务 ID
     * @return 编排结果
     */
    public RequirementDeliveryResult submit(String taskId) {
        // 关键链路入口：只允许需求任务进入交付编排，避免其他任务类型误入。
        RdTask task = taskRegistry.getTask(taskId);
        if (!(task instanceof RdRequirementTask requirementTask)) {
            throw new IllegalArgumentException("task is not a requirement task: " + taskId);
        }
        if (isNonRetryableTerminalRequirementStatus(requirementTask.status())) {
            return currentResult(requirementTask);
        }
        // 关键前置：任务必须有材料，否则 context 与 plan 生成无法闭环，直接拒绝执行。
        List<TaskMaterial> materials = materialStore.listByTask(requirementTask.taskId());
        if (materials.isEmpty()) {
            throw new IllegalStateException("requirement materials must not be empty: " + taskId);
        }
        // 先补齐阶段运行记录和角色上下文（幂等创建），确保每次提交都能有完整审计闭环。
        ensureRequirementStages(requirementTask);
        ensureRoleContexts(requirementTask, materials);

        // 状态机推进（可重入）主链路：
        // 主状态链：
        // CREATED -> MATERIAL_COLLECTING -> MATERIAL_READY -> CONTEXT_BUILDING -> CONTEXT_READY
        // -> PLAN_GENERATING -> PLAN_GENERATED -> WAITING_POLICY -> EXECUTING -> VALIDATING
        // -> PR_CREATING -> COMMITTED -> REPORTING -> COMPLETED
        // 失败分支：
        // WAITING_POLICY -> WAITING_APPROVAL、WAITING_POLICY -> FAILED_NEEDS_HUMAN
        // EXECUTING -> FAILED_NEEDS_HUMAN、EXECUTING -> REJECTED
        // VALIDATING -> REJECTED
        // PR_CREATING -> REJECTED
        if (requirementTask.status().name().equals("CREATED")) {
            // CREATED -> MATERIAL_COLLECTING：材料采集阶段开始。
            requirementTask = taskRegistry.markRequirementMaterialCollecting(requirementTask.taskId(), "收集需求材料");
        }
        if (requirementTask.status().name().equals("MATERIAL_COLLECTING")) {
            // MATERIAL_COLLECTING -> MATERIAL_READY：材料已就位，允许进入上下文构建。
            requirementTask = taskRegistry.markRequirementMaterialReady(requirementTask.taskId(), "需求材料就绪");
        }

        // 无论是否刚刚经过前置状态，都先构建一次上下文供后续阶段复用。
        RequirementContextPackage context = contextBuilder.build(requirementTask, materials);
        if (requirementTask.status().name().equals("MATERIAL_READY")) {
            // MATERIAL_READY -> CONTEXT_BUILDING：开始构建角色无关的需求上下文。
            requirementTask = taskRegistry.markRequirementContextBuilding(requirementTask.taskId(), "构建需求上下文");
        }
        if (requirementTask.status().name().equals("CONTEXT_BUILDING")) {
            // CONTEXT_BUILDING -> CONTEXT_READY：上下文 JSON 持久化。
            requirementTask = taskRegistry.markRequirementContextReady(requirementTask.taskId(), context.toJson());
        }
        // PLAN_GENERATING 与 PLAN_GENERATED 两段。
        RequirementPlan plan = planGenerator.generate(requirementTask, context);
        if (requirementTask.status().name().equals("CONTEXT_READY")) {
            // CONTEXT_READY -> PLAN_GENERATING：开始生成实现方案。
            requirementTask = taskRegistry.markRequirementPlanGenerating(requirementTask.taskId(), "生成需求实现计划");
        }
        if (requirementTask.status().name().equals("PLAN_GENERATING")) {
            // PLAN_GENERATING -> PLAN_GENERATED：将方案 JSON 落库，供后续策略和执行复用。
            requirementTask = taskRegistry.markRequirementPlanGenerated(requirementTask.taskId(), plan.toJson());
        }

        // PLAN_GENERATED -> WAITING_POLICY：策略门控执行，决定继续执行、等待审批或人工失败。
        RequirementPolicyDecision policyDecision = policyGate.decide(requirementTask, context, plan, materials);
        if (requirementTask.status().name().equals("PLAN_GENERATED")) {
            // WAITING_POLICY：写入策略决策快照，保证可追溯与可重试。
            requirementTask = taskRegistry.markRequirementWaitingPolicy(requirementTask.taskId(), policyDecision.toJson());
        }
        if (!policyDecision.allowed()) {
            if (policyDecision.waitingApproval()) {
                // WAITING_POLICY -> WAITING_APPROVAL：策略要求外部审批，任务在此暂停。
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
            // WAITING_POLICY -> FAILED_NEEDS_HUMAN：策略直接拒绝并要求人工干预，记录阻断上下文。
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
        // WAITING_POLICY -> EXECUTING：策略放行后，构建 prompt 并进入执行态。
        String prompt = buildPrompt(requirementTask, materials, context, plan, policyDecision);
        requirementTask = taskRegistry.markRequirementExecuting(requirementTask.taskId(), prompt);
        RequirementExecutionResult executionResult = executeAgentStages(
                requirementTask,
                materials,
                context,
                plan,
                policyDecision
        );
        if (!executionResult.success()) {
            String reason = executionResult.errorMessage().isBlank()
                    ? "需求执行失败"
                    : executionResult.errorMessage();
            if (needsHumanInterventionResult(executionResult)) {
                // EXECUTING -> FAILED_NEEDS_HUMAN：执行返回 NEEDS_HUMAN 等状态，保留结果等待人工恢复。
                RdRequirementTask failed = taskRegistry.markRequirementFailedNeedsHuman(
                        requirementTask.taskId(),
                        reason,
                        executionResult.resultJson()
                );
                return new RequirementDeliveryResult(
                        failed.taskId(),
                        failed.status(),
                        "",
                        failed.executionResultJson(),
                        failed.errorMessage()
                );
            }
            // EXECUTING -> REJECTED：执行失败且不可恢复，直接结束流程。
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
        // EXECUTING -> VALIDATING：执行完成后进入结果复核阶段，开始复核前把执行产物写入验证态。
        requirementTask = taskRegistry.markRequirementValidating(requirementTask.taskId(), executionResult.resultJson());
        RequirementDeliveryReviewResult reviewResult = deliveryReviewer.review(
                requirementTask.taskId(),
                executionResult.resultJson()
        );
        if (!reviewResult.approved()) {
            // VALIDATING -> REJECTED：复核不通过则直接阻断，保留复核快照用于复盘。
            publishDeliveryReviewAlert(requirementTask.taskId(), executionResult.pullRequestUrl(), reviewResult);
            captureDeliveryReviewFailureExperience(requirementTask.taskId(), reviewResult, executionResult);
            RdRequirementTask rejected = taskRegistry.markRequirementRejected(
                    requirementTask.taskId(),
                    "delivery review failed: " + reviewResult.reason(),
                    reviewResult.toJson()
            );
            return new RequirementDeliveryResult(
                    rejected.taskId(),
                    rejected.status(),
                    "",
                    rejected.executionResultJson(),
                    rejected.errorMessage()
            );
        }
        String reviewedResultJson = withDeliveryReviewJson(executionResult.resultJson(), reviewResult);
        // VALIDATING -> PR_CREATING：复核通过后，将复核结果与执行产物合并，提交 PR 生成阶段。
        requirementTask = taskRegistry.markRequirementPrCreating(requirementTask.taskId(), reviewedResultJson);
        RequirementPullRequestPublication publication = publishPullRequest(requirementTask, reviewedResultJson);
        if (!publication.success() || publication.pullRequestUrl().isBlank()) {
            String reason = publication.errorMessage().isBlank()
                    ? "pull request publication failed"
                    : publication.errorMessage();
            publishPullRequestPublicationAlert(requirementTask.taskId(), reason);
            // PR_CREATING -> REJECTED：PR 创建失败则写入拒绝态并回传错误。
            RdRequirementTask rejected = taskRegistry.markRequirementRejected(
                    requirementTask.taskId(),
                    "pull request publication failed: " + reason,
                    withPullRequestPublicationJson(reviewedResultJson, publication)
            );
            return new RequirementDeliveryResult(
                    rejected.taskId(),
                    rejected.status(),
                    "",
                    rejected.executionResultJson(),
                    rejected.errorMessage()
            );
        }
        RequirementExecutionResult reviewedExecutionResult = RequirementExecutionResult.success(
                executionResult.taskId(),
                executionResult.summary(),
                publication.pullRequestUrl(),
                withPullRequestPublicationJson(reviewedResultJson, publication)
        );
        // PR_CREATING -> COMMITTED：PR URL 与结果落库，进入提交成功状态。
        requirementTask = taskRegistry.markRequirementCommitted(
                requirementTask.taskId(),
                reviewedExecutionResult.pullRequestUrl(),
                reviewedExecutionResult.resultJson()
        );
        // COMMITTED -> REPORTING：进入沉淀报告阶段，准备交付经验与归档产物。
        requirementTask = taskRegistry.markRequirementReporting(
                requirementTask.taskId(),
                reviewedExecutionResult.resultJson()
        );
        captureDeliveryExperience(requirementTask.taskId(), reviewedExecutionResult);
        // REPORTING -> COMPLETED：全部阶段完成，返回完整交付结果。
        RdRequirementTask completed = taskRegistry.markRequirementCompleted(
                requirementTask.taskId(),
                reviewedExecutionResult.pullRequestUrl(),
                reviewedExecutionResult.resultJson()
        );
        return new RequirementDeliveryResult(
                completed.taskId(),
                completed.status(),
                completed.pullRequestUrl(),
                completed.executionResultJson(),
                completed.errorMessage()
        );
    }

    private RequirementDeliveryResult currentResult(RdRequirementTask task) {
        return new RequirementDeliveryResult(
                task.taskId(),
                task.status(),
                task.pullRequestUrl(),
                task.executionResultJson(),
                task.errorMessage()
        );
    }

    private boolean isNonRetryableTerminalRequirementStatus(RdTaskStatus status) {
        return status == RdTaskStatus.COMPLETED
                || status == RdTaskStatus.CANCELLED
                || status == RdTaskStatus.DEAD_LETTERED
                || status == RdTaskStatus.DELETED;
    }

    private void ensureRequirementStages(RdRequirementTask task) {
        List<AgentStageRun> stages = stageRunStore.listByTask(task.taskId());
        if (stages.isEmpty()) {
            stagePlanner.planRequirementDelivery(task.taskId(), System.currentTimeMillis())
                    .forEach(stageRunStore::save);
            return;
        }
        long now = System.currentTimeMillis();
        for (AgentRole role : AgentRole.requirementDeliveryOrder()) {
            AgentStageRun latest = latestStageOrNull(stages, role);
            if (latest == null) {
                stageRunStore.save(pendingStage(task.taskId(), role, 1, now));
                continue;
            }
            if (isRetryableRequirementStatus(task.status()) && isRetryableStageFailure(latest)) {
                stageRunStore.save(pendingStage(task.taskId(), role, latest.attemptNo() + 1, now));
            }
        }
    }

    private boolean isRetryableRequirementStatus(RdTaskStatus status) {
        return status == RdTaskStatus.REJECTED
                || status == RdTaskStatus.FAILED_RETRYABLE
                || status == RdTaskStatus.FAILED_NEEDS_HUMAN;
    }

    private boolean isRetryableStageFailure(AgentStageRun stage) {
        return stage.status() == AgentStageStatus.FAILED_RETRYABLE
                || (stage.status().isTerminal() && stage.status() != AgentStageStatus.SUCCEEDED);
    }

    private AgentStageRun pendingStage(String taskId, AgentRole role, int attemptNo, long createTimeEpochMillis) {
        return AgentStageRun.pending(
                idGenerator.nextIdString(),
                taskId,
                role,
                attemptNo,
                stageIdempotencyKey(taskId, role, attemptNo),
                createTimeEpochMillis
        );
    }

    private String stageIdempotencyKey(String taskId, AgentRole role, int attemptNo) {
        return taskId + ":" + role.name() + ":" + attemptNo;
    }

    private AgentStageRun latestStageOrNull(List<AgentStageRun> stages, AgentRole role) {
        return stages.stream()
                .filter(stage -> stage.role() == role)
                .max(STAGE_RUN_RECENCY)
                .orElse(null);
    }

    private void ensureRoleContexts(RdRequirementTask task, List<TaskMaterial> materials) {
        Set<String> existingRoles = roleContextPackageStore.listByTask(task.taskId()).stream()
                .map(contextPackage -> contextPackage.role())
                .collect(Collectors.toSet());
        long now = System.currentTimeMillis();
        List<TaskMaterial> contextMaterials = materialsWithReusableExperience(task, materials, now);
        AgentRole.requirementDeliveryOrder().stream()
                .filter(role -> !existingRoles.contains(role.name()))
                .map(role -> roleContextBuilder.build(
                        idGenerator.nextIdString(),
                        task,
                        contextMaterials,
                        role.name(),
                        18_000,
                        now
                ))
                .forEach(roleContextPackageStore::save);
    }

    private List<TaskMaterial> materialsWithReusableExperience(
            RdRequirementTask task,
            List<TaskMaterial> materials,
            long collectedAtEpochMillis
    ) {
        List<TaskMaterial> contextMaterials = new ArrayList<>(materials == null ? List.of() : materials);
        String query = experienceSearchQuery(task);
        experienceStore.searchReusable(query, task == null ? "" : task.taskId(), 5).stream()
                .map(experience -> experienceMaterial(task, experience, collectedAtEpochMillis))
                .forEach(contextMaterials::add);
        return List.copyOf(contextMaterials);
    }

    private String experienceSearchQuery(RdRequirementTask task) {
        if (task == null) {
            return "";
        }
        return (task.title() + " " + task.expectedResult() + " " + task.acceptanceCriteriaJson()).strip();
    }

    private TaskMaterial experienceMaterial(
            RdRequirementTask task,
            WorkflowExperienceEntry experience,
            long collectedAtEpochMillis
    ) {
        String content = firstNonBlank(experience.summary(), experience.contentJson());
        String materialId = "experience-" + experience.experienceId();
        String taskId = task == null ? "" : task.taskId();
        return new TaskMaterial(
                materialId,
                taskId,
                TaskMaterialType.REFERENCE_DOC,
                TaskMaterialSourceType.MANUAL_TEXT,
                "历史经验 - " + experience.title(),
                "rd-experience://" + experience.experienceId(),
                "application/json",
                sha256(content),
                contentPreview(content),
                "",
                "",
                "",
                """
                        {"source":"WORKFLOW_EXPERIENCE","experienceId":%s,"sourceTaskId":%s,"experienceType":%s,"role":%s,"sourceArtifactId":%s}
                        """.formatted(
                        json(experience.experienceId()),
                        json(experience.taskId()),
                        json(experience.experienceType().name()),
                        json(experience.role().name()),
                        json(experience.sourceArtifactId())
                ).strip(),
                collectedAtEpochMillis,
                collectedAtEpochMillis
        );
    }

    private RequirementExecutionResult executeAgentStages(
            RdRequirementTask task,
            List<TaskMaterial> materials,
            RequirementContextPackage context,
            RequirementPlan plan,
            RequirementPolicyDecision policyDecision
    ) {
        // 多角色执行链路（单任务内按固定顺序）：REQ_REVIEWER -> SOLUTION_ARCHITECT -> CODING_AGENT -> QA_AGENT。
        // 阶段状态流转（可重入）：PENDING -> CONTEXT_READY -> DISPATCHING -> RUNNING -> RESULT_COLLECTING
        // -> VERIFYING -> SUCCEEDED
        // 每一阶段独立失败分支：
        // RUNNING 中抛异常 -> FAILED_RETRYABLE
        // 发现 PR URL 违规 -> FAILED_NEEDS_HUMAN
        // 阶段失败/评审需人工 -> FAILED_NEEDS_HUMAN
        // 已终态（SUCCEEDED/FAILED_NEEDS_HUMAN/SKIPPED/CANCELLED）直接拒绝继续编排。
        List<String> stageResults = new ArrayList<>();
        String pullRequestUrl = "";
        String summary = "";
        String deliveryResultJson = "{}";
        // 可优化为责任链模式
        for (AgentRole role : AgentRole.requirementDeliveryOrder()) {
            AgentStageRun stage = stageRun(task.taskId(), role);
            // 已成功阶段：直接复用历史产物，不再触发重跑，保持幂等与可恢复性。
            if (stage.status() == AgentStageStatus.SUCCEEDED) {
                stageResults.add(reusedStageResultJson(role));
                continue;
            }
            // 阶段已进入终态但不成功时，当前提交链路直接失败（避免在异常阶段上继续向后推进）。
            if (stage.status().isTerminal()) {
                return RequirementExecutionResult.failure(
                        task.taskId(),
                        "agent stage is terminal before execution: " + role + " " + stage.status(),
                        aggregateAgentResultsJson("FAILED", pullRequestUrl, stageResults)
                );
            }
            RoleContextPackage roleContext = latestRoleContext(task.taskId(), role);
            stage = bindRoleContext(stage, roleContext);
            // PENDING -> CONTEXT_READY：将该角色的上下文包绑定为本阶段执行上下文快照。
            stage = transitionStage(stage, AgentStageStatus.CONTEXT_READY, "", "");
            // CONTEXT_READY -> DISPATCHING：准备调度并构造角色提示词。
            stage = transitionStage(stage, AgentStageStatus.DISPATCHING, "", "");
            String upstreamResultJson = stageResultsJson(stageResults);
            String rolePrompt = buildAgentPrompt(
                    role,
                    task,
                    materials,
                    context,
                    plan,
                    policyDecision,
                    roleContext,
                    upstreamResultJson
            );
            // DISPATCHING -> RUNNING：记录 prompt 快照后进入正式执行。
            stage = capturePromptArtifact(stage, rolePrompt);
            stage = transitionStage(stage, AgentStageStatus.RUNNING, "", "");
            RequirementExecutionResult roleResult;
            try {
                roleResult = normalizeResult(
                        task.taskId(),
                        executor.execute(new RequirementExecutionRequest(
                                task.taskId(),
                                task,
                                materials,
                                rolePrompt,
                                role,
                                roleContextJson(roleContext),
                                false,
                                upstreamResultJson
                        ))
                );
            } catch (RuntimeException exception) {
                // RUNNING -> FAILED_RETRYABLE：执行器抛出异常，先记录可重试失败并返回全链路失败。
                AgentStageRun failedStage = stageRunStore.transition(
                        stage.stageRunId(),
                        AgentStageStatus.FAILED_RETRYABLE,
                        "AGENT_EXECUTOR_EXCEPTION",
                        exception.getMessage(),
                        System.currentTimeMillis()
                );
                publishStageAlert(
                        failedStage,
                        AgentWorkflowAlertType.STAGE_FAILED_RETRYABLE,
                        "agent executor exception: " + safe(exception.getMessage())
                );
                return RequirementExecutionResult.failure(
                        task.taskId(),
                        "agent executor exception: " + role + " " + safe(exception.getMessage()),
                        aggregateAgentResultsJson("FAILED", pullRequestUrl, stageResults)
                );
            }
            // 结果产出：保存角色产物并补齐 provider 元数据，进入 RESULT_COLLECTING。
            stage = captureResultArtifact(stage, roleResult);
            stage = recordProviderMetadata(stage, roleResult);
            stage = transitionStage(stage, AgentStageStatus.RESULT_COLLECTING, "", "");
            if (!roleResult.pullRequestUrl().isBlank()) {
                // 所有角色禁止在角色阶段直接返回 PR 地址：RESULT_COLLECTING -> FAILED_NEEDS_HUMAN（强制交由交付复核阶段）。
                String reason = "agent stage returned pullRequestUrl before delivery review: " + role;
                AgentStageRun failedStage = stageRunStore.transition(
                        stage.stageRunId(),
                        AgentStageStatus.FAILED_NEEDS_HUMAN,
                        "AGENT_PR_POLICY_VIOLATION",
                        reason,
                        System.currentTimeMillis()
                );
                publishStageAlert(
                        failedStage,
                        AgentWorkflowAlertType.STAGE_FAILED_NEEDS_HUMAN,
                        reason
                );
                stageResults.add(stageResultJson(role, roleResult));
                return RequirementExecutionResult.failure(
                        task.taskId(),
                        reason,
                        aggregateAgentResultsJson("NEEDS_HUMAN", pullRequestUrl, stageResults)
                );
            }
            if (!roleResult.success()) {
                // RESULT_COLLECTING -> FAILED_NEEDS_HUMAN：角色执行失败，带上错误原因，阻断后续角色。
                String reason = roleResult.errorMessage().isBlank()
                        ? "agent stage failed: " + role
                        : roleResult.errorMessage();
                AgentStageRun failedStage = stageRunStore.transition(
                        stage.stageRunId(),
                        AgentStageStatus.FAILED_NEEDS_HUMAN,
                        "AGENT_RESULT_REJECTED",
                        reason,
                        System.currentTimeMillis()
                );
                publishStageAlert(
                        failedStage,
                        role == AgentRole.QA_AGENT
                                ? AgentWorkflowAlertType.QA_FAILED
                                : AgentWorkflowAlertType.STAGE_FAILED_NEEDS_HUMAN,
                        reason
                );
                stageResults.add(stageResultJson(role, roleResult));
                String aggregateStatus = role == AgentRole.QA_AGENT ? "NEEDS_HUMAN" : "FAILED";
                return RequirementExecutionResult.failure(
                        task.taskId(),
                        role + " failed: " + reason,
                        aggregateAgentResultsJson(aggregateStatus, pullRequestUrl, stageResults)
                );
            }
            if (role == AgentRole.REQUIREMENT_REVIEWER) {
                // 需求评审角色完成后，补充一次业务门控：NEED/HUMAN 或异常状态 -> FAILED_NEEDS_HUMAN（不进入下一角色）。
                RequirementReviewGateDecision reviewGateDecision = requirementReviewGateDecision(roleResult);
                if (reviewGateDecision.needsHuman()) {
                    AgentStageRun failedStage = stageRunStore.transition(
                            stage.stageRunId(),
                            AgentStageStatus.FAILED_NEEDS_HUMAN,
                            "REQUIREMENT_REVIEW_NEEDS_HUMAN",
                            reviewGateDecision.reason(),
                            System.currentTimeMillis()
                    );
                    publishStageAlert(
                            failedStage,
                            AgentWorkflowAlertType.STAGE_FAILED_NEEDS_HUMAN,
                            reviewGateDecision.reason()
                    );
                    stageResults.add(stageResultJson(role, roleResult));
                    return RequirementExecutionResult.failure(
                            task.taskId(),
                            reviewGateDecision.reason(),
                            aggregateAgentResultsJson("NEEDS_HUMAN", pullRequestUrl, stageResults)
                    );
                }
            }
            if (role == AgentRole.CODING_AGENT) {
                // 代码交付阶段产出作为交付交付结果基底，供后续 PR 复核聚合。
                deliveryResultJson = roleResult.resultJson();
            }
            if (!roleResult.summary().isBlank()) {
                summary = roleResult.summary();
            }
            // RESULT_COLLECTING -> VERIFYING -> SUCCEEDED：单角色执行成功后，进入阶段验收并标记完成。
            stage = transitionStage(stage, AgentStageStatus.VERIFYING, "", "");
            stage = transitionStage(stage, AgentStageStatus.SUCCEEDED, "", "");
            stageResults.add(stageResultJson(role, roleResult));
            captureExperience(stage, roleResult, experienceType(role));
        }
        // 四个角色全部 SUCCEEDED：聚合为交付执行最终结果并返回，交付层将进入 PR 复核与发布。
        RequirementExecutionResult finalResult = RequirementExecutionResult.success(
                task.taskId(),
                summary,
                pullRequestUrl,
                mergeDeliveryResultJson(deliveryResultJson, pullRequestUrl, stageResults)
        );
        return finalResult;
    }

    private boolean needsHumanInterventionResult(RequirementExecutionResult result) {
        return "NEEDS_HUMAN".equals(aggregateStatus(result == null ? "" : result.resultJson()));
    }

    private RequirementPullRequestPublication publishPullRequest(RdRequirementTask task, String reviewedResultJson) {
        try {
            return pullRequestPublisher.publish(new RequirementPullRequestPublishCommand(
                    task.taskId(),
                    task.title(),
                    task.repositoryUrl(),
                    task.repoOwner(),
                    task.repoName(),
                    task.baseBranch(),
                    workBranch(task),
                    reviewedResultJson
            ));
        } catch (RuntimeException exception) {
            return RequirementPullRequestPublication.failure(
                    task.taskId(),
                    "pull request publisher exception: " + safe(exception.getMessage())
            );
        }
    }

    private void publishStageAlert(AgentStageRun stage, AgentWorkflowAlertType type, String message) {
        alertSink.publish(new AgentWorkflowAlert(
                stage.taskId(),
                stage.stageRunId(),
                type,
                message,
                Map.of(
                        "role", stage.role().name(),
                        "status", stage.status().name(),
                        "attemptNo", Integer.toString(stage.attemptNo()),
                        "errorCategory", stage.errorCategory()
                ),
                System.currentTimeMillis()
        ));
    }

    private void publishDeliveryReviewAlert(
            String taskId,
            String pullRequestUrl,
            RequirementDeliveryReviewResult reviewResult
    ) {
        alertSink.publish(new AgentWorkflowAlert(
                taskId,
                "",
                AgentWorkflowAlertType.DELIVERY_REVIEW_FAILED,
                "delivery review failed: " + reviewResult.reason(),
                Map.of(
                        "role", "DELIVERY_REVIEWER",
                        "pullRequestUrl", safe(pullRequestUrl),
                        "reason", reviewResult.reason()
                ),
                System.currentTimeMillis()
        ));
    }

    private void publishPullRequestPublicationAlert(String taskId, String reason) {
        alertSink.publish(new AgentWorkflowAlert(
                taskId,
                "",
                AgentWorkflowAlertType.PR_PUBLICATION_FAILED,
                "pull request publication failed: " + safe(reason),
                Map.of(
                        "role", "DELIVERY_REVIEWER",
                        "reason", safe(reason)
                ),
                System.currentTimeMillis()
        ));
    }

    private AgentStageRun capturePromptArtifact(AgentStageRun stage, String rolePrompt) {
        if (!stage.promptArtifactId().isBlank()) {
            return stage;
        }
        long now = System.currentTimeMillis();
        AgentStageArtifact artifact = artifactStore.save(new AgentStageArtifact(
                idGenerator.nextIdString(),
                stage.stageRunId(),
                stage.taskId(),
                stage.role(),
                "PROMPT_SNAPSHOT",
                artifactUri(stage, "prompt"),
                stage.role().name() + " prompt snapshot",
                contentPreview(rolePrompt),
                sha256(rolePrompt),
                artifactMetadata(stage, "PROMPT_SNAPSHOT", rolePrompt),
                now
        ));
        return stageRunStore.save(stage.withPromptArtifactId(artifact.artifactId(), now));
    }

    private AgentStageRun captureResultArtifact(AgentStageRun stage, RequirementExecutionResult result) {
        if (!stage.resultArtifactId().isBlank()) {
            return stage;
        }
        String resultJson = result == null ? "{}" : result.resultJson();
        long now = System.currentTimeMillis();
        AgentStageArtifact artifact = artifactStore.save(new AgentStageArtifact(
                idGenerator.nextIdString(),
                stage.stageRunId(),
                stage.taskId(),
                stage.role(),
                "RESULT_JSON",
                artifactUri(stage, "result"),
                stage.role().name() + " result json",
                contentPreview(resultJson),
                sha256(resultJson),
                artifactMetadata(stage, "RESULT_JSON", resultJson),
                now
        ));
        captureExecutorStageArtifacts(stage, resultJson, now);
        return stageRunStore.save(stage.withResultArtifactId(artifact.artifactId(), now));
    }

    private void captureExecutorStageArtifacts(AgentStageRun stage, String resultJson, long now) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(resultJson == null ? "{}" : resultJson);
            JsonNode stageArtifacts = root.path("stageArtifacts");
            if (!stageArtifacts.isArray() || stageArtifacts.isEmpty()) {
                return;
            }
            int index = 0;
            for (JsonNode item : stageArtifacts) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                String artifactType = firstNonBlank(text(item.path("type")), text(item.path("artifactType")));
                if (artifactType.isBlank() || "RESULT_JSON".equals(artifactType)) {
                    continue;
                }
                String content = stageArtifactContentPreview(item);
                String uri = firstNonBlank(text(item.path("uri")), text(item.path("artifactUri")));
                if (content.isBlank() && uri.isBlank()) {
                    continue;
                }
                artifactStore.save(new AgentStageArtifact(
                        idGenerator.nextIdString(),
                        stage.stageRunId(),
                        stage.taskId(),
                        stage.role(),
                        artifactType,
                        uri.isBlank() ? artifactUri(stage, artifactType.toLowerCase(Locale.ROOT)) : uri,
                        firstNonBlank(text(item.path("summary")), artifactType + " artifact"),
                        content,
                        sha256(content.isBlank() ? uri : content),
                        stageArtifactMetadata(stage, artifactType, item),
                        now + (++index)
                ));
            }
        } catch (JsonProcessingException ignored) {
            // RESULT_JSON remains persisted; malformed auxiliary artifact metadata must not hide the primary result.
        }
    }

    private String stageArtifactContentPreview(JsonNode item) throws JsonProcessingException {
        JsonNode contentPreview = item.path("contentPreview");
        if (contentPreview.isMissingNode() || contentPreview.isNull()) {
            return "";
        }
        if (contentPreview.isTextual()) {
            return safe(contentPreview.asText());
        }
        return OBJECT_MAPPER.writeValueAsString(contentPreview);
    }

    private String stageArtifactMetadata(AgentStageRun stage, String artifactType, JsonNode item)
            throws JsonProcessingException {
        JsonNode metadata = item.path("metadataJson");
        if (metadata.isObject()) {
            return OBJECT_MAPPER.writeValueAsString(metadata);
        }
        return artifactMetadata(stage, artifactType, stageArtifactContentPreview(item));
    }

    private String artifactUri(AgentStageRun stage, String name) {
        return "rd-agent-stage://" + stage.taskId() + "/" + stage.stageRunId() + "/" + name;
    }

    private String artifactMetadata(AgentStageRun stage, String artifactType, String content) {
        return """
                {"taskId":%s,"stageRunId":%s,"role":%s,"artifactType":%s,"contentLength":%d}
                """.formatted(
                json(stage.taskId()),
                json(stage.stageRunId()),
                json(stage.role().name()),
                json(artifactType),
                safe(content).length()
        ).strip();
    }

    private String contentPreview(String content) {
        String normalized = safe(content);
        int maxChars = 20_000;
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars);
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(safe(content).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    private void captureExperience(
            AgentStageRun stage,
            RequirementExecutionResult result,
            WorkflowExperienceType experienceType
    ) {
        try {
            experienceStore.save(new WorkflowExperienceEntry(
                    idGenerator.nextIdString(),
                    stage.taskId(),
                    stage.stageRunId(),
                    stage.resultArtifactId(),
                    stage.role(),
                    experienceType,
                    experienceTitle(stage.role()),
                    result.summary(),
                    result.resultJson(),
                    experienceType != WorkflowExperienceType.CODE_CHANGE,
                    false,
                    true,
                    System.currentTimeMillis()
            ));
        } catch (RuntimeException exception) {
            publishStageAlert(
                    stage,
                    AgentWorkflowAlertType.EXPERIENCE_CAPTURE_FAILED,
                    "experience capture failed: " + safe(exception.getMessage())
            );
        }
    }

    private AgentStageRun recordProviderMetadata(AgentStageRun stage, RequirementExecutionResult result) {
        ProviderMetadata metadata = providerMetadata(result.resultJson());
        if (metadata.isEmpty()) {
            return stage;
        }
        AgentStageRun updatedStage = stageRunStore.save(stage.withProviderMetadata(
                metadata.providerName(),
                metadata.providerAttemptsJson(),
                System.currentTimeMillis()
        ));
        publishProviderFallbackAlert(updatedStage, metadata);
        return updatedStage;
    }

    private ProviderMetadata providerMetadata(String resultJson) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(resultJson == null ? "{}" : resultJson);
            if (root == null || !root.isObject()) {
                return ProviderMetadata.empty();
            }
            JsonNode dockerMetadata = root.path("dockerMetadata");
            String providerName = firstNonBlank(
                    text(dockerMetadata.path("provider")),
                    text(root.path("provider")),
                    text(root.path("providerName"))
            );
            String providerAttemptsJson = firstNonBlank(
                    text(dockerMetadata.path("providerAttemptsJson")),
                    text(root.path("providerAttemptsJson")),
                    arrayJson(root.path("providerAttempts"))
            );
            return new ProviderMetadata(providerName, providerAttemptsJson);
        } catch (JsonProcessingException exception) {
            return ProviderMetadata.empty();
        }
    }

    private void publishProviderFallbackAlert(AgentStageRun stage, ProviderMetadata metadata) {
        ProviderFallbackEvidence fallback = providerFallbackEvidence(metadata);
        if (fallback.isEmpty()) {
            return;
        }
        alertSink.publish(new AgentWorkflowAlert(
                stage.taskId(),
                stage.stageRunId(),
                AgentWorkflowAlertType.PROVIDER_FALLBACK,
                "provider fallback: " + fallback.failedProvider()
                        + " " + fallback.failedStatus()
                        + " -> " + fallback.activeProvider(),
                Map.of(
                        "role", stage.role().name(),
                        "status", stage.status().name(),
                        "attemptNo", Integer.toString(stage.attemptNo()),
                        "failedProvider", fallback.failedProvider(),
                        "failedStatus", fallback.failedStatus(),
                        "activeProvider", fallback.activeProvider()
                ),
                System.currentTimeMillis()
        ));
    }

    private ProviderFallbackEvidence providerFallbackEvidence(ProviderMetadata metadata) {
        if (metadata == null || metadata.providerAttemptsJson().isBlank()) {
            return ProviderFallbackEvidence.empty();
        }
        try {
            JsonNode attempts = OBJECT_MAPPER.readTree(metadata.providerAttemptsJson());
            if (attempts == null || !attempts.isArray() || attempts.size() < 2) {
                return ProviderFallbackEvidence.empty();
            }
            String failedProvider = "";
            String failedStatus = "";
            String activeProvider = "";
            for (JsonNode attempt : attempts) {
                String status = normalizedCode(attempt.path("status"));
                String provider = text(attempt.path("provider"));
                if ("SUCCESS".equals(status)) {
                    activeProvider = firstNonBlank(provider, activeProvider);
                } else if (!status.isBlank() && failedProvider.isBlank()) {
                    failedProvider = provider;
                    failedStatus = status;
                }
            }
            activeProvider = firstNonBlank(metadata.providerName(), activeProvider);
            if (failedProvider.isBlank()
                    || failedStatus.isBlank()
                    || activeProvider.isBlank()
                    || sameProvider(failedProvider, activeProvider)) {
                return ProviderFallbackEvidence.empty();
            }
            return new ProviderFallbackEvidence(failedProvider, failedStatus, activeProvider);
        } catch (JsonProcessingException exception) {
            return ProviderFallbackEvidence.empty();
        }
    }

    private boolean sameProvider(String left, String right) {
        return safe(left).equalsIgnoreCase(safe(right));
    }

    private RequirementReviewGateDecision requirementReviewGateDecision(RequirementExecutionResult result) {
        if (result == null) {
            return new RequirementReviewGateDecision(true, "requirement review result missing");
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(result.resultJson() == null ? "{}" : result.resultJson());
            if (root == null || !root.isObject()) {
                return new RequirementReviewGateDecision(
                        true,
                        firstNonBlank(result.summary(), "requirement review result json must be an object")
                );
            }
            String status = normalizedCode(root.path("status"));
            String decision = normalizedCode(root.path("decision"));
            String feasibility = normalizedCode(root.path("feasibility"));
            if (requiresHuman(status) || requiresHuman(decision) || requiresHuman(feasibility)) {
                return new RequirementReviewGateDecision(true, requirementReviewNeedsHumanReason(result, root));
            }
            if (status.isBlank() && decision.isBlank() && feasibility.isBlank()) {
                return new RequirementReviewGateDecision(
                        true,
                        firstNonBlank(result.summary(), "requirement review decision is missing")
                );
            }
            return RequirementReviewGateDecision.proceed();
        } catch (JsonProcessingException exception) {
            return new RequirementReviewGateDecision(
                    true,
                    firstNonBlank(result.summary(), "invalid requirement review result json")
            );
        }
    }

    private String requirementReviewNeedsHumanReason(RequirementExecutionResult result, JsonNode root) {
        return firstNonBlank(
                result.summary(),
                text(root.path("reason")),
                text(root.path("errorMessage")),
                text(root.path("summary")),
                "requirement review needs human input"
        );
    }

    private boolean requiresHuman(String value) {
        return switch (value) {
            case "NEED_INFO", "NEEDS_HUMAN", "UNSAFE", "REJECTED", "REJECT", "FAILED", "FAILURE", "BLOCKED" -> true;
            default -> false;
        };
    }

    private String normalizedCode(JsonNode node) {
        return text(node).toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private String aggregateStatus(String resultJson) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(resultJson == null ? "{}" : resultJson);
            if (root == null || !root.isObject()) {
                return "";
            }
            return normalizedCode(root.path("status"));
        } catch (JsonProcessingException exception) {
            return "";
        }
    }

    private void captureDeliveryExperience(String taskId, RequirementExecutionResult result) {
        try {
            experienceStore.save(new WorkflowExperienceEntry(
                    idGenerator.nextIdString(),
                    taskId,
                    "",
                    latestStageResultArtifactId(taskId, AgentRole.QA_AGENT),
                    AgentRole.QA_AGENT,
                    WorkflowExperienceType.DELIVERY_REPORT,
                    "需求交付报告",
                    result.summary(),
                    result.resultJson(),
                    true,
                    false,
                    true,
                    System.currentTimeMillis()
            ));
        } catch (RuntimeException exception) {
            alertSink.publish(new AgentWorkflowAlert(
                    taskId,
                    "",
                    AgentWorkflowAlertType.EXPERIENCE_CAPTURE_FAILED,
                    "delivery experience capture failed: " + safe(exception.getMessage()),
                    Map.of("role", "DELIVERY_REVIEWER"),
                    System.currentTimeMillis()
            ));
        }
    }

    private void captureDeliveryReviewFailureExperience(
            String taskId,
            RequirementDeliveryReviewResult reviewResult,
            RequirementExecutionResult result
    ) {
        try {
            experienceStore.save(new WorkflowExperienceEntry(
                    idGenerator.nextIdString(),
                    taskId,
                    "",
                    latestStageResultArtifactId(taskId, AgentRole.QA_AGENT),
                    AgentRole.QA_AGENT,
                    WorkflowExperienceType.DELIVERY_REPORT,
                    "需求交付复核失败",
                    reviewResult.reason(),
                    """
                            {"review":%s,"deliveryResult":%s}
                            """.formatted(reviewResult.toJson(), json(result.resultJson())).strip(),
                    false,
                    true,
                    true,
                    System.currentTimeMillis()
            ));
        } catch (RuntimeException exception) {
            alertSink.publish(new AgentWorkflowAlert(
                    taskId,
                    "",
                    AgentWorkflowAlertType.EXPERIENCE_CAPTURE_FAILED,
                    "delivery review failure experience capture failed: " + safe(exception.getMessage()),
                    Map.of("role", "DELIVERY_REVIEWER"),
                    System.currentTimeMillis()
            ));
        }
    }

    private WorkflowExperienceType experienceType(AgentRole role) {
        return switch (role) {
            case REQUIREMENT_REVIEWER -> WorkflowExperienceType.REQUIREMENT_REVIEW;
            case SOLUTION_ARCHITECT -> WorkflowExperienceType.TECHNICAL_DESIGN;
            case CODING_AGENT -> WorkflowExperienceType.CODE_CHANGE;
            case QA_AGENT -> WorkflowExperienceType.QA_REPORT;
        };
    }

    private String latestStageResultArtifactId(String taskId, AgentRole role) {
        return stageRunStore.listByTask(taskId).stream()
                .filter(stage -> stage.role() == role)
                .map(AgentStageRun::resultArtifactId)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private String experienceTitle(AgentRole role) {
        return switch (role) {
            case REQUIREMENT_REVIEWER -> "需求评审";
            case SOLUTION_ARCHITECT -> "技术方案";
            case CODING_AGENT -> "代码交付";
            case QA_AGENT -> "QA 验收";
        };
    }

    private AgentStageRun bindRoleContext(AgentStageRun stage, RoleContextPackage roleContext) {
        if (stage.contextPackageId().equals(roleContext.packageId())) {
            return stage;
        }
        return stageRunStore.save(stage.withContextPackageId(roleContext.packageId(), System.currentTimeMillis()));
    }

    private AgentStageRun transitionStage(
            AgentStageRun stage,
            AgentStageStatus targetStatus,
            String errorCategory,
            String errorMessage
    ) {
        if (stage.status() == targetStatus) {
            return stage;
        }
        return stageRunStore.transition(
                stage.stageRunId(),
                targetStatus,
                errorCategory,
                errorMessage,
                System.currentTimeMillis()
        );
    }

    private AgentStageRun stageRun(String taskId, AgentRole role) {
        return stageRunStore.listByTask(taskId).stream()
                .filter(stage -> stage.role() == role)
                .max(STAGE_RUN_RECENCY)
                .orElseThrow(() -> new IllegalStateException("agent stage run missing: " + taskId + " " + role));
    }

    private RoleContextPackage latestRoleContext(String taskId, AgentRole role) {
        List<RoleContextPackage> packages = roleContextPackageStore.listByTaskAndRole(taskId, role.name());
        if (packages.isEmpty()) {
            throw new IllegalStateException("role context package missing: " + taskId + " " + role);
        }
        return packages.get(packages.size() - 1);
    }

    private static SnowflakeIdGenerator safeIdGenerator(SnowflakeIdGenerator idGenerator) {
        return idGenerator == null ? SnowflakeIdGenerator.defaultGenerator() : idGenerator;
    }

    private String text(JsonNode node) {
        return node == null || !node.isTextual() ? "" : safe(node.asText());
    }

    private String arrayJson(JsonNode node) throws JsonProcessingException {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return "";
        }
        return OBJECT_MAPPER.writeValueAsString(node);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = safe(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
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

    private String buildAgentPrompt(
            AgentRole role,
            RdRequirementTask task,
            List<TaskMaterial> materials,
            RequirementContextPackage context,
            RequirementPlan plan,
            RequirementPolicyDecision policyDecision,
            RoleContextPackage roleContext,
            String upstreamResultJson
    ) {
        return """
                你是 RD-Bot 多 Agent 需求交付链路中的 %s。

                # 当前职责
                %s

                # 角色上下文
                %s

                # 上游阶段结果
                %s

                %s

                # 当前角色输出 JSON 协议
                %s
                """.formatted(
                role.name(),
                roleInstruction(role),
                roleContextJson(roleContext),
                upstreamResultJson,
                buildPrompt(task, materials, context, plan, policyDecision),
                roleOutputContract(role)
        ).strip();
    }

    private String roleInstruction(AgentRole role) {
        return switch (role) {
            case REQUIREMENT_REVIEWER -> """
                    - 只做需求评审，不修改代码，不创建 PR。
                    - 输出结构化需求评审结果，明确能否做、缺失信息、风险和验收覆盖。
                    - 结果必须写入 /work/output/result.json，且只使用当前角色输出 JSON 协议。
                    """.strip();
            case SOLUTION_ARCHITECT -> """
                    - 基于需求评审和证据制定开发方案，不修改代码，不创建 PR。
                    - 输出影响文件、接口/数据变更、实现步骤、验收映射和测试计划。
                    - 结果必须写入 /work/output/result.json，且只使用当前角色输出 JSON 协议。
                    """.strip();
            case CODING_AGENT -> """
                    - 根据需求评审和方案执行代码修改。
                    - 该阶段只负责代码修改和交付候选证据，不创建 PR。
                    - 成功时返回 prBody、changedFiles、testSummary 和真实测试证据，等待控制面复核后发布。
                    """.strip();
            case QA_AGENT -> """
                    - 基于代码交付候选包、验收标准和真实命令执行 QA 复核。
                    - 不创建新 PR；任一验收标准没有真实证据时必须失败。
                    - 必须真实执行命令并把每条命令结果写入 acceptanceResults。
                    - 全部通过时 status=PASSED；任一命令失败时 status=FAILED；无法真实执行时 status=SKIPPED。
                    - acceptanceResults 每项必须包含 criteria、command、status、logArtifactId。
                    """.strip();
        };
    }

    private String roleOutputContract(AgentRole role) {
        return switch (role) {
            case REQUIREMENT_REVIEWER -> """
                    只输出一个 JSON 对象，不要 markdown：
                    {
                      "decision": "APPROVED|NEED_INFO|REJECTED",
                      "feasibility": "CAN_DO|NEED_INFO|UNSAFE",
                      "missingInformation": [],
                      "risks": [],
                      "acceptanceCoverage": ["每条验收标准的覆盖判断"]
                    }
                    """.strip();
            case SOLUTION_ARCHITECT -> """
                    只输出一个 JSON 对象，不要 markdown：
                    {
                      "summary": "开发方案摘要",
                      "affectedFiles": ["预计影响文件"],
                      "implementationSteps": ["可执行开发步骤"],
                      "acceptanceMapping": [{"criteria":"验收标准","validation":"真实验证方式"}],
                      "testPlan": [{"criteria":"验收标准","command":"真实测试命令"}]
                    }
                    """.strip();
            case CODING_AGENT -> """
                    只输出一个 JSON 对象，不要 markdown：
                    {
                      "status": "SUCCESS|FAILED|NEED_INFO|UNSAFE",
                      "summary": "实现摘要",
                      "changedFiles": ["实际改动文件"],
                      "testCommands": ["真实执行过的命令"],
                      "testStatus": "PASSED|FAILED|SKIPPED",
                      "riskLevel": "LOW|MEDIUM|HIGH",
                      "prBody": "候选 PR 正文，包含改动和真实验证证据",
                      "needHumanAction": false
                    }
                    """.strip();
            case QA_AGENT -> """
                    只输出一个 JSON 对象，不要 markdown：
                    {
                      "status": "PASSED|FAILED|SKIPPED",
                      "summary": "QA 真实命令验收摘要",
                      "acceptanceResults": [
                        {
                          "criteria": "对应验收标准",
                          "command": "真实执行命令",
                          "status": "PASSED|FAILED|SKIPPED",
                          "logArtifactId": "命令日志产物 ID"
                        }
                      ]
                    }
                    """.strip();
        };
    }

    private String roleContextJson(RoleContextPackage roleContext) {
        return """
                {"packageId":%s,"taskId":%s,"role":%s,"packageVersion":%d,"acceptanceCriteria":%s,"riskHints":%s,"evidence":%s,"omittedEvidenceIds":%s}
                """.formatted(
                json(roleContext.packageId()),
                json(roleContext.taskId()),
                json(roleContext.role()),
                roleContext.packageVersion(),
                jsonArray(roleContext.acceptanceCriteria()),
                jsonArray(roleContext.riskHints()),
                evidenceJson(roleContext.evidence()),
                jsonArray(roleContext.omittedEvidenceIds())
        ).strip();
    }

    private String evidenceJson(List<RoleContextEvidence> evidence) {
        return evidence.stream()
                .map(item -> """
                        {"evidenceId":%s,"sourceType":%s,"sourceUri":%s,"title":%s,"contentHash":%s,"summary":%s,"collectedAtEpochMillis":%d}
                        """.formatted(
                        json(item.evidenceId()),
                        json(item.sourceType()),
                        json(item.sourceUri()),
                        json(item.title()),
                        json(item.contentHash()),
                        json(item.summary()),
                        item.collectedAtEpochMillis()
                ).strip())
                .collect(Collectors.joining(",", "[", "]"));
    }

    private String stageResultsJson(List<String> stageResults) {
        return stageResults.stream().collect(Collectors.joining(",", "[", "]"));
    }

    private String reusedStageResultJson(AgentRole role) {
        return """
                {"role":%s,"success":true,"reused":true,"status":%s}
                """.formatted(json(role.name()), json("SUCCEEDED")).strip();
    }

    private String stageResultJson(AgentRole role, RequirementExecutionResult result) {
        return """
                {"role":%s,"success":%s,"summary":%s,"pullRequestUrl":%s,"errorMessage":%s,"resultJson":%s}
                """.formatted(
                json(role.name()),
                result.success(),
                json(result.summary()),
                json(result.pullRequestUrl()),
                json(result.errorMessage()),
                json(result.resultJson())
        ).strip();
    }

    private String aggregateAgentResultsJson(String status, String pullRequestUrl, List<String> stageResults) {
        return """
                {"status":%s,"pullRequestUrl":%s,"stages":%s}
                """.formatted(
                json(status),
                json(pullRequestUrl),
                stageResultsJson(stageResults)
        ).strip();
    }

    private String mergeDeliveryResultJson(String deliveryResultJson, String pullRequestUrl, List<String> stageResults) {
        String normalized = deliveryResultJson == null ? "" : deliveryResultJson.strip();
        String appended = """
                "pullRequestUrl":%s,"multiAgentStatus":%s,"multiAgentStages":%s
                """.formatted(
                json(pullRequestUrl),
                json("SUCCESS"),
                stageResultsJson(stageResults)
        ).strip();
        if (normalized.startsWith("{") && normalized.endsWith("}")) {
            String body = normalized.substring(1, normalized.length() - 1).strip();
            if (body.isBlank()) {
                return "{" + appended + "}";
            }
            return "{" + body + "," + appended + "}";
        }
        return aggregateAgentResultsJson("SUCCESS", pullRequestUrl, stageResults);
    }

    private String withDeliveryReviewJson(String resultJson, RequirementDeliveryReviewResult reviewResult) {
        String reviewJson = reviewResult == null
                ? RequirementDeliveryReviewResult.rejected("", "delivery review result missing").toJson()
                : reviewResult.toJson();
        String normalized = resultJson == null ? "" : resultJson.strip();
        String appended = "\"deliveryReview\":" + reviewJson;
        if (normalized.startsWith("{") && normalized.endsWith("}")) {
            String body = normalized.substring(1, normalized.length() - 1).strip();
            if (body.isBlank()) {
                return "{" + appended + "}";
            }
            return "{" + body + "," + appended + "}";
        }
        return "{\"status\":\"SUCCESS\",\"deliveryResult\":" + json(normalized) + "," + appended + "}";
    }

    private String withPullRequestPublicationJson(
            String resultJson,
            RequirementPullRequestPublication publication
    ) {
        String publicationJson = publicationJson(publication);
        String normalized = resultJson == null ? "" : resultJson.strip();
        String appended = """
                "pullRequestUrl":%s,"pullRequestPublication":%s
                """.formatted(
                json(publication == null ? "" : publication.pullRequestUrl()),
                publicationJson
        ).strip();
        if (normalized.startsWith("{") && normalized.endsWith("}")) {
            String body = normalized.substring(1, normalized.length() - 1).strip();
            if (body.isBlank()) {
                return "{" + appended + "}";
            }
            return "{" + body + "," + appended + "}";
        }
        return "{\"status\":\"SUCCESS\",\"deliveryResult\":" + json(normalized) + "," + appended + "}";
    }

    private String publicationJson(RequirementPullRequestPublication publication) {
        if (publication == null) {
            return """
                    {"taskId":"","success":false,"pullRequestUrl":"","pullRequestNumber":"","metadataJson":{},"errorMessage":"publication missing"}
                    """.strip();
        }
        return """
                {"taskId":%s,"success":%s,"pullRequestUrl":%s,"pullRequestNumber":%s,"metadataJson":%s,"errorMessage":%s}
                """.formatted(
                json(publication.taskId()),
                publication.success(),
                json(publication.pullRequestUrl()),
                json(publication.pullRequestNumber()),
                publication.metadataJson(),
                json(publication.errorMessage())
        ).strip();
    }

    private String workBranch(RdRequirementTask task) {
        return "requirement/" + task.taskId();
    }

    private String jsonArray(List<String> values) {
        return values.stream().map(this::json).collect(Collectors.joining(",", "[", "]"));
    }

    private String json(String value) {
        return "\"" + safe(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private record ProviderMetadata(String providerName, String providerAttemptsJson) {

        private ProviderMetadata {
            providerName = safe(providerName);
            providerAttemptsJson = providerAttemptsJson == null || providerAttemptsJson.isBlank()
                    ? "[]"
                    : providerAttemptsJson.strip();
        }

        private static ProviderMetadata empty() {
            return new ProviderMetadata("", "[]");
        }

        private boolean isEmpty() {
            return providerName.isBlank() && "[]".equals(providerAttemptsJson);
        }
    }

    private record ProviderFallbackEvidence(String failedProvider, String failedStatus, String activeProvider) {

        private ProviderFallbackEvidence {
            failedProvider = safe(failedProvider);
            failedStatus = safe(failedStatus);
            activeProvider = safe(activeProvider);
        }

        private static ProviderFallbackEvidence empty() {
            return new ProviderFallbackEvidence("", "", "");
        }

        private boolean isEmpty() {
            return failedProvider.isBlank() || failedStatus.isBlank() || activeProvider.isBlank();
        }
    }

    private record RequirementReviewGateDecision(boolean needsHuman, String reason) {

        private RequirementReviewGateDecision {
            reason = safe(reason);
        }

        private static RequirementReviewGateDecision proceed() {
            return new RequirementReviewGateDecision(false, "");
        }
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
