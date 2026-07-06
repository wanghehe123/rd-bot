package com.wish.rd.engine.merge;

import com.wish.rd.engine.merge.model.PullRequestMergeStatus;


/**
 * PR 合并状态查询端口。
 *
 * <p>engine 只依赖该端口；GitHub、GitLab 或其他代码平台的 HTTP/API 细节由
 * bootstrap 适配层实现。
 */
@FunctionalInterface
public interface PullRequestMergeStatusPort {

    /**
     * 根据 PR URL 查询当前合并状态。
     *
     * @param pullRequestUrl PR URL
     * @return 合并状态快照
     */
    PullRequestMergeStatus findByUrl(String pullRequestUrl);
}
