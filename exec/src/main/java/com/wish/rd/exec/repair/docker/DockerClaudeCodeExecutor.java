package com.wish.rd.exec.repair.docker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.alert.RepairExecutionWatchdog;
import com.wish.rd.exec.repair.execution.RepairArtifact;
import com.wish.rd.exec.repair.execution.RepairArtifactType;
import com.wish.rd.exec.repair.execution.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.execution.RepairJobCommand;
import com.wish.rd.exec.repair.model.ModelCircuitBreakerPolicy;
import com.wish.rd.exec.repair.model.ModelHealthSnapshot;
import com.wish.rd.exec.repair.model.ModelHealthStore;
import com.wish.rd.exec.repair.result.StructuredRepairResult;
import com.wish.rd.exec.repair.result.StructuredResultValidation;
import com.wish.rd.exec.repair.result.StructuredResultValidator;
import com.wish.rd.exec.repair.security.ExecutionAllowlistPolicy;
import com.wish.rd.exec.repair.security.SecretRedactor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Docker Claude Code 修复执行器，负责编排工作区、容器端口、输出产物收集和结构化结果校验。
 */
public class DockerClaudeCodeExecutor implements RepairExecutorPort {

    private static final String CONTAINER_REPO_DIRECTORY = "/work/repo";
    private static final String CONTAINER_INPUT_DIRECTORY = "/work/input";
    private static final String CONTAINER_OUTPUT_DIRECTORY = "/work/output";
    private static final String AUTH_TOKEN_ENV_ROUTER = "RD_CLAUDE_AUTH_TOKEN_ENV";
    private static final String API_KEY_ENV_ROUTER = "RD_CLAUDE_API_KEY_ENV";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RepairWorkspaceFactory workspaceFactory;
    private final ContainerRunnerPort containerRunner;
    private final StructuredResultValidator resultValidator;
    private final Configuration configuration;
    private final RepairWorkspaceRepositoryPort workspaceRepository;
    private final RepairExecutionWatchdog watchdog;
    private final ModelHealthStore modelHealthStore;
    private final DockerExecutionRegistry executionRegistry;
    private final ExecutionAllowlistPolicy executionAllowlistPolicy;

    /**
     * 创建 Docker Claude Code 执行器。
     *
     * @param workspaceFactory 工作区工厂
     * @param containerRunner  容器执行端口
     * @param resultValidator  结构化结果校验器
     * @param configuration    Docker 与 Claude Code 命令配置
     */
    public DockerClaudeCodeExecutor(
            RepairWorkspaceFactory workspaceFactory,
            ContainerRunnerPort containerRunner,
            StructuredResultValidator resultValidator,
            Configuration configuration
    ) {
        this(workspaceFactory, containerRunner, resultValidator, configuration, null);
    }

    /**
     * 创建可接入告警观察器的 Docker Claude Code 执行器。
     *
     * @param workspaceFactory 工作区工厂
     * @param containerRunner  容器执行端口
     * @param resultValidator  结构化结果校验器
     * @param configuration    Docker 与 Claude Code 命令配置
     * @param watchdog         超时和预算告警观察器，可为空
     */
    public DockerClaudeCodeExecutor(
            RepairWorkspaceFactory workspaceFactory,
            ContainerRunnerPort containerRunner,
            StructuredResultValidator resultValidator,
            Configuration configuration,
            RepairExecutionWatchdog watchdog
    ) {
        this(
                workspaceFactory,
                containerRunner,
                resultValidator,
                configuration,
                RepairWorkspaceRepositoryPort.noop(),
                watchdog
        );
    }

    /**
     * 创建可接入仓库准备、仓库发布和告警观察器的 Docker Claude Code 执行器。
     *
     * @param workspaceFactory   工作区工厂
     * @param containerRunner    容器执行端口
     * @param resultValidator    结构化结果校验器
     * @param configuration      Docker 与 Claude Code 命令配置
     * @param workspaceRepository 修复工作区仓库端口
     * @param watchdog           超时和预算告警观察器，可为空
     */
    public DockerClaudeCodeExecutor(
            RepairWorkspaceFactory workspaceFactory,
            ContainerRunnerPort containerRunner,
            StructuredResultValidator resultValidator,
            Configuration configuration,
            RepairWorkspaceRepositoryPort workspaceRepository,
            RepairExecutionWatchdog watchdog
    ) {
        this(
                workspaceFactory,
                containerRunner,
                resultValidator,
                configuration,
                workspaceRepository,
                watchdog,
                new ModelHealthStore(ModelCircuitBreakerPolicy.disabled())
        );
    }

