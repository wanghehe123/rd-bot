package com.wish.rd.bootstrap.github.impl;

import com.wish.rd.bootstrap.github.GitHubCodePlatformProperties;

import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.code.model.BranchHeadResult;
import com.wish.rd.exec.repair.code.model.CreatePullRequestCommand;
import com.wish.rd.exec.repair.code.model.FindBranchHeadCommand;
import com.wish.rd.exec.repair.code.model.FindOpenPullRequestCommand;
import com.wish.rd.exec.repair.code.model.PullRequestResult;
import com.wish.rd.engine.merge.model.PullRequestMergeStatus;
import com.wish.rd.engine.merge.PullRequestMergeStatusPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 默认 GitHub mock 适配器，供本地和测试环境在无 GitHub 凭据时保持可启动。
 */
@Component
@ConditionalOnProperty(prefix = "rd.github.code-platform", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockGitHubCodePlatformAdapter implements CodePlatformPort, PullRequestMergeStatusPort {

    private final GitHubCodePlatformProperties properties;

    /**
     * 创建 mock GitHub 适配器。
     *
     * @param properties GitHub 代码平台配置
     */
    public MockGitHubCodePlatformAdapter(GitHubCodePlatformProperties properties) {
        this.properties = properties == null ? new GitHubCodePlatformProperties() : properties;
    }

    @Override
    public PullRequestResult createPullRequest(CreatePullRequestCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        String pullRequestNumber = command.workBranch().replace('/', '-');
        String repository = command.repoOwner() + "/" + command.repoName();
        String url = "%s/%s/pull/%s".formatted(
                properties.getWebBaseUrl().replaceAll("/+$", ""),
                repository,
                pullRequestNumber
        );
        return new PullRequestResult(
                url,
                pullRequestNumber,
                Map.of(
                        "provider", "mock",
                        "repository", repository
                )
        );
    }

    @Override
    public Optional<PullRequestResult> findOpenPullRequest(FindOpenPullRequestCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return Optional.empty();
    }

    @Override
    public Optional<BranchHeadResult> findBranchHead(FindBranchHeadCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return Optional.empty();
    }

    @Override
    public PullRequestMergeStatus findByUrl(String pullRequestUrl) {
        return new PullRequestMergeStatus(pullRequestUrl, "", "", "open", false);
    }
}
