package com.wish.rd.rag.runtime;

import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;

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

    private static final String LOCK_NAME = "rd-bot:lock:rag-stream-task-registry";

    private final RdTaskStore taskStore;
    private final RdTaskStatusEventStore eventStore;
    private final SnowflakeIdGenerator idGenerator;
    private final DistributedLockExecutor lockExecutor;

    @Autowired
    public RagStreamTaskRegistry(
            RdTaskStore taskStore,
            RdTaskStatusEventStore eventStore,
            SnowflakeIdGenerator idGenerator,
            ObjectProvider<DistributedLockExecutor> lockExecutorProvider
    ) {
        this(
                taskStore,
                eventStore,
                idGenerator,
                lockExecutorProvider == null
                        ? DistributedLockExecutor.local()
                        : lockExecutorProvider.getIfAvailable(DistributedLockExecutor::local)
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
        this.taskStore = taskStore == null ? new InMemoryRdTaskStore() : taskStore;
        this.eventStore = eventStore;
        this.idGenerator = idGenerator == null ? SnowflakeIdGenerator.defaultGenerator() : idGenerator;
        this.lockExecutor = lockExecutor == null ? DistributedLockExecutor.local() : lockExecutor;
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

    private <T> T withLock(Supplier<T> action) {
        return lockExecutor.execute(LOCK_NAME, action);
    }

    /**
     * 创建 Bug 修复任务，初始状态为 CREATED。
     *
     * @param ticket   工单快照
     * @param priority 优先级
     * @return 新任务快照
     */
    public RdBugFixTask createBugFixTask(TicketSnapshot ticket, String priority) {
        return withLock(() -> {
            TicketSnapshot safeTicket = normalizeTicket(ticket);
            RdBugFixTask task = RdBugFixTask.created(
                    idGenerator.nextIdString(),
                    safeTicket.ticketId(),
                    safeTicket.title(),
                    priority,
                    System.currentTimeMillis()
            );
            RdBugFixTask saved = taskStore.saveBugFixTask(task);
            recordEvent(saved, RdTaskStatus.CREATED.name(), saved.title(), "任务创建", RdTaskEventTrigger.SYSTEM);
            return saved;
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
        return withLock(() -> {
            TicketSnapshot safeTicket = normalizeTicket(ticket);
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
        return withLock(() -> {
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
            RdBugFixTask saved = taskStore.saveBugFixTask(task);
            recordEvent(saved, RdTaskStatus.CREATED.name(), saved.title(), "管理台创建任务", RdTaskEventTrigger.API);
            return saved;
        });
    }

    /**
     * 创建需求交付任务，初始状态为 CREATED。
     *
     * @param command 创建命令
     * @return 新需求任务快照
     */
    public RdRequirementTask createRequirementTask(CreateRequirementTaskCommand command) {
        return withLock(() -> {
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
            RdRequirementTask saved = taskStore.saveRequirementTask(task);
            recordEvent(saved, RdTaskStatus.CREATED.name(), saved.title(), "管理台创建需求任务", RdTaskEventTrigger.API);
            return saved;
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
        return withLock(() -> {
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
        return withLock(() -> {
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
        return withLock(() -> {
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
        return withLock(() -> {
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
        return withLock(() -> markRejected(taskId, errorMessage, ""));
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
        return withLock(() -> {
            RdBugFixTask existing = get(taskId);
            return transitionAndSave(existing, RdTaskStatus.REJECTED, "", "", "", executionResultJson, "", errorMessage);
        });
    }

    /** Marks a Bug task retryable so a later submit creates fresh stage attempts. */
    public RdBugFixTask markFailedRetryable(String taskId, String errorMessage) {
        return withLock(() -> {
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
     * 兼容旧 RAG 流：注册任务进入 SEARCHING。
     *
     * @param taskId         任务 ID
     * @param conversationId 旧会话 ID，当前任务域不再使用
     * @return 新任务快照
     */
    public RdBugFixTask registerRunning(String taskId, String conversationId) {
        return withLock(() -> {
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
        return withLock(() -> {
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

    /**
     * 兼容 stop 接口：将任务标记为 REJECTED。
     *
     * @param taskId 任务 ID
     * @return 新任务快照
     */
    public RdBugFixTask cancel(String taskId) {
        return withLock(() -> {
            String safeTaskId = requireTaskId(taskId);
            RdBugFixTask existing = taskStore.findBugFixTask(safeTaskId)
                    .orElseGet(() -> save(newTaskWithId(safeTaskId)));
            return transitionAndSave(existing, RdTaskStatus.REJECTED, "", "", "", "", "", "任务已停止");
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
        return withLock(() -> {
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
        return withLock(() -> {
            String safeTaskId = requireTaskId(taskId);
            return taskStore.findBugFixTask(safeTaskId)
                    .orElseThrow(() -> new NoSuchElementException("rd task not found: " + safeTaskId));
        });
    }

    /**
     * 按 ID 查询任意 RD 任务。
     *
     * @param taskId 任务 ID
     * @return 任务快照
     */
    public RdTask getTask(String taskId) {
        return withLock(() -> {
            String safeTaskId = requireTaskId(taskId);
            return taskStore.findTask(safeTaskId)
                    .orElseThrow(() -> new NoSuchElementException("rd task not found: " + safeTaskId));
        });
    }

    /**
     * 按 ID 查询需求交付任务。
     *
     * @param taskId 任务 ID
     * @return 需求任务快照
     */
    public RdRequirementTask getRequirementTask(String taskId) {
        return withLock(() -> {
            String safeTaskId = requireTaskId(taskId);
            return taskStore.findRequirementTask(safeTaskId)
                    .orElseThrow(() -> new NoSuchElementException("rd requirement task not found: " + safeTaskId));
        });
    }

    /**
     * 查询所有 Bug 修复任务。
     *
     * @return 任务快照列表
     */
    public List<RdBugFixTask> listBugFixTasks() {
        return withLock(taskStore::listBugFixTasks);
    }

    /**
     * 查询所有 RD 任务。
     *
     * @return 任务快照列表
     */
    public List<RdTask> listTasks() {
        return withLock(taskStore::listTasks);
    }

    /**
     * 按管理台查询条件分页查询任务（过滤掉 DELETED）。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    public RdTaskPage queryBugFixTasks(RdTaskQuery query) {
        return withLock(() -> {
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
        });
    }

    /**
     * 按管理台查询条件分页查询所有任务类型（过滤掉 DELETED）。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    public RdTaskPage queryTasks(RdTaskQuery query) {
        return withLock(() -> {
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
        });
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
        return withLock(() -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            return transitionAndSave(existing, RdTaskStatus.MATERIAL_COLLECTING, "", "", "", "");
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
        return withLock(() -> {
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
        return withLock(() -> {
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
        return withLock(() -> {
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
        return withLock(() -> {
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
        return withLock(() -> {
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
        return withLock(() -> {
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
        return withLock(() -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            return transitionAndSave(existing, RdTaskStatus.WAITING_APPROVAL, "", policyJson, "", "");
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
        return withLock(() -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            return transitionAndSave(existing, RdTaskStatus.EXECUTING, promptSnapshot, "", "", "");
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
        return withLock(() -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            return transitionAndSave(existing, RdTaskStatus.VALIDATING, "", validationJson, "", "");
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
        return withLock(() -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            return transitionAndSave(existing, RdTaskStatus.PR_CREATING, "", reviewedResultJson, "", "");
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
        return withLock(() -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            return transitionAndSave(existing, RdTaskStatus.COMMITTED, "", executionResultJson, pullRequestUrl, "");
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
        return withLock(() -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            return transitionAndSave(existing, RdTaskStatus.REPORTING, "", deliveryReportJson, "", "");
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
        return withLock(() -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            return transitionAndSave(existing, RdTaskStatus.COMPLETED, "", executionResultJson, pullRequestUrl, "");
        });
    }

    /**
     * 将需求任务推进到 MERGED。
     *
     * @param taskId 任务 ID
     * @return 新需求任务快照
     */
    public RdRequirementTask markRequirementMerged(String taskId) {
        return withLock(() -> {
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
        return withLock(() -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            return transitionAndSave(existing, RdTaskStatus.REJECTED, "", executionResultJson, "", errorMessage);
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
        return withLock(() -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            return transitionAndSave(existing, RdTaskStatus.FAILED_NEEDS_HUMAN, "", executionResultJson, "", errorMessage);
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
        return withLock(() -> {
            RdBugFixTask existing = get(taskId);
            RdBugFixTask updated = existing.withEditedFields(title, priority, ticketTitle, System.currentTimeMillis());
            return taskStore.saveBugFixTask(updated);
        });
    }

    /**
     * 管理台暂停任务（仅标记，不改变状态机合法性）。
     *
     * @param taskId  任务 ID
     * @param message 暂停说明
     * @return 更新后任务快照
     */
    public RdBugFixTask pause(String taskId, String message) {
        return withLock(() -> {
            RdBugFixTask existing = get(taskId);
            return (RdBugFixTask) pauseTask(existing.taskId(), message);
        });
    }

    /**
     * 管理台暂停任意任务（仅标记，不改变状态机合法性）。
     *
     * @param taskId  任务 ID
     * @param message 暂停说明
     * @return 更新后任务快照
     */
    public RdTask pauseTask(String taskId, String message) {
        return withLock(() -> {
            RdTask existing = getTask(taskId);
            RdTask paused = switchPaused(existing, true, System.currentTimeMillis());
            RdTask saved = saveTask(paused);
            recordEvent(saved, RdTaskStatusEvent.ACTION_PAUSED, saved.title(), message, RdTaskEventTrigger.API);
            return saved;
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
        return withLock(() -> {
            RdBugFixTask existing = get(taskId);
            return (RdBugFixTask) resumeTask(existing.taskId(), message);
        });
    }

    /**
     * 管理台恢复任意任务。
     *
     * @param taskId  任务 ID
     * @param message 恢复说明
     * @return 更新后任务快照
     */
    public RdTask resumeTask(String taskId, String message) {
        return withLock(() -> {
            RdTask existing = getTask(taskId);
            RdTask resumed = switchPaused(existing, false, System.currentTimeMillis());
            RdTask saved = saveTask(resumed);
            recordEvent(saved, RdTaskStatusEvent.ACTION_RESUMED, saved.title(), message, RdTaskEventTrigger.API);
            return saved;
        });
    }

    /**
     * 管理台审批通过等待人工确认的需求任务。
     *
     * <p>审批本身只追加审计事件，不直接改变主状态；后续由需求交付引擎通过
     * {@code WAITING_APPROVAL -> EXECUTING} 的合法状态机边继续执行。
     *
     * @param taskId  任务 ID
     * @param message 审批说明
     * @return 审批后的任务快照
     */
    public RdRequirementTask approveRequirementTask(String taskId, String message) {
        return withLock(() -> {
            RdRequirementTask existing = getRequirementTask(taskId);
            if (existing.status() != RdTaskStatus.WAITING_APPROVAL) {
                throw new IllegalStateException("requirement task is not waiting approval: " + existing.status());
            }
            RdRequirementTask saved = taskStore.saveRequirementTask(existing);
            String approvalMessage = message == null || message.isBlank() ? "管理台审批通过" : message;
            recordEvent(saved, RdTaskStatusEvent.ACTION_APPROVED, saved.title(), approvalMessage, RdTaskEventTrigger.API);
            return saved;
        });
    }

    /**
     * 管理台逻辑删除任务（状态置 DELETED）并清理状态事件。
     *
     * @param taskId 任务 ID
     * @return 是否删除（任务不存在或已删除返回 false）
     */
    public boolean deleteTask(String taskId) {
        return withLock(() -> {
            String safeTaskId = requireTaskId(taskId);
            return taskStore.findTask(safeTaskId).map(existing -> {
                if (existing.status() == RdTaskStatus.DELETED) {
                    return false;
                }
                RdTask deleted = deletedTask(existing, System.currentTimeMillis());
                recordEvent(deleted, RdTaskStatusEvent.ACTION_DELETED, deleted.title(), "管理台删除任务", RdTaskEventTrigger.API);
                saveTask(deleted);
                if (eventStore != null) {
                    eventStore.deleteByTask(safeTaskId);
                }
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
        return withLock(() -> {
            requireTaskId(taskId);
            if (eventStore == null) {
                return List.of();
            }
            return eventStore.listByTask(taskId);
        });
    }

    private void ensureTransition(RdTaskStatus source, RdTaskStatus target) {
        if (source == target) {
            return;
        }
        boolean legal = switch (source) {
            case CREATED -> target == RdTaskStatus.MATERIAL_COLLECTING
                    || target == RdTaskStatus.SEARCHING
                    || target == RdTaskStatus.REJECTED;
            case MATERIAL_COLLECTING -> target == RdTaskStatus.MATERIAL_READY || target == RdTaskStatus.REJECTED;
            case MATERIAL_READY -> target == RdTaskStatus.CONTEXT_BUILDING
                    || target == RdTaskStatus.SEARCHING
                    || target == RdTaskStatus.EXECUTING
                    || target == RdTaskStatus.REJECTED
                    || target == RdTaskStatus.FAILED_NEEDS_HUMAN;
            case CONTEXT_BUILDING -> target == RdTaskStatus.CONTEXT_READY
                    || target == RdTaskStatus.REJECTED
                    || target == RdTaskStatus.FAILED_NEEDS_HUMAN;
            case CONTEXT_READY -> target == RdTaskStatus.PLAN_GENERATING
                    || target == RdTaskStatus.REJECTED
                    || target == RdTaskStatus.FAILED_NEEDS_HUMAN;
            case PLAN_GENERATING -> target == RdTaskStatus.PLAN_GENERATED
                    || target == RdTaskStatus.REJECTED
                    || target == RdTaskStatus.FAILED_NEEDS_HUMAN;
            case PLAN_GENERATED -> target == RdTaskStatus.WAITING_POLICY
                    || target == RdTaskStatus.REJECTED
                    || target == RdTaskStatus.FAILED_NEEDS_HUMAN;
            case WAITING_POLICY -> target == RdTaskStatus.EXECUTING
                    || target == RdTaskStatus.WAITING_APPROVAL
                    || target == RdTaskStatus.REJECTED
                    || target == RdTaskStatus.FAILED_NEEDS_HUMAN;
            case WAITING_APPROVAL -> target == RdTaskStatus.EXECUTING
                    || target == RdTaskStatus.REJECTED
                    || target == RdTaskStatus.FAILED_NEEDS_HUMAN;
            case SEARCHING -> target == RdTaskStatus.EXECUTING
                    || target == RdTaskStatus.REJECTED
                    || target == RdTaskStatus.FAILED_RETRYABLE;
            case EXECUTING -> target == RdTaskStatus.VALIDATING
                    || target == RdTaskStatus.COMMITTED
                    || target == RdTaskStatus.REJECTED
                    || target == RdTaskStatus.FAILED_RETRYABLE
                    || target == RdTaskStatus.FAILED_NEEDS_HUMAN;
            case VALIDATING -> target == RdTaskStatus.PR_CREATING
                    || target == RdTaskStatus.COMMITTED
                    || target == RdTaskStatus.REJECTED
                    || target == RdTaskStatus.FAILED_NEEDS_HUMAN;
            case PR_CREATING -> target == RdTaskStatus.COMMITTED
                    || target == RdTaskStatus.REJECTED
                    || target == RdTaskStatus.FAILED_RETRYABLE;
            case COMMITTED -> target == RdTaskStatus.MERGED
                    || target == RdTaskStatus.REPORTING
                    || target == RdTaskStatus.COMPLETED
                    || target == RdTaskStatus.REJECTED;
            case REPORTING -> target == RdTaskStatus.COMPLETED || target == RdTaskStatus.FAILED_RETRYABLE;
            case REJECTED, FAILED_RETRYABLE, FAILED_NEEDS_HUMAN -> target == RdTaskStatus.SEARCHING
                    || target == RdTaskStatus.MATERIAL_COLLECTING
                    || target == RdTaskStatus.CONTEXT_BUILDING
                    || target == RdTaskStatus.EXECUTING;
            case COMPLETED -> target == RdTaskStatus.MERGED;
            case MERGED, CANCELLED, DEAD_LETTERED, DELETED -> false;
            case RECOVERING -> target == RdTaskStatus.MATERIAL_COLLECTING
                    || target == RdTaskStatus.SEARCHING
                    || target == RdTaskStatus.EXECUTING
                    || target == RdTaskStatus.FAILED_NEEDS_HUMAN;
        };
        if (!legal) {
            throw new IllegalStateException("illegal task status transition: " + source + " -> " + target);
        }
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
        ensureTransition(existing.status(), targetStatus);
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
        RdBugFixTask saved = taskStore.saveBugFixTask(next);
        String message = saved.errorMessage().isBlank() ? "" : saved.errorMessage();
        recordEvent(saved, saved.status().name(), saved.title(), message, RdTaskEventTrigger.SYSTEM);
        return saved;
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
        ensureTransition(existing.status(), targetStatus);
        RdRequirementTask next = existing.withState(
                targetStatus,
                promptSnapshot,
                executionResultJson,
                pullRequestUrl,
                errorMessage,
                System.currentTimeMillis()
        );
        RdRequirementTask saved = taskStore.saveRequirementTask(next);
        String message = saved.errorMessage().isBlank() ? "" : saved.errorMessage();
        recordEvent(saved, saved.status().name(), saved.title(), message, RdTaskEventTrigger.SYSTEM);
        return saved;
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
        if (eventStore == null) {
            return;
        }
        long now = System.currentTimeMillis();
        List<RdTaskStatusEvent> existing = eventStore.listByTask(task.taskId());
        long previousEnteredAt = existing.isEmpty() ? now : existing.get(existing.size() - 1).enteredAtEpochMillis();
        long duration = Math.max(0L, now - previousEnteredAt);
        RdTaskStatusEvent event = new RdTaskStatusEvent(
                idGenerator.nextIdString(),
                task.taskId(),
                status,
                title,
                message == null ? "" : message,
                now,
                duration,
                trigger.name()
        );
        eventStore.save(event);
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
