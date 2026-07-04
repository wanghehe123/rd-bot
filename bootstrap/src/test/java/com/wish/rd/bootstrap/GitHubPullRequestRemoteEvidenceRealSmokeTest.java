package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GitHub PR 远端 body 反查 smoke：默认跳过，显式开启才执行真实 GitHub REST 请求。
 *
 * <p>执行命令：
 * <pre>
 * GITHUB_PAT="$(gh auth token)" ./mvnw -pl bootstrap -am \
 *   -Dtest=GitHubPullRequestRemoteEvidenceRealSmokeTest \
 *   -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Drd.integration.github-pr-evidence.enabled=true \
 *   -Drd.github.pr-evidence.production-evidence=true \
 *   -Drd.github.pr-evidence.rd-bot-version=<rd-bot-version> \
 *   -Drd.github.pr-evidence.environment-id=<production-environment-id> \
 *   -Drd.github.pr-evidence.executed-by=<operator> \
 *   -Drd.github.pr-evidence.task-id=<task-id> \
 *   -Drd.github.pr-evidence.repo-owner=<owner> \
 *   -Drd.github.pr-evidence.repo-name=<repo> \
 *   -Drd.github.pr-evidence.base-branch=main \
 *   -Drd.github.pr-evidence.work-branch=requirement/<task-id> \
 *   -Drd.github.pr-evidence.pull-number=<pull-number> \
 *   -Drd.github.pr-evidence.secret-scan-needles=<secret-scan-needles> \
 *   test
 * </pre>
 */
@EnabledIfSystemProperty(named = "rd.integration.github-pr-evidence.enabled", matches = "true")
class GitHubPullRequestRemoteEvidenceRealSmokeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void fetchesAndValidatesRemotePullRequestBody() throws Exception {
        GitHubPullRequestRemoteEvidenceReport report =
                GitHubPullRequestRemoteEvidenceReport.fromSystemProperties();
        Map<String, String> smokeProperties = smokeProperties();
        List<String> missing = missingRequiredProperties(smokeProperties, System.getenv());
        if (!missing.isEmpty()) {
            Path reportPath = report.writeSkipped(missing);
            throw new AssertionError("github-pr-remote-evidence production smoke requires real properties: "
                    + missing + " report=" + reportPath);
        }

        String rdBotVersion = value(smokeProperties, "rd.github.pr-evidence.rd-bot-version");
        String environmentId = value(smokeProperties, "rd.github.pr-evidence.environment-id");
        String executedBy = value(smokeProperties, "rd.github.pr-evidence.executed-by");
        String taskId = value(smokeProperties, "rd.github.pr-evidence.task-id");
        String owner = value(smokeProperties, "rd.github.pr-evidence.repo-owner");
        String repo = value(smokeProperties, "rd.github.pr-evidence.repo-name");
        String baseBranch = defaultWhenBlank(value(smokeProperties, "rd.github.pr-evidence.base-branch"), "main");
        String workBranch = value(smokeProperties, "rd.github.pr-evidence.work-branch");
        String pullNumber = value(smokeProperties, "rd.github.pr-evidence.pull-number");
        List<String> secretNeedles = parseCsv(value(smokeProperties, "rd.github.pr-evidence.secret-scan-needles"));
        String token = firstNonBlank(
                value(smokeProperties, "rd.github.pr-evidence.pat-token"),
                System.getenv("GITHUB_PAT")
        );

        GitHubPullRequestRemoteEvidenceReport.RemotePrEvidence evidence =
                new GitHubPullRequestRemoteEvidenceReport.RemotePrEvidence(
                        rdBotVersion,
                        environmentId,
                        executedBy,
                        taskId,
                        owner,
                        repo,
                        baseBranch,
                        workBranch,
                        "",
                        pullNumber,
                        "",
                        secretNeedles
                );

