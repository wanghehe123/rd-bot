package com.wish.rd.engine.requirement;

/**
 * 需求交付 PR 发布端口。
 *
 * <p>该端口由控制面在交付复核通过后调用，具体 GitHub、GitLab 或其他代码平台由 bootstrap 适配。
 */
public interface RequirementPullRequestPublisherPort {

    /**
     * 发布已复核的需求交付 PR。
     *
     * @param command 发布命令
     * @return 发布结果
     */
    RequirementPullRequestPublication publish(RequirementPullRequestPublishCommand command);

    /**
     * 不可用端口。
     */
    static RequirementPullRequestPublisherPort unavailable() {
        return command -> RequirementPullRequestPublication.failure(
                command == null ? "" : command.taskId(),
                "requirement pull request publisher unavailable"
        );
    }
}
