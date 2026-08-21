package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.pi.impl.DockerPiAgentExecutor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Configuration for the one-shot Pi bridge container. */
@Component
@ConfigurationProperties(prefix = "rd.executor.pi")
public class PiAgentExecutorProperties {

    public static final String DEFAULT_IMAGE = "rd-bot/pi-agent:local";
    public static final String DEFAULT_QA_IMAGE = "rd-bot/pi-agent-qa:local";
    public static final String DEFAULT_NETWORK_MODE = "bridge";
    public static final String DEFAULT_CREDENTIAL_RELAY_URL =
            "http://host.docker.internal:18080/internal/pi/credential-relay/proxy";
    public static final String DEFAULT_CONTAINER_MEMORY_LIMIT =
            DockerPiAgentExecutor.DEFAULT_CONTAINER_MEMORY_LIMIT;
    public static final String DEFAULT_CONTAINER_CPU_LIMIT =
            DockerPiAgentExecutor.DEFAULT_CONTAINER_CPU_LIMIT;
    public static final long DEFAULT_EXECUTION_TIMEOUT_MILLIS = 60L * 60L * 1000L;
    public static final long DEFAULT_BASH_COMMAND_TIMEOUT_MILLIS = 15L * 60L * 1000L;

    private String image = DEFAULT_IMAGE;
    private String qaImage = DEFAULT_QA_IMAGE;
    private List<String> command = new ArrayList<>(List.of(
            "node",
            "/opt/rd-pi-bridge/src/rd-pi-bridge.mjs"
    ));
    private String networkMode = DEFAULT_NETWORK_MODE;
    private boolean removeAfterExit = true;
    private boolean allowPrivileged = false;
    private long executionTimeoutMillis = DEFAULT_EXECUTION_TIMEOUT_MILLIS;
    private long bashCommandTimeoutMillis = DEFAULT_BASH_COMMAND_TIMEOUT_MILLIS;
    private long rawEventMaxBytes = 16L * 1024L * 1024L;
    private String contextProtocolVersion = "LEGACY_ENVIRONMENT_NOTES";
    private String contextPolicyMode = "LEGACY_OBSERVE_ONLY";
    private boolean dynamicStateEnabled = false;
    private int maxInjectedStateBytes = 8192;
    private String requestProtocolVersion = "v1";
    /** Credentialed Pi requests must use the Host-owned relay by default. */
    private boolean credentialRelayEnabled = true;
    private String credentialRelayUrl = DEFAULT_CREDENTIAL_RELAY_URL;
    /** Pi/QA 容器 docker --memory 上限；小内存宿主应收口（如 1100m），默认保持历史 8g。 */
    private String containerMemoryLimit = DEFAULT_CONTAINER_MEMORY_LIMIT;
    /** Pi/QA 容器 docker --cpus 上限；不得超过宿主 CPU 数，否则 docker run exit 125。 */
    private String containerCpuLimit = DEFAULT_CONTAINER_CPU_LIMIT;

    public String getContextProtocolVersion() {
        return contextProtocolVersion;
    }

    public void setContextProtocolVersion(String contextProtocolVersion) {
        this.contextProtocolVersion = textOrDefault(contextProtocolVersion, "LEGACY_ENVIRONMENT_NOTES");
    }

    public String getContextPolicyMode() {
        return contextPolicyMode;
    }

    public void setContextPolicyMode(String contextPolicyMode) {
        this.contextPolicyMode = normalizeContextPolicyMode(contextPolicyMode);
    }

    public boolean isDynamicStateEnabled() {
        return dynamicStateEnabled;
    }

    public void setDynamicStateEnabled(boolean dynamicStateEnabled) {
        this.dynamicStateEnabled = dynamicStateEnabled;
    }

    public int getMaxInjectedStateBytes() {
        return maxInjectedStateBytes;
    }

    public void setMaxInjectedStateBytes(int maxInjectedStateBytes) {
        this.maxInjectedStateBytes = maxInjectedStateBytes > 0 ? maxInjectedStateBytes : 8192;
    }

    public String getRequestProtocolVersion() {
        return requestProtocolVersion;
    }

    public void setRequestProtocolVersion(String requestProtocolVersion) {
        this.requestProtocolVersion = normalizeRequestProtocolVersion(requestProtocolVersion);
    }

    public boolean isCredentialRelayEnabled() {
        return credentialRelayEnabled;
    }

    public void setCredentialRelayEnabled(boolean credentialRelayEnabled) {
        this.credentialRelayEnabled = credentialRelayEnabled;
    }

    public String getCredentialRelayUrl() {
        return credentialRelayUrl;
    }

