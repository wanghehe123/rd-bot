package com.wish.rd.engine.merge;

/**
 * 代码评审请求的合并状态快照。
 *
 * <p>由外部代码平台适配器返回，供 {@link RepairTaskMergeSyncEngine} 判断是否可以
 * 将 RD 任务从 {@code COMMITTED} 推进到 {@code MERGED}。
 *
 * @param pullRequestUrl    PR 访问地址
 * @param repository        仓库全名，格式为 owner/name
 * @param pullRequestNumber PR 编号
 * @param state             代码平台状态，例如 open/closed
 * @param merged            PR 是否已经合并
 */
public record PullRequestMergeStatus(
        String pullRequestUrl,
        String repository,
        String pullRequestNumber,
        String state,
        boolean merged
) {

    public PullRequestMergeStatus {
        pullRequestUrl = safe(pullRequestUrl);
        repository = safe(repository);
        pullRequestNumber = safe(pullRequestNumber);
        state = safe(state);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
