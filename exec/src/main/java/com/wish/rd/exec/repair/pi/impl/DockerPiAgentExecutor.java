package com.wish.rd.exec.repair.pi.impl;

import com.wish.rd.exec.repair.pi.AgentPrivateArtifactPublisher;
import com.wish.rd.exec.repair.pi.PiCredentialLeaseIssuer;
import com.wish.rd.exec.repair.pi.PiRequestV2Materializer;
import com.wish.rd.exec.repair.pi.PiResourceManifestMaterializerPort;
import com.wish.rd.exec.repair.pi.PiSkillMaterializerPort;
import com.wish.rd.exec.repair.pi.RuntimeContextPreflightValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.docker.ContainerOutputListener;
import com.wish.rd.exec.repair.docker.ContainerRunnerPort;
import com.wish.rd.exec.repair.docker.RepairWorkspaceFactory;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.docker.StreamingContainerRunnerPort;
import com.wish.rd.exec.repair.docker.model.ContainerRunRequest;
import com.wish.rd.exec.repair.docker.model.ContainerRunResult;
import com.wish.rd.exec.repair.docker.model.ContainerSecurityPolicy;
import com.wish.rd.exec.repair.docker.model.RepairWorkspace;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.qa.QaRepositoryProfileDetector;
import com.wish.rd.exec.repair.qa.model.QaExecutionProfile;
import com.wish.rd.exec.repair.result.AgentRoleResultValidator;
import com.wish.rd.exec.repair.result.StructuredResultValidator;
import com.wish.rd.exec.repair.result.model.AgentRoleResultValidation;
import com.wish.rd.exec.repair.result.model.StructuredRepairResult;
import com.wish.rd.exec.repair.result.model.StructuredResultValidation;
import com.wish.rd.exec.repair.runtime.AgentEventLineDecoder;
import com.wish.rd.exec.repair.runtime.AgentExecutionEventSink;
import com.wish.rd.exec.repair.runtime.AgentRuntimeExecutorPort;
import com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest;
import com.wish.rd.exec.repair.runtime.usage.AgentEventTokenUsageParser;
import com.wish.rd.exec.repair.runtime.usage.model.AgentEventTokenUsageSnapshot;
import com.wish.rd.exec.repair.security.SecretRedactor;
import com.wish.rd.exec.repair.security.model.ExecutionAllowlistPolicy;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.project.agent.model.ContextProtocolVersion;
import com.wish.rd.rag.project.agent.model.FactFreshnessEvaluator;
import com.wish.rd.rag.project.agent.model.ModelProviderProtocol;
import com.wish.rd.rag.project.agent.model.RuntimeContextManifest;
import com.wish.rd.rag.project.agent.model.RuntimeContextPolicy;

import java.math.BigDecimal;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Requirement-delivery Pi executor. It owns the Pi request contract and only
 * depends on the RD normalized event stream, never on Pi SDK types.
 */
public final class DockerPiAgentExecutor implements AgentRuntimeExecutorPort {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern ENV_NAME = Pattern.compile("[A-Z_][A-Z0-9_]*");
    private static final String CONTAINER_REPO = "/work/repo";
    private static final String CONTAINER_REPO_READONLY = "/work/repo:ro";
    private static final String CONTAINER_INPUT = "/work/input:ro";
    private static final String CONTAINER_OUTPUT = "/work/output";
    private static final String CONTAINER_CACHE = "/work/cache";
    private static final String RESULT_TOOL = "rd_submit_result";
    private static final Set<String> READ_ONLY_REPO_ROLES = Set.of(
            "REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "QA_AGENT"
    );
    /**
     * Roles that must not reach the public internet from the container. Coding/QA keep the
     * configured mode only when credential relay is off; with relay enabled they also use
     * {@code none} until a controlled relay/egress network is wired.
     */
    private static final Set<String> NETWORK_NONE_ROLES = Set.of(
            "REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT"
    );
    private static final long DEFAULT_EXECUTION_TIMEOUT_MILLIS = 60L * 60L * 1000L;
    private static final long DEFAULT_BASH_COMMAND_TIMEOUT_MILLIS = 15L * 60L * 1000L;
    private static final String DEFAULT_CREDENTIAL_RELAY_URL =
            "http://host.docker.internal:18080/internal/pi/credential-relay/redeem";
    private static final Map<String, String> PI_TMPFS_MOUNTS = Map.of(
            "/work/pi-agent", "rw,exec,size=256m,uid=1000,gid=1000",
            "/tmp", "rw,noexec,nosuid,size=1g,uid=1000,gid=1000",
            "/home/node", "rw,noexec,nosuid,size=256m,uid=1000,gid=1000"
    );
    private static final ContainerSecurityPolicy PI_SECURITY_POLICY = piSecurityPolicy(512);
    private static final ContainerSecurityPolicy PI_QA_SECURITY_POLICY = piSecurityPolicy(1024);
    private static final String AGENT_RESULT_JSON = "__agentResultJson";
    private static final int SAFE_EVENT_PREVIEW_CHARS = 64 * 1024;
    private static final Set<String> SUPPORTED_ROLES = Set.of(
            "REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT", "QA_AGENT"
    );
    private static final AgentRoleResultValidator ROLE_RESULT_VALIDATOR = new AgentRoleResultValidator();
    private static final RuntimeContextPreflightValidator RUNTIME_CONTEXT_PREFLIGHT_VALIDATOR =
            new RuntimeContextPreflightValidator();
    private static final QaRepositoryProfileDetector QA_PROFILE_DETECTOR = new QaRepositoryProfileDetector();
    // QA parity with the Claude executor: the same skill document ships in the
    // skill module and is materialized into the read-only input mount for Pi.
    private static final String QA_SKILL_RESOURCE = "skills/qa-playwright-cli/SKILL.md";
    // Auto-detected start commands may include a production build (npm run build && npm run start),
    // so the startup budget must cover the build, not just the listen phase.
    private static final String QA_STARTUP_TIMEOUT_SECONDS = "300";
    private static final long QA_COMMAND_TIMEOUT_MILLIS =
            parseQaCommandTimeoutMillis(System.getenv("RD_QA_EXECUTION_TIMEOUT_MILLIS"));
    private static final int WORKSPACE_LOCK_STRIPES = 128;
    private static final ReentrantLock[] WORKSPACE_LOCKS = workspaceLocks();

    private final RepairWorkspaceFactory workspaceFactory;
    private final ContainerRunnerPort containerRunner;
    private final StructuredResultValidator resultValidator;
    private final Configuration configuration;
    private final RepairWorkspaceRepositoryPort workspaceRepository;
    private final ExecutionAllowlistPolicy executionAllowlistPolicy;
    private final PiResourceManifestMaterializerPort resourceMaterializer;
    private final PiSkillMaterializerPort skillMaterializer;
    private final AgentExecutionEventSink eventSink;
    private final AgentPrivateArtifactPublisher privateArtifactPublisher;
    private final com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor.AuthEnvironmentResolver authEnvironmentResolver;
    private final PiCredentialLeaseIssuer credentialLeaseIssuer;

    public DockerPiAgentExecutor(
            RepairWorkspaceFactory workspaceFactory,
            ContainerRunnerPort containerRunner,
            StructuredResultValidator resultValidator,
            Configuration configuration
    ) {
        this(
                workspaceFactory,
                containerRunner,
                resultValidator,
                configuration,
                RepairWorkspaceRepositoryPort.noop(),
                ExecutionAllowlistPolicy.disabled(),
                PiResourceManifestMaterializerPort.emptyOnly(),
                PiSkillMaterializerPort.emptyOnly(),
                AgentExecutionEventSink.noop(),
                AgentPrivateArtifactPublisher.noop(),
                com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor.AuthEnvironmentResolver.system(),
                null
        );
    }

    public DockerPiAgentExecutor(
            RepairWorkspaceFactory workspaceFactory,
            ContainerRunnerPort containerRunner,
            StructuredResultValidator resultValidator,
            Configuration configuration,
            RepairWorkspaceRepositoryPort workspaceRepository,
            ExecutionAllowlistPolicy executionAllowlistPolicy,
            PiResourceManifestMaterializerPort resourceMaterializer,
            AgentExecutionEventSink eventSink,
            com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor.AuthEnvironmentResolver authEnvironmentResolver
    ) {
        this(
                workspaceFactory,
                containerRunner,
                resultValidator,
                configuration,
                workspaceRepository,
                executionAllowlistPolicy,
                resourceMaterializer,
                PiSkillMaterializerPort.emptyOnly(),
                eventSink,
                AgentPrivateArtifactPublisher.noop(),
                authEnvironmentResolver,
                null
        );
    }

    public DockerPiAgentExecutor(
            RepairWorkspaceFactory workspaceFactory,
            ContainerRunnerPort containerRunner,
            StructuredResultValidator resultValidator,
            Configuration configuration,
            RepairWorkspaceRepositoryPort workspaceRepository,
            ExecutionAllowlistPolicy executionAllowlistPolicy,
            PiResourceManifestMaterializerPort resourceMaterializer,
            AgentExecutionEventSink eventSink,
            AgentPrivateArtifactPublisher privateArtifactPublisher,
            com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor.AuthEnvironmentResolver authEnvironmentResolver
    ) {
        this(
                workspaceFactory,
                containerRunner,
                resultValidator,
                configuration,
                workspaceRepository,
                executionAllowlistPolicy,
                resourceMaterializer,
                PiSkillMaterializerPort.emptyOnly(),
                eventSink,
                privateArtifactPublisher,
                authEnvironmentResolver,
                null
        );
    }

