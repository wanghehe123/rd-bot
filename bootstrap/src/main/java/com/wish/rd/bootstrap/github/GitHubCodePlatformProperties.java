package com.wish.rd.bootstrap.github;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GitHub 代码平台适配器配置。
 *
 * <p>默认 {@link Mode#MOCK} 保持零配置启动；真实模式默认使用 GitHub App，
 * PAT 仅作为本地 smoke fallback 显式开启。
 */
@Component
@ConfigurationProperties(prefix = "rd.github.code-platform")
public class GitHubCodePlatformProperties {

    /** 代码平台适配器模式。 */
    public enum Mode {
        MOCK,
        REAL
    }

    /** GitHub 认证模式。 */
    public enum AuthMode {
        GITHUB_APP,
        PAT_LOCAL_SMOKE,
        GH_CLI_LOCAL_SMOKE
    }

    private Mode mode = Mode.MOCK;
    private AuthMode authMode = AuthMode.GITHUB_APP;
    private String apiBaseUrl = "https://api.github.com";
    private String webBaseUrl = "https://github.com";
    private List<String> allowedRepositories = List.of();
    private String patToken = "";
    private String appId = "";
    private String installationId = "";
    private String privateKeyRef = "";
    private String ghCliCommand = "gh";

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.MOCK : mode;
    }

    public AuthMode getAuthMode() {
        return authMode;
    }

    public void setAuthMode(AuthMode authMode) {
        this.authMode = authMode == null ? AuthMode.GITHUB_APP : authMode;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = defaultWhenBlank(apiBaseUrl, "https://api.github.com");
    }

    public String getWebBaseUrl() {
        return webBaseUrl;
    }

    public void setWebBaseUrl(String webBaseUrl) {
        this.webBaseUrl = defaultWhenBlank(webBaseUrl, "https://github.com");
    }

    public List<String> getAllowedRepositories() {
        return allowedRepositories;
    }

    public void setAllowedRepositories(List<String> allowedRepositories) {
        if (allowedRepositories == null || allowedRepositories.isEmpty()) {
            this.allowedRepositories = List.of();
            return;
        }
        List<String> normalized = new ArrayList<>();
        for (String repository : allowedRepositories) {
            String value = normalize(repository).toLowerCase(Locale.ROOT);
            if (!value.isBlank()) {
                normalized.add(value);
            }
        }
        this.allowedRepositories = List.copyOf(normalized);
    }

    public String getPatToken() {
        return patToken;
    }

    public void setPatToken(String patToken) {
        this.patToken = normalize(patToken);
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = normalize(appId);
    }

    public String getInstallationId() {
        return installationId;
    }

    public void setInstallationId(String installationId) {
        this.installationId = normalize(installationId);
    }

    public String getPrivateKeyRef() {
        return privateKeyRef;
    }

    public void setPrivateKeyRef(String privateKeyRef) {
        this.privateKeyRef = normalize(privateKeyRef);
    }

    public String getGhCliCommand() {
        return ghCliCommand;
    }

    public void setGhCliCommand(String ghCliCommand) {
        this.ghCliCommand = defaultWhenBlank(ghCliCommand, "gh");
    }

    public boolean isRepositoryAllowed(String owner, String repo) {
        if (allowedRepositories.isEmpty()) {
            return true;
        }
        return allowedRepositories.contains((normalize(owner) + "/" + normalize(repo)).toLowerCase(Locale.ROOT));
    }

    public void validateForRealAdapter() {
        if (authMode == AuthMode.PAT_LOCAL_SMOKE) {
            if (patToken.isBlank()) {
                throw new IllegalStateException("GitHub PAT local smoke fallback requires patToken");
            }
            return;
        }
        if (authMode == AuthMode.GH_CLI_LOCAL_SMOKE) {
            if (ghCliCommand.isBlank()) {
                throw new IllegalStateException("GitHub gh CLI local smoke fallback requires ghCliCommand");
            }
            return;
        }
        List<String> missing = new ArrayList<>();
        if (appId.isBlank()) {
            missing.add("appId");
        }
        if (installationId.isBlank()) {
            missing.add("installationId");
        }
        if (privateKeyRef.isBlank()) {
            missing.add("privateKeyRef");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("GitHub App mode requires " + String.join(", ", missing));
        }
        if (!isPrivateKeyReference(privateKeyRef)) {
            throw new IllegalStateException("GitHub App privateKeyRef must use env:, file:, or an absolute file path");
        }
    }

    private static boolean isPrivateKeyReference(String value) {
        String normalized = normalize(value);
        return normalized.startsWith("env:")
                || normalized.startsWith("file:")
                || Path.of(normalized).isAbsolute();
    }

    private static String defaultWhenBlank(String value, String defaultValue) {
        String normalized = normalize(value);
        return normalized.isBlank() ? defaultValue : normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
