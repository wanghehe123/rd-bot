package com.wish.rd.engine.ticket;

import com.wish.rd.adapter.TicketMessageQuery;
import com.wish.rd.adapter.TicketMessages;
import com.wish.rd.adapter.TicketProviderPort;
import com.wish.rd.adapter.TicketReplyCommand;
import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.adapter.TicketUpdatePort;
import com.wish.rd.adapter.TicketUpdateResult;
import com.wish.rd.engine.rag.BugFixMessage;
import com.wish.rd.engine.rag.RagBugFixEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工单修复消费编排引擎：P1 用例编排器。
 *
 * <p>消费 {@link RepairTicketMessage} 后，按以下步骤编排：
 * <ol>
 *   <li>通过 {@link TicketProviderPort} 重新拉取最新工单快照与消息（事件字段不可信为最终态）。</li>
 *   <li>{@link TicketRepairDecision} 决策：{@code IGNORED_CLOSED}/{@code WAITING_FOR_INFO}/{@code READY_FOR_RAG}/{@code FAILED}。</li>
 *   <li>创建或复用 {@link RepairRecord}，状态推进 {@code QUEUED -> CONTEXT_COLLECTING -> CONTEXT_READY}。</li>
 *   <li>调用 {@link RagBugFixEngine} 构建任务级 RAG 上下文，写入 {@code rag_summary} 与产物。</li>
 *   <li>可选：通过 {@link TicketUpdatePort} 向用户回写（由配置开关）。</li>
 * </ol>
 *
 * <p>关键约束（AGENTS.md）：
 * <ul>
 *   <li>{@link RagBugFixEngine} 仅构建 RAG 上下文，不触发 Agent 执行；本类也不直接 submit Agent。</li>
 *   <li>不加载/写入会话记忆；任务级上下文，非会话级。</li>
 *   <li>原始工单/事件 JSON 作为产物持久化，敏感字段需脱敏。</li>
 * </ul>
 */
