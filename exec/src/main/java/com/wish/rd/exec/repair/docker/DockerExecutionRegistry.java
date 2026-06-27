package com.wish.rd.exec.repair.docker;

import com.wish.rd.exec.repair.execution.RepairExecutionControlPort;
import com.wish.rd.exec.repair.execution.RepairExecutionStopCommand;
import com.wish.rd.exec.repair.execution.RepairExecutionStopResult;
import com.wish.rd.exec.repair.execution.RepairJobCommand;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Docker 执行运行态注册表，记录 taskId 到容器的映射，并提供手动停止入口。
 */
public class DockerExecutionRegistry implements RepairExecutionControlPort {

    private final Map<String, RunningExecution> runningByTaskId = new ConcurrentHashMap<>();
    private final ContainerControlPort containerControlPort;

    /**
     * 创建 Docker 执行注册表。
     *
     * @param containerControlPort 容器控制端口，可为空
     */
    public DockerExecutionRegistry(ContainerControlPort containerControlPort) {
        this.containerControlPort = containerControlPort;
    }

    /**
     * 返回无容器控制能力的注册表。
     *
     * @return 无容器控制能力的注册表
     */
    public static DockerExecutionRegistry noop() {
        return new DockerExecutionRegistry(null);
    }

    /**
     * 登记运行中的执行任务。
     *
     * @param command  修复执行命令
     * @param provider 模型供应商
     * @param request  容器运行请求
     */
    public void register(RepairJobCommand command, String provider, ContainerRunRequest request) {
        if (command == null || request == null) {
            return;
        }
        long now = System.currentTimeMillis();
        runningByTaskId.put(command.taskId(), new RunningExecution(
                command.repairRecordId(),
                command.taskId(),
                command.ticketId(),
                provider == null ? "" : provider,
                request.containerName(),
                now,
                now,
                request.outputDirectory()
        ));
    }

    /**
     * 移除运行态登记。
     *
     * @param taskId RD 任务 ID
     */
    public void unregister(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            runningByTaskId.remove(taskId.strip());
        }
    }

    /**
     * 查询当前运行任务。
     *
     * @return 运行任务快照
     */
    public List<RunningExecution> runningExecutions() {
        return runningByTaskId.values().stream()
                .sorted(java.util.Comparator.comparing(RunningExecution::startedAtEpochMillis))
                .toList();
    }

    @Override
    public RepairExecutionStopResult stop(RepairExecutionStopCommand command) {
        RunningExecution running = runningByTaskId.get(command.taskId());
        if (running == null) {
            return new RepairExecutionStopResult(command.taskId(), command.containerName(), false, "no running container");
        }
        RepairExecutionStopCommand stopCommand = new RepairExecutionStopCommand(
                running.repairRecordId(),
                running.taskId(),
                running.containerName(),
                command.reason()
        );
        RepairExecutionStopResult result = containerControlPort == null
                ? new RepairExecutionStopResult(running.taskId(), running.containerName(), false, "container control unavailable")
                : containerControlPort.stop(stopCommand);
        if (result.stopped()) {
            unregister(running.taskId());
        }
        return result;
    }

    /**
     * 当前运行中的 Docker 执行快照。
     *
     * @param repairRecordId          修复记录 ID
     * @param taskId                  RD 任务 ID
     * @param ticketId                工单 ID
     * @param provider                模型供应商
     * @param containerName           容器名称
     * @param startedAtEpochMillis    开始时间
     * @param lastHeartbeatEpochMillis 最近心跳时间
     * @param outputDirectory         输出目录
     */
    public record RunningExecution(
            String repairRecordId,
            String taskId,
            String ticketId,
            String provider,
            String containerName,
            long startedAtEpochMillis,
            long lastHeartbeatEpochMillis,
            Path outputDirectory
    ) {
    }
}
