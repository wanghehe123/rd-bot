package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.docker.model.ClaudeCodeModelProvider;
import com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor;
import com.wish.rd.exec.repair.model.ModelCircuitBreakerPolicy;
import com.wish.rd.exec.repair.security.model.ExecutionAllowlistPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Docker Claude Code 执行器配置属性。
 *
 * <p>键前缀为 {@code rd.executor.docker.*}。默认关闭真实 Docker 执行，只有显式
 * {@code enabled=true} 时才装配进程级 Docker runner。
 */
@Component
@ConfigurationProperties(prefix = "rd.executor.docker")
public class DockerExecutorProperties {

    /** 默认 Claude Code 镜像名。 */
    public static final String DEFAULT_IMAGE = "rd-bot/claude-code:local";
    /** 默认包含 Playwright CLI 和 Chromium 的 QA 镜像名。 */
    public static final String DEFAULT_QA_IMAGE = "rd-bot/claude-code-qa:local";
    /** 默认修复工作区根目录。 */
    public static final Path DEFAULT_WORKSPACE_ROOT = Path.of("/tmp/rd-bot/repair-workspaces");
    /** 默认 Claude Code 命令。 */
    public static final String DEFAULT_COMMAND = "claude";
    /** 默认容器内 yolo 模式参数。 */
    public static final String DEFAULT_YOLO_FLAG = "--dangerously-skip-permissions";
    /** 默认 Claude Code 输出格式。 */
    public static final String DEFAULT_OUTPUT_FORMAT = "stream-json";
    /** 默认 Docker 网络模式。 */
    public static final String DEFAULT_NETWORK_MODE = "bridge";
    /** 默认超时告警阈值，单位毫秒。 */
    public static final long DEFAULT_TIMEOUT_ALERT_MILLIS = 1_800_000L;
    /** 默认预算告警阈值，单位人民币元。 */
    public static final BigDecimal DEFAULT_BUDGET_ALERT_CNY = new BigDecimal("36.00");
    /** 默认模型熔断连续失败阈值。 */
    public static final int DEFAULT_CIRCUIT_BREAKER_FAILURE_THRESHOLD = 3;
    /** 默认模型熔断 OPEN 持续时间。 */
    public static final long DEFAULT_CIRCUIT_BREAKER_OPEN_DURATION_MILLIS = 60_000L;