@Service
public class TicketRepairEngine implements RepairQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(TicketRepairEngine.class);

    /** 产物类型常量，对齐 P1 规范。 */
    public static final String ARTIFACT_FEISHU_EVENT = "FEISHU_EVENT";
    public static final String ARTIFACT_FEISHU_TICKET_SNAPSHOT = "FEISHU_TICKET_SNAPSHOT";
    public static final String ARTIFACT_FEISHU_MESSAGES = "FEISHU_MESSAGES";
    public static final String ARTIFACT_RAG_CONTEXT = "RAG_CONTEXT";

    private final TicketProviderPort providerPort;
    private final TicketUpdatePort updatePort;
    private final RepairRecordRepository recordRepository;
    private final RagBugFixEngine ragBugFixEngine;
    private final TicketFieldMapping fieldMapping;
    private final boolean writeBackEnabled;

    public TicketRepairEngine(
            TicketProviderPort providerPort,
            RepairRecordRepository recordRepository,
            RagBugFixEngine ragBugFixEngine,
            @Value("${rd.ticket.write-back.enabled:${rd.feishu.helpdesk.write-back.enabled:false}}") boolean writeBackEnabled
    ) {
        this(providerPort, null, recordRepository, ragBugFixEngine, TicketFieldMapping.defaults(), writeBackEnabled);
    }

    @Autowired
    public TicketRepairEngine(
            TicketProviderPort providerPort,
            ObjectProvider<TicketUpdatePort> updatePortProvider,
            RepairRecordRepository recordRepository,
            RagBugFixEngine ragBugFixEngine,
            ObjectProvider<TicketFieldMapping> fieldMappingProvider,
            @Value("${rd.ticket.write-back.enabled:${rd.feishu.helpdesk.write-back.enabled:false}}") boolean writeBackEnabled
    ) {
        this(
                providerPort,
                updatePortProvider.getIfAvailable(),
                recordRepository,
                ragBugFixEngine,
                fieldMappingProvider.getIfAvailable(TicketFieldMapping::defaults),
                writeBackEnabled
        );
    }

    TicketRepairEngine(
            TicketProviderPort providerPort,
            TicketUpdatePort updatePort,
            RepairRecordRepository recordRepository,
            RagBugFixEngine ragBugFixEngine,
            TicketFieldMapping fieldMapping,
            boolean writeBackEnabled
    ) {
        this.providerPort = providerPort;
        this.updatePort = updatePort;
        this.recordRepository = recordRepository;
        this.ragBugFixEngine = ragBugFixEngine;
        this.fieldMapping = fieldMapping;
        this.writeBackEnabled = writeBackEnabled;
    }

    /**
     * 测试/直接装配入口。
     *
     * @param providerPort     工单读取端口
     * @param updatePort       工单写入端口（可为 null）
     * @param recordRepository 修复记录仓储
     * @param ragBugFixEngine  RAG 上下文引擎
     * @param fieldMapping     字段映射器
     * @param writeBackEnabled 是否开启回写
     * @return 消费编排引擎实例
     */
    public static TicketRepairEngine forTesting(
            TicketProviderPort providerPort,
            TicketUpdatePort updatePort,
            RepairRecordRepository recordRepository,
            RagBugFixEngine ragBugFixEngine,
            TicketFieldMapping fieldMapping,
            boolean writeBackEnabled
    ) {
        return new TicketRepairEngine(providerPort, updatePort, recordRepository, ragBugFixEngine, fieldMapping, writeBackEnabled);
    }

    @Override
    public boolean handle(RepairTicketMessage message) {
        if (message == null) {
            return false;
        }
        String ticketId = message.ticketId();
        log.info("consuming repair ticket message, ticketId={}, attempt={}, traceId={}",
                ticketId, message.attempt(), message.traceId());
        try {
            RepairOutcome outcome = process(message);
            log.info("repair ticket consumed, ticketId={}, decision={}, recordId={}",
                    ticketId, outcome.decision(), outcome.repairRecordId());
            return outcome.decision() != TicketRepairDecision.FAILED;
        } catch (Exception exception) {
            log.error("repair ticket consumption failed, ticketId=" + ticketId, exception);
            markFailed(ticketId, message, redact(exception.getMessage()));
            return false;
        }
    }

    /**
     * 处理一条消息，返回决策与修复记录 ID。
     *
     * @param message 队列消息
     * @return 处理结果
     */
    public RepairOutcome process(RepairTicketMessage message) {
        String ticketId = message.ticketId();

        // 持久化事件元数据作为产物（在 record 创建之后回填）
        Optional<TicketSnapshot> snapshotOpt = providerPort.findTicket(ticketId);
        if (snapshotOpt.isEmpty()) {
            RepairRecord failed = ensureRecord(message, "missing ticket: " + ticketId, RepairRecordStatus.FAILED);
            return new RepairOutcome(TicketRepairDecision.FAILED, failed.id(), "");
        }
        TicketSnapshot snapshot = snapshotOpt.get();
        RepairRecord record = ensureRecord(message, snapshot.title(), RepairRecordStatus.CONTEXT_COLLECTING);
        persistEventArtifact(message, record.id());
        persistTicketSnapshotArtifact(snapshot, record.id());

        TicketRepairDecision decision = decide(snapshot);
        return switch (decision) {
            case IGNORED_CLOSED -> {
                RepairRecord updated = recordRepository.updateStatus(
                        record.id(), RepairRecordStatus.COMPLETED, "ticket closed, ignored"
                );
                yield new RepairOutcome(decision, updated.id(), "");
            }
            case WAITING_FOR_INFO -> {
                RepairRecord updated = recordRepository.updateStatus(
                        record.id(), RepairRecordStatus.WAITING_FOR_INFO, "waiting for user info"
                );
                askForMissingInfo(snapshot, message);
                yield new RepairOutcome(decision, updated.id(), "");
            }
            case READY_FOR_RAG -> prepareRagContext(snapshot, message, record);
            case FAILED -> {
                RepairRecord updated = recordRepository.updateStatus(
                        record.id(), RepairRecordStatus.FAILED, "decision failed"
                );
                yield new RepairOutcome(decision, updated.id(), "");
            }
        };
    }

    private TicketRepairDecision decide(TicketSnapshot snapshot) {
        if (snapshot.isClosed()) {
            return TicketRepairDecision.IGNORED_CLOSED;
        }
        if (!fieldMapping.hasEnoughInfo(snapshot)) {
            return TicketRepairDecision.WAITING_FOR_INFO;
        }
        return TicketRepairDecision.READY_FOR_RAG;
    }

    private RepairOutcome prepareRagContext(TicketSnapshot snapshot, RepairTicketMessage message, RepairRecord record) {
        TicketMessages messages = providerPort.findMessages(record.ticketId(), TicketMessageQuery.defaults());
        persistMessagesArtifact(messages, record.id());

        List<String> logs = extractLogs(snapshot, messages);
        // 使用上游修复记录 ID 作为 taskId，保证 RAG 上下文任务级可追溯
        BugFixMessage ragMessage = ragBugFixEngine.findBugFixMessgaesForAgent(
                snapshot, logs, false, record.id()
        );

        recordRepository.updateStatus(
                record.id(),
                RepairRecordStatus.CONTEXT_READY,
                ragMessage.contextSummary()
        );
        recordRepository.addArtifact(new CreateRepairRecordArtifactCommand(
                record.id(),
                ARTIFACT_RAG_CONTEXT,
                "",
                ragMessage.contextSummary()
        ));

        if (writeBackEnabled) {
            replyToTicket(snapshot, message, ragMessage);
        }
        return new RepairOutcome(TicketRepairDecision.READY_FOR_RAG, record.id(), ragMessage.contextSummary());
    }

    private List<String> extractLogs(TicketSnapshot snapshot, TicketMessages messages) {
        String customLogs = fieldMapping.extractLogs(snapshot);
        List<String> result = new java.util.ArrayList<>();
        if (!customLogs.isBlank()) {
            result.add(customLogs);
        }
        messages.messages().stream()
                .filter(com.wish.rd.adapter.TicketMessage::isFromUser)
                .map(com.wish.rd.adapter.TicketMessage::content)
                .filter(content -> !content.isBlank())
                .forEach(result::add);
        return List.copyOf(result);
    }

    private void askForMissingInfo(TicketSnapshot snapshot, RepairTicketMessage message) {
        if (!writeBackEnabled || updatePort == null) {
            return;
        }
        TicketReplyCommand reply = new TicketReplyCommand(
                snapshot.ticketId(),
                "text",
                "为了启动自动修复，请补充：故障系统/模块、现象、相关日志或代码仓库链接。",
                List.of(),
                Map.of(),
                message.traceId(),
                ""
        );
        TicketUpdateResult result = updatePort.sendMessage(reply);
        log.info("asked user for missing info, ticketId={}, success={}", snapshot.ticketId(), result.success());
    }

    private void replyToTicket(TicketSnapshot snapshot, RepairTicketMessage message, BugFixMessage ragMessage) {
        if (updatePort == null) {
            return;
        }
        TicketReplyCommand reply = new TicketReplyCommand(
                snapshot.ticketId(),
                "text",
                ragMessage.answer(),
                List.of(),
                Map.of(),
                message.traceId(),
                ""
        );
        TicketUpdateResult result = updatePort.sendMessage(reply);
        log.info("wrote back RAG answer to ticket, ticketId={}, success={}", snapshot.ticketId(), result.success());
    }

    private RepairRecord ensureRecord(RepairTicketMessage message, String title, RepairRecordStatus status) {
        Optional<RepairRecord> existing = recordRepository.findByTicketId(message.ticketId());
        if (existing.isPresent()) {
            return recordRepository.updateStatus(existing.get().id(), status, existing.get().ragSummary());
        }
        Map<String, String> extension = buildExtension(message);
        RepairRecord created = recordRepository.create(new CreateRepairRecordCommand(
                message.ticketId(),
                "",
                title == null ? "" : title,
                extension
        ));
        return recordRepository.updateStatus(created.id(), status, created.ragSummary());
    }

    private void markFailed(String ticketId, RepairTicketMessage message, String reason) {
        try {
            Optional<RepairRecord> existing = recordRepository.findByTicketId(ticketId);
            RepairRecord record;
            if (existing.isPresent()) {
                record = existing.get();
            } else {
                record = recordRepository.create(new CreateRepairRecordCommand(
                        ticketId, "", "", buildExtension(message)
                ));
            }
            recordRepository.updateStatus(record.id(), RepairRecordStatus.FAILED, reason);
        } catch (Exception ignored) {
            // 持久化失败不影响主流程返回
            log.warn("failed to persist failed status, ticketId={}", ticketId);
        }
    }

    private void persistEventArtifact(RepairTicketMessage message, String repairRecordId) {
        String body = serializeSafe(Map.of(
                "eventId", message.eventId(),
                "eventType", message.eventType(),
                "source", message.source(),
                "traceId", message.traceId(),
                "attempt", Integer.toString(message.attempt()),
                "priority", message.priority()
        ));
        recordRepository.addArtifact(new CreateRepairRecordArtifactCommand(
                repairRecordId, ARTIFACT_FEISHU_EVENT, "", body
        ));
    }

    private void persistTicketSnapshotArtifact(TicketSnapshot snapshot, String repairRecordId) {
        Map<String, String> safe = new LinkedHashMap<>();
        safe.put("ticketId", snapshot.ticketId());
        safe.put("title", snapshot.title());
        safe.put("status", snapshot.status());
        safe.put("stage", snapshot.stage());
        safe.put("priority", snapshot.priority());
        safe.put("source", snapshot.source());
        recordRepository.addArtifact(new CreateRepairRecordArtifactCommand(
                repairRecordId, ARTIFACT_FEISHU_TICKET_SNAPSHOT, "", serializeSafe(safe)
        ));
    }

    private void persistMessagesArtifact(TicketMessages messages, String repairRecordId) {
        StringBuilder builder = new StringBuilder();
        for (com.wish.rd.adapter.TicketMessage msg : messages.messages()) {
            builder.append("[").append(msg.senderType()).append("] ")
                    .append(msg.content()).append("\n");
        }
        recordRepository.addArtifact(new CreateRepairRecordArtifactCommand(
                repairRecordId, ARTIFACT_FEISHU_MESSAGES, "", builder.toString().strip()
        ));
    }

    private Map<String, String> buildExtension(RepairTicketMessage message) {
        Map<String, String> extension = new LinkedHashMap<>();
        extension.put("priority", message.priority());
        extension.put("traceId", message.traceId());
        extension.put("eventId", message.eventId());
        extension.put("eventType", message.eventType());
        extension.put("source", message.source());
        return Map.copyOf(extension);
    }

    private String serializeSafe(Map<String, String> data) {
        // 简单 key:value 平铺，避免引入 JSON 序列化依赖；敏感字段已在调用方过滤
        StringBuilder builder = new StringBuilder();
        data.forEach((key, value) -> builder.append(key).append("=").append(value).append("\n"));
        return builder.toString().strip();
    }

    private String redact(String message) {
        if (message == null) {
            return "";
        }
        // 简单脱敏：截断过长错误信息，避免把可能的敏感内容写满数据库
        return message.length() <= 512 ? message : message.substring(0, 512);
    }

    /**
     * 处理结果。
     *
     * @param decision       决策
     * @param repairRecordId 修复记录 ID
     * @param contextSummary RAG 上下文摘要（无则空串）
     */
    public record RepairOutcome(
            TicketRepairDecision decision,
            String repairRecordId,
            String contextSummary
    ) {

        public RepairOutcome {
            repairRecordId = repairRecordId == null ? "" : repairRecordId;
            contextSummary = contextSummary == null ? "" : contextSummary;
        }
    }
}
