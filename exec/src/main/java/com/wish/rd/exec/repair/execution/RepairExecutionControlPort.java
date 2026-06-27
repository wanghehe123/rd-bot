package com.wish.rd.exec.repair.execution;

/**
 * 修复执行控制端口，用于 RD 手动终止正在运行的执行任务。
 */
@FunctionalInterface
public interface RepairExecutionControlPort {

    /**
     * 停止修复执行。
     *
     * @param command 停止命令
     * @return 停止结果
     */
    RepairExecutionStopResult stop(RepairExecutionStopCommand command);
}
