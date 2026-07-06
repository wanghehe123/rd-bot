package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.executor.DockerExecutorProperties;
import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.exec.repair.docker.impl.DockerExecutionRegistry;
import com.wish.rd.rag.context.RoleContextPackageStore;
import com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore;
import com.wish.rd.rag.context.model.RoleContextPackage;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RD 任务执行概览管理接口。
 *
 * <p>把主任务、角色阶段、上下文预算和 Docker 运行态聚合成一个低成本轮询视图。
 */
@RestController
public class RdTaskExecutionOverviewController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> ATTEMPTS_TYPE = new TypeReference<>() {
    };
    private static final int ROLE_STAGE_STEP_COUNT = 6;

    private final RagStreamTaskRegistry registry;
    private final AgentStageRunStore stageRunStore;
    private final AgentStageArtifactStore artifactStore;
    private final RoleContextPackageStore contextPackageStore;
    private final DockerExecutionRegistry executionRegistry;
    private final DockerExecutorProperties dockerExecutorProperties;

    @Autowired
    public RdTaskExecutionOverviewController(
            RagStreamTaskRegistry registry,
            ObjectProvider<AgentStageRunStore> stageRunStoreProvider,
            ObjectProvider<AgentStageArtifactStore> artifactStoreProvider,
            ObjectProvider<RoleContextPackageStore> contextPackageStoreProvider,
            ObjectProvider<DockerExecutionRegistry> executionRegistryProvider,
            ObjectProvider<DockerExecutorProperties> dockerExecutorPropertiesProvider
    ) {
        this(
                registry,
                stageRunStoreProvider.getIfAvailable(InMemoryAgentStageRunStore::new),
                artifactStoreProvider.getIfAvailable(InMemoryAgentStageArtifactStore::new),
                contextPackageStoreProvider.getIfAvailable(InMemoryRoleContextPackageStore::new),
                executionRegistryProvider.getIfAvailable(DockerExecutionRegistry::noop),
                dockerExecutorPropertiesProvider.getIfAvailable(DockerExecutorProperties::new)
        );
    }

    public RdTaskExecutionOverviewController(
            RagStreamTaskRegistry registry,
            AgentStageRunStore stageRunStore,
            RoleContextPackageStore contextPackageStore,
            DockerExecutionRegistry executionRegistry,
            DockerExecutorProperties dockerExecutorProperties
    ) {
        this(
                registry,
                stageRunStore,
                new InMemoryAgentStageArtifactStore(),
                contextPackageStore,
                executionRegistry,
                dockerExecutorProperties
        );
    }

    public RdTaskExecutionOverviewController(
            RagStreamTaskRegistry registry,
            AgentStageRunStore stageRunStore,
            AgentStageArtifactStore artifactStore,
            RoleContextPackageStore contextPackageStore,
            DockerExecutionRegistry executionRegistry,
            DockerExecutorProperties dockerExecutorProperties
    ) {
        this.registry = registry;
        this.stageRunStore = stageRunStore == null ? new InMemoryAgentStageRunStore() : stageRunStore;
        this.artifactStore = artifactStore == null ? new InMemoryAgentStageArtifactStore() : artifactStore;
        this.contextPackageStore = contextPackageStore == null
                ? new InMemoryRoleContextPackageStore()
                : contextPackageStore;
        this.executionRegistry = executionRegistry == null ? DockerExecutionRegistry.noop() : executionRegistry;
        this.dockerExecutorProperties = dockerExecutorProperties == null
                ? new DockerExecutorProperties()
                : dockerExecutorProperties;
    }

    @GetMapping("/admin/rd-tasks/{taskId}/execution-overview")
    public RdTaskExecutionOverviewView getExecutionOverview(@PathVariable("taskId") String taskId) {
        RdTask task = findTask(taskId);
        long now = System.currentTimeMillis();
        List<AgentStageRun> stageRuns = sortedStageRuns(task.taskId());
        Map<String, AgentStageArtifact> artifactById = artifactsById(task.taskId());
        Map<AgentRole, AgentStageRun> latestByRole = latestByRole(stageRuns);
        List<RoleContextPackage> contextPackages = contextPackageStore.listByTask(task.taskId());
        ExecutionBudgetView budget = budget(contextPackages, stageRuns);
        List<StageRunView> stageViews = stageRuns.stream()
                .map(stageRun -> toStageRunView(stageRun, now, artifactById))
                .toList();
        List<RunningExecutionView> runningExecutions = executionRegistry.runningExecutions().stream()
                .filter(execution -> task.taskId().equals(execution.taskId()))
                .map(execution -> new RunningExecutionView(
                        execution.repairRecordId(),
                        execution.taskId(),
                        execution.ticketId(),
                        execution.provider(),
                        execution.containerName(),
                        execution.startedAtEpochMillis(),
                        execution.lastHeartbeatEpochMillis(),
                        Math.max(0L, now - execution.startedAtEpochMillis()),
                        execution.outputDirectory() == null ? "" : execution.outputDirectory().toString()
                ))
                .toList();
        AgentStageRun current = currentStageRun(latestByRole);
        int progressTotal = AgentRole.requirementDeliveryOrder().size() * ROLE_STAGE_STEP_COUNT;
        int progressCompleted = progressCompleted(latestByRole);
        long elapsedMillis = Math.max(0L, (isTaskTerminal(task.status()) ? task.updateTimeEpochMillis() : now)
                - task.createTimeEpochMillis());

        return new RdTaskExecutionOverviewView(
                task.taskId(),
                task.taskType(),
                task.status().name(),
                task.title(),
                elapsedMillis,
                progressCompleted,
                progressTotal,
                current == null ? "" : current.role().name(),
                current == null ? "" : current.status().name(),
                budget,
                stageViews,
                runningExecutions
        );
    }

    private RdTask findTask(String taskId) {
        try {
            return registry.getTask(taskId);
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    private List<AgentStageRun> sortedStageRuns(String taskId) {
        Map<AgentRole, Integer> roleOrder = roleOrder();
        return stageRunStore.listByTask(taskId).stream()
                .sorted(Comparator
                        .comparingInt((AgentStageRun run) -> roleOrder.getOrDefault(run.role(), 99))
                        .thenComparingInt(AgentStageRun::attemptNo)
                        .thenComparingLong(AgentStageRun::createTimeEpochMillis)
                        .thenComparing(AgentStageRun::stageRunId))
                .toList();
    }

    private Map<AgentRole, AgentStageRun> latestByRole(List<AgentStageRun> stageRuns) {
        return stageRuns.stream()
                .collect(Collectors.toMap(
                        AgentStageRun::role,
                        Function.identity(),
                        (left, right) -> compareAttempt(left, right) >= 0 ? left : right,
                        LinkedHashMap::new
                ));
    }

    private Map<String, AgentStageArtifact> artifactsById(String taskId) {
        return artifactStore.listByTask(taskId).stream()
                .collect(Collectors.toMap(
                        AgentStageArtifact::artifactId,
                        Function.identity(),
                        (left, right) -> compareArtifact(left, right) >= 0 ? left : right,
                        LinkedHashMap::new
                ));
    }

    private AgentStageRun currentStageRun(Map<AgentRole, AgentStageRun> latestByRole) {
        for (AgentRole role : AgentRole.requirementDeliveryOrder()) {
            AgentStageRun run = latestByRole.get(role);
            if (run != null && run.status() != AgentStageStatus.SUCCEEDED) {
                return run;
            }
        }
        return latestByRole.values().stream()
                .max(RdTaskExecutionOverviewController::compareAttempt)
                .orElse(null);
    }

    private int progressCompleted(Map<AgentRole, AgentStageRun> latestByRole) {
        int completed = 0;
        for (AgentRole role : AgentRole.requirementDeliveryOrder()) {
            completed += progressStep(latestByRole.get(role));
        }
        return Math.min(completed, AgentRole.requirementDeliveryOrder().size() * ROLE_STAGE_STEP_COUNT);
    }

    private int progressStep(AgentStageRun run) {
        if (run == null || run.status() == null) {
            return 0;
        }
        return switch (run.status()) {
            case PENDING -> 0;
            case CONTEXT_READY -> 1;
            case DISPATCHING, RECOVERING -> 2;
            case RUNNING -> 3;
            case RESULT_COLLECTING -> 4;
            case VERIFYING -> 5;
            case SUCCEEDED, FAILED_RETRYABLE, FAILED_NEEDS_HUMAN, SKIPPED, CANCELLED -> 6;
        };
    }

    private StageRunView toStageRunView(
            AgentStageRun stageRun,
            long now,
            Map<String, AgentStageArtifact> artifactById
    ) {
        List<Map<String, Object>> providerAttempts = providerAttempts(stageRun.providerAttemptsJson());
        long elapsedMillis = elapsedMillis(stageRun, now);
        AgentStageArtifact resultArtifact = artifactById.get(stageRun.resultArtifactId());
        String resultPreview = resultPreview(stageRun, resultArtifact);
        String resultSummary = resultArtifact == null ? "" : resultArtifact.summary();
        return new StageRunView(
                stageRun.stageRunId(),
                stageRun.taskId(),
                stageRun.role().name(),
                stageRun.status().name(),
                stageRun.attemptNo(),
                stageRun.idempotencyKey(),
                stageRun.contextPackageId(),
                stageRun.promptArtifactId(),
                stageRun.resultArtifactId(),
                stageRun.providerName(),
                stageRun.providerAttemptsJson(),
                providerAttempts,
                stageRun.reviewResultJson(),
                !resultPreview.isBlank(),
                resultSummary,
                resultPreview,
                stageRun.errorCategory(),
                stageRun.errorMessage(),
                stageRun.createTimeEpochMillis(),
                stageRun.updateTimeEpochMillis(),
                stageRun.startedAtEpochMillis(),
                stageRun.finishedAtEpochMillis(),
                elapsedMillis,
                stageRun.startedAtEpochMillis() > 0L && stageRun.finishedAtEpochMillis() <= 0L
        );
    }

    private static String resultPreview(AgentStageRun stageRun, AgentStageArtifact resultArtifact) {
        if (resultArtifact != null && !resultArtifact.contentPreview().isBlank()) {
            return resultArtifact.contentPreview();
        }
        String reviewJson = stageRun.reviewResultJson();
        if (reviewJson == null || reviewJson.isBlank() || "{}".equals(reviewJson.strip())) {
            return "";
        }
        return reviewJson.strip();
    }

    private ExecutionBudgetView budget(List<RoleContextPackage> contextPackages, List<AgentStageRun> stageRuns) {
        int contextUsedChars = contextPackages.stream()
                .mapToInt(RoleContextPackage::usedChars)
                .sum();
        int contextMaxChars = contextPackages.stream()
                .mapToInt(RoleContextPackage::maxChars)
                .sum();
        BigDecimal estimatedSpend = stageRuns.stream()
                .flatMap(run -> providerAttempts(run.providerAttemptsJson()).stream())
                .map(RdTaskExecutionOverviewController::estimatedSpend)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal budgetAlertUsd = dockerExecutorProperties.getBudgetAlertUsd() == null
                ? BigDecimal.ZERO
                : dockerExecutorProperties.getBudgetAlertUsd();
        double contextUsageRatio = contextMaxChars <= 0
                ? 0D
                : BigDecimal.valueOf(contextUsedChars)
                .divide(BigDecimal.valueOf(contextMaxChars), 4, RoundingMode.HALF_UP)
                .doubleValue();
        return new ExecutionBudgetView(
                contextUsedChars,
                contextMaxChars,
                contextUsageRatio,
                estimatedSpend,
                budgetAlertUsd,
                estimatedSpend.compareTo(BigDecimal.ZERO) > 0
        );
    }

    private long elapsedMillis(AgentStageRun stageRun, long now) {
        long start = stageRun.startedAtEpochMillis();
        if (start <= 0L) {
            return 0L;
        }
        long finish = stageRun.finishedAtEpochMillis() > 0L ? stageRun.finishedAtEpochMillis() : now;
        return Math.max(0L, finish - start);
    }

    private static List<Map<String, Object>> providerAttempts(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, ATTEMPTS_TYPE);
        } catch (Exception exception) {
            return List.of(Map.of(
                    "parseError", true,
                    "raw", json
            ));
        }
    }

    private static BigDecimal estimatedSpend(Map<String, Object> attempt) {
        for (String key : List.of("estimatedSpendUsd", "estimatedSpend", "costUsd", "totalCostUsd", "cost")) {
            BigDecimal value = decimal(attempt.get(key));
            if (value.compareTo(BigDecimal.ZERO) > 0) {
                return value;
            }
        }
        return BigDecimal.ZERO;
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

    private static boolean isTaskTerminal(RdTaskStatus status) {
        return status == RdTaskStatus.COMPLETED
                || status == RdTaskStatus.MERGED
                || status == RdTaskStatus.REJECTED
                || status == RdTaskStatus.FAILED_NEEDS_HUMAN
                || status == RdTaskStatus.CANCELLED
                || status == RdTaskStatus.DEAD_LETTERED
                || status == RdTaskStatus.DELETED;
    }

    private static int compareAttempt(AgentStageRun left, AgentStageRun right) {
        int attempt = Integer.compare(left.attemptNo(), right.attemptNo());
        if (attempt != 0) {
            return attempt;
        }
        int created = Long.compare(left.createTimeEpochMillis(), right.createTimeEpochMillis());
        if (created != 0) {
            return created;
        }
        return left.stageRunId().compareTo(right.stageRunId());
    }

    private static int compareArtifact(AgentStageArtifact left, AgentStageArtifact right) {
        int created = Long.compare(left.createdAtEpochMillis(), right.createdAtEpochMillis());
        if (created != 0) {
            return created;
        }
        return left.artifactId().compareTo(right.artifactId());
    }

    private static Map<AgentRole, Integer> roleOrder() {
        Map<AgentRole, Integer> order = new LinkedHashMap<>();
        List<AgentRole> roles = AgentRole.requirementDeliveryOrder();
        for (int index = 0; index < roles.size(); index++) {
            order.put(roles.get(index), index);
        }
        return order;
    }

    public record RdTaskExecutionOverviewView(
            String taskId,
            String taskType,
            String status,
            String title,
            long elapsedMillis,
            int progressCompleted,
            int progressTotal,
            String currentRole,
            String currentStageStatus,
            ExecutionBudgetView budget,
            List<StageRunView> stageRuns,
            List<RunningExecutionView> runningExecutions
    ) {
    }

    public record ExecutionBudgetView(
            int contextUsedChars,
            int contextMaxChars,
            double contextUsageRatio,
            BigDecimal estimatedSpendUsd,
            BigDecimal budgetAlertUsd,
            boolean costAvailable
    ) {
    }

    public record StageRunView(
            String stageRunId,
            String taskId,
            String role,
            String status,
            int attemptNo,
            String idempotencyKey,
            String contextPackageId,
            String promptArtifactId,
            String resultArtifactId,
            String providerName,
            String providerAttemptsJson,
            List<Map<String, Object>> providerAttempts,
            String reviewResultJson,
            boolean resultAvailable,
            String resultSummary,
            String resultPreview,
            String errorCategory,
            String errorMessage,
            long createTimeEpochMillis,
            long updateTimeEpochMillis,
            long startedAtEpochMillis,
            long finishedAtEpochMillis,
            long elapsedMillis,
            boolean running
    ) {
    }

    public record RunningExecutionView(
            String repairRecordId,
            String taskId,
            String ticketId,
            String provider,
            String containerName,
            long startedAtEpochMillis,
            long lastHeartbeatEpochMillis,
            long elapsedMillis,
            String outputDirectory
    ) {
    }
}