    /**
     * 创建可接入仓库、告警观察器和模型健康熔断器的 Docker Claude Code 执行器。
     *
     * @param workspaceFactory    工作区工厂
     * @param containerRunner     容器执行端口
     * @param resultValidator     结构化结果校验器
     * @param configuration       Docker 与 Claude Code 命令配置
     * @param workspaceRepository 修复工作区仓库端口
     * @param watchdog            超时和预算告警观察器，可为空
     * @param modelHealthStore    模型供应商健康状态存储器
     */
    public DockerClaudeCodeExecutor(
            RepairWorkspaceFactory workspaceFactory,
            ContainerRunnerPort containerRunner,
            StructuredResultValidator resultValidator,
            Configuration configuration,
            RepairWorkspaceRepositoryPort workspaceRepository,
            RepairExecutionWatchdog watchdog,
            ModelHealthStore modelHealthStore
    ) {
        this(
                workspaceFactory,
                containerRunner,
                resultValidator,
                configuration,
                workspaceRepository,
                watchdog,
                modelHealthStore,
                DockerExecutionRegistry.noop()
        );
    }

    /**
     * 创建可接入仓库、告警观察器、模型熔断器和执行控制注册表的 Docker Claude Code 执行器。
     *
     * @param workspaceFactory    工作区工厂
     * @param containerRunner     容器执行端口
     * @param resultValidator     结构化结果校验器
     * @param configuration       Docker 与 Claude Code 命令配置
     * @param workspaceRepository 修复工作区仓库端口
     * @param watchdog            超时和预算告警观察器，可为空
     * @param modelHealthStore    模型供应商健康状态存储器
     * @param executionRegistry   Docker 执行运行态注册表
     */
    public DockerClaudeCodeExecutor(
            RepairWorkspaceFactory workspaceFactory,
            ContainerRunnerPort containerRunner,
            StructuredResultValidator resultValidator,
            Configuration configuration,
            RepairWorkspaceRepositoryPort workspaceRepository,
            RepairExecutionWatchdog watchdog,
            ModelHealthStore modelHealthStore,
            DockerExecutionRegistry executionRegistry
    ) {
        this(
                workspaceFactory,
                containerRunner,
                resultValidator,
                configuration,
                workspaceRepository,
                watchdog,
                modelHealthStore,
                executionRegistry,
                ExecutionAllowlistPolicy.disabled()
        );
    }

    /**
     * 创建可接入仓库、告警观察器、模型熔断器、执行控制注册表和安全 allowlist 的 Docker Claude Code 执行器。
     *
     * @param workspaceFactory         工作区工厂
     * @param containerRunner          容器执行端口
     * @param resultValidator          结构化结果校验器
     * @param configuration            Docker 与 Claude Code 命令配置
     * @param workspaceRepository      修复工作区仓库端口
     * @param watchdog                 超时和预算告警观察器，可为空
     * @param modelHealthStore         模型供应商健康状态存储器
     * @param executionRegistry        Docker 执行运行态注册表
     * @param executionAllowlistPolicy 执行目标 allowlist 策略
     */
    public DockerClaudeCodeExecutor(
            RepairWorkspaceFactory workspaceFactory,
            ContainerRunnerPort containerRunner,
            StructuredResultValidator resultValidator,
            Configuration configuration,
            RepairWorkspaceRepositoryPort workspaceRepository,
            RepairExecutionWatchdog watchdog,
            ModelHealthStore modelHealthStore,
            DockerExecutionRegistry executionRegistry,
            ExecutionAllowlistPolicy executionAllowlistPolicy
    ) {
        if (workspaceFactory == null) {
            throw new IllegalArgumentException("workspaceFactory must not be null");
        }
        if (containerRunner == null) {
            throw new IllegalArgumentException("containerRunner must not be null");
        }
        this.workspaceFactory = workspaceFactory;
        this.containerRunner = containerRunner;
        this.resultValidator = resultValidator == null ? new StructuredResultValidator() : resultValidator;
        this.configuration = configuration == null ? Configuration.defaultConfiguration() : configuration;
        this.workspaceRepository = workspaceRepository == null
                ? RepairWorkspaceRepositoryPort.noop()
                : workspaceRepository;
        this.watchdog = watchdog;
        this.modelHealthStore = modelHealthStore == null
                ? new ModelHealthStore(ModelCircuitBreakerPolicy.disabled())
                : modelHealthStore;
        this.executionRegistry = executionRegistry == null ? DockerExecutionRegistry.noop() : executionRegistry;
        this.executionAllowlistPolicy = executionAllowlistPolicy == null
                ? ExecutionAllowlistPolicy.disabled()
                : executionAllowlistPolicy;
    }

