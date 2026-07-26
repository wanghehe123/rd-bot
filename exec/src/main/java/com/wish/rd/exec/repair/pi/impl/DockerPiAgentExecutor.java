package com.wish.rd.exec.repair.pi.impl;

import com.wish.rd.exec.repair.pi.AgentPrivateArtifactPublisher;
import com.wish.rd.exec.repair.pi.PiResourceManifestMaterializerPort;
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
import com.wish.rd.exec.repair.docker.model.RepairWorkspace;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.result.StructuredResultValidator;
import com.wish.rd.exec.repair.result.model.StructuredRepairResult;
import com.wish.rd.exec.repair.result.model.StructuredResultValidation;
import com.wish.rd.exec.repair.runtime.AgentEventLineDecoder;
import com.wish.rd.exec.repair.runtime.AgentExecutionEventSink;
import com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest;
import com.wish.rd.exec.repair.runtime.AgentRuntimeExecutorPort;
import com.wish.rd.exec.repair.security.SecretRedactor;
import com.wish.rd.exec.repair.security.model.ExecutionAllowlistPolicy;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.project.agent.model.ModelProviderProtocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final String CONTAINER_INPUT = "/work/input:ro";
    private static final String CONTAINER_OUTPUT = "/work/output";
    private static final String CONTAINER_CACHE = "/work/cache";
    private static final String RESULT_TOOL = "rd_submit_result";
    private static final String AGENT_RESULT_JSON = "__agentResultJson";
    private static final int SAFE_EVENT_PREVIEW_CHARS = 64 * 1024;

    private final RepairWorkspaceFactory workspaceFactory;
    private final ContainerRunnerPort containerRunner;
    private final StructuredResultValidator resultValidator;
    private final Configuration configuration;
    private final RepairWorkspaceRepositoryPort workspaceRepository;
    private final ExecutionAllowlistPolicy executionAllowlistPolicy;
    private final PiResourceManifestMaterializerPort resourceMaterializer;
    private final AgentExecutionEventSink eventSink;
    private final AgentPrivateArtifactPublisher privateArtifactPublisher;
    private final com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor.AuthEnvironmentResolver authEnvironmentResolver;

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
                AgentExecutionEventSink.noop(),
                AgentPrivateArtifactPublisher.noop(),
                com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor.AuthEnvironmentResolver.system()
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
                eventSink,
                AgentPrivateArtifactPublisher.noop(),
                authEnvironmentResolver
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
        this.eventSink = eventSink == null ? AgentExecutionEventSink.noop() : eventSink;
        this.privateArtifactPublisher = privateArtifactPublisher == null
                ? AgentPrivateArtifactPublisher.noop()
                : privateArtifactPublisher;
        this.authEnvironmentResolver = authEnvironmentResolver == null
                ? com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor.AuthEnvironmentResolver.system()
                : authEnvironmentResolver;
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
                || !"CODING_AGENT".equals(snapshot.role())
                || !"CODING_AGENT".equals(agentRole(command))) {
            return failed(
                    RepairExecutionStatus.FAILED_VALIDATION,
                    "Pi runtime is restricted to CODING_AGENT.",
                    "PI_ROLE_NOT_ALLOWED",
                    "Pi runtime is supported only for CODING_AGENT",
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
            workspace = workspaceFactory.create(command);
            repositoryMetadata.putAll(workspaceRepository.prepare(command, workspace).metadataJson());
            materializeResources(snapshot, workspace.inputDirectory());
            Path requestPath = workspace.inputDirectory().resolve("request.json").normalize();
            writeRequest(requestPath, snapshot, command, snapshotJson, provider, toolPolicy);

            Map<String, String> environment = runtimeEnvironment(snapshot, provider);
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
            ToolPolicySpec toolPolicy
    ) throws IOException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("protocol", "rd-pi-request/v1");
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
        request.put("resourceManifestPath", "/work/input/resource-manifest.json");
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

    private Map<String, String> runtimeEnvironment(AgentExecutionProfileSnapshot snapshot, ProviderSpec provider) {
        String credential = authEnvironmentResolver.resolve(provider.credentialEnvironmentVariable());
        if (credential == null || credential.isBlank()) {
            throw new PiConfigurationException(
                    "PI_PROVIDER_CONFIGURATION",
                    "provider credential environment variable is missing: " + provider.credentialEnvironmentVariable()
            );
        }
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(provider.credentialEnvironmentVariable(), credential);
        environment.put("RD_AGENT_ROLE", snapshot.role());
        environment.put("RD_AGENT_RUNTIME", "PI");
        environment.put("RD_AGENT_SNAPSHOT_ID", snapshot.snapshotId());
        environment.put("RD_AGENT_STAGE_RUN_ID", snapshot.stageRunId());
        return Map.copyOf(environment);
    }

    private ContainerRunRequest containerRequest(
            RepairJobCommand command,
            AgentExecutionProfileSnapshot snapshot,
            RepairWorkspace workspace,
            Map<String, String> environment
    ) {
        Map<String, String> mounts = new LinkedHashMap<>();
        mounts.put(workspace.repoDirectory().toString(), CONTAINER_REPO);
        mounts.put(workspace.inputDirectory().toString(), CONTAINER_INPUT);
        mounts.put(workspace.outputDirectory().toString(), CONTAINER_OUTPUT);
        mounts.put(workspace.cacheDirectory().toString(), CONTAINER_CACHE);
        return new ContainerRunRequest(
                safeContainerName(command.taskId()),
                configuration.image(),
                configuration.command(),
                environment,
                mounts,
                CONTAINER_REPO,
                configuration.networkMode(),
                configuration.removeAfterExit(),
                false,
                workspace.outputDirectory(),
                false,
                "",
                configuration.executionTimeoutMillis()
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
        StructuredResultValidation validation = resultValidator.validate(rawJson);
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
            List<String> missing = missingSuccessArtifacts(artifacts);
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

    private static List<String> missingSuccessArtifacts(List<RepairArtifact> artifacts) {
        Set<String> names = artifacts.stream().map(RepairArtifact::name).collect(java.util.stream.Collectors.toSet());
        return Stream.of("patch.diff", "test.log", "agent-events.jsonl")
                .filter(name -> !names.contains(name))
                .map(name -> name + " is missing")
                .toList();
    }

    private static RepairExecutionStatus toStatus(String value) {
        return switch (value == null ? "" : value.strip().toUpperCase(Locale.ROOT)) {
            case "SUCCESS" -> RepairExecutionStatus.SUCCESS;
            case "FAILED" -> RepairExecutionStatus.FAILED;
            case "NEED_INFO" -> RepairExecutionStatus.NEED_INFO;
            case "UNSAFE" -> RepairExecutionStatus.UNSAFE;
            default -> RepairExecutionStatus.FAILED_VALIDATION;
        };
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
        return switch (name) {
            case "result.json" -> RepairArtifactType.RESULT_JSON;
            case "patch.diff" -> RepairArtifactType.PATCH_DIFF;
            case "test.log" -> RepairArtifactType.TEST_LOG;
            case "agent-events.jsonl" -> RepairArtifactType.AGENT_EVENTS;
            case "runtime-meta.json" -> RepairArtifactType.AGENT_RUNTIME_META;
            case "docker-meta.json" -> RepairArtifactType.DOCKER_METADATA;
            default -> RepairArtifactType.OTHER;
        };
    }

    private static Map<String, String> artifactMetadata(Path path) {
        try {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("bytes", String.valueOf(Files.size(path)));
            metadata.put("sha256", sha256(path));
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
            }
            return Map.copyOf(metadata);
        } catch (IOException exception) {
            return Map.of();
        }
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
        result.metadata().forEach((key, value) -> metadata.put("runner." + key, SecretRedactor.redactValue(key, value)));
        return Map.copyOf(metadata);
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
            List<String> command,
            String networkMode,
            boolean removeAfterExit,
            boolean allowPrivileged,
            long executionTimeoutMillis
    ) {

        public Configuration {
            image = requireText(image, "image");
            command = requireCommand(command);
            networkMode = imageText(networkMode);
            executionTimeoutMillis = Math.max(0L, executionTimeoutMillis);
            if (allowPrivileged) {
                throw new IllegalArgumentException("Pi executor never permits privileged containers");
            }
        }

        public static Configuration defaultConfiguration() {
            return new Configuration(
                    "rd-bot/pi-agent:local",
                    List.of("node", "/opt/rd-pi-bridge/src/rd-pi-bridge.mjs"),
                    "bridge",
                    true,
                    false,
                    0L
            );
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
