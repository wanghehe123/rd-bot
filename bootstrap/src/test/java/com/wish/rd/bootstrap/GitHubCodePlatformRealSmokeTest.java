package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.github.GitHubCodePlatformAdapter;
import com.wish.rd.bootstrap.github.GitHubCodePlatformProperties;
import com.wish.rd.exec.repair.code.CreatePullRequestCommand;
import com.wish.rd.exec.repair.code.PullRequestResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GitHub 真实 PR 创建冒烟测试：默认跳过，显式开启才执行。
 *
 * <p>该测试只验证现有 {@link GitHubCodePlatformAdapter} 的 GitHub REST PR 创建路径。
 * 测试仓库、base 分支和 work 分支应在测试前用 {@code gh} / {@code git} 准备好；
 * 这里不引入 Java gh 适配器。
 *
 * <p>执行命令：
 * <pre>
 * GITHUB_PAT="$(gh auth token)" ./mvnw -pl bootstrap -am \
 *   -Dtest=GitHubCodePlatformRealSmokeTest \
 *   -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Drd.integration.github.enabled=true \
 *   -Drd.github.smoke.production-evidence=true \
 *   -Drd.github.smoke.rd-bot-version=<rd-bot-version> \
 *   -Drd.github.smoke.environment-id=<production-environment-id> \
 *   -Drd.github.smoke.executed-by=<operator> \
 *   -Drd.github.smoke.repo-owner=wanghehe123 \
 *   -Drd.github.smoke.repo-name=rd-bot-pr-smoke-20260623 \
 *   -Drd.github.smoke.base-branch=main \
 *   -Drd.github.smoke.work-branch=repair/smoke-20260623 \
 *   test
 * </pre>
 */
@EnabledIfSystemProperty(named = "rd.integration.github.enabled", matches = "true")
class GitHubCodePlatformRealSmokeTest {

    @Test
    void createsPullRequestWithExistingJavaAdapter() throws Exception {
        GitHubCodePlatformProductionAcceptanceReport report =
                GitHubCodePlatformProductionAcceptanceReport.fromSystemProperties();
        Map<String, String> smokeProperties = smokeProperties();
        List<String> missing = missingRequiredProperties(smokeProperties, System.getenv());
        if (!missing.isEmpty()) {
            Path reportPath = report.writeSkipped(missing);
            throw new AssertionError("github-code-platform production smoke requires real properties: "
                    + missing + " report=" + reportPath);
        }
        String rdBotVersion = value(smokeProperties, "rd.github.smoke.rd-bot-version");
        String environmentId = value(smokeProperties, "rd.github.smoke.environment-id");
        String executedBy = value(smokeProperties, "rd.github.smoke.executed-by");
        String owner = value(smokeProperties, "rd.github.smoke.repo-owner");
        String repo = value(smokeProperties, "rd.github.smoke.repo-name");
        String baseBranch = defaultWhenBlank(value(smokeProperties, "rd.github.smoke.base-branch"), "main");
        String workBranch = value(smokeProperties, "rd.github.smoke.work-branch");
        String token = firstNonBlank(
                value(smokeProperties, "rd.github.code-platform.pat-token"),
                System.getenv("GITHUB_PAT")
        );

        GitHubCodePlatformProperties githubProperties = new GitHubCodePlatformProperties();
        githubProperties.setMode(GitHubCodePlatformProperties.Mode.REAL);
        githubProperties.setAuthMode(GitHubCodePlatformProperties.AuthMode.PAT_LOCAL_SMOKE);
        githubProperties.setAllowedRepositories(List.of(owner + "/" + repo));
        githubProperties.setPatToken(token);

        GitHubCodePlatformAdapter adapter = new GitHubCodePlatformAdapter(githubProperties, new ObjectMapper());
        GitHubCodePlatformProductionAcceptanceReport.GitHubPrSmokeEvidence evidence =
                new GitHubCodePlatformProductionAcceptanceReport.GitHubPrSmokeEvidence(
                        rdBotVersion,
                        environmentId,
                        executedBy,
                        owner,
                        repo,
                        baseBranch,
                        workBranch,
                        "",
                        "",
                        true,
                        List.of(token)
                );

        try {
            PullRequestResult result = adapter.createPullRequest(new CreatePullRequestCommand(
                    owner,
                    repo,
                    baseBranch,
                    workBranch,
                    "RD-Bot real PR smoke",
                    "Created by RD-Bot GitHubCodePlatformAdapter real smoke test.",
                    List.of(),
                    Map.of("smoke", "true", "preparedBy", "gh-cli")
            ));

            evidence = new GitHubCodePlatformProductionAcceptanceReport.GitHubPrSmokeEvidence(
                    rdBotVersion,
                    environmentId,
                    executedBy,
                    owner,
                    repo,
                    baseBranch,
                    workBranch,
                    result.pullRequestUrl(),
                    result.pullRequestNumber(),
                    true,
                    List.of(token)
            );

            assertFalse(result.pullRequestUrl().isBlank(), "pull request URL must not be blank");
            assertTrue(
                    result.pullRequestUrl().contains("/" + owner + "/" + repo + "/pull/"),
                    "pull request URL should point to the configured smoke repository"
            );
            assertFalse(result.pullRequestNumber().isBlank(), "pull request number must not be blank");
            Path reportPath = report.writePassed(evidence);
            System.out.println("[smoke] github pullRequestUrl=" + result.pullRequestUrl()
                    + " number=" + result.pullRequestNumber()
                    + " repository=" + owner + "/" + repo
                    + " head=" + workBranch
                    + " base=" + baseBranch
                    + " report=" + reportPath);
        } catch (Throwable failure) {
            Path reportPath = writeFailedReport(report, evidence, failure);
            throw new AssertionError("github-code-platform production smoke failed; report=" + reportPath, failure);
        }
    }