    /**
     * 执行一次 Docker Claude Code 修复任务。
     *
     * @param command 修复执行命令
     * @return 修复执行结果
     */
    @Override
    public RepairExecutionResult execute(RepairJobCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        ExecutionAllowlistPolicy.Decision securityDecision = executionAllowlistPolicy.evaluate(command);
        if (!securityDecision.allowed()) {
            return rejectedBySecurityPolicy(securityDecision.reason());
        }
        List<Map<String, String>> providerAttempts = new ArrayList<>();
        RepairExecutionResult lastResult = null;
        try {
            RepairWorkspace workspace = workspaceFactory.create(command);
            Map<String, String> repositoryMetadata = new LinkedHashMap<>(
                    workspaceRepository.prepare(command, workspace).metadataJson()
            );
            List<ClaudeCodeModelProvider> providers = configuration.providers();
            for (int index = 0; index < providers.size(); index++) {
                ClaudeCodeModelProvider provider = providers.get(index);
                if (!modelHealthStore.allowCall(provider.name())) {
                    providerAttempts.add(skippedAttemptMetadata(provider, index + 1));
                    continue;
                }
                cleanOutputDirectory(workspace.outputDirectory());
                AttemptOutcome outcome = runProvider(command, workspace, provider, index + 1, providers.size());
                RepairExecutionResult providerResult = withRepositoryMetadata(outcome.result(), repositoryMetadata);
                markProviderHealth(provider, providerResult);
                lastResult = providerResult;
                if (providerResult.status() == RepairExecutionStatus.SUCCESS) {
                    lastResult = publishRepository(command, workspace, providerResult);
                }
                providerAttempts.add(attemptMetadata(provider, index + 1, providerResult));
                if (!shouldFallback(lastResult) || index == providers.size() - 1) {
                    return withProviderMetadata(lastResult, provider, providerAttempts);
                }
            }
            if (lastResult == null && !providerAttempts.isEmpty()) {
                ClaudeCodeModelProvider provider = providers.getFirst();
                int attempt = providerAttempts.size() + 1;
                cleanOutputDirectory(workspace.outputDirectory());
                AttemptOutcome outcome = runProvider(command, workspace, provider, attempt, providers.size());
                RepairExecutionResult providerResult = withRepositoryMetadata(outcome.result(), repositoryMetadata);
                markProviderHealth(provider, providerResult);
                lastResult = providerResult;
                if (providerResult.status() == RepairExecutionStatus.SUCCESS) {
                    lastResult = publishRepository(command, workspace, providerResult);
                }
                providerAttempts.add(attemptMetadata(provider, attempt, providerResult));
                return withProviderMetadata(lastResult, provider, providerAttempts);
            }
            return withProviderAttempts(
                    lastResult == null
                            ? failedExecution("all Claude Code providers are unavailable by circuit breaker")
                            : lastResult,
                    providerAttempts
            );
        } catch (IOException e) {
            return failedExecution(e.getMessage());
        }
    }

    private RepairExecutionResult publishRepository(
            RepairJobCommand command,
            RepairWorkspace workspace,
            RepairExecutionResult result
    ) {
        try {
            RepairWorkspaceRepositoryPort.RepositoryOperationResult publishResult =
                    workspaceRepository.publish(command, workspace);
            return withRepositoryMetadata(result, publishResult.metadataJson());
        } catch (IOException exception) {
            return repositoryFailure(result, exception.getMessage());
        }
    }

    private AttemptOutcome runProvider(
            RepairJobCommand command,
            RepairWorkspace workspace,
            ClaudeCodeModelProvider provider,
            int attempt,
            int providerCount
    ) {
        RepairExecutionResult validationResult = validateProviderRuntimeEnvironment(provider);
        if (validationResult != null) {
            return new AttemptOutcome(validationResult);
        }
        try {
            ContainerRunRequest request = toRunRequest(command, workspace, provider, attempt, providerCount);
            executionRegistry.register(command, provider.name(), request);
            ContainerRunResult runResult;
            try {
                runResult = containerRunner.run(request);
            } finally {
                executionRegistry.unregister(command.taskId());
            }
            if (runResult == null) {
                return new AttemptOutcome(failedExecution("container runner returned null result"));
            }
            evaluateWatchdog(command, runResult);
            List<RepairArtifact> artifacts = collectArtifacts(workspace.outputDirectory());
            Map<String, String> dockerMetadata = dockerMetadata(request, runResult, artifacts);
            return new AttemptOutcome(toExecutionResult(runResult, artifacts, dockerMetadata));
        } catch (IOException exception) {
            return new AttemptOutcome(failedExecution(exception.getMessage()));
        }
    }

