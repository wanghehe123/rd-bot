package com.wish.rd.bootstrap.executor.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.model.RequirementExecutionRequest;
import com.wish.rd.engine.requirement.model.RequirementExecutionResult;
import com.wish.rd.engine.requirement.RequirementExecutorPort;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest;
import com.wish.rd.exec.repair.runtime.AgentRuntimeRouter;
import com.wish.rd.bootstrap.oracle.HostOwnedAssertionGate;
import com.wish.rd.engine.oracle.HostAssertionOracle;
import com.wish.rd.engine.oracle.model.AssertionType;
import com.wish.rd.engine.oracle.model.FrozenAssertionBundle;
import com.wish.rd.engine.oracle.impl.FileAssertionRunner;
import com.wish.rd.exec.repair.qa.QaExecutionMetadataKeys;
import com.wish.rd.exec.repair.result.AgentRoleResultValidator;
import com.wish.rd.exec.repair.result.QaEvidenceBundleValidator;
import com.wish.rd.exec.repair.result.model.AgentRoleResultValidation;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.qa.QaValidationProfileService;
import com.wish.rd.rag.qa.model.QaValidationProfile;
import com.wish.rd.rag.project.runtime.ProjectRuntimeProfileService;
import com.wish.rd.rag.project.agent.AgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 需求交付执行端口到 exec 执行器和代码平台端口的 bootstrap 桥接适配器。
 */
public final class EngineRequirementExecutorAdapter implements RequirementExecutorPort {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern SSH_GIT_PATTERN = Pattern.compile("[:/]([^/:]+)/([^/]+?)(?:\\.git)?$");
    static final String AGENT_RESULT_JSON_FIELD = "__agentResultJson";
    private static final AgentRoleResultValidator AGENT_ROLE_RESULT_VALIDATOR = new AgentRoleResultValidator();
    private static final QaEvidenceBundleValidator QA_EVIDENCE_BUNDLE_VALIDATOR = new QaEvidenceBundleValidator();
    private static final HostOwnedAssertionGate DEFAULT_HOST_ASSERTION_GATE = new HostOwnedAssertionGate(
            new HostAssertionOracle(Map.of(
                    AssertionType.FILE_EXISTS, new FileAssertionRunner(),
                    AssertionType.FILE_FORBIDDEN, new FileAssertionRunner()
            ))
    );

    private final RepairExecutorPort repairExecutor;
    private final AsyncTaskExecutor executorIoTaskExecutor;
    private final TaskMaterialAttachmentResolver attachmentResolver;
    private final QaValidationProfileService qaValidationProfileService;
    private final ObjectStorageQaEvidencePublisher qaEvidencePublisher;
    private final ProjectRuntimeProfileService runtimeProfileService;
    private final ObjectStorageRoleHandoffPublisher handoffPublisher;
    private final RoleHandoffAttachmentResolver handoffAttachmentResolver;
    private final AgentRuntimeConfiguration agentRuntimeConfiguration;
    private final HostOwnedAssertionGate hostOwnedAssertionGate;

    public EngineRequirementExecutorAdapter(RepairExecutorPort repairExecutor) {
        this(repairExecutor, null, null, null, null, null, null, null, AgentRuntimeConfiguration.disabled());
    }

    public EngineRequirementExecutorAdapter(
            RepairExecutorPort repairExecutor,
            AsyncTaskExecutor executorIoTaskExecutor
    ) {
        this(repairExecutor, executorIoTaskExecutor, null, null, null, null, null, null,
                AgentRuntimeConfiguration.disabled());
    }

    public EngineRequirementExecutorAdapter(
            RepairExecutorPort repairExecutor,
            AsyncTaskExecutor executorIoTaskExecutor,
            TaskMaterialAttachmentResolver attachmentResolver
    ) {
        this(repairExecutor, executorIoTaskExecutor, attachmentResolver, null, null, null, null, null,
                AgentRuntimeConfiguration.disabled());
    }

    public EngineRequirementExecutorAdapter(
            RepairExecutorPort repairExecutor,
            AsyncTaskExecutor executorIoTaskExecutor,
            TaskMaterialAttachmentResolver attachmentResolver,
            QaValidationProfileService qaValidationProfileService
    ) {
        this(repairExecutor, executorIoTaskExecutor, attachmentResolver, qaValidationProfileService, null, null,
                null, null, AgentRuntimeConfiguration.disabled());
    }

    public EngineRequirementExecutorAdapter(
            RepairExecutorPort repairExecutor,
            AsyncTaskExecutor executorIoTaskExecutor,
            TaskMaterialAttachmentResolver attachmentResolver,
            QaValidationProfileService qaValidationProfileService,
            ObjectStorageQaEvidencePublisher qaEvidencePublisher
    ) {
        this(
                repairExecutor,
                executorIoTaskExecutor,
                attachmentResolver,
                qaValidationProfileService,
                qaEvidencePublisher,
                null,
                null,
                null,
                AgentRuntimeConfiguration.disabled()
        );
    }

    public EngineRequirementExecutorAdapter(
            RepairExecutorPort repairExecutor,
            AsyncTaskExecutor executorIoTaskExecutor,
            TaskMaterialAttachmentResolver attachmentResolver,
            QaValidationProfileService qaValidationProfileService,
            ObjectStorageQaEvidencePublisher qaEvidencePublisher,
            ProjectRuntimeProfileService runtimeProfileService
    ) {
        this(
                repairExecutor,
                executorIoTaskExecutor,
                attachmentResolver,
                qaValidationProfileService,
                qaEvidencePublisher,
                runtimeProfileService,
                null,
                null,
                AgentRuntimeConfiguration.disabled()
        );
    }

