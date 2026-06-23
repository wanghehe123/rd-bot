package com.wish.rd.bootstrap.feishu.im;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 飞书 IM 机器人配置。
 *
 * <p>绑定 {@code rd.feishu.im.*}，仅保存 app 级鉴权、事件过滤和回写开关。
 * Helpdesk ID/token 不属于 IM 入口配置。
 */
@Component
@ConfigurationProperties(prefix = "rd.feishu.im")
public class FeishuImProperties {

    private boolean enabled = false;
    private String baseUrl = "https://open.feishu.cn";
    private String appId = "";
    private String appSecret = "";
    private boolean requireAtMention = true;
    private Http http = new Http();
    private WriteBack writeBack = new WriteBack();

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
        this.baseUrl = safe(baseUrl, "https://open.feishu.cn");
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = safe(appId, "");
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = safe(appSecret, "");
    }

    public boolean isRequireAtMention() {
        return requireAtMention;
    }

    public void setRequireAtMention(boolean requireAtMention) {
        this.requireAtMention = requireAtMention;
    }

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http == null ? new Http() : http;
    }

    public WriteBack getWriteBack() {
        return writeBack;
    }

    public void setWriteBack(WriteBack writeBack) {
        this.writeBack = writeBack == null ? new WriteBack() : writeBack;
    }

    private static String safe(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    /**
     * HTTP 超时配置。
     */
    public static class Http {
        private int connectTimeoutMillis = 3000;
        private int requestTimeoutMillis = 10000;

        public int getConnectTimeoutMillis() {
            return connectTimeoutMillis;
        }

        public void setConnectTimeoutMillis(int connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis <= 0 ? 3000 : connectTimeoutMillis;
        }

        public int getRequestTimeoutMillis() {
            return requestTimeoutMillis;
        }

        public void setRequestTimeoutMillis(int requestTimeoutMillis) {
            this.requestTimeoutMillis = requestTimeoutMillis <= 0 ? 10000 : requestTimeoutMillis;
        }
    }

    /**
     * IM 回写开关。
     */
    public static class WriteBack {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
