package com.wish.rd.engine.admin.dashboard;

import com.wish.rd.engine.admin.dashboard.model.RdDashboardOverview;
import com.wish.rd.rag.knowledge.model.KnowledgeBase;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.store.KnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentStore;
import com.wish.rd.rag.project.RdProjectService;
import com.wish.rd.rag.project.model.RdProject;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 项目交付驾驶舱查询服务。
 *
 * <p>服务对任务主状态进行唯一分类，并把项目、知识库和运行态端口组合为 Dashboard 的单个
 * 只读响应。所有返回数据都来自完整任务集合，而非管理台分页结果。
 */
@Service
public final class RdDashboardQueryService {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;
    private static final String ALL_PROJECTS_NAME = "全部项目";
    private static final Set<RdTaskStatus> SUCCESS_STATUSES = EnumSet.of(
            RdTaskStatus.COMMITTED,
            RdTaskStatus.MERGED,
            RdTaskStatus.COMPLETED
    );
    private static final Set<RdTaskStatus> FAILURE_STATUSES = EnumSet.of(
            RdTaskStatus.REJECTED,
            RdTaskStatus.FAILED_RETRYABLE,
            RdTaskStatus.FAILED_NEEDS_HUMAN,
            RdTaskStatus.DEAD_LETTERED
    );
    private static final Set<RdTaskStatus> WAITING_HUMAN_STATUSES = EnumSet.of(
            RdTaskStatus.WAITING_APPROVAL,
            RdTaskStatus.WAITING_USER_INPUT,
            RdTaskStatus.FAILED_NEEDS_HUMAN
    );
    private static final Set<RdTaskStatus> BLOCKED_STATUSES = EnumSet.of(
            RdTaskStatus.REJECTED,
            RdTaskStatus.FAILED_RETRYABLE,
            RdTaskStatus.FAILED_NEEDS_HUMAN,
            RdTaskStatus.DEAD_LETTERED
    );
    private static final Set<RdTaskStatus> EXCLUDED_FROM_IN_PROGRESS = EnumSet.of(
            RdTaskStatus.CREATED,
            RdTaskStatus.WAITING_POLICY,
            RdTaskStatus.WAITING_APPROVAL,
            RdTaskStatus.WAITING_USER_INPUT,
            RdTaskStatus.CANCELLED,
            RdTaskStatus.DELETED,
            RdTaskStatus.REJECTED,
            RdTaskStatus.FAILED_RETRYABLE,
            RdTaskStatus.FAILED_NEEDS_HUMAN,
            RdTaskStatus.DEAD_LETTERED,
            RdTaskStatus.COMMITTED,
            RdTaskStatus.MERGED,
            RdTaskStatus.COMPLETED
    );
    private static final Set<RdTaskStatus> RECENT_DELIVERY_STATUSES = EnumSet.of(
            RdTaskStatus.COMMITTED,
            RdTaskStatus.MERGED,
            RdTaskStatus.COMPLETED,
            RdTaskStatus.REJECTED,
            RdTaskStatus.FAILED_RETRYABLE,
            RdTaskStatus.FAILED_NEEDS_HUMAN,
            RdTaskStatus.DEAD_LETTERED
    );
    private static final Comparator<RdTask> UPDATED_DESC = Comparator
            .comparingLong(RdTask::updateTimeEpochMillis)
            .reversed()
            .thenComparing(RdTask::taskId);

    private final RagStreamTaskRegistry registry;
    private final RdProjectService projectService;
    private final KnowledgeBaseStore knowledgeBaseStore;
    private final KnowledgeDocumentStore knowledgeDocumentStore;
    private final DashboardRuntimeSnapshotPort runtimeSnapshotPort;

    /**
     * Creates the Spring-managed dashboard query service.
     *
     * <p>Memory-mode test contexts intentionally do not expose project management. The all-project
     * dashboard remains useful there, while an explicit project selection still requires the
     * PostgreSQL-backed project service.
     *
     * @param registry task state registry
     * @param projectServiceProvider optional project management provider
     * @param knowledgeBaseStore knowledge-base metadata store
     * @param knowledgeDocumentStore knowledge-document metadata store
     * @param runtimeSnapshotPort runtime snapshot port
     */
    @Autowired
    public RdDashboardQueryService(
            RagStreamTaskRegistry registry,
            ObjectProvider<RdProjectService> projectServiceProvider,
            KnowledgeBaseStore knowledgeBaseStore,
            KnowledgeDocumentStore knowledgeDocumentStore,
            DashboardRuntimeSnapshotPort runtimeSnapshotPort
    ) {
        this(
                registry,
                projectServiceProvider == null ? null : projectServiceProvider.getIfAvailable(),
                knowledgeBaseStore,
                knowledgeDocumentStore,
                runtimeSnapshotPort
        );
    }

