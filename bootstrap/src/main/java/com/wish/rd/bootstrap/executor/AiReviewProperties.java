package com.wish.rd.bootstrap.executor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Environment-backed configuration for the optional Anthropic-compatible AI delivery reviewer. */
@Component
@ConfigurationProperties(prefix = "rd.ai-review")
public class AiReviewProperties {
    private boolean enabled;
    private String baseUrl = "https://api.longcat.chat/anthropic";
    private String model = "";
    private String authTokenEnv = "LONGCAT_API_KEY";
    private String authMode = "x-api-key";
    private boolean disableThinking = true;
    private int maxTokens = 4096;
    private int maxInputChars = 80_000;
    private Duration timeout = Duration.ofSeconds(90);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = defaultWhenBlank(baseUrl, "https://api.longcat.chat/anthropic");
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = safe(model);
    }

    public String getAuthTokenEnv() {
        return authTokenEnv;
    }

    public void setAuthTokenEnv(String authTokenEnv) {
        this.authTokenEnv = defaultWhenBlank(authTokenEnv, "LONGCAT_API_KEY");
    }

    public String getAuthMode() {
        return authMode;
    }

    public void setAuthMode(String authMode) {
        String normalized = safe(authMode).toLowerCase();
        this.authMode = "authorization-bearer".equals(normalized) ? normalized : "x-api-key";
    }

    public boolean isDisableThinking() {
        return disableThinking;
    }

    public void setDisableThinking(boolean disableThinking) {
        this.disableThinking = disableThinking;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = Math.max(512, maxTokens);
    }

    public int getMaxInputChars() {
        return maxInputChars;
    }

    public void setMaxInputChars(int maxInputChars) {
        this.maxInputChars = Math.max(10_000, maxInputChars);
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(90) : timeout;
    }

    private static String defaultWhenBlank(String value, String fallback) {
        String normalized = safe(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