    public EngineRequirementExecutorAdapter(
            RepairExecutorPort repairExecutor,
            AsyncTaskExecutor executorIoTaskExecutor,
            TaskMaterialAttachmentResolver attachmentResolver,
            QaValidationProfileService qaValidationProfileService,
            ObjectStorageQaEvidencePublisher qaEvidencePublisher,
            ProjectRuntimeProfileService runtimeProfileService,
            ObjectStorageRoleHandoffPublisher handoffPublisher,
            RoleHandoffAttachmentResolver handoffAttachmentResolver
    ) {
        this(
                repairExecutor,
                executorIoTaskExecutor,
                attachmentResolver,
                qaValidationProfileService,
                qaEvidencePublisher,
                runtimeProfileService,
                handoffPublisher,
                handoffAttachmentResolver,
                AgentRuntimeConfiguration.disabled()
        );
    }

    public EngineRequirementExecutorAdapter(
            RepairExecutorPort repairExecutor,
            AsyncTaskExecutor executorIoTaskExecutor,
            TaskMaterialAttachmentResolver attachmentResolver,
            QaValidationProfileService qaValidationProfileService,
            ObjectStorageQaEvidencePublisher qaEvidencePublisher,
            ProjectRuntimeProfileService runtimeProfileService,
            ObjectStorageRoleHandoffPublisher handoffPublisher,
            RoleHandoffAttachmentResolver handoffAttachmentResolver,
            AgentRuntimeConfiguration agentRuntimeConfiguration
    ) {
        this(
                repairExecutor,
                executorIoTaskExecutor,
                attachmentResolver,
                qaValidationProfileService,
                qaEvidencePublisher,
                runtimeProfileService,
                handoffPublisher,
                handoffAttachmentResolver,
                agentRuntimeConfiguration,
                null
        );
    }

    public EngineRequirementExecutorAdapter(
            RepairExecutorPort repairExecutor,
            AsyncTaskExecutor executorIoTaskExecutor,
            TaskMaterialAttachmentResolver attachmentResolver,
            QaValidationProfileService qaValidationProfileService,
            ObjectStorageQaEvidencePublisher qaEvidencePublisher,
            ProjectRuntimeProfileService runtimeProfileService,
            ObjectStorageRoleHandoffPublisher handoffPublisher,
            RoleHandoffAttachmentResolver handoffAttachmentResolver,
            AgentRuntimeConfiguration agentRuntimeConfiguration,
            HostOwnedAssertionGate hostOwnedAssertionGate
    ) {
        this.repairExecutor = Objects.requireNonNull(repairExecutor, "repairExecutor must not be null");
        this.executorIoTaskExecutor = executorIoTaskExecutor;
        this.attachmentResolver = attachmentResolver;
        this.qaValidationProfileService = qaValidationProfileService;
        this.qaEvidencePublisher = qaEvidencePublisher;
        this.runtimeProfileService = runtimeProfileService;
        this.handoffPublisher = handoffPublisher;
        this.handoffAttachmentResolver = handoffAttachmentResolver;
        this.agentRuntimeConfiguration = agentRuntimeConfiguration == null
                ? AgentRuntimeConfiguration.disabled()
                : agentRuntimeConfiguration;
        this.hostOwnedAssertionGate = hostOwnedAssertionGate == null
                ? DEFAULT_HOST_ASSERTION_GATE
                : hostOwnedAssertionGate;
    }

    /**
     * Test/helper constructor that injects a Host-owned assertion gate while keeping other deps null.
     */
    public EngineRequirementExecutorAdapter(
            RepairExecutorPort repairExecutor,
            HostOwnedAssertionGate hostOwnedAssertionGate
    ) {
        this(
                repairExecutor,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                AgentRuntimeConfiguration.disabled(),
                hostOwnedAssertionGate
        );
    }

    public EngineRequirementExecutorAdapter(
            RepairExecutorPort repairExecutor,
            CodePlatformPort ignoredCodePlatform
    ) {
        this(repairExecutor);
    }

    public EngineRequirementExecutorAdapter(
            RepairExecutorPort repairExecutor,
            AgentRuntimeConfiguration agentRuntimeConfiguration
    ) {
        this(
                repairExecutor,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                agentRuntimeConfiguration
        );
    }

    /** Configuration for the requirement-delivery-only runtime router. */
    public record AgentRuntimeConfiguration(
            boolean enabled,
            AgentRuntimeRouter router,
            AgentExecutionProfileSnapshotStore snapshotStore
    ) {

        public AgentRuntimeConfiguration {
            if (enabled && (router == null || snapshotStore == null)) {
                throw new IllegalArgumentException(
                        "enabled agent runtime requires router and snapshotStore"
                );
            }
        }

        public static AgentRuntimeConfiguration disabled() {
            return new AgentRuntimeConfiguration(false, null, null);
        }
    }

