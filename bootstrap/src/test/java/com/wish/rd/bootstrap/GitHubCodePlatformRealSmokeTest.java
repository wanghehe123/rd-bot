package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.github.GitHubCodePlatformAdapter;
import com.wish.rd.bootstrap.github.GitHubCodePlatformProperties;
import com.wish.rd.exec.repair.code.CreatePullRequestCommand;
import com.wish.rd.exec.repair.code.PullRequestResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    void createsPullRequestWithExistingJavaAdapter() {
        String owner = requiredProperty("rd.github.smoke.repo-owner");
        String repo = requiredProperty("rd.github.smoke.repo-name");
        String baseBranch = propertyOrDefault("rd.github.smoke.base-branch", "main");
        String workBranch = requiredProperty("rd.github.smoke.work-branch");
        String token = firstNonBlank(
                System.getProperty("rd.github.code-platform.pat-token", ""),
                System.getenv("GITHUB_PAT")
        );
        assumeTrue(!token.isBlank(), "GITHUB_PAT or rd.github.code-platform.pat-token must be set");

        GitHubCodePlatformProperties properties = new GitHubCodePlatformProperties();
        properties.setMode(GitHubCodePlatformProperties.Mode.REAL);
        properties.setAuthMode(GitHubCodePlatformProperties.AuthMode.PAT_LOCAL_SMOKE);
        properties.setAllowedRepositories(List.of(owner + "/" + repo));
        properties.setPatToken(token);

        GitHubCodePlatformAdapter adapter = new GitHubCodePlatformAdapter(properties, new ObjectMapper());
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

        assertFalse(result.pullRequestUrl().isBlank(), "pull request URL must not be blank");
        assertTrue(
                result.pullRequestUrl().contains("/" + owner + "/" + repo + "/pull/"),
                "pull request URL should point to the configured smoke repository"
        );
        assertFalse(result.pullRequestNumber().isBlank(), "pull request number must not be blank");
        System.out.println("[smoke] github pullRequestUrl=" + result.pullRequestUrl()
                + " number=" + result.pullRequestNumber()
                + " repository=" + owner + "/" + repo
                + " head=" + workBranch
                + " base=" + baseBranch);
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name, "").strip();
        assumeTrue(!value.isBlank(), name + " must be set");
        return value;
    }

    private static String propertyOrDefault(String name, String defaultValue) {
        String value = System.getProperty(name, "").strip();
        return value.isBlank() ? defaultValue : value;
    }

    private static String firstNonBlank(String first, String second) {
        String normalizedFirst = first == null ? "" : first.strip();
        if (!normalizedFirst.isBlank()) {
            return normalizedFirst;
        }
        return second == null ? "" : second.strip();
    }
}