    public DockerPiAgentExecutor(
            RepairWorkspaceFactory workspaceFactory,
            ContainerRunnerPort containerRunner,
            StructuredResultValidator resultValidator,
            Configuration configuration,
            RepairWorkspaceRepositoryPort workspaceRepository,
            ExecutionAllowlistPolicy executionAllowlistPolicy,
            PiResourceManifestMaterializerPort resourceMaterializer,
            PiSkillMaterializerPort skillMaterializer,
            AgentExecutionEventSink eventSink,
            AgentPrivateArtifactPublisher privateArtifactPublisher,
            com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor.AuthEnvironmentResolver authEnvironmentResolver
    ) {
        this(
                workspaceFactory,
                containerRunner,
                resultValidator,
                configuration,
                workspaceRepository,
                executionAllowlistPolicy,
                resourceMaterializer,
                skillMaterializer,
                eventSink,
                privateArtifactPublisher,
                authEnvironmentResolver,
                null
        );
    }

    public DockerPiAgentExecutor(
            RepairWorkspaceFactory workspaceFactory,
            ContainerRunnerPort containerRunner,
            StructuredResultValidator resultValidator,
            Configuration configuration,
            RepairWorkspaceRepositoryPort workspaceRepository,
            ExecutionAllowlistPolicy executionAllowlistPolicy,
            PiResourceManifestMaterializerPort resourceMaterializer,
            PiSkillMaterializerPort skillMaterializer,
            AgentExecutionEventSink eventSink,
            AgentPrivateArtifactPublisher privateArtifactPublisher,
            com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor.AuthEnvironmentResolver authEnvironmentResolver,
            PiCredentialLeaseIssuer credentialLeaseIssuer
    ) {
        this.workspaceFactory = require(workspaceFactory, "workspaceFactory");
        this.containerRunner = require(containerRunner, "containerRunner");
        this.resultValidator = resultValidator == null ? new StructuredResultValidator() : resultValidator;
        this.configuration = configuration == null ? Configuration.defaultConfiguration() : configuration;
        this.workspaceRepository = workspaceRepository == null
                ? RepairWorkspaceRepositoryPort.noop()
                : workspaceRepository;
        this.executionAllowlistPolicy = executionAllowlistPolicy == null
                ? ExecutionAllowlistPolicy.disabled()
                : executionAllowlistPolicy;
        this.resourceMaterializer = resourceMaterializer == null
                ? PiResourceManifestMaterializerPort.emptyOnly()
                : resourceMaterializer;
        this.skillMaterializer = skillMaterializer == null
                ? PiSkillMaterializerPort.emptyOnly()
                : skillMaterializer;
        this.eventSink = eventSink == null ? AgentExecutionEventSink.noop() : eventSink;
        this.privateArtifactPublisher = privateArtifactPublisher == null
                ? AgentPrivateArtifactPublisher.noop()
                : privateArtifactPublisher;
        this.authEnvironmentResolver = authEnvironmentResolver == null
                ? com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor.AuthEnvironmentResolver.system()
                : authEnvironmentResolver;
        this.credentialLeaseIssuer = credentialLeaseIssuer;
    }

    @Override
    public RepairExecutionResult execute(AgentRuntimeExecutionRequest executionRequest) {
        if (executionRequest == null) {
            throw new IllegalArgumentException("executionRequest must not be null");
        }
        AgentExecutionProfileSnapshot snapshot = executionRequest.snapshot();
        RepairJobCommand command = executionRequest.command();
        if (!snapshot.hasValidIntegrityHash()) {
            return failed(
                    RepairExecutionStatus.FAILED_VALIDATION,
                    "Pi execution snapshot integrity validation failed.",
                    "PI_SNAPSHOT",
                    "execution profile snapshot hash does not match",
                    Map.of("executionProfileSnapshotId", snapshot.snapshotId())
            );
        }
        if (snapshot.runtimeType() != AgentRuntimeType.PI
                || !SUPPORTED_ROLES.contains(snapshot.role())
                || !snapshot.role().equals(agentRole(command))) {
            return failed(
                    RepairExecutionStatus.FAILED_VALIDATION,
                    "Pi runtime role validation failed.",
                    "PI_ROLE_NOT_ALLOWED",
                    "Pi runtime supports REQUIREMENT_REVIEWER, SOLUTION_ARCHITECT, CODING_AGENT, QA_AGENT; snapshot role must match command role",
                    Map.of("executionProfileSnapshotId", snapshot.snapshotId())
            );
        }
        ExecutionAllowlistPolicy.Decision securityDecision = executionAllowlistPolicy.evaluate(command);
        if (!securityDecision.allowed()) {
            return new RepairExecutionResult(
                    RepairExecutionStatus.UNSAFE,
                    "Pi execution rejected by security policy.",
                    "",
                    List.of(),
                    Map.of("failureCategory", "PI_SECURITY_POLICY"),
                    Map.of("securityPolicyRejected", "true"),
                    Map.of(),
                    Map.of(),
                    Map.of("reason", SecretRedactor.redactFreeform(securityDecision.reason())),
                    SecretRedactor.redactFreeform(securityDecision.reason())
            );
        }
        if (!(containerRunner instanceof StreamingContainerRunnerPort streamingRunner)) {
            return failed(
                    RepairExecutionStatus.FAILED_VALIDATION,
                    "Pi execution requires the L2 streaming container runner.",
                    "PI_CONTAINER_STREAMING_UNAVAILABLE",
                    "container runner does not implement StreamingContainerRunnerPort",
                    Map.of("executionProfileSnapshotId", snapshot.snapshotId())
            );
        }

        RepairWorkspace workspace = null;
        EventCapture eventCapture = null;
        Map<String, String> repositoryMetadata = new LinkedHashMap<>();
        try {
            JsonNode snapshotJson = parseSnapshot(snapshot);
            ProviderSpec provider = provider(snapshotJson);
            ToolPolicySpec toolPolicy = toolPolicy(snapshotJson);
            Path workspaceRoot = workspaceFactory.prepareWorkspaceRoot(command);
            try (WorkspaceExecutionLease ignored = acquireWorkspaceExecutionLease(workspaceRoot)) {
                workspace = workspaceFactory.create(command);
                cleanOutputDirectory(workspace.outputDirectory());
                repositoryMetadata.putAll(workspaceRepository.prepare(command, workspace).metadataJson());
                materializeResources(snapshot, workspace.inputDirectory());
                materializeSkills(snapshot.role(), workspace.inputDirectory());
                QaProvision qaProvision = provisionQaInputs(command, snapshot, workspace);
                String inputManifestJson = text(command.contextJson().get("inputManifestJson"));
                PiRequestV2Materializer.materializeInputManifest(workspace.inputDirectory(), inputManifestJson);
                Path requestPath = workspace.inputDirectory().resolve("request.json").normalize();
                writeRequest(
                        requestPath,
                        snapshot,
                        command,
                        snapshotJson,
                        provider,
                        toolPolicy,
                        workspace.repoDirectory()
                );

                Map<String, String> environment = runtimeEnvironment(snapshot, provider, qaProvision);
                ContainerRunRequest containerRequest = containerRequest(command, snapshot, workspace, environment);
                eventCapture = new EventCapture(snapshot, containerRequest.containerName(), eventSink);
                ContainerRunResult runResult = streamingRunner.run(containerRequest, eventCapture.listener());
            if (runResult == null) {
                return failed(
                        RepairExecutionStatus.FAILED,
                        "Pi container runner returned no result.",
                        "PI_CONTAINER",
                        "container runner returned null result",
                        baseMetadata(snapshot, provider, null)
                );
            }
            eventCapture.finish();
            List<RepairArtifact> artifacts = collectArtifacts(workspace.outputDirectory());
            try {
                List<RepairArtifact> privateArtifacts = privateArtifactPublisher.publish(
                        command,
                        snapshot,
                        workspace
                );
                if (privateArtifacts != null && !privateArtifacts.isEmpty()) {
                    artifacts = java.util.stream.Stream.concat(
                                    artifacts.stream(),
                                    privateArtifacts.stream()
                            )
                            .toList();
                }
            } catch (IOException | RuntimeException exception) {
                return failed(
                        RepairExecutionStatus.FAILED,
                        "Pi private artifact archive failed.",
                        "PI_ARTIFACT_ARCHIVE",
                        safeError(exception),
                        baseMetadata(snapshot, provider, workspace),
                        artifacts
                );
            }
            Map<String, String> dockerMetadata = dockerMetadata(
                    containerRequest,
                    runResult,
                    snapshot,
                    provider,
                    eventCapture,
                    artifacts
            );
            RepairExecutionResult preflightFailure = validateRuntimeContextPreflight(
                    command,
                    snapshot,
                    workspace.outputDirectory(),
                    dockerMetadata,
                    artifacts
            );
            if (preflightFailure != null) {
                return withRepositoryMetadata(preflightFailure, repositoryMetadata);
            }
            RepairExecutionResult result = validateResult(
                    command,
                    snapshot,
                    runResult,
                    artifacts,
                    dockerMetadata,
                    eventCapture,
                    workspace.outputDirectory()
            );
            result = withRepositoryMetadata(result, repositoryMetadata);
            if (result.status() == RepairExecutionStatus.SUCCESS && repositoryPublishRequired(command)) {
                try {
                    result = withRepositoryMetadata(
                            result,
                            workspaceRepository.publish(command, workspace).metadataJson()
                    );
                } catch (IOException exception) {
                    return new RepairExecutionResult(
                            RepairExecutionStatus.FAILED,
                            "Pi repository publish failed.",
                            result.pullRequestUrl(),
                            result.artifacts(),
                            result.rawResultJson(),
                            result.dockerMetadataJson(),
                            result.githubMetadataJson(),
                            result.testMetadataJson(),
                            result.riskMetadataJson(),
                            safeError(exception)
                    );
                }
            }
                return result;
            }
        } catch (PiConfigurationException exception) {
            return failed(
                    RepairExecutionStatus.FAILED_VALIDATION,
                    "Pi execution configuration validation failed.",
                    exception.category,
                    exception.getMessage(),
                    baseMetadata(snapshot, null, workspace)
            );
        } catch (IOException | RuntimeException exception) {
            if (eventCapture != null && eventCapture.protocolFailure != null) {
                return failed(
                        RepairExecutionStatus.FAILED_VALIDATION,
                        "Pi emitted an invalid normalized event stream.",
                        "PI_EVENT_PROTOCOL",
                        safeError(eventCapture.protocolFailure),
                        baseMetadata(snapshot, null, workspace)
                );
            }
            String category = exception instanceof IOException ? "PI_CONTAINER" : "PI_RUNTIME";
            return failed(
                    RepairExecutionStatus.FAILED,
                    "Pi execution failed before a valid result was produced.",
                    category,
                    safeError(exception),
                    baseMetadata(snapshot, null, workspace)
            );
        }
    }

