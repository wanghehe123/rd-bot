package com.wish.rd.bootstrap.executor.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.financial.FinancialProperties;
import com.wish.rd.engine.admin.dashboard.DashboardRuntimeSnapshotPort;
import com.wish.rd.engine.admin.dashboard.model.RdDashboardOverview;
import com.wish.rd.engine.agent.AgentStageProgressCalculator;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.exec.repair.alert.BudgetCurrencyConverter;
import com.wish.rd.exec.repair.docker.impl.DockerExecutionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dashboard 运行时快照的 bootstrap 适配器。
 *
 * <p>该类把阶段记录、Docker 注册表和本地告警接入 engine 的只读端口。外部系统不可用时返回
 * {@code available=false}，不会将未知数据伪装成零。
 */
@Component
public final class DashboardRuntimeSnapshotAdapter implements DashboardRuntimeSnapshotPort {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> PROVIDER_ATTEMPTS = new TypeReference<>() {
    };
    private static final List<String> COST_KEYS = List.of(
            "estimatedSpendUsd", "estimatedSpend", "costUsd", "totalCostUsd", "cost"
    );

    private final AgentStageRunStore stageRunStore;
    private final DockerExecutionRegistry executionRegistry;
    private final InMemoryRepairAlertSink alertSink;
    private final AgentStageProgressCalculator stageProgressCalculator;
    private final BudgetCurrencyConverter budgetCurrencyConverter;

    /**
     * 创建 Dashboard 运行时适配器。
     *
     * @param stageRunStore 阶段记录存储端口
     * @param executionRegistryProvider Docker 运行态注册表提供者
     * @param alertSinkProvider 可查询的本地告警存储提供者
     * @param financialPropertiesProvider 统一 CNY 汇率配置提供者
     */
    public DashboardRuntimeSnapshotAdapter(
            AgentStageRunStore stageRunStore,
            ObjectProvider<DockerExecutionRegistry> executionRegistryProvider,
            ObjectProvider<InMemoryRepairAlertSink> alertSinkProvider,
            ObjectProvider<FinancialProperties> financialPropertiesProvider
    ) {
        this.stageRunStore = stageRunStore;
        this.executionRegistry = executionRegistryProvider.getIfAvailable();
        this.alertSink = alertSinkProvider.getIfAvailable();
        this.stageProgressCalculator = new AgentStageProgressCalculator();
        this.budgetCurrencyConverter = financialPropertiesProvider
                .getIfAvailable(FinancialProperties::new)
                .toBudgetCurrencyConverter();
    }

    @Override
    public Snapshot snapshot(String projectId, List<String> taskIds) {
        List<String> safeTaskIds = taskIds == null ? List.of() : taskIds.stream()
                .filter(taskId -> taskId != null && !taskId.isBlank())
                .distinct()
                .toList();
        if (stageRunStore == null) {
            return Snapshot.unavailable();
        }
        List<AgentStageRun> stageRuns = stageRunStore.listByTasks(safeTaskIds);
        Map<String, List<AgentStageRun>> runsByTask = stageRuns.stream()
                .collect(Collectors.groupingBy(AgentStageRun::taskId));
        Map<String, DockerExecutionRegistry.RunningExecution> runningByTask = runningExecutions(safeTaskIds).stream()
                .collect(Collectors.toMap(
                        DockerExecutionRegistry.RunningExecution::taskId,
                        execution -> execution,
                        (left, right) -> left.startedAtEpochMillis() <= right.startedAtEpochMillis() ? left : right,
                        HashMap::new
                ));
        Map<String, TaskRuntime> taskRuntimes = new HashMap<>();
        long now = System.currentTimeMillis();
        for (String taskId : safeTaskIds) {
            List<AgentStageRun> taskRuns = runsByTask.getOrDefault(taskId, List.of());
            DockerExecutionRegistry.RunningExecution running = runningByTask.get(taskId);
            AgentStageProgressCalculator.AgentStageProgress progress = stageProgressCalculator.calculate(
                    taskType(taskRuns),
                    taskRuns
            );
            taskRuntimes.put(taskId, new TaskRuntime(
                    progress.currentRole(),
                    progress.currentStatus(),
                    progress.completedSteps(),
                    progress.totalSteps(),
                    provider(progress.provider(), running),
                    progress.retryCount(),
                    elapsedMillis(taskRuns, now),
                    running != null
            ));
        }
        CostSummary cost = cost(stageRuns);
        long activeAlertCount = alertSink == null ? 0L : alertSink.alerts().stream()
                .filter(alert -> safeTaskIds.contains(alert.taskId()))
                .count();
        List<DockerExecutionRegistry.RunningExecution> runningExecutions = runningExecutions(safeTaskIds);
        boolean available = executionRegistry != null && alertSink != null;
        return new Snapshot(
                new RdDashboardOverview.RuntimeSnapshot(
                        available,
                        activeAlertCount,
                        runningExecutions.size(),
                        cost.estimatedSpendCny(),
                        cost.available()
                ),
                taskRuntimes
        );
    }

