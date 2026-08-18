package com.wish.rd.engine.requirement;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.AgentWorkflowAlertSinkPort;
import com.wish.rd.engine.agent.WorkflowExperienceStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.agent.model.AgentWorkflowAlert;
import com.wish.rd.engine.agent.model.AgentWorkflowAlertType;
import com.wish.rd.engine.agent.model.WorkflowExperienceEntry;
import com.wish.rd.engine.agent.model.WorkflowExperienceType;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.engine.retrieval.model.RetrievalOutcome;
import com.wish.rd.rag.context.model.RoleContextPackage;
import com.wish.rd.rag.context.RoleContextPackageStore;
import com.wish.rd.rag.project.budget.RdProjectTokenBudgetService;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunStatus;
import com.wish.rd.rag.project.agent.model.ContextProtocolVersion;
import com.wish.rd.rag.project.agent.model.AgentManifestCanonicalJson;
import com.wish.rd.rag.project.agent.model.RoleExecutionInputManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.wish.rd.engine.requirement.model.AgentWorkflowPlan;
import com.wish.rd.engine.requirement.model.RequirementContextPackage;
import com.wish.rd.engine.requirement.model.RequirementExecutionProfileResolution;
import com.wish.rd.engine.requirement.model.RequirementExecutionRequest;
import com.wish.rd.engine.requirement.model.RequirementExecutionResult;
import com.wish.rd.engine.requirement.model.RequirementPlan;
import com.wish.rd.engine.requirement.model.RequirementPolicyDecision;
import com.wish.rd.engine.requirement.verify.HostVerificationPort;
import com.wish.rd.engine.requirement.verify.model.HostVerificationRun;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.provider.ProviderFallbackPolicyEnforcer;
import com.wish.rd.engine.provider.ProviderSideEffectStatusPort;
import com.wish.rd.engine.provider.ProviderCapabilityCatalog;
import com.wish.rd.engine.provider.model.ProviderFallbackDecision;
import com.wish.rd.engine.provider.model.ProviderFallbackEvaluation;
import com.wish.rd.engine.provider.model.ProviderFallbackSideEffectSafety;
import com.wish.rd.engine.provider.model.ProviderWorkRisk;

/**
 * 需求交付 Agent 阶段编排器（独立）。
 *
 * <p>从 {@link RequirementDeliveryEngine} 抽出 240 行阶段主循环 + 一次性 QA 修复回路
 * （spec §5.2）以及所有相关 helper 方法。本类通过构造器注入全部依赖：
 *
 * <ul>
 *     <li>{@link AgentStageRunStore} / {@link AgentStageArtifactStore}：阶段运行与产物落库</li>
 *     <li>{@link RoleContextPackageStore} / {@link RoleContextVersionManager}：角色上下文</li>
 *     <li>{@link RequirementContextRetrievalRecorder}：项目级 RAG 检索录制（可选）</li>
 *     <li>{@link RequirementExecutionProfileResolverPort}：执行 Profile 解析（可选）</li>
 *     <li>{@link HostVerificationPort}：Coding 成功后的宿主 BUILD/STATIC 门（可选；关闭时不调用）</li>
 *     <li>{@link RdProjectTokenBudgetService}：项目级 Token 额度（可选）</li>
 *     <li>{@link AgentWorkflowAlertSinkPort} / {@link WorkflowExperienceStore}：告警与经验沉淀</li>
 *     <li>{@link RagStreamTaskRegistry}：任务状态推进</li>
 *     <li>{@link SnowflakeIdGenerator}：产物 ID 生成</li>
 * </ul>
 *
 * <p>编排器不持有 {@link RequirementDeliveryEngine} 任何实例字段或私有方法；engine 仅
 * 通过 {@code stageOrchestrator.run(...)} 委托阶段循环。这是 RD-Bot V2 头等公民——
 * 后续可被 CodingBenchmark 等新场景直接复用，不需 engine 工厂。
 */
