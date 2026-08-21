package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.wish.rd.adapter.model.TicketSnapshot;
import com.wish.rd.bootstrap.threading.BugFixExecutionDispatchService;
import com.wish.rd.bootstrap.threading.RequirementDeliveryDispatchService;
import com.wish.rd.bootstrap.executor.impl.HostVerificationRetentionService;
import com.wish.rd.bootstrap.executor.impl.QaEvidenceRetentionService;
import com.wish.rd.engine.audit.impl.NoopRepairAuditSink;
import com.wish.rd.engine.audit.model.RepairAuditEvent;
import com.wish.rd.engine.audit.model.RepairAuditEventType;
import com.wish.rd.engine.audit.RepairAuditSinkPort;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.bugfix.RdBotFixEngine;
import com.wish.rd.engine.bugfix.model.RdBotFixCommand;
import com.wish.rd.engine.control.RdTaskExecutionControlEngine;
import com.wish.rd.engine.control.model.RdTaskExecutionControlResult;
import com.wish.rd.engine.ticket.RdTaskRestartEngine;
import com.wish.rd.engine.requirement.RequirementDeliveryEngine;
import com.wish.rd.engine.requirement.policy.RequirementPolicyTransactionPort;
import com.wish.rd.engine.requirement.policy.model.ApproveRequirementPolicyCommand;
import com.wish.rd.engine.oracle.AssertionSpecCompiler;
import com.wish.rd.exec.repair.execution.RepairExecutionControlPort;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStopCommand;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStopResult;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.impl.InMemoryTaskMaterialStore;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskPage;
import com.wish.rd.rag.runtime.model.RdTaskQuery;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.RdTaskStatusEvent;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.TaskMaterialStore;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import com.wish.rd.rag.project.model.RdProject;
import com.wish.rd.rag.project.RdProjectService;
import com.wish.rd.rag.ingestion.ObjectStorageService;
import com.wish.rd.rag.ingestion.impl.InMemoryObjectStorageService;
import com.wish.rd.rag.ingestion.model.StoredIngestionFile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * RD 任务管理 REST 控制器。
 *
 * <p>对外暴露 {@code /admin/rd-tasks} 系列接口，提供任务分页查询、详情、新建、修改、
 * 暂停 / 恢复、逻辑删除以及全链路状态事件时间线。仅返回脱敏后的视图：长字段
 * （prompt 快照、执行结果 JSON）做截断，避免把大块文本透出给列表。
 *
 * <p>状态机推进仍由 {@link RagStreamTaskRegistry} 的运行时方法负责；本控制器只做
 * 管理面 CRUD 与暂停 / 恢复标记，不直接绕过 {@code ensureTransition}。
 */
@RestController
public class RdTaskController {

    /** Host-controlled service actor; this deployment has no authenticated human identity context. */
    private static final String POLICY_APPROVAL_ACTOR = "ADMIN_API";

