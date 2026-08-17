package com.wish.rd.engine.requirement;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.AgentStagePlanner;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.AgentStageTransitions;
import com.wish.rd.engine.agent.recovery.InterruptedStageRecoveryService;
import com.wish.rd.engine.agent.recovery.InterruptedStageWorkspaceRecoveryPort;
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
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import com.wish.rd.engine.retrieval.DeepRetrievalOrchestrator;
import com.wish.rd.engine.retrieval.model.RetrievalOutcome;
import com.wish.rd.rag.context.model.RoleContextPackage;
import com.wish.rd.rag.context.RoleContextPackageStore;
import com.wish.rd.rag.project.budget.RdProjectTokenBudgetService;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.RdTaskStatusEvent;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.TaskMaterialStore;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
import java.util.stream.Collectors;
import com.wish.rd.engine.requirement.model.AgentWorkflowPlan;
import com.wish.rd.engine.requirement.model.RequirementContextPackage;
import com.wish.rd.engine.requirement.model.RequirementDeliveryResult;
import com.wish.rd.engine.requirement.job.model.CommandDisposition;
import com.wish.rd.engine.requirement.job.model.ContinuationSpec;
import com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.engine.requirement.job.model.RequirementTaskMutation;
import com.wish.rd.engine.requirement.model.RequirementDeliveryReviewResult;
import com.wish.rd.engine.requirement.model.RequirementExecutionRequest;
import com.wish.rd.engine.requirement.model.RequirementExecutionResult;
import com.wish.rd.engine.requirement.model.RequirementPlan;
import com.wish.rd.engine.requirement.model.RequirementPolicyDecision;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublication;
import com.wish.rd.engine.requirement.model.RequirementBranchPublication;
import com.wish.rd.engine.requirement.model.RequirementBranchPublishCommand;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublishCommand;
import com.wish.rd.engine.requirement.review.AiDeliveryReviewEngine;
import com.wish.rd.engine.requirement.review.model.AiReviewRun;
import com.wish.rd.engine.requirement.review.model.AiReviewRunStatus;
import com.wish.rd.engine.retry.TaskRetryCheckpointStore;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.requirement.publication.RequirementOperationId;
import com.wish.rd.engine.requirement.publication.RequirementPublicationCommitPort;
import com.wish.rd.engine.requirement.publication.RequirementPublicationIntentFactory;
import com.wish.rd.engine.requirement.publication.RequirementPublicationLedger;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconciliationService;
import com.wish.rd.engine.requirement.publication.model.RequirementPublication;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationPrepareCommand;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationReplayDecision;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationStatus;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationProposal;
import com.wish.rd.engine.requirement.policy.RequirementPolicyRunStore;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRunState;
import com.wish.rd.engine.provider.ProviderSideEffectStatusPort;

/**
 * 需求交付编排引擎。
 *
 * <p>负责把已创建的 REQUIREMENT 任务转换成执行器输入，推进任务状态，并记录 PR 结果。
 * 多角色阶段主循环（240 行）已迁出到 {@link RequirementAgentStageOrchestrator}，
 * 本类仅负责高层 retry、policy、PR 复核与发布等链路编排。
 */
@Service
public class RequirementDeliveryEngine {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** 单角色阶段最大 attempt 数，防止协议失败引发的盲重试风暴（审查报告 F2）。 */
    private static final int MAX_ROLE_ATTEMPTS = 3;
    /** 单次回注的失败明细上限，防止巨型校验错误把 prompt 撑爆。 */
    private static final int MAX_FAILURE_FEEDBACK_CHARS = 4_000;
    /** 单个上游阶段随交接清单传导的环境备忘条数上限，防止 prompt 膨胀。 */
    private static final int MAX_ENVIRONMENT_NOTES = 8;
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
    private final RoleContextVersionManager roleContextVersionManager;
    private final AgentWorkflowAlertSinkPort alertSink;
    private final WorkflowExperienceStore experienceStore;
    private final RequirementDeliveryReviewer deliveryReviewer;
    private final RequirementPullRequestPublisherPort pullRequestPublisher;
    // Set-once via optional setter injection; defaults to a skipped no-op so the many
    // existing constructors and tests keep their behavior unchanged when no branch
    // publisher is wired. The bootstrap adapter pushes the reviewed work branch here
    // before the PR is created, closing the "head branch not found" (GitHub 422) gap.
    private RequirementBranchPublisherPort branchPublisher = RequirementBranchPublisherPort.unavailable();
    private final SnowflakeIdGenerator idGenerator;
    private RequirementContextRetrievalRecorder retrievalRecorder;
    private AiDeliveryReviewEngine aiDeliveryReviewEngine;
    private TaskRetryCheckpointStore taskRetryCheckpointStore;
    private RequirementPublicationLedger publicationLedger;
    private RequirementPublicationCommitPort publicationCommitPort;
    private RequirementPublicationReconcilePort publicationReconciler;
    private RequirementPublicationReconciliationService publicationReconciliationService;
    private RdProjectTokenBudgetService projectTokenBudgetService;
    private RequirementExecutionProfileResolverPort executionProfileResolver =
            RequirementExecutionProfileResolverPort.unavailable();
    private ProviderSideEffectStatusPort providerSideEffectStatusPort =
            ProviderSideEffectStatusPort.unavailable();
    private RequirementAgentStageOrchestrator stageOrchestrator;
    private InterruptedStageRecoveryService interruptedStageRecoveryService;
    private RequirementPolicyRunStore requirementPolicyRunStore;

    @Autowired(required = false)
    void setRequirementPolicyRunStore(RequirementPolicyRunStore requirementPolicyRunStore) {
        this.requirementPolicyRunStore = requirementPolicyRunStore;
    }

    @Autowired
    void setDeepRetrievalOrchestrator(DeepRetrievalOrchestrator orchestrator) {
        this.retrievalRecorder = orchestrator == null
                ? null
                : new RequirementContextRetrievalRecorder(orchestrator);
        // Keep the orchestrator's retrieval recorder in sync — it owns this collaborator.
        if (this.stageOrchestrator != null) {
            this.stageOrchestrator.setRetrievalRecorder(this.retrievalRecorder);
        }
    }

    @Autowired(required = false)
    void setAiDeliveryReviewEngine(AiDeliveryReviewEngine aiDeliveryReviewEngine) {
        this.aiDeliveryReviewEngine = aiDeliveryReviewEngine;
    }

    @Autowired(required = false)
    void setTaskRetryCheckpointStore(TaskRetryCheckpointStore taskRetryCheckpointStore) {
        this.taskRetryCheckpointStore = taskRetryCheckpointStore;
    }

    /**
     * Optional publication ledger. When absent, remote publish behavior is unchanged;
     * when present, PREPARED intents are recorded before branch/PR side effects.
     */
    @Autowired(required = false)
    public void setPublicationLedger(RequirementPublicationLedger publicationLedger) {
        this.publicationLedger = publicationLedger;
        installInMemoryPublicationCommitFallback();
        rebuildPublicationReconciliationService();
    }

    /**
     * Injects the host transaction boundary used to commit a confirmed pull request.
     *
     * <p>PostgreSQL wiring must provide this port whenever the publication ledger is active;
     * the engine fails closed rather than splitting the task and ledger writes.
     *
     * @param publicationCommitPort task/publication finalization port, if configured
     */
    @Autowired(required = false)
    public void setPublicationCommitPort(RequirementPublicationCommitPort publicationCommitPort) {
        this.publicationCommitPort = publicationCommitPort;
    }

    /**
     * Supplies the same port-shaped finalization boundary for in-memory assembly.
     *
     * <p>The fallback preserves lightweight tests and local memory mode without putting the
     * task/ledger pair back into the orchestration method. PostgreSQL configuration independently
     * requires a real transactional port, which replaces this fallback through setter injection.
     */
    private void installInMemoryPublicationCommitFallback() {
        if (publicationLedger == null || publicationCommitPort != null) {
            return;
        }
        publicationCommitPort = command -> {
            RdRequirementTask committed = taskRegistry.markRequirementCommittedFenced(
                    command.taskId(),
                    command.expectedVersion(),
                    command.expectedStatus(),
                    command.expectedFencingToken(),
                    command.pullRequestUrl(),
                    command.executionResultJson());
            publicationLedger.markCommitted(command.operationId());
            return committed;
        };
    }

    /**
     * Optional UNKNOWN_REMOTE_RESULT reconciler. When absent, WAIT_RECONCILE remains
     * a hard block until an operator or later adapter advances the ledger.
     */
    @Autowired(required = false)
    public void setPublicationReconciler(RequirementPublicationReconcilePort publicationReconciler) {
        this.publicationReconciler = publicationReconciler;
        rebuildPublicationReconciliationService();
    }

    private void rebuildPublicationReconciliationService() {
        if (publicationLedger == null) {
            this.publicationReconciliationService = null;
            return;
        }
        this.publicationReconciliationService = new RequirementPublicationReconciliationService(
                publicationLedger,
                publicationReconciler
        );
    }

    @Autowired(required = false)
    void setProjectTokenBudgetService(RdProjectTokenBudgetService projectTokenBudgetService) {
        this.projectTokenBudgetService = projectTokenBudgetService;
    }

    @Autowired(required = false)
    void setExecutionProfileResolver(RequirementExecutionProfileResolverPort executionProfileResolver) {
        this.executionProfileResolver = executionProfileResolver == null
                ? RequirementExecutionProfileResolverPort.unavailable()
                : executionProfileResolver;
        // Keep the orchestrator's resolver in sync — orchestrator owns this collaborator
        // (no longer a bridge callback into the engine), but tests wire it after construction.
        if (this.stageOrchestrator != null) {
            this.stageOrchestrator.setExecutionProfileResolver(this.executionProfileResolver);
        }
    }

    @Autowired(required = false)
    void setProviderSideEffectStatusPort(ProviderSideEffectStatusPort providerSideEffectStatusPort) {
        this.providerSideEffectStatusPort = providerSideEffectStatusPort == null
                ? ProviderSideEffectStatusPort.unavailable()
                : providerSideEffectStatusPort;
        if (this.stageOrchestrator != null) {
            this.stageOrchestrator.setProviderSideEffectStatusPort(this.providerSideEffectStatusPort);
        }
    }

