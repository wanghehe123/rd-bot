package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.executor.DockerExecutorProperties;
import com.wish.rd.bootstrap.financial.FinancialProperties;
import com.wish.rd.engine.agent.AgentStageProgressCalculator;
import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.exec.repair.docker.impl.DockerExecutionRegistry;
import com.wish.rd.exec.repair.alert.BudgetCurrencyConverter;
import com.wish.rd.rag.context.RoleContextPackageStore;
import com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore;
import com.wish.rd.rag.context.model.RoleContextPackage;
import com.wish.rd.rag.project.budget.RdProjectTokenBudgetService;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
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
    private final RagStreamTaskRegistry registry;
    private final AgentStageRunStore stageRunStore;
    private final AgentStageArtifactStore artifactStore;
    private final RoleContextPackageStore contextPackageStore;
    private final DockerExecutionRegistry executionRegistry;
    private final DockerExecutorProperties dockerExecutorProperties;
    private final BudgetCurrencyConverter budgetCurrencyConverter;
    private final AgentStageProgressCalculator stageProgressCalculator;
    private RdProjectTokenBudgetService projectTokenBudgetService;

    @Autowired(required = false)
    void setProjectTokenBudgetService(RdProjectTokenBudgetService projectTokenBudgetService) {
        this.projectTokenBudgetService = projectTokenBudgetService;
    }

    @Autowired
    public RdTaskExecutionOverviewController(
            RagStreamTaskRegistry registry,
            ObjectProvider<AgentStageRunStore> stageRunStoreProvider,
            ObjectProvider<AgentStageArtifactStore> artifactStoreProvider,
            ObjectProvider<RoleContextPackageStore> contextPackageStoreProvider,
            ObjectProvider<DockerExecutionRegistry> executionRegistryProvider,
            ObjectProvider<DockerExecutorProperties> dockerExecutorPropertiesProvider,
            ObjectProvider<FinancialProperties> financialPropertiesProvider
    ) {
        this(
                registry,
                stageRunStoreProvider.getIfAvailable(InMemoryAgentStageRunStore::new),
                artifactStoreProvider.getIfAvailable(InMemoryAgentStageArtifactStore::new),
                contextPackageStoreProvider.getIfAvailable(InMemoryRoleContextPackageStore::new),
                executionRegistryProvider.getIfAvailable(DockerExecutionRegistry::noop),
                dockerExecutorPropertiesProvider.getIfAvailable(DockerExecutorProperties::new),
                financialPropertiesProvider.getIfAvailable(FinancialProperties::new).toBudgetCurrencyConverter()
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
                dockerExecutorProperties,
                new FinancialProperties().toBudgetCurrencyConverter()
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
        this(
                registry,
                stageRunStore,
                artifactStore,
                contextPackageStore,
                executionRegistry,
                dockerExecutorProperties,
                new FinancialProperties().toBudgetCurrencyConverter()
        );
    }

    public RdTaskExecutionOverviewController(
            RagStreamTaskRegistry registry,
            AgentStageRunStore stageRunStore,
            AgentStageArtifactStore artifactStore,
            RoleContextPackageStore contextPackageStore,
            DockerExecutionRegistry executionRegistry,
            DockerExecutorProperties dockerExecutorProperties,
            BudgetCurrencyConverter budgetCurrencyConverter
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
        this.budgetCurrencyConverter = budgetCurrencyConverter == null
                ? new FinancialProperties().toBudgetCurrencyConverter()
                : budgetCurrencyConverter;
        this.stageProgressCalculator = new AgentStageProgressCalculator();
    }

    @GetMapping("/admin/rd-tasks/{taskId}/execution-overview")
    public RdTaskExecutionOverviewView getExecutionOverview(@PathVariable("taskId") String taskId) {
        RdTask task = findTask(taskId);
        long now = System.currentTimeMillis();
        List<AgentRole> stageOrder = stageOrder(task);
        List<AgentStageRun> stageRuns = sortedStageRuns(task.taskId(), stageOrder);
        Map<String, AgentStageArtifact> artifactById = artifactsById(task.taskId());
        AgentStageProgressCalculator.AgentStageProgress stageProgress = stageProgressCalculator.calculate(
                task.taskType(),
                stageRuns
        );
        List<RoleContextPackage> contextPackages = contextPackageStore.listByTask(task.taskId());
        ExecutionBudgetView budget = budget(contextPackages, stageRuns);
        List<StageRunView> stageViews = stageRuns.stream()
                .map(stageRun -> toStageRunView(stageRun, now, artifactById))
                .toList();
        List<DockerExecutionRegistry.RunningExecution> runningExecutionSnapshots = executionRegistry.runningExecutions().stream()
                .filter(execution -> task.taskId().equals(execution.taskId()))
                .toList();
        List<RunningExecutionView> runningExecutions = runningExecutionSnapshots.stream()
                .map(execution -> new RunningExecutionView(
                        execution.repairRecordId(),
                        execution.taskId(),
                        execution.executionTaskId(),
                        execution.stageRunId(),
                        execution.ticketId(),
                        execution.provider(),
                        execution.containerName(),
                        execution.startedAtEpochMillis(),
                        execution.lastHeartbeatEpochMillis(),
                        Math.max(0L, now - execution.startedAtEpochMillis()),
                        execution.outputDirectory() == null ? "" : execution.outputDirectory().toString(),
                        new TokenUsageView(
                                execution.tokenUsage().inputTokens(),
                                execution.tokenUsage().outputTokens(),
                                execution.tokenUsage().cacheCreationInputTokens(),
                                execution.tokenUsage().cacheReadInputTokens(),
                                execution.tokenUsage().totalTokens(),
                                budgetCurrencyConverter.usdToCny(execution.tokenUsage().estimatedCostUsd()),
                                execution.tokenUsage().available(),
                                execution.tokenUsage().finalized()
                        )
                ))
                .toList();
        TokenBudgetView tokenBudget = tokenBudget(task, stageRuns, runningExecutionSnapshots);
        long elapsedMillis = Math.max(0L, (isTaskTerminal(task.status()) ? task.updateTimeEpochMillis() : now)
                - task.createTimeEpochMillis());

        return new RdTaskExecutionOverviewView(
                task.taskId(),
                task.taskType(),
                task.status().name(),
                task.title(),
                elapsedMillis,
                stageProgress.completedSteps(),
                stageProgress.totalSteps(),
                stageProgress.currentRole(),
                stageProgress.currentStatus(),
                budget,
                tokenBudget,
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

    private List<AgentStageRun> sortedStageRuns(String taskId, List<AgentRole> stageOrder) {
        Map<AgentRole, Integer> roleOrder = roleOrder(stageOrder);
        return stageRunStore.listByTask(taskId).stream()
                .filter(run -> stageOrder.contains(run.role()))
                .sorted(Comparator
                        .comparingInt((AgentStageRun run) -> roleOrder.getOrDefault(run.role(), 99))
                        .thenComparingInt(AgentStageRun::attemptNo)
                        .thenComparingLong(AgentStageRun::createTimeEpochMillis)
                        .thenComparing(AgentStageRun::stageRunId))
                .toList();
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

    private StageRunView toStageRunView(
            AgentStageRun stageRun,
            long now,
            Map<String, AgentStageArtifact> artifactById
    ) {
        List<Map<String, Object>> providerAttempts = providerAttemptsCny(
                providerAttempts(stageRun.providerAttemptsJson())
        );
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
                providerAttemptsJson(providerAttempts),
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
        BigDecimal estimatedSpendCny = stageRuns.stream()
                .flatMap(run -> providerAttempts(run.providerAttemptsJson()).stream())
                .map(RdTaskExecutionOverviewController::estimatedSpend)
                .map(budgetCurrencyConverter::usdToCny)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal budgetAlertCny = dockerExecutorProperties.getBudgetAlertCny() == null
                ? BigDecimal.ZERO
                : dockerExecutorProperties.getBudgetAlertCny();
        double contextUsageRatio = contextMaxChars <= 0
                ? 0D
                : BigDecimal.valueOf(contextUsedChars)
                .divide(BigDecimal.valueOf(contextMaxChars), 4, RoundingMode.HALF_UP)
                .doubleValue();
        return new ExecutionBudgetView(
                contextUsedChars,
                contextMaxChars,
                contextUsageRatio,
                estimatedSpendCny,
                budgetAlertCny,
                estimatedSpendCny.compareTo(BigDecimal.ZERO) > 0
        );
    }

    private TokenBudgetView tokenBudget(
            RdTask task,
            List<AgentStageRun> stageRuns,
            List<DockerExecutionRegistry.RunningExecution> runningExecutions
    ) {
        JsonNode budget = latestRequirementBudget(stageRuns);
        long effectiveTokenBudget = budget.has("effectiveTokenBudget")
                ? nonNegativeLong(budget.path("effectiveTokenBudget"))
                : configuredEffectiveTokenBudget(task);
        long initialTokens = nonNegativeLong(budget.path("initialTokens"));
        long retryReserveTokens = nonNegativeLong(budget.path("retryReserveTokens"));
        long estimatedTotalTokens = nonNegativeLong(budget.path("estimatedTotalTokens"));
        String confidence = text(budget.path("confidence"));
        String basis = text(budget.path("basis"));
        List<Map<String, Object>> historicalSamples = jsonObjectList(budget.path("historicalSamples"));
        long finalActualTokens = 0L;
        for (AgentStageRun stageRun : stageRuns) {
            for (Map<String, Object> attempt : providerAttempts(stageRun.providerAttemptsJson())) {
                finalActualTokens = safeAdd(finalActualTokens, nonNegativeLong(attempt.get("totalTokens")));
            }
        }
        long runningTokens = runningExecutions.stream()
                .map(DockerExecutionRegistry.RunningExecution::tokenUsage)
                .mapToLong(usage -> usage == null ? 0L : usage.totalTokens())
                .reduce(0L, RdTaskExecutionOverviewController::safeAdd);
        long actualAccumulatedTokens = safeAdd(finalActualTokens, runningTokens);
        boolean estimateAvailable = estimatedTotalTokens > 0L || !confidence.isBlank() || !basis.isBlank();
        boolean actualAvailable = finalActualTokens > 0L || runningTokens > 0L;
        return new TokenBudgetView(
                effectiveTokenBudget,
                initialTokens,
                retryReserveTokens,
                estimatedTotalTokens,
                confidence,
                basis,
                historicalSamples,
                runningTokens,
                actualAccumulatedTokens,
                finalActualTokens,
                estimateAvailable,
                actualAvailable,
                effectiveTokenBudget > 0L && (estimatedTotalTokens > effectiveTokenBudget
                        || actualAccumulatedTokens > effectiveTokenBudget)
        );
    }

    private static JsonNode latestRequirementBudget(List<AgentStageRun> stageRuns) {
        return stageRuns.stream()
                .filter(stage -> stage.role() == AgentRole.REQUIREMENT_REVIEWER)
                .max(Comparator.comparingInt(AgentStageRun::attemptNo)
                        .thenComparingLong(AgentStageRun::updateTimeEpochMillis))
                .map(AgentStageRun::reviewResultJson)
                .map(RdTaskExecutionOverviewController::readJson)
                .map(root -> root.path("tokenBudget"))
                .filter(JsonNode::isObject)
                .orElseGet(() -> OBJECT_MAPPER.createObjectNode());
    }

    private long configuredEffectiveTokenBudget(RdTask task) {
        if (!(task instanceof RdRequirementTask requirementTask)) {
            return 0L;
        }
        if (requirementTask.tokenBudgetOverride() > 0L) {
            return requirementTask.tokenBudgetOverride();
        }
        if (projectTokenBudgetService == null || requirementTask.projectId().isBlank()) {
            return 0L;
        }
        try {
            return projectTokenBudgetService.get(requirementTask.projectId()).defaultTokenBudget();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static JsonNode readJson(String value) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(value == null ? "{}" : value);
            return node == null || !node.isObject() ? OBJECT_MAPPER.createObjectNode() : node;
        } catch (Exception ignored) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    private static List<Map<String, Object>> jsonObjectList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> values = new java.util.ArrayList<>();
        for (JsonNode entry : node) {
            if (entry.isObject()) {
                try {
                    values.add(OBJECT_MAPPER.convertValue(entry, new TypeReference<>() { }));
                } catch (IllegalArgumentException ignored) {
                    // Historical budget evidence is optional and must never break the overview response.
                }
            }
        }
        return List.copyOf(values);
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue().strip() : "";
    }

    private static long nonNegativeLong(JsonNode node) {
        return node != null && node.canConvertToLong() && node.longValue() > 0L ? node.longValue() : 0L;
    }

    private static long nonNegativeLong(Object value) {
        if (value instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Math.max(0L, Long.parseLong(text.strip()));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
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

    private List<Map<String, Object>> providerAttemptsCny(List<Map<String, Object>> rawAttempts) {
        return rawAttempts.stream().map(rawAttempt -> {
            Map<String, Object> visibleAttempt = new LinkedHashMap<>(rawAttempt);
            BigDecimal estimatedSpendCny = budgetCurrencyConverter.usdToCny(estimatedSpend(rawAttempt));
            visibleAttempt.remove("estimatedSpendUsd");
            visibleAttempt.remove("estimatedSpend");
            visibleAttempt.remove("costUsd");
            visibleAttempt.remove("totalCostUsd");
            visibleAttempt.remove("cost");
            if (estimatedSpendCny.signum() > 0) {
                visibleAttempt.put("currency", "CNY");
                visibleAttempt.put("estimatedSpendCny", estimatedSpendCny);
            }
            return visibleAttempt;
        }).toList();
    }

    private static String providerAttemptsJson(List<Map<String, Object>> providerAttempts) {
        try {
            return OBJECT_MAPPER.writeValueAsString(providerAttempts);
        } catch (Exception exception) {
            return "[]";
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

    private static boolean isTaskTerminal(RdTaskStatus status) {
        return status == RdTaskStatus.COMPLETED
                || status == RdTaskStatus.MERGED
                || status == RdTaskStatus.REJECTED
                || status == RdTaskStatus.FAILED_NEEDS_HUMAN
                || status == RdTaskStatus.CANCELLED
                || status == RdTaskStatus.DEAD_LETTERED
                || status == RdTaskStatus.DELETED;
    }

    private static int compareArtifact(AgentStageArtifact left, AgentStageArtifact right) {
        int created = Long.compare(left.createdAtEpochMillis(), right.createdAtEpochMillis());
        if (created != 0) {
            return created;
        }
        return left.artifactId().compareTo(right.artifactId());
    }

    private static List<AgentRole> stageOrder(RdTask task) {
        return "BUG_FIX".equals(task.taskType())
                ? AgentRole.bugFixOrder()
                : AgentRole.requirementDeliveryOrder();
    }

    private static Map<AgentRole, Integer> roleOrder(List<AgentRole> roles) {
        Map<AgentRole, Integer> order = new LinkedHashMap<>();
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
            TokenBudgetView tokenBudget,
            List<StageRunView> stageRuns,
            List<RunningExecutionView> runningExecutions
    ) {
    }

    public record ExecutionBudgetView(
            int contextUsedChars,
            int contextMaxChars,
            double contextUsageRatio,
            BigDecimal estimatedSpendCny,
            BigDecimal budgetAlertCny,
            boolean costAvailable
    ) {
    }

    public record TokenBudgetView(
            long effectiveTokenBudget,
            long initialTokens,
            long retryReserveTokens,
            long estimatedTotalTokens,
            String confidence,
            String basis,
            List<Map<String, Object>> historicalSamples,
            long runningTokens,
            long actualAccumulatedTokens,
            long finalActualTokens,
            boolean estimateAvailable,
            boolean actualAvailable,
            boolean overBudget
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
            String executionTaskId,
            String stageRunId,
            String ticketId,
            String provider,
            String containerName,
            long startedAtEpochMillis,
            long lastHeartbeatEpochMillis,
            long elapsedMillis,
            String outputDirectory,
            TokenUsageView tokenUsage
    ) {
    }

    public record TokenUsageView(
            long inputTokens,
            long outputTokens,
            long cacheCreationInputTokens,
            long cacheReadInputTokens,
            long totalTokens,
            BigDecimal estimatedSpendCny,
            boolean available,
            boolean finalized
    ) {
    }
}
