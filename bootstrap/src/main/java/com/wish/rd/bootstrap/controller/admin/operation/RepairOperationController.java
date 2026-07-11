package com.wish.rd.bootstrap.controller.admin.operation;

import com.wish.rd.bootstrap.executor.impl.InMemoryRepairAlertSink;
import com.wish.rd.engine.audit.model.RepairAuditEvent;
import com.wish.rd.engine.audit.model.RepairAuditEventType;
import com.wish.rd.engine.audit.RepairAuditQueryPort;
import com.wish.rd.engine.audit.RepairAuditSinkPort;
import com.wish.rd.engine.ticket.model.RepairQueueDeadLetter;
import com.wish.rd.engine.ticket.RepairQueueDeadLetterRepository;
import com.wish.rd.engine.ticket.model.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.RepairQueuePublisher;
import com.wish.rd.engine.ticket.model.RepairTicketMessage;
import com.wish.rd.exec.repair.alert.model.RepairAlert;
import com.wish.rd.exec.repair.docker.impl.DockerExecutionRegistry;
import com.wish.rd.rag.knowledge.model.KnowledgeRefreshMetric;
import com.wish.rd.rag.knowledge.KnowledgeRefreshMetricQueryPort;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 生产运维 REST 控制器，聚合 P3 所需的任务、告警、审计、死信和执行运行态视图。
 */
@RestController
public class RepairOperationController {

    private final RagStreamTaskRegistry taskRegistry;
    private final InMemoryRepairAlertSink alertSink;
    private final RepairAuditQueryPort auditQueryPort;
    private final RepairAuditSinkPort auditSink;
    private final RepairQueueDeadLetterRepository deadLetterRepository;
    private final RepairQueuePublisher queuePublisher;
    private final DockerExecutionRegistry executionRegistry;
    private final KnowledgeRefreshMetricQueryPort knowledgeRefreshMetricQueryPort;

    public RepairOperationController(
            RagStreamTaskRegistry taskRegistry,
            ObjectProvider<InMemoryRepairAlertSink> alertSinkProvider,
            ObjectProvider<RepairAuditQueryPort> auditQueryProvider,
            ObjectProvider<RepairAuditSinkPort> auditSinkProvider,
            ObjectProvider<RepairQueueDeadLetterRepository> deadLetterRepositoryProvider,
            ObjectProvider<RepairQueuePublisher> queuePublisherProvider,
            ObjectProvider<DockerExecutionRegistry> executionRegistryProvider,
            ObjectProvider<KnowledgeRefreshMetricQueryPort> knowledgeRefreshMetricQueryProvider
    ) {
        this.taskRegistry = taskRegistry;
        this.alertSink = alertSinkProvider.getIfAvailable();
        this.auditQueryPort = auditQueryProvider.getIfAvailable();
        this.auditSink = auditSinkProvider.getIfAvailable(() -> event -> {
        });
        this.deadLetterRepository = deadLetterRepositoryProvider.getIfAvailable(RepairQueueDeadLetterRepository::noop);
        this.queuePublisher = queuePublisherProvider.getIfAvailable(() -> message ->
                RepairQueuePublishResult.failure("", message == null ? "" : message.tag(), "queue publisher unavailable"));
        this.executionRegistry = executionRegistryProvider.getIfAvailable(DockerExecutionRegistry::noop);
        this.knowledgeRefreshMetricQueryPort = knowledgeRefreshMetricQueryProvider.getIfAvailable();
    }

    /**
     * 查询运维总览。
     *
     * @return 运维总览
     */
    @GetMapping("/admin/operations/overview")
    public OperationOverviewView overview() {
        List<RdBugFixTask> tasks = taskRegistry.listBugFixTasks();
        return new OperationOverviewView(
                count(tasks, RdTaskStatus.CREATED),
                count(tasks, RdTaskStatus.SEARCHING) + count(tasks, RdTaskStatus.EXECUTING),
                count(tasks, RdTaskStatus.REJECTED),
                count(tasks, RdTaskStatus.COMMITTED),
                deadLetterRepository.list().stream().filter(deadLetter -> !deadLetter.replayed()).count(),
                alerts().size(),
                executionRegistry.runningExecutions().size()
        );
    }

