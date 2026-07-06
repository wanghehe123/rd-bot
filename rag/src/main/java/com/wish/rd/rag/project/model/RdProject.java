package com.wish.rd.rag.project.model;

/**
 * RD 项目配置快照。
 *
 * <p>供项目管理接口、任务创建和执行链路读取仓库、分支与项目标识。项目数据必须由
 * {@link RdProjectStore} 持久化，不提供 in-memory 生产实现。
 *
 * @param projectId             项目 ID
 * @param projectKey            业务唯一标识
 * @param name                  项目名称
 * @param description           项目描述
 * @param repositoryUrl         Git 仓库地址
 * @param repoOwner             仓库 owner
 * @param repoName              仓库名
 * @param defaultBranch         默认基准分支
 * @param enabled               是否启用
 * @param deleted               是否逻辑删除
 * @param createTimeEpochMillis 创建时间
 * @param updateTimeEpochMillis 更新时间
 */
public record RdProject(
        String projectId,
        String projectKey,
        String name,
        String description,
        String repositoryUrl,
        String repoOwner,
        String repoName,
        String defaultBranch,
        boolean enabled,
        boolean deleted,
        long createTimeEpochMillis,
        long updateTimeEpochMillis
) {

    public RdProject {
        projectId = safe(projectId);
        projectKey = safe(projectKey);
        name = safe(name);
        description = safe(description);
        repositoryUrl = safe(repositoryUrl);
        repoOwner = safe(repoOwner);
        repoName = safe(repoName);
        defaultBranch = safe(defaultBranch);
    }

    /**
     * 返回更新可编辑字段后的项目快照。
     *
     * @param command               更新命令
     * @param repoOwner             解析后的仓库 owner
     * @param repoName              解析后的仓库名
     * @param updateTimeEpochMillis 更新时间
     * @return 新项目快照
     */
    public RdProject withUpdatedFields(
            RdProjectCommand command,
            String repoOwner,
            String repoName,
            long updateTimeEpochMillis
    ) {
        return new RdProject(
                projectId,
                command.projectKey(),
                command.name(),
                command.description(),
                command.repositoryUrl(),
                repoOwner,
                repoName,
                command.defaultBranch(),
                command.enabled(),
                deleted,
                createTimeEpochMillis,
                updateTimeEpochMillis
        );
    }

    /**
     * 返回逻辑删除后的项目快照。
     *
     * @param updateTimeEpochMillis 更新时间
     * @return deleted=true 且 enabled=false 的项目快照
     */
    public RdProject deleted(long updateTimeEpochMillis) {
        return new RdProject(
                projectId,
                projectKey,
                name,
                description,
                repositoryUrl,
                repoOwner,
                repoName,
                defaultBranch,
                false,
                true,
                createTimeEpochMillis,
                updateTimeEpochMillis
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
