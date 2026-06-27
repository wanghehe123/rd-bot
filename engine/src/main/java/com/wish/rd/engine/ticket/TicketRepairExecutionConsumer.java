package com.wish.rd.engine.ticket;

import com.wish.rd.adapter.TicketMessageQuery;
import com.wish.rd.adapter.TicketMessages;
import com.wish.rd.adapter.TicketProviderPort;
import com.wish.rd.adapter.TicketReplyCommand;
import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.adapter.TicketUpdatePort;
import com.wish.rd.adapter.TicketUpdateResult;
import com.wish.rd.engine.bugfix.RdBotFixCommand;
import com.wish.rd.engine.bugfix.RdBotFixEngine;
import com.wish.rd.engine.bugfix.RdBotFixResult;
import com.wish.rd.engine.audit.NoopRepairAuditSink;
import com.wish.rd.engine.audit.RepairAuditEvent;
import com.wish.rd.engine.audit.RepairAuditEventType;
import com.wish.rd.engine.audit.RepairAuditSinkPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 正式修复队列消费者：先复用工单 RAG 编排，再按配置触发完整 BugFix 执行链路。
 *
 * <p>{@link TicketRepairEngine} 仍然只负责工单读取、决策、RAG 上下文和修复记录产物；
 * 本类作为上游队列消费编排器，负责在 {@code rd.repair.ticket.auto-execute.enabled=true}
 * 时把已就绪工单交给 {@link RdBotFixEngine} 继续执行 Docker/PR 流程。
 */