    /** 列表视图中文本字段的截断长度，避免大块 prompt/结果 JSON 透出列表。 */
    private static final int PREVIEW_MAX_CHARS = 120;
    private static final long MAX_MATERIAL_SIZE = 10L * 1024L * 1024L;
    private static final int MAX_MATERIALS_PER_TASK = 10;
    private static final Set<String> ALLOWED_UPLOAD_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif",
            "text/plain", "text/markdown", "text/csv", "text/xml", "text/html",
            "application/json", "application/xml"
    );
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final AssertionSpecCompiler ASSERTION_SPEC_COMPILER = new AssertionSpecCompiler();

    private final RagStreamTaskRegistry registry;
    private final RepairExecutionControlPort executionControlPort;
    private final RepairAuditSinkPort auditSink;
    private final RdTaskRestartEngine restartEngine;
    private final TaskMaterialStore materialStore;
    private final SnowflakeIdGenerator idGenerator;
    private final RequirementDeliveryEngine requirementDeliveryEngine;
    private final RequirementDeliveryDispatchService requirementDeliveryDispatchService;
    private final RdBotFixEngine bugFixEngine;
    private final BugFixExecutionDispatchService bugFixExecutionDispatchService;
    private final RdProjectService projectService;
    private RdTaskExecutionControlEngine taskExecutionControlEngine;
    private QaEvidenceRetentionService qaEvidenceRetentionService;
    private HostVerificationRetentionService hostVerificationRetentionService;
    private AgentStageRunStore agentStageRunStore;
    private RequirementPolicyTransactionPort requirementPolicyTransactionPort;
    private final Object materialUploadMonitor = new Object();
    private ObjectStorageService objectStorageService = new InMemoryObjectStorageService();

    @Autowired(required = false)
    void setObjectStorageService(ObjectStorageService objectStorageService) {
        if (objectStorageService != null) {
            this.objectStorageService = objectStorageService;
        }
    }

    @Autowired(required = false)
    void setTaskExecutionControlEngine(RdTaskExecutionControlEngine taskExecutionControlEngine) {
        this.taskExecutionControlEngine = taskExecutionControlEngine;
    }

    @Autowired(required = false)
    void setQaEvidenceRetentionService(QaEvidenceRetentionService qaEvidenceRetentionService) {
        this.qaEvidenceRetentionService = qaEvidenceRetentionService;
    }

    @Autowired(required = false)
    void setHostVerificationRetentionService(HostVerificationRetentionService hostVerificationRetentionService) {
        this.hostVerificationRetentionService = hostVerificationRetentionService;
    }

    @Autowired(required = false)
    void setAgentStageRunStore(AgentStageRunStore agentStageRunStore) {
        this.agentStageRunStore = agentStageRunStore;
    }

    /** Optional wiring preserves the controller's legacy constructors for standalone administration tests. */
    @Autowired(required = false)
    void setRequirementPolicyTransactionPort(RequirementPolicyTransactionPort requirementPolicyTransactionPort) {
        this.requirementPolicyTransactionPort = requirementPolicyTransactionPort;
    }

    public RdTaskController(RagStreamTaskRegistry registry) {
        this(
                registry,
                command -> new RepairExecutionStopResult(
                        command.taskId(),
                        command.containerName(),
                        false,
                        "execution control unavailable"
                ),
                NoopRepairAuditSink.instance(),
                null,
                new InMemoryTaskMaterialStore(),
                SnowflakeIdGenerator.defaultGenerator(),
                null,
                null,
                null,
                null,
                null
        );
    }

    public RdTaskController(RagStreamTaskRegistry registry, RdTaskRestartEngine restartEngine) {
        this(
                registry,
                command -> new RepairExecutionStopResult(
                        command.taskId(),
                        command.containerName(),
                        false,
                        "execution control unavailable"
                ),
                NoopRepairAuditSink.instance(),
                restartEngine,
                new InMemoryTaskMaterialStore(),
                SnowflakeIdGenerator.defaultGenerator(),
                null,
                null,
                null,
                null,
                null
        );
    }

    public RdTaskController(RagStreamTaskRegistry registry, RdProjectService projectService) {
        this(
                registry,
                command -> new RepairExecutionStopResult(
                        command.taskId(),
                        command.containerName(),
                        false,
                        "execution control unavailable"
                ),
                NoopRepairAuditSink.instance(),
                null,
                new InMemoryTaskMaterialStore(),
                SnowflakeIdGenerator.defaultGenerator(),
                null,
                null,
                null,
                null,
                projectService
        );
    }

    @Autowired
    public RdTaskController(
            RagStreamTaskRegistry registry,
            ObjectProvider<RepairExecutionControlPort> executionControlPortProvider,
            ObjectProvider<RepairAuditSinkPort> auditSinkProvider,
            ObjectProvider<RdTaskRestartEngine> restartEngineProvider,
            ObjectProvider<TaskMaterialStore> materialStoreProvider,
            ObjectProvider<SnowflakeIdGenerator> idGeneratorProvider,
            ObjectProvider<RequirementDeliveryEngine> requirementDeliveryEngineProvider,
            ObjectProvider<RequirementDeliveryDispatchService> requirementDeliveryDispatchServiceProvider,
            ObjectProvider<RdBotFixEngine> bugFixEngineProvider,
            ObjectProvider<BugFixExecutionDispatchService> bugFixExecutionDispatchServiceProvider,
            ObjectProvider<RdProjectService> projectServiceProvider
    ) {
        this(
                registry,
                executionControlPortProvider.getIfAvailable(() -> command -> new RepairExecutionStopResult(
                        command.taskId(),
                        command.containerName(),
                        false,
                        "execution control unavailable"
                )),
                auditSinkProvider.getIfAvailable(NoopRepairAuditSink::instance),
                restartEngineProvider.getIfAvailable(),
                materialStoreProvider.getIfAvailable(InMemoryTaskMaterialStore::new),
                idGeneratorProvider.getIfAvailable(SnowflakeIdGenerator::defaultGenerator),
                requirementDeliveryEngineProvider.getIfAvailable(),
                requirementDeliveryDispatchServiceProvider.getIfAvailable(),
                bugFixEngineProvider.getIfAvailable(),
                bugFixExecutionDispatchServiceProvider.getIfAvailable(),
                projectServiceProvider.getIfAvailable()
        );
    }

    private RdTaskController(
            RagStreamTaskRegistry registry,
            RepairExecutionControlPort executionControlPort,
            RepairAuditSinkPort auditSink,
            RdTaskRestartEngine restartEngine,
            TaskMaterialStore materialStore,
            SnowflakeIdGenerator idGenerator,
            RequirementDeliveryEngine requirementDeliveryEngine,
            RequirementDeliveryDispatchService requirementDeliveryDispatchService,
            RdBotFixEngine bugFixEngine,
            BugFixExecutionDispatchService bugFixExecutionDispatchService,
            RdProjectService projectService
    ) {
        this.registry = registry;
        this.executionControlPort = executionControlPort;
        this.auditSink = auditSink;
        this.restartEngine = restartEngine;
        this.materialStore = materialStore == null ? new InMemoryTaskMaterialStore() : materialStore;
        this.idGenerator = idGenerator == null ? SnowflakeIdGenerator.defaultGenerator() : idGenerator;
        this.requirementDeliveryEngine = requirementDeliveryEngine;
        this.requirementDeliveryDispatchService = requirementDeliveryDispatchService;
        this.bugFixEngine = bugFixEngine;
        this.bugFixExecutionDispatchService = bugFixExecutionDispatchService;
        this.projectService = projectService;
    }

    /**
     * 分页查询任务。
     *
     * @param taskType 任务类型过滤
     * @param status   状态过滤
     * @param priority 优先级过滤
     * @param projectId 项目 ID 过滤
     * @param ticketId 工单 ID 子串
     * @param keyword  关键词（匹配标题 / 工单标题 / 工单 ID）
     * @param page     页码（默认 1）
     * @param pageSize 每页大小（默认 20）
     * @return 分页结果
     */
    @GetMapping(value = "/admin/rd-tasks", produces = MediaType.APPLICATION_JSON_VALUE)
    public RdTaskPageView list(
            @RequestParam(value = "taskType", required = false) String taskType,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "priority", required = false) String priority,
            @RequestParam(value = "projectId", required = false) String projectId,
            @RequestParam(value = "ticketId", required = false) String ticketId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize
    ) {
        RdTaskQuery query = new RdTaskQuery(taskType, status, priority, projectId, ticketId, keyword, page, pageSize);
        RdTaskPage result = registry.queryTasks(query);
        List<RdTaskView> views = result.records().stream()
                .map(RdTaskController::toListView)
                .toList();
        return new RdTaskPageView(views, result.page(), result.pageSize(), result.total(), result.pages());
    }

    /**
     * 查询单个任务详情。
     *
     * @param taskId 任务 ID
     * @return 任务视图
     */
    @GetMapping(value = "/admin/rd-tasks/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public RdTaskView get(@PathVariable("taskId") String taskId) {
        return toWorkbenchView(registry.getAdminShell(taskId));
    }

    /**
     * Prompt snapshot and execution evidence for delivery/audit views. Workbench GET omits these blobs.
     */
    @GetMapping(value = "/admin/rd-tasks/{taskId}/audit-content", produces = MediaType.APPLICATION_JSON_VALUE)
    public RdTaskAuditContentView getAuditContent(@PathVariable("taskId") String taskId) {
        RdTaskView detail = toDetailView(registry.getTask(taskId));
        return new RdTaskAuditContentView(
                detail.taskId(),
                detail.promptSnapshot(),
                detail.executionResultJson(),
                detail.executionEvidence()
        );
    }

    /**
     * 新建任务（CREATED）。
     *
     * @param request 创建请求
     * @return 新任务视图
     */
    @PostMapping("/admin/rd-tasks")
    public RdTaskView create(@RequestBody CreateRdTaskRequest request) {
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        ProjectSnapshot project = resolveProject(request.projectId());
        RdBugFixTask task = registry.createTaskManually(
                generatedTicketId(request.ticketId()),
                request.ticketTitle(),
                request.title(),
                request.priority(),
                request.promptSnapshot(),
                project.projectId(),
                project.projectKey(),
                project.projectName(),
                project.repositoryUrl(),
                project.repoOwner(),
                project.repoName(),
                project.baseBranch()
        );
        if (request.autoExecute()) {
            submitBugFixTask(task);
            return toDetailView(registry.getTask(task.taskId()));
        }
        return toDetailView(task);
    }

    private String generatedTicketId(String ticketId) {
        if (ticketId != null && !ticketId.isBlank()) {
            return ticketId.strip();
        }
        return "ticket-" + idGenerator.nextIdString();
    }

    /**
     * 新建需求交付任务。
     *
     * @param request 创建请求
     * @return 新任务视图
     */
    @PostMapping("/admin/rd-tasks/requirements")
    public RdTaskView createRequirement(@RequestBody CreateRequirementTaskRequest request) {
        CreateRequirementTaskRequest safeRequest = requireRequirementRequest(request);
        ProjectSnapshot project = resolveProject(safeRequest.projectId());
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                safeRequest.title(),
                safeRequest.priority(),
                "ADMIN",
                "",
                "",
                project.projectId(),
                project.projectKey(),
                project.projectName(),
                firstNonBlank(safeRequest.repositoryUrl(), project.repositoryUrl()),
                firstNonBlank(safeRequest.repoOwner(), project.repoOwner()),
                firstNonBlank(safeRequest.repoName(), project.repoName()),
                firstNonBlank(safeRequest.baseBranch(), project.baseBranch()),
                safeRequest.expectedResult(),
                safeRequest.acceptanceCriteria(),
                List.of(),
                safeRequest.autoExecute(),
                safeRequest.tokenBudgetOverride(),
                safeRequest.hostAssertionBundle()
        ));
        saveRequirementMaterials(task.taskId(), safeRequest.materials());
        if (safeRequest.autoExecute()) {
            submitRequirementTask(task.taskId());
            return toDetailView(registry.getTask(task.taskId()));
        }
        return toDetailView(task);
    }

    /**
     * 修改任务标题 / 优先级 / 工单标题。
     *
     * @param taskId  任务 ID
     * @param request 修改请求
     * @return 更新后任务视图
     */
    @PutMapping("/admin/rd-tasks/{taskId}")
    public RdTaskView update(
            @PathVariable("taskId") String taskId,
            @RequestBody UpdateRdTaskRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request body must not be null");
        }
        return toDetailView(registry.updateTaskMetadata(
                taskId, request.title(), request.priority(), request.ticketTitle()));
    }

    /**
     * 暂停任务（仅标记，不改变状态机）。
     *
     * @param taskId  任务 ID
     * @param request 动作请求（可选 message）
     * @return 更新后任务视图
     */
    @PostMapping("/admin/rd-tasks/{taskId}/pause")
    public RdTaskView pause(
            @PathVariable("taskId") String taskId,
            @RequestBody(required = false) RdTaskActionRequest request
    ) {
        String message = request == null ? "管理台暂停" : request.message();
        return toDetailView(registry.pauseTask(taskId, message));
    }

    /**
     * 恢复任务，并在装配了重启用例时重新发布修复队列消息。
     *
     * @param taskId  任务 ID
     * @param request 动作请求（可选 message）
     * @return 更新后任务视图
     */
    @PostMapping("/admin/rd-tasks/{taskId}/resume")
    public RdTaskView resume(
            @PathVariable("taskId") String taskId,
            @RequestBody(required = false) RdTaskActionRequest request
    ) {
        String message = request == null ? "管理台恢复" : request.message();
        if (restartEngine != null) {
            RdTask current = registry.getTask(taskId);
            if (current instanceof RdBugFixTask) {
                return toDetailView(restartEngine.resumeAndRestart(taskId, message));
            }
        }
        RdTask resumed = registry.resumeTask(taskId, message);
        if (resumed instanceof RdRequirementTask && isRecoverableRequirementStatus(resumed.status())) {
            submitRequirementTask(taskId);
            return toDetailView(registry.getTask(taskId));
        }
        return toDetailView(resumed);
    }

    /**
     * Produces the durable, digest-bound approval resume command through the policy transaction.
     *
     * @param taskId  任务 ID
     * @param request immutable policy approval payload
     * @return latest registry task view; persistence-backed registry wiring owns freshness
     */
    @PostMapping("/admin/rd-tasks/{taskId}/approve")
    public RdTaskView approve(
            @PathVariable("taskId") String taskId,
            @RequestBody ApproveRequirementPolicyRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request body must not be null");
        }
        if (requirementPolicyTransactionPort == null) {
            throw new IllegalStateException("requirement policy transaction port unavailable");
        }
        requirementPolicyTransactionPort.approve(new ApproveRequirementPolicyCommand(
                taskId,
                request.policyRunId(),
                request.expectedTaskVersion(),
                request.expectedTaskFence(),
                request.planDigest(),
                request.policyDigest(),
                request.approvalRequestId(),
                request.decision(),
                request.note()
        ), POLICY_APPROVAL_ACTOR, Instant.now().toEpochMilli());
        return toDetailView(registry.getTask(taskId));
    }

    /**
     * 手动停止正在执行的任务。
     *
     * @param taskId  任务 ID
     * @param request 动作请求（可选 message）
     * @return 停止结果
     */
    @PostMapping("/admin/rd-tasks/{taskId}/stop")
    public StopRdTaskResponse stop(
            @PathVariable("taskId") String taskId,
            @RequestBody(required = false) RdTaskActionRequest request
    ) {
        String message = request == null || request.message().isBlank() ? "管理台停止任务" : request.message();
        auditSink.publish(RepairAuditEvent.now(
                "",
                taskId,
                "",
                RepairAuditEventType.EXECUTION_STOP_REQUESTED,
                "RD-Bot",
                message,
                Map.of()
        ));
        if (taskExecutionControlEngine != null) {
            RdTaskExecutionControlResult result = taskExecutionControlEngine.stopTask(taskId, message);
            auditSink.publish(RepairAuditEvent.now(
                    "",
                    result.task().taskId(),
                    result.task() instanceof RdBugFixTask bugFixTask ? bugFixTask.ticketId() : "",
                    RepairAuditEventType.EXECUTION_STOPPED,
                    "control-plane",
                    result.message(),
                    Map.of(
                            "stopped", String.valueOf(result.externalStopped()),
                            "containerName", result.containerName(),
                            "cancelledStageCount", String.valueOf(result.cancelledStageCount())
                    )
            ));
            return new StopRdTaskResponse(
                    toDetailView(result.task()),
                    result.externalStopped(),
                    result.containerName(),
                    result.message()
            );
        }
        RepairExecutionStopResult stopResult = executionControlPort.stop(
                new RepairExecutionStopCommand("", taskId, "", message)
        );
        RdTask task = registry.cancelTask(taskId, message);
        auditSink.publish(RepairAuditEvent.now(
                "",
                task.taskId(),
                task instanceof RdBugFixTask bugFixTask ? bugFixTask.ticketId() : "",
                RepairAuditEventType.EXECUTION_STOPPED,
                "Docker",
                stopResult.message(),
                Map.of("stopped", String.valueOf(stopResult.stopped()), "containerName", stopResult.containerName())
        ));
        return new StopRdTaskResponse(
                toDetailView(task),
                stopResult.stopped(),
                stopResult.containerName(),
                stopResult.message()
        );
    }

    /**
     * 逻辑删除任务。
     *
     * @param taskId 任务 ID
     * @return 删除结果
     */
    @DeleteMapping("/admin/rd-tasks/{taskId}")
    public DeleteResponse delete(@PathVariable("taskId") String taskId) {
        if (qaEvidenceRetentionService != null) {
            qaEvidenceRetentionService.deleteForTask(taskId);
        }
        if (hostVerificationRetentionService != null) {
            hostVerificationRetentionService.deleteForTask(taskId);
        }
        return new DeleteResponse(registry.deleteTask(taskId));
    }

    /**
     * 查询任务全链路状态事件时间线。
     *
     * @param taskId 任务 ID
     * @return 状态事件视图列表（升序）
     */
    @GetMapping(value = "/admin/rd-tasks/{taskId}/timeline", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<RdTaskStatusEventView> timeline(@PathVariable("taskId") String taskId) {
        return registry.timeline(taskId).stream()
                .map(RdTaskController::toEventView)
                .toList();
    }

    /**
     * 提交任务进入执行链路。
     *
     * @param taskId 任务 ID
     * @return 最新任务视图
     */
    @PostMapping("/admin/rd-tasks/{taskId}/submit")
    public RdTaskView submit(@PathVariable("taskId") String taskId) {
        RdTask task = registry.getTask(taskId);
        if (task instanceof RdRequirementTask) {
            submitRequirementTask(taskId);
            return toDetailView(registry.getTask(taskId));
        }
        if (task instanceof RdBugFixTask bugFixTask) {
            submitBugFixTask(bugFixTask);
            return toDetailView(registry.getTask(taskId));
        }
        throw new IllegalArgumentException("unsupported rd task type: " + task.taskType());
    }

    /**
     * 查询任务材料。
     *
     * @param taskId 任务 ID
     * @return 材料列表
     */
    @GetMapping(value = "/admin/rd-tasks/{taskId}/materials", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TaskMaterialView> materials(@PathVariable("taskId") String taskId) {
        registry.getTask(taskId);
        return materialStore.listByTask(taskId).stream()
                .map(RdTaskController::toMaterialView)
                .toList();
    }

    /**
     * 为需求任务追加手动文本材料。
     *
     * @param taskId  任务 ID
     * @param request 材料输入
     * @return 新材料视图
     */
    @PostMapping(
            value = "/admin/rd-tasks/{taskId}/materials/text",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public TaskMaterialView addTextMaterial(
            @PathVariable("taskId") String taskId,
            @RequestBody RequirementMaterialInput request
    ) {
        registry.getRequirementTask(taskId);
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw new IllegalArgumentException("material content must not be blank");
        }
        String recoveryStageRunId = requireOwnedRecoveryStageRun(taskId, request.recoveryStageRunId());
        TaskMaterial material = saveMaterial(
                taskId,
                parseMaterialType(request.materialType()),
                TaskMaterialSourceType.MANUAL_TEXT,
                request.title(),
                "",
                request.content(),
                request.mimeType(),
                request.revisionId(),
                recoveryMetadataJson(recoveryStageRunId)
        );
        return toMaterialView(material);
    }

    /**
     * 为 Bug 修复或需求任务上传本地材料文件。
     *
     * @param taskId       任务 ID
     * @param title        展示标题
     * @param materialType 材料类型
     * @param recoveryStageRunId 关联的失败阶段运行 ID（可选）
     * @param file         上传文件
     * @return 新材料视图
     */
    @PostMapping(
            value = "/admin/rd-tasks/{taskId}/materials/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public TaskMaterialView uploadMaterial(
            @PathVariable("taskId") String taskId,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "materialType", required = false) String materialType,
            @RequestParam(value = "recoveryStageRunId", required = false) String recoveryStageRunId,
            @RequestPart("file") MultipartFile file
    ) {
        registry.getTask(taskId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("upload file must not be empty");
        }
        if (file.getSize() > MAX_MATERIAL_SIZE) {
            throw new IllegalArgumentException("upload file exceeds 10 MiB limit");
        }
        String filename = safeFilename(file.getOriginalFilename());
        String mimeType = normalizeUploadMimeType(file.getContentType());
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception exception) {
            throw new IllegalArgumentException("upload file cannot be read");
        }
        if (bytes.length > MAX_MATERIAL_SIZE) {
            throw new IllegalArgumentException("upload file exceeds 10 MiB limit");
        }
        validateUploadContent(filename, mimeType, bytes);
        String ownedRecoveryStageRunId = requireOwnedRecoveryStageRun(taskId, recoveryStageRunId);
        synchronized (materialUploadMonitor) {
            // Local memory mode needs an atomic count/save section; PostgreSQL also enforces this with a trigger.
            if (materialStore.listByTask(taskId).size() >= MAX_MATERIALS_PER_TASK) {
                throw new IllegalArgumentException("task material limit exceeded: 10");
            }
            StoredIngestionFile stored = objectStorageService.upload(
                    "rd-task-materials",
                    new java.io.ByteArrayInputStream(bytes),
                    bytes.length,
                    filename,
                    mimeType
            );
            boolean image = mimeType.startsWith("image/");
            String contentPreview = image
                    ? "图片附件: " + filename + " (" + mimeType + ", " + bytes.length + " bytes)"
                    : preview(new String(bytes, StandardCharsets.UTF_8).strip(), 2000);
            long now = System.currentTimeMillis();
            TaskMaterial material = materialStore.save(new TaskMaterial(
                    idGenerator.nextIdString(),
                    taskId,
                    parseMaterialType(materialType),
                    TaskMaterialSourceType.LOCAL_UPLOAD,
                    title == null || title.isBlank() ? filename : title,
                    "local-upload://" + filename,
                    mimeType,
                    sha256(bytes),
                    contentPreview,
                    stored.url(),
                    "",
                    "",
                    uploadMetadataJson(filename, bytes.length, ownedRecoveryStageRunId),
                    now,
                    now
            ));
            return toMaterialView(material);
        }
    }

    /**
     * 为需求任务登记 Feishu 文档材料。
     *
     * <p>当前接口只登记真实来源 URL/修订信息和可审计预览，不使用本地 Mock Feishu 客户端伪造正文。
     * 后续接入真实 Feishu 文档读取端口时，可在此处把正文写入材料或知识库文档。
     *
     * @param taskId  任务 ID
     * @param request 材料输入
     * @return 新材料视图
     */
    @PostMapping(
            value = "/admin/rd-tasks/{taskId}/materials/feishu",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public TaskMaterialView addFeishuMaterial(
            @PathVariable("taskId") String taskId,
            @RequestBody RequirementMaterialInput request
    ) {
        registry.getRequirementTask(taskId);
        if (request == null || request.sourceUri() == null || request.sourceUri().isBlank()) {
            throw new IllegalArgumentException("feishu sourceUri must not be blank");
        }
        TaskMaterial material = saveMaterial(
                taskId,
                parseMaterialType(request.materialType()),
                TaskMaterialSourceType.FEISHU_DOC,
                request.title(),
                request.sourceUri(),
                request.content(),
                request.mimeType(),
                request.revisionId(),
                "{}"
        );
        return toMaterialView(material);
    }

    /**
     * 查询单个材料预览。
     *
     * @param taskId     任务 ID
     * @param materialId 材料 ID
     * @return 材料预览
     */
    @GetMapping(
            value = "/admin/rd-tasks/{taskId}/materials/{materialId}/preview",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public TaskMaterialPreviewView materialPreview(
            @PathVariable("taskId") String taskId,
            @PathVariable("materialId") String materialId
    ) {
        registry.getTask(taskId);
        TaskMaterial material = materialStore.findById(materialId)
                .filter(candidate -> candidate.taskId().equals(taskId))
                .orElseThrow(() -> new NoSuchElementException("task material not found: " + materialId));
        return toMaterialPreviewView(material);
    }

    /** Returns the original object bytes after enforcing task ownership. */
    @GetMapping("/admin/rd-tasks/{taskId}/materials/{materialId}/content")
    public ResponseEntity<byte[]> materialContent(
            @PathVariable("taskId") String taskId,
            @PathVariable("materialId") String materialId
    ) {
        registry.getTask(taskId);
        TaskMaterial material = materialStore.findById(materialId)
                .filter(candidate -> candidate.taskId().equals(taskId))
                .orElseThrow(() -> new NoSuchElementException("task material not found: " + materialId));
        if (material.artifactUri().isBlank()) {
            throw new NoSuchElementException("task material content not found: " + materialId);
        }
        try (java.io.InputStream input = objectStorageService.openStream(material.artifactUri())) {
            byte[] bytes = input.readAllBytes();
            if (material.contentHash().isBlank()) {
                throw new IllegalStateException("task material content hash missing: " + materialId);
            }
            if (!sha256(bytes).equalsIgnoreCase(material.contentHash())) {
                throw new IllegalStateException("task material content hash mismatch: " + materialId);
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(material.mimeType()));
            String disposition = material.mimeType().startsWith("image/") ? "inline" : "attachment";
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    disposition + "; filename=\"" + safeFilename(material.title()) + "\"");
            headers.setCacheControl("private, max-age=300");
            return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
        } catch (NoSuchElementException exception) {
            throw exception;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("task material content cannot be read");
        }
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", exception.getMessage()));
    }

    private static RdTaskView toListView(RdTask task) {
        return toView(task, true, false);
    }

    private static RdTaskView toDetailView(RdTask task) {
        return toView(task, false, false);
    }

    private static RdTaskView toWorkbenchView(RdTask task) {
        return toView(task, false, true);
    }

    private static RdTaskView toView(RdTask task, boolean listView, boolean omitAuditBlobs) {
        String ticketId = "";
        String ticketTitle = "";
        String promptSnapshot = "";
        String executionResultJson = "";
        String pullRequestUrl = "";
        boolean paused = false;
        String sourceType = "";
        String sourceId = "";
        String sourceUrl = "";
        String projectId = "";
        String projectKey = "";
        String projectName = "";
        String repositoryUrl = "";
        String repoOwner = "";
        String repoName = "";
        String baseBranch = "";
        String workBranch = "";
        String expectedResult = "";
        String acceptanceCriteriaJson = "[]";
        JsonNode hostAssertionBundle = null;
        long tokenBudgetOverride = 0L;
        if (task instanceof RdBugFixTask bugFixTask) {
            ticketId = bugFixTask.ticketId();
            ticketTitle = bugFixTask.ticketTitle();
            promptSnapshot = bugFixTask.promptSnapshot();
            executionResultJson = bugFixTask.executionResultJson();
            pullRequestUrl = bugFixTask.pullRequestUrl();
            paused = bugFixTask.paused();
            projectId = bugFixTask.projectId();
            projectKey = bugFixTask.projectKey();
            projectName = bugFixTask.projectName();
            repositoryUrl = bugFixTask.repositoryUrl();
            repoOwner = bugFixTask.repoOwner();
            repoName = bugFixTask.repoName();
            baseBranch = bugFixTask.baseBranch();
        } else if (task instanceof RdRequirementTask requirementTask) {
            promptSnapshot = requirementTask.promptSnapshot();
            executionResultJson = requirementTask.executionResultJson();
            pullRequestUrl = requirementTask.pullRequestUrl();
            paused = requirementTask.paused();
            sourceType = requirementTask.sourceType();
            sourceId = requirementTask.sourceId();
            sourceUrl = requirementTask.sourceUrl();
            projectId = requirementTask.projectId();
            projectKey = requirementTask.projectKey();
            projectName = requirementTask.projectName();
            repositoryUrl = requirementTask.repositoryUrl();
            repoOwner = requirementTask.repoOwner();
            repoName = requirementTask.repoName();
            baseBranch = requirementTask.baseBranch();
            workBranch = requirementTask.workBranch();
            expectedResult = requirementTask.expectedResult();
            acceptanceCriteriaJson = requirementTask.acceptanceCriteriaJson();
            hostAssertionBundle = listView ? null : requirementTask.hostAssertionBundle();
            tokenBudgetOverride = requirementTask.tokenBudgetOverride();
        }
        if (omitAuditBlobs) {
            promptSnapshot = "";
            executionResultJson = "";
        }
        return new RdTaskView(
                task.taskId(),
                task.taskType(),
                ticketId,
                ticketTitle,
                task.priority(),
                task.status().name(),
                task.title(),
                listView ? preview(promptSnapshot) : promptSnapshot,
                listView ? preview(executionResultJson) : executionResultJson,
                pullRequestUrl,
                listView ? preview(task.errorMessage(), 200) : task.errorMessage(),
                task.createTimeEpochMillis(),
                task.updateTimeEpochMillis(),
                paused,
                sourceType,
                sourceId,
                sourceUrl,
                projectId,
                projectKey,
                projectName,
                repositoryUrl,
                repoOwner,
                repoName,
                baseBranch,
                workBranch,
                expectedResult,
                acceptanceCriteriaJson,
                hostAssertionBundle,
                executionEvidence(executionResultJson, pullRequestUrl),
                tokenBudgetOverride
        );
    }

    private static ExecutionEvidenceView executionEvidence(String executionResultJson, String pullRequestUrl) {
        JsonNode root = readJson(executionResultJson);
        return new ExecutionEvidenceView(
                text(root, "summary"),
                text(root, "prBody"),
                list(root.path("changedFiles")),
                firstText(root, "testStatus", "testMetadata", "testStatus"),
                firstText(root, "riskLevel", "riskMetadata", "riskLevel"),
                list(firstNode(root, "testCommands", "testMetadata", "testCommands")),
                pullRequestUrl == null ? "" : pullRequestUrl.strip()
        );
    }

    private static JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        try {
            return OBJECT_MAPPER.readTree(value);
        } catch (Exception ignored) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    private static String text(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode value = node.path(fieldName);
        return value.isTextual() ? value.asText("").strip() : "";
    }

    private static String firstText(JsonNode root, String directField, String objectField, String nestedField) {
        String direct = text(root, directField);
        if (!direct.isBlank()) {
            return direct;
        }
        JsonNode nested = root == null ? null : root.path(objectField).path(nestedField);
        return nested != null && nested.isTextual() ? nested.asText("").strip() : "";
    }

    private static JsonNode firstNode(JsonNode root, String directField, String objectField, String nestedField) {
        JsonNode direct = root == null ? null : root.path(directField);
        if (direct != null && !direct.isMissingNode() && !direct.isNull()) {
            return direct;
        }
        return root == null ? OBJECT_MAPPER.createArrayNode() : root.path(objectField).path(nestedField);
    }

    private static List<String> list(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> values = new java.util.ArrayList<>();
            node.forEach(item -> {
                String value = item.isTextual() ? item.asText("").strip() : item.toString().strip();
                if (!value.isBlank()) {
                    values.add(value);
                }
            });
            return List.copyOf(values);
        }
        if (node.isTextual()) {
            String value = node.asText("").strip();
            if (value.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(value.split("[,\\n]"))
                    .map(String::strip)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        return List.of();
    }

    private static RdTaskStatusEventView toEventView(RdTaskStatusEvent event) {
        return new RdTaskStatusEventView(
                event.id(),
                event.taskId(),
                event.status(),
                event.title(),
                event.message(),
                event.enteredAtEpochMillis(),
                event.durationMillis(),
                event.trigger()
        );
    }

    private static String preview(String value) {
        return preview(value, PREVIEW_MAX_CHARS);
    }

    private static String preview(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...";
    }

    private CreateRequirementTaskRequest requireRequirementRequest(CreateRequirementTaskRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body must not be null");
        }
        if (request.materials() == null || request.materials().isEmpty()) {
            throw new IllegalArgumentException("materials must not be empty");
        }
        boolean hasUsableMaterial = request.materials().stream().anyMatch(RdTaskController::hasUsableMaterial);
        if (!hasUsableMaterial) {
            throw new IllegalArgumentException("at least one material content or sourceUri is required");
        }
        if (request.hostAssertionBundle() != null) {
            // The dedicated JSON field is executable Host input. Do not reinterpret a malformed
            // envelope as legacy natural-language acceptance text.
            ASSERTION_SPEC_COMPILER.compileByScope(request.hostAssertionBundle());
        }
        return request;
    }

    private static boolean hasUsableMaterial(RequirementMaterialInput input) {
        if (input == null) {
            return false;
        }
        return (input.content() != null && !input.content().isBlank())
                || (input.sourceUri() != null && !input.sourceUri().isBlank());
    }

    private ProjectSnapshot resolveProject(String projectId) {
        String safeProjectId = projectId == null ? "" : projectId.strip();
        if (safeProjectId.isBlank()) {
            return ProjectSnapshot.empty();
        }
        if (projectService == null) {
            throw new IllegalArgumentException("project management is unavailable");
        }
        try {
            RdProject project = projectService.getEnabled(safeProjectId);
            return new ProjectSnapshot(
                    project.projectId(),
                    project.projectKey(),
                    project.name(),
                    project.repositoryUrl(),
                    project.repoOwner(),
                    project.repoName(),
                    project.defaultBranch()
            );
        } catch (NoSuchElementException exception) {
            throw new IllegalArgumentException(exception.getMessage());
        }
    }

    private static String firstNonBlank(String first, String second) {
        String safeFirst = first == null ? "" : first.strip();
        if (!safeFirst.isBlank()) {
            return safeFirst;
        }
        return second == null ? "" : second.strip();
    }

    private void saveRequirementMaterials(String taskId, List<RequirementMaterialInput> inputs) {
        for (int i = 0; i < inputs.size(); i++) {
            RequirementMaterialInput input = inputs.get(i);
            if (!hasUsableMaterial(input)) {
                continue;
            }
            saveMaterial(
                    taskId,
                    parseMaterialType(input.materialType()),
                    parseSourceType(input.sourceType()),
                    input.title() == null || input.title().isBlank() ? "需求材料-" + (i + 1) : input.title(),
                    input.sourceUri(),
                    input.content(),
                    input.mimeType(),
                    input.revisionId(),
                    "{}"
            );
        }
    }

    private TaskMaterial saveMaterial(
            String taskId,
            TaskMaterialType materialType,
            TaskMaterialSourceType sourceType,
            String title,
            String sourceUri,
            String content,
            String mimeType,
            String revisionId,
            String metadataJson
    ) {
        String safeContent = content == null ? "" : content.strip();
        String safeSourceUri = sourceUri == null ? "" : sourceUri.strip();
        String previewValue = materialPreview(sourceType, safeContent, safeSourceUri);
        long now = System.currentTimeMillis();
        TaskMaterial material = new TaskMaterial(
                idGenerator.nextIdString(),
                taskId,
                materialType,
                sourceType,
                title,
                safeSourceUri,
                mimeType == null || mimeType.isBlank() ? "text/plain" : mimeType,
                sha256(safeContent.isBlank() ? safeSourceUri : safeContent),
                preview(previewValue, 2000),
                "",
                "",
                revisionId,
                metadataJson,
                now,
                now
        );
        return materialStore.save(material);
    }

    private static String materialPreview(TaskMaterialSourceType sourceType, String content, String sourceUri) {
        String safeContent = content == null ? "" : content.strip();
        if (!safeContent.isBlank()) {
            return safeContent;
        }
        String safeSourceUri = sourceUri == null ? "" : sourceUri.strip();
        if (sourceType == TaskMaterialSourceType.FEISHU_DOC && !safeSourceUri.isBlank()) {
            return "Feishu 文档来源: " + safeSourceUri;
        }
        return safeSourceUri;
    }

    private static String safeFilename(String filename) {
        String safe = filename == null ? "" : filename.strip().replace("\\", "/");
        if (safe.isBlank()) {
            return "uploaded-file";
        }
        int index = safe.lastIndexOf('/');
        String basename = index >= 0 ? safe.substring(index + 1) : safe;
        String sanitized = basename.replaceAll("[\\p{Cntrl}\"']", "_");
        return sanitized.isBlank() ? "uploaded-file" : sanitized;
    }

    private static String normalizeUploadMimeType(String mimeType) {
        String normalized = mimeType == null ? "" : mimeType.strip().toLowerCase(java.util.Locale.ROOT);
        if (!ALLOWED_UPLOAD_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported upload content type: " + normalized);
        }
        return normalized;
    }

    private static void validateUploadContent(String filename, String mimeType, byte[] bytes) {
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        boolean extensionMatches = switch (mimeType) {
            case "image/png" -> lower.endsWith(".png");
            case "image/jpeg" -> lower.endsWith(".jpg") || lower.endsWith(".jpeg");
            case "image/webp" -> lower.endsWith(".webp");
            case "image/gif" -> lower.endsWith(".gif");
            case "text/markdown" -> lower.endsWith(".md") || lower.endsWith(".markdown");
            case "text/csv" -> lower.endsWith(".csv");
            case "application/json" -> lower.endsWith(".json");
            case "application/xml", "text/xml" -> lower.endsWith(".xml");
            case "text/html" -> lower.endsWith(".html") || lower.endsWith(".htm");
            case "text/plain" -> true;
            default -> false;
        };
        if (!extensionMatches) {
            throw new IllegalArgumentException("upload filename extension does not match content type");
        }
        boolean signatureMatches = switch (mimeType) {
            case "image/png" -> startsWith(bytes, new int[]{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            case "image/jpeg" -> startsWith(bytes, new int[]{0xff, 0xd8, 0xff});
            case "image/gif" -> startsWith(bytes, "GIF8".getBytes(StandardCharsets.US_ASCII));
            case "image/webp" -> bytes.length >= 12
                    && startsWith(bytes, "RIFF".getBytes(StandardCharsets.US_ASCII))
                    && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
            default -> !containsNullByte(bytes);
        };
        if (!signatureMatches) {
            throw new IllegalArgumentException("upload content does not match declared content type");
        }
    }

    private static boolean startsWith(byte[] value, int[] prefix) {
        if (value == null || value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if ((value[index] & 0xff) != prefix[index]) return false;
        }
        return true;
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value == null || value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) return false;
        }
        return true;
    }

    private static boolean containsNullByte(byte[] value) {
        if (value == null) return false;
        for (byte item : value) if (item == 0) return true;
        return false;
    }

    private String requireOwnedRecoveryStageRun(String taskId, String recoveryStageRunId) {
        String safeStageRunId = recoveryStageRunId == null ? "" : recoveryStageRunId.strip();
        if (safeStageRunId.isBlank()) {
            return "";
        }
        if (agentStageRunStore == null) {
            throw new IllegalArgumentException("recovery stage run cannot be verified: " + safeStageRunId);
        }
        AgentStageRun stageRun = agentStageRunStore.findById(safeStageRunId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "recovery stage run not found: " + safeStageRunId));
        if (!stageRun.taskId().equals(taskId)) {
            throw new IllegalArgumentException(
                    "recovery stage run does not belong to task " + taskId + ": " + safeStageRunId);
        }
        return safeStageRunId;
    }

    private static String recoveryMetadataJson(String recoveryStageRunId) {
        String safeStageRunId = recoveryStageRunId == null ? "" : recoveryStageRunId.strip();
        if (safeStageRunId.isBlank()) {
            return "{}";
        }
        return serializeMetadata(Map.of("recoveryStageRunId", safeStageRunId));
    }

    private static String uploadMetadataJson(String filename, long sizeBytes, String recoveryStageRunId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("filename", filename);
        metadata.put("size", sizeBytes);
        String safeStageRunId = recoveryStageRunId == null ? "" : recoveryStageRunId.strip();
        if (!safeStageRunId.isBlank()) {
            metadata.put("recoveryStageRunId", safeStageRunId);
        }
        return serializeMetadata(metadata);
    }

    private static String serializeMetadata(Map<String, ?> metadata) {
        try {
            return OBJECT_MAPPER.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception exception) {
            throw new IllegalStateException("material metadata cannot be serialized", exception);
        }
    }

    private static TaskMaterialPreviewView toMaterialPreviewView(TaskMaterial material) {
        return new TaskMaterialPreviewView(
                material.materialId(),
                material.taskId(),
                material.title(),
                material.sourceType().name(),
                material.sourceUri(),
                material.mimeType(),
                material.contentHash(),
                material.contentPreview()
        );
    }

    private void submitRequirementTask(String taskId) {
        if (requirementDeliveryDispatchService == null) {
            throw new IllegalStateException("requirement delivery dispatch service unavailable");
        }
        requirementDeliveryDispatchService.submit(taskId);
    }

    private boolean isRecoverableRequirementStatus(RdTaskStatus status) {
        return status == RdTaskStatus.REJECTED
                || status == RdTaskStatus.FAILED_RETRYABLE
                || status == RdTaskStatus.FAILED_NEEDS_HUMAN
                || status == RdTaskStatus.RECOVERING;
    }

    private void submitBugFixTask(RdBugFixTask task) {
        RdBotFixCommand command = toBugFixCommand(task);
        if (bugFixExecutionDispatchService != null) {
            bugFixExecutionDispatchService.submit(command);
            return;
        }
        if (bugFixEngine == null) {
            throw new IllegalStateException("bug-fix execution engine unavailable");
        }
        bugFixEngine.runBugFix(command);
    }

    private RdBotFixCommand toBugFixCommand(RdBugFixTask task) {
        Instant now = Instant.now();
        return new RdBotFixCommand(
                new TicketSnapshot(
                        task.ticketId(),
                        firstNonBlank(task.ticketTitle(), task.title()),
                        bugFixDescription(task),
                        bugFixLabels(task),
                        task.createTimeEpochMillis() > 0 ? Instant.ofEpochMilli(task.createTimeEpochMillis()) : now,
                        task.priority(),
                        task.status().name(),
                        "",
                        "admin-rd-task",
                        "",
                        bugFixCustomFields(task),
                        task.updateTimeEpochMillis() > 0 ? Instant.ofEpochMilli(task.updateTimeEpochMillis()) : now,
                        Instant.EPOCH
                ),
                bugFixLogs(task),
                false,
                task.priority()
        );
    }

    private List<String> bugFixLogs(RdBugFixTask task) {
        String prompt = task.promptSnapshot() == null ? "" : task.promptSnapshot().strip();
        return prompt.isBlank() ? List.of() : List.of(prompt);
    }

    private List<String> bugFixLabels(RdBugFixTask task) {
        if (task.projectKey() == null || task.projectKey().isBlank()) {
            return List.of("admin", "bugfix");
        }
        return List.of("admin", "bugfix", task.projectKey().strip());
    }

    private Map<String, String> bugFixCustomFields(RdBugFixTask task) {
        Map<String, String> fields = new LinkedHashMap<>();
        putIfNotBlank(fields, "taskId", task.taskId());
        putIfNotBlank(fields, "projectId", task.projectId());
        putIfNotBlank(fields, "projectKey", task.projectKey());
        putIfNotBlank(fields, "projectName", task.projectName());
        putIfNotBlank(fields, "repositoryUrl", task.repositoryUrl());
        putIfNotBlank(fields, "repoOwner", task.repoOwner());
        putIfNotBlank(fields, "repoName", task.repoName());
        putIfNotBlank(fields, "baseBranch", task.baseBranch());
        return Map.copyOf(fields);
    }

    private void putIfNotBlank(Map<String, String> fields, String key, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(key, value.strip());
        }
    }

    private String bugFixDescription(RdBugFixTask task) {
        StringBuilder description = new StringBuilder();
        appendDescriptionLine(description, "任务标题", task.title());
        appendDescriptionLine(description, "工单标题", task.ticketTitle());
        appendDescriptionLine(description, "项目", firstNonBlank(task.projectName(), task.projectKey()));
        appendDescriptionLine(description, "仓库", task.repositoryUrl());
        appendDescriptionLine(description, "基准分支", task.baseBranch());
        appendDescriptionLine(description, "Prompt 快照", task.promptSnapshot());
        String value = description.toString().strip();
        return value.isBlank() ? task.title() : value;
    }

    private void appendDescriptionLine(StringBuilder builder, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(label).append("：").append(value.strip());
    }

    private static TaskMaterialType parseMaterialType(String value) {
        if (value == null || value.isBlank()) {
            return TaskMaterialType.REQUIREMENT_DOC;
        }
        try {
            return TaskMaterialType.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return TaskMaterialType.REQUIREMENT_DOC;
        }
    }

    private static TaskMaterialSourceType parseSourceType(String value) {
        if (value == null || value.isBlank()) {
            return TaskMaterialSourceType.MANUAL_TEXT;
        }
        try {
            return TaskMaterialSourceType.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return TaskMaterialSourceType.MANUAL_TEXT;
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String sha256(byte[] value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value == null ? new byte[0] : value);
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static TaskMaterialView toMaterialView(TaskMaterial material) {
        return new TaskMaterialView(
                material.materialId(),
                material.taskId(),
                material.materialType().name(),
                material.sourceType().name(),
                material.title(),
                material.sourceUri(),
                material.mimeType(),
                material.contentHash(),
                material.contentPreview(),
                material.artifactUri(),
                material.knowledgeDocumentId(),
                material.revisionId(),
                material.metadataJson(),
                material.createTimeEpochMillis(),
                material.updateTimeEpochMillis()
        );
    }

    /** 创建任务请求体。 */
    public record CreateRdTaskRequest(
            String ticketId,
            String ticketTitle,
            String title,
            String priority,
            String promptSnapshot,
            String projectId,
            boolean autoExecute
    ) {
    }

    /** 创建需求任务请求体。 */
    public record CreateRequirementTaskRequest(
            String title,
            String priority,
            String projectId,
            String repositoryUrl,
            String repoOwner,
            String repoName,
            String baseBranch,
            String expectedResult,
            List<String> acceptanceCriteria,
            @JsonAlias({"assertionBundle", "structuredAssertionBundle"}) JsonNode hostAssertionBundle,
            List<RequirementMaterialInput> materials,
            boolean autoExecute,
            long tokenBudgetOverride
    ) {
        public CreateRequirementTaskRequest {
            acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
            hostAssertionBundle = hostAssertionBundle == null || hostAssertionBundle.isNull()
                    ? null
                    : hostAssertionBundle.deepCopy();
            materials = materials == null ? List.of() : List.copyOf(materials);
            if (tokenBudgetOverride < 0L) {
                throw new IllegalArgumentException("tokenBudgetOverride must not be negative");
            }
        }
    }

    /** 需求材料输入。 */
    public record RequirementMaterialInput(
            String materialType,
            String sourceType,
            String title,
            String sourceUri,
            String content,
            String mimeType,
            String revisionId,
            String recoveryStageRunId
    ) {
    }

    /** 修改任务请求体。 */
    public record UpdateRdTaskRequest(
            String title,
            String priority,
            String ticketTitle
    ) {
    }

    /** 暂停 / 恢复动作请求体。 */
    public record RdTaskActionRequest(String message) {
        public RdTaskActionRequest {
            message = message == null ? "" : message;
        }
    }

    /** Immutable, digest- and concurrency-bound request for the host policy-approval transaction. */
    public record ApproveRequirementPolicyRequest(
            String policyRunId,
            long expectedTaskVersion,
            long expectedTaskFence,
            String planDigest,
            String policyDigest,
            String approvalRequestId,
            String decision,
            String note
    ) {
    }

    /** 停止任务响应体。 */
    public record StopRdTaskResponse(
            RdTaskView task,
            boolean stopped,
            String containerName,
            String message
    ) {
    }

    /** Prompt and execution blobs loaded only for delivery/audit views. */
    public record RdTaskAuditContentView(
            String taskId,
            String promptSnapshot,
            String executionResultJson,
            ExecutionEvidenceView executionEvidence
    ) {
        public RdTaskAuditContentView {
            taskId = taskId == null ? "" : taskId.strip();
            promptSnapshot = promptSnapshot == null ? "" : promptSnapshot;
            executionResultJson = executionResultJson == null ? "" : executionResultJson;
            executionEvidence = executionEvidence == null
                    ? new ExecutionEvidenceView("", "", List.of(), "", "", List.of(), "")
                    : executionEvidence;
        }
    }

    /** 任务视图。 */
    public record RdTaskView(
            String taskId,
            String taskType,
            String ticketId,
            String ticketTitle,
            String priority,
            String status,
            String title,
            String promptSnapshot,
            String executionResultJson,
            String pullRequestUrl,
            String errorMessage,
            long createTimeEpochMillis,
            long updateTimeEpochMillis,
            boolean paused,
            String sourceType,
            String sourceId,
            String sourceUrl,
            String projectId,
            String projectKey,
            String projectName,
            String repositoryUrl,
            String repoOwner,
            String repoName,
            String baseBranch,
            String workBranch,
            String expectedResult,
            String acceptanceCriteriaJson,
            JsonNode hostAssertionBundle,
            ExecutionEvidenceView executionEvidence,
            long tokenBudgetOverride
    ) {
        public RdTaskView {
            hostAssertionBundle = hostAssertionBundle == null || hostAssertionBundle.isNull()
                    ? null
                    : hostAssertionBundle.deepCopy();
        }

        public JsonNode hostAssertionBundle() {
            return hostAssertionBundle == null ? null : hostAssertionBundle.deepCopy();
        }
    }

    /** 执行证据摘要。 */
    public record ExecutionEvidenceView(
            String summary,
            String prBody,
            List<String> changedFiles,
            String testStatus,
            String riskLevel,
            List<String> testCommands,
            String pullRequestUrl
    ) {
        public ExecutionEvidenceView {
            summary = summary == null ? "" : summary.strip();
            prBody = prBody == null ? "" : prBody.strip();
            changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
            testStatus = testStatus == null ? "" : testStatus.strip();
            riskLevel = riskLevel == null ? "" : riskLevel.strip();
            testCommands = testCommands == null ? List.of() : List.copyOf(testCommands);
            pullRequestUrl = pullRequestUrl == null ? "" : pullRequestUrl.strip();
        }
    }

    /** 任务分页视图。 */
    public record RdTaskPageView(
            List<RdTaskView> records,
            int page,
            int pageSize,
            long total,
            int pages
    ) {
    }

    /** 状态事件视图。 */
    public record RdTaskStatusEventView(
            String id,
            String taskId,
            String status,
            String title,
            String message,
            long enteredAtEpochMillis,
            long durationMillis,
            String trigger
    ) {
    }

    /** 任务材料视图。 */
    public record TaskMaterialView(
            String materialId,
            String taskId,
            String materialType,
            String sourceType,
            String title,
            String sourceUri,
            String mimeType,
            String contentHash,
            String contentPreview,
            String artifactUri,
            String knowledgeDocumentId,
            String revisionId,
            String metadataJson,
            long createTimeEpochMillis,
            long updateTimeEpochMillis
    ) {
    }

    /** 任务材料预览视图。 */
    public record TaskMaterialPreviewView(
            String materialId,
            String taskId,
            String title,
            String sourceType,
            String sourceUri,
            String mimeType,
            String contentHash,
            String contentPreview
    ) {
    }

    /** 删除结果。 */
    public record DeleteResponse(boolean deleted) {
    }

    /** 任务创建时解析出的项目仓库快照。 */
    private record ProjectSnapshot(
            String projectId,
            String projectKey,
            String projectName,
            String repositoryUrl,
            String repoOwner,
            String repoName,
            String baseBranch
    ) {

        static ProjectSnapshot empty() {
            return new ProjectSnapshot("", "", "", "", "", "", "");
        }
    }
}