    private JsonNode parseSnapshot(AgentExecutionProfileSnapshot snapshot) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(snapshot.snapshotJson());
            if (root == null || !root.isObject()) {
                throw new PiConfigurationException("PI_SNAPSHOT", "snapshotJson must be a JSON object");
            }
            return root;
        } catch (JsonProcessingException exception) {
            throw new PiConfigurationException("PI_SNAPSHOT", "snapshotJson is not valid JSON", exception);
        }
    }

    private ProviderSpec provider(JsonNode snapshot) {
        String providerId = required(snapshot, "providerProfileId");
        String modelOverride = text(snapshot.path("modelOverride"));
        String model = modelOverride.isBlank() ? required(snapshot, "providerModelId") : modelOverride;
        String baseUrl = required(snapshot, "providerBaseUrl");
        String protocol = required(snapshot, "providerProtocol");
        String credentialEnv = required(snapshot, "credentialEnvironmentVariable").toUpperCase(Locale.ROOT);
        if (!ENV_NAME.matcher(credentialEnv).matches()) {
            throw new PiConfigurationException(
                    "PI_PROVIDER_CONFIGURATION",
                    "credentialEnvironmentVariable must be an env name"
            );
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new PiConfigurationException("PI_PROVIDER_CONFIGURATION", "providerBaseUrl must be HTTP(S)");
        }
        ModelProviderProtocol providerProtocol;
        try {
            providerProtocol = ModelProviderProtocol.parse(protocol);
        } catch (IllegalArgumentException exception) {
            throw new PiConfigurationException("PI_PROVIDER_CONFIGURATION", exception.getMessage(), exception);
        }
        String api = piApi(providerProtocol);
        boolean authHeader = snapshot.path("providerAuthHeader").asBoolean(false);
        return new ProviderSpec(providerId, model, baseUrl, api, credentialEnv, providerProtocol.name(), authHeader);
    }

    private ToolPolicySpec toolPolicy(JsonNode snapshot) {
        JsonNode policy = snapshot.path("toolPolicy");
        if (!policy.isObject() || !policy.path("enabled").asBoolean(false)) {
            throw new PiConfigurationException("PI_TOOL_POLICY", "frozen Pi tool policy is disabled or missing");
        }
        Set<String> hostAllow = stringSet(policy.path("hostAllow"));
        Set<String> allow = stringSet(policy.path("allow"));
        Set<String> deny = stringSet(policy.path("deny"));
        if (hostAllow.isEmpty() || !hostAllow.containsAll(allow)) {
            throw new PiConfigurationException("PI_TOOL_POLICY", "Pi tool policy exceeds the host allowlist");
        }
        List<String> effective = allow.stream()
                .filter(hostAllow::contains)
                .filter(tool -> !deny.contains(tool))
                .sorted()
                .toList();
        if (!effective.contains(RESULT_TOOL)) {
            throw new PiConfigurationException("PI_TOOL_POLICY", "Pi tool policy must allow rd_submit_result");
        }
        return new ToolPolicySpec(hostAllow, allow, deny, effective);
    }

    private void materializeSkills(String role, Path inputDirectory) throws IOException {
        try {
            skillMaterializer.materialize(role, inputDirectory);
        } catch (IOException exception) {
            throw new PiConfigurationException(
                    "PI_SKILL_CONFIGURATION",
                    "Pi skill materialization failed: " + safeError(exception),
                    exception
            );
        }
        Path manifest = inputDirectory.resolve("skill-manifest.json").normalize();
        Path root = inputDirectory.toAbsolutePath().normalize();
        if (!manifest.startsWith(root) || Files.isSymbolicLink(manifest) || !Files.isRegularFile(manifest)) {
            throw new PiConfigurationException(
                    "PI_SKILL_CONFIGURATION",
                    "Pi skill materializer did not produce a regular skill manifest"
            );
        }
    }

    private void materializeResources(AgentExecutionProfileSnapshot snapshot, Path inputDirectory) throws IOException {
        try {
            resourceMaterializer.materialize(snapshot, inputDirectory);
        } catch (IOException exception) {
            throw new PiConfigurationException(
                    "PI_RESOURCE_CONFIGURATION",
                    "Pi resource materialization failed: " + safeError(exception),
                    exception
            );
        }
        Path manifest = inputDirectory.resolve("resource-manifest.json").normalize();
        Path root = inputDirectory.toAbsolutePath().normalize();
        if (!manifest.startsWith(root) || Files.isSymbolicLink(manifest) || !Files.isRegularFile(manifest)) {
            throw new PiConfigurationException(
                    "PI_RESOURCE_CONFIGURATION",
                    "Pi resource materializer did not produce a regular resource manifest"
            );
        }
        try {
            JsonNode value = OBJECT_MAPPER.readTree(Files.readString(manifest, StandardCharsets.UTF_8));
            if (value == null
                    || !"rd-agent-resource-manifest/v1".equals(value.path("protocol").asText())
                    || !"VERIFIED".equals(value.path("verificationStatus").asText())
                    || !value.path("resources").isArray()) {
                throw new PiConfigurationException(
                        "PI_RESOURCE_CONFIGURATION",
                        "Pi resource manifest is not verified"
                );
            }
            String selectedSet = text(parseSnapshot(snapshot).path("extensionSetId"));
            String manifestSet = text(value.path("extensionSetId"));
            if (!selectedSet.isBlank() && !selectedSet.equals(manifestSet)) {
                throw new PiConfigurationException(
                        "PI_RESOURCE_CONFIGURATION",
                        "Pi resource manifest does not match the frozen extension set"
                );
            }
        } catch (JsonProcessingException exception) {
            throw new PiConfigurationException(
                    "PI_RESOURCE_CONFIGURATION",
                    "Pi resource manifest is not valid JSON",
                    exception
            );
        }
    }

    private void writeRequest(
            Path requestPath,
            AgentExecutionProfileSnapshot snapshot,
            RepairJobCommand command,
            JsonNode snapshotJson,
            ProviderSpec provider,
            ToolPolicySpec toolPolicy,
            Path repoDirectory
    ) throws IOException {
        boolean v2 = PiRequestV2Materializer.isV2(configuration.requestProtocolVersion());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("protocol", v2 ? "rd-pi-request/v2" : "rd-pi-request/v1");
        request.put("snapshotId", snapshot.snapshotId());
        request.put("stageRunId", snapshot.stageRunId());
        request.put("taskId", snapshot.taskId());
        request.put("role", snapshot.role());
        request.put("prompt", command.prompt());
        request.put("provider", provider.providerId());
        request.put("model", provider.model());
        request.put("api", provider.api());
        request.put("baseUrl", provider.baseUrl());
        request.put("authHeader", provider.authHeader());
        request.put("credentialEnvironmentVariable", provider.credentialEnvironmentVariable());
        request.put("repoPath", CONTAINER_REPO);
        request.put("inputPath", "/work/input");
        request.put("outputPath", "/work/output");
        request.put("applyCandidatePatch", Boolean.parseBoolean(
                command.policyJson().getOrDefault("applyCandidatePatch", "false")
        ));
        request.put("dynamicStateEnabled", snapshotJson.path("dynamicStateEnabled").asBoolean(false));
        request.put("maxInjectedStateBytes", positiveInt(snapshotJson.path("maxInjectedStateBytes"), 8192));
        request.put("contextProtocolVersion", text(snapshotJson.path("contextProtocolVersion")));
        request.put("agentStateSchemaVersion", text(snapshotJson.path("agentStateSchemaVersion")));
        request.put("toolRetryPolicyVersion", text(snapshotJson.path("toolRetryPolicyVersion")));
        request.put("attemptNo", snapshot.attemptNo());
        request.put("resourceManifestPath", "/work/input/resource-manifest.json");
        request.put("skillManifestPath", "/work/input/skill-manifest.json");
        String inputManifestHash = text(command.contextJson().get("inputManifestHash"));
        String contextPolicyJson = text(command.contextJson().get("contextPolicyJson"));
        if (v2) {
            if (inputManifestHash.isBlank()) {
                throw new PiConfigurationException(
                        "PI_REQUEST_V2",
                        "v2 request requires non-blank inputManifestHash"
                );
            }
            if (contextPolicyJson.isBlank()) {
                throw new PiConfigurationException(
                        "PI_REQUEST_V2",
                        "v2 request requires non-blank contextPolicyJson with protocol, policyHash, mode, expectedFiles"
                );
            }
            request.put("inputManifestHash", inputManifestHash);
            request.put("inputManifestPath", PiRequestV2Materializer.CONTAINER_INPUT_MANIFEST_PATH);
            request.put(
                    "contextPolicy",
                    enrichContextPolicyHashes(
                            PiRequestV2Materializer.parseContextPolicy(contextPolicyJson),
                            repoDirectory
                    )
            );
        } else {
            if (!inputManifestHash.isBlank()) {
                request.put("inputManifestHash", inputManifestHash);
            }
            String contextPolicyHash = text(command.contextJson().get("contextPolicyHash"));
            if (!contextPolicyHash.isBlank()) {
                request.put("contextPolicy", Map.of("policyHash", contextPolicyHash));
            }
        }
        request.put("toolPolicy", Map.of(
                "hostAllow", toolPolicy.hostAllow(),
                "allow", toolPolicy.allow(),
                "deny", toolPolicy.deny(),
                "effectiveAllow", toolPolicy.effectiveAllow()
        ));
        request.put("metadata", Map.of(
                "snapshotHash", snapshot.snapshotHash(),
                "profileId", text(snapshotJson.path("profileId")),
                "profileVersion", snapshotJson.path("profileVersion").asLong(0L),
                "extensionSetId", text(snapshotJson.path("extensionSetId")),
                "toolPolicyId", text(snapshotJson.path("toolPolicyId")),
                "toolPolicyVersion", snapshotJson.path("toolPolicyVersion").asLong(0L)
        ));
        Files.writeString(requestPath, OBJECT_MAPPER.writeValueAsString(request) + "\n", StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> enrichContextPolicyHashes(
            Map<String, Object> contextPolicy,
            Path repoDirectory
    ) throws IOException {
        if (contextPolicy == null || repoDirectory == null || !Files.isDirectory(repoDirectory)) {
            return contextPolicy;
        }
        Object expectedRaw = contextPolicy.get("expectedFiles");
        if (!(expectedRaw instanceof List<?> expectedList) || expectedList.isEmpty()) {
            return contextPolicy;
        }
        List<Map<String, Object>> enriched = new java.util.ArrayList<>();
        for (Object entry : expectedList) {
            if (!(entry instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> file = new LinkedHashMap<>();
            for (Map.Entry<?, ?> item : raw.entrySet()) {
                file.put(String.valueOf(item.getKey()), item.getValue());
            }
            String path = String.valueOf(file.getOrDefault("path", ""));
            path = path == null || "null".equals(path) ? "" : path.strip();
            String hash = String.valueOf(file.getOrDefault("contentHash", ""));
            hash = hash == null || "null".equals(hash) ? "" : hash.strip();
            if (!path.isBlank() && hash.isBlank() && !path.contains("..") && !path.startsWith("/")) {
                Path candidate = repoDirectory.resolve(path).normalize();
                if (candidate.startsWith(repoDirectory.normalize())
                        && Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    file.put("contentHash", "sha256:" + sha256Hex(Files.readAllBytes(candidate)));
                }
            }
            enriched.add(Map.copyOf(file));
        }
        Map<String, Object> copy = new LinkedHashMap<>(contextPolicy);
        copy.put("expectedFiles", List.copyOf(enriched));
        return Map.copyOf(copy);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private RepairExecutionResult validateRuntimeContextPreflight(
            RepairJobCommand command,
            AgentExecutionProfileSnapshot snapshot,
            Path outputDirectory,
            Map<String, String> dockerMetadata,
            List<RepairArtifact> artifacts
    ) {
        if (!PiRequestV2Materializer.isV2(configuration.requestProtocolVersion())) {
            return null;
        }
        String contextPolicyJson = text(command.contextJson().get("contextPolicyJson"));
        if (contextPolicyJson.isBlank()) {
            return failed(
                    RepairExecutionStatus.FAILED_VALIDATION,
                    "Pi runtime context preflight validation failed.",
                    "PI_RUNTIME_CONTEXT_PREFLIGHT",
                    "contextPolicyJson is missing for v2 request",
                    dockerMetadata,
                    artifacts
            );
        }
        Path manifestPath = outputDirectory.resolve("runtime-context-manifest.json").normalize();
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            return failed(
                    RepairExecutionStatus.FAILED_VALIDATION,
                    "Pi runtime context preflight validation failed.",
                    "PI_RUNTIME_CONTEXT_PREFLIGHT",
                    "runtime-context-manifest.json is missing",
                    dockerMetadata,
                    artifacts
            );
        }
        try {
            String manifestJson = Files.readString(manifestPath, StandardCharsets.UTF_8);
            RuntimeContextPolicy policy = PiRequestV2Materializer.parseRuntimeContextPolicy(contextPolicyJson);
            RuntimeContextManifest manifest = PiRequestV2Materializer.parseRuntimeContextManifest(manifestJson);
            RuntimeContextPreflightValidator.ExpectedIdentity identity =
                    new RuntimeContextPreflightValidator.ExpectedIdentity(
                            text(command.contextJson().get("taskId")),
                            text(command.contextJson().get("stageRunId")),
                            text(command.contextJson().get("agentRole")),
                            snapshot == null ? 0 : snapshot.attemptNo(),
                            snapshot == null ? "" : snapshot.snapshotId(),
                            text(command.contextJson().get("inputManifestHash")),
                            text(command.contextJson().get("contextPolicyHash"))
                    );
            RuntimeContextPreflightValidator.ValidationResult validation =
                    RUNTIME_CONTEXT_PREFLIGHT_VALIDATOR.validate(policy, manifest, identity);
            if (validation.accepted()) {
                return null;
            }
            return failed(
                    RepairExecutionStatus.FAILED_VALIDATION,
                    "Pi runtime context preflight validation failed.",
                    "PI_RUNTIME_CONTEXT_PREFLIGHT",
                    String.join("; ", validation.violations()),
                    dockerMetadata,
                    artifacts
            );
        } catch (IOException | IllegalArgumentException exception) {
            return failed(
                    RepairExecutionStatus.FAILED_VALIDATION,
                    "Pi runtime context preflight validation failed.",
                    "PI_RUNTIME_CONTEXT_PREFLIGHT",
                    safeError(exception),
                    dockerMetadata,
                    artifacts
            );
        }
    }

    /**
     * Mirrors the Claude executor's QA provisioning: qa-profile.json plus the
     * qa-playwright-cli skill document land in the read-only input mount so the
     * QA agent follows the shared evidence directory contract.
     */
    private QaProvision provisionQaInputs(
            RepairJobCommand command,
            AgentExecutionProfileSnapshot snapshot,
            RepairWorkspace workspace
    ) throws IOException {
        if (!"QA_AGENT".equals(snapshot.role())) {
            return QaProvision.none();
        }
        QaExecutionProfile profile = QA_PROFILE_DETECTOR.detect(command, workspace.repoDirectory());
        Files.writeString(
                workspace.inputDirectory().resolve("qa-profile.json"),
                QA_PROFILE_DETECTOR.toJson(profile),
                StandardCharsets.UTF_8
        );
        Path hubSkill = workspace.inputDirectory().resolve("skills")
                .resolve("qa-playwright-cli")
                .resolve("SKILL.md")
                .normalize();
        boolean hubPresent = Files.isRegularFile(hubSkill);
        // Prefer Skill Hub materialization under skills/; keep qa-skill/ as legacy fallback.
        boolean legacyWritten = !hubPresent && writeQaSkillDocument(workspace.inputDirectory());
        return new QaProvision(profile, hubPresent, legacyWritten);
    }

    private static boolean writeQaSkillDocument(Path inputDirectory) {
        try (InputStream stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(QA_SKILL_RESOURCE)) {
            if (stream == null) {
                return false;
            }
            Path target = inputDirectory.resolve("qa-skill").resolve("SKILL.md").normalize();
            Files.createDirectories(target.getParent());
            Files.write(target, stream.readAllBytes());
            return true;
        } catch (IOException exception) {
            // Best effort: the QA prompt still carries the evidence directory contract.
            return false;
        }
    }

    private static long parseQaCommandTimeoutMillis(String rawValue) {
        try {
            long parsed = Long.parseLong(rawValue == null ? "" : rawValue.strip());
            return Math.max(60_000L, parsed);
        } catch (NumberFormatException exception) {
            return 1_200_000L;
        }
    }

    private record QaProvision(
            QaExecutionProfile profile,
            boolean hubSkillPresent,
            boolean legacySkillDocumentWritten
    ) {
        private static QaProvision none() {
            return new QaProvision(null, false, false);
        }
    }

    private Map<String, String> runtimeEnvironment(
            AgentExecutionProfileSnapshot snapshot,
            ProviderSpec provider,
            QaProvision qaProvision
    ) {
        if (configuration.credentialRelayEnabled()) {
            if (credentialLeaseIssuer == null) {
                throw new PiConfigurationException(
                        "PI_PROVIDER_CONFIGURATION",
                        "credential relay is enabled but no PiCredentialLeaseIssuer is wired; "
                                + "set rd.executor.pi.credential-relay-enabled=false"
                );
            }
            String credential = authEnvironmentResolver.resolve(provider.credentialEnvironmentVariable());
            if (credential == null || credential.isBlank()) {
                throw new PiConfigurationException(
                        "PI_PROVIDER_CONFIGURATION",
                        "provider credential environment variable is missing: "
                                + provider.credentialEnvironmentVariable()
                );
            }
            if (configuration.credentialRelayUrl().isBlank()) {
                throw new PiConfigurationException(
                        "PI_PROVIDER_CONFIGURATION",
                        "credential relay is enabled but no relay URL is configured"
                );
            }
            PiCredentialLeaseIssuer.PiCredentialLease lease = credentialLeaseIssuer.issue(
                    snapshot.taskId(),
                    snapshot.stageRunId(),
                    provider.providerId(),
                    credential,
                    Duration.ofMinutes(45),
                    200
            );
            Map<String, String> environment = new LinkedHashMap<>();
            environment.put("RD_PI_CREDENTIAL_RELAY_ENABLED", "true");
            environment.put("RD_PI_CREDENTIAL_RELAY_URL", configuration.credentialRelayUrl());
            environment.put("RD_PI_CREDENTIAL_RELAY_TASK_ID", snapshot.taskId());
            environment.put("RD_PI_CREDENTIAL_RELAY_STAGE_RUN_ID", snapshot.stageRunId());
            environment.put("RD_PI_CREDENTIAL_RELAY_PROVIDER_ID", provider.providerId());
            environment.put("RD_PI_CREDENTIAL_LEASE", lease.token());
            environment.put("RD_PI_CREDENTIAL_LEASE_EXPIRES_AT", lease.expiresAt().toString());
            environment.put("RD_PI_CREDENTIAL_LEASE_MAX_CALLS", String.valueOf(lease.maxCalls()));
            // Raw provider secret stays on the host; container only receives the opaque lease.
            populateSharedRuntimeEnvironment(environment, snapshot, qaProvision);
            return environment;
        }
        String credential = authEnvironmentResolver.resolve(provider.credentialEnvironmentVariable());
        if (credential == null || credential.isBlank()) {
            throw new PiConfigurationException(
                    "PI_PROVIDER_CONFIGURATION",
                    "provider credential environment variable is missing: " + provider.credentialEnvironmentVariable()
            );
        }
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(provider.credentialEnvironmentVariable(), credential);
        populateSharedRuntimeEnvironment(environment, snapshot, qaProvision);
        return environment;
    }

    private void populateSharedRuntimeEnvironment(
            Map<String, String> environment,
            AgentExecutionProfileSnapshot snapshot,
            QaProvision qaProvision
    ) {
        environment.put("RD_AGENT_ROLE", snapshot.role());
        environment.put("RD_AGENT_RUNTIME", "PI");
        environment.put("RD_AGENT_SNAPSHOT_ID", snapshot.snapshotId());
        environment.put("RD_AGENT_STAGE_RUN_ID", snapshot.stageRunId());
        // The cache mount is persistent across retries. Without these variables
        // package managers silently use container-local caches and redownload.
        environment.put("npm_config_cache", CONTAINER_CACHE + "/npm");
        environment.put("PIP_CACHE_DIR", CONTAINER_CACHE + "/pip");
        environment.put("YARN_CACHE_FOLDER", CONTAINER_CACHE + "/yarn");
        environment.put("RD_PI_MAX_RAW_EVENT_BYTES", String.valueOf(configuration.rawEventMaxBytes()));
        environment.put("RD_PI_BASH_COMMAND_TIMEOUT_MILLIS",
                String.valueOf(configuration.bashCommandTimeoutMillis()));
        QaExecutionProfile qaProfile = qaProvision == null ? null : qaProvision.profile();
        if (qaProfile != null) {
            environment.put("RD_QA_PROFILE_FILE", "/work/input/qa-profile.json");
            environment.put("RD_QA_BASE_URL", qaProfile.baseUrl());
            environment.put("RD_QA_START_COMMAND", qaProfile.startCommand());
            environment.put("RD_QA_HEALTH_PATH", qaProfile.healthPath());
            environment.put("RD_QA_ALLOWED_HOSTS", String.join(",", qaProfile.allowedHosts()));
            environment.put("RD_QA_REGRESSION_COMMANDS_JSON", jsonArrayText(qaProfile.regressionCommands()));
            environment.put("RD_QA_DECISION_SOURCE", qaProfile.decisionSource());
            environment.put("RD_QA_PROFILE_AMBIGUOUS", Boolean.toString(qaProfile.ambiguous()));
            environment.put("RD_QA_STARTUP_TIMEOUT_SECONDS", QA_STARTUP_TIMEOUT_SECONDS);
            environment.put("RD_QA_COMMAND_TIMEOUT_MILLIS", String.valueOf(QA_COMMAND_TIMEOUT_MILLIS));
            environment.put("PLAYWRIGHT_MCP_OUTPUT_DIR", "/work/output/qa-work/playwright");
            if (qaProvision.hubSkillPresent()) {
                environment.put("RD_QA_SKILL_FILE", "/work/input/skills/qa-playwright-cli/SKILL.md");
            } else if (qaProvision.legacySkillDocumentWritten()) {
                environment.put("RD_QA_SKILL_FILE", "/work/input/qa-skill/SKILL.md");
            }
        }
    }

    private static String jsonArrayText(List<String> values) {
        try {
            return OBJECT_MAPPER.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }

    private ContainerRunRequest containerRequest(
            RepairJobCommand command,
            AgentExecutionProfileSnapshot snapshot,
            RepairWorkspace workspace,
            Map<String, String> environment
    ) {
        Map<String, String> mounts = new LinkedHashMap<>();
        mounts.put(
                workspace.repoDirectory().toString(),
                READ_ONLY_REPO_ROLES.contains(snapshot.role()) ? CONTAINER_REPO_READONLY : CONTAINER_REPO
        );
        mounts.put(workspace.inputDirectory().toString(), CONTAINER_INPUT);
        mounts.put(workspace.outputDirectory().toString(), CONTAINER_OUTPUT);
        mounts.put(workspace.cacheDirectory().toString(), CONTAINER_CACHE);
        String image = "QA_AGENT".equals(snapshot.role()) && !configuration.qaImage().isBlank()
                ? configuration.qaImage()
                : configuration.image();
        boolean browserQa = "QA_AGENT".equals(snapshot.role());
        return new ContainerRunRequest(
                safeContainerName(command.taskId()),
                image,
                configuration.command(),
                environment,
                mounts,
                CONTAINER_REPO,
                resolveNetworkMode(snapshot.role()),
                configuration.removeAfterExit(),
                false,
                workspace.outputDirectory(),
                browserQa,
                browserQa ? "1g" : "",
                configuration.executionTimeoutMillis(),
                browserQa ? PI_QA_SECURITY_POLICY : PI_SECURITY_POLICY
        );
    }

    private String resolveNetworkMode(String role) {
        if (NETWORK_NONE_ROLES.contains(role == null ? "" : role)) {
            return "none";
        }
        if (configuration.credentialRelayEnabled()) {
            // Fail closed on arbitrary egress while host relay holds provider secrets.
            // A future controlled relay network can replace this once sidecar + allowlist land.
            return "none";
        }
        return configuration.networkMode();
    }

    private static ContainerSecurityPolicy piSecurityPolicy(int pidsLimit) {
        return new ContainerSecurityPolicy(
                true,
                true,
                true,
                true,
                "8g",
                "4",
                pidsLimit,
                "1000:1000",
                PI_TMPFS_MOUNTS
        );
    }

    private RepairExecutionResult validateResult(
            RepairJobCommand command,
            AgentExecutionProfileSnapshot snapshot,
            ContainerRunResult runResult,
            List<RepairArtifact> artifacts,
            Map<String, String> dockerMetadata,
            EventCapture events,
            Path outputDirectory
    ) throws IOException {
        if (!events.resultSubmitted || !events.agentSettled) {
            return failed(
                    RepairExecutionStatus.FAILED_VALIDATION,
                    "Pi did not complete the required result lifecycle.",
                    "PI_RESULT_PROTOCOL",
                    "Pi result requires RESULT_SUBMITTED followed by AGENT_SETTLED",
                    dockerMetadata,
                    artifacts
            );
        }
        if (runResult.exitCode() != 0) {
            return failed(
                    RepairExecutionStatus.FAILED,
                    "Pi container exited before completing the coding attempt.",
                    "PI_CONTAINER",
                    containerFailureMessage(runResult, events.stderr()),
                    dockerMetadata,
                    artifacts
            );
        }
        Path resultPath = outputDirectory.resolve("result.json").normalize();
        if (!resultPath.startsWith(outputDirectory.toAbsolutePath().normalize())
                || !Files.isRegularFile(resultPath, LinkOption.NOFOLLOW_LINKS)) {
            return failed(
                    RepairExecutionStatus.FAILED_VALIDATION,
                    "Pi result.json is missing.",
                    "PI_RESULT_PROTOCOL",
                    "result.json is missing",
                    dockerMetadata,
                    artifacts
            );
        }
        String rawJson = Files.readString(resultPath, StandardCharsets.UTF_8);
        String role = snapshot.role();
        JsonNode snapshotJson = parseSnapshot(snapshot);
        String contextProtocolVersion = frozenContextProtocolVersion(snapshotJson);
        FactFreshnessEvaluator.FreshnessContext freshnessContext = freshnessContext(command, snapshotJson);
        // Non-coding roles submit a role protocol JSON, not the coding structured result;
        // validate it with the role-specific validator and skip the coding contract.
        if (usesAgentRoleProtocol(role)) {
            return validateRoleProtocolResult(
                    role,
                    rawJson,
                    artifacts,
                    dockerMetadata,
                    contextProtocolVersion,
                    freshnessContext
            );
        }
        StructuredResultValidation validation = ContextProtocolVersion.FACTS_V1.name().equals(contextProtocolVersion)
                ? resultValidator.validate(rawJson, contextProtocolVersion, freshnessContext)
                : resultValidator.validate(rawJson);
        if (!validation.valid()) {
            return failed(
                    RepairExecutionStatus.FAILED_VALIDATION,
                    "Pi result.json failed Java validation.",
                    "PI_RESULT_PROTOCOL",
                    String.join("; ", validation.errors()),
                    dockerMetadata,
                    artifacts
            );
        }
        StructuredRepairResult structured = validation.result();
        RepairExecutionStatus status = toStatus(structured.status());
        if (status == RepairExecutionStatus.SUCCESS) {
            List<String> missing = missingSuccessArtifacts(role, artifacts);
            if (!missing.isEmpty()) {
                return failed(
                        RepairExecutionStatus.FAILED_VALIDATION,
                        "Pi result artifacts are incomplete.",
                        "PI_RESULT_PROTOCOL",
                        String.join("; ", missing),
                        dockerMetadata,
                        artifacts
                );
            }
        }
        String error = status == RepairExecutionStatus.FAILED
                ? structured.summary()
                : "";
        Map<String, String> raw = rawResultJson(structured, rawJson);
        return new RepairExecutionResult(
                status,
                structured.summary(),
                "",
                artifacts,
                raw,
                dockerMetadata,
                Map.of(),
                testMetadata(structured),
                riskMetadata(structured),
                error
        );
    }

    private RepairExecutionResult validateRoleProtocolResult(
            String role,
            String rawJson,
            List<RepairArtifact> artifacts,
            Map<String, String> dockerMetadata,
            String contextProtocolVersion,
            FactFreshnessEvaluator.FreshnessContext freshnessContext
    ) {
        AgentRoleResultValidation roleValidation = ContextProtocolVersion.FACTS_V1.name().equals(contextProtocolVersion)
                ? ROLE_RESULT_VALIDATOR.validate(role, rawJson, contextProtocolVersion, freshnessContext)
                : ROLE_RESULT_VALIDATOR.validate(role, rawJson);
        if (!roleValidation.valid()) {
            return failed(
                    RepairExecutionStatus.FAILED_VALIDATION,
                    "Pi role protocol validation failed.",
                    "PI_RESULT_PROTOCOL",
                    String.join("; ", roleValidation.errors()),
                    dockerMetadata,
                    artifacts
            );
        }
        List<String> missing = missingSuccessArtifacts(role, artifacts);
        if (!missing.isEmpty()) {
            return failed(
                    RepairExecutionStatus.FAILED_VALIDATION,
                    "Pi result artifacts are incomplete.",
                    "PI_RESULT_PROTOCOL",
                    String.join("; ", missing),
                    dockerMetadata,
                    artifacts
            );
        }
        String status = text(parseRawJson(rawJson).path("status"));
        RepairExecutionStatus executionStatus = toStatus(status);
        String summary = text(parseRawJson(rawJson).path("summary"));
        String errorMessage = executionStatus == RepairExecutionStatus.FAILED && "QA_AGENT".equals(role)
                ? "QA_AGENT failed acceptance: " + summary
                : "";
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("status", status);
        raw.put("summary", summary);
        raw.put(AGENT_RESULT_JSON, rawJson);
        return new RepairExecutionResult(
                executionStatus,
                summary.isBlank() ? "Pi " + role + " completed." : summary,
                "",
                artifacts,
                raw,
                dockerMetadata,
                Map.of(),
                Map.of(),
                Map.of(
                        "riskLevel", "LOW",
                        "needHumanAction", String.valueOf(executionStatus != RepairExecutionStatus.SUCCESS)
                ),
                errorMessage
        );
    }

    private JsonNode parseRawJson(String rawJson) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(rawJson);
            return root == null ? OBJECT_MAPPER.createObjectNode() : root;
        } catch (JsonProcessingException exception) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    private static boolean usesAgentRoleProtocol(String role) {
        return "REQUIREMENT_REVIEWER".equals(role)
                || "SOLUTION_ARCHITECT".equals(role)
                || "QA_AGENT".equals(role);
    }

    private static Map<String, String> rawResultJson(StructuredRepairResult result, String rawJson) {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("status", result.status());
        raw.put("summary", result.summary());
        raw.put("prBody", result.prBody());
        raw.put("changedFiles", String.join(",", result.changedFiles()));
        raw.put("testCommands", String.join(",", result.testCommands()));
        raw.put(AGENT_RESULT_JSON, rawJson == null ? "" : rawJson);
        return raw;
    }

    private static Map<String, String> testMetadata(StructuredRepairResult result) {
        return Map.of(
                "testStatus", result.testStatus(),
                "testCommands", String.join(",", result.testCommands())
        );
    }

    private static Map<String, String> riskMetadata(StructuredRepairResult result) {
        return Map.of(
                "riskLevel", result.riskLevel(),
                "needHumanAction", String.valueOf(result.needHumanAction())
        );
    }

    private static List<String> missingSuccessArtifacts(String role, List<RepairArtifact> artifacts) {
        Set<String> names = artifacts.stream().map(RepairArtifact::name).collect(java.util.stream.Collectors.toSet());
        List<String> required = switch (role == null ? "" : role) {
            case "CODING_AGENT" -> List.of("patch.diff", "test.log", "agent-events.jsonl");
            case "REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT" -> List.of("handoff/next.md", "agent-events.jsonl");
            case "QA_AGENT" -> List.of("agent-events.jsonl");
            default -> List.of("agent-events.jsonl");
        };
        return required.stream()
                .filter(name -> !names.contains(name))
                .map(name -> name + " is missing")
                .toList();
    }

    private static RepairExecutionStatus toStatus(String value) {
        return switch (value == null ? "" : value.strip().toUpperCase(Locale.ROOT)) {
            // PASSED/SKIPPED are QA role-protocol report statuses; a QA run that
            // produced either completed its work, so map both to a successful execution.
            case "SUCCESS", "PASSED", "SKIPPED" -> RepairExecutionStatus.SUCCESS;
            case "FAILED" -> RepairExecutionStatus.FAILED;
            case "NEED_INFO" -> RepairExecutionStatus.NEED_INFO;
            case "UNSAFE" -> RepairExecutionStatus.UNSAFE;
            default -> RepairExecutionStatus.FAILED_VALIDATION;
        };
    }

    private static ReentrantLock[] workspaceLocks() {
        ReentrantLock[] locks = new ReentrantLock[WORKSPACE_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private static WorkspaceExecutionLease acquireWorkspaceExecutionLease(Path workspaceRoot) throws IOException {
        if (workspaceRoot == null) {
            throw new IOException("Pi workspace root is missing");
        }
        Path root = workspaceRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Pi workspace root must be a real directory");
        }
        Path lockPath = root.resolve(".pi-execution.lock").normalize();
        if (!lockPath.startsWith(root)) {
            throw new IOException("Pi workspace lock escapes its root");
        }
        ReentrantLock localLock = WORKSPACE_LOCKS[Math.floorMod(root.toString().hashCode(), WORKSPACE_LOCK_STRIPES)];
        localLock.lock();
        try {
            FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                return new WorkspaceExecutionLease(localLock, channel, channel.lock());
            } catch (IOException | RuntimeException exception) {
                channel.close();
                throw exception;
            }
        } catch (IOException | RuntimeException exception) {
            localLock.unlock();
            throw exception;
        }
    }

    private static void cleanOutputDirectory(Path outputDirectory) throws IOException {
        if (outputDirectory == null || !Files.isDirectory(outputDirectory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(outputDirectory)) {
            for (Path path : paths
                    .filter(candidate -> !candidate.equals(outputDirectory))
                    .sorted(Comparator.reverseOrder())
                    .toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static List<RepairArtifact> collectArtifacts(Path outputDirectory) throws IOException {
        if (outputDirectory == null || !Files.isDirectory(outputDirectory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(outputDirectory)) {
            return paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !outputDirectory.relativize(path).toString().replace('\\', '/').startsWith("private/"))
                    .sorted(Comparator.comparing(path -> artifactName(outputDirectory, path)))
                    .map(path -> toArtifact(outputDirectory, path))
                    .toList();
        }
    }

    private static RepairArtifact toArtifact(Path outputDirectory, Path path) {
        String name = artifactName(outputDirectory, path);
        return new RepairArtifact(
                artifactType(name),
                name,
                path.toUri().toString(),
                "Pi execution artifact: " + name,
                artifactMetadata(path)
        );
    }

    private static String artifactName(Path outputDirectory, Path path) {
        return outputDirectory.relativize(path).toString().replace('\\', '/');
    }

    private static RepairArtifactType artifactType(String name) {
        String normalized = name == null ? "" : name.replace('\\', '/');
        return switch (normalized) {
            case "result.json" -> RepairArtifactType.RESULT_JSON;
            case "patch.diff" -> RepairArtifactType.PATCH_DIFF;
            case "test.log" -> RepairArtifactType.TEST_LOG;
            case "agent-events.jsonl" -> RepairArtifactType.AGENT_EVENTS;
            case "agent-state-events.jsonl" -> RepairArtifactType.AGENT_STATE_EVENTS;
            case "agent-state-latest.json" -> RepairArtifactType.AGENT_STATE_SNAPSHOT;
            case "runtime-context-manifest.json" -> RepairArtifactType.RUNTIME_CONTEXT_MANIFEST;
            case "runtime-meta.json" -> RepairArtifactType.AGENT_RUNTIME_META;
            case "docker-meta.json" -> RepairArtifactType.DOCKER_METADATA;
            case "handoff/next.md" -> RepairArtifactType.HANDOFF_MARKDOWN;
            case "qa-evidence/manifest.json" -> RepairArtifactType.QA_EVIDENCE_MANIFEST;
            default -> qaArtifactType(normalized);
        };
    }

    // QA evidence artifact taxonomy mirrors DockerClaudeCodeExecutor so the QA
    // evidence bundle validator can resolve evidenceManifestArtifactId and the
    // per-criterion evidenceArtifactIds regardless of the runtime.
    private static RepairArtifactType qaArtifactType(String name) {
        if (!name.startsWith("qa-evidence/")) {
            return RepairArtifactType.OTHER;
        }
        if ((name.startsWith("qa-evidence/commands/")
                || name.startsWith("qa-evidence/browser/"))
                && name.endsWith(".log")) {
            return RepairArtifactType.QA_COMMAND_LOG;
        }
        if (name.startsWith("qa-evidence/screenshots/")
                && (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg"))) {
            return RepairArtifactType.QA_SCREENSHOT;
        }
        if (name.startsWith("qa-evidence/traces/") && name.endsWith(".zip")) {
            return RepairArtifactType.QA_TRACE;
        }
        if (name.startsWith("qa-evidence/console/")) {
            return RepairArtifactType.QA_CONSOLE_LOG;
        }
        if (name.startsWith("qa-evidence/network/")) {
            return RepairArtifactType.QA_NETWORK_LOG;
        }
        if (name.startsWith("qa-evidence/http/")) {
            return RepairArtifactType.QA_HTTP_TRANSCRIPT;
        }
        if (name.startsWith("qa-evidence/video/")) {
            return RepairArtifactType.QA_VIDEO;
        }
        return RepairArtifactType.OTHER;
    }

    private static Map<String, String> artifactMetadata(Path path) {
        Map<String, String> metadata = new LinkedHashMap<>();
        try {
            long size = Files.size(path);
            metadata.put("bytes", String.valueOf(size));
            metadata.put("sha256", sha256(path));
            metadata.put("contentType", contentType(path));
            if ("agent-events.jsonl".equals(path.getFileName().toString())) {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                boolean truncated = content.length() > SAFE_EVENT_PREVIEW_CHARS;
                metadata.put(
                        "contentPreview",
                        truncated
                                ? content.substring(0, SAFE_EVENT_PREVIEW_CHARS) + "\n[truncated]"
                                : content
                );
                metadata.put("contentPreviewTruncated", String.valueOf(truncated));
            } else if (isTextArtifact(path) && size <= MAX_TEXT_PREVIEW_BYTES) {
                // QA evidence bundle validation reads the manifest (and command logs)
                // back through contentPreview for integrity checks; mirror Claude parity.
                metadata.put("contentPreview", SecretRedactor.redactFreeform(
                        Files.readString(path, StandardCharsets.UTF_8)
                ));
            }
            return Map.copyOf(metadata);
        } catch (IOException exception) {
            // Preview failures (for example a binary read) must never erase the
            // integrity metadata; QA bundle validation depends on bytes/sha256.
            return Map.copyOf(metadata);
        }
    }

    private static final long MAX_TEXT_PREVIEW_BYTES = 256 * 1024L;

    private static String contentType(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".zip")) {
            return "application/zip";
        }
        if (name.endsWith(".json")) {
            return "application/json";
        }
        if (name.endsWith(".jsonl")) {
            return "application/x-ndjson";
        }
        if (name.endsWith(".log") || name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".diff")) {
            return "text/plain";
        }
        if (name.endsWith(".webm")) {
            return "video/webm";
        }
        return "application/octet-stream";
    }

    // Content-type driven so binary evidence (screenshots, traces, video) is
    // never read as UTF-8 text; the old qa-evidence path match broke on PNGs.
    private static boolean isTextArtifact(Path path) {
        String contentType = contentType(path);
        return contentType.startsWith("text/")
                || "application/json".equals(contentType)
                || "application/x-ndjson".equals(contentType);
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Map<String, String> dockerMetadata(
            ContainerRunRequest request,
            ContainerRunResult result,
            AgentExecutionProfileSnapshot snapshot,
            ProviderSpec provider,
            EventCapture events,
            List<RepairArtifact> artifacts
    ) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("runtime", "PI");
        metadata.put("executionProfileSnapshotId", snapshot.snapshotId());
        metadata.put("executionProfileSnapshotHash", snapshot.snapshotHash());
        metadata.put("stageRunId", snapshot.stageRunId());
        metadata.put("runtimeType", snapshot.runtimeType().name());
        metadata.put("provider", provider == null ? "" : provider.providerId());
        metadata.put("providerProtocol", provider == null ? "" : provider.protocol());
        metadata.put("model", provider == null ? "" : provider.model());
        metadata.put("image", request.image());
        metadata.put("containerName", request.containerName());
        metadata.put("commandJson", jsonArray(request.command()));
        metadata.put("networkMode", request.networkMode());
        metadata.put("removeAfterExit", String.valueOf(request.removeAfterExit()));
        metadata.put("allowPrivileged", String.valueOf(request.allowPrivileged()));
        metadata.put("exitCode", String.valueOf(result.exitCode()));
        metadata.put("durationMillis", String.valueOf(result.durationMillis()));
        metadata.put("agentSettled", String.valueOf(events.agentSettled));
        metadata.put("resultSubmitted", String.valueOf(events.resultSubmitted));
        metadata.put("lastEventSourceSequence", String.valueOf(events.decoder.lastSourceSequence()));
        metadata.put("outputArtifactPaths", artifacts.stream()
                .filter(artifact -> !isRestrictedArtifact(artifact))
                .map(RepairArtifact::uri)
                .reduce((a, b) -> a + "," + b)
                .orElse(""));
        metadata.put("providerAttemptsJson", providerAttemptsJson(
                provider,
                result,
                artifacts,
                events
        ));
        result.metadata().forEach((key, value) -> metadata.put("runner." + key, SecretRedactor.redactValue(key, value)));
        return Map.copyOf(metadata);
    }

    private static String providerAttemptsJson(
            ProviderSpec provider,
            ContainerRunResult result,
            List<RepairArtifact> artifacts,
            EventCapture events
    ) {
        AgentEventTokenUsageSnapshot usage = tokenUsageFromArtifacts(artifacts);
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("provider", provider == null ? "" : provider.providerId());
        attempt.put("attempt", 1);
        attempt.put("status", result == null || result.exitCode() != 0 ? "FAILED" : "SUCCESS");
        attempt.put("tokenUsageAvailable", usage.available());
        attempt.put("tokenUsageFinalized", usage.finalized());
        if (usage.available()) {
            attempt.put("inputTokens", usage.inputTokens());
            attempt.put("outputTokens", usage.outputTokens());
            attempt.put("cacheReadInputTokens", usage.cacheReadTokens());
            attempt.put("cacheCreationInputTokens", usage.cacheWriteTokens());
            attempt.put("totalTokens", usage.totalTokens());
            if (usage.estimatedCostUsd().signum() > 0) {
                attempt.put("estimatedCostUsd", usage.estimatedCostUsd().toPlainString());
            }
            attempt.put("usageEventCount", usage.usageEventCount());
        }
        if (events != null) {
            attempt.put("agentSettled", events.agentSettled);
            attempt.put("resultSubmitted", events.resultSubmitted);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(List.of(attempt));
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }

    private static AgentEventTokenUsageSnapshot tokenUsageFromArtifacts(List<RepairArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return new AgentEventTokenUsageSnapshot(
                    0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO, 0, false, false
            );
        }
        AgentEventTokenUsageParser parser = new AgentEventTokenUsageParser();
        for (RepairArtifact artifact : artifacts) {
            if (artifact.type() != RepairArtifactType.AGENT_EVENTS) {
                continue;
            }
            String uri = artifact.uri();
            if (uri == null || uri.isBlank()) {
                continue;
            }
            try {
                if (uri.startsWith("file:")) {
                    AgentEventTokenUsageSnapshot parsed = parser.parse(Path.of(java.net.URI.create(uri)));
                    if (parsed.available()) {
                        return parsed;
                    }
                }
            } catch (RuntimeException ignored) {
                // Fall through to the next artifact candidate.
            }
        }
        return new AgentEventTokenUsageSnapshot(
                0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO, 0, false, false
        );
    }

    private static Map<String, String> baseMetadata(
            AgentExecutionProfileSnapshot snapshot,
            ProviderSpec provider,
            RepairWorkspace workspace
    ) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("runtime", "PI");
        metadata.put("executionProfileSnapshotId", snapshot.snapshotId());
        metadata.put("executionProfileSnapshotHash", snapshot.snapshotHash());
        metadata.put("provider", provider == null ? "" : provider.providerId());
        metadata.put("workspaceRoot", workspace == null ? "" : workspace.root().toString());
        return Map.copyOf(metadata);
    }

    private static RepairExecutionResult withRepositoryMetadata(
            RepairExecutionResult result,
            Map<String, String> metadata
    ) {
        if (result == null || metadata == null || metadata.isEmpty()) return result;
        Map<String, String> github = new LinkedHashMap<>(result.githubMetadataJson());
        github.putAll(metadata);
        return new RepairExecutionResult(
                result.status(), result.summary(), result.pullRequestUrl(), result.artifacts(),
                result.rawResultJson(), result.dockerMetadataJson(), github,
                result.testMetadataJson(), result.riskMetadataJson(), result.errorMessage()
        );
    }

    private static RepairExecutionResult failed(
            RepairExecutionStatus status,
            String summary,
            String category,
            String error,
            Map<String, String> metadata
    ) {
        return failed(status, summary, category, error, metadata, List.of());
    }

    private static RepairExecutionResult failed(
            RepairExecutionStatus status,
            String summary,
            String category,
            String error,
            Map<String, String> metadata,
            List<RepairArtifact> artifacts
    ) {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("status", status.name());
        raw.put("summary", summary);
        raw.put("failureCategory", category);
        raw.put("retryRecommendation", status == RepairExecutionStatus.UNSAFE ? "HUMAN" : "RETRY_NEW_ATTEMPT");
        return new RepairExecutionResult(
                status,
                summary,
                "",
                artifacts,
                raw,
                metadata == null ? Map.of() : metadata,
                Map.of(),
                Map.of(),
                Map.of("riskLevel", status == RepairExecutionStatus.UNSAFE ? "HIGH" : "MEDIUM"),
                SecretRedactor.redactFreeform(error)
        );
    }

    private static String containerFailureMessage(ContainerRunResult result, String streamError) {
        String detail = text(streamError);
        String exit = "container exited with code " + result.exitCode();
        if (detail.isBlank()) return exit;
        return detail.length() > 500 ? detail.substring(0, 500) + "..." : detail + " (" + exit + ")";
    }

    private boolean repositoryPublishRequired(RepairJobCommand command) {
        return command != null && Boolean.parseBoolean(command.policyJson().getOrDefault("repositoryPublishRequired", "false"));
    }

    private static String agentRole(RepairJobCommand command) {
        return command == null ? "" : text(command.contextJson().get("agentRole")).toUpperCase(Locale.ROOT);
    }

    private static String required(JsonNode root, String field) {
        String value = text(root.path(field));
        if (value.isBlank()) {
            throw new PiConfigurationException("PI_PROVIDER_CONFIGURATION", field + " must not be blank");
        }
        return value;
    }

    private static Set<String> stringSet(JsonNode value) {
        if (value == null || !value.isArray()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        value.forEach(item -> {
            String text = item.asText("").strip();
            if (!text.isBlank()) result.add(text);
        });
        return Set.copyOf(result);
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("").strip();
    }

    private static String frozenContextProtocolVersion(JsonNode snapshotJson) {
        String version = text(snapshotJson.path("contextProtocolVersion"));
        return version.isBlank() ? ContextProtocolVersion.LEGACY_ENVIRONMENT_NOTES.name() : version;
    }

    private static FactFreshnessEvaluator.FreshnessContext freshnessContext(
            RepairJobCommand command,
            JsonNode snapshotJson
    ) {
        String revision = firstNonBlank(
                text(snapshotJson.path("repoRevision")),
                command == null ? "" : command.baseBranch()
        );
        String workspaceFingerprint = firstNonBlank(
                text(snapshotJson.path("workspaceFingerprint")),
                command == null ? "" : command.taskId()
        );
        return new FactFreshnessEvaluator.FreshnessContext(
                revision,
                workspaceFingerprint,
                Instant.now(),
                FactFreshnessEvaluator.DEFAULT_MAX_TTL
        );
    }

    private static int positiveInt(JsonNode node, int defaultValue) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.canConvertToInt()) {
            return defaultValue;
        }
        int value = node.asInt(defaultValue);
        return value > 0 ? value : defaultValue;
    }

    private static String firstNonBlank(String first, String second) {
        return !first.isBlank() ? first : second;
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }

    private static String safeError(Throwable exception) {
        if (exception == null) return "";
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static String jsonArray(List<String> values) {
        try {
            return OBJECT_MAPPER.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }

    private static boolean isRestrictedArtifact(RepairArtifact artifact) {
        return artifact != null
                && ("true".equalsIgnoreCase(artifact.metadataJson().get("restricted"))
                || artifact.type() == RepairArtifactType.PI_RAW_EVENTS
                || artifact.type() == RepairArtifactType.PI_SESSION);
    }

    private static String safeContainerName(String taskId) {
        String normalized = taskId == null ? "" : taskId.strip();
        String safe = normalized.replaceAll("[^A-Za-z0-9_.-]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return "rd-bot-pi-" + (safe.isBlank() ? "task" : safe);
    }

    private static String piApi(ModelProviderProtocol protocol) {
        return switch (protocol) {
            case ANTHROPIC_COMPATIBLE, ANTHROPIC_MESSAGES -> "anthropic-messages";
            case OPENAI_CHAT_COMPLETIONS, OPENAI_COMPLETIONS -> "openai-completions";
            case OPENAI_RESPONSES -> "openai-responses";
            case GOOGLE_GENERATIVE_AI -> "google-generative-ai";
        };
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        return value;
    }

    private record ProviderSpec(
            String providerId,
            String model,
            String baseUrl,
            String api,
            String credentialEnvironmentVariable,
            String protocol,
            boolean authHeader
    ) {
    }

    private record ToolPolicySpec(
            Set<String> hostAllow,
            Set<String> allow,
            Set<String> deny,
            List<String> effectiveAllow
    ) {
    }

    /**
     * Holds both a JVM-local striped lock and an OS file lock for the complete
     * execution. The former avoids OverlappingFileLockException; the latter
     * serializes retry workers in separate JVMs sharing the workspace root.
     */
    private static final class WorkspaceExecutionLease implements AutoCloseable {
        private final ReentrantLock localLock;
        private final FileChannel channel;
        private final FileLock fileLock;

        private WorkspaceExecutionLease(ReentrantLock localLock, FileChannel channel, FileLock fileLock) {
            this.localLock = localLock;
            this.channel = channel;
            this.fileLock = fileLock;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                fileLock.release();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                channel.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            } finally {
                localLock.unlock();
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class EventCapture {
        private final AgentEventLineDecoder decoder;
        private final StringBuilder stderr = new StringBuilder();
        private volatile boolean resultSubmitted;
        private volatile boolean agentSettled;

        private volatile RuntimeException protocolFailure;

        private EventCapture(
                AgentExecutionProfileSnapshot snapshot,
                String containerName,
                AgentExecutionEventSink sink
        ) {
            this.decoder = new AgentEventLineDecoder(OBJECT_MAPPER, event -> {
                if (!snapshot.stageRunId().equals(text(event.get("stageRunId")))
                        || !snapshot.taskId().equals(text(event.get("taskId")))
                        || !snapshot.role().equals(text(event.get("role")))) {
                    throw new IllegalArgumentException("Pi event identity does not match the frozen snapshot");
                }
                String type = text(event.get("eventType"));
                if ("RESULT_SUBMITTED".equals(type)) resultSubmitted = true;
                if ("AGENT_SETTLED".equals(type)) agentSettled = true;
                try {
                    sink.onEvent(containerName, event);
                } catch (RuntimeException exception) {
                    protocolFailure = exception;
                    throw exception;
                }
            });
        }

        private void appendStderr(String chunk) {
            if (chunk == null || chunk.isEmpty()) return;
            int available = 16_384 - stderr.length();
            if (available > 0) stderr.append(chunk, 0, Math.min(available, chunk.length()));
        }

        private String stderr() {
            return stderr.toString();
        }

        private ContainerOutputListener listener() {
            return new ContainerOutputListener() {
                @Override
                public void onStdout(String chunk) {
                    accept(chunk);
                }

                @Override
                public void onStderr(String chunk) {
                    appendStderr(chunk);
                }
            };
        }

        private void accept(String chunk) {
            try {
                decoder.accept(chunk);
            } catch (RuntimeException exception) {
                protocolFailure = exception;
                throw exception;
            }
        }

        private void finish() {
            try {
                decoder.finish();
            } catch (RuntimeException exception) {
                protocolFailure = exception;
                throw exception;
            }
        }
    }

    private static final class PiConfigurationException extends RuntimeException {
        private final String category;

        private PiConfigurationException(String category, String message) {
            super(message);
            this.category = category;
        }

        private PiConfigurationException(String category, String message, Throwable cause) {
            super(message, cause);
            this.category = category;
        }
    }

    /** Immutable image/bridge configuration for one Pi container invocation. */
    public record Configuration(
            String image,
            String qaImage,
            List<String> command,
            String networkMode,
            boolean removeAfterExit,
            boolean allowPrivileged,
            long executionTimeoutMillis,
            long bashCommandTimeoutMillis,
            long rawEventMaxBytes,
            String requestProtocolVersion,
            boolean credentialRelayEnabled,
            String credentialRelayUrl
    ) {

        public Configuration {
            image = requireText(image, "image");
            qaImage = imageText(qaImage);
            command = requireCommand(command);
            networkMode = imageText(networkMode);
            executionTimeoutMillis = positiveTimeout(
                    executionTimeoutMillis, DEFAULT_EXECUTION_TIMEOUT_MILLIS);
            bashCommandTimeoutMillis = positiveTimeout(
                    bashCommandTimeoutMillis, DEFAULT_BASH_COMMAND_TIMEOUT_MILLIS);
            rawEventMaxBytes = Math.max(1L, rawEventMaxBytes);
            requestProtocolVersion = PiRequestV2Materializer.normalizeProtocolVersion(requestProtocolVersion);
            credentialRelayUrl = imageText(credentialRelayUrl);
            if (allowPrivileged) {
                throw new IllegalArgumentException("Pi executor never permits privileged containers");
            }
        }

        public Configuration(
                String image,
                List<String> command,
                String networkMode,
                boolean removeAfterExit,
                boolean allowPrivileged,
                long executionTimeoutMillis
        ) {
            this(image, "", command, networkMode, removeAfterExit, allowPrivileged, executionTimeoutMillis,
                    DEFAULT_BASH_COMMAND_TIMEOUT_MILLIS, 16L * 1024L * 1024L, "v1", false);
        }

        public Configuration(
                String image,
                String qaImage,
                List<String> command,
                String networkMode,
                boolean removeAfterExit,
                boolean allowPrivileged,
                long executionTimeoutMillis
        ) {
            this(image, qaImage, command, networkMode, removeAfterExit, allowPrivileged, executionTimeoutMillis,
                    DEFAULT_BASH_COMMAND_TIMEOUT_MILLIS, 16L * 1024L * 1024L, "v1", false);
        }

        public Configuration(
                String image,
                String qaImage,
                List<String> command,
                String networkMode,
                boolean removeAfterExit,
                boolean allowPrivileged,
                long executionTimeoutMillis,
                long bashCommandTimeoutMillis,
                long rawEventMaxBytes,
                String requestProtocolVersion,
                boolean credentialRelayEnabled
        ) {
            this(image, qaImage, command, networkMode, removeAfterExit, allowPrivileged, executionTimeoutMillis,
                    bashCommandTimeoutMillis, rawEventMaxBytes, requestProtocolVersion, credentialRelayEnabled,
                    DEFAULT_CREDENTIAL_RELAY_URL);
        }

        public Configuration(
                String image,
                String qaImage,
                List<String> command,
                String networkMode,
                boolean removeAfterExit,
                boolean allowPrivileged,
                long executionTimeoutMillis,
                long bashCommandTimeoutMillis,
                long rawEventMaxBytes,
                String requestProtocolVersion
        ) {
            this(image, qaImage, command, networkMode, removeAfterExit, allowPrivileged, executionTimeoutMillis,
                    bashCommandTimeoutMillis, rawEventMaxBytes, requestProtocolVersion, false,
                    DEFAULT_CREDENTIAL_RELAY_URL);
        }

        public static Configuration defaultConfiguration() {
            return new Configuration(
                    "rd-bot/pi-agent:local",
                    "rd-bot/pi-agent-qa:local",
                    List.of("node", "/opt/rd-pi-bridge/src/rd-pi-bridge.mjs"),
                    "bridge",
                    true,
                    false,
                    DEFAULT_EXECUTION_TIMEOUT_MILLIS,
                    DEFAULT_BASH_COMMAND_TIMEOUT_MILLIS,
                    16L * 1024L * 1024L,
                    "v1",
                    false
            );
        }

        private static long positiveTimeout(long value, long fallback) {
            return value > 0L ? value : fallback;
        }

        private static String requireText(String value, String field) {
            String normalized = imageText(value);
            if (normalized.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
            return normalized;
        }

        private static String imageText(String value) {
            return value == null ? "" : value.strip();
        }

        private static List<String> requireCommand(List<String> values) {
            if (values == null || values.isEmpty()) throw new IllegalArgumentException("command must not be empty");
            List<String> normalized = values.stream().map(Configuration::imageText).toList();
            if (normalized.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("command entries must not be blank");
            return List.copyOf(normalized);
        }
    }
}
