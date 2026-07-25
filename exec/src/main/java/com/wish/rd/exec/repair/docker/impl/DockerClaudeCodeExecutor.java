package com.wish.rd.exec.repair.docker.impl;

import com.wish.rd.exec.repair.docker.ContainerRunnerPort;
import com.wish.rd.exec.repair.docker.RepairWorkspaceFactory;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.alert.RepairExecutionWatchdog;
import com.wish.rd.exec.repair.alert.BudgetCurrencyConverter;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.model.ModelCircuitBreakerPolicy;
import com.wish.rd.exec.repair.model.ModelHealthSnapshot;
import com.wish.rd.exec.repair.health.ModelHealthStore;
import com.wish.rd.exec.repair.qa.model.QaExecutionProfile;
import com.wish.rd.exec.repair.qa.QaRepositoryProfileDetector;
import com.wish.rd.exec.repair.result.model.AgentRoleResultValidation;
import com.wish.rd.exec.repair.result.AgentRoleResultValidator;
import com.wish.rd.exec.repair.result.QaEvidenceBundleValidator;
import com.wish.rd.exec.repair.result.model.StructuredRepairResult;
import com.wish.rd.exec.repair.result.model.StructuredResultValidation;
import com.wish.rd.exec.repair.result.StructuredResultValidator;
import com.wish.rd.exec.repair.security.model.ExecutionAllowlistPolicy;
import com.wish.rd.exec.repair.security.SecretRedactor;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import com.wish.rd.exec.repair.docker.model.ClaudeCodeModelProvider;
import com.wish.rd.exec.repair.docker.model.ContainerRunRequest;
import com.wish.rd.exec.repair.docker.model.ContainerRunResult;
import com.wish.rd.exec.repair.docker.model.RepairWorkspace;
import com.wish.rd.exec.repair.docker.usage.ClaudeTokenUsageParser;
import com.wish.rd.exec.repair.docker.usage.model.ClaudeTokenUsageSnapshot;
import com.wish.rd.exec.repair.docker.trace.ClaudeExecutionTraceParser;

import java.util.concurrent.TimeUnit;

/**
 * Docker Claude Code 修复执行器，负责编排工作区、容器端口、输出产物收集和结构化结果校验。
 */
public class DockerClaudeCodeExecutor implements RepairExecutorPort {

    private static final String CONTAINER_REPO_DIRECTORY = "/work/repo";
    private static final String CONTAINER_INPUT_DIRECTORY = "/work/input";
    private static final String CONTAINER_OUTPUT_DIRECTORY = "/work/output";
    private static final String CONTAINER_CACHE_DIRECTORY = "/work/cache";
    private static final String CONTAINER_QA_SKILL_DIRECTORY = "/home/rdbot/.claude/skills/qa-playwright-cli:ro";
    private static final String CONTAINER_HANDOFF_SKILL_DIRECTORY = "/home/rdbot/.claude/skills/role-handoff-document:ro";
    private static final String AUTH_TOKEN_ENV_ROUTER = "RD_CLAUDE_AUTH_TOKEN_ENV";
    private static final String API_KEY_ENV_ROUTER = "RD_CLAUDE_API_KEY_ENV";
    private static final String AGENT_RESULT_JSON_FIELD = "__agentResultJson";
    private static final String LAUNCHCTL_BINARY = "/bin/launchctl";
    private static final long QA_EXECUTION_TIMEOUT_MILLIS = 1_200_000L;
    private static final long MAX_TEXT_PREVIEW_BYTES = 64_000L;
    private static final long MAX_QA_MANIFEST_PREVIEW_BYTES = 1_000_000L;
    private static final String QA_STARTUP_TIMEOUT_SECONDS = "120";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ClaudeTokenUsageParser TOKEN_USAGE_PARSER = new ClaudeTokenUsageParser();
    private static final ClaudeExecutionTraceParser EXECUTION_TRACE_PARSER = new ClaudeExecutionTraceParser();

