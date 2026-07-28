package com.wish.rd.engine.requirement;

import com.wish.rd.engine.requirement.model.RequirementBranchPublication;
import com.wish.rd.engine.requirement.model.RequirementBranchPublishCommand;

/**
 * 需求交付工作分支推送端口。
 *
 * <p>在交付复核通过、创建 PR 之前，由控制面调用该端口把已复核的候选补丁提交并推送到
 * 远端工作分支；具体的工作区、Git 与对象存储操作由 bootstrap 适配。
 */
public interface RequirementBranchPublisherPort {

    /**
     * 推送已复核的工作分支。
     *
     * @param command 推送命令
     * @return 推送结果
     */
    RequirementBranchPublication publishBranch(RequirementBranchPublishCommand command);

    /**
     * 不可用端口：未配置真实分支推送时返回跳过结果，保持旧交付行为不变。
     */
    static RequirementBranchPublisherPort unavailable() {
        return command -> RequirementBranchPublication.skipped(command == null ? "" : command.taskId());
    }
}