    private RepairExecutionResult validateProviderRuntimeEnvironment(ClaudeCodeModelProvider provider) {
        List<String> missingEnvNames = missingRoutedAuthEnvNames(provider.env());
        if (missingEnvNames.isEmpty()) {
            return null;
        }
        String message = "provider " + provider.name()
                + " is missing required auth environment variable(s): "
                + String.join(", ", missingEnvNames)
                + ". Set them on the RD-Bot process and restart before running Docker Claude Code.";
        return failedProviderValidation(message, missingEnvNames);
    }

    private static List<String> missingRoutedAuthEnvNames(Map<String, String> env) {
        List<String> missingEnvNames = new ArrayList<>();
        collectMissingRoutedAuthEnvName(missingEnvNames, env, AUTH_TOKEN_ENV_ROUTER);
        collectMissingRoutedAuthEnvName(missingEnvNames, env, API_KEY_ENV_ROUTER);
        return List.copyOf(missingEnvNames);
    }

    private static void collectMissingRoutedAuthEnvName(
            List<String> missingEnvNames,
            Map<String, String> env,
            String routerKey
    ) {
        String routedEnvName = normalizeEnvText(env.get(routerKey));
        if (routedEnvName.isBlank()) {
            return;
        }
        String hostValue = normalizeEnvText(System.getenv(routedEnvName));
        if (hostValue.isBlank() && !missingEnvNames.contains(routedEnvName)) {
            missingEnvNames.add(routedEnvName);
        }
    }

    private static String normalizeEnvText(String value) {
        return value == null ? "" : value.strip();
    }

    private void evaluateWatchdog(RepairJobCommand command, ContainerRunResult runResult) {
        if (watchdog == null) {
            return;
        }
        watchdog.evaluate(
                command.repairRecordId(),
                command.taskId(),
                runResult.durationMillis(),
                estimatedSpend(runResult.metadata()),
                System.currentTimeMillis()
        );
    }

    private ContainerRunRequest toRunRequest(
            RepairJobCommand command,
            RepairWorkspace workspace,
            ClaudeCodeModelProvider provider,
            int attempt,
            int providerCount
    ) {
        Map<String, String> mounts = new LinkedHashMap<>();
        mounts.put(workspace.repoDirectory().toString(), CONTAINER_REPO_DIRECTORY);
        mounts.put(workspace.inputDirectory().toString(), CONTAINER_INPUT_DIRECTORY);
        mounts.put(workspace.outputDirectory().toString(), CONTAINER_OUTPUT_DIRECTORY);
        Map<String, String> env = new LinkedHashMap<>(provider.env());
        env.putIfAbsent("RD_CLAUDE_PROVIDER_NAME", provider.name());
        env.put("RD_CLAUDE_PROVIDER_ATTEMPT", String.valueOf(attempt));
        return new ContainerRunRequest(
                providerCount <= 1
                        ? safeContainerName(command.taskId())
                        : safeContainerName(command.taskId() + "-" + provider.name() + "-" + attempt),
                configuration.image(),
                configuration.command(),
                env,
                mounts,
                CONTAINER_REPO_DIRECTORY,
                configuration.networkMode(),
                configuration.removeAfterExit(),
                configuration.allowPrivileged(),
                workspace.outputDirectory()
        );
    }

    private static boolean shouldFallback(RepairExecutionResult result) {
        if (result == null) {
            return true;
        }
        if (result.status() == RepairExecutionStatus.FAILED_VALIDATION) {
            return true;
        }
        if (result.status() == RepairExecutionStatus.FAILED) {
            String error = result.errorMessage();
            return error.contains("container runner returned null result")
                    || error.contains("container exited with code")
                    || error.contains("docker command")
                    || error.contains("result.json is missing");
        }
        return false;
    }

    private void markProviderHealth(ClaudeCodeModelProvider provider, RepairExecutionResult result) {
        if (provider == null || result == null) {
            return;
        }
        if (result.status() == RepairExecutionStatus.SUCCESS
                || result.status() == RepairExecutionStatus.NEED_INFO
                || result.status() == RepairExecutionStatus.UNSAFE) {
            modelHealthStore.markSuccess(provider.name());
            return;
        }
        if (result.status() == RepairExecutionStatus.FAILED_VALIDATION
                && !isProviderRuntimeConfigurationFailure(result)) {
            modelHealthStore.markFailure(provider.name());
            return;
        }
        if (result.status() == RepairExecutionStatus.FAILED && shouldFallback(result)) {
            modelHealthStore.markFailure(provider.name());
        }
    }

    private static boolean isProviderRuntimeConfigurationFailure(RepairExecutionResult result) {
        return "failed".equals(result.dockerMetadataJson().get("providerAuthPrecheck"));
    }

