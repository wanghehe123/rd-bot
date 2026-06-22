package com.wish.rd.exec.repair.code;

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
}
