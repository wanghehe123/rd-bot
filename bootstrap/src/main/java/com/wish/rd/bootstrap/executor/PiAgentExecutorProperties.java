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

    private String image = DEFAULT_IMAGE;
    private String qaImage = DEFAULT_QA_IMAGE;
    private List<String> command = new ArrayList<>(List.of(
            "node",
            "/opt/rd-pi-bridge/src/rd-pi-bridge.mjs"
    ));
    private String networkMode = DEFAULT_NETWORK_MODE;
    private boolean removeAfterExit = true;
    private boolean allowPrivileged = false;
    private long executionTimeoutMillis = 0L;
    private long rawEventMaxBytes = 16L * 1024L * 1024L;

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
        this.executionTimeoutMillis = Math.max(0L, executionTimeoutMillis);
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
                rawEventMaxBytes
        );
    }

    private static String textOrDefault(String value, String fallback) {
        String normalized = value == null ? "" : value.strip();
        return normalized.isBlank() ? fallback : normalized;
    }
}