    @Override
    public RequirementExecutionResult execute(RequirementExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        RepairJobCommand command = toRepairCommand(request);
        RepairExecutionResult repairResult = executeRepair(request, command);
        if (repairResult == null) {
            return RequirementExecutionResult.failure(
                    request.taskId(),
                    "repair executor returned null",
                    "{\"status\":\"FAILED\",\"errorMessage\":\"repair executor returned null\"}"
            );
        }
        if (request.role() == AgentRole.QA_AGENT && qaEvidencePublisher != null) {
            try {
                repairResult = qaEvidencePublisher.publish(repairResult);
            } catch (RuntimeException exception) {
                return RequirementExecutionResult.failure(
                        request.taskId(),
                        "QA evidence persistence failed: " + safeMessage(exception),
                        qaEvidencePersistenceFailureJson(repairResult, exception)
                );
            }
        }
        if (request.role() != AgentRole.QA_AGENT
                && repairResult.status() == RepairExecutionStatus.SUCCESS
                && handoffPublisher != null) {
            try {
                repairResult = handoffPublisher.publish(request.role(), repairResult);
            } catch (RuntimeException exception) {
                return RequirementExecutionResult.failure(
                        request.taskId(),
                        "role handoff persistence failed: " + safeMessage(exception),
                        handoffPersistenceFailureJson(repairResult, exception)
                );
            }
        }
        String invalidQaProtocolReason = invalidQaProtocolReason(request, repairResult, command);
        if (!invalidQaProtocolReason.isBlank()) {
            return RequirementExecutionResult.failure(
                    request.taskId(),
                    invalidQaProtocolReason,
                    toResultJson(request, repairResult)
            );
        }
        if (qaReportBlocksDelivery(request, repairResult)) {
            return RequirementExecutionResult.failure(
                    request.taskId(),
                    qaFailureReason(repairResult),
                    toResultJson(request, repairResult)
            );
        }
        boolean success = repairResult.status() == RepairExecutionStatus.SUCCESS;
        if (!success) {
            String reason = repairResult.errorMessage().isBlank()
                    ? "requirement execution failed: " + repairResult.status()
                    : repairResult.errorMessage();
            return RequirementExecutionResult.failure(request.taskId(), reason, toResultJson(request, repairResult));
        }
        return RequirementExecutionResult.success(
                request.taskId(),
                repairResult.summary(),
                "",
                toResultJson(request, repairResult)
        );
    }

