package com.wish.rd.exec.repair.docker.impl;

import com.wish.rd.exec.repair.docker.ContainerControlPort;

import com.wish.rd.exec.repair.execution.RepairExecutionControlPort;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStopCommand;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStopResult;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import com.wish.rd.exec.repair.docker.model.ContainerRunRequest;
import com.wish.rd.exec.repair.docker.usage.ClaudeTokenUsageParser;
import com.wish.rd.exec.repair.docker.usage.model.ClaudeTokenUsageSnapshot;
import com.wish.rd.exec.repair.docker.trace.ClaudeExecutionTraceParser;
import com.wish.rd.exec.repair.docker.trace.model.ClaudeExecutionTraceSnapshot;

/**
 * Docker 执行运行态注册表，记录 taskId 到容器的映射，并提供手动停止入口。
 */
public class DockerExecutionRegistry implements RepairExecutionControlPort {

    private static final long USAGE_REFRESH_INTERVAL_MILLIS = 1_000L;
    private final Map<String, RunningExecution> runningByTaskId = new ConcurrentHashMap<>();
    private final ContainerControlPort containerControlPort;
    private final ClaudeTokenUsageParser tokenUsageParser = new ClaudeTokenUsageParser();
    private final ClaudeExecutionTraceParser executionTraceParser = new ClaudeExecutionTraceParser();
    private ScheduledExecutorService usageRefreshExecutor;
    private ScheduledFuture<?> usageRefreshTask;

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
        String workflowTaskId = workflowTaskId(command);
        runningByTaskId.put(workflowTaskId, new RunningExecution(
                command.repairRecordId(),
                workflowTaskId,
                command.taskId(),
                safe(command.contextJson().get("stageRunId")),
                command.ticketId(),
                provider == null ? "" : provider,
                request.containerName(),
                now,
                now,
                request.outputDirectory(),
                tokenUsageParser.parse(request.outputDirectory().resolve("claude-events.jsonl")),
                now
        ));
        ensureUsageRefresh();
    }

    /**
     * 移除运行态登记。
     *
     * @param taskId RD 任务 ID
     */
    public void unregister(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            String normalizedTaskId = taskId.strip();
            runningByTaskId.remove(normalizedTaskId);
            runningByTaskId.entrySet().removeIf(entry -> normalizedTaskId.equals(entry.getValue().executionTaskId()));
            stopUsageRefreshIfIdle();
        }
    }

    /**
     * 查询当前运行任务。
     *
     * @return 运行任务快照
     */
    public List<RunningExecution> runningExecutions() {
        refreshUsageSnapshots();
        return runningByTaskId.values().stream()
                .sorted(java.util.Comparator.comparing(RunningExecution::startedAtEpochMillis))
                .toList();
    }

    /**
     * Returns the safe, live trace for one running role stage. The raw event file never crosses this boundary.
     */
    public ClaudeExecutionTraceSnapshot executionTrace(
            String workflowTaskId,
            String stageRunId,
            long afterSequence,
            int limit
    ) {
        String normalizedTaskId = safe(workflowTaskId);
        String normalizedStageRunId = safe(stageRunId);
        if (normalizedTaskId.isBlank() || normalizedStageRunId.isBlank()) {
            return ClaudeExecutionTraceSnapshot.unavailable("LIVE");
        }
        RunningExecution running = runningByTaskId.get(normalizedTaskId);
        if (running == null || !normalizedStageRunId.equals(running.stageRunId())) {
            return ClaudeExecutionTraceSnapshot.unavailable("LIVE");
        }
        Path outputDirectory = running.outputDirectory();
        return executionTraceParser.parse(
                outputDirectory == null ? null : outputDirectory.resolve("claude-events.jsonl"),
                afterSequence,
                limit
        );
    }

    /**
     * Reports whether this exact workflow stage still owns a live container registration.
     * A newly started Claude session may not have written a visible event file yet.
     */
    public boolean isRunning(String workflowTaskId, String stageRunId) {
        String normalizedTaskId = safe(workflowTaskId);
        String normalizedStageRunId = safe(stageRunId);
        if (normalizedTaskId.isBlank() || normalizedStageRunId.isBlank()) {
            return false;
        }
        RunningExecution running = runningByTaskId.get(normalizedTaskId);
        return running != null && normalizedStageRunId.equals(running.stageRunId());
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
            String executionTaskId,
            String stageRunId,
            String ticketId,
            String provider,
            String containerName,
            long startedAtEpochMillis,
            long lastHeartbeatEpochMillis,
            Path outputDirectory,
            ClaudeTokenUsageSnapshot tokenUsage,
            long lastUsageRefreshEpochMillis
    ) {
    }

    private void refreshUsageSnapshots() {
        long now = System.currentTimeMillis();
        runningByTaskId.replaceAll((taskId, running) -> {
            if (now - running.lastUsageRefreshEpochMillis() < USAGE_REFRESH_INTERVAL_MILLIS) {
                return running;
            }
            ClaudeTokenUsageSnapshot tokenUsage = tokenUsageParser.parse(
                    running.outputDirectory() == null ? null : running.outputDirectory().resolve("claude-events.jsonl")
            );
            return new RunningExecution(
                    running.repairRecordId(),
                    running.taskId(),
                    running.executionTaskId(),
                    running.stageRunId(),
                    running.ticketId(),
                    running.provider(),
                    running.containerName(),
                    running.startedAtEpochMillis(),
                    now,
                    running.outputDirectory(),
                    tokenUsage,
                    now
            );
        });
    }

    private synchronized void ensureUsageRefresh() {
        if (usageRefreshTask != null && !usageRefreshTask.isCancelled()) {
            return;
        }
        usageRefreshExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rd-claude-token-usage-refresh");
            thread.setDaemon(true);
            return thread;
        });
        usageRefreshTask = usageRefreshExecutor.scheduleWithFixedDelay(
                this::refreshUsageSnapshots,
                USAGE_REFRESH_INTERVAL_MILLIS,
                USAGE_REFRESH_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS
        );
    }

    private synchronized void stopUsageRefreshIfIdle() {
        if (!runningByTaskId.isEmpty() || usageRefreshTask == null) {
            return;
        }
        usageRefreshTask.cancel(false);
        usageRefreshTask = null;
        if (usageRefreshExecutor != null) {
            usageRefreshExecutor.shutdown();
            usageRefreshExecutor = null;
        }
    }

    private static String workflowTaskId(RepairJobCommand command) {
        String taskId = safe(command.contextJson().get("workflowTaskId"));
        if (taskId.isBlank()) {
            taskId = safe(command.contextJson().get("taskId"));
        }
        return taskId.isBlank() ? command.taskId() : taskId;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