    public void setCredentialRelayUrl(String credentialRelayUrl) {
        this.credentialRelayUrl = textOrDefault(credentialRelayUrl, DEFAULT_CREDENTIAL_RELAY_URL);
    }

    public String getContainerMemoryLimit() {
        return containerMemoryLimit;
    }

    public void setContainerMemoryLimit(String containerMemoryLimit) {
        // 空白回落默认值；格式校验统一在 Configuration 紧凑构造器中失败关闭。
        this.containerMemoryLimit = textOrDefault(containerMemoryLimit, DEFAULT_CONTAINER_MEMORY_LIMIT);
    }

    public String getContainerCpuLimit() {
        return containerCpuLimit;
    }

    public void setContainerCpuLimit(String containerCpuLimit) {
        this.containerCpuLimit = textOrDefault(containerCpuLimit, DEFAULT_CONTAINER_CPU_LIMIT);
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = textOrDefault(image, DEFAULT_IMAGE);
    }

    public String getQaImage() {
        return qaImage;
    }

    public void setQaImage(String qaImage) {
        this.qaImage = textOrDefault(qaImage, DEFAULT_QA_IMAGE);
    }

    public List<String> getCommand() {
        return List.copyOf(command);
    }

    public void setCommand(List<String> command) {
        this.command = command == null ? new ArrayList<>() : new ArrayList<>(command);
    }

    public String getNetworkMode() {
        return networkMode;
    }

    public void setNetworkMode(String networkMode) {
        this.networkMode = textOrDefault(networkMode, DEFAULT_NETWORK_MODE);
    }

    public boolean isRemoveAfterExit() {
        return removeAfterExit;
    }

    public void setRemoveAfterExit(boolean removeAfterExit) {
        this.removeAfterExit = removeAfterExit;
    }

    public boolean isAllowPrivileged() {
        return allowPrivileged;
    }

    public void setAllowPrivileged(boolean allowPrivileged) {
        this.allowPrivileged = allowPrivileged;
    }

    public long getExecutionTimeoutMillis() {
        return executionTimeoutMillis;
    }

    public void setExecutionTimeoutMillis(long executionTimeoutMillis) {
        this.executionTimeoutMillis = positiveTimeout(executionTimeoutMillis, DEFAULT_EXECUTION_TIMEOUT_MILLIS);
    }

    public long getBashCommandTimeoutMillis() {
        return bashCommandTimeoutMillis;
    }

    public void setBashCommandTimeoutMillis(long bashCommandTimeoutMillis) {
        this.bashCommandTimeoutMillis = positiveTimeout(
                bashCommandTimeoutMillis, DEFAULT_BASH_COMMAND_TIMEOUT_MILLIS);
    }

    public long getRawEventMaxBytes() {
        return rawEventMaxBytes;
    }

    public void setRawEventMaxBytes(long rawEventMaxBytes) {
        this.rawEventMaxBytes = Math.max(1L, rawEventMaxBytes);
    }

    public DockerPiAgentExecutor.Configuration toExecutorConfiguration() {
        return new DockerPiAgentExecutor.Configuration(
                image,
                qaImage,
                command,
                networkMode,
                removeAfterExit,
                allowPrivileged,
                executionTimeoutMillis,
                bashCommandTimeoutMillis,
                rawEventMaxBytes,
                requestProtocolVersion,
                credentialRelayEnabled,
                credentialRelayUrl,
                containerMemoryLimit,
                containerCpuLimit
        );
    }

    private static String normalizeRequestProtocolVersion(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            return "v1";
        }
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        if ("v1".equals(lower) || "v2".equals(lower)) {
            return lower;
        }
        throw new IllegalArgumentException("requestProtocolVersion must be v1 or v2 but was: " + value);
    }

    private static String normalizeContextPolicyMode(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            return "LEGACY_OBSERVE_ONLY";
        }
        String upper = normalized.toUpperCase(java.util.Locale.ROOT);
        if ("LEGACY_OBSERVE_ONLY".equals(upper)
                || "ROOT_ONLY".equals(upper)
                || "ROOT_AND_ALLOWLISTED_NESTED".equals(upper)) {
            return upper;
        }
        throw new IllegalArgumentException(
                "contextPolicyMode must be LEGACY_OBSERVE_ONLY, ROOT_ONLY, or ROOT_AND_ALLOWLISTED_NESTED but was: "
                        + value
        );
    }

    private static long positiveTimeout(long value, long fallback) {
        return value > 0L ? value : fallback;
    }

    private static String textOrDefault(String value, String fallback) {
        String normalized = value == null ? "" : value.strip();
        return normalized.isBlank() ? fallback : normalized;
    }
}
