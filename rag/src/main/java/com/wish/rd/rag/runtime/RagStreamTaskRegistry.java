package com.wish.rd.rag.runtime;

import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.impl.CoordinatedRdTaskStatePersistence;

import com.wish.rd.adapter.model.TicketSnapshot;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.lock.DistributedLockExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskEventTrigger;
import com.wish.rd.rag.runtime.model.RdTaskPage;
import com.wish.rd.rag.runtime.model.RdTaskQuery;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.RdTaskStatusEvent;
import com.wish.rd.rag.runtime.model.RdTaskType;

/**
 * RD 任务状态机注册表。
 *
 * <p>供 /rag/v3/tasks/{taskId}、/rag/v3/stop、RAG 编排和 RD 修复全流程编排共同推进任务状态。
 * 任务快照通过 {@link RdTaskStore} 持久化，默认内存实现用于本地和单测，PostgreSQL 实现由 bootstrap 提供。
 *
 * <p>每次状态推进与创建都会向 {@link RdTaskStatusEventStore} 追加一条状态事件，供管理台
 * 全链路时间线展示；管理台的暂停 / 恢复 / 修改 / 删除也经由本注册表执行，保证状态事件一致。
 */
@Component
public final class RagStreamTaskRegistry {

    private static final String TASK_LOCK_PREFIX = "rd-bot:lock:rd-task:";
    private static final String TICKET_LOCK_PREFIX = "rd-bot:lock:rd-ticket:";

    private final RdTaskStore taskStore;
    private final RdTaskStatusEventStore eventStore;
    private final SnowflakeIdGenerator idGenerator;
    private final DistributedLockExecutor lockExecutor;
    private final RdTaskStatePersistence statePersistence;

    @Autowired
    public RagStreamTaskRegistry(
            RdTaskStore taskStore,
            RdTaskStatusEventStore eventStore,
            SnowflakeIdGenerator idGenerator,
            ObjectProvider<DistributedLockExecutor> lockExecutorProvider,
            ObjectProvider<RdTaskStatePersistence> statePersistenceProvider
    ) {
        this(
                taskStore,
                eventStore,
                idGenerator,
                lockExecutorProvider == null
                        ? DistributedLockExecutor.local()
                        : lockExecutorProvider.getIfAvailable(DistributedLockExecutor::local),
                statePersistenceProvider == null ? null : statePersistenceProvider.getIfAvailable()
        );
    }

    public RagStreamTaskRegistry(
            RdTaskStore taskStore,
            RdTaskStatusEventStore eventStore,
            SnowflakeIdGenerator idGenerator
    ) {
        this(taskStore, eventStore, idGenerator, DistributedLockExecutor.local());
    }

    public RagStreamTaskRegistry(
            RdTaskStore taskStore,
            RdTaskStatusEventStore eventStore,
            SnowflakeIdGenerator idGenerator,
            DistributedLockExecutor lockExecutor
    ) {
        this(taskStore, eventStore, idGenerator, lockExecutor, null);
    }

    public RagStreamTaskRegistry(
            RdTaskStore taskStore,
            RdTaskStatusEventStore eventStore,
            SnowflakeIdGenerator idGenerator,
            DistributedLockExecutor lockExecutor,
            RdTaskStatePersistence statePersistence
    ) {
        this.taskStore = taskStore == null ? new InMemoryRdTaskStore() : taskStore;
        this.eventStore = eventStore;
        this.idGenerator = idGenerator == null ? SnowflakeIdGenerator.defaultGenerator() : idGenerator;
        this.lockExecutor = lockExecutor == null ? DistributedLockExecutor.local() : lockExecutor;
        this.statePersistence = statePersistence == null
                ? new CoordinatedRdTaskStatePersistence(this.taskStore, this.eventStore)
                : statePersistence;
    }

    /**
     * 兼容旧调用方：不写状态事件（eventStore 为 null），仅维护任务快照。
     *
     * @param taskStore    任务存储
     * @param idGenerator  ID 生成器
     */
    public RagStreamTaskRegistry(RdTaskStore taskStore, SnowflakeIdGenerator idGenerator) {
        this(taskStore, null, idGenerator);
    }

