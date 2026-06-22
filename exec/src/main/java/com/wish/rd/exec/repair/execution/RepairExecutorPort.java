package com.wish.rd.exec.repair.execution;

/**
 * 修复执行器端口，由 Docker Claude Code 等执行实现提供，供 bootstrap 编排桥调用。
 */
@FunctionalInterface
public interface RepairExecutorPort {

    /**
     * 执行一次修复任务。
     *
     * @param command 修复执行命令
     * @return 修复执行结果
     */
    RepairExecutionResult execute(RepairJobCommand command);
}