    private final RepairWorkspaceFactory workspaceFactory;
    private final ContainerRunnerPort containerRunner;
    private final StructuredResultValidator resultValidator;
    private final AgentRoleResultValidator roleResultValidator;
    private final QaEvidenceBundleValidator qaEvidenceBundleValidator;
    private final QaRepositoryProfileDetector qaProfileDetector;
    private final Configuration configuration;
    private final RepairWorkspaceRepositoryPort workspaceRepository;
    private final RepairExecutionWatchdog watchdog;
    private final ModelHealthStore modelHealthStore;
    private final DockerExecutionRegistry executionRegistry;
    private final ExecutionAllowlistPolicy executionAllowlistPolicy;
    private final AuthEnvironmentResolver authEnvironmentResolver;
    private final BudgetCurrencyConverter budgetCurrencyConverter;

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
        this(
                workspaceFactory,
                containerRunner,
                resultValidator,
                configuration,
                workspaceRepository,
                watchdog,
                modelHealthStore,
                executionRegistry,
                executionAllowlistPolicy,
                AuthEnvironmentResolver.system(),
                new BudgetCurrencyConverter(BudgetCurrencyConverter.DEFAULT_CNY_PER_USD)
        );
    }

    public DockerClaudeCodeExecutor(
            RepairWorkspaceFactory workspaceFactory,
            ContainerRunnerPort containerRunner,
            StructuredResultValidator resultValidator,
            Configuration configuration,
            RepairWorkspaceRepositoryPort workspaceRepository,
            RepairExecutionWatchdog watchdog,
            ModelHealthStore modelHealthStore,
            DockerExecutionRegistry executionRegistry,
            ExecutionAllowlistPolicy executionAllowlistPolicy,
            BudgetCurrencyConverter budgetCurrencyConverter
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
                executionAllowlistPolicy,
                AuthEnvironmentResolver.system(),
                budgetCurrencyConverter
        );
    }

    /**
     * 创建可注入鉴权环境解析器的 Docker Claude Code 执行器，主要供本地启动兼容和单测使用。
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
     * @param authEnvironmentResolver  鉴权环境变量解析器
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
            ExecutionAllowlistPolicy executionAllowlistPolicy,
            AuthEnvironmentResolver authEnvironmentResolver
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
                executionAllowlistPolicy,
                authEnvironmentResolver,
                new BudgetCurrencyConverter(BudgetCurrencyConverter.DEFAULT_CNY_PER_USD)
        );
    }

    public DockerClaudeCodeExecutor(
            RepairWorkspaceFactory workspaceFactory,
            ContainerRunnerPort containerRunner,
            StructuredResultValidator resultValidator,
            Configuration configuration,
            RepairWorkspaceRepositoryPort workspaceRepository,
            RepairExecutionWatchdog watchdog,
            ModelHealthStore modelHealthStore,
            DockerExecutionRegistry executionRegistry,
            ExecutionAllowlistPolicy executionAllowlistPolicy,
            AuthEnvironmentResolver authEnvironmentResolver,
            BudgetCurrencyConverter budgetCurrencyConverter
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
        this.roleResultValidator = new AgentRoleResultValidator(OBJECT_MAPPER, this.resultValidator);
        this.qaEvidenceBundleValidator = new QaEvidenceBundleValidator();
        this.qaProfileDetector = new QaRepositoryProfileDetector();
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
        this.authEnvironmentResolver = authEnvironmentResolver == null
                ? AuthEnvironmentResolver.system()
                : authEnvironmentResolver;
        this.budgetCurrencyConverter = budgetCurrencyConverter == null
                ? new BudgetCurrencyConverter(BudgetCurrencyConverter.DEFAULT_CNY_PER_USD)
                : budgetCurrencyConverter;
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
            requireCandidatePatchApplication(command, repositoryMetadata);
            RepairWorkspaceRepositoryPort.RepositoryState qaRepositoryBaseline = qaRepositoryBaseline(command, workspace);
            QaExecutionProfile qaProfile = resolveQaProfile(command, workspace);
            List<ClaudeCodeModelProvider> providers = configuration.providers();
            for (int index = 0; index < providers.size(); index++) {
                ClaudeCodeModelProvider provider = providers.get(index);
                if (!modelHealthStore.allowCall(provider.name())) {
                    providerAttempts.add(skippedAttemptMetadata(provider, index + 1));
                    continue;
                }
                cleanOutputDirectory(workspace.outputDirectory());
                long startedAtEpochMillis = System.currentTimeMillis();
                AttemptOutcome outcome = runProvider(
                        command,
                        workspace,
                        provider,
                        index + 1,
                        providers.size(),
                        qaProfile
                );
                RepairExecutionResult guardedResult = enforceQaRepositoryUnchanged(
                        command, workspace, qaRepositoryBaseline, outcome.result());
                RepairExecutionResult providerResult = withRepositoryMetadata(guardedResult, repositoryMetadata);
                markProviderHealth(provider, providerResult);
                lastResult = providerResult;
                if (providerResult.status() == RepairExecutionStatus.SUCCESS) {
                    lastResult = publishRepositoryIfRequired(command, workspace, providerResult);
                }
                providerAttempts.add(attemptMetadata(
                        provider,
                        index + 1,
                        providerResult,
                        startedAtEpochMillis,
                        System.currentTimeMillis()
                ));
                if (!shouldFallback(lastResult) || index == providers.size() - 1) {
                    return withProviderMetadata(lastResult, provider, providerAttempts);
                }
            }
            if (lastResult == null && !providerAttempts.isEmpty()) {
                ClaudeCodeModelProvider provider = providers.getFirst();
                int attempt = providerAttempts.size() + 1;
                cleanOutputDirectory(workspace.outputDirectory());
                long startedAtEpochMillis = System.currentTimeMillis();
                AttemptOutcome outcome = runProvider(command, workspace, provider, attempt, providers.size(), qaProfile);
                RepairExecutionResult guardedResult = enforceQaRepositoryUnchanged(
                        command, workspace, qaRepositoryBaseline, outcome.result());
                RepairExecutionResult providerResult = withRepositoryMetadata(guardedResult, repositoryMetadata);
                markProviderHealth(provider, providerResult);
                lastResult = providerResult;
                if (providerResult.status() == RepairExecutionStatus.SUCCESS) {
                    lastResult = publishRepositoryIfRequired(command, workspace, providerResult);
                }
                providerAttempts.add(attemptMetadata(
                        provider,
                        attempt,
                        providerResult,
                        startedAtEpochMillis,
                        System.currentTimeMillis()
                ));
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

    /**
     * A local QA stage must never silently fall back to a clean checkout. The candidate patch is the
     * subject under test, so a repository adapter that cannot prove it staged the patch is a hard
     * infrastructure failure before Claude Code receives provider credentials or starts a container.
     */
    private static void requireCandidatePatchApplication(
            RepairJobCommand command,
            Map<String, String> repositoryMetadata
    ) throws IOException {
        if (!Boolean.parseBoolean(command.policyJson().getOrDefault("applyCandidatePatch", "false"))) {
            return;
        }
        String applied = repositoryMetadata == null ? "" : repositoryMetadata.getOrDefault("candidatePatchApplied", "");
        if (!"true".equalsIgnoreCase(applied)) {
            throw new IOException("local QA requires the Coding candidate patch to be applied in its isolated checkout");
        }
    }

    private RepairExecutionResult enforceQaRepositoryUnchanged(
            RepairJobCommand command,
            RepairWorkspace workspace,
            RepairWorkspaceRepositoryPort.RepositoryState baseline,
            RepairExecutionResult result
    ) throws IOException {
        if (!"QA_AGENT".equals(agentRole(command))) {
            return result;
        }
        RepairWorkspaceRepositoryPort.RepositoryState state = workspaceRepository.repositoryState(command, workspace);
        if (!state.supported() || baseline == null || !baseline.supported()) {
            return result;
        }
        boolean comparable = !baseline.fingerprint().isBlank() && !state.fingerprint().isBlank();
        if ((comparable && baseline.fingerprint().equals(state.fingerprint()))
                || (!comparable && baseline.clean() && state.clean())) {
            return result;
        }
        String details = qaRepositoryStateDetails(baseline, state);
        String error = "QA left repository changes"
                + (details.isBlank() ? "" : ": " + details);
        if (result != null && result.status() != RepairExecutionStatus.SUCCESS) {
            Map<String, String> dockerMetadata = new LinkedHashMap<>(result.dockerMetadataJson());
            dockerMetadata.put("qaRepositoryClean", "false");
            dockerMetadata.put("qaRepositoryState", details);
            String primaryError = result.errorMessage() == null ? "" : result.errorMessage().strip();
            String combinedError = primaryError.isBlank()
                    ? error
                    : primaryError + "; secondary QA cleanup failure: " + error;
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
                    combinedError
            );
        }
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put(AGENT_RESULT_JSON_FIELD, qaRepositoryMutationResultJson(error));
        raw.put("failureCategory", "QA_INFRASTRUCTURE");
        raw.put("retryRecommendation", "HUMAN");
        return new RepairExecutionResult(
                RepairExecutionStatus.FAILED,
                "QA repository mutation guard failed",
                "",
                result == null ? List.of() : result.artifacts(),
                raw,
                result == null ? Map.of() : result.dockerMetadataJson(),
                result == null ? Map.of() : result.githubMetadataJson(),
                result == null ? Map.of() : result.testMetadataJson(),
                result == null ? Map.of() : result.riskMetadataJson(),
                error
        );
    }

    private RepairWorkspaceRepositoryPort.RepositoryState qaRepositoryBaseline(
            RepairJobCommand command,
            RepairWorkspace workspace
    ) throws IOException {
        if (!"QA_AGENT".equals(agentRole(command))) {
            return RepairWorkspaceRepositoryPort.RepositoryState.unsupported();
        }
        return workspaceRepository.repositoryState(command, workspace);
    }

    private static String qaRepositoryStateDetails(
            RepairWorkspaceRepositoryPort.RepositoryState baseline,
            RepairWorkspaceRepositoryPort.RepositoryState current
    ) {
        String baselineSummary = abbreviateRepositoryState(baseline.summary());
        String currentSummary = abbreviateRepositoryState(current.summary());
        if (baselineSummary.isBlank()) {
            return currentSummary;
        }
        if (currentSummary.isBlank()) {
            return "baseline=" + baselineSummary + "; current=clean";
        }
        return "baseline=" + baselineSummary + "; current=" + currentSummary;
    }

    private static String abbreviateRepositoryState(String value) {
        String normalized = value == null ? "" : value.strip();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private static String qaRepositoryMutationResultJson(String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "FAILED");
        result.put("summary", error);
        result.put("failureCategory", "QA_INFRASTRUCTURE");
        result.put("retryRecommendation", "HUMAN");
        return jsonValue(result);
    }

    private RepairExecutionResult publishRepositoryIfRequired(
            RepairJobCommand command,
            RepairWorkspace workspace,
            RepairExecutionResult result
    ) {
        if (!repositoryPublishRequired(command)) {
            return withDockerMetadata(result, Map.of(
                    "repositoryPublishSkipped", "true",
                    "repositoryPublishRequired", "false"
            ));
        }
        return publishRepository(command, workspace, result);
    }

    private boolean repositoryPublishRequired(RepairJobCommand command) {
        if (command == null || command.policyJson() == null) {
            return false;
        }
        String value = firstNonBlank(
                command.policyJson().get("repositoryPublishRequired"),
                command.policyJson().get("publishRepository")
        );
        return Boolean.parseBoolean(value);
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

    private static RepairExecutionResult withDockerMetadata(
            RepairExecutionResult result,
            Map<String, String> metadata
    ) {
        if (metadata == null || metadata.isEmpty()) {
            return result;
        }
        Map<String, String> dockerMetadata = new LinkedHashMap<>(result.dockerMetadataJson());
        dockerMetadata.putAll(metadata);
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

    private AttemptOutcome runProvider(
            RepairJobCommand command,
            RepairWorkspace workspace,
            ClaudeCodeModelProvider provider,
            int attempt,
            int providerCount,
            QaExecutionProfile qaProfile
    ) {
        Map<String, String> routedAuthEnv = routedAuthEnvValues(provider.env());
        RepairExecutionResult validationResult = validateProviderRuntimeEnvironment(provider, routedAuthEnv);
        if (validationResult != null) {
            return new AttemptOutcome(validationResult);
        }
        try {
            ContainerRunRequest request = toRunRequest(
                    command,
                    workspace,
                    provider,
                    attempt,
                    providerCount,
                    routedAuthEnv,
                    qaProfile
            );
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
            ClaudeTokenUsageSnapshot tokenUsage = TOKEN_USAGE_PARSER.parse(runResult.claudeEventsJsonl());
            evaluateWatchdog(command, runResult, tokenUsage);
            List<RepairArtifact> artifacts = collectArtifacts(workspace.outputDirectory());
            Map<String, String> dockerMetadata = dockerMetadata(request, runResult, artifacts, tokenUsage);
            return new AttemptOutcome(toExecutionResult(
                    command,
                    runResult,
                    artifacts,
                    dockerMetadata,
                    qaProfile
            ));
        } catch (IOException exception) {
            return new AttemptOutcome(failedExecution(exception.getMessage()));
        }
    }

    private RepairExecutionResult validateProviderRuntimeEnvironment(
            ClaudeCodeModelProvider provider,
            Map<String, String> routedAuthEnv
    ) {
        List<String> missingEnvNames = missingRoutedAuthEnvNames(provider.env(), routedAuthEnv);
        if (missingEnvNames.isEmpty()) {
            return null;
        }
        String message = "provider " + provider.name()
                + " is missing required auth environment variable(s): "
                + String.join(", ", missingEnvNames)
                + ". Set them on the RD-Bot process and restart before running Docker Claude Code.";
        return failedProviderValidation(message, missingEnvNames);
    }

    private Map<String, String> routedAuthEnvValues(Map<String, String> env) {
        Map<String, String> values = new LinkedHashMap<>();
        collectRoutedAuthEnvValue(values, env, AUTH_TOKEN_ENV_ROUTER);
        collectRoutedAuthEnvValue(values, env, API_KEY_ENV_ROUTER);
        return Map.copyOf(values);
    }

    private void collectRoutedAuthEnvValue(
            Map<String, String> values,
            Map<String, String> env,
            String routerKey
    ) {
        String routedEnvName = normalizeEnvText(env.get(routerKey));
        if (routedEnvName.isBlank() || values.containsKey(routedEnvName)) {
            return;
        }
        values.put(routedEnvName, normalizeEnvText(authEnvironmentResolver.resolve(routedEnvName)));
    }

    private static List<String> missingRoutedAuthEnvNames(Map<String, String> env, Map<String, String> routedAuthEnv) {
        List<String> missingEnvNames = new ArrayList<>();
        collectMissingRoutedAuthEnvName(missingEnvNames, env, routedAuthEnv, AUTH_TOKEN_ENV_ROUTER);
        collectMissingRoutedAuthEnvName(missingEnvNames, env, routedAuthEnv, API_KEY_ENV_ROUTER);
        return List.copyOf(missingEnvNames);
    }

    private static void collectMissingRoutedAuthEnvName(
            List<String> missingEnvNames,
            Map<String, String> env,
            Map<String, String> routedAuthEnv,
            String routerKey
    ) {
        String routedEnvName = normalizeEnvText(env.get(routerKey));
        if (routedEnvName.isBlank()) {
            return;
        }
        String hostValue = normalizeEnvText(routedAuthEnv.get(routedEnvName));
        if (hostValue.isBlank() && !missingEnvNames.contains(routedEnvName)) {
            missingEnvNames.add(routedEnvName);
        }
    }

    private static String normalizeEnvText(String value) {
        return value == null ? "" : value.strip();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = normalizeEnvText(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private void evaluateWatchdog(
            RepairJobCommand command,
            ContainerRunResult runResult,
            ClaudeTokenUsageSnapshot tokenUsage
    ) {
        if (watchdog == null) {
            return;
        }
        watchdog.evaluate(
                command.repairRecordId(),
                command.taskId(),
                runResult.durationMillis(),
                budgetCurrencyConverter.usdToCny(tokenUsage.available()
                        ? tokenUsage.estimatedCostUsd()
                        : estimatedSpend(runResult.metadata())),
                System.currentTimeMillis()
        );
    }

    private ContainerRunRequest toRunRequest(
            RepairJobCommand command,
            RepairWorkspace workspace,
            ClaudeCodeModelProvider provider,
            int attempt,
            int providerCount,
            Map<String, String> routedAuthEnv,
            QaExecutionProfile qaProfile
    ) {
        String role = agentRole(command);
        boolean qaExecution = "QA_AGENT".equals(role);
        boolean handoffExecution = isHandoffExecution(role);
        Map<String, String> mounts = new LinkedHashMap<>();
        mounts.put(workspace.repoDirectory().toString(), CONTAINER_REPO_DIRECTORY);
        mounts.put(workspace.inputDirectory().toString(), CONTAINER_INPUT_DIRECTORY);
        mounts.put(workspace.outputDirectory().toString(), CONTAINER_OUTPUT_DIRECTORY);
        mounts.put(workspace.cacheDirectory().toString(), CONTAINER_CACHE_DIRECTORY);
        QaSkillConfiguration qaSkill = configuration.qaSkill();
        if (qaExecution && qaSkill.enabled()) {
            mounts.put(qaSkill.hostPath(), CONTAINER_QA_SKILL_DIRECTORY);
        }
        HandoffSkillConfiguration handoffSkill = configuration.handoffSkill();
        if (handoffExecution && handoffSkill.enabled()) {
            mounts.put(handoffSkill.hostPath(), CONTAINER_HANDOFF_SKILL_DIRECTORY);
        }
        Map<String, String> env = new LinkedHashMap<>(provider.env());
        // 跨 attempt 持久的包管理器缓存：同任务重试不再全量重新下载依赖（审查报告 F3）。
        env.put("npm_config_cache", CONTAINER_CACHE_DIRECTORY + "/npm");
        env.put("PIP_CACHE_DIR", CONTAINER_CACHE_DIRECTORY + "/pip");
        env.put("YARN_CACHE_FOLDER", CONTAINER_CACHE_DIRECTORY + "/yarn");
        if (routedAuthEnv != null) {
            routedAuthEnv.forEach((key, value) -> {
                if (!normalizeEnvText(value).isBlank()) {
                    env.put(key, value);
                }
            });
        }
        env.putIfAbsent("RD_CLAUDE_PROVIDER_NAME", provider.name());
        env.put("RD_CLAUDE_PROVIDER_ATTEMPT", String.valueOf(attempt));
        if (!role.isBlank()) {
            env.put("RD_AGENT_ROLE", role);
        }
        if (qaExecution) {
            env.put("RD_QA_STARTUP_TIMEOUT_SECONDS", QA_STARTUP_TIMEOUT_SECONDS);
            env.put("RD_QA_COMMAND_TIMEOUT_MILLIS", String.valueOf(QA_EXECUTION_TIMEOUT_MILLIS));
            env.put("PLAYWRIGHT_MCP_OUTPUT_DIR", "/work/output/qa-work/playwright");
            env.put("PLAYWRIGHT_MCP_OUTPUT_MODE", "stdout");
        }
        if (qaExecution && qaSkill.enabled()) {
            env.put("RD_QA_SKILL_ID", qaSkill.skillId());
            env.put("RD_QA_SKILL_VERSION", qaSkill.version());
            env.put("RD_QA_SKILL_CHECKSUM", qaSkill.checksum());
            env.put("RD_QA_SKILL_POLICY_JSON", qaSkill.policyJson());
        }
        if (handoffExecution && handoffSkill.enabled()) {
            env.put("RD_HANDOFF_SKILL_ID", handoffSkill.skillId());
            env.put("RD_HANDOFF_SKILL_VERSION", handoffSkill.version());
            env.put("RD_HANDOFF_SKILL_CHECKSUM", handoffSkill.checksum());
            env.put("RD_HANDOFF_SKILL_POLICY_JSON", handoffSkill.policyJson());
        }
        if (qaExecution && qaProfile != null) {
            env.put("RD_QA_PROFILE_FILE", "/work/input/qa-profile.json");
            env.put("RD_QA_BASE_URL", qaProfile.baseUrl());
            env.put("RD_QA_START_COMMAND", qaProfile.startCommand());
            env.put("RD_QA_HEALTH_PATH", qaProfile.healthPath());
            env.put("RD_QA_ALLOWED_HOSTS", String.join(",", qaProfile.allowedHosts()));
            env.put("RD_QA_REGRESSION_COMMANDS_JSON", jsonValue(qaProfile.regressionCommands()));
            env.put("RD_QA_DECISION_SOURCE", qaProfile.decisionSource());
            env.put("RD_QA_PROFILE_AMBIGUOUS", Boolean.toString(qaProfile.ambiguous()));
            if (!qaProfile.baseUrl().isBlank()) {
                env.put("PLAYWRIGHT_MCP_ALLOWED_ORIGINS", playwrightAllowedOrigins(qaProfile));
            }
        }
        return new ContainerRunRequest(
                providerCount <= 1
                        ? safeContainerName(command.taskId())
                        : safeContainerName(command.taskId() + "-" + provider.name() + "-" + attempt),
                runtimeImage(command, qaExecution),
                configuration.command(),
                env,
                mounts,
                CONTAINER_REPO_DIRECTORY,
                configuration.networkMode(),
                configuration.removeAfterExit(),
                configuration.allowPrivileged(),
                workspace.outputDirectory(),
                qaExecution,
                qaExecution ? "1g" : "",
                qaExecution ? QA_EXECUTION_TIMEOUT_MILLIS : 0L
        );
    }

    private String runtimeImage(RepairJobCommand command, boolean qaExecution) {
        if (command != null
                && "true".equalsIgnoreCase(normalizeEnvText(command.policyJson().get("runtimeImageVerified")))
                && "CLAUDE_CODE".equalsIgnoreCase(normalizeEnvText(command.policyJson().get("runtimeAgentType")))) {
            String candidate = normalizeEnvText(command.policyJson().get("runtimeImage"));
            if (isSafeImageReference(candidate)) {
                return candidate;
            }
        }
        return qaExecution ? configuration.qaImage() : configuration.image();
    }

    private static boolean isSafeImageReference(String value) {
        return value != null
                && value.matches("[A-Za-z0-9][A-Za-z0-9._/-]*(?::[A-Za-z0-9][A-Za-z0-9._-]*)?(?:@[A-Za-z0-9:+._-]+)?");
    }

    private QaExecutionProfile resolveQaProfile(
            RepairJobCommand command,
            RepairWorkspace workspace
    ) throws IOException {
        if (!"QA_AGENT".equals(agentRole(command))) {
            return null;
        }
        QaExecutionProfile profile = qaProfileDetector.detect(command, workspace.repoDirectory());
        Files.writeString(
                workspace.inputDirectory().resolve("qa-profile.json"),
                qaProfileDetector.toJson(profile),
                StandardCharsets.UTF_8
        );
        return profile;
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

    private static String playwrightAllowedOrigins(QaExecutionProfile profile) {
        java.util.LinkedHashSet<String> origins = new java.util.LinkedHashSet<>();
        try {
            URI uri = URI.create(profile.baseUrl());
            String origin = uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
            origins.add(origin);
        } catch (IllegalArgumentException ignored) {
            // Invalid explicit profiles are already marked ambiguous by the detector.
        }
        profile.allowedHosts().forEach(host -> {
            origins.add("http://" + host + ":*");
            origins.add("https://" + host + ":*");
        });
        return String.join(";", origins);
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
            RepairExecutionResult result,
            long startedAtEpochMillis,
            long finishedAtEpochMillis
    ) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("provider", provider.name());
        metadata.put("attempt", String.valueOf(attempt));
        metadata.put("status", result == null ? "FAILED" : result.status().name());
        metadata.put("startedAtEpochMillis", String.valueOf(startedAtEpochMillis));
        metadata.put("finishedAtEpochMillis", String.valueOf(Math.max(startedAtEpochMillis, finishedAtEpochMillis)));
        metadata.put("errorMessage", result == null ? "" : SecretRedactor.redactFreeform(result.errorMessage()));
        if (result == null || result.status() != RepairExecutionStatus.SUCCESS) {
            metadata.put("errorCategory", result == null ? "EXECUTION_FAILED" : result.status().name());
        }
        if (result != null) {
            copyTokenUsageMetadata(metadata, result.dockerMetadataJson());
        }
        putHealthMetadata(metadata, provider.name(), modelHealthStore.snapshot(provider.name()));
        return Map.copyOf(metadata);
    }

    private Map<String, String> skippedAttemptMetadata(ClaudeCodeModelProvider provider, int attempt) {
        long occurredAtEpochMillis = System.currentTimeMillis();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("provider", provider.name());
        metadata.put("attempt", String.valueOf(attempt));
        metadata.put("status", "SKIPPED_CIRCUIT_OPEN");
        metadata.put("startedAtEpochMillis", String.valueOf(occurredAtEpochMillis));
        metadata.put("finishedAtEpochMillis", String.valueOf(occurredAtEpochMillis));
        metadata.put("errorCategory", "CIRCUIT_OPEN");
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
        try (Stream<Path> paths = Files.walk(outputDirectory)) {
            for (Path path : paths
                    .filter(candidate -> !candidate.equals(outputDirectory))
                    .sorted(Comparator.reverseOrder())
                    .toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private RepairExecutionResult toExecutionResult(
            RepairJobCommand command,
            ContainerRunResult runResult,
            List<RepairArtifact> artifacts,
            Map<String, String> dockerMetadata,
            QaExecutionProfile qaProfile
    ) throws IOException {
        String agentRole = agentRole(command);
        if (usesAgentRoleProtocol(agentRole) && runResult.exitCode() != 0) {
            return failedAgentRoleContainerExecution(agentRole, runResult, artifacts, dockerMetadata);
        }
        Path resultJson = runResult.resultJson();
        if (resultJson == null || !Files.isRegularFile(resultJson)) {
            return failedValidation("result.json is missing", artifacts, dockerMetadata);
        }

        String rawJson = Files.readString(resultJson, StandardCharsets.UTF_8);
        if (usesAgentRoleProtocol(agentRole)) {
            return toAgentRoleExecutionResult(
                    command,
                    agentRole,
                    rawJson,
                    runResult,
                    artifacts,
                    dockerMetadata,
                    qaProfile
            );
        }
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

    private RepairExecutionResult toAgentRoleExecutionResult(
            RepairJobCommand command,
            String agentRole,
            String rawJson,
            ContainerRunResult runResult,
            List<RepairArtifact> artifacts,
            Map<String, String> dockerMetadata,
            QaExecutionProfile qaProfile
    ) {
        if (runResult.exitCode() != 0) {
            return failedAgentRoleContainerExecution(agentRole, runResult, artifacts, dockerMetadata);
        }
        AgentRoleResultValidation validation = roleResultValidator.validate(agentRole, rawJson);
        if (!validation.valid()) {
            return failedValidation(String.join("; ", validation.errors()), artifacts, dockerMetadata);
        }
        if ("QA_AGENT".equals(agentRole)) {
            List<String> profileErrors = validateResolvedQaProfile(rawJson, qaProfile);
            if (!profileErrors.isEmpty()) {
                return failedValidation(String.join("; ", profileErrors), artifacts, dockerMetadata);
            }
            AgentRoleResultValidation evidenceValidation = qaEvidenceBundleValidator.validate(
                    rawJson,
                    artifacts,
                    taskAcceptanceCriteria(command)
            );
            if (!evidenceValidation.valid()) {
                return failedValidation(
                        String.join("; ", evidenceValidation.errors()),
                        artifacts,
                        dockerMetadata
                );
            }
        }

        RepairExecutionStatus status = agentRoleStatus(agentRole, rawJson);
        String summary = jsonText(rawJson, "summary", "Docker Claude Code role result.");
        String errorMessage = "";
        if ("QA_AGENT".equals(agentRole) && status == RepairExecutionStatus.FAILED && errorMessage.isBlank()) {
            errorMessage = "QA_AGENT failed acceptance: " + summary;
        }

        return new RepairExecutionResult(
                status,
                summary,
                "",
                artifacts,
                roleRawResultJson(rawJson),
                dockerMetadata,
                Map.of(),
                Map.of(),
                Map.of(
                        "riskLevel", "LOW",
                        "needHumanAction", String.valueOf(status != RepairExecutionStatus.SUCCESS)
                ),
                errorMessage
        );
    }

    private static RepairExecutionResult failedAgentRoleContainerExecution(
            String agentRole,
            ContainerRunResult runResult,
            List<RepairArtifact> artifacts,
            Map<String, String> dockerMetadata
    ) {
        String normalizedRole = agentRole == null ? "" : agentRole.strip();
        String summary = normalizedRole.isBlank()
                ? "Docker Claude Code role execution failed."
                : normalizedRole + " container execution failed.";
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("status", "FAILED");
        raw.put("summary", summary);
        if ("QA_AGENT".equals(normalizedRole)) {
            raw.put("failureCategory", "QA_INFRASTRUCTURE");
            raw.put("retryRecommendation", "HUMAN");
        }
        return new RepairExecutionResult(
                RepairExecutionStatus.FAILED,
                summary,
                "",
                artifacts,
                raw,
                dockerMetadata,
                Map.of(),
                Map.of(),
                Map.of(
                        "riskLevel", "HIGH",
                        "needHumanAction", "true"
                ),
                containerFailureMessage(runResult)
        );
    }

    private static List<String> taskAcceptanceCriteria(RepairJobCommand command) {
        String rawCriteria = command == null
                ? ""
                : command.contextJson().getOrDefault("acceptanceCriteriaJson", "");
        if (rawCriteria.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(rawCriteria);
            if (root == null || !root.isArray()) {
                return List.of();
            }
            List<String> criteria = new ArrayList<>();
            root.forEach(item -> {
                String criterion = item.asText("").strip();
                if (!criterion.isBlank()) {
                    criteria.add(criterion);
                }
            });
            return List.copyOf(criteria);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private static List<String> validateResolvedQaProfile(String rawJson, QaExecutionProfile profile) {
        if (profile == null) {
            return List.of("resolved QA profile is missing");
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(rawJson == null ? "{}" : rawJson);
            JsonNode browser = root.path("browserValidation");
            List<String> errors = new ArrayList<>();
            boolean reportedRequired = browser.path("required").asBoolean(false);
            boolean reportedPerformed = browser.path("performed").asBoolean(false);
            if (profile.browserRequired() && !reportedRequired) {
                errors.add("resolved QA profile requires browser validation");
            }
            if (!profile.browserRequired() && reportedRequired) {
                errors.add("QA result reports browser validation contrary to the resolved non-web profile");
            }
            String reportedSource = browser.path("decisionSource").asText("").strip();
            if (!profile.decisionSource().equals(reportedSource)) {
                errors.add("browserValidation.decisionSource must match resolved QA profile: "
                        + profile.decisionSource());
            }
            if (profile.browserRequired() && reportedPerformed
                    && !profile.baseUrl().equals(browser.path("baseUrl").asText("").strip())) {
                errors.add("browserValidation.baseUrl must match resolved QA profile");
            }
            if (profile.ambiguous()) {
                if (!"FAILED".equals(root.path("status").asText("").strip().toUpperCase(java.util.Locale.ROOT))) {
                    errors.add("ambiguous QA profile must block delivery");
                }
                if (!"REQUIREMENT_AMBIGUITY".equals(root.path("failureCategory").asText("").strip())) {
                    errors.add("ambiguous QA profile requires failureCategory REQUIREMENT_AMBIGUITY");
                }
                if (!"HUMAN".equals(root.path("retryRecommendation").asText("").strip())) {
                    errors.add("ambiguous QA profile requires retryRecommendation HUMAN");
                }
            }
            return List.copyOf(errors);
        } catch (JsonProcessingException exception) {
            return List.of("QA result cannot be matched to resolved QA profile");
        }
    }

    private static String containerFailureMessage(ContainerRunResult runResult) {
        String providerError = claudeApiError(runResult.claudeEventsJsonl());
        String containerExit = "container exited with code " + runResult.exitCode();
        String containerFailure = Boolean.parseBoolean(runResult.metadata().getOrDefault("timedOut", "false"))
                ? "container timed out after " + runResult.durationMillis() + " ms (" + containerExit + ")"
                : containerExit;
        String runtimeError = claudeRuntimeError(runResult.claudeEventsJsonl());
        String processError = safeProcessError(runResult.stderr());
        String detail = !providerError.isBlank()
                ? providerError
                : (!runtimeError.isBlank() ? runtimeError : processError);
        if (detail.isBlank()) {
            return containerFailure;
        }
        return detail + " (" + containerFailure + ")";
    }

    private static String claudeRuntimeError(Path claudeEventsJsonl) {
        if (claudeEventsJsonl == null || !Files.isRegularFile(claudeEventsJsonl)) {
            return "";
        }
        String lastError = "";
        try {
            for (String line : Files.readAllLines(claudeEventsJsonl, StandardCharsets.UTF_8)) {
                String normalized = line == null ? "" : line.strip();
                if (normalized.contains("Argument list too long")
                        || normalized.contains("Permission denied")
                        || normalized.contains("No such file or directory")
                        || normalized.contains("command not found")
                        || normalized.contains("missing required prompt file")
                        || normalized.contains("missing required schema file")) {
                    lastError = SecretRedactor.redactFreeform(limitErrorText(normalized));
                }
            }
        } catch (IOException exception) {
            return "";
        }
        return lastError;
    }

    private static String safeProcessError(String stderr) {
        String normalized = stderr == null ? "" : stderr.strip();
        return normalized.isBlank()
                ? ""
                : SecretRedactor.redactFreeform(limitErrorText(normalized));
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

    private static String agentRole(RepairJobCommand command) {
        if (command == null || command.contextJson() == null) {
            return "";
        }
        return normalizeEnvText(command.contextJson().get("agentRole")).toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean usesAgentRoleProtocol(String agentRole) {
        return "REQUIREMENT_REVIEWER".equals(agentRole)
                || "SOLUTION_ARCHITECT".equals(agentRole)
                || "QA_AGENT".equals(agentRole);
    }

    private static boolean isHandoffExecution(String role) {
        return "REQUIREMENT_REVIEWER".equals(role)
                || "SOLUTION_ARCHITECT".equals(role)
                || "CODING_AGENT".equals(role);
    }

    private static RepairExecutionStatus agentRoleStatus(String agentRole, String rawJson) {
        if (!"QA_AGENT".equals(agentRole)) {
            return RepairExecutionStatus.SUCCESS;
        }
        String status = jsonText(rawJson, "status", "").toUpperCase(java.util.Locale.ROOT);
        if ("PASSED".equals(status)) {
            return RepairExecutionStatus.SUCCESS;
        }
        return RepairExecutionStatus.FAILED;
    }

    private static Map<String, String> roleRawResultJson(String rawJson) {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("status", jsonText(rawJson, "status", "SUCCESS"));
        raw.put("summary", jsonText(rawJson, "summary", ""));
        raw.put("prBody", jsonText(rawJson, "prBody", ""));
        raw.put(AGENT_RESULT_JSON_FIELD, rawJson == null ? "" : rawJson);
        return raw;
    }

    private static String jsonText(String rawJson, String fieldName, String fallback) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(rawJson == null ? "{}" : rawJson);
            String value = root.path(fieldName).asText("");
            return value.isBlank() ? fallback : value;
        } catch (JsonProcessingException exception) {
            return fallback;
        }
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
        try (Stream<Path> files = Files.walk(outputDirectory)) {
            return files
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !artifactName(outputDirectory, path).startsWith("qa-work/"))
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
                artifactSummary(name),
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
            case "claude-events.jsonl" -> RepairArtifactType.CLAUDE_EVENTS;
            case "docker-meta.json" -> RepairArtifactType.DOCKER_METADATA;
            case "qa-evidence/manifest.json" -> RepairArtifactType.QA_EVIDENCE_MANIFEST;
            case "handoff/next.md" -> RepairArtifactType.HANDOFF_MARKDOWN;
            default -> qaArtifactType(normalized);
        };
    }

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

    private static String artifactSummary(String name) {
        return switch (artifactType(name)) {
            case RESULT_JSON -> "Structured Claude Code result.";
            case PATCH_DIFF -> "Patch diff produced by Claude Code.";
            case TEST_LOG -> "Test log produced by the execution container.";
            case CLAUDE_EVENTS -> "Claude Code event stream.";
            case DOCKER_METADATA -> "Docker execution metadata.";
            case QA_COMMAND_LOG -> "QA command transcript with exit status.";
            case QA_SCREENSHOT -> "QA browser screenshot evidence.";
            case QA_TRACE -> "QA Playwright trace evidence.";
            case QA_CONSOLE_LOG -> "QA browser console evidence.";
            case QA_NETWORK_LOG -> "QA browser network evidence.";
            case QA_HTTP_TRANSCRIPT -> "QA real HTTP request transcript.";
            case QA_VIDEO -> "QA browser failure video evidence.";
            case QA_EVIDENCE_MANIFEST -> "QA evidence integrity manifest.";
            case HANDOFF_MARKDOWN -> "Markdown handoff for the direct downstream role.";
            default -> "Output artifact produced by the execution container.";
        };
    }

    private static Map<String, String> artifactMetadata(Path path) {
        try {
            Map<String, String> metadata = new LinkedHashMap<>();
            long size = Files.size(path);
            metadata.put("bytes", String.valueOf(size));
            metadata.put("sha256", sha256(path));
            metadata.put("contentType", contentType(path));
            if (isClaudeEventStream(path)) {
                metadata.put("contentPreview", EXECUTION_TRACE_PARSER.persistedSnapshot(path));
                return Map.copyOf(metadata);
            }
            long previewLimit = isQaEvidenceManifest(path)
                    ? MAX_QA_MANIFEST_PREVIEW_BYTES
                    : MAX_TEXT_PREVIEW_BYTES;
            if (isTextArtifact(path) && size <= previewLimit) {
                metadata.put("contentPreview", SecretRedactor.redactFreeform(
                        Files.readString(path, StandardCharsets.UTF_8)
                ));
            }
            return Map.copyOf(metadata);
        } catch (IOException e) {
            return Map.of();
        }
    }

    private static boolean isQaEvidenceManifest(Path path) {
        String normalized = path == null ? "" : path.toString().replace('\\', '/');
        return normalized.endsWith("/qa-evidence/manifest.json");
    }

    private static boolean isClaudeEventStream(Path path) {
        return path != null && "claude-events.jsonl".equals(path.getFileName().toString());
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String contentType(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
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

    private static boolean isTextArtifact(Path path) {
        String contentType = contentType(path);
        return contentType.startsWith("text/")
                || "application/json".equals(contentType)
                || "application/x-ndjson".equals(contentType);
    }

    private static Map<String, String> dockerMetadata(
            ContainerRunRequest request,
            ContainerRunResult result,
            List<RepairArtifact> artifacts,
            ClaudeTokenUsageSnapshot tokenUsage
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
        metadata.put("initEnabled", String.valueOf(request.initEnabled()));
        metadata.put("sharedMemorySize", request.sharedMemorySize());
        metadata.put("qaSkillId", request.env().getOrDefault("RD_QA_SKILL_ID", ""));
        metadata.put("qaSkillVersion", request.env().getOrDefault("RD_QA_SKILL_VERSION", ""));
        metadata.put("qaSkillChecksum", request.env().getOrDefault("RD_QA_SKILL_CHECKSUM", ""));
        metadata.put("qaSkillPolicyJson", request.env().getOrDefault("RD_QA_SKILL_POLICY_JSON", ""));
        metadata.put("qaSkillRole", request.env().getOrDefault("RD_AGENT_ROLE", ""));
        metadata.put("qaSkillSource", "classpath:skills/qa-playwright-cli/SKILL.md");
        metadata.put("qaSkillInstallPath", request.mounts().entrySet().stream()
                .filter(entry -> CONTAINER_QA_SKILL_DIRECTORY.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(""));
        metadata.put("handoffSkillId", request.env().getOrDefault("RD_HANDOFF_SKILL_ID", ""));
        metadata.put("handoffSkillVersion", request.env().getOrDefault("RD_HANDOFF_SKILL_VERSION", ""));
        metadata.put("handoffSkillChecksum", request.env().getOrDefault("RD_HANDOFF_SKILL_CHECKSUM", ""));
        metadata.put("handoffSkillPolicyJson", request.env().getOrDefault("RD_HANDOFF_SKILL_POLICY_JSON", ""));
        metadata.put("handoffSkillRole", request.env().getOrDefault("RD_AGENT_ROLE", ""));
        metadata.put("handoffSkillSource", "classpath:skills/role-handoff-document/SKILL.md");
        metadata.put("handoffSkillInstallPath", request.mounts().entrySet().stream()
                .filter(entry -> CONTAINER_HANDOFF_SKILL_DIRECTORY.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(""));
        metadata.put("outputArtifactPaths", outputArtifactPaths(artifacts));
        putTokenUsageMetadata(metadata, tokenUsage);
        result.metadata().forEach((key, value) -> metadata.put("runner." + key, SecretRedactor.redactValue(key, value)));
        return metadata;
    }

    private static void putTokenUsageMetadata(Map<String, String> metadata, ClaudeTokenUsageSnapshot tokenUsage) {
        ClaudeTokenUsageSnapshot usage = tokenUsage == null
                ? new ClaudeTokenUsageSnapshot(0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO, "", 0, false)
                : tokenUsage;
        metadata.put("tokenUsageAvailable", Boolean.toString(usage.available()));
        metadata.put("tokenUsageFinalized", Boolean.toString(usage.finalized()));
        metadata.put("inputTokens", String.valueOf(usage.inputTokens()));
        metadata.put("outputTokens", String.valueOf(usage.outputTokens()));
        metadata.put("cacheCreationInputTokens", String.valueOf(usage.cacheCreationInputTokens()));
        metadata.put("cacheReadInputTokens", String.valueOf(usage.cacheReadInputTokens()));
        metadata.put("totalTokens", String.valueOf(usage.totalTokens()));
        metadata.put("estimatedCostUsd", usage.estimatedCostUsd().toPlainString());
        metadata.put("claudeSessionId", usage.sessionId());
        metadata.put("uniqueAssistantMessages", String.valueOf(usage.uniqueAssistantMessages()));
    }

    private static void copyTokenUsageMetadata(Map<String, String> target, Map<String, String> source) {
        for (String key : List.of(
                "tokenUsageAvailable",
                "tokenUsageFinalized",
                "inputTokens",
                "outputTokens",
                "cacheCreationInputTokens",
                "cacheReadInputTokens",
                "totalTokens",
                "estimatedCostUsd",
                "claudeSessionId",
                "uniqueAssistantMessages"
        )) {
            target.put(key, source.getOrDefault(key, ""));
        }
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
     * @param qaImage          QA 角色使用的 Playwright 镜像名
     * @param command          容器内 Claude Code 命令，yolo 参数通过该列表表达
     * @param networkMode      Docker 网络模式
     * @param removeAfterExit  退出后是否删除容器
     * @param allowPrivileged  是否允许特权模式
     */
    public record Configuration(
            String image,
            String qaImage,
            List<String> command,
            String networkMode,
            boolean removeAfterExit,
            boolean allowPrivileged,
            List<ClaudeCodeModelProvider> providers,
            QaSkillConfiguration qaSkill,
            HandoffSkillConfiguration handoffSkill
    ) {

        public Configuration {
            image = requireText(image, "image");
            qaImage = requireText(qaImage, "qaImage");
            command = requireCommand(command);
            networkMode = networkMode == null ? "" : networkMode.strip();
            providers = normalizeProviders(providers);
            qaSkill = qaSkill == null ? QaSkillConfiguration.disabled() : qaSkill;
            handoffSkill = handoffSkill == null ? HandoffSkillConfiguration.disabled() : handoffSkill;
        }

        public Configuration(
                String image,
                String qaImage,
                List<String> command,
                String networkMode,
                boolean removeAfterExit,
                boolean allowPrivileged,
                List<ClaudeCodeModelProvider> providers,
                QaSkillConfiguration qaSkill
        ) {
            this(
                    image,
                    qaImage,
                    command,
                    networkMode,
                    removeAfterExit,
                    allowPrivileged,
                    providers,
                    qaSkill,
                    HandoffSkillConfiguration.disabled()
            );
        }

        public Configuration(
                String image,
                String qaImage,
                List<String> command,
                String networkMode,
                boolean removeAfterExit,
                boolean allowPrivileged,
                List<ClaudeCodeModelProvider> providers
        ) {
            this(
                    image,
                    qaImage,
                    command,
                    networkMode,
                    removeAfterExit,
                    allowPrivileged,
                    providers,
                    QaSkillConfiguration.disabled()
            );
        }

        public Configuration(
                String image,
                List<String> command,
                String networkMode,
                boolean removeAfterExit,
                boolean allowPrivileged,
                List<ClaudeCodeModelProvider> providers
        ) {
            this(
                    image,
                    image,
                    command,
                    networkMode,
                    removeAfterExit,
                    allowPrivileged,
                    providers,
                    QaSkillConfiguration.disabled()
            );
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
                    image,
                    command,
                    networkMode,
                    removeAfterExit,
                    allowPrivileged,
                    List.of(ClaudeCodeModelProvider.defaultAnthropic()),
                    QaSkillConfiguration.disabled()
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
                    "claude-code-qa:local",
                    List.of("claude", "--dangerously-skip-permissions"),
                    "none",
                    true,
                    false,
                    List.of(ClaudeCodeModelProvider.defaultAnthropic()),
                    QaSkillConfiguration.disabled()
            );
        }

        public Configuration withQaSkill(QaSkillConfiguration qaSkill) {
            return new Configuration(
                    image,
                    qaImage,
                    command,
                    networkMode,
                    removeAfterExit,
                    allowPrivileged,
                    providers,
                    qaSkill,
                    handoffSkill
            );
        }

        public Configuration withHandoffSkill(HandoffSkillConfiguration handoffSkill) {
            return new Configuration(
                    image,
                    qaImage,
                    command,
                    networkMode,
                    removeAfterExit,
                    allowPrivileged,
                    providers,
                    qaSkill,
                    handoffSkill
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

    /**
     * Verified QA skill snapshot mounted read-only into Claude Code's skill directory.
     */
    public record QaSkillConfiguration(
            String hostPath,
            String skillId,
            String version,
            String checksum,
            String policyJson
    ) {

        public QaSkillConfiguration {
            hostPath = normalizeEnvText(hostPath);
            skillId = normalizeEnvText(skillId);
            version = normalizeEnvText(version);
            checksum = normalizeEnvText(checksum);
            policyJson = normalizeEnvText(policyJson);
            boolean anyConfigured = !hostPath.isBlank()
                    || !skillId.isBlank()
                    || !version.isBlank()
                    || !checksum.isBlank()
                    || !policyJson.isBlank();
            if (anyConfigured && (hostPath.isBlank()
                    || skillId.isBlank()
                    || version.isBlank()
                    || checksum.isBlank()
                    || policyJson.isBlank())) {
                throw new IllegalArgumentException("enabled QA skill configuration must be complete");
            }
        }

        public boolean enabled() {
            return !hostPath.isBlank();
        }

        public static QaSkillConfiguration disabled() {
            return new QaSkillConfiguration("", "", "", "", "");
        }
    }

    /**
     * Verified role-handoff skill snapshot mounted read-only for review, planning, and coding stages.
     */
    public record HandoffSkillConfiguration(
            String hostPath,
            String skillId,
            String version,
            String checksum,
            String policyJson
    ) {

        public HandoffSkillConfiguration {
            hostPath = normalizeEnvText(hostPath);
            skillId = normalizeEnvText(skillId);
            version = normalizeEnvText(version);
            checksum = normalizeEnvText(checksum);
            policyJson = normalizeEnvText(policyJson);
            boolean anyConfigured = !hostPath.isBlank()
                    || !skillId.isBlank()
                    || !version.isBlank()
                    || !checksum.isBlank()
                    || !policyJson.isBlank();
            if (anyConfigured && (hostPath.isBlank()
                    || skillId.isBlank()
                    || version.isBlank()
                    || checksum.isBlank()
                    || policyJson.isBlank())) {
                throw new IllegalArgumentException("enabled handoff skill configuration must be complete");
            }
        }

        public boolean enabled() {
            return !hostPath.isBlank();
        }

        public static HandoffSkillConfiguration disabled() {
            return new HandoffSkillConfiguration("", "", "", "", "");
        }
    }

    private record AttemptOutcome(RepairExecutionResult result) {
    }

    /**
     * 鉴权环境变量解析器。默认先读当前进程环境变量，再兼容 macOS GUI 进程通过
     * {@code launchctl setenv} 写入的用户环境。
     */
    @FunctionalInterface
    public interface AuthEnvironmentResolver {

        /**
         * 按变量名解析鉴权值。
         *
         * @param envName 环境变量名
         * @return 鉴权值，未找到返回空字符串
         */
        String resolve(String envName);

        /**
         * 返回系统默认解析器。
         *
         * @return 默认解析器
         */
        static AuthEnvironmentResolver system() {
            return envName -> firstNonBlank(System.getenv(envName), launchctlGetenv(envName));
        }
    }

    private static String launchctlGetenv(String envName) {
        String normalizedEnvName = normalizeEnvText(envName);
        if (!normalizedEnvName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return "";
        }
        try {
            Process process = new ProcessBuilder(LAUNCHCTL_BINARY, "getenv", normalizedEnvName).start();
            boolean completed = process.waitFor(2, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return "";
            }
            if (process.exitValue() != 0) {
                return "";
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            return "";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
