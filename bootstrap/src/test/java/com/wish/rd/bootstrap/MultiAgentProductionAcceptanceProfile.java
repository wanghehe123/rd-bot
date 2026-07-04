package com.wish.rd.bootstrap;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 多 Agent 生产验收 smoke 的真实环境参数快照。
 *
 * <p>该类只存在于测试源码中，用于保证真实 smoke 入口不会在缺少生产等价前置条件时
 * 悄悄退化为 mock 或内存桩验证。
 */
record MultiAgentProductionAcceptanceProfile(
        String rdBotVersion,
        String environmentId,
        String executedBy,
        String baseUrl,
        String postgresUrl,
        String postgresUser,
        String postgresPassword,
        String repositoryUrl,
        String repoOwner,
        String repoName,
        String baseBranch,
        int expectedProviderCount,
        boolean autoExecute,
        String taskId,
        List<String> providerSecretEnvNames,
        String githubCodePlatformMode,
        String githubAuthMode,
        List<String> githubCredentialEnvNames,
        Duration requestTimeout,
        Duration completionTimeout,
        Duration pollInterval,
        String providerPreflightEvidenceJson,
        String feishuAlertEvidenceJson,
        String recoveryEvidenceJson,
        String skillPolicyEvidenceJson,
        String requirementReviewEvidenceJson,
        String dockerCodingEvidenceJson,
        String qaFailureEvidenceJson,
        String deliveryReviewFailureEvidenceJson,
        String githubPrRemoteEvidenceJson,
        String observabilityMetricsEvidenceJson,
        List<String> secretScanNeedles
) {

    private static final String GITHUB_MODE_REAL = "real";
    private static final String GITHUB_AUTH_APP = "GITHUB_APP";
    private static final String GITHUB_AUTH_PAT = "PAT_LOCAL_SMOKE";
    private static final String GITHUB_AUTH_CLI = "GH_CLI_LOCAL_SMOKE";
    private static final String GITHUB_AUTH_EXPECTATION =
            "GITHUB_APP|PAT_LOCAL_SMOKE|GH_CLI_LOCAL_SMOKE";

    private static final List<String> REQUIRED_PROPERTIES = List.of(
            "rd.multi-agent.smoke.production-evidence",
            "rd.multi-agent.smoke.rd-bot-version",
            "rd.multi-agent.smoke.environment-id",
            "rd.multi-agent.smoke.executed-by",
            "rd.multi-agent.smoke.base-url",
            "rd.multi-agent.smoke.postgres-url",
            "rd.multi-agent.smoke.postgres-user",
            "rd.multi-agent.smoke.postgres-password",
            "rd.multi-agent.smoke.repository-url",
            "rd.multi-agent.smoke.repo-owner",
            "rd.multi-agent.smoke.repo-name",
            "rd.multi-agent.smoke.expected-provider-count",
            "rd.multi-agent.smoke.github-code-platform-mode",
            "rd.multi-agent.smoke.github-auth-mode",
            "rd.multi-agent.smoke.secret-scan-needles"
    );

    MultiAgentProductionAcceptanceProfile {
        rdBotVersion = requireText(rdBotVersion, "rdBotVersion");
        environmentId = requireText(environmentId, "environmentId");
        executedBy = requireText(executedBy, "executedBy");
        baseUrl = requireHttpUrl(baseUrl, "baseUrl");
        postgresUrl = requirePostgresJdbcUrl(postgresUrl, "postgresUrl");
        postgresUser = requireText(postgresUser, "postgresUser");
        postgresPassword = requireText(postgresPassword, "postgresPassword");
        repositoryUrl = requireText(repositoryUrl, "repositoryUrl");
        repoOwner = requireText(repoOwner, "repoOwner");
        repoName = requireText(repoName, "repoName");
        repositoryUrl = requireRepositoryUrl(repositoryUrl, repoOwner, repoName);
        baseBranch = defaultWhenBlank(baseBranch, "main");
        if (expectedProviderCount < 2) {
            throw new IllegalArgumentException("expectedProviderCount must be at least 2");
        }
        taskId = safe(taskId);
        providerSecretEnvNames = providerSecretEnvNames == null
                ? List.of()
                : providerSecretEnvNames.stream().map(MultiAgentProductionAcceptanceProfile::safe).filter(
                        value -> !value.isBlank()).toList();
        if (providerSecretEnvNames.size() < expectedProviderCount) {
            throw new IllegalArgumentException("providerSecretEnvNames must cover expectedProviderCount");
        }
        if (distinct(providerSecretEnvNames).size() < expectedProviderCount) {
            throw new IllegalArgumentException("distinct providerSecretEnvNames must cover expectedProviderCount");
        }
        githubCodePlatformMode = requireRealGitHubMode(githubCodePlatformMode);
        githubAuthMode = requireGitHubAuthMode(githubAuthMode);
        githubCredentialEnvNames = githubCredentialEnvNames == null
                ? List.of()
                : githubCredentialEnvNames.stream().map(MultiAgentProductionAcceptanceProfile::safe).filter(
                        value -> !value.isBlank()).toList();
        int expectedGitHubCredentialCount = expectedGitHubCredentialCount(githubAuthMode);
        if (githubCredentialEnvNames.size() < expectedGitHubCredentialCount) {
            throw new IllegalArgumentException("githubCredentialEnvNames must cover githubAuthMode");
        }
        if (distinct(githubCredentialEnvNames).size() < expectedGitHubCredentialCount) {
            throw new IllegalArgumentException("distinct githubCredentialEnvNames must cover githubAuthMode");
        }
        requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        completionTimeout = requirePositive(completionTimeout, "completionTimeout");
        pollInterval = requirePositive(pollInterval, "pollInterval");
        providerPreflightEvidenceJson = safe(providerPreflightEvidenceJson);
        feishuAlertEvidenceJson = safe(feishuAlertEvidenceJson);
        recoveryEvidenceJson = safe(recoveryEvidenceJson);
        skillPolicyEvidenceJson = safe(skillPolicyEvidenceJson);
        requirementReviewEvidenceJson = safe(requirementReviewEvidenceJson);
        dockerCodingEvidenceJson = safe(dockerCodingEvidenceJson);
        qaFailureEvidenceJson = safe(qaFailureEvidenceJson);
        deliveryReviewFailureEvidenceJson = safe(deliveryReviewFailureEvidenceJson);
        githubPrRemoteEvidenceJson = safe(githubPrRemoteEvidenceJson);
        observabilityMetricsEvidenceJson = safe(observabilityMetricsEvidenceJson);
        secretScanNeedles = effectiveItems(secretScanNeedles);
        if (secretScanNeedles.isEmpty()) {
            throw new IllegalArgumentException("secretScanNeedles must include at least one effective value");
        }
    }

    static List<String> requiredPropertyNames() {
        return REQUIRED_PROPERTIES;
    }

    static MultiAgentProductionAcceptanceProfile from(Map<String, String> properties) {
        return from(properties, System.getenv());
    }

    static MultiAgentProductionAcceptanceProfile from(Map<String, String> properties, Map<String, String> env) {
        Map<String, String> safeProperties = properties == null ? Map.of() : properties;
        List<String> missing = missingRequiredProperties(safeProperties, env);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("missing production acceptance properties: " + missing);
        }
        return new MultiAgentProductionAcceptanceProfile(
                value(safeProperties, "rd.multi-agent.smoke.rd-bot-version"),
                value(safeProperties, "rd.multi-agent.smoke.environment-id"),
                value(safeProperties, "rd.multi-agent.smoke.executed-by"),
                value(safeProperties, "rd.multi-agent.smoke.base-url"),
                value(safeProperties, "rd.multi-agent.smoke.postgres-url"),
                value(safeProperties, "rd.multi-agent.smoke.postgres-user"),
                value(safeProperties, "rd.multi-agent.smoke.postgres-password"),
                value(safeProperties, "rd.multi-agent.smoke.repository-url"),
                value(safeProperties, "rd.multi-agent.smoke.repo-owner"),
                value(safeProperties, "rd.multi-agent.smoke.repo-name"),
                defaultWhenBlank(value(safeProperties, "rd.multi-agent.smoke.base-branch"), "main"),
                Integer.parseInt(value(safeProperties, "rd.multi-agent.smoke.expected-provider-count")),
                Boolean.parseBoolean(defaultWhenBlank(value(safeProperties, "rd.multi-agent.smoke.auto-execute"),
                        "true")),
                value(safeProperties, "rd.multi-agent.smoke.task-id"),
                splitCsv(value(safeProperties, "rd.multi-agent.smoke.provider-secret-env-names")),
                value(safeProperties, "rd.multi-agent.smoke.github-code-platform-mode"),
                value(safeProperties, "rd.multi-agent.smoke.github-auth-mode"),
                splitCsv(value(safeProperties, "rd.multi-agent.smoke.github-credential-env-names")),
                durationSeconds(safeProperties, "rd.multi-agent.smoke.request-timeout-seconds", 900),
                durationSeconds(safeProperties, "rd.multi-agent.smoke.completion-timeout-seconds", 1800),
                durationSeconds(safeProperties, "rd.multi-agent.smoke.poll-interval-seconds", 5),
                value(safeProperties, "rd.multi-agent.smoke.provider-preflight-evidence-json"),
                value(safeProperties, "rd.multi-agent.smoke.feishu-alert-evidence-json"),
                value(safeProperties, "rd.multi-agent.smoke.recovery-evidence-json"),
                value(safeProperties, "rd.multi-agent.smoke.skill-policy-evidence-json"),
                value(safeProperties, "rd.multi-agent.smoke.requirement-review-evidence-json"),
                value(safeProperties, "rd.multi-agent.smoke.docker-coding-evidence-json"),
                value(safeProperties, "rd.multi-agent.smoke.qa-failure-evidence-json"),
                value(safeProperties, "rd.multi-agent.smoke.delivery-review-failure-evidence-json"),
                value(safeProperties, "rd.multi-agent.smoke.github-pr-remote-evidence-json"),
                value(safeProperties, "rd.multi-agent.smoke.observability-metrics-evidence-json"),
                splitCsv(value(safeProperties, "rd.multi-agent.smoke.secret-scan-needles"))
        );
    }

    static List<String> missingRequiredProperties(Map<String, String> properties) {
        return missingRequiredProperties(properties, System.getenv());
    }

    static List<String> missingRequiredProperties(Map<String, String> properties, Map<String, String> env) {
        Map<String, String> safeProperties = properties == null ? Map.of() : properties;
        Map<String, String> safeEnv = env == null ? Map.of() : env;
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        for (String name : REQUIRED_PROPERTIES) {
            if (value(safeProperties, name).isBlank()) {
                missing.add(name);
            }
        }
        String productionEvidence = value(safeProperties, "rd.multi-agent.smoke.production-evidence");
        if (!productionEvidence.isBlank() && !"true".equalsIgnoreCase(productionEvidence)) {
            missing.remove("rd.multi-agent.smoke.production-evidence");
            missing.add("rd.multi-agent.smoke.production-evidence=true");
        }
        String baseUrl = value(safeProperties, "rd.multi-agent.smoke.base-url");
        if (!baseUrl.isBlank() && !isHttpUrl(baseUrl)) {
            missing.add("rd.multi-agent.smoke.base-url=http(s)://<host>");
        }
        String postgresUrl = value(safeProperties, "rd.multi-agent.smoke.postgres-url");
        if (!postgresUrl.isBlank() && !isPostgresJdbcUrl(postgresUrl)) {
            missing.add("rd.multi-agent.smoke.postgres-url=jdbc:postgresql://<host>/<database>");
        }
        String repositoryUrl = value(safeProperties, "rd.multi-agent.smoke.repository-url");
        String repoOwner = value(safeProperties, "rd.multi-agent.smoke.repo-owner");
        String repoName = value(safeProperties, "rd.multi-agent.smoke.repo-name");
        if (!repositoryUrl.isBlank() && !repoOwner.isBlank() && !repoName.isBlank()
                && !repositoryUrlMatches(repositoryUrl, repoOwner, repoName)) {
            missing.add("rd.multi-agent.smoke.repository-url matches repo-owner/repo-name");
        }
        String secretScanNeedles = value(safeProperties, "rd.multi-agent.smoke.secret-scan-needles");
        if (!secretScanNeedles.isBlank() && splitCsv(secretScanNeedles).isEmpty()) {
            missing.add("rd.multi-agent.smoke.secret-scan-needles>=1");
        }
        String providerCount = value(safeProperties, "rd.multi-agent.smoke.expected-provider-count");
        if (!providerCount.isBlank() && parseInt(providerCount) < 2) {
            missing.remove("rd.multi-agent.smoke.expected-provider-count");
            missing.add("rd.multi-agent.smoke.expected-provider-count>=2");
        }
        int expectedProviderCount = Math.max(2, parseInt(defaultWhenBlank(providerCount, "2")));
        List<String> providerEnvNames = splitCsv(value(safeProperties, "rd.multi-agent.smoke.provider-secret-env-names"));
        List<String> distinctProviderEnvNames = distinct(providerEnvNames);
        if (providerEnvNames.size() < expectedProviderCount) {
            missing.add("rd.multi-agent.smoke.provider-secret-env-names>=" + expectedProviderCount);
        } else if (distinctProviderEnvNames.size() < expectedProviderCount) {
            missing.add("rd.multi-agent.smoke.provider-secret-env-names distinct>=" + expectedProviderCount);
        } else {
            distinctProviderEnvNames.stream()
                    .filter(envName -> value(safeEnv, envName).isBlank())
                    .map(envName -> "env:" + envName)
                    .forEach(missing::add);
        }
        String githubMode = value(safeProperties, "rd.multi-agent.smoke.github-code-platform-mode");
        if (!githubMode.isBlank() && !GITHUB_MODE_REAL.equalsIgnoreCase(githubMode)) {
            missing.remove("rd.multi-agent.smoke.github-code-platform-mode");
            missing.add("rd.multi-agent.smoke.github-code-platform-mode=real");
        }
        String githubAuthMode = value(safeProperties, "rd.multi-agent.smoke.github-auth-mode");
        String normalizedGitHubAuthMode = normalizeGitHubAuthMode(githubAuthMode);
        if (!githubAuthMode.isBlank() && !isSupportedGitHubAuthMode(normalizedGitHubAuthMode)) {
            missing.remove("rd.multi-agent.smoke.github-auth-mode");
            missing.add("rd.multi-agent.smoke.github-auth-mode=" + GITHUB_AUTH_EXPECTATION);
        }
        int expectedGitHubCredentialCount = isSupportedGitHubAuthMode(normalizedGitHubAuthMode)
                ? expectedGitHubCredentialCount(normalizedGitHubAuthMode)
                : 1;
        List<String> githubCredentialEnvNames = splitCsv(value(
                safeProperties, "rd.multi-agent.smoke.github-credential-env-names"));
        List<String> distinctGitHubCredentialEnvNames = distinct(githubCredentialEnvNames);
        if (expectedGitHubCredentialCount == 0) {
            missing.remove("rd.multi-agent.smoke.github-credential-env-names");
        } else if (githubCredentialEnvNames.size() < expectedGitHubCredentialCount) {
            missing.add("rd.multi-agent.smoke.github-credential-env-names>=" + expectedGitHubCredentialCount);
        } else if (distinctGitHubCredentialEnvNames.size() < expectedGitHubCredentialCount) {
            missing.add("rd.multi-agent.smoke.github-credential-env-names distinct>="
                    + expectedGitHubCredentialCount);
        } else {
            distinctGitHubCredentialEnvNames.stream()
                    .filter(envName -> value(safeEnv, envName).isBlank())
                    .map(envName -> "env:" + envName)
                    .forEach(missing::add);
        }
        return List.copyOf(missing);
    }

    String describeMissingRequirements() {
        return """
                Required real production acceptance properties:
                - rd.multi-agent.smoke.production-evidence=true
                - rd.multi-agent.smoke.rd-bot-version
                - rd.multi-agent.smoke.environment-id
                - rd.multi-agent.smoke.executed-by
                - rd.multi-agent.smoke.base-url
                - rd.multi-agent.smoke.postgres-url
                - rd.multi-agent.smoke.postgres-user
                - rd.multi-agent.smoke.postgres-password
                - rd.multi-agent.smoke.repository-url
                - rd.multi-agent.smoke.repo-owner
                - rd.multi-agent.smoke.repo-name
                - rd.multi-agent.smoke.expected-provider-count>=2
                - rd.multi-agent.smoke.provider-secret-env-names
                - rd.multi-agent.smoke.github-code-platform-mode=real
                - rd.multi-agent.smoke.github-auth-mode=GITHUB_APP|PAT_LOCAL_SMOKE|GH_CLI_LOCAL_SMOKE
                - rd.multi-agent.smoke.github-credential-env-names (required for GITHUB_APP/PAT_LOCAL_SMOKE only)
                - rd.multi-agent.smoke.secret-scan-needles
                Optional:
                - rd.multi-agent.smoke.base-branch
                - rd.multi-agent.smoke.auto-execute
                - rd.multi-agent.smoke.task-id
                - rd.multi-agent.smoke.request-timeout-seconds
                - rd.multi-agent.smoke.completion-timeout-seconds
                - rd.multi-agent.smoke.poll-interval-seconds
                - rd.multi-agent.smoke.provider-preflight-evidence-json
                - rd.multi-agent.smoke.feishu-alert-evidence-json
                - rd.multi-agent.smoke.recovery-evidence-json
                - rd.multi-agent.smoke.skill-policy-evidence-json
                - rd.multi-agent.smoke.requirement-review-evidence-json
                - rd.multi-agent.smoke.docker-coding-evidence-json
                - rd.multi-agent.smoke.qa-failure-evidence-json
                - rd.multi-agent.smoke.delivery-review-failure-evidence-json
                - rd.multi-agent.smoke.github-pr-remote-evidence-json
                - rd.multi-agent.smoke.observability-metrics-evidence-json
                """.strip();
    }

    private static List<String> splitCsv(String value) {
        String safeValue = value == null ? "" : value.strip();
        if (safeValue.isBlank()) {
            return List.of();
        }
        return Arrays.stream(safeValue.split(","))
                .map(String::strip)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private static List<String> distinct(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new java.util.LinkedHashSet<>(values));
    }

    private static List<String> effectiveItems(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(MultiAgentProductionAcceptanceProfile::safe)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static String value(Map<String, String> properties, String name) {
        return properties.getOrDefault(name, "").strip();
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static Duration durationSeconds(Map<String, String> properties, String name, long defaultSeconds) {
        String raw = value(properties, name);
        long seconds = raw.isBlank() ? defaultSeconds : parseLong(raw);
        return Duration.ofSeconds(seconds);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.strip());
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    private static Duration requirePositive(Duration value, String fieldName) {
        Duration safeValue = value == null ? Duration.ZERO : value;
        if (safeValue.isZero() || safeValue.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return safeValue;
    }

    private static String requireRealGitHubMode(String value) {
        String normalized = safe(value);
        if (!GITHUB_MODE_REAL.equalsIgnoreCase(normalized)) {
            throw new IllegalArgumentException("githubCodePlatformMode must be real");
        }
        return GITHUB_MODE_REAL;
    }

    private static String requireGitHubAuthMode(String value) {
        String normalized = normalizeGitHubAuthMode(value);
        if (!isSupportedGitHubAuthMode(normalized)) {
            throw new IllegalArgumentException("githubAuthMode must be one of " + GITHUB_AUTH_EXPECTATION);
        }
        return normalized;
    }

    private static String requireHttpUrl(String value, String fieldName) {
        String normalized = requireText(value, fieldName);
        if (!isHttpUrl(normalized)) {
            throw new IllegalArgumentException(fieldName + " must be http(s) URL with host");
        }
        return normalized;
    }

    private static String requirePostgresJdbcUrl(String value, String fieldName) {
        String normalized = requireText(value, fieldName);
        if (!isPostgresJdbcUrl(normalized)) {
            throw new IllegalArgumentException(fieldName + " must be jdbc:postgresql URL");
        }
        return normalized;
    }

    private static String requireRepositoryUrl(String value, String repoOwner, String repoName) {
        String normalized = requireText(value, "repositoryUrl");
        if (!repositoryUrlMatches(normalized, repoOwner, repoName)) {
            throw new IllegalArgumentException("repositoryUrl must match repoOwner/repoName");
        }
        return normalized;
    }

    private static boolean isHttpUrl(String value) {
        try {
            java.net.URI uri = new java.net.URI(safe(value));
            String scheme = safe(uri.getScheme()).toLowerCase(java.util.Locale.ROOT);
            String host = safe(uri.getHost());
            return ("http".equals(scheme) || "https".equals(scheme)) && !host.isBlank();
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean isPostgresJdbcUrl(String value) {
        return safe(value).toLowerCase(java.util.Locale.ROOT).startsWith("jdbc:postgresql://");
    }

    private static boolean repositoryUrlMatches(String repositoryUrl, String repoOwner, String repoName) {
        if (!isHttpUrl(repositoryUrl)) {
            return false;
        }
        try {
            java.net.URI uri = new java.net.URI(safe(repositoryUrl));
            String path = safe(uri.getPath()).toLowerCase(java.util.Locale.ROOT);
            if (path.endsWith(".git")) {
                path = path.substring(0, path.length() - 4);
            }
            String expectedPath = "/" + safePathSegment(repoOwner) + "/" + safePathSegment(repoName);
            return !expectedPath.equals("//") && path.equals(expectedPath);
        } catch (Exception exception) {
            return false;
        }
    }

    private static String safePathSegment(String value) {
        return safe(value).toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isSupportedGitHubAuthMode(String value) {
        return GITHUB_AUTH_APP.equals(value) || GITHUB_AUTH_PAT.equals(value) || GITHUB_AUTH_CLI.equals(value);
    }

    private static int expectedGitHubCredentialCount(String authMode) {
        if (GITHUB_AUTH_APP.equals(authMode)) {
            return 3;
        }
        if (GITHUB_AUTH_PAT.equals(authMode)) {
            return 1;
        }
        return 0;
    }

    private static String normalizeGitHubAuthMode(String value) {
        return safe(value).replace('-', '_').toUpperCase(java.util.Locale.ROOT);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = safe(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String defaultWhenBlank(String value, String defaultValue) {
        String normalized = safe(value);
        return normalized.isBlank() ? defaultValue : normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