    /**
     * 查询当前告警列表。
     *
     * @return 告警列表
     */
    @GetMapping("/admin/operations/alerts")
    public List<RepairAlertView> alerts() {
        if (alertSink == null) {
            return List.of();
        }
        return alertSink.alerts().stream()
                .map(RepairOperationController::toAlertView)
                .toList();
    }

    /**
     * 查询审计事件。
     *
     * @param repairRecordId 修复记录 ID，可选
     * @return 审计事件列表
     */
    @GetMapping("/admin/operations/audit-events")
    public List<RepairAuditEventView> auditEvents(
            @RequestParam(value = "repairRecordId", required = false) String repairRecordId,
            @RequestParam(value = "taskId", required = false) String taskId
    ) {
        if (auditQueryPort == null) {
            return List.of();
        }
        boolean hasRepairRecordId = repairRecordId != null && !repairRecordId.isBlank();
        boolean hasTaskId = taskId != null && !taskId.isBlank();
        if (hasRepairRecordId && hasTaskId) {
            throw new IllegalArgumentException("repairRecordId and taskId cannot be used together");
        }
        List<RepairAuditEvent> events = hasTaskId
                ? auditQueryPort.eventsByTaskId(taskId)
                : hasRepairRecordId
                        ? auditQueryPort.eventsByRepairRecordId(repairRecordId)
                        : auditQueryPort.events();
        return events.stream()
                .map(RepairOperationController::toAuditView)
                .toList();
    }

    /**
     * 查询死信列表。
     *
     * @return 死信列表
     */
    @GetMapping("/admin/operations/dead-letters")
    public List<RepairQueueDeadLetterView> deadLetters() {
        return deadLetterRepository.list().stream()
                .map(RepairOperationController::toDeadLetterView)
                .toList();
    }

    /**
     * 人工重投死信。
     *
     * @param id 死信 ID
     * @return 重投结果
     */
    @PostMapping("/admin/operations/dead-letters/{id}/replay")
    public DeadLetterReplayView replayDeadLetter(@PathVariable("id") String id) {
        RepairQueueDeadLetter deadLetter = deadLetterRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("dead letter not found: " + id));
        if (deadLetter.replayed()) {
            throw new IllegalStateException("dead letter already replayed: " + id);
        }
        RepairTicketMessage message = toReplayMessage(deadLetter);
        RepairQueuePublishResult publishResult = queuePublisher.publish(message);
        if (!publishResult.success()) {
            return new DeadLetterReplayView(
                    toDeadLetterView(deadLetter),
                    false,
                    publishResult.messageId(),
                    publishResult.errorMessage()
            );
        }
        RepairQueueDeadLetter updated = deadLetterRepository.markReplayed(id);
        auditSink.publish(RepairAuditEvent.now(
                "",
                "",
                deadLetter.ticketId(),
                RepairAuditEventType.MANUAL_RECOVERY_REQUESTED,
                "RocketMQ",
                "dead letter replay requested",
                Map.of("deadLetterId", id, "publishSuccess", String.valueOf(publishResult.success()))
        ));
        return new DeadLetterReplayView(toDeadLetterView(updated), publishResult.success(), publishResult.messageId(), publishResult.errorMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }

    /**
     * 查询运行中的 Docker 执行。
     *
     * @return 运行态列表
     */
    @GetMapping("/admin/operations/running-executions")
    public List<RunningExecutionView> runningExecutions() {
        return executionRegistry.runningExecutions().stream()
                .map(RepairOperationController::toRunningExecutionView)
                .toList();
    }

    /**
     * 查询知识刷新健康概览。
     *
     * @return 知识刷新概览
     */
    @GetMapping("/admin/operations/knowledge-refresh")
    public KnowledgeRefreshOperationView knowledgeRefresh() {
        if (knowledgeRefreshMetricQueryPort == null) {
            return new KnowledgeRefreshOperationView(0L, 0.0D, List.of(), List.of());
        }
        List<KnowledgeRefreshMetric> metrics = knowledgeRefreshMetricQueryPort.metrics();
        if (metrics.isEmpty()) {
            return new KnowledgeRefreshOperationView(0L, 0.0D, List.of(), List.of());
        }
        long now = System.currentTimeMillis();
        long latestMetricAt = metrics.stream()
                .mapToLong(KnowledgeRefreshMetric::createdAtEpochMillis)
                .max()
                .orElse(now);
        double successRate = metrics.stream().filter(KnowledgeRefreshMetric::success).count() * 1.0D / metrics.size();
        List<String> topFailureSources = metrics.stream()
                .filter(metric -> !metric.success())
                .map(metric -> metric.sourceType() + ":" + metric.sourceName())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet()
                .stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(5)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList();
        List<String> chunkCountChanges = metrics.stream()
                .sorted(Comparator.comparingLong(KnowledgeRefreshMetric::createdAtEpochMillis).reversed())
                .limit(20)
                .map(metric -> metric.sourceName() + ":" + metric.oldChunkCount() + "->" + metric.newChunkCount())
                .toList();
        return new KnowledgeRefreshOperationView(now - latestMetricAt, successRate, topFailureSources, chunkCountChanges);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
    }