    /**
     * 创建 Dashboard 查询服务。
     *
     * @param registry 任务主状态真值
     * @param projectService 项目配置读取服务
     * @param knowledgeBaseStore 知识库元数据端口
     * @param knowledgeDocumentStore 知识文档元数据端口
     * @param runtimeSnapshotPort 容器、告警和阶段运行状态端口
     */
    public RdDashboardQueryService(
            RagStreamTaskRegistry registry,
            RdProjectService projectService,
            KnowledgeBaseStore knowledgeBaseStore,
            KnowledgeDocumentStore knowledgeDocumentStore,
            DashboardRuntimeSnapshotPort runtimeSnapshotPort
    ) {
        this.registry = java.util.Objects.requireNonNull(registry, "registry must not be null");
        this.projectService = projectService;
        this.knowledgeBaseStore = knowledgeBaseStore;
        this.knowledgeDocumentStore = knowledgeDocumentStore;
        this.runtimeSnapshotPort = runtimeSnapshotPort == null
                ? DashboardRuntimeSnapshotPort.unavailable()
                : runtimeSnapshotPort;
    }

    /**
     * 聚合项目或全部项目的交付状态。
     *
     * @param projectId 项目 ID；为空时表示全部项目
     * @param limit 当前执行和近期交付各自的最大返回行数
     * @return Dashboard 只读聚合
     */
    public RdDashboardOverview overview(String projectId, int limit) {
        int safeLimit = requireLimit(limit);
        RdProject project = selectedProject(projectId);
        String selectedProjectId = project == null ? "" : project.projectId();
        List<RdTask> tasks = registry.listTasks().stream()
                .filter(task -> task.status() != RdTaskStatus.DELETED)
                .filter(task -> selectedProjectId.isBlank() || selectedProjectId.equals(taskProjectId(task)))
                .sorted(UPDATED_DESC)
                .toList();
        DashboardRuntimeSnapshotPort.Snapshot runtimeSnapshot = runtimeSnapshotPort.snapshot(
                selectedProjectId,
                tasks.stream().map(RdTask::taskId).toList()
        );
        DashboardRuntimeSnapshotPort.Snapshot safeRuntimeSnapshot = runtimeSnapshot == null
                ? DashboardRuntimeSnapshotPort.Snapshot.unavailable()
                : runtimeSnapshot;

        return new RdDashboardOverview(
                selectedProjectId,
                project == null ? ALL_PROJECTS_NAME : project.name(),
                countType(tasks, "REQUIREMENT"),
                countType(tasks, "BUG_FIX"),
                countStatuses(tasks, status -> !EXCLUDED_FROM_IN_PROGRESS.contains(status)),
                countStatuses(tasks, WAITING_HUMAN_STATUSES::contains),
                countStatuses(tasks, SUCCESS_STATUSES::contains),
                countStatuses(tasks, BLOCKED_STATUSES::contains),
                statusCounts(tasks),
                successRate(tasks),
                safeRuntimeSnapshot.runtime(),
                taskSummaries(tasks, safeRuntimeSnapshot.taskRuntimes(), status -> !EXCLUDED_FROM_IN_PROGRESS.contains(status), safeLimit),
                taskSummaries(tasks, safeRuntimeSnapshot.taskRuntimes(), RECENT_DELIVERY_STATUSES::contains, safeLimit),
                knowledgeSupport(project),
                System.currentTimeMillis()
        );
    }

    private RdProject selectedProject(String projectId) {
        String safeProjectId = projectId == null ? "" : projectId.strip();
        if (safeProjectId.isBlank()) {
            return null;
        }
        if (projectService == null) {
            throw new NoSuchElementException("project management is unavailable");
        }
        return projectService.get(safeProjectId);
    }

