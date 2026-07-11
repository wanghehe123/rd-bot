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
    private LocalListener localListener = new LocalListener();
    private Alert alert = new Alert();

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

    public LocalListener getLocalListener() {
        return localListener;
    }

    public void setLocalListener(LocalListener localListener) {
        this.localListener = localListener == null ? new LocalListener() : localListener;
    }

    public Alert getAlert() {
        return alert;
    }

    public void setAlert(Alert alert) {
        this.alert = alert == null ? new Alert() : alert;
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

    /**
     * 本地事件监听配置。通过 lark-cli event consume 主动订阅飞书事件，
     * 适合本地开发环境，避免公网回调和内网穿透依赖。
     */
    public static class LocalListener {
        private boolean enabled = false;
        private String command = "lark-cli";
        private String profile = "";
        private String eventKey = "im.message.receive_v1";
        private String identity = "bot";
        private long restartDelayMillis = 5000;
        private boolean writeBackViaCli = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = safe(command, "lark-cli");
        }

        public String getProfile() {
            return profile;
        }

        public void setProfile(String profile) {
            this.profile = safe(profile, "");
        }

        public String getEventKey() {
            return eventKey;
        }

        public void setEventKey(String eventKey) {
            this.eventKey = safe(eventKey, "im.message.receive_v1");
        }

        public String getIdentity() {
            return identity;
        }

        public void setIdentity(String identity) {
            this.identity = safe(identity, "bot");
        }

        public long getRestartDelayMillis() {
            return restartDelayMillis;
        }

        public void setRestartDelayMillis(long restartDelayMillis) {
            this.restartDelayMillis = restartDelayMillis <= 0 ? 5000 : restartDelayMillis;
        }

        public boolean isWriteBackViaCli() {
            return writeBackViaCli;
        }

        public void setWriteBackViaCli(boolean writeBackViaCli) {
            this.writeBackViaCli = writeBackViaCli;
        }
    }

    /**
     * 告警通知配置。
     */
    public static class Alert {
        private boolean enabled = false;
        private String chatId = "";
        private String adminBaseUrl = "http://127.0.0.1:5173";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getChatId() {
            return chatId;
        }

        public void setChatId(String chatId) {
            this.chatId = safe(chatId, "");
        }

        public String getAdminBaseUrl() {
            return adminBaseUrl;
        }

        public void setAdminBaseUrl(String adminBaseUrl) {
            this.adminBaseUrl = safe(adminBaseUrl, "http://127.0.0.1:5173").replaceAll("/+$", "");
        }
    }
}