    private static RepairExecutionResult withProviderMetadata(
            RepairExecutionResult result,
            ClaudeCodeModelProvider provider,
            List<Map<String, String>> providerAttempts
    ) {
        Map<String, String> dockerMetadata = new LinkedHashMap<>(result.dockerMetadataJson());
        dockerMetadata.put("provider", provider.name());
        dockerMetadata.put("providerAttemptsJson", jsonValue(providerAttempts));
        return new RepairExecutionResult(
                result.status(),
                result.summary(),
                result.pullRequestUrl(),
                result.artifacts(),
                result.rawResultJson(),
                dockerMetadata,
                result.githubMetadataJson(),
                result.testMetadataJson(),
                result.riskMetadataJson(),
                result.errorMessage()
        );
    }

    private static RepairExecutionResult withRepositoryMetadata(
            RepairExecutionResult result,
            Map<String, String> repositoryMetadata
    ) {
        if (repositoryMetadata == null || repositoryMetadata.isEmpty()) {
            return result;
        }
        Map<String, String> githubMetadata = new LinkedHashMap<>(result.githubMetadataJson());
        repositoryMetadata.forEach((key, value) -> githubMetadata.put("repository." + key, value));
        return new RepairExecutionResult(
                result.status(),
                result.summary(),
                result.pullRequestUrl(),
                result.artifacts(),
                result.rawResultJson(),
                result.dockerMetadataJson(),
                githubMetadata,
                result.testMetadataJson(),
                result.riskMetadataJson(),
                result.errorMessage()
        );
    }

    private static RepairExecutionResult withProviderAttempts(
            RepairExecutionResult result,
            List<Map<String, String>> providerAttempts
    ) {
        Map<String, String> dockerMetadata = new LinkedHashMap<>(result.dockerMetadataJson());
        dockerMetadata.put("providerAttemptsJson", jsonValue(providerAttempts));
        return new RepairExecutionResult(
                result.status(),
                result.summary(),
                result.pullRequestUrl(),
                result.artifacts(),
                result.rawResultJson(),
                dockerMetadata,
                result.githubMetadataJson(),
                result.testMetadataJson(),
                result.riskMetadataJson(),
                result.errorMessage()
        );
    }