    private boolean enabled = false;
    private String image = DEFAULT_IMAGE;
    private String qaImage = DEFAULT_QA_IMAGE;
    private Path workspaceRoot = DEFAULT_WORKSPACE_ROOT;
    private String command = DEFAULT_COMMAND;
    private String yoloFlag = DEFAULT_YOLO_FLAG;
    private String outputFormat = DEFAULT_OUTPUT_FORMAT;
    private String networkMode = DEFAULT_NETWORK_MODE;
    private boolean removeAfterExit = true;
    private long timeoutAlertMillis = DEFAULT_TIMEOUT_ALERT_MILLIS;
    private BigDecimal budgetAlertCny = DEFAULT_BUDGET_ALERT_CNY;
    private GitProperties git = new GitProperties();
    private CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties();
    private SecurityProperties security = new SecurityProperties();
    private List<ModelProviderProperties> providers = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = defaultWhenBlank(image, DEFAULT_IMAGE);
    }

    public String getQaImage() {
        return qaImage;
    }

    public void setQaImage(String qaImage) {
        this.qaImage = defaultWhenBlank(qaImage, DEFAULT_QA_IMAGE);
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot == null ? DEFAULT_WORKSPACE_ROOT : workspaceRoot.toAbsolutePath().normalize();
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = defaultWhenBlank(command, DEFAULT_COMMAND);
    }

    public String getYoloFlag() {
        return yoloFlag;
    }

    public void setYoloFlag(String yoloFlag) {
        this.yoloFlag = normalize(yoloFlag);
    }

    public String getOutputFormat() {
        return outputFormat;
    }

    public void setOutputFormat(String outputFormat) {
        this.outputFormat = defaultWhenBlank(outputFormat, DEFAULT_OUTPUT_FORMAT);
    }

    public String getNetworkMode() {
        return networkMode;
    }

    public void setNetworkMode(String networkMode) {
        this.networkMode = defaultWhenBlank(networkMode, DEFAULT_NETWORK_MODE);
    }

    public boolean isRemoveAfterExit() {
        return removeAfterExit;
    }

    public void setRemoveAfterExit(boolean removeAfterExit) {
        this.removeAfterExit = removeAfterExit;
    }

    public long getTimeoutAlertMillis() {
        return timeoutAlertMillis;
    }

    public void setTimeoutAlertMillis(long timeoutAlertMillis) {
        this.timeoutAlertMillis = Math.max(0L, timeoutAlertMillis);
    }

    public BigDecimal getBudgetAlertCny() {
        return budgetAlertCny;
    }

    public void setBudgetAlertCny(BigDecimal budgetAlertCny) {
        this.budgetAlertCny = budgetAlertCny == null ? DEFAULT_BUDGET_ALERT_CNY : budgetAlertCny;
    }

    public GitProperties getGit() {
        return git;
    }

    public void setGit(GitProperties git) {
        this.git = git == null ? new GitProperties() : git;
    }

    public CircuitBreakerProperties getCircuitBreaker() {
        return circuitBreaker;
    }

    public void setCircuitBreaker(CircuitBreakerProperties circuitBreaker) {
        this.circuitBreaker = circuitBreaker == null ? new CircuitBreakerProperties() : circuitBreaker;
    }

    public SecurityProperties getSecurity() {
        return security;
    }

    public void setSecurity(SecurityProperties security) {
        this.security = security == null ? new SecurityProperties() : security;
    }

    public List<ModelProviderProperties> getProviders() {
        return providers;
    }

    public void setProviders(List<ModelProviderProperties> providers) {
        this.providers = providers == null ? new ArrayList<>() : new ArrayList<>(providers);
    }

    /**
     * 生成 exec 模块 Docker Claude Code 执行器需要的容器配置。
     *
     * @return Docker Claude Code 执行配置
     */
    public DockerClaudeCodeExecutor.Configuration toExecutorConfiguration() {
        return new DockerClaudeCodeExecutor.Configuration(
                image,
                qaImage,
                claudeCommand(),
                networkMode,
                removeAfterExit,
                false,
                modelProviders()
        );
    }

    /**
     * 生成执行目标安全 allowlist 策略。
     *
     * @return 执行目标 allowlist 策略
     */
    public ExecutionAllowlistPolicy toExecutionAllowlistPolicy() {
        return security.toPolicy();
    }

    /**
     * 生成容器内 Claude Code argv。提示词由执行器写入工作区输入文件，后续入口脚本读取。
     *
     * @return Claude Code argv
     */
    public List<String> claudeCommand() {
        List<String> argv = new ArrayList<>();
        argv.add(command);
        argv.add("-p");
        if (!yoloFlag.isBlank()) {
            argv.add(yoloFlag);
        }
        argv.add("--output-format");
        argv.add(outputFormat);
        argv.add("--verbose");
        return List.copyOf(argv);
    }

    private List<ClaudeCodeModelProvider> modelProviders() {
        if (providers == null || providers.isEmpty()) {
            return List.of(ClaudeCodeModelProvider.defaultAnthropic());
        }
        List<ClaudeCodeModelProvider> compatibleProviders = providers.stream()
                .filter(ModelProviderProperties::isDockerClaudeCodeCompatible)
                .map(ModelProviderProperties::toModelProvider)
                .toList();
        if (compatibleProviders.isEmpty()) {
            String configuredProviders = providers.stream()
                    .map(ModelProviderProperties::providerLabel)
                    .toList()
                    .toString();
            throw new IllegalStateException(
                    "Docker Claude Code requires at least one anthropic-compatible provider; configured providers="
                            + configuredProviders);
        }
        return compatibleProviders;
    }

    private static String defaultWhenBlank(String value, String defaultValue) {
        String normalized = normalize(value);
        return normalized.isBlank() ? defaultValue : normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * Docker 执行前后本地 Git 工作区配置。
     */
    public static class GitProperties {

        private boolean enabled = false;
        private String userName = "RD-Bot";
        private String userEmail = "rd-bot@example.local";
        private long timeoutSeconds = 120L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = defaultWhenBlank(userName, "RD-Bot");
        }

        public String getUserEmail() {
            return userEmail;
        }

        public void setUserEmail(String userEmail) {
            this.userEmail = defaultWhenBlank(userEmail, "rd-bot@example.local");
        }

        public long getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(long timeoutSeconds) {
            this.timeoutSeconds = Math.max(1L, timeoutSeconds);
        }
    }

    /**
     * Claude Code 模型供应商熔断配置。
     */
    public static class CircuitBreakerProperties {

        private boolean enabled = true;
        private int failureThreshold = DEFAULT_CIRCUIT_BREAKER_FAILURE_THRESHOLD;
        private long openDurationMillis = DEFAULT_CIRCUIT_BREAKER_OPEN_DURATION_MILLIS;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = Math.max(1, failureThreshold);
        }

        public long getOpenDurationMillis() {
            return openDurationMillis;
        }

        public void setOpenDurationMillis(long openDurationMillis) {
            this.openDurationMillis = Math.max(1L, openDurationMillis);
        }

        /**
         * 转换为 exec 层模型熔断策略。
         *
         * @return 模型熔断策略
         */
        public ModelCircuitBreakerPolicy toPolicy() {
            return new ModelCircuitBreakerPolicy(enabled, failureThreshold, openDurationMillis);
        }
    }

    /**
     * Docker 修复执行安全治理配置。
     */
    public static class SecurityProperties {

        private boolean enabled = true;
        private List<String> repositoryUrls = new ArrayList<>();
        private List<String> repositories = new ArrayList<>();
        private List<String> baseBranches = new ArrayList<>();
        private List<String> workBranches = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getRepositoryUrls() {
            return repositoryUrls;
        }

        public void setRepositoryUrls(List<String> repositoryUrls) {
            this.repositoryUrls = repositoryUrls == null ? new ArrayList<>() : new ArrayList<>(repositoryUrls);
        }

        public List<String> getRepositories() {
            return repositories;
        }

        public void setRepositories(List<String> repositories) {
            this.repositories = repositories == null ? new ArrayList<>() : new ArrayList<>(repositories);
        }

        public List<String> getBaseBranches() {
            return baseBranches;
        }

        public void setBaseBranches(List<String> baseBranches) {
            this.baseBranches = baseBranches == null ? new ArrayList<>() : new ArrayList<>(baseBranches);
        }

        public List<String> getWorkBranches() {
            return workBranches;
        }

        public void setWorkBranches(List<String> workBranches) {
            this.workBranches = workBranches == null ? new ArrayList<>() : new ArrayList<>(workBranches);
        }

        /**
         * 转换为 exec 层 allowlist 策略。
         *
         * @return allowlist 策略
         */
        public ExecutionAllowlistPolicy toPolicy() {
            return new ExecutionAllowlistPolicy(
                    enabled,
                    repositoryUrls,
                    repositories,
                    baseBranches,
                    workBranches
            );
        }
    }

    /**
     * Claude Code 第三方模型供应商配置。
     */
    public static class ModelProviderProperties {

        private static final String DEFAULT_PROTOCOL = "anthropic-compatible";

        private String name = "";
        private String protocol = DEFAULT_PROTOCOL;
        private String baseUrl = "";
        private String apiKeyEnv = "";
        private String authTokenEnv = "";
        private String model = "";
        private String defaultOpusModel = "";
        private String defaultSonnetModel = "";
        private String defaultHaikuModel = "";
        private String subagentModel = "";
        private String effortLevel = "";
        private Map<String, String> extraEnv = new LinkedHashMap<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = normalize(name);
        }

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = defaultWhenBlank(protocol, DEFAULT_PROTOCOL);
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = normalize(baseUrl);
        }

        public String getApiKeyEnv() {
            return apiKeyEnv;
        }

        public void setApiKeyEnv(String apiKeyEnv) {
            this.apiKeyEnv = normalize(apiKeyEnv);
        }

        public String getAuthTokenEnv() {
            return authTokenEnv;
        }

        public void setAuthTokenEnv(String authTokenEnv) {
            this.authTokenEnv = normalize(authTokenEnv);
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = normalize(model);
        }

        public String getDefaultOpusModel() {
            return defaultOpusModel;
        }

        public void setDefaultOpusModel(String defaultOpusModel) {
            this.defaultOpusModel = normalize(defaultOpusModel);
        }

        public String getDefaultSonnetModel() {
            return defaultSonnetModel;
        }

        public void setDefaultSonnetModel(String defaultSonnetModel) {
            this.defaultSonnetModel = normalize(defaultSonnetModel);
        }

        public String getDefaultHaikuModel() {
            return defaultHaikuModel;
        }

        public void setDefaultHaikuModel(String defaultHaikuModel) {
            this.defaultHaikuModel = normalize(defaultHaikuModel);
        }

        public String getSubagentModel() {
            return subagentModel;
        }

        public void setSubagentModel(String subagentModel) {
            this.subagentModel = normalize(subagentModel);
        }

        public String getEffortLevel() {
            return effortLevel;
        }

        public void setEffortLevel(String effortLevel) {
            this.effortLevel = normalize(effortLevel);
        }

        public Map<String, String> getExtraEnv() {
            return extraEnv;
        }

        public void setExtraEnv(Map<String, String> extraEnv) {
            this.extraEnv = extraEnv == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extraEnv);
        }

        private boolean isDockerClaudeCodeCompatible() {
            String normalized = normalizeProtocol(protocol);
            return "anthropic-compatible".equals(normalized)
                    || "anthropic-claude-code".equals(normalized)
                    || "claude-code".equals(normalized)
                    || "anthropic".equals(normalized);
        }

        private String providerLabel() {
            return defaultWhenBlank(name, "anthropic") + ":" + normalizeProtocol(protocol);
        }

        private ClaudeCodeModelProvider toModelProvider() {
            String providerName = defaultWhenBlank(name, "anthropic");
            Map<String, String> env = new LinkedHashMap<>();
            env.put("RD_CLAUDE_PROVIDER_PROTOCOL", normalizeProtocol(protocol));
            putIfNotBlank(env, "ANTHROPIC_BASE_URL", baseUrl);
            putIfNotBlank(env, "ANTHROPIC_MODEL", model);
            putIfNotBlank(env, "ANTHROPIC_DEFAULT_OPUS_MODEL", defaultOpusModel);
            putIfNotBlank(env, "ANTHROPIC_DEFAULT_SONNET_MODEL", defaultSonnetModel);
            putIfNotBlank(env, "ANTHROPIC_DEFAULT_HAIKU_MODEL", defaultHaikuModel);
            putIfNotBlank(env, "CLAUDE_CODE_SUBAGENT_MODEL", subagentModel);
            putIfNotBlank(env, "CLAUDE_CODE_EFFORT_LEVEL", effortLevel);
            putIfNotBlank(env, "RD_CLAUDE_AUTH_TOKEN_ENV", authTokenEnv);
            putIfNotBlank(env, "RD_CLAUDE_API_KEY_ENV", apiKeyEnv);
            if (!authTokenEnv.isBlank()) {
                env.put(authTokenEnv, "");
            }
            if (!apiKeyEnv.isBlank()) {
                env.put(apiKeyEnv, "");
            }
            if (env.isEmpty()) {
                env.put("ANTHROPIC_API_KEY", "");
            }
            extraEnv.forEach((key, value) -> {
                String normalizedKey = normalize(key);
                if (!normalizedKey.isBlank()) {
                    env.put(normalizedKey, normalize(value));
                }
            });
            return new ClaudeCodeModelProvider(providerName, env);
        }

        private static void putIfNotBlank(Map<String, String> target, String key, String value) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) {
                target.put(key, normalized);
            }
        }

        private static String normalizeProtocol(String value) {
            String normalized = defaultWhenBlank(value, DEFAULT_PROTOCOL)
                    .replace('_', '-')
                    .toLowerCase();
            return normalized.isBlank() ? DEFAULT_PROTOCOL : normalized;
        }
    }
}
