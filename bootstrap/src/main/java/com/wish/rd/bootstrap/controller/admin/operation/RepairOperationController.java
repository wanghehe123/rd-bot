package com.wish.rd.bootstrap.controller.admin.operation;

import com.wish.rd.bootstrap.executor.impl.InMemoryRepairAlertSink;
import com.wish.rd.engine.audit.model.RepairAuditEvent;
import com.wish.rd.engine.audit.RepairAuditQueryPort;
import com.wish.rd.exec.repair.alert.model.RepairAlert;
import com.wish.rd.exec.repair.docker.impl.DockerExecutionRegistry;
import com.wish.rd.rag.knowledge.model.KnowledgeRefreshMetric;
import com.wish.rd.rag.knowledge.KnowledgeRefreshMetricQueryPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 生产运维 REST 控制器，聚合告警、审计、执行运行态与知识刷新健康视图。
 */
@RestController
public class RepairOperationController {

    private final InMemoryRepairAlertSink alertSink;
    private final RepairAuditQueryPort auditQueryPort;
    private final DockerExecutionRegistry executionRegistry;
    private final KnowledgeRefreshMetricQueryPort knowledgeRefreshMetricQueryPort;

    public RepairOperationController(
            ObjectProvider<InMemoryRepairAlertSink> alertSinkProvider,
            ObjectProvider<RepairAuditQueryPort> auditQueryProvider,
            ObjectProvider<DockerExecutionRegistry> executionRegistryProvider,
            ObjectProvider<KnowledgeRefreshMetricQueryPort> knowledgeRefreshMetricQueryProvider
    ) {
        this.alertSink = alertSinkProvider.getIfAvailable();
        this.auditQueryPort = auditQueryProvider.getIfAvailable();
        this.executionRegistry = executionRegistryProvider.getIfAvailable(DockerExecutionRegistry::noop);
        this.knowledgeRefreshMetricQueryPort = knowledgeRefreshMetricQueryProvider.getIfAvailable();
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

    private static String preview(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...";
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
