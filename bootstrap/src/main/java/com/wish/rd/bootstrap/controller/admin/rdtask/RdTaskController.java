package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.wish.rd.rag.runtime.RdBugFixTask;
import com.wish.rd.rag.runtime.RdTaskPage;
import com.wish.rd.rag.runtime.RdTaskQuery;
import com.wish.rd.rag.runtime.RdTaskStatus;
import com.wish.rd.rag.runtime.RdTaskStatusEvent;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

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

    /** 列表视图中文本字段的截断长度，避免大块 prompt/结果 JSON 透出列表。 */
    private static final int PREVIEW_MAX_CHARS = 120;

    private final RagStreamTaskRegistry registry;

    public RdTaskController(RagStreamTaskRegistry registry) {
        this.registry = registry;
    }

    /**
     * 分页查询任务。
     *
     * @param status   状态过滤
     * @param priority 优先级过滤
     * @param ticketId 工单 ID 子串
     * @param keyword  关键词（匹配标题 / 工单标题 / 工单 ID）
     * @param page     页码（默认 1）
     * @param pageSize 每页大小（默认 20）
     * @return 分页结果
     */
    @GetMapping("/admin/rd-tasks")
    public RdTaskPageView list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "priority", required = false) String priority,
            @RequestParam(value = "ticketId", required = false) String ticketId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize
    ) {
        RdTaskQuery query = new RdTaskQuery(status, priority, ticketId, keyword, page, pageSize);
        RdTaskPage result = registry.queryBugFixTasks(query);
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
    @GetMapping("/admin/rd-tasks/{taskId}")
    public RdTaskView get(@PathVariable("taskId") String taskId) {
        return toDetailView(registry.get(taskId));
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
        RdBugFixTask task = registry.createTaskManually(
                request.ticketId(),
                request.ticketTitle(),
                request.title(),
                request.priority(),
                request.promptSnapshot()
        );
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
        return toDetailView(registry.updateTask(taskId, request.title(), request.priority(), request.ticketTitle()));
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
        return toDetailView(registry.pause(taskId, message));
    }

    /**
     * 恢复任务。
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
        return toDetailView(registry.resume(taskId, message));
    }

    /**
     * 逻辑删除任务。
     *
     * @param taskId 任务 ID
     * @return 删除结果
     */
    @DeleteMapping("/admin/rd-tasks/{taskId}")
    public DeleteResponse delete(@PathVariable("taskId") String taskId) {
        return new DeleteResponse(registry.deleteTask(taskId));
    }

    /**
     * 查询任务全链路状态事件时间线。
     *
     * @param taskId 任务 ID
     * @return 状态事件视图列表（升序）
     */
    @GetMapping("/admin/rd-tasks/{taskId}/timeline")
    public List<RdTaskStatusEventView> timeline(@PathVariable("taskId") String taskId) {
        return registry.timeline(taskId).stream()
                .map(RdTaskController::toEventView)
                .toList();
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

    private static RdTaskView toListView(RdBugFixTask task) {
        return new RdTaskView(
                task.taskId(),
                task.taskType(),
                task.ticketId(),
                task.ticketTitle(),
                task.priority(),
                task.status().name(),
                task.title(),
                preview(task.promptSnapshot()),
                preview(task.executionResultJson()),
                task.pullRequestUrl(),
                preview(task.errorMessage(), 200),
                task.createTimeEpochMillis(),
                task.updateTimeEpochMillis(),
                task.paused()
        );
    }

    private static RdTaskView toDetailView(RdBugFixTask task) {
        return new RdTaskView(
                task.taskId(),
                task.taskType(),
                task.ticketId(),
                task.ticketTitle(),
                task.priority(),
                task.status().name(),
                task.title(),
                task.promptSnapshot(),
                task.executionResultJson(),
                task.pullRequestUrl(),
                task.errorMessage(),
                task.createTimeEpochMillis(),
                task.updateTimeEpochMillis(),
                task.paused()
        );
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

    /** 创建任务请求体。 */
    public record CreateRdTaskRequest(
            String ticketId,
            String ticketTitle,
            String title,
            String priority,
            String promptSnapshot
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
            boolean paused
    ) {
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

    /** 删除结果。 */
    public record DeleteResponse(boolean deleted) {
    }
}
