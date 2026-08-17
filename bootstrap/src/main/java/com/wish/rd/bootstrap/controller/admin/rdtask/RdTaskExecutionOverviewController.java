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
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.exec.repair.docker.impl.DockerExecutionRegistry;
import com.wish.rd.exec.repair.docker.trace.ClaudeExecutionTraceParser;
import com.wish.rd.exec.repair.docker.trace.model.ClaudeExecutionTraceSnapshot;
import com.wish.rd.exec.repair.alert.BudgetCurrencyConverter;
import com.wish.rd.exec.repair.runtime.model.AgentExecutionEvent;
import com.wish.rd.exec.repair.runtime.AgentExecutionEventParser;
import com.wish.rd.exec.repair.runtime.usage.AgentEventTokenUsageParser;
import com.wish.rd.exec.repair.runtime.usage.AgentRuntimeMeasurementParser;
import com.wish.rd.exec.repair.runtime.usage.model.AgentRuntimeMeasurementSummary;
import com.wish.rd.exec.repair.runtime.AgentExecutionEventStore;
import com.wish.rd.exec.repair.runtime.model.AgentExecutionTraceSnapshot;
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
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
    private final AgentExecutionEventStore eventStore;
    private final DockerExecutorProperties dockerExecutorProperties;
    private final BudgetCurrencyConverter budgetCurrencyConverter;
    private final AgentStageProgressCalculator stageProgressCalculator;
    private final AgentEventTokenUsageParser agentEventTokenUsageParser = new AgentEventTokenUsageParser();
    private final AgentRuntimeMeasurementParser agentRuntimeMeasurementParser = new AgentRuntimeMeasurementParser();
    private final ClaudeExecutionTraceParser executionTraceParser = new ClaudeExecutionTraceParser();
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
            ObjectProvider<AgentExecutionEventStore> eventStoreProvider,
            ObjectProvider<DockerExecutorProperties> dockerExecutorPropertiesProvider,
            ObjectProvider<FinancialProperties> financialPropertiesProvider
    ) {
        this(
                registry,
                stageRunStoreProvider.getIfAvailable(InMemoryAgentStageRunStore::new),
                artifactStoreProvider.getIfAvailable(InMemoryAgentStageArtifactStore::new),
                contextPackageStoreProvider.getIfAvailable(InMemoryRoleContextPackageStore::new),
                executionRegistryProvider.getIfAvailable(DockerExecutionRegistry::noop),
                eventStoreProvider.getIfAvailable(AgentExecutionEventStore::noop),
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
                AgentExecutionEventStore.noop(),
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
                AgentExecutionEventStore.noop(),
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
            AgentExecutionEventStore eventStore,
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
        this.eventStore = eventStore == null ? AgentExecutionEventStore.noop() : eventStore;
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
        Map<String, AgentStageArtifact> latestAgentStateByStage = latestAgentStateSnapshots(task.taskId());
        AgentStageProgressCalculator.AgentStageProgress stageProgress = stageProgressCalculator.calculate(
                task.taskType(),
                stageRuns
        );
        List<RoleContextPackage> contextPackages = contextPackageStore.listByTask(task.taskId());
        ExecutionBudgetView budget = budget(contextPackages, stageRuns);
        List<StageRunView> stageViews = stageRuns.stream()
                .map(stageRun -> toStageRunView(stageRun, now, artifactById, latestAgentStateByStage))
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

    /**
     * Exposes a role-scoped, redacted trace for the currently running container or its archived safe snapshot.
     */
    @GetMapping("/admin/rd-tasks/{taskId}/stage-runs/{stageRunId}/execution-trace")
    public ClaudeExecutionTraceSnapshot getExecutionTrace(
            @PathVariable("taskId") String taskId,
            @PathVariable("stageRunId") String stageRunId,
            @RequestParam(name = "after", defaultValue = "0") long afterSequence,
            @RequestParam(name = "limit", defaultValue = "100") int limit
    ) {
        RdTask task = findTask(taskId);
        AgentStageRun stageRun = findStageRun(task.taskId(), stageRunId);
        ClaudeExecutionTraceSnapshot live = executionRegistry.executionTrace(
                task.taskId(),
                stageRun.stageRunId(),
                Math.max(0L, afterSequence),
                Math.max(1, Math.min(200, limit))
        );
        // Preserve the LIVE source while a container is registered, even before Claude has emitted
        // its first safe user-visible event. Otherwise the UI treats the empty snapshot as archived
        // and stops polling just as a role starts.
        if (live.available() || executionRegistry.isRunning(task.taskId(), stageRun.stageRunId())) {
            return live;
        }
        return artifactStore.listByTask(task.taskId()).stream()
                .filter(artifact -> stageRun.stageRunId().equals(artifact.stageRunId()))
                .filter(artifact -> "CLAUDE_EVENTS".equals(artifact.artifactType()))
                .max(RdTaskExecutionOverviewController::compareArtifact)
                .map(AgentStageArtifact::contentPreview)
                .map(executionTraceParser::parsePersistedSnapshot)
                .filter(ClaudeExecutionTraceSnapshot::available)
                .orElseGet(() -> ClaudeExecutionTraceSnapshot.unavailable("ARCHIVED"));
    }

    /** Returns the runtime-neutral normalized event stream for Pi and future agent runtimes. */
    @GetMapping(
            value = "/admin/rd-tasks/{taskId}/stage-runs/{stageRunId}/execution-events",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public AgentExecutionTraceSnapshot getExecutionEvents(
            @PathVariable("taskId") String taskId,
            @PathVariable("stageRunId") String stageRunId,
            @RequestParam(name = "after", defaultValue = "0") long afterSequence,
            @RequestParam(name = "limit", defaultValue = "100") int limit
    ) {
        RdTask task = findTask(taskId);
        findStageRun(task.taskId(), stageRunId);
        AgentExecutionTraceSnapshot live = eventStore.snapshot(
                task.taskId(),
                stageRunId,
                Math.max(0L, afterSequence),
                Math.max(1, Math.min(200, limit))
        );
        if (live.available()) {
            return live;
        }
        return artifactStore.listByTask(task.taskId()).stream()
                .filter(artifact -> stageRunId.equals(artifact.stageRunId()))
                .filter(artifact -> "AGENT_EVENTS".equals(artifact.artifactType()))
                .max(RdTaskExecutionOverviewController::compareArtifact)
                .map(AgentStageArtifact::contentPreview)
                .map(content -> AgentExecutionEventParser.parseJsonl(
                        content,
                        task.taskId(),
                        stageRunId,
                        Math.max(0L, afterSequence),
                        Math.max(1, Math.min(200, limit))
                ))
                .orElse(live);
    }

    /**
     * Opens a one-way SSE observation stream. There is deliberately no command
     * endpoint paired with this method, so browser disconnects cannot steer Pi.
     */
    @GetMapping(
            value = "/admin/rd-tasks/{taskId}/stage-runs/{stageRunId}/execution-events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter streamExecutionEvents(
            @PathVariable("taskId") String taskId,
            @PathVariable("stageRunId") String stageRunId,
            @RequestParam(name = "after", defaultValue = "0") long afterSequence,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId
    ) {
        RdTask task = findTask(taskId);
        findStageRun(task.taskId(), stageRunId);
        long cursor = Math.max(Math.max(0L, afterSequence), parseSequence(lastEventId));
        int boundedLimit = Math.max(1, Math.min(200, limit));
        SseEmitter emitter = new SseEmitter(30_000L);
        Object sendLock = new Object();
        AtomicLong delivered = new AtomicLong(cursor);
        AtomicReference<AutoCloseable> subscriptionRef = new AtomicReference<>();
        Runnable closeSubscription = () -> closeSubscription(subscriptionRef.getAndSet(null));
        emitter.onCompletion(closeSubscription);
        emitter.onTimeout(() -> {
            closeSubscription.run();
            emitter.complete();
        });
        emitter.onError(ignored -> closeSubscription.run());

        try {
            AutoCloseable subscription = eventStore.subscribe(task.taskId(), stageRunId, event -> {
                synchronized (sendLock) {
                    if (event.sequence() <= delivered.get()) {
                        return;
                    }
                    try {
                        sendEvent(emitter, event);
                        delivered.set(event.sequence());
                    } catch (java.io.IOException exception) {
                        closeSubscription.run();
                        emitter.completeWithError(exception);
                    }
                }
            });
            subscriptionRef.set(subscription);
            AgentExecutionTraceSnapshot snapshot = eventStore.snapshot(
                    task.taskId(), stageRunId, cursor, boundedLimit
            );
            synchronized (sendLock) {
                for (JsonNode event : snapshot.events()) {
                    long sequence = event.path("sequence").asLong(0L);
                    if (sequence <= delivered.get()) {
                        continue;
                    }
                    sendEvent(emitter, new AgentExecutionEvent(sequence, "", event));
                    delivered.set(sequence);
                }
            }
            if (snapshot.finalized()) {
                closeSubscription.run();
                emitter.complete();
            }
        } catch (java.io.IOException exception) {
            closeSubscription.run();
            emitter.completeWithError(exception);
        } catch (RuntimeException exception) {
            closeSubscription.run();
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    private AgentStageRun findStageRun(String taskId, String stageRunId) {
        return stageRunStore.listByTask(taskId).stream()
                .filter(candidate -> candidate.stageRunId().equals(stageRunId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "stage run does not belong to task"
                ));
    }

    private static void sendEvent(SseEmitter emitter, AgentExecutionEvent event) throws java.io.IOException {
        String eventType = event.event().path("eventType").asText("AGENT_EVENT");
        emitter.send(SseEmitter.event()
                .id(String.valueOf(event.sequence()))
                .name(eventType)
                .data(event.event()));
    }

    private static void closeSubscription(AutoCloseable subscription) {
        if (subscription == null) {
            return;
        }
        try {
            subscription.close();
        } catch (Exception ignored) {
            // A disconnected client has no recovery action here.
        }
    }

    private static long parseSequence(String value) {
        try {
            return value == null || value.isBlank() ? 0L : Math.max(0L, Long.parseLong(value.strip()));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
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

    private Map<String, AgentStageArtifact> latestAgentStateSnapshots(String taskId) {
        Map<String, AgentStageArtifact> latest = new LinkedHashMap<>();
        for (AgentStageArtifact artifact : artifactStore.listByTask(taskId)) {
            if (!"AGENT_STATE_SNAPSHOT".equals(artifact.artifactType())) {
                continue;
            }
            AgentStageArtifact existing = latest.get(artifact.stageRunId());
            if (existing == null || compareArtifact(artifact, existing) > 0) {
                latest.put(artifact.stageRunId(), artifact);
            }
        }
        return latest;
    }

    private StageRunView toStageRunView(
            AgentStageRun stageRun,
            long now,
            Map<String, AgentStageArtifact> artifactById,
            Map<String, AgentStageArtifact> latestAgentStateByStage
    ) {
        List<Map<String, Object>> providerAttempts = providerAttemptsCny(
                providerAttempts(stageRun.providerAttemptsJson())
        );
        long elapsedMillis = elapsedMillis(stageRun, now);
        AgentStageArtifact resultArtifact = artifactById.get(stageRun.resultArtifactId());
        String resultPreview = resultPreview(stageRun, resultArtifact);
        String resultSummary = resultArtifact == null ? "" : resultArtifact.summary();
        AgentStateOverviewSummary agentState = agentStateOverview(
                latestAgentStateByStage.get(stageRun.stageRunId())
        );
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
                stageRun.startedAtEpochMillis() > 0L && stageRun.finishedAtEpochMillis() <= 0L,
                agentState.available(),
                agentState.sequence(),
                agentState.schemaVersion(),
                agentState.todoPending(),
                agentState.todoInProgress(),
                agentState.todoBlocked(),
                agentState.todoDone(),
                agentState.previewTruncated(),
                agentState.contentHash()
        );
    }

    private AgentStateOverviewSummary agentStateOverview(AgentStageArtifact artifact) {
        if (artifact == null || artifact.contentPreview().isBlank()) {
            return AgentStateOverviewSummary.empty();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(artifact.contentPreview());
            long sequence = root.path("sequence").asLong(0L);
            String schemaVersion = root.path("protocol").asText("");
            int pending = 0;
            int inProgress = 0;
            int blocked = 0;
            int done = 0;
            JsonNode todos = root.path("todos");
            if (todos.isArray()) {
                for (JsonNode todo : todos) {
                    switch (todo.path("status").asText("")) {
                        case "PENDING" -> pending++;
                        case "IN_PROGRESS" -> inProgress++;
                        case "BLOCKED" -> blocked++;
                        case "DONE" -> done++;
                        default -> {
                            // CANCELLED and unknown statuses are omitted from summary counts.
                        }
                    }
                }
            }
            return new AgentStateOverviewSummary(
                    true,
                    sequence,
                    schemaVersion,
                    pending,
                    inProgress,
                    blocked,
                    done,
                    agentStatePreviewTruncated(artifact),
                    artifact.contentHash()
            );
        } catch (Exception ignored) {
            return AgentStateOverviewSummary.empty();
        }
    }

    private static boolean agentStatePreviewTruncated(AgentStageArtifact artifact) {
        try {
            JsonNode metadata = OBJECT_MAPPER.readTree(artifact.metadataJson());
            long contentLength = metadata.path("contentLength").asLong(0L);
            if (contentLength > 0L) {
                return contentLength > artifact.contentPreview().length();
            }
        } catch (Exception ignored) {
            // Metadata is optional for overview truncation hints.
        }
        return false;
    }

    private record AgentStateOverviewSummary(
            boolean available,
            long sequence,
            String schemaVersion,
            int todoPending,
            int todoInProgress,
            int todoBlocked,
            int todoDone,
            boolean previewTruncated,
            String contentHash
    ) {
        private static AgentStateOverviewSummary empty() {
            return new AgentStateOverviewSummary(false, 0L, "", 0, 0, 0, 0, false, "");
        }
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
        boolean actualAvailable = false;
        long finalActualTokens = 0L;
        for (AgentStageRun stageRun : stageRuns) {
            long stageTokens = measuredStageActualTokens(stageRun);
            if (stageTokens > 0L) {
                actualAvailable = true;
                finalActualTokens = safeAdd(finalActualTokens, stageTokens);
            }
        }
        long runningTokens = runningExecutions.stream()
                .map(DockerExecutionRegistry.RunningExecution::tokenUsage)
                .filter(usage -> usage != null && usage.available())
                .mapToLong(usage -> usage.totalTokens())
                .reduce(0L, RdTaskExecutionOverviewController::safeAdd);
        if (runningTokens > 0L) {
            actualAvailable = true;
        }
        long actualAccumulatedTokens = actualAvailable
                ? safeAdd(finalActualTokens, runningTokens)
                : 0L;
        boolean estimateAvailable = estimatedTotalTokens > 0L || !confidence.isBlank() || !basis.isBlank();
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

    private long measuredStageActualTokens(AgentStageRun stageRun) {
        long attemptTokens = 0L;
        boolean attemptMeasured = false;
        for (Map<String, Object> attempt : providerAttempts(stageRun.providerAttemptsJson())) {
            if (!providerAttemptUsageAvailable(attempt)) {
                continue;
            }
            attemptMeasured = true;
            attemptTokens = safeAdd(attemptTokens, measuredTotalTokens(attempt));
        }
        if (attemptMeasured) {
            return attemptTokens;
        }
        long measurementTokens = measuredTokensFromRuntimeMeasurement(stageRun);
        if (measurementTokens > 0L) {
            return measurementTokens;
        }
        if (shouldMeasureAgentEventsArtifact(stageRun)) {
            return measuredTokensFromAgentEvents(stageRun);
        }
        return 0L;
    }

    private boolean shouldMeasureAgentEventsArtifact(AgentStageRun stageRun) {
        if (isTerminalFailureStage(stageRun)) {
            return hasAgentEventsArtifact(stageRun);
        }
        return true;
    }

    private boolean isTerminalFailureStage(AgentStageRun stageRun) {
        if (stageRun == null) {
            return false;
        }
        AgentStageStatus status = stageRun.status();
        return status == AgentStageStatus.FAILED_RETRYABLE
                || status == AgentStageStatus.FAILED_NEEDS_HUMAN
                || "ORCHESTRATION_INTERRUPTED".equalsIgnoreCase(text(stageRun.errorCategory()));
    }

    private boolean hasAgentEventsArtifact(AgentStageRun stageRun) {
        return artifactStore.listByTask(stageRun.taskId()).stream()
                .anyMatch(artifact -> stageRun.stageRunId().equals(artifact.stageRunId())
                        && "AGENT_EVENTS".equals(artifact.artifactType()));
    }

    private boolean providerAttemptUsageAvailable(Map<String, Object> attempt) {
        if (attempt == null || attempt.isEmpty()) {
            return false;
        }
        if (Boolean.TRUE.equals(attempt.get("tokenUsageAvailable"))) {
            return true;
        }
        if (Boolean.TRUE.equals(attempt.get("tokenUsageFinalized"))
                && measuredTotalTokens(attempt) > 0L) {
            return true;
        }
        return measuredTotalTokens(attempt) > 0L && isFailureProviderAttemptStatus(text(attempt.get("status")));
    }

    private static boolean isFailureProviderAttemptStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "FAILED", "FAILED_RETRYABLE", "FAILED_NEEDS_HUMAN", "FAILED_VALIDATION",
                    "ORCHESTRATION_INTERRUPTED", "INTERRUPTED" -> true;
            default -> false;
        };
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().strip();
    }

    private long measuredTotalTokens(Map<String, Object> attempt) {
        long total = nonNegativeLong(attempt.get("totalTokens"));
        if (total > 0L) {
            return total;
        }
        return safeAdd(
                nonNegativeLong(attempt.get("inputTokens")),
                safeAdd(
                        nonNegativeLong(attempt.get("outputTokens")),
                        safeAdd(
                                nonNegativeLong(attempt.get("cacheReadInputTokens")),
                                nonNegativeLong(attempt.get("cacheCreationInputTokens"))
                        )
                )
        );
    }

    private long measuredTokensFromRuntimeMeasurement(AgentStageRun stageRun) {
        return artifactStore.listByTask(stageRun.taskId()).stream()
                .filter(artifact -> stageRun.stageRunId().equals(artifact.stageRunId()))
                .filter(artifact -> "RUNTIME_MEASUREMENT".equals(artifact.artifactType()))
                .max(RdTaskExecutionOverviewController::compareArtifact)
                .map(artifact -> parseRuntimeMeasurementTokens(artifact.contentPreview()))
                .orElse(0L);
    }

    private long parseRuntimeMeasurementTokens(String preview) {
        if (preview == null || preview.isBlank()) {
            return 0L;
        }
        try {
            AgentRuntimeMeasurementSummary summary = OBJECT_MAPPER.readValue(
                    preview, AgentRuntimeMeasurementSummary.class);
            return summary != null && summary.usageAvailable() ? summary.totalTokens() : 0L;
        } catch (Exception ignored) {
            AgentRuntimeMeasurementSummary parsed = agentRuntimeMeasurementParser.parse(preview);
            return parsed.usageAvailable() ? parsed.totalTokens() : 0L;
        }
    }

    private long measuredTokensFromAgentEvents(AgentStageRun stageRun) {
        return artifactStore.listByTask(stageRun.taskId()).stream()
                .filter(artifact -> stageRun.stageRunId().equals(artifact.stageRunId()))
                .filter(artifact -> "AGENT_EVENTS".equals(artifact.artifactType()))
                .max(RdTaskExecutionOverviewController::compareArtifact)
                .map(artifact -> agentEventTokenUsageParser.parse(artifact.contentPreview()))
                .filter(usage -> usage.available())
                .map(usage -> usage.totalTokens())
                .orElse(0L);
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
            boolean running,
            boolean agentStateAvailable,
            long agentStateSequence,
            String agentStateSchemaVersion,
            int agentStateTodoPending,
            int agentStateTodoInProgress,
            int agentStateTodoBlocked,
            int agentStateTodoDone,
            boolean agentStatePreviewTruncated,
            String agentStateContentHash
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