    private static int requireLimit(int limit) {
        if (limit <= 0 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }

    /** Returns the recommended first-page size used by the HTTP adapter. */
    public static int defaultLimit() {
        return DEFAULT_LIMIT;
    }

    private static long countType(List<RdTask> tasks, String taskType) {
        return tasks.stream().filter(task -> taskType.equalsIgnoreCase(task.taskType())).count();
    }

    private static long countStatuses(List<RdTask> tasks, java.util.function.Predicate<RdTaskStatus> matcher) {
        return tasks.stream().map(RdTask::status).filter(matcher).count();
    }

    private static Map<String, Long> statusCounts(List<RdTask> tasks) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (RdTaskStatus status : RdTaskStatus.values()) {
            long count = tasks.stream().filter(task -> task.status() == status).count();
            if (count > 0L) {
                counts.put(status.name(), count);
            }
        }
        return counts;
    }

    private static RdDashboardOverview.AvailabilityRatio successRate(List<RdTask> tasks) {
        long success = countStatuses(tasks, SUCCESS_STATUSES::contains);
        long failure = countStatuses(tasks, FAILURE_STATUSES::contains);
        long terminalSamples = success + failure;
        if (terminalSamples == 0L) {
            return RdDashboardOverview.AvailabilityRatio.unavailable();
        }
        return new RdDashboardOverview.AvailabilityRatio(
                true,
                BigDecimal.valueOf(success)
                        .divide(BigDecimal.valueOf(terminalSamples), 4, RoundingMode.HALF_UP)
        );
    }

    private static List<RdDashboardOverview.TaskSummary> taskSummaries(
            List<RdTask> tasks,
            Map<String, DashboardRuntimeSnapshotPort.TaskRuntime> runtimes,
            java.util.function.Predicate<RdTaskStatus> matcher,
            int limit
    ) {
        Map<String, DashboardRuntimeSnapshotPort.TaskRuntime> safeRuntimes = runtimes == null ? Map.of() : runtimes;
        return tasks.stream()
                .filter(task -> matcher.test(task.status()))
                .limit(limit)
                .map(task -> taskSummary(task, safeRuntimes.get(task.taskId())))
                .toList();
    }

    private static RdDashboardOverview.TaskSummary taskSummary(
            RdTask task,
            DashboardRuntimeSnapshotPort.TaskRuntime runtime
    ) {
        DashboardRuntimeSnapshotPort.TaskRuntime safeRuntime = runtime == null
                ? DashboardRuntimeSnapshotPort.TaskRuntime.empty()
                : runtime;
        return new RdDashboardOverview.TaskSummary(
                task.taskId(),
                taskProjectId(task),
                task.taskType(),
                task.status().name(),
                task.title(),
                task.updateTimeEpochMillis(),
                safeRuntime.currentRole(),
                safeRuntime.currentStageStatus(),
                safeRuntime.progressCompleted(),
                safeRuntime.progressTotal(),
                safeRuntime.provider(),
                safeRuntime.retryCount(),
                safeRuntime.elapsedMillis(),
                safeRuntime.running()
        );
    }

    private RdDashboardOverview.KnowledgeSupport knowledgeSupport(RdProject project) {
        if (project == null || project.knowledgeBaseId().isBlank()
                || knowledgeBaseStore == null || knowledgeDocumentStore == null) {
            return RdDashboardOverview.KnowledgeSupport.unavailable();
        }
        KnowledgeBase knowledgeBase = knowledgeBaseStore.findById(project.knowledgeBaseId()).orElse(null);
        if (knowledgeBase == null) {
            return RdDashboardOverview.KnowledgeSupport.unavailable();
        }
        List<KnowledgeDocument> documents = knowledgeDocumentStore.listByKnowledgeBaseId(knowledgeBase.id());
        return new RdDashboardOverview.KnowledgeSupport(
                true,
                knowledgeBase.id(),
                knowledgeBase.name(),
                documents.size(),
                documents.stream().filter(KnowledgeDocument::enabled).count()
        );
    }

    private static String taskProjectId(RdTask task) {
        if (task instanceof RdRequirementTask requirementTask) {
            return requirementTask.projectId();
        }
        if (task instanceof RdBugFixTask bugFixTask) {
            return bugFixTask.projectId();
        }
        return "";
    }
}
