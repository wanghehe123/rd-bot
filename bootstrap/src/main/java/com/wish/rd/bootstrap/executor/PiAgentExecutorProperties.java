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
    private boolean dynamicStateEnabled = false;
    private int maxInjectedStateBytes = 8192;

    public String getContextProtocolVersion() {
        return contextProtocolVersion;
    }

    public void setContextProtocolVersion(String contextProtocolVersion) {
        this.contextProtocolVersion = textOrDefault(contextProtocolVersion, "LEGACY_ENVIRONMENT_NOTES");
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
                rawEventMaxBytes
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
