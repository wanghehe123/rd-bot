package com.wish.rd.bootstrap.executor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * OpenAI-compatible chat completions executor properties.
 */
@Component
@ConfigurationProperties(prefix = "rd.executor.openai-chat")
public class OpenAiChatCompletionsProperties {

    private boolean enabled = false;
    private String providerName = "minimax";
    private String model = "MiniMax-M2.7";
    private String baseUrl = "https://api.minimaxi.com/v1";
    private String apiKeyEnv = "MINIMAX_API_KEY";
    private Duration timeout = Duration.ofSeconds(60);
    private String protocol = "openai-chat-completions";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = defaultWhenBlank(providerName, "minimax");
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = defaultWhenBlank(model, "MiniMax-M2.7");
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = defaultWhenBlank(baseUrl, "https://api.minimaxi.com/v1");
    }

    public String getApiKeyEnv() {
        return apiKeyEnv;
    }

    public void setApiKeyEnv(String apiKeyEnv) {
        this.apiKeyEnv = defaultWhenBlank(apiKeyEnv, "MINIMAX_API_KEY");
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(60)
                : timeout;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = defaultWhenBlank(protocol, "openai-chat-completions");
    }

    OpenAiChatCompletionsRepairExecutor.Configuration toExecutorConfiguration() {
        return new OpenAiChatCompletionsRepairExecutor.Configuration(
                providerName,
                model,
                baseUrl,
                apiKeyEnv,
                timeout,
                protocol
        );
    }

    private static String defaultWhenBlank(String value, String defaultValue) {
        String normalized = value == null ? "" : value.strip();
        return normalized.isBlank() ? defaultValue : normalized;
    }
}