        try {
            JsonNode pullRequest = fetchPullRequest(owner, repo, pullNumber, token);
            evidence = new GitHubPullRequestRemoteEvidenceReport.RemotePrEvidence(
                    rdBotVersion,
                    environmentId,
                    executedBy,
                    taskId,
                    owner,
                    repo,
                    baseBranch,
                    workBranch,
                    pullRequest.path("html_url").asText(""),
                    pullRequest.path("number").asText(""),
                    pullRequest.path("body").asText(""),
                    secretNeedles
            );
            validateRemoteBranchRefs(evidence, pullRequest);
            Path reportPath = report.writePassed(evidence);
            System.out.println("[smoke] github remote PR evidence pullRequestUrl="
                    + evidence.pullRequestUrl()
                    + " repository=" + evidence.repository()
                    + " report=" + reportPath);
        } catch (Throwable failure) {
            Path reportPath = writeFailedReport(report, evidence, failure);
            throw new AssertionError("github-pr-remote-evidence production smoke failed; report="
                    + reportPath, failure);
        }
    }

    static List<String> missingRequiredProperties(Map<String, String> properties, Map<String, String> env) {
        Map<String, String> safeProperties = properties == null ? Map.of() : properties;
        Map<String, String> safeEnv = env == null ? Map.of() : env;
        ArrayList<String> missing = new ArrayList<>();
        String productionEvidence = value(safeProperties, "rd.github.pr-evidence.production-evidence");
        if (productionEvidence.isBlank()) {
            missing.add("rd.github.pr-evidence.production-evidence");
        } else if (!"true".equalsIgnoreCase(productionEvidence)) {
            missing.add("rd.github.pr-evidence.production-evidence=true");
        }
        requireProperty(safeProperties, missing, "rd.github.pr-evidence.rd-bot-version");
        requireProperty(safeProperties, missing, "rd.github.pr-evidence.environment-id");
        requireProperty(safeProperties, missing, "rd.github.pr-evidence.executed-by");
        requireProperty(safeProperties, missing, "rd.github.pr-evidence.task-id");
        requireProperty(safeProperties, missing, "rd.github.pr-evidence.repo-owner");
        requireProperty(safeProperties, missing, "rd.github.pr-evidence.repo-name");
        requireProperty(safeProperties, missing, "rd.github.pr-evidence.work-branch");
        String taskId = value(safeProperties, "rd.github.pr-evidence.task-id");
        String workBranch = value(safeProperties, "rd.github.pr-evidence.work-branch");
        if (!taskId.isBlank() && !workBranch.isBlank() && !("requirement/" + taskId).equals(workBranch)) {
            missing.add("rd.github.pr-evidence.work-branch=requirement/<task-id>");
        }
        String pullNumber = value(safeProperties, "rd.github.pr-evidence.pull-number");
        if (pullNumber.isBlank() || !pullNumber.matches("\\d+")) {
            missing.add("rd.github.pr-evidence.pull-number");
        }
        if (parseCsv(value(safeProperties, "rd.github.pr-evidence.secret-scan-needles")).isEmpty()) {
            missing.add("rd.github.pr-evidence.secret-scan-needles");
        }
        if (firstNonBlank(value(safeProperties, "rd.github.pr-evidence.pat-token"),
                value(safeEnv, "GITHUB_PAT")).isBlank()) {
            missing.add("env:GITHUB_PAT|rd.github.pr-evidence.pat-token");
        }
        return List.copyOf(missing);
    }

    private static Map<String, String> smokeProperties() {
        return Map.ofEntries(
                Map.entry("rd.github.pr-evidence.production-evidence", System.getProperty(
                        "rd.github.pr-evidence.production-evidence", "")),
                Map.entry("rd.github.pr-evidence.rd-bot-version", System.getProperty(
                        "rd.github.pr-evidence.rd-bot-version", "")),
                Map.entry("rd.github.pr-evidence.environment-id", System.getProperty(
                        "rd.github.pr-evidence.environment-id", "")),
                Map.entry("rd.github.pr-evidence.executed-by", System.getProperty(
                        "rd.github.pr-evidence.executed-by", "")),
                Map.entry("rd.github.pr-evidence.task-id", System.getProperty(
                        "rd.github.pr-evidence.task-id", "")),
                Map.entry("rd.github.pr-evidence.repo-owner", System.getProperty(
                        "rd.github.pr-evidence.repo-owner", "")),
                Map.entry("rd.github.pr-evidence.repo-name", System.getProperty(
                        "rd.github.pr-evidence.repo-name", "")),
                Map.entry("rd.github.pr-evidence.base-branch", System.getProperty(
                        "rd.github.pr-evidence.base-branch", "main")),
                Map.entry("rd.github.pr-evidence.work-branch", System.getProperty(
                        "rd.github.pr-evidence.work-branch", "")),
                Map.entry("rd.github.pr-evidence.pull-number", System.getProperty(
                        "rd.github.pr-evidence.pull-number", "")),
                Map.entry("rd.github.pr-evidence.secret-scan-needles", System.getProperty(
                        "rd.github.pr-evidence.secret-scan-needles", "")),
                Map.entry("rd.github.pr-evidence.pat-token", System.getProperty(
                        "rd.github.pr-evidence.pat-token", ""))
        );
    }

    private static JsonNode fetchPullRequest(
            String owner,
            String repo,
            String pullNumber,
            String token
    ) throws IOException, InterruptedException {
        String apiUrl = "https://api.github.com/repos/%s/%s/pulls/%s".formatted(
                path(owner),
                path(repo),
                path(pullNumber)
        );
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("GitHub get pull request failed with status " + response.statusCode());
        }
        return OBJECT_MAPPER.readTree(response.body());
    }

    private static void validateRemoteBranchRefs(
            GitHubPullRequestRemoteEvidenceReport.RemotePrEvidence evidence,
            JsonNode pullRequest
    ) {
        String baseRef = pullRequest.path("base").path("ref").asText("");
        String headRef = pullRequest.path("head").path("ref").asText("");
        if (!evidence.baseBranch().equals(baseRef)) {
            throw new IllegalArgumentException("remote PR base branch does not match expected baseBranch");
        }
        if (!evidence.workBranch().equals(headRef)) {
            throw new IllegalArgumentException("remote PR head branch does not match expected workBranch");
        }
    }

    private static Path writeFailedReport(
            GitHubPullRequestRemoteEvidenceReport report,
            GitHubPullRequestRemoteEvidenceReport.RemotePrEvidence evidence,
            Throwable failure
    ) {
        try {
            return report.writeFailed(evidence, failure);
        } catch (IOException reportFailure) {
            throw new AssertionError("github-pr-remote-evidence production smoke failed and failed to write report",
                    reportFailure);
        }
    }

    private static void requireProperty(Map<String, String> properties, List<String> missing, String name) {
        if (value(properties, name).isBlank()) {
            missing.add(name);
        }
    }

    private static List<String> parseCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private static String defaultWhenBlank(String value, String defaultValue) {
        String normalized = value == null ? "" : value.strip();
        return normalized.isBlank() ? defaultValue : normalized;
    }

    private static String value(Map<String, String> values, String name) {
        return values.getOrDefault(name, "").strip();
    }

    private static String firstNonBlank(String first, String second) {
        String normalizedFirst = first == null ? "" : first.strip();
        if (!normalizedFirst.isBlank()) {
            return normalizedFirst;
        }
        return second == null ? "" : second.strip();
    }

    private static String path(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
