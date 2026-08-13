package com.wish.rd.bootstrap.openviking;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenViking 连接配置。API key 只从环境变量读取，绝不进配置文件或日志。
 */
@ConfigurationProperties(prefix = "rd.openviking")
public class OpenVikingProperties {

    private boolean enabled = false;
    private String baseUrl = "http://127.0.0.1:1933";
    private String apiKeyEnv = "OPENVIKING_API_KEY";
    private String ownedRoot = "viking://resources/rd-bot/";
    private long connectTimeoutMillis = 3_000L;
    private long requestTimeoutMillis = 30_000L;

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
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://127.0.0.1:1933" : baseUrl.strip();
    }

    public String getApiKeyEnv() {
        return apiKeyEnv;
    }

    public void setApiKeyEnv(String apiKeyEnv) {
        this.apiKeyEnv = apiKeyEnv == null || apiKeyEnv.isBlank() ? "OPENVIKING_API_KEY" : apiKeyEnv.strip();
    }

    public String getOwnedRoot() {
        return ownedRoot;
    }

    public void setOwnedRoot(String ownedRoot) {
        this.ownedRoot = ownedRoot == null || ownedRoot.isBlank() ? "viking://resources/rd-bot/" : ownedRoot.strip();
    }

    public long getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(long connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis <= 0L ? 3_000L : connectTimeoutMillis;
    }

    public long getRequestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    public void setRequestTimeoutMillis(long requestTimeoutMillis) {
        this.requestTimeoutMillis = requestTimeoutMillis <= 0L ? 30_000L : requestTimeoutMillis;
    }
}
