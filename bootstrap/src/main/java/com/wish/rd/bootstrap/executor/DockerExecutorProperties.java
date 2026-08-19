package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.model.ModelCircuitBreakerPolicy;
import com.wish.rd.exec.repair.security.model.ExecutionAllowlistPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 容器执行基础设施配置属性。
 *
 * <p>键前缀为 {@code rd.executor.docker.*}。默认关闭真实 Docker 执行，只有显式
 * {@code enabled=true} 时才装配进程级 Docker runner。
 *
 * <p>该前缀是**所有容器执行运行时共享**的基础设施门，不专属于任何一个执行器：
 * {@code enabled} 同时控制 {@code ProcessContainerRunner}、
 * {@code DockerRuntimeProfileImageBuilder}、{@code PiInterruptedStageWorkspaceRecovery}
 * 与 Pi agent 执行器 bean。移除某个具体执行器实现时不得连带删除该命名空间，
 * 否则容器运行器不再注册，Pi 路径会静默失去执行能力。
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
    private String networkMode = DEFAULT_NETWORK_MODE;
    private boolean removeAfterExit = true;
    private long timeoutAlertMillis = DEFAULT_TIMEOUT_ALERT_MILLIS;
    private BigDecimal budgetAlertCny = DEFAULT_BUDGET_ALERT_CNY;
    private GitProperties git = new GitProperties();
    private CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties();
    private SecurityProperties security = new SecurityProperties();

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

    /**
     * 生成执行目标安全 allowlist 策略。
     *
     * @return 执行目标 allowlist 策略
     */
    public ExecutionAllowlistPolicy toExecutionAllowlistPolicy() {
        return security.toPolicy();
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
        private String stateStore = "memory";

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

        public String getStateStore() {
            return stateStore;
        }

        public void setStateStore(String stateStore) {
            String normalized = stateStore == null ? "" : stateStore.strip().toLowerCase();
            if (!"memory".equals(normalized) && !"redis".equals(normalized)) {
                throw new IllegalArgumentException("circuit breaker state-store must be memory or redis");
            }
            this.stateStore = normalized;
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

}