@Service
@Primary
public class TicketRepairExecutionConsumer implements RepairQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(TicketRepairExecutionConsumer.class);

    private final TicketRepairEngine ticketRepairEngine;
    private final TicketProviderPort providerPort;
    private final RdBotFixEngine fixEngine;
    private final TicketFieldMapping fieldMapping;
    private final boolean autoExecuteEnabled;
    private final TicketUpdatePort updatePort;
    private final boolean writeBackEnabled;
    private final RepairRecordRepository recordRepository;
    private final RepairAuditSinkPort auditSink;
    private final RepairQueueDeadLetterRepository deadLetterRepository;
    private final int maxRetryAttempts;

    /**
     * 创建正式修复队列消费者。
     *
     * @param ticketRepairEngine RAG 工单编排引擎
     * @param providerPort       工单读取端口
     * @param fixEngine          BugFix 完整执行引擎
     * @param fieldMapping       工单字段映射器
     * @param autoExecuteEnabled 是否在 RAG 上下文就绪后继续触发执行器
     */
    @Autowired
    public TicketRepairExecutionConsumer(
            TicketRepairEngine ticketRepairEngine,
            TicketProviderPort providerPort,
            RdBotFixEngine fixEngine,
            ObjectProvider<TicketFieldMapping> fieldMapping,
            @Value("${rd.repair.ticket.auto-execute.enabled:false}") boolean autoExecuteEnabled,
            ObjectProvider<TicketUpdatePort> updatePort,
            ObjectProvider<RepairRecordRepository> recordRepository,
            ObjectProvider<RepairAuditSinkPort> auditSinkProvider,
            ObjectProvider<RepairQueueDeadLetterRepository> deadLetterRepositoryProvider,
            @Value("${rd.rocketmq.repair.max-retry-attempts:3}") int maxRetryAttempts,
            @Value("#{${rd.ticket.write-back.enabled:false} || ${rd.feishu.im.write-back.enabled:false} || "
                    + "${rd.feishu.helpdesk.write-back.enabled:false}}")
            boolean writeBackEnabled
    ) {
        this(
                ticketRepairEngine,
                providerPort,
                fixEngine,
                fieldMapping.getIfAvailable(TicketFieldMapping::defaults),
                autoExecuteEnabled,
                updatePort.getIfAvailable(),
                recordRepository.getIfAvailable(),
                writeBackEnabled,
                auditSinkProvider.getIfAvailable(NoopRepairAuditSink::instance),
                deadLetterRepositoryProvider.getIfAvailable(RepairQueueDeadLetterRepository::noop),
                maxRetryAttempts
        );
    }

    public TicketRepairExecutionConsumer(
            TicketRepairEngine ticketRepairEngine,
            TicketProviderPort providerPort,
            RdBotFixEngine fixEngine,
            TicketFieldMapping fieldMapping,
            boolean autoExecuteEnabled
    ) {
        this(ticketRepairEngine, providerPort, fixEngine, fieldMapping, autoExecuteEnabled, null, null, false);
    }

    public TicketRepairExecutionConsumer(
            TicketRepairEngine ticketRepairEngine,
            TicketProviderPort providerPort,
            RdBotFixEngine fixEngine,
            TicketFieldMapping fieldMapping,
            boolean autoExecuteEnabled,
            TicketUpdatePort updatePort,
            boolean writeBackEnabled
    ) {
        this(ticketRepairEngine, providerPort, fixEngine, fieldMapping, autoExecuteEnabled, updatePort, null, writeBackEnabled);
    }

    public TicketRepairExecutionConsumer(
            TicketRepairEngine ticketRepairEngine,
            TicketProviderPort providerPort,
            RdBotFixEngine fixEngine,
            TicketFieldMapping fieldMapping,
            boolean autoExecuteEnabled,
            TicketUpdatePort updatePort,
            RepairRecordRepository recordRepository,
            boolean writeBackEnabled
    ) {
        this(
                ticketRepairEngine,
                providerPort,
                fixEngine,
                fieldMapping,
                autoExecuteEnabled,
                updatePort,
                recordRepository,
                writeBackEnabled,
                NoopRepairAuditSink.instance(),
                RepairQueueDeadLetterRepository.noop(),
                3
        );
    }

    public TicketRepairExecutionConsumer(
            TicketRepairEngine ticketRepairEngine,
            TicketProviderPort providerPort,
            RdBotFixEngine fixEngine,
            TicketFieldMapping fieldMapping,
            boolean autoExecuteEnabled,
            TicketUpdatePort updatePort,
            RepairRecordRepository recordRepository,
            boolean writeBackEnabled,
            RepairAuditSinkPort auditSink,
            RepairQueueDeadLetterRepository deadLetterRepository,
            int maxRetryAttempts
    ) {
        this.ticketRepairEngine = ticketRepairEngine;
        this.providerPort = providerPort;
        this.fixEngine = fixEngine;
        this.fieldMapping = fieldMapping == null ? TicketFieldMapping.defaults() : fieldMapping;
        this.autoExecuteEnabled = autoExecuteEnabled;
        this.updatePort = updatePort;
        this.recordRepository = recordRepository;
        this.writeBackEnabled = writeBackEnabled;
        this.auditSink = auditSink == null ? NoopRepairAuditSink.instance() : auditSink;
        this.deadLetterRepository = deadLetterRepository == null
                ? RepairQueueDeadLetterRepository.noop()
                : deadLetterRepository;
        this.maxRetryAttempts = Math.max(1, maxRetryAttempts);
    }

    @Override
    public boolean handle(RepairTicketMessage message) {
        if (message == null) {
            return false;
        }
        if (message.attempt() > maxRetryAttempts) {
            RepairQueueDeadLetter deadLetter = deadLetterRepository.save(
                    message,
                    "repair queue retry attempts exceeded: " + maxRetryAttempts
            );
            audit(
                    "",
                    "",
                    message.ticketId(),
                    RepairAuditEventType.QUEUE_DEAD_LETTERED,
                    "RocketMQ",
                    "repair queue message moved to dead letter",
                    Map.of("deadLetterId", deadLetter.id(), "attempt", Integer.toString(message.attempt()))
            );
            return true;
        }
        TicketRepairEngine.RepairOutcome outcome = ticketRepairEngine.process(message);
        if (outcome.decision() == TicketRepairDecision.FAILED) {
            return false;
        }
        if (outcome.decision() != TicketRepairDecision.READY_FOR_RAG || !autoExecuteEnabled) {
            return true;
        }
        return executeReadyTicket(message, outcome);
    }

    private boolean executeReadyTicket(RepairTicketMessage message, TicketRepairEngine.RepairOutcome outcome) {
        Optional<TicketSnapshot> snapshotOpt = providerPort.findTicket(message.ticketId());
        if (snapshotOpt.isEmpty()) {
            log.warn("auto execute skipped because ticket disappeared, ticketId={}", message.ticketId());
            return false;
        }
        TicketSnapshot snapshot = snapshotOpt.get();
        audit(
                outcome.repairRecordId(),
                "",
                message.ticketId(),
                RepairAuditEventType.EXECUTION_STARTED,
                "Docker",
                "auto repair execution started",
                Map.of("priority", message.priority())
        );
        RdBotFixResult result = fixEngine.runBugFix(new RdBotFixCommand(
                snapshot,
                extractLogs(snapshot),
                false,
                message.priority()
        ));
        log.info(
                "auto repair execution finished, ticketId={}, taskId={}, status={}, rejected={}",
                message.ticketId(),
                result.taskId(),
                result.status(),
                result.rejected()
        );
        audit(
                outcome.repairRecordId(),
                result.taskId(),
                message.ticketId(),
                RepairAuditEventType.EXECUTION_FINISHED,
                "Docker",
                "auto repair execution finished",
                Map.of("status", result.status().name(), "rejected", String.valueOf(result.rejected()))
        );
        syncRepairRecordStatus(outcome, result);
        writeBackExecutionResult(message, outcome, result);
        return !result.rejected();
    }

    private void syncRepairRecordStatus(TicketRepairEngine.RepairOutcome outcome, RdBotFixResult result) {
        if (recordRepository == null || outcome == null || outcome.repairRecordId().isBlank() || result == null) {
            return;
        }
        RepairRecordStatus status = switch (result.status()) {
            case COMMITTED -> RepairRecordStatus.COMMITTED;
            case MERGED -> RepairRecordStatus.MERGED;
            case REJECTED -> RepairRecordStatus.FAILED;
            default -> null;
        };
        if (status == null) {
            return;
        }
        recordRepository.updateStatus(outcome.repairRecordId(), status, repairRecordSummary(status, result));
    }

    private String repairRecordSummary(RepairRecordStatus status, RdBotFixResult result) {
        String pullRequestUrl = result.executionResult().pullRequestUrl();
        return switch (status) {
            case COMMITTED -> "auto repair committed: " + pullRequestUrl;
            case MERGED -> "pull request merged: " + pullRequestUrl;
            case FAILED -> result.executionResult().resultJson().isBlank()
                    ? "auto repair failed: " + result.status().name()
                    : result.executionResult().resultJson();
            default -> result.status().name();
        };
    }

    private void writeBackExecutionResult(
            RepairTicketMessage message,
            TicketRepairEngine.RepairOutcome outcome,
            RdBotFixResult result
    ) {
        if (!writeBackEnabled || updatePort == null) {
            return;
        }
        String pullRequestUrl = result.executionResult().pullRequestUrl();
        String content = """
                RD-Bot 自动修复已完成
                内部工单：%s
                RAG 状态：CONTEXT_READY
                执行状态：%s
                任务ID：%s
                PR：%s
                """.formatted(
                message.ticketId(),
                result.status().name(),
                result.taskId(),
                pullRequestUrl.isBlank() ? "未生成" : pullRequestUrl
        ).strip();
        TicketReplyCommand reply = new TicketReplyCommand(
                message.ticketId(),
                "text",
                content,
                List.of(),
                Map.of("taskId", result.taskId(), "executionStatus", result.status().name()),
                message.traceId(),
                outcome.repairRecordId()
        );
        TicketUpdateResult updateResult = updatePort.sendMessage(reply);
        log.info("wrote back auto repair result, ticketId={}, taskId={}, success={}",
                message.ticketId(), result.taskId(), updateResult.success());
        audit(
                outcome.repairRecordId(),
                result.taskId(),
                message.ticketId(),
                RepairAuditEventType.TICKET_WRITE_BACK_FINISHED,
                "Feishu",
                "wrote back auto repair execution result",
                Map.of("success", String.valueOf(updateResult.success()), "executionStatus", result.status().name())
        );
    }

    private List<String> extractLogs(TicketSnapshot snapshot) {
        List<String> logs = new ArrayList<>();
        String customLogs = fieldMapping.extractLogs(snapshot);
        if (!customLogs.isBlank()) {
            logs.add(customLogs);
        }
        TicketMessages messages = providerPort.findMessages(snapshot.ticketId(), TicketMessageQuery.defaults());
        messages.messages().stream()
                .filter(com.wish.rd.adapter.TicketMessage::isFromUser)
                .map(com.wish.rd.adapter.TicketMessage::content)
                .filter(content -> !content.isBlank())
                .forEach(logs::add);
        return List.copyOf(logs);
    }

    private void audit(
            String repairRecordId,
            String taskId,
            String ticketId,
            RepairAuditEventType type,
            String externalSystem,
            String summary,
            Map<String, String> metadata
    ) {
        auditSink.publish(RepairAuditEvent.now(
                repairRecordId,
                taskId,
                ticketId,
                type,
                externalSystem,
                summary,
                metadata
        ));
    }
}