public class RequirementAgentStageOrchestrator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 单次回注的失败明细上限，防止巨型校验错误把 prompt 撑爆。 */
    private static final int MAX_FAILURE_FEEDBACK_CHARS = 4_000;
    /** 单个上游阶段随交接清单传导的环境备忘条数上限，防止 prompt 膨胀。 */
    private static final int MAX_ENVIRONMENT_NOTES = 8;
    /** 单个上游阶段随交接清单传导的 facts 条数上限，防止 prompt 膨胀。 */
    private static final int MAX_COMPACT_FACTS = 16;
    private static final Set<String> IMMUTABLE_ARTIFACT_TYPES = Set.of(
            RoleExecutionInputManifest.ARTIFACT_TYPE,
            "AGENT_STATE_EVENTS",
            "AGENT_STATE_SNAPSHOT",
            "RUNTIME_CONTEXT_MANIFEST",
            "RUNTIME_MEASUREMENT"
    );
    /** 角色预算份额越限时抛出，由上游归类为 TIMEOUT。上游不强制捕获，但接口包对外可观察。 */
    private static final double EPSILON = 1.0e-9d;

    private static final Comparator<AgentStageRun> STAGE_RUN_RECENCY = Comparator
            .comparingInt(AgentStageRun::attemptNo)
            .thenComparingLong(AgentStageRun::createTimeEpochMillis)
            .thenComparing(AgentStageRun::stageRunId);

    private final AgentStageRunStore stageRunStore;
    private final AgentStageArtifactStore artifactStore;
    private final RoleContextPackageStore roleContextPackageStore;
    private final RoleContextVersionManager roleContextVersionManager;
    // Retrieval recorder can be re-wired after construction by the engine (e.g. test path).
    private volatile RequirementContextRetrievalRecorder retrievalRecorder;
    // Resolver can be re-wired after construction by the engine (e.g. test path).
    // The orchestrator owns its own collaborators; it never reaches back into the engine.
    private volatile RequirementExecutionProfileResolverPort executionProfileResolver = RequirementExecutionProfileResolverPort.unavailable();
    private final RdProjectTokenBudgetService projectTokenBudgetService;
    private final AgentWorkflowAlertSinkPort alertSink;
    private final WorkflowExperienceStore experienceStore;
    private final RagStreamTaskRegistry taskRegistry;
    private final RequirementExecutorPort executor;
    private final SnowflakeIdGenerator idGenerator;
    private final ProviderFallbackPolicyEnforcer providerFallbackPolicy;
    private volatile ProviderSideEffectStatusPort providerSideEffectStatusPort =
            ProviderSideEffectStatusPort.unavailable();
    /**
     * Default is a succeeding no-op. Disabled plans never call it; enabled plans
     * must inject a real or test port via {@link #setHostVerificationPort}.
     */
    private volatile HostVerificationPort hostVerificationPort = HostVerificationPort.noop();

    public RequirementAgentStageOrchestrator(
            AgentStageRunStore stageRunStore,
            AgentStageArtifactStore artifactStore,
            RoleContextPackageStore roleContextPackageStore,
            RoleContextVersionManager roleContextVersionManager,
            RequirementContextRetrievalRecorder retrievalRecorder,
            RequirementExecutionProfileResolverPort executionProfileResolver,
            RdProjectTokenBudgetService projectTokenBudgetService,
            AgentWorkflowAlertSinkPort alertSink,
            WorkflowExperienceStore experienceStore,
            RagStreamTaskRegistry taskRegistry,
            RequirementExecutorPort executor,
            SnowflakeIdGenerator idGenerator
    ) {
        this.stageRunStore = stageRunStore;
        this.artifactStore = artifactStore;
        this.roleContextPackageStore = roleContextPackageStore;
        this.roleContextVersionManager = roleContextVersionManager;
        setRetrievalRecorder(retrievalRecorder);
        setExecutionProfileResolver(executionProfileResolver);
        this.projectTokenBudgetService = projectTokenBudgetService;
        this.alertSink = alertSink == null ? AgentWorkflowAlertSinkPort.noop() : alertSink;
        this.experienceStore = experienceStore == null ? WorkflowExperienceStore.noop() : experienceStore;
        this.taskRegistry = taskRegistry == null ? RagStreamTaskRegistry.inMemory() : taskRegistry;
        this.executor = executor == null ? RequirementExecutorPort.unavailable() : executor;
        this.idGenerator = idGenerator == null ? SnowflakeIdGenerator.defaultGenerator() : idGenerator;
        this.providerFallbackPolicy = new ProviderFallbackPolicyEnforcer();
    }

    /**
     * 重新设置执行画像解析器，便于 Spring 在构造完成后注入或测试在构造完成后覆盖。
     */
    public void setExecutionProfileResolver(RequirementExecutionProfileResolverPort executionProfileResolver) {
        this.executionProfileResolver = executionProfileResolver == null
                ? RequirementExecutionProfileResolverPort.unavailable()
                : executionProfileResolver;
    }

    /**
     * 重新设置 RAG 检索记录器，便于 Spring 在构造完成后注入或测试在构造完成后覆盖。
     */
    public void setRetrievalRecorder(RequirementContextRetrievalRecorder retrievalRecorder) {
        this.retrievalRecorder = retrievalRecorder;
    }

    /** Injects the Host-owned publication and provider-attempt safety resolver. */
    public void setProviderSideEffectStatusPort(ProviderSideEffectStatusPort providerSideEffectStatusPort) {
        this.providerSideEffectStatusPort = providerSideEffectStatusPort == null
                ? ProviderSideEffectStatusPort.unavailable()
                : providerSideEffectStatusPort;
    }

    /**
     * Injects the host BUILD/STATIC verifier used after Coding succeeds.
     *
     * <p>{@code null} fails closed with {@code QA_INFRASTRUCTURE} when the plan
     * requires verification. Disabled plans never call the port.
     *
     * @param hostVerificationPort adapter, test fake, or {@code null}
     */
    public void setHostVerificationPort(HostVerificationPort hostVerificationPort) {
        this.hostVerificationPort = hostVerificationPort;
    }

    /**
     * 按给定 plan 驱动多角色阶段链路，返回与原 {@code executeAgentStages} 字节级等价的
     * {@link RequirementExecutionResult}。
     */
    public RequirementExecutionResult run(
            AgentWorkflowPlan plan,
            RdRequirementTask task,
            List<TaskMaterial> materials,
            RequirementContextPackage context,
            RequirementPlan planSnapshot,
            RequirementPolicyDecision policyDecision,
            TaskRetryCheckpoint activeRetry
    ) {
        if (plan == null) {
            throw new IllegalArgumentException("AgentWorkflowPlan must not be null");
        }
        enforceBudgetLedger(plan, task);
        return runInternal(plan, task, materials, context, planSnapshot, policyDecision, activeRetry, "", "", 0, 0);
    }

    /**
     * 内部驱动方法：递归实现一次性 QA 修复回路与宿主验证廉价 Coding 返工。
     */
    private RequirementExecutionResult runInternal(
            AgentWorkflowPlan plan,
            RdRequirementTask task,
            List<TaskMaterial> materials,
            RequirementContextPackage context,
            RequirementPlan planSnapshot,
            RequirementPolicyDecision policyDecision,
            TaskRetryCheckpoint activeRetry,
            String qaRemediationResultJson,
            String hostVerifyFailureFeedback,
            int hostVerifyRemediationCount,
            int qaRemediationCount
    ) {
        // 多角色执行链路（按 plan.roles() 顺序）：对照原 executeAgentStages 行为，PENDING -> CONTEXT_READY
        // -> DISPATCHING -> RUNNING -> RESULT_COLLECTING -> VERIFYING -> SUCCEEDED。
        // 每一阶段独立失败分支：
        // RUNNING 中抛异常 -> FAILED_RETRYABLE
        // 发现 PR URL 违规 -> FAILED_NEEDS_HUMAN
        // 阶段失败/评审需人工 -> FAILED_NEEDS_HUMAN
        // 已终态（SUCCEEDED/FAILED_NEEDS_HUMAN/SKIPPED/CANCELLED）直接拒绝继续编排。
        List<String> stageResults = new ArrayList<>();
        String pullRequestUrl = "";
        String summary = "";
        String deliveryResultJson = "{}";
        for (AgentRole role : plan.roles()) {
            AgentStageRun stage = stageRun(task.taskId(), role);
            // 已成功阶段：直接复用历史产物，不再触发重跑，保持幂等与可恢复性。
            if (stage.status() == AgentStageStatus.SUCCEEDED) {
                stageResults.add(reusedStageResultJson(stage));
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
            // Persist complete role results for audit, but dispatch only a bounded handoff manifest.
            // This keeps Docker metadata and raw model JSON out of downstream retrieval and prompts.
            String upstreamResultJson = compactUpstreamHandoffJson(stageResults);
            if (role == AgentRole.CODING_AGENT && !qaRemediationResultJson.isBlank()) {
                upstreamResultJson = qaRemediationUpstreamJson(upstreamResultJson, qaRemediationResultJson);
            }
            RoleContextPackage roleContext;
            if (retrievalRecorder == null || !plan.retrievalEnabled()) {
                roleContext = latestRoleContext(task.taskId(), role);
            } else {
                List<TaskMaterial> retrievalMaterials = materialsWithReusableExperience(
                        task, materials, System.currentTimeMillis()
                );
                RetrievalOutcome retrieval = recordRetrieval(
                        task, retrievalMaterials, role, stage, upstreamResultJson
                );
                if (!retrieval.succeeded()) {
                    String reason = firstNonBlank(
                            retrieval.stopReason(), "RAG retrieval did not satisfy " + role + " evidence gate"
                    );
                    AgentStageRun failedStage = transitionStage(
                            stage, AgentStageStatus.FAILED_NEEDS_HUMAN,
                            "RAG_EVIDENCE_INSUFFICIENT", reason
                    );
                    publishStageAlert(
                            failedStage, AgentWorkflowAlertType.STAGE_FAILED_NEEDS_HUMAN,
                            reason
                    );
                    return RequirementExecutionResult.failure(
                            task.taskId(), reason,
                            aggregateAgentResultsJson("NEEDS_HUMAN", pullRequestUrl, stageResults)
                    );
                }
                roleContext = roleContextVersionManager.ensureLatestContext(
                        task, role, retrieval, System.currentTimeMillis()
                );
            }
            stage = bindRoleContext(stage, roleContext);
            // PENDING -> CONTEXT_READY：将该角色的上下文包绑定为本阶段执行上下文快照。
            stage = transitionStage(stage, AgentStageStatus.CONTEXT_READY, "", "");
            // CONTEXT_READY -> DISPATCHING：准备调度并解析冻结 profile，再构造角色提示词。
            stage = transitionStage(stage, AgentStageStatus.DISPATCHING, "", "");
            RequirementExecutionProfileResolution executionProfileResolution;
            try {
                executionProfileResolution = executionProfileResolver.resolve(
                        task, role, stage.stageRunId(), stage.attemptNo()
                );
            } catch (RuntimeException exception) {
                boolean retryable = profileResolutionFailureIsRetryable(exception);
                AgentStageStatus failureStatus = retryable
                        ? AgentStageStatus.FAILED_RETRYABLE
                        : AgentStageStatus.FAILED_NEEDS_HUMAN;
                String category = profileResolutionFailureCategory(exception, retryable);
                String reason = safe(exception.getMessage());
                AgentStageRun failedStage = transitionStage(
                        stage,
                        failureStatus,
                        category,
                        reason
                );
                publishStageAlert(
                        failedStage,
                        retryable
                                ? AgentWorkflowAlertType.STAGE_FAILED_RETRYABLE
                                : AgentWorkflowAlertType.STAGE_FAILED_NEEDS_HUMAN,
                        reason.isBlank() ? category : reason
                );
                return RequirementExecutionResult.failure(
                        task.taskId(),
                        "agent execution profile resolution failed: " + role
                                + (reason.isBlank() ? "" : ": " + reason),
                        aggregateAgentResultsJson(
                                retryable ? "FAILED" : "NEEDS_HUMAN",
                                pullRequestUrl,
                                stageResults
                        )
                );
            }
            String previousFailure = previousFailureFeedbackSection(task.taskId(), role, stage.attemptNo());
            if (role == AgentRole.CODING_AGENT && !safe(hostVerifyFailureFeedback).isBlank()) {
                previousFailure = joinPromptSections(hostVerifyFailureFeedback, previousFailure);
            }
            String recoverySection = joinPromptSections(
                    recoveryPromptSection(activeRetry, role, roleContext),
                    previousFailure
            );
            String rolePrompt = buildAgentPrompt(
                    role,
                    task,
                    context,
                    planSnapshot,
                    policyDecision,
                    roleContext,
                    upstreamResultJson,
                    recoverySection,
                    executionProfileResolution
            );
            // DISPATCHING -> RUNNING：记录 prompt 快照后进入正式执行。
            stage = capturePromptArtifact(stage, rolePrompt);
            ManifestCapture manifestCapture = captureRoleExecutionInputManifest(
                    stage,
                    role,
                    task,
                    materials,
                    roleContext,
                    rolePrompt,
                    roleInstruction(role, executionProfileResolution) + "\n"
                            + roleOutputContract(role, executionProfileResolution),
                    upstreamResultJson,
                    executionProfileResolution,
                    recoverySection,
                    activeRetry
            );
            stage = manifestCapture.stage();
            stage = transitionStage(stage, AgentStageStatus.RUNNING, "", "");
            RequirementExecutionResult roleResult;
            try {
                roleResult = executeRole(
                        task,
                        materials,
                        role,
                        rolePrompt,
                        roleContext,
                        upstreamResultJson,
                        stage,
                        executionProfileResolution.snapshotId(),
                        manifestCapture.manifest()
                );
            } catch (RuntimeException exception) {
                // RUNNING -> FAILED_RETRYABLE：执行器抛出异常，先记录可重试失败并返回全链路失败。
                AgentStageRun failedStage = transitionStage(
                        stage,
                        AgentStageStatus.FAILED_RETRYABLE,
                        "AGENT_EXECUTOR_EXCEPTION",
                        exception.getMessage()
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
            // 结果产出：保存角色产物并补齐 provider 元数据；Host 门控拒绝不安全静默降级。
            stage = captureResultArtifact(stage, roleResult);
            stage = recordProviderMetadata(stage, roleResult, task);
            Optional<ProviderFallbackEvaluation> rejectedFallback =
                    rejectUnsafeProviderFallback(stage, role, roleResult, task);
            if (rejectedFallback.isPresent()) {
                ProviderFallbackEvaluation evaluation = rejectedFallback.get();
                ProviderFallbackDecision decision = evaluation.decision();
                String reason = "host rejected provider fallback: " + decision.name()
                        + " (" + evaluation.reason() + ")";
                AgentStageRun failedStage = transitionStage(
                        stage,
                        AgentStageStatus.FAILED_NEEDS_HUMAN,
                        "PROVIDER_FALLBACK_POLICY",
                        reason
                );
                publishStageAlert(
                        failedStage,
                        AgentWorkflowAlertType.STAGE_FAILED_NEEDS_HUMAN,
                        reason
                );
                stageResults.add(stageResultJson(role, roleResult));
                String aggregateStatus = decision == ProviderFallbackDecision.WAITING_POLICY
                        ? "WAITING_POLICY"
                        : "NEEDS_HUMAN";
                return RequirementExecutionResult.failure(
                        task.taskId(),
                        reason,
                        aggregateAgentResultsJson(aggregateStatus, pullRequestUrl, stageResults)
                );
            }
            stage = transitionStage(stage, AgentStageStatus.RESULT_COLLECTING, "", "");
            if (!roleResult.pullRequestUrl().isBlank()) {
                // 所有角色禁止在角色阶段直接返回 PR 地址：RESULT_COLLECTING -> FAILED_NEEDS_HUMAN（强制交由交付复核阶段）。
                String reason = "agent stage returned pullRequestUrl before delivery review: " + role;
                AgentStageRun failedStage = transitionStage(
                        stage,
                        AgentStageStatus.FAILED_NEEDS_HUMAN,
                        "AGENT_PR_POLICY_VIOLATION",
                        reason
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
                AgentStageRun failedStage = transitionStage(
                        stage,
                        AgentStageStatus.FAILED_NEEDS_HUMAN,
                        "AGENT_RESULT_REJECTED",
                        reason
                );
                publishStageAlert(
                        failedStage,
                        role == AgentRole.QA_AGENT
                                ? AgentWorkflowAlertType.QA_FAILED
                                : AgentWorkflowAlertType.STAGE_FAILED_NEEDS_HUMAN,
                        reason
                );
                stageResults.add(stageResultJson(role, roleResult));
                if (role == AgentRole.QA_AGENT
                        && plan.qaRemediationAllowed()
                        && qaRemediationCount < plan.qaMaxRemediationPasses()
                        && isCodingRemediationRequested(roleResult.resultJson())) {
                    List<AgentStageRun> remediationStages = createQaRemediationAttempts(task.taskId());
                    if (!remediationStages.isEmpty()) {
                        publishQaRemediationStarted(failedStage, remediationStages, roleResult.resultJson());
                        // spec §5.2：一次性修复回路。深度上限由 plan.qaMaxRemediationPasses 守门（默认 1）。
                        return runInternal(
                                plan,
                                task,
                                materials,
                                context,
                                planSnapshot,
                                policyDecision,
                                activeRetry,
                                roleResult.resultJson(),
                                hostVerifyFailureFeedback,
                                hostVerifyRemediationCount,
                                qaRemediationCount + 1
                        );
                    }
                }
                // 任意角色 FAILED_NEEDS_HUMAN 都必须聚合为 NEEDS_HUMAN，
                // 否则上层会把任务误标成 REJECTED（CP-06）。
                return RequirementExecutionResult.failure(
                        task.taskId(),
                        role + " failed: " + reason,
                        aggregateAgentResultsJson("NEEDS_HUMAN", pullRequestUrl, stageResults)
                );
            }
            if (role == AgentRole.REQUIREMENT_REVIEWER) {
                // 需求评审角色完成后，补充一次业务门控：NEED/HUMAN 或异常状态 -> FAILED_NEEDS_HUMAN（不进入下一角色）。
                ReviewGateDecision reviewGateDecision = requirementReviewGateDecision(roleResult);
                if (reviewGateDecision.needsHuman()) {
                    AgentStageRun failedStage = transitionStage(
                            stage,
                            AgentStageStatus.FAILED_NEEDS_HUMAN,
                            "REQUIREMENT_REVIEW_NEEDS_HUMAN",
                            reviewGateDecision.reason()
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
                TokenBudgetEvaluation budgetEvaluation = evaluateTokenBudget(task, roleResult);
                if (!budgetEvaluation.valid()) {
                    String reason = "invalid requirement review budget estimate: " + budgetEvaluation.invalidReason();
                    AgentStageRun failedStage = transitionStage(
                            stage,
                            AgentStageStatus.FAILED_NEEDS_HUMAN,
                            "REQUIREMENT_REVIEW_BUDGET_INVALID",
                            reason
                    );
                    publishStageAlert(failedStage, AgentWorkflowAlertType.STAGE_FAILED_NEEDS_HUMAN, reason);
                    stageResults.add(stageResultJson(role, roleResult));
                    return RequirementExecutionResult.failure(
                            task.taskId(),
                            reason,
                            aggregateAgentResultsJson("NEEDS_HUMAN", pullRequestUrl, stageResults)
                    );
                }
                // 保持原 executeAgentStages 行为：预算快照需要附在 stage run 上后进入成功态。
                stage = attachReviewResultJson(stage, budgetEvaluation.snapshotJson());
                // RESULT_COLLECTING -> VERIFYING -> SUCCEEDED：单角色执行成功后，进入阶段验收并标记完成。
                stage = transitionStage(stage, AgentStageStatus.VERIFYING, "", "");
                stage = transitionStage(stage, AgentStageStatus.SUCCEEDED, "", "");
                stageResults.add(stageResultJson(role, roleResult));
                captureExperience(stage, roleResult, experienceTypeForRole(role));
                if (budgetEvaluation.exceedsEffectiveBudget()) {
                    markRequirementWaitingApprovalAndAlert(task, budgetEvaluation.snapshotJson());
                    return RequirementExecutionResult.success(
                            task.taskId(),
                            "token budget approval required",
                            "",
                            budgetEvaluation.snapshotJson()
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
            if (role != AgentRole.REQUIREMENT_REVIEWER) {
                // RESULT_COLLECTING -> VERIFYING -> SUCCEEDED：单角色执行成功后，进入阶段验收并标记完成。
                stage = transitionStage(stage, AgentStageStatus.VERIFYING, "", "");
                stage = transitionStage(stage, AgentStageStatus.SUCCEEDED, "", "");
                stageResults.add(stageResultJson(role, roleResult));
                captureExperience(stage, roleResult, experienceTypeForRole(role));
            }
            // Coding 成功后先过宿主 BUILD/STATIC；未成功不得派发 QA。
            if (role == AgentRole.CODING_AGENT && plan.hostVerifyRemediationAllowed()) {
                RequirementExecutionResult hostVerifyGate = gateQaBehindHostVerification(
                        plan,
                        task,
                        materials,
                        context,
                        planSnapshot,
                        policyDecision,
                        activeRetry,
                        qaRemediationResultJson,
                        hostVerifyRemediationCount,
                        qaRemediationCount,
                        stage,
                        pullRequestUrl,
                        stageResults
                );
                if (hostVerifyGate != null) {
                    return hostVerifyGate;
                }
            }
        }
        // 全部角色 SUCCEEDED：聚合为交付执行最终结果并返回，交付层将进入 PR 复核与发布。
        return RequirementExecutionResult.success(
                task.taskId(),
                summary,
                pullRequestUrl,
                mergeDeliveryResultJson(deliveryResultJson, pullRequestUrl, stageResults)
        );
    }

    // ===== 私有助手方法（从 RequirementDeliveryEngine 迁出） =====

    /** Token 预算评估结果：含预算估算是否有效、是否超过有效额度、快照 JSON。 */
    public record TokenBudgetEvaluation(
            boolean valid,
            String invalidReason,
            String snapshotJson,
            boolean exceedsEffectiveBudget
    ) {
    }

    /** 需求评审角色业务门控的轻量视图。 */
    public record ReviewGateDecision(boolean needsHuman, String reason) {
    }

    /** 角色预算份额越限时抛出，由上游归类为 TIMEOUT。 */
    public static final class BudgetExceededException extends RuntimeException {
        private final String taskId;

        public BudgetExceededException(String taskId, String message) {
            super(message);
            this.taskId = taskId == null ? "" : taskId;
        }

        public String taskId() {
            return taskId;
        }
    }

    /** 校验预算账本：角色支出比例不允许超过预算份额。A 不需要的角色比例保持不可用，不回流。 */
    private static void enforceBudgetLedger(AgentWorkflowPlan plan, RdRequirementTask task) {
        double sum = 0d;
        for (Map.Entry<AgentRole, Double> entry : plan.budgetLedger().entrySet()) {
            if (entry.getValue() == null || entry.getValue() < 0d) {
                throw new BudgetExceededException(task.taskId(),
                        "AgentWorkflowPlan share must be non-negative for role " + entry.getKey());
            }
            sum += entry.getValue();
        }
        if (sum > AgentWorkflowPlan.MAX_BUDGET_SUM + EPSILON) {
            throw new BudgetExceededException(task.taskId(),
                    "AgentWorkflowPlan budgetLedger sum exceeds 1.0: " + sum);
        }
    }

    private AgentStageRun stageRun(String taskId, AgentRole role) {
        return stageRunStore.listByTask(taskId).stream()
                .filter(stage -> stage.role() == role)
                .max(STAGE_RUN_RECENCY)
                .orElseThrow(() -> new IllegalStateException("agent stage run missing: " + taskId + " " + role));
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

    private record ManifestCapture(AgentStageRun stage, RoleExecutionInputManifest manifest) {
    }

    private ManifestCapture captureRoleExecutionInputManifest(
            AgentStageRun stage,
            AgentRole role,
            RdRequirementTask task,
            List<TaskMaterial> materials,
            RoleContextPackage roleContext,
            String rolePrompt,
            String roleContractText,
            String upstreamResultJson,
            RequirementExecutionProfileResolution executionProfileResolution,
            String recoveryContent,
            TaskRetryCheckpoint recoveryCheckpoint
    ) {
        boolean alreadySaved = artifactStore.listByTask(stage.taskId()).stream()
                .anyMatch(artifact -> artifact.stageRunId().equals(stage.stageRunId())
                        && RoleExecutionInputManifest.ARTIFACT_TYPE.equals(artifact.artifactType()));
        if (alreadySaved) {
            return new ManifestCapture(stage, null);
        }
        String inputManifestArtifactId = idGenerator.nextIdString();
        String runtimeManifestArtifactId = idGenerator.nextIdString();
        List<String> expectedArtifactIds = new ArrayList<>();
        expectedArtifactIds.add(inputManifestArtifactId);
        expectedArtifactIds.add(runtimeManifestArtifactId);
        if (!stage.promptArtifactId().isBlank()) {
            expectedArtifactIds.add(stage.promptArtifactId());
        }
        RoleExecutionInputManifest manifest = RoleExecutionInputManifestBuilder.build(
                stage,
                role,
                task,
                materials,
                roleContext,
                rolePrompt,
                roleContractText,
                upstreamResultJson,
                executionProfileResolution,
                recoveryContent,
                recoveryCheckpoint,
                List.copyOf(expectedArtifactIds),
                roleContextVersionManager
        );
        String manifestJson = manifest.canonicalJson();
        long now = System.currentTimeMillis();
        persistStageArtifact(new AgentStageArtifact(
                inputManifestArtifactId,
                stage.stageRunId(),
                stage.taskId(),
                stage.role(),
                RoleExecutionInputManifest.ARTIFACT_TYPE,
                artifactUri(stage, "role-execution-input-manifest"),
                stage.role().name() + " role execution input manifest",
                contentPreview(manifestJson),
                manifest.manifestHash(),
                """
                        {"taskId":%s,"stageRunId":%s,"role":%s,"artifactType":%s,"schemaVersion":%d,"manifestHash":%s,"contentLength":%d,"runtimeContextManifestArtifactId":%s}
                        """.formatted(
                        json(stage.taskId()),
                        json(stage.stageRunId()),
                        json(stage.role().name()),
                        json(RoleExecutionInputManifest.ARTIFACT_TYPE),
                        manifest.schemaVersion(),
                        json(manifest.manifestHash()),
                        manifestJson.length(),
                        json(runtimeManifestArtifactId)
                ).strip(),
                now
        ));
        return new ManifestCapture(stage, manifest);
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
                persistStageArtifact(new AgentStageArtifact(
                        idGenerator.nextIdString(),
                        stage.stageRunId(),
                        stage.taskId(),
                        stage.role(),
                        artifactType,
                        uri.isBlank() ? artifactUri(stage, artifactType.toLowerCase(Locale.ROOT)) : uri,
                        firstNonBlank(text(item.path("summary")), artifactType + " artifact"),
                        content,
                        stageArtifactContentHash(item, content, uri),
                        stageArtifactMetadata(stage, artifactType, item),
                        now + (++index)
                ));
            }
        } catch (JsonProcessingException ignored) {
            // RESULT_JSON remains persisted; malformed auxiliary artifact metadata must not hide the primary result.
        }
    }

    private void persistStageArtifact(AgentStageArtifact artifact) {
        if (IMMUTABLE_ARTIFACT_TYPES.contains(artifact.artifactType())) {
            artifactStore.saveImmutable(artifact);
            return;
        }
        artifactStore.save(artifact);
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

    private String stageArtifactContentHash(JsonNode item, String content, String uri) {
        String executorSha256 = text(item.path("metadataJson").path("sha256"));
        String normalized = executorSha256.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("sha256:")) {
            normalized = normalized.substring("sha256:".length());
        }
        if (normalized.matches("[0-9a-f]{64}")) {
            return "sha256:" + normalized;
        }
        return sha256(content.isBlank() ? uri : content);
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

    private AgentStageRun recordProviderMetadata(
            AgentStageRun stage,
            RequirementExecutionResult result,
            RdRequirementTask task
    ) {
        ProviderMetadata metadata = providerMetadata(result.resultJson());
        if (metadata.isEmpty()) {
            return stage;
        }
        AgentStageRun updatedStage = stageRunStore.save(stage.withProviderMetadata(
                metadata.providerName(),
                metadata.providerAttemptsJson(),
                System.currentTimeMillis()
        ));
        publishProviderFallbackAlert(updatedStage, metadata, task);
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
            SafetyMetadata safetyMetadata = providerFallbackSafetyMetadata(root, dockerMetadata);
            return new ProviderMetadata(
                    providerName,
                    providerAttemptsJson,
                    safetyMetadata.safety(),
                    safetyMetadata.declared(),
                    safetyMetadata.hostOwned()
            );
        } catch (JsonProcessingException exception) {
            return ProviderMetadata.empty();
        }
    }

    private void publishProviderFallbackAlert(
            AgentStageRun stage,
            ProviderMetadata metadata,
            RdRequirementTask task
    ) {
        ProviderFallbackEvidence fallback = providerFallbackEvidence(metadata);
        if (fallback.isEmpty()) {
            return;
        }
        ProviderFallbackEvaluation evaluation = providerFallbackPolicy.evaluateWithReason(
                stage.role(),
                fallback.failedProvider(),
                fallback.failedStatus(),
                fallback.activeProvider(),
                providerFallbackSafety(stage, metadata),
                providerWorkRisk(task, stage.role())
        );
        alertSink.publish(new AgentWorkflowAlert(
                stage.taskId(),
                stage.stageRunId(),
                AgentWorkflowAlertType.PROVIDER_FALLBACK,
                "provider fallback: " + fallback.failedProvider()
                        + " " + fallback.failedStatus()
                        + " -> " + fallback.activeProvider()
                        + " decision=" + evaluation.decision().name()
                        + " reason=" + evaluation.reason(),
                Map.of(
                        "role", stage.role().name(),
                        "status", stage.status().name(),
                        "attemptNo", Integer.toString(stage.attemptNo()),
                        "failedProvider", fallback.failedProvider(),
                        "failedStatus", fallback.failedStatus(),
                        "activeProvider", fallback.activeProvider(),
                        "hostDecision", evaluation.decision().name(),
                        "decisionReason", evaluation.reason(),
                        "safetyState", providerFallbackSafety(stage, metadata).state().name()
                ),
                System.currentTimeMillis()
        ));
    }

    /**
     * When the executor already switched providers, Host re-validates capability/risk.
     * Unsafe silent degradation is rejected (WAITING_POLICY / NEEDS_HUMAN).
     */
    private Optional<ProviderFallbackEvaluation> rejectUnsafeProviderFallback(
            AgentStageRun stage,
            AgentRole role,
            RequirementExecutionResult result,
            RdRequirementTask task
    ) {
        ProviderMetadata metadata = providerMetadata(result == null ? "" : result.resultJson());
        ProviderFallbackEvidence fallback = providerFallbackEvidence(metadata);
        if (fallback.isEmpty()) {
            return Optional.empty();
        }
        ProviderFallbackEvaluation evaluation = providerFallbackPolicy.evaluateWithReason(
                role,
                fallback.failedProvider(),
                fallback.failedStatus(),
                fallback.activeProvider(),
                providerFallbackSafety(stage, metadata),
                providerWorkRisk(task, role)
        );
        if (evaluation.allowed()) {
            return Optional.empty();
        }
        return Optional.of(evaluation);
    }

    private static ProviderWorkRisk providerWorkRisk(RdRequirementTask task, AgentRole role) {
        ProviderWorkRisk roleRisk = ProviderCapabilityCatalog.workRiskForRole(role);
        if (task == null || roleRisk == ProviderWorkRisk.GENERATION_ONLY) {
            return roleRisk;
        }
        String taskText = String.join(" ",
                safe(task.title()),
                safe(task.expectedResult()),
                safe(task.acceptanceCriteriaJson())
        ).toLowerCase(Locale.ROOT);
        String[] highRiskMarkers = {
                "authentication", "authorization", "permission", "security", "encryption", "privacy",
                "payment", "database schema", "schema migration", "migration", "credential", "secret",
                "认证", "鉴权", "授权", "权限", "安全", "加密", "隐私", "支付", "数据库结构", "迁移", "密钥"
        };
        for (String marker : highRiskMarkers) {
            if (taskText.contains(marker)) {
                return ProviderWorkRisk.HIGH_RISK;
            }
        }
        return roleRisk;
    }

    /**
     * Resolves host-owned side-effect evidence for a stage provider switch.
     *
     * <p>The executor evidence is resolved again through a Host-owned port. A
     * stage id, attempt number, or Agent-declared JSON is never proof that a
     * Provider switch used a clean workspace or that remote effects are settled.
     *
     * @param stage    current stage attempt
     * @param metadata provider result metadata
     * @return side-effect safety evidence
     */
    private ProviderFallbackSideEffectSafety providerFallbackSafety(
            AgentStageRun stage,
            ProviderMetadata metadata
    ) {
        if (stage == null) {
            return ProviderFallbackSideEffectSafety.unknown(
                    "host-owned clean attempt evidence is missing"
            );
        }
        ProviderFallbackSideEffectSafety executorEvidence = metadata == null
                ? ProviderFallbackSideEffectSafety.unknown(
                        "executor-owned provider-attempt evidence is missing"
                )
                : metadata.sideEffectSafety();
        if (metadata != null
                && !metadata.sideEffectSafetyHostOwned()
                && executorEvidence.isExplicitlySafe()) {
            executorEvidence = ProviderFallbackSideEffectSafety.unknown(
                    "host-owned clean attempt evidence is missing"
            );
        }
        if (metadata != null
                && metadata.sideEffectSafetyDeclared()
                && !metadata.sideEffectSafetyHostOwned()
                && !executorEvidence.isExplicitlySafe()) {
            // Untrusted output may make the decision stricter, never more permissive.
            return executorEvidence;
        }
        ProviderFallbackSideEffectSafety resolved = providerSideEffectStatusPort.resolve(
                new ProviderSideEffectStatusPort.Request(
                        stage.taskId(),
                        stage.stageRunId(),
                        stage.idempotencyKey(),
                        stage.attemptNo(),
                        stage.role(),
                        executorEvidence
                )
        );
        return resolved == null
                ? ProviderFallbackSideEffectSafety.unknown(
                        "host side-effect resolver returned no decision"
                )
                : resolved;
    }

    private SafetyMetadata providerFallbackSafetyMetadata(JsonNode root, JsonNode dockerMetadata) {
        JsonNode hostSafetyNode = firstObject(root.path("hostProviderFallbackSafety"));
        if (hostSafetyNode != null) {
            return parsedSafetyMetadata(hostSafetyNode, true);
        }
        JsonNode safetyNode = firstObject(
                root.path("providerFallbackSafety"),
                root.path("sideEffectSafety"),
                dockerMetadata.path("sideEffectSafety")
        );
        if (safetyNode == null) {
            return SafetyMetadata.undeclared();
        }
        return parsedSafetyMetadata(safetyNode, false);
    }

    private SafetyMetadata parsedSafetyMetadata(JsonNode safetyNode, boolean hostOwned) {
        ProviderFallbackSideEffectSafety.State state =
                ProviderFallbackSideEffectSafety.parseState(text(safetyNode.path("state")));
        String reason = firstNonBlank(
                text(safetyNode.path("reason")),
                "provider result declared side-effect fallback state " + state.name()
        );
        ProviderFallbackSideEffectSafety safety = new ProviderFallbackSideEffectSafety(
                state,
                firstNonBlank(
                        text(safetyNode.path("operationId")),
                        text(safetyNode.path("operation"))
                ),
                firstNonBlank(
                        text(safetyNode.path("attemptId")),
                        text(safetyNode.path("attemptMarker"))
                ),
                safetyNode.path("outputReset").asBoolean(false),
                reason
        );
        return new SafetyMetadata(safety, true, hostOwned);
    }

    private JsonNode firstObject(JsonNode... candidates) {
        if (candidates == null) {
            return null;
        }
        for (JsonNode candidate : candidates) {
            if (candidate != null && candidate.isObject()) {
                return candidate;
            }
        }
        return null;
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

    private AgentStageRun bindRoleContext(AgentStageRun stage, RoleContextPackage roleContext) {
        if (stage.contextPackageId().equals(roleContext.packageId())) {
            return stage;
        }
        return stageRunStore.save(stage.withContextPackageId(roleContext.packageId(), System.currentTimeMillis()));
    }

    private AgentStageRun attachReviewResultJson(AgentStageRun stage, String snapshotJson) {
        return stageRunStore.save(stage.withReviewResultJson(snapshotJson, System.currentTimeMillis()));
    }

    private RoleContextPackage latestRoleContext(String taskId, AgentRole role) {
        List<RoleContextPackage> packages = roleContextPackageStore.listByTaskAndRole(taskId, role.name());
        if (packages.isEmpty()) {
            throw new IllegalStateException("role context package missing: " + taskId + " " + role);
        }
        return packages.stream()
                .max(Comparator.comparingInt(RoleContextPackage::packageVersion)
                        .thenComparingLong(RoleContextPackage::createdAtEpochMillis)
                        .thenComparing(RoleContextPackage::packageId))
                .orElseThrow();
    }

    private RetrievalOutcome recordRetrieval(
            RdRequirementTask task,
            List<TaskMaterial> materials,
            AgentRole role,
            AgentStageRun stage,
            String upstreamResultJson
    ) {
        if (retrievalRecorder == null) {
            return new RetrievalOutcome(
                    "",
                    RetrievalRunStatus.SUCCEEDED,
                    RetrievalConsumerType.AGENT_ROLE,
                    role,
                    stage.stageRunId(),
                    List.of(),
                    null,
                    "",
                    List.of(),
                    List.of(),
                    ""
            );
        }
        return retrievalRecorder.recordOnly(
                task, materials, RetrievalConsumerType.AGENT_ROLE, role,
                stage.stageRunId(), upstreamResultJson
        );
    }

    private boolean profileResolutionFailureIsRetryable(RuntimeException exception) {
        String message = safe(exception == null ? "" : exception.getMessage()).toUpperCase(Locale.ROOT);
        return message.contains("AGENT_RUNTIME_SNAPSHOT_UNAVAILABLE");
    }

    private String profileResolutionFailureCategory(RuntimeException exception, boolean retryable) {
        String message = safe(exception == null ? "" : exception.getMessage());
        String upper = message.toUpperCase(Locale.ROOT);
        if (upper.contains("AGENT_RUNTIME_SNAPSHOT_CORRUPT")) {
            return "AGENT_RUNTIME_SNAPSHOT_CORRUPT";
        }
        if (upper.contains("AGENT_RUNTIME_SNAPSHOT_UNAVAILABLE")) {
            return "AGENT_RUNTIME_SNAPSHOT_UNAVAILABLE";
        }
        if (upper.contains("AGENT_RUNTIME_PROFILE_INVALID")) {
            return "AGENT_RUNTIME_PROFILE_INVALID";
        }
        return retryable ? "AGENT_RUNTIME_SNAPSHOT_UNAVAILABLE" : "AGENT_RUNTIME_PROFILE_INVALID";
    }

    private String buildAgentPrompt(
            AgentRole role,
            RdRequirementTask task,
            RequirementContextPackage context,
            RequirementPlan planSnapshot,
            RequirementPolicyDecision policyDecision,
            RoleContextPackage roleContext,
            String upstreamResultJson,
            String recoveryPromptSection,
            RequirementExecutionProfileResolution executionProfileResolution
    ) {
        return """
                你是 RD-Bot 多 Agent 需求交付链路中的 %s。

                # 当前职责
                %s

                # 角色上下文
                %s

                %s

                # 上游交接摘要
                %s

                # Token 预算估算证据
                %s

                %s

                %s

                %s

                # 当前角色输出 JSON 协议
                %s
                """.formatted(
                role.name(),
                roleInstruction(role, executionProfileResolution),
                roleContextJson(roleContext),
                repositoryDiscoveryPromptSection(roleContext),
                upstreamHandoffPromptSection(role, upstreamResultJson),
                budgetEstimatePromptSection(role, task),
                lightweightDeliveryPromptSection(role, task),
                recoveryPromptSection,
                buildBasePrompt(
                        role,
                        task,
                        context,
                        planSnapshot,
                        policyDecision
                ),
                roleOutputContract(role, executionProfileResolution)
        ).strip();
    }

    private String buildBasePrompt(
            AgentRole role,
            RdRequirementTask task,
            RequirementContextPackage context,
            RequirementPlan plan,
            RequirementPolicyDecision policyDecision
    ) {
        return """
                %s

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

                # 输出要求
                %s
                """.formatted(
                basePromptMission(role),
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
                basePromptOutputRequirements(role)
        ).strip();
    }

    private String basePromptMission(AgentRole role) {
        return switch (role) {
            case CODING_AGENT ->
                    "你是 RD-Bot 的需求交付执行器。请在受控仓库中完成需求编码、测试，并准备可审查 PR。";
            case QA_AGENT ->
                    "你是 RD-Bot 的 QA 验证执行器。请在受控仓库中基于上游交付候选执行真实验收与回归验证，采集可审计证据。";
            default ->
                    "你是 RD-Bot 的需求交付链路参与者。请在本角色职责范围内完成任务分析、评审或方案设计；不要修改仓库代码或创建 PR。";
        };
    }

    private String basePromptOutputRequirements(AgentRole role) {
        return switch (role) {
            case CODING_AGENT -> """
                    - 修改代码后运行必要的测试或构建命令。
                    - 返回结构化 JSON，status 使用 SUCCESS/FAILED/NEED_INFO/UNSAFE。
                    - 成功时提供 summary、changedFiles、testSummary 和 prBody。
                    """.strip();
            case QA_AGENT -> """
                    - 执行真实验证命令并采集 qa-evidence/ 下的可审计证据。
                    - 返回结构化 JSON，status 使用 PASSED/FAILED/SKIPPED。
                    - 每条 acceptanceResults 必须引用真实证据文件；不要准备或创建 PR。
                    """.strip();
            default -> """
                    - 返回结构化 JSON，严格遵循当前角色输出协议。
                    - 不要修改仓库代码、不要运行交付级构建/测试、不要创建 PR。
                    """.strip();
        };
    }

    private RequirementExecutionResult executeRole(
            RdRequirementTask task,
            List<TaskMaterial> materials,
            AgentRole role,
            String rolePrompt,
            RoleContextPackage roleContext,
            String upstreamResultJson,
            AgentStageRun stage,
            String executionProfileSnapshotId,
            RoleExecutionInputManifest inputManifest
    ) {
        String inputManifestHash = inputManifest == null ? "" : inputManifest.manifestHash();
        String contextPolicyHash = inputManifest == null
                ? ""
                : inputManifest.runtimeContextPolicy().policyHash();
        String inputManifestJson = inputManifest == null ? "" : inputManifest.canonicalJson();
        String contextPolicyJson = inputManifest == null
                ? ""
                : AgentManifestCanonicalJson.canonicalJson(inputManifest.runtimeContextPolicy());
        return normalizeResult(
                task.taskId(),
                executor.execute(new RequirementExecutionRequest(
                        task.taskId(),
                        task,
                        materials,
                        rolePrompt,
                        role,
                        roleContextJson(roleContext),
                        false,
                        upstreamResultJson,
                        stage.stageRunId(),
                        executionProfileSnapshotId,
                        inputManifestHash,
                        contextPolicyHash,
                        inputManifestJson,
                        contextPolicyJson
                ))
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

    private String repositoryDiscoveryPromptSection(RoleContextPackage roleContext) {
        boolean required = roleContext != null && roleContext.evidence().stream().anyMatch(evidence ->
                "REPOSITORY_DISCOVERY".equals(evidence.sourceType())
                        && "REPOSITORY_DISCOVERY".equals(evidence.requiredEvidenceType())
                        && evidence.sourceUri().startsWith("repo://")
        );
        if (!required) {
            return "";
        }
        return """
                # 受限仓库发现
                角色特定检索证据暂缺。请在形成方案、修改代码或执行 QA 前完成受限仓库发现：
                - 仅在当前 `/work/repo` 工作区内操作；最多 12 条只读命令、检查最多 20 个文件、读取最多 64 KiB。
                - 仅使用 `rg`、`find`、`sed`、`git grep` 等普通仓库工具定位文件、符号和测试入口；不要联网搜索。
                - 不访问基准参考答案、隐藏检查或仓库外路径，也不要切换提交、分支、提交代码或创建 PR。
                - 在当前角色的结构化结果和直接下游交接中记录已定位的文件、符号、测试入口与尚存不确定性。
                """.strip();
    }

    private String evidenceJson(List<com.wish.rd.rag.context.model.RoleContextEvidence> evidence) {
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

    private String compactUpstreamHandoffJson(List<String> stageResults) {
        List<Map<String, Object>> stages = new ArrayList<>();
        for (String rawStage : stageResults == null ? List.<String>of() : stageResults) {
            try {
                JsonNode stage = OBJECT_MAPPER.readTree(safe(rawStage));
                if (stage == null || !stage.isObject()) {
                    continue;
                }
                String role = safe(stage.path("role").asText(""));
                if (role.isBlank()) {
                    continue;
                }
                JsonNode roleResult = embeddedRoleResult(stage.path("resultJson"));
                Map<String, Object> compact = new LinkedHashMap<>();
                compact.put("role", role);
                compact.put("success", stage.path("success").asBoolean(false));
                String status = firstNonBlank(roleResult.path("status").asText(""), stage.path("status").asText(""));
                if (!status.isBlank()) {
                    compact.put("status", compactText(status));
                }
                String summary = firstNonBlank(stage.path("summary").asText(""), roleResult.path("summary").asText(""));
                if (!summary.isBlank()) {
                    compact.put("summary", compactText(summary));
                }
                String errorMessage = firstNonBlank(
                        stage.path("errorMessage").asText(""), roleResult.path("errorMessage").asText("")
                );
                if (!errorMessage.isBlank()) {
                    compact.put("errorMessage", compactText(errorMessage));
                }
                List<String> environmentNotes = compactEnvironmentNotes(roleResult.path("environmentNotes"));
                if (environmentNotes.isEmpty()) {
                    environmentNotes = deriveEnvironmentNotesFromFacts(roleResult.path("facts"));
                }
                if (!environmentNotes.isEmpty()) {
                    compact.put("environmentNotes", environmentNotes);
                }
                List<Map<String, Object>> compactFacts = compactFacts(roleResult.path("facts"));
                if (!compactFacts.isEmpty()) {
                    compact.put("facts", compactFacts);
                }
                JsonNode handoff = roleResult.path("roleHandoff");
                if (!handoff.isObject()) {
                    handoff = stage.path("roleHandoff");
                }
                Map<String, Object> compactHandoff = compactHandoff(handoff, role);
                if (!compactHandoff.isEmpty()) {
                    compact.put("handoff", compactHandoff);
                }
                Map<String, Object> compactCandidatePatch = compactCandidatePatch(roleResult, role);
                if (compactCandidatePatch.isEmpty()) {
                    compactCandidatePatch = compactCandidatePatchManifest(stage.path("candidatePatch"), role);
                }
                if (!compactCandidatePatch.isEmpty()) {
                    compact.put("candidatePatch", compactCandidatePatch);
                }
                stages.add(Map.copyOf(compact));
            } catch (JsonProcessingException ignored) {
                // An unparseable legacy stage remains audited, but cannot be safely passed to the next model.
            }
        }
        return compactJson(Map.of("version", 1, "stages", List.copyOf(stages)));
    }

    private List<String> compactEnvironmentNotes(JsonNode environmentNotes) {
        if (environmentNotes == null || !environmentNotes.isArray()) {
            return List.of();
        }
        List<String> notes = new ArrayList<>();
        for (JsonNode note : environmentNotes) {
            if (!note.isTextual()) {
                continue;
            }
            String text = compactText(note.asText());
            if (text.isBlank()) {
                continue;
            }
            notes.add(text);
            if (notes.size() >= MAX_ENVIRONMENT_NOTES) {
                break;
            }
        }
        return List.copyOf(notes);
    }

    private List<String> deriveEnvironmentNotesFromFacts(JsonNode factsNode) {
        if (factsNode == null || !factsNode.isArray()) {
            return List.of();
        }
        List<String> notes = new ArrayList<>();
        for (JsonNode factNode : factsNode) {
            if (factNode == null || !factNode.isObject()) {
                continue;
            }
            if (!"OBSERVED".equalsIgnoreCase(safe(factNode.path("kind").asText("")))) {
                continue;
            }
            String statement = compactText(safe(factNode.path("statement").asText("")));
            if (statement.isBlank()) {
                continue;
            }
            notes.add(statement);
            if (notes.size() >= MAX_ENVIRONMENT_NOTES) {
                break;
            }
        }
        return List.copyOf(notes);
    }

    private List<Map<String, Object>> compactFacts(JsonNode factsNode) {
        if (factsNode == null || !factsNode.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> facts = new ArrayList<>();
        for (JsonNode factNode : factsNode) {
            if (factNode == null || !factNode.isObject()) {
                continue;
            }
            Map<String, Object> compactFact = new LinkedHashMap<>();
            putCompactFactField(compactFact, "factId", factNode.path("factId"));
            putCompactFactField(compactFact, "kind", factNode.path("kind"));
            String statement = safe(factNode.path("statement").asText(""));
            if (!statement.isBlank()) {
                compactFact.put("statement", compactText(statement));
            }
            putCompactFactField(compactFact, "freshnessPolicy", factNode.path("freshnessPolicy"));
            putCompactFactField(compactFact, "freshnessStatus", factNode.path("freshnessStatus"));
            putCompactFactField(compactFact, "sourceArtifactId", factNode.path("sourceArtifactId"));
            putCompactFactField(compactFact, "sourceStageRunId", factNode.path("sourceStageRunId"));
            putCompactFactField(compactFact, "sourceToolCallId", factNode.path("sourceToolCallId"));
            putCompactFactField(compactFact, "repoRevision", factNode.path("repoRevision"));
            putCompactFactField(compactFact, "workspaceFingerprint", factNode.path("workspaceFingerprint"));
            putCompactFactField(compactFact, "observedAt", factNode.path("observedAt"));
            putCompactFactField(compactFact, "commandHash", factNode.path("commandHash"));
            if (!compactFact.isEmpty()) {
                facts.add(Map.copyOf(compactFact));
            }
            if (facts.size() >= MAX_COMPACT_FACTS) {
                break;
            }
        }
        return List.copyOf(facts);
    }

    private void putCompactFactField(Map<String, Object> target, String fieldName, JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return;
        }
        String text = safe(value.asText(""));
        if (!text.isBlank()) {
            target.put(fieldName, text);
        }
    }

    private JsonNode embeddedRoleResult(JsonNode rawResult) {
        if (rawResult == null || rawResult.isMissingNode() || rawResult.isNull()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        if (rawResult.isObject()) {
            return rawResult;
        }
        if (!rawResult.isTextual()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        try {
            JsonNode result = OBJECT_MAPPER.readTree(safe(rawResult.asText()));
            return result != null && result.isObject() ? result : OBJECT_MAPPER.createObjectNode();
        } catch (JsonProcessingException ignored) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    private Map<String, Object> compactHandoff(JsonNode handoff, String fallbackSourceRole) {
        if (handoff == null || !handoff.isObject()) {
            return Map.of();
        }
        String sourceRole = firstNonBlank(handoff.path("sourceRole").asText(""), fallbackSourceRole);
        String targetRole = safe(handoff.path("targetRole").asText(""));
        String artifactName = safe(handoff.path("artifactName").asText(""));
        String artifactUri = safe(handoff.path("artifactUri").asText(""));
        String sha256 = safe(handoff.path("sha256").asText(""));
        long bytes = handoff.path("bytes").asLong(-1L);
        if (sourceRole.isBlank()
                || targetRole.isBlank()
                || !"handoff/next.md".equals(artifactName)
                || !artifactUri.startsWith("s3://")
                || !sha256.matches("(?:sha256:)?[0-9a-fA-F]{64}")
                || bytes <= 0L) {
            return Map.of();
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("sourceRole", sourceRole);
        compact.put("targetRole", targetRole);
        compact.put("artifactName", artifactName);
        compact.put("artifactUri", artifactUri);
        compact.put("sha256", sha256);
        compact.put("bytes", bytes);
        String summary = safe(handoff.path("summary").asText(""));
        if (!summary.isBlank()) {
            compact.put("summary", compactText(summary));
        }
        return Map.copyOf(compact);
    }

    private Map<String, Object> compactCandidatePatch(JsonNode roleResult, String fallbackSourceRole) {
        if (!AgentRole.CODING_AGENT.name().equalsIgnoreCase(safe(fallbackSourceRole))
                || roleResult == null
                || !roleResult.path("stageArtifacts").isArray()) {
            return Map.of();
        }
        for (JsonNode artifact : roleResult.path("stageArtifacts")) {
            if (artifact == null || !artifact.isObject()
                    || !"PATCH_DIFF".equalsIgnoreCase(safe(artifact.path("type").asText("")))
                    || !"patch.diff".equals(safe(artifact.path("name").asText("")))) {
                continue;
            }
            JsonNode metadata = artifact.path("metadataJson");
            String artifactUri = safe(artifact.path("uri").asText(""));
            String sha256 = safe(metadata.path("sha256").asText(""));
            long bytes = metadata.path("bytes").asLong(-1L);
            if (!"true".equalsIgnoreCase(safe(metadata.path("candidatePatch").asText("")))
                    || !artifactUri.startsWith("s3://")
                    || !sha256.matches("(?:sha256:)?[0-9a-fA-F]{64}")
                    || bytes <= 0L
                    || bytes > 10L * 1024L * 1024L) {
                continue;
            }
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("sourceRole", AgentRole.CODING_AGENT.name());
            compact.put("targetRole", AgentRole.QA_AGENT.name());
            compact.put("artifactName", "patch.diff");
            compact.put("artifactUri", artifactUri);
            compact.put("sha256", sha256);
            compact.put("bytes", bytes);
            return Map.copyOf(compact);
        }
        return Map.of();
    }

    private Map<String, Object> compactCandidatePatchManifest(
            JsonNode candidatePatch,
            String fallbackSourceRole
    ) {
        if (candidatePatch == null || !candidatePatch.isObject()) {
            return Map.of();
        }
        String sourceRole = firstNonBlank(
                safe(candidatePatch.path("sourceRole").asText("")), safe(fallbackSourceRole)
        );
        String targetRole = safe(candidatePatch.path("targetRole").asText(""));
        String artifactName = safe(candidatePatch.path("artifactName").asText(""));
        String artifactUri = safe(candidatePatch.path("artifactUri").asText(""));
        String sha256 = safe(candidatePatch.path("sha256").asText(""));
        long bytes = candidatePatch.path("bytes").asLong(-1L);
        if (!AgentRole.CODING_AGENT.name().equalsIgnoreCase(sourceRole)
                || !AgentRole.QA_AGENT.name().equalsIgnoreCase(targetRole)
                || !"patch.diff".equals(artifactName)
                || !artifactUri.startsWith("s3://")
                || !sha256.matches("(?:sha256:)?[0-9a-fA-F]{64}")
                || bytes <= 0L
                || bytes > 10L * 1024L * 1024L) {
            return Map.of();
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("sourceRole", AgentRole.CODING_AGENT.name());
        compact.put("targetRole", AgentRole.QA_AGENT.name());
        compact.put("artifactName", "patch.diff");
        compact.put("artifactUri", artifactUri);
        compact.put("sha256", sha256);
        compact.put("bytes", bytes);
        return Map.copyOf(compact);
    }

    private String upstreamHandoffPromptSection(AgentRole role, String upstreamHandoffJson) {
        List<String> stageLines = new ArrayList<>();
        List<String> documentLines = new ArrayList<>();
        List<String> candidatePatchLines = new ArrayList<>();
        List<String> environmentNoteLines = new ArrayList<>();
        String remediation = "";
        try {
            JsonNode root = OBJECT_MAPPER.readTree(safe(upstreamHandoffJson));
            if (root != null && root.path("stages").isArray()) {
                for (JsonNode stage : root.path("stages")) {
                    String sourceRole = safe(stage.path("role").asText(""));
                    String status = firstNonBlank(stage.path("status").asText(""),
                            stage.path("success").asBoolean(false) ? "SUCCESS" : "");
                    String summary = safe(stage.path("summary").asText(""));
                    if (!sourceRole.isBlank()) {
                        stageLines.add("- " + sourceRole + ": "
                                + firstNonBlank(status, "COMPLETED")
                                + (summary.isBlank() ? "" : " - " + compactText(summary)));
                    }
                    for (JsonNode note : stage.path("environmentNotes")) {
                        if (note.isTextual() && !note.asText().isBlank()
                                && environmentNoteLines.size() < MAX_ENVIRONMENT_NOTES) {
                            environmentNoteLines.add("- [" + sourceRole + "] " + compactText(note.asText()));
                        }
                    }
                    JsonNode handoff = stage.path("handoff");
                    if (handoff.isObject() && role.name().equalsIgnoreCase(handoff.path("targetRole").asText(""))) {
                        String declaredSource = firstNonBlank(handoff.path("sourceRole").asText(""), sourceRole);
                        String localPath = "/work/input/attachments/handoff-"
                                + declaredSource.toLowerCase(Locale.ROOT) + ".md";
                        String summaryText = safe(handoff.path("summary").asText(""));
                        documentLines.add("- " + localPath + " (from " + declaredSource + ")"
                                + (summaryText.isBlank() ? "" : ": " + compactText(summaryText)));
                    }
                    JsonNode candidatePatch = stage.path("candidatePatch");
                    if (role == AgentRole.QA_AGENT && isVerifiedCandidatePatch(candidatePatch, sourceRole)) {
                        candidatePatchLines.add("- /work/input/attachments/candidate-patch.diff"
                                + " (from CODING_AGENT; platform-applied in this isolated QA checkout)");
                    }
                }
            }
            JsonNode qaRemediation = root == null ? null : root.path("qaRemediation");
            if (qaRemediation != null && qaRemediation.isObject()) {
                remediation = "\nQA remediation: " + firstNonBlank(
                        qaRemediation.path("failureCategory").asText(""),
                        qaRemediation.path("reason").asText(""),
                        "QA_CURRENT_OR_REGRESSION_FAILED"
                ) + "; " + compactText(qaRemediation.path("summary").asText(""));
            }
        } catch (JsonProcessingException ignored) {
            return "No usable upstream handoff was produced.";
        }
        String stageSummary = stageLines.isEmpty() ? "- No upstream role has completed." : String.join("\n", stageLines);
        String documents = documentLines.isEmpty()
                ? "- No direct handoff document is attached for this role."
                : "Attached verified handoff documents:\n" + String.join("\n", documentLines);
        String candidatePatches = candidatePatchLines.isEmpty()
                ? ""
                : "\nVerified candidate patches:\n" + String.join("\n", candidatePatchLines);
        String environmentNotes = environmentNoteLines.isEmpty()
                ? ""
                : "\n环境备忘（上游角色已实测验证，直接沿用，不要重复探测）:\n" + String.join("\n", environmentNoteLines);
        return (stageSummary + "\n" + documents + candidatePatches + environmentNotes + remediation).strip();
    }

    private boolean isVerifiedCandidatePatch(JsonNode candidatePatch, String fallbackSourceRole) {
        if (candidatePatch == null || !candidatePatch.isObject()) {
            return false;
        }
        String sourceRole = firstNonBlank(
                candidatePatch.path("sourceRole").asText(""), fallbackSourceRole
        );
        String artifactName = safe(candidatePatch.path("artifactName").asText(""));
        String artifactUri = safe(candidatePatch.path("artifactUri").asText(""));
        String sha256 = safe(candidatePatch.path("sha256").asText(""));
        long bytes = candidatePatch.path("bytes").asLong(-1L);
        return AgentRole.CODING_AGENT.name().equalsIgnoreCase(sourceRole)
                && AgentRole.QA_AGENT.name().equalsIgnoreCase(candidatePatch.path("targetRole").asText(""))
                && "patch.diff".equals(artifactName)
                && artifactUri.startsWith("s3://")
                && sha256.matches("(?:sha256:)?[0-9a-fA-F]{64}")
                && bytes > 0L
                && bytes <= 10L * 1024L * 1024L;
    }

    private String qaRemediationUpstreamJson(String compactUpstreamJson, String qaResultJson) {
        ObjectNode root;
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(safe(compactUpstreamJson));
            root = parsed instanceof ObjectNode objectNode ? objectNode.deepCopy() : OBJECT_MAPPER.createObjectNode();
        } catch (JsonProcessingException exception) {
            root = OBJECT_MAPPER.createObjectNode();
        }
        if (!root.path("stages").isArray()) {
            root.putArray("stages");
        }
        ObjectNode remediation = root.putObject("qaRemediation");
        remediation.put("reason", "QA_CURRENT_OR_REGRESSION_FAILED");
        try {
            JsonNode qa = OBJECT_MAPPER.readTree(safe(qaResultJson));
            if (qa != null && qa.isObject()) {
                remediation.put("status", compactText(qa.path("status").asText("")));
                remediation.put("failureCategory", compactText(qa.path("failureCategory").asText("")));
                remediation.put("retryRecommendation", compactText(qa.path("retryRecommendation").asText("")));
                remediation.put("summary", compactText(qa.path("summary").asText("")));
                List<String> evidenceIds = new ArrayList<>();
                String manifest = safe(qa.path("evidenceManifestArtifactId").asText(""));
                if (!manifest.isBlank()) {
                    evidenceIds.add(manifest);
                }
                if (qa.path("acceptanceResults").isArray()) {
                    for (JsonNode acceptance : qa.path("acceptanceResults")) {
                        String logArtifactId = safe(acceptance.path("logArtifactId").asText(""));
                        if (!logArtifactId.isBlank()) {
                            evidenceIds.add(logArtifactId);
                        }
                        if (acceptance.path("evidenceArtifactIds").isArray()) {
                            for (JsonNode evidence : acceptance.path("evidenceArtifactIds")) {
                                String evidenceId = safe(evidence.asText(""));
                                if (!evidenceId.isBlank()) {
                                    evidenceIds.add(evidenceId);
                                }
                            }
                        }
                    }
                }
                remediation.putPOJO("evidenceArtifactIds", List.copyOf(new LinkedHashSet<>(evidenceIds)));
            }
        } catch (JsonProcessingException ignored) {
            remediation.put("summary", "QA result could not be parsed; inspect the stage audit record.");
        }
        return compactJson(root);
    }

    private String compactJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{\"version\":1,\"stages\":[]}";
        }
    }

    private String compactText(String value) {
        String normalized = safe(value).replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ");
        return normalized.length() <= 1_200 ? normalized : normalized.substring(0, 1_200) + "...";
    }

    private boolean isCodingRemediationRequested(String qaResultJson) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(safe(qaResultJson));
            if (root == null || !root.isObject()) {
                return false;
            }
            String status = root.path("status").asText("").strip().toUpperCase(Locale.ROOT);
            String category = root.path("failureCategory").asText("").strip().toUpperCase(Locale.ROOT);
            String recommendation = root.path("retryRecommendation").asText("").strip().toUpperCase(Locale.ROOT);
            return "FAILED".equals(status)
                    && ("PRODUCT_DEFECT".equals(category) || "REGRESSION".equals(category))
                    && AgentRole.CODING_AGENT.name().equals(recommendation);
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private String reusedStageResultJson(AgentStageRun stage) {
        List<AgentStageArtifact> stageArtifacts = artifactStore.listByTask(stage.taskId()).stream()
                .filter(artifact -> artifact.stageRunId().equals(stage.stageRunId()))
                .toList();
        AgentStageArtifact resultArtifact = stageArtifacts.stream()
                .filter(artifact -> artifact.artifactId().equals(stage.resultArtifactId())
                        || "RESULT_JSON".equals(artifact.artifactType()))
                .max(Comparator.comparingLong(AgentStageArtifact::createdAtEpochMillis))
                .orElse(null);
        Map<String, Object> reused = new LinkedHashMap<>();
        reused.put("role", stage.role().name());
        reused.put("success", true);
        reused.put("reused", true);
        reused.put("status", "SUCCEEDED");
        reused.put("sourceStageRunId", stage.stageRunId());
        reused.put("sourceArtifactId", resultArtifact == null ? stage.resultArtifactId() : resultArtifact.artifactId());
        String resultPreview = resultArtifact == null ? "" : safe(resultArtifact.contentPreview());
        if (resultPreview.isBlank() || isJsonObject(resultPreview)) {
            reused.put("resultJson", resultPreview);
        } else {
            reused.put("resultJson", salvageTruncatedResultJson(resultPreview));
            reused.put("resultJsonTruncated", true);
        }
        Map<String, Object> handoff = persistedRoleHandoff(stage, stageArtifacts);
        if (!handoff.isEmpty()) {
            reused.put("roleHandoff", handoff);
        }
        Map<String, Object> candidatePatch = persistedCandidatePatch(stage, stageArtifacts);
        if (!candidatePatch.isEmpty()) {
            reused.put("candidatePatch", candidatePatch);
        }
        return compactJson(reused);
    }

    private boolean isJsonObject(String value) {
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(safe(value));
            return parsed != null && parsed.isObject();
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private String salvageTruncatedResultJson(String preview) {
        ObjectNode salvaged = OBJECT_MAPPER.createObjectNode();
        try (JsonParser parser = OBJECT_MAPPER.getFactory().createParser(safe(preview))) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return "";
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                salvaged.set(field, OBJECT_MAPPER.readTree(parser));
            }
        } catch (IOException truncatedTail) {
            // 截断处之后的内容不可解析，保留此前已完整读出的字段即可。
        }
        return salvaged.isEmpty() ? "" : compactJson(salvaged);
    }

    private Map<String, Object> persistedRoleHandoff(
            AgentStageRun stage,
            List<AgentStageArtifact> stageArtifacts
    ) {
        AgentRole targetRole = directDownstreamRole(stage.role());
        if (targetRole == null) {
            return Map.of();
        }
        return stageArtifacts.stream()
                .filter(artifact -> "HANDOFF_MARKDOWN".equals(artifact.artifactType()))
                .max(Comparator.comparingLong(AgentStageArtifact::createdAtEpochMillis))
                .map(artifact -> persistedRoleHandoff(stage.role(), targetRole, artifact))
                .orElseGet(Map::of);
    }

    private Map<String, Object> persistedRoleHandoff(
            AgentRole sourceRole,
            AgentRole targetRole,
            AgentStageArtifact artifact
    ) {
        if (artifact == null || !artifact.artifactUri().startsWith("s3://")) {
            return Map.of();
        }
        try {
            JsonNode metadata = OBJECT_MAPPER.readTree(artifact.metadataJson());
            if (metadata == null || !metadata.isObject()
                    || !"handoff/next.md".equals(safe(metadata.path("artifactName").asText("")))) {
                return Map.of();
            }
            String sha256 = firstNonBlank(
                    safe(metadata.path("sha256").asText("")), artifact.contentHash()
            );
            long bytes = metadata.path("bytes").canConvertToLong()
                    ? metadata.path("bytes").asLong(-1L)
                    : parsePositiveLong(metadata.path("bytes").asText(""));
            if (!sha256.matches("(?:sha256:)?[0-9a-fA-F]{64}") || bytes <= 0L) {
                return Map.of();
            }
            Map<String, Object> handoff = new LinkedHashMap<>();
            handoff.put("sourceRole", sourceRole.name());
            handoff.put("targetRole", targetRole.name());
            handoff.put("artifactName", "handoff/next.md");
            handoff.put("artifactUri", artifact.artifactUri());
            handoff.put("sha256", sha256);
            handoff.put("bytes", bytes);
            if (!artifact.summary().isBlank()) {
                handoff.put("summary", compactText(artifact.summary()));
            }
            return Map.copyOf(handoff);
        } catch (JsonProcessingException ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> persistedCandidatePatch(
            AgentStageRun stage,
            List<AgentStageArtifact> stageArtifacts
    ) {
        if (stage.role() != AgentRole.CODING_AGENT) {
            return Map.of();
        }
        return stageArtifacts.stream()
                .filter(artifact -> "PATCH_DIFF".equals(artifact.artifactType()))
                .max(Comparator.comparingLong(AgentStageArtifact::createdAtEpochMillis))
                .map(artifact -> persistedCandidatePatch(stage.role(), artifact))
                .orElseGet(Map::of);
    }

    private Map<String, Object> persistedCandidatePatch(
            AgentRole sourceRole,
            AgentStageArtifact artifact
    ) {
        if (sourceRole != AgentRole.CODING_AGENT
                || artifact == null
                || !artifact.artifactUri().startsWith("s3://")) {
            return Map.of();
        }
        try {
            JsonNode metadata = OBJECT_MAPPER.readTree(artifact.metadataJson());
            if (metadata == null || !metadata.isObject()
                    || !"true".equalsIgnoreCase(safe(metadata.path("candidatePatch").asText("")))) {
                return Map.of();
            }
            String sha256 = firstNonBlank(
                    safe(metadata.path("sha256").asText("")), artifact.contentHash()
            );
            long bytes = metadata.path("bytes").canConvertToLong()
                    ? metadata.path("bytes").asLong(-1L)
                    : parsePositiveLong(metadata.path("bytes").asText(""));
            if (!sha256.matches("(?:sha256:)?[0-9a-fA-F]{64}")
                    || bytes <= 0L
                    || bytes > 10L * 1024L * 1024L) {
                return Map.of();
            }
            Map<String, Object> candidatePatch = new LinkedHashMap<>();
            candidatePatch.put("sourceRole", AgentRole.CODING_AGENT.name());
            candidatePatch.put("targetRole", AgentRole.QA_AGENT.name());
            candidatePatch.put("artifactName", "patch.diff");
            candidatePatch.put("artifactUri", artifact.artifactUri());
            candidatePatch.put("sha256", sha256);
            candidatePatch.put("bytes", bytes);
            return Map.copyOf(candidatePatch);
        } catch (JsonProcessingException ignored) {
            return Map.of();
        }
    }

    private long parsePositiveLong(String value) {
        try {
            return Long.parseLong(safe(value));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private AgentRole directDownstreamRole(AgentRole role) {
        return switch (role) {
            case REQUIREMENT_REVIEWER -> AgentRole.SOLUTION_ARCHITECT;
            case SOLUTION_ARCHITECT -> AgentRole.CODING_AGENT;
            case CODING_AGENT -> AgentRole.QA_AGENT;
            case QA_AGENT -> null;
            default -> null;
        };
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

    private List<TaskMaterial> recoveryEvidenceMaterialsForRole(
            TaskRetryCheckpoint checkpoint,
            String taskId,
            List<TaskMaterial> materials
    ) {
        if (checkpoint == null || checkpoint.evidenceMaterialIds().isEmpty()) {
            return List.of();
        }
        if (!checkpoint.taskId().equals(taskId)) {
            throw new IllegalStateException("recovery checkpoint does not belong to task: " + taskId);
        }
        Map<String, TaskMaterial> materialsById = (materials == null ? List.<TaskMaterial>of() : materials)
                .stream()
                .filter(material -> taskId.equals(material.taskId()))
                .collect(Collectors.toMap(
                        TaskMaterial::materialId,
                        material -> material,
                        (left, ignored) -> left,
                        LinkedHashMap::new
                ));
        LinkedHashSet<String> selectedIds = new LinkedHashSet<>();
        List<TaskMaterial> selectedMaterials = new ArrayList<>();
        for (String materialId : checkpoint.evidenceMaterialIds()) {
            if (!selectedIds.add(materialId)) {
                throw new IllegalStateException("recovery checkpoint contains duplicate evidence material: " + materialId);
            }
            TaskMaterial material = materialsById.get(materialId);
            if (material == null) {
                throw new IllegalStateException("recovery evidence material is unavailable for task: " + materialId);
            }
            selectedMaterials.add(material);
        }
        return List.copyOf(selectedMaterials);
    }

    private String previousFailureFeedbackSection(String taskId, AgentRole role, int currentAttemptNo) {
        AgentStageRun previousFailure = stageRunStore.listByTask(taskId).stream()
                .filter(stage -> stage.role() == role)
                .filter(stage -> stage.attemptNo() < currentAttemptNo)
                .filter(stage -> stage.status() == AgentStageStatus.FAILED_RETRYABLE
                        || stage.status() == AgentStageStatus.FAILED_NEEDS_HUMAN)
                .max(STAGE_RUN_RECENCY)
                .orElse(null);
        if (previousFailure == null || previousFailure.errorMessage().isBlank()) {
            return "";
        }
        String detail = previousFailure.errorMessage();
        if (detail.length() > MAX_FAILURE_FEEDBACK_CHARS) {
            detail = detail.substring(0, MAX_FAILURE_FEEDBACK_CHARS) + "...(truncated)";
        }
        return """
                # 上一轮失败反馈
                上一次 %s 尝试（attempt %d）失败，错误分类：%s。
                失败明细：
                %s
                请针对以上明细修正本轮输出（逐条补齐缺失或非法的结果字段），不要原样重复上一轮输出。
                """.formatted(
                role.name(),
                previousFailure.attemptNo(),
                previousFailure.errorCategory().isBlank() ? "UNKNOWN" : previousFailure.errorCategory(),
                detail
        ).strip();
    }

    private static String joinPromptSections(String first, String second) {
        String safeFirst = first == null ? "" : first;
        String safeSecond = second == null ? "" : second;
        if (safeFirst.isBlank()) {
            return safeSecond;
        }
        if (safeSecond.isBlank()) {
            return safeFirst;
        }
        return safeFirst + "\n\n" + safeSecond;
    }

    private String recoveryPromptSection(
            TaskRetryCheckpoint checkpoint,
            AgentRole role,
            RoleContextPackage roleContext
    ) {
        if (!isRecoveryRoleOrDownstream(checkpoint, role)) {
            return "";
        }
        String operatorNote = checkpoint.operatorNote();
        String downstreamFailureSection = downstreamFailureFeedbackSection(checkpoint);
        List<com.wish.rd.rag.context.model.RoleContextEvidence> recoveryEvidence =
                selectedRecoveryEvidence(checkpoint, roleContext);
        if (operatorNote.isBlank() && recoveryEvidence.isEmpty() && downstreamFailureSection.isBlank()) {
            return "";
        }
        String noteSection = operatorNote.isBlank()
                ? ""
                : """
                        ## 操作员补充说明
                        %s
                        """.formatted(operatorNote).strip();
        String evidenceSection = recoveryEvidence.isEmpty()
                ? ""
                : """
                        ## 本次选定证据
                        %s
                        """.formatted(evidenceReferencePrompt(recoveryEvidence)).strip();
        return """
                # 本次失败恢复补充
                - 失败阶段运行 ID: %s
                - 从角色继续: %s

                %s

                %s

                %s
                """.formatted(
                checkpoint.failedStageRunId(),
                checkpoint.retryFromRole().name(),
                downstreamFailureSection,
                noteSection,
                evidenceSection
        ).strip();
    }

    private String downstreamFailureFeedbackSection(TaskRetryCheckpoint checkpoint) {
        if (checkpoint.failedStageRunId().isBlank() || checkpoint.retryFromRole() == null) {
            return "";
        }
        AgentStageRun failedStage = stageRunStore.findById(checkpoint.failedStageRunId()).orElse(null);
        if (failedStage == null || failedStage.role() == checkpoint.retryFromRole()) {
            return "";
        }
        String detail = failedStage.errorMessage();
        if (detail.isBlank()) {
            return "";
        }
        if (detail.length() > MAX_FAILURE_FEEDBACK_CHARS) {
            detail = detail.substring(0, MAX_FAILURE_FEEDBACK_CHARS) + "...(truncated)";
        }
        return """
                ## 下游 %s 失败反馈（本次打回原因）
                %s
                请针对以上反馈修正本角色产出，不要原样重复上一轮工作。
                """.formatted(failedStage.role().name(), detail).strip();
    }

    private boolean isRecoveryRoleOrDownstream(TaskRetryCheckpoint checkpoint, AgentRole role) {
        if (checkpoint == null || checkpoint.failurePhase() != TaskFailurePhase.AGENT_ROLE
                || checkpoint.retryFromRole() == null || role == null) {
            return false;
        }
        List<AgentRole> deliveryOrder = AgentRole.requirementDeliveryOrder();
        int retryIndex = deliveryOrder.indexOf(checkpoint.retryFromRole());
        int roleIndex = deliveryOrder.indexOf(role);
        return retryIndex >= 0 && roleIndex >= retryIndex;
    }

    private String budgetEstimatePromptSection(AgentRole role, RdRequirementTask task) {
        if (role != AgentRole.REQUIREMENT_REVIEWER) {
            return "本角色不输出预算估算。";
        }
        List<BudgetHistorySample> samples = historicalBudgetSamples(task);
        String confidenceRequirement = samples.isEmpty()
                ? "没有可用历史样本时，仍须由模型给出估算，confidence 必须为 LOW。"
                : "优先参考以下脱敏实际 token 样本，并由模型判断 confidence。";
        return """
                你必须估算完整四角色首轮交付和一次重试预留的 token 总量；不得使用确定性公式替代判断。
                当前有效 token 额度：%d（0 表示不限制）。
                %s
                历史实际样本（仅聚合和脱敏字段）：%s
                """.formatted(
                effectiveTokenBudget(task),
                confidenceRequirement,
                budgetHistoryJson(samples)
        ).strip();
    }

    private long effectiveTokenBudget(RdRequirementTask task) {
        if (task == null) {
            return 0L;
        }
        if (task.tokenBudgetOverride() > 0L) {
            return task.tokenBudgetOverride();
        }
        if (projectTokenBudgetService == null || task.projectId().isBlank()) {
            return 0L;
        }
        try {
            return projectTokenBudgetService.get(task.projectId()).defaultTokenBudget();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private List<BudgetHistorySample> historicalBudgetSamples(RdRequirementTask task) {
        if (task == null) {
            return List.of();
        }
        Map<String, RdRequirementTask> completedTasks = new LinkedHashMap<>();
        taskRegistry.listTasks().stream()
                .filter(RdRequirementTask.class::isInstance)
                .map(RdRequirementTask.class::cast)
                .filter(candidate -> candidate.status() == com.wish.rd.rag.runtime.model.RdTaskStatus.COMPLETED)
                .filter(candidate -> !candidate.taskId().equals(task.taskId()))
                .sorted(Comparator.comparingLong(RdRequirementTask::updateTimeEpochMillis).reversed())
                .forEach(candidate -> completedTasks.putIfAbsent(candidate.taskId(), candidate));
        if (completedTasks.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> orderedTaskIds = new LinkedHashSet<>();
        experienceStore.searchReusable(experienceSearchQuery(task), task.taskId(), 20).stream()
                .map(WorkflowExperienceEntry::taskId)
                .filter(completedTasks::containsKey)
                .forEach(orderedTaskIds::add);
        orderedTaskIds.addAll(completedTasks.keySet());

        List<BudgetHistorySample> sameProject = new ArrayList<>();
        List<BudgetHistorySample> crossProject = new ArrayList<>();
        for (String candidateTaskId : orderedTaskIds) {
            RdRequirementTask candidate = completedTasks.get(candidateTaskId);
            long actualTokens = actualTokenUsage(candidate.taskId());
            if (candidate == null || actualTokens <= 0L) {
                continue;
            }
            BudgetHistorySample sample = new BudgetHistorySample(
                    sameProject(task, candidate) ? "SAME_PROJECT" : "CROSS_PROJECT_REDACTED",
                    actualTokens,
                    candidate.updateTimeEpochMillis()
            );
            (sameProject(task, candidate) ? sameProject : crossProject).add(sample);
        }
        List<BudgetHistorySample> result = new ArrayList<>(5);
        sameProject.stream().limit(5).forEach(result::add);
        if (result.size() < 5) {
            crossProject.stream().limit(5 - result.size()).forEach(result::add);
        }
        return List.copyOf(result);
    }

    private static boolean sameProject(RdRequirementTask left, RdRequirementTask right) {
        return left != null && right != null && !left.projectId().isBlank()
                && left.projectId().equals(right.projectId());
    }

    private long actualTokenUsage(String taskId) {
        long total = 0L;
        for (AgentStageRun stageRun : stageRunStore.listByTask(taskId)) {
            try {
                JsonNode attempts = OBJECT_MAPPER.readTree(stageRun.providerAttemptsJson());
                if (attempts == null || !attempts.isArray()) {
                    continue;
                }
                for (JsonNode attempt : attempts) {
                    JsonNode tokens = attempt.get("totalTokens");
                    if (tokens != null && tokens.canConvertToLong() && tokens.longValue() > 0L) {
                        total = safeAdd(total, tokens.longValue());
                    }
                }
            } catch (JsonProcessingException ignored) {
                // Historical data is optional evidence; malformed old metadata is ignored.
            }
        }
        return total;
    }

    private TokenBudgetEstimate tokenBudgetEstimate(String reviewResultJson, boolean noHistoricalSamples) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(reviewResultJson == null ? "{}" : reviewResultJson);
            JsonNode budget = root == null ? null : root.path("budgetEstimate");
            if (budget == null || !budget.isObject()) {
                return TokenBudgetEstimate.invalid("budgetEstimate must be an object");
            }
            long initialTokens = requiredNonNegativeLong(budget, "initialTokens");
            long retryReserveTokens = requiredNonNegativeLong(budget, "retryReserveTokens");
            long estimatedTotalTokens = requiredNonNegativeLong(budget, "estimatedTotalTokens");
            String confidence = normalizedCode(budget.path("confidence"));
            String basis = text(budget.path("basis"));
            if (!("LOW".equals(confidence) || "MEDIUM".equals(confidence) || "HIGH".equals(confidence))) {
                return TokenBudgetEstimate.invalid("budgetEstimate.confidence must be LOW, MEDIUM or HIGH");
            }
            if (basis.isBlank()) {
                return TokenBudgetEstimate.invalid("budgetEstimate.basis must not be blank");
            }
            if (!budget.path("historicalSamples").isArray()) {
                return TokenBudgetEstimate.invalid("budgetEstimate.historicalSamples must be an array");
            }
            if (estimatedTotalTokens < safeAdd(initialTokens, retryReserveTokens)) {
                return TokenBudgetEstimate.invalid(
                        "budgetEstimate.estimatedTotalTokens must cover initialTokens and retryReserveTokens"
                );
            }
            if (noHistoricalSamples && !"LOW".equals(confidence)) {
                return TokenBudgetEstimate.invalid("budgetEstimate.confidence must be LOW without historical samples");
            }
            return new TokenBudgetEstimate(
                    initialTokens,
                    retryReserveTokens,
                    estimatedTotalTokens,
                    confidence,
                    basis,
                    true,
                    ""
            );
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return TokenBudgetEstimate.invalid(firstNonBlank(exception.getMessage(), "invalid budgetEstimate"));
        }
    }

    private TokenBudgetEvaluation evaluateTokenBudget(RdRequirementTask task, RequirementExecutionResult roleResult) {
        List<BudgetHistorySample> historicalSamples = historicalBudgetSamples(task);
        TokenBudgetEstimate budgetEstimate = tokenBudgetEstimate(roleResult.resultJson(), historicalSamples.isEmpty());
        if (!budgetEstimate.valid()) {
            return new TokenBudgetEvaluation(false, budgetEstimate.reason(), "{}", false);
        }
        long effectiveTokenBudget = effectiveTokenBudget(task);
        String budgetSnapshotJson = budgetSnapshotJson(
                roleResult.resultJson(), budgetEstimate, effectiveTokenBudget, historicalSamples
        );
        boolean exceeds = effectiveTokenBudget > 0L
                && budgetEstimate.estimatedTotalTokens() > effectiveTokenBudget;
        return new TokenBudgetEvaluation(true, "", budgetSnapshotJson, exceeds);
    }

    private static long requiredNonNegativeLong(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.canConvertToLong() || value.longValue() < 0L) {
            throw new IllegalArgumentException("budgetEstimate." + fieldName + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private String budgetSnapshotJson(
            String reviewResultJson,
            TokenBudgetEstimate estimate,
            long effectiveTokenBudget,
            List<BudgetHistorySample> historicalSamples
    ) {
        try {
            ObjectNode snapshot = OBJECT_MAPPER.createObjectNode();
            JsonNode review = OBJECT_MAPPER.readTree(reviewResultJson == null ? "{}" : reviewResultJson);
            snapshot.set("requirementReview", review == null ? OBJECT_MAPPER.createObjectNode() : review);
            ObjectNode budget = snapshot.putObject("tokenBudget");
            budget.put("effectiveTokenBudget", effectiveTokenBudget);
            budget.put("initialTokens", estimate.initialTokens());
            budget.put("retryReserveTokens", estimate.retryReserveTokens());
            budget.put("estimatedTotalTokens", estimate.estimatedTotalTokens());
            budget.put("confidence", estimate.confidence());
            budget.put("basis", estimate.basis());
            budget.put("overBudget", effectiveTokenBudget > 0L && estimate.estimatedTotalTokens() > effectiveTokenBudget);
            budget.put("excessTokens", effectiveTokenBudget > 0L
                    ? Math.max(0L, estimate.estimatedTotalTokens() - effectiveTokenBudget)
                    : 0L);
            budget.set("historicalSamples", OBJECT_MAPPER.valueToTree(historicalSamples));
            return OBJECT_MAPPER.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize token budget snapshot", exception);
        }
    }

    private String budgetHistoryJson(List<BudgetHistorySample> samples) {
        try {
            return OBJECT_MAPPER.writeValueAsString(samples == null ? List.of() : samples);
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private String experienceSearchQuery(RdRequirementTask task) {
        if (task == null) {
            return "";
        }
        return (task.title() + " " + task.expectedResult() + " " + task.acceptanceCriteriaJson()).strip();
    }

    private List<TaskMaterial> materialsWithReusableExperience(
            RdRequirementTask task,
            List<TaskMaterial> materials,
            long collectedAtEpochMillis
    ) {
        List<TaskMaterial> contextMaterials = new ArrayList<>(materials == null ? List.of() : materials);
        String query = experienceSearchQuery(task);
        experienceStore.searchReusable(query, task == null ? "" : task.taskId(), 100).stream()
                .filter(experience -> sameProjectExperience(task, experience))
                .filter(experience -> hasExperienceOverlap(query, experience))
                .limit(5)
                .map(experience -> experienceMaterial(task, experience, collectedAtEpochMillis))
                .forEach(contextMaterials::add);
        return List.copyOf(contextMaterials);
    }

    private boolean sameProjectExperience(RdRequirementTask task, WorkflowExperienceEntry experience) {
        if (task == null || experience == null || experience.taskId().isBlank()) {
            return false;
        }
        try {
            RdRequirementTask source = taskRegistry.getRequirementTask(experience.taskId());
            if (source == null) {
                return false;
            }
            if (!task.projectId().isBlank() || !source.projectId().isBlank()) {
                return !task.projectId().isBlank() && task.projectId().equals(source.projectId());
            }
            String currentRepo = (task.repoOwner() + "/" + task.repoName()).toLowerCase(Locale.ROOT);
            String sourceRepo = (source.repoOwner() + "/" + source.repoName()).toLowerCase(Locale.ROOT);
            return !currentRepo.equals("/") && currentRepo.equals(sourceRepo);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean hasExperienceOverlap(String query, WorkflowExperienceEntry experience) {
        String corpus = (experience.title() + " " + experience.summary() + " " + experience.contentJson())
                .toLowerCase(Locale.ROOT);
        String normalizedQuery = safe(query).toLowerCase(Locale.ROOT);
        for (String token : normalizedQuery.split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 2 && corpus.contains(token)) {
                return true;
            }
        }
        String cjk = normalizedQuery.replaceAll("[^\\p{IsHan}]", "");
        for (int index = 0; index + 2 <= cjk.length(); index++) {
            if (corpus.contains(cjk.substring(index, index + 2))) {
                return true;
            }
        }
        return false;
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
                com.wish.rd.rag.runtime.model.TaskMaterialType.REFERENCE_DOC,
                com.wish.rd.rag.runtime.model.TaskMaterialSourceType.MANUAL_TEXT,
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

    /**
     * Runs host BUILD/STATIC after Coding succeeds. Returning {@code null} lets
     * the role loop continue toward QA.
     */
    private RequirementExecutionResult gateQaBehindHostVerification(
            AgentWorkflowPlan plan,
            RdRequirementTask task,
            List<TaskMaterial> materials,
            RequirementContextPackage context,
            RequirementPlan planSnapshot,
            RequirementPolicyDecision policyDecision,
            TaskRetryCheckpoint activeRetry,
            String qaRemediationResultJson,
            int hostVerifyRemediationCount,
            int qaRemediationCount,
            AgentStageRun codingStage,
            String pullRequestUrl,
            List<String> stageResults
    ) {
        if (hostVerificationPort == null) {
            return hostVerifyNeedsHuman(
                    task,
                    pullRequestUrl,
                    stageResults,
                    "QA_INFRASTRUCTURE: host verification executor is unavailable",
                    null
            );
        }
        HostVerificationRun verification;
        try {
            verification = hostVerificationPort.verify(task, codingStage, plan, hostVerifyRemediationCount);
        } catch (RuntimeException exception) {
            return hostVerifyNeedsHuman(
                    task,
                    pullRequestUrl,
                    stageResults,
                    "QA_INFRASTRUCTURE: " + firstNonBlank(
                            exception.getMessage(),
                            "host verification executor is unavailable"
                    ),
                    null
            );
        }
        if (verification == null) {
            return hostVerifyNeedsHuman(
                    task,
                    pullRequestUrl,
                    stageResults,
                    "QA_INFRASTRUCTURE: host verification returned no result",
                    null
            );
        }
        if (verification.status() == HostVerificationStatus.SUCCEEDED
                || verification.status() == HostVerificationStatus.SKIPPED_DOCS_ONLY) {
            return null;
        }
        if (shouldCheapRemediate(plan, verification, hostVerifyRemediationCount)) {
            List<AgentStageRun> created = createHostVerifyRemediationAttempt(task.taskId());
            if (!created.isEmpty()) {
                return runInternal(
                        plan,
                        task,
                        materials,
                        context,
                        planSnapshot,
                        policyDecision,
                        activeRetry,
                        qaRemediationResultJson,
                        hostVerifyFailureFeedbackSection(verification),
                        hostVerifyRemediationCount + 1,
                        qaRemediationCount
                );
            }
        }
        return hostVerifyNeedsHuman(
                task,
                pullRequestUrl,
                stageResults,
                firstNonBlank(verification.errorMessage(), "host verification failed"),
                verification
        );
    }

    private boolean shouldCheapRemediate(
            AgentWorkflowPlan plan,
            HostVerificationRun verification,
            int hostVerifyRemediationCount
    ) {
        return plan.hostVerifyRemediationAllowed()
                && hostVerifyRemediationCount < plan.hostVerifyMaxRemediationPasses()
                && "PRODUCT_DEFECT".equals(verification.failureCategory());
    }

    private List<AgentStageRun> createHostVerifyRemediationAttempt(String taskId) {
        List<AgentStageRun> existing = stageRunStore.listByTask(taskId);
        AgentStageRun latest = existing.stream()
                .filter(stage -> stage.role() == AgentRole.CODING_AGENT)
                .max(STAGE_RUN_RECENCY)
                .orElse(null);
        if (latest != null && latest.attemptNo() >= 3) {
            return List.of();
        }
        int attemptNo = latest == null ? 1 : latest.attemptNo() + 1;
        long now = System.currentTimeMillis();
        return List.of(stageRunStore.save(AgentStageRun.pending(
                idGenerator.nextIdString(),
                taskId,
                AgentRole.CODING_AGENT,
                attemptNo,
                taskId + ":" + AgentRole.CODING_AGENT.name() + ":" + attemptNo,
                now
        )));
    }

    private String hostVerifyFailureFeedbackSection(HostVerificationRun verification) {
        String detail = firstNonBlank(verification.errorMessage(), "host verification failed");
        if (detail.length() > MAX_FAILURE_FEEDBACK_CHARS) {
            detail = detail.substring(0, MAX_FAILURE_FEEDBACK_CHARS) + "...(truncated)";
        }
        String category = verification.failureCategory().isBlank() ? "UNKNOWN" : verification.failureCategory();
        return """
                # 上一轮失败反馈
                上一次 HOST_VERIFY 尝试（attempt %d）失败，错误分类：%s。
                失败明细：
                %s
                请针对以上明细修正本轮输出（逐条补齐缺失或非法的结果字段），不要原样重复上一轮输出。
                """.formatted(verification.attemptNo(), category, detail).strip();
    }

    private RequirementExecutionResult hostVerifyNeedsHuman(
            RdRequirementTask task,
            String pullRequestUrl,
            List<String> stageResults,
            String reason,
            HostVerificationRun verification
    ) {
        return RequirementExecutionResult.failure(
                task.taskId(),
                reason,
                aggregateHostVerifyFailureJson(verification, pullRequestUrl, stageResults)
        );
    }

    private String aggregateHostVerifyFailureJson(
            HostVerificationRun verification,
            String pullRequestUrl,
            List<String> stageResults
    ) {
        String runId = verification == null ? "" : verification.runId();
        boolean stampHostVerify = !runId.isBlank()
                && verification != null
                && (verification.status() == HostVerificationStatus.FAILED_RETRYABLE
                || verification.status() == HostVerificationStatus.FAILED_NEEDS_HUMAN);
        if (!stampHostVerify) {
            return aggregateAgentResultsJson("NEEDS_HUMAN", pullRequestUrl, stageResults);
        }
        return """
                {"status":%s,"failurePhase":%s,"failedVerificationRunId":%s,"pullRequestUrl":%s,"stages":%s}
                """.formatted(
                json("NEEDS_HUMAN"),
                json(TaskFailurePhase.HOST_VERIFY.name()),
                json(runId),
                json(pullRequestUrl),
                stageResultsJson(stageResults)
        ).strip();
    }

    private List<AgentStageRun> createQaRemediationAttempts(String taskId) {
        List<AgentStageRun> existing = stageRunStore.listByTask(taskId);
        long now = System.currentTimeMillis();
        // 单角色阶段最大 attempt 数；防止协议失败引发的盲重试风暴。
        for (AgentRole role : List.of(AgentRole.CODING_AGENT, AgentRole.QA_AGENT)) {
            AgentStageRun latest = existing.stream()
                    .filter(stage -> stage.role() == role)
                    .max(STAGE_RUN_RECENCY)
                    .orElse(null);
            if (latest != null && latest.attemptNo() >= 3) {
                return List.of();
            }
        }
        List<AgentStageRun> created = new ArrayList<>(2);
        for (AgentRole role : List.of(AgentRole.CODING_AGENT, AgentRole.QA_AGENT)) {
            AgentStageRun latest = existing.stream()
                    .filter(stage -> stage.role() == role)
                    .max(STAGE_RUN_RECENCY)
                    .orElse(null);
            int attemptNo = latest == null ? 1 : latest.attemptNo() + 1;
            created.add(stageRunStore.save(AgentStageRun.pending(
                    idGenerator.nextIdString(),
                    taskId,
                    role,
                    attemptNo,
                    taskId + ":" + role.name() + ":" + attemptNo,
                    now
            )));
        }
        return List.copyOf(created);
    }

    private void publishQaRemediationStarted(
            AgentStageRun failedQaStage,
            List<AgentStageRun> remediationStages,
            String qaResultJson
    ) {
        AgentStageRun codingStage = remediationStages.stream()
                .filter(stage -> stage.role() == AgentRole.CODING_AGENT)
                .findFirst()
                .orElseThrow();
        AgentStageRun qaStage = remediationStages.stream()
                .filter(stage -> stage.role() == AgentRole.QA_AGENT)
                .findFirst()
                .orElseThrow();
        alertSink.publish(new AgentWorkflowAlert(
                failedQaStage.taskId(),
                failedQaStage.stageRunId(),
                AgentWorkflowAlertType.QA_REMEDIATION_STARTED,
                "QA product failure returned to coding for one bounded remediation attempt",
                Map.of(
                        "role", failedQaStage.role().name(),
                        "failedQaAttemptNo", Integer.toString(failedQaStage.attemptNo()),
                        "codingAttemptNo", Integer.toString(codingStage.attemptNo()),
                        "qaAttemptNo", Integer.toString(qaStage.attemptNo()),
                        "failureCategory", qaFailureCategory(qaResultJson),
                        "nextAction", AgentRole.CODING_AGENT.name()
                ),
                System.currentTimeMillis()
        ));
    }

    private String qaFailureCategory(String qaResultJson) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(safe(qaResultJson));
            return root == null ? "" : root.path("failureCategory").asText("").strip();
        } catch (JsonProcessingException exception) {
            return "";
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

    private void captureExperience(
            AgentStageRun stage,
            RequirementExecutionResult result,
            WorkflowExperienceType experienceType
    ) {
        try {
            experienceStore.save(scopeExperience(new WorkflowExperienceEntry(
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
            )));
        } catch (RuntimeException exception) {
            publishStageAlert(
                    stage,
                    AgentWorkflowAlertType.EXPERIENCE_CAPTURE_FAILED,
                    "experience capture failed: " + safe(exception.getMessage())
            );
        }
    }

    private WorkflowExperienceEntry scopeExperience(WorkflowExperienceEntry entry) {
        try {
            RdRequirementTask task = taskRegistry.getRequirementTask(entry.taskId());
            String repositoryFingerprint = !task.repoOwner().isBlank() && !task.repoName().isBlank()
                    ? (task.repoOwner() + "/" + task.repoName()).toLowerCase(Locale.ROOT)
                    : safe(task.repositoryUrl()).toLowerCase(Locale.ROOT);
            LinkedHashSet<String> tags = new LinkedHashSet<>();
            tags.add(task.projectKey());
            tags.add(entry.role().name());
            tags.add(entry.experienceType().name());
            return new WorkflowExperienceEntry(
                    entry.experienceId(), entry.taskId(), entry.stageRunId(), entry.sourceArtifactId(),
                    entry.role(), entry.experienceType(), entry.title(), entry.summary(), entry.contentJson(),
                    entry.reusable(), entry.failure(), entry.redacted(), entry.createdAtEpochMillis(),
                    task.projectId(), repositoryFingerprint, task.projectKey(),
                    tags.stream().filter(value -> !safe(value).isBlank()).toList(),
                    experienceSourceRevision(task, entry.contentJson()), entry.evidenceQuality(),
                    applicableExperienceRoles(entry.experienceType())
            );
        } catch (RuntimeException exception) {
            return entry;
        }
    }

    private String experienceSourceRevision(RdRequirementTask task, String contentJson) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(contentJson == null ? "{}" : contentJson);
            String commit = firstNonBlank(
                    text(root.path("commitSha")),
                    text(root.path("commit")),
                    text(root.path("executionResult").path("commitSha"))
            );
            if (!commit.isBlank()) {
                return commit;
            }
        } catch (JsonProcessingException ignored) {
            // Fall through to the persisted branch revision.
        }
        return firstNonBlank(task.workBranch(), task.baseBranch());
    }

    private List<AgentRole> applicableExperienceRoles(WorkflowExperienceType type) {
        return switch (type) {
            case REQUIREMENT_REVIEW -> List.of(AgentRole.REQUIREMENT_REVIEWER, AgentRole.SOLUTION_ARCHITECT);
            case TECHNICAL_DESIGN -> List.of(AgentRole.SOLUTION_ARCHITECT, AgentRole.CODING_AGENT);
            case CODE_CHANGE -> List.of(AgentRole.CODING_AGENT, AgentRole.QA_AGENT);
            case QA_REPORT, DELIVERY_REPORT -> List.of(AgentRole.QA_AGENT, AgentRole.REQUIREMENT_REVIEWER);
        };
    }

    private WorkflowExperienceType experienceTypeForRole(AgentRole role) {
        return switch (role) {
            case REQUIREMENT_REVIEWER -> WorkflowExperienceType.REQUIREMENT_REVIEW;
            case SOLUTION_ARCHITECT -> WorkflowExperienceType.TECHNICAL_DESIGN;
            case CODING_AGENT -> WorkflowExperienceType.CODE_CHANGE;
            case QA_AGENT -> WorkflowExperienceType.QA_REPORT;
            default -> throw new IllegalArgumentException("unsupported requirement role: " + role);
        };
    }

    private String experienceTitle(AgentRole role) {
        return switch (role) {
            case REQUIREMENT_REVIEWER -> "需求评审";
            case SOLUTION_ARCHITECT -> "技术方案";
            case CODING_AGENT -> "代码交付";
            case QA_AGENT -> "QA 验收";
            default -> throw new IllegalArgumentException("unsupported requirement role: " + role);
        };
    }

    private void markRequirementWaitingApprovalAndAlert(RdRequirementTask task, String budgetSnapshotJson) {
        RdRequirementTask waitingApproval = taskRegistry.markRequirementWaitingApproval(
                task.taskId(), budgetSnapshotJson
        );
        alertSink.publish(new AgentWorkflowAlert(
                waitingApproval.taskId(),
                "",
                AgentWorkflowAlertType.TASK_BLOCKED,
                "预估 token 用量超过有效额度，等待人工预算审批",
                Map.of("nextAction", "确认预估、额度与超出量后提交审批说明", "status", "TASK_BLOCKED"),
                System.currentTimeMillis()
        ));
    }

    private ReviewGateDecision requirementReviewGateDecision(RequirementExecutionResult result) {
        if (result == null) {
            return new ReviewGateDecision(true, "requirement review result missing");
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(result.resultJson() == null ? "{}" : result.resultJson());
            if (root == null || !root.isObject()) {
                return new ReviewGateDecision(
                        true,
                        firstNonBlank(result.summary(), "requirement review result json must be an object")
                );
            }
            String status = normalizedCode(root.path("status"));
            String decision = normalizedCode(root.path("decision"));
            String feasibility = normalizedCode(root.path("feasibility"));
            if (requiresHuman(status) || requiresHuman(decision) || requiresHuman(feasibility)) {
                return new ReviewGateDecision(true, requirementReviewNeedsHumanReason(result, root));
            }
            if (status.isBlank() && decision.isBlank() && feasibility.isBlank()) {
                return new ReviewGateDecision(
                        true,
                        firstNonBlank(result.summary(), "requirement review decision is missing")
                );
            }
            return new ReviewGateDecision(false, "");
        } catch (JsonProcessingException exception) {
            return new ReviewGateDecision(
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

    private String roleInstruction(AgentRole role, RequirementExecutionProfileResolution resolution) {
        if (resolution != null && resolution.protocolVersion() == ContextProtocolVersion.FACTS_V1) {
            return roleInstructionFactsV1(role, resolution.dynamicStateEnabled());
        }
        return roleInstructionLegacy(role);
    }

    private String roleInstructionLegacy(AgentRole role) {
        return switch (role) {
            case REQUIREMENT_REVIEWER -> """
                    - 只做需求评审，不修改代码，不创建 PR。
                    - 把本轮实测发现的环境事实（缺依赖、替代命令、必需环境变量、可用测试入口）写入 result.json 的 environmentNotes，供下游直接沿用。
                    - 输出结构化需求评审结果，明确能否做、缺失信息、风险和验收覆盖。
                    - 当评审允许进入下一角色时，使用已安装的 role-handoff-document Skill，把可执行交接写入 /work/output/handoff/next.md；预算见 context.json 的 roleHandoffMaxTokens。
                    - 交接文档承载详细约束、验收、风险和待确认项；result.json 中只保留 next_prompt 的简短指针，绝不写对象存储地址或凭据。
                    - 结果必须写入 /work/output/result.json，且只使用当前角色输出 JSON 协议。
                    """.strip();
            case SOLUTION_ARCHITECT -> """
                    - 基于需求评审和证据制定开发方案，不修改代码，不创建 PR。
                    - 上游环境备忘视为已验证事实直接沿用；本轮新发现的环境事实追加写入 result.json 的 environmentNotes。
                    - 输出影响文件、接口/数据变更、实现步骤、验收映射和测试计划。
                    - 使用已安装的 role-handoff-document Skill，把完整开发计划写入 /work/output/handoff/next.md，供 CODING_AGENT 作为受控附件读取；预算见 context.json 的 roleHandoffMaxTokens。
                    - result.json 中的 next_prompt 只提供目标角色、短摘要和固定相对路径；不要把完整计划或 RustFS 地址复制进 JSON。
                    - 结果必须写入 /work/output/result.json，且只使用当前角色输出 JSON 协议。
                    """.strip();
            case CODING_AGENT -> """
                    - 根据需求评审和方案执行代码修改。
                    - 上游环境备忘视为已验证事实直接沿用，不要重复探测；本轮新发现的环境事实（含可用的测试执行方式）追加写入 result.json 的 environmentNotes，供 QA 直接沿用。
                    - 依赖树和 /work/cache 是当前任务与重试共享的状态：不得删除 node_modules、package-lock.json 或 /work/cache。先检查现有依赖；仅在依赖确实缺失时执行一次与 lockfile 匹配的安装。安装失败时保留诊断并停止重复清理、重复安装或绕过包管理器的手工下载。
                    - 宿主会在本阶段成功后重跑安装、构建、仓库测试和静态检查。`testStatus` 只是交接信息，不是放行依据。
                    - 若存在上一轮宿主验证失败，只修反馈中的命令和日志，不要删 `/work/cache` 或 `node_modules`。
                    - Next.js 服务验收必须使用生产模式：执行 npm run build && npm run start；不得以 npm run dev 作为交付验证服务。
                    - HTTP 请求必须设置不超过 30 秒的请求超时；启动服务和每个 bash 命令都必须有有限 deadline。超时后停止临时服务、保留日志，并提交 FAILED 结构化结果；不得无限等待。
                    - 该阶段只负责代码修改和交付候选证据，不创建 PR。
                    - 使用已安装的 role-handoff-document Skill，把变更、已执行测试、风险和 QA 注意事项写入 /work/output/handoff/next.md；预算见 context.json 的 roleHandoffMaxTokens。
                    - result.json 中的 next_prompt 只提供目标角色、短摘要和固定相对路径；不得透传完整日志、Docker 元数据或对象存储地址。
                    - 成功时返回 prBody、changedFiles、testSummary 和真实测试证据，等待控制面复核后发布。
                    """.strip();
            case QA_AGENT -> """
                    - 基于代码交付候选包、验收标准和真实命令执行 QA 复核。
                    - 上游环境备忘（含 CODING_AGENT 已验证的测试执行方式）视为已验证事实直接沿用，不要从零重复探测环境。
                    - 先读取 /work/input/qa-profile.json，并遵循已安装的 qa-playwright-cli Skill；Web 项目且配置要求时必须执行真实 Chromium 浏览器验证。
                    - docs-only 例外（宿主根据候选补丁的真实变更文件集判定，写入 qa-profile.json 的 decisionSource=DOCS_ONLY 与 candidateChangedFiles）：仅当 profile 判定为 docs-only 时，跳过 npm install / build / start 与 Chromium 浏览器回归，browserValidation 必须为 required=false、performed=false、decisionSource=DOCS_ONLY；不得凭任务描述或自我声明降级。变更集无法判定或含任意运行时相关文件时必须跑完整浏览器画像。
                    - 若存在 /work/input/qa-skill/SKILL.md，先完整阅读并严格遵循其中的流程与工具（rd-qa-evidence.mjs / playwright-cli）。
                    - 证据目录约定：截图写入 qa-evidence/screenshots/、Playwright trace 写入 qa-evidence/traces/、浏览器 console 写入 qa-evidence/console/、network 写入 qa-evidence/network/、命令日志写入 qa-evidence/commands/；放错目录会导致证据类型无法识别而阻断交付。evidenceArtifactIds 引用的每个文件都必须非空。
                    - 不创建新 PR，也不得修改 /work/repo 中的跟踪文件；临时脚本只能写入 /work/output/qa-work。
                    - 退出状态契约：验证过程中允许用 git stash/checkout 做原始态对照，但写 result.json 前必须恢复原状——/work/repo 退出时必须保持候选补丁在位的状态（git diff HEAD 非空且与进入时一致），丢弃补丁即判基础设施失败。
                    - 当前需求验收（CURRENT）和受影响的既有关键路径回归（REGRESSION）都必须真实执行；任一必需检查缺少证据或被跳过都阻断交付。docs-only 时 CURRENT/REGRESSION 用文件/文本类命令验证即可，不得要求浏览器证据。
                    - 必须记录每条命令的退出码、耗时和日志；每个 qa-evidence/ 日志文件必须非空，至少包含命令文本、退出码和时间戳；如果命令成功且无输出（如 git diff --check），在日志中写入命令和 exit code 0 及说明。当 browserValidation.required 且 performed 为 true 时，必须补充截图、trace、console 和 network 证据，且这些证据必须被 acceptanceResults 的 logArtifactId 或 evidenceArtifactIds 显式引用：至少各引用一次 qa-evidence/console/、qa-evidence/network/、qa-evidence/traces/ 下的文件，以及 qa-evidence/screenshots/ 下文件名含 desktop 和含 mobile 的截图各一张；只采集或只写入 manifest 而不引用会导致整个结果被宿主拒绝。docs-only（decisionSource=DOCS_ONLY）不要求上述浏览器证据。
                    - 如用包装脚本记录命令，必须以 bash -c '完整命令行' 方式执行；直接把带环境变量前缀的命令（如 PYTHONPATH=x cmd）当参数逐词执行会报 127；时间预算优先保障真实命令执行与 result.json 落盘，深度分析写进 summary 即可，不要因分析耗尽容器超时。
                    - evidenceArtifactIds 和 logArtifactId 只能引用 /work/output/qa-evidence/ 下实际存在的证据文件，不要引用 /work/output/qa-work/ 下的临时文件。
                    - manifest.json 必须包含 "version": 1（整数）和 "artifacts" 数组；不要使用 "schema" 替代 "version"。
                    - manifest.json 的每个 artifact 条目必须包含 "path"、"bytes"（文件精确字节数，整数）和 "sha256"（文件 SHA-256 哈希，小写十六进制 64 位字符串）三个字段；使用 sha256sum 命令获取准确值。
                    - manifest.json 的 artifact path 只能以 "qa-evidence/" 开头；不要在 manifest 中列出 patch.diff、test.log 或任何 qa-evidence/ 以外的文件。
                    - PRODUCT_DEFECT 或 REGRESSION 失败必须建议退回 CODING_AGENT；环境、鉴权、QA 基础设施、需求歧义或 flaky 问题建议 HUMAN。
                    - 当输入上下文提供 hostAssertionContracts 时，Host 已冻结可执行断言。仅回传两个 hostAssertionResults echo（CURRENT 和 REGRESSION），每项只能有 scope、输入给定的 contentHash 和同 scope acceptanceResults 已引用的非空 evidenceArtifactIds。不得提交 hostAssertionBundle、hostAssertionWorkspace、hostAssertionBaseUrl 或 hostAssertionContext；Host 独立选择工作区和执行规范。
                    - 最后生成完整性 manifest，再把严格协议写入 /work/output/result.json。
                    """.strip();
            default -> throw new IllegalArgumentException("unsupported requirement role: " + role);
        };
    }

    private String roleInstructionFactsV1(AgentRole role, boolean dynamicStateEnabled) {
        String dynamicStateHint = dynamicStateEnabled
                ? "- 动态状态已开启：优先用 rd_record_fact 记录可验证事实，再写入 result.json 的 facts[]。"
                : "- 动态状态未开启：把环境事实结构化写入 result.json 的 facts[]。";
        return switch (role) {
            case REQUIREMENT_REVIEWER -> """
                    - 只做需求评审，不修改代码，不创建 PR。
                    %s
                    - 不要自由填写 environmentNotes；Harness 会从 fresh OBSERVED facts 派生 environmentNotes，手写冲突 notes 会导致结果被拒绝。
                    - 输出结构化需求评审结果，明确能否做、缺失信息、风险和验收覆盖。
                    - 当评审允许进入下一角色时，使用已安装的 role-handoff-document Skill，把可执行交接写入 /work/output/handoff/next.md；预算见 context.json 的 roleHandoffMaxTokens。
                    - 交接文档承载详细约束、验收、风险和待确认项；result.json 中只保留 next_prompt 的简短指针，绝不写对象存储地址或凭据。
                    - 结果必须写入 /work/output/result.json，且只使用当前角色输出 JSON 协议。
                    """.formatted(dynamicStateHint).strip();
            case SOLUTION_ARCHITECT -> """
                    - 基于需求评审和证据制定开发方案，不修改代码，不创建 PR。
                    %s
                    - 上游 facts 视为已验证事实直接沿用；本轮新发现的环境事实追加写入 facts[]，不要填写 environmentNotes。
                    - 输出影响文件、接口/数据变更、实现步骤、验收映射和测试计划。
                    - 使用已安装的 role-handoff-document Skill，把完整开发计划写入 /work/output/handoff/next.md，供 CODING_AGENT 作为受控附件读取；预算见 context.json 的 roleHandoffMaxTokens。
                    - result.json 中的 next_prompt 只提供目标角色、短摘要和固定相对路径；不要把完整计划或 RustFS 地址复制进 JSON。
                    - 结果必须写入 /work/output/result.json，且只使用当前角色输出 JSON 协议。
                    """.formatted(dynamicStateHint).strip();
            case CODING_AGENT -> """
                    - 根据需求评审和方案执行代码修改。
                    %s
                    - 上游 facts 视为已验证事实直接沿用，不要重复探测；本轮新发现的环境事实（含可用的测试执行方式）追加写入 facts[]，供 QA 直接沿用。
                    - 不要自由填写 environmentNotes；Harness 会从 fresh OBSERVED facts 派生 environmentNotes。
                    - 依赖树和 /work/cache 是当前任务与重试共享的状态：不得删除 node_modules、package-lock.json 或 /work/cache。先检查现有依赖；仅在依赖确实缺失时执行一次与 lockfile 匹配的安装。安装失败时保留诊断并停止重复清理、重复安装或绕过包管理器的手工下载。
                    - 宿主会在本阶段成功后重跑安装、构建、仓库测试和静态检查。`testStatus` 只是交接信息，不是放行依据。
                    - 若存在上一轮宿主验证失败，只修反馈中的命令和日志，不要删 `/work/cache` 或 `node_modules`。
                    - Next.js 服务验收必须使用生产模式：执行 npm run build && npm run start；不得以 npm run dev 作为交付验证服务。
                    - HTTP 请求必须设置不超过 30 秒的请求超时；启动服务和每个 bash 命令都必须有有限 deadline。超时后停止临时服务、保留日志，并提交 FAILED 结构化结果；不得无限等待。
                    - 该阶段只负责代码修改和交付候选证据，不创建 PR。
                    - 使用已安装的 role-handoff-document Skill，把变更、已执行测试、风险和 QA 注意事项写入 /work/output/handoff/next.md；预算见 context.json 的 roleHandoffMaxTokens。
                    - result.json 中的 next_prompt 只提供目标角色、短摘要和固定相对路径；不得透传完整日志、Docker 元数据或对象存储地址。
                    - 成功时返回 prBody、changedFiles、testSummary 和真实测试证据，等待控制面复核后发布。
                    """.formatted(dynamicStateHint).strip();
            case QA_AGENT -> roleInstructionLegacy(AgentRole.QA_AGENT);
            default -> throw new IllegalArgumentException("unsupported requirement role: " + role);
        };
    }

    private String roleOutputContract(AgentRole role, RequirementExecutionProfileResolution resolution) {
        if (resolution != null && resolution.protocolVersion() == ContextProtocolVersion.FACTS_V1) {
            return roleOutputContractFactsV1(role);
        }
        return roleOutputContractLegacy(role);
    }

    private String roleOutputContractLegacy(AgentRole role) {
        return switch (role) {
            case REQUIREMENT_REVIEWER -> """
                    必须调用 rd_submit_result 恰好一次，提交下面这个完整 JSON 对象（字段都在根上）。不要只在对话里打印 JSON，也不要自己写 result.json：
                    {
                      "decision": "APPROVED|NEED_INFO|REJECTED",
                      "feasibility": "CAN_DO|NEED_INFO|UNSAFE",
                      "missingInformation": [],
                      "risks": [],
                      "acceptanceCoverage": ["每条验收标准的覆盖判断"],
                      "environmentNotes": ["本轮实测的环境事实（缺依赖/替代命令/必需环境变量/可用测试入口）；无则空数组"],
                      "budgetEstimate": {
                        "initialTokens": 0,
                        "retryReserveTokens": 0,
                        "estimatedTotalTokens": 0,
                        "confidence": "LOW|MEDIUM|HIGH；预算章节未提供历史实际样本时必须填 LOW，否则整个结果会被拒绝",
                        "basis": "基于四角色首轮、一次重试预留和给定历史实际 token 样本的模型判断",
                        "historicalSamples": []
                      },
                      "next_prompt": {
                        "targetRole": "SOLUTION_ARCHITECT",
                        "summary": "最多 1200 个字符的下游摘要",
                        "handoffArtifact": "handoff/next.md"
                      }
                    }
                    """.strip();
            case SOLUTION_ARCHITECT -> """
                    必须调用 rd_submit_result 恰好一次，提交下面这个完整 JSON 对象（字段都在根上）。不要只在对话里打印 JSON，也不要自己写 result.json：
                    {
                      "summary": "开发方案摘要",
                      "affectedFiles": ["预计影响文件"],
                      "implementationSteps": ["可执行开发步骤"],
                      "acceptanceMapping": [{"criteria":"验收标准","validation":"真实验证方式"}],
                      "testPlan": [{"criteria":"验收标准","command":"真实测试命令"}],
                      "environmentNotes": ["本轮实测的环境事实；无新发现则空数组"],
                      "next_prompt": {
                        "targetRole": "CODING_AGENT",
                        "summary": "最多 1200 个字符的下游摘要",
                        "handoffArtifact": "handoff/next.md"
                      }
                    }
                    """.strip();
            case CODING_AGENT -> """
                    必须调用 rd_submit_result 恰好一次，提交下面这个完整 JSON 对象（字段都在根上）。不要只在对话里打印 JSON，也不要自己写 result.json：
                    {
                      "status": "SUCCESS|FAILED|NEED_INFO|UNSAFE",
                      "summary": "实现摘要",
                      "changedFiles": ["实际改动文件"],
                      "testCommands": ["真实执行过的命令"],
                      "testStatus": "PASSED|FAILED|SKIPPED",
                      "riskLevel": "LOW|MEDIUM|HIGH",
                      "environmentNotes": ["本轮实测的环境事实（供 QA 直接沿用）；无新发现则空数组"],
                      "prBody": "候选 PR 正文，包含改动和真实验证证据",
                      "needHumanAction": false,
                      "next_prompt": {
                        "targetRole": "QA_AGENT",
                        "summary": "最多 1200 个字符的 QA 交接摘要",
                        "handoffArtifact": "handoff/next.md"
                      }
                    }
                    """.strip();
            case QA_AGENT -> """
                    必须调用 rd_submit_result 恰好一次，提交下面这个完整 JSON 对象（字段都在根上）。不要只在对话里打印 JSON，也不要自己写 result.json：
                    {
                      "status": "PASSED|FAILED|SKIPPED",
                      "summary": "QA 当前需求与回归验证摘要",
                      "failureCategory": "NONE|PRODUCT_DEFECT|REGRESSION|ENVIRONMENT|AUTHENTICATION|QA_INFRASTRUCTURE|REQUIREMENT_AMBIGUITY|FLAKY",
                      "retryRecommendation": "NONE|CODING_AGENT|HUMAN",
                      "browserValidation": {
                        "required": true,
                        "performed": true,
                        "decisionSource": "TASK_OVERRIDE|PROJECT_PROFILE|REPOSITORY_CONFIG|AUTO_DETECTION|NOT_APPLICABLE",
                        "baseUrl": "真实浏览器验证 URL；不适用时为空字符串",
                        "browser": "chromium",
                        "viewports": ["desktop-1440x900", "mobile-390x844"]
                      },
                      "acceptanceResults": [
                        {
                          "criteria": "对应验收标准",
                          "scope": "CURRENT|REGRESSION",
                          "command": "真实执行命令",
                          "status": "PASSED|FAILED|SKIPPED",
                          "exitCode": 0,
                          "durationMillis": 0,
                          "logArtifactId": "qa-evidence/ 下的命令日志相对路径",
                          "evidenceArtifactIds": ["qa-evidence/ 下的真实证据相对路径"]
                        }
                      ],
                      "evidenceManifestArtifactId": "qa-evidence/manifest.json",
                      "hostAssertionResults": [
                        {
                          "scope": "CURRENT|REGRESSION",
                          "contentHash": "输入 hostAssertionContracts 中同 scope 的 sha256 摘要",
                          "evidenceArtifactIds": ["同 scope acceptanceResults 已引用的 qa-evidence/ 路径"]
                        }
                      ]
                    }
                    """.strip();
            default -> throw new IllegalArgumentException("unsupported requirement role: " + role);
        };
    }

    private String roleOutputContractFactsV1(AgentRole role) {
        String observedFactExample = """
                {
                  "factId": "env-test-entry",
                  "kind": "OBSERVED",
                  "statement": "npm test 可在当前仓库执行",
                  "sourceArtifactId": "qa-evidence/commands/test.log",
                  "sourceStageRunId": "stage-run-id",
                  "observedAt": "2026-08-01T00:00:00Z",
                  "freshnessPolicy": "SAME_REVISION",
                  "repoRevision": "abc123"
                }""".strip();
        return switch (role) {
            case REQUIREMENT_REVIEWER -> """
                    必须调用 rd_submit_result 恰好一次，提交下面这个完整 JSON 对象（字段都在根上）。不要只在对话里打印 JSON，也不要自己写 result.json：
                    {
                      "decision": "APPROVED|NEED_INFO|REJECTED",
                      "feasibility": "CAN_DO|NEED_INFO|UNSAFE",
                      "missingInformation": [],
                      "risks": [],
                      "acceptanceCoverage": ["每条验收标准的覆盖判断"],
                      "facts": [%s],
                      "budgetEstimate": {
                        "initialTokens": 0,
                        "retryReserveTokens": 0,
                        "estimatedTotalTokens": 0,
                        "confidence": "LOW|MEDIUM|HIGH；预算章节未提供历史实际样本时必须填 LOW，否则整个结果会被拒绝",
                        "basis": "基于四角色首轮、一次重试预留和给定历史实际 token 样本的模型判断",
                        "historicalSamples": []
                      },
                      "next_prompt": {
                        "targetRole": "SOLUTION_ARCHITECT",
                        "summary": "最多 1200 个字符的下游摘要",
                        "handoffArtifact": "handoff/next.md"
                      }
                    }
                    不要包含 environmentNotes；Harness 会从 fresh OBSERVED facts 派生。
                    """.formatted(observedFactExample).strip();
            case SOLUTION_ARCHITECT -> """
                    必须调用 rd_submit_result 恰好一次，提交下面这个完整 JSON 对象（字段都在根上）。不要只在对话里打印 JSON，也不要自己写 result.json：
                    {
                      "summary": "开发方案摘要",
                      "affectedFiles": ["预计影响文件"],
                      "implementationSteps": ["可执行开发步骤"],
                      "acceptanceMapping": [{"criteria":"验收标准","validation":"真实验证方式"}],
                      "testPlan": [{"criteria":"验收标准","command":"真实测试命令"}],
                      "facts": [%s],
                      "next_prompt": {
                        "targetRole": "CODING_AGENT",
                        "summary": "最多 1200 个字符的下游摘要",
                        "handoffArtifact": "handoff/next.md"
                      }
                    }
                    不要包含 environmentNotes；无新发现时 facts 可为空数组。
                    """.formatted(observedFactExample).strip();
            case CODING_AGENT -> """
                    必须调用 rd_submit_result 恰好一次，提交下面这个完整 JSON 对象（字段都在根上）。不要只在对话里打印 JSON，也不要自己写 result.json：
                    {
                      "status": "SUCCESS|FAILED|NEED_INFO|UNSAFE",
                      "summary": "实现摘要",
                      "changedFiles": ["实际改动文件"],
                      "testCommands": ["真实执行过的命令"],
                      "testStatus": "PASSED|FAILED|SKIPPED",
                      "riskLevel": "LOW|MEDIUM|HIGH",
                      "facts": [%s],
                      "prBody": "候选 PR 正文，包含改动和真实验证证据",
                      "needHumanAction": false,
                      "next_prompt": {
                        "targetRole": "QA_AGENT",
                        "summary": "最多 1200 个字符的 QA 交接摘要",
                        "handoffArtifact": "handoff/next.md"
                      }
                    }
                    不要包含 environmentNotes；Harness 会从 fresh OBSERVED facts 派生。
                    """.formatted(observedFactExample).strip();
            case QA_AGENT -> roleOutputContractLegacy(AgentRole.QA_AGENT);
            default -> throw new IllegalArgumentException("unsupported requirement role: " + role);
        };
    }

    private String lightweightDeliveryPromptSection(AgentRole role, RdRequirementTask task) {
        String criteria = safe(task.acceptanceCriteriaJson());
        boolean patchOnlyDelivery = criteria.contains("不创建 PR")
                || criteria.contains("不创建PR")
                || criteria.contains("禁止创建 PR")
                || criteria.toLowerCase(Locale.ROOT).contains("do not open a pull request");
        if (!patchOnlyDelivery) {
            return "";
        }
        return switch (role) {
            case REQUIREMENT_REVIEWER -> """
                    # 轻量交付模式（补丁即交付）
                    - 本任务为小补丁类交付：评审时一次性完成缺陷定位（文件/符号/根因）并写进交接文档，供下游直接复用。
                    - 不要展开与验收标准无关的风险面；评审目标是让 SOLUTION_ARCHITECT 免于重新复现。
                    """.strip();
            case SOLUTION_ARCHITECT -> """
                    # 轻量交付模式（补丁即交付）
                    - 上游评审已完成缺陷复现与定位：直接基于其交接文档制定方案，禁止重新复现 bug、禁止重复环境探测。
                    - 方案只聚焦：改哪个文件/函数、算法要点、回归测试点与验证命令；控制在最小充分范围，不做展开式架构分析。
                    """.strip();
            default -> "";
        };
    }

    private List<com.wish.rd.rag.context.model.RoleContextEvidence> selectedRecoveryEvidence(
            TaskRetryCheckpoint checkpoint,
            RoleContextPackage roleContext
    ) {
        if (checkpoint == null || checkpoint.evidenceMaterialIds().isEmpty() || roleContext == null) {
            return List.of();
        }
        LinkedHashSet<String> selectedIds = new LinkedHashSet<>(checkpoint.evidenceMaterialIds());
        return roleContext.evidence().stream()
                .filter(evidence -> selectedIds.contains(evidence.evidenceId()))
                .toList();
    }

    private String evidenceReferencePrompt(
            List<com.wish.rd.rag.context.model.RoleContextEvidence> evidence
    ) {
        if (evidence == null || evidence.isEmpty()) {
            return "";
        }
        return evidence.stream()
                .map(item -> """
                        ## %s
                        - sourceType: %s
                        - sourceUri: %s
                        - contentHash: %s
                        - summary: %s
                        """.formatted(
                        item.title(),
                        item.sourceType(),
                        item.sourceUri(),
                        item.contentHash(),
                        item.summary()
                ).strip())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    private String jsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream().map(this::json).reduce((left, right) -> left + "," + right)
                .map(value -> "[" + value + "]")
                .orElse("[]");
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

    private record ProviderMetadata(
            String providerName,
            String providerAttemptsJson,
            ProviderFallbackSideEffectSafety sideEffectSafety,
            boolean sideEffectSafetyDeclared,
            boolean sideEffectSafetyHostOwned
    ) {

        private ProviderMetadata {
            providerName = safe(providerName);
            providerAttemptsJson = providerAttemptsJson == null || providerAttemptsJson.isBlank()
                    ? "[]"
                    : providerAttemptsJson.strip();
            sideEffectSafety = sideEffectSafety == null
                    ? ProviderFallbackSideEffectSafety.unknown(
                            "provider result did not declare side-effect safety"
                    )
                    : sideEffectSafety;
        }

        private static ProviderMetadata empty() {
            return new ProviderMetadata(
                    "",
                    "[]",
                    ProviderFallbackSideEffectSafety.unknown(
                            "provider result did not declare side-effect safety"
                    ),
                    false,
                    false
            );
        }

        private boolean isEmpty() {
            return providerName.isBlank() && "[]".equals(providerAttemptsJson);
        }
    }

    private record SafetyMetadata(
            ProviderFallbackSideEffectSafety safety,
            boolean declared,
            boolean hostOwned
    ) {
        private SafetyMetadata {
            safety = safety == null
                    ? ProviderFallbackSideEffectSafety.unknown(
                            "provider result did not declare side-effect safety"
                    )
                    : safety;
        }

        private static SafetyMetadata undeclared() {
            return new SafetyMetadata(
                    ProviderFallbackSideEffectSafety.unknown(
                            "provider result did not declare side-effect safety"
                    ),
                    false,
                    false
            );
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

    private record TokenBudgetEstimate(
            long initialTokens,
            long retryReserveTokens,
            long estimatedTotalTokens,
            String confidence,
            String basis,
            boolean valid,
            String reason
    ) {
        private TokenBudgetEstimate {
            confidence = safe(confidence).toUpperCase(Locale.ROOT);
            basis = safe(basis);
            reason = safe(reason);
        }

        private static TokenBudgetEstimate invalid(String reason) {
            return new TokenBudgetEstimate(0L, 0L, 0L, "", "", false, reason);
        }
    }

    private record BudgetHistorySample(
            String scope,
            long actualTotalTokens,
            long completedAtEpochMillis
    ) {
        private BudgetHistorySample {
            scope = safe(scope);
            actualTotalTokens = Math.max(0L, actualTotalTokens);
            completedAtEpochMillis = Math.max(0L, completedAtEpochMillis);
        }
    }
}
