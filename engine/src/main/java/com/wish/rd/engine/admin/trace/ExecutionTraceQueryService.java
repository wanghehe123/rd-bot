package com.wish.rd.engine.admin.trace;

import com.wish.rd.engine.admin.trace.model.ExecutionTracePage;
import com.wish.rd.engine.admin.trace.model.ExecutionTraceQuery;
import com.wish.rd.engine.admin.trace.model.ExecutionTraceRecord;

import com.wish.rd.engine.agent.AgentStageProgressCalculator;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Builds task-rooted execution trace rows without issuing one stage query per task. */
public final class ExecutionTraceQueryService {

    private static final Set<RdTaskStatus> BLOCKING_STATUSES = Set.of(
            RdTaskStatus.REJECTED,
            RdTaskStatus.FAILED_RETRYABLE,
            RdTaskStatus.FAILED_NEEDS_HUMAN,
            RdTaskStatus.DEAD_LETTERED
    );

    private final RagStreamTaskRegistry taskRegistry;
    private final AgentStageRunStore stageRunStore;
    private final AgentStageProgressCalculator progressCalculator;

    public ExecutionTraceQueryService(
            RagStreamTaskRegistry taskRegistry,
            AgentStageRunStore stageRunStore
    ) {
        this(taskRegistry, stageRunStore, new AgentStageProgressCalculator());
    }

    public ExecutionTraceQueryService(
            RagStreamTaskRegistry taskRegistry,
            AgentStageRunStore stageRunStore,
            AgentStageProgressCalculator progressCalculator
    ) {
        this.taskRegistry = taskRegistry == null ? RagStreamTaskRegistry.inMemory() : taskRegistry;
        this.stageRunStore = stageRunStore == null ? new com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore() : stageRunStore;
        this.progressCalculator = progressCalculator == null ? new AgentStageProgressCalculator() : progressCalculator;
    }

    public ExecutionTracePage query(ExecutionTraceQuery query) {
        ExecutionTraceQuery safeQuery = query == null
                ? new ExecutionTraceQuery("", "", "", "", "", "", 1, 20)
                : query;
        List<RdTask> tasks = taskRegistry.listTasks().stream()
                .filter(task -> task.status() != RdTaskStatus.DELETED)
                .filter(task -> matchesTask(task, safeQuery))
                .sorted(Comparator.comparingLong(RdTask::updateTimeEpochMillis).reversed()
                        .thenComparing(RdTask::taskId))
                .toList();
        Map<String, List<AgentStageRun>> runsByTask = stageRunStore.listByTasks(
                        tasks.stream().map(RdTask::taskId).toList())
                .stream()
                .collect(Collectors.groupingBy(AgentStageRun::taskId));
        List<ExecutionTraceRecord> filtered = tasks.stream()
                .map(task -> toRecord(task, runsByTask.getOrDefault(task.taskId(), List.of())))
                .filter(record -> matchesStage(record, safeQuery))
                .toList();
        int total = filtered.size();
        int fromIndex = Math.min((safeQuery.page() - 1) * safeQuery.pageSize(), total);
        int toIndex = Math.min(fromIndex + safeQuery.pageSize(), total);
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / safeQuery.pageSize());
        return new ExecutionTracePage(filtered.subList(fromIndex, toIndex), total,
                safeQuery.page(), safeQuery.pageSize(), pages);
    }

    private boolean matchesTask(RdTask task, ExecutionTraceQuery query) {
        if (!query.projectId().isBlank() && !query.projectId().equals(projectId(task))) {
            return false;
        }
        if (!query.taskType().isBlank() && !query.taskType().equalsIgnoreCase(task.taskType())) {
            return false;
        }
        if (!query.status().isBlank() && !query.status().equalsIgnoreCase(task.status().name())) {
            return false;
        }
        if (query.keyword().isBlank()) {
            return true;
        }
        String candidate = (task.taskId() + "\n" + task.title() + "\n" + task.errorMessage()).toLowerCase(java.util.Locale.ROOT);
        return candidate.contains(query.keyword().toLowerCase(java.util.Locale.ROOT));
    }

    private boolean matchesStage(ExecutionTraceRecord record, ExecutionTraceQuery query) {
        if (!query.role().isBlank() && !query.role().equalsIgnoreCase(record.currentRole())) {
            return false;
        }
        return query.provider().isBlank() || query.provider().equalsIgnoreCase(record.providerName());
    }

    private ExecutionTraceRecord toRecord(RdTask task, List<AgentStageRun> stageRuns) {
        AgentStageProgressCalculator.AgentStageProgress progress = progressCalculator.calculate(task.taskType(), stageRuns);
        long now = System.currentTimeMillis();
        long end = terminal(task.status()) ? task.updateTimeEpochMillis() : now;
        return new ExecutionTraceRecord(
                task.taskId(),
                projectId(task),
                projectName(task),
                task.taskType(),
                task.status().name(),
                task.title(),
                progress.currentRole(),
                progress.currentStatus(),
                progress.provider(),
                progress.completedSteps(),
                progress.totalSteps(),
                progress.retryCount(),
                BLOCKING_STATUSES.contains(task.status()),
                Math.max(0L, end - task.createTimeEpochMillis()),
                task.updateTimeEpochMillis()
        );
    }

    private static String projectId(RdTask task) {
        if (task instanceof RdBugFixTask bugFixTask) {
            return bugFixTask.projectId();
        }
        if (task instanceof RdRequirementTask requirementTask) {
            return requirementTask.projectId();
        }
        return "";
    }

    private static String projectName(RdTask task) {
        if (task instanceof RdBugFixTask bugFixTask) {
            return bugFixTask.projectName();
        }
        if (task instanceof RdRequirementTask requirementTask) {
            return requirementTask.projectName();
        }
        return "";
    }

    private static boolean terminal(RdTaskStatus status) {
        return status == RdTaskStatus.COMMITTED
                || status == RdTaskStatus.MERGED
                || status == RdTaskStatus.COMPLETED
                || status == RdTaskStatus.REJECTED
                || status == RdTaskStatus.FAILED_RETRYABLE
                || status == RdTaskStatus.FAILED_NEEDS_HUMAN
                || status == RdTaskStatus.CANCELLED
                || status == RdTaskStatus.DEAD_LETTERED;
    }
}
