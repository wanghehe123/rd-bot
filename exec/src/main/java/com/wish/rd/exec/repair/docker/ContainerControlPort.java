package com.wish.rd.exec.repair.docker;

import com.wish.rd.exec.repair.execution.RepairExecutionStopCommand;
import com.wish.rd.exec.repair.execution.RepairExecutionStopResult;

/**
 * 容器控制端口，用于显式停止运行中的 Docker 容器。
 */
@FunctionalInterface
public interface ContainerControlPort {

    /**
     * 停止容器。
     *
     * @param command 停止命令
     * @return 停止结果
     */
    RepairExecutionStopResult stop(RepairExecutionStopCommand command);
}