    /**
     * 创建内存任务注册表（事件 store 为空时不写事件，向后兼容单测）。
     *
     * @return 内存任务注册表
     */
    public static RagStreamTaskRegistry inMemory() {
        return new RagStreamTaskRegistry(new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), SnowflakeIdGenerator.defaultGenerator());
    }

    private <T> T withTaskLock(String taskId, Supplier<T> action) {
        return lockExecutor.execute(TASK_LOCK_PREFIX + requireTaskId(taskId), action);
    }

    private <T> T withTicketLock(String ticketId, Supplier<T> action) {
        String safeTicketId = ticketId == null ? "" : ticketId.strip();
        if (safeTicketId.isBlank()) {
            return action.get();
        }
        return lockExecutor.execute(TICKET_LOCK_PREFIX + safeTicketId, action);
    }

    /**
     * 创建 Bug 修复任务，初始状态为 CREATED。
     *
     * @param ticket   工单快照
     * @param priority 优先级
     * @return 新任务快照
     */
    public RdBugFixTask createBugFixTask(TicketSnapshot ticket, String priority) {
        TicketSnapshot safeTicket = normalizeTicket(ticket);
        RdBugFixTask task = RdBugFixTask.created(
                idGenerator.nextIdString(),
                safeTicket.ticketId(),
                safeTicket.title(),
                priority,
                System.currentTimeMillis()
        );
        return withTaskLock(task.taskId(), () -> {
            return (RdBugFixTask) saveTaskWithEvent(
                    task, RdTaskStatus.CREATED.name(), task.title(), "任务创建", RdTaskEventTrigger.SYSTEM);
        });
    }

    /**
     * 创建或复用 Bug 修复任务。用于 MQ 重试/重复投递时保持同一工单只占用一个 RD 任务。
     *
     * @param ticket   工单快照
     * @param priority 优先级
     * @return 已存在任务或新任务快照
     */
    public RdBugFixTask createOrReuseBugFixTask(TicketSnapshot ticket, String priority) {
        TicketSnapshot safeTicket = normalizeTicket(ticket);
        return withTicketLock(safeTicket.ticketId(), () -> {
            if (!safeTicket.ticketId().isBlank()) {
                Optional<RdBugFixTask> existing = taskStore.findLatestBugFixTaskByTicketId(safeTicket.ticketId());
                if (existing.isPresent()) {
                    return existing.get();
                }
            }
            return createBugFixTask(safeTicket, priority);
        });
    }

    /**
     * 管理台创建任务：携带 prompt 快照与展示标题。
     *
     * @param ticketId       工单 ID
     * @param ticketTitle    工单标题
     * @param title          展示标题
     * @param priority       优先级
     * @param promptSnapshot Prompt 快照
     * @return 新任务快照
     */
    public RdBugFixTask createTaskManually(
            String ticketId,
            String ticketTitle,
            String title,
            String priority,
            String promptSnapshot
    ) {
        return createTaskManually(ticketId, ticketTitle, title, priority, promptSnapshot, "", "", "", "", "", "", "");
    }

    /**
     * 管理台创建任务：携带项目仓库快照。
     *
     * @param ticketId       工单 ID
     * @param ticketTitle    工单标题
     * @param title          展示标题
     * @param priority       优先级
     * @param promptSnapshot Prompt 快照
     * @param projectId      项目 ID
     * @param projectKey     项目 key
     * @param projectName    项目名称
     * @param repositoryUrl  仓库地址
     * @param repoOwner      仓库 owner
     * @param repoName       仓库名
     * @param baseBranch     基准分支
     * @return 新任务快照
     */
    public RdBugFixTask createTaskManually(
            String ticketId,
            String ticketTitle,
            String title,
            String priority,
            String promptSnapshot,
            String projectId,
            String projectKey,
            String projectName,
            String repositoryUrl,
            String repoOwner,
            String repoName,
            String baseBranch
    ) {
        long now = System.currentTimeMillis();
        RdBugFixTask task = new RdBugFixTask(
                idGenerator.nextIdString(),
                    RdBugFixTask.TASK_TYPE,
                    ticketId,
                    ticketTitle,
                    priority,
                    RdTaskStatus.CREATED,
                    "",
                    title == null || title.isBlank() ? (ticketTitle == null ? "" : ticketTitle) : title,
                    promptSnapshot,
                    "",
                    "",
                    "",
                    projectId,
                    projectKey,
                    projectName,
                    repositoryUrl,
                    repoOwner,
                    repoName,
                    baseBranch,
                    now,
                    now,
                false
        );
        return withTaskLock(task.taskId(), () -> {
            return (RdBugFixTask) saveTaskWithEvent(
                    task, RdTaskStatus.CREATED.name(), task.title(), "管理台创建任务", RdTaskEventTrigger.API);
        });
    }

    /**
     * 创建需求交付任务，初始状态为 CREATED。
     *
     * @param command 创建命令
     * @return 新需求任务快照
     */
    public RdRequirementTask createRequirementTask(CreateRequirementTaskCommand command) {
        CreateRequirementTaskCommand safeCommand = command == null
                ? new CreateRequirementTaskCommand("", "P2", "", "", "", "", "", List.of(), false)
                : command;
        if (safeCommand.title().isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (safeCommand.baseBranch().isBlank()) {
            throw new IllegalArgumentException("baseBranch must not be blank");
        }
        if (safeCommand.repositoryUrl().isBlank()
                && (safeCommand.repoOwner().isBlank() || safeCommand.repoName().isBlank())) {
            throw new IllegalArgumentException("repositoryUrl or repoOwner/repoName must not be blank");
        }
        if (safeCommand.expectedResult().isBlank()) {
            throw new IllegalArgumentException("expectedResult must not be blank");
        }
        long now = System.currentTimeMillis();
        RdRequirementTask task = RdRequirementTask.created(idGenerator.nextIdString(), safeCommand, now);
        return withTaskLock(task.taskId(), () -> {
            return (RdRequirementTask) saveTaskWithEvent(
                    task, RdTaskStatus.CREATED.name(), task.title(), "管理台创建需求任务", RdTaskEventTrigger.API);
        });
    }

    /**
     * 将任务推进到 SEARCHING。
     *
     * @param taskId  任务 ID
     * @param summary 检索摘要或标题
     * @return 新任务快照
     */
    public RdBugFixTask markSearching(String taskId, String summary) {
        return withTaskLock(taskId, () -> {
            RdBugFixTask existing = get(taskId);
            return transitionAndSave(existing, RdTaskStatus.SEARCHING, "", summary, "", "", "", "");
        });
    }

    /**
     * 将任务推进到 EXECUTING。
     *
     * @param taskId         任务 ID
     * @param promptSnapshot Prompt 快照
     * @return 新任务快照
     */
    public RdBugFixTask markExecuting(String taskId, String promptSnapshot) {
        return withTaskLock(taskId, () -> {
            RdBugFixTask existing = get(taskId);
            return transitionAndSave(existing, RdTaskStatus.EXECUTING, "", "", promptSnapshot, "", "", "");
        });
    }

    /**
     * 将任务推进到 COMMITTED。
     *
     * @param taskId              任务 ID
     * @param pullRequestUrl      PR 链接
     * @param executionResultJson 执行结果 JSON
     * @return 新任务快照
     */
    public RdBugFixTask markCommitted(
            String taskId,
            String pullRequestUrl,
            String executionResultJson
    ) {
        return withTaskLock(taskId, () -> {
            RdBugFixTask existing = get(taskId);
            return transitionAndSave(
                    existing,
                    RdTaskStatus.COMMITTED,
                    "",
                    "",
                    "",
                    executionResultJson,
                    pullRequestUrl,
                    ""
            );
        });
    }

    /**
     * 将任务推进到 MERGED。
     *
     * @param taskId 任务 ID
     * @return 新任务快照
     */
    public RdBugFixTask markMerged(String taskId) {
        return withTaskLock(taskId, () -> {
            RdBugFixTask existing = get(taskId);
            return transitionAndSave(existing, RdTaskStatus.MERGED, "", "", "", "", "", "");
        });
    }

    /**
     * 将任务推进到 REJECTED。
     *
     * @param taskId       任务 ID
     * @param errorMessage 错误或 RD 打回原因
     * @return 新任务快照
     */
    public RdBugFixTask markRejected(String taskId, String errorMessage) {
        return markRejected(taskId, errorMessage, "");
    }

    /**
     * 将任务推进到 REJECTED，并保留执行器结构化结果。
     *
     * @param taskId              任务 ID
     * @param errorMessage        错误或 RD 打回原因
     * @param executionResultJson 执行器返回的结构化结果 JSON
     * @return 新任务快照
     */
    public RdBugFixTask markRejected(
            String taskId,
            String errorMessage,
            String executionResultJson
    ) {
        return withTaskLock(taskId, () -> {
            RdBugFixTask existing = get(taskId);
            return transitionAndSave(existing, RdTaskStatus.REJECTED, "", "", "", executionResultJson, "", errorMessage);
        });
    }

    /** Marks a Bug task retryable so a later submit creates fresh stage attempts. */
    public RdBugFixTask markFailedRetryable(String taskId, String errorMessage) {
        return withTaskLock(taskId, () -> {
            RdBugFixTask existing = get(taskId);
            return transitionAndSave(
                    existing,
                    RdTaskStatus.FAILED_RETRYABLE,
                    "",
                    "",
                    "",
                    existing.executionResultJson(),
                    existing.pullRequestUrl(),
                    errorMessage
            );
        });
    }

    /**
     * Stops a Bug task before execution when its retrieval run identifies a concrete material gap.
     * The retrieval lifecycle stays separately auditable; this only projects its outcome to the task.
     */
    public RdBugFixTask markFailedNeedsHuman(String taskId, String errorMessage) {
        return withTaskLock(taskId, () -> {
            RdBugFixTask existing = get(taskId);
            return transitionAndSave(
                    existing,
                    RdTaskStatus.FAILED_NEEDS_HUMAN,
                    "",
                    "",
                    "",
                    existing.executionResultJson(),
                    existing.pullRequestUrl(),
                    errorMessage
            );
        });
    }

    /**
     * 兼容旧 RAG 流：注册任务进入 SEARCHING。
     *
     * @param taskId         任务 ID
     * @param conversationId 旧会话 ID，当前任务域不再使用
     * @return 新任务快照
     */
    public RdBugFixTask registerRunning(String taskId, String conversationId) {
        return withTaskLock(taskId, () -> {
            RdBugFixTask existing = taskStore.findBugFixTask(requireTaskId(taskId))
                    .orElseGet(() -> save(newTaskWithId(taskId)));
            if (existing.status() == RdTaskStatus.REJECTED || existing.status() == RdTaskStatus.MERGED) {
                return existing;
            }
            return transitionAndSave(existing, RdTaskStatus.SEARCHING, "", "", "", "", "", "");
        });
    }

    /**
     * 兼容旧 RAG 流：将任务标记为 COMMITTED。
     *
     * @param taskId    任务 ID
     * @param messageId 旧消息 ID
     * @param title     标题
     * @return 新任务快照
     */
    public RdBugFixTask complete(String taskId, String messageId, String title) {
        return withTaskLock(taskId, () -> {
            RdBugFixTask existing = get(taskId);
            if (existing.status() == RdTaskStatus.REJECTED || existing.status() == RdTaskStatus.MERGED) {
                return existing;
            }
            RdBugFixTask executing = existing.status() == RdTaskStatus.SEARCHING
                    ? transitionAndSave(existing, RdTaskStatus.EXECUTING, "", "", "", "", "", "")
                    : existing;
            return transitionAndSave(executing, RdTaskStatus.COMMITTED, messageId, title, "", "", "", "");
        });
    }

    /** 兼容旧 Bug 修复 stop 调用方。 */
    public RdBugFixTask cancel(String taskId) {
        return withTaskLock(taskId, () -> {
            String safeTaskId = requireTaskId(taskId);
            RdBugFixTask existing = taskStore.findBugFixTask(safeTaskId)
                    .orElseGet(() -> save(newTaskWithId(safeTaskId)));
            return transitionAndSave(existing, RdTaskStatus.REJECTED, "", "", "", "", "", "任务已停止");
        });
    }

    /** 将任意可取消任务推进到 CANCELLED；重复取消幂等。使用 store CAS 拒绝过期写者。 */
    public RdTask cancelTask(String taskId, String reason) {
        return withTaskLock(taskId, () -> {
            RdTask existing = getTask(taskId);
            if (existing.status() == RdTaskStatus.CANCELLED) {
                return existing;
            }
            return casTransition(existing, RdTaskStatus.CANCELLED, reason, null, null);
        });
    }

    /**
     * Fenced status advance: validates transition graph, CAS-writes store, records timeline event.
     * When CAS loses to a peer that already reached {@code targetStatus}, returns the peer snapshot.
     */
    private RdTask casTransition(
            RdTask existing,
            RdTaskStatus targetStatus,
            String reason,
            String executionResultJson,
            String pullRequestUrl
    ) {
        return casTransition(existing, targetStatus, reason, executionResultJson, pullRequestUrl, null);
    }

    /**
     * Fenced status advance: validates transition graph, CAS-writes store, records timeline event.
     * When CAS loses to a peer that already reached {@code targetStatus}, returns the peer snapshot.
     */
    private RdTask casTransition(
            RdTask existing,
            RdTaskStatus targetStatus,
            String reason,
            String executionResultJson,
            String pullRequestUrl,
            String promptSnapshot
    ) {
        String safeReason = reason == null ? "" : reason.strip();
        RdTaskType taskType = RdTaskType.parse(existing.taskType(), RdTaskType.REQUIREMENT);
        RdTaskTransitionPolicy.ensureTransition(taskType, existing.status(), targetStatus);
        long expectedVersion = existing.version();
        long expectedFencingToken = existing.fencingToken();
        long now = System.currentTimeMillis();
        RdTask next = nextSnapshot(
                existing, targetStatus, safeReason, executionResultJson, pullRequestUrl, promptSnapshot, now);
        try {
            return saveTaskWithEventCas(
                    next, targetStatus.name(), next.title(), safeReason, RdTaskEventTrigger.SYSTEM,
                    expectedVersion, expectedFencingToken, existing.status()
            );
        } catch (IllegalStateException stale) {
            RdTask latest = getTask(existing.taskId());
            if (latest.status() == targetStatus) {
                return latest;
            }
            throw stale;
        }
    }

    /** 将失败任务推进到任务级 RECOVERING；阶段重试仍创建新 attempt。 */
    public RdTask markRecovering(String taskId, String reason) {
        return withTaskLock(taskId, () -> {
            RdTask existing = getTask(taskId);
            if (existing.status() == RdTaskStatus.RECOVERING) {
                return existing;
            }
            return transitionTask(existing, RdTaskStatus.RECOVERING, reason);
        });
    }

    /** 将派发超过重试上限的任务推进到 DEAD_LETTERED；重复调用幂等。使用 store CAS。 */
    public RdTask markDeadLettered(String taskId, String reason) {
        return withTaskLock(taskId, () -> {
            RdTask existing = getTask(taskId);
            if (existing.status() == RdTaskStatus.DEAD_LETTERED) {
                return existing;
            }
            return casTransition(existing, RdTaskStatus.DEAD_LETTERED, reason, null, null);
        });
    }

    /**
     * 兼容限流拒绝分支：将任务标记为 REJECTED。
     *
     * @param taskId         任务 ID
     * @param conversationId 旧会话 ID，当前任务域不再使用
     * @param messageId      旧消息 ID
     * @param errorMessage   拒绝原因
     * @return 新任务快照
     */
    public RdBugFixTask reject(
            String taskId,
            String conversationId,
            String messageId,
            String errorMessage
    ) {
        return withTaskLock(taskId, () -> {
            String safeTaskId = requireTaskId(taskId);
            RdBugFixTask existing = taskStore.findBugFixTask(safeTaskId)
                    .orElseGet(() -> save(newTaskWithId(safeTaskId)));
            return transitionAndSave(existing, RdTaskStatus.REJECTED, messageId, "", "", "", "", errorMessage);
        });
    }

    /**
     * 按 ID 查询 Bug 修复任务。
     *
     * @param taskId 任务 ID
     * @return 任务快照
     */
    public RdBugFixTask get(String taskId) {
        String safeTaskId = requireTaskId(taskId);
        return taskStore.findBugFixTask(safeTaskId)
                .orElseThrow(() -> new NoSuchElementException("rd task not found: " + safeTaskId));
    }

    /**
     * 按 ID 查询任意 RD 任务。
     *
     * @param taskId 任务 ID
     * @return 任务快照
     */
    public RdTask getTask(String taskId) {
        String safeTaskId = requireTaskId(taskId);
        return taskStore.findTask(safeTaskId)
                .orElseThrow(() -> new NoSuchElementException("rd task not found: " + safeTaskId));
    }

    /**
     * Admin workbench identity/status without prompt or execution-result blobs.
     */
    public RdTask getAdminShell(String taskId) {
        String safeTaskId = requireTaskId(taskId);
        return taskStore.findAdminShell(safeTaskId)
                .orElseThrow(() -> new NoSuchElementException("rd task not found: " + safeTaskId));
    }

    /**
     * 按 ID 查询需求交付任务。
     *
     * @param taskId 任务 ID
     * @return 需求任务快照
     */
    public RdRequirementTask getRequirementTask(String taskId) {
        String safeTaskId = requireTaskId(taskId);
        return taskStore.findRequirementTask(safeTaskId)
                .orElseThrow(() -> new NoSuchElementException("rd requirement task not found: " + safeTaskId));
    }

    /**
     * 查询所有 Bug 修复任务。
     *
     * @return 任务快照列表
     */
    public List<RdBugFixTask> listBugFixTasks() {
        return taskStore.listBugFixTasks();
    }

    /**
     * 查询所有 RD 任务。
     *
     * @return 任务快照列表
     */
    public List<RdTask> listTasks() {
        return taskStore.listTasks();
    }

    /**
     * 按管理台查询条件分页查询任务（过滤掉 DELETED）。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    public RdTaskPage queryBugFixTasks(RdTaskQuery query) {
        RdTaskQuery oldQuery = query == null ? new RdTaskQuery(null, null, null, null, 1, 20) : query;
        RdTaskQuery safeQuery = new RdTaskQuery(
                RdTaskType.BUG_FIX.name(),
                oldQuery.status(),
                oldQuery.priority(),
                oldQuery.projectId(),
                oldQuery.ticketId(),
                oldQuery.keyword(),
                oldQuery.page(),
                oldQuery.pageSize()
        );
        return queryTasks(safeQuery);
    }

    /**
     * 按管理台查询条件分页查询所有任务类型（过滤掉 DELETED）。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    public RdTaskPage queryTasks(RdTaskQuery query) {
        RdTaskQuery safeQuery = query == null ? new RdTaskQuery(null, null, null, null, 1, 20) : query;
        List<RdTask> filtered = taskStore.listTasks().stream()
                    .filter(task -> task.status() != RdTaskStatus.DELETED)
                    .filter(task -> safeQuery.matchesTaskType(task.taskType()))
                    .filter(task -> safeQuery.matchesStatus(task.status()))
                    .filter(task -> safeQuery.matchesPriority(task.priority()))
                    .filter(task -> safeQuery.matchesProjectId(projectId(task)))
                    .filter(task -> matchesKeywords(safeQuery, task))
                    .sorted(Comparator.comparingLong(RdTask::updateTimeEpochMillis).reversed()
                            .thenComparing(RdTask::taskId))
                    .toList();
            int total = filtered.size();
            int pageSize = safeQuery.pageSize();
            int pages = total == 0 ? 0 : (int) Math.ceil((double) total / pageSize);
            int fromIndex = Math.min((safeQuery.page() - 1) * pageSize, total);
            int toIndex = Math.min(fromIndex + pageSize, total);
        return new RdTaskPage(filtered.subList(fromIndex, toIndex), total, safeQuery.page(), pageSize, pages);
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

    /**
     * 将需求任务推进到 MATERIAL_COLLECTING。
     *
     * @param taskId  任务 ID
     * @param summary 摘要
     * @return 新任务快照
     */
    public RdRequirementTask markRequirementMaterialCollecting(String taskId, String summary) {
        return withTaskLock(taskId, () -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            return transitionAndSave(existing, RdTaskStatus.MATERIAL_COLLECTING, "", "", "", "");
        });
    }

    /**
     * Applies one requirement transition using the caller's immutable snapshot metadata.
     *
     * <p>This is the stage-command write boundary: it never reloads the task before CAS, so a
     * worker whose lease expired cannot adopt a newer version/fencing token.
     *
     * @param snapshot task snapshot captured before the stage was leased
     * @param targetStatus target status
     * @param promptSnapshot optional prompt snapshot
     * @param executionResultJson optional result snapshot
     * @param pullRequestUrl optional PR URL
     * @param errorMessage optional failure reason
     * @return fenced persisted snapshot
     */
    public RdRequirementTask transitionRequirementFenced(
            RdRequirementTask snapshot,
            RdTaskStatus targetStatus,
            String promptSnapshot,
            String executionResultJson,
            String pullRequestUrl,
            String errorMessage
    ) {
        if (snapshot == null || targetStatus == null) {
            throw new IllegalArgumentException("snapshot and targetStatus must not be null");
        }
        if (snapshot.fencingToken() <= 0L) {
            throw new IllegalArgumentException("fenced requirement transition requires a positive fencing token");
        }
        return withTaskLock(snapshot.taskId(), () -> {
            RdTaskTransitionPolicy.ensureTransition(
                    RdTaskType.REQUIREMENT, snapshot.status(), targetStatus);
            RdRequirementTask next = snapshot.withState(
                    targetStatus,
                    promptSnapshot,
                    executionResultJson,
                    pullRequestUrl,
                    errorMessage,
                    System.currentTimeMillis());
            return (RdRequirementTask) saveTaskWithEventCas(
                    next, targetStatus.name(), next.title(), next.errorMessage(),
                    RdTaskEventTrigger.SYSTEM,
                    snapshot.version(), snapshot.fencingToken(), snapshot.status());
        });
    }

    /**
     * 将需求任务推进到 MATERIAL_READY。
     *
     * @param taskId  任务 ID
     * @param summary 摘要
     * @return 新任务快照
     */
    public RdRequirementTask markRequirementMaterialReady(String taskId, String summary) {
        return withTaskLock(taskId, () -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            return transitionAndSave(existing, RdTaskStatus.MATERIAL_READY, "", "", "", "");
        });
    }

    /**
     * 将需求任务推进到 CONTEXT_BUILDING。
     *
     * @param taskId  任务 ID
     * @param summary 摘要
     * @return 新任务快照
     */
    public RdRequirementTask markRequirementContextBuilding(String taskId, String summary) {
        return withTaskLock(taskId, () -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            return transitionAndSave(existing, RdTaskStatus.CONTEXT_BUILDING, "", "", "", "");
        });
    }

    /**
     * 将需求任务推进到 CONTEXT_READY。
     *
     * @param taskId        任务 ID
     * @param contextJson   上下文包 JSON，暂存到执行结果快照供管理台审计
     * @return 新任务快照
     */
    public RdRequirementTask markRequirementContextReady(String taskId, String contextJson) {
        return withTaskLock(taskId, () -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            return transitionAndSave(existing, RdTaskStatus.CONTEXT_READY, "", contextJson, "", "");
        });
    }

    /**
     * 将需求任务推进到 PLAN_GENERATING。
     *
     * @param taskId  任务 ID
     * @param summary 摘要
     * @return 新任务快照
     */
    public RdRequirementTask markRequirementPlanGenerating(String taskId, String summary) {
        return withTaskLock(taskId, () -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            return transitionAndSave(existing, RdTaskStatus.PLAN_GENERATING, "", "", "", "");
        });
    }

    /**
     * 将需求任务推进到 PLAN_GENERATED。
     *
     * @param taskId   任务 ID
     * @param planJson 计划 JSON，暂存到执行结果快照供管理台审计
     * @return 新任务快照
     */
    public RdRequirementTask markRequirementPlanGenerated(String taskId, String planJson) {
        return withTaskLock(taskId, () -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            return transitionAndSave(existing, RdTaskStatus.PLAN_GENERATED, "", planJson, "", "");
        });
    }

    /**
     * 将需求任务推进到 WAITING_POLICY。
     *
     * @param taskId     任务 ID
     * @param policyJson 策略 JSON，暂存到执行结果快照供管理台审计
     * @return 新任务快照
     */
    public RdRequirementTask markRequirementWaitingPolicy(String taskId, String policyJson) {
        return withTaskLock(taskId, () -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            return transitionAndSave(existing, RdTaskStatus.WAITING_POLICY, "", policyJson, "", "");
        });
    }

    /**
     * 将需求任务推进到 WAITING_APPROVAL。
     *
     * @param taskId     任务 ID
     * @param policyJson 策略 JSON
     * @return 新任务快照
     */
    public RdRequirementTask markRequirementWaitingApproval(String taskId, String policyJson) {
        return withTaskLock(taskId, () -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            return transitionAndSave(existing, RdTaskStatus.WAITING_APPROVAL, "", policyJson, "", "");
        });
    }

    /**
     * 将需求任务推进到 WAITING_USER_INPUT，等待操作员补充材料。
     *
     * @param taskId              任务 ID
     * @param errorMessage        需要补充的原因
     * @param executionResultJson 评审或 Manager ASK 快照
     * @return 新任务快照
     */
    public RdRequirementTask markRequirementWaitingUserInput(
            String taskId,
            String errorMessage,
            String executionResultJson
    ) {
        return withTaskLock(taskId, () -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            if (existing.status() == RdTaskStatus.WAITING_USER_INPUT) {
                return existing;
            }
            return transitionAndSave(
                    existing,
                    RdTaskStatus.WAITING_USER_INPUT,
                    "",
                    executionResultJson,
                    "",
                    errorMessage
            );
        });
    }

    /**
     * 将需求任务推进到 EXECUTING。
     *
     * @param taskId         任务 ID
     * @param promptSnapshot Prompt 快照
     * @return 新任务快照
     */
    public RdRequirementTask markRequirementExecuting(String taskId, String promptSnapshot) {
        return withTaskLock(taskId, () -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            if (existing.status() == RdTaskStatus.EXECUTING) {
                return existing;
            }
            return (RdRequirementTask) casTransition(
                    existing,
                    RdTaskStatus.EXECUTING,
                    "",
                    null,
                    null,
                    promptSnapshot == null ? "" : promptSnapshot
            );
        });
    }

    /**
     * 将需求任务推进到 VALIDATING。
     *
     * @param taskId         任务 ID
     * @param validationJson 验证输入或结果 JSON，暂存到执行结果快照供管理台审计
     * @return 新任务快照
     */
    public RdRequirementTask markRequirementValidating(String taskId, String validationJson) {
        return withTaskLock(taskId, () -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            if (existing.status() == RdTaskStatus.VALIDATING) {
                return existing;
            }
            return (RdRequirementTask) casTransition(
                    existing,
                    RdTaskStatus.VALIDATING,
                    "",
                    validationJson,
                    null
            );
        });
    }

    /**
     * 将需求任务推进到 PR_CREATING。
     *
     * @param taskId             任务 ID
     * @param reviewedResultJson 复核通过后的交付结果 JSON
     * @return 新任务快照
     */
    public RdRequirementTask markRequirementPrCreating(String taskId, String reviewedResultJson) {
        return withTaskLock(taskId, () -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            if (existing.status() == RdTaskStatus.PR_CREATING) {
                return existing;
            }
            return (RdRequirementTask) casTransition(
                    existing,
                    RdTaskStatus.PR_CREATING,
                    "",
                    reviewedResultJson,
                    null
            );
        });
    }

    /**
     * 将需求任务推进到 COMMITTED。
     *
     * @param taskId              任务 ID
     * @param pullRequestUrl      PR 链接
     * @param executionResultJson 执行结果 JSON
     * @return 新任务快照
     */
    public RdRequirementTask markRequirementCommitted(
            String taskId,
            String pullRequestUrl,
            String executionResultJson
    ) {
        return withTaskLock(taskId, () -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            if (existing.status() == RdTaskStatus.COMMITTED) {
                return existing;
            }
            return (RdRequirementTask) casTransition(
                    existing,
                    RdTaskStatus.COMMITTED,
                    "",
                    executionResultJson,
                    pullRequestUrl
            );
        });
    }

    /**
     * Commits a requirement task using only the concurrency metadata captured by the caller.
     *
     * <p>This publication-finalization path deliberately does not reload the task before issuing
     * its compare-and-set. A worker that lost its lease must fail against the original version,
     * status and fencing token instead of borrowing a newer snapshot.
     *
     * @param taskId task identifier
     * @param expectedVersion version captured before publication
     * @param expectedStatus status captured before publication
     * @param expectedFencingToken fencing token captured before publication
     * @param pullRequestUrl confirmed pull request URL
     * @param executionResultJson durable delivery result
     * @return committed task snapshot after the fenced transition
     * @throws IllegalStateException when the task has advanced since the caller's snapshot
     */
    public RdRequirementTask markRequirementCommittedFenced(
            String taskId,
            long expectedVersion,
            RdTaskStatus expectedStatus,
            long expectedFencingToken,
            String pullRequestUrl,
            String executionResultJson
    ) {
        String safeTaskId = requireTaskId(taskId);
        if (expectedVersion < 0L || expectedFencingToken <= 0L) {
            throw new IllegalArgumentException("expectedVersion must not be negative and expectedFencingToken must be positive");
        }
        if (expectedStatus == null) {
            throw new IllegalArgumentException("expectedStatus must not be null");
        }
        return withTaskLock(safeTaskId, () -> {
            RdTaskTransitionPolicy.ensureTransition(
                    RdTaskType.REQUIREMENT, expectedStatus, RdTaskStatus.COMMITTED);
            long now = System.currentTimeMillis();
            // The SQL CAS changes only status/result/PR fields. Constructing this transition
            // payload avoids a task-store reload that could let a stale worker adopt new fencing.
            RdRequirementTask next = RdRequirementTask.created(
                    safeTaskId,
                    new CreateRequirementTaskCommand("", "P2", "", "", "", "", "", List.of(), false),
                    now
            ).withState(
                    RdTaskStatus.COMMITTED,
                    "",
                    executionResultJson,
                    pullRequestUrl,
                    "",
                    now
            ).withConcurrency(expectedVersion, expectedFencingToken);
            return (RdRequirementTask) saveTaskWithEventCas(
                    next,
                    RdTaskStatus.COMMITTED.name(),
                    "",
                    "",
                    RdTaskEventTrigger.SYSTEM,
                    expectedVersion,
                    expectedFencingToken,
                    expectedStatus
            );
        });
    }

    /**
     * 将需求任务推进到 REPORTING。
     *
     * @param taskId           任务 ID
     * @param deliveryReportJson 交付报告 JSON
     * @return 新任务快照
     */
    public RdRequirementTask markRequirementReporting(String taskId, String deliveryReportJson) {
        return withTaskLock(taskId, () -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            if (existing.status() == RdTaskStatus.REPORTING) {
                return existing;
            }
            return (RdRequirementTask) casTransition(
                    existing,
                    RdTaskStatus.REPORTING,
                    "",
                    deliveryReportJson,
                    null
            );
        });
    }

    /**
     * 将需求任务推进到 COMPLETED。
     *
     * @param taskId              任务 ID
     * @param pullRequestUrl      PR 链接
     * @param executionResultJson 最终交付结果 JSON
     * @return 新任务快照
     */
    public RdRequirementTask markRequirementCompleted(
            String taskId,
            String pullRequestUrl,
            String executionResultJson
    ) {
        return withTaskLock(taskId, () -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            if (existing.status() == RdTaskStatus.COMPLETED) {
                return existing;
            }
            return (RdRequirementTask) casTransition(
                    existing,
                    RdTaskStatus.COMPLETED,
                    "",
                    executionResultJson,
                    pullRequestUrl
            );
        });
    }

    /**
     * 将需求任务推进到 MERGED。
     *
     * @param taskId 任务 ID
     * @return 新需求任务快照
     */
    public RdRequirementTask markRequirementMerged(String taskId) {
        return withTaskLock(taskId, () -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            return transitionAndSave(existing, RdTaskStatus.MERGED, "", "", "", "");
        });
    }

    /**
     * 将需求任务推进到 REJECTED。
     *
     * @param taskId              任务 ID
     * @param errorMessage        错误信息
     * @param executionResultJson 执行结果 JSON
     * @return 新任务快照
     */
    public RdRequirementTask markRequirementRejected(
            String taskId,
            String errorMessage,
            String executionResultJson
    ) {
        return withTaskLock(taskId, () -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            if (existing.status() == RdTaskStatus.REJECTED) {
                return existing;
            }
            return (RdRequirementTask) casTransition(
                    existing,
                    RdTaskStatus.REJECTED,
                    errorMessage,
                    executionResultJson,
                    null
            );
        });
    }

    /**
     * 将需求任务推进到 FAILED_NEEDS_HUMAN，并保留结构化原因。
     *
     * @param taskId              任务 ID
     * @param errorMessage        需要人工处理的原因
     * @param executionResultJson 结构化结果 JSON
     * @return 新任务快照
     */
    public RdRequirementTask markRequirementFailedNeedsHuman(
            String taskId,
            String errorMessage,
            String executionResultJson
    ) {
        return withTaskLock(taskId, () -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            if (existing.status() == RdTaskStatus.FAILED_NEEDS_HUMAN) {
                return existing;
            }
            return (RdRequirementTask) casTransition(
                    existing,
                    RdTaskStatus.FAILED_NEEDS_HUMAN,
                    errorMessage,
                    executionResultJson,
                    null
            );
        });
    }

    /**
     * 将需求任务推进到 FAILED_RETRYABLE，并保留结构化失败结果。
     *
     * @param taskId              任务 ID
     * @param errorMessage        可重试失败原因
     * @param executionResultJson 结构化结果 JSON
     * @return 新任务快照
     */
    public RdRequirementTask markRequirementFailedRetryable(
            String taskId,
            String errorMessage,
            String executionResultJson
    ) {
        return withTaskLock(taskId, () -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            if (existing.status() == RdTaskStatus.FAILED_RETRYABLE) {
                return existing;
            }
            return (RdRequirementTask) casTransition(
                    existing,
                    RdTaskStatus.FAILED_RETRYABLE,
                    errorMessage,
                    executionResultJson,
                    null
            );
        });
    }

    /**
     * 修改任务的标题 / 优先级 / 工单标题（不动状态机）。
     *
     * @param taskId      任务 ID
     * @param title       展示标题（空则保留原值）
     * @param priority    优先级（空则保留原值）
     * @param ticketTitle 工单标题（空则保留原值）
     * @return 更新后任务快照
     */
    public RdBugFixTask updateTask(String taskId, String title, String priority, String ticketTitle) {
        return withTaskLock(taskId, () -> {
            RdTask existing = getTask(taskId);
            if (!(existing instanceof RdBugFixTask)) {
                throw new IllegalStateException("rd task is not a bug-fix task: " + taskId);
            }
            return (RdBugFixTask) updateTaskMetadataLocked(existing, title, priority, ticketTitle);
        });
    }

    /**
     * 修改任意 RD 任务的通用管理元数据，不改变状态机或需求交付字段。
     *
     * <p>BugFix 还会更新工单标题；需求任务没有工单标题字段，因此该入参被有意忽略。
     * 两种任务都通过同一 action CAS 边界写快照，但不会追加状态时间线事件，避免旧阶段 worker 覆盖编辑结果。
     *
     * @param taskId      任务 ID
     * @param title       展示标题（空则保留原值）
     * @param priority    优先级（空则保留原值）
     * @param ticketTitle BugFix 工单标题（需求任务忽略）
     * @return 更新后任务快照
     */
    public RdTask updateTaskMetadata(String taskId, String title, String priority, String ticketTitle) {
        return withTaskLock(taskId, () -> {
            RdTask existing = getTask(taskId);
            return updateTaskMetadataLocked(existing, title, priority, ticketTitle);
        });
    }

    private RdTask updateTaskMetadataLocked(
            RdTask existing,
            String title,
            String priority,
            String ticketTitle
    ) {
        RdTask updated;
        if (existing instanceof RdBugFixTask bugFixTask) {
            updated = bugFixTask.withEditedFields(title, priority, ticketTitle, System.currentTimeMillis());
        } else if (existing instanceof RdRequirementTask requirementTask) {
            updated = requirementTask.withEditedFields(title, priority, System.currentTimeMillis());
        } else {
            throw new IllegalArgumentException("unsupported rd task type: " + existing.getClass().getName());
        }
        // Metadata edits are not state transitions: persist the snapshot under CAS without
        // appending a duplicate state-entry event to the task timeline.
        return saveTaskActionWithEventCas(
                updated,
                null,
                existing.version(),
                existing.fencingToken(),
                existing.status()
        );
    }

    /**
     * 管理台暂停任务（仅标记，不改变状态机合法性）。
     *
     * @param taskId  任务 ID
     * @param message 暂停说明
     * @return 更新后任务快照
     */
    public RdBugFixTask pause(String taskId, String message) {
        return (RdBugFixTask) pauseTask(taskId, message);
    }

    /**
     * 管理台暂停任意任务（仅标记，不改变状态机合法性）。
     *
     * @param taskId  任务 ID
     * @param message 暂停说明
     * @return 更新后任务快照
     */
    public RdTask pauseTask(String taskId, String message) {
        return withTaskLock(taskId, () -> {
            RdTask existing = getTask(taskId);
            RdTask paused = switchPaused(existing, true, System.currentTimeMillis());
            return saveTaskActionWithEventCas(
                    paused,
                    buildEvent(paused, RdTaskStatusEvent.ACTION_PAUSED, paused.title(), message, RdTaskEventTrigger.API),
                    existing.version(),
                    existing.fencingToken(),
                    existing.status()
            );
        });
    }

    /**
     * 管理台恢复任务。
     *
     * @param taskId  任务 ID
     * @param message 恢复说明
     * @return 更新后任务快照
     */
    public RdBugFixTask resume(String taskId, String message) {
        return (RdBugFixTask) resumeTask(taskId, message);
    }

    /**
     * 管理台恢复任意任务。
     *
     * @param taskId  任务 ID
     * @param message 恢复说明
     * @return 更新后任务快照
     */
    public RdTask resumeTask(String taskId, String message) {
        return withTaskLock(taskId, () -> {
            RdTask existing = getTask(taskId);
            RdTask resumed = switchPaused(existing, false, System.currentTimeMillis());
            return saveTaskActionWithEventCas(
                    resumed,
                    buildEvent(resumed, RdTaskStatusEvent.ACTION_RESUMED, resumed.title(), message, RdTaskEventTrigger.API),
                    existing.version(),
                    existing.fencingToken(),
                    existing.status()
            );
        });
    }

    /**
     * 管理台审批通过等待人工确认的需求任务。
     *
     * <p>审批不改变主状态（仍为 {@code WAITING_APPROVAL}），但必须与 {@code APPROVED}
     * 审计事件在同一 CAS 边界推进 version/fence，避免并发写者看不到审批。后续由需求交付
     * 引擎通过 {@code WAITING_APPROVAL -> EXECUTING} 的合法状态机边继续执行。
     *
     * @param taskId  任务 ID
     * @param message 审批说明
     * @return 审批后的任务快照
     */
    public RdRequirementTask approveRequirementTask(String taskId, String message) {
        return withTaskLock(taskId, () -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            if (existing.status() != RdTaskStatus.WAITING_APPROVAL) {
                throw new IllegalStateException("requirement task is not waiting approval: " + existing.status());
            }
            String approvalMessage = message == null || message.isBlank() ? "管理台审批通过" : message;
            return (RdRequirementTask) saveTaskActionWithEventCas(
                    existing,
                    buildEvent(existing, RdTaskStatusEvent.ACTION_APPROVED, existing.title(),
                            approvalMessage, RdTaskEventTrigger.API),
                    existing.version(),
                    existing.fencingToken(),
                    existing.status()
            );
        });
    }

    /**
     * 管理台逻辑删除任务（状态置 DELETED）并保留删除审计事件。
     *
     * @param taskId 任务 ID
     * @return 是否删除（任务不存在或已删除返回 false）
     */
    public boolean deleteTask(String taskId) {
        return withTaskLock(taskId, () -> {
            String safeTaskId = requireTaskId(taskId);
            return taskStore.findTask(safeTaskId).map(existing -> {
                if (existing.status() == RdTaskStatus.DELETED) {
                    return false;
                }
                RdTask deleted = deletedTask(existing, System.currentTimeMillis());
                // Keep the DELETED audit event; the CAS boundary prevents a late worker from reviving it.
                saveTaskActionWithEventCas(
                        deleted,
                        buildEvent(deleted, RdTaskStatusEvent.ACTION_DELETED, deleted.title(),
                                "管理台删除任务", RdTaskEventTrigger.API),
                        existing.version(),
                        existing.fencingToken(),
                        existing.status()
                );
                return true;
            }).orElse(false);
        });
    }

    /**
     * 查询任务全链路状态事件时间线（按进入时间升序）。
     *
     * @param taskId 任务 ID
     * @return 状态事件列表（升序）
     */
    public List<RdTaskStatusEvent> timeline(String taskId) {
        requireTaskId(taskId);
        if (eventStore == null) {
            return List.of();
        }
        return eventStore.listByTask(taskId);
    }

    private RdBugFixTask save(RdBugFixTask task) {
        return taskStore.saveBugFixTask(task);
    }

    private RdTask saveTask(RdTask task) {
        if (task instanceof RdBugFixTask bugFixTask) {
            return taskStore.saveBugFixTask(bugFixTask);
        }
        if (task instanceof RdRequirementTask requirementTask) {
            return taskStore.saveRequirementTask(requirementTask);
        }
        throw new IllegalArgumentException("unsupported rd task type: " + task.getClass().getName());
    }

    private RdTask transitionTask(RdTask existing, RdTaskStatus targetStatus, String reason) {
        String safeReason = reason == null ? "" : reason.strip();
        if (existing instanceof RdBugFixTask bugFixTask) {
            return transitionAndSave(bugFixTask, targetStatus, "", "", "", "", "", safeReason);
        }
        if (existing instanceof RdRequirementTask requirementTask) {
            return transitionAndSave(requirementTask, targetStatus, "", "", "", safeReason);
        }
        throw new IllegalArgumentException("unsupported rd task type: " + existing.getClass().getName());
    }

    private RdTask switchPaused(RdTask task, boolean paused, long updateTimeEpochMillis) {
        if (task instanceof RdBugFixTask bugFixTask) {
            return bugFixTask.withPaused(paused, updateTimeEpochMillis);
        }
        if (task instanceof RdRequirementTask requirementTask) {
            return requirementTask.withPaused(paused, updateTimeEpochMillis);
        }
        throw new IllegalArgumentException("unsupported rd task type: " + task.getClass().getName());
    }

    private RdTask deletedTask(RdTask task, long updateTimeEpochMillis) {
        if (task instanceof RdBugFixTask bugFixTask) {
            return bugFixTask.deleted(updateTimeEpochMillis);
        }
        if (task instanceof RdRequirementTask requirementTask) {
            return requirementTask.deleted(updateTimeEpochMillis);
        }
        throw new IllegalArgumentException("unsupported rd task type: " + task.getClass().getName());
    }

    private boolean matchesKeywords(RdTaskQuery query, RdTask task) {
        if (task instanceof RdBugFixTask bugFixTask) {
            return query.matchesKeywords(
                    bugFixTask.ticketId(),
                    bugFixTask.ticketTitle() + " " + bugFixTask.projectName(),
                    bugFixTask.title() + " " + bugFixTask.repositoryUrl()
            );
        }
        if (task instanceof RdRequirementTask requirementTask) {
            String sourceText = String.join(" ",
                    requirementTask.sourceId(),
                    requirementTask.sourceUrl(),
                    requirementTask.projectName(),
                    requirementTask.repositoryUrl(),
                    requirementTask.repoOwner(),
                    requirementTask.repoName()
            );
            return query.matchesKeywords(sourceText, requirementTask.expectedResult(), requirementTask.title());
        }
        return query.matchesKeywords("", "", task.title());
    }

    /**
     * 应用状态机转换：校验合法性、生成新快照、落库，并在状态真实变化时追加一条状态事件。
     */
    private RdBugFixTask transitionAndSave(
            RdBugFixTask existing,
            RdTaskStatus targetStatus,
            String messageId,
            String title,
            String promptSnapshot,
            String executionResultJson,
            String pullRequestUrl,
            String errorMessage
    ) {
        if (existing.status() == targetStatus) {
            return existing;
        }
        RdTaskTransitionPolicy.ensureTransition(
                RdTaskType.parse(existing.taskType(), RdTaskType.BUG_FIX),
                existing.status(),
                targetStatus
        );
        RdBugFixTask next = existing.withState(
                targetStatus,
                messageId,
                title,
                promptSnapshot,
                executionResultJson,
                pullRequestUrl,
                errorMessage,
                System.currentTimeMillis()
        );
        String message = next.errorMessage().isBlank() ? "" : next.errorMessage();
        return (RdBugFixTask) saveTaskWithEventCas(
                next, next.status().name(), next.title(), message, RdTaskEventTrigger.SYSTEM,
                existing.version(), existing.fencingToken(), existing.status());
    }

    private RdRequirementTask transitionAndSave(
            RdRequirementTask existing,
            RdTaskStatus targetStatus,
            String promptSnapshot,
            String executionResultJson,
            String pullRequestUrl,
            String errorMessage
    ) {
        if (existing.status() == targetStatus) {
            return existing;
        }
        RdTaskTransitionPolicy.ensureTransition(
                RdTaskType.parse(existing.taskType(), RdTaskType.REQUIREMENT),
                existing.status(),
                targetStatus
        );
        RdRequirementTask next = existing.withState(
                targetStatus,
                promptSnapshot,
                executionResultJson,
                pullRequestUrl,
                errorMessage,
                System.currentTimeMillis()
        );
        String message = next.errorMessage().isBlank() ? "" : next.errorMessage();
        return (RdRequirementTask) saveTaskWithEventCas(
                next, next.status().name(), next.title(), message, RdTaskEventTrigger.SYSTEM,
                existing.version(), existing.fencingToken(), existing.status());
    }

    private RdTask saveTaskWithEvent(
            RdTask task,
            String status,
            String title,
            String message,
            RdTaskEventTrigger trigger
    ) {
        return statePersistence.saveWithEvent(task, buildEvent(task, status, title, message, trigger));
    }

    private RdTask saveTaskWithEventCas(
            RdTask task,
            String status,
            String title,
            String message,
            RdTaskEventTrigger trigger,
            long expectedVersion,
            long expectedFencingToken,
            RdTaskStatus expectedStatus
    ) {
        return statePersistence.saveWithEventCas(
                task,
                buildEvent(task, status, title, message, trigger),
                expectedVersion,
                expectedFencingToken,
                expectedStatus
        );
    }

    private RdTask saveTaskActionWithEventCas(
            RdTask task,
            RdTaskStatusEvent event,
            long expectedVersion,
            long expectedFencingToken,
            RdTaskStatus expectedStatus
    ) {
        return statePersistence.saveActionWithEventCas(
                task,
                event,
                expectedVersion,
                expectedFencingToken,
                expectedStatus
        );
    }

    private RdTask nextSnapshot(
            RdTask existing,
            RdTaskStatus targetStatus,
            String reason,
            String executionResultJson,
            String pullRequestUrl,
            String promptSnapshot,
            long now
    ) {
        if (existing instanceof RdBugFixTask bugFixTask) {
            return bugFixTask.withState(
                    targetStatus, "", "", promptSnapshot, executionResultJson,
                    pullRequestUrl, reason, now);
        }
        if (existing instanceof RdRequirementTask requirementTask) {
            return requirementTask.withState(
                    targetStatus, promptSnapshot, executionResultJson, pullRequestUrl, reason, now);
        }
        throw new IllegalArgumentException("unsupported rd task type: " + existing.getClass().getName());
    }

    /**
     * 记录一条状态事件。duration = 当前时刻 − 上一事件进入时刻，首条为 0。
     * eventStore 为空（向后兼容）时直接返回。
     */
    private void recordEvent(
            RdTask task,
            String status,
            String title,
            String message,
            RdTaskEventTrigger trigger
    ) {
        RdTaskStatusEvent event = buildEvent(task, status, title, message, trigger);
        if (event != null) {
            eventStore.save(event);
        }
    }

    private RdTaskStatusEvent buildEvent(
            RdTask task,
            String status,
            String title,
            String message,
            RdTaskEventTrigger trigger
    ) {
        if (eventStore == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        List<RdTaskStatusEvent> existing = eventStore.listByTask(task.taskId());
        long previousEnteredAt = existing.isEmpty() ? now : existing.get(existing.size() - 1).enteredAtEpochMillis();
        long duration = Math.max(0L, now - previousEnteredAt);
        return new RdTaskStatusEvent(
                idGenerator.nextIdString(),
                task.taskId(),
                status,
                title,
                message == null ? "" : message,
                now,
                duration,
                trigger.name()
        );
    }

    private RdBugFixTask newTaskWithId(String taskId) {
        long now = System.currentTimeMillis();
        return RdBugFixTask.created(taskId, "", "", "P2", now);
    }

    private TicketSnapshot normalizeTicket(TicketSnapshot ticket) {
        if (ticket != null) {
            return ticket;
        }
        return new TicketSnapshot(idGenerator.nextIdString(), "", "", List.of(), Instant.now());
    }

    private String requireTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        return taskId.strip();
    }
}