    static List<String> missingRequiredProperties(Map<String, String> properties, Map<String, String> env) {
        Map<String, String> safeProperties = properties == null ? Map.of() : properties;
        Map<String, String> safeEnv = env == null ? Map.of() : env;
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        String productionEvidence = value(safeProperties, "rd.github.smoke.production-evidence");
        if (productionEvidence.isBlank()) {
            missing.add("rd.github.smoke.production-evidence");
        } else if (!"true".equalsIgnoreCase(productionEvidence)) {
            missing.add("rd.github.smoke.production-evidence=true");
        }
        requireProperty(safeProperties, missing, "rd.github.smoke.rd-bot-version");
        requireProperty(safeProperties, missing, "rd.github.smoke.environment-id");
        requireProperty(safeProperties, missing, "rd.github.smoke.executed-by");
        requireProperty(safeProperties, missing, "rd.github.smoke.repo-owner");
        requireProperty(safeProperties, missing, "rd.github.smoke.repo-name");
        requireProperty(safeProperties, missing, "rd.github.smoke.work-branch");
        String baseBranch = defaultWhenBlank(value(safeProperties, "rd.github.smoke.base-branch"), "main");
        String workBranch = value(safeProperties, "rd.github.smoke.work-branch");
        if (!workBranch.isBlank() && workBranch.equals(baseBranch)) {
            missing.add("rd.github.smoke.work-branch!=rd.github.smoke.base-branch");
        }
        if (firstNonBlank(value(safeProperties, "rd.github.code-platform.pat-token"),
                value(safeEnv, "GITHUB_PAT")).isBlank()) {
            missing.add("env:GITHUB_PAT|rd.github.code-platform.pat-token");
        }
        return List.copyOf(missing);
    }

    private static Map<String, String> smokeProperties() {
        return Map.of(
                "rd.github.smoke.production-evidence", System.getProperty(
                        "rd.github.smoke.production-evidence", ""),
                "rd.github.smoke.rd-bot-version", System.getProperty("rd.github.smoke.rd-bot-version", ""),
                "rd.github.smoke.environment-id", System.getProperty("rd.github.smoke.environment-id", ""),
                "rd.github.smoke.executed-by", System.getProperty("rd.github.smoke.executed-by", ""),
                "rd.github.smoke.repo-owner", System.getProperty("rd.github.smoke.repo-owner", ""),
                "rd.github.smoke.repo-name", System.getProperty("rd.github.smoke.repo-name", ""),
                "rd.github.smoke.base-branch", System.getProperty("rd.github.smoke.base-branch", "main"),
                "rd.github.smoke.work-branch", System.getProperty("rd.github.smoke.work-branch", ""),
                "rd.github.code-platform.pat-token", System.getProperty("rd.github.code-platform.pat-token", "")
        );
    }

    private static Path writeFailedReport(
            GitHubCodePlatformProductionAcceptanceReport report,
            GitHubCodePlatformProductionAcceptanceReport.GitHubPrSmokeEvidence evidence,
            Throwable failure
    ) {
        try {
            return report.writeFailed(evidence, failure);
        } catch (IOException reportFailure) {
            throw new AssertionError("github-code-platform production smoke failed and failed to write report",
                    reportFailure);
        }
    }

    private static void requireProperty(
            Map<String, String> properties,
            List<String> missing,
            String name
    ) {
        if (value(properties, name).isBlank()) {
            missing.add(name);
        }
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
}