    private static long count(List<RdBugFixTask> tasks, RdTaskStatus status) {
        return tasks.stream().filter(task -> task.status() == status).count();
    }

    private static RepairAlertView toAlertView(RepairAlert alert) {
        return new RepairAlertView(
                alert.repairRecordId(),
                alert.taskId(),
                alert.type().name(),
                preview(alert.message(), 200),
                alert.metadata(),
                alert.createdAtEpochMillis()
        );
    }

    private static RepairAuditEventView toAuditView(RepairAuditEvent event) {
        return new RepairAuditEventView(
                event.repairRecordId(),
                event.taskId(),
                event.ticketId(),
                event.type().name(),
                event.externalSystem(),
                preview(event.summary(), 200),
                event.metadata(),
                event.createdAtEpochMillis()
        );
    }

    private static RepairQueueDeadLetterView toDeadLetterView(RepairQueueDeadLetter deadLetter) {
        return new RepairQueueDeadLetterView(
                deadLetter.id(),
                deadLetter.ticketId(),
                deadLetter.traceId(),
                deadLetter.source(),
                deadLetter.eventId(),
                deadLetter.eventType(),
                deadLetter.originalAttempt(),
                preview(deadLetter.reason(), 200),
                deadLetter.replayed(),
                deadLetter.createdAtEpochMillis(),
                deadLetter.replayedAtEpochMillis()
        );
    }

    private static RunningExecutionView toRunningExecutionView(DockerExecutionRegistry.RunningExecution execution) {
        return new RunningExecutionView(
                execution.repairRecordId(),
                execution.taskId(),
                execution.ticketId(),
                execution.provider(),
                execution.containerName(),
                execution.startedAtEpochMillis(),
                execution.lastHeartbeatEpochMillis(),
                execution.outputDirectory() == null ? "" : execution.outputDirectory().toString()
        );
    }

    private static RepairTicketMessage toReplayMessage(RepairQueueDeadLetter deadLetter) {
        return new RepairTicketMessage(
                deadLetter.ticketId(),
                deadLetter.messageJson().getOrDefault("priority", "P2"),
                deadLetter.traceId(),
                RepairTicketMessage.FIRST_ATTEMPT,
                deadLetter.source(),
                deadLetter.eventId(),
                deadLetter.eventType(),
                Instant.now()
        );
    }

    private static String preview(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...";
    }

    public record OperationOverviewView(
            long pending,
            long running,
            long failed,
            long waitingCr,
            long deadLettered,
            long activeAlerts,
            long runningExecutions
    ) {
    }

    public record RepairAlertView(
            String repairRecordId,
            String taskId,
            String type,
            String message,
            Map<String, String> metadata,
            long createdAtEpochMillis
    ) {
    }

    public record RepairAuditEventView(
            String repairRecordId,
            String taskId,
            String ticketId,
            String type,
            String externalSystem,
            String summary,
            Map<String, String> metadata,
            long createdAtEpochMillis
    ) {
    }

    public record RepairQueueDeadLetterView(
            String id,
            String ticketId,
            String traceId,
            String source,
            String eventId,
            String eventType,
            int originalAttempt,
            String reason,
            boolean replayed,
            long createdAtEpochMillis,
            long replayedAtEpochMillis
    ) {
    }

    public record DeadLetterReplayView(
            RepairQueueDeadLetterView deadLetter,
            boolean publishSuccess,
            String messageId,
            String errorMessage
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
            String outputDirectory
    ) {
    }

    public record KnowledgeRefreshOperationView(
            long latestDelayMillis,
            double successRate,
            List<String> topFailureSources,
            List<String> chunkCountChanges
    ) {
    }
}