    private List<DockerExecutionRegistry.RunningExecution> runningExecutions(List<String> taskIds) {
        if (executionRegistry == null || taskIds.isEmpty()) {
            return List.of();
        }
        Set<String> selectedTaskIds = Set.copyOf(taskIds);
        return executionRegistry.runningExecutions().stream()
                .filter(execution -> selectedTaskIds.contains(execution.taskId()))
                .toList();
    }

    private CostSummary cost(List<AgentStageRun> stageRuns) {
        boolean available = false;
        BigDecimal spendCny = BigDecimal.ZERO;
        for (AgentStageRun stageRun : stageRuns) {
            for (Map<String, Object> attempt : providerAttempts(stageRun.providerAttemptsJson())) {
                for (String key : COST_KEYS) {
                    if (!attempt.containsKey(key)) {
                        continue;
                    }
                    available = true;
                    spendCny = spendCny.add(budgetCurrencyConverter.usdToCny(decimal(attempt.get(key))));
                    break;
                }
            }
        }
        return new CostSummary(available, spendCny);
    }

    private static List<Map<String, Object>> providerAttempts(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, PROVIDER_ATTEMPTS);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return new BigDecimal(stringValue.strip());
            } catch (NumberFormatException ignored) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    private static String taskType(List<AgentStageRun> stageRuns) {
        return stageRuns.stream()
                .map(AgentStageRun::role)
                .anyMatch(role -> com.wish.rd.engine.agent.model.AgentRole.bugFixOrder().contains(role))
                ? "BUG_FIX"
                : "REQUIREMENT";
    }

    private static String provider(
            String stageProvider,
            DockerExecutionRegistry.RunningExecution runningExecution
    ) {
        if (stageProvider != null && !stageProvider.isBlank()) {
            return stageProvider;
        }
        return runningExecution == null || runningExecution.provider() == null ? "" : runningExecution.provider();
    }

    private static long elapsedMillis(List<AgentStageRun> stageRuns, long now) {
        long startedAt = stageRuns.stream()
                .mapToLong(AgentStageRun::startedAtEpochMillis)
                .filter(value -> value > 0L)
                .min()
                .orElse(0L);
        if (startedAt <= 0L) {
            return 0L;
        }
        boolean running = stageRuns.stream().anyMatch(stageRun -> stageRun.finishedAtEpochMillis() <= 0L);
        long finishedAt = running
                ? now
                : stageRuns.stream()
                        .mapToLong(AgentStageRun::finishedAtEpochMillis)
                        .max()
                        .orElse(now);
        return Math.max(0L, finishedAt - startedAt);
    }

    private record CostSummary(boolean available, BigDecimal estimatedSpendCny) {
        private CostSummary {
            estimatedSpendCny = estimatedSpendCny == null ? BigDecimal.ZERO : estimatedSpendCny;
        }
    }
}