    /**
     * Wired by Spring after construction when {@link RequirementAgentStageOrchestrator} is a bean.
     * The engine never reaches into orchestrator internals — it only delegates to its public API.
     * Tests / non-Spring code paths keep working because the constructor already builds a default
     * orchestrator; this setter only overrides when an explicit bean is present.
     */
    @Autowired(required = false)
    void setStageOrchestrator(RequirementAgentStageOrchestrator stageOrchestrator) {
        if (stageOrchestrator != null) {
            this.stageOrchestrator = stageOrchestrator;
            this.stageOrchestrator.setProviderSideEffectStatusPort(providerSideEffectStatusPort);
        }
    }

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
        this.idGenerator = safeIdGenerator(idGenerator);
        this.roleContextVersionManager = new RoleContextVersionManager(
                this.roleContextBuilder, this.roleContextPackageStore, this.idGenerator::nextIdString, 18_000);
        this.alertSink = alertSink == null ? AgentWorkflowAlertSinkPort.noop() : alertSink;
        this.experienceStore = experienceStore == null ? WorkflowExperienceStore.noop() : experienceStore;
        this.deliveryReviewer = deliveryReviewer == null ? new RequirementDeliveryReviewer() : deliveryReviewer;
        this.pullRequestPublisher = pullRequestPublisher == null
                ? RequirementPullRequestPublisherPort.unavailable()
                : pullRequestPublisher;
        // Stage orchestrator is now self-contained: it owns its collaborators and the 240-line
        // stage loop. The engine only holds an injection point and any explicitly-injected
        // orchestrator will overwrite this default via {@link
        // #setStageOrchestrator(RequirementAgentStageOrchestrator)} when Spring resolves the bean.
        // Reuse the same RoleContextVersionManager instance so engine-side ensureLatestContexts
        // and orchestrator-side ensureLatestContext stay consistent.
        this.stageOrchestrator = new RequirementAgentStageOrchestrator(
                stageRunStore,
                artifactStore,
                roleContextPackageStore,
                this.roleContextVersionManager,
                retrievalRecorder,
                executionProfileResolver,
                projectTokenBudgetService,
                alertSink,
                experienceStore,
                taskRegistry,
                executor,
                this.idGenerator
        );
        this.interruptedStageRecoveryService = new InterruptedStageRecoveryService(
                InterruptedStageWorkspaceRecoveryPort.unavailable(),
                stageRunStore,
                artifactStore,
                this.idGenerator::nextIdString
        );
    }

    @Autowired(required = false)
    void setInterruptedStageWorkspaceRecovery(InterruptedStageWorkspaceRecoveryPort workspaceRecoveryPort) {
        this.interruptedStageRecoveryService = new InterruptedStageRecoveryService(
                workspaceRecoveryPort,
                stageRunStore,
                artifactStore,
                idGenerator::nextIdString
        );
    }

    /** Direct injection for tests and non-Spring assembly. */
    public void setInterruptedStageRecoveryService(InterruptedStageRecoveryService interruptedStageRecoveryService) {
        this.interruptedStageRecoveryService = interruptedStageRecoveryService == null
                ? new InterruptedStageRecoveryService(
                        InterruptedStageWorkspaceRecoveryPort.unavailable(),
                        stageRunStore,
                        artifactStore,
                        idGenerator::nextIdString
                )
                : interruptedStageRecoveryService;
    }

    /**
     * 可选注入工作分支推送端口。使用 setter 注入避免改动众多既有构造函数：未配置时
     * 字段保持 {@link RequirementBranchPublisherPort#unavailable()} 跳过语义。
     */
    @Autowired(required = false)
    public void setBranchPublisher(ObjectProvider<RequirementBranchPublisherPort> branchPublisherProvider) {
        if (branchPublisherProvider != null) {
            this.branchPublisher = branchPublisherProvider.getIfAvailable(RequirementBranchPublisherPort::unavailable);
        }
    }

    /** 直接注入分支推送端口，供测试与非 Spring 组装场景使用。 */
    public void setBranchPublisher(RequirementBranchPublisherPort branchPublisher) {
        this.branchPublisher = branchPublisher == null
                ? RequirementBranchPublisherPort.unavailable()
                : branchPublisher;
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
        if (isRetryableRequirementStatus(requirementTask.status())) {
            if (requirementTask.status() != RdTaskStatus.RECOVERING) {
                requirementTask = (RdRequirementTask) taskRegistry.markRecovering(
                        requirementTask.taskId(), "创建新的角色阶段尝试");
            }
        }
        TaskRetryCheckpoint activeRetry = activeDispatchedRetry(requirementTask.taskId());
        if (activeRetry != null && activeRetry.failurePhase() == TaskFailurePhase.PR_PUBLICATION) {
            return resumePullRequestPublication(requirementTask);
        }
        if (activeRetry != null && activeRetry.failurePhase() == TaskFailurePhase.DETERMINISTIC_REVIEW) {
            return validateAndPublish(requirementTask, recoverExecutionResult(requirementTask), false);
        }
        if (activeRetry != null && activeRetry.failurePhase() == TaskFailurePhase.AI_REVIEW
                && activeRetry.retryFromRole() == null) {
            return validateAndPublish(requirementTask, recoverExecutionResult(requirementTask), true);
        }
        // 先补齐阶段运行记录和角色上下文（幂等创建），确保每次提交都能有完整审计闭环。
        ensureRequirementStages(requirementTask);
        ensureRoleContexts(requirementTask, materials, activeRetry);

        // 状态机推进（可重入）主链路：
        // 主状态链：
        // CREATED -> MATERIAL_COLLECTING -> MATERIAL_READY -> CONTEXT_BUILDING -> CONTEXT_READY
        // -> PLAN_GENERATING -> PLAN_GENERATED -> WAITING_POLICY -> EXECUTING -> VALIDATING
        // -> PR_CREATING -> COMMITTED -> REPORTING -> COMPLETED
        // 失败分支：
        // WAITING_POLICY -> WAITING_APPROVAL、WAITING_POLICY -> FAILED_NEEDS_HUMAN
        // RECOVERING -> WAITING_APPROVAL（恢复后再入审批，依赖状态机边，禁止裸抛）
        // EXECUTING -> FAILED_NEEDS_HUMAN、EXECUTING -> REJECTED
        // VALIDATING -> REJECTED
        // PR_CREATING -> COMMITTED、PR_CREATING -> REJECTED、PR_CREATING -> FAILED_NEEDS_HUMAN
        // （远端冲突/marker 不匹配升人工，超时/5xx 仍走 REJECTED+UNKNOWN ledger）
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
        // 已由管理台审批过的 WAITING_APPROVAL 任务再次提交时，视为人工放行，避免同一规则反复拦截。
        boolean approvedByHuman = requirementTask.status() == RdTaskStatus.WAITING_APPROVAL
                && hasApprovalEvent(requirementTask.taskId());
        RequirementPolicyDecision policyDecision = approvedByHuman
                ? new RequirementPolicyDecision("ALLOWED", "APPROVED", "人工审批通过，允许进入沙箱执行")
                : policyGate.decide(requirementTask, context, plan, materials);
        if (requirementTask.status().name().equals("PLAN_GENERATED")) {
            // WAITING_POLICY：写入策略决策快照，保证可追溯与可重试。
            requirementTask = taskRegistry.markRequirementWaitingPolicy(requirementTask.taskId(), policyDecision.toJson());
        }
        if (!policyDecision.allowed()) {
            if (policyDecision.waitingApproval()) {
                // WAITING_POLICY / RECOVERING -> WAITING_APPROVAL：策略要求外部审批，任务在此暂停。
                RdRequirementTask waitingApproval = taskRegistry.markRequirementWaitingApproval(
                        requirementTask.taskId(),
                        policyDecision.toJson()
                );
                publishTaskLifecycleAlert(waitingApproval.taskId(), AgentWorkflowAlertType.TASK_BLOCKED,
                        "需求任务等待策略审批", "完成策略审批后恢复任务");
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
            publishTaskLifecycleAlert(failed.taskId(), AgentWorkflowAlertType.TASK_BLOCKED,
                    policyDecision.reason(), "人工复核策略门控");
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
                policyDecision,
                activeRetry
        );
        RdTask afterStages = taskRegistry.getTask(requirementTask.taskId());
        if (afterStages.status() == RdTaskStatus.WAITING_APPROVAL) {
            return currentResult((RdRequirementTask) afterStages);
        }
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
                publishTaskLifecycleAlert(failed.taskId(), AgentWorkflowAlertType.TASK_BLOCKED,
                        reason, "人工处理阶段阻塞");
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
            publishTaskLifecycleAlert(rejected.taskId(), AgentWorkflowAlertType.TASK_FAILED,
                    reason, "检查阶段产物和失败日志");
            return new RequirementDeliveryResult(
                    rejected.taskId(),
                    rejected.status(),
                    "",
                    rejected.executionResultJson(),
                    rejected.errorMessage()
            );
        }
        return validateAndPublish(requirementTask, executionResult, false);
    }

    /**
     * Executes a leased stage through the delivery engine's current stage implementation.
     *
     * <p>The command boundary is deliberately explicit even while the legacy workflow still
     * owns some multi-stage transitions. Workers must present the task snapshot version and
     * fencing token captured at enqueue time; a late lease cannot silently refresh and continue
     * against a newer task snapshot.
     *
     * @param command leased stage command
     * @return delivery outcome
     */
    public RequirementDeliveryResult executeStage(RequirementStageCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("stage command must not be null");
        }
        RdTask current = taskRegistry.getTask(command.taskId());
        boolean versionMismatch = current.version() != command.taskVersion();
        boolean fencingMismatch = current.fencingToken() <= 0L
                || command.fencingToken() <= 0L
                || current.fencingToken() != command.fencingToken();
        if (versionMismatch || fencingMismatch) {
            throw new IllegalStateException("stage command fencing mismatch: " + command.commandId());
        }
        if (!(current instanceof RdRequirementTask task)) {
            throw new IllegalArgumentException("stage command task is not a requirement: " + command.taskId());
        }
        return switch (command.stage()) {
            case "MATERIAL_COLLECTING" -> stageResult(taskRegistry.transitionRequirementFenced(
                    task, RdTaskStatus.MATERIAL_COLLECTING, "", "", "", ""));
            case "MATERIAL_READY" -> stageResult(taskRegistry.transitionRequirementFenced(
                    task, RdTaskStatus.MATERIAL_READY, "", "", "", ""));
            case "CONTEXT_BUILDING" -> stageResult(taskRegistry.transitionRequirementFenced(
                    task, RdTaskStatus.CONTEXT_BUILDING, "", "", "", ""));
            case "CONTEXT_READY" -> {
                List<com.wish.rd.rag.runtime.model.TaskMaterial> materials = materialStore.listByTask(task.taskId());
                RequirementContextPackage context = contextBuilder.build(task, materials);
                yield stageResult(taskRegistry.transitionRequirementFenced(
                        task, RdTaskStatus.CONTEXT_READY, "", context.toJson(), "", ""));
            }
            case "PLAN_GENERATING" -> stageResult(taskRegistry.transitionRequirementFenced(
                    task, RdTaskStatus.PLAN_GENERATING, "", "", "", ""));
            case "PLAN_GENERATED" -> {
                List<com.wish.rd.rag.runtime.model.TaskMaterial> materials = materialStore.listByTask(task.taskId());
                RequirementContextPackage context = contextBuilder.build(task, materials);
                RequirementPlan plan = planGenerator.generate(task, context);
                yield stageResult(taskRegistry.transitionRequirementFenced(
                        task, RdTaskStatus.PLAN_GENERATED, "", plan.toJson(), "", ""));
            }
            case "POLICY" -> executePolicyStage(task);
            case "ROLE_EXECUTION" -> executeRoleStage(task, command, AgentRole.REQUIREMENT_REVIEWER);
            case String roleStage when roleStage.startsWith("ROLE_EXECUTION:") ->
                    executeRoleStage(task, command, parseRoleStage(roleStage));
            case "DETERMINISTIC_REVIEW" -> executeDeterministicReviewStage(task);
            case "AI_REVIEW" -> executeAiReviewStage(task);
            case "PUBLICATION" -> executePublicationStage(task);
            case String publicationStage when publicationStage.startsWith("PUBLICATION:") ->
                    executeReconciledPublicationStage(task);
            case "REPORTING" -> executeReportingStage(task);
            case "COMPLETION" -> executeCompletionStage(task);
            default -> throw new IllegalArgumentException("unsupported requirement stage: " + command.stage());
        };
    }

    /**
     * Produces the durable, non-mutating proposal for a bounded setup or policy stage.
     *
     * <p>The Host finalizer is the only component permitted to apply these mutations. This method
     * reads the task snapshot exactly once, rejects a stale command fence, and then only invokes
     * deterministic context, plan, and policy collaborators.
     *
     * @param command leased command carrying the expected task version and fencing token
     * @return immutable mutation plan for Host-owned atomic finalization
     * @throws IllegalArgumentException when the command is absent, targets another task type, or
     *                                  names a stage outside this bounded proposal slice
     * @throws IllegalStateException when the command no longer matches the current task snapshot
     */
    public RequirementStageExecutionPlan planStage(RequirementStageCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("stage command must not be null");
        }
        // The sole registry read establishes the snapshot which the Host will later CAS.
        RdTask current = taskRegistry.getTask(command.taskId());
        boolean versionMismatch = current.version() != command.taskVersion();
        boolean fencingMismatch = current.fencingToken() <= 0L
                || command.fencingToken() <= 0L
                || current.fencingToken() != command.fencingToken();
        if (versionMismatch || fencingMismatch) {
            throw new IllegalStateException("stage command fencing mismatch: " + command.commandId());
        }
        if (!(current instanceof RdRequirementTask task)) {
            throw new IllegalArgumentException("stage command task is not a requirement: " + command.taskId());
        }
        return switch (command.stage()) {
            case "MATERIAL_COLLECTING" -> simpleStagePlan(
                    task, command, RdTaskStatus.MATERIAL_COLLECTING, "MATERIAL_READY");
            case "MATERIAL_READY" -> simpleStagePlan(
                    task, command, RdTaskStatus.MATERIAL_READY, "CONTEXT_BUILDING");
            case "CONTEXT_BUILDING" -> simpleStagePlan(
                    task, command, RdTaskStatus.CONTEXT_BUILDING, "CONTEXT_READY");
            case "CONTEXT_READY" -> planContextReadyStage(task, command);
            case "PLAN_GENERATING" -> simpleStagePlan(
                    task, command, RdTaskStatus.PLAN_GENERATING, "PLAN_GENERATED");
            case "PLAN_GENERATED" -> planPlanGeneratedStage(task, command);
            case "POLICY" -> planPolicyStage(task, command);
            case String roleStage when roleStage.startsWith("ROLE_EXECUTION:") ->
                    planRoleExecutionStage(task, command, parseRoleStage(roleStage));
            case "DETERMINISTIC_REVIEW" -> planDeterministicReviewStage(task, command);
            case "AI_REVIEW" -> planAiReviewStage(task, command);
            case "PUBLICATION" -> planPublicationStage(task, command, false);
            case String publicationStage when publicationStage.startsWith("PUBLICATION:") ->
                    planPublicationStage(task, command, true);
            case "REPORTING" -> planReportingStage(task, command);
            case "COMPLETION" -> planCompletionStage(task, command);
            default -> throw new IllegalArgumentException("unsupported non-mutating requirement stage: " + command.stage());
        };
    }

    /**
     * Evaluates policy against the exact plan JSON already persisted by {@code PLAN_GENERATED}.
     *
     * <p>This method is deliberately read-only. It never regenerates the plan and performs no
     * task, command, or ledger write, so the dispatcher can invoke it before opening the host
     * policy transaction.
     */
    public RequirementPolicyEvaluationProposal evaluateFrozenPolicy(RequirementStageCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("policy-evaluate command must not be null");
        }
        if (!"REQUIREMENT_DELIVERY".equals(command.role())
                || !"POLICY_EVALUATE".equals(command.stage())) {
            throw new IllegalStateException("policy-evaluate command identity is invalid: " + command.commandId());
        }
        RdTask current = taskRegistry.getTask(command.taskId());
        if (!(current instanceof RdRequirementTask task)
                || task.status() != RdTaskStatus.PLAN_GENERATED
                || task.version() != command.taskVersion()
                || task.fencingToken() <= 0L
                || command.fencingToken() <= 0L
                || task.fencingToken() != command.fencingToken()) {
            throw new IllegalStateException("policy-evaluate command fencing or task status is stale: "
                    + command.commandId());
        }
        String frozenPlanJson;
        RequirementPlan frozenPlan;
        try {
            frozenPlanJson = RequirementPolicyRun.canonicalizeJson(task.executionResultJson());
            frozenPlan = OBJECT_MAPPER.readValue(frozenPlanJson, RequirementPlan.class);
        } catch (RuntimeException | JsonProcessingException invalidPlan) {
            throw new IllegalStateException("PLAN_GENERATED task has no valid frozen plan: " + task.taskId(),
                    invalidPlan);
        }
        if (!task.taskId().equals(frozenPlan.taskId())) {
            throw new IllegalStateException("frozen plan targets another requirement task: " + task.taskId());
        }
        List<TaskMaterial> materials = taskMaterials(task);
        RequirementContextPackage context = contextBuilder.build(task, materials);
        RequirementPolicyDecision decision = policyGate.decide(task, context, frozenPlan, materials);
        String policyJson = RequirementPolicyRun.canonicalizeJson(decision.toJson());
        return new RequirementPolicyEvaluationProposal(
                task.taskId(), task.version(), task.fencingToken(),
                frozenPlanJson, RequirementPolicyRun.canonicalJsonDigest(frozenPlanJson),
                policyJson, RequirementPolicyRun.canonicalJsonDigest(policyJson), decision.action());
    }

    private RequirementStageExecutionPlan simpleStagePlan(
            RdRequirementTask task,
            RequirementStageCommand command,
            RdTaskStatus targetStatus,
            String continuationStage
    ) {
        return plan(task, command,
                List.of(mutation(task.status(), targetStatus, "", "", "", "")),
                CommandDisposition.SUCCEEDED,
                new ContinuationSpec("REQUIREMENT_DELIVERY", continuationStage));
    }

    private RequirementStageExecutionPlan planContextReadyStage(
            RdRequirementTask task,
            RequirementStageCommand command
    ) {
        RequirementContextPackage context = contextBuilder.build(task, taskMaterials(task));
        return plan(task, command,
                List.of(mutation(task.status(), RdTaskStatus.CONTEXT_READY, "", context.toJson(), "", "")),
                CommandDisposition.SUCCEEDED,
                new ContinuationSpec("REQUIREMENT_DELIVERY", "PLAN_GENERATING"));
    }

    private RequirementStageExecutionPlan planPlanGeneratedStage(
            RdRequirementTask task,
            RequirementStageCommand command
    ) {
        List<TaskMaterial> materials = taskMaterials(task);
        RequirementContextPackage context = contextBuilder.build(task, materials);
        RequirementPlan generatedPlan = planGenerator.generate(task, context);
        return plan(task, command,
                List.of(mutation(task.status(), RdTaskStatus.PLAN_GENERATED, "", generatedPlan.toJson(), "", "")),
                CommandDisposition.SUCCEEDED,
                new ContinuationSpec("REQUIREMENT_DELIVERY", "POLICY_EVALUATE"));
    }

    private RequirementStageExecutionPlan planPolicyStage(
            RdRequirementTask task,
            RequirementStageCommand command
    ) {
        List<TaskMaterial> materials = taskMaterials(task);
        RequirementContextPackage context = contextBuilder.build(task, materials);
        RequirementPlan generatedPlan = planGenerator.generate(task, context);
        RequirementPolicyDecision decision = policyGate.decide(task, context, generatedPlan, materials);
        RequirementTaskMutation waitingPolicy = mutation(
                task.status(), RdTaskStatus.WAITING_POLICY, "", decision.toJson(), "", "");
        if (decision.allowed()) {
            return plan(task, command,
                    List.of(waitingPolicy, mutation(RdTaskStatus.WAITING_POLICY, RdTaskStatus.EXECUTING,
                            buildPrompt(task, materials, context, generatedPlan, decision), "", "", "")),
                    CommandDisposition.SUCCEEDED,
                    new ContinuationSpec(AgentRole.REQUIREMENT_REVIEWER.name(),
                            "ROLE_EXECUTION:" + AgentRole.REQUIREMENT_REVIEWER.name()));
        }
        if (decision.waitingApproval()) {
            return plan(task, command,
                    List.of(waitingPolicy, mutation(RdTaskStatus.WAITING_POLICY, RdTaskStatus.WAITING_APPROVAL,
                            "", decision.toJson(), "", "")),
                    CommandDisposition.SUCCEEDED,
                    ContinuationSpec.terminal());
        }
        return plan(task, command,
                List.of(waitingPolicy, mutation(RdTaskStatus.WAITING_POLICY, RdTaskStatus.FAILED_NEEDS_HUMAN,
                        "", policyBlockedResult(decision), "", decision.reason())),
                CommandDisposition.TERMINAL_FAILURE,
                ContinuationSpec.terminal());
    }

    /**
     * Executes one durably authorized role while leaving the requirement task untouched.
     *
     * <p>The resulting same-status snapshot update is intentionally only a proposal. The host
     * finalizer owns the fenced task/timeline mutation and the continuation enqueue, so a worker
     * cannot commit either half of a role outcome on its own.
     */
    private RequirementStageExecutionPlan planRoleExecutionStage(
            RdRequirementTask task, RequirementStageCommand command, AgentRole role
    ) {
        boolean checkpointRecovery = task.status() == RdTaskStatus.RECOVERING
                && !command.retryCheckpointId().isBlank();
        if (task.status() != RdTaskStatus.EXECUTING && !checkpointRecovery) {
            throw new IllegalStateException("role command task is not executing: " + command.commandId());
        }
        RequirementPolicyRun authorization = requireRoleAuthorization(task, command, role);
        List<com.wish.rd.rag.runtime.model.TaskMaterial> materials = taskMaterials(task);
        RequirementContextPackage context = contextBuilder.build(task, materials);
        RequirementPlan plan = parseAuthorizedPlan(authorization);
        RequirementPolicyDecision decision = parseAuthorizedPolicy(authorization);
        // Agent-stage records are orchestration evidence, not task/timeline state. The Host later
        // applies the returned task mutation atomically with the command completion.
        ensureRequirementStages(task);
        ensureRoleContexts(task, materials, null);
        RequirementExecutionResult execution = stageOrchestrator.run(
                boundedRolePlan(role), task, materials, context, plan, decision, null);
        if (!execution.success()) {
            boolean needsHuman = needsHumanInterventionResult(execution);
            RdTaskStatus failureStatus = needsHuman
                    ? RdTaskStatus.FAILED_NEEDS_HUMAN
                    : RdTaskStatus.FAILED_RETRYABLE;
            List<RequirementTaskMutation> mutations = new ArrayList<>();
            if (checkpointRecovery) {
                // 检查点初始化必须停在 RECOVERING；首个精确角色命令在同一最终化事务中恢复执行态。
                mutations.add(mutation(RdTaskStatus.RECOVERING, RdTaskStatus.EXECUTING, "", "", "", ""));
            }
            mutations.add(mutation(RdTaskStatus.EXECUTING, failureStatus, "", execution.resultJson(), "",
                    execution.errorMessage()));
            return plan(task, command, mutations,
                    needsHuman ? CommandDisposition.TERMINAL_FAILURE
                            : CommandDisposition.RETRYABLE_TECHNICAL_FAILURE,
                    ContinuationSpec.terminal());
        }
        List<RequirementTaskMutation> mutations = new ArrayList<>();
        if (checkpointRecovery) {
            // 同上：只允许携带精确 checkpoint 身份的首个角色命令走这条恢复前缀。
            mutations.add(mutation(RdTaskStatus.RECOVERING, RdTaskStatus.EXECUTING, "", "", "", ""));
        }
        mutations.add(RequirementTaskMutation.snapshotUpdate(
                RdTaskStatus.EXECUTING, "", execution.resultJson(), execution.pullRequestUrl(), "", ""));
        return plan(task, command, mutations,
                CommandDisposition.SUCCEEDED,
                roleContinuation(role));
    }

    private RequirementStageExecutionPlan planDeterministicReviewStage(
            RdRequirementTask task,
            RequirementStageCommand command
    ) {
        requireStageStatus(task, command, RdTaskStatus.EXECUTING);
        RequirementExecutionResult execution = recoverExecutionResult(task);
        RequirementDeliveryReviewResult review = deliveryReviewer.review(task.taskId(), execution.resultJson());
        String reviewedResultJson = withDeliveryReviewJson(execution.resultJson(), review);
        List<RequirementTaskMutation> mutations = new ArrayList<>();
        mutations.add(mutation(task.status(), RdTaskStatus.VALIDATING, "", execution.resultJson(), "", ""));
        if (!review.approved()) {
            mutations.add(mutation(RdTaskStatus.VALIDATING, RdTaskStatus.REJECTED, "", reviewedResultJson,
                    "", "delivery review failed: " + review.reason()));
            return plan(task, command, mutations, CommandDisposition.TERMINAL_FAILURE, ContinuationSpec.terminal());
        }
        mutations.add(RequirementTaskMutation.snapshotUpdate(
                RdTaskStatus.VALIDATING, "", reviewedResultJson, "", "", ""));
        return plan(task, command, mutations, CommandDisposition.SUCCEEDED,
                new ContinuationSpec("REQUIREMENT_DELIVERY", "AI_REVIEW"));
    }

    private RequirementStageExecutionPlan planAiReviewStage(
            RdRequirementTask task,
            RequirementStageCommand command
    ) {
        requireStageStatus(task, command, RdTaskStatus.VALIDATING);
        if (aiDeliveryReviewEngine == null || !aiDeliveryReviewEngine.isEnabled()) {
            return plan(task, command, List.of(), CommandDisposition.SUCCEEDED,
                    new ContinuationSpec("REQUIREMENT_DELIVERY", "PUBLICATION"));
        }
        AiReviewRun reviewRun = aiDeliveryReviewEngine.review(
                task, existingDeliveryReviewJson(task.executionResultJson()), "AUTO");
        String reviewedResultJson = withAiReviewJson(task.executionResultJson(), reviewRun);
        if (reviewRun.status() == AiReviewRunStatus.FAILED_RETRYABLE) {
            String reason = reviewRun.errorMessage().isBlank()
                    ? "AI delivery review failed and can be retried" : reviewRun.errorMessage();
            return plan(task, command, List.of(mutation(task.status(), RdTaskStatus.FAILED_RETRYABLE,
                    "", reviewedResultJson, "", reason)), CommandDisposition.RETRYABLE_TECHNICAL_FAILURE,
                    ContinuationSpec.terminal());
        }
        if (reviewRun.status() == AiReviewRunStatus.SUCCEEDED_NOT_OK
                || reviewRun.status() == AiReviewRunStatus.SUCCEEDED_NEEDS_HUMAN) {
            String reason = reviewRun.summary().isBlank()
                    ? "AI delivery review requires human intervention" : reviewRun.summary();
            return plan(task, command, List.of(mutation(task.status(), RdTaskStatus.FAILED_NEEDS_HUMAN,
                    "", reviewedResultJson, "", reason)), CommandDisposition.TERMINAL_FAILURE,
                    ContinuationSpec.terminal());
        }
        if (reviewRun.status() != AiReviewRunStatus.SUCCEEDED_OK) {
            throw new IllegalStateException("AI delivery review did not reach a terminal decision: "
                    + reviewRun.status());
        }
        return plan(task, command, List.of(RequirementTaskMutation.snapshotUpdate(
                RdTaskStatus.VALIDATING, "", reviewedResultJson, "", "", "")),
                CommandDisposition.SUCCEEDED,
                new ContinuationSpec("REQUIREMENT_DELIVERY", "PUBLICATION"));
    }

    private RequirementStageExecutionPlan planPublicationStage(
            RdRequirementTask task,
            RequirementStageCommand command,
            boolean recovery
    ) {
        String reviewedResultJson = task.executionResultJson();
        List<RequirementTaskMutation> mutations = new ArrayList<>();
        RdTaskStatus publishStatus = task.status();
        if (recovery && publishStatus == RdTaskStatus.REJECTED) {
            mutations.add(mutation(RdTaskStatus.REJECTED, RdTaskStatus.RECOVERING,
                    "", reviewedResultJson, "", ""));
            publishStatus = RdTaskStatus.RECOVERING;
        }
        if (publishStatus != RdTaskStatus.VALIDATING
                && publishStatus != RdTaskStatus.RECOVERING
                && publishStatus != RdTaskStatus.PR_CREATING) {
            throw new IllegalStateException("publication command has unsupported task status: " + task.status());
        }
        if (publishStatus != RdTaskStatus.PR_CREATING) {
            mutations.add(mutation(publishStatus, RdTaskStatus.PR_CREATING, "", reviewedResultJson, "", ""));
            publishStatus = RdTaskStatus.PR_CREATING;
        }
        if (isPatchOnlyDelivery(task)) {
            mutations.add(mutation(publishStatus, RdTaskStatus.COMMITTED, "", reviewedResultJson, "", ""));
            return plan(task, command, mutations, CommandDisposition.SUCCEEDED,
                    new ContinuationSpec("REQUIREMENT_DELIVERY", "REPORTING"));
        }
        if (publicationLedger == null) {
            throw new IllegalStateException("publication proposal requires a durable publication ledger");
        }
        String operationId = publicationOperationId(task, reviewedResultJson);
        if (operationId.isBlank()) {
            return publicationFailurePlan(task, command, mutations, reviewedResultJson,
                    "publication requires a candidate-patch operation id", true);
        }
        preparePublicationIntent(task, reviewedResultJson);
        PublicationRemotePlan remotePlan = resolvePublicationRemotePlan(task, reviewedResultJson);
        if (remotePlan.blockedReason() != null) {
            return publicationFailurePlan(task, command, mutations, reviewedResultJson,
                    remotePlan.blockedReason(), remotePlan.needsHuman());
        }
        RequirementPullRequestPublication publication = remotePlan.reusedPublication();
        if (publication == null) {
            if (remotePlan.pushBranch()) {
                String branchError = pushReviewedBranch(task, reviewedResultJson);
                if (!branchError.isBlank()) {
                    return publicationRemoteFailurePlan(
                            task, command, mutations, reviewedResultJson, branchError);
                }
            }
            publication = publishPullRequest(task, reviewedResultJson);
        }
        if (!publication.success() || publication.pullRequestUrl().isBlank()) {
            String reason = publication.errorMessage().isBlank()
                    ? "pull request publication failed" : publication.errorMessage();
            return publicationRemoteFailurePlan(task, command, mutations, reviewedResultJson, reason);
        }
        confirmPublicationPullRequest(task, reviewedResultJson, publication);
        RequirementPublication durablePublication = publicationLedger.findByOperationId(operationId).orElseThrow(
                () -> new IllegalStateException("publication ledger entry disappeared: " + operationId));
        if (durablePublication.status() != RequirementPublicationStatus.PR_CONFIRMED
                && durablePublication.status() != RequirementPublicationStatus.COMMITTED) {
            throw new IllegalStateException("publication did not reach a finalizable receipt state: "
                    + durablePublication.status());
        }
        RequirementPullRequestPublication confirmedPublication = RequirementPullRequestPublication.success(
                task.taskId(), durablePublication.pullRequestUrl(),
                Integer.toString(durablePublication.pullRequestNumber()), publication.metadataJson());
        String publishedJson = withPullRequestPublicationJson(reviewedResultJson, confirmedPublication);
        mutations.add(mutation(publishStatus, RdTaskStatus.COMMITTED, "", publishedJson,
                durablePublication.pullRequestUrl(), ""));
        ExternalEffectReceipt receipt = new ExternalEffectReceipt(
                ExternalEffectReceipt.Kind.PUBLICATION,
                durablePublication.operationId(),
                durablePublication.status().name(),
                "{\"taskId\":" + json(task.taskId())
                        + ",\"operationId\":" + json(durablePublication.operationId())
                        + ",\"pullRequestUrl\":" + json(durablePublication.pullRequestUrl())
                        + ",\"pullRequestNumber\":" + durablePublication.pullRequestNumber() + "}");
        return plan(task, command, mutations, CommandDisposition.SUCCEEDED,
                new ContinuationSpec("REQUIREMENT_DELIVERY", "REPORTING"), receipt);
    }

    private RequirementStageExecutionPlan publicationFailurePlan(
            RdRequirementTask task,
            RequirementStageCommand command,
            List<RequirementTaskMutation> prefix,
            String reviewedResultJson,
            String reason,
            boolean needsHuman
    ) {
        RdTaskStatus fromStatus = prefix.isEmpty() ? task.status() : prefix.getLast().toStatus();
        RdTaskStatus targetStatus = needsHuman ? RdTaskStatus.FAILED_NEEDS_HUMAN : RdTaskStatus.FAILED_RETRYABLE;
        List<RequirementTaskMutation> mutations = new ArrayList<>(prefix);
        mutations.add(mutation(fromStatus, targetStatus, "", reviewedResultJson, "", reason));
        CommandDisposition disposition = needsHuman
                ? CommandDisposition.TERMINAL_FAILURE : CommandDisposition.RETRYABLE_TECHNICAL_FAILURE;
        ExternalEffectReceipt receipt = publicationFailureReceipt(task, reviewedResultJson);
        return plan(task, command, mutations, disposition, ContinuationSpec.terminal(), receipt);
    }

    private ExternalEffectReceipt publicationFailureReceipt(RdRequirementTask task, String reviewedResultJson) {
        if (publicationLedger == null) {
            return ExternalEffectReceipt.none();
        }
        String operationId = publicationOperationId(task, reviewedResultJson);
        if (operationId.isBlank()) {
            return ExternalEffectReceipt.none();
        }
        RequirementPublication ledger = publicationLedger.findByOperationId(operationId).orElseThrow(
                () -> new IllegalStateException("publication ledger entry missing: " + operationId));
        if (!task.taskId().equals(ledger.taskId())) {
            throw new IllegalStateException("publication ledger task mismatch: " + operationId);
        }
        String receiptJson = "{\"taskId\":" + json(task.taskId())
                + ",\"operationId\":" + json(ledger.operationId()) + "}";
        return new ExternalEffectReceipt(
                ExternalEffectReceipt.Kind.PUBLICATION,
                ledger.operationId(),
                ledger.status().name(),
                receiptJson);
    }

    private RequirementStageExecutionPlan publicationRemoteFailurePlan(
            RdRequirementTask task,
            RequirementStageCommand command,
            List<RequirementTaskMutation> prefix,
            String reviewedResultJson,
            String reason
    ) {
        if (isPublicationConflictNeedsHuman(reason)) {
            markPublicationNeedsHuman(task, reviewedResultJson, reason);
            return publicationFailurePlan(task, command, prefix, reviewedResultJson, reason, true);
        }
        if (isAmbiguousRemoteFailure(reason)) {
            markPublicationUnknownRemoteResult(task, reviewedResultJson, reason);
            return publicationFailurePlan(task, command, prefix, reviewedResultJson, reason, false);
        }
        RdTaskStatus fromStatus = prefix.isEmpty() ? task.status() : prefix.getLast().toStatus();
        List<RequirementTaskMutation> mutations = new ArrayList<>(prefix);
        mutations.add(mutation(fromStatus, RdTaskStatus.REJECTED, "", reviewedResultJson, "",
                "pull request publication failed: " + reason));
        return plan(task, command, mutations, CommandDisposition.TERMINAL_FAILURE, ContinuationSpec.terminal());
    }

    private RequirementStageExecutionPlan planReportingStage(
            RdRequirementTask task,
            RequirementStageCommand command
    ) {
        requireStageStatus(task, command, RdTaskStatus.COMMITTED);
        return simpleStagePlan(task, command, RdTaskStatus.REPORTING, "COMPLETION");
    }

    private RequirementStageExecutionPlan planCompletionStage(
            RdRequirementTask task,
            RequirementStageCommand command
    ) {
        requireStageStatus(task, command, RdTaskStatus.REPORTING);
        return plan(task, command, List.of(mutation(task.status(), RdTaskStatus.COMPLETED, "", "", "", "")),
                CommandDisposition.SUCCEEDED, ContinuationSpec.terminal());
    }

    private void requireStageStatus(
            RdRequirementTask task,
            RequirementStageCommand command,
            RdTaskStatus expectedStatus
    ) {
        if (task.status() != expectedStatus) {
            throw new IllegalStateException("stage command " + command.commandId()
                    + " requires task status " + expectedStatus + " but was " + task.status());
        }
    }

    private ContinuationSpec roleContinuation(AgentRole role) {
        List<AgentRole> deliveryOrder = AgentRole.requirementDeliveryOrder();
        int roleIndex = deliveryOrder.indexOf(role);
        if (roleIndex < 0) {
            throw new IllegalArgumentException("role is not part of requirement delivery plan: " + role);
        }
        if (roleIndex + 1 < deliveryOrder.size()) {
            AgentRole nextRole = deliveryOrder.get(roleIndex + 1);
            return new ContinuationSpec(nextRole.name(), "ROLE_EXECUTION:" + nextRole.name());
        }
        return new ContinuationSpec("REQUIREMENT_DELIVERY", "DETERMINISTIC_REVIEW");
    }

    private List<TaskMaterial> taskMaterials(RdRequirementTask task) {
        return materialStore == null ? List.of() : materialStore.listByTask(task.taskId());
    }

    private RequirementStageExecutionPlan plan(
            RdRequirementTask task,
            RequirementStageCommand command,
            List<RequirementTaskMutation> mutations,
            CommandDisposition disposition,
            ContinuationSpec continuation
    ) {
        return plan(task, command, mutations, disposition, continuation, ExternalEffectReceipt.none());
    }

    private RequirementStageExecutionPlan plan(
            RdRequirementTask task,
            RequirementStageCommand command,
            List<RequirementTaskMutation> mutations,
            CommandDisposition disposition,
            ContinuationSpec continuation,
            ExternalEffectReceipt externalEffectReceipt
    ) {
        return new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                task.taskId(),
                command.taskVersion(),
                command.fencingToken(),
                task.status(),
                mutations,
                disposition,
                continuation,
                externalEffectReceipt);
    }

    private RequirementTaskMutation mutation(
            RdTaskStatus fromStatus,
            RdTaskStatus toStatus,
            String promptSnapshot,
            String executionResultJson,
            String pullRequestUrl,
            String errorMessage
    ) {
        return RequirementTaskMutation.statusTransition(
                fromStatus,
                toStatus,
                promptSnapshot,
                executionResultJson,
                pullRequestUrl,
                errorMessage,
                errorMessage);
    }

    private RequirementDeliveryResult executePolicyStage(RdRequirementTask task) {
        List<com.wish.rd.rag.runtime.model.TaskMaterial> materials = materialStore.listByTask(task.taskId());
        RequirementContextPackage context = contextBuilder.build(task, materials);
        RequirementPlan plan = planGenerator.generate(task, context);
        RequirementPolicyDecision decision = policyGate.decide(task, context, plan, materials);
        RdRequirementTask policy = taskRegistry.transitionRequirementFenced(
                task, RdTaskStatus.WAITING_POLICY, "", decision.toJson(), "", "");
        if (!decision.allowed()) {
            if (decision.waitingApproval()) {
                return stageResult(taskRegistry.transitionRequirementFenced(
                        policy, RdTaskStatus.WAITING_APPROVAL, "", decision.toJson(), "", ""));
            }
            return stageResult(taskRegistry.transitionRequirementFenced(
                    policy, RdTaskStatus.FAILED_NEEDS_HUMAN, "", decision.toJson(), "", decision.reason()));
        }
        return stageResult(taskRegistry.transitionRequirementFenced(
                policy, RdTaskStatus.EXECUTING, "policy allowed; execute bounded role stages", "", "", ""));
    }

    private RequirementDeliveryResult executeRoleStage(
            RdRequirementTask task, RequirementStageCommand command, AgentRole role
    ) {
        RequirementPolicyRun authorization = requireRoleAuthorization(task, command, role);
        List<com.wish.rd.rag.runtime.model.TaskMaterial> materials = materialStore.listByTask(task.taskId());
        RequirementContextPackage context = contextBuilder.build(task, materials);
        RequirementPlan plan = parseAuthorizedPlan(authorization);
        RequirementPolicyDecision decision = parseAuthorizedPolicy(authorization);
        ensureRequirementStages(task);
        ensureRoleContexts(task, materials, null);
        RequirementExecutionResult execution = stageOrchestrator.run(
                boundedRolePlan(role), task, materials, context, plan, decision, null);
        if (!execution.success()) {
            RdTaskStatus failureStatus = needsHumanInterventionResult(execution)
                    ? RdTaskStatus.FAILED_NEEDS_HUMAN
                    : RdTaskStatus.FAILED_RETRYABLE;
            RdRequirementTask failed = taskRegistry.transitionRequirementFenced(
                    task, failureStatus, "", execution.resultJson(), "", execution.errorMessage());
            return stageResult(failed);
        }
        // A role command owns exactly one newly-dispatched role. The task remains EXECUTING until
        // the final role has completed and the dedicated review command advances it.
        RdRequirementTask persisted = taskRegistry.transitionRequirementFenced(
                task,
                task.status(),
                "",
                execution.resultJson(),
                task.pullRequestUrl(),
                "");
        return stageResult(persisted);
    }

    private RequirementPolicyRun requireRoleAuthorization(
            RdRequirementTask task, RequirementStageCommand command, AgentRole role
    ) {
        RequirementPolicyRunStore policyStore = requirementPolicyRunStore;
        if (policyStore == null || command.policyRunId().isBlank()) {
            throw new IllegalStateException("role command is missing its durable policy authorization: "
                    + command.commandId());
        }
        RequirementPolicyRun authorization = policyStore.findById(command.policyRunId())
                .orElseThrow(() -> new IllegalStateException(
                        "role command policy authorization is missing: " + command.policyRunId()));
        if (authorization.state() != RequirementPolicyRunState.APPLIED
                || !authorization.taskId().equals(task.taskId())
                || !("ALLOWED".equals(authorization.policyAction())
                || "WAITING_APPROVAL".equals(authorization.policyAction()))) {
            throw new IllegalStateException("role command policy authorization is not applied to its task: "
                    + command.policyRunId());
        }
        AgentRole firstRole = AgentRole.requirementDeliveryOrder().getFirst();
        if (role == firstRole && (command.taskVersion() != authorization.boundTaskVersion()
                || command.fencingToken() != authorization.boundFencingToken())) {
            throw new IllegalStateException("first role command is not bound to the applied policy generation: "
                    + command.commandId());
        }
        return authorization;
    }

    private RequirementPlan parseAuthorizedPlan(RequirementPolicyRun authorization) {
        try {
            RequirementPlan plan = OBJECT_MAPPER.readValue(authorization.planJson(), RequirementPlan.class);
            if (!authorization.taskId().equals(plan.taskId())) {
                throw new IllegalStateException("authorized plan targets another task: " + authorization.id());
            }
            return plan;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("applied policy run contains an invalid frozen plan: "
                    + authorization.id(), exception);
        }
    }

    private RequirementPolicyDecision parseAuthorizedPolicy(RequirementPolicyRun authorization) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(authorization.policyJson());
            String action = node.path("policyAction").asText("");
            String riskLevel = node.path("riskLevel").asText("");
            String reason = node.path("reason").asText("");
            if (action.isBlank() || !action.equals(authorization.policyAction())) {
                throw new IllegalStateException("applied policy JSON action conflicts with its ledger: "
                        + authorization.id());
            }
            return new RequirementPolicyDecision(action, riskLevel, reason);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("applied policy run contains invalid frozen policy JSON: "
                    + authorization.id(), exception);
        }
    }

    private AgentWorkflowPlan boundedRolePlan(AgentRole targetRole) {
        AgentWorkflowPlan production = AgentWorkflowPlan.production();
        List<AgentRole> roles = new ArrayList<>();
        for (AgentRole role : production.roles()) {
            roles.add(role);
            if (role == targetRole) {
                break;
            }
        }
        if (roles.isEmpty() || roles.get(roles.size() - 1) != targetRole) {
            throw new IllegalArgumentException("role is not part of requirement delivery plan: " + targetRole);
        }
        Map<AgentRole, Double> ledger = new LinkedHashMap<>();
        for (AgentRole role : roles) {
            ledger.put(role, production.budgetShare(role));
        }
        return new AgentWorkflowPlan(
                roles,
                production.retrievalEnabled(),
                false,
                AgentWorkflowPlan.DEFAULT_QA_REMEDIATION_PASSES,
                false,
                AgentWorkflowPlan.DEFAULT_HOST_VERIFY_REMEDIATION_PASSES,
                ledger,
                "BOUNDED_STAGE_" + targetRole.name());
    }

    private AgentRole parseRoleStage(String stage) {
        String roleName = stage.substring("ROLE_EXECUTION:".length());
        try {
            return AgentRole.valueOf(roleName);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported role stage: " + stage, exception);
        }
    }

    private RequirementDeliveryResult executeDeterministicReviewStage(RdRequirementTask task) {
        RequirementExecutionResult execution = recoverExecutionResult(task);
        RdRequirementTask validating = task.status() == RdTaskStatus.VALIDATING
                ? task
                : taskRegistry.transitionRequirementFenced(
                        task, RdTaskStatus.VALIDATING, "", execution.resultJson(), "", "");
        RequirementDeliveryReviewResult reviewResult = deliveryReviewer.review(
                validating.taskId(), execution.resultJson());
        String reviewedResultJson = withDeliveryReviewJson(execution.resultJson(), reviewResult);
        if (!reviewResult.approved()) {
            publishDeliveryReviewAlert(validating.taskId(), execution.pullRequestUrl(), reviewResult);
            captureDeliveryReviewFailureExperience(validating.taskId(), reviewResult, execution);
            return stageResult(taskRegistry.transitionRequirementFenced(
                    validating,
                    RdTaskStatus.REJECTED,
                    "",
                    reviewedResultJson,
                    "",
                    "delivery review failed: " + reviewResult.reason()));
        }
        return stageResult(taskRegistry.transitionRequirementFenced(
                validating, RdTaskStatus.VALIDATING, "", reviewedResultJson, "", ""));
    }

    private RequirementDeliveryResult executeAiReviewStage(RdRequirementTask task) {
        if (aiDeliveryReviewEngine == null || !aiDeliveryReviewEngine.isEnabled()) {
            return stageResult(task);
        }
        String deterministicReviewJson = existingDeliveryReviewJson(task.executionResultJson());
        AiReviewRun aiReviewRun = aiDeliveryReviewEngine.review(task, deterministicReviewJson, "AUTO");
        String reviewedResultJson = withAiReviewJson(task.executionResultJson(), aiReviewRun);
        if (aiReviewRun.status() == AiReviewRunStatus.FAILED_RETRYABLE) {
            String reason = aiReviewRun.errorMessage().isBlank()
                    ? "AI delivery review failed and can be retried" : aiReviewRun.errorMessage();
            return stageResult(taskRegistry.transitionRequirementFenced(
                    task, RdTaskStatus.FAILED_RETRYABLE, "", reviewedResultJson, "", reason));
        }
        if (aiReviewRun.status() == AiReviewRunStatus.SUCCEEDED_NOT_OK
                || aiReviewRun.status() == AiReviewRunStatus.SUCCEEDED_NEEDS_HUMAN) {
            String reason = aiReviewRun.summary().isBlank()
                    ? "AI delivery review requires human intervention" : aiReviewRun.summary();
            return stageResult(taskRegistry.transitionRequirementFenced(
                    task, RdTaskStatus.FAILED_NEEDS_HUMAN, "", reviewedResultJson, "", reason));
        }
        if (aiReviewRun.status() != AiReviewRunStatus.SUCCEEDED_OK) {
            throw new IllegalStateException("AI delivery review did not reach a terminal decision: "
                    + aiReviewRun.status());
        }
        return stageResult(taskRegistry.transitionRequirementFenced(
                task, RdTaskStatus.VALIDATING, "", reviewedResultJson, "", ""));
    }

    private RequirementDeliveryResult executePublicationStage(RdRequirementTask task) {
        RequirementExecutionResult execution = RequirementExecutionResult.success(
                task.taskId(), "bounded publication", task.pullRequestUrl(), task.executionResultJson());
        // The publication adapter owns the remote idempotency protocol. This call is intentionally
        // isolated behind the PUBLICATION command; REPORTING and COMPLETION are separate commands.
        PublicationStageOutcome published = executePublicationSideEffects(
                task, execution, task.executionResultJson());
        return published.success()
                ? stageResult(published.committedTask())
                : published.failureResult();
    }

    /**
     * Re-enters the bounded publication stage after reconciliation confirms remote evidence.
     *
     * <p>Ambiguous remote failures leave the task in {@code REJECTED}; the operation-keyed stage
     * command carries that exact snapshot. Move that snapshot through {@code RECOVERING} with the
     * fenced transition API, then reuse the ordinary bounded publication path. This intentionally
     * avoids reloading a newer task through {@code markRequirementPrCreating}.
     */
    private RequirementDeliveryResult executeReconciledPublicationStage(RdRequirementTask task) {
        if (task.status() == RdTaskStatus.REJECTED) {
            task = taskRegistry.transitionRequirementFenced(
                    task, RdTaskStatus.RECOVERING, "", task.executionResultJson(), "", "");
        }
        if (task.status() != RdTaskStatus.RECOVERING && task.status() != RdTaskStatus.PR_CREATING) {
            throw new IllegalStateException(
                    "reconciled publication command requires REJECTED, RECOVERING, or PR_CREATING task status: "
                            + task.status());
        }
        return executePublicationStage(task);
    }

    private RequirementDeliveryResult executeReportingStage(RdRequirementTask task) {
        RdRequirementTask reporting = task.status() == RdTaskStatus.REPORTING
                ? task
                : taskRegistry.transitionRequirementFenced(
                        task, RdTaskStatus.REPORTING, "", task.executionResultJson(),
                        task.pullRequestUrl(), "");
        captureDeliveryExperience(reporting.taskId(), RequirementExecutionResult.success(
                reporting.taskId(), "bounded reporting", reporting.pullRequestUrl(), reporting.executionResultJson()));
        return stageResult(reporting);
    }

    private RequirementDeliveryResult executeCompletionStage(RdRequirementTask task) {
        if (task.status() == RdTaskStatus.COMPLETED) {
            return stageResult(task);
        }
        return stageResult(taskRegistry.transitionRequirementFenced(
                task, RdTaskStatus.COMPLETED, "", task.executionResultJson(),
                task.pullRequestUrl(), ""));
    }

    private RequirementDeliveryResult stageResult(RdRequirementTask task) {
        return new RequirementDeliveryResult(
                task.taskId(), task.status(), task.pullRequestUrl(), task.executionResultJson(), task.errorMessage());
    }

    private RequirementDeliveryResult validateAndPublish(
            RdRequirementTask task,
            RequirementExecutionResult executionResult,
            boolean skipDeterministicReview
    ) {
        RdRequirementTask requirementTask = taskRegistry.markRequirementValidating(
                task.taskId(), executionResult.resultJson());
        String deterministicReviewJson = existingDeliveryReviewJson(executionResult.resultJson());
        String reviewedResultJson = executionResult.resultJson();
        if (!skipDeterministicReview) {
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
                    withDeliveryReviewJson(executionResult.resultJson(), reviewResult)
            );
            return new RequirementDeliveryResult(
                    rejected.taskId(),
                    rejected.status(),
                    "",
                    rejected.executionResultJson(),
                    rejected.errorMessage()
            );
        }
            deterministicReviewJson = reviewResult.toJson();
            reviewedResultJson = withDeliveryReviewJson(executionResult.resultJson(), reviewResult);
        }
        if (aiDeliveryReviewEngine != null && aiDeliveryReviewEngine.isEnabled()) {
            AiReviewRun aiReviewRun = aiDeliveryReviewEngine.review(
                    requirementTask, deterministicReviewJson, skipDeterministicReview ? "RETRY" : "AUTO");
            reviewedResultJson = withAiReviewJson(reviewedResultJson, aiReviewRun);
            if (aiReviewRun.status() == AiReviewRunStatus.FAILED_RETRYABLE) {
                String reason = aiReviewRun.errorMessage().isBlank()
                        ? "AI delivery review failed and can be retried"
                        : aiReviewRun.errorMessage();
                RdRequirementTask failed = taskRegistry.markRequirementFailedRetryable(
                        requirementTask.taskId(), reason, reviewedResultJson);
                return new RequirementDeliveryResult(
                        failed.taskId(), failed.status(), "", failed.executionResultJson(), failed.errorMessage());
            }
            if (aiReviewRun.status() == AiReviewRunStatus.SUCCEEDED_NOT_OK
                    || aiReviewRun.status() == AiReviewRunStatus.SUCCEEDED_NEEDS_HUMAN) {
                String reason = aiReviewRun.summary().isBlank()
                        ? "AI delivery review requires human intervention"
                        : aiReviewRun.summary();
                RdRequirementTask failed = taskRegistry.markRequirementFailedNeedsHuman(
                        requirementTask.taskId(), reason, reviewedResultJson);
                return new RequirementDeliveryResult(
                        failed.taskId(), failed.status(), "", failed.executionResultJson(), failed.errorMessage());
            }
            if (aiReviewRun.status() != AiReviewRunStatus.SUCCEEDED_OK) {
                throw new IllegalStateException("AI delivery review did not reach a terminal decision: "
                        + aiReviewRun.status());
            }
        }
        return publishValidatedResult(requirementTask, executionResult, reviewedResultJson);
    }

    private RequirementDeliveryResult publishValidatedResult(
            RdRequirementTask requirementTask,
            RequirementExecutionResult executionResult,
            String reviewedResultJson
    ) {
        PublicationStageOutcome published = executePublicationSideEffects(
                requirementTask, executionResult, reviewedResultJson);
        if (!published.success()) {
            return published.failureResult();
        }
        requirementTask = published.committedTask();
        RequirementExecutionResult reviewedExecutionResult = published.executionResult();
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
        publishTaskLifecycleAlert(completed.taskId(), AgentWorkflowAlertType.TASK_COMPLETED,
                "需求交付已完成", "打开任务详情并检查 PR 与交付报告");
        return new RequirementDeliveryResult(
                completed.taskId(),
                completed.status(),
                completed.pullRequestUrl(),
                completed.executionResultJson(),
                completed.errorMessage()
        );
    }

    /**
     * Executes only the remote publication and PR_CREATING -> COMMITTED transition. The caller
     * controls the subsequent REPORTING and COMPLETION commands so a durable stage lease never
     * owns the entire tail of the workflow.
     */
    private PublicationStageOutcome executePublicationSideEffects(
            RdRequirementTask requirementTask,
            RequirementExecutionResult executionResult,
            String reviewedResultJson
    ) {
        // VALIDATING -> PR_CREATING: use the command's immutable snapshot so a stale worker
        // cannot reload a newer task and publish against a different fencing token.
        requirementTask = requirementTask.status() == RdTaskStatus.PR_CREATING
                ? requirementTask
                : taskRegistry.transitionRequirementFenced(
                        requirementTask,
                        RdTaskStatus.PR_CREATING,
                        "",
                        reviewedResultJson,
                        "",
                        "");
        boolean patchOnlyDelivery = isPatchOnlyDelivery(requirementTask);
        RequirementPullRequestPublication publication;
        if (patchOnlyDelivery) {
            publication = RequirementPullRequestPublication.success(requirementTask.taskId(), "", "", "{}");
        } else {
            preparePublicationIntent(requirementTask, reviewedResultJson);
            PublicationRemotePlan plan = resolvePublicationRemotePlan(requirementTask, reviewedResultJson);
            if (plan.blockedReason() != null) {
                RequirementDeliveryResult blocked = blockPublication(
                        requirementTask, reviewedResultJson, plan.blockedReason(), plan.needsHuman());
                return PublicationStageOutcome.failure(blocked);
            }
            if (plan.reusedPublication() != null) {
                publication = plan.reusedPublication();
            } else {
                if (plan.pushBranch()) {
                    String branchError = pushReviewedBranch(requirementTask, reviewedResultJson);
                    if (!branchError.isEmpty()) {
                        RequirementDeliveryResult failed = failPublicationAfterRemoteWrite(
                                requirementTask, reviewedResultJson, branchError, null);
                        return PublicationStageOutcome.failure(failed);
                    }
                }
                publication = publishPullRequest(requirementTask, reviewedResultJson);
            }
        }
        if (!publication.success() || (!patchOnlyDelivery && publication.pullRequestUrl().isBlank())) {
            String reason = publication.errorMessage().isBlank()
                    ? "pull request publication failed" : publication.errorMessage();
            RequirementDeliveryResult failed = failPublicationAfterRemoteWrite(
                    requirementTask, reviewedResultJson, reason, publication);
            return PublicationStageOutcome.failure(failed);
        }
        if (!patchOnlyDelivery) {
            confirmPublicationPullRequest(requirementTask, reviewedResultJson, publication);
        }
        RequirementExecutionResult reviewedExecutionResult = RequirementExecutionResult.success(
                executionResult.taskId(), executionResult.summary(), publication.pullRequestUrl(),
                withPullRequestPublicationJson(reviewedResultJson, publication));
        RdRequirementTask committed = commitPublishedRequirement(
                requirementTask, reviewedResultJson, reviewedExecutionResult, patchOnlyDelivery);
        return PublicationStageOutcome.success(committed, reviewedExecutionResult);
    }

    private void publishTaskLifecycleAlert(
            String taskId,
            AgentWorkflowAlertType type,
            String message,
            String nextAction
    ) {
        alertSink.publish(new AgentWorkflowAlert(
                taskId, "", type, message,
                Map.of("nextAction", nextAction, "status", type.name()),
                System.currentTimeMillis()
        ));
    }

    private TaskRetryCheckpoint activeDispatchedRetry(String taskId) {
        if (taskRetryCheckpointStore == null) {
            return null;
        }
        return taskRetryCheckpointStore.findActiveByTask(taskId)
                .filter(checkpoint -> checkpoint.status() == TaskRetryCheckpointStatus.DISPATCHED)
                .orElse(null);
    }

    private RequirementExecutionResult recoverExecutionResult(RdRequirementTask task) {
        String persisted = task.executionResultJson();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(persisted);
            if (root != null && root.isObject()
                    && "SUCCESS".equals(root.path("multiAgentStatus").asText())
                    && hasParseableStageResults(root.path("multiAgentStages"))) {
                return RequirementExecutionResult.success(task.taskId(), "reused persisted delivery result", "", persisted);
            }
        } catch (JsonProcessingException ignored) {
            // Fall through to the immutable stage artifacts below.
        }
        List<String> stageResults = new ArrayList<>();
        List<AgentStageRun> stages = stageRunStore.listByTask(task.taskId());
        for (AgentRole role : AgentRole.requirementDeliveryOrder()) {
            AgentStageRun latest = latestStageOrNull(stages, role);
            if (latest == null || latest.status() != AgentStageStatus.SUCCEEDED) {
                throw new IllegalStateException("cannot recover delivery result, successful stage missing: " + role);
            }
            stageResults.add(reusedStageResultJson(latest));
        }
        return RequirementExecutionResult.success(
                task.taskId(), "recovered from immutable role artifacts", "",
                mergeDeliveryResultJson("{}", "", stageResults));
    }

    /**
     * 判断已落库的多角色结果里，每个阶段的 resultJson 是否仍是可解析的角色结果。
     *
     * <p>历史任务可能保存了被截断的 RESULT_JSON 预览，这类结果不能直接拿去复核，
     * 必须回到不可变阶段产物重建，否则复核只会重复读到同一份坏数据。
     *
     * @param stages 已落库的 multiAgentStages 节点
     * @return 全部阶段结果可解析时返回 true
     */
    private boolean hasParseableStageResults(JsonNode stages) {
        if (stages == null || !stages.isArray()) {
            return true;
        }
        for (JsonNode stage : stages) {
            JsonNode result = stage.path("resultJson");
            if (!result.isTextual()) {
                continue;
            }
            String raw = safe(result.asText());
            if (!raw.isBlank() && !isJsonObject(raw)) {
                return false;
            }
        }
        return true;
    }

    private String existingDeliveryReviewJson(String resultJson) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(resultJson == null ? "{}" : resultJson);
            JsonNode review = root == null ? null : root.path("deliveryReview");
            return review == null || review.isMissingNode() || review.isNull() ? "{}" : review.toString();
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private RequirementDeliveryResult resumePullRequestPublication(RdRequirementTask task) {
        String reviewedResultJson = task.executionResultJson();
        RdRequirementTask publishing = taskRegistry.markRequirementPrCreating(task.taskId(), reviewedResultJson);
        // 补丁即交付：与首次发布同样跳过 PR 发布器（历史 REJECTED 任务 retry 也要能走到完成）
        boolean patchOnlyDelivery = isPatchOnlyDelivery(publishing);
        RequirementPullRequestPublication publication;
        if (patchOnlyDelivery) {
            publication = RequirementPullRequestPublication.success(publishing.taskId(), "", "", "{}");
        } else {
            preparePublicationIntent(publishing, reviewedResultJson);
            PublicationRemotePlan plan = resolvePublicationRemotePlan(publishing, reviewedResultJson);
            if (plan.blockedReason() != null) {
                return blockPublication(publishing, reviewedResultJson, plan.blockedReason(), plan.needsHuman());
            }
            if (plan.reusedPublication() != null) {
                publication = plan.reusedPublication();
            } else {
                if (plan.pushBranch()) {
                    String branchError = pushReviewedBranch(publishing, reviewedResultJson);
                    if (!branchError.isEmpty()) {
                        return failPublicationAfterRemoteWrite(
                                publishing, reviewedResultJson, branchError, null);
                    }
                }
                publication = publishPullRequest(publishing, reviewedResultJson);
            }
        }
        if (!publication.success() || (!patchOnlyDelivery && publication.pullRequestUrl().isBlank())) {
            String reason = publication.errorMessage().isBlank()
                    ? "pull request publication failed"
                    : publication.errorMessage();
            return failPublicationAfterRemoteWrite(
                    publishing, reviewedResultJson, reason, publication);
        }
        if (!patchOnlyDelivery) {
            confirmPublicationPullRequest(publishing, reviewedResultJson, publication);
        }
        RequirementExecutionResult published = RequirementExecutionResult.success(
                task.taskId(), "PR publication retry succeeded", publication.pullRequestUrl(),
                withPullRequestPublicationJson(reviewedResultJson, publication));
        RdRequirementTask committed = commitPublishedRequirement(
                publishing, reviewedResultJson, published, patchOnlyDelivery);
        RdRequirementTask reporting = taskRegistry.markRequirementReporting(
                committed.taskId(), published.resultJson());
        captureDeliveryExperience(reporting.taskId(), published);
        RdRequirementTask completed = taskRegistry.markRequirementCompleted(
                reporting.taskId(), published.pullRequestUrl(), published.resultJson());
        publishTaskLifecycleAlert(completed.taskId(), AgentWorkflowAlertType.TASK_COMPLETED,
                "需求交付已完成", "打开任务详情并检查 PR 与交付报告");
        return currentResult(completed);
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

    private boolean hasApprovalEvent(String taskId) {
        return taskRegistry.timeline(taskId).stream()
                .anyMatch(event -> RdTaskStatusEvent.ACTION_APPROVED.equals(event.status()));
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
            if (isRetryableRequirementStatus(task.status())
                    && AgentStageTransitions.requiresFreshAttemptOnRecovery(latest.status())) {
                if (interruptedStageRecoveryService != null
                        && interruptedStageRecoveryService.tryRecoverInterruptedStage(latest, now)) {
                    continue;
                }
                stageRunStore.transition(
                        latest.stageRunId(),
                        AgentStageStatus.FAILED_RETRYABLE,
                        "ORCHESTRATION_INTERRUPTED",
                        "previous role attempt was interrupted before it reached a terminal state",
                        now
                );
                // attempt 硬上限：达到上限后不再开新 attempt，任务停在终态等待人工（CP-F2）。
                if (latest.attemptNo() < MAX_ROLE_ATTEMPTS) {
                    stageRunStore.save(pendingStage(task.taskId(), role, latest.attemptNo() + 1, now));
                }
                continue;
            }
            if (isRetryableRequirementStatus(task.status()) && isRetryableStageFailure(latest)
                    && latest.attemptNo() < MAX_ROLE_ATTEMPTS) {
                stageRunStore.save(pendingStage(task.taskId(), role, latest.attemptNo() + 1, now));
            }
        }
    }

    private boolean isRetryableRequirementStatus(RdTaskStatus status) {
        return status == RdTaskStatus.REJECTED
                || status == RdTaskStatus.FAILED_RETRYABLE
                || status == RdTaskStatus.FAILED_NEEDS_HUMAN
                || status == RdTaskStatus.RECOVERING;
    }

    private boolean isRetryableStageFailure(AgentStageRun stage) {
        // CANCELLED / SKIPPED 是人工终止语义，禁止当作可重试失败自动开新 attempt（CP-16）。
        return stage.status() == AgentStageStatus.FAILED_RETRYABLE
                || stage.status() == AgentStageStatus.FAILED_NEEDS_HUMAN;
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

    private List<AgentStageRun> createQaRemediationAttempts(String taskId) {
        List<AgentStageRun> existing = stageRunStore.listByTask(taskId);
        long now = System.currentTimeMillis();
        // 任一角色达到 attempt 上限则整体放弃补救，直接走人工失败分支（CP-F2）。
        for (AgentRole role : List.of(AgentRole.CODING_AGENT, AgentRole.QA_AGENT)) {
            AgentStageRun latest = latestStageOrNull(existing, role);
            if (latest != null && latest.attemptNo() >= MAX_ROLE_ATTEMPTS) {
                return List.of();
            }
        }
        List<AgentStageRun> created = new ArrayList<>(2);
        for (AgentRole role : List.of(AgentRole.CODING_AGENT, AgentRole.QA_AGENT)) {
            AgentStageRun latest = latestStageOrNull(existing, role);
            int attemptNo = latest == null ? 1 : latest.attemptNo() + 1;
            created.add(stageRunStore.save(pendingStage(taskId, role, attemptNo, now)));
        }
        return List.copyOf(created);
    }

    private AgentStageRun latestStageOrNull(List<AgentStageRun> stages, AgentRole role) {
        return stages.stream()
                .filter(stage -> stage.role() == role)
                .max(STAGE_RUN_RECENCY)
                .orElse(null);
    }

    private void ensureRoleContexts(
            RdRequirementTask task,
            List<TaskMaterial> materials,
            TaskRetryCheckpoint activeRetry
    ) {
        long now = System.currentTimeMillis();
        List<TaskMaterial> contextMaterials = materialsWithReusableExperience(task, materials, now);
        if (retrievalRecorder != null) {
            // Base context is observable up front; role contexts are retrieved and bound immediately
            // before their exact AgentStageRun is dispatched.
            retrievalRecorder.recordOnly(
                    task, contextMaterials, RetrievalConsumerType.REQUIREMENT_BASE, null, "", ""
            );
            return;
        }
        // Compatibility path for unit tests and installations without the Deep RAG control plane.
        roleContextVersionManager.ensureLatestContexts(task, contextMaterials, now);
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
            RdTask source = taskRegistry.getTask(experience.taskId());
            if (!(source instanceof RdRequirementTask sourceTask)) {
                return false;
            }
            if (!task.projectId().isBlank() || !sourceTask.projectId().isBlank()) {
                return !task.projectId().isBlank() && task.projectId().equals(sourceTask.projectId());
            }
            String currentRepo = (task.repoOwner() + "/" + task.repoName()).toLowerCase(Locale.ROOT);
            String sourceRepo = (sourceTask.repoOwner() + "/" + sourceTask.repoName()).toLowerCase(Locale.ROOT);
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
            RequirementPolicyDecision policyDecision,
            TaskRetryCheckpoint activeRetry
    ) {
        // 生产 D 路径：直接委托给 stageOrchestrator，保持字节级行为不变。
        return stageOrchestrator.run(
                AgentWorkflowPlan.production(),
                task,
                materials,
                context,
                plan,
                policyDecision,
                activeRetry
        );
    }

    private RequirementExecutionResult executeAgentStages(
            RdRequirementTask task,
            List<TaskMaterial> materials,
            RequirementContextPackage context,
            RequirementPlan plan,
            RequirementPolicyDecision policyDecision,
            TaskRetryCheckpoint activeRetry,
            String qaRemediationResultJson,
            int qaRemediationCount
    ) {
        // QA 修复回路递归入口：因 spec §5.2 限制只能直跑 production（D）plan，这里也是。
        // qaRemediationResultJson / qaRemediationCount 由 orchestrator 内部维护，本入口仅作
        // 反向兼容（即不再使用，但保留编译期签名）。
        return executeAgentStages(task, materials, context, plan, policyDecision, activeRetry);
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
            // Existing migrated tasks can outlive a deleted project. They remain unlimited rather than failing review.
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
                .filter(candidate -> candidate.status() == RdTaskStatus.COMPLETED)
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

    private boolean needsHumanInterventionResult(RequirementExecutionResult result) {
        String status = aggregateStatus(result == null ? "" : result.resultJson());
        return "NEEDS_HUMAN".equals(status)
                || "FAILED_NEEDS_HUMAN".equals(status)
                || "WAITING_POLICY".equals(status);
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

    /**
     * 补丁即交付判定：验收标准显式禁止创建 PR（SWE-bench 评测红线）时，
     * 交付物为工作区补丁本体，对零提交分支建 PR 必然 422，应直接完成。
     */
    private boolean isPatchOnlyDelivery(RdRequirementTask task) {
        String criteria = safe(task.acceptanceCriteriaJson());
        return criteria.contains("不创建 PR")
                || criteria.contains("不创建PR")
                || criteria.contains("禁止创建 PR")
                || criteria.toLowerCase(java.util.Locale.ROOT).contains("do not open a pull request");
    }

    /**
     * 轻量交付模式（P2）：补丁即交付类小任务中 REVIEWER 与 ARCHITECT 的“复现 bug→定位→给方案”
     * 工作高度重叠。让评审一次性把定位做实，架构直接沿用评审结论，消除重复复现（实测约 10 分钟/$1.5 每题）。
     * 仅作用于 prompt 层，不改变四角色状态机与交付复核链路。
     */
    private String lightweightDeliveryPromptSection(AgentRole role, RdRequirementTask task) {
        if (!isPatchOnlyDelivery(task)) {
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
                    reviewedResultJson,
                    publicationOperationId(task, reviewedResultJson)
            ));
        } catch (RuntimeException exception) {
            return RequirementPullRequestPublication.failure(
                    task.taskId(),
                    "pull request publisher exception: " + safe(exception.getMessage())
            );
        }
    }

    /**
     * Records a PREPARED publication intent before any remote branch/PR write.
     * Missing ledger or missing candidate-patch identity is a no-op so Slice-1
     * never blocks delivery when the optional ledger is absent.
     */
    private void preparePublicationIntent(RdRequirementTask task, String reviewedResultJson) {
        String operationId = publicationOperationId(task, reviewedResultJson);
        if (publicationLedger == null || operationId.isBlank()) {
            return;
        }
        String candidatePatchSha256 = RequirementPublicationIntentFactory.candidatePatchSha256(reviewedResultJson);
        publicationLedger.prepare(new RequirementPublicationPrepareCommand(
                operationId,
                task.taskId(),
                "",
                task.baseBranch(),
                workBranch(task),
                candidatePatchSha256
        ));
    }

    /**
     * Uses the ledger replay decision to skip already-confirmed remote writes,
     * reuse an existing PR, or block when reconciliation / human review is required.
     * When the ledger is absent or the operation is unknown, defaults to push+PR.
     */
    private PublicationRemotePlan resolvePublicationRemotePlan(RdRequirementTask task, String reviewedResultJson) {
        String operationId = publicationOperationId(task, reviewedResultJson);
        if (publicationLedger == null || operationId.isBlank()) {
            return PublicationRemotePlan.pushThenCreatePullRequest();
        }
        boolean preparedBeforeReconcile = publicationLedger.findByOperationId(operationId)
                .map(snapshot -> snapshot.status() == RequirementPublicationStatus.PREPARED)
                .orElse(false);
        maybeReconcileUnknownPublication(task, operationId);
        if (preparedBeforeReconcile) {
            String preparedPreflightBlock = preflightPreparedRemoteBranch(task, operationId);
            if (!preparedPreflightBlock.isBlank()) {
                return PublicationRemotePlan.block(preparedPreflightBlock, false);
            }
        }
        RequirementPublicationReplayDecision decision = publicationLedger.decideReplay(operationId);
        return switch (decision) {
            case ALLOW_PUSH, REUSE_BRANCH -> PublicationRemotePlan.pushThenCreatePullRequest();
            case ALLOW_CREATE_PULL_REQUEST -> PublicationRemotePlan.createPullRequestOnly();
            case REUSE_PULL_REQUEST -> {
                RequirementPublication snapshot = publicationLedger.findByOperationId(operationId).orElse(null);
                if (snapshot == null || snapshot.pullRequestUrl().isBlank() || snapshot.pullRequestNumber() <= 0) {
                    yield PublicationRemotePlan.createPullRequestOnly();
                }
                yield PublicationRemotePlan.reuse(RequirementPullRequestPublication.success(
                        task.taskId(),
                        snapshot.pullRequestUrl(),
                        Integer.toString(snapshot.pullRequestNumber()),
                        "{\"reusedFromPublicationLedger\":true}"
                ));
            }
            case WAIT_RECONCILE -> PublicationRemotePlan.block(
                    "publication is waiting for remote reconciliation; do not replay push/PR",
                    false
            );
            case NEEDS_HUMAN -> PublicationRemotePlan.block(
                    "publication requires human review before remote replay",
                    true
            );
        };
    }

    /**
     * Checks a prepared publication's remote branch before allowing the candidate
     * patch to be applied again.
     *
     * <p>A present remote head is recorded only when its commit markers prove the
     * exact operation and candidate patch. A bare SHA, a different operation, or
     * a different patch is an ambiguous external side effect and requires human
     * resolution rather than replaying or overwriting the branch.
     *
     * @param task        requirement task being published
     * @param operationId publication operation identity
     * @return a reconciliation block reason, or blank when publication may continue
     */
    private String preflightPreparedRemoteBranch(RdRequirementTask task, String operationId) {
        if (publicationReconciler == null) {
            return "";
        }
        RequirementPublication snapshot = publicationLedger.findByOperationId(operationId).orElse(null);
        if (snapshot == null || snapshot.status() != RequirementPublicationStatus.PREPARED) {
            return "";
        }
        RequirementPublicationReconcilePort.RemoteBranchHead remoteHead;
        try {
            remoteHead = publicationReconciler.resolveRemoteBranchHead(
                    new RequirementPublicationReconcilePort.BranchHeadQuery(
                            task.repoOwner(),
                            task.repoName(),
                            snapshot.workBranch()
                    )
            );
        } catch (RuntimeException exception) {
            return markPreparedPublicationUnknown(
                    operationId,
                    "remote branch preflight failed: " + safe(exception.getMessage())
            );
        }
        if (remoteHead instanceof RequirementPublicationReconcilePort.RemoteBranchHead.Present present
                && !present.commitSha().isBlank()) {
            if (!present.matches(snapshot.operationId(), snapshot.candidatePatchSha256())) {
                try {
                    publicationLedger.markNeedsHuman(
                            operationId,
                            "remote branch markers do not match publication operation or candidate patch"
                    );
                } catch (RuntimeException ignored) {
                    // A concurrent worker may already have terminally reconciled the operation.
                }
                return "publication requires human review before remote replay";
            }
            publicationLedger.markBranchConfirmed(operationId, present.commitSha());
            return "";
        }
        if (remoteHead instanceof RequirementPublicationReconcilePort.RemoteBranchHead.Absent) {
            return "";
        }
        return markPreparedPublicationUnknown(
                operationId,
                "remote branch preflight could not prove branch presence or absence"
        );
    }

    private String markPreparedPublicationUnknown(String operationId, String reason) {
        try {
            publicationLedger.markUnknownRemoteResult(operationId, reason);
        } catch (RuntimeException ignored) {
            // A concurrent publisher may have already advanced the same operation.
            RequirementPublication current = publicationLedger.findByOperationId(operationId).orElse(null);
            if (current != null && current.status() != RequirementPublicationStatus.PREPARED) {
                return "";
            }
        }
        return "publication is waiting for remote reconciliation; do not replay push/PR";
    }

    /**
     * Best-effort recovery for UNKNOWN after ambiguous push/PR via shared reconciliation service.
     */
    private void maybeReconcileUnknownPublication(RdRequirementTask task, String operationId) {
        if (publicationReconciliationService == null) {
            return;
        }
        publicationReconciliationService.reconcileUnknown(
                operationId,
                task.taskId(),
                task.repoOwner(),
                task.repoName()
        );
    }

    private RequirementDeliveryResult blockPublication(
            RdRequirementTask task,
            String reviewedResultJson,
            String reason,
            boolean needsHuman
    ) {
        publishPullRequestPublicationAlert(task.taskId(), reason);
        if (needsHuman) {
            RdRequirementTask failed = taskRegistry.transitionRequirementFenced(
                    task, RdTaskStatus.FAILED_NEEDS_HUMAN, "", reviewedResultJson, "", reason);
            return new RequirementDeliveryResult(
                    failed.taskId(),
                    failed.status(),
                    "",
                    failed.executionResultJson(),
                    failed.errorMessage()
            );
        }
        RdRequirementTask rejected = taskRegistry.transitionRequirementFenced(
                task,
                RdTaskStatus.REJECTED,
                "",
                reviewedResultJson,
                "",
                "pull request publication failed: " + reason);
        return new RequirementDeliveryResult(
                rejected.taskId(),
                rejected.status(),
                "",
                rejected.executionResultJson(),
                rejected.errorMessage()
        );
    }

    /**
     * After an ambiguous remote write (timeout/5xx), mark UNKNOWN_REMOTE_RESULT so
     * retries wait for reconciliation instead of replaying push/PR.
     * Marker/identity conflicts escalate to NEEDS_HUMAN instead of blind UNKNOWN.
     */
    private RequirementDeliveryResult failPublicationAfterRemoteWrite(
            RdRequirementTask task,
            String reviewedResultJson,
            String reason,
            RequirementPullRequestPublication publication
    ) {
        publishPullRequestPublicationAlert(task.taskId(), reason);
        String persisted = publication == null
                ? reviewedResultJson
                : withPullRequestPublicationJson(reviewedResultJson, publication);
        if (isPublicationConflictNeedsHuman(reason)) {
            markPublicationNeedsHuman(task, reviewedResultJson, reason);
            RdRequirementTask failed = taskRegistry.transitionRequirementFenced(
                    task,
                    RdTaskStatus.FAILED_NEEDS_HUMAN,
                    "",
                    persisted,
                    "",
                    "pull request publication failed: " + reason);
            return new RequirementDeliveryResult(
                    failed.taskId(),
                    failed.status(),
                    "",
                    failed.executionResultJson(),
                    failed.errorMessage()
            );
        }
        if (isAmbiguousRemoteFailure(reason)) {
            markPublicationUnknownRemoteResult(task, reviewedResultJson, reason);
        }
        RdRequirementTask rejected = taskRegistry.transitionRequirementFenced(
                task,
                RdTaskStatus.REJECTED,
                "",
                persisted,
                "",
                "pull request publication failed: " + reason);
        return new RequirementDeliveryResult(
                rejected.taskId(),
                rejected.status(),
                "",
                rejected.executionResultJson(),
                rejected.errorMessage()
        );
    }

    private void markPublicationNeedsHuman(
            RdRequirementTask task,
            String reviewedResultJson,
            String reason
    ) {
        if (publicationLedger == null) {
            return;
        }
        String operationId = publicationOperationId(task, reviewedResultJson);
        if (operationId.isBlank()) {
            return;
        }
        try {
            publicationLedger.markNeedsHuman(operationId, reason);
        } catch (RuntimeException ignored) {
            // Optional ledger must never hide the original remote failure.
        }
    }

    private void markPublicationUnknownRemoteResult(
            RdRequirementTask task,
            String reviewedResultJson,
            String reason
    ) {
        if (publicationLedger == null) {
            return;
        }
        String operationId = publicationOperationId(task, reviewedResultJson);
        if (operationId.isBlank()) {
            return;
        }
        try {
            publicationLedger.markUnknownRemoteResult(operationId, reason);
        } catch (RuntimeException ignored) {
            // Optional ledger must never hide the original remote failure.
        }
    }

    private static boolean isPublicationConflictNeedsHuman(String reason) {
        String normalized = safe(reason).toLowerCase(Locale.ROOT);
        return normalized.contains("markers do not match")
                || normalized.contains("conflicting open pull request")
                || normalized.contains("multiple open pull requests");
    }

    private static boolean isAmbiguousRemoteFailure(String reason) {
        String normalized = safe(reason).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.contains("timeout")
                || normalized.contains("timed out")
                || normalized.contains("connection reset")
                || normalized.contains("connection refused")
                || normalized.contains("temporarily unavailable")
                || normalized.contains("503")
                || normalized.contains("502")
                || normalized.contains("504")
                || normalized.contains("500 ")
                || normalized.endsWith(" 500")
                || normalized.contains("status 5");
    }

    /**
     * Advances PREPARED → BRANCH_CONFIRMED after a successful non-skipped push.
     * Skipped publishers and blank commit SHAs leave the ledger untouched.
     */
    private void confirmPublicationBranch(
            RdRequirementTask task,
            String reviewedResultJson,
            RequirementBranchPublication branch
    ) {
        if (publicationLedger == null || branch == null || branch.skipped() || branch.commitSha().isBlank()) {
            return;
        }
        String operationId = publicationOperationId(task, reviewedResultJson);
        if (operationId.isBlank()) {
            return;
        }
        publicationLedger.markBranchConfirmed(operationId, branch.commitSha());
    }

    private String publicationOperationId(RdRequirementTask task, String reviewedResultJson) {
        if (task == null) {
            return "";
        }
        String candidatePatchSha256 = RequirementPublicationIntentFactory.candidatePatchSha256(reviewedResultJson);
        if (candidatePatchSha256.isBlank()) {
            return "";
        }
        return RequirementOperationId.of(
                task.taskId(),
                task.baseBranch(),
                workBranch(task),
                candidatePatchSha256
        );
    }

    /**
     * Advances BRANCH_CONFIRMED → PR_CONFIRMED after a successful PR create.
     * Leaves PREPARED alone when branch push was skipped (no remote head), so
     * the optional ledger never blocks delivery.
     */
    private void confirmPublicationPullRequest(
            RdRequirementTask task,
            String reviewedResultJson,
            RequirementPullRequestPublication publication
    ) {
        if (publicationLedger == null || publication == null || publication.pullRequestUrl().isBlank()) {
            return;
        }
        String operationId = publicationOperationId(task, reviewedResultJson);
        if (operationId.isBlank()) {
            return;
        }
        int pullRequestNumber = parsePullRequestNumber(publication.pullRequestNumber());
        if (pullRequestNumber <= 0) {
            return;
        }
        boolean branchConfirmed = publicationLedger.findByOperationId(operationId)
                .map(snapshot -> snapshot.status() == RequirementPublicationStatus.BRANCH_CONFIRMED)
                .orElse(false);
        if (!branchConfirmed) {
            return;
        }
        publicationLedger.markPullRequestConfirmed(
                operationId,
                publication.pullRequestUrl(),
                pullRequestNumber
        );
    }

    /**
     * Finalizes a published requirement task.
     *
     * <p>When a non-patch-only delivery has a publication ledger, the confirmed PR and task
     * transition form one Host transaction. An absent operation, missing {@code PR_CONFIRMED}
     * ledger state, or missing commit port is an unsafe partial configuration and must not allow
     * the task to advance independently.
     *
     * @param task                 task currently in {@code PR_CREATING}
     * @param reviewedResultJson   reviewed delivery result that identifies the candidate patch
     * @param publishedResult      successful publication result persisted on the task
     * @param patchOnlyDelivery    whether the workflow intentionally has no remote PR
     * @return committed task snapshot
     */
    private RdRequirementTask commitPublishedRequirement(
            RdRequirementTask task,
            String reviewedResultJson,
            RequirementExecutionResult publishedResult,
            boolean patchOnlyDelivery
    ) {
        if (patchOnlyDelivery || publicationLedger == null) {
            return taskRegistry.transitionRequirementFenced(
                    task,
                    RdTaskStatus.COMMITTED,
                    "",
                    publishedResult.resultJson(),
                    publishedResult.pullRequestUrl(),
                    "");
        }
        if (publicationCommitPort == null) {
            throw new IllegalStateException(
                    "requirement publication ledger requires an atomic publication commit port");
        }
        String operationId = publicationOperationId(task, reviewedResultJson);
        if (operationId.isBlank()) {
            throw new IllegalStateException(
                    "requirement publication ledger requires a candidate-patch operation id");
        }
        RequirementPublication publication = publicationLedger.findByOperationId(operationId)
                .orElseThrow(() -> new IllegalStateException(
                        "publication ledger entry not found for operation " + operationId));
        if (publication.status() != RequirementPublicationStatus.PR_CONFIRMED
                && publication.status() != RequirementPublicationStatus.COMMITTED) {
            throw new IllegalStateException(
                    "publication " + operationId + " must be PR_CONFIRMED before task commit, but was "
                            + publication.status());
        }
        return publicationCommitPort.commit(new RequirementPublicationCommitPort.RequirementPublicationCommitCommand(
                task.taskId(),
                operationId,
                publishedResult.pullRequestUrl(),
                publishedResult.resultJson(),
                task.version(),
                task.status(),
                task.fencingToken()
        ));
    }

    private static int parsePullRequestNumber(String pullRequestNumber) {
        String normalized = safe(pullRequestNumber);
        if (normalized.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private record PublicationRemotePlan(
            boolean pushBranch,
            RequirementPullRequestPublication reusedPublication,
            String blockedReason,
            boolean needsHuman
    ) {
        private static PublicationRemotePlan pushThenCreatePullRequest() {
            return new PublicationRemotePlan(true, null, null, false);
        }

        private static PublicationRemotePlan createPullRequestOnly() {
            return new PublicationRemotePlan(false, null, null, false);
        }

        private static PublicationRemotePlan reuse(RequirementPullRequestPublication publication) {
            return new PublicationRemotePlan(false, publication, null, false);
        }

        private static PublicationRemotePlan block(String reason, boolean needsHuman) {
            return new PublicationRemotePlan(false, null, reason, needsHuman);
        }
    }

    private record PublicationStageOutcome(
            boolean success,
            RdRequirementTask committedTask,
            RequirementExecutionResult executionResult,
            RequirementDeliveryResult failureResult
    ) {
        private static PublicationStageOutcome success(
                RdRequirementTask committedTask,
                RequirementExecutionResult executionResult
        ) {
            return new PublicationStageOutcome(true, committedTask, executionResult, null);
        }

        private static PublicationStageOutcome failure(RequirementDeliveryResult failureResult) {
            return new PublicationStageOutcome(false, null, null, failureResult);
        }
    }

    /**
     * 交付复核通过后、创建 PR 前，把已复核的候选补丁提交并推送到工作分支。
     * 返回空字符串表示成功或被跳过（未配置分支推送端口）；非空表示推送失败原因，
     * 由调用方写入 REJECTED。分支未推送时直接建 PR 会被代码平台以 head 不存在拒绝（GitHub 422）。
     */
    private String pushReviewedBranch(RdRequirementTask task, String reviewedResultJson) {
        try {
            RequirementBranchPublication branch = branchPublisher.publishBranch(new RequirementBranchPublishCommand(
                    task.taskId(),
                    task.title(),
                    task.repositoryUrl(),
                    task.repoOwner(),
                    task.repoName(),
                    task.baseBranch(),
                    workBranch(task),
                    reviewedResultJson
            ));
            if (branch == null) {
                return "work branch push returned no result";
            }
            if (!branch.success()) {
                return branch.errorMessage().isBlank() ? "work branch push failed" : branch.errorMessage();
            }
            confirmPublicationBranch(task, reviewedResultJson, branch);
            return "";
        } catch (RuntimeException exception) {
            return "work branch push exception: " + safe(exception.getMessage());
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
                        stageArtifactContentHash(item, content, uri),
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
            // Historical/in-memory tests may not have a persisted requirement task.
            // Preserve the entry rather than manufacturing a false project scope.
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
            experienceStore.save(scopeExperience(new WorkflowExperienceEntry(
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
            )));
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
            experienceStore.save(scopeExperience(new WorkflowExperienceEntry(
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
            )));
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
            default -> throw new IllegalArgumentException("unsupported requirement role: " + role);
        };
    }

    private String latestStageResultArtifactId(String taskId, AgentRole role) {
        return stageRunStore.listByTask(taskId).stream()
                .filter(stage -> stage.role() == role)
                .filter(stage -> !stage.resultArtifactId().isBlank())
                .max(STAGE_RUN_RECENCY)
                .map(AgentStageRun::resultArtifactId)
                .orElse("");
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
        return packages.stream()
                .max(Comparator.comparingInt(RoleContextPackage::packageVersion)
                        .thenComparingLong(RoleContextPackage::createdAtEpochMillis)
                        .thenComparing(RoleContextPackage::packageId))
                .orElseThrow();
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
            String upstreamResultJson,
            String recoveryPromptSection
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
                roleInstruction(role),
                roleContextJson(roleContext),
                repositoryDiscoveryPromptSection(roleContext),
                upstreamHandoffPromptSection(role, upstreamResultJson),
                budgetEstimatePromptSection(role, task),
                lightweightDeliveryPromptSection(role, task),
                recoveryPromptSection,
                buildPrompt(task, materials, context, plan, policyDecision),
                roleOutputContract(role)
        ).strip();
    }

    /**
     * Resolves the immutable evidence selected for the active recovery checkpoint before any role is dispatched.
     *
     * <p>Retry creation validates these IDs as well. The execution-time validation protects against a material
     * being removed or a stale checkpoint being replayed between checkpoint creation and dispatch.
     *
     * @param checkpoint active recovery checkpoint, if any
     * @param taskId current task ID
     * @param taskMaterials material snapshot loaded for this execution
     * @return selected recovery evidence in the operator-selected order
     */
    private List<TaskMaterial> recoveryEvidenceMaterials(
            TaskRetryCheckpoint checkpoint,
            String taskId,
            List<TaskMaterial> taskMaterials
    ) {
        if (checkpoint == null || checkpoint.evidenceMaterialIds().isEmpty()) {
            return List.of();
        }
        if (!checkpoint.taskId().equals(taskId)) {
            throw new IllegalStateException("recovery checkpoint does not belong to task: " + taskId);
        }
        Map<String, TaskMaterial> materialsById = (taskMaterials == null ? List.<TaskMaterial>of() : taskMaterials)
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

    /**
     * 生成上一轮失败反馈 prompt 段落，让重试 attempt 针对性自纠错而不是盲重跑（审查报告 F2）。
     *
     * <p>无历史失败 attempt 时返回空字符串，首轮 prompt 保持不变。
     *
     * @param taskId           RD 任务 ID
     * @param role             当前角色
     * @param currentAttemptNo 当前 attempt 序号
     * @return 失败反馈段落或空字符串
     */
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
        if (first.isBlank()) {
            return second;
        }
        if (second.isBlank()) {
            return first;
        }
        return first + "\n\n" + second;
    }

    /**
     * Builds the explicit recovery section injected into the newly-created failed stage and all downstream roles.
     *
     * @param checkpoint active recovery checkpoint, if any
     * @param role role about to be dispatched
     * @param recoveryMaterials selected evidence resolved from the task snapshot
     * @return prompt section or an empty string when the role precedes the recovery point
     */
    private String recoveryPromptSection(
            TaskRetryCheckpoint checkpoint,
            AgentRole role,
            List<TaskMaterial> recoveryMaterials
    ) {
        if (!isRecoveryRoleOrDownstream(checkpoint, role)) {
            return "";
        }
        String operatorNote = checkpoint.operatorNote();
        String downstreamFailureSection = downstreamFailureFeedbackSection(checkpoint);
        if (operatorNote.isBlank() && recoveryMaterials.isEmpty() && downstreamFailureSection.isBlank()) {
            return "";
        }
        String noteSection = operatorNote.isBlank()
                ? ""
                : """
                        ## 操作员补充说明
                        %s
                        """.formatted(operatorNote).strip();
        String evidenceSection = recoveryMaterials.isEmpty()
                ? ""
                : """
                        ## 本次选定证据
                        %s
                        """.formatted(materialPrompt(recoveryMaterials)).strip();
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

    /**
     * 操作员把失败任务打回到上游角色时，上游看不到下游失败细节（previousFailureFeedbackSection
     * 只覆盖同角色的历史失败），这里把被打回的下游阶段失败原因显式注入恢复提示词。
     */
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

    private String roleInstruction(AgentRole role) {
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
                    - 最后生成完整性 manifest，再把严格协议写入 /work/output/result.json。
                    - 当输入上下文提供 hostAssertionContracts 时，Host 已经冻结可执行断言。仅回传两个 hostAssertionResults echo（CURRENT 和 REGRESSION），每项只能有 scope、输入给定的 contentHash 和同 scope acceptanceResults 已引用的非空 evidenceArtifactIds。不得提交 hostAssertionBundle、hostAssertionWorkspace、hostAssertionBaseUrl 或 hostAssertionContext；Host 独立选择工作区和执行规范。
                    """.strip();
            default -> throw new IllegalArgumentException("unsupported requirement role: " + role);
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
                    只输出一个 JSON 对象，不要 markdown：
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
                    只输出一个 JSON 对象，不要 markdown：
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
                    只输出一个 JSON 对象，不要 markdown：
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

    /**
     * Makes the controlled fallback operational rather than leaving it as an opaque evidence row in context JSON.
     * The text is deliberately static: no retrieved source content or arbitrary command is promoted into the prompt.
     */
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

    /**
     * Keeps the complete stage result only in the audit trail and forwards a small, structured manifest to agents.
     * RustFS object URIs remain necessary here for the server-side attachment resolver, but are never rendered into a
     * model prompt.
     */
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
                if (!environmentNotes.isEmpty()) {
                    compact.put("environmentNotes", environmentNotes);
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

    /**
     * 环境备忘跨角色传导（P1）：上游角色实测的环境事实（缺依赖、替代命令、必需环境变量等）
     * 随交接清单传给下游，避免同一个环境坑被多个角色各自重新发现（实测浪费 5~8 分钟/$1 每题）。
     */
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

    /**
     * Carries the verified Coding diff as private server-side metadata only. The model receives neither its
     * RustFS URI nor its contents; the executor rematerializes and applies it in the isolated QA worktree.
     */
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

    private String qaFailureCategory(String qaResultJson) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(safe(qaResultJson));
            return root == null ? "" : root.path("failureCategory").asText("").strip();
        } catch (JsonProcessingException exception) {
            return "";
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
            // RESULT_JSON 预览按字符截断，超长结果会被切断在字段中间而不再是合法 JSON。
            // 复用时只保留已完整写入的顶层字段，交付复核才能读到结构合法且未被补全的角色证据。
            reused.put("resultJson", salvageTruncatedResultJson(resultPreview));
            reused.put("resultJsonTruncated", true);
        }
        Map<String, Object> handoff = persistedRoleHandoff(stage, stageArtifacts);
        if (!handoff.isEmpty()) {
            // RESULT_JSON is deliberately preview-truncated for audit storage. The separately persisted,
            // integrity-checked Markdown artifact is the recovery source for downstream role handoff.
            reused.put("roleHandoff", handoff);
        }
        Map<String, Object> candidatePatch = persistedCandidatePatch(stage, stageArtifacts);
        if (!candidatePatch.isEmpty()) {
            // PATCH_DIFF is persisted independently from the preview-truncated result for retry-safe local QA.
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

    /**
     * 从被截断的 RESULT_JSON 预览中抢救出结构完整的顶层字段。
     *
     * <p>只保留能够被完整解析的键值对；被切断的那个字段及其之后的内容整体丢弃，
     * 不做任何补全，避免用推断出来的内容冒充角色交付证据。
     *
     * @param preview 被截断的结果预览
     * @return 合法的紧凑 JSON 对象；无法抢救时返回空串
     */
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

    private String withAiReviewJson(String resultJson, AiReviewRun aiReviewRun) {
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(resultJson == null ? "{}" : resultJson);
            com.fasterxml.jackson.databind.node.ObjectNode root = parsed != null && parsed.isObject()
                    ? (com.fasterxml.jackson.databind.node.ObjectNode) parsed
                    : OBJECT_MAPPER.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode review = root.putObject("aiDeliveryReview");
            review.put("runId", aiReviewRun.runId());
            review.put("attemptNo", aiReviewRun.attemptNo());
            review.put("status", aiReviewRun.status().name());
            review.put("decision", aiReviewRun.decision() == null ? "" : aiReviewRun.decision().name());
            review.put("score", aiReviewRun.score());
            review.put("retryFromRole", aiReviewRun.retryFromRole());
            review.put("summary", aiReviewRun.summary());
            review.put("packageHash", aiReviewRun.packageHash());
            review.put("errorCategory", aiReviewRun.errorCategory());
            review.put("errorMessage", aiReviewRun.errorMessage());
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot attach AI delivery review result", exception);
        }
    }

    private String withPullRequestPublicationJson(
            String resultJson,
            RequirementPullRequestPublication publication
    ) {
        String normalized = resultJson == null ? "" : resultJson.strip();
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(normalized);
            if (parsed != null && parsed.isObject()) {
                root = ((ObjectNode) parsed).deepCopy();
            } else if (!normalized.isBlank()) {
                root.put("status", "SUCCESS");
                root.put("deliveryResult", normalized);
            }
            root.put("pullRequestUrl", publication == null ? "" : publication.pullRequestUrl());
            root.set("pullRequestPublication", OBJECT_MAPPER.readTree(publicationJson(publication)));
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot attach pull request publication result", exception);
        }
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