    private RepairExecutionResult executeRepair(RepairJobCommand command) {
        if (executorIoTaskExecutor == null) {
            return repairExecutor.execute(command);
        }
        try {
            return executorIoTaskExecutor.submit(() -> repairExecutor.execute(command)).get();
        } catch (TaskRejectedException exception) {
            throw new IllegalStateException("executor I/O thread pool rejected requirement execution", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("requirement execution interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("requirement execution failed", cause);
        }
    }

    private RepairExecutionResult executeRepair(
            RequirementExecutionRequest request,
            RepairJobCommand command
    ) {
        if (!agentRuntimeConfiguration.enabled()) {
            return executeRepair(command);
        }
        String snapshotId = request.executionProfileSnapshotId();
        if (snapshotId.isBlank()) {
            throw new IllegalStateException(
                    "agent runtime is enabled but execution profile snapshot id is blank"
            );
        }
        AgentExecutionProfileSnapshot snapshot = agentRuntimeConfiguration.snapshotStore()
                .findBySnapshotId(snapshotId)
                .orElseThrow(() -> new IllegalStateException(
                        "execution profile snapshot not found: " + snapshotId
                ));
        if (!snapshot.stageRunId().equals(request.stageRunId())
                || !snapshot.taskId().equals(request.taskId())
                || !snapshot.role().equals(request.role().name())) {
            throw new IllegalStateException(
                    "execution profile snapshot identity does not match request: " + snapshotId
            );
        }
        if (!snapshot.hasValidIntegrityHash()) {
            throw new IllegalStateException(
                    "execution profile snapshot is corrupt: " + snapshotId
            );
        }
        return agentRuntimeConfiguration.router().execute(
                new AgentRuntimeExecutionRequest(snapshot, command)
        );
    }

    private RepairJobCommand toRepairCommand(RequirementExecutionRequest request) {
        RdRequirementTask task = request.task();
        RepositoryParts repository = repositoryParts(task);
        String executionTaskId = executionTaskId(request);
        List<com.wish.rd.exec.repair.execution.model.RepairInputAttachment> attachments = inputAttachments(request);
        requireVerifiedCandidatePatchForLocalQa(request, attachments);
        Map<String, String> context = contextJson(request, attachments);
        return new RepairJobCommand(
                executionTaskId,
                executionTaskId,
                "",
                task.title(),
                request.prompt(),
                task.repositoryUrl(),
                repository.owner(),
                repository.name(),
                task.baseBranch(),
                workBranch(task),
                context,
                policyJson(request, attachments),
                attachments
        );
    }

    private List<com.wish.rd.exec.repair.execution.model.RepairInputAttachment> inputAttachments(
            RequirementExecutionRequest request
    ) {
        List<com.wish.rd.exec.repair.execution.model.RepairInputAttachment> attachments = new ArrayList<>();
        if (attachmentResolver != null) {
            attachments.addAll(attachmentResolver.resolve(request.materials()));
        }
        if (handoffAttachmentResolver != null) {
            attachments.addAll(handoffAttachmentResolver.resolve(request.role().name(), request.upstreamResultJson()));
        }
        return List.copyOf(attachments);
    }

    private String executionTaskId(RequirementExecutionRequest request) {
        if (request.role() == AgentRole.CODING_AGENT) {
            return request.taskId();
        }
        return request.taskId() + "-" + request.role().name().toLowerCase(java.util.Locale.ROOT);
    }

    private Map<String, String> policyJson(
            RequirementExecutionRequest request,
            List<com.wish.rd.exec.repair.execution.model.RepairInputAttachment> attachments
    ) {
        Map<String, String> policy = new LinkedHashMap<>();
        policy.put("bridge", "engine-requirement-executor");
        // A coding stage may produce a local patch without publishing it. Only an explicit
        // task-level PR requirement grants the executor permission to mutate the remote.
        policy.put("repositoryPublishRequired", Boolean.toString(request.pullRequestRequired()));
        policy.put("repositoryDeliveryMode", request.pullRequestRequired() ? "PUBLISH" : "LOCAL_ONLY");
        policy.put("applyCandidatePatch", Boolean.toString(shouldApplyCandidatePatch(request, attachments)));
        if (request.executionProfileSnapshotId() != null
                && !request.executionProfileSnapshotId().isBlank()) {
            policy.put("executionProfileSnapshotId", request.executionProfileSnapshotId());
        }
        appendProjectRuntimePolicy(policy, request);
        return Map.copyOf(policy);
    }

    private void appendProjectRuntimePolicy(
            Map<String, String> policy,
            RequirementExecutionRequest request
    ) {
        if (agentRuntimeConfiguration.enabled()) {
            return;
        }
        if (runtimeProfileService == null || request == null || request.task() == null) {
            return;
        }
        String projectId = request.task().projectId();
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        runtimeProfileService.list(projectId).stream()
                .filter(profile -> request.role().name().equals(profile.role()))
                .filter(profile -> ProjectRuntimeProfileService.VERIFIED_STATUS.equals(profile.validationStatus()))
                .filter(profile -> ProjectRuntimeProfileService.SUPPORTED_AGENT_TYPE.equals(profile.agentType()))
                .filter(profile -> !ProjectRuntimeProfileService.usesCurrentRuntimeProfileContract(profile))
                .findFirst()
                .ifPresent(profile -> {
                    throw new IllegalStateException(
                            "project runtime profile for " + request.role().name()
                                    + " was verified under a legacy Dockerfile contract; re-upload it before execution"
                    );
                });
        runtimeProfileService.resolveVerified(projectId, request.role().name()).ifPresent(profile -> {
            policy.put("runtimeImage", profile.image());
            policy.put("runtimeImageVerified", "true");
            policy.put("runtimeAgentType", profile.agentType());
            policy.put("runtimeProfileRole", profile.role());
            policy.put("runtimeProfileDockerfileSha256", profile.dockerfileSha256());
        });
    }

    private Map<String, String> contextJson(
            RequirementExecutionRequest request,
            List<com.wish.rd.exec.repair.execution.model.RepairInputAttachment> attachments
    ) {
        Map<String, String> context = new LinkedHashMap<>();
        RdRequirementTask task = request.task();
        context.put("workflowTaskId", request.taskId());
        context.put("stageRunId", request.stageRunId());
        context.put("taskId", task.taskId());
        context.put("taskType", task.taskType());
        context.put("agentRole", request.role().name());
        context.put("pullRequestRequired", Boolean.toString(request.pullRequestRequired()));
        context.put("title", task.title());
        context.put("expectedResult", task.expectedResult());
        List<FrozenAssertionBundle> hostAssertionContracts = freezeHostAssertions(request, task);
        if (hostAssertionContracts.isEmpty()) {
            context.put("acceptanceCriteriaJson", task.acceptanceCriteriaJson());
        } else {
            // The QA agent receives identities only; canonical executable specs remain Host-owned.
            context.put("acceptanceCriteriaJson", "[]");
            context.put("hostAssertionContracts", hostAssertionContractsJson(hostAssertionContracts));
        }
        context.put("roleContextJson", request.roleContextJson());
        context.put("upstreamHandoffManifestJson", handoffManifestForWorkspace(request, attachments));
        if (request.executionProfileSnapshotId() != null
                && !request.executionProfileSnapshotId().isBlank()) {
            context.put("executionProfileSnapshotId", request.executionProfileSnapshotId());
        }
        if (!request.inputManifestHash().isBlank()) {
            context.put("inputManifestHash", request.inputManifestHash());
        }
        if (!request.contextPolicyHash().isBlank()) {
            context.put("contextPolicyHash", request.contextPolicyHash());
        }
        if (!request.inputManifestJson().isBlank()) {
            context.put("inputManifestJson", request.inputManifestJson());
        }
        if (!request.contextPolicyJson().isBlank()) {
            context.put("contextPolicyJson", request.contextPolicyJson());
        }
        if (handoffPublisher != null) {
            context.put("roleHandoffMaxTokens", String.valueOf(handoffPublisher.maxTokens()));
        }
        context.put("materials", materialSummary(request.materials()));
        appendQaProfileContext(context, request, task);
        return Map.copyOf(context);
    }

    private List<FrozenAssertionBundle> freezeHostAssertions(
            RequirementExecutionRequest request,
            RdRequirementTask task
    ) {
        if (request == null || task == null || request.role() != AgentRole.QA_AGENT) {
            return List.of();
        }
        return hostOwnedAssertionGate.freeze(
                request.taskId(),
                request.stageRunId(),
                task.hostAssertionBundle()
        );
    }

    private String hostAssertionContractsJson(List<FrozenAssertionBundle> contracts) {
        List<Map<String, Object>> values = (contracts == null ? List.<FrozenAssertionBundle>of() : contracts).stream()
                .sorted(java.util.Comparator.comparing(FrozenAssertionBundle::scope))
                .map(bundle -> Map.<String, Object>of(
                        "scope", bundle.scope(),
                        "contentHash", bundle.contentHash(),
                        "version", bundle.version()
                ))
                .toList();
        try {
            return OBJECT_MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Host assertion contracts cannot be serialized", exception);
        }
    }

    private String handoffManifestForWorkspace(
            RequirementExecutionRequest request,
            List<com.wish.rd.exec.repair.execution.model.RepairInputAttachment> attachments
    ) {
        if (request == null || request.upstreamResultJson() == null || request.upstreamResultJson().isBlank()) {
            return "[]";
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(request.upstreamResultJson());
            if (root == null || !root.path("stages").isArray()) {
                return "[]";
            }
            List<Map<String, Object>> manifests = new ArrayList<>();
            for (JsonNode stage : root.path("stages")) {
                JsonNode handoff = stage.has("handoff") ? stage.path("handoff") : stage.path("roleHandoff");
                if (!handoff.isObject()
                        || !request.role().name().equalsIgnoreCase(handoff.path("targetRole").asText(""))) {
                    continue;
                }
                String sourceRole = firstNonBlank(
                        handoff.path("sourceRole").asText(""), stage.path("role").asText("")
                );
                if (sourceRole.isBlank()) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sourceRole", sourceRole);
                item.put("targetRole", request.role().name());
                item.put("path", "/work/input/attachments/handoff-"
                        + sourceRole.toLowerCase(java.util.Locale.ROOT) + ".md");
                item.put("sha256", handoff.path("sha256").asText(""));
                item.put("bytes", handoff.path("bytes").asLong(0L));
                item.put("summary", handoff.path("summary").asText(""));
                manifests.add(Map.copyOf(item));

                // Continue scanning the stage for a Coding candidate patch below; a stage may contain both a
                // Markdown handoff and the patch that local QA will validate.
            }
            if (shouldApplyCandidatePatch(request, attachments)) {
                for (JsonNode stage : root.path("stages")) {
                    JsonNode candidatePatch = stage.path("candidatePatch");
                    if (!isCandidatePatchForLocalQa(candidatePatch, stage.path("role").asText(""))) {
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("sourceRole", AgentRole.CODING_AGENT.name());
                    item.put("targetRole", AgentRole.QA_AGENT.name());
                    item.put("path", "/work/input/attachments/candidate-patch.diff");
                    item.put("sha256", candidatePatch.path("sha256").asText(""));
                    item.put("bytes", candidatePatch.path("bytes").asLong(0L));
                    item.put("applied", true);
                    manifests.add(Map.copyOf(item));
                    break;
                }
            }
            return OBJECT_MAPPER.writeValueAsString(manifests);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private void requireVerifiedCandidatePatchForLocalQa(
            RequirementExecutionRequest request,
            List<com.wish.rd.exec.repair.execution.model.RepairInputAttachment> attachments
    ) {
        if (request == null
                || request.role() != AgentRole.QA_AGENT
                || request.pullRequestRequired()
                || !hasCodingStage(request.upstreamResultJson())) {
            return;
        }
        if (!hasCandidatePatchAttachment(attachments)) {
            throw new IllegalStateException(
                    "local QA requires a verified candidate patch from the successful CODING_AGENT stage"
            );
        }
    }

    private boolean shouldApplyCandidatePatch(
            RequirementExecutionRequest request,
            List<com.wish.rd.exec.repair.execution.model.RepairInputAttachment> attachments
    ) {
        return request != null
                && request.role() == AgentRole.QA_AGENT
                && !request.pullRequestRequired()
                && hasCandidatePatchAttachment(attachments);
    }

    private static boolean hasCandidatePatchAttachment(
            List<com.wish.rd.exec.repair.execution.model.RepairInputAttachment> attachments
    ) {
        return (attachments == null ? List.<com.wish.rd.exec.repair.execution.model.RepairInputAttachment>of() : attachments)
                .stream()
                .anyMatch(attachment -> "candidate-patch.diff".equals(attachment.filename())
                        && "text/x-diff".equalsIgnoreCase(attachment.mimeType()));
    }

    private static boolean hasCodingStage(String upstreamResultJson) {
        if (upstreamResultJson == null || upstreamResultJson.isBlank()) {
            return false;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(upstreamResultJson);
            if (root == null || !root.path("stages").isArray()) {
                return false;
            }
            for (JsonNode stage : root.path("stages")) {
                if (AgentRole.CODING_AGENT.name().equalsIgnoreCase(stage.path("role").asText(""))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // The handoff resolver owns malformed manifests; this guard does not downgrade their failure.
        }
        return false;
    }

    private static boolean isCandidatePatchForLocalQa(JsonNode candidatePatch, String fallbackSourceRole) {
        if (candidatePatch == null || !candidatePatch.isObject()) {
            return false;
        }
        String sourceRole = firstNonBlank(
                candidatePatch.path("sourceRole").asText(""), fallbackSourceRole
        );
        String sha256 = candidatePatch.path("sha256").asText("").strip();
        long bytes = candidatePatch.path("bytes").asLong(-1L);
        return AgentRole.CODING_AGENT.name().equalsIgnoreCase(sourceRole)
                && AgentRole.QA_AGENT.name().equalsIgnoreCase(candidatePatch.path("targetRole").asText(""))
                && "patch.diff".equals(candidatePatch.path("artifactName").asText(""))
                && candidatePatch.path("artifactUri").asText("").startsWith("s3://")
                && sha256.matches("(?:sha256:)?[0-9a-fA-F]{64}")
                && bytes > 0L
                && bytes <= 10L * 1024L * 1024L;
    }

    private void appendQaProfileContext(
            Map<String, String> context,
            RequirementExecutionRequest request,
            RdRequirementTask task
    ) {
        if (request.role() != AgentRole.QA_AGENT || qaValidationProfileService == null) {
            return;
        }
        QaValidationProfileService.Resolution resolution = qaValidationProfileService.resolve(
                request.taskId(),
                task == null ? "" : task.projectId()
        );
        if (resolution.profile().isEmpty()) {
            return;
        }
        String key = "TASK_OVERRIDE".equals(resolution.source())
                ? "qaTaskOverrideJson"
                : "qaProjectProfileJson";
        context.put(key, qaProfileJson(resolution.profile().orElseThrow()));
    }

    private String qaProfileJson(QaValidationProfile profile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("mode", profile.mode());
        value.put("baseUrl", profile.baseUrl());
        value.put("startCommand", profile.startCommand());
        value.put("healthPath", profile.healthPath());
        value.put("allowedHosts", profile.allowedHosts());
        value.put("regressionCommands", profile.regressionCommands());
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize QA validation profile", exception);
        }
    }

    private String materialSummary(List<TaskMaterial> materials) {
        return materials.stream()
                .map(material -> "%s|%s|%s".formatted(
                        material.materialId(),
                        material.sourceType().name(),
                        material.contentHash()
                ))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String toResultJson(RequirementExecutionRequest request, RepairExecutionResult repairResult) {
        Map<String, Object> value = expandedAgentResult(repairResult.rawResultJson());
        value.putIfAbsent("status", repairResult.status().name());
        value.putIfAbsent("summary", repairResult.summary());
        normalizeRoleResult(request, repairResult, value);
        value.put("pullRequestUrl", "");
        value.put("dockerMetadata", repairResult.dockerMetadataJson());
        appendHostProviderFallbackSafety(repairResult, value);
        value.put("codePlatformMetadata", Map.of());
        value.put("testMetadata", repairResult.testMetadataJson());
        value.put("riskMetadata", repairResult.riskMetadataJson());
        value.put("stageArtifacts", stageArtifacts(repairResult));
        appendRoleHandoff(request, repairResult, value);
        if (!repairResult.errorMessage().isBlank()) {
            value.putIfAbsent("errorMessage", repairResult.errorMessage());
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{\"status\":\"FAILED\",\"errorMessage\":\"failed to serialize execution result\"}";
        }
    }

    private static void appendHostProviderFallbackSafety(
            RepairExecutionResult repairResult,
            Map<String, Object> value
    ) {
        String safetyJson = repairResult.dockerMetadataJson().getOrDefault(
                "providerFallbackSafety", ""
        );
        if (safetyJson.isBlank()) {
            return;
        }
        try {
            JsonNode safety = OBJECT_MAPPER.readTree(safetyJson);
            if (safety != null && safety.isObject()) {
                value.put("hostProviderFallbackSafety", safety);
            }
        } catch (JsonProcessingException ignored) {
            // Malformed executor metadata remains absent and therefore fails closed.
        }
    }

    private void appendRoleHandoff(
            RequirementExecutionRequest request,
            RepairExecutionResult repairResult,
            Map<String, Object> value
    ) {
        if (request == null || request.role() == AgentRole.QA_AGENT) {
            return;
        }
        AgentRole targetRole = directDownstreamRole(request.role());
        if (targetRole == null) {
            return;
        }
        RepairArtifact artifact = repairResult.artifacts().stream()
                .filter(candidate -> candidate.type() == com.wish.rd.exec.repair.execution.model.RepairArtifactType.HANDOFF_MARKDOWN)
                .findFirst()
                .orElse(null);
        if (artifact == null || artifact.uri().isBlank() || !artifact.uri().startsWith("s3://")) {
            return;
        }
        JsonNode nextPrompt = nextPrompt(value);
        String declaredTarget = nextPrompt == null ? "" : nextPrompt.path("targetRole").asText("").strip();
        if (!declaredTarget.isBlank() && !targetRole.name().equalsIgnoreCase(declaredTarget)) {
            return;
        }
        Map<String, Object> handoff = new LinkedHashMap<>();
        handoff.put("sourceRole", request.role().name());
        handoff.put("targetRole", targetRole.name());
        handoff.put("artifactName", artifact.name());
        handoff.put("artifactUri", artifact.uri());
        handoff.put("sha256", artifact.metadataJson().getOrDefault("sha256", ""));
        handoff.put("bytes", parseLong(artifact.metadataJson().get("bytes")));
        handoff.put("summary", nextPrompt == null
                ? repairResult.summary()
                : firstNonBlank(nextPrompt.path("summary").asText(""), repairResult.summary()));
        value.put("roleHandoff", Map.copyOf(handoff));
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value.strip());
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    /**
     * Accept the explicit snake_case protocol while keeping the earlier camelCase draft readable.
     */
    private static JsonNode nextPrompt(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        Object raw = value.containsKey("next_prompt")
                ? value.get("next_prompt")
                : value.get("nextPrompt");
        return raw instanceof JsonNode node && node.isObject() ? node : null;
    }

    private static AgentRole directDownstreamRole(AgentRole role) {
        return switch (role) {
            case REQUIREMENT_REVIEWER -> AgentRole.SOLUTION_ARCHITECT;
            case SOLUTION_ARCHITECT -> AgentRole.CODING_AGENT;
            case CODING_AGENT -> AgentRole.QA_AGENT;
            case QA_AGENT -> null;
            default -> null;
        };
    }

    private List<Map<String, Object>> stageArtifacts(RepairExecutionResult repairResult) {
        List<Map<String, Object>> artifacts = new ArrayList<>();
        for (RepairArtifact artifact : repairResult.artifacts()) {
            if (isRestrictedPrivateArtifact(artifact)) {
                continue;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", artifact.type().name());
            value.put("name", artifact.name());
            value.put("uri", artifact.uri());
            value.put("summary", artifact.summary());
            value.put("contentPreview", artifact.metadataJson().getOrDefault("contentPreview", ""));
            Map<String, String> metadata = new LinkedHashMap<>(artifact.metadataJson());
            metadata.put("artifactName", artifact.name());
            value.put("metadataJson", Map.copyOf(metadata));
            artifacts.add(Map.copyOf(value));
        }
        if (!repairResult.dockerMetadataJson().isEmpty()) {
            Map<String, Object> dockerMetadata = new LinkedHashMap<>();
            dockerMetadata.put("type", "DOCKER_METADATA");
            dockerMetadata.put("name", "docker-metadata.json");
            dockerMetadata.put("uri", "");
            dockerMetadata.put("summary", "Docker execution metadata");
            dockerMetadata.put("contentPreview", repairResult.dockerMetadataJson());
            dockerMetadata.put("metadataJson", repairResult.dockerMetadataJson());
            artifacts.add(Map.copyOf(dockerMetadata));
        }
        return List.copyOf(artifacts);
    }

    private static boolean isRestrictedPrivateArtifact(RepairArtifact artifact) {
        return artifact != null
                && ("true".equalsIgnoreCase(artifact.metadataJson().get("restricted"))
                || artifact.type() == com.wish.rd.exec.repair.execution.model.RepairArtifactType.PI_RAW_EVENTS
                || artifact.type() == com.wish.rd.exec.repair.execution.model.RepairArtifactType.PI_SESSION);
    }

    private void normalizeRoleResult(
            RequirementExecutionRequest request,
            RepairExecutionResult repairResult,
            Map<String, Object> value
    ) {
        if (request == null || repairResult.status() != RepairExecutionStatus.SUCCESS) {
            return;
        }
        if (request.role() == AgentRole.SOLUTION_ARCHITECT) {
            normalizeSolutionArchitectResult(request, repairResult, value);
        }
    }

    private void normalizeSolutionArchitectResult(
            RequirementExecutionRequest request,
            RepairExecutionResult repairResult,
            Map<String, Object> value
    ) {
        boolean hasSolutionProtocol = hasNonEmptyArray(value.get("affectedFiles"))
                && hasNonEmptyArray(value.get("implementationSteps"))
                && hasNonEmptyArray(value.get("acceptanceMapping"))
                && hasNonEmptyArray(value.get("testPlan"));
        if (hasSolutionProtocol) {
            return;
        }
        value.putIfAbsent("summary", repairResult.summary().isBlank()
                ? "Solution plan normalized from executor output"
                : repairResult.summary());
        if (!hasNonEmptyArray(value.get("affectedFiles"))) {
            value.put("affectedFiles", normalizedChangedFiles(value));
        }
        if (!hasNonEmptyArray(value.get("implementationSteps"))) {
            value.put("implementationSteps", List.of(
                    "Review role context, evidence and requirement acceptance criteria",
                    "Apply the minimal scoped change described by affectedFiles",
                    "Run the testPlan commands and record QA evidence before delivery review"
            ));
        }
        if (!hasNonEmptyArray(value.get("acceptanceMapping"))) {
            value.put("acceptanceMapping", acceptanceCriteria(request.task()).stream()
                    .map(criteria -> Map.of(
                            "criteria", criteria,
                            "validation", validationForCriterion(criteria)
                    ))
                    .toList());
        }
        if (!hasNonEmptyArray(value.get("testPlan"))) {
            value.put("testPlan", acceptanceCriteria(request.task()).stream()
                    .map(criteria -> {
                        String command = commandFromCriterion(criteria);
                        return Map.of(
                                "criteria", criteria,
                                "command", command.isBlank()
                                        ? "manual verification for acceptance criterion"
                                        : command
                        );
                    })
                    .toList());
        }
    }

    private List<String> normalizedChangedFiles(Map<String, Object> value) {
        List<String> affectedFiles = stringList(value.get("changedFiles"));
        if (!affectedFiles.isEmpty()) {
            return affectedFiles;
        }
        affectedFiles = stringList(value.get("affectedFiles"));
        if (!affectedFiles.isEmpty()) {
            return affectedFiles;
        }
        return List.of("TBD by coding agent from requirement evidence");
    }

    private List<String> stringList(Object rawValue) {
        if (rawValue instanceof List<?> list) {
            return list.stream()
                    .map(this::text)
                    .filter(value -> !value.isBlank())
                    .toList();
        }
        String rawText = text(rawValue);
        if (rawText.isBlank()) {
            return List.of();
        }
        return rawText.lines()
                .flatMap(line -> java.util.Arrays.stream(line.split(",")))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private String validationForCriterion(String criteria) {
        String command = commandFromCriterion(criteria);
        if (!command.isBlank()) {
            return command;
        }
        return "Verify the criterion during coding and QA review";
    }

    private List<String> acceptanceCriteria(RdRequirementTask task) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(task == null ? "[]" : task.acceptanceCriteriaJson());
            if (!root.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            root.forEach(item -> {
                String value = item.asText("").strip();
                if (!value.isBlank()) {
                    values.add(value);
                }
            });
            return List.copyOf(values);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private String commandFromCriterion(String criterion) {
        if (criterion == null || criterion.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("`([^`]*(?:test|grep)[^`]*)`").matcher(criterion);
        List<String> commands = new ArrayList<>();
        while (matcher.find()) {
            String command = matcher.group(1).strip();
            if (!command.isBlank()) {
                commands.add(command);
            }
        }
        return String.join(" && ", commands);
    }

    private boolean hasNonEmptyArray(Object value) {
        if (value instanceof JsonNode node) {
            return node.isArray() && !node.isEmpty();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        return false;
    }

    private boolean qaReportBlocksDelivery(
            RequirementExecutionRequest request,
            RepairExecutionResult repairResult
    ) {
        if (request == null
                || request.role() != AgentRole.QA_AGENT
                || repairResult == null
                || repairResult.status() != RepairExecutionStatus.SUCCESS) {
            return false;
        }
        Map<String, Object> value = expandedAgentResult(repairResult.rawResultJson());
        String status = text(value.get("status"));
        return "FAILED".equalsIgnoreCase(status) || "SKIPPED".equalsIgnoreCase(status);
    }

    private String invalidQaProtocolReason(
            RequirementExecutionRequest request,
            RepairExecutionResult repairResult,
            RepairJobCommand command
    ) {
        if (request == null
                || request.role() != AgentRole.QA_AGENT
                || repairResult == null
                || repairResult.status() != RepairExecutionStatus.SUCCESS) {
            return "";
        }
        try {
            String json = OBJECT_MAPPER.writeValueAsString(expandedAgentResult(repairResult.rawResultJson()));
            AgentRoleResultValidation validation = AGENT_ROLE_RESULT_VALIDATOR.validate("QA_AGENT", json);
            if (!validation.valid()) {
                return "QA evidence protocol invalid: " + String.join("; ", validation.errors());
            }
            AgentRoleResultValidation evidenceValidation = QA_EVIDENCE_BUNDLE_VALIDATOR.validate(
                    json,
                    repairResult.artifacts(),
                    acceptanceCriteria(request.task()),
                    candidateChangedFilesFromMetadata(repairResult.dockerMetadataJson())
            );
            if (!evidenceValidation.valid()) {
                return "QA evidence bundle invalid: " + String.join("; ", evidenceValidation.errors());
            }
            List<String> hostAssertionErrors = hostOwnedAssertionGate.validate(
                    json,
                    request.taskId(),
                    request.stageRunId(),
                    command
            );
            return hostAssertionErrors.isEmpty()
                    ? ""
                    : "Host-owned assertion failed: " + String.join("; ", hostAssertionErrors);
        } catch (JsonProcessingException exception) {
            return "QA evidence protocol invalid: result cannot be serialized";
        }
    }

    private static List<String> candidateChangedFilesFromMetadata(Map<String, String> dockerMetadata) {
        // Fail-closed: only the docker metadata channel is authoritative for docs-only.
        return QaExecutionMetadataKeys.candidateChangedFilesFrom(dockerMetadata);
    }

    private String qaFailureReason(RepairExecutionResult repairResult) {
        Map<String, Object> value = expandedAgentResult(repairResult.rawResultJson());
        String summary = text(value.get("summary"));
        if (summary.isBlank()) {
            return "QA_AGENT failed acceptance";
        }
        return "QA_AGENT failed: " + summary;
    }

    private String qaEvidencePersistenceFailureJson(
            RepairExecutionResult repairResult,
            RuntimeException exception
    ) {
        Map<String, Object> value = expandedAgentResult(repairResult.rawResultJson());
        value.put("status", "FAILED");
        value.put("summary", "QA evidence could not be persisted");
        value.put("failureCategory", "QA_INFRASTRUCTURE");
        value.put("retryRecommendation", "HUMAN");
        value.put("evidencePersistenceError", safeMessage(exception));
        value.put("stageArtifacts", List.of());
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ignored) {
            return "{\"status\":\"FAILED\",\"failureCategory\":\"QA_INFRASTRUCTURE\","
                    + "\"retryRecommendation\":\"HUMAN\"}";
        }
    }

    private String handoffPersistenceFailureJson(
            RepairExecutionResult repairResult,
            RuntimeException exception
    ) {
        Map<String, Object> value = expandedAgentResult(repairResult.rawResultJson());
        value.put("status", "FAILED");
        value.put("summary", "role handoff could not be persisted");
        value.put("handoffPersistenceError", safeMessage(exception));
        value.put("stageArtifacts", List.of());
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ignored) {
            return "{\"status\":\"FAILED\",\"summary\":\"role handoff could not be persisted\"}";
        }
    }

    private static String safeMessage(RuntimeException exception) {
        if (exception == null || exception.getMessage() == null || exception.getMessage().isBlank()) {
            return "unknown persistence error";
        }
        String message = exception.getMessage().strip();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private String text(Object value) {
        if (value instanceof JsonNode node) {
            return node.isTextual() ? node.asText("").strip() : node.toString();
        }
        return value == null ? "" : value.toString().strip();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = value == null ? "" : value.strip();
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private Map<String, Object> expandedAgentResult(Map<String, String> rawResultJson) {
        Map<String, Object> value = new LinkedHashMap<>();
        String completeAgentJson = rawResultJson.getOrDefault(AGENT_RESULT_JSON_FIELD, "");
        if (!completeAgentJson.isBlank()) {
            try {
                JsonNode root = OBJECT_MAPPER.readTree(completeAgentJson);
                if (root != null && root.isObject()) {
                    root.fields().forEachRemaining(entry -> value.put(entry.getKey(), entry.getValue()));
                }
            } catch (JsonProcessingException ignored) {
                value.put(AGENT_RESULT_JSON_FIELD, completeAgentJson);
            }
        }
        rawResultJson.forEach((key, rawValue) -> {
            if (!AGENT_RESULT_JSON_FIELD.equals(key)) {
                value.putIfAbsent(key, rawValue);
            }
        });
        return value;
    }

    private RepositoryParts repositoryParts(RdRequirementTask task) {
        String owner = task.repoOwner();
        String name = task.repoName();
        if (!owner.isBlank() && !name.isBlank()) {
            return new RepositoryParts(owner, stripGitSuffix(name));
        }
        RepositoryParts parsed = parseRepositoryUrl(task.repositoryUrl());
        if (!owner.isBlank()) {
            return new RepositoryParts(owner, parsed.name());
        }
        if (!name.isBlank()) {
            return new RepositoryParts(parsed.owner(), stripGitSuffix(name));
        }
        return parsed;
    }

    private RepositoryParts parseRepositoryUrl(String repositoryUrl) {
        String normalized = repositoryUrl == null ? "" : repositoryUrl.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("repositoryUrl must not be blank");
        }
        try {
            URI uri = new URI(normalized);
            String path = uri.getPath() == null ? "" : uri.getPath();
            RepositoryParts parts = parsePath(path);
            if (!parts.owner().isBlank() && !parts.name().isBlank()) {
                return parts;
            }
        } catch (URISyntaxException ignored) {
            // Fall back to SSH-like parsing below.
        }
        Matcher matcher = SSH_GIT_PATTERN.matcher(normalized);
        if (matcher.find()) {
            return new RepositoryParts(matcher.group(1), stripGitSuffix(matcher.group(2)));
        }
        throw new IllegalArgumentException("repositoryUrl must contain owner and repository name");
    }

    private RepositoryParts parsePath(String path) {
        String normalized = path == null ? "" : path.strip();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        String[] parts = normalized.split("/");
        if (parts.length < 2) {
            return new RepositoryParts("", "");
        }
        return new RepositoryParts(parts[0], stripGitSuffix(parts[1]));
    }

    private String workBranch(RdRequirementTask task) {
        return "requirement/" + task.taskId();
    }

    private String stripGitSuffix(String value) {
        String normalized = value == null ? "" : value.strip();
        return normalized.endsWith(".git") ? normalized.substring(0, normalized.length() - 4) : normalized;
    }

    private record RepositoryParts(String owner, String name) {
    }
}
