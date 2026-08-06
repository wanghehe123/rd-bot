package com.wish.rd.exec.repair.code;

import com.wish.rd.exec.repair.code.model.BranchHeadResult;
import com.wish.rd.exec.repair.code.model.CreatePullRequestCommand;
import com.wish.rd.exec.repair.code.model.FindBranchHeadCommand;
import com.wish.rd.exec.repair.code.model.FindOpenPullRequestCommand;
import com.wish.rd.exec.repair.code.model.PullRequestResult;

import java.util.Optional;


/**
 * 代码平台端口，负责把修复分支和执行产物发布为代码评审请求。
 */
public interface CodePlatformPort {

    /**
     * 创建代码评审请求。
     *
     * @param command 创建请求命令
     * @return 创建后的评审请求结果
     */
    PullRequestResult createPullRequest(CreatePullRequestCommand command);

    /**
     * Finds an open pull request for the given head/base pair when one already exists.
     *
     * <p>Default returns empty so existing adapters remain source-compatible until
     * they implement remote open-PR lookup for publication reconciliation.
     *
     * @param command open-PR lookup command
     * @return matching open pull request when present
     */
    default Optional<PullRequestResult> findOpenPullRequest(FindOpenPullRequestCommand command) {
        return Optional.empty();
    }

    /**
     * Finds the tip commit SHA for a remote branch when it exists.
     *
     * <p>Default returns empty so existing adapters remain source-compatible until
     * they implement branch-head lookup for push-timeout reconciliation.
     *
     * @param command branch-head lookup command
     * @return branch tip when present
     */
    default Optional<BranchHeadResult> findBranchHead(FindBranchHeadCommand command) {
        return Optional.empty();
    }
}