    private Map<String, String> attemptMetadata(
            ClaudeCodeModelProvider provider,
            int attempt,
            RepairExecutionResult result
    ) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("provider", provider.name());
        metadata.put("attempt", String.valueOf(attempt));
        metadata.put("status", result == null ? "FAILED" : result.status().name());
        metadata.put("errorMessage", result == null ? "" : SecretRedactor.redactFreeform(result.errorMessage()));
        putHealthMetadata(metadata, provider.name(), modelHealthStore.snapshot(provider.name()));
        return Map.copyOf(metadata);
    }

    private Map<String, String> skippedAttemptMetadata(ClaudeCodeModelProvider provider, int attempt) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("provider", provider.name());
        metadata.put("attempt", String.valueOf(attempt));
        metadata.put("status", "SKIPPED_CIRCUIT_OPEN");
        metadata.put("errorMessage", "provider is unavailable by circuit breaker");
        putHealthMetadata(metadata, provider.name(), modelHealthStore.snapshot(provider.name()));
        return Map.copyOf(metadata);
    }

    private static void putHealthMetadata(
            Map<String, String> metadata,
            String providerName,
            ModelHealthSnapshot healthSnapshot
    ) {
        ModelHealthSnapshot snapshot = healthSnapshot == null
                ? new ModelHealthSnapshot(providerName, null, 0, 0L, false)
                : healthSnapshot;
        metadata.put("circuitState", snapshot.state().name());
        metadata.put("consecutiveFailures", String.valueOf(snapshot.consecutiveFailures()));
        metadata.put("openUntilEpochMillis", String.valueOf(snapshot.openUntilEpochMillis()));
        metadata.put("halfOpenInFlight", String.valueOf(snapshot.halfOpenInFlight()));
    }

    private static void cleanOutputDirectory(Path outputDirectory) throws IOException {
        if (outputDirectory == null || !Files.isDirectory(outputDirectory)) {
            return;
        }
        try (Stream<Path> files = Files.list(outputDirectory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    private RepairExecutionResult toExecutionResult(
            ContainerRunResult runResult,
            List<RepairArtifact> artifacts,
            Map<String, String> dockerMetadata
    ) throws IOException {
        Path resultJson = runResult.resultJson();
        if (resultJson == null || !Files.isRegularFile(resultJson)) {
            return failedValidation("result.json is missing", artifacts, dockerMetadata);
        }

        String rawJson = Files.readString(resultJson, StandardCharsets.UTF_8);
        StructuredResultValidation validation = resultValidator.validate(rawJson);
        if (!validation.valid()) {
            return failedValidation(String.join("; ", validation.errors()), artifacts, dockerMetadata);
        }

        StructuredRepairResult structured = validation.result();
        RepairExecutionStatus status = toStatus(structured.status());
        if (status == RepairExecutionStatus.SUCCESS) {
            List<String> missingArtifacts = missingSuccessArtifacts(artifacts);
            if (!missingArtifacts.isEmpty()) {
                return failedValidation(String.join("; ", missingArtifacts), artifacts, dockerMetadata);
            }
        }
        String errorMessage = "";
        if (runResult.exitCode() != 0 && status != RepairExecutionStatus.NEED_INFO) {
            status = RepairExecutionStatus.FAILED;
            errorMessage = containerFailureMessage(runResult);
        }

        return new RepairExecutionResult(
                status,
                structured.summary(),
                "",
                artifacts,
                rawResultJson(structured),
                dockerMetadata,
                Map.of(),
                testMetadataJson(structured),
                riskMetadataJson(structured),
                errorMessage
        );
    }

    private static String containerFailureMessage(ContainerRunResult runResult) {
        String providerError = claudeApiError(runResult.claudeEventsJsonl());
        String containerExit = "container exited with code " + runResult.exitCode();
        if (providerError.isBlank()) {
            return containerExit;
        }
        return providerError + " (" + containerExit + ")";
    }

    private static String claudeApiError(Path claudeEventsJsonl) {
        if (claudeEventsJsonl == null || !Files.isRegularFile(claudeEventsJsonl)) {
            return "";
        }
        String lastError = "";
        try {
            List<String> lines = Files.readAllLines(claudeEventsJsonl, StandardCharsets.UTF_8);
            for (String line : lines) {
                String error = apiErrorFromEventLine(line);
                if (!error.isBlank()) {
                    lastError = error;
                }
            }
        } catch (IOException exception) {
            return "";
        }
        return lastError;
    }

    private static String apiErrorFromEventLine(String line) {
        String normalized = line == null ? "" : line.strip();
        if (normalized.isBlank() || !normalized.contains("API Error:")) {
            return "";
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(normalized);
            return formatClaudeApiError(firstTextContainingApiError(root));
        } catch (JsonProcessingException exception) {
            return formatClaudeApiError(normalized);
        }
    }

    private static String firstTextContainingApiError(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            String text = node.asText("");
            return text.contains("API Error:") ? text : "";
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                String result = firstTextContainingApiError(child);
                if (!result.isBlank()) {
                    return result;
                }
            }
            return "";
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                String result = firstTextContainingApiError(fields.next().getValue());
                if (!result.isBlank()) {
                    return result;
                }
            }
        }
        return "";
    }

    private static String formatClaudeApiError(String text) {
        String normalized = text == null ? "" : text.strip();
        int marker = normalized.indexOf("API Error:");
        if (marker < 0) {
            return "";
        }
        String apiError = normalized.substring(marker + "API Error:".length()).strip();
        if (apiError.isBlank()) {
            return "";
        }
        int jsonStart = apiError.indexOf('{');
        String statusCode = jsonStart < 0 ? "" : apiError.substring(0, jsonStart).strip();
        String message = jsonStart < 0 ? "" : apiErrorMessage(apiError.substring(jsonStart));
        if (!statusCode.isBlank() && !message.isBlank()) {
            return SecretRedactor.redactFreeform("Claude Code API error " + statusCode + ": " + message);
        }
        return SecretRedactor.redactFreeform(limitErrorText("Claude Code API error: " + apiError));
    }

    private static String apiErrorMessage(String json) {
        try {
            return OBJECT_MAPPER.readTree(json).path("error").path("message").asText("");
        } catch (JsonProcessingException exception) {
            return "";
        }
    }

    private static String limitErrorText(String text) {
        String normalized = text == null ? "" : text.strip();
        if (normalized.length() <= 500) {
            return normalized;
        }
        return normalized.substring(0, 500) + "...";
    }

    private RepairExecutionResult failedExecution(String errorMessage) {
        return new RepairExecutionResult(
                RepairExecutionStatus.FAILED,
                "Docker Claude Code execution failed.",
                "",
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                errorMessage == null ? "" : errorMessage
        );
    }

    private RepairExecutionResult failedProviderValidation(String errorMessage, List<String> missingEnvNames) {
        String safeErrorMessage = errorMessage == null ? "" : errorMessage;
        return new RepairExecutionResult(
                RepairExecutionStatus.FAILED_VALIDATION,
                "Docker Claude Code provider validation failed.",
                "",
                List.of(),
                Map.of("validationErrors", safeErrorMessage),
                Map.of(
                        "providerAuthPrecheck", "failed",
                        "missingAuthEnvNames", String.join(",", missingEnvNames == null ? List.of() : missingEnvNames)
                ),
                Map.of(),
                Map.of(),
                Map.of(),
                safeErrorMessage
        );
    }

    private RepairExecutionResult repositoryFailure(RepairExecutionResult result, String errorMessage) {
        return new RepairExecutionResult(
                RepairExecutionStatus.FAILED,
                "Repository publish failed.",
                "",
                result.artifacts(),
                result.rawResultJson(),
                result.dockerMetadataJson(),
                result.githubMetadataJson(),
                result.testMetadataJson(),
                result.riskMetadataJson(),
                errorMessage == null ? "" : errorMessage
        );
    }

    private RepairExecutionResult rejectedBySecurityPolicy(String reason) {
        String message = reason == null || reason.isBlank()
                ? "execution rejected by security policy"
                : reason;
        return new RepairExecutionResult(
                RepairExecutionStatus.UNSAFE,
                "Repair execution rejected by security policy.",
                "",
                List.of(),
                Map.of(),
                Map.of("securityPolicyRejected", "true"),
                Map.of(),
                Map.of(),
                Map.of(
                        "securityPolicy", "executionAllowlist",
                        "rejected", "true",
                        "reason", SecretRedactor.redactFreeform(message)
                ),
                SecretRedactor.redactFreeform(message)
        );
    }

    private RepairExecutionResult failedValidation(
            String errorMessage,
            List<RepairArtifact> artifacts,
            Map<String, String> dockerMetadata
    ) {
        return new RepairExecutionResult(
                RepairExecutionStatus.FAILED_VALIDATION,
                "Docker Claude Code result validation failed.",
                "",
                artifacts,
                Map.of("validationErrors", errorMessage == null ? "" : errorMessage),
                dockerMetadata,
                Map.of(),
                Map.of(),
                Map.of(),
                errorMessage
        );
    }

    private static RepairExecutionStatus toStatus(String status) {
        return switch (status) {
            case "SUCCESS" -> RepairExecutionStatus.SUCCESS;
            case "FAILED" -> RepairExecutionStatus.FAILED;
            case "NEED_INFO" -> RepairExecutionStatus.NEED_INFO;
            case "UNSAFE" -> RepairExecutionStatus.UNSAFE;
            default -> RepairExecutionStatus.FAILED_VALIDATION;
        };
    }

    private static List<String> missingSuccessArtifacts(List<RepairArtifact> artifacts) {
        List<String> artifactNames = artifacts.stream()
                .map(RepairArtifact::name)
                .toList();
        return Stream.of("patch.diff", "test.log", "claude-events.jsonl")
                .filter(name -> !artifactNames.contains(name))
                .map(name -> name + " is missing")
                .toList();
    }

    private static List<RepairArtifact> collectArtifacts(Path outputDirectory) throws IOException {
        if (outputDirectory == null || !Files.isDirectory(outputDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(outputDirectory)) {
            return files
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(DockerClaudeCodeExecutor::toArtifact)
                    .toList();
        }
    }

    private static RepairArtifact toArtifact(Path path) {
        String name = path.getFileName().toString();
        return new RepairArtifact(
                artifactType(name),
                name,
                path.toUri().toString(),
                artifactSummary(name),
                artifactMetadata(path)
        );
    }

    private static RepairArtifactType artifactType(String name) {
        return switch (name) {
            case "result.json" -> RepairArtifactType.RESULT_JSON;
            case "patch.diff" -> RepairArtifactType.PATCH_DIFF;
            case "test.log" -> RepairArtifactType.TEST_LOG;
            case "claude-events.jsonl" -> RepairArtifactType.CLAUDE_EVENTS;
            case "docker-meta.json" -> RepairArtifactType.DOCKER_METADATA;
            default -> RepairArtifactType.OTHER;
        };
    }

    private static String artifactSummary(String name) {
        return switch (artifactType(name)) {
            case RESULT_JSON -> "Structured Claude Code result.";
            case PATCH_DIFF -> "Patch diff produced by Claude Code.";
            case TEST_LOG -> "Test log produced by the execution container.";
            case CLAUDE_EVENTS -> "Claude Code event stream.";
            case DOCKER_METADATA -> "Docker execution metadata.";
            default -> "Output artifact produced by the execution container.";
        };
    }

    private static Map<String, String> artifactMetadata(Path path) {
        try {
            return Map.of("bytes", String.valueOf(Files.size(path)));
        } catch (IOException e) {
            return Map.of();
        }
    }

    private static Map<String, String> dockerMetadata(
            ContainerRunRequest request,
            ContainerRunResult result,
            List<RepairArtifact> artifacts
    ) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("image", request.image());
        metadata.put("containerName", request.containerName());
        metadata.put("commandJson", jsonArray(request.command()));
        metadata.put("exitCode", String.valueOf(result.exitCode()));
        metadata.put("durationMillis", String.valueOf(result.durationMillis()));
        metadata.put("networkMode", request.networkMode());
        metadata.put("removeAfterExit", String.valueOf(request.removeAfterExit()));
        metadata.put("allowPrivileged", String.valueOf(request.allowPrivileged()));
        metadata.put("outputArtifactPaths", outputArtifactPaths(artifacts));
        result.metadata().forEach((key, value) -> metadata.put("runner." + key, SecretRedactor.redactValue(key, value)));
        return metadata;
    }

    private static BigDecimal estimatedSpend(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return BigDecimal.ZERO;
        }
        String rawValue = metadata.getOrDefault("estimatedSpend", metadata.getOrDefault("estimatedSpendUsd", ""));
        if (rawValue.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(rawValue);
        } catch (NumberFormatException exception) {
            return BigDecimal.ZERO;
        }
    }

    private static String safeContainerName(String taskId) {
        String normalized = taskId == null ? "" : taskId.strip();
        String safeTaskId = normalized.replaceAll("[^A-Za-z0-9_.-]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        if (safeTaskId.isBlank()) {
            safeTaskId = "task";
        }
        return "rd-bot-repair-" + safeTaskId;
    }

    private static String jsonArray(List<String> values) {
        try {
            return OBJECT_MAPPER.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private static String jsonValue(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private static String outputArtifactPaths(List<RepairArtifact> artifacts) {
        return artifacts.stream()
                .map(RepairArtifact::uri)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private static Map<String, String> rawResultJson(StructuredRepairResult result) {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("status", result.status());
        raw.put("summary", result.summary());
        raw.put("prBody", result.prBody());
        raw.put("changedFiles", String.join(",", result.changedFiles()));
        return raw;
    }

    private static Map<String, String> testMetadataJson(StructuredRepairResult result) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("testStatus", result.testStatus());
        metadata.put("testCommands", String.join(",", result.testCommands()));
        return metadata;
    }

    private static Map<String, String> riskMetadataJson(StructuredRepairResult result) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("riskLevel", result.riskLevel());
        metadata.put("needHumanAction", String.valueOf(result.needHumanAction()));
        return metadata;
    }

    /**
     * Docker Claude Code 执行配置，镜像、命令和容器安全选项均由上游装配层传入。
     *
     * @param image            Docker 镜像名
     * @param command          容器内 Claude Code 命令，yolo 参数通过该列表表达
     * @param networkMode      Docker 网络模式
     * @param removeAfterExit  退出后是否删除容器
     * @param allowPrivileged  是否允许特权模式
     */
    public record Configuration(
            String image,
            List<String> command,
            String networkMode,
            boolean removeAfterExit,
            boolean allowPrivileged,
            List<ClaudeCodeModelProvider> providers
    ) {

        public Configuration {
            image = requireText(image, "image");
            command = requireCommand(command);
            networkMode = networkMode == null ? "" : networkMode.strip();
            providers = normalizeProviders(providers);
        }

        public Configuration(
                String image,
                List<String> command,
                String networkMode,
                boolean removeAfterExit,
                boolean allowPrivileged
        ) {
            this(
                    image,
                    command,
                    networkMode,
                    removeAfterExit,
                    allowPrivileged,
                    List.of(ClaudeCodeModelProvider.defaultAnthropic())
            );
        }

        /**
         * 返回可用于本地测试的默认配置。
         *
         * @return 默认 Docker Claude Code 配置
         */
        public static Configuration defaultConfiguration() {
            return new Configuration(
                    "claude-code:local",
                    List.of("claude", "--dangerously-skip-permissions"),
                    "none",
                    true,
                    false,
                    List.of(ClaudeCodeModelProvider.defaultAnthropic())
            );
        }

        private static String requireText(String value, String fieldName) {
            String normalized = value == null ? "" : value.strip();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return normalized;
        }

        private static List<String> requireCommand(List<String> source) {
            if (source == null || source.isEmpty()) {
                throw new IllegalArgumentException("command must not be empty");
            }
            List<String> normalized = source.stream()
                    .map(value -> value == null ? "" : value.strip())
                    .toList();
            if (normalized.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("command entries must not be blank");
            }
            return List.copyOf(normalized);
        }

        private static List<ClaudeCodeModelProvider> normalizeProviders(List<ClaudeCodeModelProvider> source) {
            if (source == null || source.isEmpty()) {
                return List.of(ClaudeCodeModelProvider.defaultAnthropic());
            }
            return List.copyOf(source);
        }
    }

    private record AttemptOutcome(RepairExecutionResult result) {
    }
}
